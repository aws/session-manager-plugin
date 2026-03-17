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
 *
 */

package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SmithyGoTypes.Private.RequestCompression.AddCaptureUncompressedRequest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.traits.RequestCompressionTrait;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates HTTP protocol unit tests for HTTP request test cases.
 */
public class HttpProtocolUnitTestRequestGenerator extends HttpProtocolUnitTestGenerator<HttpRequestTestCase> {
    private static final Logger LOGGER = Logger.getLogger(HttpProtocolUnitTestRequestGenerator.class.getName());

    private static final Set<String> ALLOWED_ALGORITHMS = new HashSet<>(Arrays.asList("gzip"));

    /**
     * Initializes the protocol test generator.
     *
     * @param builder the builder initializing the generator.
     */
    protected HttpProtocolUnitTestRequestGenerator(Builder builder) {
        super(builder);
    }

    /**
     * Provides the unit test function's format string.
     *
     * @return returns format string paired with unitTestFuncNameArgs
     */
    @Override
    protected String unitTestFuncNameFormat() {
        return "TestClient_$L_$LSerialize";
    }

    /**
     * Provides the unit test function name's format string arguments.
     *
     * @return returns a list of arguments used to format the unitTestFuncNameFormat returned format string.
     */
    @Override
    protected Object[] unitTestFuncNameArgs() {
        return new Object[]{opSymbol.getName(), protocolName};
    }

    /**
     * Hook to generate the parameter declarations as struct parameters into the test case's struct definition.
     * Must generate all test case parameters before returning.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestCaseParams(GoWriter writer) {
        writer.write("Params $P", inputSymbol);
        // TODO authScheme

        writer.write("ExpectMethod string");
        writer.write("ExpectURIPath string");

        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("ExpectQuery []smithytesting.QueryItem");
        writer.write("RequireQuery []string");
        writer.write("ForbidQuery []string");

        writer.addUseImports(SmithyGoDependency.NET_HTTP);
        writer.write("ExpectHeader http.Header");
        writer.write("RequireHeader []string");
        writer.write("ForbidHeader []string");

        writer.write("Host $P", SymbolUtils.createPointableSymbolBuilder("URL",
                SmithyGoDependency.NET_URL).build());

        writer.write("BodyMediaType string");
        writer.addUseImports(SmithyGoDependency.IO);
        writer.write("BodyAssert func(io.Reader) error");
    }

    /**
     * Hook to generate all the test case parameters as struct member values for a single test case.
     * Must generate all test case parameter values before returning.
     *
     * @param writer   writer to write generated code with.
     * @param testCase definition of a single test case.
     */
    @Override
    protected void generateTestCaseValues(GoWriter writer, HttpRequestTestCase testCase) {
        writeStructField(writer, "Params", inputShape, testCase.getParams());

        writeStructField(writer, "ExpectMethod", "$S", testCase.getMethod());
        writeStructField(writer, "ExpectURIPath", "$S", testCase.getUri());

        writeQueryItemsStructField(writer, "ExpectQuery", testCase.getQueryParams());
        writeStringSliceStructField(writer, "RequireQuery", testCase.getRequireQueryParams());
        writeStringSliceStructField(writer, "ForbidQuery", testCase.getForbidQueryParams());

        writeHeaderStructField(writer, "ExpectHeader", testCase.getHeaders());
        writeStringSliceStructField(writer, "RequireHeader", testCase.getRequireHeaders());
        writeStringSliceStructField(writer, "ForbidHeader", testCase.getForbidHeaders());

        if (testCase.getHost().isPresent()) {
            writeStructField(writer, "Host", (w) -> {
                var hostValue = testCase.getHost()
                        .orElse("");
                if (hostValue.length() == 0) {
                    w.write("nil,");
                    return;
                }
                if (hostValue.split("://", 2).length == 1) {
                    hostValue = "https://" + hostValue;
                }
                w.pushState();
                w.putContext("url", SymbolUtils.createPointableSymbolBuilder("URL",
                        SmithyGoDependency.NET_URL).build());
                w.putContext("parse", SymbolUtils.createPointableSymbolBuilder("Parse",
                        SmithyGoDependency.NET_URL).build());
                w.putContext("host", hostValue);
                w.write("""
                        func () $url:P {
                            host := $host:S
                            if len(host) == 0 {
                                return nil
                            }
                            u, err := $parse:T(host)
                            if err != nil {
                                panic(err)
                            }
                            return u
                        }(),""");
                w.popState();
            });
        }

        String bodyMediaType = "";
        if (testCase.getBodyMediaType().isPresent()) {
            bodyMediaType = testCase.getBodyMediaType().get();
            writeStructField(writer, "BodyMediaType", "$S", bodyMediaType);
        }
        if (testCase.getBody().isPresent()) {
            String body = testCase.getBody().get();

            writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
            writer.addUseImports(SmithyGoDependency.IO);
            if (body.length() == 0) {
                writeStructField(writer, "BodyAssert", "func(actual io.Reader) error {\n"
                                                       + "   return smithytesting.CompareReaderEmpty(actual)\n"
                                                       + "}");
            } else {
                String compareFunc = "";
                switch (bodyMediaType.toLowerCase()) {
                    case "application/json":
                        compareFunc = String.format(
                                "return smithytesting.CompareJSONReaderBytes(actual, []byte(`%s`))",
                                body);
                        break;
                    case "application/xml":
                        compareFunc = String.format(
                                "return smithytesting.CompareXMLReaderBytes(actual, []byte(`%s`))",
                                body);
                        break;
                    case "application/x-www-form-urlencoded":
                        compareFunc = String.format(
                                "return smithytesting.CompareURLFormReaderBytes(actual, []byte(`%s`))",
                                body);
                        break;
                    case "application/cbor":
                        compareFunc = String.format(
                                "return smithytesting.CompareCBOR(actual, `%s`)",
                                body);
                        break;
                    default:
                        compareFunc = String.format(
                                "return smithytesting.CompareReaderBytes(actual, []byte(`%s`))",
                                body);
                        break;

                }
                writeStructField(writer, "BodyAssert", "func(actual io.Reader) error {\n $L \n}", compareFunc);
            }
        }
    }

