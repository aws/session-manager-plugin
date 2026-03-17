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

package software.amazon.smithy.go.codegen;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeVisitor;
import software.amazon.smithy.model.node.NullNode;
import software.amazon.smithy.model.node.NumberNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.SimpleShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.OptionalUtils;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * Generates a shape type declaration based on the parameters provided.
 */
public final class ShapeValueGenerator {
    private static final Logger LOGGER = Logger.getLogger(ShapeValueGenerator.class.getName());

    private final GoSettings settings;
    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoPointableIndex pointableIndex;
    private final Config config;

    /**
     * Initializes a shape value generator.
     *
     * @param settings       the Smithy Go settings.
     * @param model          the Smithy model references.
     * @param symbolProvider the symbol provider.
     */
    public ShapeValueGenerator(GoSettings settings, Model model, SymbolProvider symbolProvider) {
        this(settings, model, symbolProvider, Config.builder().build());
    }

    /**
     * Initializes a shape value generator.
     *
     * @param settings       the Smithy Go settings.
     * @param model          the Smithy model references.
     * @param symbolProvider the symbol provider.
     * @param config         the shape value generator config.
     */
    public ShapeValueGenerator(GoSettings settings, Model model, SymbolProvider symbolProvider, Config config) {
        this.settings = settings;
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.pointableIndex = GoPointableIndex.of(model);
        this.config = config;
    }

    /**
     * Writes generation of a shape value type declaration for the given the parameters.
     *
     * @param writer writer to write generated code with.
     * @param shape  the shape that will be declared.
     * @param params parameters to fill the generated shape declaration.
     */
    public void writePointableStructureShapeValueInline(GoWriter writer, StructureShape shape, Node params) {
        if (params.isNullNode()) {
            writer.writeInline("nil");
        }

        // Input/output struct top level shapes are special since they are the only shape that can be used directly,
        // not within the context of a member shape reference.
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.write("&$T{", symbol);
        params.accept(new ShapeValueNodeVisitor(writer, this, shape, ListUtils.copyOf(shape.getAllTraits().values()),
                config));
        writer.writeInline("}");
    }

    /**
     * Writes generation of a member shape value type declaration for the given the parameters.
     *
     * @param writer writer to write generated code with.
     * @param member the shape that will be declared.
     * @param params parameters to fill the generated shape declaration.
     */
    protected void writeMemberValueInline(GoWriter writer, MemberShape member, Node params) {
        Shape targetShape = model.expectShape(member.getTarget());

        // Null params need to be represented as zero values for member,
        if (params.isNullNode()) {
            if (pointableIndex.isNillable(member)) {
                writer.writeInline("nil");

            } else if (targetShape.getType() == ShapeType.STRING && targetShape.hasTrait(EnumTrait.class)) {
                Symbol enumSymbol = symbolProvider.toSymbol(targetShape);
                writer.writeInline("$T($S)", enumSymbol, "");

            } else {
                Symbol shapeSymbol = symbolProvider.toSymbol(member);
                writer.writeInline("func() (v $P) { return v }()", shapeSymbol);
            }
            return;
        }

        switch (targetShape.getType()) {
            case STRUCTURE:
                structDeclShapeValue(writer, member, params);
                break;

            case SET:
            case LIST:
                listDeclShapeValue(writer, member, params);
                break;

            case MAP:
                mapDeclShapeValue(writer, member, params);
                break;

            case UNION:
                unionDeclShapeValue(writer, member, params.expectObjectNode());
                break;

            case DOCUMENT:
                documentDeclShapeValue(writer, member, params);
                break;

            default:
                writeScalarPointerInline(writer, member, params);
        }
    }

    private void documentDeclShapeValue(GoWriter writer, MemberShape member, Node params) {
        Symbol newMarshaler = ProtocolDocumentGenerator.Utilities.getDocumentSymbolBuilder(settings,
                ProtocolDocumentGenerator.NEW_LAZY_DOCUMENT).build();

        writer.writeInline("$T(", newMarshaler);
        params.accept(new DocumentValueNodeVisitor(writer));
        writer.writeInline(")");
    }

    /**
     * Writes the declaration for a Go structure. Delegates to the runner for member fields within the structure.
     *
     * @param writer writer to write generated code with.
     * @param member the structure shape
     * @param params parameters to fill the generated shape declaration.
     */
    protected void structDeclShapeValue(GoWriter writer, MemberShape member, Node params) {
        Symbol symbol = symbolProvider.toSymbol(member);

        String addr = CodegenUtils.asAddressIfAddressable(model, pointableIndex, member, "");
        writer.write("$L$T{", addr, symbol);
        params.accept(new ShapeValueNodeVisitor(writer, this, model.expectShape(member.getTarget()),
                ListUtils.copyOf(member.getAllTraits().values()), config));
        writer.writeInline("}");
    }

