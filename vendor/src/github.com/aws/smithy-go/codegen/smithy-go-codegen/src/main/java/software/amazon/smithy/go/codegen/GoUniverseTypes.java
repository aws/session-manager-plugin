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

/**
 * Collection of Symbol constants for golang universe types.
 * See <a href="https://go.dev/ref/spec#Predeclared_identifiers">predeclared identifiers</a>.
 */
public final class GoUniverseTypes {
    public static final Symbol Any = universe("any");
    public static final Symbol Bool = universe("bool");
    public static final Symbol Byte = universe("byte");
    public static final Symbol Comparable = universe("comparable");

    public static final Symbol Complex64 = universe("complex64");
    public static final Symbol Complex128 = universe("complex128");
    public static final Symbol Error = universe("error");
    public static final Symbol Float32 = universe("float32");
    public static final Symbol Float64 = universe("float64");

    public static final Symbol Int = universe("int");
    public static final Symbol Int8 = universe("int8");
    public static final Symbol Int16 = universe("int16");
    public static final Symbol Int32 = universe("int32");
    public static final Symbol Int64 = universe("int64");
    public static final Symbol Rune = universe("rune");
    public static final Symbol String = universe("string");

    public static final Symbol Uint = universe("uint");
    public static final Symbol Uint8 = universe("uint8");
    public static final Symbol Uint16 = universe("uint16");
    public static final Symbol Uint32 = universe("uint32");
    public static final Symbol Uint64 = universe("uint64");
    public static final Symbol Uintptr = universe("uintptr");

    private GoUniverseTypes() {}

    private static Symbol universe(String name) {
        return SymbolUtils.createValueSymbolBuilder(name).putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build();
    }
}
