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

import static software.amazon.smithy.go.codegen.testutils.ExecuteCommand.execute;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.go.codegen.testutils.ExecuteCommand;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;


public class TestUtils {

    public static final String SMITHY_TESTS_PREFIX = "smithy-tests";
    public static final String SMITHY_TESTS_EXPECTED_PREFIX = "expected";

    public static Model loadSmithyModelFromResource(String testPath) {
        String resourcePath =
            SMITHY_TESTS_PREFIX + "/" + testPath + "/" + testPath + ".smithy";
        return Model.assembler()
            .addImport(TestUtils.class.getResource(resourcePath))
            .discoverModels()
            .assemble()
            .unwrap();
    }

    public static String loadExpectedFileStringFromResource(String testPath, String filePath) {
        String resourcePath =
            SMITHY_TESTS_PREFIX + "/" + testPath + "/" + SMITHY_TESTS_EXPECTED_PREFIX + "/" + filePath;
        return getResourceAsString(resourcePath);
    }

    public static String getResourceAsString(String resourcePath) {
        try {
            return Files.readString(
                Paths.get(TestUtils.class.getResource(resourcePath).toURI()),
                Charset.forName("utf-8"));
        } catch (Exception e) {
            return null;
        }
    }

    public static PluginContext buildMockPluginContext(
        Model model,
        FileManifest manifest,
        String serviceShapeId
    ) {
        return buildPluginContext(
            model,
            manifest,
            serviceShapeId,
            "example",
            "0.0.1",
            false);
    }

    public static PluginContext buildPluginContext(
        Model model,
        FileManifest manifest,
        String serviceShapeId,
        String moduleName,
        String moduleVersion,
        Boolean generateGoMod
    ) {
        return PluginContext.builder()
            .model(model)
            .fileManifest(manifest)
            .settings(getSettingsNode(
                serviceShapeId,
                moduleName,
                moduleVersion,
                generateGoMod,
                "Example"))
            .build();
    }

    public static ObjectNode getSettingsNode(
        String serviceShapeId,
        String moduleName,
        String moduleVersion,
        Boolean generateGoMod,
        String sdkId
    ) {
        return Node.objectNodeBuilder()
            .withMember("service", Node.from(serviceShapeId))
            .withMember("module", Node.from(moduleName))
            .withMember("moduleVersion", Node.from(moduleVersion))
            .withMember("generateGoMod", Node.from(generateGoMod))
            .withMember("homepage", Node.from("https://docs.amplify.aws/"))
            .withMember("sdkId", Node.from(sdkId))
            .withMember("author", Node.from("Amazon Web Services"))
            .withMember("gitRepo", Node.from("https://github.com/aws-amplify/amplify-codegen.git"))
            .withMember("swiftVersion", Node.from("5.5.0"))
            .build();
    }

    /**
     * Returns the path for the repository's root relative to the smithy-go-codegen module.
     * @return repo root.
     */
    public static Path getRepoRootDir() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("..")
                .resolve("..")
                .toAbsolutePath();
    }

    /**
     * Returns true if the go command can be executed.
     * @return go command can be executed.
     */
    public static boolean hasGoInstalled() {
        try{
            ExecuteCommand.builder()
                    .addCommand("go", "version")
                    .build()
                    .execute();
        } catch (Exception e) {
            return false;
        }

        return true;
    }

    public static void makeGoModule(Path path) throws Exception {
        var repoRoot = getRepoRootDir();
        execute(repoRoot.toFile(),
                "go", "run",
                "github.com/awslabs/aws-go-multi-module-repository-tools/cmd/gomodgen@latest",
                "--build", path.resolve("..").toAbsolutePath().toString(),
                "--plugin-dir", path.getFileName().toString(),
                "--copy-artifact=false",
                "--prepare-target-dir=false"
        );

        execute(path.toFile(), "go", "mod", "edit", "--replace", "github.com/aws/smithy-go="+repoRoot);
        execute(path.toFile(), "go", "mod", "tidy");
        execute(path.toFile(), "gofmt", "-w", "-s", ".");
    }

    public static void testGoModule(Path path) throws Exception {
        execute(path.toFile(), "go", "test", "-v", "./...");
    }
}
