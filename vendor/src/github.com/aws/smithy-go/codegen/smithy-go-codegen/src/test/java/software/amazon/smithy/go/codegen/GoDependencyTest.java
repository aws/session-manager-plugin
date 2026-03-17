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

import static org.hamcrest.MatcherAssert.assertThat;

import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.codegen.core.SymbolDependency;

public class GoDependencyTest {
    @Test
    public void testStandardLibraryDependency() {
        GoDependency dependency = GoDependency.builder()
                .type(GoDependency.Type.STANDARD_LIBRARY)
                .importPath("net/http")
                .version("1.14")
                .build();
        List<SymbolDependency> symbolDependencies = dependency.getDependencies();
        assertThat(symbolDependencies.size(), Matchers.equalTo(1));
        SymbolDependency symbolDependency = symbolDependencies.get(0);

        assertThat(symbolDependency.getDependencyType(), Matchers.equalTo("stdlib"));
        assertThat(symbolDependency.getPackageName(), Matchers.equalTo(""));
        assertThat(symbolDependency.getVersion(), Matchers.equalTo("1.14"));
    }

    @Test
    public void testSingleDependency() {
        GoDependency dependency = GoDependency.builder()
                .type(GoDependency.Type.DEPENDENCY)
                .sourcePath("github.com/aws/smithy-go")
                .importPath("github.com/aws/smithy-go/middleware")
                .version("1.2.3")
                .build();
        List<SymbolDependency> symbolDependencies = dependency.getDependencies();
        assertThat(symbolDependencies.size(), Matchers.equalTo(1));
        SymbolDependency symbolDependency = symbolDependencies.get(0);

        assertThat(symbolDependency.getDependencyType(), Matchers.equalTo("dependency"));
        assertThat(symbolDependency.getPackageName(), Matchers.equalTo("github.com/aws/smithy-go"));
        assertThat(symbolDependency.getVersion(), Matchers.equalTo("1.2.3"));
    }

    @Test
    public void testDependencyWithDependencies() {
        GoDependency dependency = GoDependency.builder()
                .type(GoDependency.Type.DEPENDENCY)
                .sourcePath("github.com/aws/aws-sdk-go-v2")
                .importPath("github.com/aws/aws-sdk-go-v2/aws/middleware")
                .version("1.2.3")
                .addDependency(GoDependency.builder()
                        .type(GoDependency.Type.DEPENDENCY)
                        .sourcePath("github.com/aws/smithy-go")
                        .importPath("github.com/aws/smithy-go/middleware")
                        .version("3.4.5")
                        .build())
                .build();
        List<SymbolDependency> symbolDependencies = dependency.getDependencies();
        assertThat(symbolDependencies.size(), Matchers.equalTo(2));
        assertThat(symbolDependencies, Matchers.containsInAnyOrder(
                Matchers.equalTo(SymbolDependency.builder()
                        .dependencyType("dependency")
                        .packageName("github.com/aws/aws-sdk-go-v2")
                        .version("1.2.3")
                        .build()),
                Matchers.equalTo(SymbolDependency.builder()
                        .dependencyType("dependency")
                        .packageName("github.com/aws/smithy-go")
                        .version("3.4.5")
                        .build())
        ));
    }

    @Test
    public void testDependencyWithNestedDependencies() {
        GoDependency dependency = GoDependency.builder()
                .type(GoDependency.Type.DEPENDENCY)
                .sourcePath("github.com/aws/aws-sdk-go-v2")
                .importPath("github.com/aws/aws-sdk-go-v2/aws/middleware")
                .version("1.2.3")
                .addDependency(GoDependency.builder()
                        .type(GoDependency.Type.DEPENDENCY)
                        .sourcePath("github.com/aws/smithy-go")
                        .importPath("github.com/aws/smithy-go/middleware")
                        .version("3.4.5")
                        .addDependency(GoDependency.builder()
                                .type(GoDependency.Type.DEPENDENCY)
                                .sourcePath("github.com/awslabs/smithy-go-extensions")
                                .importPath("github.com/awslabs/smithy-go-extensions/foobar")
                                .version("6.7.8")
                                .addDependency(GoDependency.builder()
                                        .type(GoDependency.Type.STANDARD_LIBRARY)
                                        .importPath("net/http")
                                        .version("1.14")
                                        .build())
                                .addDependency(GoDependency.builder()
                                        .type(GoDependency.Type.STANDARD_LIBRARY)
                                        .importPath("time")
                                        .version("1.14")
                                        .build())
                                .build())
                        .build())
                .build();

        List<SymbolDependency> symbolDependencies = dependency.getDependencies();
        assertThat(symbolDependencies.size(), Matchers.equalTo(4));
        assertThat(symbolDependencies, Matchers.containsInAnyOrder(
                Matchers.equalTo(SymbolDependency.builder()
                        .dependencyType("dependency")
                        .packageName("github.com/aws/aws-sdk-go-v2")
                        .version("1.2.3")
                        .build()),
                Matchers.equalTo(SymbolDependency.builder()
                        .dependencyType("dependency")
                        .packageName("github.com/aws/smithy-go")
                        .version("3.4.5")
                        .build()),
                Matchers.equalTo(SymbolDependency.builder()
                        .dependencyType("dependency")
                        .packageName("github.com/awslabs/smithy-go-extensions")
                        .version("6.7.8")
                        .build()),
                Matchers.equalTo(SymbolDependency.builder()
                        .dependencyType("stdlib")
                        .packageName("")
                        .version("1.14")
                        .build())
        ));
    }
}
