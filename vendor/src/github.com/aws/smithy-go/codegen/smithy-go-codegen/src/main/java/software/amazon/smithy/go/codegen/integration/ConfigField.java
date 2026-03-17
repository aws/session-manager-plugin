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
 * Represents a config field on a client config struct.
 */
public class ConfigField implements ToSmithyBuilder<ConfigField> {
    private final String name;
    private final Symbol type;
    private final String documentation;
    private final String deprecated;
    private final Boolean withHelper;

    public ConfigField(Builder builder) {
        this.name = Objects.requireNonNull(builder.name);
        this.type = Objects.requireNonNull(builder.type);
        this.documentation = builder.documentation;
        this.deprecated = builder.deprecated;
        this.withHelper = builder.withHelper;
    }

    /**
     * @return Returns the name of the config field.
     */
    public String getName() {
        return name;
    }

    /**
     * @return Returns the type Symbol for the field.
     */
    public Symbol getType() {
        return type;
    }

    /**
     * @return Returns if the config option should have a with helper or not.
     */
    public Boolean getWithHelper() {
        return withHelper;
    }

    /**
     * @return Gets the optional documentation for the field.
     */
    public Optional<String> getDocumentation() {
        return Optional.ofNullable(documentation);
    }

    /**
     * @return The optional deprecation documentation for the field.
     */
    public Optional<String> getDeprecated() {
        return Optional.ofNullable(deprecated);
    }

    /**
     * @return Returns if the config option is deprecated.
     */
    public Boolean isDeprecated() {
        return getDeprecated().isPresent();
    }

    @Override
    public SmithyBuilder<ConfigField> toBuilder() {
        return builder().type(type).name(name).documentation(documentation).withHelper(withHelper);
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
        ConfigField that = (ConfigField) o;
        return Objects.equals(getName(), that.getName())
                && Objects.equals(getType(), that.getType())
                && Objects.equals(getDocumentation(), that.getDocumentation())
                && Objects.equals(getWithHelper(), that.getWithHelper());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getType(), getDocumentation(), getWithHelper());
    }

    /**
     * Builds a ConfigField.
     */
    public static class Builder implements SmithyBuilder<ConfigField> {
        private String name;
        private Symbol type;
        private String documentation;
        private String deprecated;
        private Boolean withHelper = false;

        @Override
        public ConfigField build() {
            return new ConfigField(this);
        }

        /**
         * Set the name of the config field.
         *
         * @param name the name of the config field.
         * @return Returns the builder.
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the type of the config field.
         *
         * @param type A Symbol representing the type of the config field.
         * @return Returns the builder.
         */
        public Builder type(Symbol type) {
            this.type = type;
            return this;
        }

        /**
         * Sets the documentation for the config field.
         *
         * @param documentation The documentation for the config field.
         * @return Returns the builder.
         */
        public Builder documentation(String documentation) {
            this.documentation = documentation;
            return this;
        }

        /**
         * Sets the configuration field as deprecated with the provided message.
         *
         * @param deprecated The deprecation message for the config field.
         * @return Returns the builder.
         */
        public Builder deprecated(String deprecated) {
            this.deprecated = deprecated;
            return this;
        }

        /**
         * Sets if the client include a With__ helper for this config option.
         *
         * @param withHelper if with helper should be generated or not.
         * @return Returns the builder.
         */
        public Builder withHelper(Boolean withHelper) {
            this.withHelper = withHelper;
            return this;
        }

        /**
         * Sets that the client will  include a With__ helper for the client option.
         *
         * @return Returns the builder.
         */
        public Builder withHelper() {
            this.withHelper = true;
            return this;
        }
    }
}
