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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_1Trait;
import software.amazon.smithy.aws.traits.protocols.AwsQueryTrait;
import software.amazon.smithy.aws.traits.protocols.Ec2QueryTrait;
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait;
import software.amazon.smithy.aws.traits.protocols.RestXmlTrait;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ProtocolDefinitionTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.protocol.traits.Rpcv2CborTrait;

// Implements the protocol selection test cases in the rpcv2 CBOR SEP (protocol-selection_smithy-rpc-v2.md).
public class ResolveProtocolTest {
    private static final Set<ShapeId> SUPPORTED_PROTOCOLS = Set.of(
            Rpcv2CborTrait.ID,
            AwsJson1_0Trait.ID,
            AwsJson1_1Trait.ID,
            RestJson1Trait.ID,
            RestXmlTrait.ID,
            AwsQueryTrait.ID,
            Ec2QueryTrait.ID);

    private ServiceShape buildTestService(Trait ...protocols) {
        var builder = ServiceShape.builder()
                .id("smithy.go.test#TestService");
        for (var protocol : protocols) {
            builder.addTrait(protocol);
        }
        return builder.build();
    }

    private Model buildTestModel(ServiceShape service) {
        var pd = ProtocolDefinitionTrait.builder().build();
        return Model.builder()
                .addShape(StructureShape.builder().id(Rpcv2CborTrait.ID).addTrait(pd).build())
                .addShape(StructureShape.builder().id(AwsJson1_0Trait.ID).addTrait(pd).build())
                .addShape(StructureShape.builder().id(AwsJson1_1Trait.ID).addTrait(pd).build())
                .addShape(StructureShape.builder().id(RestJson1Trait.ID).addTrait(pd).build())
                .addShape(StructureShape.builder().id(RestXmlTrait.ID).addTrait(pd).build())
                .addShape(StructureShape.builder().id(AwsQueryTrait.ID).addTrait(pd).build())
                .addShape(StructureShape.builder().id(Ec2QueryTrait.ID).addTrait(pd).build())
                .addShape(service)
                .build();
    }

    @Test
    public void testResolveProtocol0() {
        var service = buildTestService(
                Rpcv2CborTrait.builder().build(),
                AwsJson1_0Trait.builder().build());
        var model = buildTestModel(service);

        var protocol = new GoSettings().resolveServiceProtocol(ServiceIndex.of(model), service, SUPPORTED_PROTOCOLS);
        assertThat(protocol, equalTo(Rpcv2CborTrait.ID));
    }

    @Test
    public void testResolveProtocol1() {
        var service = buildTestService(
                Rpcv2CborTrait.builder().build());
        var model = buildTestModel(service);

        var protocol = new GoSettings().resolveServiceProtocol(ServiceIndex.of(model), service, SUPPORTED_PROTOCOLS);
        assertThat(protocol, equalTo(Rpcv2CborTrait.ID));
    }

    @Test
    public void testResolveProtocol2() {
        var service = buildTestService(
                Rpcv2CborTrait.builder().build(),
                AwsJson1_0Trait.builder().build(),
                new AwsQueryTrait());
        var model = buildTestModel(service);

        var protocol = new GoSettings().resolveServiceProtocol(ServiceIndex.of(model), service, SUPPORTED_PROTOCOLS);
        assertThat(protocol, equalTo(Rpcv2CborTrait.ID));
    }

    @Test
    public void testResolveProtocol3() {
        var service = buildTestService(
                AwsJson1_0Trait.builder().build(),
                new AwsQueryTrait());
        var model = buildTestModel(service);

        var protocol = new GoSettings().resolveServiceProtocol(ServiceIndex.of(model), service, SUPPORTED_PROTOCOLS);
        assertThat(protocol, equalTo(AwsJson1_0Trait.ID));
    }

    @Test
    public void testResolveProtocol4() {
        var service = buildTestService(
                new AwsQueryTrait());
        var model = buildTestModel(service);

        var protocol = new GoSettings().resolveServiceProtocol(ServiceIndex.of(model), service, SUPPORTED_PROTOCOLS);
        assertThat(protocol, equalTo(AwsQueryTrait.ID));
    }
}
