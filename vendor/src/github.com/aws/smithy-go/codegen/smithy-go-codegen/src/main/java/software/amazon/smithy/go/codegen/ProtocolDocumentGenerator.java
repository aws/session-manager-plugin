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

import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.selector.Selector;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.IoUtils;

/**
 * Generates the service's internal and external document Go packages. The document packages contain the service
 * specific document Interface definition, protocol specific document marshaler and unmarshaller implementations for
 * that interface, and constructors for creating service document types.
 */
public final class ProtocolDocumentGenerator {
    public static final String DOCUMENT_INTERFACE_NAME = "Interface";
    public static final String NO_DOCUMENT_SERDE_TYPE_NAME = "noSmithyDocumentSerde";
    public static final String NEW_LAZY_DOCUMENT = "NewLazyDocument";
    public static final String INTERNAL_NEW_DOCUMENT_MARSHALER_FUNC = "NewDocumentMarshaler";
    public static final String INTERNAL_NEW_DOCUMENT_UNMARSHALER_FUNC = "NewDocumentUnmarshaler";
    public static final String INTERNAL_IS_DOCUMENT_INTERFACE = "IsInterface";
    public static final String UNMARSHAL_SMITHY_DOCUMENT_METHOD = "UnmarshalSmithyDocument";
    public static final String MARSHAL_SMITHY_DOCUMENT_METHOD = "MarshalSmithyDocument";

    private static final String SERVICE_SMITHY_DOCUMENT_INTERFACE = "smithyDocument";
    private static final String IS_SMITHY_DOCUMENT_METHOD = "isSmithyDocument";

    private final GoSettings settings;
    private final GoDelegator delegator;
    private final Model model;
    private final boolean hasDocumentShapes;

    public ProtocolDocumentGenerator(
            GoSettings settings,
            Model model,
            GoDelegator delegator
    ) {
        this.settings = settings;
        this.model = model;
        this.delegator = delegator;

        ShapeId serviceId = settings.getService().toShapeId();
        this.hasDocumentShapes = Selector.parse(String.format("[service = %s] ~> document", serviceId))
                .matches(model).findAny()
                .isPresent();
    }

    /**
     * Generates any required client types or functions to support protocol document types.
     */
    public void generateDocumentSupport() {
        generateNoSerdeType();
        generateInternalDocumentInterface();
        generateDocumentPackage();
    }

    /**
     * Generates the publicly accessible service document package. This package contains a type alias definition
     * for document interface, as well as a constructor function for creating a document marshaller.
     * <p>
     * This package is not generated if the service does not have any document shapes in the model.
     *
     * <pre>{@code
     * // <servicePath>/document
     * package document
     *
     * import (
     *      internaldocument "<servicePath>/internal/document"
     * )
     *
     * type Interface = internaldocument.Interface
     *
     * func NewLazyDocument(v interface{}) Interface {
     *      return internaldocument.NewDocumentMarshaler(v)
     * }
     * }</pre>
     */
    private void generateDocumentPackage() {
        if (!this.hasDocumentShapes) {
            return;
        }

        writeDocumentPackage("doc.go", writer -> {
            String documentTemplate = IoUtils.readUtf8Resource(getClass(), "document_doc.go.template");
            writer.writeRawPackageDocs(documentTemplate);
        });

        writeDocumentPackage("document.go", writer -> {
            writer.writeDocs(String.format("%s defines a document which is a protocol-agnostic type which supports a "
                    + "JSON-like data-model. You can use this type to send UTF-8 strings, arbitrary precision "
                    + "numbers, booleans, nulls, a list of these values, and a map of UTF-8 strings to these "
                    + "values.", DOCUMENT_INTERFACE_NAME));
            writer.writeDocs("");
            writer.writeDocs(String.format("You create a document type using the %s function and passing it the Go "
                    + "type to marshal. When receiving a document in an API response, you use the "
                    + "document's UnmarshalSmithyDocument function to decode the response to your desired Go "
                    + "type. Unless documented specifically generated structure types in client packages or "
                    + "client types packages are not supported at this time. Such types embed a "
                    + "noSmithyDocumentSerde and will cause an error to be returned when attempting to send an "
                    + "API request.", NEW_LAZY_DOCUMENT));
            writer.writeDocs("");
            writer.writeDocs("For more information see the accompanying package documentation and linked references.");
            writer.write("type $L = $T", DOCUMENT_INTERFACE_NAME,
                            getInternalDocumentSymbol(DOCUMENT_INTERFACE_NAME))
                    .write("");

            writer.writeDocs(String.format("You create document type using the %s function and passing it the Go "
                    + "type to be marshaled and sent to the service. The document marshaler supports semantics similar "
                    + "to the encoding/json Go standard library.", NEW_LAZY_DOCUMENT));
            writer.writeDocs("");
            writer.writeDocs("For more information see the accompanying package documentation and linked references.");
            writer.openBlock("func $L(v interface{}) $T {", "}", NEW_LAZY_DOCUMENT,
                            getDocumentSymbol(DOCUMENT_INTERFACE_NAME), () -> {
                                writer.write("return $T(v)",
                                        getInternalDocumentSymbol(INTERNAL_NEW_DOCUMENT_MARSHALER_FUNC));
                            })
                    .write("");
        });
    }

