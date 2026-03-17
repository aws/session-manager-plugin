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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoSettings.ArtifactType;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.IntEnumShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.transform.ModelTransformer;
import software.amazon.smithy.utils.OptionalUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Orchestrates Go client generation.
 */
@SmithyInternalApi
final class CodegenVisitor extends ShapeVisitor.Default<Void> {

    private static final Logger LOGGER = Logger.getLogger(CodegenVisitor.class.getName());

    private final GoSettings settings;
    private final Model model;
    private final Model modelWithoutTraitShapes;
    private final ServiceShape service;
    private final FileManifest fileManifest;
    private final SymbolProvider symbolProvider;
    private final GoDelegator writers;
    private final List<GoIntegration> integrations = new ArrayList<>();
    private final ProtocolGenerator protocolGenerator;
    private final ApplicationProtocol applicationProtocol;
    private final List<RuntimeClientPlugin> runtimePlugins = new ArrayList<>();
    private final ProtocolDocumentGenerator protocolDocumentGenerator;
    private final EventStreamGenerator eventStreamGenerator;
    private final GoCodegenContext ctx;

    CodegenVisitor(PluginContext context) {
        // Load all integrations.
        ClassLoader loader = context.getPluginClassLoader().orElse(getClass().getClassLoader());
        LOGGER.info("Attempting to discover GoIntegration from the classpath...");
        ServiceLoader.load(GoIntegration.class, loader)
                .forEach(integration -> {
                    if (integration.getArtifactType().equals(ArtifactType.CLIENT)) {
                        LOGGER.info(() -> "Adding GoIntegration: " + integration.getClass().getName());
                        integrations.add(integration);
                    }
                });
        integrations.sort(Comparator.comparingInt(GoIntegration::getOrder));

        settings = GoSettings.from(context.getSettings());
        fileManifest = context.getFileManifest();

        Model resolvedModel = context.getModel();

        var modelTransformer = ModelTransformer.create();

        /*
         * smithy 1.23.0 added support for mixins. This transform flattens and applies
         * the mixins
         * and remove them from the model
         */
        resolvedModel = modelTransformer.flattenAndRemoveMixins(resolvedModel);

        // Add unique operation input/output shapes
        resolvedModel = AddOperationShapes.execute(resolvedModel, settings.getService());

        /*
         * smithy 1.12.0 added support for binding common errors to the service shape
         * this transform copies these common errors to the operations
         */
        resolvedModel = modelTransformer.copyServiceErrorsToOperations(resolvedModel,
                settings.getService(resolvedModel));

        LOGGER.info(() -> "Preprocessing smithy model");
        for (GoIntegration goIntegration : integrations) {
            resolvedModel = goIntegration.preprocessModel(resolvedModel, settings);
        }

        model = resolvedModel;

        // process final model
        integrations.forEach(integration -> {
            integration.processFinalizedModel(settings, model);
        });

        // fetch runtime plugins
        integrations.forEach(integration -> {
            integration.getClientPlugins().forEach(runtimePlugin -> {
                LOGGER.info(() -> "Adding Go runtime plugin: " + runtimePlugin);
                runtimePlugins.add(runtimePlugin);
            });
        });

        modelWithoutTraitShapes = modelTransformer.getModelWithoutTraitShapes(model);

        service = settings.getService(model);
        LOGGER.info(() -> "Generating Go client for service " + service.getId());

        SymbolProvider resolvedProvider = GoCodegenPlugin.createSymbolProvider(model, settings);
        for (GoIntegration integration : integrations) {
            resolvedProvider = integration.decorateSymbolProvider(settings, model, resolvedProvider);
        }
        symbolProvider = resolvedProvider;

        protocolGenerator = resolveProtocolGenerator(integrations, model, service, settings);
        applicationProtocol = protocolGenerator == null
                ? ApplicationProtocol.createDefaultHttpApplicationProtocol()
                : protocolGenerator.getApplicationProtocol();

        writers = new GoDelegator(fileManifest, symbolProvider);

        protocolDocumentGenerator = new ProtocolDocumentGenerator(settings, model, writers);

        this.eventStreamGenerator = new EventStreamGenerator(settings, model, writers, symbolProvider, service);

        this.ctx = new GoCodegenContext(model, settings, symbolProvider, fileManifest, writers, integrations);
    }

