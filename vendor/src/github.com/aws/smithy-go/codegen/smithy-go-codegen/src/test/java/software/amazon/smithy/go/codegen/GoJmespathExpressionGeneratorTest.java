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

import static org.hamcrest.MatcherAssert.assertThat;
import static software.amazon.smithy.go.codegen.util.ShapeUtil.listOf;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.jmespath.JmespathExpression;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ShapeId;

import java.util.Map;

public class GoJmespathExpressionGeneratorTest {
    private static final String TEST_MODEL_STR = """
            $version: "2.0"

            namespace smithy.go.test

            service Test {
            }

            structure Struct {
                simpleShape: String
                simpleShape2: String
                objectList: ObjectList
                objectMap: ObjectMap
                nested: NestedStruct
                nullableIntegerA: Integer
                nullableIntegerB: Integer
            }

            structure Object {
                key: String
                innerObjectList: InnerObjectList
            }

            structure InnerObject {
                innerKey: String
            }

            structure NestedStruct {
                nestedField: String
            }

            list ObjectList {
                member: Object
            }

            list InnerObjectList {
                member: InnerObject
            }

            map ObjectMap {
                key: String,
                value: Object
            }
            """;

    private static final Model TEST_MODEL = Model.assembler()
            .addUnparsedModel("model.smithy", TEST_MODEL_STR)
            .assemble().unwrap();

    private static final GoSettings TEST_SETTINGS = GoSettings.from(ObjectNode.fromStringMap(Map.of(
            "service", "smithy.go.test#Test",
            "module", "github.com/aws/aws-sdk-go-v2/test"
    )));

    private static GoWriter testWriter() {
        return new GoWriter("test").setIndentText("    "); // for ease of string comparison
    }

    private static GoCodegenContext testContext() {
        return new GoCodegenContext(
                TEST_MODEL, TEST_SETTINGS,
                new SymbolVisitor(TEST_MODEL, TEST_SETTINGS),
                null, null, null
        );
    }

