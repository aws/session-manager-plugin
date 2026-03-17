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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.aws.traits.protocols.AwsQueryCompatibleTrait;
import software.amazon.smithy.go.codegen.EventStreamGenerator;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.protocol.SerializeRequestMiddleware;
import software.amazon.smithy.go.codegen.trait.BackfilledInputOutputTrait;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public abstract class Rpc2SerializeRequestMiddleware extends SerializeRequestMiddleware {
    private final EventStreamIndex eventStreamIndex;

    protected Rpc2SerializeRequestMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    ) {
        super(generator, ctx, operation);

        this.eventStreamIndex = EventStreamIndex.of(ctx.getModel());
    }

    public abstract String getProtocolName();

    public abstract String getContentType();

    @Override
    public final Writable generateRouteRequest() {
        return goTemplate("""
                req.Method = $methodPost:T
                req.URL.Path = "/service/$service:L/operation/$operation:L"
                req.Header.Set("smithy-protocol", $protocol:S)

                $contentTypeHeader:W
                $acceptHeader:W
                $awsQueryCompatible:W
                """,
                MapUtils.of(
                        "methodPost", GoStdlibTypes.Net.Http.MethodPost,
                        "service", ctx.getService().getId().getName(),
                        "operation", operation.getId().getName(),
                        "protocol", getProtocolName(),
                        "contentTypeHeader", setContentTypeHeader(),
                        "acceptHeader", acceptHeader(),
                        "awsQueryCompatible", ctx.getService().hasTrait(AwsQueryCompatibleTrait.class)
                                ? setAwsQueryModeHeader()
                                : emptyGoTemplate()
                ));
    }

    private Writable setContentTypeHeader() {
        if (input.hasTrait(BackfilledInputOutputTrait.class)) {
            return emptyGoTemplate();
        }

        return goTemplate("""
                req.Header.Set("Content-Type", $S)
                """, isInputEventStream() ? EventStreamGenerator.AMZ_CONTENT_TYPE : getContentType());
    }

    private Writable acceptHeader() {
        return goTemplate("""
                req.Header.Set("Accept", $S)
                """, isOutputEventStream() ? EventStreamGenerator.AMZ_CONTENT_TYPE : getContentType());
    }

    private Writable setAwsQueryModeHeader() {
        return goTemplate("""
                req.Header.Set("X-Amzn-Query-Mode", "true")
                """);
    }

    private boolean isInputEventStream() {
        return eventStreamIndex.getInputInfo(operation).isPresent();
    }

    private boolean isOutputEventStream() {
        return eventStreamIndex.getOutputInfo(operation).isPresent();
    }
}
