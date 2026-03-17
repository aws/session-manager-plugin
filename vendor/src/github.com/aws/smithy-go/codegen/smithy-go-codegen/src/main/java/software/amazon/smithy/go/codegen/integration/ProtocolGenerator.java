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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.Synthetic;
import software.amazon.smithy.go.codegen.auth.AuthGenerator;
import software.amazon.smithy.go.codegen.endpoints.EndpointResolutionGenerator;
import software.amazon.smithy.go.codegen.endpoints.FnGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ExpectationNotMetException;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.utils.CaseUtils;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.StringUtils;

/**
 * Smithy protocol code generators.
 */
public interface ProtocolGenerator {
    /**
     * Sanitizes the name of the protocol so it can be used as a symbol
     * in Go.
     *
     * <p>
     * For example, the default implementation converts "." to "_",
     * and converts "-" to become camelCase separated words. This means
     * that "aws.rest-json-1.1" becomes "Aws_RestJson1_1".
     *
     * @param name Name of the protocol to sanitize.
     * @return Returns the sanitized name.
     */
    static String getSanitizedName(String name) {
        name = name.replaceAll("(\\s|\\.|-)+", "_");
        return CaseUtils.toCamelCase(name, true, '_');
    }

    /**
     * Gets the supported protocol {@link ShapeId}.
     *
     * @return Returns the protocol supported
     */
    ShapeId getProtocol();

    default String getProtocolName() {
        ShapeId protocol = getProtocol();
        String prefix = protocol.getNamespace();
        int idx = prefix.indexOf('.');
        if (idx != -1) {
            prefix = prefix.substring(0, idx);
        }
        return CaseUtils.toCamelCase(prefix) + getSanitizedName(protocol.getName());
    }

    /**
     * Creates an application protocol for the generator.
     *
     * @return Returns the created application protocol.
     */
    ApplicationProtocol getApplicationProtocol();

    /**
     * Determines if two protocol generators are compatible at the
     * application protocol level, meaning they both use HTTP, or MQTT
     * for example.
     *
     * <p>
     * Two protocol implementations are considered compatible if the
     * {@link ApplicationProtocol#equals} method of {@link #getApplicationProtocol}
     * returns true when called with {@code other}. The default implementation
     * should work for most interfaces, but may be overridden for more in-depth
     * handling of things like minor version incompatibilities.
     *
     * <p>
     * By default, if the application protocols are considered equal, then
     * {@code other} is returned.
     *
     * @param service            Service being generated.
     * @param protocolGenerators Other protocol generators that are being generated.
     * @param other              Protocol generator to resolve against.
     * @return Returns the resolved application protocol object.
     */
    default ApplicationProtocol resolveApplicationProtocol(
            ServiceShape service,
            Collection<ProtocolGenerator> protocolGenerators,
            ApplicationProtocol other) {
        if (!getApplicationProtocol().equals(other)) {
            String protocolNames = protocolGenerators.stream()
                    .map(ProtocolGenerator::getProtocol)
                    .map(Trait::getIdiomaticTraitName)
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new CodegenException(String.format(
                    "All of the protocols generated for a service must be runtime compatible, but "
                            + "protocol `%s` is incompatible with other application protocols: [%s]. Please pick a "
                            + "set of compatible protocols using the `protocols` option when generating %s.",
                    getProtocol(), protocolNames, service.getId()));
        }

        return other;
    }

    /**
     * Generates any standard code for service request/response serde.
     *
     * @param context Serde context.
     */
    default void generateSharedSerializerComponents(GenerationContext context) {
    }

    /**
     * Generates the code used to serialize the shapes of a service
     * for requests.
     *
     * @param context Serialization context.
     */
    void generateRequestSerializers(GenerationContext context);

    /**
     * Generates any standard code for service response deserialization.
     *
     * @param context Serde context.
     */
    default void generateSharedDeserializerComponents(GenerationContext context) {
    }

    /**
     * Generates the code used to deserialize the shapes of a service
     * for responses.
     *
     * @param context Deserialization context.
     */
    void generateResponseDeserializers(GenerationContext context);

    /**
     * Generates the code for validating the generated protocol's serializers and
     * deserializers.
     *
     * @param context Generation context
     */
    default void generateProtocolTests(GenerationContext context) {
    }