    /**
     * Hook to optionally generate additional setup needed before the test body is created.
     *
     * @param writer writer to write generated code with.
     */
    protected void generateTestBodySetup(GoWriter writer) {
        writer.write("actualReq := &http.Request{}");
        if (operation.hasTrait(RequestCompressionTrait.class)) {
            writer.addUseImports(SmithyGoDependency.BYTES);
            writer.write("rawBodyBuf := &bytes.Buffer{}");
        }
    }

    /**
     * Hook to generate the HTTP response body of the protocol test.
     *
     * @param writer writer to write generated code with.
     */
    protected void generateTestServerHandler(GoWriter writer) {
        super.generateTestServerHandler(writer);
    }

    /**
     * Hook to generate the body of the test that will be invoked for all test cases of this operation. Should not
     * do any assertions.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestInvokeClientOperation(GoWriter writer, String clientName) {
        Symbol stackSymbol = SymbolUtils.createPointableSymbolBuilder("Stack",
                SmithyGoDependency.SMITHY_MIDDLEWARE).build();
        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.openBlock("result, err := $L.$T(context.Background(), c.Params, func(options *Options) {", "})",
            clientName, opSymbol, () -> {
                writer.openBlock("options.APIOptions = append(options.APIOptions, func(stack $P) error {", "})",
                    stackSymbol, () -> {
                            writer.write("return $T(stack, actualReq)",
                            SymbolUtils.createValueSymbolBuilder("AddCaptureRequestMiddleware",
                            SmithyGoDependency.SMITHY_PRIVATE_PROTOCOL).build());
                });
                if (operation.hasTrait(RequestCompressionTrait.class)) {
                    writer.write(goTemplate("""
                                options.APIOptions = append(options.APIOptions, func(stack $stack:P) error {
                                    return $captureRequest:T(stack, rawBodyBuf)
                                })
                                """,
                            MapUtils.of(
                                    "stack", SmithyGoTypes.Middleware.Stack,
                                    "captureRequest", AddCaptureUncompressedRequest
                            )));
                }
        });

        if (operation.hasTrait(RequestCompressionTrait.class)) {
            writer.write(goTemplate("""
                    disable := $client:L.Options().DisableRequestCompression
                    min := $client:L.Options().RequestMinCompressSizeBytes
                    """,
                    MapUtils.of(
                    "client", clientName
                    )));
        }
    }

    /**
     * Hook to generate the assertions for the operation's test cases. Will be in the same scope as the test body.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestAssertions(GoWriter writer) {
        writeAssertNil(writer, "err");
        writeAssertNotNil(writer, "result");

        writeAssertScalarEqual(writer, "c.ExpectMethod", "actualReq.Method", "method");
        writeAssertScalarEqual(writer, "c.ExpectURIPath", "actualReq.URL.RawPath", "path");

        writeQueryItemBreakout(writer, "actualReq.URL.RawQuery", "queryItems");

        writeAssertHasQuery(writer, "c.ExpectQuery", "queryItems");
        writeAssertRequireQuery(writer, "c.RequireQuery", "queryItems");
        writeAssertForbidQuery(writer, "c.ForbidQuery", "queryItems");

        writeAssertHasHeader(writer, "c.ExpectHeader", "actualReq.Header");
        writeAssertRequireHeader(writer, "c.RequireHeader", "actualReq.Header");
        writeAssertForbidHeader(writer, "c.ForbidHeader", "actualReq.Header");

        writer.openBlock("if c.BodyAssert != nil {", "}", () -> {
            writer.openBlock("if err := c.BodyAssert(actualReq.Body); err != nil {", "}", () -> {
                writer.write("t.Errorf(\"expect body equal, got %v\", err)");
            });
        });

        if (operation.hasTrait(RequestCompressionTrait.class)) {
            String algorithm = operation.expectTrait(RequestCompressionTrait.class).getEncodings()
                .stream().filter(it -> ALLOWED_ALGORITHMS.contains(it)).findFirst().get();
            writer.write(goTemplate("""
                if err := smithytesting.CompareCompressedBytes(rawBodyBuf, actualReq.Body,
                disable, min, $algorithm:S); err != nil {
                    t.Errorf("unzipped request body not match: %q", err)
                }
                """,
                MapUtils.of(
                    "algorithm", algorithm
            )));
        }
    }

    public static class Builder extends HttpProtocolUnitTestGenerator.Builder<HttpRequestTestCase> {
        @Override
        public HttpProtocolUnitTestRequestGenerator build() {
            return new HttpProtocolUnitTestRequestGenerator(this);
        }
    }

    @Override
    protected void generateTestServer(
            GoWriter writer,
            String name,
            Consumer<GoWriter> handler
    ) {
        // We aren't using a test server, but we do need a URL to set.
        writer.write("serverURL := \"http://localhost:8888/\"");
        writer.pushState();
        writer.putContext("parse", SymbolUtils.createValueSymbolBuilder("Parse", SmithyGoDependency.NET_URL)
                .build());
        writer.write("""
                     if c.Host != nil {
                         u, err := $parse:T(serverURL)
                         if err != nil {
                             t.Fatalf("expect no error, got %v", err)
                         }
                         u.Path = c.Host.Path
                         u.RawPath = c.Host.RawPath
                         u.RawQuery = c.Host.RawQuery
                         serverURL = u.String()
                     }""");
        writer.popState();
    }
}
