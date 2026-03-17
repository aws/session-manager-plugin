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

package software.amazon.smithy.go.codegen.auth;

import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates auth scheme resolver parameters.
 * By default, the only field that exists universally is the name of the operation being invoked. Services that use
 * SigV4[A] will also have a field for the region.
 * Additional parameters can be loaded via GoIntegration.
 */
public class AuthParametersGenerator {
    public static final String STRUCT_NAME = "AuthResolverParameters";

    public static final Symbol STRUCT_SYMBOL = SymbolUtils.createPointableSymbolBuilder(STRUCT_NAME).build();

    private final ProtocolGenerator.GenerationContext context;

    private final ArrayList<AuthParameter> fields = new ArrayList<>(
            ListUtils.of(AuthParameter.OPERATION)
    );

    public AuthParametersGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public Writable generate() {
        loadFields();

        return goTemplate(
                """
                        $doc:W
                        type $name:L struct {
                            $fields:W
                        }
                        """,
                MapUtils.of(
                        "doc", generateDocs(),
                        "name", STRUCT_NAME,
                        "fields", generateFields()
                )
        );
    }

    private Writable generateDocs() {
        return goDocTemplate(
                "$name:L contains the set of inputs necessary for auth scheme resolution.",
                MapUtils.of("name", STRUCT_NAME)
        );
    }

    private Writable generateFields() {
        return (writer) -> {
            for (var field: fields) {
                writer.write("""
                        $W
                        $L $P
                        """,
                        goDocTemplate(field.docs()),
                        field.name(),
                        field.type()
                );
            }
        };
    }

    private void loadFields() {
        for (var integration: context.getIntegrations()) {
            var plugins = integration.getClientPlugins().stream().filter(it ->
                    it.matchesService(context.getModel(), context.getService())).toList();
            for (var plugin: plugins) {
                fields.addAll(plugin.getAuthParameters());
            }
        }
    }
}
