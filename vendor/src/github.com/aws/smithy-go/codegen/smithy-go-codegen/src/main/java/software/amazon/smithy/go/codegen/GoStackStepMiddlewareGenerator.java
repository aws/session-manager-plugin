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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.function.BiConsumer;
import software.amazon.smithy.codegen.core.Symbol;

/**
 * Helper for generating stack step middleware.
 */
public final class GoStackStepMiddlewareGenerator {
    private static final Symbol CONTEXT_TYPE = SymbolUtils.createValueSymbolBuilder(
            "Context", SmithyGoDependency.CONTEXT).build();
    private static final Symbol METADATA_TYPE = SymbolUtils.createValueSymbolBuilder(
            "Metadata", SmithyGoDependency.SMITHY_MIDDLEWARE).build();

    private final Symbol middlewareSymbol;
    private final MiddlewareIdentifier middlewareId;
    private final String handleMethodName;
    private final Symbol inputType;
    private final Symbol outputType;
    private final Symbol handlerType;

    /**
     * Creates a new middleware generator with the given builder definition.
     *
     * @param builder the builder to create the generator with.
     */
    public GoStackStepMiddlewareGenerator(Builder builder) {
        this.middlewareSymbol = SymbolUtils.createPointableSymbolBuilder(builder.name).build();
        this.middlewareId = builder.id;
        this.handleMethodName = builder.handleMethodName;
        this.inputType = builder.inputType;
        this.outputType = builder.outputType;
        this.handlerType = builder.handlerType;
    }

