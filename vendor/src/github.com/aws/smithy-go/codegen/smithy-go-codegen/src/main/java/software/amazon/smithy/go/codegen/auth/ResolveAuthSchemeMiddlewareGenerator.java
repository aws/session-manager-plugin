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

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.utils.MapUtils;

public class ResolveAuthSchemeMiddlewareGenerator {
    public static final String MIDDLEWARE_NAME = "resolveAuthSchemeMiddleware";
    public static final String MIDDLEWARE_ID = "ResolveAuthScheme";

    private final ProtocolGenerator.GenerationContext context;

    public ResolveAuthSchemeMiddlewareGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public static Writable generateAddToProtocolFinalizers() {
        return goTemplate("""
                if err := stack.Finalize.Add(&$L{operation: operation, options: options}, $T); err != nil {
                    return $T("add $L: %w", err)
                }
                """,
                MIDDLEWARE_NAME,
                SmithyGoTypes.Middleware.Before,
                GoStdlibTypes.Fmt.Errorf,
                MIDDLEWARE_ID);
    }

    public Writable generate() {
        return ChainWritable.of(
                createFinalizeStepMiddleware(MIDDLEWARE_NAME, MiddlewareIdentifier.string(MIDDLEWARE_ID))
                        .asWritable(generateBody(), generateFields()),
                generateSelectScheme(),
                generateContextFuncs()
        ).compose();
    }

    private Writable generateFields() {
        return goTemplate("""
                operation string
                options   Options
                """);
    }

    private Writable generateBody() {
        return goTemplate("""
                _, span := $3T(ctx, "ResolveAuthScheme")
                defer span.End()

                params, err := $1L(ctx, m.operation, getOperationInput(ctx), m.options)
                if err != nil {
                    return out, metadata, $2T("bind auth scheme params: %w", err)
                }
                options, err := m.options.AuthSchemeResolver.ResolveAuthSchemes(ctx, params)
                if err != nil {
                    return out, metadata, $2T("resolve auth scheme: %w", err)
                }

                scheme, ok := m.selectScheme(options)
                if !ok {
                    return out, metadata, $2T("could not select an auth scheme")
                }

                ctx = setResolvedAuthScheme(ctx, scheme)

                span.SetProperty("auth.scheme_id", scheme.Scheme.SchemeID())
                span.End()
                return next.HandleFinalize(ctx, in)
                """,
                AuthParametersResolverGenerator.FUNC_NAME,
                GoStdlibTypes.Fmt.Errorf,
                SmithyGoDependency.SMITHY_TRACING.func("StartSpan")
        );
    }

    private Writable generateSelectScheme() {
        return goTemplate("""
                $strings:D $slices:D
                func (m *$middlewareName:L) selectScheme(options []$option:P) (*resolvedAuthScheme, bool) {
                    sorted := sortAuthOptions(options, m.options.AuthSchemePreference)
                    for _, option := range sorted {
                        if option.SchemeID == $schemeIDAnonymous:T {
                            return newResolvedAuthScheme($newAnonymousScheme:T(), option), true
                        }

                        for _, scheme := range m.options.AuthSchemes {
                            if scheme.SchemeID() != option.SchemeID {
                                continue
                            }

                            if scheme.IdentityResolver(m.options) != nil {
                                return newResolvedAuthScheme(scheme, option), true
                            }
                        }
                    }

                    return nil, false
                }

                func sortAuthOptions(options []$option:P, preferred []string) []$option:P {
                    byPriority := make([]$option:P, 0, len(options))
                    for _, prefName := range preferred {
                        for _, option := range options {
                            optName := option.SchemeID
                            if parts := strings.Split(option.SchemeID, "#"); len(parts) == 2 {
                                optName = parts[1]
                            }
                            if prefName == optName {
                                byPriority = append(byPriority, option)
                            }
                        }
                    }
                    for _, option := range options {
                        if !slices.ContainsFunc(byPriority, func(o $option:P) bool {
                            return o.SchemeID == option.SchemeID
                        }) {
                            byPriority = append(byPriority, option)
                        }
                    }
                    return byPriority
                }
                """,
                MapUtils.of(
                        "strings", SmithyGoDependency.STRINGS,
                        "slices", SmithyGoDependency.SLICES,
                        "middlewareName", MIDDLEWARE_NAME,
                        "option", SmithyGoTypes.Auth.Option,
                        "schemeIDAnonymous", SmithyGoTypes.Auth.SchemeIDAnonymous,
                        "newAnonymousScheme", SmithyGoTypes.Transport.Http.NewAnonymousScheme
                )
        );
    }

    private Writable generateContextFuncs() {
        return goTemplate("""
                type resolvedAuthSchemeKey struct{}

                type resolvedAuthScheme struct {
                    Scheme             $authScheme:T
                    IdentityProperties $properties:T
                    SignerProperties   $properties:T
                }

                func newResolvedAuthScheme(scheme $authScheme:T, option $option:P) *resolvedAuthScheme {
                    return &resolvedAuthScheme{
                        Scheme:             scheme,
                        IdentityProperties: option.IdentityProperties,
                        SignerProperties:   option.SignerProperties,
                    }
                }

                func setResolvedAuthScheme(ctx $context:T, scheme *resolvedAuthScheme) $context:T {
                    return $withStackValue:T(ctx, resolvedAuthSchemeKey{}, scheme)
                }

                func getResolvedAuthScheme(ctx $context:T) *resolvedAuthScheme {
                    v, _ := $getStackValue:T(ctx, resolvedAuthSchemeKey{}).(*resolvedAuthScheme)
                    return v
                }
                """,
                MapUtils.of(
                        "authScheme", getAuthSchemeSymbol(),
                        "option", SmithyGoTypes.Auth.Option,
                        "properties", SmithyGoTypes.Smithy.Properties,
                        "context", GoStdlibTypes.Context.Context,
                        "withStackValue", SmithyGoTypes.Middleware.WithStackValue,
                        "getStackValue", SmithyGoTypes.Middleware.GetStackValue
                ));
    }

    // FUTURE(#458): when protocols are defined here, they should supply the auth scheme symbol, for
    //               now it's pinned to the HTTP variant
    private Symbol getAuthSchemeSymbol() {
        return SmithyGoTypes.Transport.Http.AuthScheme;
    }
}
