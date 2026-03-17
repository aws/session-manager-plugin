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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.IdempotencyTokenTrait;
import software.amazon.smithy.utils.ListUtils;

public class IdempotencyTokenMiddlewareGenerator implements GoIntegration {
    public static final String IDEMPOTENCY_CONFIG_NAME = "IdempotencyTokenProvider";
    public static final MiddlewareIdentifier OPERATION_IDEMPOTENCY_TOKEN_MIDDLEWARE_ID = MiddlewareIdentifier
            .string("OperationIdempotencyTokenAutoFill");

    List<RuntimeClientPlugin> runtimeClientPlugins = new ArrayList<>();

    private void execute(
            Model model,
            GoWriter writer,
            SymbolProvider symbolProvider,
            OperationShape operation,
            MemberShape idempotencyTokenMemberShape
    ) {
        GoStackStepMiddlewareGenerator middlewareGenerator =
                GoStackStepMiddlewareGenerator.createInitializeStepMiddleware(
                        getIdempotencyTokenMiddlewareName(operation),
                        OPERATION_IDEMPOTENCY_TOKEN_MIDDLEWARE_ID
                );

        Shape inputShape = model.expectShape(operation.getInput().get());
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        String memberName = symbolProvider.toMemberName(idempotencyTokenMemberShape);
        middlewareGenerator.writeMiddleware(writer, (generator, middlewareWriter) -> {
            // if token provider is nil, skip this middleware
            middlewareWriter.openBlock("if m.tokenProvider == nil {", "}", () -> {
                middlewareWriter.write("return next.$L(ctx, in)", middlewareGenerator.getHandleMethodName());
            });
            writer.write("");

            middlewareWriter.write("input, ok := in.Parameters.($P)", inputSymbol);
            middlewareWriter.write("if !ok { return out, metadata, "
                    + "fmt.Errorf(\"expected middleware input to be of type $P \")}", inputSymbol);
            middlewareWriter.addUseImports(SmithyGoDependency.FMT);
            writer.write("");

            middlewareWriter.openBlock("if input.$L == nil {", "}", memberName, () -> {
                middlewareWriter.write("t, err := m.tokenProvider.GetIdempotencyToken()");
                middlewareWriter.write(" if err != nil { return out, metadata, err }");
                middlewareWriter.write("input.$L = &t", memberName);
            });
            middlewareWriter.write("return next.$L(ctx, in)", middlewareGenerator.getHandleMethodName());
        }, ((generator, memberWriter) -> {
            memberWriter.write("tokenProvider IdempotencyTokenProvider");
        }));
    }

    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        ServiceShape serviceShape = settings.getService(model);
        Map<ShapeId, MemberShape> map = getOperationsWithIdempotencyToken(model, serviceShape);

        if (map.isEmpty()) {
            return;
        }

        runtimeClientPlugins.add(
                RuntimeClientPlugin.builder()
                        .configFields(ListUtils.of(ConfigField.builder()
                                .name(IDEMPOTENCY_CONFIG_NAME)
                                .type(SymbolUtils.createValueSymbolBuilder("IdempotencyTokenProvider").build())
                                .documentation("Provides idempotency tokens values "
                                        + "that will be automatically populated into idempotent API operations.")
                                .build()))
                        .build()
        );

