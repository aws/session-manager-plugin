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

import static java.util.Collections.emptySet;
import static software.amazon.smithy.go.codegen.GoWriter.autoDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.isPointable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.ClientOptions;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoJmespathExpressionGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.jmespath.JmespathExpression;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.StringUtils;
import software.amazon.smithy.waiters.Acceptor;
import software.amazon.smithy.waiters.Matcher;
import software.amazon.smithy.waiters.PathComparator;
import software.amazon.smithy.waiters.WaitableTrait;
import software.amazon.smithy.waiters.Waiter;

/**
 * Implements support for WaitableTrait.
 */
public class Waiters2 implements GoIntegration {
    private static final String WAITER_INVOKER_FUNCTION_NAME = "Wait";
    private static final String WAITER_INVOKER_WITH_OUTPUT_FUNCTION_NAME = "WaitForOutput";

    public Set<Symbol> getAdditionalClientOptions() {
        return emptySet();
    }

    public boolean enabledForService(Model model, ShapeId service) {
        return true;
    }

    @Override
    public void writeAdditionalFiles(GoCodegenContext ctx) {
        if (!enabledForService(ctx.model(), ctx.settings().getService())) {
            return;
        }

        var service = ctx.settings().getService(ctx.model());

        TopDownIndex.of(ctx.model()).getContainedOperations(service).stream()
                .forEach(operation -> {
                    if (!operation.hasTrait(WaitableTrait.ID)) {
                        return;
                    }

                    Map<String, Waiter> waiters = operation.expectTrait(WaitableTrait.class).getWaiters();
                    generateOperationWaiter(ctx, operation, waiters);
                });
    }


    /**
     * Generates all waiter components used for the operation.
     */
    private void generateOperationWaiter(GoCodegenContext ctx, OperationShape operation, Map<String, Waiter> waiters) {
        var model = ctx.model();
        var symbolProvider = ctx.symbolProvider();
        ctx.writerDelegator().useShapeWriter(operation, writer -> {
            waiters.forEach((name, waiter) -> {
                generateWaiterOptions(model, symbolProvider, writer, operation, name, waiter);
                generateWaiterClient(model, symbolProvider, writer, operation, name, waiter);
                generateWaiterInvoker(model, symbolProvider, writer, operation, name, waiter);
                generateWaiterInvokerWithOutput(model, symbolProvider, writer, operation, name, waiter);
                generateRetryable(ctx, writer, operation, name, waiter);
            });
        });
    }

    /**
     * Generates waiter options to configure a waiter client.
     */
    private void generateWaiterOptions(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName,
            Waiter waiter
    ) {
        String optionsName = generateWaiterOptionsName(waiterName);
        String waiterClientName = generateWaiterClientName(waiterName);

        StructureShape inputShape = model.expectShape(
                operationShape.getInput().get(), StructureShape.class
        );
        StructureShape outputShape = model.expectShape(
                operationShape.getOutput().get(), StructureShape.class
        );

        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        Symbol outputSymbol = symbolProvider.toSymbol(outputShape);

        writer.write("");
        writer.writeDocs(
                String.format("%s are waiter options for %s", optionsName, waiterClientName)
        );

        writer.openBlock("type $L struct {", "}",
                optionsName, () -> {
                    writer.addUseImports(SmithyGoDependency.TIME);

                    writer.write("");
                    var apiOptionsDocs = autoDocTemplate("""
                            Set of options to modify how an operation is invoked. These apply to all operations invoked
                            for this client. Use functional options on operation call to modify this list for per
                            operation behavior.

                            Passing options here is functionally equivalent to passing values to this config's
                            ClientOptions field that extend the inner client's APIOptions directly.""");
                    Symbol stackSymbol = SymbolUtils.createPointableSymbolBuilder("Stack",
                            SmithyGoDependency.SMITHY_MIDDLEWARE)
                            .build();
                    writer.write(goTemplate("""
                            $W
                            APIOptions []func($P) error
                            """, apiOptionsDocs, stackSymbol));

                    var clientOptionsDocs = autoDocTemplate("""
                            Functional options to be passed to all operations invoked by this client.

                            Function values that modify the inner APIOptions are applied after the waiter config's own
                            APIOptions modifiers.""");
                    writer.write("");
                    writer.write(goTemplate("""
                            $W
                            ClientOptions []func(*$L)
                            """, clientOptionsDocs, ClientOptions.NAME));

                    writer.write("");
                    writer.writeDocs(
                            String.format("MinDelay is the minimum amount of time to delay between retries. "
                                    + "If unset, %s will use default minimum delay of %s seconds. "
                                    + "Note that MinDelay must resolve to a value lesser than or equal "
                                    + "to the MaxDelay.", waiterClientName, waiter.getMinDelay())
                    );
                    writer.write("MinDelay time.Duration");

                    writer.write("");
                    writer.writeDocs(
                            String.format("MaxDelay is the maximum amount of time to delay between retries. "
                                    + "If unset or set to zero, %s will use default max delay of %s seconds. "
                                    + "Note that MaxDelay must resolve to value greater than or equal "
                                    + "to the MinDelay.", waiterClientName, waiter.getMaxDelay())
                    );
                    writer.write("MaxDelay time.Duration");

                    writer.write("");
                    writer.writeDocs("LogWaitAttempts is used to enable logging for waiter retry attempts");
                    writer.write("LogWaitAttempts bool");

                    writer.write("");
                    writer.writeDocs(
                            "Retryable is function that can be used to override the "
                                    + "service defined waiter-behavior based on operation output, or returned error. "
                                    + "This function is used by the waiter to decide if a state is retryable "
                                    + "or a terminal state.\n\nBy default service-modeled logic "
                                    + "will populate this option. This option can thus be used to define a custom "
                                    + "waiter state with fall-back to service-modeled waiter state mutators."
                                    + "The function returns an error in case of a failure state. "
                                    + "In case of retry state, this function returns a bool value of true and "
                                    + "nil error, while in case of success it returns a bool value of false and "
                                    + "nil error."
                    );
                    writer.write(
                            "Retryable func(context.Context, $P, $P, error) "
                                    + "(bool, error)", inputSymbol, outputSymbol);
                }
        );
        writer.write("");
    }


