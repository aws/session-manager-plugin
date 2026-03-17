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
 *
 */

package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.CONTEXT;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_METRICS;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_MIDDLEWARE;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.TIME;

import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.Writable;

/**
 * Writable operationMetrics structure that records operation-specific metrics.
 */
public class OperationMetricsStruct implements Writable {
    private final String scope;

    public OperationMetricsStruct(String scope) {
        this.scope = scope;
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(ChainWritable.of(
                useDependencies(), generateStruct(), generateHelpers(), generateContextApis()
        ).compose());
    }

    private Writable useDependencies() {
        return writer -> writer
                .addUseImports(CONTEXT)
                .addUseImports(TIME)
                .addUseImports(SMITHY_METRICS)
                .addUseImports(SMITHY_MIDDLEWARE);
    }

    private Writable generateStruct() {
        return goTemplate("""
                type operationMetrics struct {
                    Duration                metrics.Float64Histogram
                    SerializeDuration       metrics.Float64Histogram
                    ResolveIdentityDuration metrics.Float64Histogram
                    ResolveEndpointDuration metrics.Float64Histogram
                    SignRequestDuration     metrics.Float64Histogram
                    DeserializeDuration     metrics.Float64Histogram
                }
                """);
    }

    private Writable generateContextApis() {
        return goTemplate("""
                type operationMetricsKey struct{}

                func withOperationMetrics(parent context.Context, mp metrics.MeterProvider) (context.Context, error) {
                    if _, ok := mp.(metrics.NopMeterProvider); ok {
                        // not using the metrics system - setting up the metrics context is a memory-intensive operation
                        // so we should skip it in this case
                        return parent, nil
                    }

                    meter := mp.Meter($S)
                    om := &operationMetrics{}

                    var err error

                    om.Duration, err = operationMetricTimer(meter, "client.call.duration",
                        "Overall call duration (including retries and time to send or receive request and response body)")
                    if err != nil {
                        return nil, err
                    }
                    om.SerializeDuration, err = operationMetricTimer(meter, "client.call.serialization_duration",
                        "The time it takes to serialize a message body")
                    if err != nil {
                        return nil, err
                    }
                    om.ResolveIdentityDuration, err = operationMetricTimer(meter, "client.call.auth.resolve_identity_duration",
                        "The time taken to acquire an identity (AWS credentials, bearer token, etc) from an Identity Provider")
                    if err != nil {
                        return nil, err
                    }
                    om.ResolveEndpointDuration, err = operationMetricTimer(meter, "client.call.resolve_endpoint_duration",
                        "The time it takes to resolve an endpoint (endpoint resolver, not DNS) for the request")
                    if err != nil {
                        return nil, err
                    }
                    om.SignRequestDuration, err = operationMetricTimer(meter, "client.call.auth.signing_duration",
                        "The time it takes to sign a request")
                    if err != nil {
                        return nil, err
                    }
                    om.DeserializeDuration, err = operationMetricTimer(meter, "client.call.deserialization_duration",
                        "The time it takes to deserialize a message body")
                    if err != nil {
                        return nil, err
                    }

                    return context.WithValue(parent, operationMetricsKey{}, om), nil
                }

                func operationMetricTimer(m metrics.Meter, name, desc string) (metrics.Float64Histogram, error) {
                    return m.Float64Histogram(name, func(o *metrics.InstrumentOptions) {
                        o.UnitLabel = "s"
                        o.Description = desc
                    })
                }

                func getOperationMetrics(ctx context.Context) *operationMetrics {
                    if v := ctx.Value(operationMetricsKey{}); v != nil {
                        return v.(*operationMetrics)
                    }
                    return nil
                }
                """, scope);
    }

    private Writable generateHelpers() {
        return goTemplate("""
                func (m *operationMetrics) histogramFor(name string) metrics.Float64Histogram {
                    switch name {
                    case "client.call.duration":
                        return m.Duration
                    case "client.call.serialization_duration":
                        return m.SerializeDuration
                    case "client.call.resolve_identity_duration":
                        return m.ResolveIdentityDuration
                    case "client.call.resolve_endpoint_duration":
                        return m.ResolveEndpointDuration
                    case "client.call.signing_duration":
                        return m.SignRequestDuration
                    case "client.call.deserialization_duration":
                        return m.DeserializeDuration
                    default:
                        panic("unrecognized operation metric")
                    }
                }

                func timeOperationMetric[T any](
                    ctx context.Context, metric string, fn func() (T, error),
                    opts ...metrics.RecordMetricOption,
                ) (T, error) {
                    mm := getOperationMetrics(ctx)
                    if mm == nil { // not using the metrics system
                        return fn()
                    }

                    instr := mm.histogramFor(metric)
                    opts = append([]metrics.RecordMetricOption{withOperationMetadata(ctx)}, opts...)

                    start := time.Now()
                    v, err := fn()
                    end := time.Now()

                    elapsed := end.Sub(start)
                    instr.Record(ctx, float64(elapsed)/1e9, opts...)
                    return v, err
                }

                func startMetricTimer(ctx context.Context, metric string, opts ...metrics.RecordMetricOption) func() {
                    mm := getOperationMetrics(ctx)
                    if mm == nil { // not using the metrics system
                        return func() {}
                    }

                    instr := mm.histogramFor(metric)
                    opts = append([]metrics.RecordMetricOption{withOperationMetadata(ctx)}, opts...)

                    var ended bool
                    start := time.Now()
                    return func() {
                        if ended {
                            return
                        }
                        ended = true

                        end := time.Now()

                        elapsed := end.Sub(start)
                        instr.Record(ctx, float64(elapsed)/1e9, opts...)
                    }
                }

                func withOperationMetadata(ctx context.Context) metrics.RecordMetricOption {
                    return func(o *metrics.RecordMetricOptions) {
                        o.Properties.Set("rpc.service", middleware.GetServiceID(ctx))
                        o.Properties.Set("rpc.method", middleware.GetOperationName(ctx))
                    }
                }
                """);
    }
}
