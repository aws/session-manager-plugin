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

import java.util.List;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.SmithyGoDependency;

// This DOES NOT add retry interceptors, because pure Smithy clients don't have a retry loop right now.
public class OperationInterceptors implements GoIntegration {
    @Override
    public byte getOrder() {
        return 127;
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return List.of(
                RuntimeClientPlugin.builder()
                        .registerMiddleware(
                                MiddlewareRegistrar.builder()
                                        .resolvedFunction("addInterceptors")
                                        .useClientOptions()
                                        .build()
                        )
                        .build()
        );
    }

    @Override
    public void writeAdditionalFiles(GoCodegenContext ctx) {
        ctx.writerDelegator().useFileWriter("api_client.go", ctx.settings().getModuleName(), writer -> {
            writer.addUseImports(SmithyGoDependency.ERRORS);
            writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            writer.write("""
                    func addInterceptors(stack *middleware.Stack, opts Options) error {
                        // middlewares are expensive, don't add all of these interceptor ones unless the caller
                        // actually has at least one interceptor configured
                        //
                        // at the moment it's all-or-nothing because some of the middlewares here are responsible for
                        // setting fields in the interceptor context for future ones
                        if len(opts.Interceptors.BeforeExecution) == 0 &&
                            len(opts.Interceptors.BeforeSerialization) == 0 && len(opts.Interceptors.AfterSerialization) == 0 &&
                            len(opts.Interceptors.BeforeRetryLoop) == 0 &&
                            len(opts.Interceptors.BeforeAttempt) == 0 &&
                            len(opts.Interceptors.BeforeSigning) == 0 && len(opts.Interceptors.AfterSigning) == 0 &&
                            len(opts.Interceptors.BeforeTransmit) == 0 && len(opts.Interceptors.AfterTransmit) == 0 &&
                            len(opts.Interceptors.BeforeDeserialization) == 0 && len(opts.Interceptors.AfterDeserialization) == 0 &&
                            len(opts.Interceptors.AfterAttempt) == 0 && len(opts.Interceptors.AfterExecution) == 0 {
                            return nil
                        }

                        return errors.Join(
                            stack.Initialize.Add(&smithyhttp.InterceptExecution{
                                BeforeExecution: opts.Interceptors.BeforeExecution,
                                AfterExecution:  opts.Interceptors.AfterExecution,
                            }, middleware.Before),
                            stack.Serialize.Insert(&smithyhttp.InterceptBeforeSerialization{
                                Interceptors: opts.Interceptors.BeforeSerialization,
                            }, "OperationSerializer", middleware.Before),
                            stack.Serialize.Insert(&smithyhttp.InterceptAfterSerialization{
                                Interceptors: opts.Interceptors.AfterSerialization,
                            }, "OperationSerializer", middleware.After),
                            stack.Finalize.Insert(&smithyhttp.InterceptBeforeSigning{
                                Interceptors: opts.Interceptors.BeforeSigning,
                            }, "Signing", middleware.Before),
                            stack.Finalize.Insert(&smithyhttp.InterceptAfterSigning{
                                Interceptors: opts.Interceptors.AfterSigning,
                            }, "Signing", middleware.After),
                            stack.Deserialize.Add(&smithyhttp.InterceptTransmit{
                                BeforeTransmit: opts.Interceptors.BeforeTransmit,
                                AfterTransmit:  opts.Interceptors.AfterTransmit,
                            }, middleware.After),
                            stack.Deserialize.Insert(&smithyhttp.InterceptBeforeDeserialization{
                                Interceptors: opts.Interceptors.BeforeDeserialization,
                            }, "OperationDeserializer", middleware.After), // (deserialize stack is called in reverse)
                            stack.Deserialize.Insert(&smithyhttp.InterceptAfterDeserialization{
                                Interceptors: opts.Interceptors.AfterDeserialization,
                            }, "OperationDeserializer", middleware.Before),
                        )
                    }
                    """);
        });
    }
}
