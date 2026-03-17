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

import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import software.amazon.smithy.go.codegen.auth.AuthSchemeResolverGenerator;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.integration.auth.AnonymousDefinition;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.synthetic.NoAuthTrait;
import software.amazon.smithy.utils.MapUtils;

/**
 * Implements codegen for service client config.
 */
public class ClientOptions implements Writable {
    public static final String NAME = "Options";

    private final ProtocolGenerator.GenerationContext context;
    private final ApplicationProtocol protocol;

    private final List<ConfigField> fields;
    private final Map<ShapeId, AuthSchemeDefinition> authSchemes;

    public ClientOptions(ProtocolGenerator.GenerationContext context, ApplicationProtocol protocol) {
        this.context = context;
        this.protocol = protocol;

        this.fields = context.getIntegrations().stream()
                .flatMap(it -> it.getClientPlugins(context.getModel(), context.getService()).stream())
                .flatMap(it -> it.getConfigFields().stream())
                .distinct()
                .sorted(Comparator.comparing(ConfigField::getName))
                .toList();
        this.authSchemes = context.getIntegrations().stream()
                .flatMap(it -> it.getClientPlugins(context.getModel(), context.getService()).stream())
                .flatMap(it -> it.getAuthSchemeDefinitions().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(generate());
    }

    private Writable generate() {
        var apiOptionsDocs = goDocTemplate(
                "Set of options to modify how an operation is invoked. These apply to all operations "
                        + "invoked for this client. Use functional options on operation call to modify this "
                        + "list for per operation behavior."
        );
        return goTemplate("""
                $protocolTypes:W

                type $options:L struct {
                    $apiOptionsDocs:W
                    APIOptions []func($stack:P) error

                    $fields:W

                    $protocolFields:W
                }

                $copy:W

                $getIdentityResolver:W

                $helpers:W
                """,
                MapUtils.of(
                        "protocolTypes", generateProtocolTypes(),
                        "apiOptionsDocs", apiOptionsDocs,
                        "options", NAME,
                        "stack", SmithyGoTypes.Middleware.Stack,
                        "fields", ChainWritable.of(fields.stream().map(this::writeField).toList()).compose(),
                        "protocolFields", generateProtocolFields(),
                        "copy", generateCopy(),
                        "getIdentityResolver", generateGetIdentityResolver(),
                        "helpers", generateHelpers()
                ));
    }

    private Writable generateProtocolTypes() {
        ensureSupportedProtocol();
        return goTemplate("""
                type HTTPClient interface {
                    Do($P) ($P, error)
                }
                """, GoStdlibTypes.Net.Http.Request, GoStdlibTypes.Net.Http.Response);
    }

    private Writable writeField(ConfigField field) {
        Writable docs = writer -> {
            field.getDocumentation().ifPresent(writer::writeDocs);
            field.getDeprecated().ifPresent(s -> {
                if (field.getDocumentation().isPresent()) {
                    writer.writeDocs("");
                }
                writer.writeDocs(String.format("Deprecated: %s", s));
            });
        };
        return goTemplate("""
                $W
                $L $P
                """, docs, field.getName(), field.getType());
    }

    private Writable generateProtocolFields() {
        ensureSupportedProtocol();
        return goTemplate("""
                $1W
                HTTPClient HTTPClient

                $6W
                Interceptors $7T

                $2W
                AuthSchemeResolver $4L

                $3W
                AuthSchemes []$5T

                $8W
                AuthSchemePreference []string
                """,
                goDocTemplate("The HTTP client to invoke API calls with. "
                        + "Defaults to client's default HTTP implementation if nil."),
                goDocTemplate("The auth scheme resolver which determines how to authenticate for each operation."),
                goDocTemplate("The list of auth schemes supported by the client."),
                AuthSchemeResolverGenerator.INTERFACE_NAME,
                SmithyGoTypes.Transport.Http.AuthScheme,
                goDocTemplate("Client registry of operation interceptors."),
                SmithyGoDependency.SMITHY_HTTP_TRANSPORT.struct("InterceptorRegistry"),
                goDocTemplate("Priority list of preferred auth scheme names (e.g. sigv4a)."));
    }

    private Writable generateCopy() {
        return goTemplate("""
                // Copy creates a clone where the APIOptions list is deep copied.
                func (o $1L) Copy() $1L {
                    to := o
                    to.APIOptions = make([]func($2P) error, len(o.APIOptions))
                    copy(to.APIOptions, o.APIOptions)
                    to.Interceptors = o.Interceptors.Copy()

                    return to
                }
                """, NAME, SmithyGoTypes.Middleware.Stack);
    }

    private Writable generateGetIdentityResolver() {
        return goTemplate("""
                func (o $L) GetIdentityResolver(schemeID string) $T {
                    $W
                    $W
                    return nil
                }
                """,
                NAME,
                SmithyGoTypes.Auth.IdentityResolver,
                ChainWritable.of(
                        ServiceIndex.of(context.getModel())
                                .getEffectiveAuthSchemes(context.getService()).keySet().stream()
                                .filter(authSchemes::containsKey)
                                .map(trait -> generateGetIdentityResolverMapping(trait, authSchemes.get(trait)))
                                .toList()
                ).compose(false),
                generateGetIdentityResolverMapping(NoAuthTrait.ID, new AnonymousDefinition()));
    }

    private Writable generateGetIdentityResolverMapping(ShapeId schemeId, AuthSchemeDefinition scheme) {
        return goTemplate("""
                if schemeID == $S {
                    return $W
                }""", schemeId.toString(), scheme.generateOptionsIdentityResolver());
    }

    private Writable generateHelpers() {
        return writer -> {
            writer.write("""
                    $W
                    func WithAPIOptions(optFns ...func($P) error) func(*Options) {
                        return func (o *Options) {
                            o.APIOptions = append(o.APIOptions, optFns...)
                        }
                    }
                    """,
                    goDocTemplate(
                            "WithAPIOptions returns a functional option for setting the Client's APIOptions option."
                    ),
                    SmithyGoTypes.Middleware.Stack);

            fields.stream().filter(ConfigField::getWithHelper).filter(ConfigField::isDeprecated)
                    .forEach(configField -> {
                        writer.writeDocs(configField.getDeprecated().get());
                        writeHelper(writer, configField);
                    });

            fields.stream().filter(ConfigField::getWithHelper).filter(Predicate.not(ConfigField::isDeprecated))
                    .forEach(configField -> {
                        writer.writeDocs(
                                String.format(
                                        "With%s returns a functional option for setting the Client's %s option.",
                                        configField.getName(), configField.getName()));
                        writeHelper(writer, configField);
                    });
        };
    }

    private void writeHelper(GoWriter writer, ConfigField configField) {
        writer.write("""
                func With$1L(v $2P) func(*Options) {
                    return func(o *Options) {
                        o.$1L = v
                    }
                }
                """, configField.getName(), configField.getType());
    }

    private void ensureSupportedProtocol() {
        if (!protocol.isHttpProtocol()) {
            throw new UnsupportedOperationException("Protocols other than HTTP are not yet implemented: " + protocol);
        }
    }
}
