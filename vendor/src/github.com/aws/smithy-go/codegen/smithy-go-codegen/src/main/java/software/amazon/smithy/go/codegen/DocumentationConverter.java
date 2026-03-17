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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import software.amazon.smithy.utils.CodeWriter;
import software.amazon.smithy.utils.SetUtils;
import software.amazon.smithy.utils.StringUtils;

/**
 * Converts commonmark-formatted documentation into godoc format.
 */
public final class DocumentationConverter {
    // godoc only supports text blocks, root-level non-inline code blocks, headers, and links.
    // This allowlist strips out anything we can't reasonably convert, vastly simplifying the
    // node tree we end up having to crawl through.
    private static final Safelist GODOC_ALLOWLIST = new Safelist()
            .addTags("code", "pre", "ul", "ol", "li", "a", "br", "h1", "h2", "h3", "h4", "h5", "h6", "p")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https");

    // Construct a markdown parser that specifically ignores parsing indented code blocks. This
    // is because HTML blocks can have really wonky formatting that can be mis-attributed to an
    // indented code blocks. We may need to add a configuration option to re-enable this.
    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .enabledBlockTypes(SetUtils.of(
                    Heading.class, HtmlBlock.class, ThematicBreak.class, FencedCodeBlock.class,
                    BlockQuote.class, ListBlock.class))
            .build();

    private DocumentationConverter() {}

    /**
     * Converts a commonmark formatted string into a godoc formatted string.
     *
     * @param docs commonmark formatted documentation
     * @return godoc formatted documentation
     */
    public static String convert(String docs, int docWrapLength) {
        // Smithy's documentation format is commonmark, which can inline html. So here we convert
        // to html so we have a single known format to work with.
        String htmlDocs = HtmlRenderer.builder().escapeHtml(false).build().render(MARKDOWN_PARSER.parse(docs));

        // Strip out tags and attributes we can't reasonably convert to godoc.
        htmlDocs = Jsoup.clean(htmlDocs, GODOC_ALLOWLIST);

        // Now we parse the html and visit the resultant nodes to render the godoc.
        FormattingVisitor formatter = new FormattingVisitor(docWrapLength);
        Node body = Jsoup.parse(htmlDocs).body();
        NodeTraversor.traverse(formatter, body);
        return formatter.toString();
    }

    private static class FormattingVisitor implements NodeVisitor {
        private static final Set<String> TEXT_BLOCK_NODES = SetUtils.of(
                "br", "p", "h1", "h2", "h3", "h4", "h5", "h6", "note"
        );
        private static final Set<String> LIST_BLOCK_NODES = SetUtils.of("ul", "ol");
        private static final Set<String> CODE_BLOCK_NODES = SetUtils.of("pre", "code");
        private final CodeWriter writer;
        // Godoc links are added at the end as `[text]: http://example.com` so keeping a collection to add them later
        private final Map<String, String> links;

        private boolean needsListPrefix = false;
        private boolean shouldStripPrefixWhitespace = false;
        private int docWrapLength;
        private int listDepth;
        // the last line string written, used to calculate the remaining spaces to reach docWrapLength
        // and determine if a split char is needed between it and next string
        private String lastLineString;

        FormattingVisitor(int docWrapLength) {
            writer = new CodeWriter();
            writer.trimTrailingSpaces(false);
            writer.trimBlankLines();
            writer.insertTrailingNewline(false);
            this.docWrapLength = docWrapLength;
            this.links = new HashMap<>();
            this.lastLineString = "";
        }

