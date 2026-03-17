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


package software.amazon.smithy.go.codegen.integration;

import java.util.List;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.traits.RequiresLengthTrait;
import software.amazon.smithy.utils.ListUtils;

/**
 * Adds a runtime plugin to support requires-length trait behavior.
 */
public class RequiresLengthTraitSupport implements GoIntegration {
    @Override
    public byte getOrder() {
        return 127;
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .operationPredicate(this::hasRequiresLengthTrait)
                        .registerMiddleware(MiddlewareRegistrar.builder()
                                .resolvedFunction(SymbolUtils.createValueSymbolBuilder(
                                        "ValidateContentLengthHeader",
                                        SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build())
                                .build())
                        .build()
        );
    }

    // return true if operation shape has a streaming blob member decorated with `requiresLength` trait.
    private boolean hasRequiresLengthTrait(Model model, ServiceShape service, OperationShape operation) {
        for (MemberShape member : operation.members()) {
            if (member.hasTrait(RequiresLengthTrait.class)) {
                return true;
            }
        }
        return false;
    }
}
