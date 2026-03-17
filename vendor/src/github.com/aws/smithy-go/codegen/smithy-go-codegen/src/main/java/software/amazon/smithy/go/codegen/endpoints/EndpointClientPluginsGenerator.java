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

import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.parameterAsSymbol;

import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.traits.ClientContextParamsTrait;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.StringUtils;


/**
 * Class responsible for registering client config fields that
 * are modeled in endpoint rulesets.
 */
public class EndpointClientPluginsGenerator implements GoIntegration {

    private final List<RuntimeClientPlugin> runtimeClientPlugins = new ArrayList<>();

    private static String getExportedParameterName(Parameter parameter) {
        return StringUtils.capitalize(parameter.getName().getName().getValue());
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        runtimeClientPlugins.add(RuntimeClientPlugin.builder()
        .configFields(ListUtils.of(
            ConfigField.builder()
                    .name("BaseEndpoint")
                    .type(SymbolUtils.createPointableSymbolBuilder("string")
                        .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build())
                    .documentation(
                        """
                        This endpoint will be given as input to an EndpointResolverV2.
                        It is used for providing a custom base endpoint that is subject
                        to modifications by the processing EndpointResolverV2.
                        """
                    )
                    .build()
        ))
        .build());
        return runtimeClientPlugins;
    }

    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        var service = settings.getService(model);
        if (!service.hasTrait(EndpointRuleSetTrait.class) || !service.hasTrait(ClientContextParamsTrait.class)) {
            return;
        }

        var ruleset = EndpointRuleSet.fromNode(service.expectTrait(EndpointRuleSetTrait.class).getRuleSet());
        var ccParams = service.expectTrait(ClientContextParamsTrait.class).getParameters();
        var parameters = ruleset.getParameters();
        parameters.forEach(param -> {
            var ccParam = ccParams.get(param.getName().getName().getValue());
            if (ccParam == null || param.getBuiltIn().isPresent()) {
                return;
            }

            runtimeClientPlugins.add(
                    RuntimeClientPlugin.builder()
                            .addConfigField(
                                    ConfigField.builder()
                                            .name(getExportedParameterName(param))
                                            .type(parameterAsSymbol(param))
                                            .documentation(ccParam.getDocumentation().orElse(""))
                                            .build()
                            )
                            .build()
            );
        });
    }
}

