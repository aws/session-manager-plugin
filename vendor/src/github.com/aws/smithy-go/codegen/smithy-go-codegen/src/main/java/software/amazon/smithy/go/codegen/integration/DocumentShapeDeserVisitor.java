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

import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;

/**
 * Visitor to generate deserialization functions for shapes in protocol document bodies.
 * <p>
 * Visitor methods for aggregate types except maps and collections are final and will
 * generate functions that dispatch their loading from the body to the matching abstract method.
 * <p>
 * Visitor methods for all other types will default to not generating deserialization
 * functions. This may be overwritten by downstream implementations if the protocol requires
 * more complex deserialization strategies for those types.
 * <p>
 * The standard implementation is as follows; no assumptions are made about the protocol
 * being generated for.
 *
 * <ul>
 *   <li>Service, Operation, Resource: no function generated. <b>Not overridable.</b></li>
 *   <li>Document, List, Map, Set, Structure, Union: generates a deserialization function.
 *     <b>Not overridable.</b></li>
 *   <li>All other types: no function generated. <b>May be overridden.</b></li>
 * </ul>
 */
public abstract class DocumentShapeDeserVisitor extends ShapeVisitor.Default<Void> {
    public interface DeserializerNameProvider {
        String getName(Shape shape, ServiceShape service, String protocol);
    }

    private final GenerationContext context;
    private final DeserializerNameProvider deserializerNameProvider;

    public DocumentShapeDeserVisitor(GenerationContext context) {
        this(context, null);
    }

    public DocumentShapeDeserVisitor(GenerationContext context, DeserializerNameProvider deserializerNameProvider) {
        this.context = context;
        this.deserializerNameProvider = deserializerNameProvider;
    }

    /**
     * Gets the generation context.
     *
     * @return The generation context.
     */
    protected final GenerationContext getContext() {
        return context;
    }

    @Override
    protected Void getDefault(Shape shape) {
        return null;
    }

    /**
     * Writes the code needed to deserialize a collection in the document of a response.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns the type generated for the CollectionShape {@code shape} parameter.
     *
     * <p>For example, given the following Smithy model:
     *
     * <pre>{@code
     * list ParameterList {
     *     member: Parameter
     * }
     * }</pre>
     *
     * <p>The function signature for this body will return only {@code error} and have at
     * least one parameter in scope:
     * <ul>
     *   <li>{@code v *[]*string}: a pointer to the location the resulting list should be deserialized to.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json, for instance, you may want to pass around a {@code *json.Decoder} to handle parsing
     * the json.
     *
     * <p>The function could end up looking like:
     *
     * <pre>{@code
     * func myProtocol_deserializeDocumentParameterList(v *[]*types.Parameter, decoder *json.Decoder) error {
     *     if v == nil {
     *         return fmt.Errorf("unexpected nil of type %T", v)
     *     }
     *     startToken, err := decoder.Token()
     *     if err == io.EOF {
     *         return nil
     *     }
     *     if err != nil {
     *         return err
     *     }
     *     if startToken == nil {
     *         return nil
     *     }
     *     if t, ok := startToken.(json.Delim); !ok || t != '[' {
     *         return fmt.Errorf("expect `[` as start token")
     *     }
     *
     *     var cv []*types.Parameter
     *     if *v == nil {
     *         cv = []*types.Parameter{}
     *     } else {
     *         cv = *v
     *     }
     *
     *     for decoder.More() {
     *         var col *types.Parameter
     *         if err := myProtocol_deserializeDocumentParameter(&col, decoder); err != nil {
     *             return err
     *         }
     *         cv = append(cv, col)
     *     }
     *
     *     endToken, err := decoder.Token()
     *     if err != nil {
     *         return err
     *     }
     *     if t, ok := endToken.(json.Delim); !ok || t != ']' {
     *         return fmt.Errorf("expect `]` as end token")
     *     }
     *
     *     *v = cv
     *     return nil
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param shape   The collection shape being generated.
     */
    protected abstract void deserializeCollection(GenerationContext context, CollectionShape shape);

    /**
     * Writes the code needed to deserialize a document shape in the document of a response.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns the type generated for the DocumentShape {@code shape} parameter.
     *
     * <p>For example, given the following Smithy model:
     *
     * <pre>{@code
     * document FooDocument
     * }</pre>
     *
     * <p>The function signature for this body will return only {@code error} and have at
     * least one parameter in scope:
     * <ul>
     *   <li>{@code v *Document}: a pointer to the location the resulting document should be deserialized to.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json, for instance, you may want to pass around a {@code *json.Decoder} to handle parsing
     * the json.
     *
     * @param context The generation context.
     * @param shape   The document shape being generated.
     */
    protected abstract void deserializeDocument(GenerationContext context, DocumentShape shape);