    /**
     * Generates waiter client used to invoke waiter function. The waiter client is specific to a modeled waiter.
     * Each waiter client is unique within a enclosure of a service.
     * This function also generates a waiter client constructor that takes in a API client interface, and waiter options
     * to configure a waiter client.
     */
    private void generateWaiterClient(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName,
            Waiter waiter
    ) {
        Symbol operationSymbol = symbolProvider.toSymbol(operationShape);
        String clientName = generateWaiterClientName(waiterName);

        writer.write("");
        writer.writeDocs(
                String.format("%s defines the waiters for %s", clientName, waiterName)
        );
        writer.openBlock("type $L struct {", "}",
                clientName, () -> {
                    writer.write("");
                    writer.write("client $L", OperationInterfaceGenerator.getApiClientInterfaceName(operationSymbol));

                    writer.write("");
                    writer.write("options $L", generateWaiterOptionsName(waiterName));
                });

        writer.write("");

        String constructorName = String.format("New%s", clientName);

        Symbol waiterOptionsSymbol = SymbolUtils.createPointableSymbolBuilder(
                generateWaiterOptionsName(waiterName)
        ).build();

        Symbol clientSymbol = SymbolUtils.createPointableSymbolBuilder(
                clientName
        ).build();

        writer.writeDocs(
                String.format("%s constructs a %s.", constructorName, clientName)
        );
        writer.openBlock("func $L(client $L, optFns ...func($P)) $P {", "}",
                constructorName, OperationInterfaceGenerator.getApiClientInterfaceName(operationSymbol),
                waiterOptionsSymbol, clientSymbol, () -> {
                    writer.write("options := $T{}", waiterOptionsSymbol);
                    writer.addUseImports(SmithyGoDependency.TIME);

                    // set defaults
                    writer.write("options.MinDelay = $L * time.Second", waiter.getMinDelay());
                    writer.write("options.MaxDelay = $L * time.Second", waiter.getMaxDelay());
                    writer.write("options.Retryable = $L", generateRetryableName(waiterName));
                    writer.write("");

                    writer.openBlock("for _, fn := range optFns {",
                            "}", () -> {
                                writer.write("fn(&options)");
                            });

                    writer.openBlock("return &$T {", "}", clientSymbol, () -> {
                        writer.write("client: client, ");
                        writer.write("options: options, ");
                    });
                });
    }

    /**
     * Generates waiter invoker functions to call specific operation waiters
     * These waiter invoker functions is defined on each modeled waiter client.
     * The invoker function takes in a context, along with operation input, and
     * optional functional options for the waiter.
     */
    private void generateWaiterInvoker(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName,
            Waiter waiter
    ) {
        StructureShape inputShape = model.expectShape(
                operationShape.getInput().get(), StructureShape.class
        );

        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);

