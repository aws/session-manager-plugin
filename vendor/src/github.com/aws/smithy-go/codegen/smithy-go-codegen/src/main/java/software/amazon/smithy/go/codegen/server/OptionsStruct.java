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

import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class OptionsStruct implements Writable {
    public static final String NAME = "Options";

    private final ServerProtocolGenerator protocolGenerator;

    public OptionsStruct(ServerProtocolGenerator protocolGenerator) {
        this.protocolGenerator = protocolGenerator;
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(generate());
    }

    private Writable generate() {
        return goTemplate("""
                type $this:L struct {
                    $protocolOptions:W
                }
                """,
                MapUtils.of(
                        "this", NAME,
                        "protocolOptions", protocolGenerator.generateOptions()
                ));
    }
}
