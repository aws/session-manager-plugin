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

import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Generates a concrete request handler. This class handles base struct generation, options, and construction, the
 * protocol generator must fill in the actual handler logic as appropriate.
 */
@SmithyInternalApi
public final class RequestHandler implements Writable {
    public static final String NAME = "RequestHandler";

    private final ServerProtocolGenerator protocolGenerator;

    public RequestHandler(ServerProtocolGenerator protocolGenerator) {
        this.protocolGenerator = protocolGenerator;
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(generate());
    }

    private Writable generate() {
        return ChainWritable.of(
                generateStruct(),
                generateNew(),
                protocolGenerator.generateHandleRequest()
        ).compose();
    }

    private Writable generateStruct() {
        return goTemplate("""
                type $this:L struct {
                    service $service:L
                    options $options:L
                }
                """,
                MapUtils.of(
                        "this", NAME,
                        "service", ServerInterface.NAME,
                        "options", OptionsStruct.NAME
                ));
    }

    private Writable generateNew() {
        return goTemplate("""
                func New(svc $interface:L, opts $options:L, optFns ...func(*$options:L)) *$this:L {
                    o := opts
                    for _, fn := range optFns {
                        fn(&o)
                    }

                    h := &$this:L{
                        service: svc,
                        options: o,
                    }

                    return h
                }
                """,
                MapUtils.of(
                        "this", NAME,
                        "interface", ServerInterface.NAME,
                        "options", OptionsStruct.NAME
                ));
    }
}
