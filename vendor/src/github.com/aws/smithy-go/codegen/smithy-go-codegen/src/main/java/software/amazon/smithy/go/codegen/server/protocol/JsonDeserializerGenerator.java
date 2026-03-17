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
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class JsonDeserializerGenerator {
    private final Model model;
    private final SymbolProvider symbolProvider;

    public JsonDeserializerGenerator(Model model, SymbolProvider symbolProvider) {
        this.model = model;
        this.symbolProvider = symbolProvider;
    }

    public static String getDeserializerName(Shape shape) {
        return "deserialize" + shape.getId().getName();
    }

    public Writable generate(Set<Shape> shapes) {
        return ChainWritable.of(
                shapes.stream()
                        .map(this::generateShapeDeserializer)
                        .toList()
        ).compose();
    }

    private Writable generateShapeDeserializer(Shape shape) {
        return goTemplate("""
                func $name:L(v interface{}) ($shapeType:P, error) {
                    av, ok := v.($assert:W)
                    if !ok {
                        return $zero:W, $error:T("invalid")
                    }
                    $deserialize:W
                }
                """,
                MapUtils.of(
                        "name", getDeserializerName(shape),
                        "shapeType", symbolProvider.toSymbol(shape),
                        "assert", generateOpaqueAssert(shape),
                        "zero", generateZeroValue(shape),
                        "error", GoStdlibTypes.Fmt.Errorf,
                        "deserialize", generateDeserializeAssertedValue(shape, "av")
                ));
    }

    private Writable generateOpaqueAssert(Shape shape) {
        return switch (shape.getType()) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, INT_ENUM ->
                    goTemplate("$T", GoStdlibTypes.Encoding.Json.Number);
            case STRING, BLOB, TIMESTAMP, ENUM, BIG_DECIMAL, BIG_INTEGER ->
                    goTemplate("string");
            case BOOLEAN ->
                    goTemplate("bool");
            case LIST, SET ->
                    goTemplate("[]interface{}");
            case MAP, STRUCTURE, UNION ->
                    goTemplate("map[string]interface{}");
            default ->
                    throw new CodegenException("Unsupported: " + shape.getType());
        };
    }

    private Writable generateZeroValue(Shape shape) {
        return switch (shape.getType()) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE ->
                    goTemplate("0");
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
            case TIMESTAMP ->
                    goTemplate("$T{}", GoStdlibTypes.Time.Time);
            default ->
                    throw new CodegenException("Unsupported: " + shape.getType());
        };
    }

    private Writable generateDeserializeAssertedValue(Shape shape, String ident) {
        return switch (shape.getType()) {
            case BYTE -> generateDeserializeIntegral(ident, "int8", Byte.MIN_VALUE, Byte.MAX_VALUE);
            case SHORT -> generateDeserializeIntegral(ident, "int16", Short.MIN_VALUE, Short.MAX_VALUE);
            case INTEGER -> generateDeserializeIntegral(ident, "int32", Integer.MIN_VALUE, Integer.MAX_VALUE);
            case LONG -> generateDeserializeIntegral(ident, "int64", Long.MIN_VALUE, Long.MAX_VALUE);
            case STRING, BOOLEAN -> goTemplate("return $L, nil", ident);
            case ENUM -> goTemplate("return $T($L), nil", symbolProvider.toSymbol(shape), ident);
            case BLOB -> goTemplate("""
                    p, err := $b64:T.DecodeString($ident:L)
                    if err != nil {
                        return nil, err
                    }
                    return p, nil
                    """,
                    MapUtils.of(
                            "ident", ident,
                            "b64", GoStdlibTypes.Encoding.Base64.StdEncoding
                    ));
            case LIST, SET -> {
                var target = normalize(model.expectShape(((CollectionShape) shape).getMember().getTarget()));
                var symbol = symbolProvider.toSymbol(shape);
                var targetSymbol = symbolProvider.toSymbol(target);
                yield goTemplate("""
                        var deserializedList $type:T
                        for _, serializedItem := range $ident:L {
                            deserializedItem, err := $deserialize:L(serializedItem)
                            if err != nil {
                                return nil, err
                            }
                            deserializedList = append(deserializedList, $deref:L)
                        }
                        return deserializedList, nil
                        """,
                        MapUtils.of(
                                "type", symbol,
                                "ident", ident,
                                "deserialize", getDeserializerName(target),
                                "deref", isPointable(getReference(symbol)) != isPointable(targetSymbol)
                                        ? "*deserializedItem" : "deserializedItem"
                        ));
            }
            case MAP -> {
                var value = normalize(model.expectShape(((MapShape) shape).getValue().getTarget()));
                var symbol = symbolProvider.toSymbol(shape);
                var valueSymbol = symbolProvider.toSymbol(value);
                yield goTemplate("""
                        deserializedMap := $type:T{}
                        for key, serializedValue := range $ident:L {
                            deserializedValue, err := $deserialize:L(serializedValue)
                            if err != nil {
                                return nil, err
                            }
                            deserializedMap[key] = $deref:L
                        }
                        return deserializedMap, nil
                        """,
                        MapUtils.of(
                                "type", symbol,
                                "ident", ident,
                                "deserialize", getDeserializerName(value),
                                "deref", isPointable(getReference(symbol)) != isPointable(valueSymbol)
                                        ? "*deserializedValue" : "deserializedValue"
                        ));
            }
            case STRUCTURE -> goTemplate("""
                    deserializedStruct := &$type:T{}
                    for key, serializedValue := range $ident:L {
                        $deserializeFields:W
                    }
                    return deserializedStruct, nil
                    """,
                    MapUtils.of(
                            "type", symbolProvider.toSymbol(shape),
                            "ident", ident,
                            "deserializeFields", ChainWritable.of(
                                    shape.getAllMembers().entrySet().stream()
                                            .map(it -> {
                                                var target = model.expectShape(it.getValue().getTarget());
                                                return goTemplate("""
                                                        if key == $field:S {
                                                            fieldValue, err := $deserialize:L(serializedValue)
                                                            if err != nil {
                                                                return nil, err
                                                            }
                                                            deserializedStruct.$fieldName:L = $deref:W
                                                        }
                                                        """,
                                                        MapUtils.of(
                                                                "field", it.getKey(),
                                                                "fieldName", symbolProvider.toMemberName(it.getValue()),
                                                                "deserialize", getDeserializerName(normalize(target)),
                                                                "deref", generateStructFieldDeref(
                                                                        it.getValue(), "fieldValue")
                                                        ));
                                            })
                                            .toList()
                            ).compose(false)
                    ));
            case UNION -> goTemplate("""
                    for key, serializedValue := range $ident:L {
                        $deserializeVariant:W
                    }
                    """,
                    MapUtils.of(
                            "type", symbolProvider.toSymbol(shape),
                            "ident", ident,
                            "deserializeVariant", ChainWritable.of(
                                    shape.getAllMembers().entrySet().stream()
                                            .map(it -> {
                                                var target = model.expectShape(it.getValue().getTarget());
                                                return goTemplate("""
                                                        if key == $variant:S {
                                                            variant, err := $deserialize:L(serializedValue)
                                                            if err != nil {
                                                                return nil, err
                                                            }
                                                            return variant, nil
                                                        }
                                                        """,
                                                        MapUtils.of(
                                                                "variant", it.getKey(),
                                                                "deserialize", getDeserializerName(normalize(target))
                                                        ));
                                            })
                                            .toList()
                            ).compose(false)
                    ));
            case TIMESTAMP -> goTemplate("""
                    dts, err := $T(serializedValue)
                    if err != nil {
                        return nil, err
                    }
                    return dts, nil
                    """, SmithyGoTypes.Time.ParseDateTime);
            default ->
                    throw new CodegenException("Unsupported: " + shape.getType());
        };
    }

    private Writable generateDeserializeIntegral(String ident, String castTo, long min, long max) {
        return goTemplate("""
                $nextident:L, err := $ident:L.Int64()
                if err != nil {
                    return 0, err
                }
                if $nextident:L < $min:L || $nextident:L > $max:L {
                    return 0, $errorf:T("invalid")
                }
                return $cast:L($nextident:L), nil
                """,
                MapUtils.of(
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "ident", ident,
                        "nextident", ident + "_",
                        "min", min,
                        "max", max,
                        "cast", castTo
                ));
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
            case STRING -> goTemplate("$T($L)", SmithyGoTypes.Ptr.String, ident);
            case BOOLEAN -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Bool, ident);
            default -> goTemplate(ident);
        };
    }

}
