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
 *
 *
 */

package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;

/**
 * Defines code generation for a modeled auth scheme.
 */
public interface AuthSchemeDefinition {
    /**
     * Generates the service default option for this scheme.
     */
    Writable generateServiceOption(ProtocolGenerator.GenerationContext context, ServiceShape service);

    /**
     * Generates an operation-specific option for this scheme. This will only be called when the generator encounters
     * an operation with auth overrides.
     */
    Writable generateOperationOption(ProtocolGenerator.GenerationContext context, OperationShape operation);

    /**
     * Generates a default auth scheme. Called within a context where client Options are available.
     */
    default Writable generateDefaultAuthScheme() {
        return emptyGoTemplate();
    }

    /**
     * Generates the value to return from Options.GetIdentityResolver(schemeID). Called within a context where client
     * Options are available.
     */
    default Writable generateOptionsIdentityResolver() {
        return goTemplate("nil");
    }
}
