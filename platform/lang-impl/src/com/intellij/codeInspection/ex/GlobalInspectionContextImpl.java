/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInspection.ex;

import com.intellij.analysis.AnalysisScope;
import com.intellij.analysis.AnalysisUIOptions;
import com.intellij.analysis.PerformAnalysisInBackgroundOption;
import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoProcessor;
import com.intellij.codeInsight.daemon.impl.LocalInspectionsPass;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.lang.GlobalInspectionContextExtension;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefManagerImpl;
import com.intellij.codeInspection.reference.RefVisitor;
import com.intellij.codeInspection.ui.DefaultInspectionToolPresentation;
import com.intellij.codeInspection.ui.InspectionResultsView;
import com.intellij.codeInspection.ui.InspectionToolPresentation;
import com.intellij.concurrency.JobLauncher;
import com.intellij.concurrency.JobSchedulerImpl;
import com.intellij.lang.annotation.ProblemGroup;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.notification.NotificationGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtilCore;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.content.*;
import com.intellij.util.*;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashMap;
import com.intellij.util.ui.UIUtil;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;

public class GlobalInspectionContextImpl extends GlobalInspectionContextBase implements GlobalInspectionContext {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.ex.GlobalInspectionContextImpl");
  private static final NotificationGroup NOTIFICATION_GROUP = NotificationGroup.toolWindowGroup("Inspection Results", ToolWindowId.INSPECTION);
  private final NotNullLazyValue<ContentManager> myContentManager;
  private InspectionResultsView myView = null;
  private Content myContent = null;

  private AnalysisUIOptions myUIOptions;

  public GlobalInspectionContextImpl(@NotNull Project project, @NotNull NotNullLazyValue<ContentManager> contentManager) {
    super(project);

    myUIOptions = AnalysisUIOptions.getInstance(project).copy();
    myContentManager = contentManager;
  }

  public ContentManager getContentManager() {
    return myContentManager.getValue();
  }

  public synchronized void addView(@NotNull InspectionResultsView view, String title) {
    if (myContent != null) return;
    myContentManager.getValue().addContentManagerListener(new ContentManagerAdapter() {
      @Override
      public void contentRemoved(ContentManagerEvent event) {
        if (event.getContent() == myContent) {
          if (myView != null) {
            close(false);
          }
          myContent = null;
        }
      }
    });

    myView = view;
    myContent = ContentFactory.SERVICE.getInstance().createContent(view, title, false);

    myContent.setDisposer(myView);

    ContentManager contentManager = getContentManager();
    contentManager.addContent(myContent);
    contentManager.setSelectedContent(myContent);

    ToolWindowManager.getInstance(getProject()).getToolWindow(ToolWindowId.INSPECTION).activate(null);
  }

  public void addView(@NotNull InspectionResultsView view) {
    addView(view, view.getCurrentProfileName() == null
                  ? InspectionsBundle.message("inspection.results.title")
                  : InspectionsBundle.message("inspection.results.for.profile.toolwindow.title", view.getCurrentProfileName()));

  }

  @Override
  public void doInspections(@NotNull final AnalysisScope scope) {
    if (myContent != null) {
      getContentManager().removeContent(myContent, true);
    }
    super.doInspections(scope);
  }

