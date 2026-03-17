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
import static software.amazon.smithy.go.codegen.GoWriter.joinWritables;
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.VALIDATE_REQUIRED_FUNC_NAME;
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.getExportedParameterName;
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.haveRequiredParameters;
import static software.amazon.smithy.go.codegen.endpoints.FnGenerator.isFnResultOptional;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.SourceException;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.rulesengine.language.Endpoint;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.error.RuleError;
import software.amazon.smithy.rulesengine.language.evaluation.type.ArrayType;
import software.amazon.smithy.rulesengine.language.evaluation.type.OptionalType;
import software.amazon.smithy.rulesengine.language.evaluation.type.StringType;
import software.amazon.smithy.rulesengine.language.evaluation.type.Type;
import software.amazon.smithy.rulesengine.language.syntax.Identifier;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression;
import software.amazon.smithy.rulesengine.language.syntax.expressions.ExpressionVisitor;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Reference;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.FunctionDefinition;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.IsSet;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.Literal;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;
import software.amazon.smithy.rulesengine.language.syntax.rule.Condition;
import software.amazon.smithy.rulesengine.language.syntax.rule.Rule;
import software.amazon.smithy.rulesengine.language.syntax.rule.RuleValueVisitor;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyBuilder;

public final class EndpointResolverGenerator {
    private static final String PARAMS_ARG_NAME = "params";
    private static final String REALIZED_URL_VARIABLE_NAME = "uri";
    private static final String ERROR_MESSAGE_ENDOFTREE =
        "Endpoint resolution failed. Invalid operation or environment input.";
    private static final Logger LOGGER = Logger.getLogger(EndpointResolverGenerator.class.getName());

    private final Map<String, Object> commonCodegenArgs;
    private final FnProvider fnProvider;

    private int conditionIdentCounter = 0;

    private EndpointResolverGenerator(Builder builder) {
        var parametersType = SmithyBuilder.requiredState("parametersType", builder.parametersType);
        var resolverInterfaceType = SmithyBuilder.requiredState("resolverInterfaceType", builder.resolverInterfaceType);
        var resolverImplementationType = SmithyBuilder.requiredState("resolverImplementationType",
                builder.resolverImplementationType);
        var newResolverFn = SmithyBuilder.requiredState("newResolverFn", builder.newResolverFn);
        var endpointType = SmithyBuilder.requiredState("endpointType", builder.endpointType);
        var resolveEndpointMethodName = SmithyBuilder.requiredState("resolveEndpointMethodName",
                builder.resolveEndpointMethodName);

        this.fnProvider = SmithyBuilder.requiredState("fnProvider", builder.fnProvider);
        this.commonCodegenArgs = MapUtils.of(
                "paramArgName", PARAMS_ARG_NAME,
                "parametersType", parametersType,
                "endpointType", endpointType,
                "resolverInterfaceType", resolverInterfaceType,
                "resolverImplementationType", resolverImplementationType,
                "newResolverFn", newResolverFn,
                "resolveEndpointMethodName", resolveEndpointMethodName,
                "fmtErrorf", SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build());
    }

    public Writable generate(Optional<EndpointRuleSet> ruleset) {
        if (ruleset.isPresent()) {
            return generateResolverType(generateResolveMethodBody(ruleset.get()));
        } else {
            LOGGER.warning("service does not have modeled endpoint rules");
            return generateEmptyRules();
        }
    }

    public Writable generateEmptyRules() {
        return generateResolverType(generateEmptyResolveMethodBody());
    }