    /**
     * Writes the declaration for a Go union.
     *
     * @param writer writer to write generated code with.
     * @param member the union shape.
     * @param params the params.
     */
    protected void unionDeclShapeValue(GoWriter writer, MemberShape member, ObjectNode params) {
        UnionShape targetShape = (UnionShape) model.expectShape(member.getTarget());

        for (Map.Entry<StringNode, Node> entry : params.getMembers().entrySet()) {
            targetShape.getMember(entry.getKey().toString()).ifPresent((unionMember) -> {
                Shape unionTarget = model.expectShape(unionMember.getTarget());

                // Need to manually create a symbol builder for a union member struct type because the "member"
                // of a union will return the inner value type not the member not the member type it self.
                Symbol memberSymbol = SymbolUtils.createPointableSymbolBuilder(
                        symbolProvider.toMemberName(unionMember),
                        symbolProvider.toSymbol(targetShape).getNamespace()
                ).build();

                // Union member types are always pointers
                writer.writeInline("&$T{Value: ", memberSymbol);
                if (unionTarget instanceof SimpleShape) {
                    writeScalarValueInline(writer, unionMember, entry.getValue());
                } else {
                    writeMemberValueInline(writer, unionMember, entry.getValue());
                }
                writer.writeInline("}");
            });

            return;
        }
    }

    /**
     * Writes the declaration for a Go slice. Delegates to the runner for fields within the slice.
     *
     * @param writer writer to write generated code with.
     * @param member the collection shape
     * @param params parameters to fill the generated shape declaration.
     */
    protected void listDeclShapeValue(GoWriter writer, MemberShape member, Node params) {
        writer.write("$P{", symbolProvider.toSymbol(member));
        params.accept(new ShapeValueNodeVisitor(writer, this, model.expectShape(member.getTarget()),
                ListUtils.copyOf(member.getAllTraits().values()), config));
        writer.writeInline("}");
    }

    /**
     * Writes the declaration for a Go map. Delegates to the runner for key/value fields within the map.
     *
     * @param writer writer to write generated code with.
     * @param member the map shape.
     * @param params parameters to fill the generated shape declaration.
     */
    protected void mapDeclShapeValue(GoWriter writer, MemberShape member, Node params) {
        writer.write("$P{", symbolProvider.toSymbol(member));
        params.accept(new ShapeValueNodeVisitor(writer, this, model.expectShape(member.getTarget()),
                ListUtils.copyOf(member.getAllTraits().values()), config));
        writer.writeInline("}");
    }

    private void writeScalarWrapper(
            GoWriter writer,
            MemberShape member,
            Node params,
            String funcName,
            TriConsumer<GoWriter, MemberShape, Node> inner
    ) {
        if (pointableIndex.isPointable(member)) {
            writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
            writer.writeInline("ptr." + funcName + "(");
            inner.accept(writer, member, params);
            writer.writeInline(")");
        } else {
            inner.accept(writer, member, params);
        }
    }

    /**
     * Writes scalar values with pointer value wrapping as needed based on the shape type.
     *
     * @param writer writer to write generated code with.
     * @param member scalar shape.
     * @param params parameters to fill the generated shape declaration.
     */
    protected void writeScalarPointerInline(GoWriter writer, MemberShape member, Node params) {
        Shape target = model.expectShape(member.getTarget());

        String funcName = "";
        switch (target.getType()) {
            case BOOLEAN:
                funcName = "Bool";
                break;

            case STRING:
                funcName = "String";
                break;

            case ENUM:
                funcName = target.getId().getName();
                break;

            case TIMESTAMP:
                funcName = "Time";
                break;

            case BYTE:
                funcName = "Int8";
                break;
            case SHORT:
                funcName = "Int16";
                break;
            case INTEGER:
            case INT_ENUM:
                funcName = "Int32";
                break;
            case LONG:
                funcName = "Int64";
                break;

            case FLOAT:
                funcName = "Float32";
                break;
            case DOUBLE:
                funcName = "Float64";
                break;

            case BLOB:
                break;

            case BIG_INTEGER:
            case BIG_DECIMAL:
                return;

            default:
                throw new CodegenException("unexpected shape type " + target.getType());
        }

        writeScalarWrapper(writer, member, params, funcName, this::writeScalarValueInline);
    }

