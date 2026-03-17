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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.MapUtils;

public class MiddlewareStackSnapshotTests implements GoIntegration {
    @Override
    public void writeAdditionalFiles(
            GoSettings settings, Model model, SymbolProvider symbolProvider, GoDelegator goDelegator
    ) {
        goDelegator.useFileWriter("snapshot_test.go", settings.getModuleName(), writer -> {
            writer.addBuildTag("snapshot");
            writer.write(commonTestSource());
            writer.write(snapshotTests(model, settings.getService(model), symbolProvider));
            writer.write(snapshotUpdaters(model, settings.getService(model), symbolProvider));
        });
    }

    private Writable commonTestSource() {
        return goTemplate("""
                $os:D $fs:D $io:D $errors:D $fmt:D $middleware:D

                const ssprefix = "snapshot"

                type snapshotOK struct{}

                func (snapshotOK) Error() string { return "error: success" }

                func createp(path string) (*os.File, error) {
                    if err := os.Mkdir(ssprefix, 0700); err != nil && !errors.Is(err, fs.ErrExist) {
                        return nil, err
                    }
                    return os.Create(path)
                }

                func sspath(op string) string {
                    return fmt.Sprintf("%s/api_op_%s.go.snap", ssprefix, op)
                }

                func updateSnapshot(stack *middleware.Stack, operation string) error {
                    f, err := createp(sspath(operation))
                    if err != nil {
                        return err
                    }
                    defer f.Close()
                    if _, err := f.Write([]byte(stack.String())); err != nil {
                        return err
                    }
                    return snapshotOK{}
                }

                func testSnapshot(stack *middleware.Stack, operation string) error {
                    f, err := os.Open(sspath(operation))
                    if errors.Is(err, fs.ErrNotExist) {
                        return snapshotOK{}
                    }
                    if err != nil {
                        return err
                    }
                    defer f.Close()
                    expected, err := io.ReadAll(f)
                    if err != nil {
                        return err
                    }
                    if actual := stack.String(); actual != string(expected) {
                        return fmt.Errorf("%s != %s", expected, actual)
                    }
                    return snapshotOK{}
                }
                """,
                MapUtils.of(
                        "errors", SmithyGoDependency.ERRORS, "fmt", SmithyGoDependency.FMT,
                        "fs", SmithyGoDependency.FS, "io", SmithyGoDependency.IO,
                        "middleware", SmithyGoDependency.SMITHY_MIDDLEWARE, "os", SmithyGoDependency.OS
                ));
    }

    private Writable snapshotUpdaters(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        return ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .map(it -> testUpdateSnapshot(it, symbolProvider))
                        .toList()
        ).compose();
    }

    private Writable snapshotTests(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        return ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .map(it -> testCheckSnapshot(it, symbolProvider))
                        .toList()
        ).compose();
    }

    private Writable testUpdateSnapshot(OperationShape operation, SymbolProvider symbolProvider) {
        return goTemplate("""
                func TestUpdateSnapshot_$operation:L(t $testingT:P) {
                    svc := New(Options{})
                    _, err := svc.$operation:L($contextBackground:T(), nil, func(o *Options) {
                        o.APIOptions = append(o.APIOptions, func(stack $middlewareStack:P) error {
                            return updateSnapshot(stack, $operation:S)
                        })
                    })
                    if _, ok := err.(snapshotOK); !ok && err != nil {
                        t.Fatal(err)
                    }
                }
                """,
                MapUtils.of(
                        "testingT", GoStdlibTypes.Testing.T,
                        "contextBackground", GoStdlibTypes.Context.Background,
                        "middlewareStack", SmithyGoTypes.Middleware.Stack,
                        "operation", symbolProvider.toSymbol(operation).getName()
                ));
    }

    private Writable testCheckSnapshot(OperationShape operation, SymbolProvider symbolProvider) {
        return goTemplate("""
                func TestCheckSnapshot_$operation:L(t $testingT:P) {
                    svc := New(Options{})
                    _, err := svc.$operation:L($contextBackground:T(), nil, func(o *Options) {
                        o.APIOptions = append(o.APIOptions, func(stack $middlewareStack:P) error {
                            return testSnapshot(stack, $operation:S)
                        })
                    })
                    if _, ok := err.(snapshotOK); !ok && err != nil {
                        t.Fatal(err)
                    }
                }
                """,
                MapUtils.of(
                        "testingT", GoStdlibTypes.Testing.T,
                        "contextBackground", GoStdlibTypes.Context.Background,
                        "middlewareStack", SmithyGoTypes.Middleware.Stack,
                        "operation", symbolProvider.toSymbol(operation).getName()
                ));
    }
}
