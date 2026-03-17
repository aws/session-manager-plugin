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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.isNilable;
import static software.amazon.smithy.go.codegen.SymbolUtils.isPointable;
import static software.amazon.smithy.go.codegen.SymbolUtils.sliceOf;
import static software.amazon.smithy.go.codegen.util.ShapeUtil.BOOL_SHAPE;
import static software.amazon.smithy.go.codegen.util.ShapeUtil.INT_SHAPE;
import static software.amazon.smithy.go.codegen.util.ShapeUtil.STRING_SHAPE;
import static software.amazon.smithy.utils.StringUtils.capitalize;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.jmespath.JmespathExpression;
import software.amazon.smithy.jmespath.ast.AndExpression;
import software.amazon.smithy.jmespath.ast.ComparatorExpression;
import software.amazon.smithy.jmespath.ast.ComparatorType;
import software.amazon.smithy.jmespath.ast.CurrentExpression;
import software.amazon.smithy.jmespath.ast.FieldExpression;
import software.amazon.smithy.jmespath.ast.FilterProjectionExpression;
import software.amazon.smithy.jmespath.ast.FlattenExpression;
import software.amazon.smithy.jmespath.ast.FunctionExpression;
import software.amazon.smithy.jmespath.ast.LiteralExpression;
import software.amazon.smithy.jmespath.ast.MultiSelectListExpression;
import software.amazon.smithy.jmespath.ast.NotExpression;
import software.amazon.smithy.jmespath.ast.OrExpression;
import software.amazon.smithy.jmespath.ast.ProjectionExpression;
import software.amazon.smithy.jmespath.ast.Subexpression;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.NumberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Traverses a JMESPath expression, producing a series of statements that evaluate the entire expression. The generator
 * is shape-aware and the return indicates the underlying shape/symbol being referenced in the final result.
 * <br/>
 * Note that the use of writer.write() here is deliberate, it's easier to structure the code in that way instead of
 * trying to recursively compose/organize Writable templates.
 */
@SmithyInternalApi
public class GoJmespathExpressionGenerator {
    private final GoCodegenContext ctx;
    private final GoWriter writer;

    private int idIndex = 0;

    // as we traverse an expression, we may produce intermediate "synthetic" lists - e.g. list of string, list of list
    // of string, etc.
    // we may need to pull the member shapes back out later, but they're not guaranteed to be in the model - so keep a
    // shadow map of synthetic -> member to short-circuit the model lookup
    private final Map<Shape, Shape> synthetics = new HashMap<>();

    public GoJmespathExpressionGenerator(GoCodegenContext ctx, GoWriter writer) {
        this.ctx = ctx;
        this.writer = writer;
    }

    public Variable generate(JmespathExpression expr, Variable input) {
        return visit(expr, input);
    }

    private Variable visit(JmespathExpression expr, Variable current) {
        if (expr instanceof FunctionExpression tExpr) {
            return visitFunction(tExpr, current);
        } else if (expr instanceof FieldExpression tExpr) {
            return visitField(tExpr, current);
        } else if (expr instanceof Subexpression tExpr) {
            return visitSub(tExpr, current);
        } else if (expr instanceof ProjectionExpression tExpr) {
            return visitProjection(tExpr, current);
        } else if (expr instanceof FlattenExpression tExpr) {
            return visitFlatten(tExpr, current);
        } else if (expr instanceof ComparatorExpression tExpr) {
            return visitComparator(tExpr, current);
        } else if (expr instanceof LiteralExpression tExpr) {
            return visitLiteral(tExpr);
        } else if (expr instanceof AndExpression tExpr) {
            return visitAnd(tExpr, current);
        } else if (expr instanceof OrExpression tExpr) {
            return visitOr(tExpr, current);
        } else if (expr instanceof NotExpression tExpr) {
            return visitNot(tExpr, current);
        } else if (expr instanceof FilterProjectionExpression tExpr) {
            return visitFilterProjection(tExpr, current);
        } else if (expr instanceof MultiSelectListExpression tExpr) {
            return visitMultiSelectList(tExpr, current);
        } else if (expr instanceof CurrentExpression) {
            return current;
        } else {
            throw new CodegenException("unhandled jmespath expression " + expr.getClass().getSimpleName());
        }
    }

