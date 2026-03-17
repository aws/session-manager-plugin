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

import static software.amazon.smithy.go.codegen.server.ServerCodegenUtil.isUnit;
import static software.amazon.smithy.go.codegen.server.ServerCodegenUtil.withUnit;

import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.WriterDelegator;
import software.amazon.smithy.codegen.core.directed.CodegenDirector;
import software.amazon.smithy.codegen.core.directed.CreateContextDirective;
import software.amazon.smithy.codegen.core.directed.CreateSymbolProviderDirective;
import software.amazon.smithy.codegen.core.directed.DirectedCodegen;
import software.amazon.smithy.codegen.core.directed.GenerateEnumDirective;
import software.amazon.smithy.codegen.core.directed.GenerateErrorDirective;
import software.amazon.smithy.codegen.core.directed.GenerateIntEnumDirective;
import software.amazon.smithy.codegen.core.directed.GenerateStructureDirective;
import software.amazon.smithy.codegen.core.directed.GenerateUnionDirective;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.IntEnumShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public abstract class AbstractDirectedCodegen implements DirectedCodegen<GoCodegenContext, GoSettings, GoIntegration> {
    public static void run(PluginContext context, DirectedCodegen<GoCodegenContext, GoSettings, GoIntegration> directedCodegen) {
        CodegenDirector<GoWriter,
                GoIntegration,
                GoCodegenContext,
                GoSettings> runner = new CodegenDirector<>();

        runner.model(context.getModel());
        runner.directedCodegen(directedCodegen);

        runner.integrationClass(GoIntegration.class);

        runner.fileManifest(context.getFileManifest());

        GoSettings settings = runner.settings(GoSettings.class,
                context.getSettings());

        runner.service(settings.getService());

        runner.performDefaultCodegenTransforms();
        runner.createDedicatedInputsAndOutputs();
        runner.changeStringEnumsToEnumShapes(false);

        runner.run();
    }

    @Override
    public final SymbolProvider createSymbolProvider(CreateSymbolProviderDirective<GoSettings> directive) {
        return new SymbolVisitor(withUnit(directive.model()), directive.settings());
    }

    @Override
    public final GoCodegenContext createContext(CreateContextDirective<GoSettings, GoIntegration> directive) {
        return new GoCodegenContext(
                withUnit(directive.model()),
                directive.settings(),
                directive.symbolProvider(),
                directive.fileManifest(),
                new WriterDelegator<>(directive.fileManifest(), directive.symbolProvider(),
                        (filename, namespace) -> new GoWriter(namespace)),
                directive.integrations()
        );
    }

    @Override
    public void generateStructure(GenerateStructureDirective<GoCodegenContext, GoSettings> directive) {
        if (isUnit(directive.shape().getId())) {
            return;
        }

        var delegator = directive.context().writerDelegator();
        delegator.useShapeWriter(directive.shape(), writer ->
                new StructureGenerator(
                        directive.model(),
                        directive.symbolProvider(),
                        writer,
                        directive.service(),
                        directive.shape(),
                        directive.symbolProvider().toSymbol(directive.shape()),
                        null
                ).run()
        );
    }

    @Override
    public void generateError(GenerateErrorDirective<GoCodegenContext, GoSettings> directive) {
        var delegator = directive.context().writerDelegator();
        delegator.useShapeWriter(directive.shape(), writer ->
                new StructureGenerator(
                        directive.model(),
                        directive.symbolProvider(),
                        writer,
                        directive.service(),
                        directive.shape(),
                        directive.symbolProvider().toSymbol(directive.shape()),
                        null
                ).run()
        );
    }

    @Override
    public void generateUnion(GenerateUnionDirective<GoCodegenContext, GoSettings> directive) {
        var delegator = directive.context().writerDelegator();
        delegator.useShapeWriter(directive.shape(), writer ->
                new UnionGenerator(directive.model(), directive.symbolProvider(), directive.shape())
                        .generateUnion(writer)
        );
    }

    @Override
    public void generateEnumShape(GenerateEnumDirective<GoCodegenContext, GoSettings> directive) {
        var delegator = directive.context().writerDelegator();
        delegator.useShapeWriter(directive.shape(), writer ->
                new EnumGenerator(directive.symbolProvider(), writer, (EnumShape) directive.shape()).run()
        );
    }

    @Override
    public void generateIntEnumShape(GenerateIntEnumDirective<GoCodegenContext, GoSettings> directive) {
        directive.context().writerDelegator().useShapeWriter(directive.shape(), writer ->
                new IntEnumGenerator(directive.symbolProvider(), writer, (IntEnumShape) directive.shape()).run()
        );
    }
}
