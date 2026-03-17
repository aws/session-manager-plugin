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
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.utils.StringUtils.capitalize;

import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoJmespathExpressionGenerator;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.jmespath.JmespathExpression;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.traits.ContextParamTrait;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.rulesengine.traits.OperationContextParamDefinition;
import software.amazon.smithy.rulesengine.traits.OperationContextParamsTrait;
import software.amazon.smithy.rulesengine.traits.StaticContextParamDefinition;
import software.amazon.smithy.rulesengine.traits.StaticContextParamsTrait;

/**
 * Generates operation-specific bindings (@operationContextParam, @contextParam, @staticContextParam) as a receiver
 * method on the operation's input structure.
 */
public class EndpointParameterOperationBindingsGenerator {
    private final GoCodegenContext ctx;
    private final OperationShape operation;
    private final StructureShape input;

    private final EndpointRuleSet rules;

    public EndpointParameterOperationBindingsGenerator(
            GoCodegenContext ctx,
            OperationShape operation,
            StructureShape input
    ) {
        this.ctx = ctx;
        this.operation = operation;
        this.input = input;

        this.rules = ctx.settings().getService(ctx.model())
                .expectTrait(EndpointRuleSetTrait.class)
                .getEndpointRuleSet();
    }

    private boolean hasBindings() {
        var hasContextBindings = input.getAllMembers().values().stream().anyMatch(it ->
                it.hasTrait(ContextParamTrait.class));
        return hasContextBindings
                || operation.hasTrait(StaticContextParamsTrait.class)
                || operation.hasTrait(OperationContextParamsTrait.class);
    }

    public Writable generate() {
        if (!hasBindings()) {
            return goTemplate("");
        }

        return goTemplate("""
                func (in $P) bindEndpointParams(p *EndpointParameters) {
                    $W
                    $W
                    $W
                }
                """,
                ctx.symbolProvider().toSymbol(input),
                generateOperationContextParamBindings(),
                generateContextParamBindings(),
                generateStaticContextParamBindings());
    }

    private Writable generateOperationContextParamBindings() {
        if (!operation.hasTrait(OperationContextParamsTrait.class)) {
            return emptyGoTemplate();
        }

        var params = operation.expectTrait(OperationContextParamsTrait.class);
        return ChainWritable.of(
                params.getParameters().entrySet().stream()
                        .map(it -> generateOpContextParamBinding(it.getKey(), it.getValue()))
                        .toList()
        ).compose(false);
    }

    private Writable generateOpContextParamBinding(String paramName, OperationContextParamDefinition def) {
        var expr = JmespathExpression.parse(def.getPath());

        return writer -> {
            var generator = new GoJmespathExpressionGenerator(ctx, writer);

            writer.write("func() {"); // contain the scope for each binding
            var result = generator.generate(expr, new GoJmespathExpressionGenerator.Variable(input, "in"));
            writer.write("p.$L = $L", capitalize(paramName), result.ident());
            writer.write("}()");
        };
    }

    private Writable generateContextParamBindings() {
        return writer -> {
            input.getAllMembers().values().forEach(it -> {
                if (!it.hasTrait(ContextParamTrait.class)) {
                    return;
                }

                var contextParam = it.expectTrait(ContextParamTrait.class);
                writer.write("p.$L = in.$L", contextParam.getName(), it.getMemberName());
            });
        };
    }

    private Writable generateStaticContextParamBindings() {
        if (!operation.hasTrait(StaticContextParamsTrait.class)) {
            return goTemplate("");
        }

        StaticContextParamsTrait params = operation.expectTrait(StaticContextParamsTrait.class);
        return writer -> {
            params.getParameters().forEach((k, v) -> {
                writer.write("p.$L = $W", capitalize(k), generateStaticLiteral(v));
            });
        };
    }

    private Writable generateStaticLiteral(StaticContextParamDefinition literal) {
        return writer -> {
            Node value = literal.getValue();
            if (value.isStringNode()) {
                writer.writeInline("$T($S)", SmithyGoTypes.Ptr.String, value.expectStringNode().getValue());
            } else if (value.isBooleanNode()) {
                writer.writeInline("$T($L)", SmithyGoTypes.Ptr.Bool, value.expectBooleanNode().getValue());
            } else {
                writer.writeInline("[]string{$W}", ChainWritable.of(
                        value.expectArrayNode().getElements().stream()
                                .map(it -> goTemplate("$S,", it.expectStringNode().getValue()))
                                .toList()
                ).compose(false));
            }
        };
    }
}
