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

package software.amazon.smithy.go.codegen.server;

import java.util.logging.Logger;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.build.SmithyBuildPlugin;
import software.amazon.smithy.codegen.core.directed.CodegenDirector;
import software.amazon.smithy.go.codegen.AbstractDirectedCodegen;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Plugin to trigger Go server code generation.
 */
@SmithyInternalApi
public final class ServerCodegenPlugin implements SmithyBuildPlugin {
    private static final Logger LOGGER = Logger.getLogger(ServerCodegenPlugin.class.getName());

    @Override
    public String getName() {
        return "go-server-codegen";
    }

    @Override
    public void execute(PluginContext context) {
        String onlyBuild = System.getenv("SMITHY_GO_BUILD_API");
        if (onlyBuild != null && !onlyBuild.isEmpty()) {
            String targetServiceId =
                GoSettings.from(context.getSettings(), GoSettings.ArtifactType.SERVER).getService().toString();

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

        generate(context);
    }

    private void generate(PluginContext context) {
        AbstractDirectedCodegen.run(context, new ServerDirectedCodegen());
    }
}
