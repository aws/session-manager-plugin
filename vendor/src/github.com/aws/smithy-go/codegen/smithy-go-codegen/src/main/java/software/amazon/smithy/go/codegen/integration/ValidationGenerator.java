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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.TriConsumer;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.go.codegen.knowledge.GoValidationIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.StringUtils;

/**
 * Generates Go validation middleware and shape helpers.
 */
public class ValidationGenerator implements GoIntegration {
    public static final MiddlewareIdentifier OPERATION_INPUT_VALIDATION_MIDDLEWARE_ID = MiddlewareIdentifier
            .string("OperationInputValidation");

    private final List<RuntimeClientPlugin> runtimeClientPlugins = new ArrayList<>();

    /**
     * Gets the sort order of the customization from -128 to 127, with lowest
     * executed first.
     *
     * @return Returns the sort order, defaults to 20.
     */
    @Override
    public byte getOrder() {
        return 20;
    }

    private void execute(GoWriter writer, Model model, SymbolProvider symbolProvider, ServiceShape service) {
        GoValidationIndex validationIndex = model.getKnowledge(GoValidationIndex.class);
        Map<Shape, OperationShape> inputShapeToOperation = new TreeMap<>();
        validationIndex.getOperationsRequiringValidation(service).forEach(shapeId -> {
            OperationShape operationShape = model.expectShape(shapeId).asOperationShape().get();
            Shape inputShape = model.expectShape(operationShape.getInput().get());
            inputShapeToOperation.put(inputShape, operationShape);
        });
        Set<ShapeId> shapesWithHelpers = validationIndex.getShapesRequiringValidationHelpers(service);

        generateOperationValidationMiddleware(writer, symbolProvider, inputShapeToOperation);
        generateAddMiddlewareStackHelper(writer, symbolProvider, inputShapeToOperation.values());
        generateShapeValidationFunctions(writer, model, symbolProvider, inputShapeToOperation.keySet(),
                shapesWithHelpers);
    }

    private void generateOperationValidationMiddleware(
            GoWriter writer,
            SymbolProvider symbolProvider,
            Map<Shape, OperationShape> operationShapeMap
    ) {
        for (Map.Entry<Shape, OperationShape> entry : operationShapeMap.entrySet()) {
            GoStackStepMiddlewareGenerator generator = GoStackStepMiddlewareGenerator.createInitializeStepMiddleware(
                    getOperationValidationMiddlewareName(entry.getValue()),
                    OPERATION_INPUT_VALIDATION_MIDDLEWARE_ID
            );
            String helperName = getShapeValidationFunctionName(entry.getKey(), true);
            Symbol inputSymbol = symbolProvider.toSymbol(entry.getKey());
            generator.writeMiddleware(writer, (g, w) -> {
                writer.addUseImports(SmithyGoDependency.FMT);
                // cast input parameters type to the input type of the operation
                writer.write("input, ok := in.Parameters.($P)", inputSymbol);
                writer.openBlock("if !ok {", "}", () -> {
                    writer.write("return out, metadata, "
                            + "fmt.Errorf(\"unknown input parameters type %T\", in.Parameters)");
                });
                writer.openBlock("if err := $L(input); err != nil {", "}", helperName,
                        () -> writer.write("return out, metadata, err"));
                writer.write("return next.$L(ctx, in)", g.getHandleMethodName());
            });
            writer.write("");
        }
    }