        @Override
        public void head(Node node, int depth) {
            String name = node.nodeName();
            if (isTopLevelCodeBlock(node, depth)) {
                writer.indent();
            }

            if (node.nodeName().equals("a")) {
                // Logic to format anchors as Go Links https://tip.golang.org/doc/comment#links
                Element element = (Element) node;
                String text = element.text();
                String url = element.absUrl("href");
                if (url.isEmpty()) {
                    // an empty anchor won't have a reference at the end, so just
                    // output it directly to the output
                    writer.writeInlineWithNoFormatting(text);
                    return;
                }
                String wrappedAnchorText = "[" + text + "]";
                writer.writeInlineWithNoFormatting(wrappedAnchorText);
                links.put(text, url);
            }

            Node parentNode = node.parentNode();
            if (parentNode != null && parentNode.nodeName().equals("a") && node instanceof TextNode) {
                // anchor tags get processed twice: once as anchor tags and another one as
                // textNodes. Since this was already processed as anchor, no need to do anything else
                return;
            }
            if (node instanceof TextNode) {
                writeText((TextNode) node);
            } else if (isTopLevelCodeBlock(node, depth)) {
                writeNewline();
                writeIndent();
            } else if (LIST_BLOCK_NODES.contains(name)) {
                listDepth++;
            } else if (name.equals("li")) {
                // We don't actually write out the list prefix here in case the list element
                // starts with one or more text blocks. By deferring writing those out until
                // the first bit of actual text, we can ensure that no intermediary newlines
                // are kept. It also has the added benefit of eliminating empty list elements.
                needsListPrefix = true;
            }
        }

        private void writeText(TextNode node) {
            if (node.isBlank()) {
                return;
            }

            // Docs can have valid $ characters that shouldn't run through formatters.
            String text = node.text().replace("$", "$$");
            if (shouldStripPrefixWhitespace) {
                shouldStripPrefixWhitespace = false;
                text = StringUtils.stripStart(text, " \t");
            }

            if (listDepth > 0) {
                text = StringUtils.stripStart(text, " \t");
                if (needsListPrefix) {
                    needsListPrefix = false;
                    text = "  - " + text;
                    writeNewline();
                }

                writeWrappedText(text, "\n  ");
            } else {
                writeWrappedText(text, "\n");
            }
        }

        private void writeWrappedText(String text, String newLineIndent) {
            // check the last line's remaining space to see if test should be
            // split to 2 parts to write to current and next line
            // note that wrapped text will not contain desired indent at the beginning,
            // so indent will be added to the wrapped text when it is written to a new line

            // right boundary index of text to be written to the same line exceeding
            // neither docWrapLength nor text length
            int trailingLineCutoff = Math.min(Math.max(docWrapLength - lastLineString.length(), 0), text.length());
            // the index of last space on the left of boundary if exist
            int lastSpace = text.substring(0, trailingLineCutoff).lastIndexOf(" ");
            // if current line is large enough to put the text, just append complete text to current line
            // otherwise, cut out next line string starting from lastSpace index
            String appendString = trailingLineCutoff < text.length() ? text.substring(0, lastSpace + 1) : text;
            String nextLineString = trailingLineCutoff < text.length() ? text.substring(lastSpace + 1) : "";

            if (!appendString.isEmpty()) {
                ensureSplit(" ", appendString);
                writeInline(appendString);
            }
            if (!nextLineString.isEmpty()) {
                nextLineString = StringUtils.stripStart(newLineIndent, "\n")
                    + StringUtils.wrap(nextLineString, docWrapLength, newLineIndent, false);
                writeNewline();
                writeInline(nextLineString);
            }
        }

        private void ensureSplit(String split, String text) {
            if (!text.startsWith(split) && !lastLineString.isEmpty() && !lastLineString.endsWith(split)) {
                writeInline(split);
            }
        }

        private void writeNewline() {
            // While jsoup will strip out redundant whitespace, it will still leave some. If we
            // start a new line then we want to make sure we don't keep any prefixing whitespace.
            // need to refresh last line string
            shouldStripPrefixWhitespace = true;
            writer.write("");
            lastLineString = "";
        }

        private void writeInline(String contents, String... args) {
            // write text at the current line, update last line string
            String formatText = writer.format(contents, args);
            writer.writeInlineWithNoFormatting(formatText);
            formatText = lastLineString + formatText;
            lastLineString = formatText.substring(formatText.lastIndexOf("\n") + 1);
        }

