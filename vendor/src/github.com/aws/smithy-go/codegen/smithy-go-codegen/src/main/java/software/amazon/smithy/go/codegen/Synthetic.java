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

package software.amazon.smithy.go.codegen;

import java.util.Optional;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.AbstractTrait;
import software.amazon.smithy.model.traits.AbstractTraitBuilder;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Defines a shape as being a clone of another modeled shape.
 * <p>
 * Must only be used as a runtime trait-only applied to shapes based on model processing
 */
public final class Synthetic extends AbstractTrait implements ToSmithyBuilder<Synthetic> {
    public static final ShapeId ID = ShapeId.from("smithy.go.traits#Synthetic");

    private static final String ARCHETYPE = "archetype";

    private final Optional<ShapeId> archetype;

    private Synthetic(Builder builder) {
        super(ID, builder.getSourceLocation());
        this.archetype = builder.archetype;
    }

    /**
     * Get the archetype shape that this clone is based on.
     *
     * @return the original archetype shape
     */
    public Optional<ShapeId> getArchetype() {
        return archetype;
    }

    @Override
    protected Node createNode() {
        throw new CodegenException("attempted to serialize runtime only trait");
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        } else if (!(other instanceof Synthetic)) {
            return false;
        } else {
            Synthetic b = (Synthetic) other;
            return toShapeId().equals(b.toShapeId()) && archetype.equals(b.getArchetype());
        }
    }

    @Override
    public int hashCode() {
        return toShapeId().hashCode() * 17 + Node.objectNode()
            .withOptionalMember(ARCHETYPE, archetype.map(ShapeId::toString).map(Node::from))
            .hashCode();
    }

    @Override
    public SmithyBuilder<Synthetic> toBuilder() {
        Builder builder = builder();
        getArchetype().ifPresent(builder::archetype);

        return builder;
    }

    /**
     * @return Returns a builder used to create {@link Synthetic}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link Synthetic}.
     */
    public static final class Builder extends AbstractTraitBuilder<Synthetic, Builder> {
        private Optional<ShapeId> archetype = Optional.empty();

        private Builder() {
        }

        @Override
        public Synthetic build() {
            return new Synthetic(this);
        }

        public Builder archetype(ShapeId archetype) {
            this.archetype = Optional.ofNullable(archetype);
            return this;
        }

        public Builder removeArchetype() {
            this.archetype = Optional.empty();
            return this;
        }
    }
}