    /**
     * Generates the name of a serializer function for shapes of a service.
     *
     * @param shape    The shape the serializer function is being generated for.
     * @param service  The service shape.
     * @param protocol Name of the protocol being generated.
     * @return Returns the generated function name.
     */
    static String getOperationHttpBindingsSerFunctionName(Shape shape, ServiceShape service, String protocol) {
        return protocol
                + "_serializeOpHttpBindings"
                + StringUtils.capitalize(shape.getId().getName(service));
    }

    /**
     * Generates the name of a deserializer function for shapes of a service.
     *
     * @param shape    The shape the deserializer function is being generated for.
     * @param service  The service shape.
     * @param protocol Name of the protocol being generated.
     * @return Returns the generated function name.
     */
    static String getOperationHttpBindingsDeserFunctionName(Shape shape, ServiceShape service, String protocol) {
        return protocol
                + "_deserializeOpHttpBindings"
                + StringUtils.capitalize(shape.getId().getName(service));
    }

    /**
     * Generates the name of a serializer function for shapes of a service.
     *
     * @param shape    The shape the serializer function is being generated for.
     * @param service  The service shape within which the deserialized shape is
     *                 enclosed.
     * @param protocol Name of the protocol being generated.
     * @return Returns the generated function name.
     */
    static String getDocumentSerializerFunctionName(Shape shape, ServiceShape service, String protocol) {
        String name = shape.getId().getName(service);
        String extra = "";
        if (shape.hasTrait(Synthetic.class)) {
            extra = "Op";
        }
        return protocol + "_serialize" + extra + "Document" + StringUtils.capitalize(name);
    }

    /**
     * Generates the name of a deserializer function for shapes of a service.
     *
     * @param shape    The shape the deserializer function is being generated for.
     * @param service  The service shape within which the deserialized shape is
     *                 enclosed.
     * @param protocol Name of the protocol being generated.
     * @return Returns the generated function name.
     */
    static String getDocumentDeserializerFunctionName(Shape shape, ServiceShape service, String protocol) {
        String name = shape.getId().getName(service);
        String extra = "";
        if (shape.hasTrait(Synthetic.class)) {
            extra = "Op";
        }
        return protocol + "_deserialize" + extra + "Document" + StringUtils.capitalize(name);
    }

    static String getOperationErrorDeserFunctionName(OperationShape shape, ServiceShape service, String protocol) {
        return protocol + "_deserializeOpError" + StringUtils.capitalize(shape.getId().getName(service));
    }

    /**
     * Generates the name of an error deserializer function for shapes of a service.
     *
     * @param shape    The error structure shape for which deserializer name is
     *                 being generated.
     * @param service  The service enclosing the service shape.
     * @param protocol Name of the protocol being generated.
     * @return Returns the generated function name.
     */
    static String getErrorDeserFunctionName(StructureShape shape, ServiceShape service, String protocol) {
        String name = shape.getId().getName(service);
        return protocol + "_deserializeError" + StringUtils.capitalize(name);
    }

    static String getSerializeMiddlewareName(ShapeId operationShapeId, ServiceShape service, String protocol) {
        return protocol
                + "_serializeOp"
                + operationShapeId.getName(service);
    }

    static String getDeserializeMiddlewareName(ShapeId operationShapeId, ServiceShape service, String protocol) {
        return protocol
                + "_deserializeOp"
                + operationShapeId.getName(service);
    }

    /**
     * Returns a map of error names to their {@link ShapeId}.
     *
     * @param context   the generation context
     * @param operation the operation shape to retrieve errors for
     * @return map of error names to {@link ShapeId}
     */
    default Map<String, ShapeId> getOperationErrors(GenerationContext context, OperationShape operation) {
        return HttpProtocolGeneratorUtils.getOperationErrors(context, operation);
    }

    /**
     * Generates the UnmarshalSmithyDocument function body of the service's internal
     * documentMarshaler type.
     * <p>
     * The document marshaler type is expected to handle user provided Go types and
     * convert them to protocol documents.
     * <p>
     * The default implementation will throw a {@code CodegenException} if not
     * implemented.
     *
     * <pre>{@code
     * type documentMarshaler struct {
     *     value interface{}
     * }
     *
     * // ...
     *
     * func (m *documentMarshaler) UnmarshalSmithyDocument(v interface{}) error {
     *      // Generated code from generateProtocolDocumentMarshalerUnmarshalDocument
     * }
     * }</pre>
     *
     * @param context the generation context.
     */
    default void generateProtocolDocumentMarshalerUnmarshalDocument(GenerationContext context) {
        throw new CodegenException("document types not implemented for " + this.getProtocolName() + " protocol");
    }

