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

import java.util.Objects;
import java.util.Optional;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * A String or Go Symbol of type string used for middleware to identify themselves by.
 */
public final class MiddlewareIdentifier {
    private final String string;
    private final Symbol symbol;

    private MiddlewareIdentifier(Builder builder) {
        if ((Objects.isNull(builder.string) && Objects.isNull(builder.symbol))) {
            throw new IllegalStateException("string or symbol must be provided");
        }

        if ((!Objects.isNull(builder.string) && !Objects.isNull(builder.symbol))) {
            throw new IllegalStateException("either string or symbol must be provided, not both");
        }

        string = builder.string;
        symbol = builder.symbol;
    }

    public Optional<String> getString() {
        return Optional.ofNullable(string);
    }

    public Optional<Symbol> getSymbol() {
        return Optional.ofNullable(symbol);
    }


    public void writeInline(GoWriter writer) {
        if (getSymbol().isPresent()) {
            writer.writeInline("$T", getSymbol().get());
        } else if (getString().isPresent()) {
            writer.writeInline("$S", getString().get());
        } else {
            throw new CodegenException("unsupported identifier state");
        }
    }

    public static MiddlewareIdentifier symbol(Symbol symbol) {
        return builder().symbol(symbol).build();
    }

    public static MiddlewareIdentifier string(String string) {
        return builder().name(string).build();
    }

    @Override
    public String toString() {
        if (symbol != null) {
            return symbol.toString();
        } else if (string != null) {
            return string;
        } else {
            throw new CodegenException("unexpected identifier state");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public int hashCode() {
        return Objects.hash(string, symbol);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof MiddlewareIdentifier)) {
            return false;
        }
        MiddlewareIdentifier identifier = (MiddlewareIdentifier) o;

        return Objects.equals(string, identifier.string)
                && ((symbol == identifier.symbol)
                || (symbol != null && identifier.symbol != null
                && symbol.getNamespace().equals(identifier.symbol.getNamespace())
                && symbol.getName().equals(identifier.symbol.getName())));
    }

    /**
     * A builder for {@link MiddlewareIdentifier}.
     */
    public static class Builder implements SmithyBuilder<MiddlewareIdentifier> {
        private String string;
        private Symbol symbol;

        public Builder name(String name) {
            this.string = name;
            return this;
        }

        public Builder symbol(Symbol symbol) {
            this.symbol = symbol;
            return this;
        }

        @Override
        public MiddlewareIdentifier build() {
            return new MiddlewareIdentifier(this);
        }
    }
}
