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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_1Trait;
import software.amazon.smithy.aws.traits.protocols.AwsQueryTrait;
import software.amazon.smithy.aws.traits.protocols.Ec2QueryTrait;
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait;
import software.amazon.smithy.aws.traits.protocols.RestXmlTrait;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.protocol.traits.Rpcv2CborTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Settings used by {@link GoCodegenPlugin}.
 */
@SmithyInternalApi
public final class GoSettings {
    public static final List<ShapeId> PROTOCOLS_BY_PRIORITY = List.of(
            Rpcv2CborTrait.ID,
            AwsJson1_0Trait.ID,
            AwsJson1_1Trait.ID,
            RestJson1Trait.ID,
            RestXmlTrait.ID,
            AwsQueryTrait.ID,
            Ec2QueryTrait.ID
    );

    private static final String SERVICE = "service";
    private static final String MODULE_NAME = "module";
    private static final String MODULE_DESCRIPTION = "moduleDescription";
    private static final String MODULE_VERSION = "moduleVersion";
    private static final String GENERATE_GO_MOD = "generateGoMod";
    private static final String GO_DIRECTIVE = "goDirective";

    private ShapeId service;
    private String moduleName;
    private String moduleDescription = "";
    private String moduleVersion;
    private Boolean generateGoMod = false;
    private String goDirective = GoModuleInfo.DEFAULT_GO_DIRECTIVE;
    private ShapeId protocol;
    private ArtifactType artifactType;

    @SmithyInternalApi
    public enum ArtifactType {
        CLIENT,
        SERVER;
    }

    /**
     * Create a settings object from a configuration object node.
     *
     * @param config Config object to load.
     * @return Returns the extracted settings.
     */
    public static GoSettings from(ObjectNode config) {
        return from(config, ArtifactType.CLIENT);
    }

    @SmithyInternalApi
    public static GoSettings from(ObjectNode config, ArtifactType artifactType) {
        GoSettings settings = new GoSettings();
        config.warnIfAdditionalProperties(
            Arrays.asList(SERVICE, MODULE_NAME, MODULE_DESCRIPTION, MODULE_VERSION, GENERATE_GO_MOD, GO_DIRECTIVE));
        settings.setArtifactType(artifactType);
        settings.setService(config.expectStringMember(SERVICE).expectShapeId());
        settings.setModuleName(config.expectStringMember(MODULE_NAME).getValue());
        settings.setModuleDescription(config.getStringMemberOrDefault(
                MODULE_DESCRIPTION, settings.getModuleName() + " client"));
        settings.setModuleVersion(config.getStringMemberOrDefault(MODULE_VERSION, null));
        settings.setGenerateGoMod(config.getBooleanMemberOrDefault(GENERATE_GO_MOD, false));
        settings.setGoDirective(config.getStringMemberOrDefault(GO_DIRECTIVE, GoModuleInfo.DEFAULT_GO_DIRECTIVE));
        return settings;
    }

    /**
     * Gets the id of the service that is being generated.
     *
     * @return Returns the service id.
     * @throws NullPointerException if the service has not been set.
     */
    public ShapeId getService() {
        return Objects.requireNonNull(service, SERVICE + " not set");
    }

    /**
     * Gets the corresponding {@link ServiceShape} from a model.
     *
     * @param model Model to search for the service shape by ID.
     * @return Returns the found {@code Service}.
     * @throws NullPointerException if the service has not been set.
     * @throws CodegenException     if the service is invalid or not found.
     */
    public ServiceShape getService(Model model) {
        return model
                .getShape(getService())
                .orElseThrow(() -> new CodegenException("Service shape not found: " + getService()))
                .asServiceShape()
                .orElseThrow(() -> new CodegenException("Shape is not a Service: " + getService()));
    }

    /**
     * Sets the service to generate.
     *
     * @param service The service to generate.
     */
    public void setService(ShapeId service) {
        this.service = Objects.requireNonNull(service);
    }

    /**
     * Gets the required module name for the module that will be generated.
     *
     * @return Returns the module name.
     * @throws NullPointerException if the module name has not been set.
     */
    public String getModuleName() {
        return Objects.requireNonNull(moduleName, MODULE_NAME + " not set");
    }

