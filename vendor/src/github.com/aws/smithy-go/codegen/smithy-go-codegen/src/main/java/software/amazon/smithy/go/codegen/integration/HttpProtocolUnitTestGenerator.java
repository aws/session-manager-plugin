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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.ShapeValueGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.IdempotencyTokenTrait;
import software.amazon.smithy.protocoltests.traits.AppliesTo;
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * Abstract base implementation for protocol test generators to extend in order to generate HttpMessageTestCase
 * specific protocol tests.
 *
 * @param <T> Specific HttpMessageTestCase the protocol test generator is for.
 */
public abstract class HttpProtocolUnitTestGenerator<T extends HttpMessageTestCase> {
    private static final Logger LOGGER = Logger.getLogger(HttpProtocolUnitTestGenerator.class.getName());

    protected final GoSettings settings;
    protected final Model model;
    protected final SymbolProvider symbolProvider;
    protected final List<T> testCases;
    protected final ServiceShape service;
    protected final OperationShape operation;
    protected final Symbol opSymbol;
    protected final StructureShape inputShape;
    protected final Symbol inputSymbol;
    protected final StructureShape outputShape;
    protected final Symbol outputSymbol;
    protected final String protocolName;
    protected final Set<ConfigValue> clientConfigValues = new TreeSet<>();
    protected final Set<SkipTest> skipTests = new TreeSet<>();
    protected final ShapeValueGenerator.Config shapeValueGeneratorConfig;

    /**
     * Initializes the abstract protocol tests generator.
     *
     * @param builder the builder initializing the generator.
     */
    protected HttpProtocolUnitTestGenerator(Builder<T> builder) {
        this.settings = SmithyBuilder.requiredState("settings", builder.settings);
        this.model = SmithyBuilder.requiredState("model", builder.model);
        this.symbolProvider = SmithyBuilder.requiredState("symbolProvider", builder.symbolProvider);
        this.protocolName = SmithyBuilder.requiredState("protocolName", builder.protocolName);
        this.service = SmithyBuilder.requiredState("service", builder.service);
        this.operation = SmithyBuilder.requiredState("operation", builder.operation);
        this.testCases = SmithyBuilder.requiredState("testCases", builder.testCases);
        this.clientConfigValues.addAll(builder.clientConfigValues);
        this.skipTests.addAll(builder.skipTests);
        this.shapeValueGeneratorConfig = SmithyBuilder.requiredState("config", builder.shapeValueGeneratorConfig);

        opSymbol = symbolProvider.toSymbol(operation);

        inputShape = model.expectShape(operation.getInput()
                        .orElseThrow(() -> new CodegenException("missing input shape for operation: "
                                + operation.getId())),
                StructureShape.class);
        inputSymbol = symbolProvider.toSymbol(inputShape);

        outputShape = model.expectShape(operation.getOutput()
                        .orElseThrow(() -> new CodegenException("missing output shape for operation: "
                                + operation.getId())),
                StructureShape.class);
        outputSymbol = symbolProvider.toSymbol(outputShape);
    }

    /**
     * Provides the unit test function's format string.
     *
     * @return returns format string paired with unitTestFuncNameArgs
     */
    abstract String unitTestFuncNameFormat();

    /**
     * Provides the unit test function name's format string arguments.
     *
     * @return returns a list of arguments used to format the unitTestFuncNameFormat returned format string.
     */
    abstract Object[] unitTestFuncNameArgs();


    /**
     * Hook to provide custom generated code within a test function before test cases are defined.
     *
     * @param writer writer to write generated code with.
     */
    protected void generateTestSetup(GoWriter writer) {
        // Pass
    }

    /**
     * Hook to generate the parameter declarations as struct parameters into the test case's struct definition.
     * Must generate all test case parameters before returning.
     *
     * @param writer writer to write generated code with.
     */
    abstract void generateTestCaseParams(GoWriter writer);

    /**
     * Hook to generate all the test case parameters as struct member values for a single test case.
     * Must generate all test case parameter values before returning.
     *
     * @param writer   writer to write generated code with.
     * @param testCase definition of a single test case.
     */
    abstract void generateTestCaseValues(GoWriter writer, T testCase);

    /**
     * Hook to optionally generate additional setup needed before the test body is created.
     *
     * @param writer writer to write generated code with.
     */
    protected void generateTestBodySetup(GoWriter writer) {
        // pass
    }

