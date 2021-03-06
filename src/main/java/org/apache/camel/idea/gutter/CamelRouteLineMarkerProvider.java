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
package org.apache.camel.idea.gutter;

import java.util.Collection;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import org.apache.camel.idea.util.CamelService;
import org.apache.camel.idea.util.IdeaUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Provider that adds the Camel icon in the gutter when it detects a Camel route.
 */
public class CamelRouteLineMarkerProvider extends RelatedItemLineMarkerProvider {

    @Override
    protected void collectNavigationMarkers(@NotNull PsiElement element,
                                            Collection<? super RelatedItemLineMarkerInfo> result) {

        if (ServiceManager.getService(element.getProject(), CamelService.class).isCamelPresent()) {
            if (IdeaUtils.isCamelRouteStart(element)) {
                NavigationGutterIconBuilder<PsiElement> builder =
                    NavigationGutterIconBuilder.create(IconLoader.getIcon("/icons/camel.png")).
                        setTargets(element).
                        setTooltipText("Camel route");
                result.add(builder.createLineMarkerInfo(element));
            }
        }
    }

}