    @Test
    public void testFieldExpression() {
        var expr = "simpleShape";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(TEST_MODEL.expectShape(ShapeId.from("smithy.api#String"))));
        assertThat(actual.ident(), Matchers.equalTo("v1"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.SimpleShape
                """));
    }

    @Test
    public void testSubexpression() {
        var expr = "nested.nestedField";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(TEST_MODEL.expectShape(ShapeId.from("smithy.api#String"))));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.Nested
                var v2 *string
                if v1 != nil {
                    v3 := v1.NestedField
                    v2 = v3
                }
                """));
    }

    @Test
    public void testKeysFunctionExpression() {
        var expr = "keys(objectMap)";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(listOf(ShapeUtil.STRING_SHAPE)));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectMap
                var v2 []string
                for k := range v1 {
                    v2 = append(v2, k)
                }
                """));
    }

    @Test
    public void testProjectionExpression() {
        var expr = "objectList[*].key";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(
                listOf(TEST_MODEL.expectShape(ShapeId.from("smithy.api#String")))));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                var v2 []string
                for _, v := range v1 {
                    v3 := v.Key
                    if v3 != nil {
                        v2 = append(v2, *v3)
                    }
                }
                """));
    }

    @Test
    public void testNopFlattenExpression() {
        var expr = "objectList[].key";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(
                listOf(TEST_MODEL.expectShape(ShapeId.from("smithy.api#String")))));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                var v2 []string
                for _, v := range v1 {
                    v3 := v.Key
                    if v3 != nil {
                        v2 = append(v2, *v3)
                    }
                }
                """));
    }

    @Test
    public void testActualFlattenExpression() {
        var expr = "objectList[].innerObjectList[].innerKey";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(
                listOf(TEST_MODEL.expectShape(ShapeId.from("smithy.api#String")))));
        assertThat(actual.ident(), Matchers.equalTo("v5"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                var v2 [][]types.InnerObject
                for _, v := range v1 {
                    v3 := v.InnerObjectList
                    v2 = append(v2, v3)
                }
                var v4 []types.InnerObject
                for _, v := range v2 {
                    v4 = append(v4, v...)
                }
                var v5 []string
                for _, v := range v4 {
                    v6 := v.InnerKey
                    if v6 != nil {
                        v5 = append(v5, *v6)
                    }
                }
                """));
    }

    @Test
    public void testLengthFunctionExpression() {
        var expr = "length(objectList)";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.INT_SHAPE));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                v2 := len(v1)
                """));
    }

    @Test
    public void testLengthFunctionStringPtr() {
        var expr = "length(simpleShape)";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.INT_SHAPE));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.SimpleShape
                var _v1 string
                if v1 != nil {
                    _v1 = *v1
                }
                v2 := len(_v1)
                """));
    }

    @Test
    public void testComparatorInt() {
        var expr = "length(objectList) > `99`";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.BOOL_SHAPE));
        assertThat(actual.ident(), Matchers.equalTo("v4"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                v2 := len(v1)
                v3 := 99 
                v4 := int64(v2) > int64(v3)
                """));
    }

    @Test
    public void testComparatorStringLHSNil() {
        var expr = "nested.nestedField == 'foo'";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.BOOL_SHAPE));
        assertThat(actual.ident(), Matchers.equalTo("v5"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.Nested
                var v2 *string
                if v1 != nil {
                    v3 := v1.NestedField
                    v2 = v3
                }
                v4 := "foo"
                var v5 bool

                if v2 != nil   {
                    v5 = string(*v2) == string(v4)
                }
                """));
    }

    @Test
    public void testComparatorStringRHSNil() {
        var expr = "'foo' == nested.nestedField";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.BOOL_SHAPE));
        assertThat(actual.ident(), Matchers.equalTo("v5"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := "foo"
                v2 := input.Nested
                var v3 *string
                if v2 != nil {
                    v4 := v2.NestedField
                    v3 = v4
                }
                var v5 bool

                if   v3 != nil {
                    v5 = string(v1) == string(*v3)
                }
                """));
    }

    @Test
    public void testComparatorStringBothNil() {
        var expr = "nested.nestedField == simpleShape";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.BOOL_SHAPE));
        assertThat(actual.ident(), Matchers.equalTo("v5"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.Nested
                var v2 *string
                if v1 != nil {
                    v3 := v1.NestedField
                    v2 = v3
                }
                v4 := input.SimpleShape
                var v5 bool

                if v2 != nil && v4 != nil {
                    v5 = string(*v2) == string(*v4)
                }else { v5 = v2 == nil && v4 == nil }
                """));
    }

    @Test
    public void testContainsFunctionExpression() {
        var expr = "contains(objectList[].key, 'foo')";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.BOOL_SHAPE));
        assertThat(actual.ident(), Matchers.equalTo("v5"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                var v2 []string
                for _, v := range v1 {
                    v3 := v.Key
                    if v3 != nil {
                        v2 = append(v2, *v3)
                    }
                }
                v4 := "foo"
                var v5 bool
                for _, v := range v2 {
                    if v == v4 {
                        v5 = true
                        break
                    }
                }
                """));
    }

    @Test
    public void testAndExpression() {
        var expr = "length(objectList) > `0` && length(objectList) <= `10`";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.BOOL_SHAPE));
        assertThat(actual.ident(), Matchers.equalTo("v9"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                v2 := len(v1)
                v3 := 0
                v4 := int64(v2) > int64(v3)
                v5 := input.ObjectList
                v6 := len(v5)
                v7 := 10
                v8 := int64(v6) <= int64(v7)
                v9 := v4 && v8
                """));
    }

    @Test
    public void testFilterExpression() {
        var expr = "objectList[?length(innerObjectList) > `0`]";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#ObjectList"))));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                var v2 []types.Object
                for _, v := range v1 {
                    v3 := v.InnerObjectList
                    v4 := len(v3)
                    v5 := 0
                    v6 := int64(v4) > int64(v5)
                    if v6 {
                        v2 = append(v2, v)
                    }
                }
                """));
    }

    @Test
    public void testNot() {
        var expr = "objectList[?!(length(innerObjectList) > `0`)]";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape(), Matchers.equalTo(TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#ObjectList"))));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                var v2 []types.Object
                for _, v := range v1 {
                    v3 := v.InnerObjectList
                    v4 := len(v3)
                    v5 := 0
                    v6 := int64(v4) > int64(v5)
                    v7 := !v6
                    if v7 {
                        v2 = append(v2, v)
                    }
                }
                """));
    }

    @Test
    public void testMultiSelect() {
        var expr = "[simpleShape, simpleShape2]";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape().toShapeId().toString(), Matchers.equalTo("smithy.go.synthetic#StringList"));
        assertThat(actual.ident(), Matchers.equalTo("v3"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.SimpleShape
                v2 := input.SimpleShape2
                v3 := []string{}
                if v1 != nil {
                    v3 = append(v3, *v1)
                }
                if v2 != nil {
                    v3 = append(v3, *v2)
                }
                """));
    }

    @Test
    public void testMultiSelectFlatten() {
        var expr = "objectList[*].[key][]";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape().toShapeId().toString(), Matchers.equalTo("smithy.go.synthetic#StringList"));
        assertThat(actual.ident(), Matchers.equalTo("v5"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.ObjectList
                var v2 [][]string
                for _, v := range v1 {
                    v3 := v.Key
                    v4 := []string{}
                    if v3 != nil {
                        v4 = append(v4, *v3)
                    }
                    if v4 != nil {
                        v2 = append(v2, v4)
                    }
                }
                var v5 []string
                for _, v := range v2 {
                    v5 = append(v5, v...)
                }
                """));
    }

    @Test
    public void testOrderComparatorNumberCoercesLeftNullable() {
        var expr = "nullableIntegerA > `9`";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape().toShapeId().toString(), Matchers.equalTo("smithy.api#PrimitiveBoolean"));
        assertThat(actual.ident(), Matchers.equalTo("v3"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.NullableIntegerA
                v2 := 9
                var v3 bool
                if (v1 == nil) {
                    v1 = new(int32)
                    *v1 = 0
                }

                if v1 != nil   {
                    v3 = int64(*v1) > int64(v2)
                }
                """));
    }

    @Test
    public void testOrderComparatorNumberCoercesBothNullable() {
        var expr = "nullableIntegerA > nullableIntegerB";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape().toShapeId().toString(), Matchers.equalTo("smithy.api#PrimitiveBoolean"));
        assertThat(actual.ident(), Matchers.equalTo("v3"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.NullableIntegerA
                v2 := input.NullableIntegerB
                var v3 bool
                if (v1 == nil) {
                    v1 = new(int32)
                    *v1 = 0
                }
                if (v2 == nil) {
                    v2 = new(int32)
                    *v2 = 0
                }
                if v1 != nil && v2 != nil {
                    v3 = int64(*v1) > int64(*v2)
                }
                """));
    }

    @Test
    public void testEqualBothNullable() {
        var expr = "nullableIntegerA == nullableIntegerB";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape().toShapeId().toString(), Matchers.equalTo("smithy.api#PrimitiveBoolean"));
        assertThat(actual.ident(), Matchers.equalTo("v3"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.NullableIntegerA
                v2 := input.NullableIntegerB
                var v3 bool

                if v1 != nil && v2 != nil {
                    v3 = int64(*v1) == int64(*v2)
                }else { v3 = v1 == nil && v2 == nil }
                """));
    }

    @Test
    public void testNotEqualBothNullable() {
        var expr = "nullableIntegerA != nullableIntegerB";

        var writer = testWriter();
        var generator = new GoJmespathExpressionGenerator(testContext(), writer);
        var actual = generator.generate(JmespathExpression.parse(expr), new GoJmespathExpressionGenerator.Variable(
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                "input"
        ));
        assertThat(actual.shape().toShapeId().toString(), Matchers.equalTo("smithy.api#PrimitiveBoolean"));
        assertThat(actual.ident(), Matchers.equalTo("v3"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input.NullableIntegerA
                v2 := input.NullableIntegerB
                var v3 bool

                if v1 != nil && v2 != nil {
                    v3 = int64(*v1) != int64(*v2)
                }else { v3 = (v1 == nil && v2 != nil) || (v1 != nil && v2 == nil) }
                """));
    }
}
