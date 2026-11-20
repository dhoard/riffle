package io.github.dhoard.riffle;

import static io.github.dhoard.riffle.Json.array;
import static io.github.dhoard.riffle.Json.object;
import static io.github.dhoard.riffle.Json.string;
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
import org.junit.jupiter.api.Test;
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
        assertCanonicalPath(path);
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
        assertCanonicalPath(path);
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

    /** Checks that a manifest path is written in the canonical form of language-spec §10.1. */
    private static void assertCanonicalPath(String path) {
        assertThat(parsePath(path).toString())
                .as("manifest path %s uses the path syntax of language-spec \u00a710.1", path)
                .isEqualTo(path);
    }

    /** Returns the interpreted value in the manifest's text form; collections return their kind. */
    private static String read(RiffleObject root, String path, String as) {
        RiffleValue value = find(root, parsePath(path))
                .orElseThrow(
                        () -> new RiffleAccessException(RiffleAccessError.MISSING, "missing", parsePath(path), null));
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

    /** Resolves a path of language-spec §10.1 against a parsed value tree. */
    private static Optional<RiffleValue> find(RiffleObject root, String path) {
        return find(root, parsePath(path));
    }

    private static Optional<RiffleValue> find(RiffleValue start, RifflePath path) {
        RiffleValue current = start;
        for (RifflePath.Segment segment : path.segments()) {
            RiffleValue next = null;
            if (segment instanceof RifflePath.Key key && current instanceof RiffleObject object) {
                next = object.entries().get(key.key());
            } else if (segment instanceof RifflePath.Index index
                    && current instanceof RiffleArray array
                    && index.index() < array.size()) {
                next = array.get(index.index());
            }
            if (next == null) {
                return Optional.empty();
            }
            current = next;
        }
        return Optional.of(current);
    }

    /**
     * Parses the normative path syntax of language-spec §10.1: bare or quoted key segments
     * separated by dots, and {@code [n]} indexes.
     */
    static RifflePath parsePath(String path) {
        RifflePath result = RifflePath.root();
        int at = 0;
        while (at < path.length()) {
            char first = path.charAt(at);
            if (first == '[') {
                int close = path.indexOf(']', at);
                if (close < 0) {
                    throw new AssertionError("unterminated index in path: " + path);
                }
                result = result.index(Integer.parseInt(path.substring(at + 1, close)));
                at = close + 1;
                if (at < path.length() && path.charAt(at) == '.') {
                    at++;
                }
                continue;
            }
            String key;
            if (first == '"') {
                int end = at;
                while (true) {
                    end = path.indexOf('"', end + 1);
                    if (end < 0) {
                        throw new AssertionError("unterminated quoted key in path: " + path);
                    }
                    int backslashes = 0;
                    for (int i = end - 1; i > at && path.charAt(i) == '\\'; i--) {
                        backslashes++;
                    }
                    if (backslashes % 2 == 0) {
                        break;
                    }
                }
                key = unescape(path.substring(at + 1, end), path);
                at = end + 1;
            } else {
                int end = at;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                key = path.substring(at, end);
                at = end;
            }
            result = result.key(key);
            if (at < path.length() && path.charAt(at) == '.') {
                at++;
            }
        }
        return result;
    }

    @Test
    void canonicalPathsWithEscapedKeysParseBackToTheSameKey() {
        for (String key : List.of("a\\", "a\\\\", "say \"hi\"", "display name", "a.b", "")) {
            RifflePath path = RifflePath.root().key(key);
            assertThat(parsePath(path.toString())).as("canonical path %s", path).isEqualTo(path);
        }
    }

    /** Applies the quoted-key escapes of language-spec §5.2 to the text between the quotes. */
    private static String unescape(String body, String path) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= body.length()) {
                throw new AssertionError("dangling escape in path: " + path);
            }
            char escape = body.charAt(i + 1);
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 5 >= body.length()) {
                        throw new AssertionError("truncated \\u escape in path: " + path);
                    }
                    out.append((char) Integer.parseInt(body.substring(i + 2, i + 6), 16));
                    i += 6;
                    continue;
                }
                default -> throw new AssertionError("unsupported escape in path: " + path);
            }
            i += 2;
        }
        return out.toString();
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
}
