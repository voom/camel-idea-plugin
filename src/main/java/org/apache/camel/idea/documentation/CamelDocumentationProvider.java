/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.idea.documentation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.intellij.ide.BrowserUtil;
import com.intellij.lang.documentation.DocumentationProviderEx;
import com.intellij.lang.documentation.ExternalDocumentationHandler;
import com.intellij.lang.documentation.ExternalDocumentationProvider;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameterList;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.util.PsiTreeUtil;
import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.JSonSchemaHelper;
import org.apache.camel.idea.catalog.CamelCatalogService;
import org.apache.camel.idea.model.ComponentModel;
import org.apache.camel.idea.model.ModelHelper;
import org.apache.camel.idea.util.CamelService;
import org.apache.camel.idea.util.IdeaUtils;
import org.apache.camel.idea.util.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.apache.camel.idea.util.IdeaUtils.extractTextFromElement;
import static org.apache.camel.idea.util.StringUtils.asComponentName;
import static org.apache.camel.idea.util.StringUtils.asLanguageName;
import static org.apache.camel.idea.util.StringUtils.wrapSeparator;
import static org.apache.camel.idea.util.StringUtils.wrapWords;

/**
 * Camel documentation provider to hook into IDEA to show Camel endpoint documentation in popups and various other places.
 */
public class CamelDocumentationProvider extends DocumentationProviderEx implements ExternalDocumentationProvider, ExternalDocumentationHandler {

    private static final String GITHUB_EXTERNAL_DOC_URL = "https://github.com/apache/camel/blob/master";

