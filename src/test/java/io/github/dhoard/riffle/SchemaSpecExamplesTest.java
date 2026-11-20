package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SCHEMA_SPEC.md carries worked examples whose lines, paths, and categories are normative. This test
 * reads them out of the specification rather than copying them, so the specification remains the
 * thing that is checked.
 */
class SchemaSpecExamplesTest {

    /** The dash §16.3 writes where a document is expected to be valid. */
    private static final String NONE = "\u2014";

    private static final Path SPEC = Path.of("SCHEMA_SPEC.md");
    private static final Path RESOURCE = Path.of("src/main/resources/io/github/dhoard/riffle/meta-schema.riffle");
    private static final Path CONFORMANCE = Path.of("conformance/schema/meta-schema.riffle");
    private static final Path EXAMPLE_SCHEMA = Path.of("examples/service.schema.riffle");
    private static final Path EXAMPLE_DOCUMENT = Path.of("examples/service.riffle");

    /** The kindUnion §16.3 describes in prose, written as a schema document. */
    private static final String KIND_UNION = """
            riffleSchema : 1
            root : {
                type : map
                keys : {
                    mode : {
                        type : kindUnion
                        string : {
                            type : integer
                        }
                        map : {
                            type : map
                        }
                        array : {
                            type : array
                            items : {
                                type : any
                            }
                        }
                    }
                }
            }
            """;

    /** The array-with-`values` schema §16.3 describes in prose, written as a schema document. */
    private static final String DECIMAL_VALUES = """
            riffleSchema : 1
            root : {
                type : map
                keys : {
                    values : {
                        type : array
                        unique : true
                        items : {
                            type : decimal
                        }
                    }
                }
            }
            """;