    private Variable visitNot(NotExpression expr, Variable current) {
        var inner = visit(expr.getExpression(), current);
        var ident = nextIdent();
        writer.write("$L := !$L", ident, inner.ident);
        return new Variable(BOOL_SHAPE, ident, GoUniverseTypes.Bool);
    }

    private Variable visitMultiSelectList(MultiSelectListExpression expr, Variable current) {
        if (expr.getExpressions().isEmpty()) {
            throw new CodegenException("multi-select list w/ no expressions");
        }

        var items = expr.getExpressions().stream()
                .map(it -> visit(it, current))
                .toList();
        var first = items.get(0);

        var ident = nextIdent();
        writer.write("$L := []$T{}", ident, first.type);
        for (var item : items) {
            if (isPointable(item.type)) {
                writer.write("""
                        if $2L != nil {
                            $1L = append($1L, *$2L)
                        }""", ident, item.ident);
            } else {
                writer.write("$1L = append($1L, $2L)", ident, item.ident);
            }
        }

        return new Variable(listOf(first.shape), ident, sliceOf(first.type));
    }

    private Variable visitFilterProjection(FilterProjectionExpression expr, Variable current) {
        var unfiltered = visitProjection(new ProjectionExpression(expr.getLeft(), expr.getRight()), current);
        if (!(unfiltered.shape instanceof CollectionShape unfilteredCol)) {
            throw new CodegenException("projection did not create a list: " + expr);
        }

        var member = expectMember(unfilteredCol);
        var type = ctx.symbolProvider().toSymbol(unfiltered.shape);

        var ident = nextIdent();
        writer.write("var $L $T", ident, type);
        writer.openBlock("for _, v := range $L {", "}", unfiltered.ident, () -> {
            var filterResult = visit(expr.getComparison(), new Variable(member, "v", type));
            writer.write("""
                    if $1L {
                        $2L = append($2L, v)
                    }""", filterResult.ident, ident);
        });

        return new Variable(unfiltered.shape, ident, type);
    }

    private Variable visitAnd(AndExpression expr, Variable current) {
        var left = visit(expr.getLeft(), current);
        var right = visit(expr.getRight(), current);
        var ident = nextIdent();
        writer.write("$L := $L && $L", ident, left.ident, right.ident);
        return new Variable(BOOL_SHAPE, ident, GoUniverseTypes.Bool);
    }

    private Variable visitOr(OrExpression expr, Variable current) {
        var left = visit(expr.getLeft(), current);
        var right = visit(expr.getRight(), current);
        var ident = nextIdent();
        writer.write("$L := $L || $L", ident, left.ident, right.ident);
        return new Variable(BOOL_SHAPE, ident, GoUniverseTypes.Bool);
    }

    private Variable visitLiteral(LiteralExpression expr) {
        var ident = nextIdent();
        if (expr.isNumberValue()) {
            // FUTURE: recognize floating-point, for now we just use int
            writer.write("$L := $L", ident, expr.expectNumberValue().intValue());
            return new Variable(INT_SHAPE, ident, GoUniverseTypes.Int);
        } else if (expr.isStringValue()) {
            writer.write("$L := $S", ident, expr.expectStringValue());
            return new Variable(STRING_SHAPE, ident, GoUniverseTypes.String);
        } else if (expr.isBooleanValue()) {
            writer.write("$L := $L", ident, expr.expectBooleanValue());
            return new Variable(STRING_SHAPE, ident, GoUniverseTypes.Bool);
        } else {
            throw new CodegenException("unhandled literal expression " + expr.getValue());
        }
    }

