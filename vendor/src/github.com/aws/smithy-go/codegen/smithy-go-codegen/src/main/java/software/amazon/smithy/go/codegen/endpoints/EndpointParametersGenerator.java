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

package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goBlockTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.pointerTo;
import static software.amazon.smithy.go.codegen.SymbolUtils.sliceOf;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoUniverseTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.evaluation.value.Value;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.StringUtils;

public final class EndpointParametersGenerator {
    public static final String VALIDATE_REQUIRED_FUNC_NAME = "ValidateRequired";
    public static final String DEFAULT_VALUE_FUNC_NAME = "WithDefaults";
    private final Map<String, Object> commonCodegenArgs;

    private EndpointParametersGenerator(Builder builder) {
        var parametersType = SmithyBuilder.requiredState("parametersType", builder.parametersType);

        commonCodegenArgs = MapUtils.of(
                "parametersType", parametersType,
                "fmtErrorf", SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build());
    }

    public Writable generate(Optional<EndpointRuleSet> ruleset) {
        if (ruleset.isPresent()) {
            var parameters = ruleset.get().getParameters();
            return generateParameters(MapUtils.of(
                    "parametersMembers", generateParametersMembers(parameters),
                    "parametersValidationMethod", generateValidationMethod(parameters),
                    "parametersDefaultValueMethod", generateDefaultsMethod(parameters)));
        } else {
            return generateParameters(MapUtils.of(
                    "parametersMembers", emptyGoTemplate(),
                    "parametersValidationMethod", emptyGoTemplate(),
                    "parametersDefaultValueMethod", emptyGoTemplate()));
        }
    }

    private Writable generateParameters(Map<String, Object> overriddenArgs) {
        return goTemplate("""
                $parametersTypeDocs:W
                type $parametersType:T struct {
                    $parametersMembers:W
                }

                $parametersValidationMethod:W
                $parametersDefaultValueMethod:W
                """,
                commonCodegenArgs,
                MapUtils.of(
                        "parametersTypeDocs", generateParametersTypeDocs()),
                overriddenArgs);
    }

    private Writable generateParametersTypeDocs() {
        return goDocTemplate("""
                $parametersType:T provides the parameters that influence how endpoints are resolved.
                """, commonCodegenArgs);
    }

    private Writable generateParametersMembers(Parameters parameters) {
        return (GoWriter w) -> {
            w.indent();
            parameters.forEach((parameter) -> {
                var writeChain = new ChainWritable()
                        .add(parameter.getDocumentation(), GoWriter::goTemplate)
                        // TODO[GH-25977529]: fix incorrect wrapping in generated comment
                        .add(parameter.isRequired(), goTemplate("Parameter is required."))
                        .add(parameter.getDefault(), (defaultValue) -> {
                            return goTemplate("Defaults to " + defaultValue + " if no value is provided.");
                        })
                        .add(parameter.getBuiltIn(), GoWriter::goTemplate)
                        .add(parameter.getDeprecated(), (deprecated) -> {
                            return goTemplate("Deprecated: " + deprecated.getMessage());
                        });
                w.writeRenderedDocs(writeChain.compose());
                w.write("$L $P", getExportedParameterName(parameter), parameterAsSymbol(parameter));
                w.write("");
            });

        };
    }

    private Writable generateDefaultsMethod(Parameters parameters) {
        return goTemplate("""
                $methodDocs:W
                func (p $parametersType:T) $funcName:L() $parametersType:T {
                    $setDefaults:W
                    return p
                }
                """,
                commonCodegenArgs,
                MapUtils.of(
                        "funcName", DEFAULT_VALUE_FUNC_NAME,
                        "methodDocs", goDocTemplate("$funcName:L returns a shallow copy of $parametersType:T"
                                + "with default values applied to members where applicable.",
                                commonCodegenArgs,
                                MapUtils.of(
                                        "funcName", DEFAULT_VALUE_FUNC_NAME)),
                        "setDefaults", (Writable) (GoWriter w) -> {
                            sortParameters(parameters).forEach((parameter) -> {
                                parameter.getDefault().ifPresent(defaultValue -> {
                                    w.writeGoTemplate("""
                                            if p.$memberName:L == nil {
                                                p.$memberName:L = $defaultValue:W
                                            }
                                            """,
                                            MapUtils.of(
                                                    "memberName", getExportedParameterName(parameter),
                                                    "defaultValue", generateDefaultValue(parameter, defaultValue)

                                            ));
                                });
                            });
                        }));
    }

