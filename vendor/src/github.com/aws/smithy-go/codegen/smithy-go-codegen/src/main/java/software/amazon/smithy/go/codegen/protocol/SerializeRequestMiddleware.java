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

import static software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator.createSerializeStepMiddleware;
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_TRACING;

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
public abstract class SerializeRequestMiddleware implements Writable {
    protected final ProtocolGenerator generator;
    protected final ProtocolGenerator.GenerationContext ctx;
    protected final OperationShape operation;

    protected final StructureShape input;
    protected final StructureShape output;

    public SerializeRequestMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    ) {
        this.generator = generator;
        this.ctx = ctx;
        this.operation = operation;

        this.input = ctx.getModel().expectShape(operation.getInputShape(), StructureShape.class);
        this.output = ctx.getModel().expectShape(operation.getOutputShape(), StructureShape.class);
    }

    @Override
    public void accept(GoWriter writer) {
        var name = ProtocolGenerator.getSerializeMiddlewareName(operation.getId(), ctx.getService(),
                generator.getProtocolName());
        var middleware = createSerializeStepMiddleware(name, ProtocolUtils.OPERATION_SERIALIZER_MIDDLEWARE_ID);
        writer.write(middleware.asWritable(generateHandleSerialize(), emptyGoTemplate()));
    }

    public abstract Writable generateRouteRequest();

    public abstract Writable generateSerialize();

    private Writable generateHandleSerialize() {
        return goTemplate("""
                _, span := $startSpan:T(ctx, "OperationSerializer")
                endTimer := startMetricTimer(ctx, "client.call.serialization_duration")
                defer endTimer()
                defer span.End()
                input, ok := in.Parameters.($input:P)
                if !ok {
                    return out, metadata, $errorf:T("unexpected input type %T", in.Parameters)
                }
                _ = input

                req, ok := in.Request.($request:P)
                if !ok {
                    return out, metadata, $errorf:T("unexpected transport type %T", in.Request)
                }

                $route:W

                $serialize:W

                endTimer()
                span.End()

                return next.HandleSerialize(ctx, in)
                """,
                MapUtils.of(
                        "startSpan", SMITHY_TRACING.func("StartSpan"),
                        "input", ctx.getSymbolProvider().toSymbol(input),
                        "request", generator.getApplicationProtocol().getRequestType(),
                        "route", generateRouteRequest(),
                        "serialize", generateSerialize(),
                        "errorf", GoStdlibTypes.Fmt.Errorf
                ));
    }
}