    private Variable visitComparator(ComparatorExpression expr, Variable current) {
        var left = visit(expr.getLeft(), current);
        var right = visit(expr.getRight(), current);

        String cast;
        if (left.shape instanceof StringShape) {
            cast = "string";
        } else if (left.shape instanceof NumberShape) {
            cast = "int64";
        } else {
            throw new CodegenException("don't know how to compare shape type" + left.shape.getType());
        }

        var ident = nextIdent();
        writer.write(compareVariables(ident, left, right, expr.getComparator(), cast));
        return new Variable(BOOL_SHAPE, ident, GoUniverseTypes.Bool);
    }

    private Variable visitFlatten(FlattenExpression tExpr, Variable current) {
        var inner = visit(tExpr.getExpression(), current);

        // inner HAS to be a list by spec, otherwise something is wrong
        if (!(inner.shape instanceof CollectionShape innerList)) {
            throw new CodegenException("projection did not create a list: " + tExpr);
        }

        // inner expression may not be a list-of-list - if so, we're done, the result is passed up as-is
        var innerMember = expectMember(innerList);
        if (!(innerMember instanceof CollectionShape)) {
            return inner;
        }

        var innerSymbol = ctx.symbolProvider().toSymbol(innerMember);
        var ident = nextIdent();
        writer.write("""
                var $1L $3P
                for _, v := range $2L {
                    $1L = append($1L, v...)
                }""", ident, inner.ident, innerSymbol);
        return new Variable(innerMember, ident, innerSymbol);
    }

    private Variable visitProjection(ProjectionExpression expr, Variable current) {
        var left = visit(expr.getLeft(), current);
        if (expr.getRight() instanceof CurrentExpression) { // e.g. "Field[]" - the projection is just itself
            return left;
        }

        Shape leftMember;
        if (left.shape instanceof CollectionShape col) {
            leftMember = expectMember(col);
        } else if (left.shape instanceof MapShape map) {
            leftMember = expectMember(map);
        } else {
            // left of projection HAS to be an array/map by spec, otherwise something is wrong
            throw new CodegenException("projection did not create a list: " + expr);
        }

        var leftSymbol = ctx.symbolProvider().toSymbol(leftMember);

        // We have to know the element type for the list that we're generating, use a dummy writer to "peek" ahead and
        // get the traversal result
        var lookahead = new GoJmespathExpressionGenerator(ctx, new GoWriter(""))
                .generate(expr.getRight(), new Variable(leftMember, "v", leftSymbol));

        var ident = nextIdent();
        writer.write("""
                var $L []$T
                for _, v := range $L {""", ident, ctx.symbolProvider().toSymbol(lookahead.shape), left.ident);

        writer.indent();
        // projected.shape is the _member_ of the resulting list
        var projected = visit(expr.getRight(), new Variable(leftMember, "v", leftSymbol));
        if (isPointable(lookahead.type)) { // projections implicitly filter out nil evaluations of RHS...
            var deref = lookahead.shape instanceof CollectionShape || lookahead.shape instanceof MapShape
                    ? "" : "*"; // ...but slices/maps do not get dereferenced
            writer.write("""
                    if $1L != nil {
                        $2L = append($2L, $3L$1L)
                    }""", projected.ident, ident, deref);
        } else {
            writer.write("$1L = append($1L, $2L)", ident, projected.ident);
        }
        writer.dedent();
        writer.write("}");

        return new Variable(listOf(projected.shape), ident, sliceOf(ctx.symbolProvider().toSymbol(projected.shape)));
    }

    private Variable visitSub(Subexpression expr, Variable current) {
        var left = visit(expr.getLeft(), current);
        if (!isNilable(left.type)) {
            return visit(expr.getRight(), left);
        }

        var lookahead = new GoJmespathExpressionGenerator(ctx, new GoWriter(""))
                .generate(expr.getRight(), left);
        var ident = nextIdent();
        writer.write("var $L $P", ident, lookahead.type);
        writer.write("if $L != nil {", left.ident);
        writer.indent();
        var inner = visit(expr.getRight(), left);
        writer.write("$L = $L", ident, inner.ident);
        writer.dedent();
        writer.write("}");
        return new Variable(inner.shape, ident, inner.type);
    }

