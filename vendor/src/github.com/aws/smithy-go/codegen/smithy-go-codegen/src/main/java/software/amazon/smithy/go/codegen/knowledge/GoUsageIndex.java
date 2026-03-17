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

package software.amazon.smithy.go.codegen.knowledge;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.KnowledgeIndex;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.neighbor.RelationshipDirection;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.ToShapeId;

/**
 * Provides {@link KnowledgeIndex} of how shapes are used in the model.
 */
public class GoUsageIndex implements KnowledgeIndex {
    private final Model model;
    private final Walker walker;

    private final Set<ShapeId> inputShapes = new HashSet<>();
    private final Set<ShapeId> outputShapes = new HashSet<>();

    public GoUsageIndex(Model model) {
        this.model = model;
        this.walker = new Walker(model);

        TopDownIndex topDownIndex = TopDownIndex.of(model);
        OperationIndex operationIndex = OperationIndex.of(model);

        model.shapes(ServiceShape.class).forEach(serviceShape -> {
            topDownIndex.getContainedOperations(serviceShape).forEach(operationShape -> {
                StructureShape inputShape = operationIndex.getInput(operationShape).get();
                StructureShape outputShape = operationIndex.getOutput(operationShape).get();

                inputShapes.addAll(walker.walkShapes(inputShape, relationship ->
                        relationship.getDirection() == RelationshipDirection.DIRECTED).stream()
                        .map(Shape::toShapeId).collect(Collectors.toList()));

                outputShapes.addAll(walker.walkShapes(outputShape, relationship ->
                        relationship.getDirection() == RelationshipDirection.DIRECTED).stream()
                        .map(Shape::toShapeId).collect(Collectors.toList()));

            });
        });
    }

    /**
     * Returns whether shape is used as part of an input to an operation.
     *
     * @param shape the shape
     * @return whether the shape is used as input.
     */
    public boolean isUsedForInput(ToShapeId shape) {
        return inputShapes.contains(shape.toShapeId());
    }

    /**
     * Returns whether shape is used as output of an operation.
     *
     * @param shape the shape
     * @return whether the shape is used as input.
     */
    public boolean isUsedForOutput(ToShapeId shape) {
        return outputShapes.contains(shape.toShapeId());
    }

    public static GoUsageIndex of(Model model) {
        return model.getKnowledge(GoUsageIndex.class, GoUsageIndex::new);
    }
}
