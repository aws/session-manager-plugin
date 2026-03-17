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
 * Visitor to generate serialization for shapes in protocol document bodies.
 * <p>
 * Visitor methods for aggregate types are final and will generate functions that dispatch
 * their body generation to the matching abstract method.
 * <p>
 * Visitor methods for all other types will default to not generating serialization functions.
 * This may be overwritten by downstream implementations if the protocol requires a more
 * complex serialization strategy for those types.
 * <p>
 * The standard implementation is as follows; no assumptions are made about the protocol
 * being generated for.
 *
 * <ul>
 *   <li>Service, Operation, Resource: no function generated. <b>Not overridable.</b></li>
 *   <li>Document, List, Map, Set, Structure, Union: generates a serialization function.
 *     <b>Not overridable.</b></li>
 *   <li>All other types: no function generated. <b>May be overridden.</b></li>
 * </ul>
 */
public abstract class DocumentShapeSerVisitor extends ShapeVisitor.Default<Void> {
    public interface SerializerNameProvider {
        String getName(Shape shape, ServiceShape service, String protocol);
    }

    private final GenerationContext context;
    private final SerializerNameProvider serializerNameProvider;

    public DocumentShapeSerVisitor(GenerationContext context) {
        this(context, null);
    }

    public DocumentShapeSerVisitor(GenerationContext context, SerializerNameProvider serializerNameProvider) {
        this.context = context;
        this.serializerNameProvider = serializerNameProvider;
    }

    /**
     * Gets the generation context.
     *
     * @return The generation context.
     */
    protected final GenerationContext getContext() {
        return context;
    }

    /**
     * Writes the code needed to serialize a collection in the document of a request.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns a value representing the CollectionShape {@code shape} parameter.
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
     *   <li>{@code v []*types.Parameter}: the list to be serialized.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json protocols, for instance, smithy has the Value accumulator that can be passed
     * around to build up the json.
     *
     * <p>The function could end up looking like:
     *
     * <pre>{@code
     * func myProtocol_serializeDocumentParameterList(v []*types.Parameter, value smithyjson.Value) error {
     *     array := value.Array()
     *     defer array.Close()
     *
     *     for i := range v {
     *         av := array.Value()
     *         if vv := v[i]; vv == nil {
     *             av.Null()
     *             continue
     *         }
     *         if err := myProtocol_serializeDocumentParameter(v[i], av); err != nil {
     *             return err
     *         }
     *     }
     *     return nil
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param shape   The collection shape being generated.
     */
    protected abstract void serializeCollection(GenerationContext context, CollectionShape shape);

    /**
     * Writes the code needed to serialize a document shape in the document of a request.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns a value representing the DocumentShape {@code shape} parameter.
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
     *   <li>{@code v Document}: the document to be serialized.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json protocols, for instance, smithy has the Value accumulator that can be passed
     * around to build up the json.
     *
     * @param context The generation context.
     * @param shape   The document shape being generated.
     */
    protected abstract void serializeDocument(GenerationContext context, DocumentShape shape);

    /**
     * Writes the code needed to serialize a map in the document of a request.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns a value representing the MapShape {@code shape} parameter.
     *
     * <p>For example, given the following Smithy model:
     *
     * <pre>{@code
     * map FieldMap {
     *     key: String,
     *     value: Field
     * }
     * }</pre>
     *
     * <p>The function signature for this body will return only {@code error} and have at
     * least one parameter in scope:
     * <ul>
     *   <li>{@code v map[string]*types.Field}: the map to be serialized.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json protocols, for instance, smithy has the Value accumulator that can be passed
     * around to build up the json.
     *
     * <p>The function could end up looking like:
     *
     * <pre>{@code
     * func myProtocol_serializeDocumentFieldMap(v map[string]*types.Field, value smithyjson.Value) error {
     *     object := value.Object()
     *     defer object.Close()
     *
     *     for key := range v {
     *         om := object.Key(key)
     *         if vv := v[key]; vv == nil {
     *             om.Null()
     *             continue
     *         }
     *         if err := myProtocol_serializeDocumentField(v[key], om); err != nil {
     *             return err
     *         }
     *     }
     *     return nil
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param shape   The map shape being generated.
     */
    protected abstract void serializeMap(GenerationContext context, MapShape shape);

    /**
     * Writes the code needed to serialize a structure in the document of a request.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns a value representing the StructureShape {@code shape} parameter.
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
     *   <li>{@code v *types.Field}: the structure to be serialized.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json protocols, for instance, smithy has the Value accumulator that can be passed
     * around to build up the json.
     *
     * <p>The function could end up looking like:
     *
     * <pre>{@code
     * func myProtocol_serializeDocumentField(v *types.Field, value smithyjson.Value) error {
     *     object := value.Object()
     *     defer object.Close()
     *
     *     if v.FooValue != nil {
     *         ok := object.Key("FooValue")
     *         if err := myProtocol_serializeDocumentFoo(v.FooValue, ok); err != nil {
     *             return err
     *         }
     *     }
     *
     *     if v.BarValue != nil {
     *         ok := object.Key("BarValue")
     *         ok.String(*v.Value)
     *     }
     *
     *     return nil
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param shape   The structure shape being generated.
     */
    protected abstract void serializeStructure(GenerationContext context, StructureShape shape);

