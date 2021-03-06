/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

/*
 * User: anna
 * Date: 28-Nov-2008
 */
package org.jetbrains.idea.eclipse;

import com.intellij.openapi.application.PluginPathManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModel;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.testFramework.IdeaTestCase;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.eclipse.config.EclipseClasspathStorageProvider;
import org.jetbrains.idea.eclipse.conversion.EclipseClasspathReader;
import org.jetbrains.idea.eclipse.conversion.EclipseClasspathWriter;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public class EclipseClasspathTest extends IdeaTestCase {
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    final File testRoot = new File(PluginPathManager.getPluginHomePath("eclipse") + "/testData", "round");
    assertTrue(testRoot.getAbsolutePath(), testRoot.isDirectory());

    final File currentTestRoot = new File(testRoot, getTestName(true));
    assertTrue(currentTestRoot.getAbsolutePath(), currentTestRoot.isDirectory());

    FileUtil.copyDir(currentTestRoot, new File(getProject().getBaseDir().getPath()));
  }

  private void doTest() throws Exception {
    doTest("/test", getProject());
  }

  protected static void doTest(final String relativePath, final Project project) throws Exception {
    final String path = project.getBaseDir().getPath() + relativePath;
    checkModule(path, setUpModule(path, project));
  }

  static Module setUpModule(final String path, @NotNull final Project project) throws Exception {
    final File classpathFile = new File(path, EclipseXml.DOT_CLASSPATH_EXT);
    String fileText = FileUtil.loadFile(classpathFile).replaceAll("\\$ROOT\\$", project.getBaseDir().getPath());
    if (!SystemInfo.isWindows) {
      fileText = fileText.replaceAll(EclipseXml.FILE_PROTOCOL + "/", EclipseXml.FILE_PROTOCOL);
    }
    final Element classpathElement = JDOMUtil.loadDocument(fileText).getRootElement();

    final Module module = WriteCommandAction.runWriteCommandAction(null, new Computable<Module>() {
      @Override
      public Module compute() {
        String imlPath = path + "/" + EclipseProjectFinder.findProjectName(path) + IdeaXml.IML_EXT;
        return ModuleManager.getInstance(project).newModule(imlPath, StdModuleTypes.JAVA.getId());
      }
    });

    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        try {
          final Set<String> sink = ContainerUtil.newHashSet();
          final EclipseClasspathReader classpathReader = new EclipseClasspathReader(path, project, null);
          classpathReader.init(model);
          classpathReader.readClasspath(model, sink, sink, sink, sink, null, classpathElement);
          new EclipseClasspathStorageProvider().assertCompatible(model);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    });
    return module;
  }

  static void checkModule(String path, Module module) throws IOException, JDOMException, ConversionException {
    final File classpathFile1 = new File(path, EclipseXml.DOT_CLASSPATH_EXT);
    if (!classpathFile1.exists()) return;
    String fileText1 = FileUtil.loadFile(classpathFile1).replaceAll("\\$ROOT\\$", module.getProject().getBaseDir().getPath());
    if (!SystemInfo.isWindows) {
      fileText1 = fileText1.replaceAll(EclipseXml.FILE_PROTOCOL + "/", EclipseXml.FILE_PROTOCOL);
    }
    final Element classpathElement1 = JDOMUtil.loadDocument(fileText1).getRootElement();
    final ModuleRootModel model = ModuleRootManager.getInstance(module);
    final Element resultClasspathElement = new Element(EclipseXml.CLASSPATH_TAG);
    new EclipseClasspathWriter(model).writeClasspath(resultClasspathElement, classpathElement1);

    String resulted = new String(JDOMUtil.printDocument(new Document(resultClasspathElement), "\n"));
    assertTrue(resulted.replaceAll(StringUtil.escapeToRegexp(module.getProject().getBaseDir().getPath()), "\\$ROOT\\$"),
               JDOMUtil.areElementsEqual(classpathElement1, resultClasspathElement));
  }


  public void testAbsolutePaths() throws Exception {
    doTest("/parent/parent/test", getProject());
  }


  public void testWorkspaceOnly() throws Exception {
    doTest();
  }

  public void testExportedLibs() throws Exception {
    doTest();
  }

  public void testPathVariables() throws Exception {
    doTest();
  }

  public void testJunit() throws Exception {
    doTest();
  }

  public void testSrcBinJRE() throws Exception {
    doTest();
  }

  public void testSrcBinJRESpecific() throws Exception {
    doTest();
  }

  public void testAccessrulez() throws Exception {
    doTest();
  }

  public void testSrcBinJREProject() throws Exception {
    doTest();
  }

  public void testSourceFolderOutput() throws Exception {
    doTest();
  }

  public void testMultipleSourceFolders() throws Exception {
    doTest();
  }

  public void testEmptySrc() throws Exception {
    doTest();
  }

  public void testHttpJavadoc() throws Exception {
    doTest();
  }

  public void testHome() throws Exception {
    doTest();
  }

  //public void testNoJava() throws Exception {
  //  doTest();
  //}

  public void testNoSource() throws Exception {
    doTest();
  }

  public void testPlugin() throws Exception {
    doTest();
  }

  public void testRoot() throws Exception {
    doTest();
  }

  public void testUnknownCon() throws Exception {
    doTest();
  }

  public void testSourcesAfterAll() throws Exception {
    doTest();
  }

  public void testLinkedSrc() throws Exception {
    doTest();
  }

  public void testSrcRootsOrder() throws Exception {
    doTest();
  }
}