    /**
     * Generates the UnmarshalSmithyDocument function body of the service's internal
     * documentMarshaler type.
     * <p>
     * The document marshaler type is expected to handle user provided Go types and
     * convert them to protocol documents.
     * <p>
     * The default implementation will throw a {@code CodegenException} if not
     * implemented.
     *
     * <pre>{@code
     * type documentMarshaler struct {
     *     value interface{}
     * }
     *
     * // ...
     *
     * func (m *documentMarshaler) MarshalSmithyDocument() ([]byte, error) {
     *      // Generated code from generateProtocolDocumentMarshalerMarshalDocument
     * }
     * }</pre>
     *
     * @param context the generation context.
     */
    default void generateProtocolDocumentMarshalerMarshalDocument(GenerationContext context) {
        throw new CodegenException("document types not implemented for " + this.getProtocolName() + " protocol");
    }

    /**
     * Generates the UnmarshalSmithyDocument function body of the service's internal
     * documentUnmarshaler type.
     * <p>
     * The document unmarshaler type is expected to handle protocol documents
     * received from the service and provide the
     * ability to unmarshal or round-trip the document.
     * <p>
     * The default implementation will throw a {@code CodegenException} if not
     * implemented.
     *
     * <pre>{@code
     * type documentUnmarshaler struct {
     *     value interface{}
     * }
     *
     * // ...
     *
     * func (m *documentUnmarshaler) UnmarshalSmithyDocument(v interface{}) error {
     *      // Generated code from generateProtocolDocumentUnmarshalerUnmarshalDocument
     * }
     * }</pre>
     *
     * @param context the generation context.
     */
    default void generateProtocolDocumentUnmarshalerUnmarshalDocument(GenerationContext context) {
        throw new CodegenException("document types not implemented for " + this.getProtocolName() + " protocol");
    }

    /**
     * Generates the MarshalSmithyDocument function body of the service's internal
     * documentUnmarshaler type.
     * <p>
     * The document unmarshaler type is expected to handle protocol documents
     * received from the service and provide the
     * ability to unmarshal or round-trip the document.
     * <p>
     * The default implementation will throw a {@code CodegenException} if not
     * implemented.
     *
     * <pre>{@code
     * type documentUnmarshaler struct {
     *     value interface{}
     * }
     *
     * // ...
     *
     * func (m *documentUnmarshaler) MarshalSmithyDocument() ([]byte, error) {
     *      // Generated code from generateProtocolDocumentUnmarshalerMarshalDocument
     * }
     * }</pre>
     *
     * @param context the generation context.
     */
    default void generateProtocolDocumentUnmarshalerMarshalDocument(GenerationContext context) {
        throw new CodegenException("document types not implemented for " + this.getProtocolName() + " protocol");
    }

    /**
     * Generate the internal constructor function body for the service's internal
     * documentMarshaler type.
     *
     * <pre>{@code
     * func NewDocumentMarshaler(v interface{}) Interface {
     *     return &documentMarshaler{
     *         value: v,
     *     }
     * }
     * }</pre>
     *
     * @param context         the generation context.
     * @param marshalerSymbol the symbol for the {@code documentMarshaler} type.
     */
    default void generateNewDocumentMarshaler(GenerationContext context, Symbol marshalerSymbol) {
        GoWriter writer = context.getWriter().get();
        writer.openBlock("return &$T{", "}", marshalerSymbol, () -> {
            writer.write("value: v,");
        });
    }

    /**
     * Generate the internal constructor function body for the service's internal
     * documentUnmarshaler type.
     *
     * <pre>{@code
     * func NewDocumentUnmarshaler(v interface{}) Interface {
     *     return &documentUnmarshaler{
     *         value: v,
     *     }
     * }
     * }</pre>
     *
     * @param context           the generation context.
     * @param unmarshalerSymbol the symbol for the {@code documentUnmarshaler} type.
     */
    default void generateNewDocumentUnmarshaler(GenerationContext context, Symbol unmarshalerSymbol) {
        GoWriter writer = context.getWriter().get();
        writer.openBlock("return &$T{", "}", unmarshalerSymbol, () -> {
            writer.write("value: v,");
        });
    }

    /**
     * Returns an error code for an error shape. Defaults to error shape name as
     * error code.
     *
     * @param service    the service enclosure for the error shape.
     * @param errorShape the error shape for which error code is retrieved.
     * @return the error code associated with the provided shape.
     * @throws ExpectationNotMetException if provided shape is not modeled with an
     *                                    {@link ErrorTrait}.
     */
    default String getErrorCode(ServiceShape service, StructureShape errorShape) {
        errorShape.expectTrait(ErrorTrait.class);
        return errorShape.getId().getName(service);
    }