    @Test
    void loadsTheCanonicalSchemaOfSectionTwo() throws IOException {
        assertThatCode(() -> RiffleSchema.parse(sectionTwo().get(0))).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheValidDocumentOfSectionTwo() throws IOException {
        List<String> blocks = sectionTwo();
        RiffleSchema schema = RiffleSchema.parse(blocks.get(0));

        assertThatCode(() -> Riffle.parse(blocks.get(1), schema)).doesNotThrowAnyException();
    }

    @Test
    void reportsExactlyTheProblemsSectionTwoLists() throws IOException {
        List<String> blocks = sectionTwo();
        RiffleSchema schema = RiffleSchema.parse(blocks.get(0));
        List<Expected> expected = sectionTwoProblems();

        RiffleValidationException failure = catchThrowableOfType(
                RiffleValidationException.class, () -> Riffle.parse(blocks.get(blocks.size() - 1), schema));

        assertThat(failure.problems()).as("problems").hasSameSizeAs(expected);
        for (int position = 0; position < expected.size(); position++) {
            Expected row = expected.get(position);
            RiffleProblem actual = failure.problems().get(position);
            assertThat(actual.category().name())
                    .as("problem %d category", position + 1)
                    .isEqualTo(row.category());
            assertThat(actual.path()).as("problem %d path", position + 1).isEqualTo(path(row.path()));
            assertThat(actual.location())
                    .as("problem %d line", position + 1)
                    .map(RiffleLocation::line)
                    .contains(row.line());
        }
    }

    @Test
    void appliesNoDefaultsToTheCanonicalExample() throws IOException {
        List<String> blocks = sectionTwo();
        RiffleSchema schema = RiffleSchema.parse(blocks.get(0));
        RiffleObject document = Riffle.parse(blocks.get(1));

        assertThat(schema.applyDefaults(document)).isEqualTo(document);
    }

    @Test
    void loadsEverySchemaOfSectionSixteenOne() throws IOException {
        List<String> blocks = blocks("### 16.1", "### 16.2");

        assertThat(blocks).as("schemas in §16.1").isNotEmpty();
        for (String block : blocks) {
            assertThatCode(() -> RiffleSchema.parse(block))
                    .as("schema %s", block)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsEverySchemaOfSectionSixteenTwoAsItsTableSays() throws IOException {
        List<Row> rows = expectations(tables("### 16.2", "### 16.3").get(0));

        assertThat(rows).as("rows in §16.2").hasSizeGreaterThanOrEqualTo(10);
        for (Row row : rows) {
            String input = unescape(row.cell(1));

            RiffleSchemaException failure =
                    catchThrowableOfType(RiffleSchemaException.class, () -> RiffleSchema.parse(input));

            assertThat(failure.problems()).as("input %s", input).singleElement().satisfies(problem -> {
                assertThat(problem.category().name()).isEqualTo(row.cell(2));
                assertThat(problem.path()).isEqualTo(path(row.cell(3)));
            });
        }
    }

    @Test
    void checksTheFirstValidationTableOfSectionSixteenThreeAgainstTheSchemaOfSectionTwo() throws IOException {
        RiffleSchema schema = RiffleSchema.parse(sectionTwo().get(0));
        List<Row> rows = expectations(tables("### 16.3", "### 16.4").get(0));

        assertThat(rows).as("rows against the schema of §2").hasSizeGreaterThanOrEqualTo(5);
        for (Row row : rows) {
            assertDocument(row, schema);
        }
    }

    @Test
    void checksTheKindUnionTableOfSectionSixteenThree() throws IOException {
        RiffleSchema schema = RiffleSchema.parse(KIND_UNION);
        List<Row> rows = expectations(tables("### 16.3", "### 16.4").get(1)).stream()
                .filter(row -> row.cell(1).matches("mode : [^ {]+"))
                .toList();

        assertThat(rows).as("kindUnion rows").hasSizeGreaterThanOrEqualTo(2);
        for (Row row : rows) {
            assertDocument(row, schema);
        }
        assertThat(schema.validate(Riffle.parse("mode : {}\n"))).isEmpty();
        assertThat(schema.validate(Riffle.parse("mode : {\n    a : 1\n}\n")))
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.UNKNOWN_KEY);
    }

    @Test
    void checksTheValuesTableOfSectionSixteenThree() throws IOException {
        RiffleSchema schema = RiffleSchema.parse(DECIMAL_VALUES);
        List<Row> rows = expectations(tables("### 16.3", "### 16.4").get(2));

        assertThat(rows).as("values rows").hasSizeGreaterThanOrEqualTo(2);
        for (Row row : rows) {
            assertDocument(row, schema);
        }
    }

    @Test
    void keepsTheShippedExamplesEqualToTheOnesSectionTwoShows() throws IOException {
        List<String> blocks = sectionTwo();

        assertThat(Files.readString(EXAMPLE_SCHEMA, StandardCharsets.UTF_8)).isEqualTo(blocks.get(0) + "\n");
        assertThat(Files.readString(EXAMPLE_DOCUMENT, StandardCharsets.UTF_8))
                .isEqualTo(blocks.get(blocks.size() - 1) + "\n");
    }

    @Test
    void shipsTheMetaSchemaOfSectionFifteenUnchangedInEveryCopy() throws IOException {
        String fromSpec = blocks("## 15.", "## 16.").get(0) + "\n";

        assertThat(fromSpec).isEqualTo(Files.readString(RESOURCE, StandardCharsets.UTF_8));
        assertThat(fromSpec).isEqualTo(Files.readString(CONFORMANCE, StandardCharsets.UTF_8));
    }

    @Test
    void validatesTheMetaSchemaAgainstItself() throws IOException {
        assertThatCode(() -> RiffleSchema.parse(Files.readString(RESOURCE, StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();
    }

    /** Runs the document a validation table row holds and checks what that row expects. */
    private static void assertDocument(Row row, RiffleSchema schema) {
        String input = unescape(row.cell(1));
        List<RiffleProblem> problems = schema.validate(Riffle.parse(input + "\n"));

        if (row.cell(2).equals(NONE)) {
            assertThat(problems).as("document %s", input).isEmpty();
            return;
        }
        assertThat(problems).as("document %s", input).singleElement().satisfies(problem -> {
            assertThat(problem.category().name()).isEqualTo(row.cell(2));
            assertThat(problem.path()).isEqualTo(path(row.cell(3)));
        });
    }

    /** The ```riffle blocks of §2: the schema, then its documents. */
    private static List<String> sectionTwo() throws IOException {
        List<String> blocks = blocks("## 2.", "## 3.");
        assertThat(blocks).as("blocks in §2").hasSize(3);
        return blocks;
    }

    /** The rows of the problem list of §2: a category, a path, and a line. */
    private static List<Expected> sectionTwoProblems() throws IOException {
        List<Row> rows = expectations(tables("## 2.", "## 3.").get(0));

        assertThat(rows).as("problem rows in §2").hasSizeGreaterThanOrEqualTo(6);
        return rows.stream()
                .map(row -> new Expected(row.cell(2), row.cell(3), Integer.parseInt(row.cell(4))))
                .toList();
    }

    /**
     * Returns the tables between two headings. Each table is its rows without the heading row and the
     * separator; a caller decides which of them interest it.
     */
    private static List<List<Row>> tables(String from, String until) throws IOException {
        List<String> lines = readLines();
        List<List<Row>> found = new ArrayList<>();
        List<Row> current = null;
        for (String line : lines.subList(indexOf(lines, from), indexOf(lines, until))) {
            if (!line.startsWith("|")) {
                if (current != null && current.size() > 1) {
                    found.add(current);
                }
                current = null;
                continue;
            }
            String[] cells = line.split("\\|");
            if (cells.length < 4) {
                continue;
            }
            if (current == null) {
                current = new ArrayList<>();
            }
            current.add(new Row(cells));
        }
        if (current != null && current.size() > 1) {
            found.add(current);
        }
        return found.stream().map(rows -> rows.subList(1, rows.size())).toList();
    }

    /** Returns the rows that name a category, or the dash standing for no problem at all. */
    private static List<Row> expectations(List<Row> rows) {
        return rows.stream()
                .filter(row -> isCategory(row.cell(2)) || row.cell(2).equals(NONE))
                .toList();
    }

    private static boolean isCategory(String text) {
        for (RiffleValidationError category : RiffleValidationError.values()) {
            if (category.name().equals(text)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the text of each ```riffle block between two headings, in order. */
    private static List<String> blocks(String from, String until) throws IOException {
        List<String> lines = readLines();
        List<String> blocks = new ArrayList<>();
        StringBuilder text = null;
        for (String line : lines.subList(indexOf(lines, from), indexOf(lines, until))) {
            String trimmed = line.trim();
            if (trimmed.equals("```riffle")) {
                text = new StringBuilder();
            } else if (trimmed.equals("```")) {
                if (text == null) {
                    throw new AssertionError("a closing fence without an opening one under " + from);
                }
                blocks.add(text.toString().replaceAll("\n$", ""));
                text = null;
            } else if (text != null) {
                text.append(line).append('\n');
            }
        }
        if (text != null) {
            throw new AssertionError("an unclosed ```riffle block under " + from);
        }
        return blocks;
    }

    /** Reads a path in the syntax of LANGUAGE_SPEC.md §10.1, as a table or a manifest writes it. */
    private static RifflePath path(String text) {
        return text.equals("(root)") ? RifflePath.root() : ConformanceTest.parsePath(text);
    }

    /** Turns the `\n` a table cell writes for a line break back into a line break. */
    private static String unescape(String cell) {
        return cell.replace("\\n", "\n");
    }

    private static List<String> readLines() throws IOException {
        return Files.readAllLines(SPEC, StandardCharsets.UTF_8);
    }

    private static int indexOf(List<String> lines, String heading) {
        for (int position = 0; position < lines.size(); position++) {
            if (lines.get(position).startsWith(heading)) {
                return position;
            }
        }
        throw new UncheckedIOException(new IOException("SCHEMA_SPEC.md has no heading " + heading));
    }

    /** One row of a markdown table, with its cells as written. */
    private record Row(String[] cells) {

        String cell(int position) {
            return cells[position].trim().replace("`", "").trim();
        }
    }

    /** One expected problem of a specification example. */
    private record Expected(String category, String path, int line) {}
}
