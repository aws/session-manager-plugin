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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.ReservedWordSymbolProvider;
import software.amazon.smithy.codegen.core.ReservedWords;
import software.amazon.smithy.codegen.core.ReservedWordsBuilder;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.go.codegen.trait.UnexportedMemberTrait;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.NeighborProviderIndex;
import software.amazon.smithy.model.knowledge.NullableIndex;
import software.amazon.smithy.model.neighbor.NeighborProvider;
import software.amazon.smithy.model.neighbor.Relationship;
import software.amazon.smithy.model.neighbor.RelationshipType;
import software.amazon.smithy.model.shapes.BigDecimalShape;
import software.amazon.smithy.model.shapes.BigIntegerShape;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ByteShape;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.IntEnumShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.LongShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.utils.StringUtils;

/**
 * Responsible for type mapping and file/identifier formatting.
 *
 * <p>Reserved words for Go are automatically escaped so that they are
 * suffixed with "_". See "reserved-words.txt" for the list of words.
 */
@SmithyInternalApi
public final class SymbolVisitor implements SymbolProvider, ShapeVisitor<Symbol> {
    private static final Logger LOGGER = Logger.getLogger(SymbolVisitor.class.getName());

    private final Model model;
    private final String rootModuleName;
    private final String typesPackageName;
    private final ReservedWordSymbolProvider.Escaper escaper;
    private final ReservedWordSymbolProvider.Escaper errorMemberEscaper;
    private final Map<ShapeId, ReservedWordSymbolProvider.Escaper> structureSpecificMemberEscapers = new HashMap<>();
    private final GoPointableIndex pointableIndex;
    private final GoSettings settings;

    public SymbolVisitor(Model model, GoSettings settings) {
        this.model = model;
        this.settings = settings;
        this.rootModuleName = settings.getModuleName();
        this.typesPackageName = this.rootModuleName + "/types";
        this.pointableIndex = settings.getArtifactType() == GoSettings.ArtifactType.SERVER
                ? GoPointableIndex.of(model, NullableIndex.CheckMode.SERVER)
                : GoPointableIndex.of(model);

        // Reserve the generated names for union members, including the unknown case.
        ReservedWordsBuilder reservedNames = new ReservedWordsBuilder()
                .put(UnionGenerator.UNKNOWN_MEMBER_NAME,
                        escapeWithTrailingUnderscore(UnionGenerator.UNKNOWN_MEMBER_NAME));
        reserveUnionMemberNames(model, reservedNames);

        ReservedWords reservedMembers = new ReservedWordsBuilder()
                // Since Go only exports names if the first character is upper case and all
                // the go reserved words are lower case, it's functionally impossible to conflict,
                // so we only need to protect against common names. As of now there's only one.
                .put("String", "String_")
                .put("GetStream", "GetStream_")
                .build();

        model.shapes(StructureShape.class)
                .filter(this::supportsInheritance)
                .forEach(this::reserveInterfaceMemberAccessors);

        escaper = ReservedWordSymbolProvider.builder()
                .nameReservedWords(reservedNames.build())
                .memberReservedWords(reservedMembers)
                // Only escape words when the symbol has a definition file to
                // prevent escaping intentional references to built-in types.
                .escapePredicate((shape, symbol) -> !StringUtils.isEmpty(symbol.getDefinitionFile()))
                .buildEscaper();

        // Reserved words that only apply to error members.
        ReservedWords reservedErrorMembers = new ReservedWordsBuilder()
                .put("ErrorCode", "ErrorCode_")
                .put("ErrorMessage", "ErrorMessage_")
                .put("ErrorFault", "ErrorFault_")
                .put("Unwrap", "Unwrap_")
                .put("Error", "Error_")
                .put("ErrorCodeOverride", "ErrorCodeOverride_")
                .build();

        errorMemberEscaper = ReservedWordSymbolProvider.builder()
                .memberReservedWords(ReservedWords.compose(reservedMembers, reservedErrorMembers))
                .escapePredicate((shape, symbol) -> !StringUtils.isEmpty(symbol.getDefinitionFile()))
                .buildEscaper();
    }