    /**
     * Generates an unexported type alias for the {@code github.com/aws/smithy-go/document#NoSerde} type in both the
     * service and types package. This allows for this type to be used as an embedded member in structures to
     * prevent usage of generated Smithy structure shapes as document types. Additionally, since the member is
     * unexported this prevents the need de-conflict naming collisions.
     * <p>
     * These type aliases are always generated regardless of whether there are document shapes present in the model
     * or not.
     *
     * <pre>{@code
     * package types
     *
     * type noSmithyDocumentSerde = smithydocument.NoSerde
     *
     * type ExampleStructureShape struct {
     *      FieldOne *string
     *
     *      noSmithyDocumentSerde
     * }
     *
     * }</pre>
     */
    private void generateNoSerdeType() {
        Symbol noSerde = SymbolUtils.createValueSymbolBuilder("NoSerde",
                SmithyGoDependency.SMITHY_DOCUMENT).build();

        delegator.useShapeWriter(settings.getService(model), writer -> {
            writer.write("type $L = $T", NO_DOCUMENT_SERDE_TYPE_NAME, noSerde);
        });

        delegator.useFileWriter("./types/types.go", settings.getModuleName() + "/types", writer -> {
            writer.write("type $L = $T", NO_DOCUMENT_SERDE_TYPE_NAME, noSerde);
        });
    }

    /**
     * Generates the document interface definition in the internal document package.
     *
     * <pre>{@code
     * import smithydocument "github.com/aws/smithy-go/document"
     *
     * type smithyDocument interface {
     *      isSmithyDocument()
     * }
     *
     * type Interface interface {
     *      smithydocument.Marshaler
     *      smithydocument.Unmarshaler
     *      smithyDocument
     * }
     * }</pre>
     */
    private void generateInternalDocumentInterface() {
        if (!this.hasDocumentShapes) {
            return;
        }

        Symbol serviceSmithyDocumentInterface = getInternalDocumentSymbol(SERVICE_SMITHY_DOCUMENT_INTERFACE);
        Symbol internalDocumentInterface = getInternalDocumentSymbol(DOCUMENT_INTERFACE_NAME);
        Symbol smithyDocumentMarshaler = SymbolUtils.createValueSymbolBuilder("Marshaler",
                SmithyGoDependency.SMITHY_DOCUMENT).build();
        Symbol smithyDocumentUnmarshaler = SymbolUtils.createValueSymbolBuilder("Unmarshaler",
                SmithyGoDependency.SMITHY_DOCUMENT).build();

        writeInternalDocumentPackage("document.go", writer -> {
            writer.writeDocs(String.format("%s is an interface which is used to bind"
                    + " a document type to its service client.", serviceSmithyDocumentInterface));
            writer.openBlock("type $T interface {", "}", serviceSmithyDocumentInterface,
                            () -> writer.write("$L()", IS_SMITHY_DOCUMENT_METHOD))
                    .write("");

            writer.writeDocs(String.format("%s is a JSON-like data model type that is protocol agnostic and is used"
                    + "to send open-content to a service.", internalDocumentInterface));
            writer.openBlock("type $T interface {", "}", internalDocumentInterface, () -> {
                writer.write("$T", serviceSmithyDocumentInterface);
                writer.write("$T", smithyDocumentMarshaler);
                writer.write("$T", smithyDocumentUnmarshaler);
            }).write("");
        });

        writeInternalDocumentPackage("document_test.go", writer -> {
            writer.write("var _ $T = ($P)(nil)",
                    serviceSmithyDocumentInterface, internalDocumentInterface);
            writer.write("var _ $T = ($P)(nil)",
                    smithyDocumentMarshaler, internalDocumentInterface);
            writer.write("var _ $T = ($P)(nil)",
                    smithyDocumentUnmarshaler,
                    internalDocumentInterface);
            writer.write("");
        });
    }

