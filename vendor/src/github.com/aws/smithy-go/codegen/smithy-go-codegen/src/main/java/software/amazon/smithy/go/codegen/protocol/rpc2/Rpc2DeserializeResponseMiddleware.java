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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.protocol.ProtocolUtil.hasEventStream;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.protocol.DeserializeResponseMiddleware;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public abstract class Rpc2DeserializeResponseMiddleware extends DeserializeResponseMiddleware {
    protected Rpc2DeserializeResponseMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    ) {
        super(generator, ctx, operation);
    }

    protected abstract String getProtocolName();

    protected abstract Writable deserializeSuccessResponse();

    @Override
    public Writable generateDeserialize() {
        return goTemplate("""
                if resp.Header.Get("smithy-protocol") != $protocol:S {
                    return out, metadata, &$deserError:T{
                        Err: $errorf:T(
                            "unexpected smithy-protocol response header '%s' (HTTP status: %s)",
                            resp.Header.Get("smithy-protocol"),
                            resp.Status,
                        ),
                    }
                }

                if resp.StatusCode != 200 {
                    return out, metadata, $deserializeError:L(resp)
                }

                $handleResponse:W
                """,
                MapUtils.of(
                        "deserError", SmithyGoDependency.SMITHY.struct("DeserializationError"),
                        "protocol", getProtocolName(),
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "handleResponse", handleResponse(),
                        "deserializeError", ProtocolGenerator
                                .getOperationErrorDeserFunctionName(operation, ctx.getService(), "rpc2")
                ));
    }

    private Writable handleResponse() {
        if (output.members().isEmpty()) {
            return discardDeserialize();
        } else if (hasEventStream(ctx.getModel(), output)) {
            return deserializeEventStream();
        }
        return deserializeSuccessResponse();
    }

    private Writable discardDeserialize() {
        return goTemplate("""
                if _, err = $copy:T($discard:T, resp.Body); err != nil {
                    return out, metadata, $errorf:T("discard response body: %w", err)
                }

                out.Result = &$result:T{}
                """,
                MapUtils.of(
                        "copy", GoStdlibTypes.Io.Copy,
                        "discard", GoStdlibTypes.Io.IoUtil.Discard,
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "result", ctx.getSymbolProvider().toSymbol(output)
                ));
    }

    // Basically a no-op. Event stream deserializer middleware, implemented elsewhere, will handle the wire-up here,
    // including handling the initial-response message to deserialize any non-stream members to output.
    private Writable deserializeEventStream() {
        return goTemplate("out.Result = &$T{}", ctx.getSymbolProvider().toSymbol(output));
    }
}
