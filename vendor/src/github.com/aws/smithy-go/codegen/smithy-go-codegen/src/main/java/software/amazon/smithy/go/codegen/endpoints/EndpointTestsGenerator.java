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

package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goBlockTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.joinWritables;
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.getExportedParameterName;
import static software.amazon.smithy.go.codegen.util.NodeUtil.writableStringSlice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeVisitor;
import software.amazon.smithy.model.node.NullNode;
import software.amazon.smithy.model.node.NumberNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;
import software.amazon.smithy.rulesengine.traits.EndpointTestCase;
import software.amazon.smithy.rulesengine.traits.ExpectedEndpoint;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyBuilder;

public final class EndpointTestsGenerator {
    private final Map<String, Object> commonCodegenArgs;

    private EndpointTestsGenerator(Builder builder) {
        var newResolverFn = SmithyBuilder.requiredState("newResolverFn", builder.newResolverFn);
        var parametersType = SmithyBuilder.requiredState("parametersType", builder.parametersType);
        var endpointType = SmithyBuilder.requiredState("endpointType", builder.endpointType);
        var resolveEndpointMethodName = SmithyBuilder.requiredState("resolveEndpointMethodName",
                builder.resolveEndpointMethodName);

        commonCodegenArgs = MapUtils.of(
                "parametersType", parametersType,
                "endpointType", endpointType,
                "newResolverFn", newResolverFn,
                "resolveEndpointMethodName", resolveEndpointMethodName,
                "fmtErrorf", SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build());
    }

    public Writable generate(Optional<EndpointRuleSet> ruleset, List<EndpointTestCase> testCases) {

        if (!ruleset.isPresent()) {
            return emptyGoTemplate();
        }
        var parameters = ruleset.get().getParameters();
        List<Writable> writables = new ArrayList<>();

        for (int i = 0; i < testCases.size(); i++) {
            var testCase = testCases.get(i);

            writables.add(goTemplate("""
                    $testCaseDocs:W
                    func TestEndpointCase$caseIdx:L(t *$testingT:T) {
                        $testBody:W
                    }
                    """,
                    commonCodegenArgs,
                    MapUtils.of(
                            "caseIdx", i,
                            "testingT", SymbolUtils.createValueSymbolBuilder("T", SmithyGoDependency.TESTING).build(),
                            "testCaseDocs", generateTestCaseDocs(testCase),
                            "testBody", generateTestCase(parameters, testCase))));
        }

        return joinWritables(writables, "\n\n");
    }

    private Writable generateTestCaseDocs(EndpointTestCase testCase) {
        if (testCase.getDocumentation().isPresent()) {
            return goDocTemplate(testCase.getDocumentation().get());
        }
        return emptyGoTemplate();
    }

    private Writable generateTestCase(Parameters parameters, EndpointTestCase testCase) {
        return goTemplate("""
                var params = $parametersType:T{
                    $parameterValues:W
                }

                resolver := $newResolverFn:T()
                result, err := resolver.$resolveEndpointMethodName:L($contextBG:T(), params)
                _, _ = result, err

                $expectErr:W

                $expectEndpoint:W
                """,
                commonCodegenArgs,
                MapUtils.of(
                        "contextBG",
                        SymbolUtils.createValueSymbolBuilder("Background", SmithyGoDependency.CONTEXT).build(),
                        "parameterValues", generateParameterValues(parameters, testCase),
                        "expectErr", generateExpectError(testCase.getExpect().getError()),
                        "expectEndpoint", generateExpectEndpoint(testCase.getExpect().getEndpoint())));
    }

    private Writable generateParameterValues(Parameters parameters, EndpointTestCase testCase) {
        List<Writable> writables = new ArrayList<>();
        // TODO filter keys based on actual modeled parameters
        Set<String> parameterNames = new TreeSet<>();

        parameters.forEach((p) -> {
            parameterNames.add(getExportedParameterName(p));
        });

        testCase.getParams().getMembers().forEach((key, value) -> {
            var exportedName = getExportedParameterName(key);
            if (parameterNames.contains(exportedName)) {
                writables.add((GoWriter w) -> {
                    w.write("$L: $W,", getExportedParameterName(key), generateParameterValue(value));
                });
            }
        });

        return joinWritables(writables, "\n");
    }