        Symbol waiterOptionsSymbol = SymbolUtils.createPointableSymbolBuilder(
                generateWaiterOptionsName(waiterName)
        ).build();

        Symbol clientSymbol = SymbolUtils.createPointableSymbolBuilder(
                generateWaiterClientName(waiterName)
        ).build();

        writer.write("");
        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.addUseImports(SmithyGoDependency.TIME);
        writer.writeDocs(
                String.format(
                        "%s calls the waiter function for %s waiter. The maxWaitDur is the maximum wait duration "
                                + "the waiter will wait. The maxWaitDur is required and must be greater than zero.",
                        WAITER_INVOKER_FUNCTION_NAME, waiterName)
        );
        writer.openBlock(
                "func (w $P) $L(ctx context.Context, params $P, maxWaitDur time.Duration, optFns ...func($P)) error {",
                "}",
                clientSymbol, WAITER_INVOKER_FUNCTION_NAME, inputSymbol, waiterOptionsSymbol,
                () -> {
                    writer.write(
                            "_, err := w.$L(ctx, params, maxWaitDur, optFns...)",
                            WAITER_INVOKER_WITH_OUTPUT_FUNCTION_NAME
                    );

                    writer.write("return err");
                });
    }

    /**
     * Generates waiter invoker functions to call specific operation waiters
     * and return the output of the successful operation.
     * These waiter invoker functions is defined on each modeled waiter client.
     * The invoker function takes in a context, along with operation input, and
     * optional functional options for the waiter.
     */
    private void generateWaiterInvokerWithOutput(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName,
            Waiter waiter
    ) {
        StructureShape inputShape = model.expectShape(
                operationShape.getInput().get(), StructureShape.class
        );

        StructureShape outputShape = model.expectShape(
                operationShape.getOutput().get(), StructureShape.class
        );

        Symbol operationSymbol = symbolProvider.toSymbol(operationShape);
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        Symbol outputSymbol = symbolProvider.toSymbol(outputShape);

        Symbol waiterOptionsSymbol = SymbolUtils.createPointableSymbolBuilder(
                generateWaiterOptionsName(waiterName)
        ).build();

        Symbol clientSymbol = SymbolUtils.createPointableSymbolBuilder(
                generateWaiterClientName(waiterName)
        ).build();

        writer.write("");
        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.addUseImports(SmithyGoDependency.TIME);
        writer.writeDocs(
                String.format(
                        "%s calls the waiter function for %s waiter and returns the output of the successful "
                                + "operation. The maxWaitDur is the maximum wait duration the waiter will wait. The "
                                + "maxWaitDur is required and must be greater than zero.",
                        WAITER_INVOKER_WITH_OUTPUT_FUNCTION_NAME, waiterName)
        );
        writer.openBlock(
                "func (w $P) $L(ctx context.Context, params $P, maxWaitDur time.Duration, optFns ...func($P)) "
                        + "($P, error) {",
                "}",
                clientSymbol, WAITER_INVOKER_WITH_OUTPUT_FUNCTION_NAME, inputSymbol, waiterOptionsSymbol, outputSymbol,
                () -> {
                    writer.openBlock("if maxWaitDur <= 0 {", "}", () -> {
                        writer.addUseImports(SmithyGoDependency.FMT);
                        writer.write(
                                "return nil, fmt.Errorf(\"maximum wait time for waiter must be greater than zero\")"
                        );
                    }).write("");

                    writer.write("options := w.options");

                    writer.openBlock("for _, fn := range optFns {",
                            "}", () -> {
                                writer.write("fn(&options)");
                            });
                    writer.write("");

                    // validate values for MaxDelay from options
                    writer.openBlock("if options.MaxDelay <= 0 {", "}", () -> {
                        writer.write("options.MaxDelay = $L * time.Second", waiter.getMaxDelay());
                    });
                    writer.write("");

                    // validate that MinDelay is lesser than or equal to resolved MaxDelay
                    writer.openBlock("if options.MinDelay > options.MaxDelay {", "}", () -> {
                        writer.addUseImports(SmithyGoDependency.FMT);
                        writer.write("return nil, fmt.Errorf(\"minimum waiter delay %v must be lesser than or equal to "
                                + "maximum waiter delay of %v.\", options.MinDelay, options.MaxDelay)");
                    }).write("");

                    writer.addUseImports(SmithyGoDependency.CONTEXT);
                    writer.write("ctx, cancelFn := context.WithTimeout(ctx, maxWaitDur)");
                    writer.write("defer cancelFn()");
                    writer.write("");

                    Symbol loggerMiddleware = SymbolUtils.createValueSymbolBuilder(
                            "Logger", SmithyGoDependency.SMITHY_WAITERS
                    ).build();
                    writer.write("logger := $T{}", loggerMiddleware);
                    writer.write("remainingTime := maxWaitDur").write("");

                    writer.write("var attempt int64");
                    writer.openBlock("for {", "}", () -> {
                        writer.write("");
                        writer.write("attempt++");

                        writer.write("apiOptions := options.APIOptions");
                        writer.write("start := time.Now()").write("");

                        // add waiter logger middleware to log an attempt, if LogWaitAttempts is enabled.
                        writer.openBlock("if options.LogWaitAttempts {", "}", () -> {
                            writer.write("logger.Attempt = attempt");
                            writer.write(
                                    "apiOptions = append([]func(*middleware.Stack) error{}, options.APIOptions...)");
                            writer.write("apiOptions = append(apiOptions, logger.AddLogger)");
                        }).write("");

                        // make a request
                        var baseOpts = ChainWritable.of(
                                getAdditionalClientOptions().stream()
                                        .map(it -> goTemplate("$T,", it))
                                        .toList()
                        ).compose(false);
                        writer.openBlock("out, err := w.client.$T(ctx, params, func (o *Options) { ", "})",
                                operationSymbol, () -> {
                                    writer.write("""
                                            baseOpts := []func(*Options) {
                                                $W
                                            }""", baseOpts);
                                    writer.write("o.APIOptions = append(o.APIOptions, apiOptions...)");
                                    writer.write("""
                                            for _, opt := range baseOpts {
                                                opt(o)
                                            }
                                            for _, opt := range options.ClientOptions {
                                                opt(o)
                                            }""");
                                });
                        writer.write("");

                        // handle response and identify waiter state
                        writer.write("retryable, err := options.Retryable(ctx, params, out, err)");
                        writer.write("if err != nil { return nil, err }");
                        writer.write("if !retryable { return out, nil }").write("");

                        // update remaining time
                        writer.write("remainingTime -= time.Since(start)");

                        // check if next iteration is possible
                        writer.openBlock("if remainingTime < options.MinDelay || remainingTime <= 0 {", "}", () -> {
                            writer.write("break");
                        });
                        writer.write("");

                        // handle retry delay computation, sleep.
                        Symbol computeDelaySymbol = SymbolUtils.createValueSymbolBuilder(
                                "ComputeDelay", SmithyGoDependency.SMITHY_WAITERS
                        ).build();
                        writer.writeDocs("compute exponential backoff between waiter retries");
                        writer.openBlock("delay, err := $T(", ")", computeDelaySymbol, () -> {
                            writer.write("attempt, options.MinDelay, options.MaxDelay, remainingTime,");
                        });

                        writer.addUseImports(SmithyGoDependency.FMT);
                        writer.write(
                                "if err != nil { return nil, fmt.Errorf(\"error computing waiter delay, %w\", err)}");
                        writer.write("");

                        // update remaining time as per computed delay
                        writer.write("remainingTime -= delay");

                        // sleep for delay
                        Symbol sleepWithContextSymbol = SymbolUtils.createValueSymbolBuilder(
                                "SleepWithContext", SmithyGoDependency.SMITHY_TIME
                        ).build();
                        writer.writeDocs("sleep for the delay amount before invoking a request");
                        writer.openBlock("if err := $T(ctx, delay); err != nil {", "}", sleepWithContextSymbol,
                                () -> {
                                    writer.write(
                                            "return nil, fmt.Errorf(\"request cancelled while waiting, %w\", err)");
                                });
                    });
                    writer.write("return nil, fmt.Errorf(\"exceeded max wait time for $L waiter\")", waiterName);
                });
    }

    /**
     * Generates a waiter state mutator function which is used by the waiter retrier Middleware to mutate
     * waiter state as per the defined logic and returned operation response.
     *
     * @param ctx            the GoCodegenContext
     * @param writer         the Gowriter
     * @param operationShape operation shape on which the waiter is modeled
     * @param waiterName     the waiter name
     * @param waiter         the waiter structure that contains info on modeled waiter
     */
    private void generateRetryable(
            GoCodegenContext ctx,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName,
            Waiter waiter
    ) {
        var model = ctx.model();
        var symbolProvider = ctx.symbolProvider();
        var serviceShape = ctx.settings().getService(model);
        StructureShape inputShape = model.expectShape(
                operationShape.getInput().get(), StructureShape.class
        );
        StructureShape outputShape = model.expectShape(
                operationShape.getOutput().get(), StructureShape.class
        );

        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        Symbol outputSymbol = symbolProvider.toSymbol(outputShape);

        writer.write("");
        writer.openBlock("func $L(ctx context.Context, input $P, output $P, err error) (bool, error) {",
                "}", generateRetryableName(waiterName), inputSymbol, outputSymbol, () -> {
                    waiter.getAcceptors().forEach(acceptor -> {
                        writer.write("");
                        // scope each acceptor to avoid name collisions
                        Matcher matcher = acceptor.getMatcher();
                        switch (matcher.getMemberName()) {
                            case "output":
                                writer.addUseImports(SmithyGoDependency.FMT);

                                Matcher.OutputMember outputMember = (Matcher.OutputMember) matcher;
                                String path = outputMember.getValue().getPath();
                                String expectedValue = outputMember.getValue().getExpected();
                                PathComparator comparator = outputMember.getValue().getComparator();
                                writer.openBlock("if err == nil {", "}", () -> {
                                    var pathInput = new GoJmespathExpressionGenerator.Variable(outputShape, "output");
                                    var searchResult = new GoJmespathExpressionGenerator(ctx, writer)
                                            .generate(JmespathExpression.parse(path), pathInput);

                                    writer.write("expectedValue := $S", expectedValue);
                                    writeWaiterComparator(writer, acceptor, comparator, searchResult);
                                });
                                break;

                            case "inputOutput":
                                writer.addUseImports(SmithyGoDependency.FMT);

                                Matcher.InputOutputMember ioMember = (Matcher.InputOutputMember) matcher;
                                path = ioMember.getValue().getPath();
                                expectedValue = ioMember.getValue().getExpected();
                                comparator = ioMember.getValue().getComparator();

                                // inputOutput matchers operate on a synthetic structure with operation input and output
                                // as top-level fields - we set that up here both in codegen for jmespathing and for
                                // the actual generated code to work
                                var inputOutputShape = StructureShape.builder()
                                        .addMember("input", inputShape.toShapeId())
                                        .addMember("output", outputShape.toShapeId())
                                        .build();
                                writer.write("""
                                        inputOutput := struct{
                                            Input  $P
                                            Output $P
                                        }{
                                            Input:  input,
                                            Output: output,
                                        }
                                        """);

                                writer.openBlock("if err == nil {", "}", () -> {
                                    var pathInput = new GoJmespathExpressionGenerator.Variable(
                                            inputOutputShape, "inputOutput");
                                    var searchResult = new GoJmespathExpressionGenerator(ctx, writer)
                                            .generate(JmespathExpression.parse(path), pathInput);

                                    writer.write("expectedValue := $S", expectedValue);
                                    writeWaiterComparator(writer, acceptor, comparator, searchResult);
                                });
                                break;

                            case "success":
                                Matcher.SuccessMember successMember = (Matcher.SuccessMember) matcher;
                                writer.openBlock("if err == nil {", "}",
                                        () -> {
                                            writeMatchedAcceptorReturn(writer, acceptor);
                                        });
                                break;

                            case "errorType":
                                Matcher.ErrorTypeMember errorTypeMember = (Matcher.ErrorTypeMember) matcher;
                                String errorType = errorTypeMember.getValue();

                                writer.openBlock("if err != nil {", "}", () -> {

                                    // identify if this is a modeled error shape
                                    Optional<ShapeId> errorShapeId = operationShape.getErrors().stream().filter(
                                            shapeId -> {
                                                return shapeId.getName(serviceShape).equalsIgnoreCase(errorType);
                                            }).findFirst();

                                    // if modeled error shape
                                    if (errorShapeId.isPresent()) {
                                        Shape errorShape = model.expectShape(errorShapeId.get());
                                        Symbol modeledErrorSymbol = symbolProvider.toSymbol(errorShape);
                                        writer.addUseImports(SmithyGoDependency.ERRORS);
                                        writer.write("var errorType *$T", modeledErrorSymbol);
                                        writer.openBlock("if errors.As(err, &errorType) {", "}", () -> {
                                            writeMatchedAcceptorReturn(writer, acceptor);
                                        });
                                    } else {
                                        // fall back to un-modeled error shape matching
                                        writer.addUseImports(SmithyGoDependency.SMITHY);
                                        writer.addUseImports(SmithyGoDependency.ERRORS);

                                        // assert unmodeled error to smithy's API error
                                        writer.write("var apiErr smithy.APIError");
                                        writer.write("ok := errors.As(err, &apiErr)");
                                        writer.openBlock("if !ok {", "}", () -> {
                                            writer.write("return false, "
                                                    + "fmt.Errorf(\"expected err to be of type smithy.APIError, "
                                                    + "got %w\", err)");
                                        });
                                        writer.write("");

                                        writer.openBlock("if $S == apiErr.ErrorCode() {", "}",
                                                errorType, () -> {
                                                    writeMatchedAcceptorReturn(writer, acceptor);
                                                });
                                    }
                                });
                                break;

                            default:
                                throw new CodegenException(
                                        String.format("unknown waiter state : %v", matcher.getMemberName())
                                );
                        }
                    });

                    writer.write("");
                    writer.write("if err != nil { return false, err }");
                    writer.write("return true, nil");
                });
    }

    private void writeWaiterComparator(GoWriter writer, Acceptor acceptor, PathComparator comparator,
                                       GoJmespathExpressionGenerator.Variable searchResult) {
        switch (comparator) {
            case STRING_EQUALS:
                writer.write("var pathValue string");
                if (!isPointable(searchResult.type())) {
                    writer.write("pathValue = string($L)", searchResult.ident());
                } else {
                    writer.write("""
                            if $1L != nil {
                                pathValue = string(*$1L)
                            }""", searchResult.ident());
                }
                writer.openBlock("if pathValue == expectedValue {", "}", () -> {
                    writeMatchedAcceptorReturn(writer, acceptor);
                });
                break;

            case BOOLEAN_EQUALS:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("bv, err := strconv.ParseBool($L)", "expectedValue");
                writer.write(
                        "if err != nil { return false, "
                                + "fmt.Errorf(\"error parsing boolean from string %w\", err)}");

                writer.openBlock("if $L == bv {", "}", searchResult.ident(), () -> {
                    writeMatchedAcceptorReturn(writer, acceptor);
                });
                break;

            case ALL_STRING_EQUALS:
                writer.write("match := len($L) > 0", searchResult.ident());
                writer.openBlock("for _, v := range $L {", "}", searchResult.ident(), () -> {
                    writer.write("""
                        if string(v) != expectedValue {
                            match = false
                            break
                        }""");
                });
                writer.write("");

                writer.openBlock("if match {", "}", () -> {
                    writeMatchedAcceptorReturn(writer, acceptor);
                });
                break;

            case ANY_STRING_EQUALS:
                writer.write("var match bool");
                writer.openBlock("for _, v := range $L {", "}", searchResult.ident(), () -> {
                    writer.write("""
                        if string(v) == expectedValue {
                            match = true
                            break
                        }""");
                });
                writer.write("");

                writer.openBlock("if match {", "}", () -> {
                    writeMatchedAcceptorReturn(writer, acceptor);
                });
                break;

            default:
                throw new CodegenException(
                        String.format("Found unknown waiter path comparator, %s", comparator.toString()));
        }
    }


    /**
     * Writes return statement for state where a waiter's acceptor state is a match.
     *
     * @param writer   the Go writer
     * @param acceptor the waiter acceptor who's state is used to write an appropriate return statement.
     */
    private void writeMatchedAcceptorReturn(GoWriter writer, Acceptor acceptor) {
        switch (acceptor.getState()) {
            case SUCCESS:
                writer.write("return false, nil");
                break;

            case FAILURE:
                writer.addUseImports(SmithyGoDependency.FMT);
                writer.write("return false, fmt.Errorf(\"waiter state transitioned to Failure\")");
                break;

            case RETRY:
                writer.write("return true, nil");
                break;

            default:
                throw new CodegenException("unknown acceptor state defined for the waiter");
        }
    }

    private String generateWaiterOptionsName(
            String waiterName
    ) {
        waiterName = StringUtils.capitalize(waiterName);
        return String.format("%sWaiterOptions", waiterName);
    }

    private String generateWaiterClientName(
            String waiterName
    ) {
        waiterName = StringUtils.capitalize(waiterName);
        return String.format("%sWaiter", waiterName);
    }

    private String generateRetryableName(
            String waiterName
    ) {
        waiterName = StringUtils.uncapitalize(waiterName);
        return String.format("%sStateRetryable", waiterName);
    }
}
