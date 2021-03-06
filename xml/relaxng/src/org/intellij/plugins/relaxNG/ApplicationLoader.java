/*
 * Copyright 2007 Sascha Weinreuter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.plugins.relaxNG;

import com.intellij.javaee.ResourceRegistrar;
import com.intellij.javaee.StandardResourceProvider;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.psi.PsiElement;
import com.intellij.psi.filters.AndFilter;
import com.intellij.psi.filters.ClassFilter;
import com.intellij.psi.filters.ElementFilter;
import com.intellij.psi.filters.position.NamespaceFilter;
import com.intellij.psi.meta.MetaDataRegistrar;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomManager;
import org.intellij.plugins.relaxNG.compact.psi.impl.RncDocument;
import org.intellij.plugins.relaxNG.inspections.RngDomInspection;
import org.intellij.plugins.relaxNG.inspections.UnusedDefineInspection;
import org.intellij.plugins.relaxNG.model.descriptors.RngNsDescriptor;
import org.intellij.plugins.relaxNG.validation.ValidateAction;
import org.intellij.plugins.relaxNG.xml.dom.RngDefine;
import org.intellij.plugins.relaxNG.xml.dom.impl.RngDefineMetaData;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * Created by IntelliJ IDEA.
 * User: sweinreuter
 * Date: 18.07.2007
 */
public class ApplicationLoader implements ApplicationComponent {
  private static final String RNG_EXT = "rng";
  private static final String VALIDATE_XML = "ValidateXml";
  public static final String RNG_NAMESPACE = "http://relaxng.org/ns/structure/1.0";

  @Override
  @NonNls
  @NotNull
  public String getComponentName() {
    return "Relax-NG";
  }

  @Override
  public void initComponent() {
    registerMetaData();

    installValidateXmlAction();
  }

  private static void installValidateXmlAction() {
    final ActionManager mgr = ActionManager.getInstance();
    final AnAction validateAction = mgr.getAction(VALIDATE_XML);
    mgr.unregisterAction(VALIDATE_XML);
    mgr.registerAction(VALIDATE_XML, new ValidateAction(validateAction));
  }

  private static void registerMetaData() {
    final MetaDataRegistrar registrar = MetaDataRegistrar.getInstance();
    registrar.registerMetaData(
            new AndFilter(
                    new NamespaceFilter(RNG_NAMESPACE),
                    new ClassFilter(XmlDocument.class)
            ),
            RngNsDescriptor.class);

    registrar.registerMetaData(
            new ClassFilter(RncDocument.class),
            RngNsDescriptor.class);

    registrar.registerMetaData(new ElementFilter() {
      @Override
      public boolean isAcceptable(Object element, PsiElement context) {
        if (element instanceof XmlTag) {
          final XmlTag tag = (XmlTag)element;
          final DomElement domElement = DomManager.getDomManager(tag.getProject()).getDomElement(tag);
          return domElement instanceof RngDefine;
        }
        return false;
      }

      @Override
      public boolean isClassAcceptable(Class hintClass) {
        return XmlTag.class.isAssignableFrom(hintClass);
      }
    }, RngDefineMetaData.class);
  }

  @Override
  public void disposeComponent() {
  }

  public static Class[] getInspectionClasses() {
    return new Class[]{
            RngDomInspection.class,
            UnusedDefineInspection.class
    };
  }

  public static class ResourceProvider implements StandardResourceProvider {
    @Override
    public void registerResources(ResourceRegistrar registrar) {
      registrar.addStdResource(RNG_NAMESPACE, "/resources/relaxng.rng", getClass());
      registrar.addIgnoredResource("http://relaxng.org/ns/compatibility/annotations/1.0");
    }
  }
}