    @Nullable
    @Override
    public String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        if (ServiceManager.getService(element.getProject(), CamelService.class).isCamelPresent()) {
            PsiExpressionList exps = PsiTreeUtil.getNextSiblingOfType(originalElement, PsiExpressionList.class);
            if (exps != null) {
                if (exps.getExpressions().length >= 1) {
                    // grab first string parameter (as the string would contain the camel endpoint uri
                    final PsiClassType stringType = PsiType.getJavaLangString(element.getManager(), element.getResolveScope());
                    PsiExpression exp = Arrays.stream(exps.getExpressions()).filter(
                        (e) -> e.getType() != null && stringType.isAssignableFrom(e.getType()))
                        .findFirst().orElse(null);
                    if (exp instanceof PsiLiteralExpression) {
                        Object o = ((PsiLiteralExpression) exp).getValue();
                        String val = o != null ? o.toString() : null;
                        // okay only allow this popup to work when its from a RouteBuilder class
                        PsiClass clazz = PsiTreeUtil.getParentOfType(originalElement, PsiClass.class);
                        if (clazz != null) {
                            PsiClassType[] types = clazz.getExtendsListTypes();
                            boolean found = Arrays.stream(types).anyMatch((p) -> p.getClassName().equals("RouteBuilder"));
                            if (found) {
                                String componentName = StringUtils.asComponentName(val);
                                if (componentName != null) {
                                    // the quick info cannot be so wide so wrap at 120 chars
                                    return generateCamelComponentDocumentation(componentName, val, 120, element.getProject());
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    @Nullable
    @Override
    public List<String> getUrlFor(PsiElement element, PsiElement originalElement) {
        return null;
    }

    @Nullable
    @Override
    public String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        String val = null;
        if (ServiceManager.getService(element.getProject(), CamelService.class).isCamelPresent()) {
            val = fetchLiteralForCamelDocumentation(element);
            if (val == null) {
                return null;
            }
        }

        String componentName = StringUtils.asComponentName(val);
        if (componentName != null) {
            return generateCamelComponentDocumentation(componentName, val, -1, element.getProject());
        } else {
            // its maybe a method call for a Camel language
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
            if (call != null) {
                PsiMethod method = call.resolveMethod();
                if (method != null) {
                    // try to see if we have a Camel language with the method name
                    String name = asLanguageName(method.getName());
                    if (ServiceManager.getService(element.getProject(), CamelCatalogService.class).get().findLanguageNames().contains(name)) {
                        // okay its a potential Camel language so see if the psi method call is using
                        // camel-core types so we know for a fact its really a Camel language
                        if (isPsiMethodCamelLanguage(method)) {
                            String html = ServiceManager.getService(element.getProject(), CamelCatalogService.class).get().languageHtmlDoc(name);
                            if (html != null) {
                                return html;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    @Override
    public PsiElement getDocumentationElementForLookupItem(PsiManager psiManager, Object object, PsiElement element) {
        return null;
    }

    @Nullable
    @Override
    public PsiElement getDocumentationElementForLink(PsiManager psiManager, String link, PsiElement context) {
        return null;
    }

    @Nullable
    @Override
    public PsiElement getCustomDocumentationElement(@NotNull Editor editor, @NotNull PsiFile file, @Nullable PsiElement contextElement) {
        // documentation from properties file will cause IDEA to call this method where we can tell IDEA we can provide
        // documentation for the element if we can detect its a Camel component
        if (ServiceManager.getService(contextElement.getProject(), CamelService.class).isCamelPresent() && hasDocumentationForCamelComponent(contextElement)) {
            return contextElement;
        }
        return null;
    }

    @Nullable
    @Override
    public String fetchExternalDocumentation(Project project, PsiElement element, List<String> docUrls) {
        return null;
    }

    @Override
    public boolean hasDocumentationFor(PsiElement element, PsiElement originalElement) {
        return hasDocumentationForCamelComponent(element);
    }

    @Override
    public boolean canPromptToConfigureDocumentation(PsiElement element) {
        return false;
    }

    @Override
    public void promptToConfigureDocumentation(PsiElement element) {
        // noop
    }

    @Override
    public boolean handleExternal(PsiElement element, PsiElement originalElement) {
        String val = fetchLiteralForCamelDocumentation(element);
        if (val == null || !ServiceManager.getService(element.getProject(), CamelService.class).isCamelPresent()) {
            return false;
        }

        String name = StringUtils.asComponentName(val);
        Project project = element.getProject();
        CamelCatalog camelCatalog = ServiceManager.getService(project, CamelCatalogService.class).get();
        if (name != null && camelCatalog.findComponentNames().contains(name)) {

            String json = camelCatalog.componentJSonSchema(name);
            ComponentModel component = ModelHelper.generateComponentModel(json, false);

            // to build external links which points to github
            String a = component.getArtifactId();

            String url;
            if ("camel-core".equals(a)) {
                url = GITHUB_EXTERNAL_DOC_URL + "/camel-core/src/main/docs/" + name + "-component.adoc";
            } else {
                url = GITHUB_EXTERNAL_DOC_URL + "/components/" + component.getArtifactId() + "/src/main/docs/" + name + "-component.adoc";
            }

            String hash = component.getTitle().toLowerCase().replace(' ', '-') + "-component";
            BrowserUtil.browse(url + "#" + hash);
            return true;
        }

        return false;
    }

    @Override
    public boolean handleExternalLink(PsiManager psiManager, String link, PsiElement context) {
        return false;
    }

    @Override
    public boolean canFetchDocumentationLink(String link) {
        return false;
    }

    @NotNull
    @Override
    public String fetchExternalDocumentation(@NotNull String link, @Nullable PsiElement element) {
        return null;
    }

    private boolean hasDocumentationForCamelComponent(PsiElement element) {
        if (ServiceManager.getService(element.getProject(), CamelService.class).isCamelPresent()) {
            String text = fetchLiteralForCamelDocumentation(element);
            if (text != null) {
                // check if its a known Camel component
                String name = asComponentName(text);
                Project project = element.getProject();
                return ServiceManager.getService(project, CamelCatalogService.class).get().findComponentNames().contains(name);
            }
        }
        return false;
    }

    private String fetchLiteralForCamelDocumentation(PsiElement element) {
        if (element == null) {
            return null;
        }
        return extractTextFromElement(element);
    }

    private String generateCamelComponentDocumentation(String componentName, String val, int wrapLength, Project project) {
        // it is a known Camel component
        CamelCatalog camelCatalog = ServiceManager.getService(project, CamelCatalogService.class).get();
        String json = camelCatalog.componentJSonSchema(componentName);
        if (json == null) {
            return null;
        }

        ComponentModel component = ModelHelper.generateComponentModel(json, false);

        Map<String, String> existing = null;
        try {
            existing = camelCatalog.endpointProperties(val);
        } catch (Throwable e) {
            // ignore
        }

        StringBuilder options = new StringBuilder();
        if (existing != null && !existing.isEmpty()) {
            List<Map<String, String>> lines = JSonSchemaHelper.parseJsonSchema("properties", json, true);

            for (Map.Entry<String, String> entry : existing.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();

                Map<String, String> row = JSonSchemaHelper.getRow(lines, name);
                if (row != null) {
                    String kind = row.get("kind");

                    String line;
                    if ("path".equals(kind)) {
                        line = value + "<br/>";
                    } else {
                        line = name + "=" + value + "<br/>";
                    }
                    options.append("<br/>");
                    options.append("<b>").append(line).append("</b>");

                    String summary = row.get("description");
                    options.append(wrapText(summary, wrapLength)).append("<br/>");
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(component.getTitle()).append(" Component</b><br/>");
        sb.append(wrapText(component.getDescription(), wrapLength)).append("<br/><br/>");
        sb.append("Syntax: <tt>").append(component.getSyntax()).append("?options</tt><br/>");
        sb.append("Java class: <tt>").append(component.getJavaType()).append("</tt><br/>");

        String g = component.getGroupId();
        String a = component.getArtifactId();
        String v = component.getVersion();
        if (g != null && a != null && v != null) {
            sb.append("Maven: <tt>").append(g).append(":").append(a).append(":").append(v).append("</tt><br/>");
        }
        sb.append("<p/>");

        // indent the endpoint url with 5 spaces and wrap it by url separator
        String wrapped = wrapSeparator(val, "&", "<br/>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;", 100);
        sb.append("&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<b>").append(wrapped).append("</b><br/>");

        if (options.length() > 0) {
            sb.append(options.toString());
        }
        return sb.toString();
    }

    private boolean isPsiMethodCamelLanguage(PsiMethod method) {
        PsiType type = method.getReturnType();
        if (type != null && type instanceof PsiClassReferenceType) {
            PsiClassReferenceType clazz = (PsiClassReferenceType) type;
            PsiClass resolved = clazz.resolve();
            if (resolved != null) {
                boolean language = IdeaUtils.isCamelExpressionOrLanguage(resolved);
                // try parent using some weird/nasty stub stuff which is how complex IDEA AST
                // is when its parsing the Camel route builder
                if (!language) {
                    PsiElement elem = resolved.getParent();
                    if (elem instanceof PsiTypeParameterList) {
                        elem = elem.getParent();
                    }
                    if (elem instanceof PsiClass) {
                        language = IdeaUtils.isCamelExpressionOrLanguage((PsiClass) elem);
                    }
                }
                return language;
            }
        }

        return false;
    }

    private static String wrapText(String text, int wrapLength) {
        if (wrapLength > 0) {
            text = wrapWords(text, "<br/>", wrapLength, true);
        }
        return text;
    }
}
