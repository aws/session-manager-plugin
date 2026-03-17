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

import java.util.List;
import software.amazon.smithy.go.codegen.protocol.rpc2.cbor.Rpc2CborProtocolGenerator;
import software.amazon.smithy.utils.ListUtils;

public class DefaultProtocols implements GoIntegration {
    @Override
    public List<ProtocolGenerator> getProtocolGenerators() {
        return ListUtils.of(new Rpc2CborProtocolGenerator());
    }
}
