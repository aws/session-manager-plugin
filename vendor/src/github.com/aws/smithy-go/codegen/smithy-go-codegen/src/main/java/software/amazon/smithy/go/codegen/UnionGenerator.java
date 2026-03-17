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

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.SimpleShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Renders unions and type aliases for all their members.
 */
@SmithyInternalApi
public class UnionGenerator {
    public static final String UNKNOWN_MEMBER_NAME = "UnknownUnionMember";

    private final Model model;
    private final SymbolProvider symbolProvider;
    private final UnionShape shape;
    private final boolean isEventStream;

    public UnionGenerator(Model model, SymbolProvider symbolProvider, UnionShape shape) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.shape = shape;
        this.isEventStream = StreamingTrait.isEventStream(shape);
    }

    /**
     * Generates the Go type definitions for the UnionShape.
     *
     * @param writer the writer
     */
    public void generateUnion(GoWriter writer) {
        Symbol symbol = symbolProvider.toSymbol(shape);
        Collection<MemberShape> memberShapes = shape.getAllMembers().values()
                .stream()
                .filter(memberShape -> !isEventStreamErrorMember(memberShape))
                .collect(Collectors.toCollection(TreeSet::new));

        // Creates the parent interface for the union, which only defines a
        // non-exported method whose purpose is only to enable satisfying the
        // interface.
        if (writer.writeShapeDocs(shape)) {
            writer.writeDocs("");
        }
        writer.writeDocs("The following types satisfy this interface:");
        memberShapes.stream().map(symbolProvider::toMemberName).forEach(name -> {
            writer.write("//  " + name);
        });
        writer.openBlock("type $L interface {", "}", symbol.getName(), () -> {
            writer.write("is$L()", symbol.getName());
        }).write("");

        // Create structs for each member that satisfy the interface.
        for (MemberShape member : memberShapes) {
            Symbol memberSymbol = symbolProvider.toSymbol(member);
            String exportedMemberName = symbolProvider.toMemberName(member);
            Shape target = model.expectShape(member.getTarget());

            // Create the member's concrete type
            writer.writeMemberDocs(model, member);
            writer.openBlock("type $L struct {", "}", exportedMemberName, () -> {
                // Union members can't have null values, so for simple shapes we don't
                // use pointers. We have to use pointers for complex shapes since,
                // for example, we could still have a map that's empty or which has
                // null values.
                if (target instanceof SimpleShape) {
                    writer.write("Value $T", memberSymbol);
                } else {
                    writer.write("Value $P", memberSymbol);
                }
                writer.write("");
                writer.write("$L", ProtocolDocumentGenerator.NO_DOCUMENT_SERDE_TYPE_NAME);
            });

            writer.write("func (*$L) is$L() {}", exportedMemberName, symbol.getName());
        }
    }

    private boolean isEventStreamErrorMember(MemberShape memberShape) {
        return isEventStream && memberShape.getMemberTrait(model, ErrorTrait.class).isPresent();
    }

    /**
     * Generates union usage examples for documentation.
     *
     * @param writer the writer
     */
    public void generateUnionExamples(GoWriter writer) {
        Symbol symbol = symbolProvider.toSymbol(shape);
        Set<MemberShape> members = shape.getAllMembers().values().stream()
                .filter(memberShape -> !isEventStreamErrorMember(memberShape))
                .collect(Collectors.toCollection(TreeSet::new));

        Set<Symbol> referenced = new HashSet<>();

        writer.openBlock("func Example$L_outputUsage() {", "}", symbol.getName(), () -> {
            writer.write("var union $P", symbol);

            writer.writeDocs("type switches can be used to check the union value");
            writer.openBlock("switch v := union.(type) {", "}", () -> {
                for (MemberShape member : members) {
                    Symbol targetSymbol = symbolProvider.toSymbol(model.expectShape(member.getTarget()));
                    referenced.add(targetSymbol);
                    Symbol memberSymbol = SymbolUtils.createValueSymbolBuilder(symbolProvider.toMemberName(member),
                            symbol.getNamespace()).build();

                    writer.openBlock("case *$T:", "", memberSymbol, () -> {
                        writer.write("_ = v.Value // Value is $T", targetSymbol);
                    });
                }
                writer.addUseImports(SmithyGoDependency.FMT);
                Symbol unknownUnionMember = SymbolUtils.createPointableSymbolBuilder("UnknownUnionMember",
                        symbol.getNamespace()).build();
                writer.openBlock("case $P:", "", unknownUnionMember, () -> {
                    writer.write("fmt.Println(\"unknown tag:\", v.Tag)");
                });
                writer.openBlock("default:", "", () -> {
                    writer.write("fmt.Println(\"union is nil or unknown type\")");
                });
            });
        }).write("");

        referenced.forEach(s -> {
            writer.write("var _ $P", s);
        });
    }

    /**
     * Generates a struct for unknown union values that applies to every union in the given set.
     *
     * @param writer         The writer to write the union to.
     * @param unions         A set of unions whose interfaces the union should apply to.
     * @param symbolProvider A symbol provider used to get the symbols for the unions.
     */
    public static void generateUnknownUnion(
            GoWriter writer,
            Collection<UnionShape> unions,
            SymbolProvider symbolProvider
    ) {
        // Creates a fallback type for use when an unknown member is found. This
        // could be the result of an outdated client, for example.
        writer.writeDocs(UNKNOWN_MEMBER_NAME
                + " is returned when a union member is returned over the wire, but has an unknown tag.");
        writer.openBlock("type $L struct {", "}", UNKNOWN_MEMBER_NAME, () -> {
            // The tag (member) name received over the wire.
            writer.write("Tag string");
            // The value received.
            writer.write("Value []byte");
            writer.write("");
            writer.write("$L", ProtocolDocumentGenerator.NO_DOCUMENT_SERDE_TYPE_NAME);
        });

        for (UnionShape union : unions) {
            writer.write("func (*$L) is$L() {}", UNKNOWN_MEMBER_NAME, symbolProvider.toSymbol(union).getName());
        }
    }
}
