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

package software.amazon.smithy.go.codegen.util;

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StringShape;

public final class ShapeUtil {
    public static final StringShape STRING_SHAPE = StringShape.builder()
            .id("smithy.api#PrimitiveString")
            .build();

    public static final IntegerShape INT_SHAPE = IntegerShape.builder()
            .id("smithy.api#PrimitiveInteger")
            .build();

    public static final BooleanShape BOOL_SHAPE = BooleanShape.builder()
            .id("smithy.api#PrimitiveBoolean")
            .build();

    private ShapeUtil() {}

    public static ListShape listOf(Shape member) {
        return ListShape.builder()
                .id("smithy.go.synthetic#" + member.getId().getName() + "List")
                .member(member.getId())
                .build();
    }

    public static Shape expectMember(Model model, Shape shape, String memberName) {
        var optMember = shape.getMember(memberName);
        if (optMember.isEmpty()) {
            throw new CodegenException("expected member " + memberName + " in shape " + shape);
        }

        var member = optMember.get();
        return model.expectShape(member.getTarget());
    }

    public static Shape expectMember(Model model, CollectionShape shape) {
        return model.expectShape(shape.getMember().getTarget());
    }

    public static Shape expectMember(Model model, MapShape shape) {
        return model.expectShape(shape.getValue().getTarget());
    }
}