    private Writable generateParameterValue(Node value) {
        return switch (value.getType()) {
            case STRING -> goTemplate("$T($S)",
                    SmithyGoDependency.SMITHY_PTR.func("String"), value.expectStringNode().getValue());
            case BOOLEAN -> goTemplate("$T($L)",
                    SmithyGoDependency.SMITHY_PTR.func("Bool"), value.expectBooleanNode().getValue());
            // only array parameter type is STRING_ARRAY
            case ARRAY -> writableStringSlice(value.expectArrayNode());
            default -> throw new CodegenException("Unhandled member type: " + value.getType());
        };
    }

    Writable generateExpectError(Optional<String> expectErr) {
        if (expectErr.isEmpty()) {
            return goTemplate("""
                    if err != nil {
                        t.Fatalf("expect no error, got %v", err)
                    }
                    """);
        }

        return goTemplate("""
                if err == nil {
                    t.Fatalf("expect error, got none")
                }
                if e, a := $expect:S, err.Error(); !$stringsContains:T(a, e) {
                    t.Errorf("expect %v error in %v", e, a)
                }
                """,
                MapUtils.of(
                        "expect", expectErr.get(),
                        "stringsContains", SymbolUtils.createValueSymbolBuilder("Contains",
                                SmithyGoDependency.STRINGS).build()));
    }

    Writable generateExpectEndpoint(Optional<ExpectedEndpoint> expectEndpoint) {
        if (expectEndpoint.isEmpty()) {
            return emptyGoTemplate();
        }

        var endpoint = expectEndpoint.get();

        return goTemplate("""
                uri, _ := $urlParse:T($expectURL:S)

                expectEndpoint := $endpointType:T{
                    URI: *uri,
                    $headers:W
                    $properties:W
                }

                if e, a := expectEndpoint.URI, result.URI; e != a{
                    t.Errorf("expect %v URI, got %v", e, a)
                }

                $assertFields:W

                $assertProperties:W
                """,
                commonCodegenArgs,
                MapUtils.of(
                        "urlParse", SymbolUtils.createValueSymbolBuilder("Parse", SmithyGoDependency.NET_URL).build(),
                        "expectURL", endpoint.getUrl(),
                        "headers", generateHeaders(endpoint.getHeaders()),
                        "properties", generateProperties(endpoint.getProperties()),
                        "assertFields", generateAssertFields(),
                        "assertProperties", generateAssertProperties()));
    }

    Writable generateHeaders(Map<String, List<String>> headers) {
        Map<String, Object> commonArgs = MapUtils.of(
            "memberName", "Headers",
            "headerType", SymbolUtils.createPointableSymbolBuilder("Header",
                    SmithyGoDependency.NET_HTTP).build(),
            "newHeaders", SymbolUtils.createValueSymbolBuilder("Header{}",
                    SmithyGoDependency.NET_HTTP).build());

        if (headers.isEmpty()) {
            return goTemplate("Headers: $newHeaders:T,", commonArgs);
        }

        return goBlockTemplate("""
                $memberName:L: func() $headerType:T {
                    headers := $newHeaders:T
                """, """
                    return headers
                }(),
                """, commonArgs,
                (w) -> {
                    headers.forEach((key, values) -> {
                        List<Writable> valueWritables = new ArrayList<>();
                        values.forEach((value) -> {
                            valueWritables.add((GoWriter ww) -> ww.write("$S", value));
                        });

                        w.writeGoTemplate("headers.Set($key:S, $values:W)",
                                commonArgs, MapUtils.of(
                                        "key", key,
                                        "values", joinWritables(valueWritables, ", ")));
                    });
                });
    }

    Writable generateAssertFields() {
        return goTemplate("""
                if !$T(expectEndpoint.Headers, result.Headers) {
                    t.Errorf("expect headers to match\\n%v != %v", expectEndpoint.Headers, result.Headers)
                }
                """, SmithyGoDependency.REFLECT.valueSymbol("DeepEqual"));
    }

