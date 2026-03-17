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

package software.amazon.smithy.go.codegen.middleware;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.SmithyGoDependency;

/**
 * Abstract class for InitializeStep middleware generation.
 */
public abstract class InitializeStepMiddleware extends OperationMiddleware {
    @Override
    public String getFuncName() {
        return "HandleInitialize";
    }

    @Override
    public Symbol getInput() {
        return SmithyGoDependency.SMITHY_MIDDLEWARE.struct("InitializeInput");
    }

    @Override
    public Symbol getHandler() {
        return SmithyGoDependency.SMITHY_MIDDLEWARE.struct("InitializeHandler");
    }

    @Override
    public Symbol getOutput() {
        return SmithyGoDependency.SMITHY_MIDDLEWARE.struct("InitializeOutput");
    }
}
