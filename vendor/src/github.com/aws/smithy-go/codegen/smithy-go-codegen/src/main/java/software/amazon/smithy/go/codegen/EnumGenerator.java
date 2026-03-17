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

package software.amazon.smithy.go.codegen;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.traits.EnumDefinition;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.utils.StringUtils;

/**
 * Renders enums and their constants.
 */
@SmithyInternalApi
public final class EnumGenerator implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(EnumGenerator.class.getName());

    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final StringShape shape;

    public EnumGenerator(SymbolProvider symbolProvider, GoWriter writer, StringShape shape) {
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.shape = shape;
    }

    @Override
    public void run() {
        Symbol symbol = symbolProvider.toSymbol(shape);
        EnumTrait enumTrait = shape.expectTrait(EnumTrait.class);

        writer.write("type $L string", symbol.getName()).write("");

        // Don't generate constants if there are no explicitly modeled names. We only need to
        // look at one, since Smithy validates that if one has a name then they must all have
        // a name.
        if (enumTrait.getValues().get(0).getName().isPresent()) {
            writer.writeDocs(String.format("Enum values for %s", symbol.getName()));
            Set<String> constants = new LinkedHashSet<>();
            writer.openBlock("const (", ")", () -> {
                for (EnumDefinition definition : enumTrait.getValues()) {
                    StringBuilder labelBuilder = new StringBuilder(symbol.getName());
                    String name = definition.getName().get();

                    for (String part : name.split("(?U)[\\W_]")) {
                        if (part.matches(".*[a-z].*") && part.matches(".*[A-Z].*")) {
                            // Mixed case names should not be changed other than first letter capitalized.
                            labelBuilder.append(StringUtils.capitalize(part));
                        } else {
                            // For all non-mixed case parts title case first letter, followed by all other lower cased.
                            labelBuilder.append(StringUtils.capitalize(part.toLowerCase(Locale.US)));
                        }
                    }
                    String label = labelBuilder.toString();

                    // If camel-casing would cause a conflict, don't camel-case this enum value.
                    if (constants.contains(label)) {
                        LOGGER.warning(String.format(
                                "Multiple enums resolved to the same name, `%s`, using unaltered value for: %s",
                                label, name));
                        label = name;
                    }
                    constants.add(label);

                    definition.getDocumentation().ifPresent(writer::writeDocs);
                    writer.write("$L $L = $S", label, symbol.getName(), definition.getValue());
                }
            }).write("");
        }

        writer.writeDocs(String.format("Values returns all known values for %s. Note that this can be expanded in the "
                + "future, and so it is only as up to date as the client.%n%nThe ordering of this slice is not "
                + "guaranteed to be stable across updates.", symbol.getName()));
        writer.openBlock("func ($L) Values() []$L {", "}", symbol.getName(), symbol.getName(), () -> {
            writer.openBlock("return []$L{", "}", symbol.getName(), () -> {
                for (EnumDefinition definition : enumTrait.getValues()) {
                    writer.write("$S,", definition.getValue());
                }
            });
        });
    }
}
