package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Acceptance and round-trip tests for EOL-delimited quoted values (LANGUAGE_SPEC.md §5.2). */
class EOLQuotedStringsTest {

    private static String value(String source) {
        return Riffle.parse("k : " + source + "\n").get("k").asString();
    }

    private static RiffleObject object(String text) {
        return RiffleObject.of(Map.of("k", RiffleString.of(text)));
    }

    private static void rejects(String source, RiffleError expected) {
        assertThatThrownBy(() -> Riffle.parse("k : " + source + "\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(expected);
                    assertThat(e.location().line()).isEqualTo(1);
                });
    }

    @Test
    void literalAndEscapedInteriorQuotesDecodeIdentically() {
        String literal = value("\"He said \"hello\"\"");
        String escaped = value("\"He said \\\"hello\\\"\"");
        assertThat(literal).isEqualTo("He said \"hello\"");
        assertThat(escaped).isEqualTo(literal);
    }

    @Test
    void boundaryAndWhitespaceCasesFollowTheQuotedValueGrammar() {
        assertThat(value("Hello world")).isEqualTo("Hello world");
        assertThat(value("He said \"hello\"")).isEqualTo("He said \"hello\"");
        assertThat(value("\" Hello \"")).isEqualTo(" Hello ");
        assertThat(value("\"\"hello\"\"")).isEqualTo("\"hello\"");
        assertThat(value("\"hello\"\"")).isEqualTo("hello\"");
        assertThat(value("\"\"")).isEmpty();
        assertThat(value("\"\\\"\"")).isEqualTo("\"");
        assertThat(value("\"\"\"\"")).isEqualTo("\"\"");
        assertThat(value("\"a # b\"")).isEqualTo("a # b");
        assertThat(value("\"a // b\"")).isEqualTo("a // b");
        assertThat(value("He said \"hello\" # not a comment")).isEqualTo("He said \"hello\" # not a comment");
        assertThat(value("\"a \\n b\"")).isEqualTo("a \n b");
    }

    @Test
    void existingEscapesStillDecodeInsideTheInteriorSpan() {
        assertThat(value("\"abc\\\"\"")).isEqualTo("abc\"");
        assertThat(value("\"abc\"\"")).isEqualTo("abc\"");
        assertThat(value("\"a\\\\b\"")).isEqualTo("a\\b");
        assertThat(value("\"\\t\\b\\f\\r\"")).isEqualTo("\t\b\f\r");
        assertThat(value("\"\\u0041\\uD83D\\uDCA1\"")).isEqualTo("A💡");
        assertThat(value("\"a\\uFEFFb\"")).isEqualTo("a\uFEFFb");
        rejects("\"abc\\\"", RiffleError.INVALID_ESCAPE);
        rejects("\"bad\\q\"", RiffleError.INVALID_ESCAPE);
        rejects("\"\\u12\"", RiffleError.INVALID_ESCAPE);
        rejects("\"\\uD83D\"", RiffleError.INVALID_ESCAPE);
        rejects("\"abc", RiffleError.UNTERMINATED_STRING);
        rejects("\"", RiffleError.UNTERMINATED_STRING);
    }

    @Test
    void theLastQuoteOnTheLineEndsTheValue() {
        assertThat(value("\"a # b\"")).isEqualTo("a # b");
        assertThat(value("\"a\"#c\"")).isEqualTo("a\"#c");
        assertThat(value("\"a\" \"b\"")).isEqualTo("a\" \"b");
        assertThat(value("\"a\" # text\"")).isEqualTo("a\" # text");
    }

    @Test
    void textAfterTheClosingQuoteIsRejected() {
        rejects("\"a\" # text", RiffleError.TRAILING_TOKENS);
        rejects("\"a\" garbage", RiffleError.TRAILING_TOKENS);
    }

    @Test
    void arrayElementsUseTheSameValueRule() {
        RiffleObject root = Riffle.parse("""
                list : [
                    "one "two""
                    "a # b"
                ]
                """);
        assertThat(root.get("list").asArray().get(0).asString()).isEqualTo("one \"two\"");
        assertThat(root.get("list").asArray().get(1).asString()).isEqualTo("a # b");
    }

    @Test
    void quotedKeysKeepTheFirstUnescapedQuoteRule() {
        // The escaped quote is data, so the delimiter is the closing quote of `"a\"b"` and the key
        // text is `a"b`. A quote is outside the key alphabet, so the document fails as an invalid
        // key rather than as a missing colon or trailing text: that is how the delimiter choice
        // shows itself now that a key cannot be quoted into existence.
        assertThatThrownBy(() -> Riffle.parse("\"a\\\"b\" : \"He said \"hello\"\"\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.INVALID_KEY));

        assertThatThrownBy(() -> Riffle.parse("\"a\"b\" = v\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.MISSING_COLON));
    }

    @Test
    void theCanonicalWriterPrefersLiteralInteriorQuotes() {
        RiffleObject document = object("  He said \"hello\"  ");
        String written = Riffle.write(document);
        assertThat(written).isEqualTo("k : \"  He said \"hello\"  \"\n");
        assertThat(Riffle.parse(written)).isEqualTo(document);
        assertThat(Riffle.write(Riffle.parse(written))).isEqualTo(written);
        assertThat(Riffle.write(object("He said \"hello\""))).isEqualTo("k : He said \"hello\"\n");
    }

    @Test
    void interiorQuotesAndHashesRoundTripThroughTheCanonicalWriter() {
        for (String text : new String[] {"a\" # b", "a\" // b", "a\"\" # b", "a\"\t#b", "x # y"}) {
            RiffleObject document = object(text);
            String written = Riffle.write(document);
            assertThat(Riffle.parse(written)).as(written).isEqualTo(document);
            assertThat(Riffle.write(Riffle.parse(written))).as(written).isEqualTo(written);
        }
        assertThat(Riffle.write(object("a\" # b"))).isEqualTo("k : a\" # b\n");
        assertThat(Riffle.write(object("x # y"))).isEqualTo("k : x # y\n");
        assertThat(Riffle.write(object("#x"))).isEqualTo("k : \"#x\"\n");
    }

    @Test
    void boundedStringsRoundTripThroughTheCanonicalWriter() {
        char[] alphabet = {'"', '\\', '#', '/', ' ', '\t', '\n', 'a', '\uFEFF'};
        for (int length = 0; length <= 4; length++) {
            int combinations = (int) Math.pow(alphabet.length, length);
            for (int number = 0; number < combinations; number++) {
                StringBuilder text = new StringBuilder();
                int digits = number;
                for (int i = 0; i < length; i++) {
                    text.append(alphabet[digits % alphabet.length]);
                    digits /= alphabet.length;
                }
                RiffleObject document = object(text.toString());
                String written = Riffle.write(document);
                assertThat(Riffle.parse(written)).as("written: %s", written).isEqualTo(document);
                assertThat(Riffle.write(Riffle.parse(written)))
                        .as("written: %s", written)
                        .isEqualTo(written);
            }
        }
    }
}
