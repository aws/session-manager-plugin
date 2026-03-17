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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.traits.ClientContextParamsTrait;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates the main endpoint parameter binding function. Operation-specific bindings are generated elsewhere
 * conditionally through EndpointParameterOperationBindingsGenerator.
 */
public class EndpointParameterBindingsGenerator {
    private final ProtocolGenerator.GenerationContext context;

    private final Map<String, Writable> builtinBindings;

    public EndpointParameterBindingsGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
        this.builtinBindings = context.getIntegrations().stream()
                .flatMap(it -> it.getClientPlugins(context.getModel(), context.getService()).stream())
                .flatMap(it -> it.getEndpointBuiltinBindings().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Writable generate() {
        if (!context.getService().hasTrait(EndpointRuleSetTrait.class)) {
            return emptyGoTemplate();
        }

        return goTemplate("""
                type endpointParamsBinder interface {
                    bindEndpointParams(*EndpointParameters)
                }

                func bindEndpointParams(ctx $context:T, input interface{}, options Options) (*EndpointParameters, error) {
                    params := &EndpointParameters{}
                
                    $builtinBindings:W

                    $clientContextBindings:W

                    if b, ok := input.(endpointParamsBinder); ok {
                        b.bindEndpointParams(params)
                    }

                    return params, nil
                }
                """,
                MapUtils.of(
                        "context", GoStdlibTypes.Context.Context,
                        "builtinBindings", generateBuiltinBindings(),
                        "clientContextBindings", generateClientContextBindings()
                ));
    }

    private Writable generateBuiltinBindings() {
        var bindings = new HashMap<String, Writable>();
        for (var integration: context.getIntegrations()) {
            var plugins = integration.getClientPlugins(context.getModel(), context.getService());
            for (var plugin: plugins) {
                bindings.putAll(plugin.getEndpointBuiltinBindings());
            }
        }

        var params = new ArrayList<Parameter>();
        context.getEndpointRules().getParameters().forEach(params::add);
        var boundBuiltins = params.stream()
                .filter(it -> it.isBuiltIn() && bindings.containsKey(it.getBuiltIn().get()))
                .toList();
        return writer -> {
            for (var param: boundBuiltins) {
                String paramName =  EndpointParametersGenerator.getExportedParameterName(param);
                if (paramName.equals("Region")) {
                    writer.write("""
                                    region, err := $W
                                    if err != nil {
                                        return nil, err
                                    }
                                    params.Region = region
                                    """,
                            builtinBindings.get(param.getBuiltIn().get()));
                } else {
                    writer.write(
                            "params.$L = $W",
                            paramName,
                            builtinBindings.get(param.getBuiltIn().get()));
                }
            }
        };
    }

    private Writable generateClientContextBindings() {
        if (!context.getService().hasTrait(ClientContextParamsTrait.class)) {
            return goTemplate("");
        }

        var allParams = new ArrayList<Parameter>();
        context.getEndpointRules().getParameters().forEach(allParams::add);
        var contextParams = context.getService().expectTrait(ClientContextParamsTrait.class).getParameters();
        var params = allParams.stream()
                .filter(it -> contextParams.containsKey(it.getName().getName().getValue()) && !it.isBuiltIn())
                .toList();
        return writer -> {
            params.forEach(it -> {
                writer.write("params.$1L = options.$1L",
                        EndpointParametersGenerator.getExportedParameterName(it));
            });
        };
    }
}
