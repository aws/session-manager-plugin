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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.protocol.rpc2.Rpc2ProtocolGenerator.CONTENT_TYPE;
import static software.amazon.smithy.go.codegen.protocol.rpc2.Rpc2ProtocolGenerator.SMITHY_PROTOCOL_NAME;
import static software.amazon.smithy.go.codegen.serde.cbor.CborSerializerGenerator.getSerializerName;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.protocol.rpc2.Rpc2SerializeRequestMiddleware;
import software.amazon.smithy.go.codegen.trait.BackfilledInputOutputTrait;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.utils.MapUtils;

final class SerializeMiddleware extends Rpc2SerializeRequestMiddleware {
    SerializeMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    ) {
        super(generator, ctx, operation);
    }

    @Override
    public String getProtocolName() {
        return SMITHY_PROTOCOL_NAME;
    }

    @Override
    public String getContentType() {
        return CONTENT_TYPE;
    }

    @Override
    public Writable generateSerialize() {
        if (input.hasTrait(BackfilledInputOutputTrait.class)) {
            return emptyGoTemplate();
        }

        return goTemplate("""
                cv, err := $serialize:L(input)
                if err != nil {
                    return out, metadata, &$error:T{Err: err}
                }

                payload := $reader:T($encode:T(cv))
                if req, err = req.SetStream(payload); err != nil {
                    return out, metadata, &$error:T{Err: err}
                }

                in.Request = req
                """,
                MapUtils.of(
                        "serialize", getSerializerName(input),
                        "encode", SmithyGoTypes.Encoding.Cbor.Encode,
                        "reader", GoStdlibTypes.Bytes.NewReader,
                        "error", SmithyGoTypes.Smithy.SerializationError
                ));
    }
}