    private void generateShapeValidationFunctions(
            GoWriter writer,
            Model model, SymbolProvider symbolProvider,
            Set<Shape> operationInputShapes,
            Set<ShapeId> shapesWithHelpers
    ) {
        GoPointableIndex pointableIndex = GoPointableIndex.of(model);

        for (ShapeId shapeId : shapesWithHelpers) {
            Shape shape = model.expectShape(shapeId);
            boolean topLevelShape = operationInputShapes.contains(shape);
            String functionName = getShapeValidationFunctionName(shape, topLevelShape);
            Symbol shapeSymbol = symbolProvider.toSymbol(shape);
            writer.openBlock("func $L(v $P) error {", "}", functionName, shapeSymbol, () -> {
                writer.addUseImports(SmithyGoDependency.SMITHY);

                if (pointableIndex.isNillable(shape)) {
                    writer.openBlock("if v == nil {", "}", () -> writer.write("return nil"));
                }

                writer.write("invalidParams := smithy.InvalidParamsError{Context: $S}", shapeSymbol.getName());
                switch (shape.getType()) {
                    case STRUCTURE:
                        shape.members().forEach(memberShape -> {
                            if (StreamingTrait.isEventStream(model, memberShape)) {
                                return;
                            }

                            String memberName = symbolProvider.toMemberName(memberShape);
                            Shape targetShape = model.expectShape(memberShape.getTarget());
                            boolean required = GoValidationIndex.isRequiredParameter(model, memberShape, topLevelShape);
                            boolean hasHelper = shapesWithHelpers.contains(targetShape.getId());
                            boolean isEnum = targetShape.getTrait(EnumTrait.class).isPresent();

                            if (required) {
                                Runnable runnable = () -> {
                                    writer.write("invalidParams.Add(smithy.NewErrParamRequired($S))", memberName);
                                    if (hasHelper) {
                                        writer.writeInline("} else ");
                                    } else {
                                        writer.write("}");
                                    }
                                };

                                if (isEnum) {
                                    writer.write("if len(v.$L) == 0 {", memberName);
                                    runnable.run();
                                } else if (pointableIndex.isNillable(memberShape)) {
                                    writer.write("if v.$L == nil {", memberName);
                                    runnable.run();
                                }
                            }

                            if (hasHelper) {
                                Runnable runnable = () -> {
                                    String helperName = getShapeValidationFunctionName(targetShape, false);
                                    writer.openBlock("if err := $L(v.$L); err != nil {", "}", helperName, memberName,
                                            () -> {
                                                writer.addUseImports(SmithyGoDependency.SMITHY);
                                                writer.write(
                                                        "invalidParams.AddNested($S, err.(smithy.InvalidParamsError))",
                                                        memberName);
                                            });
                                };

                                if (isEnum) {
                                    writer.openBlock("if len(v.$L) > 0 {", "}", memberName, runnable);
                                } else if (pointableIndex.isNillable(memberShape)) {
                                    writer.openBlock("if v.$L != nil {", "}", memberName, runnable);
                                }
                            }
                        });
                        break;

                    case LIST:
                    case SET:
                        CollectionShape collectionShape = CodegenUtils.expectCollectionShape(shape);
                        MemberShape member = collectionShape.getMember();
                        Shape memberTarget = model.expectShape(member.getTarget());
                        String helperName = getShapeValidationFunctionName(memberTarget, false);

                        writer.openBlock("for i := range v {", "}", () -> {
                            String addr = "";
                            if (!pointableIndex.isPointable(member) && pointableIndex.isPointable(memberTarget)) {
                                addr = "&";
                            }
                            writer.openBlock("if err := $L($Lv[i]); err != nil {", "}", helperName, addr, () -> {
                                writer.addUseImports(SmithyGoDependency.SMITHY);
                                writer.write("invalidParams.AddNested(fmt.Sprintf(\"[%d]\", i), "
                                        + "err.(smithy.InvalidParamsError))");
                            });
                        });
                        break;

                    case MAP:
                        MapShape mapShape = shape.asMapShape().get();
                        MemberShape mapValue = mapShape.getValue();
                        Shape valueTarget = model.expectShape(mapValue.getTarget());
                        helperName = getShapeValidationFunctionName(valueTarget, false);

                        writer.openBlock("for key := range v {", "}", () -> {
                            String valueVar = "v[key]";
                            if (!pointableIndex.isPointable(mapValue) && pointableIndex.isPointable(valueTarget)) {
                                writer.write("value := $L", valueVar);
                                valueVar = "&value";
                            }
                            writer.openBlock("if err := $L($L); err != nil {", "}", helperName, valueVar, () -> {
                                writer.addUseImports(SmithyGoDependency.SMITHY);
                                writer.write("invalidParams.AddNested(fmt.Sprintf(\"[%q]\", key), "
                                        + "err.(smithy.InvalidParamsError))");
                            });
                        });
                        break;

                    case UNION:
                        UnionShape unionShape = shape.asUnionShape().get();
                        Symbol unionSymbol = symbolProvider.toSymbol(unionShape);

                        Set<MemberShape> memberShapes = unionShape.getAllMembers().values().stream()
                                .filter(memberShape ->
                                        shapesWithHelpers.contains(model.expectShape(memberShape.getTarget()).getId()))
                                .collect(Collectors.toCollection(TreeSet::new));

                        if (memberShapes.size() > 0) {
                            writer.openBlock("switch uv := v.(type) {", "}", () -> {
                                // Use a TreeSet to sort the members.
                                for (MemberShape unionMember : memberShapes) {
                                    Shape target = model.expectShape(unionMember.getTarget());
                                    Symbol memberSymbol = SymbolUtils.createValueSymbolBuilder(
                                            symbolProvider.toMemberName(unionMember),
                                            unionSymbol.getNamespace()
                                    ).build();
                                    String memberHelper = getShapeValidationFunctionName(target, false);

                                    writer.openBlock("case *$T:", "", memberSymbol, () -> {
                                        String addr = "";
                                        if (!pointableIndex.isPointable(unionMember)
                                                && pointableIndex.isPointable(target)) {
                                            addr = "&";
                                        }
                                        writer.openBlock("if err := $L($Luv.Value); err != nil {", "}", memberHelper,
                                                addr, () -> {
                                                    writer.addUseImports(SmithyGoDependency.SMITHY);
                                                    writer.write("invalidParams.AddNested(\"[$L]\", "
                                                                    + "err.(smithy.InvalidParamsError))",
                                                            unionMember.getMemberName());
                                                });
                                    });
                                }
                            });
                        }
                        break;

                    default:
                        throw new CodegenException("Unexpected validation helper shape type " + shape.getType());
                }

                writer.write("if invalidParams.Len() > 0 {");
                writer.write("return invalidParams");
                writer.write("} else {");
                writer.write("return nil");
                writer.write("}");
            });
            writer.write("");
        }
    }

