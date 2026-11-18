package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(Riffle.parse("\n\n  \n# a comment\n// another\n")).isEqualTo(RiffleObject.of(Map.of()));
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
    void commentsAreFullLineOrInlineAfterWhitespace() {
        RiffleObject root = map("""
                # leading
                // leading too
                  # indented
                k : value # inline
                n : other // inline
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
    void aMarkerAfterWhitespaceStartsAComment() {
        assertThat(value("k : hello #world").asString()).isEqualTo("hello");
        assertThat(value("k : hello //world").asString()).isEqualTo("hello");
        assertThat(value("k : # just a comment").asString()).isEmpty();
    }

    @Test
    void markersInsideQuotesAreText() {
        assertThat(value("k : \"a # b\"").asString()).isEqualTo("a # b");
        assertThat(value("k : \"a // b\"").asString()).isEqualTo("a // b");
        assertThat(value("k : \"a # b\" // comment").asString()).isEqualTo("a # b");
        assertThat(value("k : \"a \\\" # b\"").asString()).isEqualTo("a \" # b");
    }

    @Test
    void aQuoteInsideABareValueIsOrdinaryText() {
        assertThat(value("k : a\"b # c").asString()).isEqualTo("a\"b");
        assertThat(value("k : a\"b // c").asString()).isEqualTo("a\"b");
        assertThat(value("k : he said \"hi\" # c").asString()).isEqualTo("he said \"hi\"");
    }

    @Test
    void commentsMayFollowValuesOpenersAndClosers() {
        RiffleObject root = map("""
                a : @int 1 # one
                b : "x" // note
                c : [ # open
                    2
                ] # close
                d : {} # empty
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
    void typedScalarsParse() {
        RiffleObject root = map("""
                i : @int 42
                n : @int -7
                l : @long 9223372036854775807
                d : @decimal -0.125
                e : @decimal 1e3
                big : @decimal 12345678901234567890.123456789
                t : @boolean true
                f : @boolean false
                s : @string true
                q : @string "quoted"
                nothing : @null
                """);
        assertThat(root.get("i").asInt()).isEqualTo(42);
        assertThat(root.get("n").asInt()).isEqualTo(-7);
        assertThat(root.get("l").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(root.get("d").asDecimal()).isEqualByComparingTo("-0.125");
        assertThat(root.get("e").asDecimal()).isEqualByComparingTo("1000");
        assertThat(root.get("big").asDecimal()).isEqualByComparingTo("12345678901234567890.123456789");
        assertThat(root.get("t").asBoolean()).isTrue();
        assertThat(root.get("f").asBoolean()).isFalse();
        assertThat(root.get("s").asString()).isEqualTo("true");
        assertThat(root.get("q").asString()).isEqualTo("quoted");
        assertThat(root.get("nothing").isNull()).isTrue();
    }

    @Test
    void decimalEqualityIgnoresScale() {
        assertThat(value("k : @decimal 1.0")).isEqualTo(value("k : @decimal 1.00"));
        assertThat(value("k : @decimal 1e3")).isEqualTo(value("k : @decimal 1000"));
        assertThat(value("k : @decimal 1.5")).isNotEqualTo(value("k : @decimal 1.50e1"));
    }

    @Test
    void emptyCollectionsAreInline() {
        RiffleObject root = map("a : {}\nb : []\nc : @int []\nd : @map []\n");
        assertThat(root.get("a").asObject().entries()).isEmpty();
        assertThat(root.get("b").asArray().values()).isEmpty();
        assertThat(root.get("c").asArray().values()).isEmpty();
        assertThat(root.get("d").asArray().values()).isEmpty();
    }

    @Test
    void nestedCollectionsAndMixedArrays() {
        RiffleObject root = map("""
                server : {
                    host : localhost
                    port : @int 8080
                }
                values : [
                    hello
                    @int 123
                    @boolean true
                    @null
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
        assertThat(values.size()).isEqualTo(6);
        assertThat(values.get(0).asString()).isEqualTo("hello");
        assertThat(values.get(1).asInt()).isEqualTo(123);
        assertThat(values.get(2).asBoolean()).isTrue();
        assertThat(values.get(3).isNull()).isTrue();
        assertThat(values.get(4).asObject().get("name").asString()).isEqualTo("example");
        assertThat(values.get(5).asArray().values())
                .extracting(RiffleValue::asString)
                .containsExactly("one", "two");
    }

    @Test
    void typedArraysSupplyTheElementType() {
        RiffleObject root = map("""
                ports : @int [
                    8080
                    -1
                ]
                flags : @boolean [
                    true
                    false
                ]
                levels : @string [
                    low
                    true
                ]
                nulls : @null [
                    null
                    null
                ]
                servers : @map [
                    {
                        host : a
                    }
                    {}
                ]
                rows : @array [
                    [
                        @int 1
                    ]
                    @int [
                        2
                        3
                    ]
                ]
                """);
        assertThat(root.get("ports").asArray().values())
                .extracting(RiffleValue::asInt)
                .containsExactly(8080, -1);
        assertThat(root.get("flags").asArray().values())
                .extracting(RiffleValue::asBoolean)
                .containsExactly(true, false);
        assertThat(root.get("levels").asArray().values())
                .extracting(RiffleValue::asString)
                .containsExactly("low", "true");
        assertThat(root.get("nulls").asArray().values())
                .allSatisfy(v -> assertThat(v.isNull()).isTrue());
        assertThat(root.get("servers").asArray().get(0).asObject().get("host").asString())
                .isEqualTo("a");
        assertThat(root.get("servers").asArray().get(1).asObject().entries()).isEmpty();
        assertThat(root.get("rows").asArray().size()).isEqualTo(2);
        assertThat(root.get("rows").asArray().get(0).asArray().get(0).asInt()).isEqualTo(1);
        assertThat(root.get("rows").asArray().get(1).asArray().values())
                .extracting(RiffleValue::asInt)
                .containsExactly(2, 3);
    }

    @Test
    void typedAndUntypedArraysHaveTheSameValue() {
        assertThat(value("k : @int [\n    1\n]")).isEqualTo(value("k : [\n    @int 1\n]"));
        assertThat(value("k : @string [\n    a\n]")).isEqualTo(value("k : [\n    a\n]"));
        assertThat(value("k : @null []")).isEqualTo(value("k : []"));
    }

    @Test
    void aBareNullInAnArrayIsAString() {
        RiffleArray array = map("k : [\n    null\n    @null\n]").get("k").asArray();
        assertThat(array.get(0).asString()).isEqualTo("null");
        assertThat(array.get(1).isNull()).isTrue();
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
        RiffleValue value = map("a : {\n    b : [\n        @int 1\n    ]\n}\n")
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
            Files.writeString(file, "a : @int 1\n", StandardCharsets.UTF_8);
            assertThat(Riffle.parse(file).get("a").asInt()).isEqualTo(1);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
