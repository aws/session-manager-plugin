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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;

/**
 * Implements codegen for smithy.api#noAuth.
 */
public class AnonymousDefinition implements AuthSchemeDefinition {
    @Override
    public Writable generateServiceOption(ProtocolGenerator.GenerationContext c, ServiceShape s) {
        return goTemplate("&$T{SchemeID: $T},",
                SmithyGoTypes.Auth.Option,
                SmithyGoTypes.Auth.SchemeIDAnonymous);
    }

    @Override
    public Writable generateOperationOption(ProtocolGenerator.GenerationContext c, OperationShape o) {
        return goTemplate("&$T{SchemeID: $T},",
                SmithyGoTypes.Auth.Option,
                SmithyGoTypes.Auth.SchemeIDAnonymous);
    }

    @Override
    public Writable generateOptionsIdentityResolver() {
        return goTemplate("&$T{}", SmithyGoTypes.Auth.AnonymousIdentityResolver);
    }
}
