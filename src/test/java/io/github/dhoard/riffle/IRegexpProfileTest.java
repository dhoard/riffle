package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The I-Regexp profile of SCHEMA_SPEC.md §14 at its boundaries: an escaped pattern character is
 * literal, a class range has to run upwards, and a code point that is not a pattern character is
 * named rather than accepted.
 */
class IRegexpProfileTest {

    @Test
    void everyMetacharacterCanBeEscapedAndMatchesItself() {
        for (String metacharacter : new String[] {"(", ")", "*", "+", "-", ".", "?", "[", "\\", "]", "^", "$", "|"}) {
            assertThat(IRegexp.compile("a\\" + metacharacter + "b").matches("a" + metacharacter + "b"))
                    .as("escaped %s", metacharacter)
                    .isTrue();
        }
    }

    @Test
    void aRangeInsideAClassRunsUpwards() {
        assertThat(IRegexp.compile("[b-d]").matches("c")).isTrue();
        assertThat(IRegexp.compile("[b-d]").matches("a")).isFalse();
        assertThatThrownBy(() -> IRegexp.compile("[d-b]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts up");
    }

    @Test
    void aRangeCannotEndWithACategoryEscape() {
        assertThatThrownBy(() -> IRegexp.compile("[a-\\p{L}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category escape");
    }

    @Test
    void aRangeQuantifierNeedsItsClosingBrace() {
        assertThatThrownBy(() -> IRegexp.compile("a{2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'}' is missing");
        assertThatThrownBy(() -> IRegexp.compile("a{2,"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'}' is missing");
    }

    @Test
    void aGroupNeedsItsClosingParenthesis() {
        assertThatThrownBy(() -> IRegexp.compile("(a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("')' is missing");
    }

    @Test
    void aCharacterThatIsNeitherALiteralNorAnOperatorIsRejectedByName() {
        assertThatThrownBy(() -> IRegexp.compile("+"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'+' is not a pattern character");
        // The profile is ASCII-punctuated, not ASCII-only: any other code point is a literal.
        String lineSeparator = "\u2028";
        assertThat(IRegexp.compile("a" + lineSeparator + "b").matches("a" + lineSeparator + "b"))
                .isTrue();
    }

    @Test
    void anUnpairedSurrogateIsNotAPatternCharacter() {
        assertThatThrownBy(() -> IRegexp.compile("a\uD83D"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a pattern character");
        assertThatThrownBy(() -> IRegexp.compile("[a\uDE00]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be escaped in a character class");
    }

    @Test
    void anAstralCharacterIsAPatternCharacterAndAClassCharacter() {
        // U+1F600 is one code point, written as a surrogate pair in a Java string.
        String astral = "\uD83D\uDE00";
        assertThat(IRegexp.compile("a" + astral).matches("a" + astral)).isTrue();
        assertThat(IRegexp.compile("[a" + astral + "]").matches(astral)).isTrue();
        assertThat(IRegexp.compile("[a\uD83D\uDE00]").matches("\uD83D\uDE00")).isTrue();
    }

    @Test
    void aClassThatWouldBeEmptyOrUnclosedIsRejected() {
        assertThatThrownBy(() -> IRegexp.compile("[")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IRegexp.compile("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IRegexp.compile("[^]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[^]");
    }

    @Test
    void thePatternEndsWithABackslash() {
        assertThatThrownBy(() -> IRegexp.compile("a\\"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ends with a backslash");
    }

    @Test
    void aClassOrGroupThatStopsShortIsRejected() {
        assertThatThrownBy(() -> IRegexp.compile("(a]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a pattern character");
        assertThatThrownBy(() -> IRegexp.compile("(a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("')' is missing");
        assertThatThrownBy(() -> IRegexp.compile("[^")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoClassesThatDifferOnlyInTheirRangesOrCategoriesAreDistinct() {
        assertThat(IRegexp.compile("[a][^a]").predicateCount()).isEqualTo(2);
        assertThat(IRegexp.compile("[\\p{L}a][\\p{Lu}a]").predicateCount()).isEqualTo(2);
    }

    @Test
    void aStartAnchorIsEvaluatedAtEveryPositionTheScanReaches() {
        assertThat(IRegexp.compile("a|^b").matches("a")).isTrue();
        assertThat(IRegexp.compile("a|^b").matches("b")).isTrue();
        assertThat(IRegexp.compile("a|^b").matches("ab")).isFalse();
    }
}
