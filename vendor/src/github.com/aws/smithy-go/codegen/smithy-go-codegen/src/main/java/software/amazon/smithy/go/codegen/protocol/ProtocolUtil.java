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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.model.traits.StreamingTrait.isEventStream;

import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class ProtocolUtil {
    public static final Writable GET_AWS_QUERY_ERROR_CODE = goTemplate("""
            func getAwsQueryErrorCode(resp $P) string {
                header := resp.Header.Get("x-amzn-query-error")
                if header == "" {
                    return ""
                }

                parts := $T(header, ";")
                if len(parts) != 2 {
                    return ""
                }

                return parts[0]
            }
            """,
            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.struct("Response"),
            SmithyGoDependency.STRINGS.func("Split")
    );

    private ProtocolUtil() {}

    public static boolean hasEventStream(Model model, Shape shape) {
        return shape.members().stream()
                .anyMatch(it -> isEventStream(model, it));
    }
}