    /**
     * Reserves generated member names for unions.
     *
     * <p>These have the format {UnionName}Member{MemberName}.
     *
     * @param model   The model whose unions should be reserved.
     * @param builder A reserved words builder to add on to.
     */
    private void reserveUnionMemberNames(Model model, ReservedWordsBuilder builder) {
        model.shapes(UnionShape.class).forEach(union -> {
            for (MemberShape member : union.getAllMembers().values()) {
                String memberName = formatUnionMemberName(union, member);
                builder.put(memberName, escapeWithTrailingUnderscore(memberName));
            }
        });
    }

    private boolean supportsInheritance(Shape shape) {
        return shape.isStructureShape() && shape.hasTrait(ErrorTrait.class);
    }

    /**
     * Reserves Get* and Has* member names for the given structure for use as accessor methods.
     *
     * <p>These reservations will only apply to the given structure, not to other structures.
     *
     * @param shape The structure shape whose members should be reserved.
     */
    private void reserveInterfaceMemberAccessors(StructureShape shape) {
        ReservedWordsBuilder builder = new ReservedWordsBuilder();
        for (MemberShape member : shape.getAllMembers().values()) {
            String name = getDefaultMemberName(member);
            String getterName = "Get" + name;
            String haserName = "Has" + name;
            builder.put(getterName, escapeWithTrailingUnderscore(getterName));
            builder.put(haserName, escapeWithTrailingUnderscore(haserName));
        }
        ReservedWordSymbolProvider.Escaper structureSpecificMemberEscaper = ReservedWordSymbolProvider.builder()
                .memberReservedWords(builder.build())
                .buildEscaper();
        structureSpecificMemberEscapers.put(shape.getId(), structureSpecificMemberEscaper);
    }

    private String escapeWithTrailingUnderscore(String symbolName) {
        return symbolName + "_";
    }

    @Override
    public Symbol toSymbol(Shape shape) {
        Symbol symbol = shape.accept(this);
        LOGGER.fine(() -> String.format("Creating symbol from %s: %s", shape, symbol));
        return linkArchetypeShape(shape, escaper.escapeSymbol(shape, symbol));
    }

    /**
     * Links the archetype shape id for the symbol.
     *
     * @param shape  the model shape
     * @param symbol the symbol to set the archetype property on
     * @return the symbol with archetype set if shape is a synthetic clone otherwise the original symbol
     */
    private Symbol linkArchetypeShape(Shape shape, Symbol symbol) {
        return shape.getTrait(Synthetic.class)
                .map(synthetic -> symbol.toBuilder()
                        .putProperty("archetype", synthetic.getArchetype())
                        .build())
                .orElse(symbol);
    }

    @Override
    public String toMemberName(MemberShape shape) {
        Shape container = model.expectShape(shape.getContainer());
        if (container.isUnionShape()) {
            // Union member names are not escaped as they are used to build the escape set.
            return formatUnionMemberName(container.asUnionShape().get(), shape);
        }

        String memberName = getDefaultMemberName(shape);
        memberName = escaper.escapeMemberName(memberName);

        // Escape words reserved for the specific container.
        if (structureSpecificMemberEscapers.containsKey(shape.getContainer())) {
            memberName = structureSpecificMemberEscapers.get(shape.getContainer()).escapeMemberName(memberName);
        }

        // Escape words that are only reserved for error members.
        if (isErrorMember(shape)) {
            memberName = errorMemberEscaper.escapeMemberName(memberName);
        }
        return memberName;
    }

    private String formatUnionMemberName(UnionShape union, MemberShape member) {
        return String.format("%sMember%s", getDefaultShapeName(union), getDefaultMemberName(member));
    }

    private String getDefaultShapeName(Shape shape) {
        ServiceShape serviceShape = model.expectShape(settings.getService(), ServiceShape.class);
        return StringUtils.capitalize(removeLeadingInvalidIdentCharacters(shape.getId().getName(serviceShape)));
    }

    private String getDefaultMemberName(MemberShape shape) {
        String memberName = StringUtils.capitalize(removeLeadingInvalidIdentCharacters(shape.getMemberName()));

        // change to lowercase first character if unexported structure member.
        if (model.expectShape(shape.getContainer()).isStructureShape() && shape.hasTrait(UnexportedMemberTrait.class)) {
            memberName = Character.toLowerCase(memberName.charAt(0)) + memberName.substring(1);
        }

        return memberName;
    }

