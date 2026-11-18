package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** {@code \\uXXXX} escapes require four ASCII hexadecimal digits and correct surrogate pairs. */
class UnicodeEscapeTest {

    private static String valueOf(String document) {
        return Riffle.parse("k : " + document + "\n").get("k").asString();
    }

    static Stream<Arguments> asciiEscapes() {
        return Stream.of(
                Arguments.of("\"\\u0041\"", "A"),
                Arguments.of("\"\\u00e9\"", "é"),
                Arguments.of("\"\\u00E9\"", "é"),
                Arguments.of("\"\\u0000\"", "\u0000"),
                Arguments.of("\"\\u007F\"", "\u007F"),
                Arguments.of("\"\\uFFFD\"", "\uFFFD"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("asciiEscapes")
    void asciiHexadecimalDigitsAreAccepted(String document, String expected) {
        assertThat(valueOf(document)).isEqualTo(expected);
    }

    static Stream<Arguments> surrogatePairs() {
        return Stream.of(
                Arguments.of("\"\\uD83D\\uDCA1\"", "💡"),
                Arguments.of("\"a\\uD83D\\uDCA1b\"", "a💡b"),
                Arguments.of("\"\\uD83D\\uDCA1\\uD83D\\uDCA1\"", "💡💡"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("surrogatePairs")
    void surrogatePairsDecodeToOneCodePoint(String document, String expected) {
        assertThat(valueOf(document)).isEqualTo(expected);
    }

    static Stream<Arguments> nonAsciiHexDigits() {
        return Stream.of(
                // Arabic-Indic digits U+0660..U+0669: a non-ASCII spelling of 0041.
                Arguments.of("\"\\u\u0660\u0660\u0664\u0661\""),
                // Fullwidth digits U+FF10..U+FF19: a non-ASCII spelling of FF10.
                Arguments.of("\"\\u\uFF10\uFF10\uFF14\uFF11\""),
                // Devanagari digits U+0966..U+096F.
                Arguments.of("\"\\u\u0966\u0966\u096A\u0967\""),
                // Mixed ASCII and non-ASCII digits.
                Arguments.of("\"\\u004\u0661\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonAsciiHexDigits")
    void nonAsciiHexadecimalDigitsAreRejected(String document) {
        assertThatThrownBy(() -> Riffle.parse("k : " + document + "\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.INVALID_ESCAPE));
    }

    static Stream<Arguments> malformedEscapes() {
        return Stream.of(
                Arguments.of("\"\\u\""),
                Arguments.of("\"\\u0\""),
                Arguments.of("\"\\u00\""),
                Arguments.of("\"\\u004\""),
                Arguments.of("\"\\uD83D\""),
                Arguments.of("\"\\uDCA1\""),
                Arguments.of("\"\\uD83Dx\""),
                Arguments.of("\"\\uD83D\\uD83D\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedEscapes")
    void truncatedAndIsolatedSurrogateEscapesAreRejected(String document) {
        assertThatThrownBy(() -> Riffle.parse("k : " + document + "\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.INVALID_ESCAPE));
    }

    @Test
    void aLoneLowSurrogateInTheSourceIsRejected() {
        assertThatThrownBy(() -> Riffle.parse("k : \"\uDCA1\"\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.INVALID_UTF8));
    }

    @Test
    void escapedSupplementaryCharactersAreWrittenLiterallyAndRoundTrip() {
        RiffleObject value = RiffleObject.of(java.util.Map.of("k", RiffleString.of("💡")));
        assertThat(Riffle.write(value)).isEqualTo("k : 💡\n");
        assertThat(Riffle.parse(Riffle.write(value))).isEqualTo(value);
    }
}
