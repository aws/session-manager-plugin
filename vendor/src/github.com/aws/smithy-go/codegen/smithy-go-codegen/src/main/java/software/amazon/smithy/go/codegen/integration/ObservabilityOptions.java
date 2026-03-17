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

package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;

import java.util.List;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.SmithyGoDependency;

/**
 * Adds observability providers to client options.
 */
public class ObservabilityOptions implements GoIntegration {
    private static final ConfigField TRACER_PROVIDER = ConfigField.builder()
            .name("TracerProvider")
            .type(SmithyGoDependency.SMITHY_TRACING.interfaceSymbol("TracerProvider"))
            .documentation("The client tracer provider.")
            .build();

    private static final ConfigField METER_PROVIDER = ConfigField.builder()
            .name("MeterProvider")
            .type(SmithyGoDependency.SMITHY_METRICS.interfaceSymbol("MeterProvider"))
            .documentation("The client meter provider.")
            .build();

    private static final ConfigFieldResolver RESOLVE_TRACER_PROVIDER = ConfigFieldResolver.builder()
            .resolver(buildPackageSymbol("resolveTracerProvider"))
            .location(ConfigFieldResolver.Location.CLIENT)
            .target(ConfigFieldResolver.Target.INITIALIZATION)
            .build();

    private static final ConfigFieldResolver RESOLVE_METER_PROVIDER = ConfigFieldResolver.builder()
            .resolver(buildPackageSymbol("resolveMeterProvider"))
            .location(ConfigFieldResolver.Location.CLIENT)
            .target(ConfigFieldResolver.Target.INITIALIZATION)
            .build();

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return List.of(
                RuntimeClientPlugin.builder()
                        .addConfigField(TRACER_PROVIDER)
                        .addConfigField(METER_PROVIDER)
                        .addConfigFieldResolver(RESOLVE_TRACER_PROVIDER)
                        .addConfigFieldResolver(RESOLVE_METER_PROVIDER)
                        .build()
        );
    }

    @Override
    public void writeAdditionalFiles(GoCodegenContext ctx) {
        ctx.writerDelegator().useFileWriter("api_client.go", ctx.settings().getModuleName(), goTemplate("""
                func resolveTracerProvider(options *Options) {
                    if options.TracerProvider == nil {
                        options.TracerProvider = &$T{}
                    }
                }

                func resolveMeterProvider(options *Options) {
                    if options.MeterProvider == nil {
                        options.MeterProvider = $T{}
                    }
                }
                """,
                SmithyGoDependency.SMITHY_TRACING.struct("NopTracerProvider"),
                SmithyGoDependency.SMITHY_METRICS.struct("NopMeterProvider")
        ));
    }
}
