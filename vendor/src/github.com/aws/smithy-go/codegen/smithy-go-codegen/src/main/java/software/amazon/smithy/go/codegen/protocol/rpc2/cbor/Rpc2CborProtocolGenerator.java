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

package software.amazon.smithy.go.codegen.protocol.rpc2.cbor;

import static java.util.stream.Collectors.toCollection;
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.protocol.ProtocolUtil.GET_AWS_QUERY_ERROR_CODE;
import static software.amazon.smithy.go.codegen.protocol.rpc2.cbor.ProtocolUtil.GET_PROTOCOL_ERROR_INFO;
import static software.amazon.smithy.go.codegen.serde.SerdeUtil.getShapesToSerde;
import static software.amazon.smithy.go.codegen.serde.cbor.CborDeserializerGenerator.getDeserializerName;

import java.util.LinkedHashSet;
import java.util.stream.Stream;
import software.amazon.smithy.aws.traits.protocols.AwsQueryCompatibleTrait;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.protocol.DeserializeResponseMiddleware;
import software.amazon.smithy.go.codegen.protocol.rpc2.Rpc2ProtocolGenerator;
import software.amazon.smithy.go.codegen.protocol.rpc2.Rpc2SerializeRequestMiddleware;
import software.amazon.smithy.go.codegen.serde.cbor.CborDeserializerGenerator;
import software.amazon.smithy.go.codegen.serde.cbor.CborSerializerGenerator;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.protocol.traits.Rpcv2CborTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class Rpc2CborProtocolGenerator extends Rpc2ProtocolGenerator {
    @Override
    public final ShapeId getProtocol() {
        return Rpcv2CborTrait.ID;
    }

    @Override
    public void generateSharedSerializerComponents(GenerationContext context) {
        var model = context.getModel();
        var service = context.getService();
        var shapes = TopDownIndex.of(model).getContainedOperations(service).stream()
                .map(it -> model.expectShape(it.getInputShape(), StructureShape.class))
                .flatMap(it -> getShapesToSerde(model, it).stream())
                .sorted()
                .collect(toCollection(LinkedHashSet::new));
        var generator = new CborSerializerGenerator(context);
        context.getWriter().get().write(generator.generate(shapes));
    }

    @Override
    public void generateSharedDeserializerComponents(GenerationContext context) {
        var model = context.getModel();
        var service = context.getService();
        var operations = TopDownIndex.of(model).getContainedOperations(service);

        var outputShapes = operations.stream()
                .map(it -> model.expectShape(it.getOutputShape(), StructureShape.class))
                .filter(it -> !it.members().isEmpty())
                .flatMap(it -> getShapesToSerde(model, it).stream());
        var errorShapes = operations.stream()
                .flatMap(it -> it.getErrors().stream())
                .map(model::expectShape)
                .flatMap(it -> getShapesToSerde(model, it).stream());

        var generator = new CborDeserializerGenerator(context);
        var writer = context.getWriter().get();
        writer.write(generator.generate(
                Stream.concat(outputShapes, errorShapes)
                        .sorted()
                        .collect(toCollection(LinkedHashSet::new))) // in case of overlap
        );
        writer.write(ChainWritable.of(
                operations.stream()
                        .sorted()
                        .map(it -> deserializeOperationError(context, it))
                        .toList()
        ).compose());

        writer.write(GET_PROTOCOL_ERROR_INFO);
        writer.write(GET_AWS_QUERY_ERROR_CODE);
    }

    @Override
    public final Rpc2SerializeRequestMiddleware getSerializeRequestMiddleware(
            ProtocolGenerator generator, GenerationContext ctx, OperationShape operation
    ) {
        return new SerializeMiddleware(generator, ctx, operation);
    }

    @Override
    public final DeserializeResponseMiddleware getDeserializeResponseMiddleware(
            ProtocolGenerator generator, GenerationContext ctx, OperationShape operation
    ) {
        return new DeserializeMiddleware(generator, ctx, operation);
    }

    private Writable deserializeOperationError(
            ProtocolGenerator.GenerationContext ctx, OperationShape operation
    ) {
        var model = ctx.getModel();
        var service = ctx.getService();
        return goTemplate("""
                func $func:L(resp $smithyhttpResponse:P) error {
                    payload, err := $readAll:T(resp.Body)
                    if err != nil {
                        return &$deserError:T{Err: $fmtErrorf:T("read response body: %w", err)}
                    }

                    typ, msg, v, err := getProtocolErrorInfo(payload)
                    if err != nil {
                        return &$deserError:T{Err: $fmtErrorf:T("get error info: %w", err)}
                    }

                    if len(typ) == 0 {
                        typ = "UnknownError"
                    }
                    if len(msg) == 0 {
                        msg = "UnknownError"
                    }

                    _ = v
                    // namespace can be mangled by service, so matching by error shape name
                    errorParts := strings.Split(typ, "#")
                    errorName := errorParts[len(errorParts)-1]
                    switch string(errorName) {
                    $errors:W
                    default:
                        $awsQueryCompatible:W
                        return &$genericAPIError:T{Code: typ, Message: msg}
                    }
                }
                """,
                MapUtils.of(
                        "cborDecode", SmithyGoTypes.Encoding.Cbor.Decode,
                        "cborMap", SmithyGoTypes.Encoding.Cbor.Map,
                        "cborString", SmithyGoTypes.Encoding.Cbor.String
                ),
                MapUtils.of(
                        "deserError", SmithyGoDependency.SMITHY.pointableSymbol("DeserializationError"),
                        "fmtErrorf", GoStdlibTypes.Fmt.Errorf,
                        "func", ProtocolGenerator.getOperationErrorDeserFunctionName(operation, service, "rpc2"),
                        "genericAPIError", SmithyGoDependency.SMITHY.pointableSymbol("GenericAPIError"),
                        "readAll", SmithyGoDependency.IO.func("ReadAll"),
                        "smithyhttpResponse", SmithyGoTypes.Transport.Http.Response,
                        "awsQueryCompatible", ctx.getService().hasTrait(AwsQueryCompatibleTrait.class)
                                ? deserializeAwsQueryError()
                                : emptyGoTemplate(),
                        "errors", ChainWritable.of(
                                operation.getErrors(service).stream()
                                        .map(it ->
                                                deserializeErrorCase(ctx, model.expectShape(it, StructureShape.class)))
                                        .toList()
                        ).compose(false)
                ));
    }

    private Writable deserializeErrorCase(GenerationContext ctx, StructureShape error) {
        return goTemplate("""
                case $errorType:S:
                    verr, err := $deserialize:L(v)
                    if err != nil {
                        return &$deserError:T{
                            Err: $fmtErrorf:T("deserialize $type:L: %w", err),
                            Snapshot: payload,
                        }
                    }
                    $awsQueryCompatible:W
                    return verr
                """,
                MapUtils.of(
                        "errorType", error.getId().getName(),
                        "deserError", SmithyGoDependency.SMITHY.pointableSymbol("DeserializationError"),
                        "deserialize", getDeserializerName(error),
                        "equalFold", SmithyGoDependency.STRINGS.func("EqualFold"),
                        "fmtErrorf", GoStdlibTypes.Fmt.Errorf,
                        "type", error.getId().toString(),
                        "awsQueryCompatible", ctx.getService().hasTrait(AwsQueryCompatibleTrait.class)
                                ? deserializeModeledAwsQueryError()
                                : emptyGoTemplate()
                ));
    }

    private String getShapeName(ShapeId id) {
        return id.getName() + "Error";
    }

    private Writable deserializeAwsQueryError() {
        return goTemplate("""
                if qtype := getAwsQueryErrorCode(resp); len(qtype) > 0 {
                    typ = qtype
                }""");
    }

    private Writable deserializeModeledAwsQueryError() {
        return goTemplate("""
                if qtype := getAwsQueryErrorCode(resp); len(qtype) > 0 {
                    verr.ErrorCodeOverride = $T(qtype)
                }""", SmithyGoTypes.Ptr.String);
    }
}
