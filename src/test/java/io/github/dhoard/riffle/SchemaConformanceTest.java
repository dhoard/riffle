package io.github.dhoard.riffle;

import static io.github.dhoard.riffle.ConformanceTest.parsePath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The schema conformance suite of {@code conformance/schema/}, run against this implementation.
 *
 * <p>The manifest is the contract: it lists, for every case, the ordered problems the
 * specification requires, each with a category, a path, and the line the problem is reported at.
 * Categories, paths, lines, order, and the count are compared; the wording of a reason, the column,
 * and the source name are not, which is what lets a host in another language pass the same
 * fixtures.</p>
 */
class SchemaConformanceTest {

    private static final Path ROOT = Path.of("conformance/schema");

    @TestFactory
    Stream<DynamicTest> manifestCases() throws IOException {
        Map<String, Object> manifest = Json.object(Json.parse(read(ROOT.resolve("manifest.json"))));
        assertThat(((BigDecimal) manifest.get("schemaVersion")).intValue())
                .as("manifest schemaVersion")
                .isEqualTo(1);
        List<Object> cases = Json.array(manifest.get("cases"));
        assertThat(cases).as("manifest cases").isNotEmpty();
        return cases.stream().map(candidate -> {
            Map<String, Object> testCase = Json.object(candidate);
            return DynamicTest.dynamicTest(Json.string(testCase.get("id")), () -> run(testCase));
        });
    }

    private static void run(Map<String, Object> testCase) throws IOException {
        String id = Json.string(testCase.get("id"));
        String expectation = Json.string(testCase.get("expectation"));
        Path schemaPath = ROOT.resolve(Json.string(testCase.get("schema")));
        switch (expectation) {
            case "schema-valid" ->
                assertThatCode(() -> RiffleSchema.parse(schemaPath))
                        .as("schema %s loads", id)
                        .doesNotThrowAnyException();
            case "schema-invalid" -> {
                List<Expected> expected = problems(testCase);
                assertSchemaProblems(id, schemaPath, expected);
            }
            case "document" -> {
                RiffleSchema schema = RiffleSchema.parse(schemaPath);
                RiffleObject document = Riffle.parse(ROOT.resolve(Json.string(testCase.get("input"))));
                assertDocumentProblems(id, schema, document, problems(testCase));
            }
            default -> throw new AssertionError("unknown expectation: " + expectation);
        }
    }

    private static void assertSchemaProblems(String id, Path schemaPath, List<Expected> expected) throws IOException {
        List<Expected> actual = new ArrayList<>();
        try {
            RiffleSchema.parse(schemaPath);
        } catch (RiffleSchemaException e) {
            describe(e.problems(), actual);
        } catch (RiffleParseException e) {
            throw new AssertionError(id + ": the schema document does not parse: " + e.getMessage(), e);
        }
        assertThat(actual).as("problems with the schema of %s", id).isEqualTo(expected);
    }

    private static void assertDocumentProblems(
            String id, RiffleSchema schema, RiffleObject document, List<Expected> expected) {
        List<Expected> actual = new ArrayList<>();
        describe(schema.validate(document), actual);
        assertThat(actual).as("problems with the document of %s", id).isEqualTo(expected);
    }

    /** Renders each problem as the three fields the manifest compares, dropping the reason. */
    private static void describe(List<RiffleProblem> problems, List<Expected> sink) {
        for (RiffleProblem problem : problems) {
            sink.add(new Expected(
                    problem.category().name(),
                    problem.path().toString(),
                    problem.location().map(RiffleLocation::line).orElse(0)));
        }
    }

    private static List<Expected> problems(Map<String, Object> testCase) {
        List<Expected> expected = new ArrayList<>();
        for (Object candidate : Json.array(testCase.get("problems"))) {
            Map<String, Object> problem = Json.object(candidate);
            String category = Json.string(problem.get("category"));
            String path = Json.string(problem.get("path"));
            assertThat(RiffleValidationError.valueOf(category))
                    .as("manifest category %s is one of the categories of spec §10", category)
                    .isNotNull();
            parsePath(path);
            expected.add(new Expected(category, path, ((BigDecimal) problem.get("line")).intValue()));
        }
        return List.copyOf(expected);
    }

    /** The three published copies of the meta-schema, and the self-validation it promises. */
    @Test
    void shippedMetaSchemaCopiesAreIdentical() throws IOException {
        byte[] resource = Files.readAllBytes(Path.of("src/main/resources/io/github/dhoard/riffle/meta-schema.riffle"));
        byte[] published = Files.readAllBytes(ROOT.resolve("meta-schema.riffle"));
        byte[] normative = metaSchemaFromSpec();
        assertThat(published).as("the conformance copy of the meta-schema").isEqualTo(resource);
        assertThat(normative).as("the meta-schema fenced in SCHEMA_SPEC.md §15").isEqualTo(resource);
    }

    /** Loading the shipped meta-schema, which validates the shipped copy against itself. */
    @Test
    void metaSchemaLoadsAndValidatesItself() {
        assertThatCode(SchemaConformanceTest::loadShippedMetaSchema).doesNotThrowAnyException();
    }

    /** No manifest case may name a category that the closed vocabulary of spec §10 does not hold. */
    @Test
    void manifestCategoriesAreFromTheClosedVocabulary() throws IOException {
        Map<String, Object> manifest = Json.object(Json.parse(read(ROOT.resolve("manifest.json"))));
        Set<String> named = new HashSet<>();
        collectCategories(Json.array(manifest.get("cases")), named);
        assertThat(named).as("categories the suite exercises").isNotEmpty();
        for (String category : named) {
            assertThatCode(() -> RiffleValidationError.valueOf(category))
                    .as("manifest category %s is one of the categories of spec §10", category)
                    .doesNotThrowAnyException();
        }
    }

    private static void collectCategories(List<Object> cases, Set<String> sink) {
        for (Object candidate : cases) {
            Map<String, Object> testCase = Json.object(candidate);
            if (!testCase.containsKey("problems")) {
                continue;
            }
            for (Object problem : Json.array(testCase.get("problems"))) {
                sink.add(Json.string(Json.object(problem).get("category")));
            }
        }
    }

    private static RiffleSchema loadShippedMetaSchema() {
        try {
            return RiffleSchema.parse(
                    Files.readString(Path.of("src/main/resources/io/github/dhoard/riffle/meta-schema.riffle")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Extracts the fenced meta-schema of SCHEMA_SPEC.md §15, the normative text itself. */
    private static byte[] metaSchemaFromSpec() throws IOException {
        String spec = read(Path.of("SCHEMA_SPEC.md"));
        int section = spec.indexOf("## 15. Meta-schema");
        int open = spec.indexOf("```riffle\n", section) + "```riffle\n".length();
        int close = spec.indexOf("\n```\n", open) + 1;
        return spec.substring(open, close).getBytes(StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** One problem as the manifest states it: a category, a rendered path, and a line. */
    private record Expected(String category, String path, int line) {}
}
