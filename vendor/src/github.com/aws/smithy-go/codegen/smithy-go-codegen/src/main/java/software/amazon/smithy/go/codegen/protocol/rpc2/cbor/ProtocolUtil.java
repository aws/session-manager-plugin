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

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.utils.MapUtils;

final class ProtocolUtil {
    public static final Writable GET_PROTOCOL_ERROR_INFO = goTemplate("""
            func getProtocolErrorInfo(payload []byte) (typ, msg string, v $cborValue:T, err error) {
                v, err = $cborDecode:T(payload)
                if err != nil {
                    return "", "", nil, $fmtErrorf:T("decode: %w", err)
                }

                mv, ok := v.($cborMap:T)
                if !ok {
                    return "", "", nil, $fmtErrorf:T("unexpected payload type %T", v)
                }

                if ctyp, ok := mv["__type"]; ok {
                    if ttyp, ok := ctyp.($cborString:T); ok {
                        typ = string(ttyp)
                    }
                }

                if cmsg, ok := mv["message"]; ok {
                    if tmsg, ok := cmsg.($cborString:T); ok {
                        msg = string(tmsg)
                    }
                }

                return typ, msg, mv, nil
            }
            """,
            MapUtils.of(
                    "fmtErrorf", GoStdlibTypes.Fmt.Errorf,
                    "cborDecode", SmithyGoTypes.Encoding.Cbor.Decode,
                    "cborValue", SmithyGoTypes.Encoding.Cbor.Value,
                    "cborMap", SmithyGoTypes.Encoding.Cbor.Map,
                    "cborString", SmithyGoTypes.Encoding.Cbor.String
            ));

    private ProtocolUtil() {}
}