    private static ProtocolGenerator resolveProtocolGenerator(
            Collection<GoIntegration> integrations,
            Model model,
            ServiceShape service,
            GoSettings settings) {
        // Collect all the supported protocol generators.
        Map<ShapeId, ProtocolGenerator> generators = new HashMap<>();
        for (GoIntegration integration : integrations) {
            for (ProtocolGenerator generator : integration.getProtocolGenerators()) {
                generators.put(generator.getProtocol(), generator);
            }
        }

        ServiceIndex serviceIndex = ServiceIndex.of(model);

        ShapeId protocolTrait;
        try {
            protocolTrait = settings.resolveServiceProtocol(serviceIndex, service, generators.keySet());
            settings.setProtocol(protocolTrait);
        } catch (UnresolvableProtocolException e) {
            LOGGER.warning("Unable to find a protocol generator for " + service.getId() + ": " + e.getMessage());
            protocolTrait = null;
        }

        return protocolTrait != null ? generators.get(protocolTrait) : null;
    }

    void execute() {
        // Generate models that are connected to the service being generated.
        LOGGER.fine("Walking shapes from " + service.getId() + " to find shapes to generate");
        Set<Shape> serviceShapes = new TreeSet<>(new Walker(modelWithoutTraitShapes).walkShapes(service));

        for (Shape shape : serviceShapes) {
            shape.accept(this);
        }

        // Generate any required types and functions need to support protocol documents.
        protocolDocumentGenerator.generateDocumentSupport();

        // Generate a struct to handle unknown tags in unions
        List<UnionShape> unions = serviceShapes.stream()
                .map(Shape::asUnionShape)
                .flatMap(OptionalUtils::stream)
                .collect(Collectors.toList());
        if (!unions.isEmpty()) {
            writers.useShapeWriter(unions.get(0), writer -> {
                UnionGenerator.generateUnknownUnion(writer, unions, symbolProvider);
            });
        }

        for (GoIntegration integration : integrations) {
            integration.writeAdditionalFiles(settings, model, symbolProvider, writers::useFileWriter);
            integration.writeAdditionalFiles(settings, model, symbolProvider, writers);
            integration.writeAdditionalFiles(ctx);
        }

        eventStreamGenerator.generateEventStreamInterfaces();
        TopDownIndex.of(model).getContainedOperations(service)
                .forEach(eventStreamGenerator::generateOperationEventStreamStructure);

        if (protocolGenerator != null) {
            LOGGER.info("Generating serde for protocol " + protocolGenerator.getProtocol() + " on " + service.getId());
            ProtocolGenerator.GenerationContext.Builder contextBuilder = ProtocolGenerator.GenerationContext.builder()
                    .protocolName(protocolGenerator.getProtocolName())
                    .integrations(integrations)
                    .model(model)
                    .service(service)
                    .settings(settings)
                    .symbolProvider(symbolProvider)
                    .delegator(writers);

            LOGGER.info("Generating serde for protocol " + protocolGenerator.getProtocol()
                    + " on " + service.getId());
            writers.useFileWriter("serializers.go", settings.getModuleName(), writer -> {
                ProtocolGenerator.GenerationContext context = contextBuilder.writer(writer).build();
                protocolGenerator.generateRequestSerializers(context);
                protocolGenerator.generateSharedSerializerComponents(context);
            });

            writers.useFileWriter("deserializers.go", settings.getModuleName(), writer -> {
                ProtocolGenerator.GenerationContext context = contextBuilder.writer(writer).build();
                protocolGenerator.generateResponseDeserializers(context);
                protocolGenerator.generateSharedDeserializerComponents(context);
            });

            if (eventStreamGenerator.hasEventStreamOperations()) {
                eventStreamGenerator.writeEventStreamImplementation(writer -> {
                    ProtocolGenerator.GenerationContext context = contextBuilder.writer(writer).build();
                    protocolGenerator.generateEventStreamComponents(context);
                });
            }

            writers.useFileWriter("endpoints.go", settings.getModuleName(), writer -> {
                ProtocolGenerator.GenerationContext context = contextBuilder.writer(writer).build();
                protocolGenerator.generateEndpointResolution(context);
            });

            writers.useFileWriter("auth.go", settings.getModuleName(), writer -> {
                ProtocolGenerator.GenerationContext context = contextBuilder.writer(writer).build();
                protocolGenerator.generateAuth(context);
            });

            writers.useFileWriter("endpoints_test.go", settings.getModuleName(), writer -> {
                ProtocolGenerator.GenerationContext context = contextBuilder.writer(writer).build();
                protocolGenerator.generateEndpointResolutionTests(context);
            });

            LOGGER.info("Generating protocol " + protocolGenerator.getProtocol()
                    + " unit tests for " + service.getId());
            writers.useFileWriter("protocol_test.go", settings.getModuleName(), writer -> {
                protocolGenerator.generateProtocolTests(contextBuilder.writer(writer).build());
            });

            protocolDocumentGenerator.generateInternalDocumentTypes(protocolGenerator, contextBuilder.build());
        }

        LOGGER.fine("Flushing go writers");
        List<SymbolDependency> dependencies = writers.getDependencies();
        writers.flushWriters();

        GoModuleInfo goModuleInfo = new GoModuleInfo.Builder()
                .goDirective(settings.getGoDirective())
                .dependencies(dependencies)
                .build();

        GoModGenerator.writeGoMod(settings, fileManifest, goModuleInfo);

        LOGGER.fine("Generating build manifest file");
        ManifestWriter.writeManifest(settings, model, fileManifest, goModuleInfo);
    }

