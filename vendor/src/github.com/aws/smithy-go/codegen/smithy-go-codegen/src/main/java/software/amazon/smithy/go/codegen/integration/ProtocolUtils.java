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

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.neighbor.RelationshipType;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.SetUtils;

/**
 * Utility functions for protocol generation.
 */
public final class ProtocolUtils {
    public static final MiddlewareIdentifier OPERATION_SERIALIZER_MIDDLEWARE_ID = MiddlewareIdentifier
            .string("OperationSerializer");
    public static final MiddlewareIdentifier OPERATION_DESERIALIZER_MIDDLEWARE_ID = MiddlewareIdentifier
            .string("OperationDeserializer");

    private static final Set<ShapeType> REQUIRES_SERDE = SetUtils.of(
            ShapeType.MAP, ShapeType.LIST, ShapeType.SET, ShapeType.DOCUMENT, ShapeType.STRUCTURE, ShapeType.UNION);
    private static final Set<RelationshipType> MEMBER_RELATIONSHIPS = SetUtils.of(
            RelationshipType.STRUCTURE_MEMBER, RelationshipType.UNION_MEMBER, RelationshipType.LIST_MEMBER,
            RelationshipType.SET_MEMBER, RelationshipType.MAP_VALUE, RelationshipType.MEMBER_TARGET
    );

    private ProtocolUtils() {
    }

    /**
     * Resolves the entire set of shapes that will require serde given an initial set of shapes.
     *
     * @param model  the model
     * @param shapes the shapes to walk and resolve additional required serializers, deserializers for
     * @return the complete set of shapes requiring serializers, deserializers
     */
    public static Set<Shape> resolveRequiredDocumentShapeSerde(Model model, Set<Shape> shapes) {
        Set<ShapeId> processed = new TreeSet<>();
        Set<Shape> resolvedShapes = new TreeSet<>();
        Walker walker = new Walker(model);

        shapes.forEach(shape -> {
            processed.add(shape.getId());
            resolvedShapes.add(shape);
            walker.iterateShapes(shape, relationship -> MEMBER_RELATIONSHIPS.contains(
                            relationship.getRelationshipType()))
                    .forEachRemaining(walkedShape -> {
                        // MemberShape type itself is not what we are interested in
                        if (walkedShape.getType() == ShapeType.MEMBER) {
                            return;
                        }
                        if (processed.contains(walkedShape.getId())) {
                            return;
                        }
                        if (requiresDocumentSerdeFunction(shape)) {
                            resolvedShapes.add(walkedShape);
                            processed.add(walkedShape.getId());
                        }
                    });
        });

        return resolvedShapes;
    }

    /**
     * Determines whether a document serde function is required for the given shape.
     * <p>
     * The following shape types will require a serde function: maps, lists, sets, documents, structures, and unions.
     *
     * @param shape the shape
     * @return true if the shape requires a serde function
     */
    public static boolean requiresDocumentSerdeFunction(Shape shape) {
        return REQUIRES_SERDE.contains(shape.getType());
    }

    /**
     * Gets the operation input as a structure shape or throws an exception.
     *
     * @param model     The model that contains the operation.
     * @param operation The operation to get the input from.
     * @return The operation's input as a structure shape.
     */
    public static StructureShape expectInput(Model model, OperationShape operation) {
        return OperationIndex.of(model).getInput(operation)
                .orElseThrow(() -> new CodegenException(
                        "Expected input shape for operation " + operation.getId().toString()));
    }

    /**
     * Gets the operation output as a structure shape or throws an exception.
     *
     * @param model     The model that contains the operation.
     * @param operation The operation to get the output from.
     * @return The operation's output as a structure shape.
     */
    public static StructureShape expectOutput(Model model, OperationShape operation) {
        return OperationIndex.of(model).getOutput(operation)
                .orElseThrow(() -> new CodegenException(
                        "Expected output shape for operation " + operation.getId().toString()));
    }

    /**
     * Wraps the protocol's delegation function changing the destination variable to a double pointer if the
     * shape type is not pointable.
     *
     * @param context         generation context
     * @param writer          go writer
     * @param member          shape to determine if pointable
     * @param origDestOperand original variable name
     * @param lambda          runnable
     */
    public static void writeDeserDelegateFunction(
            GenerationContext context,
            GoWriter writer,
            MemberShape member,
            String origDestOperand,
            Consumer<String> lambda
    ) {
        Shape targetShape = context.getModel().expectShape(member.getTarget());
        Shape container = context.getModel().expectShape(member.getContainer());

        boolean withAddr = !GoPointableIndex.of(context.getModel()).isPointable(member)
                && GoPointableIndex.of(context.getModel()).isPointable(targetShape);
        boolean isMap = container.getType() == ShapeType.MAP;

        String destOperand = origDestOperand;
        if (isMap) {
            writer.write("mapVar := $L", origDestOperand);
            destOperand = "mapVar";
        }

        if (withAddr) {
            writer.write("destAddr := &$L", destOperand);
            destOperand = "destAddr";
        }

        lambda.accept(destOperand);

        if (isMap || withAddr) {
            if (withAddr) {
                destOperand = "*" + destOperand;
            }

            writer.write("$L = $L", origDestOperand, destOperand);
        }
    }

    /**
     * Writes helper variables for the delegation function to ensure that map values are safely delegated down
     * each level.
     *
     * @param context         generation context
     * @param writer          go writer
     * @param member          shape to determine if pointable
     * @param origDestOperand original variable name
     * @param lambda          runnable
     */
    public static void writeSerDelegateFunction(
            GenerationContext context,
            GoWriter writer,
            MemberShape member,
            String origDestOperand,
            Consumer<String> lambda
    ) {
        Shape targetShape = context.getModel().expectShape(member.getTarget());
        Shape container = context.getModel().expectShape(member.getContainer());

        boolean withAddr = !GoPointableIndex.of(context.getModel()).isPointable(member)
                && GoPointableIndex.of(context.getModel()).isPointable(targetShape);
        boolean isMap = container.getType() == ShapeType.MAP;

        String destOperand = origDestOperand;
        if (isMap && withAddr) {
            writer.write("mapVar := $L", origDestOperand);
            destOperand = "mapVar";
        }

        String acceptVar = destOperand;
        if (withAddr) {
            acceptVar = "&" + destOperand;
        }

        lambda.accept(acceptVar);
    }
}
