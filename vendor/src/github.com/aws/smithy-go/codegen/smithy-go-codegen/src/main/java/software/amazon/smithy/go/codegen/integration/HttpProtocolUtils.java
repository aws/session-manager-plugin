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

import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.ListUtils;

/**
 * Provides a set of RuntimePlugins to ensure that a HTTP response is closed when it needs to be needed. This should be
 * used by all HTTP based protocols to ensure that the response body is closed, and any errors are checked. This
 * ensures that connections are not leaked by the underlying HTTP client.
 */
public final class HttpProtocolUtils {
    private HttpProtocolUtils() {
    }

    /**
     * Returns a set of RuntimePlugs to close the HTTP operation response. Uses the servicePredicate parameter to
     * filter the RuntimePlugins to protocols that are relevant.
     *
     * @param servicePredicate service filter
     * @return RuntimePlugins
     */
    public static List<RuntimeClientPlugin> getCloseResponseClientPlugins(
            BiPredicate<Model, ServiceShape> servicePredicate
    ) {
        return ListUtils.of(
                // Add deserialization middleware to close the response in case of errors.
                RuntimeClientPlugin.builder()
                        .servicePredicate(servicePredicate)
                        .operationPredicate((model, service, operation) -> {
                            var eventStreamIndex = EventStreamIndex.of(model);

                            return eventStreamIndex.getInputInfo(operation).isEmpty()
                                   && eventStreamIndex.getOutputInfo(operation).isEmpty();
                        })
                        .registerMiddleware(MiddlewareRegistrar.builder()
                                .resolvedFunction(SymbolUtils.createValueSymbolBuilder(
                                                "AddErrorCloseResponseBodyMiddleware",
                                                SmithyGoDependency.SMITHY_HTTP_TRANSPORT)
                                        .build())
                                .build()
                        )
                        .build(),

                // Add deserialization middleware to close the response for non-output-streaming operations.
                RuntimeClientPlugin.builder()
                        .servicePredicate(servicePredicate)
                        .operationPredicate((model, service, operation) -> {
                            // Don't auto close response body when response is streaming.
                            HttpBindingIndex httpBindingIndex = HttpBindingIndex.of(model);
                            Optional<HttpBinding> payloadBinding = httpBindingIndex.getResponseBindings(operation,
                                    HttpBinding.Location.PAYLOAD).stream().findFirst();

                            var eventStreamIndex = EventStreamIndex.of(model);

                            if (eventStreamIndex.getInputInfo(operation).isPresent()
                                || eventStreamIndex.getOutputInfo(operation).isPresent()) {
                                return false;
                            }

                            if (payloadBinding.isPresent()) {
                                MemberShape memberShape = payloadBinding.get().getMember();
                                Shape payloadShape = model.expectShape(memberShape.getTarget());

                                return !payloadShape.hasTrait(StreamingTrait.class);
                            }

                            return true;
                        })
                        .registerMiddleware(MiddlewareRegistrar.builder()
                                .resolvedFunction(SymbolUtils.createValueSymbolBuilder(
                                                "AddCloseResponseBodyMiddleware",
                                                SmithyGoDependency.SMITHY_HTTP_TRANSPORT)
                                        .build())
                                .build()
                        )
                        .build()
        );
    }
}
