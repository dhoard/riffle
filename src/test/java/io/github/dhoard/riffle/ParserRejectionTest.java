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
                Arguments.of("missing payload", "k = @int\n", RiffleError.MISSING_VALUE, 1),
                Arguments.of("leading zero", "k = @int 01\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("signed int", "k = @int +5\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("underscored int", "k = @int 1_000\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("int overflow", "k = @int 2147483648\n", RiffleError.NUMBER_OUT_OF_RANGE, 1),
                Arguments.of("long overflow", "k = @long 9223372036854775808\n", RiffleError.NUMBER_OUT_OF_RANGE, 1),
                Arguments.of("bad boolean", "k = @boolean yes\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("null payload", "k = @null null\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("unknown type", "k = @float 1.0\n", RiffleError.UNKNOWN_TYPE, 1),
                Arguments.of("unknown bare type", "k = @home\n", RiffleError.UNKNOWN_TYPE, 1),
                Arguments.of("quoted int", "k = @int \"1\"\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("trailing tokens after int", "k = @int 1 2\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("bad decimal", "k = @decimal NaN\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("decimal without leading digit", "k = @decimal .5\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("string without payload", "k = @string\n", RiffleError.MISSING_VALUE, 1),
                Arguments.of("map without key marker", "k = @map\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("inline map", "x = { a = b }\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("inline array", "x = [ one ]\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("inline typed array", "x = @int [ 1 ]\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("empty map with spaces", "x = { }\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of("empty array with spaces", "x = [ ]\n", RiffleError.UNSUPPORTED_INLINE_COLLECTION, 1),
                Arguments.of(
                        "empty typed array with spaces",
                        "x = @int [ ]\n",
                        RiffleError.UNSUPPORTED_INLINE_COLLECTION,
                        1),
                Arguments.of("trailing tokens after empty map", "x = {} y = 2\n", RiffleError.TRAILING_TOKENS, 1),
                Arguments.of("trailing tokens after quoted string", "x = \"a\" b\n", RiffleError.TRAILING_TOKENS, 1),
                Arguments.of("mismatched closer", "x = [\n  @int 1\n}\n", RiffleError.MISMATCHED_CLOSER, 3),
                Arguments.of(
                        "repeated annotation", "x = @int [\n  @int 1\n]\n", RiffleError.INVALID_ARRAY_ELEMENT_TYPE, 2),
                Arguments.of(
                        "wrong element type", "x = @int [\n  text\n]\n", RiffleError.INVALID_ARRAY_ELEMENT_TYPE, 2),
                Arguments.of(
                        "quoted numeric element",
                        "x = @int [\n  \"1\"\n]\n",
                        RiffleError.INVALID_ARRAY_ELEMENT_TYPE,
                        2),
                Arguments.of(
                        "string element with marker",
                        "x = @string [\n  @int 1\n]\n",
                        RiffleError.INVALID_ARRAY_ELEMENT_TYPE,
                        2),
                Arguments.of(
                        "map element expected", "x = @map [\n  hello\n]\n", RiffleError.INVALID_ARRAY_ELEMENT_TYPE, 2),
                Arguments.of(
                        "null element expected",
                        "x = @null [\n  hello\n]\n",
                        RiffleError.INVALID_ARRAY_ELEMENT_TYPE,
                        2),
                Arguments.of(
                        "array element expected",
                        "x = @array [\n  hello\n]\n",
                        RiffleError.INVALID_ARRAY_ELEMENT_TYPE,
                        2),
                Arguments.of("duplicate key", "x = a\nx = b\n", RiffleError.DUPLICATE_KEY, 2),
                Arguments.of("duplicate quoted key", "\"a b\" = 1\na b = 2\n", RiffleError.INVALID_KEY, 2),
                Arguments.of("typed key", "@int 1 = a\n", RiffleError.INVALID_KEY, 1),
                Arguments.of("unterminated string", "x = \"abc\n", RiffleError.UNTERMINATED_STRING, 1),
                Arguments.of("invalid escape", "x = \"bad\\q\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("dangling escape before boundary", "x = \"abc\\\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("trailing tokens after boundary", "x = \"a\" junk\n", RiffleError.TRAILING_TOKENS, 1),
                Arguments.of("short unicode escape", "x = \"\\u12\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("isolated surrogate", "x = \"\\uD83D\"\n", RiffleError.INVALID_ESCAPE, 1),
                Arguments.of("unclosed map", "x = {\n y = z\n", RiffleError.UNCLOSED_COLLECTION, 1),
                Arguments.of("unclosed array", "x = [\n y\n", RiffleError.UNCLOSED_COLLECTION, 1),
                Arguments.of("stray closer", "}\n", RiffleError.UNEXPECTED_CLOSER, 1),
                Arguments.of("root array", "[\n  a\n]\n", RiffleError.MISSING_EQUALS, 1),
                Arguments.of("missing equals", "x\n", RiffleError.MISSING_EQUALS, 1),
                Arguments.of("bare value starting with closer", "x = }\n", RiffleError.INVALID_VALUE, 1),
                Arguments.of("bare value starting with marker", "x = @\n", RiffleError.INVALID_TYPED_VALUE, 1),
                Arguments.of("control character", "x = a\u0001b\n", RiffleError.INVALID_CHARACTER, 1),
                Arguments.of("tab in bare string", "x = a\tb\n", RiffleError.INVALID_CHARACTER, 1),
                Arguments.of("lone carriage return", "x = a\r y = b\n", RiffleError.INVALID_LINE_ENDING, 1),
                Arguments.of("carriage return at end of input", "x = a\r", RiffleError.INVALID_LINE_ENDING, 1),
                Arguments.of("bom in the middle", "x = a\n\uFEFFy = b\n", RiffleError.INVALID_CHARACTER, 2),
                Arguments.of("unpaired surrogate", "x = a\uD83Db\n", RiffleError.INVALID_UTF8, 1));
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
    void inlineCollectionDiagnosticsNameTheCorrectShape() {
        assertThatThrownBy(() -> Riffle.parse("chatgpt-oauth = { key = Test value }\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.UNSUPPORTED_INLINE_COLLECTION);
                    assertThat(e.getMessage())
                            .contains("separate lines")
                            .contains("entries")
                            .contains("on its own line");
                    assertThat(e.location().column()).isEqualTo(19);
                });
        assertThatThrownBy(() -> Riffle.parse("x = [ one ]\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.getMessage()).contains("elements"));
        assertThatThrownBy(() -> Riffle.parse("x = @int [ 1 ]\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.getMessage()).contains("elements"));
        assertThatThrownBy(() -> Riffle.parse("x = { }\n")).isInstanceOfSatisfying(RiffleParseException.class, e -> {
            assertThat(e.getMessage()).contains("empty collection").contains("{}");
            assertThat(e.getMessage()).doesNotContain("nonempty");
        });
        assertThatThrownBy(() -> Riffle.parse("x = [ ]\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.getMessage())
                                .contains("empty collection")
                                .contains("[]"));
    }

    @Test
    void errorsCarryAColumnAndAMessage() {
        assertThatThrownBy(() -> Riffle.parse("a = {\n    port = @int 80a\n}\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.location().line()).isEqualTo(2);
                    assertThat(e.location().column()).isEqualTo(17);
                    assertThat(e.getMessage()).startsWith("line 2, column 17: ");
                });
    }

    @Test
    void messagesDoNotIncludeScalarValues() {
        assertThatThrownBy(() -> Riffle.parse("x = @int 80a\n"))
                .isInstanceOf(RiffleParseException.class)
                .hasMessageNotContaining("80a");
        assertThatThrownBy(() -> Riffle.parse("x = @boolean yes\n"))
                .isInstanceOf(RiffleParseException.class)
                .hasMessageNotContaining("yes");
    }

    @Test
    void nestingBeyondTheLimitIsReportedAtTheOpener() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 129; i++) {
            document.append("k").append(i).append(" = {\n");
        }
        document.append("}\n".repeat(129));
        assertThatThrownBy(() -> Riffle.parse(document.toString()))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.RESOURCE_LIMIT_EXCEEDED);
                    assertThat(e.location().line()).isEqualTo(129);
                });
    }

    @Test
    void theLimitItselfIsAccepted() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            document.append("k").append(i).append(" = {\n");
        }
        document.append("}\n".repeat(128));
        assertThat(Riffle.parse(document.toString()).entries()).hasSize(1);
    }

    @Test
    void parseExceptionsAreRiffleExceptions() {
        assertThatThrownBy(() -> Riffle.parse("bad line\n")).isInstanceOf(RiffleException.class);
    }
}