    protected void writeScalarValueInline(GoWriter writer, MemberShape member, Node params) {
        Shape target = model.expectShape(member.getTarget());

        String closing = "";
        switch (target.getType()) {
            case BLOB:
                // blob streams are io.Readers not byte slices.
                if (target.hasTrait(StreamingTrait.class)) {
                    writer.addUseImports(SmithyGoDependency.SMITHY_IO);
                    writer.addUseImports(SmithyGoDependency.BYTES);
                    writer.writeInline("smithyio.ReadSeekNopCloser{ReadSeeker: bytes.NewReader([]byte(");
                    closing = "))}";
                } else {
                    writer.writeInline("[]byte(");
                    closing = ")";
                }
                break;

            case STRING:
                // String streams are io.Readers not strings.
                if (target.hasTrait(StreamingTrait.class)) {
                    writer.addUseImports(SmithyGoDependency.SMITHY_IO);
                    writer.addUseImports(SmithyGoDependency.STRINGS);
                    writer.writeInline("smithyio.ReadSeekNopCloser{ReadSeeker: strings.NewReader(");
                    closing = ")}";

                } else if (target.hasTrait(EnumTrait.class)) {
                    // Enum are not pointers, but string alias values
                    Symbol enumSymbol = symbolProvider.toSymbol(target);
                    writer.writeInline("$T(", enumSymbol);
                    closing = ")";
                }
                break;
            case ENUM:
                // Enum are not pointers, but string alias values
                Symbol enumSymbol = symbolProvider.toSymbol(target);
                writer.writeInline("$T(", enumSymbol);
                closing = ")";
                break;

            default:
                break;
        }

        params.accept(new ShapeValueNodeVisitor(writer, this, target,
                ListUtils.copyOf(member.getAllTraits().values()), config));
        writer.writeInline(closing);
    }

    /**
     * Configuration that determines how shapes values are generated.
     */
    public static final class Config {
        private final boolean normalizeHttpPrefixHeaderKeys;

        private Config(Builder builder) {
            normalizeHttpPrefixHeaderKeys = builder.normalizeHttpPrefixHeaderKeys;
        }

        public static Builder builder() {
            return new Builder();
        }

        /**
         * Returns whether maps with the httpPrefixHeader trait should have their keys normalized.
         *
         * @return whether to normalize http prefix header keys
         */
        public boolean isNormalizeHttpPrefixHeaderKeys() {
            return normalizeHttpPrefixHeaderKeys;
        }

        public static final class Builder implements SmithyBuilder<Config> {
            private boolean normalizeHttpPrefixHeaderKeys;

            public Builder normalizeHttpPrefixHeaderKeys(boolean normalizeHttpPrefixHeaderKeys) {
                this.normalizeHttpPrefixHeaderKeys = normalizeHttpPrefixHeaderKeys;
                return this;
            }

            @Override
            public Config build() {
                return new Config(this);
            }
        }
    }

    private static final class DocumentValueNodeVisitor implements NodeVisitor<Void> {
        private final GoWriter writer;

        private DocumentValueNodeVisitor(GoWriter writer) {
            this.writer = writer;
        }

        @Override
        public Void arrayNode(ArrayNode node) {
            writer.writeInline("[]interface{}{\n");
            for (Node element : node.getElements()) {
                element.accept(this);
                writer.writeInline(",\n");
            }
            writer.writeInline("}");
            return null;
        }

        @Override
        public Void booleanNode(BooleanNode node) {
            if (node.getValue()) {
                writer.writeInline("true");
            } else {
                writer.writeInline("false");
            }
            return null;
        }

        @Override
        public Void nullNode(NullNode node) {
            writer.writeInline("nil");
            return null;
        }

        @Override
        public Void numberNode(NumberNode node) {
            if (node.isNaturalNumber()) {
                Number value = node.getValue();
                if (value instanceof BigInteger) {
                    writer.addUseImports(SmithyGoDependency.BIG);
                    writer.writeInline("func () *big.Int {\n"
                            + "\ti, ok := (&big.Int{}).SetString($S, 10)\n"
                            + "\tif !ok { panic(\"failed to parse string to integer: \" + $S) }\n"
                            + "\treturn i\n"
                            + "}()", value, value);
                } else {
                    writer.writeInline("$L", node.getValue());
                }
            } else {
                Number value = node.getValue();
                if (value instanceof Float) {
                    writer.writeInline("float32($L)", value.floatValue(), value);
                } else if (value instanceof Double) {
                    writer.writeInline("float64($L)", value.doubleValue(), value);
                } else {
                    writer.addUseImports(SmithyGoDependency.BIG);
                    writer.writeInline("func () *big.Float {\n"
                            + "\tf, ok := (&big.Float{}).SetString($S)\n"
                            + "\tif !ok { panic(\"failed to parse string to float: \" + $S) }\n"
                            + "\treturn f\n"
                            + "}()", value, value);
                }
            }
            return null;
        }

