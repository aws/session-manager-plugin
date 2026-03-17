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

package software.amazon.smithy.go.codegen.util;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.node.ArrayNode;

public final class NodeUtil {
    private NodeUtil() {}

    public static Writable writableStringSlice(ArrayNode node) {
        return goTemplate("[]string{$W}", ChainWritable.of(
                node.getElements().stream()
                        .map(it -> goTemplate("$S,", it.expectStringNode().getValue()))
                        .toList()
        ).compose(false));
    }
}
