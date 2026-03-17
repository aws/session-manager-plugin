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

package software.amazon.smithy.go.codegen.middleware;

import static java.util.Collections.emptyMap;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;

/**
 * Abstract base class for code generation of operation middleware.
 */
public abstract class OperationMiddleware implements Writable {
    public abstract String getStructName();

    public Map<String, Symbol> getFields() {
        return emptyMap();
    }

    public String getId() {
        return getStructName();
    }

    public abstract String getFuncName();

    public abstract Symbol getInput();

    public abstract Symbol getHandler();

    public abstract Symbol getOutput();

    public abstract Writable getFuncBody();

    @Override
    public final void accept(GoWriter goWriter) {
        goWriter.write(goTemplate("""
                type $name:L struct {
                    $fields:W
                }

                func (*$name:L) ID() string {
                    return $id:S
                }

                func (m *$name:L) $func:L (
                    ctx $context:T, in $in:T, next $next:T,
                ) (
                    $out:T, $md:T, error,
                ) {
                    $body:W
                }
                """,
                Map.of(
                        "name", getStructName(),
                        "fields", renderFields(),
                        "id", getId(),
                        "func", getFuncName(),
                        "context", GoStdlibTypes.Context.Context,
                        "in", getInput(),
                        "next", getHandler(),
                        "out", getOutput(),
                        "md", SmithyGoTypes.Middleware.Metadata,
                        "body", getFuncBody()
                )));
    }

    private Writable renderFields() {
        return ChainWritable.of(
                getFields().entrySet().stream()
                        .map(it -> goTemplate("$L $P", it.getKey(), it.getValue()))
                        .toList()
        ).compose(false);
    }
}
