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

import static software.amazon.smithy.go.codegen.GoWriter.autoDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.auth.AuthSchemeResolverGenerator;
import software.amazon.smithy.go.codegen.auth.GetIdentityMiddlewareGenerator;
import software.amazon.smithy.go.codegen.auth.ResolveAuthSchemeMiddlewareGenerator;
import software.amazon.smithy.go.codegen.auth.SignRequestMiddlewareGenerator;
import software.amazon.smithy.go.codegen.endpoints.EndpointMiddlewareGenerator;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ClientMember;
import software.amazon.smithy.go.codegen.integration.ClientMemberResolver;
import software.amazon.smithy.go.codegen.integration.ConfigFieldResolver;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.OperationMetricsStruct;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates a service client, its constructors, and core supporting logic.
 */
final class ServiceGenerator implements Runnable {

    public static final String CONFIG_NAME = "Options";

    private final GoSettings settings;
    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final ServiceShape service;
    private final List<GoIntegration> integrations;
    private final List<RuntimeClientPlugin> runtimePlugins;
    private final ApplicationProtocol applicationProtocol;
    private final Map<ShapeId, AuthSchemeDefinition> authSchemes;

    ServiceGenerator(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            ServiceShape service,
            List<GoIntegration> integrations,
            List<RuntimeClientPlugin> runtimePlugins,
            ApplicationProtocol applicationProtocol
    ) {
        this.settings = settings;
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.service = service;
        this.integrations = integrations;
        this.runtimePlugins = runtimePlugins;
        this.applicationProtocol = applicationProtocol;
        this.authSchemes = integrations.stream()
                .flatMap(it -> it.getClientPlugins(model, service).stream())
                .flatMap(it -> it.getAuthSchemeDefinitions().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void run() {
        writer.write(generate());
        writeProtocolResolverImpls();
    }

    private Writable generate() {
        return ChainWritable.of(
                generateMetadata(),
                generateObservabilityComponents(),
                generateClient(),
                generateNew(),
                generateGetOptions(),
                generateInvokeOperation(),
                generateEventStreamInvokeOperation(),
                asyncStreamReaders(),
                generateInputContextFuncs(),
                generateAddProtocolFinalizerMiddleware()
        ).compose();
    }

    private Writable generateObservabilityComponents() {
        return goTemplate("""
                $operationMetrics:W

                func operationTracer(p $tracerProvider:T) $tracer:T {
                    return p.Tracer($scope:S)
                }
                """,
                Map.of(
                        "tracerProvider", SmithyGoDependency.SMITHY_TRACING.interfaceSymbol("TracerProvider"),
                        "tracer", SmithyGoDependency.SMITHY_TRACING.interfaceSymbol("Tracer"),
                        "scope", settings.getModuleName(),
                        "operationMetrics", new OperationMetricsStruct(settings.getModuleName())
                ));
    }

    private Writable generateMetadata() {
        var serviceId = settings.getService().toString();
        for (var integration : integrations) {
            serviceId = integration.processServiceId(settings, model, serviceId);
        }

        return goTemplate("""
                const ServiceID = $S
                const ServiceAPIVersion = $S
                """, serviceId, service.getVersion());
    }

    private Writable generateClient() {
        return goTemplate("""
                $W
                type $T struct {
                    options $L

                    $W
                }
                """,
                generateClientDocs(),
                symbolProvider.toSymbol(service),
                CONFIG_NAME,
                ChainWritable.of(
                        getAllClientMembers().stream()
                                .map(this::generateClientMember)
                                .toList()
                ).compose());
    }

    private Writable generateClientDocs() {
        return writer ->
            writer.writeDocs(String.format(
                    "%s provides the API client to make operations call for %s.",
                    symbolProvider.toSymbol(service).getName(),
                    CodegenUtils.getServiceTitle(service, "the API")
            ));
    }

    private Writable generateClientMember(ClientMember member) {
        return goTemplate("""
                $W
                $L $P
                """,
                member.getDocumentation().isPresent()
                        ? goDocTemplate(member.getDocumentation().get())
                        : emptyGoTemplate(),
                member.getName(),
                member.getType());
    }

    private Writable generateNew() {
        var plugins = runtimePlugins.stream()
                .filter(it -> it.matchesService(model, service))
                .toList();
        var serviceSymbol = symbolProvider.toSymbol(service);
        var docs = goDocTemplate(
                "New returns an initialized $name:L based on the functional options. Provide "
                + "additional functional options to further configure the behavior of the client, such as changing the "
                + "client's endpoint or adding custom middleware behavior.",
                MapUtils.of("name", serviceSymbol.getName()));
        return goTemplate("""
                $docs:W
                func New(options $options:L, optFns ...func(*$options:L)) *$client:L {
                    options = options.Copy()

                    $resolvers:W

                    $protocolResolvers:W

                    for _, fn := range optFns {
                        fn(&options)
                    }

                    $finalizers:W

                    $protocolFinalizers:W

                    client := &$client:L{
                        options: options,
                    }

                    $withClientFinalizers:W

                    $clientMemberResolvers:W

                    return client
                }
                """, MapUtils.of(
                        "docs", docs,
                        "options", CONFIG_NAME,
                        "client", serviceSymbol.getName(),
                        "protocolResolvers", generateProtocolResolvers(),
                        "protocolFinalizers", generateProtocolFinalizers(),
                        "resolvers", ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.CLIENT,
                                        ConfigFieldResolver.Target.INITIALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "finalizers", ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.CLIENT,
                                        ConfigFieldResolver.Target.FINALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "withClientFinalizers", ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.CLIENT,
                                        ConfigFieldResolver.Target.FINALIZATION_WITH_CLIENT
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "clientMemberResolvers", ChainWritable.of(
                                plugins.stream()
                                        .flatMap(it -> it.getClientMemberResolvers().stream())
                                        .map(this::generateClientMemberResolver)
                                        .toList()
                        ).compose()
                ));
    }

    private Writable generateGetOptions() {
        var docs = autoDocTemplate("""
                Options returns a copy of the client configuration.

                Callers SHOULD NOT perform mutations on any inner structures within client config. Config overrides
                should instead be made on a per-operation basis through functional options.""");
        return goTemplate("""
                $W
                func (c $P) Options() $L {
                    return c.options.Copy()
                }
                """, docs, symbolProvider.toSymbol(service), ClientOptions.NAME);
    }

    private Writable generateConfigFieldResolver(ConfigFieldResolver resolver) {
        return writer -> {
                writer.writeInline("$T(&options", resolver.getResolver());
                if (resolver.isWithOperationName()) {
                    writer.writeInline(", opID");
                }
                if (resolver.isWithClientInput()) {
                    if (resolver.getLocation() == ConfigFieldResolver.Location.CLIENT) {
                        writer.writeInline(", client");
                    } else {
                        writer.writeInline(", *c");
                    }
                }
                writer.write(")");
        };
    }

    private Writable generateClientMemberResolver(ClientMemberResolver resolver) {
        return goTemplate("$T(client)", resolver.getResolver());
    }

    private List<ClientMember> getAllClientMembers() {
        List<ClientMember> clientMembers = new ArrayList<>();
        for (RuntimeClientPlugin runtimeClientPlugin : runtimePlugins) {
            if (!runtimeClientPlugin.matchesService(model, service)) {
                continue;
            }

            clientMembers.addAll(runtimeClientPlugin.getClientMembers());
        }
        return clientMembers.stream()
                .distinct()
                .sorted(Comparator.comparing(ClientMember::getName))
                .collect(Collectors.toList());
    }

    private Writable generateProtocolResolvers() {
        ensureSupportedProtocol();
        return goTemplate("""
                resolveAuthSchemeResolver(&options)
                """);
    }

    private Writable generateProtocolFinalizers() {
        ensureSupportedProtocol();
        return goTemplate("""
                resolveAuthSchemes(&options)
                """);
    }

    private void writeProtocolResolverImpls() {
        ensureSupportedProtocol();

        var schemeMappings = ChainWritable.of(
                ServiceIndex.of(model)
                        .getEffectiveAuthSchemes(service).keySet().stream()
                        .filter(authSchemes::containsKey)
                        .map(authSchemes::get)
                        .map(it -> goTemplate("$W, ", it.generateDefaultAuthScheme()))
                        .toList()
        ).compose(false);

        writer.write("""
                func resolveAuthSchemeResolver(options *Options) {
                    if options.AuthSchemeResolver == nil {
                        options.AuthSchemeResolver = &$L{}
                    }
                }

                func resolveAuthSchemes(options *Options) {
                    if options.AuthSchemes == nil {
                        options.AuthSchemes = []$T{
                            $W
                        }
                    }
                }
                """,
                AuthSchemeResolverGenerator.DEFAULT_NAME,
                SmithyGoTypes.Transport.Http.AuthScheme,
                schemeMappings);
    }

    private Writable generateInvokeOperation() {
        return goTemplate("""
                $middleware:D $tracing:D
                func (c *Client) invokeOperation(
                    ctx context.Context, opID string, params interface{}, optFns []func(*Options), stackFns ...func(*middleware.Stack, Options) error,
                ) (
                    result interface{}, metadata middleware.Metadata, err error,
                ) {
                    ctx = middleware.ClearStackValues(ctx)
                    ctx = middleware.WithServiceID(ctx, ServiceID)
                    ctx = middleware.WithOperationName(ctx, opID)

                    $newStack:W
                    options := c.options.Copy()
                    $resolvers:W

                    for _, fn := range optFns {
                        fn(&options)
                    }

                    $finalizers:W

                    for _, fn := range stackFns {
                        if err := fn(stack, options); err != nil {
                            return nil, metadata, err
                        }
                    }

                    for _, fn := range options.APIOptions {
                        if err := fn(stack); err != nil {
                            return nil, metadata, err
                        }
                    }

                    ctx, err = withOperationMetrics(ctx, options.MeterProvider)
                    if err != nil {
                        return nil, metadata, err
                    }

                    tracer := operationTracer(options.TracerProvider)
                    spanName := fmt.Sprintf("%s.%s", ServiceID, opID)

                    ctx = tracing.WithOperationTracer(ctx, tracer)

                    ctx, span := tracer.StartSpan(ctx, spanName, func (o *tracing.SpanOptions) {
                        o.Kind = tracing.SpanKindClient
                        o.Properties.Set("rpc.system", "aws-api")
                        o.Properties.Set("rpc.method", opID)
                        o.Properties.Set("rpc.service", ServiceID)
                    })
                    endTimer := startMetricTimer(ctx, "client.call.duration")
                    defer endTimer()
                    defer span.End()

                    handler := $newClientHandler:T(options.HTTPClient, func(o *smithyhttp.ClientHandler) {
                        o.Meter = options.MeterProvider.Meter($scope:S)
                    })
                    decorated := middleware.DecorateHandler(handler, stack)
                    result, metadata, err = decorated.Handle(ctx, params)
                    if err != nil {
                        span.SetProperty("exception.type", fmt.Sprintf("%T", err))
                        span.SetProperty("exception.message", err.Error())

                        var aerr smithy.APIError
                        if $errors.As:T(err, &aerr) {
                            span.SetProperty("api.error_code", aerr.ErrorCode())
                            span.SetProperty("api.error_message", aerr.ErrorMessage())
                            span.SetProperty("api.error_fault", aerr.ErrorFault().String())
                        }

                        err = &$operationError:T{
                            ServiceID: ServiceID,
                            OperationName: opID,
                            Err: err,
                        }
                    }

                    span.SetProperty("error", err != nil)
                    if err == nil {
                        span.SetStatus(tracing.SpanStatusOK)
                    } else {
                        span.SetStatus(tracing.SpanStatusError)
                    }

                    return result, metadata, err
                }
                """,
                MapUtils.of(
                        "middleware", SmithyGoDependency.SMITHY_MIDDLEWARE,
                        "tracing", SmithyGoDependency.SMITHY_TRACING,
                        "newStack", generateNewStack(),
                        "operationError", SmithyGoTypes.Smithy.OperationError,
                        "resolvers", ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.OPERATION,
                                        ConfigFieldResolver.Target.INITIALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "finalizers", ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.OPERATION,
                                        ConfigFieldResolver.Target.FINALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "newClientHandler", SmithyGoDependency.SMITHY_HTTP_TRANSPORT.func("NewClientHandlerWithOptions"),
                        "scope", settings.getModuleName()
                ));
    }

    private Writable generateEventStreamInvokeOperation() {
         if (!hasV2EventStreamOperations()) {
            return emptyGoTemplate();
        }
        return goTemplate("""
                $middleware:D $tracing:D
                func (c *Client) invokeEventStreamOperation(
                    ctx context.Context, opID string, params interface{}, optFns []func(*Options), stackFns ...func(*middleware.Stack, Options) error,
                ) (
                    result interface{}, metadata middleware.Metadata, err error,
                ) {
                    ctx = middleware.ClearStackValues(ctx)
                    ctx = middleware.WithServiceID(ctx, ServiceID)
                    ctx = middleware.WithOperationName(ctx, opID)

                    $newStack:W
                    options := c.options.Copy()
                    $resolvers:W

                    for _, fn := range optFns {
                        fn(&options)
                    }

                    $finalizers:W

                    for _, fn := range stackFns {
                        if err := fn(stack, options); err != nil {
                            return nil, metadata, err
                        }
                    }

                    for _, fn := range options.APIOptions {
                        if err := fn(stack); err != nil {
                            return nil, metadata, err
                        }
                    }

                    ctx, err = withOperationMetrics(ctx, options.MeterProvider)
                    if err != nil {
                        return nil, metadata, err
                    }

                    tracer := operationTracer(options.TracerProvider)
                    spanName := fmt.Sprintf("%s.%s", ServiceID, opID)

                    ctx = tracing.WithOperationTracer(ctx, tracer)

                    ctx, span := tracer.StartSpan(ctx, spanName, func (o *tracing.SpanOptions) {
                        o.Kind = tracing.SpanKindClient
                        o.Properties.Set("rpc.system", "aws-api")
                        o.Properties.Set("rpc.method", opID)
                        o.Properties.Set("rpc.service", ServiceID)
                    })
                    endTimer := startMetricTimer(ctx, "client.call.duration")
                    defer endTimer()
                    defer span.End()

                    handler := $newClientHandler:T(options.HTTPClient, func(o *smithyhttp.ClientHandler) {
                        o.Meter = options.MeterProvider.Meter($scope:S)
                    })
                    decorated := middleware.DecorateHandler(handler, stack)
                    // create a channel that returns immediately as soon as the request to the server is made
                    results := make(chan PartialResult, 1)
                    ctx = context.WithValue(ctx, partialResultChan{}, results)
                    go func() {
                           _, _, asyncErr := decorated.Handle(ctx, params)
                           if asyncErr != nil {
                                   span.SetProperty("exception.type", fmt.Sprintf("%T", asyncErr))
                                   span.SetProperty("exception.message", asyncErr.Error())

                                   var aerr smithy.APIError
                                   if errors.As(asyncErr, &aerr) {
                                           span.SetProperty("api.error_code", aerr.ErrorCode())
                                           span.SetProperty("api.error_message", aerr.ErrorMessage())
                                           span.SetProperty("api.error_fault", aerr.ErrorFault().String())
                                   }

                                   asyncErr = &smithy.OperationError{
                                           ServiceID:     ServiceID,
                                           OperationName: opID,
                                           Err:           asyncErr,
                                   }
                           }
                           span.SetProperty("error", asyncErr != nil)
                           if asyncErr == nil {
                                   span.SetStatus(tracing.SpanStatusOK)
                           } else {
                                   span.SetStatus(tracing.SpanStatusError)
                           }
                    }()
                    res := <-results
                    return res.Output, res.Metadata, res.Error
                }
                """,
                MapUtils.of(
                        "middleware", SmithyGoDependency.SMITHY_MIDDLEWARE,
                        "tracing", SmithyGoDependency.SMITHY_TRACING,
                        "newStack", generateNewStack(),
                        "operationError", SmithyGoTypes.Smithy.OperationError,
                        "resolvers", ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.OPERATION,
                                        ConfigFieldResolver.Target.INITIALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "finalizers", ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.OPERATION,
                                        ConfigFieldResolver.Target.FINALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "newClientHandler", SmithyGoDependency.SMITHY_HTTP_TRANSPORT.func("NewClientHandlerWithOptions"),
                        "scope", settings.getModuleName()
                ));
    }

    private Writable asyncStreamReaders() {
        if (!hasV2EventStreamOperations()) {
            return emptyGoTemplate();
        }

        return goTemplate("""
                $D $D

                type partialResultChan struct{
                }

				type deserializeResult struct {
				       reader io.ReadCloser
				       err    error
				}

				type asyncEventStreamReader struct {
				       pipeReader *io.PipeReader
				       pipeWriter *io.PipeWriter
				}

				func newAsyncEventStreamReader(resultChan <-chan deserializeResult) *asyncEventStreamReader {
				       pipeReader, pipeWriter := io.Pipe()

				       reader := &asyncEventStreamReader{
				               pipeReader: pipeReader,
				               pipeWriter: pipeWriter,
				       }

				       // Start background copying
				       go func() {
				               for result := range resultChan {
				                       if result.err != nil {
				                               // consume the error, this can be retried
				                               // and if we close the pipeline, it will prevent us
				                               // from retrying
				                               continue
				                       }

				                       // Copy response body to pipe
				                       _, err := io.Copy(pipeWriter, result.reader)
				                       pipeWriter.CloseWithError(err)
				               }
				       }()

				       return reader
				}

				// PartialResult represents a placeholder value to return
				// immediately when calling an event streaming operation. This contains no
				// meaningful result for the caller
				type PartialResult struct {
				       Output   any
				       Metadata middleware.Metadata
				       Error    error
				}
            """, SmithyGoDependency.SMITHY_MIDDLEWARE, SmithyGoDependency.IO);
    }

    private Writable generateNewStack() {
        ensureSupportedProtocol();
        return goTemplate("stack := $T(opID, $T)",
                SmithyGoTypes.Middleware.NewStack, SmithyGoTypes.Transport.Http.NewStackRequest);
    }

    private void ensureSupportedProtocol() {
        if (!applicationProtocol.isHttpProtocol()) {
            throw new UnsupportedOperationException(
                    "Protocols other than HTTP are not yet implemented: " + applicationProtocol);
        }
    }

    private Stream<ConfigFieldResolver> getConfigResolvers(
            ConfigFieldResolver.Location location, ConfigFieldResolver.Target target
    ) {
        return runtimePlugins.stream()
                .filter(it -> it.matchesService(model, service))
                .flatMap(it -> it.getConfigFieldResolvers().stream())
                .filter(it -> it.getLocation() == location && it.getTarget() == target);
    }

    private Writable generateInputContextFuncs() {
        return goTemplate("""
                type operationInputKey struct{}

                func setOperationInput(ctx $1T, input interface{}) $1T {
                    return $2T(ctx, operationInputKey{}, input)
                }

                func getOperationInput(ctx $1T) interface{} {
                    return $3T(ctx, operationInputKey{})
                }

                $4W
                """,
                GoStdlibTypes.Context.Context,
                SmithyGoTypes.Middleware.WithStackValue,
                SmithyGoTypes.Middleware.GetStackValue,
                new SetOperationInputContextMiddleware().generate());
    }

    private Writable generateAddProtocolFinalizerMiddleware() {
        ensureSupportedProtocol();
        return goTemplate("""
                func addProtocolFinalizerMiddlewares(stack $P, options $L, operation string) error {
                    $W
                    return nil
                }
                """,
                SmithyGoTypes.Middleware.Stack,
                CONFIG_NAME,
                ChainWritable.of(
                        ResolveAuthSchemeMiddlewareGenerator.generateAddToProtocolFinalizers(),
                        GetIdentityMiddlewareGenerator.generateAddToProtocolFinalizers(),
                        EndpointMiddlewareGenerator.generateAddToProtocolFinalizers(),
                        SignRequestMiddlewareGenerator.generateAddToProtocolFinalizers()
                ).compose(false));
    }

    /**
     * Checks if the service has any v2 (non-legacy) event stream operations.
     */
    private boolean hasV2EventStreamOperations() {
        return TopDownIndex.of(model).getContainedOperations(service).stream()
                .anyMatch(operation -> EventStreamGenerator.hasEventStream(model, operation)
                        && !EventStreamGenerator.isLegacyEventStreamGenerator(operation));
    }
}
