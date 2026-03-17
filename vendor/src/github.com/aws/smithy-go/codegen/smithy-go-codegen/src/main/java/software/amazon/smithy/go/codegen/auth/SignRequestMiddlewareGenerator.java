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

package software.amazon.smithy.go.codegen.auth;

import static software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator.createFinalizeStepMiddleware;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.endpoints.EndpointMiddlewareGenerator;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.utils.MapUtils;

public class SignRequestMiddlewareGenerator {
    public static final String MIDDLEWARE_NAME = "signRequestMiddleware";
    public static final String MIDDLEWARE_ID = "Signing";

    private final ProtocolGenerator.GenerationContext context;

    public SignRequestMiddlewareGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public static Writable generateAddToProtocolFinalizers() {
        return goTemplate("""
                if err := stack.Finalize.Insert(&$L{options: options}, $S, $T); err != nil {
                    return $T("add $L: %w", err)
                }
                """,
                MIDDLEWARE_NAME,
                EndpointMiddlewareGenerator.MIDDLEWARE_ID,
                SmithyGoTypes.Middleware.After,
                GoStdlibTypes.Fmt.Errorf,
                MIDDLEWARE_ID);
    }

    public Writable generate() {
        return createFinalizeStepMiddleware(MIDDLEWARE_NAME, MiddlewareIdentifier.string(MIDDLEWARE_ID))
                .asWritable(generateBody(), generateFields());
    }

    private Writable generateFields() {
        return goTemplate("""
                options Options
                """);
    }

    private Writable generateBody() {
        return goTemplate("""
                _, span := $startSpan:T(ctx, "SignRequest")
                defer span.End()

                req, ok := in.Request.($request:P)
                if !ok {
                    return out, metadata, $fmt.Errorf:T("unexpected transport type %T", in.Request)
                }

                rscheme := getResolvedAuthScheme(ctx)
                if rscheme == nil {
                    return out, metadata, $fmt.Errorf:T("no resolved auth scheme")
                }

                identity := getIdentity(ctx)
                if identity == nil {
                    return out, metadata, $fmt.Errorf:T("no identity")
                }

                signer := rscheme.Scheme.Signer()
                if signer == nil {
                    return out, metadata, $fmt.Errorf:T("no signer")
                }

                _, err = timeOperationMetric(ctx, "client.call.signing_duration", func() (any, error) {
                    return nil, signer.SignRequest(ctx, req, identity, rscheme.SignerProperties)
                }, func(o $recordMetricOptions:P) {
                    o.Properties.Set("auth.scheme_id", rscheme.Scheme.SchemeID())
                })
                if err != nil {
                    return out, metadata, $fmt.Errorf:T("sign request: %w", err)
                }

                span.End()
                return next.HandleFinalize(ctx, in)
                """,
                MapUtils.of(
                        // FUTURE(#458) protocol generator should specify the transport type
                        "request", SmithyGoTypes.Transport.Http.Request,
                        "startSpan", SmithyGoDependency.SMITHY_TRACING.func("StartSpan"),
                        "recordMetricOptions", SmithyGoDependency.SMITHY_METRICS.struct("RecordMetricOptions")
                ));
    }
}
