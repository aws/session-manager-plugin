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
import org.junit.jupiter.api.Test;

import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.go.codegen.GoCodegenPlugin;
import software.amazon.smithy.model.Model;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import static software.amazon.smithy.go.codegen.TestUtils.buildMockPluginContext;
import static software.amazon.smithy.go.codegen.TestUtils.loadSmithyModelFromResource;
import static software.amazon.smithy.go.codegen.TestUtils.loadExpectedFileStringFromResource;


public class EnumShapeGeneratorTest {
    private static final Logger LOGGER = Logger.getLogger(EnumShapeGeneratorTest.class.getName());

    @Test
    public void testEnumShapeTest() {

        // Arrange
        Model model =
            loadSmithyModelFromResource("enum-shape-test");
        MockManifest manifest =
            new MockManifest();
        PluginContext context =
            buildMockPluginContext(model, manifest, "smithy.example#Example");

        // Act
        (new GoCodegenPlugin()).execute(context);

        // Assert
        String actualSuitEnumShapeCode =
            manifest.getFileString("types/enums.go").get();
        String expectedSuitEnumShapeCode =
            loadExpectedFileStringFromResource("enum-shape-test", "types/enums.go");
        assertThat("enum shape actual generated code is equal to the expected generated code",
            actualSuitEnumShapeCode,
            is(expectedSuitEnumShapeCode));

    }

}
