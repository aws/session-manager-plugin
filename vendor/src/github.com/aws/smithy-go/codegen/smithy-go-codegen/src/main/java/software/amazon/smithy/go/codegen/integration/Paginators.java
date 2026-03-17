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
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoValueAccessUtils;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.go.codegen.trait.PagingExtensionTrait;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.PaginatedIndex;
import software.amazon.smithy.model.knowledge.PaginationInfo;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.DocumentationTrait;

/**
 * Implements support for PaginatedTrait.
 */
public class Paginators implements GoIntegration {
    public Set<Symbol> getAdditionalClientOptions() {
        return emptySet();
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator goDelegator
    ) {
        ServiceShape serviceShape = settings.getService(model);

        PaginatedIndex paginatedIndex = PaginatedIndex.of(model);

        TopDownIndex topDownIndex = TopDownIndex.of(model);

        topDownIndex.getContainedOperations(serviceShape).stream()
                .map(operationShape -> paginatedIndex.getPaginationInfo(serviceShape, operationShape))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(paginationInfo -> {
                    goDelegator.useShapeWriter(paginationInfo.getOperation(), writer -> {
                        generateOperationPaginator(model, symbolProvider, writer, paginationInfo);
                    });
                });
    }

    private void generateOperationPaginator(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            PaginationInfo paginationInfo
    ) {
        Symbol operationSymbol = symbolProvider.toSymbol(paginationInfo.getOperation());

        Symbol interfaceSymbol = SymbolUtils.createValueSymbolBuilder(
                OperationInterfaceGenerator.getApiClientInterfaceName(operationSymbol)
        ).build();
        Symbol paginatorSymbol = SymbolUtils.createPointableSymbolBuilder(String.format("%sPaginator",
                operationSymbol.getName())).build();
        Symbol optionsSymbol = SymbolUtils.createPointableSymbolBuilder(String.format("%sOptions",
                paginatorSymbol.getName())).build();

        writePaginatorOptions(writer, model, symbolProvider, paginationInfo, operationSymbol, optionsSymbol);
        writePaginator(writer, model, symbolProvider, paginationInfo, interfaceSymbol, paginatorSymbol, optionsSymbol);
    }

    private void writePaginator(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            PaginationInfo paginationInfo,
            Symbol interfaceSymbol,
            Symbol paginatorSymbol,
            Symbol optionsSymbol
    ) {
        var inputMember = symbolProvider.toMemberName(paginationInfo.getInputTokenMember());

        var operation = paginationInfo.getOperation();
        var pagingExtensionTrait = operation.getTrait(PagingExtensionTrait.class);

        var operationSymbol = symbolProvider.toSymbol(operation);
        var inputSymbol = symbolProvider.toSymbol(paginationInfo.getInput());
        var inputTokenSymbol = symbolProvider.toSymbol(paginationInfo.getInputTokenMember());
        var inputTokenShape = model.expectShape(paginationInfo.getInputTokenMember().getTarget());

        GoPointableIndex pointableIndex = GoPointableIndex.of(model);

        writer.pushState();
        writer.putContext("paginator", paginatorSymbol);
        writer.putContext("options", optionsSymbol);
        writer.putContext("client", interfaceSymbol);
        writer.putContext("input", inputSymbol);
        writer.putContext("token", inputTokenSymbol);
        writer.putContext("inputMember", inputMember);

        writer.writeDocs(String.format("%s is a paginator for %s", paginatorSymbol, operationSymbol.getName()));
        writer.write("""
                     type $paginator:T struct {
                         options $options:T
                         client $client:T
                         params $input:P
                         nextToken $token:P
                         firstPage bool
                     }
                     """);

        Symbol newPagiantor = SymbolUtils.createValueSymbolBuilder(String.format("New%s",
                paginatorSymbol.getName())).build();

        writer.putContext("newPaginator", newPagiantor);

        writer.writeDocs(String.format("%s returns a new %s", newPagiantor.getName(), paginatorSymbol.getName()));
        writer.openBlock("func $newPaginator:T(client $client:T, params $input:P, "
                         + "optFns ...func($options:P)) $paginator:P {", "}",
                () -> {
                    writer.write("""
                                 if params == nil {
                                     params = &$input:T{}
                                 }

                                 options := $options:T{}""");
                    paginationInfo.getPageSizeMember().ifPresent(memberShape -> {
                        GoValueAccessUtils.writeIfNonZeroValueMember(model, symbolProvider, writer, memberShape,
                                "params", op -> {
                                    op = CodegenUtils.getAsValueIfDereferencable(pointableIndex, memberShape, op);
                                    writer.write("options.Limit = $L", op);
                                });

                    });
                    writer.write("""

                                 for _, fn := range optFns {
                                     fn(&options)
                                 }

                                 return &$paginator:T{
                                     options: options,
                                     client: client,
                                     params: params,
                                     firstPage: true,
                                     nextToken: params.$inputMember:L,
                                 }""");
                }).write("");

        writer.writeDocs("HasMorePages returns a boolean indicating whether more pages are available");
        writer.openBlock("func (p $paginator:P) HasMorePages() bool {", "}", () -> {
            writer.writeInline("return p.firstPage || ");
            Runnable checkNotNil = () -> writer.writeInline("p.nextToken != nil");
            if (inputTokenShape.getType() == ShapeType.STRING) {
                writer.writeInline("(");
                checkNotNil.run();
                writer.write(" && len(*p.nextToken) != 0 )");
            } else {
                checkNotNil.run();
            }
        }).write("");

        var contextSymbol = SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT)
                .build();
        var outputSymbol = symbolProvider.toSymbol(paginationInfo.getOutput());
        var pageSizeMember = paginationInfo.getPageSizeMember();