    /**
     * Sets the name of the module to generate.
     *
     * @param moduleName The name of the module to generate.
     */
    public void setModuleName(String moduleName) {
        this.moduleName = Objects.requireNonNull(moduleName);
    }

    /**
     * Sets the name of the module to generate.
     *
     * @param moduleName The name of the module to generate.
     */
    public void setModule(String moduleName) {
        this.moduleName = Objects.requireNonNull(moduleName);
    }

    /**
     * Gets the optional module description for the module that will be generated.
     *
     * @return Returns the module description.
     */
    public String getModuleDescription() {
        return moduleDescription;
    }

    /**
     * Sets the description of the module to generate.
     *
     * @param moduleDescription The description of the module to generate.
     */
    public void setModuleDescription(String moduleDescription) {
        this.moduleDescription = Objects.requireNonNull(moduleDescription);
    }

    /**
     * Gets the optional module version for the module that will be generated.
     *
     * @return Returns the module version.
     */
    public Optional<String> getModuleVersion() {
        return Optional.ofNullable(moduleVersion);
    }

    /**
     * Sets the version of the module to generate.
     *
     * @param moduleVersion The version of the module to generate.
     */
    public void setModuleVersion(String moduleVersion) {
        if (moduleVersion != null) {
            this.moduleVersion = moduleVersion;
        }
    }

     /**
     * Gets the flag for generating go.mod file.
     *
     * @return Returns if go.mod will be generated (true) or not (false)
     */
    public Boolean getGenerateGoMod() {
        return generateGoMod;
    }

    /**
     * Sets the flag for generating go.mod file.
     *
     * @param generateGoMod If go.mod will be generated (true) or not (false)
     */
    public void setGenerateGoMod(Boolean generateGoMod) {
        this.generateGoMod = Objects.requireNonNull(generateGoMod);
    }

    /**
     * Gets the optional Go directive for the module that will be generated.
     *
     * @return Returns the Go directive.
     */
    public String getGoDirective() {
        return goDirective;
    }

    /**
     * Sets the Go directive of the module to generate.
     *
     * @param goDirective The Go directive of the module to generate.
     */
    public void setGoDirective(String goDirective) {
        this.goDirective = Objects.requireNonNull(goDirective);
    }

    /**
     * Gets the configured protocol to generate.
     *
     * @return Returns the configured protocol.
     */
    public ShapeId getProtocol() {
        return protocol;
    }

    /**
     * Resolves the highest priority protocol from a service shape that is
     * supported by the generator.
     *
     * @param serviceIndex Service index containing the support
     * @param service                 Service to get the protocols from if "protocols" is not set.
     * @param supportedProtocolTraits The set of protocol traits supported by the generator.
     * @return Returns the resolved protocol name.
     * @throws UnresolvableProtocolException if no protocol could be resolved.
     */
    public ShapeId resolveServiceProtocol(
            ServiceIndex serviceIndex,
            ServiceShape service,
            Set<ShapeId> supportedProtocolTraits) {
        if (protocol != null) {
            return protocol;
        }

        Set<ShapeId> resolvedProtocols = serviceIndex.getProtocols(service).keySet();

        var byPriority = PROTOCOLS_BY_PRIORITY.stream()
                .filter(resolvedProtocols::contains)
                .toList();
        return byPriority.stream()
                .filter(supportedProtocolTraits::contains)
                .findFirst()
                .orElseThrow(() -> new UnresolvableProtocolException(String.format(
                        "The %s service supports the following unsupported protocols %s. The following protocol "
                                + "generators were found on the class path: %s",
                        service.getId(), resolvedProtocols, supportedProtocolTraits)));
    }

    /**
     * Sets the protocol to generate.
     *
     * @param protocol Protocols to generate.
     */
    public void setProtocol(ShapeId protocol) {
        this.protocol = Objects.requireNonNull(protocol);
    }

    @SmithyInternalApi
    public ArtifactType getArtifactType() {
        return artifactType;
    }

    @SmithyInternalApi
    public void setArtifactType(ArtifactType artifactType) {
        this.artifactType = artifactType;
    }
}