    /**
     * Generates the internal document Go package for the service client. Delegates the logic for document marshaling
     * and unmarshalling types to the provided protocol generator using the given context.
     * <p>
     * Generate a document marshaler type for marshaling documents to the service's protocol document format.
     *
     * <pre>{@code
     * type documentMarshaler struct {
     *     value interface{}
     * }
     *
     * func NewDocumentMarshaler(v interface{}) Interface {
     *     // default or protocol implementation
     * }
     *
     * func (m *documentMarshaler) UnmarshalSmithyDocument(v interface{}) error {
     *     // implemented by protocol generator
     * }
     *
     * func (m *documentUnmarshaler) MarshalSmithyDocument() ([]byte, error) {
     *     // implemented by protocol generator
     * }
     * }</pre>
     * <p>
     * Generate a document marshaler type for unmarshalling documents from the service's protocol response to a Go
     * type.
     *
     * <pre>{@code
     * type documentUnmarshaler struct {
     *     value interface{}
     * }
     * func NewDocumentUnmarshaler(v interface{}) Interface {
     *     // default or protocol implementation
     * }
     *
     * func (m *documentUnmarshaler) UnmarshalSmithyDocument(v interface{}) error {
     *     // implemented by protocol generator
     * }
     *
     * func (m *documentUnmarshaler) MarshalSmithyDocument() ([]byte, error) {
     *     // implemented by protocol generator
     * }
     * }</pre>
     * <p>
     * Generate {@code IsInterface} function which is used to assert whether a given document type
     * is a valid service protocol document type implementation.
     *
     * <pre>{@code
     * func IsInterface(v Interface) bool {
     *     // implementation
     * }
     * }</pre>
     *
     * @param protocolGenerator the protocol generator.
     * @param context           the protocol generator context.
     */
    public void generateInternalDocumentTypes(ProtocolGenerator protocolGenerator, GenerationContext context) {
        if (!this.hasDocumentShapes) {
            return;
        }

        writeInternalDocumentPackage("document.go", writer -> {
            Symbol marshalerSymbol = getInternalDocumentSymbol("documentMarshaler",
                    true);
            Symbol unmarshalerSymbol = getInternalDocumentSymbol("documentUnmarshaler", true);

            Symbol isDocumentInterface = getInternalDocumentSymbol(INTERNAL_IS_DOCUMENT_INTERFACE);

            writeInternalDocumentImplementation(
                    writer,
                    marshalerSymbol,
                    () -> {
                        protocolGenerator.generateProtocolDocumentMarshalerUnmarshalDocument(context.toBuilder()
                                .writer(writer)
                                .build());
                    },
                    () -> {
                        protocolGenerator.generateProtocolDocumentMarshalerMarshalDocument(context.toBuilder()
                                .writer(writer)
                                .build());
                    });
            writeInternalDocumentImplementation(writer,
                    unmarshalerSymbol,
                    () -> {
                        protocolGenerator.generateProtocolDocumentUnmarshalerUnmarshalDocument(context.toBuilder()
                                .writer(writer)
                                .build());
                    },
                    () -> {
                        protocolGenerator.generateProtocolDocumentUnmarshalerMarshalDocument(context.toBuilder()
                                .writer(writer)
                                .build());
                    });

            Symbol documentInterfaceSymbol = getInternalDocumentSymbol(DOCUMENT_INTERFACE_NAME);

            writer.writeDocs(String.format("%s creates a new document marshaler for the given input type",
                    INTERNAL_NEW_DOCUMENT_MARSHALER_FUNC));
            writer.openBlock("func $L(v interface{}) $T {", "}", INTERNAL_NEW_DOCUMENT_MARSHALER_FUNC,
                    documentInterfaceSymbol, () -> {
                        protocolGenerator.generateNewDocumentMarshaler(context.toBuilder()
                                .writer(writer)
                                .build(), marshalerSymbol);
                    }).write("");

            writer.writeDocs(String.format("%s creates a new document unmarshaler for the given service response",
                    INTERNAL_NEW_DOCUMENT_UNMARSHALER_FUNC));
            writer.openBlock("func $L(v interface{}) $T {", "}", INTERNAL_NEW_DOCUMENT_UNMARSHALER_FUNC,
                    documentInterfaceSymbol, () -> {
                        protocolGenerator.generateNewDocumentUnmarshaler(context.toBuilder()
                                .writer(writer)
                                .build(), unmarshalerSymbol);
                    }).write("");

            writer.writeDocs(String.format("%s returns whether the given Interface implementation is"
                    + " a valid client implementation", isDocumentInterface));
            writer.openBlock("func $T(v Interface) (ok bool) {", "}", isDocumentInterface, () -> {
                writer.openBlock("defer func() {", "}()", () -> {
                    writer.openBlock("if err := recover(); err != nil {", "}", () -> writer.write("ok = false"));
                });
                writer.write("v.$L()", IS_SMITHY_DOCUMENT_METHOD);
                writer.write("return true");
            }).write("");
        });
    }

