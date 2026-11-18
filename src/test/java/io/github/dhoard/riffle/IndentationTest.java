package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Indentation has no syntactic significance. */
class IndentationTest {

    private static final String CANONICAL = """
            root : {
                a : text
                b : @int 1
                nested : {
                    c : [
                        one
                        two
                    ]
                }
                typed : @int [
                    1
                    2
                ]
                quoted : "a # b"
            }
            """;

    private static String reindent(String document, String indent, String lineEnding) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n")) {
            if (!line.isBlank()) {
                out.append(indent);
            }
            out.append(line).append(lineEnding);
        }
        return out.toString();
    }

    @Test
    void leadingWhitespaceIsIgnoredOnEveryKindOfLine() {
        RiffleObject canonical = Riffle.parse(CANONICAL);
        assertThat(Riffle.parse(reindent(CANONICAL, " ", "\n"))).isEqualTo(canonical);
        assertThat(Riffle.parse(reindent(CANONICAL, "        ", "\n"))).isEqualTo(canonical);
        assertThat(Riffle.parse(reindent(CANONICAL, "\t\t", "\r\n"))).isEqualTo(canonical);
        assertThat(Riffle.parse(reindent(CANONICAL, " \t \t", "\r\n"))).isEqualTo(canonical);
        assertThat(Riffle.parse(CANONICAL.replace("\n    ", "\n"))).isEqualTo(canonical);
    }

    @Test
    void everyLineMayUseADifferentIndent() {
        StringBuilder out = new StringBuilder();
        Random random = new Random(7);
        for (String line : CANONICAL.split("\n")) {
            out.append(line.isBlank() ? "" : " \t".repeat(random.nextInt(4)));
            out.append(line).append('\n');
        }
        assertThat(Riffle.parse(out.toString())).isEqualTo(Riffle.parse(CANONICAL));
    }

    @Test
    void indentationDoesNotChangeLineNumbersOrPaths() {
        RiffleValue flat =
                Riffle.parse("a : {\nb : @int 1\n}\n").get("a").asObject().get("b");
        RiffleValue indented = Riffle.parse("a : {\n            b : @int 1\n}\n")
                .get("a")
                .asObject()
                .get("b");
        assertThat(indented.path()).isEqualTo(flat.path());
        assertThat(indented.location().orElseThrow().line())
                .isEqualTo(flat.location().orElseThrow().line());
        assertThat(indented.location().orElseThrow().column())
                .isGreaterThan(flat.location().orElseThrow().column());
    }

    @ParameterizedTest
    @ValueSource(strings = {"amanda/profile.riffle", "amanda/theme.riffle"})
    void reindentingAFixtureDoesNotChangeItsValue(String resource) throws Exception {
        Path path = Path.of(getClass().getClassLoader().getResource(resource).toURI());
        String document = Files.readString(path, StandardCharsets.UTF_8);
        RiffleObject expected = Riffle.parse(document);
        Random random = new Random(resource.hashCode());
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder out = new StringBuilder();
            for (String line : document.split("\n")) {
                if (!line.isBlank()) {
                    StringBuilder indent = new StringBuilder();
                    for (int i = 0; i < random.nextInt(5); i++) {
                        indent.append(random.nextBoolean() ? ' ' : '\t');
                    }
                    out.append(indent);
                }
                out.append(line).append(random.nextBoolean() ? "\n" : "\r\n");
            }
            assertThat(Riffle.parse(out.toString())).isEqualTo(expected);
        }
    }

    static IntStream seeds() {
        return IntStream.range(0, 200);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void randomIndentationNeverChangesTheValue(int seed) {
        Random random = new Random(seed);
        RiffleObject value = new RiffleRandom(random).object();
        String canonical = Riffle.write(value);
        StringBuilder out = new StringBuilder();
        for (String line : canonical.split("\n", -1)) {
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < random.nextInt(6); i++) {
                indent.append(random.nextBoolean() ? ' ' : '\t');
            }
            out.append(indent).append(line).append(random.nextBoolean() ? "\n" : "\r\n");
        }
        assertThat(Riffle.parse(out.toString())).as(out.toString()).isEqualTo(value);
    }

    @Test
    void reindentedDocumentsStillWriteTheCanonicalForm() {
        assertThat(Riffle.write(Riffle.parse(reindent(CANONICAL, "\t", "\r\n"))))
                .isEqualTo(Riffle.write(Riffle.parse(CANONICAL)));
    }

    @Test
    void indentationInsideACommentIsIrrelevant() {
        assertThat(Riffle.parse("  # a\n\t# b\n\nk : v\n")).isEqualTo(Riffle.parse("k : v\n"));
    }

    @Test
    void exampleFileIsIndentationAgnostic() throws Exception {
        String document = Files.readString(Path.of("examples/amanda.riffle"), StandardCharsets.UTF_8);
        RiffleObject expected = Riffle.parse(document);
        for (String indent : List.of("", " ", "\t\t", "        ")) {
            assertThat(Riffle.parse(reindent(document, indent, "\n"))).isEqualTo(expected);
        }
    }
}
