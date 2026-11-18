package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Canonical output, quoting, round-tripping, and file and reader I/O. */
class WriterTest {

    private static RiffleString s(String text) {
        return RiffleString.of(text);
    }

    private static RiffleObject object(Object... keysAndValues) {
        Map<String, RiffleValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], (RiffleValue) keysAndValues[i + 1]);
        }
        return RiffleObject.of(map);
    }

    private static RiffleArray array(RiffleValue... values) {
        return RiffleArray.of(List.of(values));
    }

    @Test
    void writesTheCanonicalSpelling() {
        RiffleObject document = object(
                "name", s("Amanda"),
                "url", s("https://example.com:8080/a?b=1#c"),
                "empty", s(""),
                "padded", s("  x  "),
                "nullText", s("null"),
                "marker", s("@int 1"),
                "brace", s("}"),
                "hash", s("# x"),
                "commentish", s("a # b"),
                "newline", s("a\nb"),
                "tab", s("a\tb"),
                "int", RiffleInt.of(42),
                "long", RiffleLong.of(9007199254740993L),
                "decimal", RiffleDecimal.of(new BigDecimal("0.25")),
                "flag", RiffleBoolean.of(true),
                "nothing", RiffleNull.INSTANCE,
                "emptyMap", object(),
                "emptyArray", array(),
                "map", object("a", s("b")),
                "list", array(s("a"), RiffleInt.of(1)),
                "key with space", s("v"),
                "a.b", s("dotted"));
        assertThat(Riffle.write(document)).isEqualTo("""
                name : Amanda
                url : https://example.com:8080/a?b=1#c
                empty : ""
                padded : "  x  "
                nullText : null
                marker : "@int 1"
                brace : "}"
                hash : "# x"
                commentish : a # b
                newline : "a\\nb"
                tab : "a\\tb"
                int : @int 42
                long : @long 9007199254740993
                decimal : @decimal 0.25
                flag : @boolean true
                nothing : @null
                emptyMap : {}
                emptyArray : []
                map : {
                    a : b
                }
                list : [
                    a
                    @int 1
                ]
                "key with space" : v
                a.b : dotted
                """);
    }

    @Test
    void canonicalSeparatorHasOneSpaceOnEachSide() {
        RiffleObject parsed = Riffle.parse("a:b\n   c   :   d\n\"e f\":g\nmessage:Hello: world\n");
        assertThat(Riffle.write(parsed)).isEqualTo("a : b\nc : d\n\"e f\" : g\nmessage : Hello: world\n");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("stringSpellings")
    void stringsAreQuotedOnlyWhenRequired(String text, String expected) {
        String written = Riffle.write(object("k", s(text)));
        assertThat(written).isEqualTo("k : " + expected + "\n");
        assertThat(Riffle.parse(written)).isEqualTo(object("k", s(text)));
    }

    static java.util.stream.Stream<Arguments> stringSpellings() {
        return java.util.stream.Stream.of(
                Arguments.of("text", "text"),
                Arguments.of("null", "null"),
                Arguments.of("true", "true"),
                Arguments.of("123", "123"),
                Arguments.of("~", "~"),
                Arguments.of("a=b", "a=b"),
                Arguments.of("https://x/y#frag", "https://x/y#frag"),
                Arguments.of("a//b", "a//b"),
                Arguments.of("", "\"\""),
                Arguments.of(" x", "\" x\""),
                Arguments.of("x ", "\"x \""),
                Arguments.of("  x  ", "\"  x  \""),
                Arguments.of("@int 1", "\"@int 1\""),
                Arguments.of("#x", "\"#x\""),
                Arguments.of("//x", "//x"),
                Arguments.of("# x", "\"# x\""),
                Arguments.of("a # b", "a # b"),
                Arguments.of("a // b", "a // b"),
                Arguments.of("{", "\"{\""),
                Arguments.of("}", "\"}\""),
                Arguments.of("[", "\"[\""),
                Arguments.of("]", "\"]\""),
                Arguments.of("\"quoted\"", "\"\"quoted\"\""),
                Arguments.of("back\\slash", "back\\slash"),
                Arguments.of("line1\nline2", "\"line1\\nline2\""),
                Arguments.of("tab\there", "\"tab\\there\""),
                Arguments.of("é", "é"));
    }

    @Test
    void keysAreQuotedOnlyWhenRequired() {
        RiffleObject document = object(
                "provider", s("v"),
                "a.b", s("v"),
                "1", s("v"),
                "_x", s("v"),
                "a b", s("v"),
                "a=b", s("v"),
                "@x", s("v"),
                "clé", s("v"),
                "", s("v"));
        assertThat(Riffle.write(document)).isEqualTo("""
                provider : v
                a.b : v
                1 : v
                _x : v
                "a b" : v
                "a=b" : v
                "@x" : v
                "clé" : v
                "" : v
                """);
        assertThat(Riffle.parse(Riffle.write(document))).isEqualTo(document);
    }

    @Test
    void commentsAreNotPreserved() {
        String withComments = "# header\nk : v\n\n# footer\n";
        assertThat(Riffle.write(Riffle.parse(withComments))).isEqualTo("k : v\n");
    }

    @Test
    void emptyDocumentWritesNothing() {
        assertThat(Riffle.write(RiffleObject.of(Map.of()))).isEmpty();
    }

    @Test
    void rootArraysCannotBeWritten() {
        assertThatThrownBy(() -> Riffle.write(array(s("a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root");
    }

    @Test
    void writingIsIdempotentAndRoundTrips() {
        RiffleObject document = object(
                "a", s("text # not a comment"),
                "b", RiffleInt.of(-1),
                "c", array(RiffleBoolean.of(false), RiffleNull.INSTANCE, s("@null")));
        String written = Riffle.write(document);
        assertThat(Riffle.parse(written)).isEqualTo(document);
        assertThat(Riffle.write(Riffle.parse(written))).isEqualTo(written);
    }

    static IntStream seeds() {
        return IntStream.range(0, 300);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void randomValuesRoundTripAndWritingIsIdempotent(int seed) {
        RiffleObject value = new RiffleRandom(new Random(seed)).object();
        String written = Riffle.write(value);
        RiffleObject parsed = Riffle.parse(written);
        assertThat(parsed).as(written).isEqualTo(value);
        assertThat(Riffle.write(parsed)).isEqualTo(written);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void reindentingNeverChangesTheValue(int seed) {
        Random random = new Random(seed);
        RiffleObject value = new RiffleRandom(random).object();
        StringBuilder reindented = new StringBuilder();
        for (String line : Riffle.write(value).split("\n", -1)) {
            String stripped = line.replaceFirst("^[ \t]+", "");
            reindented.append(randomIndent(random)).append(stripped).append(random.nextBoolean() ? "\n" : "\r\n");
        }
        assertThat(Riffle.parse(reindented.toString())).isEqualTo(value);
    }

    @Test
    void writesAndReadsFilesWithTheSourceNameInDiagnostics(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("config.riffle");
        RiffleObject value = object("port", RiffleInt.of(80));
        Riffle.write(file, value);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("port : @int 80\n");
        RiffleObject parsed = Riffle.parse(file);
        assertThat(parsed).isEqualTo(value);
        assertThat(parsed.get("port").location().flatMap(RiffleLocation::source))
                .contains(file.toString());
        assertThatThrownBy(() -> parsed.get("port").asString())
                .isInstanceOf(RiffleAccessException.class)
                .hasMessage(file + ":1:8: port: expected string but was int");
    }

    @Test
    void parseErrorsInFilesNameTheFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("bad.riffle");
        Files.writeString(file, "a : 1\nb : @int\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> Riffle.parse(file)).isInstanceOfSatisfying(RiffleParseException.class, e -> {
            assertThat(e.location().source()).contains(file.toString());
            assertThat(e.getMessage()).startsWith(file + ":2:");
        });
    }

    @Test
    void unicodeSurvivesFiles(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("unicode.riffle");
        RiffleObject value = object("clé", s("💡 naïve"), "escaped", s("a\nb"));
        Riffle.write(file, value);
        assertThat(Riffle.parse(file)).isEqualTo(value);
    }

    @Test
    void malformedUtf8IsAReportedError(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("latin1.riffle");
        Files.write(file, new byte[] {'k', ' ', '=', ' ', (byte) 0xE9});
        assertThatThrownBy(() -> Riffle.parse(file))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.INVALID_UTF8));
    }

    @Test
    void missingFileIsAnIoError(@TempDir Path directory) {
        assertThatThrownBy(() -> Riffle.parse(directory.resolve("absent.riffle")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void readerIsFullyConsumedAndLeftOpen() throws IOException {
        TrackingReader reader = new TrackingReader("a : text\nb : \"x\\ny\"\n");
        RiffleObject parsed = Riffle.parse(reader);
        assertThat(parsed.get("b").asString()).isEqualTo("x\ny");
        assertThat(parsed.get("a").location().flatMap(RiffleLocation::source)).isEmpty();
        assertThat(reader.closed).isFalse();
    }

    @Test
    void nullArgumentsAreRejected(@TempDir Path directory) {
        assertThatThrownBy(() -> Riffle.write((RiffleContainer) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Riffle.write(directory.resolve("x"), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Riffle.write(null, object())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Riffle.parse((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Riffle.parse((StringReader) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Riffle.parse((Path) null)).isInstanceOf(NullPointerException.class);
    }

    private static String randomIndent(Random random) {
        StringBuilder indent = new StringBuilder();
        int width = random.nextInt(6);
        for (int i = 0; i < width; i++) {
            indent.append(random.nextBoolean() ? ' ' : '\t');
        }
        return indent.toString();
    }

    private static final class TrackingReader extends StringReader {
        private boolean closed;

        TrackingReader(String text) {
            super(text);
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }
}
