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
import static software.amazon.smithy.go.codegen.integration.ProtocolUtils.requiresDocumentSerdeFunction;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.GoEventStreamIndex;
import software.amazon.smithy.go.codegen.EventStreamGenerator;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoValueAccessUtils;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.go.codegen.trait.NoSerializeTrait;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.EventStreamInfo;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.utils.OptionalUtils;


/**
 * Abstract implementation useful for all protocols that use HTTP bindings.
 */
public abstract class HttpBindingProtocolGenerator implements ProtocolGenerator {
    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private final boolean isErrorCodeInBody;
    private final Set<Shape> serializeDocumentBindingShapes = new TreeSet<>();
    private final Set<Shape> deserializeDocumentBindingShapes = new TreeSet<>();
    private final Set<StructureShape> deserializingErrorShapes = new TreeSet<>();
    private final Map<ShapeId, Symbol> deserializerOverrides = new HashMap<>();

    /**
     * Creates a Http binding protocol generator.
     *
     * @param isErrorCodeInBody A boolean that indicates if the error code for the implementing protocol is located in
     *                          the error response body, meaning this generator will parse the body before attempting to
     *                          load an error code.
     */
    public HttpBindingProtocolGenerator(boolean isErrorCodeInBody) {
        this.isErrorCodeInBody = isErrorCodeInBody;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    @Override
    public void generateSharedSerializerComponents(GenerationContext context) {
        serializeDocumentBindingShapes.addAll(ProtocolUtils.resolveRequiredDocumentShapeSerde(
                context.getModel(), serializeDocumentBindingShapes));
        generateDocumentBodyShapeSerializers(context, serializeDocumentBindingShapes);
    }

    /**
     * Get the operations with HTTP Bindings.
     *
     * @param context the generation context
     * @return the list of operation shapes
     */
    public Set<OperationShape> getHttpBindingOperations(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>();
        for (OperationShape operation : topDownIndex.getContainedOperations(context.getService())) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> containedOperations.add(operation),
                    () -> LOGGER.warning(String.format(
                            "Unable to fetch %s protocol request bindings for %s because it does not have an "
                                    + "http binding trait", getProtocol(), operation.getId()))
            );
        }
        return containedOperations;
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        Set<OperationShape> operations = getHttpBindingOperations(context);

