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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolDependencyContainer;
import software.amazon.smithy.utils.SetUtils;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 *
 */
public final class GoDependency implements SymbolDependencyContainer, Comparable<GoDependency> {
    private final Type type;
    private final String sourcePath;
    private final String importPath;
    private final String alias;
    private final String version;
    private final Set<GoDependency> dependencies;
    private final SymbolDependency symbolDependency;

    private GoDependency(Builder builder) {
        this.type = SmithyBuilder.requiredState("type", builder.type);
        this.sourcePath = !this.type.equals(Type.STANDARD_LIBRARY)
                ? SmithyBuilder.requiredState("sourcePath", builder.sourcePath) : "";
        this.importPath = SmithyBuilder.requiredState("importPath", builder.importPath);
        this.alias = builder.alias;
        this.version = SmithyBuilder.requiredState("version", builder.version);
        this.dependencies = builder.dependencies;

        this.symbolDependency = SymbolDependency.builder()
                .dependencyType(this.type.toString())
                .packageName(this.sourcePath)
                .version(this.version)
                .build();
    }

    /**
     * Given two {@link SymbolDependency} referring to the same package, return the minimum dependency version using
     * minimum version selection. The version strings must be semver compatible.
     *
     * @param dx the first dependency
     * @param dy the second dependency
     * @return the minimum dependency
     */
    public static SymbolDependency mergeByMinimumVersionSelection(SymbolDependency dx, SymbolDependency dy) {
        SemanticVersion sx = SemanticVersion.parseVersion(dx.getVersion());
        SemanticVersion sy = SemanticVersion.parseVersion(dy.getVersion());

        // This *shouldn't* happen in Go since the Go module import path must end with the major version component.
        // Exception is the case where the major version is 0 or 1.
        if (sx.getMajor() != sy.getMajor() && !(sx.getMajor() == 0 || sy.getMajor() == 0)) {
            throw new CodegenException(String.format("Dependency %s has conflicting major versions",
                    dx.getPackageName()));
        }

        int cmp = sx.compareTo(sy);
        if (cmp < 0) {
            return dy;
        } else if (cmp > 0) {
            return dx;
        }
        return dx;
    }

    /**
     * Get the the set of {@link GoDependency} required by this dependency.
     *
     * @return the set of dependencies
     */
    public Set<GoDependency> getGoDependencies() {
        return this.dependencies;
    }

    /**
     * Get the symbol dependency representing the dependency.
     *
     * @return the symbol dependency
     */
    public SymbolDependency getSymbolDependency() {
        return symbolDependency;
    }

    /**
     * Get the type of dependency.
     *
     * @return the dependency type
     */
    public Type getType() {
        return type;
    }

    /**
     * Get the source code path of the dependency.
     *
     * @return the dependency source code path
     */
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * Get the import path of the dependency.
     *
     * @return the import path of the dependency
     */
    public String getImportPath() {
        return importPath;
    }

    /**
     * Get the alias of the module to use.
     *
     * @return the alias
     */
    public String getAlias() {
        return alias;
    }

    /**
     * Get the version of the dependency.
     *
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Creates a Symbol for a name exported by this package.
     * @param name The name.
     * @return The symbol.
     */
    public Symbol valueSymbol(String name) {
        return SymbolUtils.createValueSymbolBuilder(name, this).build();
    }

    /**
     * Creates a Symbol for a `const` exported by this package.
     * @param name The name.
     * @return The symbol.
     */
    public Symbol constSymbol(String name) {
        return SymbolUtils.createValueSymbolBuilder(name, this).build();
    }

    /**
     * Creates a Symbol for a `func` exported by this package.
     * @param name The name.
     * @return The symbol.
     */
    public Symbol func(String name) {
        return SymbolUtils.createValueSymbolBuilder(name, this).build();
    }

    /**
     * Creates a Symbol for a `struct` exported by this package.
     * @param name The name.
     * @return The symbol.
     */
    public Symbol struct(String name) {
        return SymbolUtils.createPointableSymbolBuilder(name, this).build();
    }

    /**
     * Creates a Symbol for a `Value` exported by this package.
     * @param name The name.
     * @return The symbol.
     */
    public Symbol interfaceSymbol(String name) {
        return SymbolUtils.createValueSymbolBuilder(name, this).build();
    }

    /**
     * Creates a pointable Symbol for a name exported by this package.
     * @param name The name.
     * @return The symbol.
     */
    public Symbol pointableSymbol(String name) {
        return SymbolUtils.createPointableSymbolBuilder(name, this).build();
    }

    @Override
    public List<SymbolDependency> getDependencies() {
        Set<SymbolDependency> symbolDependencySet = new TreeSet<>(SetUtils.of(getSymbolDependency()));

        symbolDependencySet.addAll(resolveDependencies(getGoDependencies(), new TreeSet<>(SetUtils.of(this))));

        return new ArrayList<>(symbolDependencySet);
    }