    private void writeInternalDocumentImplementation(
            GoWriter writer,
            Symbol typeSymbol,
            Runnable unmarshalMethodDefinition,
            Runnable marshalMethodDefinition
    ) {
        writer.openBlock("type $T struct {", "}", typeSymbol, () -> {
            writer.write("value interface{}");
        });
        writer.write("");

        writer.openBlock("func (m $P) $L(v interface{}) error {", "}", typeSymbol, UNMARSHAL_SMITHY_DOCUMENT_METHOD,
                unmarshalMethodDefinition);
        writer.write("");

        writer.openBlock("func (m $P) $L() ([]byte, error) {", "}", typeSymbol, MARSHAL_SMITHY_DOCUMENT_METHOD,
                marshalMethodDefinition);
        writer.write("");

        writer.write("func (m $P) $L() {}", typeSymbol, IS_SMITHY_DOCUMENT_METHOD);
        writer.write("");

        writer.write("var _ $T = ($P)(nil)", getInternalDocumentSymbol(DOCUMENT_INTERFACE_NAME, true), typeSymbol);
        writer.write("");
    }

    private void writeDocumentPackage(String fileName, Consumer<GoWriter> writerConsumer) {
        delegator.useFileWriter(getDocumentFilePath(fileName), getDocumentPackage(), writerConsumer);
    }

    private void writeInternalDocumentPackage(String fileName, Consumer<GoWriter> writerConsumer) {
        delegator.useFileWriter(getInternalDocumentFilePath(fileName), getInternalDocumentPackage(), writerConsumer);
    }

    private String getInternalDocumentPackage() {
        return Utilities.getInternalDocumentPackage(settings);
    }

    private String getDocumentPackage() {
        return Utilities.getDocumentPackage(settings);
    }

    private String getInternalDocumentFilePath(String fileName) {
        return "./internal/document/" + fileName;
    }

    private String getDocumentFilePath(String fileName) {
        return "./document/" + fileName;
    }

    private Symbol getDocumentSymbol(String typeName) {
        return getDocumentSymbol(typeName, false);
    }

    private Symbol getDocumentSymbol(String typeName, boolean pointable) {
        return Utilities.getDocumentSymbolBuilder(settings, typeName, pointable).build();
    }

    private Symbol getInternalDocumentSymbol(String typeName) {
        return getInternalDocumentSymbol(typeName, false);
    }

    private Symbol getInternalDocumentSymbol(String typeName, boolean pointable) {
        return Utilities.getInternalDocumentSymbolBuilder(settings, typeName, pointable).build();
    }

    /**
     * Collection of helper utility functions for creating references to the service client's internal
     * and external document package types.
     */
    public static final class Utilities {
        /**
         * Create a non-pointable {@link Symbol.Builder} for typeName in the service's document package.
         *
         * @param settings the Smithy Go settings.
         * @param typeName the name of the Go type.
         * @return the symbol builder.
         */
        public static Symbol.Builder getDocumentSymbolBuilder(GoSettings settings, String typeName) {
            return getDocumentSymbolBuilder(settings, typeName, false);
        }

        /**
         * Create {@link Symbol.Builder} for typeName in the service's document package.
         *
         * @param settings  the Smithy Go settings.
         * @param typeName  the name of the Go type.
         * @param pointable whether typeName is pointable.
         * @return the symbol builder.
         */
        public static Symbol.Builder getDocumentSymbolBuilder(
                GoSettings settings,
                String typeName,
                boolean pointable
        ) {
            return pointable
                    ? SymbolUtils.createPointableSymbolBuilder(typeName, getDocumentPackage(settings))
                    : SymbolUtils.createValueSymbolBuilder(typeName, getDocumentPackage(settings));
        }

        /**
         * Create a non-pointable {@link Symbol.Builder} for typeName in the service's internal document package.
         *
         * @param settings the Smithy Go settings.
         * @param typeName the name of the Go type.
         * @return the symbol builder.
         */
        public static Symbol.Builder getInternalDocumentSymbolBuilder(GoSettings settings, String typeName) {
            return getInternalDocumentSymbolBuilder(settings, typeName, false);
        }

        /**
         * Create {@link Symbol.Builder} for typeName in the service's internal document package.
         *
         * @param settings  the Smithy Go settings.
         * @param typeName  the name of the Go type.
         * @param pointable whether typeName is pointable.
         * @return the symbol builder.
         */
        public static Symbol.Builder getInternalDocumentSymbolBuilder(
                GoSettings settings,
                String typeName,
                boolean pointable
        ) {
            Symbol.Builder builder = pointable
                    ? SymbolUtils.createPointableSymbolBuilder(typeName, getInternalDocumentPackage(settings))
                    : SymbolUtils.createValueSymbolBuilder(typeName, getInternalDocumentPackage(settings));
            builder.putProperty(SymbolUtils.NAMESPACE_ALIAS, "internaldocument");
            return builder;
        }

        private static String getInternalDocumentPackage(GoSettings settings) {
            return settings.getModuleName() + "/internal/document";
        }

        private static String getDocumentPackage(GoSettings settings) {
            return settings.getModuleName() + "/document";
        }
    }
}
