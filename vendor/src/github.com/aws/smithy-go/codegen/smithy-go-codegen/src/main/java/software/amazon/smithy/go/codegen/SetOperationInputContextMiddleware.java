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

import static software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator.createSerializeStepMiddleware;
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

/**
 * Middleware to set the final operation input on the context at the start of the serialize step such that protocol
 * middlewares in later phases can use it.
 */
public class SetOperationInputContextMiddleware {
    public static final String MIDDLEWARE_NAME = "setOperationInputMiddleware";
    public static final String MIDDLEWARE_ID = "setOperationInput";

    public Writable generate() {
        return createSerializeStepMiddleware(MIDDLEWARE_NAME, MiddlewareIdentifier.string(MIDDLEWARE_ID))
                .asWritable(generateBody(), emptyGoTemplate());
    }

    private Writable generateBody() {
        return goTemplate("""
                ctx = setOperationInput(ctx, in.Parameters)
                return next.HandleSerialize(ctx, in)
                """);
    }
}
