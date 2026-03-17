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
 *
 *
 */

package software.amazon.smithy.go.codegen;

import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.EnumTrait;

/**
 * Utilities for generating accessor checks around other generated blocks.
 */
public final class GoValueAccessUtils {
    private GoValueAccessUtils() {
    }

    /**
     * Writes non-zero conditional checks around a lambda specific to the member shape type.
     * <p>
     * Note: Collections and map member values by default will not have individual checks on member values. To check
     * not empty strings set the ignoreEmptyString to false.
     *
     * @param model              smithy model
     * @param writer             go writer
     * @param member             API shape member to determine wrapping check with
     * @param operand            string of text with access to value
     * @param ignoreEmptyString  if empty strings also checked
     * @param ignoreUnboxedTypes if unboxed member types should be ignored
     * @param lambda             lambda to run
     */
    public static void writeIfNonZeroValue(
            Model model,
            GoWriter writer,
            MemberShape member,
            String operand,
            boolean ignoreEmptyString,
            boolean ignoreUnboxedTypes,
            Runnable lambda
    ) {
        Shape targetShape = model.expectShape(member.getTarget());
        Shape container = model.expectShape(member.getContainer());

        // default to empty block for variable scoping with not value check.
        String check = "{";

        if (GoPointableIndex.of(model).isNillable(member)) {
            if (!ignoreEmptyString && targetShape.getType() == ShapeType.STRING) {
                check = String.format("if %s != nil && len(*%s) > 0 {", operand, operand);
            } else {
                check = String.format("if %s != nil {", operand);
            }
        } else if (container instanceof CollectionShape || container.getType() == ShapeType.MAP) {
            if (!ignoreEmptyString && targetShape.getType() == ShapeType.STRING) {
                check = String.format("if len(%s) > 0 {", operand);
            } else if (!ignoreEmptyString && targetShape.getType() == ShapeType.ENUM) {
                check = String.format("if len(%s) > 0 {", operand);
            }
        } else if (targetShape.hasTrait(EnumTrait.class)) {
            check = String.format("if len(%s) > 0 {", operand);

        } else if (!ignoreUnboxedTypes && targetShape.getType() == ShapeType.BOOLEAN) {
            check = String.format("if %s {", operand);

        } else if (!ignoreUnboxedTypes && CodegenUtils.isNumber(targetShape)) {
            check = String.format("if %s != 0 {", operand);

        } else if (!ignoreEmptyString && targetShape.getType() == ShapeType.STRING) {
            check = String.format("if len(%s) > 0 {", operand);
        }

        writer.openBlock(check, "}", lambda);
    }

    /**
     * Writes non-zero conditional checks around a lambda specific to the member shape type.
     * <p>
     * Ignores empty strings of string pointers, and nested within list and maps.
     *
     * @param model   smithy model
     * @param writer  go writer
     * @param member  API shape member to determine wrapping check with
     * @param operand string of text with access to value
     * @param lambda  lambda to run
     */
    public static void writeIfNonZeroValue(
            Model model,
            GoWriter writer,
            MemberShape member,
            String operand,
            Runnable lambda
    ) {
        writeIfNonZeroValue(model, writer, member, operand, true, false, lambda);
    }

    /**
     * Writes non-zero conditional check around a lambda specific to a member of a container.
     * <p>
     * Ignores empty strings of string pointers, and members nested within list and maps.
     *
     * @param model          smithy model
     * @param symbolProvider symbol provider
     * @param writer         go writer
     * @param member         API shape member to determine wrapping check with
     * @param container      operand of source member is a part of.
     * @param lambda         lambda to run
     */
    public static void writeIfNonZeroValueMember(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            MemberShape member,
            String container,
            Consumer<String> lambda
    ) {
        writeIfNonZeroValueMember(model, symbolProvider, writer, member, container, true, false, lambda);
    }

    /**
     * Writes non-zero conditional check around a lambda specific to a member of a container.
     * <p>
     * Note: Collections and map member values by default will not have individual checks on member values. To check
     * not empty strings set the ignoreEmptyString to false.
     *
     * @param model              smithy model
     * @param symbolProvider     symbol provider
     * @param writer             go writer
     * @param member             API shape member to determine wrapping check with
     * @param container          operand of source member is a part of.
     * @param ignoreEmptyString  if empty strings also checked
     * @param ignoreUnboxedTypes if unboxed member types should be ignored
     * @param lambda             lambda to run
     */
    public static void writeIfNonZeroValueMember(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            MemberShape member,
            String container,
            boolean ignoreEmptyString,
            boolean ignoreUnboxedTypes,
            Consumer<String> lambda
    ) {
        String memberName = symbolProvider.toMemberName(member);
        String operand = container + "." + memberName;

        writeIfNonZeroValue(model, writer, member, operand, ignoreEmptyString, ignoreUnboxedTypes, () -> {
            lambda.accept(operand);
        });
    }

