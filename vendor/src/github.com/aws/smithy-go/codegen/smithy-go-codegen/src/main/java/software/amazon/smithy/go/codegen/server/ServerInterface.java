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
 * Generates the interface that describes the service API.
 */
@SmithyInternalApi
public final class ServerInterface implements Writable {
    public static final String NAME = "Service";

    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;
    private final OperationIndex operationIndex;

    public ServerInterface(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        this.model = model;
        this.service = service;
        this.symbolProvider = symbolProvider;

        this.operationIndex = OperationIndex.of(model);
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(generateInterface());
    }

    private Writable generateInterface() {
        return goTemplate("""
                type $L interface {
                    $W
                }
                """, NAME, generateOperations());
    }

    private Writable generateOperations() {
        return ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .filter(op -> !ServerCodegenUtil.operationHasEventStream(
                            model, operationIndex.expectInputShape(op), operationIndex.expectOutputShape(op)))
                        .map(this::generateOperation)
                        .toList()
        ).compose(false);
    }

    private Writable generateOperation(OperationShape operation) {
        return goTemplate(
                "$operation:L($context:T, $input:P) ($output:P, error)",
                MapUtils.of(
                        "operation", symbolProvider.toSymbol(operation).getName(),
                        "context", GoStdlibTypes.Context.Context,
                        "input", symbolProvider.toSymbol(model.expectShape(operation.getInputShape())),
                        "output", symbolProvider.toSymbol(model.expectShape(operation.getOutputShape()))
                )
        );
    }
}
