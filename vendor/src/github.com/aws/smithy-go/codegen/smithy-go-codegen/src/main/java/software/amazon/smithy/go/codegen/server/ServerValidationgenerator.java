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

package software.amazon.smithy.go.codegen.server;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
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
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.utils.StringUtils;

@SmithyInternalApi
public final class ServerValidationgenerator {
    public Writable generate(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        return writer -> execute(writer, model, symbolProvider, service);
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

        generateShapeValidationFunctions(writer, model, symbolProvider, inputShapeToOperation.keySet(),
                shapesWithHelpers);
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
            String functionName = getShapeValidatorName(shape, topLevelShape);
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
                                    String helperName = getShapeValidatorName(targetShape, false);
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
                        String helperName = getShapeValidatorName(memberTarget, false);

                        writer.openBlock("for i := range v {", "}", () -> {
                            String addr = "";
                            if (!pointableIndex.isPointable(member) && pointableIndex.isPointable(memberTarget)) {
                                addr = "&";
                            }
                            writer.openBlock("if err := $L($Lv[i]); err != nil {", "}", helperName, addr, () -> {
                                writer.addUseImports(SmithyGoDependency.SMITHY);
                                writer.addUseImports(SmithyGoDependency.FMT);
                                writer.write("invalidParams.AddNested(fmt.Sprintf(\"[%d]\", i), "
                                        + "err.(smithy.InvalidParamsError))");
                            });
                        });
                        break;

                    case MAP:
                        MapShape mapShape = shape.asMapShape().get();
                        MemberShape mapValue = mapShape.getValue();
                        Shape valueTarget = model.expectShape(mapValue.getTarget());
                        helperName = getShapeValidatorName(valueTarget, false);

                        writer.openBlock("for key := range v {", "}", () -> {
                            String valueVar = "v[key]";
                            if (!pointableIndex.isPointable(mapValue) && pointableIndex.isPointable(valueTarget)) {
                                writer.write("value := $L", valueVar);
                                valueVar = "&value";
                            }
                            writer.openBlock("if err := $L($L); err != nil {", "}", helperName, valueVar, () -> {
                                writer.addUseImports(SmithyGoDependency.SMITHY);
                                writer.addUseImports(SmithyGoDependency.FMT);
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
                                    String memberHelper = getShapeValidatorName(target, false);

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

    public static String getShapeValidatorName(Shape shape) {
        return getShapeValidatorName(shape, false);
    }

    public static String getShapeValidatorName(Shape shape, boolean topLevelOpShape) {
        StringBuilder builder = new StringBuilder();
        builder.append("validate");
        builder.append(StringUtils.capitalize(shape.getId().getName()));
        return builder.toString();
    }
}
