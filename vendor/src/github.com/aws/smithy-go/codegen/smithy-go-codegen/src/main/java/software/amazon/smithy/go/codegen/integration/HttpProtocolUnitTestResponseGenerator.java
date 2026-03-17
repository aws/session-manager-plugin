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

import java.util.function.Consumer;
import java.util.logging.Logger;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase;

/**
 * Generates HTTP protocol unit tests for HTTP response test cases.
 */
public class HttpProtocolUnitTestResponseGenerator extends HttpProtocolUnitTestGenerator<HttpResponseTestCase> {
    private static final Logger LOGGER = Logger.getLogger(HttpProtocolUnitTestResponseGenerator.class.getName());

    /**
     * Initializes the protocol test generator.
     *
     * @param builder the builder initializing the generator.
     */
    protected HttpProtocolUnitTestResponseGenerator(Builder builder) {
        super(builder);
    }

    /**
     * Provides the unit test function's format string.
     *
     * @return returns format string paired with unitTestFuncNameArgs
     */
    @Override
    protected String unitTestFuncNameFormat() {
        return "TestClient_$L_$LDeserialize";
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
        writer.write("StatusCode int");
        // TODO authScheme
        writer.addUseImports(SmithyGoDependency.NET_HTTP);

        writer.write("Header http.Header");
        // TODO why do these exist?
        // writer.write("RequireHeaders []string");
        // writer.write("ForbidHeaders []string");

        writer.write("BodyMediaType string");
        writer.write("Body []byte");

        writer.write("ExpectResult $P", outputSymbol);
    }

    /**
     * Hook to generate all the test case parameters as struct member values for a single test case.
     * Must generate all test case parameter values before returning.
     *
     * @param writer   writer to write generated code with.
     * @param testCase definition of a single test case.
     */
    @Override
    protected void generateTestCaseValues(GoWriter writer, HttpResponseTestCase testCase) {
        writeStructField(writer, "StatusCode", testCase.getCode());
        writeHeaderStructField(writer, "Header", testCase.getHeaders());

        testCase.getBodyMediaType().ifPresent(mediaType -> {
            writeStructField(writer, "BodyMediaType", "$S", mediaType);
        });
        testCase.getBody().ifPresent(body -> {
            var mediaType = testCase.getBodyMediaType().orElse("");
            if (mediaType.equalsIgnoreCase("application/cbor")) {
                writeStructField(writer, "Body", """
                        func() []byte {
                            p, err := $T.DecodeString(`$L`)
                            if err != nil {
                                panic(err)
                            }

                            return p
                        }()""", SmithyGoDependency.BASE64.func("StdEncoding"), body);
            } else {
                writeStructField(writer, "Body", "[]byte(`$L`)", body);
            }
        });

        writeStructField(writer, "ExpectResult", outputShape, testCase.getParams());
    }

    @Override
    protected void generateTestClient(GoWriter writer, String clientName) {
        writer.openBlock("$L := New(Options{", "})", clientName, () -> {
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            writer.openBlock("HTTPClient: smithyhttp.ClientDoFunc(func(r *http.Request) (*http.Response, error) {",
                    "}),", () -> generateResponse(writer));
            for (ConfigValue value : clientConfigValues) {
                writeStructField(writer, value.getName(), value.getValue());
            }
        });
    }

    @Override
    protected void generateTestServer(GoWriter writer, String name, Consumer<GoWriter> handler) {
        // We aren't using a test server, but we do need a URL to set.
        writer.write("serverURL := \"http://localhost:8888/\"");
    }

    /**
     * Generates a Response object to return for testing.
     *
     * @param writer The writer to write generated code with.
     */
    protected void generateResponse(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.NET_HTTP);
        writer.write("headers := http.Header{}");
        writer.openBlock("for k, vs := range c.Header {", "}", () -> {
            writer.openBlock("for _, v := range vs {", "}", () -> {
                writer.write("headers.Add(k, v)");
            });
        });

        writer.openBlock("if len(c.BodyMediaType) != 0 && len(headers.Values(\"Content-Type\")) == 0 {", "}", () -> {
            writer.write("headers.Set(\"Content-Type\", c.BodyMediaType)");
        });

        writer.openBlock("response := &http.Response{", "}", () -> {
            writer.write("StatusCode: c.StatusCode,");
            writer.write("Header: headers,");
            writer.write("Request: r,");
        });

        writer.addUseImports(SmithyGoDependency.BYTES);
        writer.addUseImports(SmithyGoDependency.IOUTIL);
        writer.openBlock("if len(c.Body) != 0 {", "} else {", () -> {
            writer.write("response.ContentLength = int64(len(c.Body))");
            writer.write("response.Body = ioutil.NopCloser(bytes.NewReader(c.Body))");
        });
        writer.openBlock("", "}", () -> {
            // We have to set this special sentinel value for no body, or anything that relies on there being
            // a value set will panic.
            writer.write("response.Body = http.NoBody");
        });

        writer.write("return response, nil");
    }

    /**
     * Hook to generate the body of the test that will be invoked for all test cases of this operation. Should not
     * do any assertions.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestInvokeClientOperation(GoWriter writer, String clientName) {
        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.write("var params $T", inputSymbol);
        writer.write("result, err := $L.$T(context.Background(), &params)", clientName, opSymbol);
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

        writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
        writeAssertComplexEqual(writer, "c.ExpectResult", "result", new String[]{"middleware.Metadata{}"});

        // TODO assertion for protocol metadata
    }

    public static class Builder extends HttpProtocolUnitTestGenerator.Builder<HttpResponseTestCase> {
        @Override
        public HttpProtocolUnitTestResponseGenerator build() {
            return new HttpProtocolUnitTestResponseGenerator(this);
        }
    }
}