    private Writable generateResolverType(Writable resolveMethodBody) {
        return goTemplate("""
                $stringSlice:W

                // $resolverInterfaceType:T provides the interface for resolving service endpoints.
                type $resolverInterfaceType:T interface {
                    $resolveEndpointMethodDocs:W
                    $resolveEndpointMethodName:L(ctx $context:T, $paramArgName:L $parametersType:T) (
                        $endpointType:T, error,
                    )
                }

                $resolverTypeDocs:W
                type $resolverImplementationType:T struct{}

                func $newResolverFn:T() $resolverInterfaceType:T {
                    return &$resolverImplementationType:T{}
                }

                $resolveEndpointMethodDocs:W
                func (r *$resolverImplementationType:T) $resolveEndpointMethodName:L(
                    ctx $context:T, $paramArgName:L $parametersType:T,
                ) (
                    endpoint $endpointType:T, err error,
                ) {
                    $resolveMethodBody:W
                }
                """,
                commonCodegenArgs,
                MapUtils.of(
                        "context", SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT).build(),
                        "stringSlice", generateStringSliceHelper(),
                        "resolverTypeDocs", generateResolverTypeDocs(),
                        "resolveEndpointMethodDocs", generateResolveEndpointMethodDocs(),
                        "resolveMethodBody", resolveMethodBody));
    }

    private static String getLocalVarParameterName(Parameter p) {
        return "_" + p.getName().toString();
    }

    private static String getMemberParameterName(Parameter p) {
        return PARAMS_ARG_NAME + "." + getExportedParameterName(p);
    }

    private Writable generateResolveMethodBody(EndpointRuleSet ruleset) {
        var scope = Scope.empty();
        for (Iterator<Parameter> iter = ruleset.getParameters().iterator(); iter.hasNext();) {
            // Required parameters can be dereferenced directly so that read access are
            // always by value.
            // Optional parameters will be dereferenced via conditional checks.
            String identName;
            Parameter p = iter.next();
            if (p.isRequired()) {
                identName = getLocalVarParameterName(p);
            } else {
                identName = getMemberParameterName(p);
            }
            scope = scope.withIdent(p.toExpression(), identName);
        }
        ruleset.typeCheck();
        return goTemplate("""
                    $paramsWithDefaults:W
                    $validateParams:W
                    $paramVars:W

                    $rules:W
                """,
                commonCodegenArgs,
                MapUtils.of(
                        "logPrintln", SymbolUtils.createValueSymbolBuilder("Println", SmithyGoDependency.LOG).build(),
                        "validateParams", generateValidateParams(ruleset.getParameters()),
                        "paramsWithDefaults", generateParamsWithDefaults(),
                        "paramVars", (Writable) (GoWriter w) -> {
                            for (Iterator<Parameter> iter = ruleset.getParameters().iterator(); iter.hasNext();) {
                                Parameter param = iter.next();
                                if (!param.isRequired()) {
                                    continue;
                                }
                                switch (param.getType()) {
                                    case STRING, BOOLEAN -> {
                                        w.write("$L := *$L",
                                                getLocalVarParameterName(param), getMemberParameterName(param));
                                        // even if the parameter is required, it's not guaranteed that it will be used
                                        // so we generate a blank identifier to prevent a compiler error if the
                                        // variable is not used
                                        w.write("_ = $L", getLocalVarParameterName(param));
                                    }
                                    case STRING_ARRAY -> {
                                        w.write("$L := stringSlice($L)",
                                                getLocalVarParameterName(param), getMemberParameterName(param));
                                        w.write("_ = $L", getLocalVarParameterName(param));
                                    }
                                    default -> throw new CodegenException("unrecognized parameter type");
                                }
                            }
                        },
                        "rules", generateRulesList(ruleset.getRules(), scope)));
    }

    private Writable generateParamsWithDefaults() {
        return goTemplate("$paramArgName:L = $paramArgName:L.$withDefaults:L()",
                commonCodegenArgs,
                MapUtils.of(
                        "withDefaults", EndpointParametersGenerator.DEFAULT_VALUE_FUNC_NAME));
    }

    private Writable generateEmptyResolveMethodBody() {
        return goTemplate("return endpoint, $fmtErrorf:T(\"no endpoint rules defined\")", commonCodegenArgs);
    }

    private Writable generateResolverTypeDocs() {
        return goDocTemplate("$resolverImplementationType:T provides the implementation for resolving endpoints.",
                commonCodegenArgs);
    }

    private Writable generateResolveEndpointMethodDocs() {
        return goDocTemplate("$resolveEndpointMethodName:L attempts to resolve the endpoint with the provided options,"
                + " returning the endpoint if found. Otherwise an error is returned.",
                commonCodegenArgs);
    }

    private Writable generateValidateParams(Parameters parameters) {
        if (!haveRequiredParameters(parameters)) {
            return emptyGoTemplate();
        }
        return goTemplate("""
                if err = $paramArgName:L.$paramsValidateMethod:L(); err != nil {
                        return endpoint, $fmtErrorf:T("endpoint parameters are not valid, %w", err)
                    }
                """,
                commonCodegenArgs,
                MapUtils.of(
                        "paramsValidateMethod", VALIDATE_REQUIRED_FUNC_NAME));

    }

    private Writable generateRulesList(List<Rule> rules, Scope scope) {
        return (w) -> {
            rules.forEach(rule -> {
                rule.getDocumentation().ifPresent(w::writeDocs);
                w.write("$W", generateRule(rule, rule.getConditions(), scope));
            });

            if (!rules.isEmpty()) {
                Rule lastRule = rules.get(rules.size() - 1);
                // Trees are terminal, so we must ensure there's a final fallback condition at
                // the end of each one.
                // Generally we know we need to insert one when the final rule in a tree is not
                // "static" i.e. it has
                // conditions that might mean it is not selected. Since it may not be chosen
                // (its set of conditions may
                // evaluate to false) we MUST put a fallback error return after which we know
                // will get executed.
                //
                // However, assignment statements are conflated with conditions in the rules
                // language, and while certain
                // assignments DO have a condition associated with them (basically, checking
                // that the result of the
                // assignment is not nil), some do not. Therefore, remove "static"
                // condition/assignments from
                // consideration.
                boolean needsFallback = !lastRule.getConditions().stream().filter(
                        condition -> {
                            // You can't assert into a FunctionDefinition from an Expression - we have to
                            // inspect the fn
                            // member of the node directly.
                            String fn = condition.toNode().expectObjectNode().expectStringMember("fn").getValue();
                            // the only static assignment condition, as of this writing...
                            return !fn.equals("uriEncode");
                        }).toList().isEmpty();
                if (needsFallback) {
                    w.writeGoTemplate(
                            "return endpoint, $fmtErrorf:T(\"" + ERROR_MESSAGE_ENDOFTREE + "\")",
                            commonCodegenArgs);
                }
            }
        };
    }

    private Writable generateRule(Rule rule, List<Condition> conditions, Scope scope) {
        if (conditions.isEmpty()) {
            return rule.accept(new RuleVisitor(scope, this.fnProvider));
        }

        var condition = conditions.get(0);
        var remainingConditions = conditions.subList(1, conditions.size());

        var generator = new ExpressionGenerator(scope, this.fnProvider);
        var fn = conditionalFunc(condition);

        String conditionIdentifier;
        if (condition.getResult().isPresent()) {
            var ident = condition.getResult().get();
            conditionIdentifier = "_" + ident.getName().getValue();

            // Store the condition result so that it can be referenced in the future by the
            // result identifier.
            scope = scope.withIdent(new Reference(ident, SourceLocation.NONE), conditionIdentifier);
        } else {
            conditionIdentifier = nameForExpression(fn);
        }

        if (fn.type() instanceof OptionalType || isConditionalFnResultOptional(condition, fn)) {
            // []string (e.g. in endpoint params) needs to be casted as stringSlice instead of dereferenced for index
            // operations
            var isStringSlice = false;
            if (fn.type() instanceof OptionalType opt) {
                isStringSlice = isStringSlice(opt.inner());
            }
            return goTemplate("""
                    if exprVal := $target:W; exprVal != nil {
                        $conditionIdent:L := $exprVal:L
                        _ = $conditionIdent:L
                        $next:W
                    }
                    """,
                    MapUtils.of(
                            "exprVal", isStringSlice ? "stringSlice(exprVal)" : "*exprVal",
                            "conditionIdent", conditionIdentifier,
                            "target", generator.generate(fn),
                            "next", generateRule(
                                    rule,
                                    remainingConditions,
                                    scope.withMember(fn, conditionIdentifier))));
        }

        if (condition.getResult().isPresent()) {
            return goTemplate("""
                    $conditionIdent:L := $target:W
                    _ = $conditionIdent:L
                    $next:W
                    """,
                    MapUtils.of(
                            "conditionIdent", conditionIdentifier,
                            "target", generator.generate(fn),
                            "next", generateRule(
                                    rule,
                                    remainingConditions,
                                    scope.withMember(fn, conditionIdentifier))));
        }

        return goTemplate("""
                if $target:W {
                    $next:W
                }
                """,
                MapUtils.of(
                        "target", generator.generate(fn),
                        "next", generateRule(rule, remainingConditions, scope)));
    }

    private static Expression conditionalFunc(Condition condition) {
        var fn = condition.getFunction();
        if (fn instanceof IsSet) {
            var setFn = ((IsSet) fn);
            List<Expression> argv = setFn.getArguments();
            if (argv.size() == 1) {
                return argv.get(0);
            }
            throw new RuleError(new SourceException("expected 1 argument but found " + argv.size(), setFn));
        }
        return fn;
    }

    private String nameForExpression(Expression expr) {
        conditionIdentCounter++;
        if (expr instanceof Reference) {
            return nameForRef((Reference) expr);
        }
        return String.format("_var_%d", conditionIdentCounter);
    }

    /**
     * Returns a name for a reference.
     *
     * @param ref reference to get name for
     * @return name
     */
    private static String nameForRef(Reference ref) {
        return "_" + ref.getName();
    }

    private Writable generateEndpoint(Endpoint endpoint, Scope scope) {
        return goTemplate("""
                $endpointType:T{
                    URI: *$uriVariableName:L,
                    $headers:W
                    $properties:W
                }
                """,
                commonCodegenArgs,
                MapUtils.of(
                        "uriVariableName", REALIZED_URL_VARIABLE_NAME,
                        "headers", generateEndpointHeaders(endpoint.getHeaders(), scope),
                        "properties", generateEndpointProperties(endpoint.getProperties(), scope)));
    }

    private Writable generateEndpointHeaders(Map<String, List<Expression>> headers, Scope scope) {
        Map<String, Object> args = MapUtils.of(
                "memberName", "Headers",
                "headerType", SymbolUtils.createPointableSymbolBuilder("Header",
                        SmithyGoDependency.NET_HTTP).build(),
                "newHeaders", SymbolUtils.createValueSymbolBuilder("Header{}",
                        SmithyGoDependency.NET_HTTP).build());

        // TODO: consider removing this line (letting it default to nil init)
        // rather than generating empty headers
        // https://github.com/aws/aws-sdk-go-v2/pull/2110/files#r1186193501
        if (headers.isEmpty()) {
            return goTemplate("Headers: $newHeaders:T,", args);
        }

        var writableHeaders = new TreeMap<String, List<Writable>>();
        var generator = new ExpressionGenerator(scope, this.fnProvider);
        headers.forEach((k, vs) -> {
            var writables = new ArrayList<Writable>();
            vs.forEach(v -> writables.add(generator.generate(v)));
            writableHeaders.put(k, writables);
        });

        return goBlockTemplate("$memberName:L: func() $headerType:T {", "}(),", args, (w) -> {
            w.writeGoTemplate("headers := $newHeaders:T", args);
            writableHeaders.forEach((k, vs) -> {
                w.write("headers.Set($W)", generateNewHeaderValue(k, vs));
            });
            w.write("return headers");
        });
    }

    private Writable generateNewHeaderValue(String headerName, List<Writable> headerValues) {
        Map<String, Object> args = MapUtils.of(
                "headerName", headerName,
                "headerValues", joinWritables(headerValues, ", "));

        if (headerValues.isEmpty()) {
            return goTemplate("$headerName:W", args);
        }

        return goTemplate("$headerName:S, $headerValues:W", args);
    }

    private Writable generateEndpointProperties(Map<Identifier, Literal> properties, Scope scope) {
        if (properties.isEmpty()) {
            return emptyGoTemplate();
        }

        var generator = new ExpressionGenerator(scope, this.fnProvider);
        return goTemplate("""
                Properties: func() $1T {
                    var out $1T
                    $2W
                    return out
                }(),
                """,
                SmithyGoTypes.Smithy.Properties,
                ChainWritable.of(
                        properties.entrySet().stream()
                                .map(it -> generateSetProperty(generator, it.getKey(), it.getValue()))
                                .toList()
                ).compose(false));
    }

    private Writable generateSetProperty(ExpressionGenerator generator, Identifier ident, Expression expr) {
        // FUTURE: add these via GoIntegration?
        return ident.toString().equals("authSchemes")
                ? new AuthSchemePropertyGenerator(generator).generate(expr)
                : goTemplate("out.Set($S, $W)", ident.toString(), generator.generate(expr));
    }

    class RuleVisitor implements RuleValueVisitor<Writable> {
        final Scope scope;
        final FnProvider fnProvider;

        RuleVisitor(Scope scope, FnProvider fnProvider) {
            this.scope = scope;
            this.fnProvider = fnProvider;
        }

        @Override
        public Writable visitTreeRule(List<Rule> rules) {
            return generateRulesList(rules, scope);
        }

        @Override
        public Writable visitErrorRule(Expression errorExpr) {
            return goTemplate("""
                    return endpoint, $fmtErrorf:T("endpoint rule error, %s", $errorExpr:W)
                    """,
                    commonCodegenArgs,
                    MapUtils.of(
                            "errorExpr", new ExpressionGenerator(scope, fnProvider).generate(errorExpr)));
        }

        @Override
        public Writable visitEndpointRule(Endpoint endpoint) {
            return goTemplate("""
                    uriString := $url:W

                    $uriVariableName:L, err := url.Parse(uriString)
                    if err != nil {
                        return endpoint, fmt.Errorf(\"Failed to parse uri: %s\", uriString)
                    }

                    return $endpoint:W, nil
                    """,
                    MapUtils.of(
                            // TODO: consider simplifying how the URI string is built
                            // look into strings.Join
                            "uriVariableName", REALIZED_URL_VARIABLE_NAME,
                            "url", new ExpressionGenerator(scope, this.fnProvider).generate(endpoint.getUrl()),
                            "endpoint", generateEndpoint(endpoint, scope)));
        }

    }

    private static boolean isConditionalFnResultOptional(Condition condition, Expression fn) {
        if (condition.getResult().isEmpty()) {
            return false;
        }

        final boolean[] isOptionalResult = {false};
        fn.accept(new ExpressionVisitor.Default<Void>() {
            @Override
            public Void getDefault() {
                return null;
            }

            @Override
            public Void visitLibraryFunction(FunctionDefinition fn, List<Expression> args) {
                isOptionalResult[0] = isFnResultOptional(fn);
                return null;
            }
        });

        return isOptionalResult[0];
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements SmithyBuilder<EndpointResolverGenerator> {
        private Symbol resolverInterfaceType;
        private Symbol resolverImplementationType;
        private Symbol newResolverFn;
        private Symbol parametersType;
        private Symbol endpointType;
        private String resolveEndpointMethodName;
        private FnProvider fnProvider;

        private Builder() {
        }

        public Builder endpointType(Symbol endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        public Builder resolverInterfaceType(Symbol resolverInterfaceType) {
            this.resolverInterfaceType = resolverInterfaceType;
            return this;
        }

        public Builder resolverImplementationType(Symbol resolverImplementationType) {
            this.resolverImplementationType = resolverImplementationType;
            return this;
        }

        public Builder newResolverFn(Symbol newResolverFn) {
            this.newResolverFn = newResolverFn;
            return this;
        }

        public Builder resolveEndpointMethodName(String resolveEndpointMethodName) {
            this.resolveEndpointMethodName = resolveEndpointMethodName;
            return this;
        }

        public Builder parametersType(Symbol parametersType) {
            this.parametersType = parametersType;
            return this;
        }

        public Builder fnProvider(FnProvider fnProvider) {
            this.fnProvider = fnProvider;
            return this;
        }

        @Override
        public EndpointResolverGenerator build() {
            return new EndpointResolverGenerator(this);
        }
    }

    private Writable generateStringSliceHelper() {
        return goTemplate("""
                type stringSlice []string

                func (s stringSlice) Get(i int) *string {
                    if i < 0 || i >= len(s) {
                        return nil
                    }

                    v := s[i]
                    return &v
                }""");
    }

    private boolean isStringSlice(Type type) {
        if (!(type instanceof ArrayType array)) {
            return false;
        }
        return array.getMember() instanceof StringType;
    }
}
