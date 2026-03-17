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

package software.amazon.smithy.go.codegen.endpoints;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression;

/*
 * Provides contextualized scope down the call tree to inform generator of expression origin.
 */
class Scope {
    private final Map<Expression, String> mapping;

    Scope(Map<Expression, String> mapping) {
        this.mapping = mapping;
    }

    static Scope empty() {
        return new Scope(new HashMap<>());
    }

    Optional<String> getIdent(Expression expr) {
        if (!mapping.containsKey(expr)) {
            return Optional.empty();
        }
        return Optional.of(mapping.get(expr));
    }

    Scope withMember(Expression expr, String name) {
        Map<Expression, String> newMapping = new HashMap<>(mapping);
        newMapping.put(expr, name);
        return new Scope(newMapping);
    }

    Scope withIdent(Expression expr, String name) {
        var newMapping = new HashMap<>(mapping);
        newMapping.put(expr, name);
        return new Scope(newMapping);
    }
}
