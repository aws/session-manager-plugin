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

import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.endpoints.EndpointMiddlewareGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.pattern.SmithyPattern;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EndpointTrait;

/**
 * EndpointHostPrefixMiddleware adds middlewares to identify
 * a host prefix and mutate the request URL host if permitted.
**/
public class EndpointHostPrefixMiddleware implements GoIntegration {

    private static final MiddlewareIdentifier MIDDLEWARE_ID = MiddlewareIdentifier.string("EndpointHostPrefix");

    final List<RuntimeClientPlugin> runtimeClientPlugins = new ArrayList<>();
    final List<OperationShape> endpointPrefixOperations = new ArrayList<>();

    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        ServiceShape service = settings.getService(model);
        endpointPrefixOperations.addAll(getOperationsWithEndpointPrefix(model, service));

        endpointPrefixOperations.forEach((operation) -> {
            String middlewareHelperName = getMiddlewareHelperName(operation);
            runtimeClientPlugins.add(RuntimeClientPlugin.builder()
                    .operationPredicate((m, s, o) -> o.equals(operation))
                    .registerMiddleware(MiddlewareRegistrar.builder()
                            .resolvedFunction(SymbolUtils.createValueSymbolBuilder(middlewareHelperName).build())
                            .build())
                    .build()
            );
        });
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return runtimeClientPlugins;
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator delegator
    ) {
        endpointPrefixOperations.forEach((operation) -> {
            delegator.useShapeWriter(operation, (writer) -> {
                SmithyPattern pattern = operation.expectTrait(EndpointTrait.class).getHostPrefix();

                writeMiddleware(writer, model, symbolProvider, operation, pattern);

                String middlewareName = getMiddlewareName(operation);
                String middlewareHelperName = getMiddlewareHelperName(operation);
                writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
                writer.openBlock("func $L(stack *middleware.Stack) error {", "}",
                        middlewareHelperName,
                        () -> {
                            writer.write(
                                    "return stack.Finalize.Insert(&$L{}, $S, middleware.After)",
                                    middlewareName, EndpointMiddlewareGenerator.MIDDLEWARE_ID);
                        });
            });
        });
    }

    private static void writeMiddleware(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            OperationShape operation,
            SmithyPattern pattern
    ) {
        GoStackStepMiddlewareGenerator middlewareGenerator =
                GoStackStepMiddlewareGenerator.createFinalizeStepMiddleware(
                        getMiddlewareName(operation),
                        MIDDLEWARE_ID
                );

        middlewareGenerator.writeMiddleware(writer, (generator, w) -> {
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            writer.addUseImports(SmithyGoDependency.FMT);

            w.openBlock("if smithyhttp.GetHostnameImmutable(ctx) || "
                    + "smithyhttp.IsEndpointHostPrefixDisabled(ctx) {", "}", () -> {
                w.write("return next.$L(ctx, in)", generator.getHandleMethodName());
            }).write("");

            w.write("req, ok := in.Request.(*smithyhttp.Request)");
            w.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, fmt.Errorf(\"unknown transport type %T\", in.Request)");
            }).write("");

            if (pattern.getLabels().isEmpty()) {
                w.write("req.URL.Host = $S + req.URL.Host", pattern.toString());
            } else {
                // If the pattern has labels, we need to build up the host prefix using a string builder.
                writer.addUseImports(SmithyGoDependency.STRINGS);
                writer.addUseImports(SmithyGoDependency.SMITHY);
                StructureShape input = ProtocolUtils.expectInput(model, operation);
                writer.write("opaqueInput := getOperationInput(ctx)");
                writer.write("input, ok := opaqueInput.($P)", symbolProvider.toSymbol(input));
                w.openBlock("if !ok {", "}", () -> {
                    writer.write("return out, metadata, fmt.Errorf(\"unknown input type %T\", opaqueInput)");
                }).write("");

                w.write("var prefix strings.Builder");
                for (SmithyPattern.Segment segment : pattern.getSegments()) {
                    if (!segment.isLabel()) {
                        w.write("prefix.WriteString($S)", segment.toString());
                    } else {
                        MemberShape member = input.getMember(segment.getContent()).get();
                        String memberName = symbolProvider.toMemberName(member);
                        String memberReference = "input." + memberName;

                        // Theoretically this should never be nil or empty by this point unless validation has
                        // been disabled.
                        w.write("if $L == nil {", memberReference).indent();
                        w.write("return out, metadata, &smithy.SerializationError{Err: "
                                        + "fmt.Errorf(\"$L forms part of the endpoint host and so may not be nil\")}",
                                memberName);
                        w.dedent().write("} else if !smithyhttp.ValidHostLabel(*$L) {", memberReference).indent();
                        w.write("return out, metadata, &smithy.SerializationError{Err: "
                                + "fmt.Errorf(\"$L forms part of the endpoint host and so must match "
                                + "\\\"[a-zA-Z0-9-]{1,63}\\\""
                                + ", but was \\\"%s\\\"\", *$L)}", memberName, memberReference);
                        w.dedent().openBlock("} else {", "}", () -> {
                            w.write("prefix.WriteString(*$L)", memberReference);
                        });
                    }
                }
                w.write("req.URL.Host = prefix.String() + req.URL.Host");
            }
            w.write("");

            w.write("return next.$L(ctx, in)", generator.getHandleMethodName());
        });
    }

    /**
     * Gets a list of the operations decorated with the EndpointTrait.
     *
     * @param model   Model used for generation.
     * @param service Service for getting list of operations.
     * @return list of operations decorated with the EndpointTrait.
     */
    public static List<OperationShape> getOperationsWithEndpointPrefix(Model model, ServiceShape service) {
        List<OperationShape> operations = new ArrayList<>();
        TopDownIndex.of(model).getContainedOperations(service).stream().forEach((operation) -> {
            if (!operation.hasTrait(EndpointTrait.ID)) {
                return;
            }

            operations.add(operation);
        });
        return operations;
    }

    private static String getMiddlewareName(OperationShape operation) {
        return String.format("endpointPrefix_op%sMiddleware", operation.getId().getName());
    }

    private static String getMiddlewareHelperName(OperationShape operation) {
        return String.format("addEndpointPrefix_op%sMiddleware", operation.getId().getName());
    }
}
