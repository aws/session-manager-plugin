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

package software.amazon.smithy.go.codegen.server.integration;

import java.util.List;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.server.ServerProtocolGenerator;
import software.amazon.smithy.go.codegen.server.protocol.aws.AwsJson10ProtocolGenerator;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class DefaultProtocols implements GoIntegration {
    @Override
    public GoSettings.ArtifactType getArtifactType() {
        return GoSettings.ArtifactType.SERVER;
    }

    @Override
    public List<ServerProtocolGenerator> getServerProtocolGenerators(GoCodegenContext ctx) {
        return ListUtils.of(
                new AwsJson10ProtocolGenerator(ctx)
        );
    }
}