    private String removeLeadingInvalidIdentCharacters(String value) {
        if (Character.isAlphabetic(value.charAt(0))) {
            return value;
        }

        int i;
        for (i = 0; i < value.length(); i++) {
            if (Character.isAlphabetic(value.charAt(i))) {
                break;
            }
        }

        String remaining = value.substring(i);
        if (remaining.length() == 0) {
            throw new CodegenException("tried to clean name " + value + ", but resulted in empty string");
        }

        return remaining;
    }


    private boolean isErrorMember(MemberShape shape) {
        return model.getShape(shape.getContainer())
                .map(container -> container.hasTrait(ErrorTrait.ID))
                .orElse(false);
    }

    @Override
    public Symbol blobShape(BlobShape shape) {
        if (shape.hasTrait(StreamingTrait.ID)) {
            Symbol inputVariant = symbolBuilderFor(shape, "Reader", SmithyGoDependency.IO).build();
            return symbolBuilderFor(shape, "ReadCloser", SmithyGoDependency.IO)
                    .putProperty(SymbolUtils.INPUT_VARIANT, inputVariant)
                    .build();
        }
        return symbolBuilderFor(shape, "[]byte")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol booleanShape(BooleanShape shape) {
        return symbolBuilderFor(shape, "bool")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol listShape(ListShape shape) {
        return createCollectionSymbol(shape);
    }

    @Override
    public Symbol setShape(SetShape shape) {
        // Go doesn't have a set type. Rather than hack together a set using a map,
        // we instead just create a list and let the service be responsible for
        // asserting that there are no duplicates.
        return createCollectionSymbol(shape);
    }

    private Symbol createCollectionSymbol(CollectionShape shape) {
        Symbol reference = toSymbol(shape.getMember());
        // Shape name will be unused for symbols that represent a slice, but in the event it does we set the collection
        // shape's name to make debugging simpler.
        return symbolBuilderFor(shape, getDefaultShapeName(shape))
                .putProperty(SymbolUtils.GO_SLICE, true)
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE,
                        reference.getProperty(SymbolUtils.GO_UNIVERSE_TYPE, Boolean.class).orElse(false))
                .putProperty(SymbolUtils.GO_ELEMENT_TYPE, reference)
                .build();
    }

    @Override
    public Symbol mapShape(MapShape shape) {
        Symbol reference = toSymbol(shape.getValue());
        // Shape name will be unused for symbols that represent a map, but in the event it does we set the map shape's
        // name to make debugging simpler.
        return symbolBuilderFor(shape, getDefaultShapeName(shape))
                .putProperty(SymbolUtils.GO_MAP, true)
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE,
                        reference.getProperty(SymbolUtils.GO_UNIVERSE_TYPE, Boolean.class).orElse(false))
                .putProperty(SymbolUtils.GO_ELEMENT_TYPE, reference)
                .build();
    }

    private Symbol.Builder symbolBuilderFor(Shape shape, String typeName) {
        if (pointableIndex.isPointable(shape)) {
            return SymbolUtils.createPointableSymbolBuilder(shape, typeName);
        }

        return SymbolUtils.createValueSymbolBuilder(shape, typeName);
    }

    private Symbol.Builder symbolBuilderFor(Shape shape, String typeName, GoDependency namespace) {
        if (pointableIndex.isPointable(shape)) {
            return SymbolUtils.createPointableSymbolBuilder(shape, typeName, namespace);
        }

        return SymbolUtils.createValueSymbolBuilder(shape, typeName, namespace);
    }

    private Symbol.Builder symbolBuilderFor(Shape shape, String typeName, String namespace) {
        if (pointableIndex.isPointable(shape)) {
            return SymbolUtils.createPointableSymbolBuilder(shape, typeName, namespace);
        }

        return SymbolUtils.createValueSymbolBuilder(shape, typeName, namespace);
    }

