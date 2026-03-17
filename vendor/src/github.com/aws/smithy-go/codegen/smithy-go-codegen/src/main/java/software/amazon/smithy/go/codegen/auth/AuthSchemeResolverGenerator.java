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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.smithy.aws.traits.auth.UnsignedPayloadTrait;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.MapUtils;

/**
 * Implements modeled auth scheme resolver generation.
 */
public class AuthSchemeResolverGenerator {
    public static final String INTERFACE_NAME = "AuthSchemeResolver";
    public static final String DEFAULT_NAME = "defaultAuthSchemeResolver";

    private final ProtocolGenerator.GenerationContext context;
    private final ServiceIndex serviceIndex;
    private final Map<ShapeId, AuthSchemeDefinition> schemeDefinitions;

    public AuthSchemeResolverGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
        this.serviceIndex = ServiceIndex.of(context.getModel());
        this.schemeDefinitions = context.getIntegrations().stream()
                .flatMap(it -> it.getClientPlugins(context.getModel(), context.getService()).stream())
                .flatMap(it -> it.getAuthSchemeDefinitions().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // an operation has auth overrides if any of the following are true:
    // 1. its list of supported schemes differs from that of the service
    // 2. its auth optionality differs from that of the service (covered by checking [1] w/ NO_AUTH_AWARE)
    // 3. it has an unsigned payload
    private boolean hasAuthOverrides(OperationShape operation) {
        var serviceSchemes = serviceIndex
                .getEffectiveAuthSchemes(context.getService(), ServiceIndex.AuthSchemeMode.NO_AUTH_AWARE)
                .keySet();
        var operationSchemes = serviceIndex
                .getEffectiveAuthSchemes(context.getService(), operation, ServiceIndex.AuthSchemeMode.NO_AUTH_AWARE)
                .keySet();
        return !serviceSchemes.equals(operationSchemes) || operation.hasTrait(UnsignedPayloadTrait.class);
    }

    public Writable generate() {
       return goTemplate("""
               $W

               $W
               """, generateInterface(), generateDefault());
    }

    private Writable generateInterface() {
        return goTemplate("""
                $W
                type $L interface {
                    ResolveAuthSchemes($T, *$L) ([]$P, error)
                }
                """,
                generateDocs(),
                INTERFACE_NAME,
                GoStdlibTypes.Context.Context,
                AuthParametersGenerator.STRUCT_NAME,
                SmithyGoTypes.Auth.Option);
    }

    private Writable generateDocs() {
        return goDocTemplate("AuthSchemeResolver returns a set of possible authentication options for an "
                + "operation.");
    }

    private Writable generateDefault() {
        return goTemplate("""
                $W

                $W
                """,
                generateDefaultStruct(),
                generateDefaultResolve());
    }

    private Writable generateDefaultStruct() {
        return goTemplate("""
                type $1L struct{}

                var _ $2L = (*$1L)(nil)
                """, DEFAULT_NAME, INTERFACE_NAME);
    }

    private Writable generateDefaultResolve() {
        return goTemplate("""
                func (*$receiver:L) ResolveAuthSchemes(ctx $ctx:L, params *$params:L) ([]$options:P, error) {
                    if overrides, ok := operationAuthOptions[params.Operation]; ok {
                        return overrides(params), nil
                    }
                    return serviceAuthOptions(params), nil
                }

                $opAuthOptions:W

                $svcAuthOptions:W
                """, MapUtils.of(
                        "receiver", DEFAULT_NAME,
                        "ctx", GoStdlibTypes.Context.Context,
                        "params", AuthParametersGenerator.STRUCT_NAME,
                        "options", SmithyGoTypes.Auth.Option,
                        "opAuthOptions", generateOperationAuthOptions(),
                        "svcAuthOptions", generateServiceAuthOptions()));
    }

    private Writable generateOperationAuthOptions() {
        var options = new ChainWritable();
        TopDownIndex.of(context.getModel())
                .getContainedOperations(context.getService()).stream()
                .filter(this::hasAuthOverrides)
                .forEach(it -> {
                    options.add(generateOperationAuthOptionsEntry(it));
                });

        return goTemplate("""
                var operationAuthOptions = map[string]func(*$L) []$P{
                    $W
                }
                """,
                AuthParametersGenerator.STRUCT_NAME,
                SmithyGoTypes.Auth.Option,
                options.compose());
    }

    private Writable generateOperationAuthOptionsEntry(OperationShape operation) {
        var options = new ChainWritable();
        serviceIndex
                .getEffectiveAuthSchemes(context.getService(), operation, ServiceIndex.AuthSchemeMode.NO_AUTH_AWARE)
                .entrySet().stream()
                .filter(it -> schemeDefinitions.containsKey(it.getKey()))
                .forEach(it -> {
                    var definition = schemeDefinitions.get(it.getKey());
                    options.add(definition.generateOperationOption(context, operation));
                });

        return options.isEmpty()
                ? emptyGoTemplate()
                : goTemplate("""
                        $1S: func(params *$2L) []$3P {
                            return []$3P{
                                $4W
                            }
                        },""",
                        operation.getId().getName(),
                        AuthParametersGenerator.STRUCT_NAME,
                        SmithyGoTypes.Auth.Option,
                        options.compose());
    }

    private Writable generateServiceAuthOptions() {
        var options = new ChainWritable();
        serviceIndex
                .getEffectiveAuthSchemes(context.getService(), ServiceIndex.AuthSchemeMode.NO_AUTH_AWARE)
                .entrySet().stream()
                .filter(it -> schemeDefinitions.containsKey(it.getKey()))
                .forEach(it -> {
                    var definition = schemeDefinitions.get(it.getKey());
                    options.add(definition.generateServiceOption(context, context.getService()));
                });

        return goTemplate("""
                func serviceAuthOptions(params *$1L) []$2P {
                    return []$2P{
                        $3W
                    }
                }
                """,
                AuthParametersGenerator.STRUCT_NAME,
                SmithyGoTypes.Auth.Option,
                options.compose());
    }
}
