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
 * Collection of Symbol constants for types in the go standard library.
 */
public final class GoStdlibTypes {
    private GoStdlibTypes() { }

    public static final class Bytes {
        public static final Symbol NewReader = SmithyGoDependency.BYTES.valueSymbol("NewReader");
    }

    public static final class Context {
        public static final Symbol Context = SmithyGoDependency.CONTEXT.valueSymbol("Context");
        public static final Symbol Background = SmithyGoDependency.CONTEXT.valueSymbol("Background");
    }

    public static final class Time {
        public static final Symbol Time = SmithyGoDependency.TIME.pointableSymbol("Time");
    }

    public static final class Encoding {
        public static final class Json {
            public static final Symbol NewDecoder = SmithyGoDependency.JSON.valueSymbol("NewDecoder");
            public static final Symbol Number = SmithyGoDependency.JSON.valueSymbol("Number");
        }

        public static final class Base64 {
            public static final Symbol StdEncoding = SmithyGoDependency.BASE64.valueSymbol("StdEncoding");
        }
    }

    public static final class Crypto {
        public static final class Rand {
            public static final Symbol Reader = SmithyGoDependency.CRYPTORAND.valueSymbol("Reader");
        }
    }

    public static final class Fmt {
        public static final Symbol Errorf = SmithyGoDependency.FMT.valueSymbol("Errorf");
        public static final Symbol Sprintf = SmithyGoDependency.FMT.valueSymbol("Sprintf");
    }

    public static final class Io {
        public static final Symbol ReadAll = SmithyGoDependency.IO.valueSymbol("ReadAll");
        public static final Symbol Copy = SmithyGoDependency.IO.valueSymbol("Copy");

        public static final class IoUtil {
            public static final Symbol Discard = SmithyGoDependency.IOUTIL.valueSymbol("Discard");
        }
    }

    public static final class Net {
        public static final class Http {
            public static final Symbol Request = SmithyGoDependency.NET_HTTP.pointableSymbol("Request");
            public static final Symbol Response = SmithyGoDependency.NET_HTTP.pointableSymbol("Response");
            public static final Symbol Server = SmithyGoDependency.NET_HTTP.pointableSymbol("Server");
            public static final Symbol Handler = SmithyGoDependency.NET_HTTP.valueSymbol("Handler");
            public static final Symbol ResponseWriter = SmithyGoDependency.NET_HTTP.valueSymbol("ResponseWriter");
            public static final Symbol MethodPost = SmithyGoDependency.NET_HTTP.valueSymbol("MethodPost");
        }
    }

    public static final class Path {
        public static final Symbol Join = SmithyGoDependency.PATH.valueSymbol("Join");
    }

    public static final class Testing {
        public static final Symbol T = SmithyGoDependency.TESTING.pointableSymbol("T");
    }
}
