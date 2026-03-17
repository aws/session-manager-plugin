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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_TRACING;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.GoEventStreamIndex;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.EventStreamInfo;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;

public abstract class HttpRpcProtocolGenerator implements ProtocolGenerator {

    private final Set<Shape> serializingDocumentShapes = new TreeSet<>();
    private final Set<Shape> deserializingDocumentShapes = new TreeSet<>();
    private final Set<StructureShape> deserializingErrorShapes = new TreeSet<>();

    /**
     * Creates an Http RPC protocol generator.
     */
    public HttpRpcProtocolGenerator() {
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    /**
     * Gets the content-type for a request body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    @Override
    public void generateSharedSerializerComponents(GenerationContext context) {
        serializingDocumentShapes.addAll(ProtocolUtils.resolveRequiredDocumentShapeSerde(
                context.getModel(), serializingDocumentShapes));
        generateDocumentBodyShapeSerializers(context, serializingDocumentShapes);
    }

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code serializeInputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate serialization for.
     */
    protected abstract void generateDocumentBodyShapeSerializers(GenerationContext context, Set<Shape> shapes);

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        Model model = context.getModel();

        TopDownIndex topDownIndex = TopDownIndex.of(model);

        for (OperationShape operation : topDownIndex.getContainedOperations(context.getService())) {
            generateOperationSerializer(context, operation);
        }

        GoEventStreamIndex goEventStreamIndex = GoEventStreamIndex.of(context.getModel());

        goEventStreamIndex.getInputEventStreams(context.getService()).ifPresent(shapeIdSetMap ->
                shapeIdSetMap.forEach((shapeId, eventStreamInfos) -> {
                    generateEventStreamSerializers(context, context.getModel().expectShape(shapeId, UnionShape.class),
                            eventStreamInfos);
                }));
    }

    /**
     * Generate the event stream serializers for the given event stream target and associated operations.
     *
     * @param context          the generation context
     * @param eventUnion       the event stream union
     * @param eventStreamInfos the event stream infos
     */
    protected abstract void generateEventStreamSerializers(
            GenerationContext context,
            UnionShape eventUnion,
            Set<EventStreamInfo> eventStreamInfos
    );

    /**
     * Generate the event stream deserializers for the given event stream target and asscioated operations.
     *
     * @param context          the generation context
     * @param eventUnion       the event stream union
     * @param eventStreamInfos the event stream infos
     */
    protected abstract void generateEventStreamDeserializers(
            GenerationContext context,
            UnionShape eventUnion,
            Set<EventStreamInfo> eventStreamInfos
    );

    private void generateOperationSerializer(GenerationContext context, OperationShape operation) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        ServiceShape service = context.getService();
        Shape inputShape = ProtocolUtils.expectInput(model, operation);
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        ApplicationProtocol applicationProtocol = getApplicationProtocol();
        Symbol requestType = applicationProtocol.getRequestType();
        GoWriter writer = context.getWriter().get();

        writer.pushState();

        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createSerializeStepMiddleware(
                ProtocolGenerator.getSerializeMiddlewareName(operation.getId(), service, getProtocolName()),
                ProtocolUtils.OPERATION_SERIALIZER_MIDDLEWARE_ID);

