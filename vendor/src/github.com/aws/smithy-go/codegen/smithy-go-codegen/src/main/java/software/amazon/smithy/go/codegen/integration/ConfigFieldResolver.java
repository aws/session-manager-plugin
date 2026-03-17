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

package software.amazon.smithy.go.codegen.integration;

import java.util.Objects;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * Represent symbol that points to a function that operates
 * on the client options during client construction or operation invocation.
 * <p>
 * Can target options prior customer mutation (Initialization), or after customer mutation (Finalization).
 * <p>
 * Any configuration that a plugin requires in order to function should be
 * checked in this function, either setting a default value if possible or
 * returning an error if not.
 */
public final class ConfigFieldResolver {
    private final Location location;
    private final Target target;
    private final Symbol resolver;
    private final boolean withOperationName;
    private final boolean withClientInput;

    private ConfigFieldResolver(Builder builder) {
        location = SmithyBuilder.requiredState("location", builder.location);
        target = SmithyBuilder.requiredState("target", builder.target);
        resolver = SmithyBuilder.requiredState("resolver", builder.resolver);
        withOperationName = builder.withOperationName;
        withClientInput = builder.withClientInput;
    }

    public Location getLocation() {
        return location;
    }

    public Target getTarget() {
        return target;
    }

    public Symbol getResolver() {
        return resolver;
    }

    public boolean isWithOperationName() {
        return withOperationName && location == Location.OPERATION;
    }

    public boolean isWithClientInput() {
        return withClientInput;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ConfigFieldResolver that = (ConfigFieldResolver) o;
        return location == that.location
                && target == that.target
                && resolver.equals(that.resolver)
                && withOperationName == that.withOperationName
                && withClientInput == that.withClientInput;
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(location, target, resolver);
    }

    /**
     * The location where the resolver is executed.
     */
    public enum Location {
        /**
         * Indicates that the resolver is executed in the client constructor.
         */
        CLIENT,
        /**
         * Indicates that the resolver is executed during operation invocation.
         */
        OPERATION
    }

    /**
     * Indicates the target of the resolver.
     */
    public enum Target {
        /**
         * Indicates that the resolver targets config fields prior to customer mutation.
         */
        INITIALIZATION,

        /**
         * Indicates that the resolver targets config fields after customer mutation.
         */
        FINALIZATION,

        /**
         * Indicates that the resolver targets config fields after the client has been instantiated. Resolvers with this
         * target can then take a reference to the instantiated client in existing Options, but CANNOT modify fields on
         * Options since it is passed to the already-existing client by value.
         */
        FINALIZATION_WITH_CLIENT
    }

    public static class Builder implements SmithyBuilder<ConfigFieldResolver> {
        private Location location;
        private Target target;
        private Symbol resolver;
        private boolean withOperationName = false;
        private boolean withClientInput = false;

        public Builder location(Location location) {
            this.location = location;
            return this;
        }

        public Builder target(Target target) {
            this.target = target;
            return this;
        }

        public Builder resolver(Symbol resolver) {
            this.resolver = resolver;
            return this;
        }

        public Builder withOperationName(boolean withOperationName) {
            this.withOperationName = withOperationName;
            return this;
        }

        public Builder withClientInput(boolean withClientInput) {
            this.withClientInput = withClientInput;
            return this;
        }

        @Override
        public ConfigFieldResolver build() {
            return new ConfigFieldResolver(this);
        }
    }
}
