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

package software.amazon.smithy.go.codegen.server.protocol;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.getReference;
import static software.amazon.smithy.go.codegen.SymbolUtils.isPointable;
import static software.amazon.smithy.go.codegen.server.ServerCodegenUtil.normalize;

import java.util.Set;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class JsonSerializerGenerator {
    private final Model model;
    private final SymbolProvider symbolProvider;

    public JsonSerializerGenerator(Model model, SymbolProvider symbolProvider) {
        this.model = model;
        this.symbolProvider = symbolProvider;
    }

    public static String getSerializerName(Shape shape) {
        return "serialize" + shape.getId().getName();
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
                func $name:L(v $shapeType:P, jv $jsonValue:T) (error) {
                    $serialize:W
                    return nil
                }
                """,
                MapUtils.of(
                        "name", getSerializerName(shape),
                        "shapeType", symbolProvider.toSymbol(shape),
                        "jsonValue", SmithyGoTypes.Encoding.Json.Value,
                        "serialize", generateSerializeValue(shape)
                ));
    }

    private Writable generateSerializeValue(Shape shape) {
        return switch (shape.getType()) {
            case BYTE -> goTemplate("jv.Byte(v)");
            case SHORT -> goTemplate("jv.Short(v)");
            case INTEGER -> goTemplate("jv.Integer(v)");
            case LONG -> goTemplate("jv.Long(v)");
            case FLOAT -> goTemplate("jv.Float(v)");
            case DOUBLE -> goTemplate("jv.Double(v)");
            case STRING -> goTemplate("jv.String(v)");
            case BOOLEAN -> goTemplate("jv.Boolean(v)");
            case BLOB -> goTemplate("jv.Base64EncodeBytes(v)");
            case ENUM -> goTemplate("jv.String(string(v))");
            case INT_ENUM -> goTemplate("jv.Integer(int32(v))");
            case TIMESTAMP -> generateSerializeTimestamp((TimestampShape) shape);
            case LIST, SET -> generateSerializeList((CollectionShape) shape);
            case MAP -> generateSerializeMap((MapShape) shape);
            case STRUCTURE -> generateSerializeStruct((StructureShape) shape);
            case UNION -> generateSerializeUnion((UnionShape) shape);
            default ->
                    throw new CodegenException("Unsupported: " + shape.getType());
        };
    }

    private Writable generateSerializeTimestamp(TimestampShape shape) {
        return goTemplate("""
                if v != nil {
                    jv.String($T(*v))
                }
                """, SmithyGoTypes.Time.FormatDateTime);
    }

    private Writable generateSerializeList(CollectionShape shape) {
            var target = normalize(model.expectShape(shape.getMember().getTarget()));
            var symbol = symbolProvider.toSymbol(shape);
            var targetSymbol = symbolProvider.toSymbol(target);
            return goTemplate("""
                    a := jv.Array()
                    defer a.Close()
                    for i := range v {
                        av := a.Value()
                        if err := $serialize:L($indirect:L, av); err != nil {
                            return err
                        }
                    }
                    """,
                    MapUtils.of(
                            "serialize", getSerializerName(target),
                            "indirect", isPointable(getReference(symbol)) != isPointable(targetSymbol)
                                    ? "&v[i]" : "v[i]"
                    ));
    }

    private Writable generateSerializeMap(MapShape shape) {
        var value = normalize(model.expectShape(shape.getValue().getTarget()));
        var symbol = symbolProvider.toSymbol(shape);
        var valueSymbol = symbolProvider.toSymbol(value);
        return goTemplate("""
                mp := jv.Object()
                defer mp.Close()
                for k, vv := range v {
                    mv := mp.Key(k)
                    if err := $serialize:L($indirect:L, mv); err != nil {
                        return err
                    }
                }
                """,
                MapUtils.of(
                        "serialize", getSerializerName(value),
                        "indirect", isPointable(getReference(symbol)) != isPointable(valueSymbol)
                                ? "&vv" : "vv"
                ));
    }

    private Writable generateSerializeStruct(StructureShape shape) {
        return goTemplate("""
                mp := jv.Object()
                defer mp.Close()
                $W
                """, ChainWritable.of(
                        shape.getAllMembers().values().stream()
                                .map(this::generateSerializeField)
                                .toList()
                ).compose(false));
    }

    private Writable generateSerializeField(MemberShape member) {
        var symbol = symbolProvider.toSymbol(member);
        var target = normalize(model.expectShape(member.getTarget()));
        return switch (target.getType()) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, STRING, BOOLEAN ->
                    isPointable(symbol)
                            ? serializeNilableMember(member, target, true)
                            : serializeMember(member, target);
            case BLOB, LIST, SET, MAP, STRUCTURE, UNION ->
                    serializeNilableMember(member, target, false);
            default ->
                    serializeMember(member, target);
        };
    }

    private Writable serializeNilableMember(MemberShape member, Shape target, boolean deref) {
        return goTemplate("""
                if v.$field:L != nil {
                    if err := $serialize:L($deref:L v.$field:L, mp.Key($key:S)); err != nil {
                        return err
                    }
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
                if err := $serialize:L(v.$field:L, mp.Key($key:S)); err != nil {
                    return err
                }
                """,
                MapUtils.of(
                        "field", symbolProvider.toMemberName(member),
                        "key", member.getMemberName(),
                        "serialize", getSerializerName(target)
                ));
    }

    private Writable generateSerializeUnion(UnionShape shape) {
        return goTemplate("""
                mp := jv.Object()
                defer mp.Close()
                $W
                """, ChainWritable.of(
                shape.getAllMembers().values().stream()
                        .map(this::generateSerializeVariant)
                        .toList()
        ).compose(false));
    }

    private Writable generateSerializeVariant(MemberShape member) {
        var target = normalize(model.expectShape(member.getTarget()));
        return goTemplate("""
                if variant, ok := v.($variant:P); ok {
                    if err := $serialize:L(variant, mp.Key($key:S)); err != nil {
                        return err
                    }
                }
                """,
                MapUtils.of(
                        "variant", symbolProvider.toSymbol(target),
                        "serialize", getSerializerName(target),
                        "key", member.getMemberName()
                ));
    }
}
