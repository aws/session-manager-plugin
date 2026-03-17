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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.PaginatedIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.waiters.WaitableTrait;

/**
 * Generates API client Interfaces as per API operation.
 */
public class OperationInterfaceGenerator implements GoIntegration {

    private final Map<ShapeId, Set<ShapeId>> mapOfClientInterfaceOperations = new HashMap<>();

    /**
     * Returns name of an API client interface.
     *
     * @param operationSymbol Symbol of operation shape for which Api client interface is being generated.
     * @return name of the interface.
     */
    public static String getApiClientInterfaceName(Symbol operationSymbol) {
        return String.format("%sAPIClient", operationSymbol.getName());
    }

    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        ServiceShape serviceShape = settings.getService(model);
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        PaginatedIndex paginatedIndex = PaginatedIndex.of(model);

        Set<ShapeId> listOfClientInterfaceOperations = new TreeSet<>();

        // fetch operations for which paginators are generated
        topDownIndex.getContainedOperations(serviceShape).stream()
                .map(operationShape -> paginatedIndex.getPaginationInfo(serviceShape, operationShape))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(paginationInfo -> listOfClientInterfaceOperations.add(paginationInfo.getOperation().getId()));

        // fetch operations for which waitable trait is applied
        topDownIndex.getContainedOperations(serviceShape).stream()
                .filter(operationShape -> operationShape.hasTrait(WaitableTrait.class))
                .forEach(operationShape -> listOfClientInterfaceOperations.add(operationShape.getId()));

        if (!listOfClientInterfaceOperations.isEmpty()) {
            mapOfClientInterfaceOperations.put(serviceShape.getId(), listOfClientInterfaceOperations);
        }
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator goDelegator
    ) {
        ShapeId serviceId = settings.getService(model).getId();

        if (mapOfClientInterfaceOperations.containsKey(serviceId)) {
            Set<ShapeId> listOfClientInterfaceOperations = mapOfClientInterfaceOperations.get(serviceId);
            listOfClientInterfaceOperations.forEach(shapeId -> {
                OperationShape operationShape = model.expectShape(shapeId, OperationShape.class);
                goDelegator.useShapeWriter(operationShape, writer -> {
                    generateApiClientInterface(writer, model, symbolProvider, operationShape);
                });
            });
        }
    }

    private void generateApiClientInterface(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            OperationShape operationShape
    ) {
        Symbol contextSymbol = SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT)
                .build();

        Symbol operationSymbol = symbolProvider.toSymbol(operationShape);

        Symbol interfaceSymbol = SymbolUtils.createValueSymbolBuilder(getApiClientInterfaceName(operationSymbol))
                .build();

        Symbol inputSymbol = symbolProvider.toSymbol(model.expectShape(operationShape.getInput().get()));
        Symbol outputSymbol = symbolProvider.toSymbol(model.expectShape(operationShape.getOutput().get()));

        writer.writeDocs(String.format("%s is a client that implements the %s operation.",
                interfaceSymbol.getName(), operationSymbol.getName()));
        writer.openBlock("type $T interface {", "}", interfaceSymbol, () -> {
            writer.write("$L($T, $P, ...func(*Options)) ($P, error)", operationSymbol.getName(), contextSymbol,
                    inputSymbol, outputSymbol);
        });
        writer.write("");
        writer.write("var _ $T = (*Client)(nil)", interfaceSymbol);
        writer.write("");
    }
}