    Writable generateProperties(Map<String, Node> properties) {
        Map<String, Object> commonArgs = MapUtils.of(
                "propertiesType",
                SymbolUtils.createValueSymbolBuilder("Properties", SmithyGoDependency.SMITHY).build());

        if (properties.isEmpty()) {
            return goTemplate("Properties: $propertiesType:T{},", commonArgs);
        }

        var expressionGenerator = new ExpressionGenerator(Scope.empty(), name -> null);

        return goBlockTemplate("""
                Properties: func() $propertiesType:T {
                    var out $propertiesType:T
                """, """
                    return out
                }(),
                """, commonArgs,
                (w) -> {
                    properties.forEach((key, value) -> {
                        if (key.equals("authSchemes")) {
                            w.write("$W", new AuthSchemePropertyGenerator(expressionGenerator)
                                    .generate(Expression.fromNode(value)));
                        } else {
                            w.writeGoTemplate("out.Set($key:S, $value:W)",
                                    commonArgs, MapUtils.of(
                                            "key", key,
                                            "value", generateNodeValue(value)));
                        }
                    });
                });
    }

    Writable generateNodeValue(Node value) {
        return value.accept(new NodeVisitor<>() {
            @Override
            public Writable arrayNode(ArrayNode arrayNode) {
                List<Writable> elements = new ArrayList<>();
                arrayNode.getElements().forEach((e) -> {
                    elements.add((GoWriter w) -> w.write("$W,", e.accept(this)));
                });

                return (GoWriter w) -> {
                    w.write("""
                            []interface{}{
                                $W
                            }
                            """, joinWritables(elements, "\n"));
                };
            }

            @Override
            public Writable booleanNode(BooleanNode booleanNode) {
                return (GoWriter w) -> w.write("$L", booleanNode.getValue());
            }

            @Override
            public Writable nullNode(NullNode nullNode) {
                return (GoWriter w) -> w.write("nil");
            }

            @Override
            public Writable numberNode(NumberNode numberNode) {
                return (GoWriter w) -> w.write("$L", numberNode.toString());
            }

            @Override
            public Writable objectNode(ObjectNode objectNode) {
                List<Writable> members = new ArrayList<>();
                objectNode.getMembers().forEach((k, v) -> {
                    members.add((GoWriter w) -> w.write("$S: $W,", k.getValue(), v.accept(this)));
                });

                return (GoWriter w) -> {
                    w.write("""
                            map[string]interface{}{
                                $W
                            }
                            """, joinWritables(members, "\n"));
                };
            }

            @Override
            public Writable stringNode(StringNode stringNode) {
                return (GoWriter w) -> w.write("$S", stringNode.getValue());
            }
        });
    }

    Writable generateAssertProperties() {
        return goTemplate("""
                if !$reflectDeepEqual:T(expectEndpoint.Properties, result.Properties) {
                    t.Errorf("expect properties to match\\n%v != %v", expectEndpoint.Properties, result.Properties)
                }
                """,
                MapUtils.of(
                        "reflectDeepEqual", SmithyGoDependency.REFLECT.valueSymbol("DeepEqual"),
                        "propertiesType", SymbolUtils.createValueSymbolBuilder("Properties",
                                SmithyGoDependency.SMITHY).build()));

    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements SmithyBuilder<EndpointTestsGenerator> {
        private Symbol newResolverFn;
        private Symbol parametersType;
        private Symbol endpointType;
        private String resolveEndpointMethodName;

        private Builder() {
        }

        public Builder endpointType(Symbol endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        public Builder newResolverFn(Symbol newResolverFn) {
            this.newResolverFn = newResolverFn;
            return this;
        }

        public Builder parametersType(Symbol parametersType) {
            this.parametersType = parametersType;
            return this;
        }

        public Builder resolveEndpointMethodName(String resolveEndpointMethodName) {
            this.resolveEndpointMethodName = resolveEndpointMethodName;
            return this;
        }

        @Override
        public EndpointTestsGenerator build() {
            return new EndpointTestsGenerator(this);
        }
    }
}
