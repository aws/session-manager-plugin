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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolContainer;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.codegen.core.SymbolWriter;
import software.amazon.smithy.go.codegen.knowledge.GoUsageIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.DeprecatedTrait;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.model.traits.StringTrait;
import software.amazon.smithy.utils.AbstractCodeWriter;
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.utils.StringUtils;

/**
 * Specialized code writer for managing Go dependencies.
 *
 * <p>Use the {@code $T} formatter to refer to {@link Symbol}s without using pointers.
 *
 * <p>Use the {@code $P} formatter to refer to {@link Symbol}s using pointers where appropriate.
 */
@SmithyInternalApi
public final class GoWriter extends SymbolWriter<GoWriter, ImportDeclarations> {

    private static final Logger LOGGER = Logger.getLogger(GoWriter.class.getName());
    private static final int DEFAULT_DOC_WRAP_LENGTH = 80;
    private static final Pattern ARGUMENT_NAME_PATTERN = Pattern.compile("\\$([a-z][a-zA-Z_0-9]\\.+)(:\\w)?");
    private final String fullPackageName;
    private final boolean innerWriter;
    private final List<String> buildTags = new ArrayList<>();

    private int docWrapLength = DEFAULT_DOC_WRAP_LENGTH;
    private AbstractCodeWriter<GoWriter> packageDocs;

    /**
     * Initializes the GoWriter for the package and filename to be written to.
     *
     * @param fullPackageName package and filename to be written to.
     */
    public GoWriter(String fullPackageName) {
        super(new ImportDeclarations(fullPackageName));

        this.fullPackageName = fullPackageName;
        this.innerWriter = false;
        init();
    }

    private GoWriter(String fullPackageName, boolean innerWriter) {
        super(new ImportDeclarations(fullPackageName));

        this.fullPackageName = fullPackageName;
        this.innerWriter = innerWriter;
        init();
    }

    private void init() {
        trimBlankLines();
        trimTrailingSpaces();
        setIndentText("\t");
        putFormatter('T', new GoSymbolFormatter());
        putFormatter('P', new PointableGoSymbolFormatter());
        putFormatter('W', new GoWritableInjector());
        putFormatter('D', new GoDependencyFormatter());

        putContext("fmt.Sprintf", SmithyGoDependency.FMT.func("Sprintf"));
        putContext("fmt.Errorf", SmithyGoDependency.FMT.func("Errorf"));
        putContext("errors.As", SmithyGoDependency.ERRORS.func("As"));
        putContext("context.Context", SmithyGoDependency.CONTEXT.func("Context"));
        putContext("time.Now", SmithyGoDependency.TIME.func("Now"));

        if (!innerWriter) {
            packageDocs = new GoWriter(this.fullPackageName, true);
        }
    }

    // TODO figure out better way to annotate where the failure occurs, check templates and args
    // TODO to try to find programming bugs.

    /**
     * Joins multiple writables together within a single writable without newlines between writables in the list.
     *
     * @param writables list of writables to join
     * @param separator separator between writables
     * @return new writable
     */
    public static Writable joinWritables(List<Writable> writables, String separator) {
        return (GoWriter w) -> {
            for (int i = 0; i < writables.size(); i++) {
                var writable = writables.get(i);
                var sep = separator;
                if (i == writables.size() - 1) {
                    sep = "";
                }
                w.writeInline("$W" + sep, writable);
            }
        };
    }

    /**
     * Returns a Writable for the string and args to be composed inline to another writer's contents.
     *
     * @param contents string to write.
     * @param args     Arguments to use when evaluating the contents string.
     * @return Writable to be evaluated.
     */
    @SafeVarargs
    public static Writable goTemplate(String contents, Map<String, Object>... args) {
        validateTemplateArgsNotNull(args);
        return (GoWriter w) -> {
            w.writeGoTemplate(contents, args);
        };
    }

    /**
     * Returns a Writable for the string and args to be composed inline to another writer's contents.
     *
     * @param content  string to write.
     * @param args     Arguments to use when evaluating the contents string.
     * @return Writable to be evaluated.
     */
    public static Writable goTemplate(Object content, Object... args) {
        return writer -> writer.write(content, args);
    }

