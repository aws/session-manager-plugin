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

package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.rulesengine.language.syntax.Identifier;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.RecordLiteral;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.StringLiteral;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.TupleLiteral;

/**
 * Handles codegen for the `authSchemes` endpoint property. This property is represented in codegen as `[]*auth.Option`.
 */
public class AuthSchemePropertyGenerator {
    private final ExpressionGenerator generator;

    public AuthSchemePropertyGenerator(ExpressionGenerator generator) {
        this.generator = generator;
    }

    public static String mapEndpointPropertyAuthSchemeName(String name) {
        return switch (name) {
            case "sigv4" -> "aws.auth#sigv4";
            case "sigv4a" -> "aws.auth#sigv4a";
            default -> name;
        };
    }

    public Writable generate(Expression expr) {
        return goTemplate("""
                $T(&out, []$P{
                    $W
                })
                """,
                SmithyGoTypes.Auth.SetAuthOptions,
                SmithyGoTypes.Auth.Option,
                ChainWritable.of(
                        ((TupleLiteral) expr).members().stream()
                                .map(it -> generateOption(generator, (RecordLiteral) it))
                                .toList()
                ).compose(false));
    }

    private Writable generateOption(ExpressionGenerator generator, RecordLiteral scheme) {
        var members = scheme.members();
        var schemeName = ((StringLiteral) members.get(Identifier.of("name"))).value().expectLiteral();
        return goTemplate("""
                {
                    SchemeID: $1S,
                    SignerProperties: func() $2T {
                        var sp $2T
                        $3W
                        return sp
                    }(),
                },""",
                mapEndpointPropertyAuthSchemeName(schemeName),
                SmithyGoTypes.Smithy.Properties,
                generateOptionSignerProps(generator, scheme));
    }

    private Writable generateOptionSignerProps(ExpressionGenerator generator, RecordLiteral scheme) {
        var props = new ChainWritable();
        scheme.members().forEach((ident, expr) -> {
            var name = ident.getName().expectStringNode().getValue();
            switch (name) { // properties that don't apply to the scheme would just be ignored by the signer impl.
                case "signingName" -> props.add(goTemplate("""
                        $1T(&sp, $3W)
                        $2T(&sp, $3W)""",
                        SmithyGoTypes.Transport.Http.SetSigV4SigningName,
                        SmithyGoTypes.Transport.Http.SetSigV4ASigningName,
                        generator.generate(expr)));
                case "signingRegion" -> props.add(goTemplate("$T(&sp, $W)",
                        SmithyGoTypes.Transport.Http.SetSigV4SigningRegion, generator.generate(expr)));
                case "signingRegionSet" -> {
                    var regions = ChainWritable.of(
                            ((TupleLiteral) expr).members().stream()
                                    .map(generator::generate)
                                    .toList()
                    ).compose();
                    props.add(goTemplate("$T(&sp, []string{$W})",
                            SmithyGoTypes.Transport.Http.SetSigV4ASigningRegions, regions));
                }
                case "disableDoubleEncoding" -> props.add(goTemplate("$T(&sp, $W)",
                        SmithyGoTypes.Transport.Http.SetDisableDoubleEncoding, generator.generate(expr)));
                default -> {
                    return;
                }
            }
        });
        return props.compose();
    }
}
