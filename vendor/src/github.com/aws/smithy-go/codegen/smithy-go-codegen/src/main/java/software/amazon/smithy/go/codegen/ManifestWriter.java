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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.traits.UnstableTrait;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Generates a manifest description of the generated code, minimum go version,
 * and minimum dependencies required.
 */
@SmithyInternalApi
public final class ManifestWriter {

    private static final Logger LOGGER = Logger.getLogger(ManifestWriter.class.getName());

    private static final String GENERATED_JSON = "generated.json";

    private final String moduleName;
    private final FileManifest fileManifest;
    private final GoModuleInfo goModuleInfo;
    private final boolean isUnstable;

    private ManifestWriter(Builder builder) {
        moduleName = SmithyBuilder.requiredState("moduleName", builder.moduleName);
        fileManifest = SmithyBuilder.requiredState("fileManifest", builder.fileManifest);
        goModuleInfo = SmithyBuilder.requiredState("goModuleInfo", builder.goModuleInfo);
        isUnstable = builder.isUnstable;
    }

    /**
     * Write the manifest description of the Smithy model based generated source code.
     *
     * @param settings     the go settings
     * @param model        the smithy model
     * @param fileManifest the file manifest
     * @param goModuleInfo the go module info
     */
    public static void writeManifest(
            GoSettings settings,
            Model model,
            FileManifest fileManifest,
            GoModuleInfo goModuleInfo
    ) {
        builder()
                .moduleName(settings.getModuleName())
                .fileManifest(fileManifest)
                .goModuleInfo(goModuleInfo)
                .isUnstable(settings.getService(model).getTrait(UnstableTrait.class).isPresent())
                .build()
                .writeManifest();
    }


    /**
     * Write the manifest description of the generated code.
     */
    public void writeManifest() {
        Path manifestFile = fileManifest.getBaseDir().resolve(GENERATED_JSON);

        if (Files.exists(manifestFile)) {
            try {
                Files.delete(manifestFile);
            } catch (IOException e) {
                throw new CodegenException("Failed to delete existing " + GENERATED_JSON + " file", e);
            }
        }
        fileManifest.addFile(manifestFile);

        LOGGER.fine("Creating manifest at path " + manifestFile.toString());

        Node generatedJson = buildManifestFile();
        fileManifest.writeFile(manifestFile.toString(), Node.prettyPrintJson(generatedJson) + "\n");

    }

    private Node buildManifestFile() {
        Map<StringNode, Node> dependencyNodes = gatherDependencyNodes(goModuleInfo.getMinimumNonStdLibDependencies());
        Collection<String> generatedFiles = gatherGeneratedFiles(fileManifest);
        return ObjectNode.objectNode(Map.of(
            StringNode.from("module"), StringNode.from(moduleName),
            StringNode.from("go"), StringNode.from(goModuleInfo.getGoDirective()),
            StringNode.from("dependencies"), ObjectNode.objectNode(dependencyNodes),
            StringNode.from("files"), ArrayNode.fromStrings(generatedFiles),
            StringNode.from("unstable"), BooleanNode.from(isUnstable)
        )).withDeepSortedKeys();
    }

    private Map<StringNode, Node> gatherDependencyNodes(Map<String, String> dependencies) {
        Map<StringNode, Node> dependencyNodes = new HashMap<>();
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            dependencyNodes.put(StringNode.from(entry.getKey()), StringNode.from(entry.getValue()));
        }
        return dependencyNodes;
    }

    private static Collection<String> gatherGeneratedFiles(FileManifest fileManifest) {
        Collection<String> generatedFiles = new ArrayList<>();
        Path baseDir = fileManifest.getBaseDir();
        for (Path filePath : fileManifest.getFiles()) {
            generatedFiles.add(baseDir.relativize(filePath).toString());
        }
        generatedFiles = generatedFiles.stream().sorted().collect(Collectors.toList());
        return generatedFiles;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder implements SmithyBuilder<ManifestWriter> {
        private String moduleName;
        private FileManifest fileManifest;
        private GoModuleInfo goModuleInfo;
        private boolean isUnstable;

        public Builder moduleName(String moduleName) {
            this.moduleName = moduleName;
            return this;
        }

        public Builder fileManifest(FileManifest fileManifest) {
            this.fileManifest = fileManifest;
            return this;
        }

        public Builder goModuleInfo(GoModuleInfo goModuleInfo) {
            this.goModuleInfo = goModuleInfo;
            return this;
        }

        public Builder isUnstable(boolean isUnstable) {
            this.isUnstable = isUnstable;
            return this;
        }

        @Override
        public ManifestWriter build() {
            return new ManifestWriter(this);
        }
    }
}