    public static Writable goDocTemplate(String contents) {
        return goDocTemplate(contents, new HashMap<>());
    }

    /**
     * Auto-formats a multi-paragraph string as a doc writable (including line wrapping).
     * @param contents The docs.
     * @return writer for formatted docs.
     */
    public static Writable autoDocTemplate(String contents) {
        var paragraphs = contents.split("\n\n");
        var chain = new ChainWritable();
        for (int i = 0; i < paragraphs.length; ++i) {
            chain.add(docParagraphWriter(paragraphs[i], i < paragraphs.length - 1));
        }
        return chain.compose(false);
    }

    private static Writable docParagraphWriter(String paragraph, boolean writeNewline) {
        return writer -> {
            writer.writeDocs(paragraph);
            if (writeNewline) {
                writer.writeDocs("");
            }
        };
    }

    @SafeVarargs
    public static Writable goDocTemplate(String contents, Map<String, Object>... args) {
        validateTemplateArgsNotNull(args);
        return (GoWriter w) -> {
            w.writeGoDocTemplate(contents, args);
        };
    }

    /**
     * Returns a Writable that can later be invoked to write the contents as template
     * as a code block instead of single content of text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param fn            closure to write
     */
    public static Writable goBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Consumer<GoWriter> fn
    ) {
        return goBlockTemplate(beforeNewLine, afterNewLine, new Map[0], fn);
    }

    /**
     * Returns a Writable that can later be invoked to write the contents as template
     * as a code block instead of single content of text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param args1         template arguments
     * @param fn            closure to write
     */
    public static Writable goBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> args1,
            Consumer<GoWriter> fn
    ) {
        return goBlockTemplate(beforeNewLine, afterNewLine, new Map[]{args1}, fn);
    }

    /**
     * Returns a Writable that can later be invoked to write the contents as template
     * as a code block instead of single content of text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param args1         template arguments
     * @param args2         template arguments
     * @param fn            closure to write
     */
    public static Writable goBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> args1,
            Map<String, Object> args2,
            Consumer<GoWriter> fn
    ) {
        return goBlockTemplate(beforeNewLine, afterNewLine, new Map[]{args1, args2}, fn);
    }

    /**
     * Returns a Writable that can later be invoked to write the contents as template
     * as a code block instead of single content of text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param args1         template arguments
     * @param args2         template arguments
     * @param args3         template arguments
     * @param fn            closure to write
     */
    public static Writable goBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> args1,
            Map<String, Object> args2,
            Map<String, Object> args3,
            Consumer<GoWriter> fn
    ) {
        return goBlockTemplate(beforeNewLine, afterNewLine, new Map[]{args1, args2, args3}, fn);
    }

    /**
     * Returns a Writable that can later be invoked to write the contents as template
     * as a code block instead of single content of text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param args1         template arguments
     * @param args2         template arguments
     * @param args3         template arguments
     * @param args4         template arguments
     * @param fn            closure to write
     */
    public static Writable goBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> args1,
            Map<String, Object> args2,
            Map<String, Object> args3,
            Map<String, Object> args4,
            Consumer<GoWriter> fn
    ) {
        return goBlockTemplate(beforeNewLine, afterNewLine, new Map[]{args1, args2, args3, args4}, fn);
    }

    /**
     * Returns a Writable that can later be invoked to write the contents as template
     * as a code block instead of single content of text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param args1         template arguments
     * @param args2         template arguments
     * @param args3         template arguments
     * @param args4         template arguments
     * @param args5         template arguments
     * @param fn            closure to write
     */
    public static Writable goBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> args1,
            Map<String, Object> args2,
            Map<String, Object> args3,
            Map<String, Object> args4,
            Map<String, Object> args5,
            Consumer<GoWriter> fn
    ) {
        return goBlockTemplate(beforeNewLine, afterNewLine, new Map[]{args1, args2, args3, args4, args5}, fn);
    }

    /**
     * Returns a Writable that can later be invoked to write the contents as template
     * as a code block instead of single content of text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param args          template arguments
     * @param fn            closure to write
     */
    public static Writable goBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object>[] args,
            Consumer<GoWriter> fn
    ) {
        validateTemplateArgsNotNull(args);
        return (GoWriter w) -> {
            w.writeGoBlockTemplate(beforeNewLine, afterNewLine, args, fn);
        };
    }

    /**
     * Returns a Writable that does nothing.
     *
     * @return Writable that does nothing
     */
    public static Writable emptyGoTemplate() {
        return (GoWriter w) -> {
        };
    }

    /**
     * Writes the contents and arguments as a template to the writer.
     *
     * @param contents string to write
     * @param args     Arguments to use when evaluating the contents string.
     */
    @SafeVarargs
    public final void writeGoTemplate(String contents, Map<String, Object>... args) {
        withTemplate(contents, args, (template) -> {
            try {
                write(contents);
            } catch (Exception e) {
                throw new CodegenException("Failed to render template\n" + contents + "\nReason: " + e.getMessage(), e);
            }
        });
    }

    @SafeVarargs
    public final void writeGoDocTemplate(String contents, Map<String, Object>... args) {
        writeRenderedDocs(goTemplate(contents, args));
    }

    /**
     * Writes the contents as template as a code block instead of single content fo text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param fn            closure to write
     */
    public void writeGoBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Consumer<GoWriter> fn
    ) {
        writeGoBlockTemplate(beforeNewLine, afterNewLine, new Map[0], fn);
    }

    /**
     * Writes the contents as template as a code block instead of single content fo text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param arg1          first map argument
     * @param fn            closure to write
     */
    public void writeGoBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> arg1,
            Consumer<GoWriter> fn
    ) {
        writeGoBlockTemplate(beforeNewLine, afterNewLine, new Map[]{arg1}, fn);
    }

    /**
     * Writes the contents as template as a code block instead of single content fo text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param arg1          first map argument
     * @param arg2          second map argument
     * @param fn            closure to write
     */
    public void writeGoBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> arg1,
            Map<String, Object> arg2,
            Consumer<GoWriter> fn
    ) {
        writeGoBlockTemplate(beforeNewLine, afterNewLine, new Map[]{arg1, arg2}, fn);
    }

    /**
     * Writes the contents as template as a code block instead of single content fo text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param arg1          first map argument
     * @param arg2          second map argument
     * @param arg3          third map argument
     * @param fn            closure to write
     */
    public void writeGoBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> arg1,
            Map<String, Object> arg2,
            Map<String, Object> arg3,
            Consumer<GoWriter> fn
    ) {
        writeGoBlockTemplate(beforeNewLine, afterNewLine, new Map[]{arg1, arg2, arg3}, fn);
    }

    /**
     * Writes the contents as template as a code block instead of single content fo text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param arg1          first map argument
     * @param arg2          second map argument
     * @param arg3          third map argument
     * @param arg4          forth map argument
     * @param fn            closure to write
     */
    public void writeGoBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> arg1,
            Map<String, Object> arg2,
            Map<String, Object> arg3,
            Map<String, Object> arg4,
            Consumer<GoWriter> fn
    ) {
        writeGoBlockTemplate(beforeNewLine, afterNewLine, new Map[]{arg1, arg2, arg3, arg4}, fn);
    }

    /**
     * Writes the contents as template as a code block instead of single content fo text.
     *
     * @param beforeNewLine text before new line
     * @param afterNewLine  text after new line
     * @param arg1          first map argument
     * @param arg2          second map argument
     * @param arg3          third map argument
     * @param arg4          forth map argument
     * @param arg5          forth map argument
     * @param fn            closure to write
     */
    public void writeGoBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object> arg1,
            Map<String, Object> arg2,
            Map<String, Object> arg3,
            Map<String, Object> arg4,
            Map<String, Object> arg5,
            Consumer<GoWriter> fn
    ) {
        writeGoBlockTemplate(beforeNewLine, afterNewLine, new Map[]{arg1, arg2, arg3, arg4, arg5}, fn);
    }

    public void writeGoBlockTemplate(
            String beforeNewLine,
            String afterNewLine,
            Map<String, Object>[] args,
            Consumer<GoWriter> fn
    ) {
        withTemplate(beforeNewLine, args, (header) -> {
            conditionalBlock(header, afterNewLine, true, new Object[0], fn);
        });
    }

    private void withTemplate(
            String template,
            Map<String, Object>[] argMaps,
            Consumer<String> fn
    ) {
        pushState();
        for (var args : argMaps) {
            putContext(args);
        }
        validateContext(template);
        fn.accept(template);
        popState();
    }

    private GoWriter conditionalBlock(
            String beforeNewLine,
            String afterNewLine,
            boolean conditional,
            Object[] args,
            Consumer<GoWriter> fn
    ) {
        if (conditional) {
            openBlock(beforeNewLine.trim(), args);
        }

        fn.accept(this);

        if (conditional) {
            closeBlock(afterNewLine.trim());
        }

        return this;
    }

    private static void validateTemplateArgsNotNull(Map<String, Object>[] argMaps) {
        for (var args : argMaps) {
            args.forEach((k, v) -> {
                if (v == null) {
                    throw new CodegenException("Template argument " + k + " cannot be null");
                }
            });
        }
    }

    private void validateContext(String template) {
        var matcher = ARGUMENT_NAME_PATTERN.matcher(template);

        while (matcher.find()) {
            var keyName = matcher.group(1);
            var value = getContext(keyName);
            if (value == null) {
                throw new CodegenException(
                        "Go template expected " + keyName + " but was not present in context scope."
                                + " Template: \n" + template);
            }
        }
    }

    /**
     * Sets the wrap length of doc strings written.
     *
     * @param wrapLength The wrap length of the doc string.
     * @return Returns the writer.
     */
    public GoWriter setDocWrapLength(final int wrapLength) {
        this.docWrapLength = wrapLength;
        return this;
    }

    /**
     * Sets the wrap length of doc strings written to the default value for the Go writer.
     *
     * @return Returns the writer.
     */
    public GoWriter setDocWrapLength() {
        this.docWrapLength = DEFAULT_DOC_WRAP_LENGTH;
        return this;
    }

    /**
     * Imports one or more symbols if necessary, using the name of the
     * symbol and only "USE" references.
     *
     * @param container Container of symbols to add.
     * @return Returns the writer.
     */
    public GoWriter addUseImports(SymbolContainer container) {
        for (Symbol symbol : container.getSymbols()) {
            addImport(symbol,
                    CodegenUtils.getSymbolNamespaceAlias(symbol),
                    SymbolReference.ContextOption.USE);
        }
        return this;
    }

    /**
     * Imports a symbol reference if necessary, using the alias of the
     * reference and only associated "USE" references.
     *
     * @param symbolReference Symbol reference to import.
     * @return Returns the writer.
     */
    public GoWriter addUseImports(SymbolReference symbolReference) {
        return addImport(symbolReference.getSymbol(), symbolReference.getAlias(), SymbolReference.ContextOption.USE);
    }

    /**
     * Adds and imports the given dependency.
     *
     * @param goDependency The GoDependency to import.
     * @return Returns the writer.
     */
    public GoWriter addUseImports(GoDependency goDependency) {
        addDependency(goDependency);
        return addImport(goDependency.getImportPath(), goDependency.getAlias());
    }

    private void addImports(GoWriter other) {
        this.getImportContainer().addImports(other.getImportContainer());
    }

    private boolean isExternalNamespace(String namespace) {
        return !StringUtils.isBlank(namespace) && !namespace.equals(fullPackageName);
    }

    void addImportReferences(Symbol symbol, SymbolReference.ContextOption... options) {
        for (SymbolReference reference : symbol.getReferences()) {
            for (SymbolReference.ContextOption option : options) {
                if (reference.hasOption(option)) {
                    addImport(reference.getSymbol(), reference.getAlias(), options);
                    break;
                }
            }
        }
    }

    /**
     * Imports a package using an alias if necessary.
     *
     * @param packageName Package to import.
     * @param as          Alias to refer to the package as.
     * @return Returns the writer.
     */
    public GoWriter addImport(String packageName, String as) {
        getImportContainer().addImport(packageName, as);
        return this;
    }

    private void addDependencies(GoWriter other) {
        addDependency(other);
    }

    /**
     * Writes documentation comments.
     *
     * @param runnable Runnable that handles actually writing docs with the writer.
     * @return Returns the writer.
     */
    private void writeDocs(AbstractCodeWriter<GoWriter> writer, Runnable runnable) {
        writer.pushState("docs");
        writer.setNewlinePrefix("// ");
        runnable.run();
        writer.setNewlinePrefix("");
        writer.popState();
    }

    private void writeDocs(AbstractCodeWriter<GoWriter> writer, int docWrapLength, String docs) {
        String convertedDoc = DocumentationConverter.convert(docs, docWrapLength);
        writeDocs(writer, () -> writer.write(convertedDoc.replace("$", "$$")));
    }

    /**
     * Writes documentation comments from a string.
     *
     * <p>This function escapes "$" characters so formatters are not run.
     *
     * @param docs Documentation to write.
     * @return Returns the writer.
     */
    public GoWriter writeDocs(String docs) {
        writeDocs(this, docWrapLength, docs);
        return this;
    }

    /**
     * Writes documentation from an arbitrary Writable.
     *
     * @param writable Contents to be written.
     * @return Returns the writer.
     */
    public GoWriter writeRenderedDocs(Writable writable) {
        writeRenderedDocs(this, docWrapLength, writable);
        return this;
    }

    private void writeRenderedDocs(AbstractCodeWriter<GoWriter> writer, int docWrapLength, Writable writable) {
        var innerWriter = new GoWriter(fullPackageName, true);
        writable.accept(innerWriter);
        var wrappedDocs = StringUtils.wrap(innerWriter.toString().trim(), docWrapLength);
        writeDocs(writer, () -> writer.write(wrappedDocs.replace("$", "$$")));
    }

    /**
     * Writes the doc to the Go package docs that are written prior to the go package statement.
     *
     * @param docs documentation to write to package doc.
     * @return writer
     */
    public GoWriter writePackageDocs(String docs) {
        writeDocs(packageDocs, docWrapLength, docs);
        return this;
    }

    /**
     * Writes the doc to the Go package docs that are written prior to the go package statement. This does not perform
     * line wrapping and the provided formatting must be valid Go doc.
     *
     * @param docs documentation to write to package doc.
     * @return writer
     */
    public GoWriter writeRawPackageDocs(String docs) {
        writeDocs(packageDocs, () -> {
            packageDocs.write(docs);
        });
        return this;
    }

    /**
     * Writes shape documentation comments if docs are present.
     *
     * @param shape Shape to write the documentation of.
     * @return Returns true if docs were written.
     */
    @SmithyInternalApi
    public boolean writeShapeDocs(Shape shape) {
        return shape.getTrait(DocumentationTrait.class)
                .map(DocumentationTrait::getValue)
                .map(docs -> {
                    writeDocs(docs);
                    return true;
                }).orElse(false);
    }

    /**
     * Writes shape documentation comments to the writer's package doc if docs are present.
     *
     * @param shape Shape to write the documentation of.
     * @return Returns true if docs were written.
     */
    public boolean writePackageShapeDocs(Shape shape) {
        return shape.getTrait(DocumentationTrait.class)
                .map(DocumentationTrait::getValue)
                .map(docs -> {
                    writePackageDocs(docs);
                    if (shape.hasTrait(DeprecatedTrait.class)) {
                        var message = shape.expectTrait(DeprecatedTrait.class)
                                .getMessage()
                                .orElse("This package is deprecated.");
                        writePackageDocs("");
                        writePackageDocs("Deprecated: " + message);
                    }
                    return true;
                }).orElse(false);
    }

    /**
     * Writes member shape documentation comments if docs are present.
     *
     * @param model  Model used to dereference targets.
     * @param member Shape to write the documentation of.
     * @return Returns true if docs were written.
     */
    public boolean writeMemberDocs(Model model, MemberShape member) {
        boolean hasDocs;

        hasDocs = member.getMemberTrait(model, DocumentationTrait.class)
                .map(DocumentationTrait::getValue)
                .map(docs -> {
                    writeDocs(docs);
                    return true;
                }).orElse(false);

        Optional<String> stringOptional = member.getMemberTrait(model, MediaTypeTrait.class)
                .map(StringTrait::getValue);
        if (stringOptional.isPresent()) {
            if (hasDocs) {
                writeDocs("");
            }
            writeDocs("This value conforms to the media type: " + stringOptional.get());
            hasDocs = true;
        }

        GoUsageIndex usageIndex = GoUsageIndex.of(model);
        if (usageIndex.isUsedForOutput(member)) {
            if (member.getMemberTrait(model,
                    HttpPrefixHeadersTrait.class).isPresent()) {
                if (hasDocs) {
                    writeDocs("");
                }
                writeDocs("Map keys will be normalized to lower-case.");
                hasDocs = true;
            }
        }

        if (member.getMemberTrait(model, RequiredTrait.class).isPresent()) {
            if (hasDocs) {
                writeDocs("");
            }
            writeDocs("This member is required.");
            hasDocs = true;
        }

        Optional<DeprecatedTrait> deprecatedTrait = member.getMemberTrait(model, DeprecatedTrait.class);
        if (member.getTrait(DeprecatedTrait.class).isPresent() || isTargetDeprecated(model, member)) {
            if (hasDocs) {
                writeDocs("");
            }
            final String defaultMessage = "This member has been deprecated.";
            String message = defaultMessage;
            if (deprecatedTrait.isPresent()) {
                message = deprecatedTrait.get().getMessage().map(s -> {
                    if (s.length() == 0) {
                        return defaultMessage;
                    }
                    return s;
                }).orElse(defaultMessage);
            }
            writeDocs("Deprecated: " + message);
        }

        return hasDocs;
    }

    private boolean isTargetDeprecated(Model model, MemberShape member) {
        return model.expectShape(member.getTarget()).getTrait(DeprecatedTrait.class).isPresent()
                // don't consider deprecated prelude shapes (like PrimitiveBoolean)
                && !Prelude.isPreludeShape(member.getTarget());
    }

    public void write(Writable w) {
        write("$W", w);
    }

    public GoWriter addBuildTag(String tag) {
        buildTags.add(tag);
        return this;
    }

    @Override
    public String toString() {
        String contents = super.toString();

        if (innerWriter) {
            return contents;
        }

        var tags = buildTags.isEmpty()
                ? ""
                : "//go:build " + String.join(",", buildTags) + "\n";

        String[] packageParts = fullPackageName.split("/");
        String header = String.format("// Code generated by smithy-go-codegen DO NOT EDIT.%n%n");

        String packageName = packageParts[packageParts.length - 1];
        if (packageName.startsWith("v") && packageParts.length >= 2) {
            String remaining = packageName.substring(1);
            try {
                int value = Integer.parseInt(remaining);
                packageName = packageParts[packageParts.length - 2];
                if (value == 0 || value == 1) {
                    throw new CodegenException("module paths vN version component must only be N >= 2");
                }
            } catch (NumberFormatException ne) {
                // Do nothing
            }
        }

        String packageDocs = this.packageDocs.toString();
        String packageStatement = String.format("package %s%n%n", packageName);

        String importString = getImportContainer().toString();
        String strippedContents = StringUtils.stripStart(contents, null);
        String strippedImportString = StringUtils.strip(importString, null);

        // Don't add an additional new line between explicit imports and managed imports.
        if (!strippedImportString.isEmpty() && strippedContents.startsWith("import ")) {
            return header + strippedImportString + "\n" + strippedContents;
        }

        return header + packageDocs + tags + packageStatement + importString + contents;
    }

    /**
     * Implements Go symbol formatting for the {@code $T} formatter.
     */
    private class GoSymbolFormatter implements BiFunction<Object, String, String> {
        @Override
        public String apply(Object type, String indent) {
            if (type instanceof Symbol) {
                Symbol resolvedSymbol = (Symbol) type;
                String literal = resolvedSymbol.getName();

                boolean isSlice = resolvedSymbol.getProperty(SymbolUtils.GO_SLICE, Boolean.class).orElse(false);
                boolean isMap = resolvedSymbol.getProperty(SymbolUtils.GO_MAP, Boolean.class).orElse(false);
                if (isSlice || isMap) {
                    resolvedSymbol = resolvedSymbol.getProperty(SymbolUtils.GO_ELEMENT_TYPE, Symbol.class)
                            .orElseThrow(() -> new CodegenException("Expected go element type property to be defined"));
                    literal = new PointableGoSymbolFormatter().apply(resolvedSymbol, "nested");
                } else if (!SymbolUtils.isUniverseType(resolvedSymbol)
                        && isExternalNamespace(resolvedSymbol.getNamespace())) {
                    literal = formatWithNamespace(resolvedSymbol);
                }
                addUseImports(resolvedSymbol);

                if (isSlice) {
                    return "[]" + literal;
                } else if (isMap) {
                    return "map[string]" + literal;
                } else {
                    return literal;
                }
            } else if (type instanceof SymbolReference) {
                SymbolReference typeSymbol = (SymbolReference) type;
                addImport(typeSymbol.getSymbol(), typeSymbol.getAlias(), SymbolReference.ContextOption.USE);
                return typeSymbol.getAlias();
            } else {
                throw new CodegenException(
                        "Invalid type provided to $T. Expected a Symbol, but found `" + type + "`");
            }
        }

        private String formatWithNamespace(Symbol symbol) {
            if (StringUtils.isEmpty(symbol.getNamespace())) {
                return symbol.getName();
            }
            return String.format("%s.%s", CodegenUtils.getSymbolNamespaceAlias(symbol), symbol.getName());
        }
    }

    /**
     * Implements Go symbol formatting for the {@code $P} formatter. This is identical to the $T
     * formatter, except that it will add a * to symbols that can be pointers.
     */
    private final class PointableGoSymbolFormatter extends GoSymbolFormatter {
        @Override
        public String apply(Object type, String indent) {
            String formatted = super.apply(type, indent);
            if (isPointer(type)) {
                formatted = "*" + formatted;
            }
            return formatted;
        }

        private boolean isPointer(Object type) {
            if (type instanceof Symbol) {
                Symbol typeSymbol = (Symbol) type;
                return typeSymbol.getProperty(SymbolUtils.POINTABLE, Boolean.class).orElse(false);
            } else if (type instanceof SymbolReference) {
                SymbolReference typeSymbol = (SymbolReference) type;
                return typeSymbol.getProperty(SymbolUtils.POINTABLE, Boolean.class).orElse(false)
                        || typeSymbol.getSymbol().getProperty(SymbolUtils.POINTABLE, Boolean.class).orElse(false);
            } else {
                throw new CodegenException(
                        "Invalid type provided to $P. Expected a Symbol, but found `" + type + "`");
            }
        }
    }

    private final class GoWritableInjector extends GoSymbolFormatter {
        @Override
        public String apply(Object type, String indent) {
            if (!(type instanceof Writable)) {
                throw new CodegenException(
                        "expect Writable for GoWriter W injector, but got " + type);
            }
            var innerWriter = new GoWriter(fullPackageName, true);
            ((Writable) type).accept(innerWriter);
            addImports(innerWriter);
            addDependencies(innerWriter);
            return innerWriter.toString().trim();
        }
    }

    /**
     * Implements Go symbol formatting for the {@code $D} formatter.
     */
    private final class GoDependencyFormatter implements BiFunction<Object, String, String> {
        @Override
        public String apply(Object type, String indent) {
            if (type instanceof GoDependency) {
                addUseImports((GoDependency) type);
            } else {
                throw new CodegenException(
                        "Invalid type provided to $D. Expected a GoDependency, but found `" + type + "`");
            }
            return "";
        }
    }

}
