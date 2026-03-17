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

import java.util.List;
import software.amazon.smithy.aws.traits.auth.SigV4ATrait;
import software.amazon.smithy.aws.traits.auth.SigV4Trait;
import software.amazon.smithy.go.codegen.auth.AuthParameter;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.ListUtils;

/**
 * Code generation for SigV4/SigV4A.
 */
public class SigV4AuthScheme implements GoIntegration {
    private boolean isSigV4XService(Model model, ServiceShape service) {
        return service.hasTrait(SigV4Trait.class) || service.hasTrait(SigV4ATrait.class);
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        // FUTURE: add default Region client option and scheme definitions - we need a more structured way of
        //         suppressing elements of a GoIntegration before we do so, for now those are registered SDK-side
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .servicePredicate(this::isSigV4XService)
                        .addAuthParameter(AuthParameter.REGION)
                        .build()
        );
    }
}
