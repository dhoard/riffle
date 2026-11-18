package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Grammar acceptance for Riffle 0.1.0. */
class ParserTest {

    private static RiffleObject map(String document) {
        return Riffle.parse(document);
    }

    private static RiffleValue value(String document) {
        return map(document).get("k");
    }

    @Test
    void emptyDocumentIsAnEmptyMap() {
        assertThat(Riffle.parse("")).isEqualTo(RiffleObject.of(Map.of()));
        assertThat(Riffle.parse("\n\n  \n# a comment\n")).isEqualTo(RiffleObject.of(Map.of()));
    }

    @Test
    void onlyHashStartsALineComment() {
        assertThat(value("k : a // b").asString()).isEqualTo("a // b");
        assertThat(value("k : a//b").asString()).isEqualTo("a//b");
        assertThat(value("k : // not a comment").asString()).isEqualTo("// not a comment");
        assertThatThrownBy(() -> Riffle.parse("// not a comment\nk : v\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.MISSING_COLON));
    }

    @Test
    void entriesUseColonSeparator() {
        RiffleObject root = map("a : 1\nb:2\n   c   :   3   \n");
        assertThat(root.entries()).containsOnlyKeys("a", "b", "c");
        assertThat(root.get("a").asString()).isEqualTo("1");
        assertThat(root.get("c").asString()).isEqualTo("3");
    }

    @Test
    void theKeyEndsAtTheFirstColon() {
        assertThat(value("k : a : b").asString()).isEqualTo("a : b");
        assertThat(value("k:a:123").asString()).isEqualTo("a:123");
        assertThat(value("k : a=b").asString()).isEqualTo("a=b");
    }

    @Test
    void commentsOccupyWholeLines() {
        RiffleObject root = map("""
                # leading
                  # indented
                k : value
                n : other
                """);
        assertThat(root.get("k").asString()).isEqualTo("value");
        assertThat(root.get("n").asString()).isEqualTo("other");
    }

    @Test
    void markersWithoutPrecedingWhitespaceAreText() {
        assertThat(value("k : https://example.com/a#frag").asString()).isEqualTo("https://example.com/a#frag");
        assertThat(value("k : path//segment").asString()).isEqualTo("path//segment");
        assertThat(value("k : hello#world").asString()).isEqualTo("hello#world");
        assertThat(value("k : a/b//c").asString()).isEqualTo("a/b//c");
    }

    @Test
    void aHashAfterAValueIsPartOfThatValue() {
        assertThat(value("k : hello #world").asString()).isEqualTo("hello #world");
        assertThat(value("k : hello  #  world").asString()).isEqualTo("hello  #  world");
        assertThat(value("k : # not a comment").asString()).isEqualTo("# not a comment");
        assertThat(value("k : a # b # c").asString()).isEqualTo("a # b # c");
    }

    @Test
    void markersInsideQuotesAreText() {
        assertThat(value("k : \"a # b\"").asString()).isEqualTo("a # b");
        assertThat(value("k : \"a // b\"").asString()).isEqualTo("a // b");
        assertThat(value("k : \"a \\\" # b\"").asString()).isEqualTo("a \" # b");
    }

    @Test
    void aQuoteInsideABareValueIsOrdinaryText() {
        assertThat(value("k : a\"b # c").asString()).isEqualTo("a\"b # c");
        assertThat(value("k : a\"b // c").asString()).isEqualTo("a\"b // c");
        assertThat(value("k : he said \"hi\" # c").asString()).isEqualTo("he said \"hi\" # c");
    }

    @Test
    void aCommentCannotFollowANonBareValue() {
        // §8.1: a comment occupies a whole line, so these lines are rejected rather than read
        // with a trailing comment.
        assertThatThrownBy(() -> Riffle.parse("b : \"x\" # note"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.TRAILING_TOKENS));
        assertThatThrownBy(() -> Riffle.parse("c : [\n    2\n] # close"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.TRAILING_TOKENS));
        assertThatThrownBy(() -> Riffle.parse("d : {} # empty"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.TRAILING_TOKENS));
        // A bare value runs to the end of the line, so the text becomes part of it.
        assertThat(value("k : 1 # one").asString()).isEqualTo("1 # one");
    }

    @Test
    void commentsMayFollowValuesOpenersAndClosersOnTheirOwnLines() {
        RiffleObject root = map("""
                # one
                a : 1
                # note
                b : "x"
                # open
                c : [
                    # element
                    2
                ]
                # empty
                d : {}
                """);
        assertThat(root.get("a").asInt()).isEqualTo(1);
        assertThat(root.get("b").asString()).isEqualTo("x");
        assertThat(root.get("c").asArray().get(0).asString()).isEqualTo("2");
        assertThat(root.get("d").asObject().entries()).isEmpty();
    }

    @Test
    void keysAreBareOrQuoted() {
        RiffleObject root = map("""
                1 : a
                schema.version : b
                chatgpt-oauth : c
                _x : d
                "key with spaces" : e
                "a=b" : f
                "a\\"b" : g
                "clé" : h
                """);
        assertThat(root.entries())
                .containsOnlyKeys(
                        "1", "schema.version", "chatgpt-oauth", "_x", "key with spaces", "a=b", "a\"b", "clé");
        assertThat(root.get("schema.version").asString()).isEqualTo("b");
        assertThat(root.get("key with spaces").asString()).isEqualTo("e");
    }

    @Test
    void dotsInKeysAreLiteral() {
        RiffleObject flat = map("a.b : x\n");
        RiffleObject nested = map("a : {\n    b : x\n}\n");
        assertThat(flat.entries()).containsOnlyKeys("a.b");
        assertThat(nested.get("a").asObject().entries()).containsOnlyKeys("b");
        assertThat(flat).isNotEqualTo(nested);
    }

    @Test
    void bareScalarsAreAlwaysStrings() {
        RiffleObject root = map("""
                nullValue : null
                trueValue : true
                number : 123
                tilde : ~
                empty :
                trimmed :    spaced
                interior : a  b
                """);
        assertThat(root.get("nullValue").asString()).isEqualTo("null");
        assertThat(root.get("trueValue").asString()).isEqualTo("true");
        assertThat(root.get("number").asString()).isEqualTo("123");
        assertThat(root.get("tilde").asString()).isEqualTo("~");
        assertThat(root.get("empty").asString()).isEmpty();
        assertThat(root.get("trimmed").asString()).isEqualTo("spaced");
        assertThat(root.get("interior").asString()).isEqualTo("a  b");
        assertThat(root.entries().values())
                .allSatisfy(v -> assertThat(v.isString()).isTrue());
    }

    @Test
    void aValueStartingWithAtMustBeQuoted() {
        assertThat(value("k : \"@scope/name\"").asString()).isEqualTo("@scope/name");
        assertThat(value("k : user@example.com").asString()).isEqualTo("user@example.com");
    }

    @Test
    void quotedStringsSupportJsonEscapes() {
        RiffleObject root = map("""
                quote : "a\\"b"
                backslash : "a\\\\b"
                newline : "a\\nb"
                tab : "a\\tb"
                backspace : "a\\bb"
                formfeed : "a\\fb"
                unicode : "caf\\u00e9"
                emoji : "\\uD83D\\uDCA1"
                """);
        assertThat(root.get("quote").asString()).isEqualTo("a\"b");
        assertThat(root.get("backslash").asString()).isEqualTo("a\\b");
        assertThat(root.get("newline").asString()).isEqualTo("a\nb");
        assertThat(root.get("tab").asString()).isEqualTo("a\tb");
        assertThat(root.get("backspace").asString()).isEqualTo("a\b" + "b");
        assertThat(root.get("formfeed").asString()).isEqualTo("a\f" + "b");
        assertThat(root.get("unicode").asString()).isEqualTo("café");
        assertThat(root.get("emoji").asString()).isEqualTo("💡");
    }

    @Test
    void emptyCollectionsAreInline() {
        RiffleObject root = map("a : {}\nb : []\n");
        assertThat(root.get("a").asObject().entries()).isEmpty();
        assertThat(root.get("b").asArray().values()).isEmpty();
    }

    @Test
    void nestedCollectionsAndMixedArrays() {
        RiffleObject root = map("""
                server : {
                    host : localhost
                    port : 8080
                }
                values : [
                    hello
                    123
                    true
                    {
                        name : example
                    }
                    [
                        one
                        two
                    ]
                ]
                """);
        assertThat(root.get("server").asObject().get("port").asInt()).isEqualTo(8080);
        RiffleArray values = root.get("values").asArray();
        assertThat(values.size()).isEqualTo(5);
        assertThat(values.get(0).asString()).isEqualTo("hello");
        assertThat(values.get(1).asInt()).isEqualTo(123);
        assertThat(values.get(2).asBoolean()).isTrue();
        assertThat(values.get(3).asObject().get("name").asString()).isEqualTo("example");
        assertThat(values.get(4).asArray().values())
                .extracting(RiffleValue::asString)
                .containsExactly("one", "two");
    }

    @Test
    void sameLineTokensArePartOfABareString() {
        assertThat(value("k : 1 y : 2").asString()).isEqualTo("1 y : 2");
    }

    @Test
    void indentationIsInsignificant() {
        RiffleContainer canonical = Riffle.parse("""
                a : {
                    b : [
                        one
                        two
                    ]
                }
                """);
        assertThat(Riffle.parse("a:{\nb:[\none\ntwo\n]\n}\n")).isEqualTo(canonical);
        assertThat(Riffle.parse("        a : {\n\tb : [\n\t\t one\n   two\n]\n}\n"))
                .isEqualTo(canonical);
    }

    @Test
    void lineTerminatorsAndBom() {
        assertThat(map("a : 1\r\nb : 2\r\n").entries()).containsOnlyKeys("a", "b");
        assertThat(map("\uFEFFa : 1\n").entries()).containsOnlyKeys("a");
        assertThat(map("a : 1").entries()).containsOnlyKeys("a");
    }

    @Test
    void unicodeSurvives() {
        assertThat(value("k : 💡 naïve").asString()).isEqualTo("💡 naïve");
        assertThat(map("\"clé\" : valeur").get("clé").asString()).isEqualTo("valeur");
    }

    @Test
    void valuesRecordLineColumnAndPath() {
        RiffleValue value = map("a : {\n    b : [\n        1\n    ]\n}\n")
                .get("a")
                .asObject()
                .get("b")
                .asArray()
                .get(0);
        assertThat(value.path()).contains("a.b[0]");
        assertThat(value.location().orElseThrow().line()).isEqualTo(3);
        assertThat(value.location().orElseThrow().column()).isEqualTo(9);
        assertThat(value.asInt()).isEqualTo(1);
    }

    @Test
    void theExampleFileParses() throws Exception {
        RiffleObject root = Riffle.parse(Path.of("examples/amanda.riffle"));
        assertThat(root.get("name").asString()).isEqualTo("Amanda");
        assertThat(root.get("model").asObject().get("temperature").asDecimal())
                .isEqualByComparingTo(new BigDecimal("0.7"));
        assertThat(root.get("tools").asArray().size()).isEqualTo(4);
    }

    @Test
    void documentsReadFromFilesAreParsed() throws Exception {
        Path file = Files.createTempFile("riffle", ".riffle");
        try {
            Files.writeString(file, "a : 1\n", StandardCharsets.UTF_8);
            assertThat(Riffle.parse(file).get("a").asInt()).isEqualTo(1);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
