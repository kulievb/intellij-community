/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.psi.search;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestCase;
import com.intellij.testFramework.TempFiles;

import java.io.IOException;

public class GlobalSearchScopeTest extends PlatformTestCase {
  public void testUniteDirectorySearchScopeDoesNotSOE() throws Exception {
    VirtualFile genRoot = getVirtualFile(createTempDir("genSrcRoot"));
    VirtualFile srcRoot = getVirtualFile(createTempDir("srcRoot"));
    VirtualFile child = srcRoot.createChildDirectory(this, "child");
    GlobalSearchScope childScope = GlobalSearchScopesCore.directoryScope(getProject(), child, true);

    GlobalSearchScope directoryScope = GlobalSearchScopesCore.directoryScope(getProject(), srcRoot, true);
    GlobalSearchScope scope = GlobalSearchScope.EMPTY_SCOPE.uniteWith(directoryScope);
    assertSame(scope, directoryScope);
    scope = scope.uniteWith(directoryScope);
    assertSame(scope, directoryScope);

    scope = scope.uniteWith(childScope);
    assertSame(scope, directoryScope);

    GlobalSearchScope s = childScope;
    int N = 1000;
    VirtualFile[] d = new VirtualFile[N];
    for (int i=0; i< N;i++) {
      d[i] = srcRoot.createChildDirectory(this, "d"+i);
      GlobalSearchScope united = s.uniteWith(GlobalSearchScopesCore.directoryScope(getProject(), d[i], true));
      assertNotSame(s, united);
      s = united;
      assertTrue(s instanceof GlobalSearchScopesCore.DirectoriesScope);
    }
    for (VirtualFile file : d) {
      VirtualFile f = createChildData(file, "f");
      assertTrue(s.contains(f));
    }
    assertFalse(s.contains(genRoot));

    assertSame(s.uniteWith(childScope), s);
    assertSame(s.uniteWith(s), s);
  }

  @Override
  protected Module createMainModule() throws IOException {
    return super.createMainModule();
  }

  public void testNotScope() throws Exception {
    VirtualFile moduleRoot = new TempFiles(myFilesToDelete).createTempVDir();
    ModuleRootModificationUtil.addContentRoot(getModule(), moduleRoot.getPath());

    GlobalSearchScope projectScope = GlobalSearchScope.projectScope(getProject());
    assertFalse(projectScope.isSearchInLibraries());
    assertTrue(projectScope.isSearchInModuleContent(getModule()));
    assertTrue(projectScope.contains(moduleRoot));

    GlobalSearchScope notProjectScope = GlobalSearchScope.notScope(projectScope);
    assertTrue(notProjectScope.isSearchInLibraries());
    assertFalse(notProjectScope.isSearchInModuleContent(getModule()));
    assertFalse(notProjectScope.contains(moduleRoot));

    GlobalSearchScope allScope = GlobalSearchScope.allScope(getProject());
    assertTrue(allScope.isSearchInLibraries());
    assertTrue(allScope.isSearchInModuleContent(getModule()));
    assertTrue(allScope.contains(moduleRoot));

    GlobalSearchScope notAllScope = GlobalSearchScope.notScope(allScope);
    assertFalse(notAllScope.isSearchInLibraries());
    assertFalse(notAllScope.isSearchInModuleContent(getModule()));
    assertFalse(notAllScope.contains(moduleRoot));
  }
}