    /**
     * Writes the code needed to deserialize a map in the document of a response.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns the type generated for the MapShape {@code shape} parameter.
     *
     * <p>For example, given the following Smithy model:
     *
     * <pre>{@code
     * map c {
     *     key: String,
     *     value: Field
     * }
     * }</pre>
     *
     * <p>The function signature for this body will return only {@code error} and have at
     * least one parameter in scope:
     * <ul>
     *   <li>{@code v *map[string]*types.FieldMap}: a pointer to the location the resulting map should
     *   be deserialized to.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json, for instance, you may want to pass around a {@code *json.Decoder} to handle parsing
     * the json.
     *
     * <p>The function could end up looking like:
     *
     * <pre>{@code
     * func myProtocol_deserializeDocumentFieldMap(v *map[string]*types.FieldMap, decoder *json.Decoder) error {
     *     if v == nil {
     *         return fmt.Errorf("unexpected nil of type %T", v)
     *     }
     *     startToken, err := decoder.Token()
     *     if err == io.EOF {
     *         return nil
     *     }
     *     if err != nil {
     *         return err
     *     }
     *     if startToken == nil {
     *         return nil
     *     }
     *     if t, ok := startToken.(json.Delim); !ok || t != '{' {
     *         return fmt.Errorf("expect `{` as start token")
     *     }
     *
     *     var mv map[string]*types.FieldMap
     *     if *v == nil {
     *         mv = map[string]*types.FieldMap{}
     *     } else {
     *         mv = *v
     *     }
     *
     *     for decoder.More() {
     *         token, err := decoder.Token()
     *         if err != nil {
     *             return err
     *         }
     *
     *         key, ok := token.(string)
     *         if !ok {
     *             return fmt.Errorf("expected map-key of type string, found type %T", token)
     *         }
     *
     *         var parsedVal *types.FieldMap
     *         if err := myProtocol_deserializeDocumentFieldMap(&parsedVal, decoder); err != nil {
     *             return err
     *         }
     *         mv[key] = parsedVal
     *
     *     }
     *     endToken, err := decoder.Token()
     *     if err != nil {
     *         return err
     *     }
     *     if t, ok := endToken.(json.Delim); !ok || t != '}' {
     *         return fmt.Errorf("expect `}` as end token")
     *     }
     *
     *     *v = mv
     *     return nil
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param shape   The map shape being generated.
     */
    protected abstract void deserializeMap(GenerationContext context, MapShape shape);

    /**
     * Writes the code needed to deserialize a structure in the document of a response.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns the type generated for the StructureShape {@code shape} parameter.
     *
     * <p>For example, given the following Smithy model:
     *
     * <pre>{@code
     * structure Field {
     *     FooValue: Foo,
     *     BarValue: String,
     * }
     * }</pre>
     *
     * <p>The function signature for this body will return only {@code error} and have at
     * least one parameter in scope:
     * <ul>
     *   <li>{@code v **types.Field}: a pointer to the location the resulting structure should be deserialized to.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json, for instance, you may want to pass around a {@code *json.Decoder} to handle parsing
     * the json.
     *
     * <p>The function could end up looking like:
     *
     * <pre>{@code
     * func myProtocol_deserializeDocumentField(v **types.Field, decoder *json.Decoder) error {
     *     if v == nil {
     *         return fmt.Errorf("unexpected nil of type %T", v)
     *     }
     *     startToken, err := decoder.Token()
     *     if err == io.EOF {
     *         return nil
     *     }
     *     if err != nil {
     *         return err
     *     }
     *     if startToken == nil {
     *         return nil
     *     }
     *     if t, ok := startToken.(json.Delim); !ok || t != '{' {
     *         return fmt.Errorf("expect `{` as start token")
     *     }
     *
     *     var sv *types.KitchenSink
     *     if *v == nil {
     *         sv = &types.KitchenSink{}
     *     } else {
     *         sv = *v
     *     }
     *
     *     for decoder.More() {
     *         t, err := decoder.Token()
     *         if err != nil {
     *             return err
     *         }
     *         switch t {
     *         case "FooValue":
     *             if err := myProtocol_deserializeDocumentFoo(&sv.FooValue, decoder); err != nil {
     *                 return err
     *             }
     *         case "BarValue":
     *             val, err := decoder.Token()
     *             if err != nil {
     *                 return err
     *             }
     *             if val != nil {
     *                 jtv, ok := val.(string)
     *                 if !ok {
     *                     return fmt.Errorf("expected String to be of type string, got %T instead", val)
     *                 }
     *                 sv.BarValue = &jtv
     *             }
     *         default:
     *             // Discard the unknown
     *         }
     *     }
     *     endToken, err := decoder.Token()
     *     if err != nil {
     *         return err
     *     }
     *     if t, ok := endToken.(json.Delim); !ok || t != '}' {
     *         return fmt.Errorf("expect `}` as end token")
     *     }
     *     *v = sv
     *     return nil
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param shape   The structure shape being generated.
     */
    protected abstract void deserializeStructure(GenerationContext context, StructureShape shape);

