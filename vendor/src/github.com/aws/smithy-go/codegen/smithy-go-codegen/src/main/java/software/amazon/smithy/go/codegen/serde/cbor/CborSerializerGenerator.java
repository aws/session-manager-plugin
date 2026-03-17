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
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class CborSerializerGenerator {
    private final Model model;
    private final SymbolProvider symbolProvider;

    public CborSerializerGenerator(ProtocolGenerator.GenerationContext ctx) {
        this.model = ctx.getModel();
        this.symbolProvider = ctx.getSymbolProvider();
    }

    public static String getSerializerName(Shape shape) {
        return "serializeCBOR_" + shape.getId().getName();
    }

    public Writable generate(Set<Shape> shapes) {
        return ChainWritable.of(
                shapes.stream()
                        .map(this::generateShapeSerializer)
                        .toList()
        ).compose();
    }

    private Writable generateShapeSerializer(Shape shape) {
        return goTemplate("""
                func $name:L(v $shapeType:P) ($cborValue:T, error) {
                    $serialize:W
                }
                """,
                MapUtils.of(
                        "name", getSerializerName(shape),
                        "shapeType", symbolProvider.toSymbol(shape),
                        "cborValue", SmithyGoTypes.Encoding.Cbor.Value,
                        "serialize", generateSerializeValue(shape)
                ));
    }

    private Writable generateSerializeValue(Shape shape) {
        return switch (shape.getType()) {
            case BYTE, SHORT, INTEGER, LONG, INT_ENUM -> generateSerializeIntegral();
            case FLOAT -> goTemplate("return $T(v), nil", SmithyGoTypes.Encoding.Cbor.Float32);
            case DOUBLE -> goTemplate("return $T(v), nil", SmithyGoTypes.Encoding.Cbor.Float64);
            case STRING -> goTemplate("return $T(v), nil", SmithyGoTypes.Encoding.Cbor.String);
            case BOOLEAN -> goTemplate("return $T(v), nil", SmithyGoTypes.Encoding.Cbor.Bool);
            case BLOB -> goTemplate("return $T(v), nil", SmithyGoTypes.Encoding.Cbor.Slice);
            case ENUM -> goTemplate("return $T(string(v)), nil", SmithyGoTypes.Encoding.Cbor.String);
            case TIMESTAMP -> generateSerializeTimestamp((TimestampShape) shape);
            case LIST, SET -> generateSerializeList((CollectionShape) shape);
            case MAP -> generateSerializeMap((MapShape) shape);
            case STRUCTURE -> generateSerializeStruct((StructureShape) shape);
            case UNION -> generateSerializeUnion((UnionShape) shape);
            case DOCUMENT -> serializeDocument((DocumentShape) shape); // implemented, but not currently supported
            case BIG_INTEGER, BIG_DECIMAL ->
                    throw new CodegenException("arbitrary-precision nums are not supported (" + shape.getType() + ")");
            case MEMBER, SERVICE, RESOURCE, OPERATION ->
                    throw new CodegenException("cannot generate serializer for shape type " + shape.getType());
        };
    }

    private Writable generateSerializeIntegral() {
        return goTemplate("""
                if v < 0 {
                    return $T(uint64(-v)), nil
                }
                return $T(uint64(v)), nil
                """, SmithyGoTypes.Encoding.Cbor.NegInt, SmithyGoTypes.Encoding.Cbor.Uint);
    }

    private Writable generateSerializeTimestamp(TimestampShape shape) {
        return goTemplate("""
                return &$tag:T{
                    ID:    1,
                    Value: $float64:T(float64(v.UnixMilli()) / 1000),
                }, nil
                """,
                MapUtils.of(
                        "tag", SmithyGoTypes.Encoding.Cbor.Tag,
                        "float64", SmithyGoTypes.Encoding.Cbor.Float64
                ));
    }

    private Writable generateSerializeList(CollectionShape shape) {
        var target = normalize(model.expectShape(shape.getMember().getTarget()));
        var symbol = symbolProvider.toSymbol(shape);
        var targetSymbol = symbolProvider.toSymbol(target);
        return goTemplate("""
                    vl := $list:T{}
                    for i := range v {
                        $sparse:W
                        ser, err := $serialize:L($indirect:L v[i])
                        if err != nil {
                            return nil, err
                        }
                        vl = append(vl, ser)
                    }
                    return vl, nil
                    """,
                MapUtils.of(
                        "list", SmithyGoTypes.Encoding.Cbor.List,
                        "sparse", isNilable(getReference(symbol)) ? handleSparseList() : emptyGoTemplate(),
                        "serialize", getSerializerName(target),
                        "indirect", resolveIndirect(getReference(symbol), targetSymbol)
                ));
    }

    private Writable handleSparseList() {
        return goTemplate("""
                if v[i] == nil {
                    vl = append(vl, &$T{})
                    continue
                }
                """, SmithyGoTypes.Encoding.Cbor.Nil);
    }

    private Writable generateSerializeMap(MapShape shape) {
        var value = normalize(model.expectShape(shape.getValue().getTarget()));
        var symbol = symbolProvider.toSymbol(shape);
        var valueSymbol = symbolProvider.toSymbol(value);
        return goTemplate("""
                vm := $map:T{}
                for k, vv := range v {
                    $sparse:W
                    ser, err := $serialize:L($indirect:L vv)
                    if err != nil {
                        return nil, err
                    }
                    vm[k] = ser
                }
                return vm, nil
                """,
                MapUtils.of(
                        "map", SmithyGoTypes.Encoding.Cbor.Map,
                        "sparse", isNilable(getReference(symbol)) ? handleSparseMap() : emptyGoTemplate(),
                        "serialize", getSerializerName(value),
                        "indirect", resolveIndirect(getReference(symbol), valueSymbol)
                ));
    }

    private Writable handleSparseMap() {
        return goTemplate("""
                if vv == nil {
                    vm[k] = &$T{}
                    continue
                }
                """, SmithyGoTypes.Encoding.Cbor.Nil);
    }

    private Writable generateSerializeStruct(StructureShape shape) {
        return goTemplate("""
                vm := $map:T{}
                $serialize:W
                return vm, nil
                """,
                MapUtils.of(
                        "map", SmithyGoTypes.Encoding.Cbor.Map,
                        "serialize", ChainWritable.of(
                                shape.getAllMembers().values().stream()
                                        .map(this::generateSerializeField)
                                        .toList()
                        ).compose(false)
                ));
    }

    private Writable generateSerializeField(MemberShape member) {
        var target = normalize(model.expectShape(member.getTarget()));
        if (target.hasTrait(StreamingTrait.class)) {
            return emptyGoTemplate(); // event stream, not an actual field
        }

        var symbol = symbolProvider.toSymbol(member);
        return switch (target.getType()) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, BOOLEAN, TIMESTAMP ->
                    isPointable(symbol)
                            ? serializeNilableMember(member, target, true)
                            : serializeMember(member, target);
            case BLOB, LIST, SET, MAP, STRUCTURE, UNION ->
                    serializeNilableMember(member, target, false);
            case STRING ->
                    isPointable(symbol)
                            ? serializeNilableMember(member, target, true)
                            : serializeString(member, target);
            case ENUM ->
                    serializeString(member, target);
            default ->
                    serializeMember(member, target);
        };
    }

    private Writable serializeString(MemberShape member, Shape target) {
        return goTemplate("""
               if len(v.$field:L) > 0 {
                   ser, err := $serialize:L(v.$field:L)
                   if err != nil {
                       return nil, err
                   }
                   vm[$key:S] = ser
               }
               """,
                MapUtils.of(
                        "field", symbolProvider.toMemberName(member),
                        "key", member.getMemberName(),
                        "serialize", getSerializerName(target)
                ));
    }

    private Writable serializeNilableMember(MemberShape member, Shape target, boolean deref) {
        return goTemplate("""
                if v.$field:L != nil {
                    ser, err := $serialize:L($deref:L v.$field:L)
                    if err != nil {
                        return nil, err
                    }
                    vm[$key:S] = ser
                }
                """,
                MapUtils.of(
                        "field", symbolProvider.toMemberName(member),
                        "key", member.getMemberName(),
                        "serialize", getSerializerName(target),
                        "deref", deref ? "*" : ""
                ));
    }

    private Writable serializeMember(MemberShape member, Shape target) {
        return goTemplate("""
                ser$key:L, err := $serialize:L(v.$field:L)
                if err != nil {
                    return nil, err
                }
                vm[$key:S] = ser$key:L
                """,
                MapUtils.of(
                        "field", symbolProvider.toMemberName(member),
                        "key", member.getMemberName(),
                        "serialize", getSerializerName(target)
                ));
    }

    private Writable generateSerializeUnion(UnionShape union) {
        return goTemplate("""
                vm := $map:T{}
                switch uv := v.(type) {
                $serialize:W
                default:
                    return nil, $errorf:T("unknown variant type %T", v)
                }
                return vm, nil
                """,
                MapUtils.of(
                        "map", SmithyGoTypes.Encoding.Cbor.Map,
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "serialize", ChainWritable.of(
                                union.getAllMembers().values().stream()
                                        .map(it -> serializeVariant(union, it))
                                        .toList()
                        ).compose(false)
                ));
    }

    private Writable serializeVariant(UnionShape union, MemberShape member) {
        var target = normalize(model.expectShape(member.getTarget()));
        var symbol = symbolProvider.toSymbol(union);
        var variantSymbol = buildSymbol(symbolProvider.toMemberName(member), symbol.getNamespace());
        return goTemplate("""
                case *$variant:T:
                    ser, err := $serialize:L($indirect:L uv.Value)
                    if err != nil {
                        return nil, err
                    }
                    vm[$key:S] = ser
                """,
                MapUtils.of(
                        "variant", variantSymbol,
                        "serialize", getSerializerName(target),
                        "key", member.getMemberName(),
                        "indirect", target.getType() == ShapeType.STRUCTURE ? "&" : ""
                ));
    }

    private Writable serializeDocument(DocumentShape document) {
        return goTemplate("""
                raw, err := v.MarshalSmithyDocument()
                if err != nil {
                    return nil, err
                }
                return $encodeRaw:T(raw), nil
                """,
                MapUtils.of(
                        "encoder", SmithyGoTypes.Document.Cbor.NewEncoder,
                        "encodeRaw", SmithyGoTypes.Encoding.Cbor.EncodeRaw
                ));
    }

    private String resolveIndirect(Symbol ref, Symbol serialized) {
        if (isPointable(ref) == isPointable(serialized)) {
            return "";
        }
        return isPointable(serialized) ? "&" : "*";
    }
}
