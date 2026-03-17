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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.joinWritables;

import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.FunctionDefinition;
import software.amazon.smithy.utils.MapUtils;

public class FnGenerator {
    private final Scope scope;
    private final FnProvider fnProvider;

    public FnGenerator(Scope scope, FnProvider fnProvider) {
        this.scope = scope;
        this.fnProvider = fnProvider;
    }

    Writable generate(FunctionDefinition fnDef, List<Expression> fnArgs) {

        Symbol goFn;
        if (this.fnProvider.fnFor(fnDef.getId()) == null) {
            var defaultFnProvider = new DefaultFnProvider();
            goFn = defaultFnProvider.fnFor(fnDef.getId());
        } else {
            goFn = this.fnProvider.fnFor(fnDef.getId());
        }

        List<Writable> writableFnArgs = new ArrayList<>();
        fnArgs.forEach((expr) -> {
            writableFnArgs.add(new ExpressionGenerator(scope, this.fnProvider).generate(expr));
        });

        return goTemplate("$fn:T($args:W)", MapUtils.of(
                "fn", goFn,
                "args", joinWritables(writableFnArgs, ", ")));
    }

    public static class DefaultFnProvider implements FnProvider {

        @Override
        public Symbol fnFor(String name) {
            return switch (name) {
                case "isValidHostLabel" -> SymbolUtils.createValueSymbolBuilder("IsValidHostLabel",
                        SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();
                case "parseURL" -> SymbolUtils.createValueSymbolBuilder("ParseURL",
                        SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();
                case "substring" -> SymbolUtils.createValueSymbolBuilder("SubString",
                        SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();
                case "uriEncode" -> SymbolUtils.createValueSymbolBuilder("URIEncode",
                        SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();

                default -> null;
            };
        }
    }

    static boolean isFnResultOptional(FunctionDefinition fn) {
        return switch (fn.getId()) {
            case "isValidHostLabel" -> true;
            case "parseURL" -> true;
            case "substring" -> true;
            case "uriEncode" -> false;

            default -> false;
        };
    }

}
