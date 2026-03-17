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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Generates a no-op implementation of the service that returns 501 Not Implemented for every operation.
 */
@SmithyInternalApi
public final class NoopServiceStruct implements Writable {
    public static final String NAME = "NoopFallbackService";

    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;

    private final OperationIndex operationIndex;

    public NoopServiceStruct(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        this.model = model;
        this.service = service;
        this.symbolProvider = symbolProvider;

        this.operationIndex = OperationIndex.of(model);
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(generateStruct());
    }

    private Writable generateStruct() {
        return goTemplate("""
                type $struct:L struct{}

                var _ $interface:L = (*$struct:L)(nil)

                $operations:W
                """,
                MapUtils.of(
                        "struct", NAME,
                        "interface", ServerInterface.NAME,
                        "operations", generateOperations()
                ));
    }

    private Writable generateOperations() {
        return ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .filter(op -> !ServerCodegenUtil.operationHasEventStream(
                            model, operationIndex.expectInputShape(op), operationIndex.expectOutputShape(op)))
                        .map(this::generateOperation)
                        .toList()
        ).compose();
    }

    private Writable generateOperation(OperationShape operation) {
        final var operationSymbol = symbolProvider.toSymbol(operation);
        return goTemplate("""
                func (*$struct:L) $operation:L($context:T, $input:P) ($output:P, error) {
                    return nil, &$notImplemented:L{$operationName:S}
                }
                """,
                MapUtils.of(
                        "struct", NAME,
                        "operation", operationSymbol.getName(),
                        "context", GoStdlibTypes.Context.Context,
                        "input", symbolProvider.toSymbol(model.expectShape(operation.getInputShape())),
                        "output", symbolProvider.toSymbol(model.expectShape(operation.getOutputShape())),
                        "notImplemented", NotImplementedError.NAME,
                        "operationName", operationSymbol.getName()
                )
        );
    }
}
