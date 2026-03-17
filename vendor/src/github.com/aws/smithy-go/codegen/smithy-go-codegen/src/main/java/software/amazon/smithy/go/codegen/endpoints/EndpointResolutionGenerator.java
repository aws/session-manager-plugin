/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.rulesengine.traits.EndpointTestCase;
import software.amazon.smithy.rulesengine.traits.EndpointTestsTrait;

/**
 * Generates all components required for Smithy Ruleset Endpoint Resolution.
 * These components include a Provider, Parameters, and Tests.
 */
public class EndpointResolutionGenerator {

    public static final String FEATURE_NAME = "V2";
    public static final String PARAMETERS_TYPE_NAME = "EndpointParameters";
    public static final String RESOLVER_INTERFACE_NAME = "Endpoint" + "Resolver" + FEATURE_NAME;
    public static final String RESOLVER_IMPLEMENTATION_NAME = "resolver";
    public static final String RESOLVER_ENDPOINT_METHOD_NAME = "ResolveEndpoint";
    public static final String NEW_RESOLVER_FUNC_NAME = "NewDefault" + RESOLVER_INTERFACE_NAME;

    private final FnProvider fnProvider;
    private final Symbol endpointType;
    private final Symbol parametersType;
    private final Symbol resolverInterfaceType;
    private final Symbol resolverImplementationType;
    private final Symbol newResolverFn;


    public EndpointResolutionGenerator(FnProvider fnProvider) {
        this.fnProvider = fnProvider;
        this.endpointType = SymbolUtils.createValueSymbolBuilder("Endpoint",
                SmithyGoDependency.SMITHY_ENDPOINTS).build();

        this.parametersType = SymbolUtils.createValueSymbolBuilder(PARAMETERS_TYPE_NAME).build();
        this.resolverInterfaceType = SymbolUtils.createValueSymbolBuilder(RESOLVER_INTERFACE_NAME).build();
        this.resolverImplementationType = SymbolUtils.createValueSymbolBuilder(RESOLVER_IMPLEMENTATION_NAME).build();
        this.newResolverFn = SymbolUtils.createValueSymbolBuilder(NEW_RESOLVER_FUNC_NAME).build();
    }

    public void generate(ProtocolGenerator.GenerationContext context) {
        if (context.getWriter().isEmpty()) {
            throw new CodegenException("writer is required");
        }

        var writer = context.getWriter().get();

        var serviceShape = context.getService();

        var parametersGenerator = EndpointParametersGenerator.builder()
                .parametersType(parametersType)
                .build();

        var resolverGenerator = EndpointResolverGenerator.builder()
                .parametersType(parametersType)
                .resolverInterfaceType(resolverInterfaceType)
                .resolverImplementationType(resolverImplementationType)
                .newResolverFn(newResolverFn)
                .endpointType(endpointType)
                .resolveEndpointMethodName(RESOLVER_ENDPOINT_METHOD_NAME)
                .fnProvider(this.fnProvider)
                .build();

        var bindingGenerator = new EndpointParameterBindingsGenerator(context);
        var middlewareGenerator = new EndpointMiddlewareGenerator(context);

        Optional<EndpointRuleSet> ruleset = serviceShape.getTrait(EndpointRuleSetTrait.class)
                .map((trait) -> EndpointRuleSet.fromNode(trait.getRuleSet()));

        writer
                .write("$W\n", parametersGenerator.generate(ruleset))
                .write("$W\n", resolverGenerator.generate(ruleset))
                .write("$W\n", bindingGenerator.generate())
                .write("$W", middlewareGenerator.generate());
    }

    public void generateTests(ProtocolGenerator.GenerationContext context) {
        if (context.getWriter().isEmpty()) {
            throw new CodegenException("writer is required");
        }

        var writer = context.getWriter().get();
        var serviceShape = context.getService();
        Optional<EndpointRuleSet> ruleset = serviceShape.getTrait(EndpointRuleSetTrait.class)
                .map((trait) -> EndpointRuleSet.fromNode(trait.getRuleSet()));

        var testsGenerator = EndpointTestsGenerator.builder()
            .parametersType(parametersType)
            .newResolverFn(newResolverFn)
            .endpointType(endpointType)
            .resolveEndpointMethodName(RESOLVER_ENDPOINT_METHOD_NAME)
            .build();

        final List<EndpointTestCase> testCases = new ArrayList<>();
        var endpointTestTrait = serviceShape.getTrait(EndpointTestsTrait.class);
        endpointTestTrait.ifPresent(trait -> testCases.addAll(trait.getTestCases()));

        writer.write("$W", testsGenerator.generate(ruleset, testCases));
    }
}
