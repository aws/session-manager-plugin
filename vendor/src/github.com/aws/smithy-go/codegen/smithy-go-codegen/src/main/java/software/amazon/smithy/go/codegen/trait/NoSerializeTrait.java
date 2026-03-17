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
 *
 *
 */

package software.amazon.smithy.go.codegen.trait;

import java.util.function.Predicate;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.AnnotationTrait;

/**
 * Provides a custom trait for labeling structure members as not serialized. Used to decorate custom API input
 * parameters that should not be serialized, but are needed for the SDK's customizations.
 */
public class NoSerializeTrait extends AnnotationTrait {
    public static final ShapeId ID = ShapeId.from("smithy.go.trait#NoSerialize");

    public NoSerializeTrait() {
        this(Node.objectNode());
    }

    public NoSerializeTrait(ObjectNode node) {
        super(ID, node);
    }

    public static final class Provider extends AnnotationTrait.Provider<NoSerializeTrait> {
        public Provider() {
            super(ID, NoSerializeTrait::new);
        }
    }

    /**
     * Predicate to filter out members decorated with the NoSerializeTrait from a collection of member shapes.
     *
     * @return predicate to filter members.
     */
    public static Predicate<MemberShape> excludeNoSerializeMembers() {
        return member -> !member.hasTrait(NoSerializeTrait.class);
    }

    /**
     * Predicate to filter out HttpBinding members decorated with the NoSerializeTrait from a collection of
     * HttpBindings.
     *
     * @return predicate to filter HttpBinding members.
     */
    public static Predicate<HttpBinding> excludeNoSerializeHttpBindingMembers() {
        return binding -> !binding.getMember().hasTrait(NoSerializeTrait.class);
    }
}