        for (Map.Entry<ShapeId, MemberShape> entry : map.entrySet()) {
            ShapeId operationShapeId = entry.getKey();
            OperationShape operation = model.expectShape(operationShapeId, OperationShape.class);

            String getMiddlewareHelperName = getIdempotencyTokenMiddlewareHelperName(operation);
            RuntimeClientPlugin runtimeClientPlugin = RuntimeClientPlugin.builder()
                    .operationPredicate((predicateModel, predicateService, predicateOperation) -> {
                        return operation.equals(predicateOperation);
                    })
                    .registerMiddleware(MiddlewareRegistrar.builder()
                            .resolvedFunction(SymbolUtils.createValueSymbolBuilder(getMiddlewareHelperName).build())
                            .useClientOptions()
                            .build())
                    .build();
            runtimeClientPlugins.add(runtimeClientPlugin);
        }
    }

    /**
     * Gets the sort order of the customization from -128 to 127, with lowest
     * executed first.
     *
     * @return Returns the sort order, defaults to 10.
     */
    @Override
    public byte getOrder() {
        return 10;
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator delegator
    ) {
        ServiceShape serviceShape = settings.getService(model);
        Map<ShapeId, MemberShape> map = getOperationsWithIdempotencyToken(model, serviceShape);
        if (map.size() == 0) {
            return;
        }

        delegator.useShapeWriter(serviceShape, (writer) -> {
            writer.write("// IdempotencyTokenProvider interface for providing idempotency token");
            writer.openBlock("type IdempotencyTokenProvider interface {", "}", () -> {
                writer.write("GetIdempotencyToken() (string, error)");
            });
            writer.write("");
        });

        for (Map.Entry<ShapeId, MemberShape> entry : map.entrySet()) {
            ShapeId operationShapeId = entry.getKey();
            OperationShape operation = model.expectShape(operationShapeId, OperationShape.class);
            delegator.useShapeWriter(operation, (writer) -> {
                // Generate idempotency token middleware
                MemberShape memberShape = map.get(operationShapeId);
                execute(model, writer, symbolProvider, operation, memberShape);

                // Generate idempotency token middleware registrar function
                writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
                String middlewareHelperName = getIdempotencyTokenMiddlewareHelperName(operation);
                writer.openBlock("func $L(stack *middleware.Stack, cfg Options) error {", "}", middlewareHelperName,
                        () -> {
                            writer.write("return stack.Initialize.Add(&$L{tokenProvider: cfg.$L}, middleware.Before)",
                                    getIdempotencyTokenMiddlewareName(operation),
                                    IDEMPOTENCY_CONFIG_NAME);
                        });
            });
        }
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return runtimeClientPlugins;
    }

    /**
     * Get Idempotency Token Middleware name.
     *
     * @param operationShape Operation shape for which middleware is defined.
     * @return name of the idempotency token middleware.
     */
    private String getIdempotencyTokenMiddlewareName(OperationShape operationShape) {
        return String.format("idempotencyToken_initializeOp%s", operationShape.getId().getName());
    }

    /**
     * Get Idempotency Token Middleware Helper name.
     *
     * @param operationShape Operation shape for which middleware is defined.
     * @return name of the idempotency token middleware.
     */
    private String getIdempotencyTokenMiddlewareHelperName(OperationShape operationShape) {
        return String.format("addIdempotencyToken_op%sMiddleware", operationShape.getId().getName());
    }

    /**
     * Gets a map with key as OperationId and Member shape as value for member shapes of an operation
     * decorated with the Idempotency token trait.
     *
     * @param model   Model used for generation.
     * @param service Service for which idempotency token map is retrieved.
     * @return map of operation shapeId as key, member shape as value.
     */
    public static Map<ShapeId, MemberShape> getOperationsWithIdempotencyToken(Model model, ServiceShape service) {
        Map<ShapeId, MemberShape> map = new TreeMap<>();
        TopDownIndex.of(model).getContainedOperations(service).stream().forEach((operationShape) -> {
            MemberShape memberShape = getMemberWithIdempotencyToken(model, operationShape);
            if (memberShape != null) {
                map.put(operationShape.getId(), memberShape);
            }
        });
        return map;
    }

    /**
     * Returns if there are any operations within the service that use idempotency token auto fill trait.
     *
     * @param model   Model used for generation.
     * @param service Service for which idempotency token map is retrieved.
     * @return if operations use idempotency token auto fill trait.
     */
    public static boolean hasOperationsWithIdempotencyToken(Model model, ServiceShape service) {
        return !getOperationsWithIdempotencyToken(model, service).isEmpty();
    }

    /**
     * Returns member shape which gets members decorated with Idempotency Token trait.
     *
     * @param model     Model used for generation.
     * @param operation Operation shape consisting of member decorated with idempotency token trait.
     * @return member shape decorated with Idempotency token trait.
     */
    private static MemberShape getMemberWithIdempotencyToken(Model model, OperationShape operation) {
        OperationIndex operationIndex = model.getKnowledge(OperationIndex.class);
        Shape inputShape = operationIndex.getInput(operation).get();
        for (MemberShape member : inputShape.members()) {
            if (member.hasTrait(IdempotencyTokenTrait.class)) {
                return member;
            }
        }
        return null;
    }
}