    @Override
    public Symbol byteShape(ByteShape shape) {
        return symbolBuilderFor(shape, "int8")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol shortShape(ShortShape shape) {
        return symbolBuilderFor(shape, "int16")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol integerShape(IntegerShape shape) {
        return symbolBuilderFor(shape, "int32")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol longShape(LongShape shape) {
        return symbolBuilderFor(shape, "int64")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol floatShape(FloatShape shape) {
        return symbolBuilderFor(shape, "float32")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol doubleShape(DoubleShape shape) {
        return symbolBuilderFor(shape, "float64")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol bigIntegerShape(BigIntegerShape shape) {
        return createBigSymbol(shape, "Int");
    }

    @Override
    public Symbol bigDecimalShape(BigDecimalShape shape) {

        return createBigSymbol(shape, "Float");
    }

    private Symbol createBigSymbol(Shape shape, String symbolName) {
        return symbolBuilderFor(shape, symbolName, SmithyGoDependency.BIG)
                .build();
    }

    @Override
    public Symbol documentShape(DocumentShape shape) {
        return ProtocolDocumentGenerator.Utilities.getDocumentSymbolBuilder(settings,
                        ProtocolDocumentGenerator.DOCUMENT_INTERFACE_NAME)
                .build();
    }

    @Override
    public Symbol operationShape(OperationShape shape) {
        String name = getDefaultShapeName(shape);
        return SymbolUtils.createPointableSymbolBuilder(shape, name, rootModuleName)
                .definitionFile(String.format("./api_op_%s.go", name))
                .build();
    }

    @Override
    public Symbol resourceShape(ResourceShape shape) {
        // TODO: implement resources
        return SymbolUtils.createPointableSymbolBuilder(shape, "nil").build();
    }

    @Override
    public Symbol serviceShape(ServiceShape shape) {
        return symbolBuilderFor(shape, "Client", rootModuleName)
                .definitionFile("./api_client.go")
                .build();
    }

    @Override
    public Symbol stringShape(StringShape shape) {
        if (shape.hasTrait(EnumTrait.class)) {
            String name = getDefaultShapeName(shape);
            return symbolBuilderFor(shape, name, typesPackageName)
                    .definitionFile("./types/enums.go")
                    .build();
        }

        return symbolBuilderFor(shape, "string")
                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                .build();
    }

    @Override
    public Symbol structureShape(StructureShape shape) {
        String name = getDefaultShapeName(shape);
        if (shape.getId().getNamespace().equals(CodegenUtils.getSyntheticTypeNamespace())) {
            Optional<String> boundOperationName = getNameOfBoundOperation(shape);
            if (boundOperationName.isPresent()) {
                return symbolBuilderFor(shape, name, rootModuleName)
                        .definitionFile("./api_op_" + boundOperationName.get() + ".go")
                        .build();
            }
        }

        Symbol.Builder builder = symbolBuilderFor(shape, name, typesPackageName);
        if (shape.hasTrait(ErrorTrait.ID)) {
            builder.definitionFile("./types/errors.go");
        } else {
            builder.definitionFile("./types/types.go");
        }

        return builder.build();
    }

    private Optional<String> getNameOfBoundOperation(StructureShape shape) {
        NeighborProvider provider = NeighborProviderIndex.of(model).getReverseProvider();
        for (Relationship relationship : provider.getNeighbors(shape)) {
            RelationshipType relationshipType = relationship.getRelationshipType();
            if (relationshipType == RelationshipType.INPUT || relationshipType == RelationshipType.OUTPUT) {
                return Optional.of(getDefaultShapeName(relationship.getNeighborShape().get()));
            }
        }
        return Optional.empty();
    }

    @Override
    public Symbol unionShape(UnionShape shape) {
        String name = getDefaultShapeName(shape);
        return symbolBuilderFor(shape, name, typesPackageName)
                .definitionFile("./types/types.go")
                .build();
    }

    @Override
    public Symbol memberShape(MemberShape member) {
        Shape targetShape = model.expectShape(member.getTarget());
        return toSymbol(targetShape)
                .toBuilder()
                .putProperty(SymbolUtils.POINTABLE, pointableIndex.isPointable(member))
                .build();
    }

    @Override
    public Symbol timestampShape(TimestampShape shape) {
        return symbolBuilderFor(shape, "Time", SmithyGoDependency.TIME).build();
    }

    @Override
    public Symbol intEnumShape(IntEnumShape shape) {
        String name = getDefaultShapeName(shape);
        return symbolBuilderFor(shape, name, typesPackageName)
                .definitionFile("./types/enums.go")
                .build();
    }
}