  public void launchInspectionsOffline(@NotNull final AnalysisScope scope,
                                       @Nullable final String outputPath,
                                       final boolean runGlobalToolsOnly,
                                       @NotNull final List<File> inspectionsResults) {
    cleanupTools();
    setCurrentScope(scope);

    DefaultInspectionToolPresentation.setOutputPath(outputPath);
    try {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          performInspectionsWithProgress(scope, runGlobalToolsOnly);
          @NonNls final String ext = ".xml";
          final Map<Element, Tools> globalTools = new HashMap<Element, Tools>();
          for (Map.Entry<String,Tools> stringSetEntry : myTools.entrySet()) {
            final Tools sameTools = stringSetEntry.getValue();
            boolean hasProblems = false;
            String toolName = stringSetEntry.getKey();
            if (sameTools != null) {
              for (ScopeToolState toolDescr : sameTools.getTools()) {
                InspectionToolWrapper toolWrapper = toolDescr.getTool();
                if (toolWrapper instanceof LocalInspectionToolWrapper) {
                  hasProblems = new File(outputPath, toolName + ext).exists();
                }
                else {
                  InspectionToolPresentation presentation = getPresentation(toolWrapper);
                  presentation.updateContent();
                  if (presentation.hasReportedProblems()) {
                    final Element root = new Element(InspectionsBundle.message("inspection.problems"));
                    globalTools.put(root, sameTools);
                    LOG.assertTrue(!hasProblems, toolName);
                    break;
                  }
                }
              }
            }
            if (!hasProblems) continue;
            try {
              new File(outputPath).mkdirs();
              final File file = new File(outputPath, toolName + ext);
              inspectionsResults.add(file);
              FileUtil.writeToFile(file, ("</" + InspectionsBundle.message("inspection.problems") + ">").getBytes(CharsetToolkit.UTF8_CHARSET), true);
            }
            catch (IOException e) {
              LOG.error(e);
            }
          }

          getRefManager().iterate(new RefVisitor() {
            @Override
            public void visitElement(@NotNull final RefEntity refEntity) {
              for (Element element : globalTools.keySet()) {
                final Tools tools = globalTools.get(element);
                for (ScopeToolState state : tools.getTools()) {
                  try {
                    InspectionToolWrapper toolWrapper = state.getTool();
                    InspectionToolPresentation presentation = getPresentation(toolWrapper);
                    presentation.exportResults(element, refEntity);
                  }
                  catch (Throwable e) {
                    LOG.error("Problem when exporting: " + refEntity.getExternalName(), e);
                  }
                }
              }
            }
          });

          for (Element element : globalTools.keySet()) {
            final String toolName = globalTools.get(element).getShortName();
            element.setAttribute(LOCAL_TOOL_ATTRIBUTE, Boolean.toString(false));
            final org.jdom.Document doc = new org.jdom.Document(element);
            PathMacroManager.getInstance(getProject()).collapsePaths(doc.getRootElement());
            try {
              new File(outputPath).mkdirs();
              final File file = new File(outputPath, toolName + ext);
              inspectionsResults.add(file);

              OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), CharsetToolkit.UTF8_CHARSET);
              try {
                JDOMUtil.writeDocument(doc, writer, "\n");
              }
              finally {
                writer.close();
              }
            }
            catch (IOException e) {
              LOG.error(e);
            }
          }
        }
      });
    }
    finally {
      DefaultInspectionToolPresentation.setOutputPath(null);
    }
  }

  public void ignoreElement(@NotNull InspectionProfileEntry tool, final PsiElement element) {
    final RefElement refElement = getRefManager().getReference(element);
    final Tools tools = myTools.get(tool.getShortName());
    if (tools != null){
      for (ScopeToolState state : tools.getTools()) {
        InspectionToolWrapper toolWrapper = state.getTool();
        ignoreElementRecursively(toolWrapper, refElement);
      }
    }
  }

  public InspectionResultsView getView() {
    return myView;
  }

  private void ignoreElementRecursively(@NotNull InspectionToolWrapper toolWrapper, final RefEntity refElement) {
    if (refElement != null) {
      InspectionToolPresentation presentation = getPresentation(toolWrapper);
      presentation.ignoreCurrentElement(refElement);
      final List<RefEntity> children = refElement.getChildren();
      if (children != null) {
        for (RefEntity child : children) {
          ignoreElementRecursively(toolWrapper, child);
        }
      }
    }
  }

  public AnalysisUIOptions getUIOptions() {
    return myUIOptions;
  }

  public void setSplitterProportion(final float proportion) {
    myUIOptions.SPLITTER_PROPORTION = proportion;
  }

  public ToggleAction createToggleAutoscrollAction() {
    return myUIOptions.getAutoScrollToSourceHandler().createToggleAction();
  }

  @Override
  protected void launchInspections(@NotNull final AnalysisScope scope) {
    myUIOptions = AnalysisUIOptions.getInstance(getProject()).copy();
    myView = new InspectionResultsView(getProject(), getCurrentProfile(), scope, this, new InspectionRVContentProviderImpl(getProject()));
    super.launchInspections(scope);
  }

  @Override
  protected PerformInBackgroundOption createOption() {
    return new PerformAnalysisInBackgroundOption(getProject());
  }

  @Override
  protected void notifyInspectionsFinished() {
    if (ApplicationManager.getApplication().isUnitTestMode()) return;
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        LOG.info("Code inspection finished");

        if (myView != null) {
          if (!myView.update() && !getUIOptions().SHOW_ONLY_DIFF) {
            NOTIFICATION_GROUP.createNotification(InspectionsBundle.message("inspection.no.problems.message"), MessageType.INFO).notify(getProject());
            close(true);
          }
          else {
            addView(myView);
          }
        }
      }
    });
  }

  @Override
  protected void runTools(@NotNull final AnalysisScope scope, boolean runGlobalToolsOnly) {
    if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
      throw new IncorrectOperationException("Must not start inspections from within write action");
    }
    final InspectionManager inspectionManager = InspectionManager.getInstance(getProject());
    final List<Tools> globalTools = new ArrayList<Tools>();
    final List<Tools> localTools = new ArrayList<Tools>();
    final List<Tools> globalSimpleTools = new ArrayList<Tools>();
    initializeTools(globalTools, localTools, globalSimpleTools);
    appendPairedInspectionsForUnfairTools(globalTools, globalSimpleTools, localTools);

    ((RefManagerImpl)getRefManager()).initializeAnnotators();
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      @Override
      public void run() {
        runGlobalTools(scope, inspectionManager, globalTools);
      }
    });

    if (runGlobalToolsOnly) return;

    final Set<VirtualFile> localScopeFiles = scope.toSearchScope() instanceof LocalSearchScope ? new THashSet<VirtualFile>() : null;
    for (Tools tools : globalSimpleTools) {
      GlobalInspectionToolWrapper toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
      GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
      tool.inspectionStarted(inspectionManager, this, getPresentation(toolWrapper));
    }

    final boolean headlessEnvironment = ApplicationManager.getApplication().isHeadlessEnvironment();
    final Map<String, InspectionToolWrapper> map = getInspectionWrappersMap(localTools);

    final BlockingQueue<List<PsiFile>> chunksToInspect = new ArrayBlockingQueue<List<PsiFile>>(10);
    startIterateScopeIntoChunks(scope, localScopeFiles, headlessEnvironment, chunksToInspect);
    try {
      for (List<PsiFile> chunk = chunksToInspect.take(); !chunk.isEmpty(); chunk = chunksToInspect.take()) {
        JobLauncher.getInstance().invokeConcurrentlyUnderProgress(chunk, myProgressIndicator, true, false, new Processor<PsiFile>() {
          @Override
          public boolean process(final PsiFile file) {
            return inspectFile(file, inspectionManager, localTools, globalSimpleTools, map);
          }
        });
      }
    }
    catch (InterruptedException ignored) {
    }
    for (Tools tools : globalSimpleTools) {
      GlobalInspectionToolWrapper toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
      GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
      ProblemDescriptionsProcessor problemDescriptionProcessor = getProblemDescriptionProcessor(toolWrapper, map);
      tool.inspectionFinished(inspectionManager, this, problemDescriptionProcessor);
    }
  }

  private boolean inspectFile(@NotNull final PsiFile file,
                              @NotNull final InspectionManager inspectionManager,
                              @NotNull List<Tools> localTools,
                              @NotNull List<Tools> globalSimpleTools,
                              @NotNull final Map<String, InspectionToolWrapper> wrappersMap) {
    Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
    if (document == null) return true;
    final LocalInspectionsPass pass = new LocalInspectionsPass(file, document, 0,
                                                               file.getTextLength(), LocalInspectionsPass.EMPTY_PRIORITY_RANGE, true,
                                                               HighlightInfoProcessor.getEmpty());
    try {
      final List<LocalInspectionToolWrapper> lTools = getWrappersFromTools(localTools, file);
      pass.doInspectInBatch(this, inspectionManager, lTools);

      final List<GlobalInspectionToolWrapper> tools = getWrappersFromTools(globalSimpleTools, file);
      JobLauncher.getInstance().invokeConcurrentlyUnderProgress(tools, myProgressIndicator, false, new Processor<GlobalInspectionToolWrapper>() {
        @Override
        public boolean process(GlobalInspectionToolWrapper toolWrapper) {
            GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
            ProblemsHolder problemsHolder = new ProblemsHolder(inspectionManager, file, false);
            ProblemDescriptionsProcessor problemDescriptionProcessor = getProblemDescriptionProcessor(toolWrapper, wrappersMap);
            tool.checkFile(file, inspectionManager, problemsHolder, GlobalInspectionContextImpl.this, problemDescriptionProcessor);
            InspectionToolPresentation toolPresentation = getPresentation(toolWrapper);
            LocalDescriptorsUtil.addProblemDescriptors(problemsHolder.getResults(), false, GlobalInspectionContextImpl.this, null,
                                                       CONVERT, toolPresentation);
          return true;
        }
      });
    }
    catch (ProcessCanceledException e) {
      final Throwable cause = e.getCause();
      if (cause == null) {
        throw e;
      }
      LOG.error("In file: " + file, cause);
    }
    catch (IndexNotReadyException e) {
      throw e;
    }
    catch (Throwable e) {
      LOG.error("In file: " + file, e);
    }
    finally {
      InjectedLanguageManager.getInstance(getProject()).dropFileCaches(file);
    }
    return true;
  }

  private void startIterateScopeIntoChunks(@NotNull final AnalysisScope scope,
                                           final Set<VirtualFile> localScopeFiles,
                                           final boolean headlessEnvironment,
                                           @NotNull final BlockingQueue<List<PsiFile>> chunksToInspect) {
    ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
      @Override
      public void run() {
        final List<PsiFile> chunk = new ArrayList<PsiFile>();
        try {
          scope.accept(new Processor<VirtualFile>() {
            @Override
            public boolean process(final VirtualFile file) {
              Document document = ApplicationManager.getApplication().runReadAction(new Computable<Document>() {
                @Override
                public Document compute() {
                  PsiFile psiFile = PsiManager.getInstance(getProject()).findFile(file);
                  Document document = psiFile == null ? null : shouldProcess(psiFile, headlessEnvironment, localScopeFiles);
                  if (document != null) {
                    chunk.add(psiFile);
                  }
                  return document;
                }
              });
              //do not inspect binary files
              if (document != null) {
                document.getText(); // preload text

                if (chunk.size() >= JobSchedulerImpl.CORES_COUNT) {
                  try {
                    LOG.assertTrue(!ApplicationManager.getApplication().isReadAccessAllowed());
                    chunksToInspect.put(new ArrayList<PsiFile>(chunk));
                  }
                  catch (InterruptedException e) {
                    LOG.error(e);
                  }
                  chunk.clear();
                }
              }
              return true;
            }
          });
          if (!chunk.isEmpty()) {
            LOG.assertTrue(!ApplicationManager.getApplication().isReadAccessAllowed());
            chunksToInspect.put(new ArrayList<PsiFile>(chunk));
            chunk.clear();
          }
        }
        catch (InterruptedException e) {
          LOG.error(e);
        }
        finally {
          try {
            chunksToInspect.put(Collections.<PsiFile>emptyList()); // tombstone
          }
          catch (InterruptedException e) {
            LOG.error(e);
          }
        }
      }
    });
  }

  private Document shouldProcess(@NotNull PsiFile file, boolean headlessEnvironment, Set<VirtualFile> localScopeFiles) {
    final VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return null;
    if (isBinary(file)) return null; //do not inspect binary files

    if (myView == null && !headlessEnvironment) {
      throw new ProcessCanceledException();
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Running local inspections on " + virtualFile.getPath());
    }

    String url = ProjectUtilCore.displayUrlRelativeToProject(virtualFile, virtualFile.getPresentableUrl(), getProject(), true, false);
    incrementJobDoneAmount(getStdJobDescriptors().LOCAL_ANALYSIS, url);
    if (SingleRootFileViewProvider.isTooLargeForIntelligence(virtualFile)) return null;
    if (localScopeFiles != null && !localScopeFiles.add(virtualFile)) return null;

    return PsiDocumentManager.getInstance(getProject()).getDocument(file);
  }

  private void runGlobalTools(@NotNull AnalysisScope scope, @NotNull InspectionManager inspectionManager, @NotNull List<Tools> globalTools) {
    final List<InspectionToolWrapper> needRepeatSearchRequest = new ArrayList<InspectionToolWrapper>();

    final boolean surelyNoExternalUsages = scope.getScopeType() == AnalysisScope.PROJECT;
    for (Tools tools : globalTools) {
      for (ScopeToolState state : tools.getTools()) {
        InspectionToolWrapper toolWrapper = state.getTool();
        GlobalInspectionTool tool = (GlobalInspectionTool)toolWrapper.getTool();
        InspectionToolPresentation toolPresentation = getPresentation(toolWrapper);
        try {
          if (tool.isGraphNeeded()) {
            try {
              ((RefManagerImpl)getRefManager()).findAllDeclarations();
            }
            catch (Throwable e) {
              getStdJobDescriptors().BUILD_GRAPH.setDoneAmount(0);
              throw e;
            }
          }
          tool.runInspection(scope, inspectionManager, this, toolPresentation);
          //skip phase when we are sure that scope already contains everything
          if (!surelyNoExternalUsages && tool.queryExternalUsagesRequests(inspectionManager, this, toolPresentation)) {
            needRepeatSearchRequest.add(toolWrapper);
          }
        }
        catch (ProcessCanceledException e) {
          throw e;
        }
        catch (IndexNotReadyException e) {
          throw e;
        }
        catch (Throwable e) {
          LOG.error(e);
        }
      }
    }
    for (GlobalInspectionContextExtension extension : myExtensions.values()) {
      try {
        extension.performPostRunActivities(needRepeatSearchRequest, this);
      }
      catch (ProcessCanceledException e) {
        throw e;
      }
      catch (IndexNotReadyException e) {
        throw e;
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }
  }

  private void appendPairedInspectionsForUnfairTools(@NotNull List<Tools> globalTools,
                                                     @NotNull List<Tools> globalSimpleTools,
                                                     @NotNull List<Tools> localTools) {
    Tools[] larray = localTools.toArray(new Tools[localTools.size()]);
    for (Tools tool : larray) {
      LocalInspectionToolWrapper toolWrapper = (LocalInspectionToolWrapper)tool.getTool();
      LocalInspectionTool localTool = toolWrapper.getTool();
      if (localTool instanceof PairedUnfairLocalInspectionTool) {
        String batchShortName = ((PairedUnfairLocalInspectionTool)localTool).getInspectionForBatchShortName();
        InspectionProfile currentProfile = getCurrentProfile();
        InspectionToolWrapper batchInspection = currentProfile == null ? null : currentProfile.getInspectionTool(batchShortName, getProject());
        if (batchInspection != null && !myTools.containsKey(batchShortName)) {
          // add to existing inspections to run
          InspectionProfileEntry batchTool = batchInspection.getTool();
          Tools newTool = new ToolsImpl(batchInspection, batchInspection.getDefaultLevel(), true);
          if (batchTool instanceof LocalInspectionTool) localTools.add(newTool);
          else if (batchTool instanceof GlobalSimpleInspectionTool) globalSimpleTools.add(newTool);
          else if (batchTool instanceof GlobalInspectionTool) globalTools.add(newTool);
          else throw new AssertionError(batchTool);
          myTools.put(batchShortName, newTool);
          batchInspection.initialize(this);
        }
      }
    }
  }

  private static <T extends InspectionToolWrapper> List<T> getWrappersFromTools(List<Tools> localTools, PsiFile file) {
    final List<T> lTools = new ArrayList<T>();
    for (Tools tool : localTools) {
      final T enabledTool = (T)tool.getEnabledTool(file);
      if (enabledTool != null) {
        lTools.add(enabledTool);
      }
    }
    return lTools;
  }

  @NotNull
  private ProblemDescriptionsProcessor getProblemDescriptionProcessor(@NotNull final GlobalInspectionToolWrapper toolWrapper,
                                                                      @NotNull final Map<String, InspectionToolWrapper> wrappersMap) {
    return new ProblemDescriptionsProcessor() {
      @Nullable
      @Override
      public CommonProblemDescriptor[] getDescriptions(@NotNull RefEntity refEntity) {
        return new CommonProblemDescriptor[0];
      }

      @Override
      public void ignoreElement(@NotNull RefEntity refEntity) {

      }

      @Override
      public void addProblemElement(@Nullable RefEntity refEntity, @NotNull CommonProblemDescriptor... commonProblemDescriptors) {
        for (CommonProblemDescriptor problemDescriptor : commonProblemDescriptors) {
          if (!(problemDescriptor instanceof ProblemDescriptor)) {
            continue;
          }
          ProblemGroup problemGroup = ((ProblemDescriptor)problemDescriptor).getProblemGroup();

          InspectionToolWrapper targetWrapper = problemGroup == null ? toolWrapper : wrappersMap.get(problemGroup.getProblemName());
          if (targetWrapper != null) { // Else it's switched off
            InspectionToolPresentation toolPresentation = getPresentation(targetWrapper);
            toolPresentation.addProblemElement(refEntity, problemDescriptor);
          }
        }
      }

      @Override
      public RefEntity getElement(@NotNull CommonProblemDescriptor descriptor) {
        return null;
      }
    };
  }

  @NotNull
  private static Map<String, InspectionToolWrapper> getInspectionWrappersMap(@NotNull List<Tools> tools) {
    Map<String, InspectionToolWrapper> name2Inspection = new HashMap<String, InspectionToolWrapper>(tools.size());
    for (Tools tool : tools) {
      InspectionToolWrapper toolWrapper = tool.getTool();
      name2Inspection.put(toolWrapper.getShortName(), toolWrapper);
    }

    return name2Inspection;
  }

  private static final TripleFunction<LocalInspectionTool,PsiElement,GlobalInspectionContext,RefElement> CONVERT =
    new TripleFunction<LocalInspectionTool, PsiElement, GlobalInspectionContext, RefElement>() {
      @Override
      public RefElement fun(LocalInspectionTool tool,
                            PsiElement elt,
                            GlobalInspectionContext context) {
        final PsiNamedElement problemElement = PsiTreeUtil.getNonStrictParentOfType(elt, PsiFile.class);

        RefElement refElement = context.getRefManager().getReference(problemElement);
        if (refElement == null && problemElement != null) {  // no need to lose collected results
          refElement = GlobalInspectionContextUtil.retrieveRefElement(elt, context);
        }
        return refElement;
      }
    };


  @Override
  public void close(boolean noSuspisiousCodeFound) {
    if (!noSuspisiousCodeFound && (myView == null || myView.isRerun())) return;
    AnalysisUIOptions.getInstance(getProject()).save(myUIOptions);
    if (myContent != null) {
      final ContentManager contentManager = getContentManager();
      if (contentManager != null) {  //null for tests
        contentManager.removeContent(myContent, true);
      }
    }
    myView = null;
    super.close(noSuspisiousCodeFound);
  }

  @Override
  public void cleanup() {
    ((InspectionManagerEx)InspectionManager.getInstance(getProject())).closeRunningContext(this);
    for (Tools tools : myTools.values()) {
      for (ScopeToolState state : tools.getTools()) {
        InspectionToolWrapper toolWrapper = state.getTool();
        getPresentation(toolWrapper).finalCleanup();
      }
    }
    super.cleanup();
  }

  public void refreshViews() {
    if (myView != null) {
      myView.updateView(false);
    }
  }

  private final ConcurrentMap<InspectionToolWrapper, InspectionToolPresentation> myPresentationMap = ContainerUtil.newConcurrentMap();
  @NotNull
  public InspectionToolPresentation getPresentation(@NotNull InspectionToolWrapper toolWrapper) {
    InspectionToolPresentation presentation = myPresentationMap.get(toolWrapper);
    if (presentation == null) {
      String presentationClass = StringUtil.notNullize(toolWrapper.myEP == null ? null : toolWrapper.myEP.presentation, DefaultInspectionToolPresentation.class.getName());

      try {
        Constructor<?> constructor = Class.forName(presentationClass).getConstructor(InspectionToolWrapper.class, GlobalInspectionContextImpl.class);
        presentation = (InspectionToolPresentation)constructor.newInstance(toolWrapper, this);
      }
      catch (Exception e) {
        LOG.error(e);
        throw new RuntimeException(e);
      }
      presentation = ConcurrencyUtil.cacheOrGet(myPresentationMap, toolWrapper, presentation);
    }
    return presentation;
  }

  @Override
  public void codeCleanup(final Project project,
                          final AnalysisScope scope,
                          final InspectionProfile profile,
                          final String commandName,
                          final Runnable postRunnable, 
                          final boolean modal) {
    Task task;
    if (modal) {
      task = new Task.Modal(project, "Inspect code...", true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          cleanup(scope, profile, project, postRunnable, commandName);
        }
      };
    } else {
      task = new Task.Backgroundable(project, "Inspect code...", true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          cleanup(scope, profile, project, postRunnable, commandName);
        }
      }; 
    }
    ProgressManager.getInstance().run(task);
  }

  private void cleanup(final AnalysisScope scope,
                       final InspectionProfile profile,
                       final Project project,
                       final Runnable postRunnable,
                       final String commandName) {
    final int fileCount = scope.getFileCount();
    final ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
    final List<LocalInspectionToolWrapper> lTools = new ArrayList<LocalInspectionToolWrapper>();

    final LinkedHashMap<PsiFile, List<HighlightInfo>> results = new LinkedHashMap<PsiFile, List<HighlightInfo>>();

    final SearchScope searchScope = scope.toSearchScope();
    final TextRange range;
    if (searchScope instanceof LocalSearchScope) {
      final PsiElement[] elements = ((LocalSearchScope)searchScope).getScope();
      range = elements.length == 1 ? elements[0].getTextRange() : null;
    }
    else {
      range = null;
    }
    final Iterable<Tools> inspectionTools = ContainerUtil.filter(profile.getAllEnabledInspectionTools(project), new Condition<Tools>() {
      @Override
      public boolean value(Tools tools) {
        assert tools != null;
        return tools.getTool().getTool() instanceof CleanupLocalInspectionTool;
      }
    });
    scope.accept(new PsiElementVisitor() {
      private int myCount = 0;
      @Override
      public void visitFile(PsiFile file) {
        if (progressIndicator != null) {
          progressIndicator.setFraction(((double)++ myCount)/fileCount);
        }
        if (isBinary(file)) return;
        for (final Tools tools : inspectionTools) {
          final InspectionToolWrapper tool = tools.getEnabledTool(file);
          if (tool instanceof LocalInspectionToolWrapper) {
            lTools.add((LocalInspectionToolWrapper)tool);
            tool.initialize(GlobalInspectionContextImpl.this);
          }
        }

        if (!lTools.isEmpty()) {
          final LocalInspectionsPass pass = new LocalInspectionsPass(file, PsiDocumentManager.getInstance(project).getDocument(file), range != null ? range.getStartOffset() : 0,
                                                                     range != null ? range.getEndOffset() : file.getTextLength(), LocalInspectionsPass.EMPTY_PRIORITY_RANGE, true,
                                                                     HighlightInfoProcessor.getEmpty());
          Runnable runnable = new Runnable() {
            @Override
            public void run() {
              pass.doInspectInBatch(GlobalInspectionContextImpl.this, InspectionManager.getInstance(project), lTools);
            }
          };
          ApplicationManager.getApplication().runReadAction(runnable);
          final List<HighlightInfo> infos = pass.getInfos();
          if (searchScope instanceof LocalSearchScope) {
            for (Iterator<HighlightInfo> iterator = infos.iterator(); iterator.hasNext(); ) {
              final HighlightInfo info = iterator.next();
              final TextRange infoRange = new TextRange(info.getStartOffset(), info.getEndOffset());
              if (!((LocalSearchScope)searchScope).containsRange(file, infoRange)) {
                iterator.remove();
              }
            }
          }
          if (!infos.isEmpty()) {
            results.put(file, infos);
          }
        }
      }
    });

    if (results.isEmpty()) {
      UIUtil.invokeLaterIfNeeded(new Runnable() {
        @Override
        public void run() {
          if (commandName != null) {
            NOTIFICATION_GROUP.createNotification(InspectionsBundle.message("inspection.no.problems.message"), MessageType.INFO).notify(getProject());
          }
          if (postRunnable != null) {
            postRunnable.run();
          }
        }
      });
      return;
    }
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        if (!FileModificationService.getInstance().preparePsiElementsForWrite(results.keySet())) return;

        final SequentialModalProgressTask progressTask = new SequentialModalProgressTask(project, "Code Cleanup", true);
        progressTask.setMinIterationTime(200);
        progressTask.setTask(new SequentialCleanupTask(project, results, progressTask));
        CommandProcessor.getInstance().executeCommand(project, new Runnable() {
          @Override
          public void run() {
            if (commandName != null) {
              CommandProcessor.getInstance().markCurrentCommandAsGlobal(project);
            }
            ApplicationManager.getApplication().runWriteAction(new Runnable() {
              @Override
              public void run() {
                ProgressManager.getInstance().run(progressTask);
              }
            });
            if (postRunnable != null) {
              ApplicationManager.getApplication().invokeLater(postRunnable);
            }
          }
        }, commandName, null);
      }
    };
    if (ApplicationManager.getApplication().isDispatchThread()) {
      runnable.run();
    } else {
      ApplicationManager.getApplication().invokeLater(runnable);
    }
  }

  private static boolean isBinary(PsiFile file) {
    return file instanceof PsiBinaryFile || file.getFileType().isBinary();
  }
}