    /**
     * Create a new InitializeStep middleware generator with the provided type name.
     *
     * @param name is the type name to identify the middleware.
     * @param id   the unique ID for the middleware.
     * @return the middleware generator.
     */
    public static GoStackStepMiddlewareGenerator createInitializeStepMiddleware(String name, MiddlewareIdentifier id) {
        return createMiddleware(name,
                id,
                "HandleInitialize",
                SymbolUtils.createValueSymbolBuilder("InitializeInput", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder("InitializeOutput", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder("InitializeHandler", SmithyGoDependency.SMITHY_MIDDLEWARE)
                        .build());
    }

    /**
     * Create an inline Initialize func.
     *
     * @param body is the function body.
     * @return the generated middleware func.
     */
    public static Writable generateInitializeMiddlewareFunc(Writable body) {
        return goTemplate("""
                func(ctx $T, in $T, next $T) (
                    out $T, metadata $T, err error,
                ) {
                    $W
                }
                """,
                GoStdlibTypes.Context.Context,
                SmithyGoTypes.Middleware.InitializeInput,
                SmithyGoTypes.Middleware.InitializeHandler,
                SmithyGoTypes.Middleware.InitializeOutput,
                SmithyGoTypes.Middleware.Metadata,
                body);
    }

    /**
     * Create a new BuildStep middleware generator with the provided type name.
     *
     * @param name is the type name to identify the middleware.
     * @param id   the unique ID for the middleware.
     * @return the middleware generator.
     */
    public static GoStackStepMiddlewareGenerator createBuildStepMiddleware(String name, MiddlewareIdentifier id) {
        return createMiddleware(name,
                id,
                "HandleBuild",
                SymbolUtils.createValueSymbolBuilder("BuildInput", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder("BuildOutput", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder("BuildHandler", SmithyGoDependency.SMITHY_MIDDLEWARE).build());
    }

    /**
     * Create a new SerializeStep middleware generator with the provided type name.
     *
     * @param name is the type name to identify the middleware.
     * @param id   the unique ID for the middleware.
     * @return the middleware generator.
     */
    public static GoStackStepMiddlewareGenerator createSerializeStepMiddleware(String name, MiddlewareIdentifier id) {
        return createMiddleware(name,
                id,
                "HandleSerialize",
                SymbolUtils.createValueSymbolBuilder("SerializeInput", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder("SerializeOutput", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder("SerializeHandler", SmithyGoDependency.SMITHY_MIDDLEWARE).build());
    }

    /**
     * Create a new FinalizeStep middleware generator with the provided type name.
     *
     * @param name is the type name to identify the middleware.
     * @param id   the unique ID for the middleware.
     * @return the middleware generator.
     */
    public static GoStackStepMiddlewareGenerator createFinalizeStepMiddleware(String name, MiddlewareIdentifier id) {
        return createMiddleware(name,
                id,
                "HandleFinalize",
                SmithyGoTypes.Middleware.FinalizeInput,
                SmithyGoTypes.Middleware.FinalizeOutput,
                SmithyGoTypes.Middleware.FinalizeHandler);
    }

    /**
     * Create an inline Finalize func.
     *
     * @param body is the function body.
     * @return the generated middleware func.
     */
    public static Writable generateFinalizeMiddlewareFunc(Writable body) {
        return goTemplate("""
                func(ctx $T, in $T, next $T) (
                    out $T, metadata $T, err error,
                ) {
                    $W
                }
                """,
                GoStdlibTypes.Context.Context,
                SmithyGoTypes.Middleware.FinalizeInput,
                SmithyGoTypes.Middleware.FinalizeHandler,
                SmithyGoTypes.Middleware.FinalizeOutput,
                SmithyGoTypes.Middleware.Metadata,
                body);
    }

    /**
     * Create a new DeserializeStep middleware generator with the provided type name.
     *
     * @param name is the type name to identify the middleware.
     * @param id   the unique ID for the middleware.
     * @return the middleware generator.
     */
    public static GoStackStepMiddlewareGenerator createDeserializeStepMiddleware(String name, MiddlewareIdentifier id) {
        return createMiddleware(name,
                id,
                "HandleDeserialize",
                SymbolUtils.createValueSymbolBuilder("DeserializeInput", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder("DeserializeOutput", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder("DeserializeHandler", SmithyGoDependency.SMITHY_MIDDLEWARE)
                        .build());
    }

    /**
     * Generates a new step middleware generator.
     *
     * @param name              the name of the middleware type.
     * @param id   the unique ID for the middleware.
     * @param handlerMethodName method name to be implemented.
     * @param inputType         the middleware input type.
     * @param outputType        the middleware output type.
     * @param handlerType       the next handler type.
     * @return the middleware generator.
     */
    public static GoStackStepMiddlewareGenerator createMiddleware(
            String name,
            MiddlewareIdentifier id,
            String handlerMethodName,
            Symbol inputType,
            Symbol outputType,
            Symbol handlerType
    ) {
        return builder()
                .name(name)
                .id(id)
                .handleMethodName(handlerMethodName)
                .inputType(inputType)
                .outputType(outputType)
                .handlerType(handlerType)
                .build();
    }


    /**
     * Writes the middleware definition to the provided writer.
     * See the writeMiddleware overloaded function signature for a more complete definition.
     *
     * @param writer              the writer to which the middleware definition will be written to.
     * @param handlerBodyConsumer is a consumer that will be call in the context of populating the handler definition.
     */
    public void writeMiddleware(
            GoWriter writer,
            BiConsumer<GoStackStepMiddlewareGenerator, GoWriter> handlerBodyConsumer
    ) {
        writeMiddleware(writer, handlerBodyConsumer, (m, w) -> {
        });
    }

    /**
     * Writes the middleware definition to the provided writer.
     * <p>
     * The following Go variables will be in scope of the handler body:
     * ctx - the Go standard library context.Context type.
     * in - the input for the given middleware type.
     * next - the next handler to be called.
     * out - the output for the given middleware type.
     * metadata - the smithy middleware.Metadata type.
     * err - the error interface type.
     *
     * @param writer              the writer to which the middleware definition will be written to.
     * @param handlerBodyConsumer is a consumer that will be called in the context of populating the handler definition.
     * @param fieldConsumer       is a consumer that will be called in the context of populating the struct members.
     */
    public void writeMiddleware(
            GoWriter writer,
            BiConsumer<GoStackStepMiddlewareGenerator, GoWriter> handlerBodyConsumer,
            BiConsumer<GoStackStepMiddlewareGenerator, GoWriter> fieldConsumer
    ) {
        writer.addUseImports(CONTEXT_TYPE);
        writer.addUseImports(METADATA_TYPE);
        writer.addUseImports(inputType);
        writer.addUseImports(outputType);
        writer.addUseImports(handlerType);

        // generate the structure type definition for the middleware
        writer.openBlock("type $L struct {", "}", middlewareSymbol, () -> {
            fieldConsumer.accept(this, writer);
        });

        writer.write("");

        // each middleware step has to implement the ID function and return a unique string to identify itself with
        // here we return the name of the type
        writer.openBlock("func ($P) ID() string {", "}", middlewareSymbol, () -> {
            writer.writeInline("return ");
            middlewareId.writeInline(writer);
            writer.write("");
        });

        writer.write("");

        // each middleware must implement their given handlerMethodName in order to satisfy the interface for
        // their respective step.
        writer.openBlock("func (m $P) $L(ctx $T, in $T, next $T) (\n"
                        + "\tout $T, metadata $T, err error,\n"
                        + ") {", "}",
                new Object[]{
                        middlewareSymbol, handleMethodName, CONTEXT_TYPE, inputType, handlerType, outputType,
                        METADATA_TYPE,
                },
                () -> {
                    handlerBodyConsumer.accept(this, writer);
                });
    }

    /**
     * Creates a Writable which renders the middleware.
     * @param body A Writable that renders the middleware body.
     * @param fields A Writable that renders the middleware struct's fields.
     * @return the writable.
     */
    public Writable asWritable(Writable body, Writable fields) {
        return writer -> writeMiddleware(
                writer,
                (generator, bodyWriter) -> bodyWriter.write("$W", body),
                (generator, fieldWriter) -> fieldWriter.write("$W", fields)
        );
    }

    /**
     * Returns a new middleware generator builder.
     *
     * @return the middleware generator builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the handle method name.
     *
     * @return handler method name.
     */
    public String getHandleMethodName() {
        return handleMethodName;
    }

    /**
     * Get the middleware type symbol.
     *
     * @return Symbol for the middleware type.
     */
    public Symbol getMiddlewareSymbol() {
        return middlewareSymbol;
    }

    /**
     * Get the id of the middleware.
     *
     * @return id for the middleware.
     */
    public MiddlewareIdentifier getMiddlewareId() {
        return middlewareId;
    }

    /**
     * Get the input type symbol reference.
     *
     * @return the input type symbol reference.
     */
    public Symbol getInputType() {
        return inputType;
    }

    /**
     * Get the output type symbol reference.
     *
     * @return the output type symbol reference.
     */
    public Symbol getOutputType() {
        return outputType;
    }

    /**
     * Get the handler type symbol reference.
     *
     * @return the handler type symbol reference.
     */
    public Symbol getHandlerType() {
        return handlerType;
    }

    /**
     * Get the context type symbol.
     *
     * @return the context type symbol.
     */
    public static Symbol getContextType() {
        return CONTEXT_TYPE;
    }

    /**
     * Get the middleware metadata type symbol.
     *
     * @return the middleware metadata type symbol.
     */
    public static Symbol getMiddlewareMetadataType() {
        return METADATA_TYPE;
    }

    /**
     * Builds a {@link GoStackStepMiddlewareGenerator}.
     */
    public static class Builder {
        private String name;
        private MiddlewareIdentifier id;
        private String handleMethodName;
        private Symbol inputType;
        private Symbol outputType;
        private Symbol handlerType;

        /**
         * Builds the middleware generator.
         *
         * @return the middleware generator.
         */
        public GoStackStepMiddlewareGenerator build() {
            return new GoStackStepMiddlewareGenerator(this);
        }

        /**
         * Sets the handler method name.
         *
         * @param handleMethodName the middleware handler method name to implement.
         * @return the builder.
         */
        public Builder handleMethodName(String handleMethodName) {
            this.handleMethodName = handleMethodName;
            return this;
        }

        /**
         * Set the name of the middleware type to be generated.
         *
         * @param name the name of the middleware type.
         * @return the builder.
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Set the id for the middleware to be generated.
         *
         * @param id the middleware stack identifier.
         * @return the builder.
         */
        public Builder id(MiddlewareIdentifier id) {
            this.id = id;
            return this;
        }

        /**
         * Set the input type symbol reference for the middleware.
         *
         * @param inputType the symbol reference to the input type.
         * @return the builder.
         */
        public Builder inputType(Symbol inputType) {
            this.inputType = inputType;
            return this;
        }

        /**
         * Set the output type symbol reference for the middleware.
         *
         * @param outputType the symbol reference to the output type.
         * @return the builder.
         */
        public Builder outputType(Symbol outputType) {
            this.outputType = outputType;
            return this;
        }

        /**
         * Set the handler type symbol reference for the middleware.
         *
         * @param handlerType the symbol reference to the handler type.
         * @return the builder.
         */
        public Builder handlerType(Symbol handlerType) {
            this.handlerType = handlerType;
            return this;
        }
    }
}
