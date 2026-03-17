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
import java.util.Optional;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Represents a member field on a client struct.
 */
public class ClientMember implements ToSmithyBuilder<ClientMember> {
    private final String name;
    private final Symbol type;
    private final String documentation;

    public ClientMember(Builder builder) {
        this.name = Objects.requireNonNull(builder.name);
        this.type = Objects.requireNonNull(builder.type);
        this.documentation = builder.documentation;
    }

    /**
     * @return Returns the name of the client member field.
     */
    public String getName() {
        return name;
    }

    /**
     * @return Returns the type Symbol for the member field.
     */
    public Symbol getType() {
        return type;
    }

    /**
     * @return Gets the optional documentation for the member field.
     */
    public Optional<String> getDocumentation() {
        return Optional.ofNullable(documentation);
    }

    @Override
    public SmithyBuilder<ClientMember> toBuilder() {
        return builder().type(type).name(name).documentation(documentation);
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
        ClientMember that = (ClientMember) o;
        return Objects.equals(getName(), that.getName())
                && Objects.equals(getType(), that.getType())
                && Objects.equals(getDocumentation(), that.getDocumentation());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getType(), getDocumentation());
    }

    /**
     * Builds a ClientMember.
     */
    public static class Builder implements SmithyBuilder<ClientMember> {
        private String name;
        private Symbol type;
        private String documentation;

        @Override
        public ClientMember build() {
            return new ClientMember(this);
        }

        /**
         * Set the name of the member field on client.
         *
         * @param name is the name of the field on the client.
         * @return Returns the builder.
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the type of the client field.
         *
         * @param type A Symbol representing the type of the client field.
         * @return Returns the builder.
         */
        public Builder type(Symbol type) {
            this.type = type;
            return this;
        }

        /**
         * Sets the documentation for the client field.
         *
         * @param documentation The documentation for the client field.
         * @return Returns the builder.
         */
        public Builder documentation(String documentation) {
            this.documentation = documentation;
            return this;
        }
    }
}