    private Variable visitField(FieldExpression expr, Variable current) {
        var member = current.shape.getMember(expr.getName()).orElseThrow(() ->
                new CodegenException("field expression referenced nonexistent member: " + expr.getName()));

        var target = ctx.model().expectShape(member.getTarget());
        var ident = nextIdent();
        writer.write("$L := $L.$L", ident, current.ident, capitalize(expr.getName()));
        return new Variable(target, ident, ctx.symbolProvider().toSymbol(member));
    }

    private Variable visitFunction(FunctionExpression expr, Variable current) {
        return switch (expr.name) {
            case "keys" -> visitKeysFunction(expr.arguments, current);
            case "length" -> visitLengthFunction(expr.arguments, current);
            case "contains" -> visitContainsFunction(expr.arguments, current);
            default -> throw new CodegenException("unsupported function " + expr.name);
        };
    }

    private Variable visitContainsFunction(List<JmespathExpression> args, Variable current) {
        if (args.size() != 2) {
            throw new CodegenException("unexpected contains() arg length " + args.size());
        }

        var list = visit(args.get(0), current);
        var item = visit(args.get(1), current);
        var ident = nextIdent();
        writer.write("""
                var $1L bool
                for _, v := range $2L {
                    if v == $3L {
                        $1L = true
                        break
                    }
                }""", ident, list.ident, item.ident);
        return new Variable(BOOL_SHAPE, ident, GoUniverseTypes.Bool);
    }

    private Variable visitLengthFunction(List<JmespathExpression> args, Variable current) {
        if (args.size() != 1) {
            throw new CodegenException("unexpected length() arg length " + args.size());
        }

        var arg = visit(args.get(0), current);
        var ident = nextIdent();

        // length() can be used on a string (so also *string) - dereference if required
        if (arg.shape instanceof StringShape && isPointable(arg.type)) {
            writer.write("""
                    var _$1L string
                    if $1L != nil {
                        _$1L = *$1L
                    }
                    $2L := len(_$1L)""", arg.ident, ident);
        } else {
            writer.write("$L := len($L)", ident, arg.ident);
        }

        return new Variable(INT_SHAPE, ident, GoUniverseTypes.Int);
    }

    private Variable visitKeysFunction(List<JmespathExpression> args, Variable current) {
        if (args.size() != 1) {
            throw new CodegenException("unexpected keys() arg length " + args.size());
        }

        var arg = visit(args.get(0), current);
        ++idIndex;
        writer.write("""
                var v$1L []string
                for k := range $2L {
                    v$1L = append(v$1L, k)
                }""", idIndex, arg.ident);

        return new Variable(listOf(STRING_SHAPE), "v" + idIndex, sliceOf(GoUniverseTypes.String));
    }

    private String nextIdent() {
        ++idIndex;
        return "v" + idIndex;
    }

    private Shape listOf(Shape shape) {
        var list = ShapeUtil.listOf(shape);
        synthetics.putIfAbsent(list, shape);
        return list;
    }

    private Shape expectMember(CollectionShape shape) {
        return synthetics.containsKey(shape)
                ? synthetics.get(shape)
                : ShapeUtil.expectMember(ctx.model(), shape);
    }

    private Shape expectMember(MapShape shape) {
        return synthetics.containsKey(shape)
                ? synthetics.get(shape)
                : ShapeUtil.expectMember(ctx.model(), shape);
    }