    /**
     * Hook to generate the HTTP response body of the protocol test. If overriding and delegating to this method must
     * the last usage of ResponseWriter.
     *
     * @param writer writer to write generated code with.
     */
    protected void generateTestServerHandler(GoWriter writer) {
        writer.write("w.WriteHeader(200)");
    }

    /**
     * Hook to generate the HTTP test server that will receive requests and provide responses back to the requester.
     *
     * @param writer  writer to write generated code with.
     * @param name    test server variable name
     * @param handler lambda for writing handling of HTTP request
     */
    protected void generateTestServer(GoWriter writer, String name, Consumer<GoWriter> handler) {
        writer.addUseImports(SmithyGoDependency.NET_HTTP);
        writer.addUseImports(SmithyGoDependency.NET_HTTP_TEST);
        writer.openBlock("$L := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request){",
                "}))", name, () -> {
                    handler.accept(writer);
                });
        writer.write("defer $L.Close()", name);
        writer.write("serverURL := server.URL");
    }

    /**
     * Hook to generate the instance of the API client for the protocol test.
     *
     * @param writer     writer to write generated code with.
     * @param clientName test client variable name
     */
    protected void generateTestClient(GoWriter writer, String clientName) {
        writer.openBlock("$L := New(Options{", "})", clientName, () -> {
            for (ConfigValue value : clientConfigValues) {
                writeStructField(writer, value.getName(), value.getValue());
            }
        });
    }

    /**
     * Hook to generate the client invoking the API operation of the test. Should not do any assertions.
     *
     * @param writer     writer to write generated code with.
     * @param clientName name of the client variable.
     */
    abstract void generateTestInvokeClientOperation(GoWriter writer, String clientName);

    /**
     * Hook to generate the assertions for the operation's test cases. Will be in the same scope as the test body.
     *
     * @param writer writer to write generated code with.
     */
    abstract void generateTestAssertions(GoWriter writer);