        writer.putContext("context", contextSymbol);
        writer.putContext("output", outputSymbol);

        writer.writeDocs(String.format("NextPage retrieves the next %s page.", operationSymbol.getName()));
        writer.openBlock("func (p $paginator:P) NextPage(ctx $context:T, optFns ...func(*Options)) "
                         + "($output:P, error) {", "}",
                () -> {
                    writer.putContext("errorf", SymbolUtils.createValueSymbolBuilder("Errorf",
                            SmithyGoDependency.FMT).build());
                    writer.write("""
                                 if !p.HasMorePages() {
                                     return nil, $errorf:T("no more pages available")
                                 }

                                 params := *p.params
                                 params.$inputMember:L = p.nextToken
                                 """);

                    pageSizeMember.ifPresent(memberShape -> {
                        if (pointableIndex.isPointable(memberShape)) {
                            writer.write("""
                                         var limit $P
                                         if p.options.Limit > 0 {
                                             limit = &p.options.Limit
                                         }
                                         params.$L = limit
                                         """,
                                    symbolProvider.toSymbol(memberShape),
                                    symbolProvider.toMemberName(memberShape));
                        } else {
                            writer.write("params.$L = p.options.Limit", symbolProvider.toMemberName(memberShape))
                                    .write("");
                        }
                    });

                    var optFns = ChainWritable.of(
                            getAdditionalClientOptions().stream()
                                    .map(it -> goTemplate("$T,", it))
                                    .toList()
                    ).compose(false);
                    writer.write("""
                                 optFns = append([]func(*Options) {
                                     $W
                                 }, optFns...)
                                 result, err := p.client.$L(ctx, &params, optFns...)
                                 if err != nil {
                                     return nil, err
                                 }
                                 p.firstPage = false
                                 """, optFns, operationSymbol.getName());

                    var outputMemberPath = paginationInfo.getOutputTokenMemberPath();
                    var tokenMember = outputMemberPath.get(outputMemberPath.size() - 1);
                    Consumer<String> setNextTokenFromOutput = (container) -> {
                        writer.write("p.nextToken = $L", container + "."
                                                         + symbolProvider.toMemberName(tokenMember));
                    };

                    for (int i = outputMemberPath.size() - 2; i >= 0; i--) {
                        var memberShape = outputMemberPath.get(i);
                        Consumer<String> inner = setNextTokenFromOutput;
                        setNextTokenFromOutput = (container) -> {
                            GoValueAccessUtils.writeIfNonZeroValueMember(model, symbolProvider, writer, memberShape,
                                    container, inner);
                        };
                    }

                    {
                        final Consumer<String> inner = setNextTokenFromOutput;
                        setNextTokenFromOutput = s -> {
                            if (outputMemberPath.size() > 1) {
                                writer.write("p.nextToken = nil");
                            }
                            inner.accept(s);
                        };
                    }

                    {
                        final Consumer<String> setToken = setNextTokenFromOutput;
                        writer.write("prevToken := p.nextToken");
                        Optional<MemberShape> moreResults = pagingExtensionTrait
                                .flatMap(PagingExtensionTrait::getMoreResults);

                        if (moreResults.isPresent()) {
                            MemberShape memberShape = moreResults.get();
                            model.expectShape(memberShape.getTarget(), BooleanShape.class); // Must be boolean
                            writer.write("p.nextToken = nil");
                            String memberName = symbolProvider.toMemberName(memberShape);
                            if (pointableIndex.isNillable(memberShape.getTarget())) {
                                writer.openBlock("if result.$L != nil && *result.$L {", "}", memberName, memberName,
                                        () -> setToken.accept("result"));
                            } else {
                                writer.openBlock("if result.$L {", "}", memberName, () -> setToken.accept("result"));
                            }
                        } else {
                            setToken.accept("result");
                        }
                    }
                    writer.write("");

                    if (inputTokenShape.isStringShape()) {
                        writer.write("""
                                     if p.options.StopOnDuplicateToken &&
                                         prevToken != nil &&
                                         p.nextToken != nil &&
                                         *prevToken == *p.nextToken {
                                         p.nextToken = nil
                                     }
                                     """);
                    } else {
                        writer.write("_ = prevToken").write("");
                    }

                    writer.write("return result, nil");
                });

        writer.popState();
    }

    private void writePaginatorOptions(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            PaginationInfo paginationInfo,
            Symbol operationSymbol,
            Symbol optionsSymbol
    ) {
        writer.writeDocs(String.format("%s is the paginator options for %s", optionsSymbol.getName(),
                operationSymbol.getName()));
        writer.openBlock("type $T struct {", "}", optionsSymbol, () -> {
            paginationInfo.getPageSizeMember().ifPresent(memberShape -> {
                memberShape.getMemberTrait(model, DocumentationTrait.class).ifPresent(documentationTrait -> {
                    writer.writeDocs(documentationTrait.getValue());
                });
                writer.write("Limit $T", symbolProvider.toSymbol(memberShape));
                writer.write("");
            });
            if (model.expectShape(paginationInfo.getInputTokenMember().getTarget()).isStringShape()) {
                writer.writeDocs("Set to true if pagination should stop if the service returns a pagination token that "
                                 + "matches the most recent token provided to the service.");
                writer.write("StopOnDuplicateToken bool");
            }
        });
        writer.write("");
    }
}
