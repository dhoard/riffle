package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Multiline raw blocks: every rule of LANGUAGE_SPEC.md §5.3 and their canonical form. */
class RawBlockTest {
    private static final String Q = "\"\"\"";

    private static String text(String document) {
        return Riffle.parse(document).get("k").asString();
    }

    private static String block(String... lines) {
        return "k : " + Q + "\n" + String.join("\n", lines) + "\n";
    }

    private static void rejects(String document, RiffleError category, int line) {
        assertThatThrownBy(() -> Riffle.parse(document)).isInstanceOfSatisfying(RiffleParseException.class, e -> {
            assertThat(e.category()).isEqualTo(category);
            assertThat(e.location().line()).isEqualTo(line);
        });
    }

    private static RiffleObject document(String value) {
        return RiffleObject.of(Map.of("k", RiffleString.of(value)));
    }

    @Test
    void theOpenerMayBeFollowedOnlyByWhitespace() {
        assertThat(text("k : " + Q + " \t\n    a\n    " + Q + "\n")).isEqualTo("a");
        assertThat(text("k:" + Q + "\n    a\n    " + Q)).isEqualTo("a");
        rejects("k : " + Q + " text\n", RiffleError.TRAILING_TOKENS, 1);
    }

    @Test
    void anyOtherFragmentStartingWithAQuoteIsASingleLineValue() {
        assertThat(text("k : " + Q + "abc" + Q + "\n")).isEqualTo("\"\"abc\"\"");
        assertThat(text("k : \"\"\"\"\n")).isEqualTo("\"\"");
        assertThat(text("k : \"\\\"\"\n")).isEqualTo("\"");
    }

    @Test
    void aRawBlockIsNeverAKey() {
        rejects(Q + " : value\n", RiffleError.MISSING_COLON, 1);
        rejects(Q + "\n    a\n" + Q + "\n", RiffleError.MISSING_COLON, 1);
    }

    @Test
    void arrayElementsMayBeRawBlocks() {
        RiffleArray array = Riffle.parse("k : [\n    " + Q + "\n    a\n    b\n    " + Q + "\n    c\n]\n")
                .getArray("k");
        assertThat(array.values()).extracting(RiffleValue::asString).containsExactly("a\nb", "c");
    }

    @Test
    void theFirstLineThatTrimsToTheDelimiterCloses() {
        RiffleObject root = Riffle.parse(block("  \ta", "  \t" + Q + " \t", "after : 1"));
        assertThat(root.getString("k")).isEqualTo("a");
        assertThat(root.getInt("after")).isEqualTo(1);
    }

    @Test
    void linesThatOnlyContainTheDelimiterWithOtherTextAreContent() {
        assertThat(text(block("    " + Q + "x", "    x" + Q, "    " + Q + " y", "    " + Q)))
                .isEqualTo(Q + "x\nx" + Q + "\n" + Q + " y");
    }

    @Test
    void contentIsRaw() {
        String content = String.join(
                "\n",
                "# c",
                "// d",
                "/#",
                "#/",
                "{",
                "[",
                "}",
                "]",
                "@int 1",
                "a : b",
                "\\n \\q \\u0000",
                "\"quoted\"");
        StringBuilder document = new StringBuilder("k : " + Q + "\n");
        for (String line : content.split("\n")) {
            document.append("  ").append(line).append('\n');
        }
        assertThat(text(document.append("  ").append(Q).append('\n').toString()))
                .isEqualTo(content);
    }

    @Test
    void theClosersIndentationIsRemovedFromEveryLine() {
        assertThat(text(block("    a", "      b", "    c", "    " + Q))).isEqualTo("a\n  b\nc");
        assertThat(text(block("a", "  b", Q))).isEqualTo("a\n  b");
        assertThat(text(block("\t\ta", "\t\t\tb", "\t\t" + Q))).isEqualTo("a\n\tb");
    }

    @Test
    void shortWhitespaceOnlyLinesBecomeEmpty() {
        assertThat(text(block("    a", "", "  ", "\t", "    b", "    " + Q))).isEqualTo("a\n\n\n\nb");
    }

    @Test
    void whitespaceOnlyLinesKeepWhatFollowsTheIndentation() {
        assertThat(text(block("    a", "    ", "      ", "    \t", "    " + Q))).isEqualTo("a\n\n  \n\t");
    }

    @ParameterizedTest
    @ValueSource(strings = {"  a", "\ta", "   \ta", "a", "\t\t\t\t\t"})
    void aLineWithoutTheIndentationIsRejectedAtThatLine(String line) {
        rejects(block("    ok", line, "    " + Q), RiffleError.INVALID_INDENTATION, 3);
    }

    @Test
    void tabsAndSpacesAreNotInterchangeable() {
        rejects(block("\ta", "    " + Q), RiffleError.INVALID_INDENTATION, 2);
        rejects(block("    a", "\t" + Q), RiffleError.INVALID_INDENTATION, 2);
        rejects(block(" \ta", "\t " + Q), RiffleError.INVALID_INDENTATION, 2);
    }

    @Test
    void trailingWhitespaceIsPreserved() {
        assertThat(text(block("    a  ", "    b\t", "    " + Q))).isEqualTo("a  \nb\t");
    }

    @Test
    void linesJoinWithLfAndTheLastLineHasNoNewline() {
        assertThat(text("k : " + Q + "\r\n    a\r\n\r\n    b\r\n    " + Q + "\r\n"))
                .isEqualTo("a\n\nb");
        assertThat(text(block("    a", "", "    " + Q))).isEqualTo("a\n");
        assertThat(text(block("", "    a", "    " + Q))).isEqualTo("\na");
    }

