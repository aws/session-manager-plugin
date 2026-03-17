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

package software.amazon.smithy.go.codegen.auth;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoUniverseTypes;

public record AuthParameter(String name, String docs, Symbol type) {
    public static final AuthParameter OPERATION = new AuthParameter(
            "Operation",
            "The name of the operation being invoked.",
            GoUniverseTypes.String
    );

    public static final AuthParameter REGION = new AuthParameter(
            "Region",
            "The region in which the operation is being invoked.",
            GoUniverseTypes.String
    );
}
