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

import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.AnnotationTrait;

/**
 * Provides a trait to decorate an member as unexported, so it will not be exported within the containing structure.
 * This should only be used on top level input/output structure shapes, because internal members of non-input/output
 * shapes are not visible outside of the API client's "types" package.
 */
public class UnexportedMemberTrait extends AnnotationTrait {
    public static final ShapeId ID = ShapeId.from("smithy.go.trait#UnexportedMember");

    public UnexportedMemberTrait() {
        this(Node.objectNode());
    }

    public UnexportedMemberTrait(ObjectNode node) {
        super(ID, node);
    }

    public static final class Provider extends AnnotationTrait.Provider<UnexportedMemberTrait> {
        public Provider() {
            super(ID, UnexportedMemberTrait::new);
        }
    }
}
