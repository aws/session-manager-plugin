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

package software.amazon.smithy.go.codegen.auth;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates a single method which binds auth scheme resolver parameters from operation input and client options.
 * The only value bound by default is the operation name. Generators must load additional bindings through
 * GoIntegration.
 */
public class AuthParametersResolverGenerator {
    public static final String FUNC_NAME = "bindAuthResolverParams";

    private final ProtocolGenerator.GenerationContext context;

    private final ArrayList<AuthParametersResolver> resolvers = new ArrayList<>();

    public AuthParametersResolverGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public Writable generate() {
        loadResolvers();

        return goTemplate("""
                func $name:L(ctx $context:T, operation string, input interface{}, options Options) ($params:P, error) {
                    params := &$params:T{
                        Operation: operation,
                    }

                    $bindings:W
                
                    return params, nil
                }
                """,
                MapUtils.of(
                        "name", FUNC_NAME,
                        "params", AuthParametersGenerator.STRUCT_SYMBOL,
                        "bindings", generateResolvers(),
                        "context", GoStdlibTypes.Context.Context
                ));
    }

    private Writable generateResolvers() {
        return (writer) -> {
            for (var resolver: resolvers) {
                    writer.write("""
                        if err := $T(ctx, params, input, options); err != nil {
                            return nil, err
                        }
                    """, resolver.resolver());
            }
        };
    }

    private void loadResolvers() {
        for (var integration: context.getIntegrations()) {
            var plugins = integration.getClientPlugins().stream().filter(it ->
                    it.matchesService(context.getModel(), context.getService())).toList();
            for (var plugin: plugins) {
                resolvers.addAll(plugin.getAuthParameterResolvers());
            }
        }
    }
}
