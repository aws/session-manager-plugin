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

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.SmithyIntegration;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoSettings.ArtifactType;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.TriConsumer;
import software.amazon.smithy.go.codegen.server.ServerProtocolGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Java SPI for customizing Go code generation, registering
 * new protocol code generators, renaming shapes, modifying the model,
 * adding custom code, etc.
 */
@SmithyUnstableApi
public interface GoIntegration extends SmithyIntegration<GoSettings, GoWriter, GoCodegenContext> {
    /**
     * Gets the sort order of the customization from -128 to 127.
     *
     * <p>Customizations are applied according to this sort order. Lower values
     * are executed before higher values (for example, -128 comes before 0,
     * comes before 127). Customizations default to 0, which is the middle point
     * between the minimum and maximum order values. The customization
     * applied later can override the runtime configurations that provided
     * by customizations applied earlier.
     *
     * @return Returns the sort order, defaulting to 0.
     */
    default byte getOrder() {
        return 0;
    }

    default ArtifactType getArtifactType() {
        return ArtifactType.CLIENT;
    }

    /**
     * Preprocess the model before code generation.
     *
     * <p>This can be used to remove unsupported features, remove traits
     * from shapes (e.g., make members optional), etc.
     *
     * @param model model definition.
     * @param settings Setting used to generate.
     * @return Returns the updated model.
     */
    default Model preprocessModel(Model model, GoSettings settings) {
        return model;
    }

    /**
     * Updates the {@link SymbolProvider} used when generating code.
     *
     * <p>This can be used to customize the names of shapes, the package
     * that code is generated into, add dependencies, add imports, etc.
     *
     * @param settings Setting used to generate.
     * @param model Model being generated.
     * @param symbolProvider The original {@code SymbolProvider}.
     * @return The decorated {@code SymbolProvider}.
     */
    default SymbolProvider decorateSymbolProvider(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider
    ) {
        return symbolProvider;
    }

    /**
     * Writes additional files.
     *
     * Called each time a writer is used that defines a shape.
     *
     * <p>Any mutations made on the writer (for example, adding
     * section interceptors) are removed after the callback has completed;
     * the callback is invoked in between pushing and popping state from
     * the writer.
     *
     * @param settings Settings used to generate.
     * @param model Model to generate from.
     * @param symbolProvider Symbol provider used for codegen.
     * @param writer Writer that will be used.
     * @param definedShape Shape that is being defined in the writer.
     */
    default void onShapeWriterUse(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            Shape definedShape
    ) {
        // pass
    }

    /**
     * Writes additional files.
     *
     * @param settings Settings used to generate.
     * @param model Model to generate from.
     * @param symbolProvider Symbol provider used for codegen.
     * @param writerFactory A factory function that takes the name of a file
     *   to write and a {@code Consumer} that receives a
     *   {@link GoSettings} to perform the actual writing to the file.
     * @deprecated use {@link #writeAdditionalFiles(GoCodegenContext)}.
     */
    default void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TriConsumer<String, String, Consumer<GoWriter>> writerFactory
    ) {
        // pass
    }

    /**
     * Writes additional files.
     *
     * @param settings Settings used to generate.
     * @param model Model to generate from.
     * @param symbolProvider Symbol provider used for codegen.
     * @param goDelegator GoDelegator used to manage writer for the file.
     * @deprecated use {@link #writeAdditionalFiles(GoCodegenContext)}.
     */
    default void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator goDelegator
    ) {
        // pass
    }

    /**
     * Writes additional files.
     * @param ctx The codegen context.
     */
    default void writeAdditionalFiles(GoCodegenContext ctx) {
        // pass
    }

    /**
     * Gets a list of protocol generators to register.
     *
     * @return Returns the list of protocol generators to register.
     */
    default List<ProtocolGenerator> getProtocolGenerators() {
        return Collections.emptyList();
    }

    /**
     * Gets a list of server protocol generators to register. Protocol generators should generally be written to accept
     * the codegen context at construction time, such that all the model information necessary for codegen is available.
     */
    default List<ServerProtocolGenerator> getServerProtocolGenerators(GoCodegenContext ctx) {
        return Collections.emptyList();
    }

    /**
     * Processes the finalized model before runtime plugins are consumed and
     * code generation starts. This plugin can be used to add RuntimeClientPlugins
     * to the integration's list of plugin.
     *
     * @param settings Settings used to generate.
     * @param model Model to generate from.
     */
    default void processFinalizedModel(GoSettings settings, Model model) {
        // pass
    }

    /**
     * Gets a list of plugins to apply to the generated client.
     *
     * @return Returns the list of RuntimePlugins to apply to the client.
     */
    default List<RuntimeClientPlugin> getClientPlugins() {
        return Collections.emptyList();
    }

    /**
     * Gets a list of plugins to apply to the generated client.
     *
     * @return Returns the list of RuntimePlugins to apply to the client.
     */
    default List<RuntimeClientPlugin> getClientPlugins(Model model, ServiceShape service) {
        return getClientPlugins().stream().filter(plugin -> plugin.matchesService(model, service)).toList();
    }

    /**
     * Processes the given serviceId and may return a unmodified, modified, or replacement value.
     *
     * @param settings Settings used to generate
     * @param model model to generate from
     * @param serviceId the serviceId
     * @return the new serviceId
     */
    default String processServiceId(GoSettings settings, Model model, String serviceId) {
        return serviceId;
    }

    default void renderPreEndpointResolutionHook(GoSettings settings, GoWriter writer, Model model) {
        // pass
    }

    default void renderPostEndpointResolutionHook(GoSettings settings, GoWriter writer, Model model) {
        // pass
    }
}