    /**
     * Generate specific components for the protocol's event stream implementation.
     * These components
     * types should provide implementations that satisfy the reader and writer event
     * stream interfaces.
     *
     * @param context the generation context.
     */
    default void generateEventStreamComponents(GenerationContext context) {
    }

    /**
     * Generate all components necessary for Endpoint Rules Engine endpoint
     * resolution.
     *
     * @param context the generation context.
     */
    default void generateEndpointResolution(GenerationContext context) {
        var generator = new EndpointResolutionGenerator(new FnGenerator.DefaultFnProvider());
        generator.generate(context);
    }

    /**
     * Generate the tests for Endpoint Rules Engine endpoint resolution.
     *
     * @param context the generation context.
     */
    default void generateEndpointResolutionTests(GenerationContext context) {
        var generator = new EndpointResolutionGenerator(new FnGenerator.DefaultFnProvider());
        generator.generateTests(context);
    }

    /**
     * Generates smithy client auth components.
     *
     * @param context The generation context.
     */
    default void generateAuth(GenerationContext context) {
        new AuthGenerator(context).generate();
    }

    /**
     * Context object used for service serialization and deserialization.
     */
    final class GenerationContext {
        private final GoSettings settings;
        private final Model model;
        private final ServiceShape service;
        private final SymbolProvider symbolProvider;
        private final GoWriter writer;
        private final List<GoIntegration> integrations;
        private final String protocolName;
        private final GoDelegator delegator;

        private GenerationContext(Builder builder) {
            this.settings = SmithyBuilder.requiredState("settings", builder.settings);
            this.model = SmithyBuilder.requiredState("model", builder.model);
            this.service = SmithyBuilder.requiredState("service", builder.service);
            this.symbolProvider = SmithyBuilder.requiredState("symbolProvider", builder.symbolProvider);
            this.writer = builder.writer;
            this.integrations = SmithyBuilder.requiredState("integrations", builder.integrations);
            this.protocolName = SmithyBuilder.requiredState("protocolName", builder.protocolName);
            this.delegator = SmithyBuilder.requiredState("delegator", builder.delegator);
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return builder()
                    .settings(this.settings)
                    .model(this.model)
                    .service(this.service)
                    .symbolProvider(this.symbolProvider)
                    .writer(this.writer)
                    .integrations(this.integrations)
                    .protocolName(this.protocolName)
                    .delegator(this.delegator);
        }

        public GoSettings getSettings() {
            return settings;
        }

        public Model getModel() {
            return model;
        }

        public ServiceShape getService() {
            return service;
        }

        public EndpointRuleSet getEndpointRules() {
            return EndpointRuleSet.fromNode(service.expectTrait(EndpointRuleSetTrait.class).getRuleSet());
        }

        public SymbolProvider getSymbolProvider() {
            return symbolProvider;
        }

        public Optional<GoWriter> getWriter() {
            return Optional.ofNullable(writer);
        }

        public GoDelegator getDelegator() {
            return delegator;
        }

        public List<GoIntegration> getIntegrations() {
            return integrations;
        }

        public String getProtocolName() {
            return protocolName;
        }

        public static class Builder implements SmithyBuilder<GenerationContext> {
            private GoSettings settings;
            private Model model;
            private ServiceShape service;
            private SymbolProvider symbolProvider;
            private GoWriter writer;
            private final List<GoIntegration> integrations = new ArrayList<>();
            private String protocolName;
            private GoDelegator delegator;

            public Builder settings(GoSettings settings) {
                this.settings = settings;
                return this;
            }

            public Builder model(Model model) {
                this.model = model;
                return this;
            }

            public Builder service(ServiceShape service) {
                this.service = service;
                return this;
            }

            public Builder symbolProvider(SymbolProvider symbolProvider) {
                this.symbolProvider = symbolProvider;
                return this;
            }

            public Builder writer(GoWriter writer) {
                this.writer = writer;
                return this;
            }

            public Builder integrations(Collection<GoIntegration> integrations) {
                this.integrations.clear();
                this.integrations.addAll(integrations);
                return this;
            }

            public Builder protocolName(String protocolName) {
                this.protocolName = protocolName;
                return this;
            }

            public Builder delegator(GoDelegator delegator) {
                this.delegator = delegator;
                return this;
            }

            @Override
            public GenerationContext build() {
                return new GenerationContext(this);
            }
        }
    }
}
