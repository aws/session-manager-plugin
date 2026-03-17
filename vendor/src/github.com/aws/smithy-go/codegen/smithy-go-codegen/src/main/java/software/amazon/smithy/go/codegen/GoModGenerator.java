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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Generates a go.mod file for the project.
 *
 * <p>See here for more information on the format: https://github.com/golang/go/wiki/Modules#gomod
 */
@SmithyInternalApi
public final class GoModGenerator {

    private static final Logger LOGGER = Logger.getLogger(GoModGenerator.class.getName());

    private GoModGenerator() {}

    public static void writeGoMod(
            GoSettings settings,
            FileManifest manifest,
            GoModuleInfo goModuleInfo
    ) {
        Boolean generateGoMod = settings.getGenerateGoMod();
        if (!generateGoMod) {
            return;
        }

        Path goModFile = manifest.getBaseDir().resolve("go.mod");
        LOGGER.fine("Generating go.mod file at path " + goModFile.toString());

        // `go mod init` will fail if the `go.mod` already exists, so this deletes
        //  it if it's present in the output. While it's technically possible
        //  to simply edit the file, it's easier to just start fresh.
        if (Files.exists(goModFile)) {
            try {
                Files.delete(goModFile);
            } catch (IOException e) {
                throw new CodegenException("Failed to delete existing go.mod file", e);
            }
        }
        manifest.addFile(goModFile);
        CodegenUtils.runCommand("go mod init " + settings.getModuleName(), manifest.getBaseDir());

        for (Map.Entry<String, String> dependency : goModuleInfo.getMinimumNonStdLibDependencies().entrySet()) {
            CodegenUtils.runCommand(
                    String.format("go mod edit -require=%s@%s", dependency.getKey(), dependency.getValue()),
                    manifest.getBaseDir());
        }

        CodegenUtils.runCommand(
            String.format("go mod edit -go=%s", goModuleInfo.getGoDirective()),
            manifest.getBaseDir());
    }
}
