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

package software.amazon.smithy.go.codegen.serde.cbor;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildSymbol;
import static software.amazon.smithy.go.codegen.SymbolUtils.getReference;
import static software.amazon.smithy.go.codegen.SymbolUtils.isNilable;
import static software.amazon.smithy.go.codegen.SymbolUtils.isPointable;
import static software.amazon.smithy.go.codegen.serde.SerdeUtil.normalize;

import java.util.Set;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.ProtocolDocumentGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class CborDeserializerGenerator {
    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoSettings settings;

    public CborDeserializerGenerator(ProtocolGenerator.GenerationContext ctx) {
        this.model = ctx.getModel();
        this.symbolProvider = ctx.getSymbolProvider();
        this.settings = ctx.getSettings();
    }

    public static String getDeserializerName(Shape shape) {
        return "deserializeCBOR_" + shape.getId().getName();
    }

    public Writable generate(Set<Shape> shapes) {
        return ChainWritable.of(
                shapes.stream()
                        .map(this::deserializeShape)
                        .toList()
        ).compose();
    }

    private Writable deserializeShape(Shape shape) {
        return switch (shape.getType()) {
            case BIG_INTEGER, BIG_DECIMAL ->
                    throw new CodegenException("arbitrary-precision nums are not supported (" + shape.getType() + ")");
            case BYTE -> deserializeStatic(shape, SmithyGoTypes.Encoding.Cbor.AsInt8); // special types with coercers
            case SHORT -> deserializeStatic(shape, SmithyGoTypes.Encoding.Cbor.AsInt16);
            case INTEGER -> deserializeStatic(shape, SmithyGoTypes.Encoding.Cbor.AsInt32);
            case LONG -> deserializeStatic(shape, SmithyGoTypes.Encoding.Cbor.AsInt64);
            case FLOAT -> deserializeStatic(shape, SmithyGoTypes.Encoding.Cbor.AsFloat32);
            case DOUBLE -> deserializeStatic(shape, SmithyGoTypes.Encoding.Cbor.AsFloat64);
            case TIMESTAMP -> deserializeStatic(shape, SmithyGoTypes.Encoding.Cbor.AsTime);
            case INT_ENUM -> deserializeIntEnum(shape);
            case STRING -> deserializeString(shape);
            case DOCUMENT -> deserializeDocument(shape); // implemented, but not currently supported
            default -> deserializeAssertFunc(shape); // everything else is a static assert
        };
    }

    private Writable deserializeStatic(Shape shape, Symbol coercer) {
        return goTemplate("""
                func $deserName:L(v $cborValue:T) ($type:T, error) {
                    return $coercer:T(v)
                }
                """,
                MapUtils.of(
                        "deserName", getDeserializerName(shape),
                        "cborValue", SmithyGoTypes.Encoding.Cbor.Value,
                        "type", symbolProvider.toSymbol(shape),
                        "coercer", coercer
                ));
    }

    private Writable deserializeIntEnum(Shape shape) {
        return goTemplate("""
                func $name:L(v $cborValue:T) ($shapeType:T, error) {
                    av, err := $asInt32:T(v)
                    if err != nil {
                        return 0, err
                    }
                    return $shapeType:T(av), nil
                }
                """,
                MapUtils.of(
                        "name", getDeserializerName(shape),
                        "cborValue", SmithyGoTypes.Encoding.Cbor.Value,
                        "shapeType", symbolProvider.toSymbol(shape),
                        "asInt32", SmithyGoTypes.Encoding.Cbor.AsInt32
                ));
    }

    private Writable deserializeString(Shape shape) {
        return goTemplate("""
                func $name:L(v $cborValue:T) (string, error) {
                    av, ok := v.($assert:T)
                    if !ok {
                        return "", $error:T("unexpected value type %T", v)
                    }
                    return string(av), nil
                }
                """,
                MapUtils.of(
                        "name", getDeserializerName(shape),
                        "cborValue", SmithyGoTypes.Encoding.Cbor.Value,
                        "assert", SmithyGoTypes.Encoding.Cbor.String,
                        "error", GoStdlibTypes.Fmt.Errorf
                ));
    }

    private Writable deserializeAssertFunc(Shape shape) {
        return goTemplate("""
                func $name:L(v $cborValue:T) ($shapeType:P, error) {
                    av, ok := v.($assert:W)
                    if !ok {
                        return $zero:W, $error:T("unexpected value type %T", v)
                    }
                    $deserialize:W
                }
                """,
                MapUtils.of(
                        "name", getDeserializerName(shape),
                        "cborValue", SmithyGoTypes.Encoding.Cbor.Value,
                        "shapeType", symbolProvider.toSymbol(shape),
                        "assert", typeAssert(shape),
                        "zero", zeroValue(shape),
                        "error", GoStdlibTypes.Fmt.Errorf,
                        "deserialize", deserializeAsserted(shape, "av")
                ));
    }

    private Writable typeAssert(Shape shape) {
        return switch (shape.getType()) {
            case STRING, ENUM ->
                    goTemplate("$T", SmithyGoTypes.Encoding.Cbor.String);
            case BLOB ->
                    goTemplate("$T", SmithyGoTypes.Encoding.Cbor.Slice);
            case LIST, SET ->
                    goTemplate("$T", SmithyGoTypes.Encoding.Cbor.List);
            case MAP, STRUCTURE, UNION ->
                    goTemplate("$T", SmithyGoTypes.Encoding.Cbor.Map);
            case TIMESTAMP, BIG_DECIMAL, BIG_INTEGER ->
                    goTemplate("$P", SmithyGoTypes.Encoding.Cbor.Tag);
            case BOOLEAN ->
                    goTemplate("$T", SmithyGoTypes.Encoding.Cbor.Bool);
            default ->
                    throw new CodegenException("Unexpected shape for single-assert: " + shape.getType());
        };
    }

    private Writable zeroValue(Shape shape) {
        return switch (shape.getType()) {
            case STRING ->
                    goTemplate("\"\"");
            case BOOLEAN ->
                    goTemplate("false");
            case BLOB, LIST, SET, MAP, STRUCTURE, UNION ->
                    goTemplate("nil");
            case ENUM ->
                    goTemplate("$T(\"\")", symbolProvider.toSymbol(shape));
            case INT_ENUM ->
                    goTemplate("$T(0)", symbolProvider.toSymbol(shape));
            default ->
                    throw new CodegenException("Unexpected shape for zero-value: " + shape.getType());
        };
    }

    private Writable deserializeAsserted(Shape shape, String ident) {
        return switch (shape.getType()) {
            case STRING -> goTemplate("return string($L), nil", ident);
            case ENUM -> goTemplate("return $T($L), nil", symbolProvider.toSymbol(shape), ident);
            case BOOLEAN -> goTemplate("return bool($L), nil", ident);
            case BLOB -> goTemplate("return []byte($L), nil", ident);
            case LIST, SET -> deserializeList((CollectionShape) shape, ident);
            case MAP -> deserializeMap((MapShape) shape, ident);
            case STRUCTURE -> deserializeStruct((StructureShape) shape, ident);
            case UNION -> deserializeUnion((UnionShape) shape, ident);
            default ->
                    throw new CodegenException("Cannot deserialize " + shape.getType());
        };
    }

    private Writable deserializeList(CollectionShape shape, String ident) {
        var target = normalize(model.expectShape(shape.getMember().getTarget()));
        var symbol = symbolProvider.toSymbol(shape);
        var targetSymbol = symbolProvider.toSymbol(target);
        return goTemplate("""
                var dl $type:T
                for _, si := range $ident:L {
                    $sparse:W
                    di, err := $deserialize:L(si)
                    if err != nil {
                        return nil, err
                    }
                    dl = append(dl, $deref:L di)
                }
                return dl, nil
                """,
                MapUtils.of(
                        "type", symbol,
                        "ident", ident,
                        "deserialize", getDeserializerName(target),
                        "deref", resolveDeref(getReference(symbol), targetSymbol),
                        "sparse", isNilable(getReference(symbol)) ? handleSparseList() : emptyGoTemplate()
                ));
    }

    private Writable handleSparseList() {
        return goTemplate("""
                if _, ok := si.($P); ok {
                    dl = append(dl, nil)
                    continue
                }
                """, SmithyGoTypes.Encoding.Cbor.Nil);
    }

    private Writable deserializeMap(MapShape shape, String ident) {
        var value = normalize(model.expectShape(shape.getValue().getTarget()));
        var symbol = symbolProvider.toSymbol(shape);
        var valueSymbol = symbolProvider.toSymbol(value);
        return goTemplate("""
                dm := $type:T{}
                for key, sv := range $ident:L {
                    $sparse:W
                    dv, err := $deserialize:L(sv)
                    if err != nil {
                        return nil, err
                    }
                    dm[key] = $deref:L dv
                }
                return dm, nil
                """,
                MapUtils.of(
                        "type", symbol,
                        "ident", ident,
                        "deserialize", getDeserializerName(value),
                        "deref", resolveDeref(getReference(symbol), valueSymbol),
                        "sparse", isNilable(getReference(symbol)) ? handleSparseMap() : emptyGoTemplate()
                ));
    }

    private Writable handleSparseMap() {
        return goTemplate("""
                if _, ok := sv.($P); ok {
                    dm[key] = nil
                    continue
                }
                """, SmithyGoTypes.Encoding.Cbor.Nil);
    }

    private String resolveDeref(Symbol ref, Symbol deserialized) {
        if (isPointable(ref) == isPointable(deserialized)) {
            return "";
        }
        return isPointable(deserialized) ? "*" : "&";
    }

    private Writable deserializeStruct(StructureShape shape, String ident) {
        return goTemplate("""
                ds := &$type:T{}
                for key, sv := range $ident:L {
                    _, _ = key, sv
                    $fields:W
                }
                return ds, nil
                """,
                MapUtils.of(
                        "type", symbolProvider.toSymbol(shape),
                        "ident", ident,
                        "fields", ChainWritable.of(
                                shape.getAllMembers().values().stream()
                                        .map(this::deserializeField)
                                        .toList()
                        ).compose()
                ));
    }

    private Writable deserializeField(MemberShape member) {
        var target = model.expectShape(member.getTarget());
        if (target.hasTrait(StreamingTrait.class)) {
            return emptyGoTemplate(); // event stream, not an actual field
        }

        var memberSymbol = symbolProvider.toSymbol(member);
        return goTemplate("""
                if key == $field:S {
                    $nilable:W
                    dv, err := $deserialize:L(sv)
                    if err != nil {
                        return nil, err
                    }
                    ds.$fieldName:L = $deref:W
                }
                """,
                MapUtils.of(
                        "field", member.getMemberName(),
                        "fieldName", symbolProvider.toMemberName(member),
                        "deserialize", getDeserializerName(normalize(target)),
                        "deref", generateStructFieldDeref(member, "dv"),
                        "nilable", isNilable(memberSymbol) ? handleSparseField() : emptyGoTemplate()
                ));
    }

    private Writable handleSparseField() {
        return goTemplate("""
                if _, ok := sv.($P); ok {
                    continue
                }""", SmithyGoTypes.Encoding.Cbor.Nil);
    }

    private Writable generateStructFieldDeref(MemberShape member, String ident) {
        var symbol = symbolProvider.toSymbol(member);
        if (!isPointable(symbol)) {
            return goTemplate(ident);
        }
        return switch (model.expectShape(member.getTarget()).getType()) {
            case BYTE -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Int8, ident);
            case SHORT -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Int16, ident);
            case INTEGER -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Int32, ident);
            case LONG -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Int64, ident);
            case FLOAT -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Float32, ident);
            case DOUBLE -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Float64, ident);
            case STRING -> goTemplate("$T($L)", SmithyGoTypes.Ptr.String, ident);
            case BOOLEAN -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Bool, ident);
            case TIMESTAMP -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Time, ident);
            default -> goTemplate(ident);
        };
    }

    private Writable deserializeUnion(UnionShape union, String ident) {
        return goTemplate("""
                for key, sv := range $ident:L {
                    $variants:W
                }
                return nil, $errorf:T("unrecognized variant")
                """,
                MapUtils.of(
                        "type", symbolProvider.toSymbol(union),
                        "ident", ident,
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "variants", ChainWritable.of(
                                union.getAllMembers().values().stream()
                                        .map(it -> deserializeVariant(union, it, "sv"))
                                        .toList()
                        ).compose()
                ));
    }

    private Writable deserializeVariant(UnionShape union, MemberShape member, String ident) {
        var target = normalize(model.expectShape(member.getTarget()));
        var symbol = symbolProvider.toSymbol(union);
        var variantSymbol = buildSymbol(symbolProvider.toMemberName(member), symbol.getNamespace());
        return goTemplate("""
                if key == $variantName:S {
                    if _, ok := $ident:L.($cborNil:P); ok {
                        continue
                    }
                    dv, err := $deserialize:L($ident:L)
                    if err != nil {
                        return nil, err
                    }
                    return &$variantSymbol:T{Value: $deref:L dv}, nil
                }
                """,
                MapUtils.of(
                        "cborNil", SmithyGoDependency.SMITHY_CBOR.struct("Nil"),
                        "variantName", member.getMemberName(),
                        "deserialize", getDeserializerName(target),
                        "ident", ident,
                        "variantSymbol", variantSymbol,
                        "deref", target.getType() == ShapeType.STRUCTURE ? "*" : ""
                ));
    }

    private Writable deserializeDocument(Shape shape) {
        var unmarshaler = ProtocolDocumentGenerator.Utilities.getInternalDocumentSymbolBuilder(settings,
                ProtocolDocumentGenerator.INTERNAL_NEW_DOCUMENT_UNMARSHALER_FUNC).build();
        return goTemplate("""
                func $deser:L(v $cborValue:T) ($document:T, error) {
                    return $unmarshaler:T(v), nil
                }
                """,
                MapUtils.of(
                        "deser", getDeserializerName(shape),
                        "cborValue", SmithyGoTypes.Encoding.Cbor.Value,
                        "document", symbolProvider.toSymbol(shape),
                        "unmarshaler", unmarshaler
                ));
    }
}
