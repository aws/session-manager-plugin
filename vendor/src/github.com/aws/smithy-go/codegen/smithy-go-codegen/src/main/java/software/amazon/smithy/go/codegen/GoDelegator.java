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

import java.util.function.Consumer;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.WriterDelegator;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Manages writers for Go files.Based off of GoWriterDelegator adding support
 * for getting shape specific GoWriters.
 */
@SmithyInternalApi
public final class GoDelegator extends WriterDelegator<GoWriter> {
    private final SymbolProvider symbolProvider;

    public GoDelegator(FileManifest fileManifest, SymbolProvider symbolProvider) {
        super(fileManifest, symbolProvider, (filename, namespace) -> new GoWriter(namespace));

        this.symbolProvider = symbolProvider;
    }

    /**
     * Gets a previously created writer or creates a new one for the Go test file for the associated shape.
     *
     * @param shape          Shape to create the writer for.
     * @param writerConsumer Consumer that accepts and works with the file.
     */
    public void useShapeTestWriter(Shape shape, Consumer<GoWriter> writerConsumer) {
        var symbol = symbolProvider.toSymbol(shape);
        var filename = symbol.getDefinitionFile();
        var testFilename = new StringBuilder(filename)
                .insert(filename.lastIndexOf(".go"), "_test")
                .toString();
        useFileWriter(testFilename, symbol.getNamespace(), writerConsumer);
    }

    /**
     * Gets a previously created writer or creates a new one for the Go public package test file for the associated
     * shape.
     *
     * @param shape          Shape to create the writer for.
     * @param writerConsumer Consumer that accepts and works with the file.
     */
    public void useShapeExportedTestWriter(Shape shape, Consumer<GoWriter> writerConsumer) {
        var symbol = symbolProvider.toSymbol(shape);
        var filename = symbol.getDefinitionFile();
        var testFilename = new StringBuilder(filename)
                .insert(filename.lastIndexOf(".go"), "_exported_test")
                .toString();
        useFileWriter(testFilename, symbol.getNamespace() + "_test", writerConsumer);
    }
}
