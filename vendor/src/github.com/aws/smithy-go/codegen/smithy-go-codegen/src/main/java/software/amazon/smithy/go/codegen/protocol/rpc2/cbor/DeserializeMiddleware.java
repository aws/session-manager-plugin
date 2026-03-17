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

package software.amazon.smithy.go.codegen.protocol.rpc2.cbor;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.protocol.rpc2.Rpc2ProtocolGenerator.SMITHY_PROTOCOL_NAME;
import static software.amazon.smithy.go.codegen.serde.cbor.CborDeserializerGenerator.getDeserializerName;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.protocol.rpc2.Rpc2DeserializeResponseMiddleware;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.utils.MapUtils;

final class DeserializeMiddleware extends Rpc2DeserializeResponseMiddleware {
    DeserializeMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    ) {
        super(generator, ctx, operation);
    }

    @Override
    protected String getProtocolName() {
        return SMITHY_PROTOCOL_NAME;
    }

    @Override
    public Writable deserializeSuccessResponse() {
        return goTemplate("""
                    payload, err := $readAll:T(resp.Body)
                    if err != nil {
                        return out, metadata, err
                    }

                    if len(payload) == 0 {
                        out.Result = &$output:T{}
                        return out, metadata, nil
                    }

                    cv, err := $decode:T(payload)
                    if err != nil {
                        return out, metadata, err
                    }

                    output, err := $deserialize:L(cv)
                    if err != nil {
                        return out, metadata, err
                    }

                    out.Result = output
                    """,
                MapUtils.of(
                        "readAll", GoStdlibTypes.Io.ReadAll,
                        "decode", SmithyGoTypes.Encoding.Cbor.Decode,
                        "deserialize", getDeserializerName(output),
                        "output", ctx.getSymbolProvider()
                                .toSymbol(ctx.getModel().expectShape(operation.getOutputShape()))
                ));
    }
}
