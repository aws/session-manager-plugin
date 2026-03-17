package software.amazon.smithy.go.codegen;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DocumentationConverterTest {
    private static final int DEFAULT_DOC_WRAP_LENGTH = 80;

    @ParameterizedTest
    @MethodSource("cases")
    void convertsDocs(String given, String expected) {
        assertThat(DocumentationConverter.convert(given, DEFAULT_DOC_WRAP_LENGTH), equalTo(expected));
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(
                        "Testing 1 2 3",
                        "Testing 1 2 3"
                ),
                Arguments.of(
                        "<a href=\"https://example.com\">a link</a>",
                        "[a link]\n\n[a link]: https://example.com"
                ),
                Arguments.of(
                        "<a>empty link</a>",
                        "empty link"
                ),
                Arguments.of(
                        "<ul><li>Testing 1 2 3</li> <li>FooBar</li></ul>",
                        "  - Testing 1 2 3\n  - FooBar"
                ),
                Arguments.of(
                        "<ul> <li>Testing 1 2 3</li> <li>FooBar</li> </ul>",
                        "  - Testing 1 2 3\n  - FooBar"
                ),
                Arguments.of(
                        " <ul> <li>Testing 1 2 3</li> <li>FooBar</li> </ul>",
                        "  - Testing 1 2 3\n  - FooBar"
                ),
                Arguments.of(
                        "<ul> <li> <p>Testing 1 2 3</p> </li><li> <p>FooBar</p></li></ul>",
                        "  - Testing 1 2 3\n\n  - FooBar"
                ),
                Arguments.of(
                        "<ul> <li><code>Testing</code>: 1 2 3</li> <li>FooBar</li> </ul>",
                        "  - Testing : 1 2 3\n  - FooBar"
                ),
                Arguments.of(
                        "<ul> <li><p><code>FOO</code> Bar</p></li><li><p><code>Xyz</code> ABC</p></li></ul>",
                        "  - FOO Bar\n\n  - Xyz ABC"
                ),
                Arguments.of(
                        "<ul><li>        foo</li><li>\tbar</li><li>\nbaz</li></ul>",
                        "  - foo\n  - bar\n  - baz"
                ),
                Arguments.of(
                        "<p><code>Testing</code>: 1 2 3</p>",
                        "Testing : 1 2 3"
                ),
                Arguments.of(
                        "<pre><code>Testing</code></pre>",
                        "    Testing"
                ),
                Arguments.of(
                        "<p>Testing 1 2                       3</p>",
                        "Testing 1 2 3"
                ),
                Arguments.of(
                        "<span data-target-type=\"operation\" data-service=\"secretsmanager\" "
                                + "data-target=\"CreateSecret\">CreateSecret</span> <span data-target-type="
                                + "\"structure\" data-service=\"secretsmanager\" data-target=\"SecretListEntry\">"
                                + "SecretListEntry</span> <span data-target-type=\"structure\" data-service="
                                + "\"secretsmanager\" data-target=\"CreateSecret$SecretName\">SecretName</span> "
                                + "<span data-target-type=\"structure\" data-service=\"secretsmanager\" "
                                + "data-target=\"SecretListEntry$KmsKeyId\">KmsKeyId</span>",
                        "CreateSecret SecretListEntry SecretName KmsKeyId"
                ),
                Arguments.of(
                        "<p> Deletes the replication configuration from the bucket. For information about replication"
                                + " configuration, see "
                                + "<a href=\" https://docs.aws.amazon.com/AmazonS3/latest/dev/crr.html\">"
                                + "Cross-Region Replication (CRR)</a> in the <i>Amazon S3 Developer Guide</i>. </p>",
                        " Deletes the replication configuration from the bucket. For information about\n" +
                                "replication configuration, see [Cross-Region Replication (CRR)]in the Amazon S3 Developer Guide.\n\n" +
                                "[Cross-Region Replication (CRR)]: https://docs.aws.amazon.com/AmazonS3/latest/dev/crr.html"
                ),
                Arguments.of(
                        "* foo\n* bar",
                        "  - foo\n  - bar"
                ),
                Arguments.of(
                        "[a link](https://example.com)",
                        "[a link]\n\n[a link]: https://example.com"
                ),
                Arguments.of("", ""),
                Arguments.of("<!-- foo bar -->", ""),
                Arguments.of("# Foo\nbar", "Foo\n\nbar"),
                Arguments.of("<h1>Foo</h1>bar", "Foo\n\nbar"),
                Arguments.of("## Foo\nbar", "Foo\n\nbar"),
                Arguments.of("<h2>Foo</h2>bar", "Foo\n\nbar"),
                Arguments.of("### Foo\nbar", "Foo\n\nbar"),
                Arguments.of("<h3>Foo</h3>bar", "Foo\n\nbar"),
                Arguments.of("#### Foo\nbar", "Foo\n\nbar"),
                Arguments.of("<h4>Foo</h4>bar", "Foo\n\nbar"),
                Arguments.of("##### Foo\nbar", "Foo\n\nbar"),
                Arguments.of("<h5>Foo</h5>bar", "Foo\n\nbar"),
                Arguments.of("###### Foo\nbar", "Foo\n\nbar"),
                Arguments.of("<h6>Foo</h6>bar", "Foo\n\nbar"),
                Arguments.of("Inline `code`", "Inline code"),
                Arguments.of("```\ncode block\n ```", "    code block"),
                Arguments.of("```java\ncode block\n ```", "    code block"),
                Arguments.of("foo<br/>bar", "foo\n\nbar"),
                Arguments.of("         <p>foo</p>", "foo"),
                Arguments.of("<p>paragraph one</p><p>paragraph two</p>", "paragraph one\n\nparagraph two"),
                Arguments.of("<a href=\"mailto:someone@example.com\">someone</a>", "someone")
        );
    }
}
