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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.model.traits.TitleTrait;
import software.amazon.smithy.utils.StringUtils;

/**
 * Utility methods likely to be needed across packages.
 */
public final class CodegenUtils {

    private static final Logger LOGGER = Logger.getLogger(CodegenUtils.class.getName());

    private static final String SYNTHETIC_NAMESPACE = "smithy.go.synthetic";

    private CodegenUtils() {
    }

    /**
     * Executes a given shell command in a given directory.
     *
     * @param command   The string command to execute, e.g. "go fmt".
     * @param directory The directory to run the command in.
     * @return Returns the console output of the command.
     */
    public static String runCommand(String command, Path directory) {
        String[] finalizedCommand;
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            finalizedCommand = new String[]{"cmd.exe", "/c", command};
        } else {
            finalizedCommand = new String[]{"sh", "-c", command};
        }

        ProcessBuilder processBuilder = new ProcessBuilder(finalizedCommand)
                .redirectErrorStream(true)
                .directory(directory.toFile());

        try {
            Process process = processBuilder.start();
            List<String> output = new ArrayList<>();

            // Capture output for reporting.
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    LOGGER.finest(line);
                    output.add(line);
                }
            }

            process.waitFor();
            process.destroy();

            String joinedOutput = String.join(System.lineSeparator(), output);
            if (process.exitValue() != 0) {
                throw new CodegenException(String.format(
                        "Command `%s` failed with output:%n%n%s", command, joinedOutput));
            }
            return joinedOutput;
        } catch (InterruptedException | IOException e) {
            throw new CodegenException(e);
        }
    }

    /**
     * Gets the name under which the given package will be exported by default.
     *
     * @param packageName The full package name of the exported package.
     * @return The name a the package will be imported under by default.
     */
    public static String getDefaultPackageImportName(String packageName) {
        if (StringUtils.isBlank(packageName) || !packageName.contains("/")) {
            return packageName;
        }
        return packageName.substring(packageName.lastIndexOf('/') + 1);
    }

    /**
     * Gets the alias to use when referencing the given symbol outside of its namespace.
     *
     * <p>The default value is the last path component of the symbol's namespace.
     *
     * @param symbol The symbol whose whose namespace alias should be retrieved.
     * @return The alias of the symbol's namespace.
     */
    public static String getSymbolNamespaceAlias(Symbol symbol) {
        return symbol.getProperty(SymbolUtils.NAMESPACE_ALIAS, String.class)
                .filter(StringUtils::isNotBlank)
                .orElse(CodegenUtils.getDefaultPackageImportName(symbol.getNamespace()));
    }

    /**
     * Detects if an annotated mediatype indicates JSON contents.
     *
     * @param mediaType The media type to inspect.
     * @return If the media type indicates JSON contents.
     */
    public static boolean isJsonMediaType(String mediaType) {
        return mediaType.equals("application/json") || mediaType.endsWith("+json");
    }

    /**
     * Get the namespace where synthetic types are generated at runtime.
     *
     * @return synthetic type namespace
     */
    public static String getSyntheticTypeNamespace() {
        return CodegenUtils.SYNTHETIC_NAMESPACE;
    }

    /**
     * Get if the passed in shape is decorated as a synthetic clone, but there is no other shape the clone is
     * created from.
     *
     * @param shape the shape to check if its a stubbed synthetic clone without an archetype.
     * @return if the shape is synthetic clone, but not based on a specific shape.
     */
    public static boolean isStubSynthetic(Shape shape) {
        Optional<Synthetic> optional = shape.getTrait(Synthetic.class);
        if (!optional.isPresent()) {
            return false;
        }

        Synthetic synth = optional.get();
        return !synth.getArchetype().isPresent();
    }

    /**
     * Returns the operand decorated with an &amp; if the address of the shape type can be taken.
     *
     * @param model          API model reference
     * @param pointableIndex pointable index
     * @param shape          shape to use
     * @param operand        value to decorate
     * @return updated operand
     */
    public static String asAddressIfAddressable(
            Model model,
            GoPointableIndex pointableIndex,
            Shape shape,
            String operand
    ) {
        boolean isStruct = shape.getType() == ShapeType.STRUCTURE;
        if (shape.isMemberShape()) {
            isStruct = model.expectShape(shape.asMemberShape().get().getTarget()).getType() == ShapeType.STRUCTURE;
        }

        boolean shouldAddress = pointableIndex.isPointable(shape) && isStruct;
        return shouldAddress ? "&" + operand : operand;
    }

    /**
     * Returns the operand decorated with an "*" if the shape is dereferencable.
     *
     * @param pointableIndex knowledge index for if shape is pointable.
     * @param shape          The shape whose value needs to be read.
     * @param operand        The value to be read from.
     * @return updated operand
     */
    public static String getAsValueIfDereferencable(
            GoPointableIndex pointableIndex,
            Shape shape,
            String operand
    ) {
        if (!pointableIndex.isDereferencable(shape)) {
            return operand;
        }

        return '*' + operand;
    }

    /**
     * Returns the operand decorated as a pointer type, without creating double pointer.
     *
     * @param pointableIndex knowledge index for if shape is pointable.
     * @param shape          The shape whose value of the type.
     * @param operand        The value to read.
     * @return updated operand
     */
    public static String getTypeAsTypePointer(
            GoPointableIndex pointableIndex,
            Shape shape,
            String operand
    ) {
        if (pointableIndex.isPointable(shape)) {
            return operand;
        }

        return '*' + operand;
    }

    /**
     * Get the pointer reference to operand , if symbol is pointable.
     * This method can be used by deserializers to get pointer to
     * operand.
     *
     * @param model          model for api.
     * @param writer         The writer dependencies will be added to, if needed.
     * @param pointableIndex knowledge index for if shape is pointable.
     * @param shape          The shape whose value needs to be assigned.
     * @param operand        The Operand is the value to be assigned to the symbol shape.
     * @return The Operand, along with pointer reference if applicable
     */
    public static String getAsPointerIfPointable(
            Model model,
            GoWriter writer,
            GoPointableIndex pointableIndex,
            Shape shape,
            String operand
    ) {
        if (!pointableIndex.isPointable(shape)) {
            return operand;
        }

        if (shape.isMemberShape()) {
            shape = model.expectShape(shape.asMemberShape().get().getTarget());
        }

        String prefix = "";
        String suffix = ")";

        switch (shape.getType()) {
            case STRING:
                prefix = "ptr.String(";
                break;

            case BOOLEAN:
                prefix = "ptr.Bool(";
                break;

            case BYTE:
                prefix = "ptr.Int8(";
                break;
            case SHORT:
                prefix = "ptr.Int16(";
                break;
            case INTEGER:
                prefix = "ptr.Int32(";
                break;
            case LONG:
                prefix = "ptr.Int64(";
                break;

            case FLOAT:
                prefix = "ptr.Float32(";
                break;
            case DOUBLE:
                prefix = "ptr.Float64(";
                break;

            case TIMESTAMP:
                prefix = "ptr.Time(";
                break;

            default:
                return '&' + operand;
        }

        writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
        return prefix + operand + suffix;
    }

    /**
     * Returns the shape unpacked as a CollectionShape. Throws and exception if the passed in
     * shape is not a list or set.
     *
     * @param shape the list or set shape.
     * @return The unpacked CollectionShape.
     */
    public static CollectionShape expectCollectionShape(Shape shape) {
        if (shape instanceof CollectionShape) {
            return (CollectionShape) (shape);
        }

        throw new CodegenException("expect shape " + shape.getId() + " to be Collection, was " + shape.getType());
    }

    /**
     * Returns the shape unpacked as a MapShape. Throws and exception if the passed in
     * shape is not a map.
     *
     * @param shape the map shape.
     * @return The unpacked MapShape.
     */
    public static MapShape expectMapShape(Shape shape) {
        if (shape instanceof MapShape) {
            return (MapShape) (shape);
        }

        throw new CodegenException("expect shape " + shape.getId() + " to be Map, was " + shape.getType());
    }

    /**
     * Comparator to sort ShapeMember lists alphabetically, with required members first followed by optional members.
     */
    public static final class SortedMembers implements Comparator<MemberShape> {
        private final SymbolProvider symbolProvider;

        /**
         * Initializes the SortedMembers.
         *
         * @param symbolProvider symbol provider used for codegen.
         */
        public SortedMembers(SymbolProvider symbolProvider) {
            this.symbolProvider = symbolProvider;
        }

        @Override
        public int compare(MemberShape a, MemberShape b) {
            // first compare if the members are required or not, which ever member is required should win. If both
            // members are required or not required, continue on to alphabetic search.

            // If a is required but b isn't return -1 so a is sorted before b
            // If b is required but a isn't, return 1 so a is sorted after b
            // If both a and b are required or optional, use alphabetic sorting of a and b's member name.

            int requiredMember = 0;
            if (a.hasTrait(RequiredTrait.class)) {
                requiredMember -= 1;
            }
            if (b.hasTrait(RequiredTrait.class)) {
                requiredMember += 1;
            }
            if (requiredMember != 0) {
                return requiredMember;
            }

            return symbolProvider.toMemberName(a)
                    .compareTo(symbolProvider.toMemberName(b));
        }
    }

    /**
     * Attempts to find the first member by exact name in the containing structure. If the member is not found an
     * exception will be thrown.
     *
     * @param shape structure containing member
     * @param name  member name
     * @return MemberShape if found
     */
    public static MemberShape expectMember(StructureShape shape, String name) {
        return expectMember(shape, name::equals);
    }

    /**
     * Attempts to find the first member by name using a member name predicate in the containing structure. If the
     * member is not found an exception will be thrown.
     *
     * @param shape               structure containing member
     * @param memberNamePredicate member name to search for
     * @return MemberShape if found
     */
    public static MemberShape expectMember(StructureShape shape, Predicate<String> memberNamePredicate) {
        return shape.getAllMembers().values().stream()
                .filter((p) -> memberNamePredicate.test(p.getMemberName()))
                .findFirst()
                .orElseThrow(() -> new CodegenException("did not find member in structure shape, " + shape.getId()));
    }

    /**
     * Attempts to get the title of the API's service from the model. If unalbe to get the title the fallback value
     * will be returned instead.
     *
     * @param shape    service shape
     * @param fallback string to return if service does not have a title
     * @return title of service
     */
    public static String getServiceTitle(ServiceShape shape, String fallback) {
        return shape.getTrait(TitleTrait.class).map(TitleTrait::getValue).orElse(fallback);
    }

    /**
     * isNumber returns if the shape is a number shape.
     *
     * @param shape shape to check
     * @return true if is a number shape.
     */
    public static boolean isNumber(Shape shape) {
        switch (shape.getType()) {
            case BYTE:
            case SHORT:
            case INTEGER:
            case INT_ENUM:
            case LONG:
            case FLOAT:
            case DOUBLE:
                return true;
            default:
                return false;
        }
    }
}
