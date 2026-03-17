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

package software.amazon.smithy.go.codegen.server.protocol;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.knowledge.GoValidationIndex;
import software.amazon.smithy.go.codegen.server.RequestHandler;
import software.amazon.smithy.go.codegen.server.ServerProtocolGenerator;
import software.amazon.smithy.go.codegen.server.ServerValidationgenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Base class for HTTP protocol codegen.
 * HTTP protocols serve requests by generating a net/http.Handler implementation onto the base RequestHandler struct.
 */
@SmithyInternalApi
public abstract class HttpHandlerProtocolGenerator implements ServerProtocolGenerator {
    protected final GoCodegenContext ctx;

    private final GoValidationIndex validationIndex;

    protected HttpHandlerProtocolGenerator(GoCodegenContext ctx) {
        this.ctx = ctx;

        this.validationIndex = GoValidationIndex.of(ctx.model());
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    @Override
    public Writable generateHandleRequest() {
        return goTemplate("""
            var _ $httpHandler:T = (*$requestHandler:L)(nil)

            $serveHttp:W
            """,
                MapUtils.of(
                        "requestHandler", RequestHandler.NAME,
                        "httpHandler", GoStdlibTypes.Net.Http.Handler,
                        "serveHttp", generateServeHttp()
                ));
    }

    @Override
    public Writable generateOptions() {
        return goTemplate("""
                Interceptors HTTPInterceptors
                """);
    }

    @Override
    public Writable generateProtocolSource() {
        return goTemplate("""
                type InterceptBeforeDeserialize interface {
                    BeforeDeserialize($ctx:T, string, $r:P) error
                }

                type InterceptAfterDeserialize interface {
                    AfterDeserialize($ctx:T, string, interface{}) error
                }

                type InterceptBeforeSerialize interface {
                    BeforeSerialize($ctx:T, string, interface{}) error
                }

                type InterceptBeforeWriteResponse interface {
                    BeforeWriteResponse($ctx:T, string, $w:T) error
                }

                type HTTPInterceptors struct {
                    BeforeDeserialize   []InterceptBeforeDeserialize
                    AfterDeserialize    []InterceptAfterDeserialize
                    BeforeSerialize     []InterceptBeforeSerialize
                    BeforeWriteResponse []InterceptBeforeWriteResponse
                }
                """,
                MapUtils.of(
                        "ctx", GoStdlibTypes.Context.Context,
                        "w", GoStdlibTypes.Net.Http.ResponseWriter,
                        "r", GoStdlibTypes.Net.Http.Request
                ));
    }

    @Override
    public final Writable generateHandleOperation(OperationShape operation) {
        var service = ctx.settings().getService(ctx.model());
        var input = ctx.model().expectShape(operation.getInputShape());
        return goTemplate("""
                func (h *$requestHandler:L) $funcName:L(w $rw:T, r $r:P) {
                    id, err := $newUuid:T($rand:T).GetUUID()
                    if err != nil {
                        serializeError(w, err)
                        return
                    }

                    $beforeDeserialize:W
                    $deserialize:W
                    $afterDeserialize:W

                    $validate:W

                    out, err := h.service.$operation:L(r.Context(), in)
                    if err != nil {
                        serializeError(w, err)
                        return
                    }

                    $beforeSerialize:W
                    $beforeWriteResponse:W
                    $serialize:W
                }
                """,
                MapUtils.of(
                        "requestHandler", RequestHandler.NAME,
                        "funcName", getOperationHandlerName(operation),
                        "rw", GoStdlibTypes.Net.Http.ResponseWriter,
                        "r", GoStdlibTypes.Net.Http.Request
                ),
                MapUtils.of(
                        "newUuid", SmithyGoTypes.Rand.NewUUID,
                        "rand", GoStdlibTypes.Crypto.Rand.Reader,
                        "deserialize", generateDeserializeRequest(operation),
                        "validate", validationIndex.operationRequiresValidation(service, operation)
                                ? generateValidateInput(input)
                                : emptyGoTemplate(),
                        "operation", ctx.symbolProvider().toSymbol(operation).getName(),
                        "serialize", generateSerializeResponse(operation),
                        "beforeDeserialize", generateInvokeInterceptor("BeforeDeserialize", "r"),
                        "afterDeserialize", generateInvokeInterceptor("AfterDeserialize", "in"),
                        "beforeSerialize", generateInvokeInterceptor("BeforeSerialize", "out"),
                        "beforeWriteResponse", generateInvokeInterceptor("BeforeWriteResponse", "w")
                ));
    }

    /**
     * Generates the net/http.Handler's ServeHTTP implementation for this protocol.
     * Individual operation handlers are generated by generateServeHttpOperation. Implementors should fill in logic here
     * to route requests to those methods according to the protocol.
     */
    public abstract Writable generateServeHttp();

    /**
     * Generates a block of logic to convert the input http.Request `r` into the modeled input structure `in`.
     */
    public abstract Writable generateDeserializeRequest(OperationShape operation);

    /**
     * Generates a block of serialize the modeled output structure `out` to the http.ResponseWriter `w`.
     */
    public abstract Writable generateSerializeResponse(OperationShape operation);

    protected final String getOperationHandlerName(OperationShape operation) {
        return "serveHTTP" + operation.getId().getName();
    }

    private Writable generateValidateInput(Shape input) {
        return goTemplate("""
                if err := $L(in); err != nil {
                    serializeError(w, err)
                    return
                }
                """, ServerValidationgenerator.getShapeValidatorName(input));
    }

    private Writable generateInvokeInterceptor(String type, String args) {
        return goTemplate("""
                for _, i := range h.options.Interceptors.$1L {
                    if err := i.$1L(r.Context(), id, $2L); err != nil {
                        serializeError(w, err)
                        return
                    }
                }
                """, type, args);
    }
}
