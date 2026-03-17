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

package software.amazon.smithy.go.codegen.serde;

import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.Set;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ByteShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.LongShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class SerdeUtil {
    private SerdeUtil() {}

    /**
     * Gets the set of all shapes that require serde codegen for the given root shape. This is generally called for
     * every input/output shape in a model, with all the results collected into a single set.
     * @param model The model
     * @param shape The root shape to walk to find serdeables.
     * @return The set of shapes that require serde codegen.
     */
    public static Set<Shape> getShapesToSerde(Model model, Shape shape) {
        var toSerde = new HashSet<Shape>();
        visitShapesToSerde(model, shape, toSerde);

        // We don't want to actually generate serde for event stream unions - their variants can target errors, which
        // shouldn't be handled generally. We DO want any of their inner members though which is why we didn't filter
        // them in the previous visit step.
        //
        // Serde for the root unions is handled as a special case by event streaming serde codegen.
        return toSerde.stream()
                .filter(it -> !it.hasTrait(StreamingTrait.class))
                .collect(toSet());
    }

    /**
     * Normalizes a scalar shape, erasing any nullability information and giving the shape a single unique synthetic ID.
     * Non-scalar shapes are returned unmodified.
     * @param shape The shape.
     * @return The normalized shape.
     */
    public static Shape normalize(Shape shape) {
        return switch (shape.getType()) {
            case BLOB -> BlobShape.builder().id("com.amazonaws.synthetic#Blob").build();
            case BOOLEAN -> BooleanShape.builder().id("com.amazonaws.synthetic#Bool").build();
            case STRING -> StringShape.builder().id("com.amazonaws.synthetic#String").build();
            case TIMESTAMP -> TimestampShape.builder().id("com.amazonaws.synthetic#Time").build();
            case BYTE -> ByteShape.builder().id("com.amazonaws.synthetic#Int8").build();
            case SHORT -> ShortShape.builder().id("com.amazonaws.synthetic#Int16").build();
            case INTEGER -> IntegerShape.builder().id("com.amazonaws.synthetic#Int32").build();
            case LONG -> LongShape.builder().id("com.amazonaws.synthetic#Int64").build();
            case FLOAT -> FloatShape.builder().id("com.amazonaws.synthetic#Float32").build();
            case DOUBLE -> DoubleShape.builder().id("com.amazonaws.synthetic#Float64").build();
            default -> shape;
        };
    }

    private static void visitShapesToSerde(Model model, Shape shape, Set<Shape> visited) {
        if (isUnit(shape.getId()) || visited.contains(shape)) {
            return;
        }

        visited.add(normalize(shape));
        shape.members().stream()
                .map(it -> model.expectShape(it.getTarget()))
                .forEach(it -> visitShapesToSerde(model, it, visited));
    }

    private static boolean isUnit(ShapeId id) {
        return id.toString().equals("smithy.api#Unit");
    }
}