    @Override
    protected Void getDefault(Shape shape) {
        return null;
    }

    @Override
    public Void structureShape(StructureShape shape) {
        if (shape.getId().getNamespace().equals(CodegenUtils.getSyntheticTypeNamespace())) {
            return null;
        }
        Symbol symbol = symbolProvider.toSymbol(shape);
        writers.useShapeWriter(shape, writer -> new StructureGenerator(
                model, symbolProvider, writer, service, shape, symbol, protocolGenerator).run());
        return null;
    }

    @Override
    public Void stringShape(StringShape shape) {
        if (shape.hasTrait(EnumTrait.class)) {
            writers.useShapeWriter(shape, writer -> new EnumGenerator(symbolProvider, writer, shape).run());
        }
        return null;
    }

    @Override
    public Void unionShape(UnionShape shape) {
        UnionGenerator generator = new UnionGenerator(model, symbolProvider, shape);
        writers.useShapeWriter(shape, generator::generateUnion);
        writers.useShapeExportedTestWriter(shape, generator::generateUnionExamples);
        return null;
    }

    @Override
    public Void serviceShape(ServiceShape shape) {
        if (!Objects.equals(service, shape)) {
            LOGGER.fine(() -> "Skipping `" + shape.getId() + "` because it is not `" + service.getId() + "`");
            return null;
        }

        var protocol = protocolGenerator != null
                ? protocolGenerator.getApplicationProtocol()
                : ApplicationProtocol.createDefaultHttpApplicationProtocol();
        var context = ProtocolGenerator.GenerationContext.builder()
                .protocolName(protocol.getName())
                .integrations(integrations)
                .model(model)
                .service(service)
                .settings(settings)
                .symbolProvider(symbolProvider)
                .delegator(writers)
                .build();

        // Write API client's package doc for the service.
        writers.useFileWriter("doc.go", settings.getModuleName(), (writer) -> {
            writer.writePackageDocs(String.format(
                    "Package %s provides the API client, operations, and parameter types for %s.",
                    CodegenUtils.getDefaultPackageImportName(settings.getModuleName()),
                    CodegenUtils.getServiceTitle(shape, "the API")));
            writer.writePackageDocs("");
            writer.writePackageShapeDocs(shape);
        });

        // Write API client type and utilities.
        writers.useShapeWriter(shape, serviceWriter -> {
            new ServiceGenerator(settings, model, symbolProvider, serviceWriter, shape, integrations,
                    runtimePlugins, applicationProtocol).run();

            // Generate each operation for the service. We do this here instead of via the
            // operation visitor method to
            // limit it to the operations bound to the service.
            TopDownIndex topDownIndex = model.getKnowledge(TopDownIndex.class);
            Set<OperationShape> containedOperations = new TreeSet<>(topDownIndex.getContainedOperations(service));
            for (OperationShape operation : containedOperations) {
                writers.useShapeWriter(operation, operationWriter ->
                        new OperationGenerator(ctx, operationWriter, operation, protocolGenerator, runtimePlugins)
                                .run());
            }
        });

        var clientOptions = new ClientOptions(context, protocol);
        writers.useFileWriter("options.go", settings.getModuleName(), clientOptions);
        return null;
    }

    @Override
    public Void intEnumShape(IntEnumShape shape) {
        writers.useShapeWriter(shape, writer -> new IntEnumGenerator(symbolProvider, writer, shape).run());
        return null;
    }
}
