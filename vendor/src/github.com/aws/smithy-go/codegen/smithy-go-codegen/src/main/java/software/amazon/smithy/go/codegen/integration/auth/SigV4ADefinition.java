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

package software.amazon.smithy.go.codegen.integration.auth;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.aws.traits.auth.SigV4ATrait;
import software.amazon.smithy.aws.traits.auth.SigV4Trait;
import software.amazon.smithy.aws.traits.auth.UnsignedPayloadTrait;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.MapUtils;

/**
 * Implements codegen for aws.auth#sigv4a.
 */
public class SigV4ADefinition implements AuthSchemeDefinition {
    private static final Map<String, Object> COMMON_ENV = MapUtils.of(
            "properties", SmithyGoTypes.Smithy.Properties,
            "option", SmithyGoTypes.Auth.Option,
            "schemeId", SmithyGoTypes.Auth.SchemeIDSigV4A,
            "setSigningName", SmithyGoTypes.Transport.Http.SetSigV4ASigningName,
            "setSigningRegions", SmithyGoTypes.Transport.Http.SetSigV4ASigningRegions
    );

    @Override
    public Writable generateServiceOption(
            ProtocolGenerator.GenerationContext context, ServiceShape service
    ) {
        var trait = service.expectTrait(SigV4ATrait.class);
        return goTemplate("""
                &$option:T{
                    SchemeID: $schemeId:T,
                    SignerProperties: func() $properties:T {
                        var props $properties:T
                        $setSigningName:T(&props, $name:S)
                        $setSigningRegions:T(&props, []string{params.Region})
                        return props
                    }(),
                },""",
                COMMON_ENV,
                MapUtils.of(
                        "name", trait.getName()
                ));
    }

    @Override
    public Writable generateOperationOption(
            ProtocolGenerator.GenerationContext context, OperationShape operation
    ) {
        var trait = context.getService().expectTrait(SigV4Trait.class);
        return goTemplate("""
                &$option:T{
                    SchemeID: $schemeId:T,
                    SignerProperties: func() $properties:T {
                        var props $properties:T
                        $setSigningName:T(&props, $name:S)
                        $setSigningRegions:T(&props, []string{params.Region})
                        $unsignedPayload:W
                        return props
                    }(),
                },""",
                COMMON_ENV,
                MapUtils.of(
                        "name", trait.getName(),
                        "unsignedPayload", generateIsUnsignedPayload(operation)
                ));
    }

    private Writable generateIsUnsignedPayload(OperationShape operation) {
        return operation.hasTrait(UnsignedPayloadTrait.class)
                ? goTemplate("$T(&props, true)", SmithyGoTypes.Transport.Http.SetIsUnsignedPayload)
                : emptyGoTemplate();
    }
}