    @Test
    void anOpenerFollowedByTheCloserIsTheEmptyString() {
        assertThat(text("k : " + Q + "\n" + Q + "\n")).isEmpty();
        assertThat(text("k : " + Q + "\n    " + Q)).isEmpty();
    }

    @Test
    void tabsAreAllowedAndOtherControlCharactersAreNot() {
        assertThat(text(block("    a\tb", "    " + Q))).isEqualTo("a\tb");
        rejects(block("    a\u0001b", "    " + Q), RiffleError.INVALID_CHARACTER, 2);
        rejects(block("    a\u0000b", "    " + Q), RiffleError.INVALID_CHARACTER, 2);
        rejects(block("    a\u007Fb", "    " + Q), RiffleError.INVALID_CHARACTER, 2);
        rejects(block("    a\uFEFFb", "    " + Q), RiffleError.INVALID_CHARACTER, 2);
        rejects(block("    a\rb", "    " + Q), RiffleError.INVALID_LINE_ENDING, 2);
    }

    @Test
    void anUnterminatedBlockIsReportedAtTheOpener() {
        rejects("a : 1\nk : " + Q + "\n    text\n", RiffleError.UNTERMINATED_STRING, 2);
        rejects("k : " + Q, RiffleError.UNTERMINATED_STRING, 1);
        rejects("k : " + Q + "\nbad indentation\n    \u0001\n", RiffleError.UNTERMINATED_STRING, 1);
    }

    @Test
    void aRawBlockDoesNotCountTowardTheNestingLimit() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            document.append("k").append(i).append(" : {\n");
        }
        document.append("k : ").append(Q).append("\na\n").append(Q).append('\n');
        document.append("}\n".repeat(128));
        assertThat(Riffle.parse(document.toString()).entries()).hasSize(1);
    }

    @Test
    void anOpenerInsideABlockCommentIsCommentedOutText() {
        RiffleObject root = Riffle.parse("/#\nk : " + Q + "\n#/\nafter : value\n");
        assertThat(root.entries()).containsOnlyKeys("after");
        assertThat(Riffle.parse("/#\n" + Q + "\n/#\n" + Q + "\n#/\n#/\nafter : 1\n")
                        .entries())
                .containsOnlyKeys("after");
    }

    @Test
    void blockCommentDelimitersInsideARawBlockAreContent() {
        assertThat(text(block("    /#", "    #/", "    /# open", "    " + Q))).isEqualTo("/#\n#/\n/# open");
        assertThat(Riffle.parse(block("/#", Q) + "after : 1\n").entries()).containsOnlyKeys("k", "after");
    }

    @Test
    void theWriterUsesARawBlockForRepresentableMultilineStrings() {
        assertThat(Riffle.write(document("a\n\n  b\n")))
                .isEqualTo("k : " + Q + "\n    a\n\n      b\n\n    " + Q + "\n");
        assertThat(Riffle.write(document("   \nx"))).isEqualTo("k : " + Q + "\n       \n    x\n    " + Q + "\n");
    }

    @Test
    void theWriterIndentsContentOneLevelDeeperThanItsEntryOrElement() {
        RiffleObject value = RiffleObject.of(Map.of(
                "m",
                RiffleObject.of(Map.of("k", RiffleString.of("a\nb"))),
                "l",
                RiffleArray.of(List.of(RiffleString.of("c\nd")))));
        String written = Riffle.write(value);
        assertThat(written).contains("    k : " + Q + "\n        a\n        b\n        " + Q + "\n");
        assertThat(written).contains("    " + Q + "\n        c\n        d\n        " + Q + "\n");
        assertThat(Riffle.parse(written)).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a\r\nb",
                "a\nb\u0001",
                "a\n\u007F",
                "a\n\uFEFF",
                "a\n\"\"\"",
                "\"\"\"\nb",
                "a\n  \"\"\"\t\nb",
            })
    void unrepresentableMultilineStringsAreQuotedOnOneLine(String value) {
        String written = Riffle.write(document(value));
        assertThat(written.lines()).hasSize(1);
        assertThat(Riffle.parse(written)).isEqualTo(document(value));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\n",
                "\n\n",
                "a\n",
                "\na",
                "\n\na\n\n",
                "   \n\t\n  x  \n",
                "\ta\n\tb",
                "# c\n// d\n/#\n#/\n{\n[\n@int 1",
                "\"\"\"x\nx\"\"\"\n\"\"\" y",
                "\"\nb",
                "a\\n\nb\\",
            })
    void multilineStringsRoundTrip(String value) {
        String written = Riffle.write(document(value));
        assertThat(written).startsWith("k : " + Q + "\n");
        assertThat(Riffle.parse(written)).as(written).isEqualTo(document(value));
        assertThat(Riffle.write(Riffle.parse(written))).isEqualTo(written);
    }

    static IntStream seeds() {
        return IntStream.range(0, 200);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void shiftingAWholeDocumentByOneIndentNeverChangesTheValue(int seed) {
        RiffleObject value = new RiffleRandom(new Random(seed)).object();
        StringBuilder shifted = new StringBuilder();
        for (String line : Riffle.write(value).split("\n", -1)) {
            shifted.append(line.isEmpty() ? "" : "\t ").append(line).append('\n');
        }
        assertThat(Riffle.parse(shifted.toString())).as(shifted.toString()).isEqualTo(value);
    }
}