        for (OperationShape operation : operations) {
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

    /**
     * Gets the default serde format for timestamps.
     *
     * @return Returns the default format.
     */
    protected abstract Format getDocumentTimestampFormat();

    /**
     * Gets the default content-type when a document is synthesized in the body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    private void generateOperationSerializer(GenerationContext context, OperationShape operation) {
        generateOperationSerializerMiddleware(context, operation);
        generateOperationHttpBindingSerializer(context, operation);

        Optional<EventStreamInfo> streamInfo = EventStreamIndex.of(context.getModel()).getInputInfo(operation);

        if (!CodegenUtils.isStubSynthetic(ProtocolUtils.expectInput(context.getModel(), operation))
                && streamInfo.isEmpty()) {
            generateOperationDocumentSerializer(context, operation);
            addOperationDocumentShapeBindersForSerializer(context, operation);
        }
    }

    /**
     * Generates the operation document serializer function.
     *
     * @param context   the generation context
     * @param operation the operation shape being generated
     */
    protected abstract void generateOperationDocumentSerializer(GenerationContext context, OperationShape operation);

    /**
     * Adds the top-level shapes from the operation that bind to the body document that require serializer functions.
     *
     * @param context   the generator context
     * @param operation the operation to add document binders from
     */
    private void addOperationDocumentShapeBindersForSerializer(GenerationContext context, OperationShape operation) {
        Model model = context.getModel();

        // Walk and add members shapes to the list that will require serializer functions
        Collection<HttpBinding> bindings = HttpBindingIndex.of(model)
                .getRequestBindings(operation).values();

        for (HttpBinding binding : bindings) {
            MemberShape memberShape = binding.getMember();
            Shape targetShape = model.expectShape(memberShape.getTarget());

            // Check if the input shape has a members that target the document or payload and require serializers.
            // If an operation has an input event stream it will have seperate serializers generated.
            if (requiresDocumentSerdeFunction(targetShape)
                    && (binding.getLocation() == HttpBinding.Location.DOCUMENT
                    || binding.getLocation() == HttpBinding.Location.PAYLOAD)) {
                serializeDocumentBindingShapes.add(targetShape);
            }
        }
    }

    private void generateOperationSerializerMiddleware(GenerationContext context, OperationShape operation) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        ServiceShape service = context.getService();
        Shape inputShape = model.expectShape(operation.getInput()
                .orElseThrow(() -> new CodegenException("expect input shape for operation: " + operation.getId())));
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        ApplicationProtocol applicationProtocol = getApplicationProtocol();
        Symbol requestType = applicationProtocol.getRequestType();
        HttpTrait httpTrait = operation.expectTrait(HttpTrait.class);

        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createSerializeStepMiddleware(
                ProtocolGenerator.getSerializeMiddlewareName(operation.getId(), service, getProtocolName()),
                ProtocolUtils.OPERATION_SERIALIZER_MIDDLEWARE_ID);

        middleware.writeMiddleware(context.getWriter().get(), (generator, writer) -> {
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.addUseImports(SmithyGoDependency.SMITHY);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_BINDING);

            writer.write(goTemplate("""
                    _, span := $T(ctx, "OperationSerializer")
                    endTimer := startMetricTimer(ctx, "client.call.serialization_duration")
                    defer endTimer()
                    defer span.End()
                    """, SMITHY_TRACING.func("StartSpan")));

            // cast input request to smithy transport type, check for failures
            writer.write("request, ok := in.Request.($P)", requestType);
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                        + "&smithy.SerializationError{Err: fmt.Errorf(\"unknown transport type %T\", in.Request)}"
                );
            });
            writer.write("");

            // cast input parameters type to the input type of the operation
            writer.write("input, ok := in.Parameters.($P)", inputSymbol);
            writer.write("_ = input");
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                        + "&smithy.SerializationError{Err: fmt.Errorf(\"unknown input parameters type %T\","
                        + " in.Parameters)}");
            });

            writer.write("");
            writer.write("opPath, opQuery := httpbinding.SplitURI($S)", httpTrait.getUri());
            writer.write("request.URL.Path = smithyhttp.JoinPath(request.URL.Path, opPath)");
            writer.write("request.URL.RawQuery = smithyhttp.JoinRawQuery(request.URL.RawQuery, opQuery)");
            writer.write("request.Method = $S", httpTrait.getMethod());
            writer.write(
                """
                var restEncoder $P
                if request.URL.RawPath == "" {
                    restEncoder, err = $T(request.URL.Path, request.URL.RawQuery, request.Header)
                } else {
                    request.URL.RawPath = $T(request.URL.RawPath, opPath)
                    restEncoder, err = $T(request.URL.Path, request.URL.RawPath, request.URL.RawQuery, request.Header)
                }
                """,
                SymbolUtils.createPointableSymbolBuilder(
                    "Encoder", SmithyGoDependency.SMITHY_HTTP_BINDING).build(),
                SymbolUtils.createValueSymbolBuilder(
                    "NewEncoder", SmithyGoDependency.SMITHY_HTTP_BINDING).build(),
                SymbolUtils.createValueSymbolBuilder(
                    "JoinPath", SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build(),
                SymbolUtils.createValueSymbolBuilder(
                    "NewEncoderWithRawPath", SmithyGoDependency.SMITHY_HTTP_BINDING).build()
            );

            writer.openBlock("if err != nil {", "}", () -> {
                writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
            });
            writer.write("");

            // we only generate an operations http bindings function if there are bindings
            if (isOperationWithRestRequestBindings(model, operation)) {
                String serFunctionName = ProtocolGenerator.getOperationHttpBindingsSerFunctionName(
                        inputShape, service, getProtocolName());
                writer.openBlock("if err := $L(input, restEncoder); err != nil {", "}", serFunctionName, () -> {
                    writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
                });
                writer.write("");
            }

            // Don't consider serializing the body if the input shape is a stubbed synthetic clone, without an
            // archetype.
            if (!CodegenUtils.isStubSynthetic(ProtocolUtils.expectInput(model, operation))) {
                Optional<EventStreamInfo> eventStreamInfo = EventStreamIndex.of(model).getInputInfo(operation);
                // document bindings vs payload bindings vs event streams
                HttpBindingIndex httpBindingIndex = HttpBindingIndex.of(model);
                boolean hasDocumentBindings = httpBindingIndex
                        .getRequestBindings(operation, HttpBinding.Location.DOCUMENT)
                        .stream().anyMatch(httpBinding -> eventStreamInfo.map(streamInfo ->
                                !streamInfo.getEventStreamMember().equals(httpBinding.getMember())).orElse(true));

                Optional<HttpBinding> payloadBinding = httpBindingIndex.getRequestBindings(operation,
                        HttpBinding.Location.PAYLOAD).stream()
                        .filter(httpBinding -> eventStreamInfo.map(streamInfo ->
                                !streamInfo.getEventStreamMember().equals(httpBinding.getMember())).orElse(true))
                        .findFirst();

                if (eventStreamInfo.isPresent() && (hasDocumentBindings || payloadBinding.isPresent())) {
                    throw new CodegenException("HTTP Binding Protocol unexpected document or payload bindings with "
                            + "input event stream");
                }

                if (eventStreamInfo.isPresent()) {
                    writeOperationSerializerMiddlewareEventStreamSetup(context, eventStreamInfo.get());
                } else if (hasDocumentBindings) {
                    // delegate the setup and usage of the document serializer function for the protocol
                    writeMiddlewareDocumentSerializerDelegator(context, operation, generator);

                } else if (payloadBinding.isPresent()) {
                    // delegate the setup and usage of the payload serializer function for the protocol
                    MemberShape memberShape = payloadBinding.get().getMember();
                    writeMiddlewarePayloadSerializerDelegator(context, memberShape);
                }
                writer.write("");
            }

            // Serialize HTTP request with payload, if set.
            writer.openBlock("if request.Request, err = restEncoder.Encode(request.Request); err != nil {", "}", () -> {
                writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
            });
            writer.write("in.Request = request");
            writer.write("");

            writer.write("endTimer()");
            writer.write("span.End()");
            writer.write("return next.$L(ctx, in)", generator.getHandleMethodName());
        });
    }

    protected abstract void writeOperationSerializerMiddlewareEventStreamSetup(
            GenerationContext context,
            EventStreamInfo eventStreamInfo
    );

    // Generates operation deserializer middleware that delegates to appropriate deserializers for the error,
    // output shapes for the operation.
    private void generateOperationDeserializerMiddleware(GenerationContext context, OperationShape operation) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        ServiceShape service = context.getService();

        ApplicationProtocol applicationProtocol = getApplicationProtocol();
        Symbol responseType = applicationProtocol.getResponseType();
        GoWriter goWriter = context.getWriter().get();

        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createDeserializeStepMiddleware(
                ProtocolGenerator.getDeserializeMiddlewareName(operation.getId(), service, getProtocolName()),
                ProtocolUtils.OPERATION_DESERIALIZER_MIDDLEWARE_ID);

        String errorFunctionName = ProtocolGenerator.getOperationErrorDeserFunctionName(
                operation, service, context.getProtocolName());

        middleware.writeMiddleware(goWriter, (generator, writer) -> {
            writer.addUseImports(SmithyGoDependency.FMT);

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
                writer.addUseImports(SmithyGoDependency.SMITHY);
                writer.write(String.format("return out, metadata, &smithy.DeserializationError{Err: %s}",
                        "fmt.Errorf(\"unknown transport type %T\", out.RawResponse)"));
            });
            writer.write("");

            writer.openBlock("if response.StatusCode < 200 || response.StatusCode >= 300 {", "}", () -> {
                writer.write("return out, metadata, $L(response, &metadata)", errorFunctionName);
            });

            Shape outputShape = model.expectShape(operation.getOutput()
                .orElseThrow(() -> new CodegenException("expect output shape for operation: " + operation.getId()))
            );
            Symbol outputSymbol;
            if (EventStreamGenerator.isV2EventStream(model, operation)) {
                outputSymbol = EventStreamGenerator.getEventStreamInitialReplyStructureSymbol(service, operation);
            } else {
                outputSymbol = symbolProvider.toSymbol(outputShape);
            }

            // initialize out.Result as output structure shape
            writer.write("output := &$T{}", outputSymbol);
            writer.write("out.Result = output");
            writer.write("");

            // Output shape HTTP binding middleware generation
            if (isShapeWithRestResponseBindings(model, operation)) {
                String deserFuncName = ProtocolGenerator.getOperationHttpBindingsDeserFunctionName(
                        outputShape, service, getProtocolName());

                writer.write("err= $L(output, response)", deserFuncName);
                writer.openBlock("if err != nil {", "}", () -> {
                    writer.addUseImports(SmithyGoDependency.SMITHY);
                    writer.write(String.format("return out, metadata, &smithy.DeserializationError{Err: %s}",
                            "fmt.Errorf(\"failed to decode response with invalid Http bindings, %w\", err)"));
                });
                writer.write("");
            }

            Optional<EventStreamInfo> streamInfoOptional = EventStreamIndex.of(model).getOutputInfo(operation);

            // Discard without deserializing the response if the input shape is a stubbed synthetic clone
            // without an archetype.
            if (CodegenUtils.isStubSynthetic(ProtocolUtils.expectOutput(model, operation))
                    && streamInfoOptional.isEmpty()) {
                writer.addUseImports(SmithyGoDependency.IOUTIL);
                writer.openBlock("if _, err = io.Copy(ioutil.Discard, response.Body); err != nil {", "}", () -> {
                    writer.openBlock("return out, metadata, &smithy.DeserializationError{", "}", () -> {
                        writer.write("Err: fmt.Errorf(\"failed to discard response body, %w\", err),");
                    });
                });
            } else {
                boolean hasBodyBinding = HttpBindingIndex.of(model).getResponseBindings(operation).values().stream()
                        .filter(httpBinding -> httpBinding.getLocation() == HttpBinding.Location.DOCUMENT
                                || httpBinding.getLocation() == HttpBinding.Location.PAYLOAD)
                        .anyMatch(httpBinding -> streamInfoOptional.map(esi -> !esi.getEventStreamMember()
                                .equals(httpBinding.getMember())).orElse(true));
                if (hasBodyBinding && streamInfoOptional.isPresent()) {
                    throw new CodegenException("HTTP Binding Protocol unexpected document or payload bindings with "
                            + "output event stream");
                }
                if (hasBodyBinding) {
                    // Output Shape Document Binding middleware generation
                    writeMiddlewareDocumentDeserializerDelegator(context, operation, generator);
                }
            }
            writer.write("");

            writer.write("span.End()");
            writer.write("return out, metadata, err");
        });
        goWriter.write("");

        Set<StructureShape> errorShapes = HttpProtocolGeneratorUtils.generateErrorDispatcher(
                context, operation, responseType, this::writeErrorMessageCodeDeserializer,
                this::getOperationErrors);
        deserializingErrorShapes.addAll(errorShapes);
        deserializeDocumentBindingShapes.addAll(errorShapes);
    }

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

    /**
     * Generate the document serializer logic for the serializer middleware body.
     *
     * @param context   the generation context
     * @param operation the operation
     * @param generator middleware generator definition
     */
    protected abstract void writeMiddlewareDocumentSerializerDelegator(
            GenerationContext context,
            OperationShape operation,
            GoStackStepMiddlewareGenerator generator
    );

    /**
     * Writes a payload content-type header setter.
     *
     * @param writer       the {@link GoWriter}.
     * @param payloadShape the payload shape.
     */
    protected void writeSetPayloadShapeHeader(GoWriter writer, Shape payloadShape) {
        writer.pushState();

        writer.putContext("withIsDefaultContentType", SymbolUtils.createValueSymbolBuilder(
                "SetIsContentTypeDefaultValue", SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build());
        writer.putContext("payloadMediaType", getPayloadShapeMediaType(payloadShape));

        writer.write("""
                if !restEncoder.HasHeader("Content-Type") {
                    ctx = $withIsDefaultContentType:T(ctx, true)
                    restEncoder.SetHeader("Content-Type").String($payloadMediaType:S)
                }
                """);

        writer.popState();
    }

    /**
     * Writes the stream setter that set the operand as the HTTP body.
     *
     * @param writer  the {@link GoWriter}.
     * @param operand the operand for the value to be set as the HTTP body.
     */
    protected void writeSetStream(GoWriter writer, String operand) {
        writer.write("""
                if request, err = request.SetStream($L); err != nil {
                    return out, metadata, &smithy.SerializationError{Err: err}
                }""", operand);
    }

    /**
     * Generate the payload serializer logic for the serializer middleware body.
     *
     * @param context     the generation context
     * @param memberShape the payload target member
     */
    protected void writeMiddlewarePayloadSerializerDelegator(
            GenerationContext context,
            MemberShape memberShape
    ) {
        GoWriter writer = context.getWriter().get();
        Model model = context.getModel();
        Shape payloadShape = model.expectShape(memberShape.getTarget());

        if (payloadShape.hasTrait(StreamingTrait.class)) {
            writeSetPayloadShapeHeader(writer, payloadShape);
            GoValueAccessUtils.writeIfNonZeroValueMember(context.getModel(), context.getSymbolProvider(), writer,
                    memberShape, "input", (s) -> {
                        writer.write("payload := $L", s);
                        writeSetStream(writer, "payload");
                    });
        } else if (payloadShape.isBlobShape()) {
            writeSetPayloadShapeHeader(writer, payloadShape);
            GoValueAccessUtils.writeIfNonZeroValueMember(context.getModel(), context.getSymbolProvider(), writer,
                    memberShape, "input", (s) -> {
                        writer.addUseImports(SmithyGoDependency.BYTES);
                        writer.write("payload := bytes.NewReader($L)", s);
                        writeSetStream(writer, "payload");
                    });
        } else if (payloadShape.isStringShape()) {
            writeSetPayloadShapeHeader(writer, payloadShape);
            GoValueAccessUtils.writeIfNonZeroValueMember(context.getModel(), context.getSymbolProvider(), writer,
                    memberShape, "input", (s) -> {
                        writer.addUseImports(SmithyGoDependency.STRINGS);
                        if (payloadShape.hasTrait(EnumTrait.class)) {
                            writer.write("payload := strings.NewReader(string($L))", s);
                        } else {
                            writer.write("payload := strings.NewReader(*$L)", s);
                        }
                        writeSetStream(writer, "payload");
                    });
        } else {
            writeMiddlewarePayloadAsDocumentSerializerDelegator(context, memberShape, "input");
        }
    }

    /**
     * Returns the MediaType for the payload shape derived from the MediaTypeTrait, shape type, or
     * document content type.
     *
     * @param payloadShape shape bound to the payload.
     * @return string for media type.
     */
    private String getPayloadShapeMediaType(Shape payloadShape) {
        Optional<MediaTypeTrait> mediaTypeTrait = payloadShape.getTrait(MediaTypeTrait.class);

        if (mediaTypeTrait.isPresent()) {
            return mediaTypeTrait.get().getValue();
        }

        if (payloadShape.isBlobShape()) {
            return "application/octet-stream";
        }

        if (payloadShape.isStringShape()) {
            return "text/plain";
        }

        return getDocumentContentType();
    }

    /**
     * Generate the payload serializers with document serializer logic for the serializer middleware body.
     *
     * @param context     the generation context
     * @param memberShape the payload target member
     * @param operand     the operand that is used to access the member value
     */
    protected abstract void writeMiddlewarePayloadAsDocumentSerializerDelegator(
            GenerationContext context,
            MemberShape memberShape,
            String operand
    );

    /**
     * Generate the document deserializer logic for the deserializer middleware body.
     *
     * @param context   the generation context
     * @param operation the operation
     * @param generator middleware generator definition
     */
    protected abstract void writeMiddlewareDocumentDeserializerDelegator(
            GenerationContext context,
            OperationShape operation,
            GoStackStepMiddlewareGenerator generator
    );

    private boolean isRestBinding(HttpBinding.Location location) {
        return location == HttpBinding.Location.HEADER
                || location == HttpBinding.Location.PREFIX_HEADERS
                || location == HttpBinding.Location.LABEL
                || location == HttpBinding.Location.QUERY
                || location == HttpBinding.Location.QUERY_PARAMS
                || location == HttpBinding.Location.RESPONSE_CODE;
    }

    // returns whether an operation shape has Rest Request Bindings
    private boolean isOperationWithRestRequestBindings(Model model, OperationShape operationShape) {
        Collection<HttpBinding> bindings = HttpBindingIndex.of(model)
                .getRequestBindings(operationShape).values();

        for (HttpBinding binding : bindings) {
            if (isRestBinding(binding.getLocation())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns whether a shape has rest response bindings.
     * The shape can be an operation shape, error shape or an output shape.
     *
     * @param model the model
     * @param shape the shape with possible presence of rest response bindings
     * @return boolean indicating presence of rest response bindings in the shape
     */
    protected boolean isShapeWithRestResponseBindings(Model model, Shape shape) {
        Collection<HttpBinding> bindings = HttpBindingIndex.of(model).getResponseBindings(shape).values();

        for (HttpBinding binding : bindings) {
            if (isRestBinding(binding.getLocation())) {
                return true;
            }
        }
        return false;
    }

    private void generateOperationHttpBindingSerializer(GenerationContext context, OperationShape operation) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter().get();

        Shape inputShape = model.expectShape(operation.getInput()
                .orElseThrow(() -> new CodegenException("missing input shape for operation: " + operation.getId())));

        HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
        List<HttpBinding> bindings = bindingIndex.getRequestBindings(operation).values().stream()
                .filter(httpBinding -> isRestBinding(httpBinding.getLocation()))
                .sorted(Comparator.comparing(HttpBinding::getMember))
                .collect(Collectors.toList());

        Symbol httpBindingEncoder = getHttpBindingEncoderSymbol();
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        String functionName = ProtocolGenerator.getOperationHttpBindingsSerFunctionName(
                inputShape, context.getService(), getProtocolName());

        writer.addUseImports(SmithyGoDependency.FMT);
        writer.openBlock("func $L(v $P, encoder $P) error {", "}", functionName, inputSymbol, httpBindingEncoder,
                () -> {
                    writer.openBlock("if v == nil {", "}", () -> {
                        writer.write("return fmt.Errorf(\"unsupported serialization of nil %T\", v)");
                    });

                    writer.write("");

                    for (HttpBinding binding : bindings.stream()
                            .filter(NoSerializeTrait.excludeNoSerializeHttpBindingMembers())
                            .collect(Collectors.toList())) {

                        writeHttpBindingMember(context, binding);
                        writer.write("");
                    }
                    writer.write("return nil");
                });
        writer.write("");
    }

    private Symbol getHttpBindingEncoderSymbol() {
        return SymbolUtils.createPointableSymbolBuilder("Encoder", SmithyGoDependency.SMITHY_HTTP_BINDING).build();
    }

    private void generateHttpBindingTimestampSerializer(
            Model model,
            GoWriter writer,
            MemberShape memberShape,
            HttpBinding.Location location,
            String operand,
            BiConsumer<GoWriter, String> locationEncoder
    ) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TIME);

        TimestampFormatTrait.Format format = model.getKnowledge(HttpBindingIndex.class).determineTimestampFormat(
                memberShape, location, getDocumentTimestampFormat());

        switch (format) {
            case DATE_TIME:
                locationEncoder.accept(writer, "String(smithytime.FormatDateTime(" + operand + "))");
                break;
            case HTTP_DATE:
                locationEncoder.accept(writer, "String(smithytime.FormatHTTPDate(" + operand + "))");
                break;
            case EPOCH_SECONDS:
                locationEncoder.accept(writer, "Double(smithytime.FormatEpochSeconds(" + operand + "))");
                break;
            default:
                throw new CodegenException("Unknown timestamp format");
        }
    }

    private boolean isHttpDateTimestamp(Model model, HttpBinding.Location location, MemberShape memberShape) {
        Shape targetShape = model.expectShape(memberShape.getTarget().toShapeId());
        if (targetShape.getType() != ShapeType.TIMESTAMP) {
            return false;
        }

        TimestampFormatTrait.Format format = HttpBindingIndex.of(model).determineTimestampFormat(
                memberShape, location, getDocumentTimestampFormat());

        return format == Format.HTTP_DATE;
    }

    private void writeHttpBindingSetter(
            GenerationContext context,
            GoWriter writer,
            MemberShape memberShape,
            HttpBinding.Location location,
            String operand,
            BiConsumer<GoWriter, String> locationEncoder
    ) {
        Model model = context.getModel();
        Shape targetShape = model.expectShape(memberShape.getTarget());

        // We only need to dereference if we pass the shape around as reference in Go.
        // Note we make two exceptions here: big.Int and big.Float should still be passed as reference to the helper
        // method as they can be arbitrarily large.
        operand = CodegenUtils.getAsValueIfDereferencable(GoPointableIndex.of(context.getModel()), memberShape,
                operand);

        switch (targetShape.getType()) {
            case BOOLEAN:
                locationEncoder.accept(writer, "Boolean(" + operand + ")");
                break;
            case STRING:
                operand = targetShape.hasTrait(EnumTrait.class) ? "string(" + operand + ")" : operand;
                locationEncoder.accept(writer, "String(" + operand + ")");
                break;
            case ENUM:
                operand = "string(" + operand + ")";
                locationEncoder.accept(writer, "String(" + operand + ")");
                break;
            case TIMESTAMP:
                generateHttpBindingTimestampSerializer(model, writer, memberShape, location, operand, locationEncoder);
                break;
            case BYTE:
                locationEncoder.accept(writer, "Byte(" + operand + ")");
                break;
            case SHORT:
                locationEncoder.accept(writer, "Short(" + operand + ")");
                break;
            case INTEGER:
            case INT_ENUM:
                locationEncoder.accept(writer, "Integer(" + operand + ")");
                break;
            case LONG:
                locationEncoder.accept(writer, "Long(" + operand + ")");
                break;
            case FLOAT:
                locationEncoder.accept(writer, "Float(" + operand + ")");
                break;
            case DOUBLE:
                locationEncoder.accept(writer, "Double(" + operand + ")");
                break;
            case BIG_INTEGER:
                locationEncoder.accept(writer, "BigInteger(" + operand + ")");
                break;
            case BIG_DECIMAL:
                locationEncoder.accept(writer, "BigDecimal(" + operand + ")");
                break;
            default:
                throw new CodegenException("unexpected shape type " + targetShape.getType());
        }
    }

    private void writeHttpBindingMember(
            GenerationContext context,
            HttpBinding binding
    ) {
        GoWriter writer = context.getWriter().get();
        Model model = context.getModel();
        MemberShape memberShape = binding.getMember();
        Shape targetShape = model.expectShape(memberShape.getTarget());
        HttpBinding.Location location = binding.getLocation();

        // return an error if member shape targets location label, but is unset.
        if (location.equals(HttpBinding.Location.LABEL)) {
            // labels must always be set to be serialized on URI, and non empty strings,
            GoValueAccessUtils.writeIfZeroValueMember(context.getModel(), context.getSymbolProvider(), writer,
                    memberShape, "v", false, true, operand -> {
                        writer.addUseImports(SmithyGoDependency.SMITHY);
                        writer.write("return &smithy.SerializationError { "
                                        + "Err: fmt.Errorf(\"input member $L must not be empty\")}",
                                memberShape.getMemberName());
                    });
        }

        GoValueAccessUtils.writeIfNonZeroValueMember(context.getModel(), context.getSymbolProvider(), writer,
                memberShape, "v", true, memberShape.isRequired(), (operand) -> {
                    final String locationName = binding.getLocationName().isEmpty()
                            ? memberShape.getMemberName() : binding.getLocationName();
                    switch (location) {
                        case HEADER:
                            writer.write("locationName := $S", getCanonicalHeader(locationName));
                            writeHeaderBinding(context, memberShape, operand, location, "locationName", "encoder");
                            break;
                        case PREFIX_HEADERS:
                            // the prefix is allowed to be empty for headers, this simply means that there is none
                            // and map keys are used directly as headers
                            var prefix = binding.getLocationName();

                            MemberShape valueMemberShape = model.expectShape(targetShape.getId(),
                                    MapShape.class).getValue();
                            Shape valueMemberTarget = model.expectShape(valueMemberShape.getTarget());

                            if (targetShape.getType() != ShapeType.MAP) {
                                throw new CodegenException("Unexpected prefix headers target shape "
                                        + valueMemberTarget.getType() + ", "
                                        + valueMemberShape.getId());
                            }

                            writer.write("hv := encoder.Headers($S)", getCanonicalHeader(prefix));
                            writer.addUseImports(SmithyGoDependency.NET_HTTP);
                            writer.openBlock("for mapKey, mapVal := range $L {", "}", operand, () -> {
                                writeHeaderBinding(context, valueMemberShape, "mapVal", location,
                                        "http.CanonicalHeaderKey(mapKey)", "hv");
                            });
                            break;
                        case LABEL:
                            writeHttpBindingSetter(context, writer, memberShape, location, operand, (w, s) -> {
                                w.openBlock("if err := encoder.SetURI($S).$L; err != nil {", "}", locationName, s,
                                        () -> {
                                            w.write("return err");
                                        });
                            });
                            break;
                        case QUERY:
                            writeQueryBinding(context, memberShape, targetShape, operand,
                                    location, locationName, "encoder", false);
                            break;
                        case QUERY_PARAMS:
                            MemberShape queryMapValueMemberShape = CodegenUtils.expectMapShape(targetShape).getValue();
                            Shape queryMapValueTargetShape = model.expectShape(queryMapValueMemberShape.getTarget());
                            MemberShape queryMapKeyMemberShape = CodegenUtils.expectMapShape(targetShape).getKey();
                            writer.openBlock("for qkey, qvalue := range $L {", "}", operand, () -> {
                                writer.write("if encoder.HasQuery(qkey) { continue }");
                                writeQueryBinding(context, queryMapKeyMemberShape, queryMapValueTargetShape,
                                        "qvalue", location, "qkey", "encoder", true);
                            });
                            break;

                        default:
                            throw new CodegenException("unexpected http binding found");
                    }
                });
    }

    /**
     * Writes query bindings, as per the target shape. This method is shared
     * between members modeled with Location.Query and Location.QueryParams.
     * Precedence across Location.Query and Location.QueryParams is handled
     * outside the scope of this function.
     *
     * @param context       is the generation context
     * @param memberShape   is the member shape for which query is serialized
     * @param targetShape   is the target shape of the query member.
     *                      This can either be string, or a list/set of string.
     * @param operand       is the member value accessor .
     * @param location      is the location of the member - can be Location.Query
     *                      or Location.QueryParams.
     * @param locationName  is the key for which query is encoded.
     * @param dest          is the query encoder destination.
     * @param isQueryParams boolean representing if Location used for query binding is
     *                      QUERY_PARAMS.
     */
    private void writeQueryBinding(
            GenerationContext context,
            MemberShape memberShape,
            Shape targetShape,
            String operand,
            HttpBinding.Location location,
            String locationName,
            String dest,
            boolean isQueryParams
    ) {
        GoWriter writer = context.getWriter().get();

        if (targetShape instanceof CollectionShape) {
            MemberShape collectionMember = CodegenUtils.expectCollectionShape(targetShape)
                    .getMember();
            writer.openBlock("for i := range $L {", "}", operand, () -> {
                GoValueAccessUtils.writeIfZeroValue(context.getModel(), writer, collectionMember,
                        operand + "[i]", () -> writer.write("continue"));

                String addQuery = String.format("$L.AddQuery(%s).$L", isQueryParams ? "$L" : "$S");
                writeHttpBindingSetter(context, writer, collectionMember, location, operand + "[i]",
                        (w, s) -> w.writeInline(addQuery, dest, locationName, s));
            });
            return;
        }

        String setQuery = String.format("$L.SetQuery(%s).$L", isQueryParams ? "$L" : "$S");
        writeHttpBindingSetter(context, writer, memberShape, location, operand,
                (w, s) -> w.writeInline(setQuery, dest, locationName, s));
    }

    private void writeHeaderBinding(
            GenerationContext context,
            MemberShape memberShape,
            String operand,
            HttpBinding.Location location,
            String locationName,
            String dest
    ) {
        GoWriter writer = context.getWriter().get();
        Model model = context.getModel();
        Shape targetShape = model.expectShape(memberShape.getTarget());

        if (!(targetShape instanceof CollectionShape)) {
            String op = conditionallyBase64Encode(context, writer, targetShape, operand);
            writeHttpBindingSetter(context, writer, memberShape, location, op, (w, s) -> {
                w.writeInline("$L.SetHeader($L).$L", dest, locationName, s);
            });
            return;
        }

        MemberShape collectionMemberShape = CodegenUtils.expectCollectionShape(targetShape).getMember();
        // On empty collection header will be set to an empty value
        writer.openBlock("if len($L) == 0 {", "}", operand, () -> {
            writeHttpBindingSetter(context, writer, collectionMemberShape, location, operand,
                    (w, s) -> {
                        w.writeInline("$L.AddHeader($L).String(\"\")", dest, locationName);
                    });
        });
        writer.openBlock("for i := range $L {", "}", operand, () -> {
            // Only set non-empty non-nil header values
            String indexedOperand = operand + "[i]";
            GoValueAccessUtils.writeIfNonZeroValue(context.getModel(), writer, collectionMemberShape, indexedOperand,
                    false, false, () -> {
                        String op = conditionallyEscapeHeader(context, writer, collectionMemberShape, indexedOperand);
                        writeHttpBindingSetter(context, writer, collectionMemberShape, location, op,
                                (w, s) -> {
                                    w.writeInline("$L.AddHeader($L).$L", dest, locationName, s);
                                });
                    });
        });
    }

    private String conditionallyEscapeHeader(
            GenerationContext context,
            GoWriter writer,
            MemberShape memberShape,
            String operand
    ) {
        var targetShape = context.getModel().expectShape(memberShape.getTarget());
        if (!targetShape.isStringShape()) {
            return operand;
        }
        if (targetShape.hasTrait(MediaTypeTrait.class)) {
            return conditionallyBase64Encode(context, writer, targetShape, operand);
        }

        writer.pushState();

        var returnVar = "escaped";
        var pointableIndex = GoPointableIndex.of(context.getModel());
        var shouldDereference = pointableIndex.isDereferencable(memberShape);
        if (shouldDereference) {
            operand = CodegenUtils.getAsValueIfDereferencable(pointableIndex, memberShape, operand);
            writer.putContext("escapedVar", "escapedVal");
            returnVar = "escapedPtr";
        } else {
            writer.putContext("escapedVar", returnVar);
        }
        writer.putContext("returnVar", returnVar);

        if (targetShape.hasTrait(EnumTrait.class)) {
            operand = "string(" + operand + ")";
        }

        writer.putContext("value", operand);
        writer.putContext("quoteValue", SymbolUtils.createValueSymbolBuilder(
                "Quote", SmithyGoDependency.STRCONV).build());
        writer.putContext("indexOf", SymbolUtils.createValueSymbolBuilder(
                "Index", SmithyGoDependency.STRINGS).build());
        writer.putContext("ptrString", SymbolUtils.createValueSymbolBuilder(
                "String", SmithyGoDependency.SMITHY_PTR).build());

        writer.write("""
                $escapedVar:L := $value:L
                if $indexOf:T($value:L, `,`) != -1 || $indexOf:T($value:L, `"`) != -1 {
                    $escapedVar:L = $quoteValue:T($value:L)
                }
                """);
        if (shouldDereference) {
            writer.write("$returnVar:L := $ptrString:T($escapedVar:L)");
        }

        writer.popState();

        return returnVar;
    }

    private String conditionallyBase64Encode(
            GenerationContext context,
            GoWriter writer,
            Shape targetShape,
            String operand
    ) {
        // MediaType strings written to headers must be base64 encoded
        if (!targetShape.isStringShape() || !targetShape.hasTrait(MediaTypeTrait.class)) {
            return operand;
        }

        writer.pushState();

        var returnVar = "encoded";
        var pointableIndex = GoPointableIndex.of(context.getModel());
        var shouldDereference = pointableIndex.isDereferencable(targetShape);
        if (shouldDereference) {
            operand = CodegenUtils.getAsValueIfDereferencable(pointableIndex, targetShape, operand);
            writer.putContext("encodedVar", "encodedVal");
            returnVar = "encodedPtr";
        } else {
            writer.putContext("encodedVar", returnVar);
        }
        writer.putContext("returnVar", returnVar);

        writer.putContext("value", operand);
        writer.putContext("ptrString", SymbolUtils.createValueSymbolBuilder(
                "String", SmithyGoDependency.SMITHY_PTR).build());
        writer.putContext("encodeToString", SymbolUtils.createValueSymbolBuilder(
                "StdEncoding.EncodeToString", SmithyGoDependency.BASE64).build());

        writer.write("$encodedVar:L := $encodeToString:T([]byte($value:L))");
        if (shouldDereference) {
            writer.write("$returnVar:L := $ptrString:T($encodedVar:L)");
        }

        writer.popState();
        return returnVar;
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
    public void generateResponseDeserializers(GenerationContext context) {
        deserializerOverrides.putAll(
                context.getIntegrations().stream()
                        .flatMap(it -> it.getClientPlugins(context.getModel(), context.getService()).stream())
                        .flatMap(it -> it.getShapeDeserializers().entrySet().stream())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );

        EventStreamIndex streamIndex = EventStreamIndex.of(context.getModel());

        for (OperationShape operation : getHttpBindingOperations(context)) {
            generateOperationDeserializerMiddleware(context, operation);
            generateHttpBindingDeserializer(context, operation);

            Optional<EventStreamInfo> streamInfo = streamIndex.getOutputInfo(operation);

            if (!CodegenUtils.isStubSynthetic(ProtocolUtils.expectOutput(context.getModel(), operation))
                    && streamInfo.isEmpty()) {
                generateOperationDocumentDeserializer(context, operation);
                addOperationDocumentShapeBindersForDeserializer(context, operation);
            }
        }

        GoEventStreamIndex goEventStreamIndex = GoEventStreamIndex.of(context.getModel());

        goEventStreamIndex.getOutputEventStreams(context.getService()).ifPresent(shapeIdSetMap ->
                shapeIdSetMap.forEach((shapeId, eventStreamInfos) -> {
                    generateEventStreamDeserializers(context, context.getModel().expectShape(shapeId, UnionShape.class),
                            eventStreamInfos);
                }));

        for (StructureShape error : deserializingErrorShapes) {
            generateHttpBindingDeserializer(context, error);
        }
    }

    // Generates Http Binding shape deserializer function.
    private void generateHttpBindingDeserializer(GenerationContext context, Shape shape) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter().get();

        HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
        List<HttpBinding> bindings = bindingIndex.getResponseBindings(shape).values().stream()
                .filter(binding -> isRestBinding(binding.getLocation()))
                .sorted(Comparator.comparing(HttpBinding::getMember))
                .collect(Collectors.toList());

        // Don't generate anything if there are no bindings.
        if (bindings.size() == 0) {
            return;
        }

        Shape targetShape = shape;
        if (shape.isOperationShape()) {
            targetShape = ProtocolUtils.expectOutput(model, shape.asOperationShape().get());
        }
        Symbol targetSymbol;
        if (shape.isOperationShape() && EventStreamGenerator.isV2EventStream(model, shape.asOperationShape().get())) {
            targetSymbol = EventStreamGenerator.getEventStreamInitialReplyPointableSymbol(context.getService(), shape.asOperationShape().get());
        } else {
            targetSymbol = symbolProvider.toSymbol(targetShape);
        }
        Symbol smithyHttpResponsePointableSymbol = SymbolUtils.createPointableSymbolBuilder(
                "Response", SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build();

        writer.addUseImports(SmithyGoDependency.FMT);

        String functionName = ProtocolGenerator.getOperationHttpBindingsDeserFunctionName(
                targetShape, context.getService(), getProtocolName());
        writer.openBlock("func $L(v $P, response $P) error {", "}", functionName, targetSymbol,
                smithyHttpResponsePointableSymbol, () -> {
                    writer.openBlock("if v == nil {", "}", () -> {
                        writer.write("return fmt.Errorf(\"unsupported deserialization for nil %T\", v)");
                    });
                    writer.write("");

                    for (HttpBinding binding : bindings) {
                        writeRestDeserializerMember(context, writer, binding);
                        writer.write("");
                    }
                    writer.write("return nil");
                });
    }

    private String generateHttpHeaderValue(
            GenerationContext context,
            GoWriter writer,
            MemberShape memberShape,
            HttpBinding binding,
            String operand
    ) {
        Shape targetShape = context.getModel().expectShape(memberShape.getTarget());

        if (targetShape.getType() != ShapeType.LIST && targetShape.getType() != ShapeType.SET) {
            writer.addUseImports(SmithyGoDependency.STRINGS);
            writer.write("$L = strings.TrimSpace($L)", operand, operand);
        }

        String value = "";
        switch (targetShape.getType()) {
            case STRING:
                if (targetShape.hasTrait(EnumTrait.class)) {
                    value = String.format("types.%s(%s)", targetShape.getId().getName(), operand);
                    return value;
                }
                // MediaType strings must be base-64 encoded when sent in headers.
                if (targetShape.hasTrait(MediaTypeTrait.class)) {
                    writer.addUseImports(SmithyGoDependency.BASE64);
                    writer.write("b, err := base64.StdEncoding.DecodeString($L)", operand);
                    writer.write("if err != nil { return err }");
                    return "string(b)";
                }
                return operand;
            case ENUM:
                value = String.format("types.%s(%s)", targetShape.getId().getName(), operand);
                return value;
            case BOOLEAN:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseBool($L)", operand);
                writer.write("if err != nil { return err }");
                return "vv";
            case TIMESTAMP:
                writer.addUseImports(SmithyGoDependency.SMITHY_TIME);
                HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
                TimestampFormatTrait.Format format = bindingIndex.determineTimestampFormat(
                        memberShape,
                        binding.getLocation(),
                        Format.HTTP_DATE
                );
                switch (format) {
                    case EPOCH_SECONDS:
                        writer.addUseImports(SmithyGoDependency.STRCONV);
                        writer.write("f, err := strconv.ParseFloat($L, 64)", operand);
                        writer.write("if err != nil { return err }");
                        writer.write("t := smithytime.ParseEpochSeconds(f)");
                        break;
                    case HTTP_DATE:
                        writer.write("t, err := smithytime.ParseHTTPDate($L)", operand);
                        writer.write("if err != nil { return err }");
                        break;
                    case DATE_TIME:
                        writer.write("t, err := smithytime.ParseDateTime($L)", operand);
                        writer.write("if err != nil { return err }");
                        break;
                    default:
                        throw new CodegenException("Unexpected timestamp format " + format);
                }
                return "t";
            case BYTE:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseInt($L, 0, 8)", operand);
                writer.write("if err != nil { return err }");
                return "int8(vv)";
            case SHORT:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseInt($L, 0, 16)", operand);
                writer.write("if err != nil { return err }");
                return "int16(vv)";
            case INTEGER:
            case INT_ENUM:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseInt($L, 0, 32)", operand);
                writer.write("if err != nil { return err }");
                return "int32(vv)";
            case LONG:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseInt($L, 0, 64)", operand);
                writer.write("if err != nil { return err }");
                return "vv";
            case FLOAT:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseFloat($L, 32)", operand);
                writer.write("if err != nil { return err }");
                return "float32(vv)";
            case DOUBLE:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseFloat($L, 64)", operand);
                writer.write("if err != nil { return err }");
                return "vv";
            case BIG_INTEGER:
                writer.addUseImports(SmithyGoDependency.BIG);
                writer.write("i := big.NewInt(0)");
                writer.write("bi, ok := i.SetString($L,0)", operand);
                writer.openBlock("if !ok {", "}", () -> {
                    writer.write(
                            "return fmt.Error($S)",
                            "Incorrect conversion from string to BigInteger type"
                    );
                });
                return "*bi";
            case BIG_DECIMAL:
                writer.addUseImports(SmithyGoDependency.BIG);
                writer.write("f := big.NewFloat(0)");
                writer.write("bd, ok := f.SetString($L,0)", operand);
                writer.openBlock("if !ok {", "}", () -> {
                    writer.write(
                            "return fmt.Error($S)",
                            "Incorrect conversion from string to BigDecimal type"
                    );
                });
                return "*bd";
            case BLOB:
                writer.addUseImports(SmithyGoDependency.BASE64);
                writer.write("b, err := base64.StdEncoding.DecodeString($L)", operand);
                writer.write("if err != nil { return err }");
                return "b";
            case SET:
            case LIST:
                // handle list/Set as target shape
                MemberShape targetValueListMemberShape = CodegenUtils.expectCollectionShape(targetShape).getMember();
                return getHttpHeaderCollectionDeserializer(context, writer, targetValueListMemberShape,
                        binding,
                        operand);
            default:
                throw new CodegenException("unexpected shape type " + targetShape.getType());
        }
    }

    private String getHttpHeaderCollectionDeserializer(
            GenerationContext context,
            GoWriter writer,
            MemberShape memberShape,
            HttpBinding binding,
            String operand
    ) {
        writer.write("var list []$P", context.getSymbolProvider().toSymbol(memberShape));

        String operandValue = operand + "Val";
        writer.openBlock("for _, $L := range $L {", "}", operandValue, operand, () -> {
            String value = generateHttpHeaderValue(context, writer, memberShape, binding, operandValue);
            writer.write("list = append(list, $L)",
                    CodegenUtils.getAsPointerIfPointable(context.getModel(), writer,
                            GoPointableIndex.of(context.getModel()), memberShape, value));
        });
        return "list";
    }

    private void writeRestDeserializerMember(
            GenerationContext context,
            GoWriter writer,
            HttpBinding binding
    ) {
        MemberShape memberShape = binding.getMember();
        Shape targetShape = context.getModel().expectShape(memberShape.getTarget());
        String memberName = context.getSymbolProvider().toMemberName(memberShape);

        switch (binding.getLocation()) {
            case HEADER:
                writeHeaderDeserializerFunction(context, writer, memberName, memberShape, binding);
                break;
            case PREFIX_HEADERS:
                if (!targetShape.isMapShape()) {
                    throw new CodegenException("unexpected prefix-header shape type found in Http bindings");
                }
                writePrefixHeaderDeserializerFunction(context, writer, memberName, memberShape, binding);
                break;
            case RESPONSE_CODE:
                writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
                writer.write("v.$L = $L", memberName,
                        CodegenUtils.getAsPointerIfPointable(context.getModel(), writer,
                                GoPointableIndex.of(context.getModel()), memberShape, "int32(response.StatusCode)"));
                break;
            default:
                throw new CodegenException("unexpected http binding found");
        }
    }

    private void writeHeaderDeserializerFunction(
            GenerationContext context,
            GoWriter writer,
            String memberName,
            MemberShape memberShape,
            HttpBinding binding
    ) {
        writer.openBlock("if headerValues := response.Header.Values($S); len(headerValues) != 0 {", "}",
                binding.getLocationName(), () -> {
                    var target = memberShape.getTarget();
                    Shape targetShape = context.getModel().expectShape(target);

                    String operand = "headerValues";
                    operand = writeHeaderValueAccessor(context, writer, targetShape, binding, operand);

                    if (deserializerOverrides.containsKey(target)) {
                        writer.write("""
                            deserOverride, err := $T($L)
                            if err != nil {
                                return err
                            }
                            v.$L = deserOverride
                            """, deserializerOverrides.get(target), operand, memberName);
                        return;
                    }

                    var value = generateHttpHeaderValue(context, writer, memberShape, binding, operand);
                    writer.write("v.$L = $L", memberName,
                            CodegenUtils.getAsPointerIfPointable(context.getModel(), writer,
                                    GoPointableIndex.of(context.getModel()), memberShape, value));
                });
    }

    private void writePrefixHeaderDeserializerFunction(
            GenerationContext context,
            GoWriter writer,
            String memberName,
            MemberShape memberShape,
            HttpBinding binding
    ) {
        String prefix = binding.getLocationName();
        Shape targetShape = context.getModel().expectShape(memberShape.getTarget());

        MemberShape valueMemberShape = targetShape.asMapShape()
                .orElseThrow(() -> new CodegenException("prefix headers must target map shape"))
                .getValue();

        writer.openBlock("for headerKey, headerValues := range response.Header {", "}", () -> {
            writer.addUseImports(SmithyGoDependency.STRINGS);
            Symbol targetSymbol = context.getSymbolProvider().toSymbol(targetShape);

            writer.openBlock(
                    "if lenPrefix := len($S); "
                            + "len(headerKey) >= lenPrefix && strings.EqualFold(headerKey[:lenPrefix], $S) {",
                    "}", prefix, prefix, () -> {
                        writer.openBlock("if v.$L == nil {", "}", memberName, () -> {
                            writer.write("v.$L = $P{}", memberName, targetSymbol);
                        });

                        String operand = "headerValues";
                        operand = writeHeaderValueAccessor(context, writer, targetShape, binding, operand);

                        String value = generateHttpHeaderValue(context, writer, valueMemberShape,
                                binding, operand);
                        writer.write("v.$L[strings.ToLower(headerKey[lenPrefix:])] = $L", memberName,
                                CodegenUtils.getAsPointerIfPointable(context.getModel(), writer,
                                        GoPointableIndex.of(context.getModel()), valueMemberShape, value));
                    });
        });
    }

    /**
     * Returns the header value accessor operand, and also if the target shape is a list/set will write the splitting
     * of the header values by comma(,) utility helper.
     *
     * @param context     generation context
     * @param writer      writer
     * @param targetShape target shape
     * @param binding     http binding location
     * @param operand     operand of the header values.
     * @return returns operand for accessing the header values
     */
    private String writeHeaderValueAccessor(
            GenerationContext context,
            GoWriter writer,
            Shape targetShape,
            HttpBinding binding,
            String operand
    ) {
        switch (targetShape.getType()) {
            case LIST:
            case SET:
                writerHeaderListValuesSplit(context, writer, CodegenUtils.expectCollectionShape(targetShape), binding,
                        operand);
                break;
            default:
                // Always use first element in header, ignores if there are multiple headers with this key.
                operand += "[0]";
                break;
        }

        return operand;
    }

    /**
     * Writes the utility to split split comma separate header values into a single list for consistent iteration. Also
     * has special case handling for HttpDate timestamp format when serialized as a header list. Assigns the split
     * header values back to the same operand name.
     *
     * @param context generation context
     * @param writer  writer
     * @param shape   target collection shape
     * @param binding http binding location
     * @param operand operand of the header values.
     */
    private void writerHeaderListValuesSplit(
            GenerationContext context,
            GoWriter writer,
            CollectionShape shape,
            HttpBinding binding,
            String operand
    ) {
        writer.openBlock("{", "}", () -> {
            writer.write("var err error");
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            if (isHttpDateTimestamp(context.getModel(), binding.getLocation(), shape.getMember())) {
                writer.write("$L, err = smithyhttp.SplitHTTPDateTimestampHeaderListValues($L)", operand, operand);
            } else {
                writer.write("$L, err = smithyhttp.SplitHeaderListValues($L)", operand, operand);
            }
            writer.openBlock("if err != nil {", "}", () -> {
                writer.write("return err");
            });
        });
    }

    @Override
    public void generateSharedDeserializerComponents(GenerationContext context) {
        deserializingErrorShapes.forEach(error -> generateErrorDeserializer(context, error));
        deserializeDocumentBindingShapes.addAll(ProtocolUtils.resolveRequiredDocumentShapeSerde(
                context.getModel(), deserializeDocumentBindingShapes));
        generateDocumentBodyShapeDeserializers(context, deserializeDocumentBindingShapes);
    }

    /**
     * Adds the top-level shapes from the operation that bind to the body document that require deserializer functions.
     *
     * @param context   the generator context
     * @param operation the operation to add document binders from
     */
    private void addOperationDocumentShapeBindersForDeserializer(GenerationContext context, OperationShape operation) {
        Model model = context.getModel();
        HttpBindingIndex httpBindingIndex = HttpBindingIndex.of(model);

        addDocumentDeserializerBindingShapes(model, httpBindingIndex, operation);

        for (ShapeId errorShapeId : operation.getErrors()) {
            addDocumentDeserializerBindingShapes(model, httpBindingIndex, errorShapeId);
        }
    }

    /**
     * Adds shapes from provided shape that require document deserializer functions to be generated.
     *
     * @param model the smithy model.
     * @param index the http binding index
     * @param shape the shape to enumerate member shapes for document deserializers
     */
    private void addDocumentDeserializerBindingShapes(Model model, HttpBindingIndex index, ToShapeId shape) {
        // Walk and add members shapes to the list that will require deserializer functions
        for (HttpBinding binding : index.getResponseBindings(shape).values()) {
            MemberShape memberShape = binding.getMember();
            Shape targetShape = model.expectShape(memberShape.getTarget());

            // Event Stream Member should not immediately generate a document deserializer
            // and is handled via generateOperationEventMessageDeserializers.
            if (StreamingTrait.isEventStream(model, memberShape)) {
                continue;
            }

            // Add deserializer helpers for document and payload shape bindings if the operation does not have
            // any output event streams.
            if (requiresDocumentSerdeFunction(targetShape)
                    && (binding.getLocation() == HttpBinding.Location.DOCUMENT
                    || binding.getLocation() == HttpBinding.Location.PAYLOAD)) {
                deserializeDocumentBindingShapes.add(targetShape);
            }
        }
    }

    /**
     * Generates the operation document deserializer function.
     *
     * @param context   the generation context
     * @param operation the operation shape being generated
     */
    protected abstract void generateOperationDocumentDeserializer(GenerationContext context, OperationShape operation);

    /**
     * Generates deserialization functions for shapes in the provided set. These functions
     * should return a value that can then be deserialized by the implementation of
     * {@code deserializeOutputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentBodyShapeDeserializers(GenerationContext context, Set<Shape> shapes);

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
     * Converts the first letter and any letter following a hyphen to upper case. The remaining letters are lower cased.
     *
     * @param key the header
     * @return the canonical header
     */
    private String getCanonicalHeader(String key) {
        char[] chars = key.toCharArray();
        boolean upper = true;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            c = upper ? Character.toUpperCase(c) : Character.toLowerCase(c);
            chars[i] = c;
            upper = c == '-';
        }
        return new String(chars);
    }
}
