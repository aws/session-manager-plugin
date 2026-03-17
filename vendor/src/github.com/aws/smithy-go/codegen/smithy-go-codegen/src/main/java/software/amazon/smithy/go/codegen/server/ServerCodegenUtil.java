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

package software.amazon.smithy.go.codegen.server;

import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
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
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.model.traits.UnitTypeTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class ServerCodegenUtil {
    private ServerCodegenUtil() {}

    public static boolean operationHasEventStream(
        Model model,
        StructureShape inputShape,
        StructureShape outputShape
    ) {
        return Stream
            .concat(
                inputShape.members().stream(),
                outputShape.members().stream())
            .anyMatch(memberShape -> StreamingTrait.isEventStream(model, memberShape));
    }

    public static Set<Shape> getShapesToSerde(Model model, Shape shape) {
        if (isUnit(shape.getId())) {
            return new HashSet<>();
        }

        return Stream.concat(
                Stream.of(normalize(shape)),
                shape.members().stream()
                        .map(it -> model.expectShape(it.getTarget()))
                        .flatMap(it -> getShapesToSerde(model, it).stream())
        ).collect(toSet());
    }

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

    public static boolean isUnit(ShapeId id) {
        return id.toString().equals("smithy.api#Unit");
    }

    public static Model withUnit(Model model) {
        return model.toBuilder()
                .addShape(
                        StructureShape.builder()
                                .id("smithy.api#Unit")
                                .addTrait(new UnitTypeTrait())
                                .build()
                )
                .build();
    }
}