    private Writable generateDefaultValue(Parameter parameter, Value defaultValue) {
        return switch (parameter.getType()) {
            case STRING -> goTemplate("$T($S)",
                    SmithyGoDependency.SMITHY_PTR.func("String"), defaultValue.expectStringValue());
            case BOOLEAN -> goTemplate("$T($L)",
                    SmithyGoDependency.SMITHY_PTR.func("Bool"), defaultValue.expectBooleanValue());
            case STRING_ARRAY -> goTemplate("[]string{$W}", ChainWritable.of(
                    defaultValue.expectArrayValue().getValues().stream()
                            .map(it -> goTemplate("$S,", it.expectStringValue()))
                            .toList()
            ).compose(false));
        };
    }

    private Writable generateValidationMethod(Parameters parameters) {
        if (!haveRequiredParameters(parameters)) {
            return emptyGoTemplate();
        }

        return goBlockTemplate("""
                $methodDocs:W
                func (p $parametersType:T) $funcName:L() error {
                """, "}",
                commonCodegenArgs,
                MapUtils.of(
                        "funcName", VALIDATE_REQUIRED_FUNC_NAME,
                        "methodDocs", goDocTemplate("""
                                $funcName:L validates required parameters are set.
                                """,
                                MapUtils.of(
                                        "funcName", VALIDATE_REQUIRED_FUNC_NAME))),
                (w) -> {
                    sortParameters(parameters).forEach((parameter) -> {
                        if (!parameter.isRequired()) {
                            return;
                        }

                        w.writeGoTemplate("""
                                if p.$memberName:L == nil {
                                    return $fmtErrorf:T("parameter $memberName:L is required")
                                }
                                """,
                                commonCodegenArgs,
                                MapUtils.of(
                                        "memberName", getExportedParameterName(parameter)));
                    });
                    w.write("return nil");
                });

    }

    public static Symbol parameterAsSymbol(Parameter parameter) {
        return switch (parameter.getType()) {
            case STRING -> pointerTo(GoUniverseTypes.String);
            case BOOLEAN -> pointerTo(GoUniverseTypes.Bool);
            case STRING_ARRAY -> sliceOf(GoUniverseTypes.String);
        };
    }

    public static boolean haveRequiredParameters(Parameters parameters) {
        for (Iterator<Parameter> iter = parameters.iterator(); iter.hasNext();) {
            if (iter.next().isRequired()) {
                return true;
            }
        }

        return false;
    }

    public static String getExportedParameterName(Parameter parameter) {
        return StringUtils.capitalize(parameter.getName().getName().getValue());
    }

    public static String getExportedParameterName(StringNode name) {
        return StringUtils.capitalize(name.getValue());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements SmithyBuilder<EndpointParametersGenerator> {
        private Symbol parametersType;

        private Builder() {
        }

        public Builder parametersType(Symbol parametersType) {
            this.parametersType = parametersType;
            return this;
        }

        @Override
        public EndpointParametersGenerator build() {
            return new EndpointParametersGenerator(this);
        }
    }

    public static Stream<Parameter> sortParameters(Parameters parameters) {
        return StreamSupport
            .stream(parameters.spliterator(), false)
            .sorted(new Sorted());
    }

    public static final class Sorted implements Comparator<Parameter>, Serializable {
        /**
         * Initializes the SortedMembers.
         */
        @Override
        public int compare(Parameter a, Parameter b) {
            // first compare if the options are required or not, whichever option is
            // required should win. If both
            // options are required or not required, continue on to alphabetic search.

            // If a is required but b isn't return -1 so a is sorted before b
            // If b is required but a isn't, return 1 so a is sorted after b
            // If both a and b are required or optional, use alphabetic sorting of a and b's
            // member name.

            int requiredOption = 0;
            if (a.isRequired()) {
                requiredOption -= 1;
            }
            if (b.isRequired()) {
                requiredOption += 1;
            }
            if (requiredOption != 0) {
                return requiredOption;
            }

            return a.getName().getName().getValue().compareTo(b.getName().getName().getValue());
        }
    }
}