    /**
     * Writes zero conditional checks around a lambda specific to the member shape type.
     * <p>
     * Members with containers of Collection and map shapes, will ignore the lambda block
     * and not call it. Optionally will ignore empty strings based on the ignoreEmptyString flag.
     * <p>
     * Non-nillable shapes other than Enum, Boolean, and Number will ignore the lambda block. Optionally will ignore
     * empty strings based on the ignoreEmptyString flag.
     * <p>
     * Note: Collections and map member values by default will not have individual checks on member values. To check
     * for empty strings set the ignoreEmptyString to false.
     *
     * @param model              smithy model
     * @param writer             go writer
     * @param member             API shape member to determine wrapping check with
     * @param operand            string of text with access to value
     * @param ignoreEmptyString  if empty strings also checked
     * @param ignoreUnboxedTypes if unboxed member types should be ignored
     * @param lambda             lambda to run
     */
    public static void writeIfZeroValue(
            Model model,
            GoWriter writer,
            MemberShape member,
            String operand,
            boolean ignoreEmptyString,
            boolean ignoreUnboxedTypes,
            Runnable lambda
    ) {
        Shape targetShape = model.expectShape(member.getTarget());
        Shape container = model.expectShape(member.getContainer());

        String check = "{";
        if (GoPointableIndex.of(model).isNillable(member)) {
            if (!ignoreEmptyString && targetShape.getType() == ShapeType.STRING) {
                check = String.format("if %s == nil || len(*%s) == 0 {", operand, operand);
            } else {
                check = String.format("if %s == nil {", operand);
            }
        } else if (container instanceof CollectionShape || container.getType() == ShapeType.MAP) {
            // Always serialize values in map/list/sets, no additional check, which means that the
            // lambda will not be run, because there is no zero value to check against.
            if (!ignoreEmptyString && targetShape.getType() == ShapeType.STRING) {
                check = String.format("if len(%s) == 0 {", operand);
            } else {
                return;
            }

        } else if (targetShape.hasTrait(EnumTrait.class)) {
            check = String.format("if len(%s) == 0 {", operand);

        } else if (!ignoreUnboxedTypes && targetShape.getType() == ShapeType.BOOLEAN) {
            check = String.format("if !%s {", operand);

        } else if (!ignoreUnboxedTypes && CodegenUtils.isNumber(targetShape)) {
            check = String.format("if %s == 0 {", operand);

        } else if (!ignoreEmptyString && targetShape.getType() == ShapeType.STRING) {
            check = String.format("if len(%s) == 0 {", operand);

        } else {
            // default to empty block for variable scoping with not value check.
            return;
        }

        writer.openBlock(check, "}", lambda);
    }

    /**
     * Writes zero conditional checks around a lambda specific to the member shape type.
     * <p>
     * Ignores empty strings of string pointers, and members nested within list and maps.
     *
     * @param model   smithy model
     * @param writer  go writer
     * @param member  API shape member to determine wrapping check with
     * @param operand string of text with access to value
     * @param lambda  lambda to run
     */
    public static void writeIfZeroValue(
            Model model,
            GoWriter writer,
            MemberShape member,
            String operand,
            Runnable lambda
    ) {
        writeIfZeroValue(model, writer, member, operand, true, false, lambda);
    }

    /**
     * Writes zero conditional check around a lambda specific to a member of a container.
     * <p>
     * Ignores empty strings of string pointers, and members nested within list and maps.
     *
     * @param model          smithy model
     * @param symbolProvider symbol provider
     * @param writer         go writer
     * @param member         API shape member to determine wrapping check with
     * @param container      operand of source member is a part of.
     * @param lambda         lambda to run
     */
    public static void writeIfZeroValueMember(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            MemberShape member,
            String container,
            Consumer<String> lambda
    ) {
        writeIfZeroValueMember(model, symbolProvider, writer, member, container, true, false, lambda);
    }

    /**
     * Writes zero conditional check around a lambda specific to a member of a container.
     * <p>
     * Ignores empty strings of string pointers, and members nested within list and maps.
     *
     * @param model              smithy model
     * @param symbolProvider     symbol provider
     * @param writer             go writer
     * @param member             API shape member to determine wrapping check with
     * @param container          operand of source member is a part of.
     * @param ignoreEmptyString  if empty strings also checked
     * @param ignoreUnboxedTypes if unboxed member types should be ignored
     * @param lambda             lambda to run
     */
    public static void writeIfZeroValueMember(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            MemberShape member,
            String container,
            boolean ignoreEmptyString,
            boolean ignoreUnboxedTypes,
            Consumer<String> lambda
    ) {
        String memberName = symbolProvider.toMemberName(member);
        String operand = container + "." + memberName;

        writeIfZeroValue(model, writer, member, operand, ignoreEmptyString, ignoreUnboxedTypes, () -> {
            lambda.accept(operand);
        });
    }
}
