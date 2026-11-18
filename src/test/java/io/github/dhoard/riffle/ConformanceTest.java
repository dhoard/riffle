package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Runs every manifest case against the Java parser and canonical writer. */
class ConformanceTest {
    private static final Path ROOT = Path.of("conformance");

    @TestFactory
    Stream<DynamicTest> manifestCases() throws Exception {
        Map<String, Object> manifest = object(Json.parse(read("manifest.json")));
        assertThat(((BigDecimal) manifest.get("schemaVersion")).intValue())
                .as("manifest schemaVersion")
                .isEqualTo(1);
        List<Object> cases = array(manifest.get("cases"));
        assertThat(cases).as("manifest cases").isNotEmpty();
        return cases.stream().map(candidate -> {
            Map<String, Object> testCase = object(candidate);
            return DynamicTest.dynamicTest(string(testCase.get("id")), () -> run(testCase));
        });
    }

    private static void run(Map<String, Object> testCase) throws Exception {
        switch (string(testCase.get("expectation"))) {
            case "valid" -> runValid(testCase);
            case "invalid" -> runInvalid(testCase);
            default -> throw new AssertionError("unknown expectation: " + testCase.get("expectation"));
        }
    }

    private static void runValid(Map<String, Object> testCase) throws Exception {
        Path input = inputPath(testCase);
        RiffleObject parsed = Riffle.parse(input);
        byte[] expected = Files.readAllBytes(ROOT.resolve(path(testCase.get("expectedCanonical"))));
        byte[] actual = Riffle.write(parsed).getBytes(StandardCharsets.UTF_8);

        assertThat(actual).as("canonical bytes for %s", testCase.get("id")).isEqualTo(expected);

        RiffleObject reparsed = Riffle.parse(new String(actual, StandardCharsets.UTF_8));
        assertThat(reparsed)
                .as("semantic round trip for %s", testCase.get("id"))
                .isEqualTo(parsed);
        assertThat(Riffle.write(reparsed).getBytes(StandardCharsets.UTF_8))
                .as("canonical idempotence for %s", testCase.get("id"))
                .isEqualTo(expected);

        for (Object candidate : array(testCase.get("semantics"))) {
            checkSemantics(parsed, object(candidate));
        }
        for (Object candidate : array(testCase.get("accessors"))) {
            checkAccessor(parsed, object(candidate));
        }
    }

    private static void runInvalid(Map<String, Object> testCase) {
        Path input = inputPath(testCase);
        String expectedCategory = string(testCase.get("errorCategory"));
        assertThatThrownBy(() -> Riffle.parse(input))
                .as("rejection of %s", testCase.get("id"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category().name()).isEqualTo(expectedCategory));
    }

    private static void checkSemantics(RiffleObject root, Map<String, Object> assertion) {
        String path = string(assertion.get("path"));
        String kind = string(assertion.get("kind"));
        Optional<RiffleValue> found = find(root, path);

        if (kind.equals("missing")) {
            assertThat(found).as(path).isEmpty();
            return;
        }
        RiffleValue value = found.orElseThrow(() -> new AssertionError("missing path " + path));
        switch (kind) {
            case "string" -> {
                assertThat(value.isString()).as(path).isTrue();
                assertThat(value.asString()).as(path).isEqualTo(string(assertion.get("value")));
            }
            case "map" -> assertThat(value.isObject()).as(path).isTrue();
            case "array" -> assertThat(value.isArray()).as(path).isTrue();
            default -> throw new AssertionError("unknown semantic kind: " + kind);
        }
    }

    /** Reads a path as the requested interpretation and checks the value or the error category. */
    private static void checkAccessor(RiffleObject root, Map<String, Object> assertion) {
        String path = string(assertion.get("path"));
        String as = string(assertion.get("as"));
        String description = path + " as " + as;
        if (assertion.containsKey("error")) {
            String expected = string(assertion.get("error"));
            assertThatThrownBy(() -> read(root, path, as))
                    .as(description)
                    .isInstanceOfSatisfying(
                            RiffleAccessException.class,
                            e -> assertThat(e.category().name()).isEqualTo(expected));
            return;
        }
        String actual = read(root, path, as);
        if (assertion.containsKey("value")) {
            assertThat(actual).as(description).isEqualTo(string(assertion.get("value")));
        }
    }

    /** Returns the interpreted value in the manifest's text form; collections return their kind. */
    private static String read(RiffleObject root, String path, String as) {
        RiffleValue value = find(root, path)
                .orElseThrow(() -> new RiffleAccessException(RiffleAccessError.MISSING, "missing", path, null));
        return switch (as) {
            case "string" -> value.asString();
            case "integer32" -> Integer.toString(value.asInt());
            case "integer64" -> Long.toString(value.asLong());
            case "decimal" -> {
                BigDecimal decimal = value.asDecimal();
                yield decimal.unscaledValue() + "E" + -(long) decimal.scale();
            }
            case "boolean" -> Boolean.toString(value.asBoolean());
            case "map" -> value.asObject().getClass().getSimpleName();
            case "array" -> value.asArray().getClass().getSimpleName();
            default -> throw new AssertionError("unknown interpretation: " + as);
        };
    }

    /** Resolves dot-separated keys and {@code [index]} segments against a parsed value tree. */
    private static Optional<RiffleValue> find(RiffleObject root, String path) {
        RiffleValue current = root;
        int at = 0;
        while (at < path.length()) {
            if (current instanceof RiffleObject object) {
                int bracket = path.indexOf('[', at);
                int dot = path.indexOf('.', at);
                int end = firstIndex(bracket, dot, path.length());
                RiffleValue next = object.entries().get(path.substring(at, end));
                if (next == null) {
                    return Optional.empty();
                }
                current = next;
                at = end;
            } else if (current instanceof RiffleArray array) {
                if (path.charAt(at) != '[') {
                    return Optional.empty();
                }
                int close = path.indexOf(']', at);
                if (close < 0) {
                    return Optional.empty();
                }
                int index = Integer.parseInt(path.substring(at + 1, close));
                if (index < 0 || index >= array.size()) {
                    return Optional.empty();
                }
                current = array.get(index);
                at = close + 1;
            } else {
                return Optional.empty();
            }
            if (at < path.length() && path.charAt(at) == '.') {
                at++;
            }
        }
        return Optional.of(current);
    }

    private static int firstIndex(int first, int second, int fallback) {
        if (first < 0) {
            return second < 0 ? fallback : second;
        }
        return second < 0 ? first : Math.min(first, second);
    }

    private static Path inputPath(Map<String, Object> testCase) {
        return ROOT.resolve(path(testCase.get("input")));
    }

    private static Path path(Object value) {
        Path result = Path.of("");
        for (String part : string(value).split("/")) {
            result = result.resolve(part);
        }
        return result;
    }

    private static String read(String file) throws Exception {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        if (value == null) {
            return List.of();
        }
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }

    private static String string(Object value) {
        assertThat(value).isInstanceOf(String.class);
        return (String) value;
    }
}
