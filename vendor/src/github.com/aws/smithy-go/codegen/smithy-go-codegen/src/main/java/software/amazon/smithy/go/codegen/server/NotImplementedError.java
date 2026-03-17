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

package software.amazon.smithy.go.codegen.server;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Generates the NotImplemented error sentinel to be returned when a service doesn't support a specific action.
 */
@SmithyInternalApi
public final class NotImplementedError implements Writable {
    public static final String NAME = "NotImplemented";

    @Override
    public void accept(GoWriter writer) {
        writer.write(generateStruct());
    }

    private Writable generateStruct() {
        return goTemplate("""
                type $struct:L struct {
                    Operation string
                }

                var _ error = (*$struct:L)(nil)

                func (err *$struct:L) Error() string {
                    return $sprintf:T("%s is not implemented", err.Operation)
                }
                """,
                MapUtils.of(
                        "struct", NAME,
                        "sprintf", GoStdlibTypes.Fmt.Sprintf
                ));
    }
}
