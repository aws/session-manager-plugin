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
 * on the client member fields during client construction.
 *
 * Any configuration that a plugin requires in order to function should be
 * checked in this function, either setting a default value if possible or
 * returning an error if not.
 */
public final class ClientMemberResolver {
    private final Symbol resolver;

    private ClientMemberResolver(Builder builder) {
        resolver = SmithyBuilder.requiredState("resolver", builder.resolver);
    }

    public Symbol getResolver() {
        return resolver;
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
        ClientMemberResolver that = (ClientMemberResolver) o;
        return resolver.equals(that.resolver);
    }

    /**
     * Returns a hash code value for the object.
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(resolver);
    }

    public static class Builder implements SmithyBuilder<ClientMemberResolver> {
        private Symbol resolver;

        public Builder resolver(Symbol resolver) {
            this.resolver = resolver;
            return this;
        }

        @Override
        public ClientMemberResolver build() {
            return new ClientMemberResolver(this);
        }
    }
}
