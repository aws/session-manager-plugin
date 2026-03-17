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

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.model.shapes.Shape;

/**
 * Common symbol utility building functions.
 */
public final class SymbolUtils {

    public static final String POINTABLE = "pointable";
    public static final String NAMESPACE_ALIAS = "namespaceAlias";
    public static final String GO_UNIVERSE_TYPE = "universeType";
    public static final String GO_SLICE = "goSlice";
    public static final String GO_MAP = "goMap";
    public static final String GO_ELEMENT_TYPE = "goElementType";

    // Used when a given shape must be represented differently on input.
    public static final String INPUT_VARIANT = "inputVariant";

    private SymbolUtils() {
    }

    /**
     * Create a value symbol builder.
     *
     * @param typeName the name of the type.
     * @return the symbol builder type.
     */
    public static Symbol.Builder createValueSymbolBuilder(String typeName) {
        return Symbol.builder()
                .putProperty(POINTABLE, false)
                .name(typeName);
    }

    /**
     * Create a pointable symbol builder.
     *
     * @param typeName the name of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createPointableSymbolBuilder(String typeName) {
        return Symbol.builder()
                .putProperty(POINTABLE, true)
                .name(typeName);
    }

    /**
     * Create a value symbol builder.
     *
     * @param shape    the shape that the type is for.
     * @param typeName the name of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createValueSymbolBuilder(Shape shape, String typeName) {
        return createValueSymbolBuilder(typeName).putProperty("shape", shape);
    }

    /**
     * Create a pointable symbol builder.
     *
     * @param shape    the shape that the type is for.
     * @param typeName the name of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName) {
        return createPointableSymbolBuilder(typeName).putProperty("shape", shape);
    }

    /**
     * Create a pointable symbol builder.
     *
     * @param typeName  the name of the type.
     * @param namespace the namespace of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createPointableSymbolBuilder(String typeName, String namespace) {
        return createPointableSymbolBuilder(typeName).namespace(namespace, ".");
    }

    /**
     * Create a value symbol builder.
     *
     * @param typeName  the name of the type.
     * @param namespace the namespace of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createValueSymbolBuilder(String typeName, String namespace) {
        return createValueSymbolBuilder(typeName).namespace(namespace, ".");
    }

    /**
     * Create a pointable symbol builder.
     *
     * @param shape     the shape that the type is for.
     * @param typeName  the name of the type.
     * @param namespace the namespace of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createPointableSymbolBuilder(shape, typeName).namespace(namespace, ".");
    }

    /**
     * Create a value symbol builder.
     *
     * @param shape     the shape that the type is for.
     * @param typeName  the name of the type.
     * @param namespace the namespace of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createValueSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createValueSymbolBuilder(shape, typeName).namespace(namespace, ".");
    }

    /**
     * Create a pointable symbol builder.
     *
     * @param shape     the shape that the type is for.
     * @param typeName  the name of the type.
     * @param namespace the namespace of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName, GoDependency namespace) {
        return setImportedNamespace(createPointableSymbolBuilder(shape, typeName), namespace);
    }

    /**
     * Create a value symbol builder.
     *
     * @param shape     the shape that the type is for.
     * @param typeName  the name of the type.
     * @param namespace the namespace of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createValueSymbolBuilder(Shape shape, String typeName, GoDependency namespace) {
        return setImportedNamespace(createValueSymbolBuilder(shape, typeName), namespace);
    }

    /**
     * Create a pointable symbol builder.
     *
     * @param typeName  the name of the type.
     * @param namespace the namespace of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createPointableSymbolBuilder(String typeName, GoDependency namespace) {
        return setImportedNamespace(createPointableSymbolBuilder(typeName), namespace);
    }

    /**
     * Create a value symbol builder.
     *
     * @param typeName  the name of the type.
     * @param namespace the namespace of the type.
     * @return the symbol builder.
     */
    public static Symbol.Builder createValueSymbolBuilder(String typeName, GoDependency namespace) {
        return setImportedNamespace(createValueSymbolBuilder(typeName), namespace);
    }

    private static Symbol.Builder setImportedNamespace(Symbol.Builder builder, GoDependency dependency) {
        return builder.namespace(dependency.getImportPath(), ".")
                .addDependency(dependency)
                .putProperty(NAMESPACE_ALIAS, dependency.getAlias());
    }

    /**
     * Go declares several built-in language types in what is known as the Universe block. This function determines
     * whether the provided symbol represents a Go universe type.
     *
     * @param symbol the symbol to check
     * @return whether the symbol type is in the Go universe block
     * @see <a href="https://golang.org/ref/spec#Predeclared_identifiers">The Go Programming Language Specification</a>
     */
    public static boolean isUniverseType(Symbol symbol) {
        return symbol.getProperty(SymbolUtils.GO_UNIVERSE_TYPE, Boolean.class)
                .orElse(false);
    }

    public static boolean isPointable(Symbol symbol) {
        return symbol.getProperty(SymbolUtils.POINTABLE, Boolean.class).orElse(false);
    }

    public static Symbol getReference(Symbol symbol) {
        return symbol.getProperty(SymbolUtils.GO_ELEMENT_TYPE, Symbol.class).orElse(null);
    }

    /**
     * Builds a symbol within the context of the package in which codegen is taking place.
     *
     * @param name Symbol name.
     * @return The built symbol.
     */
    public static Symbol buildPackageSymbol(String name) {
        return Symbol.builder().name(name).build();
    }

    public static Symbol buildSymbol(String name, String namespace) {
        return Symbol.builder()
                .name(name)
                .namespace(namespace, ".")
                .build();
    }

    public static boolean isNilable(Symbol symbol) {
        return isPointable(symbol)
                || symbol.getProperty(SymbolUtils.GO_SLICE).isPresent()
                || symbol.getProperty(SymbolUtils.GO_MAP).isPresent();
    }

    public static Symbol pointerTo(Symbol symbol) {
        return symbol.toBuilder().putProperty(POINTABLE, true).build();
    }

    public static Symbol sliceOf(Symbol symbol) {
        return symbol.toBuilder()
                .putProperty(GO_SLICE, true)
                .putProperty(GO_ELEMENT_TYPE, symbol)
                .build();
    }
}