    // helper to generate comparisons from two results, automatically handling any dereferencing in the process
    private Writable compareVariables(String ident, Variable left, Variable right, ComparatorType cmp,
                                      String cast) {
        var isLPtr = isPointable(left.type);
        var isRPtr = isPointable(right.type);
        if (!isLPtr && !isRPtr) {
            return goTemplate("$1L := $5L($2L) $4L $5L($3L)", ident, left.ident, right.ident, cmp, cast);
        }

        // undocumented jmespath behavior: null in numeric _ordering_ comparisons coerces to 0
        // this means the subsequent nil checks for numerics are moot, but it's either this or branch the codegen even
        // further for questionable benefit
        var nilCoerceLeft = emptyGoTemplate();
        var nilCoerceRight = emptyGoTemplate();
        if (isOrderComparator(cmp)) {
            if (isLPtr && left.shape instanceof NumberShape) {
                nilCoerceLeft = goTemplate("""
                        if ($1L == nil) {
                            $1L = new($2T)
                            *$1L = 0
                        }""", left.ident, left.type);
            }
            if (isRPtr && right.shape instanceof NumberShape) {
                nilCoerceRight = goTemplate("""
                        if ($1L == nil) {
                            $1L = new($2T)
                            *$1L = 0
                        }""", right.ident, right.type);
            }
        }

        // also, if they're both pointers, and it's (in)equality, there's an additional true case where both are nil,
        // or both are different
        var elseCheckPtrs = emptyGoTemplate();
        if (isLPtr && isRPtr) {
            if (cmp == ComparatorType.EQUAL) {
                elseCheckPtrs = goTemplate("else { $L = $L == nil && $L == nil }",
                        ident, left.ident, right.ident);
            } else if (cmp == ComparatorType.NOT_EQUAL) {
                elseCheckPtrs = goTemplate("else { $1L = ($2L == nil && $3L != nil) || ($2L != nil && $3L == nil) }",
                        ident, left.ident, right.ident);
            }
        }

        return goTemplate("""
                 var $ident:L bool
                 $nilCoerceLeft:W
                 $nilCoerceRight:W
                 if $lif:L $amp:L $rif:L {
                     $ident:L = $cast:L($lhs:L) $cmp:L $cast:L($rhs:L)
                 }$elseCheckPtrs:W""",
                Map.of(
                        "ident", ident,
                        "lif", isLPtr ? left.ident + " != nil" : "",
                        "rif", isRPtr ? right.ident + " != nil" : "",
                        "amp", isLPtr && isRPtr ? "&&" : "",
                        "cmp", cmp,
                        "lhs", isLPtr ? "*" + left.ident : left.ident,
                        "rhs", isRPtr ? "*" + right.ident : right.ident,
                        "cast", cast,
                        "nilCoerceLeft", nilCoerceLeft,
                        "nilCoerceRight", nilCoerceRight
                ),
                Map.of(
                        "elseCheckPtrs", elseCheckPtrs
                ));
    }

    private static boolean isOrderComparator(ComparatorType cmp) {
        return cmp == ComparatorType.GREATER_THAN || cmp == ComparatorType.LESS_THAN
                || cmp == ComparatorType.GREATER_THAN_EQUAL || cmp == ComparatorType.LESS_THAN_EQUAL;
    }

    /**
     * Represents a variable (input, intermediate, or final output) of a JMESPath traversal.
     * @param shape The underlying shape referenced by this variable. For certain jmespath expressions (e.g.
     *              LiteralExpression) the value here is a synthetic shape and does not necessarily have meaning.
     * @param ident The identifier of the variable in the generated traversal.
     * @param type The symbol that records the type of the variable. This does NOT necessarily correspond to the result
     *             of toSymbol(shape) because certain jmespath expressions (such as projections) may affect the type of
     *             the resulting variable in a way that severs that relationship. The caller MUST use this field to
     *             determine whether the variable is pointable/nillable.
     */
    public record Variable(Shape shape, String ident, Symbol type) {
        public Variable(Shape shape, String ident) {
            this(shape, ident, null);
        }
    }
}