        @Override
        public Void objectNode(ObjectNode node) {
            writer.writeInline("map[string]interface{}{\n");
            node.getMembers().forEach((key, value) -> {
                writer.writeInline("$S: ", key.getValue());
                value.accept(this);
                writer.writeInline(",\n");
            });
            writer.writeInline("}");
            return null;
        }

        @Override
        public Void stringNode(StringNode node) {
            writer.writeInline("$S", node.getValue());
            return null;
        }
    }

    /**
     * NodeVisitor to walk shape value declarations with node values.
     */
    private final class ShapeValueNodeVisitor implements NodeVisitor<Void> {
        private final GoWriter writer;
        private final ShapeValueGenerator valueGen;
        private final Shape currentShape;
        private final List<Trait> traits;
        private final Config config;

        /**
         * Initializes shape value visitor.
         *
         * @param writer   writer to write generated code with.
         * @param valueGen shape value generator.
         * @param shape    the shape that visiting is relative to.
         */
        private ShapeValueNodeVisitor(GoWriter writer, ShapeValueGenerator valueGen, Shape shape) {
            this(writer, valueGen, shape, ListUtils.of());
        }

        /**
         * Initializes shape value visitor.
         *
         * @param writer   writer to write generated code with.
         * @param valueGen shape value generator.
         * @param shape    the shape that visiting is relative to.
         * @param traits   the traits applied to the target shape by a MemberShape.
         */
        private ShapeValueNodeVisitor(GoWriter writer, ShapeValueGenerator valueGen, Shape shape, List<Trait> traits) {
            this(writer, valueGen, shape, traits, Config.builder().build());
        }

        /**
         * Initializes shape value visitor.
         *
         * @param writer   writer to write generated code with.
         * @param valueGen shape value generator.
         * @param shape    the shape that visiting is relative to.
         * @param traits   the traits applied to the target shape by a MemberShape.
         * @param config   the shape value generator config.
         */
        private ShapeValueNodeVisitor(
                GoWriter writer,
                ShapeValueGenerator valueGen,
                Shape shape,
                List<Trait> traits,
                Config config
        ) {
            this.writer = writer;
            this.valueGen = valueGen;
            this.currentShape = shape;
            this.traits = traits;
            this.config = config;
        }

