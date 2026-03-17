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

import static software.amazon.smithy.go.codegen.GoWriter.goBlockTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.rulesengine.language.syntax.Identifier;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression;
import software.amazon.smithy.rulesengine.language.syntax.expressions.ExpressionVisitor;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Reference;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Template;
import software.amazon.smithy.rulesengine.language.syntax.expressions.TemplateVisitor;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.FunctionDefinition;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.GetAttr;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.Literal;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.LiteralVisitor;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.StringUtils;

final class ExpressionGenerator {
    private final Scope scope;
    private final FnProvider fnProvider;

    ExpressionGenerator(Scope scope, FnProvider fnProvider) {
        this.scope = scope;
        this.fnProvider = fnProvider;
    }

    public Writable generate(Expression expr) {
        var exprOrRef = expr;
        var optExprIdent = scope.getIdent(expr);
        if (optExprIdent.isPresent()) {
            exprOrRef = new Reference(Identifier.of(optExprIdent.get()), SourceLocation.NONE);
        }
        return exprOrRef.accept(new ExpressionGeneratorVisitor(scope, fnProvider));
    }

    private record ExpressionGeneratorVisitor(Scope scope, FnProvider fnProvider)
            implements ExpressionVisitor<Writable> {

        @Override
        public Writable visitLiteral(Literal literal) {
            return literal.accept(new LiteralGeneratorVisitor(scope, fnProvider));
        }

        @Override
        public Writable visitRef(Reference ref) {
            return goTemplate(ref.getName().toString());
        }

        @Override
        public Writable visitGetAttr(GetAttr getAttr) {
            var target = new ExpressionGenerator(scope, fnProvider).generate(getAttr.getTarget());
            var path = (Writable) (GoWriter w) -> {
                getAttr.getPath().stream().toList().forEach((part) -> {
                    if (part instanceof GetAttr.Part.Key) {
                        w.writeInline(".$L", getBuiltinMemberName(((GetAttr.Part.Key) part).key()));
                    } else if (part instanceof GetAttr.Part.Index) {
                        w.writeInline(".Get($L)", ((GetAttr.Part.Index) part).index());
                    }
                });
            };

            return (GoWriter w) -> w.writeInline("$W$W", target, path);
        }

        @Override
        public Writable visitIsSet(Expression expr) {
            return (GoWriter w) -> {
                w.write("$W != nil", new ExpressionGenerator(scope, fnProvider).generate(expr));
            };
        }

        @Override
        public Writable visitNot(Expression expr) {
            return (GoWriter w) -> {
                w.write("!($W)", new ExpressionGenerator(scope, fnProvider).generate(expr));
            };
        }

        @Override
        public Writable visitBoolEquals(Expression left, Expression right) {
            return (GoWriter w) -> {
                var generator = new ExpressionGenerator(scope, fnProvider);
                w.write("$W == $W", generator.generate(left), generator.generate(right));
            };
        }

        @Override
        public Writable visitStringEquals(Expression left, Expression right) {
            return (GoWriter w) -> {
                var generator = new ExpressionGenerator(scope, fnProvider);
                w.write("$W == $W", generator.generate(left), generator.generate(right));
            };
        }

        @Override
        public Writable visitLibraryFunction(FunctionDefinition fnDef, List<Expression> args) {
            return new FnGenerator(scope, fnProvider).generate(fnDef, args);
        }
    }

    private record LiteralGeneratorVisitor(Scope scope, FnProvider fnProvider)
            implements LiteralVisitor<Writable> {

        @Override
        public Writable visitBoolean(boolean b) {
            return goTemplate(String.valueOf(b));
        }

        @Override
        public Writable visitString(Template value) {
            var parts = value.accept(
                    new TemplateGeneratorVisitor((expr) -> new ExpressionGenerator(scope, fnProvider).generate(expr))
            ).toList();

            return (GoWriter w) -> {
                parts.forEach((p) -> w.write("$W", p));
            };
        }

        @Override
        public Writable visitRecord(Map<Identifier, Literal> members) {
            return goBlockTemplate("map[string]interface{}{", "}",
                    (w) -> {
                        members.forEach((k, v) -> {
                            w.write("$S: $W,", k.getName().toString(),
                                    new ExpressionGenerator(scope, fnProvider).generate(v));
                        });
                    });
        }

        @Override
        public Writable visitTuple(List<Literal> members) {
            return goBlockTemplate("[]interface{}{", "}",
                    (w) -> {
                        members.forEach((v) -> w.write("$W,", new ExpressionGenerator(scope, fnProvider).generate(v)));
                    });
        }

        @Override
        public Writable visitInteger(int i) {
            return goTemplate(String.valueOf(i));
        }
    }

    private record TemplateGeneratorVisitor(
            Function<Expression, Writable> generator) implements TemplateVisitor<Writable> {

        @Override
        public Writable visitStaticTemplate(String s) {
            return (GoWriter w) -> w.write("$S", s);
        }

        @Override
        public Writable visitSingleDynamicTemplate(Expression expr) {
            return this.generator.apply(expr);
        }

        @Override
        public Writable visitStaticElement(String s) {
            return (GoWriter w) -> {
                w.write("out.WriteString($S)", s);
            };
        }

        @Override
        public Writable visitDynamicElement(Expression expr) {
            return (GoWriter w) -> {
                w.write("out.WriteString($W)", this.generator.apply(expr));
            };
        }

        @Override
        public Writable startMultipartTemplate() {
            return goTemplate("""
                    func() string {
                        var out $stringsBuilder:T
                    """,
                    MapUtils.of(
                            "stringsBuilder", SymbolUtils.createValueSymbolBuilder("Builder",
                                    SmithyGoDependency.STRINGS).build()));
        }

        @Override
        public Writable finishMultipartTemplate() {
            return goTemplate("""
                        return out.String()
                    }()
                    """);
        }
    }

    private static String getBuiltinMemberName(Identifier ident) {
        return StringUtils.capitalize(ident.getName().toString());
    }
}