    /**
     * Generates the test function for the operation using the provided writer.
     *
     * @param writer writer to write generated code with.
     */
    public void generateTestFunction(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.TESTING);
        writer.openBlock("func " + unitTestFuncNameFormat() + "(t *testing.T) {", "}", unitTestFuncNameArgs(),
                () -> {
                    skipTests.forEach((skipTest) -> {
                        if (skipTest.matches(service.getId(), operation.getId())) {
                            writer.write("t.Skip(\"disabled test $L $L\")", service.getId(), operation.getId());
                            writer.write("");
                        }
                    });
                    generateTestSetup(writer);

                    writer.write("cases := map[string]struct {");
                    generateTestCaseParams(writer);
                    writer.openBlock("}{", "}", () -> {
                        for (T testCase : testCases) {
                            Optional<AppliesTo> appliesTo = testCase.getAppliesTo();
                            if (appliesTo.isPresent() && !(appliesTo.get().equals(AppliesTo.CLIENT))) {
                                continue;
                            }

                            testCase.getDocumentation().ifPresent(writer::writeDocs);
                            writer.openBlock("$S: {", "},", testCase.getId(), () -> {
                                generateTestCaseValues(writer, testCase);
                            });
                        }
                    });

                    // And test case iteration/assertions
                    writer.openBlock("for name, c := range cases {", "}", () -> {
                        writer.openBlock("t.Run(name, func(t *testing.T) {", "})", () -> {
                            skipTests.forEach((skipTest) -> {
                                for (T testCase : testCases) {
                                    if (skipTest.matches(service.getId(), operation.getId(), testCase.getId())) {
                                        writer.openBlock("if name == $S {", "}", testCase.getId(), () -> {
                                            writer.write("t.Skip(\"disabled test $L $L\")", service.getId(),
                                                    operation.getId());
                                        });
                                        writer.write("");
                                    }
                                }
                            });

                            generateTestBodySetup(writer);
                            generateTestServer(writer, "server", this::generateTestServerHandler);
                            generateTestClient(writer, "client");
                            generateTestInvokeClientOperation(writer, "client");
                            generateTestAssertions(writer);
                        });
                    });
                });
    }

    /**
     * Writes a single Go structure field key and value.
     *
     * @param writer writer to write generated code with.
     * @param field  the field name of the struct member.
     * @param value  the value of the struct member.
     */
    protected void writeStructField(GoWriter writer, String field, Object value) {
        writer.write("$L: $L,", field, value);
    }

    /**
     * Writes a  single Go structure field key and value. Provides inline formatting of the field value.
     *
     * @param writer      writer to write generated code with.
     * @param field       the field name of the struct member.
     * @param valueFormat the format string to use for the field value
     * @param args        the format string arguments for the field value.
     */
    protected void writeStructField(GoWriter writer, String field, String valueFormat, Object... args) {
        writer.writeInline("$L: ", field);
        writer.writeInline(valueFormat, args);
        writer.write(",");
    }

    /**
     * Writes a single Go structure field key and value. Writes the field value inline from the shape and
     * ObjectNode graph provided.
     *
     * @param writer writer to write generated code with.
     * @param field  the field name of the struct member.
     * @param shape  the shape the field member.
     * @param params the node of values to fill the member with.
     */
    protected void writeStructField(GoWriter writer, String field, StructureShape shape, ObjectNode params) {
        writer.writeInline("$L: ", field);
        writeShapeValueInline(writer, shape, params);
        writer.write(",");
    }

    /**
     * Writes a single Go structure field key and value. Writes the field value inline from the shape and
     * ObjectNode graph provided. Value writer is responsible for writing the proceeding comma after the value.
     *
     * @param writer writer to write generated code with.
     * @param field  the field name of the struct member.
     * @param value  inline value writer.
     */
    protected void writeStructField(GoWriter writer, String field, Consumer<GoWriter> value) {
        writer.writeInline("$L: ", field);
        value.accept(writer);
    }

    /**
     * Writes a Go structure field for a QueryItem value.
     *
     * @param writer writer to write generated code with.
     * @param field  the name of the field.
     * @param values list of values for the query.
     */
    protected void writeQueryItemsStructField(GoWriter writer, String field, List<String> values) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.openBlock("$L: []smithytesting.QueryItem {", "},", field, () -> {
            writeQueryItemsValues(writer, values);
        });
    }

    /**
     * Writes values of query items as slice members.
     *
     * @param writer writer to write generated code with.
     * @param values list of values for the query.
     */
    protected void writeQueryItemsValues(GoWriter writer, List<String> values) {
        for (String item : values) {
            String[] parts = item.split("=", 2);
            String value = "";
            if (parts.length > 1) {
                value = parts[1];
            }
            writer.write("{Key: $S, Value: $S},", parts[0], value);
        }
    }

    /**
     * Writes utility to breakout RawQuery string into its components for testing.
     *
     * @param writer writer to write generated code with.
     * @param source name of variable containing raw query string.
     * @param target name of destination variable that will be created to hold QueryItems
     */
    protected void writeQueryItemBreakout(GoWriter writer, String source, String target) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("$L := smithytesting.ParseRawQuery($L)", target, source);
    }

    /**
     * Writes a structure header member with values from a map.
     *
     * @param writer writer to write generated code with.
     * @param field  name of the field.
     * @param values map of header key and value pairs.
     */
    protected void writeHeaderStructField(GoWriter writer, String field, Map<String, String> values) {
        if (values.size() == 0) {
            return;
        }
        writer.openBlock("$L: http.Header{", "},", field, () -> {
            writeHeaderValues(writer, values);
        });
    }

    /**
     * Writes individual header key value field pairs.
     *
     * @param writer writer to write generated code with.
     * @param values map of header key/value pairs.
     */
    protected void writeHeaderValues(GoWriter writer, Map<String, String> values) {
        values.forEach((k, v) -> {
            writer.write("$S: []string{$S},", k, v);
        });
    }

    /**
     * Writes a string slice to a struct field.
     *
     * @param writer writer to write generated code with.
     * @param field  the name of the field.
     * @param values the list of field values.
     */
    protected void writeStringSliceStructField(GoWriter writer, String field, List<String> values) {
        if (values.size() == 0) {
            return;
        }

        writer.openBlock("$L: []string{", "},", field, () -> {
            writeStringSliceValues(writer, values);
        });
    }

    /**
     * Writes a list of strings as go string slice members.
     *
     * @param writer writer to write generated code with.
     * @param values the list of string values.
     */
    protected void writeStringSliceValues(GoWriter writer, List<String> values) {
        for (String value : values) {
            writer.write("$S,", value);
        }
    }

    /**
     * Writes the assertion for comparing two scalar values.
     *
     * @param writer writer to write generated code with.
     * @param expect variable name of the expected value.
     * @param actual variable name of the actual value.
     * @param tag    additional error message description.
     */
    protected void writeAssertScalarEqual(GoWriter writer, String expect, String actual, String tag) {
        writer.openBlock("if e, a := $L, $L; e != a {", "}", expect, actual, () -> {
            writer.write("t.Errorf(\"expect %v $L, got %v\", e, a)", tag);
        });
    }

    /**
     * Writes the assertion for comparing two complex type values, e.g. structures.
     *
     * @param writer      writer to write generated code with.
     * @param expect      the variable name of the expected value.
     * @param actual      the variable name of the actual value.
     * @param ignoreTypes list of type values that should be ignored by the compare.
     */
    protected void writeAssertComplexEqual(
            GoWriter writer, String expect, String actual, String[] ignoreTypes
    ) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.openBlock("if err := smithytesting.CompareValues($L, $L); err != nil {", "}",
                expect, actual, () -> {
                    writer.write("t.Errorf(\"expect $L value match:\\n%v\", err)", expect);
                });
    }

    /**
     * Writes assertion that a variable's value must be nil.
     *
     * @param writer writer to write generated code with.
     * @param field  the variable name of the value.
     */
    protected void writeAssertNil(GoWriter writer, String field) {
        writer.openBlock("if $L != nil {", "}", field, () -> {
            writer.write("t.Fatalf(\"expect nil $L, got %v\", $L)", field, field);
        });
    }

    /**
     * Writes the assertion that a variable must not be nil.
     *
     * @param writer writer to write generated code with.
     * @param field  the variable name of the value.
     */
    protected void writeAssertNotNil(GoWriter writer, String field) {
        writer.openBlock("if $L == nil {", "}", field, () -> {
            writer.write("t.Fatalf(\"expect not nil $L\")", field);
        });
    }

    /**
     * Writes the assertion that query contains expected values.
     *
     * @param writer writer to write generated code with.
     * @param expect variable name with the expected values.
     * @param actual variable name with the actual values.
     */
    void writeAssertHasQuery(GoWriter writer, String expect, String actual) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("smithytesting.AssertHasQuery(t, $L, $L)", expect, actual);
    }

    /**
     * Writes the assertion that an query contains keys.
     *
     * @param writer writer to write generated code with.
     * @param expect variable name with the expected values.
     * @param actual variable name with the actual values.
     */
    protected void writeAssertRequireQuery(GoWriter writer, String expect, String actual) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("smithytesting.AssertHasQueryKeys(t, $L, $L)", expect, actual);
    }

    /**
     * Writes the assertion that an query must not contain keys.
     *
     * @param writer writer to write generated code with.
     * @param expect variable name with the expected values.
     * @param actual variable name with the actual values.
     */
    protected void writeAssertForbidQuery(GoWriter writer, String expect, String actual) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("smithytesting.AssertNotHaveQueryKeys(t, $L, $L)", expect, actual);
    }

    /**
     * Writes the assertion that headers contain expected values.
     *
     * @param writer writer to write generated code with.
     * @param expect variable name with the expected values.
     * @param actual variable name with the actual values.
     */
    protected void writeAssertHasHeader(GoWriter writer, String expect, String actual) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("smithytesting.AssertHasHeader(t, $L, $L)", expect, actual);
    }

    /**
     * Writes the assertion that the header contains keys.
     *
     * @param writer writer to write generated code with.
     * @param expect variable name with the expected values.
     * @param actual variable name with the actual values.
     */
    protected void writeAssertRequireHeader(GoWriter writer, String expect, String actual) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("smithytesting.AssertHasHeaderKeys(t, $L, $L)", expect, actual);
    }

    /**
     * Writes the assertion that the header must not contain keys.
     *
     * @param writer writer to write generated code with.
     * @param expect variable name with the expected values.
     * @param actual variable name with the actual values.
     */
    protected void writeAssertForbidHeader(GoWriter writer, String expect, String actual) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("smithytesting.AssertNotHaveHeaderKeys(t, $L, $L)", expect, actual);
    }

    /**
     * Writes a shape type declaration value filled with values in the ObjectNode.
     *
     * @param writer writer to write generated code with.
     * @param shape  shape of the value type to be created.
     * @param params values to initialize shape type with.
     */
    protected void writeShapeValueInline(GoWriter writer, StructureShape shape, ObjectNode params) {
        new ShapeValueGenerator(settings, model, symbolProvider, shapeValueGeneratorConfig)
                .writePointableStructureShapeValueInline(writer, shape, params);
    }

    /**
     * Returns if the operation has an idempotency token input member.
     *
     * @return if the operation has an idempotency token input member.
     */
    private boolean hasIdempotencyTokenInputMember() {
        for (MemberShape member : inputShape.members()) {
            if (member.hasTrait(IdempotencyTokenTrait.class)) {
                return true;
            }
        }
        return false;
    }

    public abstract static class Builder<T extends HttpMessageTestCase> {
        protected GoSettings settings;
        protected Model model;
        protected SymbolProvider symbolProvider;
        protected String protocolName = "";
        protected ServiceShape service;
        protected OperationShape operation;
        protected List<T> testCases = new ArrayList<>();
        protected Set<ConfigValue> clientConfigValues = new TreeSet<>();
        protected Set<SkipTest> skipTests = new TreeSet<>();
        protected ShapeValueGenerator.Config shapeValueGeneratorConfig = ShapeValueGenerator.Config.builder().build();

        public Builder<T> settings(GoSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder<T> model(Model model) {
            this.model = model;
            return this;
        }

        public Builder<T> symbolProvider(SymbolProvider symbolProvider) {
            this.symbolProvider = symbolProvider;
            return this;
        }

        public Builder<T> protocolName(String protocolName) {
            this.protocolName = protocolName;
            return this;
        }

        public Builder<T> service(ServiceShape service) {
            this.service = service;
            return this;
        }

        public Builder<T> operation(OperationShape operation) {
            this.operation = operation;
            return this;
        }

        public Builder<T> testCases(List<T> testCases) {
            this.testCases.clear();
            return this.addTestCases(testCases);
        }

        public Builder<T> addTestCases(List<T> testCases) {
            this.testCases.addAll(testCases);
            return this;
        }

        public Builder<T> clientConfigValue(ConfigValue configValue) {
            this.clientConfigValues.add(configValue);
            return this;
        }

        public Builder<T> clientConfigValues(Set<ConfigValue> clientConfigValues) {
            this.clientConfigValues.clear();
            return this.addClientConfigValues(clientConfigValues);
        }

        public Builder<T> addClientConfigValues(Set<ConfigValue> clientConfigValues) {
            this.clientConfigValues.addAll(clientConfigValues);
            return this;
        }

        public Builder<T> skipTest(SkipTest skipTest) {
            this.skipTests.add(skipTest);
            return this;
        }

        public Builder<T> skipTests(Set<SkipTest> skipTests) {
            this.skipTests.clear();
            return this.addSkipTests(skipTests);
        }

        public Builder<T> addSkipTests(Set<SkipTest> skipTests) {
            this.skipTests.addAll(skipTests);
            return this;
        }

        public Builder<T> shapeValueGeneratorConfig(ShapeValueGenerator.Config config) {
            this.shapeValueGeneratorConfig = config;
            return this;
        }

        abstract HttpProtocolUnitTestGenerator<T> build();
    }

    /**
     * Represents a test client option configuration value.
     */
    public static class ConfigValue implements Comparable<ConfigValue> {
        private final String name;
        private final Consumer<GoWriter> value;

        ConfigValue(Builder builder) {
            this.name = SmithyBuilder.requiredState("name", builder.name);
            this.value = SmithyBuilder.requiredState("value", builder.value);
        }

        /**
         * Get the config field name.
         *
         * @return the field name
         */
        public String getName() {
            return name;
        }

        /**
         * Get the inline value writer for the field.
         *
         * @return the inline value writer
         */
        public Consumer<GoWriter> getValue() {
            return value;
        }

        public static Builder builder() {
            return new Builder();
        }

        @Override
        public int compareTo(ConfigValue o) {
            return getName().compareTo(o.getName());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ConfigValue that = (ConfigValue) o;
            return Objects.equals(getName(), that.getName())
                    && Objects.equals(getValue(), that.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }

        /**
         * Builder for {@link ConfigValue}.
         */
        public static final class Builder implements SmithyBuilder<ConfigValue> {
            private String name;
            private Consumer<GoWriter> value;

            private Builder() {
            }

            /**
             * Set the name of the field.
             *
             * @param name field name
             * @return the builder
             */
            public Builder name(String name) {
                this.name = name;
                return this;
            }

            /**
             * Set the inline value writer.
             *
             * @param value the inline value writer
             * @return the builder
             */
            public Builder value(Consumer<GoWriter> value) {
                this.value = value;
                return this;
            }

            @Override
            public ConfigValue build() {
                return new ConfigValue(this);
            }
        }
    }

    /**
     * Represents a test tests that should be skipped.
     */
    public static class SkipTest implements Comparable<SkipTest> {
        private final ShapeId service;
        private final ShapeId operation;
        private final List<String> testNames;

        SkipTest(Builder builder) {
            this.service = SmithyBuilder.requiredState("service id", builder.service);
            this.operation = SmithyBuilder.requiredState("operation id", builder.operation);
            this.testNames = builder.testNames;
        }

        /**
         * Get the service the skip test applies to.
         *
         * @return the service id
         */
        public ShapeId getService() {
            return service;
        }

        /**
         * Get the operation the skip test applies to.
         *
         * @return the operation id
         */
        public ShapeId getOperation() {
            return operation;
        }

        /**
         * Get the names of the tests the skip test applies to.
         *
         * @return the name of the test to skip
         */
        public List<String> getTestNames() {
            return testNames;
        }

        /**
         * Returns if the skip test case matches the test being evaluated. If a test name isn't specified in the skip
         * test only the service and operation are considered for matches.
         *
         * @param service   id of the service
         * @param operation id of the operation
         * @param testName  name of the test
         * @return if the skip test matches
         */
        public boolean matches(ShapeId service, ShapeId operation, String testName) {
            if (!this.service.equals(service)) {
                return false;
            }

            if (!this.operation.equals(operation)) {
                return false;
            }

            // SkipTests not for specific test should not match this check.
            if (this.testNames.isEmpty()) {
                return false;
            }

            return this.testNames.contains(testName);
        }

        /**
         * Returns if the skip test matches the service and operation, with no individual test defined. If an individual
         * test name is specified the skip test will not match.
         *
         * @param service   id of the service
         * @param operation id of the operation
         * @return if the skip test matches
         */
        public boolean matches(ShapeId service, ShapeId operation) {
            if (!this.service.equals(service)) {
                return false;
            }

            if (!this.operation.equals(operation)) {
                return false;
            }

            // SkipTests for specific test should not match this check.
            return this.testNames.isEmpty();
        }

        public static Builder builder() {
            return new Builder();
        }

        @Override
        public int compareTo(SkipTest o) {
            return Comparator.comparing(SkipTest::getService)
                    .thenComparing(SkipTest::getOperation)
                    .compare(this, o);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SkipTest that = (SkipTest) o;
            return Objects.equals(getService(), that.getService())
                    && Objects.equals(getOperation(), that.getOperation())
                    && Objects.equals(getTestNames(), that.getTestNames());
        }

        @Override
        public int hashCode() {
            return Objects.hash(service, operation, testNames);
        }

        /**
         * Builder for {@link SkipTest}.
         */
        public static final class Builder implements SmithyBuilder<SkipTest> {
            private ShapeId service;
            private ShapeId operation;
            private List<String> testNames = new ArrayList<>();

            private Builder() {
            }

            /**
             * Set the service of the test.
             *
             * @param service field service
             * @return the builder
             */
            public Builder service(ShapeId service) {
                this.service = service;
                return this;
            }

            /**
             * Set the operation of the test.
             *
             * @param operation is the operation of the test to skip
             * @return the builder
             */
            public Builder operation(ShapeId operation) {
                this.operation = operation;
                return this;
            }

            /**
             * Set the name of the test to skip.
             *
             * @param testName is the name of the test to skip
             * @return the builder
             */
            public Builder addTestName(String testName) {
                this.testNames.add(testName);
                return this;
            }

            @Override
            public SkipTest build() {
                return new SkipTest(this);
            }
        }
    }
}
