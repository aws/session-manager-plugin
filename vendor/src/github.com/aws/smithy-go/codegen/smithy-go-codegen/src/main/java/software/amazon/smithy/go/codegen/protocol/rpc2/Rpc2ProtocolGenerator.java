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

package software.amazon.smithy.go.codegen.protocol.rpc2;

import static software.amazon.smithy.go.codegen.ApplicationProtocol.createDefaultHttpApplicationProtocol;

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.protocol.DeserializeResponseMiddleware;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public abstract class Rpc2ProtocolGenerator implements ProtocolGenerator {
    public static final String SMITHY_PROTOCOL_NAME = "rpc-v2-cbor";
    public static final String CONTENT_TYPE = "application/cbor";

    public abstract Rpc2SerializeRequestMiddleware getSerializeRequestMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    );

    public abstract DeserializeResponseMiddleware getDeserializeResponseMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    );

    @Override
    public final ApplicationProtocol getApplicationProtocol() {
        return createDefaultHttpApplicationProtocol();
    }

    @Override
    public final void generateRequestSerializers(GenerationContext ctx) {
        TopDownIndex.of(ctx.getModel()).getContainedOperations(ctx.getService()).forEach(it -> {
            ctx.getWriter().get().write(getSerializeRequestMiddleware(this, ctx, it));
        });
    }

    @Override
    public final void generateResponseDeserializers(GenerationContext ctx) {
        TopDownIndex.of(ctx.getModel()).getContainedOperations(ctx.getService()).forEach(it -> {
            ctx.getWriter().get().write(getDeserializeResponseMiddleware(this, ctx, it));
        });
    }

    @Override
    public void generateEventStreamComponents(GenerationContext context) {
        throw new CodegenException("event stream codegen is not currently supported in smithy-go");
    }
}
