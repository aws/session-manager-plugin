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

import java.util.List;
import java.util.Set;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase;

/**
 * Generates HTTP protocol unit tests for HTTP response API error test cases.
 */
public class HttpProtocolUnitTestResponseErrorGenerator extends HttpProtocolUnitTestResponseGenerator {
    protected final StructureShape errorShape;
    protected final Symbol errorSymbol;

    /**
     * Initializes the protocol test generator.
     *
     * @param builder the builder initializing the generator.
     */
    protected HttpProtocolUnitTestResponseErrorGenerator(Builder builder) {
        super(builder);
        this.errorShape = builder.error;
        this.errorSymbol = symbolProvider.toSymbol(errorShape);
    }

    /**
     * Provides the unit test function's format string.
     *
     * @return returns format string paired with unitTestFuncNameArgs
     */
    @Override
    protected String unitTestFuncNameFormat() {
        return "TestClient_$L_$L_$LDeserialize";
    }

    /**
     * Provides the unit test function name's format string arguments.
     *
     * @return returns a list of arguments used to format the unitTestFuncNameFormat returned format string.
     */
    @Override
    protected Object[] unitTestFuncNameArgs() {
        return new Object[]{opSymbol.getName(), errorSymbol.getName(), protocolName};
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

        // TODO vendorParams for requestID
        writer.write("ExpectError $P", errorSymbol);
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

        var errorParams = testCase.getParams();

        // inject query-compatible ErrorCodeOverride if present
        var vendorParamsShape = testCase.getVendorParamsShape();
        if (testCase.getHeaders().containsKey("x-amzn-query-error") && vendorParamsShape.isPresent()) {
            var vp = testCase.getVendorParams();
            errorParams = errorParams.withMember("ErrorCodeOverride", vp.expectStringMember("code").getValue());
        }

        writeStructField(writer, "ExpectError", errorShape, errorParams);
    }

    /**
     * Hook to generate the body of the test that will be invoked for all test cases of this operation. Should not
     * do any assertions.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestAssertions(GoWriter writer) {
        writeAssertNotNil(writer, "err");
        writeAssertNil(writer, "result");

        // Operation Metadata
        writer.openBlock("var opErr interface{", "}", () -> {
            writer.write("Service() string");
            writer.write("Operation() string");
        });
        writer.addUseImports(SmithyGoDependency.ERRORS);
        writer.openBlock("if !errors.As(err, &opErr) {", "}", () -> {
            writer.write("t.Fatalf(\"expect $P operation error, got %T\", err)", errorSymbol);
        });
        writer.openBlock("if e, a := ServiceID, opErr.Service(); e != a {", "}", () -> {
            writer.write("t.Errorf(\"expect %v operation service name, got %v\", e, a)");
        });
        writer.openBlock("if e, a := $S, opErr.Operation(); e != a {", "}", opSymbol.getName(), () -> {
            writer.write("t.Errorf(\"expect %v operation service name, got %v\", e, a)");
        });

        // Smithy API error
        writer.write("var actualErr $P", errorSymbol);
        writer.openBlock("if !errors.As(err, &actualErr) {", "}", () -> {
            writer.write("t.Fatalf(\"expect $P result error, got %T\", err)", errorSymbol);
        });

        writeAssertComplexEqual(writer, "c.ExpectError", "actualErr", new String[]{"middleware.Metadata{}"});

        // TODO assertion for protocol metadata
    }

    public static class Builder extends HttpProtocolUnitTestResponseGenerator.Builder {
        protected StructureShape error;

        // TODO should be a way not to define these override methods since they are all defined in the base Builder.
        // the return type breaks this though since this builder adds a new builder field.

        @Override
        public Builder model(Model model) {
            super.model(model);
            return this;
        }

        @Override
        public Builder symbolProvider(SymbolProvider symbolProvider) {
            super.symbolProvider(symbolProvider);
            return this;
        }

        @Override
        public Builder protocolName(String protocolName) {
            super.protocolName(protocolName);
            return this;
        }

        @Override
        public Builder service(ServiceShape service) {
            super.service(service);
            return this;
        }

        @Override
        public Builder operation(OperationShape operation) {
            super.operation(operation);
            return this;
        }

        public Builder error(StructureShape error) {
            this.error = error;
            return this;
        }

        @Override
        public Builder testCases(List<HttpResponseTestCase> testCases) {
            super.testCases(testCases);
            return this;
        }

        @Override
        public Builder addTestCases(List<HttpResponseTestCase> testCases) {
            super.addTestCases(testCases);
            return this;
        }

        @Override
        public Builder clientConfigValue(ConfigValue configValue) {
            super.clientConfigValue(configValue);
            return this;
        }

        @Override
        public Builder clientConfigValues(Set<ConfigValue> clientConfigValues) {
            super.clientConfigValues(clientConfigValues);
            return this;
        }

        @Override
        public Builder addClientConfigValues(Set<ConfigValue> clientConfigValues) {
            super.addClientConfigValues(clientConfigValues);
            return this;
        }

        @Override
        public Builder skipTest(SkipTest skipTest) {
            super.skipTest(skipTest);
            return this;
        }

        @Override
        public Builder skipTests(Set<SkipTest> skipTests) {
            super.skipTests(skipTests);
            return this;
        }

        @Override
        public Builder addSkipTests(Set<SkipTest> skipTests) {
            super.addSkipTests(skipTests);
            return this;
        }

        @Override
        public HttpProtocolUnitTestResponseErrorGenerator build() {
            return new HttpProtocolUnitTestResponseErrorGenerator(this);
        }
    }
}