    /**
     * Writes the code needed to serialize a union in the document of a request.
     *
     * <p>Implementations of this method are expected to generate a function body that
     * returns a value representing the UnionShape {@code shape} parameter.
     *
     * <p>For example, given the following Smithy model:
     *
     * <pre>{@code
     * union Field {
     *     FooValue: Foo,
     *     BarValue: String,
     * }
     * }</pre>
     *
     * <p>The function signature for this body will return only {@code error} and have at
     * least one parameter in scope:
     * <ul>
     *   <li>{@code v types.Field}: the union to be serialized.</li>
     * </ul>
     *
     * <p>It will also have any parameters in scope as defined by {@code getAdditionalArguments}.
     * For json protocols, for instance, smithy has the Value accumulator that can be passed
     * around to build up the json.
     *
     * <p>The function could end up looking like:
     *
     * <pre>{@code
     * func myProtocol_serializeDocumentField(v types.Field, value smithyjson.Value) error {
     *     object := value.Object()
     *     defer object.Close()
     *
     *     switch uv := v.(type) {
     *     case *types.FieldFooValue:
     *         ok := object.Key("FooValue")
     *         if err := myProtocol_serializeDocumentFoo(v.FooValue, ok); err != nil {
     *             return err
     *         }
     *     case *types.FieldBarValue:
     *         ok := object.Key("BarValue")
     *         ok.String(*v.Value)
     *     case *types.FieldUnknown:
     *         return fmt.Errorf("unknown member type %T for union %T", uv, v)
     *     }
     *
     *     return nil
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param shape   The union shape being generated.
     */
    protected abstract void serializeUnion(GenerationContext context, UnionShape shape);

    /**
     * Generates a function for serializing the input shape, dispatching the body generation
     * to the supplied function.
     *
     * @param shape        The shape to generate a serializer for.
     * @param functionBody An implementation that will generate a function body to serialize the shape.
     */
    private void generateSerFunction(
            Shape shape,
            BiConsumer<GenerationContext, Shape> functionBody
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        GoWriter writer = context.getWriter().get();

        Symbol symbol = symbolProvider.toSymbol(shape);

        final String functionName;
        if (serializerNameProvider != null) {
            functionName = serializerNameProvider.getName(shape, context.getService(), context.getProtocolName());
        } else {
            functionName = ProtocolGenerator.getDocumentSerializerFunctionName(
                    shape, context.getService(), context.getProtocolName());
        }

        String additionalArguments = getAdditionalSerArguments().entrySet().stream()
                .map(entry -> String.format(", %s %s", entry.getKey(), entry.getValue()))
                .collect(Collectors.joining());

        writer.openBlock("func $L(v $P$L) error {", "}",
                functionName, symbol, additionalArguments, () -> {
                    functionBody.accept(context, shape);
                });
        writer.write("");
    }

    /**
     * Gets any additional arguments needed for every serializer function.
     * <p>
     * For example, a json protocol may wish to pass around a {@code smithy/json.Value} builder.
     *
     * @return a map of argument name to argument type.
     */
    protected Map<String, String> getAdditionalSerArguments() {
        return Collections.emptyMap();
    }

    @Override
    protected Void getDefault(Shape shape) {
        return null;
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
     * Dispatches to create the body of document shape serialization functions.
     *
     * @param shape The document shape to generate serialization for.
     * @return null
     */
    @Override
    public final Void documentShape(DocumentShape shape) {
        generateSerFunction(shape, (c, s) -> serializeDocument(c, s.asDocumentShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of list shape serialization functions.
     *
     * @param shape The list shape to generate serialization for.
     * @return null
     */
    @Override
    public final Void listShape(ListShape shape) {
        generateSerFunction(shape, (c, s) -> serializeCollection(c, s.asListShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of map shape serialization functions.
     *
     * @param shape The map shape to generate serialization for.
     * @return null
     */
    @Override
    public final Void mapShape(MapShape shape) {
        generateSerFunction(shape, (c, s) -> serializeMap(c, s.asMapShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of set shape serialization functions.
     *
     * @param shape The set shape to generate serialization for.
     * @return null
     */
    @Override
    public final Void setShape(SetShape shape) {
        generateSerFunction(shape, (c, s) -> serializeCollection(c, s.asSetShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of structure shape serialization functions.
     *
     * @param shape The structure shape to generate serialization for.
     * @return null
     */
    @Override
    public final Void structureShape(StructureShape shape) {
        generateSerFunction(shape, (c, s) -> serializeStructure(c, s.asStructureShape().get()));
        return null;
    }

    /**
     * Dispatches to create the body of union shape serialization functions.
     *
     * @param shape The union shape to generate serialization for.
     * @return null
     */
    @Override
    public final Void unionShape(UnionShape shape) {
        generateSerFunction(shape, (c, s) -> serializeUnion(c, s.asUnionShape().get()));
        return null;
    }
}