    /**
     * Writes the code needed to deserialize a union in the document of a response.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns the type generated for the UnionShape {@code shape} parameter.
     *
     * <pre>{@code
     * union Field {
     *     fooValue: Foo,
     *     barValue: String,
     * }
     * }</pre>
     *
     * <p>The function signature for this body will return only {@code error} and have at
     * least one parameter in scope:
     * <ul>
     *   <li>{@code v *Field}: a pointer to the location the resulting union should be deserialized to.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json, for instance, you may want to pass around a {@code *json.Decoder} to handle parsing
     * the json.
     *
     * @param context The generation context.
     * @param shape   The union shape being generated.
     */
    protected abstract void deserializeUnion(GenerationContext context, UnionShape shape);

    /**
     * Generates a function for deserializing the output shape, dispatching body handling
     * to the supplied function.
     *
     * @param shape        The shape to generate a deserializer for.
     * @param functionBody An implementation that will generate a function body to
     *                     deserialize the shape.
     */
    protected final void generateDeserFunction(
            Shape shape,
            BiConsumer<GenerationContext, Shape> functionBody
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        GoWriter writer = context.getWriter().get();

        Symbol symbol = symbolProvider.toSymbol(shape);

        final String functionName;
        if (this.deserializerNameProvider != null) {
            functionName = deserializerNameProvider.getName(shape, context.getService(), context.getProtocolName());
        } else {
            functionName = ProtocolGenerator.getDocumentDeserializerFunctionName(
                    shape, context.getService(), context.getProtocolName());
        }

        String additionalArguments = getAdditionalArguments().entrySet().stream()
                .map(entry -> String.format(", %s %s", entry.getKey(), entry.getValue()))
                .collect(Collectors.joining());

        writer.openBlock("func $L(v *$P$L) error {", "}", functionName, symbol, additionalArguments, () -> {
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.openBlock("if v == nil {", "}", () -> {
                writer.write("return fmt.Errorf(\"unexpected nil of type %T\", v)");
            });
            functionBody.accept(context, shape);
        }).write("");
    }

    /**
     * Gets any additional arguments needed for every deserializer function.
     *
     * @return a map of argument name to argument type.
     */
    protected Map<String, String> getAdditionalArguments() {
        return Collections.emptyMap();
    }

    @Override
    public final Void operationShape(OperationShape shape) {
        throw new CodegenException("Operation shapes cannot be bound to documents.");
    }

    @Override
    public final Void resourceShape(ResourceShape shape) {
        throw new CodegenException("Resource shapes cannot be bound to documents.");
    }

    @Override
    public final Void serviceShape(ServiceShape shape) {
        throw new CodegenException("Service shapes cannot be bound to documents.");
    }

    /**
     * Dispatches to create the body of document shape deserilization functions.
     *
     * @param shape The document shape to generate deserialization for.
     * @return null
     */
    @Override
    public final Void documentShape(DocumentShape shape) {
        generateDeserFunction(shape, (c, s) -> deserializeDocument(c, s.asDocumentShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of list shape deserilization functions.
     *
     * @param shape The list shape to generate deserialization for.
     * @return null
     */
    @Override
    public Void listShape(ListShape shape) {
        generateDeserFunction(shape, (c, s) -> deserializeCollection(c, s.asListShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of map shape deserilization functions.
     *
     * @param shape The map shape to generate deserialization for.
     * @return null
     */
    @Override
    public Void mapShape(MapShape shape) {
        generateDeserFunction(shape, (c, s) -> deserializeMap(c, s.asMapShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of set shape deserilization functions.
     *
     * @param shape The set shape to generate deserialization for.
     * @return null
     */
    @Override
    public Void setShape(SetShape shape) {
        generateDeserFunction(shape, (c, s) -> deserializeCollection(c, s.asSetShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of structure shape deserilization functions.
     *
     * @param shape The structure shape to generate deserialization for.
     * @return null
     */
    @Override
    public final Void structureShape(StructureShape shape) {
        generateDeserFunction(shape, (c, s) -> deserializeStructure(c, s.asStructureShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of union shape deserilization functions.
     *
     * @param shape The union shape to generate deserialization for.
     * @return null
     */
    @Override
    public final Void unionShape(UnionShape shape) {
        generateDeserFunction(shape, (c, s) -> deserializeUnion(c, s.asUnionShape().get()));
        return null;
    }
}