    private Set<SymbolDependency> resolveDependencies(Set<GoDependency> goDependencies, Set<GoDependency> processed) {
        Set<SymbolDependency> symbolDependencies = new TreeSet<>();
        if (goDependencies.size() == 0) {
            return symbolDependencies;
        }

        Set<GoDependency> dependenciesToResolve = new TreeSet<>();
        for (GoDependency dependency : goDependencies) {
            if (processed.contains(dependency)) {
                continue;
            }
            processed.add(dependency);
            symbolDependencies.add(dependency.getSymbolDependency());
            dependenciesToResolve.addAll(dependency.getGoDependencies());
        }

        symbolDependencies.addAll(resolveDependencies(dependenciesToResolve, processed));

        return symbolDependencies;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof GoDependency)) {
            return false;
        }

        GoDependency other = (GoDependency) o;

        return this.type.equals(other.type) && this.sourcePath.equals(other.sourcePath)
                && this.importPath.equals(other.importPath) && this.alias.equals(other.alias)
                && this.version.equals(other.version) && this.dependencies.equals(other.dependencies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sourcePath, importPath, alias, version, dependencies);
    }

    @Override
    public int compareTo(GoDependency o) {
        if (equals(o)) {
            return 0;
        }

        int importPathCompare = importPath.compareTo(o.importPath);
        if (importPathCompare != 0) {
            return importPathCompare;
        }

        if (alias != null) {
            int aliasCompare = alias.compareTo(o.alias);
            if (aliasCompare != 0) {
                return aliasCompare;
            }
        }

        return version.compareTo(o.version);
    }

    /**
     * Represents a dependency type.
     */
    public enum Type {
        STANDARD_LIBRARY, DEPENDENCY;

        @Override
        public String toString() {
            switch (this) {
                case STANDARD_LIBRARY:
                    return "stdlib";
                case DEPENDENCY:
                    return "dependency";
                default:
                    return "unknown";
            }
        }
    }

    /**
     * Get {@link GoDependency} representing the provided module description.
     *
     * @param sourcePath the root source path for the given code
     * @param importPath the import path of the package
     * @param version    the version of source module
     * @param alias      a default alias to use when importing the package, can be null
     * @return the dependency
     */
    public static GoDependency moduleDependency(String sourcePath, String importPath, String version, String alias) {
        GoDependency.Builder builder = GoDependency.builder()
                .type(GoDependency.Type.DEPENDENCY)
                .sourcePath(sourcePath)
                .importPath(importPath)
                .version(version);
        if (alias != null) {
            builder.alias(alias);
        }
        return builder.build();
    }

    /**
     * Get {@link GoDependency} representing the standard library import description.
     *
     * @param importPath the import path of the package
     * @param version    the version of source module
     * @return the dependency
     */
    public static GoDependency standardLibraryDependency(String importPath, String version) {
        return GoDependency.builder()
                .type(GoDependency.Type.STANDARD_LIBRARY)
                .importPath(importPath)
                .version(version)
                .build();
    }

    /**
     * Get {@link GoDependency} representing the standard library import description.
     *
     * @param importPath the import path of the package
     * @param version    the version of source module
     * @param alias      the alias for stdlib dependency
     * @return the dependency
     */
    public static GoDependency standardLibraryDependency(String importPath, String version, String alias) {
        GoDependency.Builder builder = GoDependency.builder()
                .type(GoDependency.Type.STANDARD_LIBRARY)
                .importPath(importPath)
                .version(version);

        if (alias != null) {
            builder.alias(alias);
        }
        return builder.build();
    }

    /**
     * Builder for {@link GoDependency}.
     */
    public static final class Builder implements SmithyBuilder<GoDependency> {
        private Type type;
        private String sourcePath;
        private String importPath;
        private String alias;
        private String version;
        private final Set<GoDependency> dependencies = new TreeSet<>();

        private Builder() {
        }

        /**
         * Set the dependency type.
         *
         * @param type dependency type
         * @return the builder
         */
        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        /**
         * Set the source path.
         *
         * @param sourcePath the source path root
         * @return the builder
         */
        public Builder sourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }

        /**
         * Set the import path.
         *
         * @param importPath the import path
         * @return the builder
         */
        public Builder importPath(String importPath) {
            this.importPath = importPath;
            return this;
        }

        /**
         * Set the dependency alias.
         *
         * @param alias the alias
         * @return the builder
         */
        public Builder alias(String alias) {
            this.alias = alias;
            return this;
        }

        /**
         * Set the dependency version.
         *
         * @param version the version
         * @return the builder
         */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * Set the collection of {@link GoDependency} required by this dependency.
         *
         * @param dependencies a collection of dependencies
         * @return the builder
         */
        public Builder dependencies(Collection<GoDependency> dependencies) {
            this.dependencies.clear();
            this.dependencies.addAll(dependencies);
            return this;
        }

        /**
         * Add a dependency on another {@link GoDependency}.
         *
         * @param dependency the dependency
         * @return the builder
         */
        public Builder addDependency(GoDependency dependency) {
            this.dependencies.add(dependency);
            return this;
        }

        /**
         * Remove a dependency on another {@link GoDependency}.
         *
         * @param dependency the dependency
         * @return the builder
         */
        public Builder removeDependency(GoDependency dependency) {
            this.dependencies.remove(dependency);
            return this;
        }

        @Override
        public GoDependency build() {
            return new GoDependency(this);
        }
    }
}
