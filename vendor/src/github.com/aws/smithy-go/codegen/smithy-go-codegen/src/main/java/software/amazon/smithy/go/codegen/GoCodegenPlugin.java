/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.go.codegen;

import java.util.logging.Logger;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.build.SmithyBuildPlugin;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;

/**
 * Plugin to trigger Go code generation.
 */
public final class GoCodegenPlugin implements SmithyBuildPlugin {
    private static final Logger LOGGER = Logger.getLogger(GoCodegenPlugin.class.getName());

    @Override
    public String getName() {
        return "go-codegen";
    }

    @Override
    public void execute(PluginContext context) {
        String onlyBuild = System.getenv("SMITHY_GO_BUILD_API");
        if (onlyBuild != null && !onlyBuild.isEmpty()) {
            String targetServiceId = GoSettings.from(context.getSettings()).getService().toString();

            boolean found = false;
            for (String includeServiceId : onlyBuild.split(",")) {
                if (targetServiceId.startsWith(includeServiceId)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                LOGGER.info("skipping " + targetServiceId);
                return;
            }
        }

        new CodegenVisitor(context).execute();
    }

    /**
     * Creates a Go symbol provider.
     *
     * @param model    The model to generate symbols for.
     * @param settings The Gosettings to use to create symbol provider
     * @return Returns the created provider.
     */
    public static SymbolProvider createSymbolProvider(Model model, GoSettings settings) {
        return new SymbolVisitor(model, settings);
    }
}