    private static String getOperationValidationMiddlewareName(OperationShape operationShape) {
        return "validateOp"
                + StringUtils.capitalize(operationShape.getId().getName());
    }

    private static String getShapeValidationFunctionName(Shape shape, boolean topLevelOpShape) {
        StringBuilder builder = new StringBuilder();
        builder.append("validate");
        if (topLevelOpShape) {
            builder.append("Op");
        }
        builder.append(StringUtils.capitalize(shape.getId().getName()));
        return builder.toString();
    }

    private String getAddMiddlewareStackHelperFunctionName(OperationShape operationShape) {
        return "addOp" + StringUtils.capitalize(operationShape.getId().getName()) + "ValidationMiddleware";
    }

    private void generateAddMiddlewareStackHelper(
            GoWriter writer,
            SymbolProvider symbolProvider,
            Collection<OperationShape> operationShapes
    ) {
        Symbol smithyStack = SymbolUtils.createPointableSymbolBuilder("Stack", SmithyGoDependency.SMITHY_MIDDLEWARE)
                .build();

        for (OperationShape operationShape : operationShapes) {
            Symbol middlewareSymbol = SymbolUtils.createPointableSymbolBuilder(getOperationValidationMiddlewareName(
                    operationShape)).build();
            String functionName = getAddMiddlewareStackHelperFunctionName(operationShape);
            writer.openBlock("func $L(stack $P) error {", "}", functionName, smithyStack, () -> {
                writer.write("return stack.Initialize.Add(&$T{}, middleware.After)", middlewareSymbol);
            });
            writer.write("");
        }
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TriConsumer<String, String, Consumer<GoWriter>> writerFactory
    ) {
        writerFactory.accept("validators.go", settings.getModuleName(), writer -> {
            execute(writer, model, symbolProvider, settings.getService(model));
        });
    }

    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        GoValidationIndex validationIndex = GoValidationIndex.of(model);
        ServiceShape service = settings.getService(model);
        Set<ShapeId> requiringValidation = validationIndex.getOperationsRequiringValidation(service);

        for (ShapeId shapeId : requiringValidation) {
            OperationShape operationShape = model.expectShape(shapeId).asOperationShape().get();
            String helperFunctionName = getAddMiddlewareStackHelperFunctionName(operationShape);
            runtimeClientPlugins.add(RuntimeClientPlugin.builder()
                    .servicePredicate((m, s) -> s.equals(service))
                    .operationPredicate((m, s, o) -> o.equals(operationShape))
                    .registerMiddleware(MiddlewareRegistrar.builder()
                            .resolvedFunction(SymbolUtils.createValueSymbolBuilder(helperFunctionName)
                                    .build())
                            .build())
                    .build());
        }
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return runtimeClientPlugins;
    }
}