        void writeIndent() {
            writer.setNewline("").write("").setNewline("\n");
        }

        private boolean isTopLevelCodeBlock(Node node, int depth) {
            // The node must be a code block node
            if (!CODE_BLOCK_NODES.contains(node.nodeName())) {
                return false;
            }

            // It must either have no siblings or its siblings must be separate blocks.
            if (!allSiblingsAreBlocks(node)) {
                return false;
            }

            // Depth 0 will always be a "body" element, so depth 1 means it's top level.
            if (depth == 1) {
                return true;
            }

            // If its depth is 2, it could still be effectively top level if its parent is a p
            // node whose siblings are all blocks.
            Node parent = node.parent();
            return depth == 2 && parent.nodeName().equals("p") && allSiblingsAreBlocks(parent);
        }

        /**
         * Determines whether a given node's siblings are all text blocks, code blocks, or lists.
         *
         * <p>Siblings that are blank text nodes are skipped.
         *
         * @param node The node whose siblings should be checked.
         * @return true if the node's siblings are blocks, otherwise false.
         */
        private boolean allSiblingsAreBlocks(Node node) {
            // Find the nearest sibling to the left which is not a blank text node.
            Node previous = node.previousSibling();
            while (true) {
                if (previous instanceof TextNode) {
                    if (((TextNode) previous).isBlank()) {
                        previous = previous.previousSibling();
                        continue;
                    }
                }
                break;
            }

            // Find the nearest sibling to the right which is not a blank text node.
            Node next = node.nextSibling();
            while (true) {
                if (next instanceof TextNode) {
                    if (((TextNode) next).isBlank()) {
                        next = next.nextSibling();
                        continue;
                    }
                }
                break;
            }

            return (previous == null || isBlockNode(previous)) && (next == null || isBlockNode(next));
        }

        private boolean isBlockNode(Node node) {
            String name = node.nodeName();
            return TEXT_BLOCK_NODES.contains(name) || LIST_BLOCK_NODES.contains(name)
                    || CODE_BLOCK_NODES.contains(name);
        }

        @Override
        public void tail(Node node, int depth) {
            String name = node.nodeName();
            if (isTopLevelCodeBlock(node, depth)) {
                writer.dedent();
            }

            if (TEXT_BLOCK_NODES.contains(name) || isTopLevelCodeBlock(node, depth)) {
                // A single newline is just treated as a line break, but gets
                // rendered as the same block.
                // Two ensure the text gets treated as a separate text block
                writeNewline();
                writeNewline();
            } else if (LIST_BLOCK_NODES.contains(name)) {
                listDepth--;
                if (listDepth == 0) {
                    writeNewline();
                }
            } else if (name.equals("li")) {
                // Clear out the expectation of a list element if the element's body is empty.
                needsListPrefix = false;
            }
        }

        @Override
        public String toString() {
            String result = writer.toString();
            if (StringUtils.isBlank(result)) {
                return "";
            }

            // Strip trailing whitespace from every line. We can't use the codewriter for this due to
            // not knowing when a line will end, as we typically build them up over many elements.
            String[] lines = result.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                lines[i] = StringUtils.stripEnd(lines[i], " \t");
            }
            result = String.join("\n", lines);

            // iterate over every link found and append it at the end
            String allLinks = links.entrySet().stream()
                    .map(link -> formatLink(link))
                    .collect(Collectors.joining("\n"));
            if (!allLinks.isEmpty()) {
                result += "\n\n" + allLinks;
            }

            // Strip out leading and trailing newlines.
            return StringUtils.strip(result, "\n");
        }

        private String formatLink(Map.Entry<String, String> link) {
            String anchor = link.getKey();
            String destination = link.getValue();
            return "[%s]: %s".formatted(anchor, destination);
        }
    }
}
