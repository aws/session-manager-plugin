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

package software.amazon.smithy.go.codegen.protocol;

import static software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator.createDeserializeStepMiddleware;
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_TRACING;
import static software.amazon.smithy.go.codegen.integration.ProtocolGenerator.getDeserializeMiddlewareName;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.integration.ProtocolUtils;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public abstract class DeserializeResponseMiddleware implements Writable {
    protected final ProtocolGenerator generator;
    protected final ProtocolGenerator.GenerationContext ctx;
    protected final OperationShape operation;

    protected final StructureShape output;

    public DeserializeResponseMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    ) {
        this.generator = generator;
        this.ctx = ctx;
        this.operation = operation;

        this.output = ctx.getModel().expectShape(operation.getOutputShape(), StructureShape.class);
    }

    @Override
    public void accept(GoWriter writer) {
        var middleware = createDeserializeStepMiddleware(
                getDeserializeMiddlewareName(operation.getId(), ctx.getService(), generator.getProtocolName()),
                ProtocolUtils.OPERATION_DESERIALIZER_MIDDLEWARE_ID
        );

        writer.write(middleware.asWritable(generateHandleDeserialize(), emptyGoTemplate()));
    }

    public abstract Writable generateDeserialize();

    private Writable generateHandleDeserialize() {
        return goTemplate("""
                out, metadata, err = next.HandleDeserialize(ctx, in)

                _, span := $startSpan:T(ctx, "OperationDeserializer")
                endTimer := startMetricTimer(ctx, "client.call.deserialization_duration")
                defer endTimer()
                defer span.End()

                if err != nil {
                    return out, metadata, err
                }

                resp, ok := out.RawResponse.($response:P)
                if !ok {
                    return out, metadata, $errorf:T("unexpected transport type %T", out.RawResponse)
                }

                $deserialize:W

                return out, metadata, nil
                """,
                MapUtils.of(
                        "startSpan", SMITHY_TRACING.func("StartSpan"),
                        "response", generator.getApplicationProtocol().getResponseType(),
                        "deserialize", generateDeserialize(),
                        "errorf", GoStdlibTypes.Fmt.Errorf
                ));
    }
}
