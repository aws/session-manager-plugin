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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import static software.amazon.smithy.go.codegen.TestUtils.buildMockPluginContext;
import static software.amazon.smithy.go.codegen.TestUtils.loadSmithyModelFromResource;
import static software.amazon.smithy.go.codegen.TestUtils.loadExpectedFileStringFromResource;


public class IntEnumShapeGeneratorTest {
    private static final Logger LOGGER = Logger.getLogger(IntEnumShapeGeneratorTest.class.getName());

    @Test
    public void testIntEnumShapeTest() {

        // Arrange
        Model model =
            loadSmithyModelFromResource("int-enum-shape-test");
        MockManifest manifest =
            new MockManifest();
        PluginContext context =
            buildMockPluginContext(model, manifest, "smithy.example#Example");

        // Act
        (new GoCodegenPlugin()).execute(context);

        // Assert
        String actualEnumShapeCode =
            manifest.getFileString("types/enums.go").get();
        String expectedEnumShapeCode =
            loadExpectedFileStringFromResource("int-enum-shape-test", "types/enums.go");
        assertThat("intEnum shape actual generated code is equal to the expected generated code",
            actualEnumShapeCode,
            is(expectedEnumShapeCode));
        String actualChangeCardOperationCode =
            manifest.getFileString("api_op_ChangeCard.go").get();
        String expectedChangeCardInputCode =
            loadExpectedFileStringFromResource("int-enum-shape-test", "changeCardInput.go.struct");
        assertThat("intEnum shape properly referenced in generated input structure code",
            actualChangeCardOperationCode,
            containsString(expectedChangeCardInputCode));
        String expectedChangeCardOutputCode =
            loadExpectedFileStringFromResource("int-enum-shape-test", "changeCardOutput.go.struct");
        assertThat("intEnum shape properly referenced in generated output structure code",
            actualChangeCardOperationCode,
            containsString(expectedChangeCardOutputCode));

    }

}