        /**
         * When array nodes elements are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void arrayNode(ArrayNode node) {
            MemberShape memberShape = CodegenUtils.expectCollectionShape(this.currentShape).getMember();

            node.getElements().forEach(element -> {
                valueGen.writeMemberValueInline(writer, memberShape, element);
                writer.write(",");
            });
            return null;
        }

        /**
         * When an object node elements are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void objectNode(ObjectNode node) {
            node.getMembers().forEach((keyNode, valueNode) -> {
                MemberShape member;
                switch (currentShape.getType()) {
                    case STRUCTURE:
                        // query-compatible error code override field isn't modeled, short-circuit here
                        if (keyNode.getValue().equals("ErrorCodeOverride")) {
                            var value = valueNode.expectStringNode().getValue();
                            writer.write("ErrorCodeOverride: ptr.String($S),", value);
                            break;
                        }

                        if (currentShape.asStructureShape().get().getMember(keyNode.getValue()).isPresent()) {
                            member = currentShape.asStructureShape().get().getMember(keyNode.getValue()).get();
                        } else {
                            throw new CodegenException(
                                    "unknown member " + currentShape.getId() + "." + keyNode.getValue());
                        }

                        String memberName = symbolProvider.toMemberName(member);
                        writer.write("$L: ", memberName);
                        valueGen.writeMemberValueInline(writer, member, valueNode);
                        writer.write(",");
                        break;

                    case MAP:
                        MapShape mapShape = this.currentShape.asMapShape().get();

                        String keyValue = keyNode.getValue();
                        if (config.isNormalizeHttpPrefixHeaderKeys()) {
                            keyValue = OptionalUtils.or(getTrait(HttpPrefixHeadersTrait.class),
                                            () -> mapShape.getTrait(HttpPrefixHeadersTrait.class))
                                    .map(httpPrefixHeadersTrait -> keyNode.getValue().toLowerCase())
                                    .orElse(keyValue);
                        }

                        writer.write("$S: ", keyValue);
                        valueGen.writeMemberValueInline(writer, mapShape.getValue(), valueNode);
                        writer.write(",");
                        break;

                    default:
                        throw new CodegenException("unexpected shape type " + currentShape.getType());
                }
            });

            return null;
        }

        /**
         * When boolean nodes are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void booleanNode(BooleanNode node) {
            if (!currentShape.getType().equals(ShapeType.BOOLEAN)) {
                throw new CodegenException("unexpected shape type " + currentShape + " for boolean value");
            }

            writer.writeInline("$L", node.getValue() ? "true" : "false");
            return null;
        }

        /**
         * When null nodes are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void nullNode(NullNode node) {
            throw new CodegenException("unexpected null node walked, should not be encountered in walker");
        }

        /**
         * When number nodes are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void numberNode(NumberNode node) {
            switch (currentShape.getType()) {
                case TIMESTAMP:
                    writer.addUseImports(SmithyGoDependency.SMITHY_TIME);
                    writer.writeInline("smithytime.ParseEpochSeconds($L)", node.getValue());
                    break;

                case BYTE:
                case SHORT:
                case INTEGER:
                case INT_ENUM:
                case LONG:
                case FLOAT:
                case DOUBLE:
                    writer.writeInline("$L", node.getValue());
                    break;

                case BIG_INTEGER:
                    writeInlineBigIntegerInit(writer, node.getValue());
                    break;

                case BIG_DECIMAL:
                    writeInlineBigDecimalInit(writer, node.getValue());
                    break;

                default:
                    throw new CodegenException("unexpected shape type " + currentShape + " for string value");
            }

            return null;
        }

        /**
         * When string nodes are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void stringNode(StringNode node) {
            switch (currentShape.getType()) {
                case BLOB:
                case STRING:
                    writer.writeInline("$S", node.getValue());
                    break;

                case ENUM:
                    writer.writeInline("$S", node.getValue());
                    break;

                case BIG_INTEGER:
                    writeInlineBigIntegerInit(writer, node.getValue());
                    break;

                case BIG_DECIMAL:
                    writeInlineBigDecimalInit(writer, node.getValue());
                    break;

                case DOUBLE:
                    writeInlineNonNumericFloat(writer, node.getValue());
                    break;
                case FLOAT:
                    writer.writeInline("float32(");
                    writeInlineNonNumericFloat(writer, node.getValue());
                    writer.writeInline(")");
                    break;

                default:
                    throw new CodegenException("unexpected shape type " + currentShape.getType());
            }

            return null;
        }

        private void writeInlineBigDecimalInit(GoWriter writer, Object value) {
            writer.addUseImports(SmithyGoDependency.BIG);
            writer.writeInline("func() *big.Float {\n"
                            + "    i, ok := big.ParseFloat($S, 10, 200, big.ToNearestAway)\n"
                            + "    if !ok { panic(\"invalid generated param value, \" + $S) }\n"
                            + "    return i"
                            + "}()",
                    value, value);
        }

        private void writeInlineBigIntegerInit(GoWriter writer, Object value) {
            writer.addUseImports(SmithyGoDependency.BIG);
            writer.writeInline("func() *big.Int {\n"
                            + "    i, ok := new(big.Int).SetString($S, 10)\n"
                            + "    if !ok { panic(\"invalid generated param value, \" + $S) }\n"
                            + "    return i"
                            + "}()",
                    value, value);
        }

        private void writeInlineNonNumericFloat(GoWriter writer, String value) {
            writer.addUseImports(SmithyGoDependency.stdlib("math"));
            switch (value) {
                case "NaN":
                    writer.writeInline("math.NaN()");
                    break;
                case "Infinity":
                    writer.writeInline("math.Inf(1)");
                    break;
                case "-Infinity":
                    writer.writeInline("math.Inf(-1)");
                    break;
                default:
                    throw new CodegenException(String.format(
                            "Unexpected string value for `%s`: \"%s\"", currentShape.getId(), value));
            }
        }

        private <T extends Trait> Optional<T> getTrait(Class<T> traitClass) {
            for (Trait trait : traits) {
                if (traitClass.isInstance(trait)) {
                    return Optional.of((T) trait);
                }
            }
            return Optional.empty();
        }
    }
}
