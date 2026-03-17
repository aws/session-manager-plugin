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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.utils.BuilderRef;
import software.amazon.smithy.utils.SmithyBuilder;

public final class GoModuleInfo {

    public static final String DEFAULT_GO_DIRECTIVE = "1.24";

    private List<SymbolDependency> dependencies;

    private List<SymbolDependency> stdLibDependencies;
    private List<SymbolDependency> nonStdLibDependencies;

    private String goDirective;
    private Map<String, String> minimumNonStdLibDependencies;

    private GoModuleInfo(Builder builder) {
        goDirective = SmithyBuilder.requiredState("goDirective", builder.goDirective);
        dependencies = builder.dependencies.copy();

        // Intermediate dependency information
        stdLibDependencies = gatherStdLibDependencies();
        nonStdLibDependencies = gatherNonStdLibDependencies();

        // Module information used by ManifestWriter and GoModGenerator
        goDirective = calculateMinimumGoDirective();
        minimumNonStdLibDependencies = gatherMinimumNonStdDependencies();
    }

    public String getGoDirective() {
        return goDirective;
    }

    public Map<String, String> getMinimumNonStdLibDependencies() {
        return minimumNonStdLibDependencies;
    }

    private String calculateMinimumGoDirective() {
        String minimumGoDirective = goDirective;
        if (minimumGoDirective.compareTo(DEFAULT_GO_DIRECTIVE) < 0) {
            throw new IllegalArgumentException(
                "`goDirective` must be greater than or equal to the default go directive ("
                + DEFAULT_GO_DIRECTIVE + "): " + minimumGoDirective);
        }
        for (SymbolDependency dependency : stdLibDependencies) {
            var otherVersion = dependency.getVersion();
            if (minimumGoDirective.compareTo(otherVersion) < 0) {
                minimumGoDirective = otherVersion;
            }
        }
        return minimumGoDirective;
    }

    private List<SymbolDependency> gatherStdLibDependencies() {
        return filterDependencies(dependency ->
            dependency.getDependencyType().equals(GoDependency.Type.STANDARD_LIBRARY.toString()));
    }

    private List<SymbolDependency> gatherNonStdLibDependencies() {
        return filterDependencies(dependency ->
            !dependency.getDependencyType().equals(GoDependency.Type.STANDARD_LIBRARY.toString()));
    }

    private List<SymbolDependency> filterDependencies(
        Predicate<SymbolDependency> predicate
    ) {
        List<SymbolDependency> filteredDependencies = new ArrayList<>();
        for (SymbolDependency dependency : dependencies) {
            if (predicate.test(dependency)) {
                filteredDependencies.add(dependency);
            }
        }
        return filteredDependencies;
    }

    private Map<String, String> gatherMinimumNonStdDependencies() {
        return SymbolDependency.gatherDependencies(nonStdLibDependencies.stream(),
                GoDependency::mergeByMinimumVersionSelection).entrySet().stream().flatMap(
                entry -> entry.getValue().entrySet().stream()).collect(
                Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getVersion(), (a, b) -> b, TreeMap::new));
    }

    public static class Builder implements SmithyBuilder<GoModuleInfo> {
        private String goDirective = DEFAULT_GO_DIRECTIVE;
        private final BuilderRef<List<SymbolDependency>> dependencies = BuilderRef.forList();

        public Builder goDirective(String goDirective) {
            this.goDirective = goDirective;
            return this;
        }

        public Builder dependencies(List<SymbolDependency> dependencies) {
            this.dependencies.clear();
            this.dependencies.get().addAll(dependencies);
            return this;
        }

        @Override
        public GoModuleInfo build() {
            return new GoModuleInfo(this);
        }
    }
}
