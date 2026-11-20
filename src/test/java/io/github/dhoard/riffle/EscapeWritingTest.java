package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/**
 * The escapes of LANGUAGE_SPEC.md §5.2 in both directions: what a writer must escape so the
 * document parses again, and what a parser accepts or rejects in a quoted string.
 */
class EscapeWritingTest {

    private static String writeValue(String text) {
        LinkedHashMap<String, RiffleValue> entries = new LinkedHashMap<>();
        entries.put("k", RiffleString.of(text));
        return Riffle.write(RiffleObject.of(entries));
    }

    private static String readValue(String document) {
        return Riffle.parse(document).get("k").asString();
    }

    /** The category the parser reports for a document that does not parse. */
    private static RiffleError rejection(String document) {
        RiffleParseException failure = catchThrowableOfType(RiffleParseException.class, () -> Riffle.parse(document));
        return failure.category();
    }

    @Test
    void theNamedControlEscapesSurviveAWriteAndParse() {
        for (String text : new String[] {"a\bb", "a\fb", "a\nb", "a\rb", "a\tb"}) {
            String written = writeValue(text);

            assertThat(written)
                    .as("written form of %s", text.replace('\n', 'N').replace('\r', 'R'))
                    .startsWith("k : \"");
            assertThat(readValue(written)).isEqualTo(text);
        }
    }

    @Test
    void anUnnamedControlCharacterIsWrittenAsAUnicodeEscape() {
        String written = writeValue("a\u0001b");

        assertThat(written).isEqualTo("k : \"a\\u0001b\"\n");
        assertThat(readValue(written)).isEqualTo("a\u0001b");
    }

    @Test
    void aBackslashAndAQuoteAreWrittenSoThatTheDocumentParsesAgain() {
        assertThat(readValue(writeValue("a\\b"))).isEqualTo("a\\b");
        assertThat(readValue(writeValue("a\"b"))).isEqualTo("a\"b");
        assertThat(readValue(writeValue("a\\\"b"))).isEqualTo("a\\\"b");
    }

    @Test
    void aSurrogatePairIsWrittenAndReadAsOneCharacter() {
        String text = "a\uD83D\uDE00b";

        assertThat(readValue(writeValue(text))).isEqualTo(text);
    }

    @Test
    void anUnpairedSurrogateIsRecognisedInEveryPosition() {
        assertThat(Syntax.hasUnpairedSurrogate("a\uD83D\uDE00b")).isFalse();
        assertThat(Syntax.hasUnpairedSurrogate("x\uD83D")).isTrue();
        assertThat(Syntax.hasUnpairedSurrogate("x\uD83Dy")).isTrue();
        assertThat(Syntax.hasUnpairedSurrogate("\uDE00x")).isTrue();
        assertThat(Syntax.hasUnpairedSurrogate("")).isFalse();
    }

    @Test
    void aQuotedKeyEndingWithABackslashIsAnUnterminatedEscape() {
        assertThat(rejection("\"key\\\n")).isEqualTo(RiffleError.INVALID_ESCAPE);
    }

    @Test
    void hexadecimalEscapesAcceptDigitsUpperAndLowerCase() {
        assertThat(readValue("k : \"\\u00FF\\u00ff\\u0041\"\n")).isEqualTo("\u00FF\u00ffA");
    }

    @Test
    void aSurrogatePairWrittenAsTwoEscapesBecomesOneCharacter() {
        assertThat(readValue("k : \"\\uD83D\\uDE00\"\n")).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void anIsolatedSurrogateEscapeIsRejected() {
        assertThat(rejection("k : \"\\uD83Dx\"\n")).isEqualTo(RiffleError.INVALID_ESCAPE);
        assertThat(rejection("k : \"\\uDE00\"\n")).isEqualTo(RiffleError.INVALID_ESCAPE);
    }

    @Test
    void anEscapeNeedsFourHexadecimalDigits() {
        assertThat(rejection("k : \"\\u00\"\n")).isEqualTo(RiffleError.INVALID_ESCAPE);
        assertThat(rejection("k : \"\\uZZZZ\"\n")).isEqualTo(RiffleError.INVALID_ESCAPE);
    }

    @Test
    void anUnpairedSurrogateIsRejectedWhereverItAppears() {
        assertThat(rejection("k : \"x\uD83D\"\n")).isEqualTo(RiffleError.INVALID_UTF8);
        assertThat(rejection("k : \"x\uD83Dy\"\n")).isEqualTo(RiffleError.INVALID_UTF8);
        assertThat(rejection("k : x\uD83D\n")).isEqualTo(RiffleError.INVALID_UTF8);
    }

    @Test
    void anOpenerIsOnlyAnEmptyCollectionWhenItsCloserEndsTheLine() {
        assertThatThrownBy(() -> Riffle.parse("k : [ ] x\n")).isInstanceOf(RiffleParseException.class);
        assertThatThrownBy(() -> Riffle.parse("k : { } y\n")).isInstanceOf(RiffleParseException.class);
    }
}
