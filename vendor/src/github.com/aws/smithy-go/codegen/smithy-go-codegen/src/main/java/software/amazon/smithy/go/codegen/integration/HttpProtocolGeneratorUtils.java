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

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;

public final class HttpProtocolGeneratorUtils {

    private HttpProtocolGeneratorUtils() {
    }

    /**
     * Generates a function that handles error deserialization by getting the error code then
     * dispatching to the error-specific deserializer.
     * <p>
     * If the error code does not map to a known error, a generic error will be returned using
     * the error code and error message discovered in the response.
     * <p>
     * The default error message and code are both "UnknownError".
     *
     * @param context                   The generation context.
     * @param operation                 The operation to generate for.
     * @param responseType              The response type for the HTTP protocol.
     * @param errorMessageCodeGenerator A consumer that generates a snippet that sets the {@code errorCode}
     *                                  and {@code errorMessage} variables from the http response.
     * @return A set of all error structure shapes for the operation that were dispatched to.
     */
    public static Set<StructureShape> generateErrorDispatcher(
            GenerationContext context,
            OperationShape operation,
            Symbol responseType,
            Consumer<GenerationContext> errorMessageCodeGenerator,
            BiFunction<GenerationContext, OperationShape, Map<String, ShapeId>> operationErrorsToShapes
    ) {
        return generateErrorDispatcher(
            context, operation, responseType,
            errorMessageCodeGenerator, operationErrorsToShapes, writer -> defaultBlock(writer));
    }

    private static void defaultBlock(GoWriter writer) {
        writer.openBlock("default:", "", () -> {
            writer.openBlock("genericError := &smithy.GenericAPIError{", "}", () -> {
                writer.write("Code: errorCode,");
                writer.write("Message: errorMessage,");
            });
            writer.write("return genericError");
        });
    }

    @FunctionalInterface
    public interface DefaultBlockWriter {
        void write(GoWriter writer);
    }

    /**
     * Generates a function that handles error deserialization by getting the error code then
     * dispatching to the error-specific deserializer. Provies an option to write custom default
     * error block.
     */
    public static Set<StructureShape> generateErrorDispatcher(
            GenerationContext context,
            OperationShape operation,
            Symbol responseType,
            Consumer<GenerationContext> errorMessageCodeGenerator,
            BiFunction<GenerationContext, OperationShape, Map<String, ShapeId>> operationErrorsToShapes,
            DefaultBlockWriter defaultBlockWriter
    ) {
        GoWriter writer = context.getWriter().get();
        ServiceShape service = context.getService();
        String protocolName = context.getProtocolName();
        Set<StructureShape> errorShapes = new TreeSet<>();

        String errorFunctionName = ProtocolGenerator.getOperationErrorDeserFunctionName(
                operation, service, protocolName);

        writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
        writer.openBlock("func $L(response $P, metadata *middleware.Metadata) error {", "}",
                errorFunctionName, responseType, () -> {
                    writer.addUseImports(SmithyGoDependency.BYTES);
                    writer.addUseImports(SmithyGoDependency.IO);

                    // Copy the response body into a seekable type
                    writer.write("var errorBuffer bytes.Buffer");
                    writer.openBlock("if _, err := io.Copy(&errorBuffer, response.Body); err != nil {", "}", () -> {
                        writer.write("return &smithy.DeserializationError{Err: fmt.Errorf("
                                + "\"failed to copy error response body, %w\", err)}");
                    });
                    writer.write("errorBody := bytes.NewReader(errorBuffer.Bytes())");
                    writer.write("");

                    // Set the default values for code and message.
                    writer.write("errorCode := \"UnknownError\"");
                    writer.write("errorMessage := errorCode");
                    writer.write("");

                    // Dispatch to the message/code generator to try to get the specific code and message.
                    errorMessageCodeGenerator.accept(context);

                    writer.openBlock("switch {", "}", () -> {
                        operationErrorsToShapes.apply(context, operation).forEach((name, errorId) -> {
                            StructureShape error = context.getModel().expectShape(errorId).asStructureShape().get();
                            errorShapes.add(error);
                            String errorDeserFunctionName = ProtocolGenerator.getErrorDeserFunctionName(
                                    error, service, protocolName);
                            writer.addUseImports(SmithyGoDependency.STRINGS);
                            writer.openBlock("case strings.EqualFold($S, errorCode):", "", name, () -> {
                                writer.write("return $L(response, errorBody)", errorDeserFunctionName);
                            });
                        });

                        // Create a generic error
                        writer.addUseImports(SmithyGoDependency.SMITHY);
                        defaultBlockWriter.write(writer);
                    });
                }).write("");

        return errorShapes;
    }

    /**
     * Returns whether a shape has response bindings for the provided HttpBinding location.
     * The shape can be an operation shape, error shape or an output shape.
     *
     * @param model    the model
     * @param shape    the shape with possible presence of response bindings
     * @param location the HttpBinding location for response binding
     * @return boolean indicating presence of response bindings in the shape for provided location
     */
    public static boolean isShapeWithResponseBindings(Model model, Shape shape, HttpBinding.Location location) {
        Collection<HttpBinding> bindings = HttpBindingIndex.of(model)
                .getResponseBindings(shape).values();

        for (HttpBinding binding : bindings) {
            if (binding.getLocation() == location) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a map of error names to their {@link ShapeId}.
     *
     * @param context   the generation context
     * @param operation the operation shape to retrieve errors for
     * @return map of error names to {@link ShapeId}
     */
    public static Map<String, ShapeId> getOperationErrors(GenerationContext context, OperationShape operation) {
        return operation.getErrors().stream()
                .collect(Collectors.toMap(
                        shapeId -> shapeId.getName(context.getService()),
                        Function.identity(),
                        (x, y) -> {
                            if (!x.equals(y)) {
                                throw new CodegenException(String.format("conflicting error shape ids: %s, %s", x, y));
                            }
                            return x;
                        }, TreeMap::new));
    }
}
