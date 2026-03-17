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

import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

public class MiddlewareRegistrar implements ToSmithyBuilder<MiddlewareRegistrar> {
    private final Symbol resolvedFunction;
    private final Collection<Symbol> functionArguments;
    private final String inlineRegisterMiddlewareStatement;
    private final Symbol inlineRegisterMiddlewarePosition;

    public MiddlewareRegistrar(Builder builder) {
        this.resolvedFunction = builder.resolvedFunction;
        this.functionArguments = builder.functionArguments;
        this.inlineRegisterMiddlewareStatement = builder.inlineRegisterMiddlewareStatement;
        this.inlineRegisterMiddlewarePosition = builder.inlineRegisterMiddlewarePosition;
    }

    /**
     * @return symbol that resolves to a function.
     */
    public Symbol getResolvedFunction() {
        return resolvedFunction;
    }

    /**
     * @return collection of symbols denoting the arguments of the resolved function.
     */
    public Collection<Symbol> getFunctionArguments() {
        return functionArguments;
    }

    /**
     * @return string denoting inline middleware registration in the stack
     */
    public String getInlineRegisterMiddlewareStatement() {
        return inlineRegisterMiddlewareStatement;
    }

    /**
     * @return symbol used to define the middleware position in the stack
     */
    public Symbol getInlineRegisterMiddlewarePosition() {
        return inlineRegisterMiddlewarePosition;
    }

    @Override
    public SmithyBuilder<MiddlewareRegistrar> toBuilder() {
        return builder().functionArguments(functionArguments).resolvedFunction(resolvedFunction);
    }

    public static MiddlewareRegistrar.Builder builder() {
        return new MiddlewareRegistrar.Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MiddlewareRegistrar that = (MiddlewareRegistrar) o;
        return Objects.equals(getResolvedFunction(), that.getResolvedFunction())
                && Objects.equals(getFunctionArguments(), that.getFunctionArguments());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getResolvedFunction(), getFunctionArguments());
    }


    /**
     * Builds a MiddlewareRegistrar.
     */
    public static class Builder implements SmithyBuilder<MiddlewareRegistrar> {
        private Symbol resolvedFunction;
        private Collection<Symbol> functionArguments;
        private String inlineRegisterMiddlewareStatement;
        private Symbol inlineRegisterMiddlewarePosition;

        @Override
        public MiddlewareRegistrar build() {
            return new MiddlewareRegistrar(this);
        }

        /**
         * Set the name of the MiddlewareRegistrar function.
         *
         * @param resolvedFunction a symbol that resolves to the function .
         * @return Returns the builder.
         */
        public Builder resolvedFunction(Symbol resolvedFunction) {
            this.resolvedFunction = resolvedFunction;
            return this;
        }

        /**
         * Set the name of the MiddlewareRegistrar function.
         *
         * @param resolvedFunction the name of a package-local function in the generated client.
         * @return Returns the builder.
         */
        public Builder resolvedFunction(String resolvedFunction) {
            this.resolvedFunction = buildPackageSymbol(resolvedFunction);
            return this;
        }

        /**
         * Sets the function Arguments for the MiddlewareRegistrar function.
         *
         * @param functionArguments A collection of symbols representing the arguments
         *                          to the middleware register function.
         * @return Returns the builder.
         */
        public Builder functionArguments(Collection<Symbol> functionArguments) {
            this.functionArguments = new ArrayList<>(functionArguments);
            return this;
        }

        /**
         * Sets symbol that resolves to options as an argument for the resolved function.
         *
         * @return Returns the builder.
         */
        public Builder useClientOptions() {
            Collection<Symbol> args = new ArrayList<>();
            args.add(SymbolUtils.createValueSymbolBuilder("options").build());
            this.functionArguments = args;
            return this;
        }

        /**
         * Adds a middleware to the middleware stack at relative position of After.
         * @param stackStep Stack step.
         * @return Returns the Builder.
         */
        public Builder registerAfter(MiddlewareStackStep stackStep) {
            this.inlineRegisterMiddlewareStatement = String.format("%s.Add(", stackStep);
            this.inlineRegisterMiddlewarePosition = getMiddlewareAfterPositionSymbol();
            return this;
        }

        /**
         * Adds the middleware to the middleware stack at relative position of Before.
         * @param stackStep Stack step at which the middleware is to be register.
         * @return Returns the Builder.
         */
        public Builder registerBefore(MiddlewareStackStep stackStep) {
            this.inlineRegisterMiddlewareStatement = String.format("%s.Add(", stackStep);
            this.inlineRegisterMiddlewarePosition = getMiddlewareBeforePositionSymbol();
            return this;
        }

        private Symbol getMiddlewareAfterPositionSymbol() {
            return SymbolUtils.createValueSymbolBuilder("After",
                    SmithyGoDependency.SMITHY_MIDDLEWARE).build();
        }

        private Symbol getMiddlewareBeforePositionSymbol() {
            return SymbolUtils.createValueSymbolBuilder("Before",
                    SmithyGoDependency.SMITHY_MIDDLEWARE).build();
        }
    }

}
