package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Block comments (`/#` … `#/`) as defined in LANGUAGE_SPEC.md §8: the delimiters occupy whole
 * lines, regions nest, and a region is never part of a key, value, opener, or closer.
 */
class BlockCommentsTest {

    private static RiffleObject map(String document) {
        return Riffle.parse(document);
    }

    private static RiffleValue value(String document) {
        return map(document).get("k");
    }

    @Test
    void aRegionHidesWholeLines() {
        RiffleObject root = map("""
                /#
                hidden : one
                hiddenMap : {
                }
                #/
                kept : two
                """);
        assertThat(root.entries()).containsOnlyKeys("kept");
    }

    @Test
    void regionsNest() {
        RiffleObject root = map("""
                /#
                 foo : bar
                 /#
                 # a line comment inside a region is text
                 bar : baz
                 #/
                #/
                kept : value
                """);
        assertThat(root.entries()).containsOnlyKeys("kept");
        assertThat(root.get("kept").asString()).isEqualTo("value");
    }

    @Test
    void nestedOpenersRequireOneCloserEach() {
        assertThatThrownBy(() -> Riffle.parse("/#\n/# inner\n#/\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.UNCLOSED_COMMENT));
    }

    @Test
    void textAfterTheOpenerIsCommentText() {
        RiffleObject root = map("/# disabled until the port allocation lands\nhidden : 1\n#/\nkept : 2\n");
        assertThat(root.entries()).containsOnlyKeys("kept");
    }

    @Test
    void textAfterTheCloserKeepsTheRegionOpen() {
        RiffleObject root = map("/#\nhidden : 1\n#/ trailing text is still inside\nhidden2 : 2\n#/\nkept : 3\n");
        assertThat(root.entries()).containsOnlyKeys("kept");
    }

    @Test
    void theCloserAcceptsTrailingWhitespace() {
        RiffleObject root = map("/#\nhidden : 1\n#/  \t \nkept : 2\n");
        assertThat(root.entries()).containsOnlyKeys("kept");
    }

    @Test
    void anIndentedRegionIsRecognized() {
        RiffleObject root = map("  /#\n hidden : 1\n\t#/\nkept : 2\n");
        assertThat(root.entries()).containsOnlyKeys("kept");
    }

    @Test
    void invalidRiffleInsideARegionIsIgnored() {
        RiffleObject root = map("""
                /#
                broken : {
                broken : {
                weight : @int not-a-number
                list : [ one ]
                "unterminated : x
                ] }
                #/
                kept : fine
                """);
        assertThat(root.entries()).containsOnlyKeys("kept");
        assertThat(root.get("kept").asString()).isEqualTo("fine");
    }

    @Test
    void aRegionInsideCollectionBodiesHidesEntriesAndElements() {
        RiffleObject root = map("""
                servers : {
                    # entries below stay disabled
                    /#
                    host : placeholder
                    port : 8080
                    #/
                    host : real
                }
                values : [
                    first
                    /#
                    second
                    #/
                    third
                ]
                """);
        assertThat(root.get("servers").asObject().entries()).containsOnlyKeys("host");
        assertThat(root.get("servers").asObject().get("host").asString()).isEqualTo("real");
        assertThat(root.get("values").asArray().values())
                .extracting(RiffleValue::asString)
                .containsExactly("first", "third");
    }

    @Test
    void aStrayCloserLineIsAnOrdinaryLineComment() {
        RiffleObject root = map("#/ still just a comment\nkept : 1\n");
        assertThat(root.entries()).containsOnlyKeys("kept");
        assertThat(value("k : a #/ tail").asString()).isEqualTo("a #/ tail");
    }

    @Test
    void aCloserLikeTextInsideARegionIsInert() {
        RiffleObject root = map("/#\n# a line comment mentioning #/ stays text\nhidden : 1\n#/\nkept : 2\n");
        assertThat(root.entries()).containsOnlyKeys("kept");
    }

    @Test
    void aBlockCommentIsNotAnInlineComment() {
        // §8: the delimiters occupy whole lines, so none of these is a comment; a `#` after a
        // value is part of the value.
        assertThat(value("k : a /# note #/").asString()).isEqualTo("a /# note #/");
        assertThat(value("k : a /# note").asString()).isEqualTo("a /# note");
        assertThat(value("k : a/# note#/").asString()).isEqualTo("a/# note#/");
        assertThat(value("k : /# note #/ b").asString()).isEqualTo("/# note #/ b");
    }

    @Test
    void aMarkerLineInsideAnArrayIsARegionNotAnElement() {
        assertThatThrownBy(() -> Riffle.parse("list : [\n /#\n ]\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.UNCLOSED_COMMENT));
        assertThat(Riffle.parse("list : [\n \"/#\"\n \"#/\"\n]\n")
                        .get("list")
                        .asArray()
                        .values())
                .extracting(RiffleValue::asString)
                .containsExactly("/#", "#/");
    }

    @Test
    void unclosedBlockCommentFailsAtTheOutermostOpener() {
        assertThatThrownBy(() -> Riffle.parse("ok : 1\n/#\n/#\n#/\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.UNCLOSED_COMMENT);
                    assertThat(e.location().line()).isEqualTo(2);
                    assertThat(e.location().column()).isEqualTo(1);
                });
    }

    @Test
    void anIndentedUnclosedOpenerReportsItsColumn() {
        assertThatThrownBy(() -> Riffle.parse("x : 1\n   /#\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.UNCLOSED_COMMENT);
                    assertThat(e.location().line()).isEqualTo(2);
                    assertThat(e.location().column()).isEqualTo(4);
                });
    }

    @Test
    void diagnosticsAfterARegionReportSourceLines() {
        assertThatThrownBy(() -> Riffle.parse("/#\nhidden : 1\n#/\nbroken : \"abc\n"))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.UNTERMINATED_STRING);
                    assertThat(e.location().line()).isEqualTo(4);
                });
    }

    @Test
    void aCommentOnlyDocumentIsEmpty() {
        assertThat(Riffle.parse("/#\n\n   \n#/\n").entries()).isEmpty();
        assertThat(Riffle.parse("/#\n#/").entries()).isEmpty();
    }

    @Test
    void regionsWorkWithCrlfAndAtEndOfFile() {
        assertThat(map("x : 1\r\n/#\r\nhidden : 2\r\n#/\r\nkept : 3\r\n").entries())
                .containsOnlyKeys("x", "kept");
        assertThat(map("kept : 1\n/#\nhidden : 2\n#/").entries()).containsOnlyKeys("kept");
    }

    @Test
    void canonicalOutputDropsRegionsAndRoundTrips() {
        String document = "/#\nhidden : 1\nhidden2 : {}\n#/\nkept : value\n";
        String written = Riffle.write(Riffle.parse(document));
        assertThat(written).isEqualTo("kept : value\n");
        assertThat(Riffle.write(Riffle.parse(written))).isEqualTo(written);
    }

    @Test
    void stringsThatBeginWithTheOpenerAreQuoted() {
        RiffleArray elements = RiffleArray.of(List.of(RiffleString.of("/#not a comment"), RiffleString.of("#/")));
        RiffleObject list = RiffleObject.of(Map.of("list", elements));
        String writtenList = Riffle.write(list);
        assertThat(writtenList).isEqualTo("list : [\n    \"/#not a comment\"\n    \"#/\"\n]\n");
        assertThat(Riffle.parse(writtenList)).isEqualTo(list);

        RiffleObject value = RiffleObject.of(Map.of("k", RiffleString.of("/#x")));
        String writtenValue = Riffle.write(value);
        assertThat(writtenValue).isEqualTo("k : \"/#x\"\n");
        assertThat(Riffle.parse(writtenValue)).isEqualTo(value);
    }
}
