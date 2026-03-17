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

package software.amazon.smithy.go.codegen.trait;

import java.util.Optional;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.AbstractTrait;
import software.amazon.smithy.model.traits.AbstractTraitBuilder;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Provides a custom runtime trait that can be used to extend the standard the paginator generation behavior.
 * <p>
 * Currently exposes the ability to specify a MemberShape of the operation output that should be checked to determine
 * if the output was truncated.
 */
public final class PagingExtensionTrait extends AbstractTrait implements ToSmithyBuilder<PagingExtensionTrait> {
    public static final ShapeId ID = ShapeId.from("smithy.go.traits#PagingExtensionTrait");

    private final MemberShape moreResults;

    private PagingExtensionTrait(Builder builder) {
        super(ID, builder.getSourceLocation());
        moreResults = builder.moreResults;
    }

    /**
     * Get the output member shape that is used to indicate that there are more results.
     *
     * @return the member shape.
     */
    public Optional<MemberShape> getMoreResults() {
        return Optional.ofNullable(moreResults);
    }

    @Override
    protected Node createNode() {
        throw new CodegenException("attempted to serialize runtime only trait");
    }

    @Override
    public SmithyBuilder<PagingExtensionTrait> toBuilder() {
        return new Builder()
                .moreResults(moreResults);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractTraitBuilder<PagingExtensionTrait, PagingExtensionTrait.Builder> {
        private MemberShape moreResults;

        private Builder() {
        }

        public Builder moreResults(MemberShape moreResults) {
            this.moreResults = moreResults;
            return this;
        }

        @Override
        public PagingExtensionTrait build() {
            return new PagingExtensionTrait(this);
        }
    }
}
