package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Rejected input: every case fails with a stable category, a location and a message. */
class ParserRejectionTest {

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("reserved first character", "k : @home\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("scoped package name", "pkg : @scope/name\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("former type marker", "k : @int 1\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("former null marker", "k : @null\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("former typed array", "k : @int [\n  1\n]\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("former empty typed array", "k : @int []\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("reserved array element", "k : [\n  @x\n]\n", RiffleError.INVALID_VALUE, 2),
                Arguments.of("escaped nul in a value", "k : \"a\\u0000b\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("escaped nul in a key", "\"a\\u0000b\" : v\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("literal nul in a value", "k : a\u0000b\n", RiffleError.INVALID_CHARACTER, 1),
                Arguments.of("inline map", "x : { a : b }\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("inline array", "x : [ one ]\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("unquoted pattern", "re : [a-z]+\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("unquoted template", "t : {name}\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("empty map with spaces", "x : { }\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("empty array with spaces", "x : [ ]\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("text after an empty array", "x : []y\n", RiffleError.TRAILING_TOKENS, 1),
                Arguments.of("trailing tokens after empty map", "x : {} y : 2\n", RiffleError.TRAILING_TOKENS, 1),
                Arguments.of("trailing tokens after quoted string", "x : \"a\" b\n", RiffleError.TRAILING_TOKENS, 1),
                Arguments.of("mismatched closer", "x : [\n  1\n}\n", RiffleError.MISMATCHED_CLOSER, 3),
                Arguments.of("duplicate key", "x : a\nx : b\n", RiffleError.DUPLICATE_KEY, 2),
                Arguments.of("duplicate quoted key", "\"a b\" : 1\na b : 2\n", RiffleError.INVALID_KEY, 2),
                Arguments.of("typed key", "@int 1 : a\n", RiffleError.INVALID_KEY, 1),
                Arguments.of("unterminated string", "x : \"abc\n", RiffleError.UNTERMINATED_STRING, 1),
                Arguments.of("invalid escape", "x : \"bad\\q\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("dangling escape before boundary", "x : \"abc\\\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("trailing tokens after boundary", "x : \"a\" junk\n", RiffleError.TRAILING_TOKENS, 1),
                Arguments.of("short unicode escape", "x : \"\\u12\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("isolated surrogate", "x : \"\\uD83D\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("unclosed map", "x : {\n y : z\n", RiffleError.UNCLOSED_COLLECTION, 1),
                Arguments.of("unclosed array", "x : [\n y\n", RiffleError.UNCLOSED_COLLECTION, 1),
                Arguments.of("stray closer", "}\n", RiffleError.UNEXPECTED_CLOSER, 1),
                Arguments.of("root array", "[\n  a\n]\n", RiffleError.MISSING_COLON, 1),
                Arguments.of("missing colon", "x\n", RiffleError.MISSING_COLON, 1),
                Arguments.of("equals separator", "key = value\n", RiffleError.MISSING_COLON, 1),
                Arguments.of(
                        "equals separator after quoted key", "\"key name\" = value\n", RiffleError.MISSING_COLON, 1),
                Arguments.of("bare value starting with closer", "x : }\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("bare value starting with marker", "x : @\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("control character", "x : a\u0001b\n", RiffleError.INVALID_CHARACTER, 1),
                Arguments.of("tab in bare string", "x : a\tb\n", RiffleError.INVALID_CHARACTER, 1),
                Arguments.of("lone carriage return", "x : a\r y : b\n", RiffleError.INVALID_LINE_ENDING, 1),
                Arguments.of("carriage return at end of input", "x : a\r", RiffleError.INVALID_LINE_ENDING, 1),
                Arguments.of("bom in the middle", "x : a\n\uFEFFy : b\n", RiffleError.INVALID_CHARACTER, 2),
                Arguments.of("unpaired surrogate", "x : a\uD83Db\n", RiffleError.INVALID_UTF8, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void eachRejectedDocumentReportsItsCategory(String description, String document, RiffleError category, int line) {
        assertThatThrownBy(() -> Riffle.parse(document)).isInstanceOfSatisfying(RiffleParseException.class, e -> {
            assertThat(e.category()).as(description).isEqualTo(category);
            assertThat(e.location().line()).as(description).isEqualTo(line);
        });
    }

    @Test
    void inlineCollectionDiagnosticsSuggestQuotingFirst() {
        assertThatThrownBy(() -> Riffle.parse("chatgpt-oauth : { key : Test value }\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.UNSUPPORTED_INLINE_COLLECTION);
                    assertThat(e.reason())
                            .startsWith("a value starting with '{' must be quoted")
                            .contains("to write a map")
                            .contains("entries")
                            .contains("on its own line");
                    assertThat(e.location().column()).isEqualTo(19);
                });
        assertThatThrownBy(() -> Riffle.parse("re : [a-z]+\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.reason())
                            .startsWith("a value starting with '[' must be quoted")
                            .contains("to write an array, put '[' at the end of the line")
                            .contains("elements");
                    assertThat(e.getMessage()).doesNotContain("a-z");
                });
        assertThatThrownBy(() -> Riffle.parse("x : []y\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.reason()).startsWith("a value starting with '[' must be quoted"));
        assertThatThrownBy(() -> Riffle.parse("x : { }\n")).isInstanceOfSatisfying(RiffleParseException.class, e -> {
            assertThat(e.getMessage()).contains("empty collection").contains("{}");
            assertThat(e.getMessage()).doesNotContain("nonempty");
        });
        assertThatThrownBy(() -> Riffle.parse("x : [ ]\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.getMessage())
                                .contains("empty collection")
                                .contains("[]"));
    }

    @Test
    void aReservedFirstCharacterTellsTheAuthorToQuote() {
        assertThatThrownBy(() -> Riffle.parse("pkg : @scope/name\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.INVALID_VALUE);
                    assertThat(e.reason()).isEqualTo("a value starting with '@' must be quoted");
                    assertThat(e.getMessage()).doesNotContain("scope");
                });
    }

    @Test
    void errorsCarryAColumnAndAMessage() {
        assertThatThrownBy(() -> Riffle.parse("a : {\n    port : @80\n}\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.location().line()).isEqualTo(2);
                    assertThat(e.location().column()).isEqualTo(12);
                    assertThat(e.getMessage()).startsWith("line 2, column 12: ");
                });
    }

    @Test
    void nestingBeyondAConfiguredCapIsReportedAtTheOpener() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 129; i++) {
            document.append("k").append(i).append(" : {\n");
        }
        document.append("}\n".repeat(129));
        assertThatThrownBy(() -> Riffle.parse(document.toString(), RiffleOptions.maxDepth(128)))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.RESOURCE_LIMIT_EXCEEDED);
                    assertThat(e.location().line()).isEqualTo(129);
                });
    }

    @Test
    void theConfiguredCapItselfIsAccepted() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            document.append("k").append(i).append(" : {\n");
        }
        document.append("}\n".repeat(128));
        assertThat(Riffle.parse(document.toString(), RiffleOptions.maxDepth(128))
                        .entries())
                .hasSize(1);
        assertThat(Riffle.parse(document.toString()).entries()).hasSize(1);
    }

    @Test
    void parseExceptionsAreRiffleExceptions() {
        assertThatThrownBy(() -> Riffle.parse("bad line\n")).isInstanceOf(RiffleException.class);
    }
}
