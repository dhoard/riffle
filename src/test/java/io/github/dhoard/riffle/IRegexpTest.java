package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The I-Regexp profile of SCHEMA_SPEC.md §6: the RFC 9485 grammar, a whole-string match over code
 * points, and no backtracking.
 */
class IRegexpTest {

    @ParameterizedTest(name = "{0} against [{1}] is {2}")
    @MethodSource("matches")
    void wholeStringMatching(String pattern, String text, boolean expected) {
        assertThat(IRegexp.compile(pattern).matches(text))
                .describedAs("%s against [%s]", pattern, text)
                .isEqualTo(expected);
    }

    static Stream<Arguments> matches() {
        List<Arguments> rows = new ArrayList<>();
        // Alternation, groups and concatenation.
        add(rows, "a|b", "a", true);
        add(rows, "a|b", "b", true);
        add(rows, "a|b", "c", false);
        add(rows, "a|", "", true);
        add(rows, "a|", "b", false);
        add(rows, "|a", "", true);
        add(rows, "(a|b)c", "bc", true);
        add(rows, "(a|b)c", "ac", true);
        add(rows, "(a|b)c", "cc", false);
        add(rows, "((a))", "a", true);
        add(rows, "(a(b))c", "abc", true);
        add(rows, "(a|b)*abb", "abb", true);
        add(rows, "(a|b)*abb", "babb", true);
        add(rows, "https?://[a-z]+:[0-9]+", "https://x:8080", true);
        add(rows, "https?://[a-z]+:[0-9]+", "https://x:", false);
        // The whole string, never a substring.
        add(rows, "a", "a", true);
        add(rows, "a", "ab", false);
        add(rows, "a", "ba", false);
        add(rows, "abc", "xabcx", false);
        add(rows, "^a$", "a", true);
        add(rows, "^a$", "ab", false);
        add(rows, "a$", "a", true);
        add(rows, "a$", "ba", false);
        add(rows, "a$b", "a$b", false);
        add(rows, "", "", true);
        add(rows, "", "a", false);
        // Quantifiers.
        add(rows, "(ab)+", "abab", true);
        add(rows, "(ab)+", "aba", false);
        add(rows, "a?", "", true);
        add(rows, "a?", "a", true);
        add(rows, "a?", "aa", false);
        add(rows, "a*", "", true);
        add(rows, "a*", "aaa", true);
        add(rows, "a*", "b", false);
        add(rows, "a+", "", false);
        add(rows, "a+", "aaa", true);
        add(rows, "a{2}", "aa", true);
        add(rows, "a{2}", "aaa", false);
        add(rows, "a{2,3}", "a", false);
        add(rows, "a{2,3}", "aaa", true);
        add(rows, "a{2,3}", "aaaa", false);
        add(rows, "a{2,}", "aaaa", true);
        add(rows, "a{0}", "", true);
        add(rows, "a{0}", "a", false);
        add(rows, "a{0,0}b", "b", true);
        add(rows, "(ab){0,2}", "", true);
        add(rows, "(ab){0,2}", "ababab", false);
        // Character classes, ranges and negation.
        add(rows, "[a-z]+", "abc", true);
        add(rows, "[a-z]+", "abC", false);
        add(rows, "[^a-z]", "A", true);
        add(rows, "[^a-z]", "a", false);
        add(rows, "[-a]", "-", true);
        add(rows, "[-a]", "a", true);
        add(rows, "[-a]", "b", false);
        add(rows, "[a-]", "-", true);
        add(rows, "[!-,]", ",", true);
        add(rows, "[!-,]", "!", true);
        add(rows, "[!-,]", "-", false);
        add(rows, "[!-,]", ".", false);
        add(rows, "[a-]", "a", true);
        add(rows, "[\\]]", "]", true);
        add(rows, "[\\-]", "-", true);
        add(rows, "[A-Z_][A-Z0-9_]*", "STAGE_2", true);
        add(rows, "[A-Z_][A-Z0-9_]*", "stage", false);
        add(rows, "[A-Z_][A-Z0-9_]*", "_A1", true);
        // The single-character wildcard excludes LF and CR and covers everything else.
        add(rows, "a.b", "axb", true);
        add(rows, "a.b", "a.b", true);
        add(rows, "a.*b", "aXYb", true);
        add(rows, "a.*b", "aX\nYb", false);
        add(rows, "a.*b", "aX\rYb", false);
        add(rows, "a\\.b", "a.b", true);
        add(rows, "a\\.b", "axb", false);
        // Single-character escapes of the RFC 9485 list.
        add(rows, "\\n", "\n", true);
        add(rows, "\\n", "n", false);
        add(rows, "\\r", "\r", true);
        add(rows, "\\t", "\t", true);
        add(rows, "\\.", ".", true);
        add(rows, "\\.", "x", false);
        add(rows, "\\\\", "\\", true);
        add(rows, "\\|", "|", true);
        add(rows, "\\{a\\}", "{a}", true);
        add(rows, "\\+x", "+x", true);
        add(rows, "\\^", "^", true);
        add(rows, "\\$", "$", true);
        add(rows, "$", "$", false);
        add(rows, "[$]", "$", true);
        add(rows, "^a", "a", true);
        add(rows, "^a", "^a", false);
        // Unicode categories, the only property escapes of the profile.
        add(rows, "\\p{Nd}+", "123", true);
        add(rows, "\\p{Nd}+", "١٢٣", true);
        add(rows, "\\p{Nd}+", "ab", false);
        add(rows, "\\p{L}+", "a", true);
        add(rows, "\\p{L}+", "é", true);
        add(rows, "\\p{L}+", "1", false);
        add(rows, "\\p{Lu}", "A", true);
        add(rows, "\\p{Lu}", "a", false);
        add(rows, "\\P{Lu}", "a", true);
        add(rows, "\\p{Zs}", " ", true);
        add(rows, "\\p{Z}", " ", true);
        add(rows, "\\p{Z}", "\n", false);
        add(rows, "\\p{P}+", ".,!", true);
        add(rows, "[\\p{Lu}\\p{Nd}]+", "A1", true);
        add(rows, "[\\p{Lu}\\p{Nd}]+", "a1", false);
        add(rows, "[^\\p{L}]+", "12", true);
        add(rows, "[^\\p{L}]+", "ab", false);
        // Category escapes in one class are alternatives, not one merged mask.
        add(rows, "[\\P{L}\\P{N}]", "a", true);
        add(rows, "[\\P{L}\\P{N}]", "1", true);
        add(rows, "[\\P{L}\\P{N}]", "-", true);
        add(rows, "[\\P{L}\\P{N}]+", "a1-", true);
        add(rows, "[^\\P{L}\\P{N}]", "a", false);
        add(rows, "[^\\P{L}\\P{N}]", "1", false);
        add(rows, "[^\\P{L}\\P{N}]", "-", false);
        add(rows, "[\\P{L}\\p{N}]", "a", false);
        add(rows, "[\\P{L}\\p{N}]", "1", true);
        add(rows, "[\\P{L}\\p{N}]", "-", true);
        add(rows, "\\p{Nd}", "\u0660", true);
        // Supplementary characters are one value each, not one encoding unit each.
        add(rows, ".{1}", "💦", true);
        add(rows, ".{2}", "💦", false);
        add(rows, ".{2}", "💦💦", true);
        add(rows, "\\p{Lu}", "\uD801\uDC00", true);
        add(rows, "\\p{L}", "\uD801\uDC00", true);
        add(rows, "..", "\uD801\uDC00a", true);
        // The canonical examples of the specification.
        add(rows, "https?://.+", "https://example.org/gateway", true);
        add(rows, "https?://.+", "example.org", false);
        return rows.stream();
    }