        middleware.writeMiddleware(context.getWriter().get(), (generator, w) -> {
            writer.addUseImports(SmithyGoDependency.SMITHY);
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_BINDING);

            writer.write(goTemplate("""
                    _, span := $T(ctx, "OperationSerializer")
                    endTimer := startMetricTimer(ctx, "client.call.serialization_duration")
                    defer endTimer()
                    defer span.End()
                    """, SMITHY_TRACING.func("StartSpan")));

            // TODO: refactor the http binding encoder to be split up into its component parts
            // This would allow most of this shared code to be split off into its own function
            // to reduce duplication, and potentially allowing it to be a static function.
            // For example, a HeaderBag type could handle all the headers.
            // Cast the input request to the transport request type and check for errors.
            writer.write("request, ok := in.Request.($P)", requestType);
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                             + "&smithy.SerializationError{Err: fmt.Errorf(\"unknown transport type %T\", in.Request)}"
                );
            }).write("");

            // Cast the input parameters to the operation request type and check for errors.
            writer.write("input, ok := in.Parameters.($P)", inputSymbol);
            writer.write("_ = input");
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                             + "&smithy.SerializationError{Err: fmt.Errorf(\"unknown input parameters type %T\","
                             + " in.Parameters)}");
            }).write("");

            writer.putContext("opPath", getOperationPath(context, operation));
            writer.putContext("pathJoin", SymbolUtils.createValueSymbolBuilder("Join",
                    SmithyGoDependency.PATH).build());
            writer.write("""
                         operationPath := $opPath:S
                         if len(request.Request.URL.Path) == 0 {
                             request.Request.URL.Path = operationPath
                         } else {
                             request.Request.URL.Path = $pathJoin:T(request.Request.URL.Path, operationPath)
                             if request.Request.URL.Path != "/" && operationPath[len(operationPath)-1] == '/' {
                                 request.Request.URL.Path += "/"
                             }
                         }""");
            writer.write("request.Request.Method = \"POST\"");
            writer.write("httpBindingEncoder, err := httpbinding.NewEncoder(request.URL.Path, "
                         + "request.URL.RawQuery, request.Header)");
            writer.openBlock("if err != nil {", "}", () -> {
                writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
            });

            Optional<EventStreamInfo> optionalEventStreamInfo = EventStreamIndex.of(model).getInputInfo(operation);
            // Skip and Handle Input Event Stream Setup Separately
            if (optionalEventStreamInfo.isEmpty()) {
                writeRequestHeaders(context, operation, writer);
                writer.write("");
                // delegate the setup and usage of the document serializer function for the protocol
                serializeInputDocument(context, operation);
                // Skipping calling serializer method for the input shape is responsibility of the
                // serializeInputDocument implementation.
                if (!CodegenUtils.isStubSynthetic(ProtocolUtils.expectInput(context.getModel(), operation))) {
                    serializingDocumentShapes.add(ProtocolUtils.expectInput(model, operation));
                }
            } else {
                writeDefaultHeaders(context, operation, writer);
                writeOperationSerializerMiddlewareEventStreamSetup(context, optionalEventStreamInfo.get());
            }

            writer.write("");

            writer.openBlock("if request.Request, err = httpBindingEncoder.Encode(request.Request); err != nil {",
                    "}", () -> {
                        writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
                    });
            // Ensure the request value is updated if modified for a document.
            writer.write("in.Request = request");

            writer.write("");
            writer.write("endTimer()");
            writer.write("span.End()");
            writer.write("return next.$L(ctx, in)", generator.getHandleMethodName());
        });

        writer.popState();
    }

    protected abstract void writeOperationSerializerMiddlewareEventStreamSetup(
            GenerationContext context,
            EventStreamInfo eventStreamInfo
    );

    private void writeRequestHeaders(GenerationContext context, OperationShape operation, GoWriter writer) {
        writer.write("httpBindingEncoder.SetHeader(\"Content-Type\").String($S)", getDocumentContentType());
        writeDefaultHeaders(context, operation, writer);
    }

    /**
     * Writes any additional HTTP headers required by the protocol implementation.
     *
     * <p>Four parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code request: smithyhttp.HTTPRequest}: the HTTP request that will be sent.</li>
     *   <li>{@code httpBindingEncoder: httpbinding.Encoder}: the HTTP encoder to use to set the headers.</li>
     *   <li>{@code ctx: context.Context}: a type containing context and tools for type serde.</li>
     * </ul>
     *
     * @param context   The generation context.
     * @param operation The operation being generated.
     * @param writer    The writer to use.
     */
    protected void writeDefaultHeaders(GenerationContext context, OperationShape operation, GoWriter writer) {
    }

    /**
     * Provides the request path for the operation.
     *
     * @param context   The generation context.
     * @param operation The operation being generated.
     * @return The path to send HTTP requests to.
     */
    protected abstract String getOperationPath(GenerationContext context, OperationShape operation);

    /**
     * Generate the document serializer logic for the serializer middleware body.
     *
     * <p>Three parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code request: smithyhttp.HTTPRequest}: the HTTP request that will be sent.</li>
     *   <li>{@code ctx: context.Context}: a type containing context and tools for type serde.</li>
     * </ul>
     *
     * @param context   The generation context.
     * @param operation The operation to serialize for.
     */
    protected abstract void serializeInputDocument(GenerationContext context, OperationShape operation);

    @Override
    public void generateSharedDeserializerComponents(GenerationContext context) {
        deserializingErrorShapes.forEach(error -> generateErrorDeserializer(context, error));
        deserializingDocumentShapes.addAll(ProtocolUtils.resolveRequiredDocumentShapeSerde(
                context.getModel(), deserializingDocumentShapes));
        generateDocumentBodyShapeDeserializers(context, deserializingDocumentShapes);
    }

    /**
     * Generated deserialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code deserializeOutputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentBodyShapeDeserializers(GenerationContext context, Set<Shape> shapes);

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);
        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            generateOperationDeserializer(context, operation);
        }

        GoEventStreamIndex goEventStreamIndex = GoEventStreamIndex.of(context.getModel());

        goEventStreamIndex.getOutputEventStreams(context.getService()).ifPresent(shapeIdSetMap ->
                shapeIdSetMap.forEach((shapeId, eventStreamInfos) -> {
                    generateEventStreamDeserializers(context, context.getModel().expectShape(shapeId, UnionShape.class),
                            eventStreamInfos);
                }));
    }

    private void generateOperationDeserializer(GenerationContext context, OperationShape operation) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter().get();
        ServiceShape service = context.getService();
        StructureShape outputShape = ProtocolUtils.expectOutput(context.getModel(), operation);
        Symbol outputSymbol = symbolProvider.toSymbol(outputShape);
        ApplicationProtocol applicationProtocol = getApplicationProtocol();
        Symbol responseType = applicationProtocol.getResponseType();
        String errorFunctionName = ProtocolGenerator.getOperationErrorDeserFunctionName(
                operation, context.getService(), context.getProtocolName());

        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createDeserializeStepMiddleware(
                ProtocolGenerator.getDeserializeMiddlewareName(operation.getId(), service, getProtocolName()),
                ProtocolUtils.OPERATION_DESERIALIZER_MIDDLEWARE_ID);

        middleware.writeMiddleware(writer, (generator, w) -> {
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.addUseImports(SmithyGoDependency.SMITHY);

            writer.write("out, metadata, err = next.$L(ctx, in)", generator.getHandleMethodName());
            writer.write("if err != nil { return out, metadata, err }");
            writer.write("");

            writer.write(goTemplate("""
                    _, span := $T(ctx, "OperationDeserializer")
                    endTimer := startMetricTimer(ctx, "client.call.deserialization_duration")
                    defer endTimer()
                    defer span.End()
                    """, SMITHY_TRACING.func("StartSpan")));

            writer.write("response, ok := out.RawResponse.($P)", responseType);
            writer.openBlock("if !ok {", "}", () -> {
                writer.write(String.format("return out, metadata, &smithy.DeserializationError{Err: %s}",
                        "fmt.Errorf(\"unknown transport type %T\", out.RawResponse)"));
            });
            writer.write("");

            writer.openBlock("if response.StatusCode < 200 || response.StatusCode >= 300 {", "}", () -> {
                writer.write("return out, metadata, $L(response, &metadata)", errorFunctionName);
            });

            writer.write("output := &$T{}", outputSymbol);
            writer.write("out.Result = output");
            writer.write("");

            Optional<EventStreamInfo> streamInfoOptional = EventStreamIndex.of(model).getOutputInfo(operation);

            // Discard without deserializing the response if the input shape is a stubbed synthetic clone
            // without an archetype.
            if (CodegenUtils.isStubSynthetic(ProtocolUtils.expectOutput(model, operation))
                && streamInfoOptional.isEmpty()) {
                writer.addUseImports(SmithyGoDependency.IOUTIL);
                writer.openBlock("if _, err = io.Copy(ioutil.Discard, response.Body); err != nil {", "}",
                        () -> {
                            writer.openBlock("return out, metadata, &smithy.DeserializationError{", "}", () -> {
                                writer.write("Err: fmt.Errorf(\"failed to discard response body, %w\", err),");
                            });
                        });
            } else if (streamInfoOptional.isEmpty()) {
                deserializeOutputDocument(context, operation);
                deserializingDocumentShapes.add(ProtocolUtils.expectOutput(model, operation));
            }
            writer.write("");

            writer.write("return out, metadata, err");
        });
        writer.write("");

        Set<StructureShape> errorShapes = generateErrorShapes(context, operation, responseType);
        deserializingErrorShapes.addAll(errorShapes);
        deserializingDocumentShapes.addAll(errorShapes);
    }

    protected Set<StructureShape> generateErrorShapes(
        GenerationContext context, OperationShape operation, Symbol responseType) {
        return HttpProtocolGeneratorUtils.generateErrorDispatcher(
                context, operation, responseType, this::writeErrorMessageCodeDeserializer,
                this::getOperationErrors);
    }

    /**
     * Generate the document deserializer logic for the deserializer middleware body.
     *
     * <p>Three parameters will be available in scope:
     * <ul>
     *   <li>{@code output: <T>}: the type generated for the operation's output.</li>
     *   <li>{@code response: smithyhttp.HTTPRequest}: the HTTP response received.</li>
     *   <li>{@code ctx: context.Context}: a type containing context and tools for type serde.</li>
     * </ul>
     *
     * @param context   The generation context
     * @param operation The operation to deserialize for.
     */
    protected abstract void deserializeOutputDocument(GenerationContext context, OperationShape operation);

    private void generateErrorDeserializer(GenerationContext context, StructureShape shape) {
        GoWriter writer = context.getWriter().get();
        String functionName = ProtocolGenerator.getErrorDeserFunctionName(
                shape, context.getService(), context.getProtocolName());
        Symbol responseType = getApplicationProtocol().getResponseType();

        writer.addUseImports(SmithyGoDependency.BYTES);
        writer.openBlock("func $L(response $P, errorBody *bytes.Reader) error {", "}",
                functionName, responseType, () -> deserializeError(context, shape));
        writer.write("");
    }

    /**
     * Writes a function body that deserializes the given error.
     *
     * <p>Two parameters will be available in scope:
     * <ul>
     *   <li>{@code response: smithyhttp.HTTPResponse}: the HTTP response received.</li>
     *   <li>{@code errorBody: bytes.BytesReader}: the HTTP response body.</li>
     * </ul>
     *
     * @param context The generation context.
     * @param shape   The error shape.
     */
    protected abstract void deserializeError(GenerationContext context, StructureShape shape);

    /**
     * Writes a code snippet that gets the error code and error message.
     *
     * <p>Four parameters will be available in scope:
     * <ul>
     *   <li>{@code response: smithyhttp.HTTPResponse}: the HTTP response received.</li>
     *   <li>{@code errorBody: bytes.BytesReader}: the HTTP response body.</li>
     *   <li>{@code errorMessage: string}: the error message initialized to a default value.</li>
     *   <li>{@code errorCode: string}: the error code initialized to a default value.</li>
     * </ul>
     *
     * @param context the generation context.
     */
    protected abstract void writeErrorMessageCodeDeserializer(GenerationContext context);
}