    private static void add(List<Arguments> rows, String pattern, String text, boolean expected) {
        rows.add(Arguments.of(pattern, text, expected));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "(",
                ")",
                "a)",
                "a(",
                "[",
                "[]",
                "[^]",
                "[a]]",
                "a{",
                "a{}",
                "a{x}",
                "a{2,1}",
                "a{,",
                "a}",
                "**",
                "*",
                "+",
                "?",
                "{2}",
                "[a-",
                "[a--b]",
                "[a-b-c]",
                "\\",
                "\\d",
                "\\D",
                "\\w",
                "\\W",
                "\\s",
                "\\S",
                "\\f",
                "\\v",
                "\\0",
                "\\x41",
                "\\A",
                "\\z",
                "\\b",
                "\\Q",
                "\\E",
                "\\x",
                "\\'",
                "\\p",
                "\\pL",
                "\\p{",
                "\\p{L",
                "\\p{Foo}",
                "\\p{IsL}",
                "\\p{IsLatin}",
                "\\p{Script=Latin}",
                "\\P{Zzz}",
                "[a\\d]",
                "[\\d]",
                "a??",
                "a*?",
                "a+?",
                "(?:a)",
                "(?i)a",
                "(?<n>a)",
                "[^]a]",
                "\\\\p{L}"
            })
    void patternsOutsideTheProfileAreRejected(String pattern) {
        assertThatThrownBy(() -> IRegexp.compile(pattern))
                .as("reject [%s]", pattern)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a",
                "",
                "()",
                "(|)",
                "a|",
                "|a",
                "a|b|c",
                "(-)",
                "[-]",
                "[a-]",
                "[^a]",
                "[\\]]",
                "[\\-]",
                "\\|",
                "\\{",
                "\\}",
                "\\^",
                "\\.",
                "\\+",
                "\\?",
                "\\(",
                "\\)",
                "\\[",
                "\\]",
                "\\\\",
                "\\n\\r\\t",
                "\\p{L}",
                "\\P{C}",
                "[\\p{Lu}]",
                "[^\\p{L}]",
                "[\\p{L}\\p{Nd}]",
                "[\\P{L}\\P{N}]",
                "[^\\P{L}\\P{N}]",
                "[^\\p{Nd}]",
                "((a))",
                "(a(b))c",
                "a{0,}",
                "a{1000}",
                "[a-z]+",
                "[^a-z0-9]",
                "[!-,]",
                "x{0}y",
                "(a+)+b",
                "[A-Z_][A-Z0-9_]*",
                "https?://.+",
                "[a-z]+(, ?[a-z]+)*"
            })
    void patternsInsideTheProfileCompile(String pattern) {
        assertThat(IRegexp.compile(pattern)).isNotNull();
    }

    @Test
    void aQuantifierNeedsSomethingToRepeat() {
        assertThatThrownBy(() -> IRegexp.compile("{2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantifier");
    }

    @Test
    void aRangeQuantifierAboveTheDocumentedBoundIsRejected() {
        assertThat(IRegexp.compile("a{" + IRegexp.MAX_REPEAT + "}").matches("a".repeat(IRegexp.MAX_REPEAT)))
                .isTrue();
        assertThatThrownBy(() -> IRegexp.compile("a{" + (IRegexp.MAX_REPEAT + 1) + "}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
        assertThatThrownBy(() -> IRegexp.compile("a{9999999999}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void repeatedLiteralsShareOnePredicate() {
        assertThat(IRegexp.compile("a{1000}").predicateCount()).isEqualTo(1);
        assertThat(IRegexp.compile("[ab]{1000}").predicateCount()).isEqualTo(1);
        assertThat(IRegexp.compile("a{1000}b{1000}").predicateCount()).isEqualTo(2);
    }

    @Test
    void anExpandedPatternBeyondTheTotalStateCapIsRejected() {
        String pattern = "((a{100}){100}){100}";
        assertThatThrownBy(() -> IRegexp.compile(pattern))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("states");

        String schema = """
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        x : {
                            type : string
                            pattern : "((a{100}){100}){100}"
                        }
                    }
                }
                """;
        assertThatThrownBy(() -> RiffleSchema.parse(schema))
                .isInstanceOf(RiffleSchemaException.class)
                .satisfies(failure -> assertThat(((RiffleSchemaException) failure).problems())
                        .singleElement()
                        .extracting(RiffleProblem::category)
                        .isEqualTo(RiffleValidationError.INVALID_PATTERN));
    }

    @Test
    void groupNestingBeyondTheDocumentedBoundIsRejected() {
        String atLimit = "(".repeat(IRegexp.MAX_NESTING) + "a" + ")".repeat(IRegexp.MAX_NESTING);
        String tooDeep = "(".repeat(IRegexp.MAX_NESTING + 1) + "a" + ")".repeat(IRegexp.MAX_NESTING + 1);
        assertThat(IRegexp.compile(atLimit).matches("a")).isTrue();
        assertThatThrownBy(() -> IRegexp.compile(tooDeep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nest");

        String schema = "riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n        x : {\n"
                + "            type : string\n            pattern : \"" + tooDeep + "\"\n"
                + "        }\n    }\n}\n";
        assertThatThrownBy(() -> RiffleSchema.parse(schema))
                .isInstanceOf(RiffleSchemaException.class)
                .satisfies(failure -> assertThat(((RiffleSchemaException) failure).problems())
                        .singleElement()
                        .extracting(RiffleProblem::category)
                        .isEqualTo(RiffleValidationError.INVALID_PATTERN));
    }

    @Test
    void aPathologicalPatternStaysLinear() {
        String text = "a".repeat(100_000);
        IRegexp pathological = IRegexp.compile("(a+)+b");
        IRegexp nested = IRegexp.compile("((a|b)*)*c");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(pathological.matches(text)).isFalse();
            assertThat(pathological.matches(text + "b")).isTrue();
            assertThat(nested.matches(text)).isFalse();
            assertThat(nested.matches(text + "c")).isTrue();
        });
    }

    @Test
    void aLongMatchStaysCheap() {
        IRegexp pattern = IRegexp.compile("[A-Z_][A-Z0-9_]*");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            text.append("NAME_").append(i).append('_').append(i);
        }
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(pattern.matches(text.toString())).isTrue();
            assertThat(pattern.matches(text + " ")).isFalse();
        });
    }

    @Test
    void anEmptyPatternMatchesOnlyTheEmptyText() {
        assertThat(IRegexp.compile("").matches("")).isTrue();
        assertThat(IRegexp.compile("").matches("a")).isFalse();
    }

    @Test
    void aCompiledPatternIsReusableAcrossThreads() throws Exception {
        IRegexp pattern = IRegexp.compile("[a-z]+");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(pool.submit(() -> pattern.matches("abcdefghij")));
            }
            for (Future<Boolean> future : futures) {
                assertThat(future.get()).isTrue();
            }
        } finally {
            pool.shutdown();
        }
    }

    @ParameterizedTest(name = "category {0} compiles")
    @MethodSource("categories")
    void everyDocumentedCategoryCompiles(String name) {
        assertThat(IRegexp.compile("\\p{" + name + "}")).isNotNull();
        assertThat(IRegexp.compile("\\P{" + name + "}")).isNotNull();
        assertThat(IRegexp.compile("[\\p{" + name + "}]")).isNotNull();
    }

    static Stream<Arguments> categories() {
        return Stream.of(
                        "L", "Ll", "Lm", "Lo", "Lt", "Lu", "M", "Mc", "Me", "Mn", "N", "Nd", "Nl", "No", "P", "Pc",
                        "Pd", "Pe", "Pf", "Pi", "Po", "Ps", "S", "Sc", "Sk", "Sm", "So", "Z", "Zl", "Zp", "Zs", "C",
                        "Cc", "Cf", "Cn", "Co")
                .map(Arguments::of);
    }
}
