package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validation of SCHEMA_SPEC.md §12: one value against one node, reporting every problem rather
 * than the first one.
 *
 * <p>The traversal is fixed by §12 and is what makes a problem list reproducible. A kind mismatch
 * stops there, so nothing inside a value of the wrong kind is reported; a scalar reports its format
 * problem and stops, but a string's length, blankness and pattern are each reported independently.
 * Paths are computed as the traversal goes, starting from the path of the value being validated, so
 * a subtree reports paths that begin where the subtree begins and a document assembled in code
 * still gets paths where it has no locations.</p>
 */
final class SchemaValidator {

    private final List<RiffleProblem> problems = new ArrayList<>();

    private SchemaValidator() {}

    /**
     * Validates a document against a schema, appending every problem to {@code sink}.
     *
     * @param root the root node of the schema
     * @param document the value to validate
     * @param sink the list the problems are appended to, in document order
     */
    static void validate(SchemaNode root, RiffleValue document, List<RiffleProblem> sink) {
        validate(root, document, sink, RiffleOptions.UNBOUNDED);
    }

    /**
     * Validates with caller-supplied resource options, rejecting a tree deeper than a configured cap.
     */
    static void validate(SchemaNode root, RiffleValue document, List<RiffleProblem> sink, RiffleOptions options) {
        TreeDepth.check(document, options, "document tree is deeper than the configured limit");
        SchemaValidator validator = new SchemaValidator();
        validator.check(root, document, document.path().orElse(RifflePath.root()));
        sink.addAll(validator.problems);
    }

    /**
     * Returns every problem with one value against one node, used by the load-time checks of
     * SCHEMA_SPEC.md §11 that must know whether a default satisfies its node.
     *
     * @param node the node the value is checked against
     * @param value the value
     * @param base the path the value sits at
     * @return the problems, in the order §12 requires
     */
    static List<RiffleProblem> problems(SchemaNode node, RiffleValue value, RifflePath base) {
        List<RiffleProblem> found = new ArrayList<>();
        SchemaValidator validator = new SchemaValidator();
        validator.check(node, value, base);
        found.addAll(validator.problems);
        return found;
    }

    /** Walks the tree with an explicit stack, preserving the depth-first order of §12. */
    private void check(SchemaNode start, RiffleValue document, RifflePath startPath) {
        Deque<Action> work = new ArrayDeque<>();
        work.push(new Visit(start, document, startPath));
        while (!work.isEmpty()) {
            Action action = work.pop();
            if (action instanceof Problem problem) {
                add(problem.category(), problem.path(), problem.location(), problem.reason());
            } else if (action instanceof ArrayFinish finish) {
                arrayFinish(finish.node(), finish.value(), finish.path());
            } else if (action instanceof MapFinish finish) {
                mapFinish(finish.node(), finish.value(), finish.path());
            } else {
                visit((Visit) action, work);
            }
        }
    }

    private void visit(Visit visit, Deque<Action> work) {
        RiffleValue value = visit.value();
        RifflePath path = visit.path();
        SchemaNode node = visit.node().resolve();
        if (node instanceof SchemaNode.KindUnionNode union) {
            SchemaNode alternative = union.forValue(value);
            if (alternative == null) {
                add(RiffleValidationError.WRONG_KIND, path, value.location().orElse(null), expected(union));
                return;
            }
            push(work, new Visit(alternative, value, path));
            return;
        }
        if (node instanceof SchemaNode.TaggedUnionNode union) {
            SchemaNode selected = taggedUnion(union, value, path);
            if (selected != null) {
                push(work, new Visit(selected, value, path));
            }
            return;
        }
        if (!matches(node, value)) {
            add(RiffleValidationError.WRONG_KIND, path, value.location().orElse(null), expected(node));
            return;
        }
        switch (node.kind()) {
            case STRING -> string((SchemaNode.StringNode) node, value, path);
            case INTEGER -> integer((SchemaNode.IntegerNode) node, value, path);
            case DECIMAL -> decimal((SchemaNode.DecimalNode) node, value, path);
            case BOOLEAN -> booleanValue(value, path);
            case ARRAY -> {
                SchemaNode.ArrayNode array = (SchemaNode.ArrayNode) node;
                List<Action> children = new ArrayList<>();
                if (array.items() != null) {
                    List<RiffleValue> elements = ((RiffleArray) value).values();
                    for (int index = 0; index < elements.size(); index++) {
                        children.add(new Visit(array.items(), elements.get(index), path.index(index)));
                    }
                }
                children.add(new ArrayFinish(array, (RiffleArray) value, path));
                push(work, children);
            }
            case MAP -> {
                SchemaNode.MapNode map = (SchemaNode.MapNode) node;
                mapChildren(map, (RiffleObject) value, path, work);
            }
            case ANY -> {}
            default -> throw new IllegalStateException("the schema node " + node.kind() + " was reached unresolved");
        }
    }

    private static void push(Deque<Action> work, Action action) {
        work.push(action);
    }

    private static void push(Deque<Action> work, List<Action> actions) {
        for (int index = actions.size() - 1; index >= 0; index--) {
            work.push(actions.get(index));
        }
    }

    /** Returns the selected case, or {@code null} when the value itself was the problem. */
    private SchemaNode taggedUnion(SchemaNode.TaggedUnionNode node, RiffleValue value, RifflePath path) {
        if (!(value instanceof RiffleObject map)) {
            add(RiffleValidationError.WRONG_KIND, path, value.location().orElse(null), "expected a map");
            return null;
        }
        RiffleValue discriminator = map.find(node.discriminator()).orElse(null);
        if (discriminator == null) {
            add(
                    RiffleValidationError.MISSING,
                    path.key(node.discriminator()),
                    map.location().orElse(null),
                    "the discriminating member is missing");
            return null;
        }
        RifflePath discriminatorPath = path.key(node.discriminator());
        if (!(discriminator instanceof RiffleString)) {
            add(
                    RiffleValidationError.WRONG_KIND,
                    discriminatorPath,
                    discriminator.location().orElse(null),
                    "expected the discriminating member to be a string");
            return null;
        }
        SchemaNode selected = node.cases().get(SchemaValues.text(discriminator));
        if (selected == null) {
            add(
                    RiffleValidationError.NOT_ALLOWED,
                    discriminatorPath,
                    discriminator.location().orElse(null),
                    "expected one of the cases "
                            + SchemaValues.describeNames(
                                    new ArrayList<>(node.cases().keySet())));
            return null;
        }
        return selected;
    }

    private void string(SchemaNode.StringNode node, RiffleValue value, RifflePath path) {
        String text = SchemaValues.text(value);
        if (node.values() != null) {
            if (!allows(node.kind(), node.values(), value)) {
                add(
                        RiffleValidationError.NOT_ALLOWED,
                        path,
                        value.location().orElse(null),
                        "expected one of the values " + SchemaValues.describeValues(node.values()));
            }
            return;
        }
        int length = text.codePointCount(0, text.length());
        if (node.minLength() != null && length < node.minLength()) {
            add(
                    RiffleValidationError.INVALID_LENGTH,
                    path,
                    value.location().orElse(null),
                    "expected at least " + node.minLength() + " characters");
        }
        if (node.maxLength() != null && length > node.maxLength()) {
            add(
                    RiffleValidationError.INVALID_LENGTH,
                    path,
                    value.location().orElse(null),
                    "expected at most " + node.maxLength() + " characters");
        }
        if (node.notBlank() && isBlank(text)) {
            add(
                    RiffleValidationError.BLANK,
                    path,
                    value.location().orElse(null),
                    "expected at least one character that is not blank");
        }
        if (node.pattern() != null
                && node.pattern().compiled()
                && !node.pattern().regexp().matches(text)) {
            add(
                    RiffleValidationError.PATTERN_MISMATCH,
                    path,
                    value.location().orElse(null),
                    "does not match the pattern `" + node.pattern().text() + "`");
        }
    }

    /** Only the four characters SCHEMA_SPEC.md §5 lists count as blank. */
    private static boolean isBlank(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != '\t' && character != '\n' && character != '\r' && character != ' ') {
                return false;
            }
        }
        return true;
    }

    private void integer(SchemaNode.IntegerNode node, RiffleValue value, RifflePath path) {
        String text = SchemaValues.text(value);
        if (!Conversions.isIntegerText(text)) {
            add(
                    RiffleValidationError.INVALID_FORMAT,
                    path,
                    value.location().orElse(null),
                    Conversions.expectedInteger());
            return;
        }
        Long number = Conversions.parseIntegerText(text);
        if (number == null) {
            add(
                    RiffleValidationError.OUT_OF_RANGE,
                    path,
                    value.location().orElse(null),
                    "outside the range of a signed 64-bit integer");
            return;
        }
        if (node.min() != null && number < node.min()) {
            add(
                    RiffleValidationError.OUT_OF_RANGE,
                    path,
                    value.location().orElse(null),
                    "expected at least " + node.min());
            return;
        }
        if (node.max() != null && number > node.max()) {
            add(
                    RiffleValidationError.OUT_OF_RANGE,
                    path,
                    value.location().orElse(null),
                    "expected at most " + node.max());
            return;
        }
        if (node.values() != null && !allows(node.kind(), node.values(), value)) {
            add(
                    RiffleValidationError.NOT_ALLOWED,
                    path,
                    value.location().orElse(null),
                    "expected one of the values " + SchemaValues.describeValues(node.values()));
        }
    }

    private void decimal(SchemaNode.DecimalNode node, RiffleValue value, RifflePath path) {
        String text = SchemaValues.text(value);
        if (!Conversions.isDecimalText(text)) {
            add(
                    RiffleValidationError.INVALID_FORMAT,
                    path,
                    value.location().orElse(null),
                    Conversions.expectedDecimal());
            return;
        }
        BigDecimal number = Conversions.parseDecimalText(text);
        if (number == null) {
            add(
                    RiffleValidationError.OUT_OF_RANGE,
                    path,
                    value.location().orElse(null),
                    "outside the range of a decimal this host can hold");
            return;
        }
        if (node.min() != null) {
            int comparison = number.compareTo(node.min());
            if (comparison < 0 || (comparison == 0 && node.minExclusive())) {
                add(
                        RiffleValidationError.OUT_OF_RANGE,
                        path,
                        value.location().orElse(null),
                        "expected " + (node.minExclusive() ? "more than " : "at least ")
                                + node.min().toPlainString());
                return;
            }
        }
        if (node.max() != null) {
            int comparison = number.compareTo(node.max());
            if (comparison > 0 || (comparison == 0 && node.maxExclusive())) {
                add(
                        RiffleValidationError.OUT_OF_RANGE,
                        path,
                        value.location().orElse(null),
                        "expected " + (node.maxExclusive() ? "less than " : "at most ")
                                + node.max().toPlainString());
                return;
            }
        }
        if (node.values() != null && !allows(node.kind(), node.values(), value)) {
            add(
                    RiffleValidationError.NOT_ALLOWED,
                    path,
                    value.location().orElse(null),
                    "expected one of the values " + SchemaValues.describeValues(node.values()));
        }
    }

    private void booleanValue(RiffleValue value, RifflePath path) {
        if (!Conversions.isBooleanText(SchemaValues.text(value))) {
            add(
                    RiffleValidationError.INVALID_FORMAT,
                    path,
                    value.location().orElse(null),
                    Conversions.expectedBoolean());
        }
    }

    private void arrayFinish(SchemaNode.ArrayNode node, RiffleArray value, RifflePath path) {
        List<RiffleValue> elements = value.values();
        int size = elements.size();
        if (node.minItems() != null && size < node.minItems()) {
            add(
                    RiffleValidationError.INVALID_SIZE,
                    path,
                    value.location().orElse(null),
                    "expected at least " + node.minItems() + " elements");
        }
        if (node.maxItems() != null && size > node.maxItems()) {
            add(
                    RiffleValidationError.INVALID_SIZE,
                    path,
                    value.location().orElse(null),
                    "expected at most " + node.maxItems() + " elements");
        }
        if (node.unique()) {
            duplicateElements(node.items(), elements, path);
        } else if (node.uniqueKey() != null) {
            duplicateMembers(node.items(), elements, path, node.uniqueKey());
        }
    }

    /**
     * `unique`: the later element of each pair that is equal is reported (§12). Elements are compared
     * per the type of the item node when that node is a scalar, and by the equality of the value
     * model otherwise (§7).
     */
    private void duplicateElements(SchemaNode items, List<RiffleValue> elements, RifflePath path) {
        SchemaNode node = items == null ? null : items.resolve();
        boolean perType = isScalar(node);
        Set<Object> seen = new HashSet<>();
        for (int index = 0; index < elements.size(); index++) {
            RiffleValue element = elements.get(index);
            Object key = perType ? SchemaValues.comparisonKey(node.kind(), element) : element;
            if (!seen.add(hashable(key))) {
                add(
                        RiffleValidationError.DUPLICATE,
                        path.index(index),
                        element.location().orElse(null),
                        "duplicates an earlier element");
            }
        }
    }

    /** `uniqueKey`: the named member of map elements must be distinct, and elements without it take no part. */
    private void duplicateMembers(SchemaNode items, List<RiffleValue> elements, RifflePath path, String member) {
        SchemaNode node = items == null ? null : items.resolve();
        SchemaNode declared =
                node instanceof SchemaNode.MapNode map ? map.keys().get(member) : null;
        Set<Object> seen = new HashSet<>();
        for (int index = 0; index < elements.size(); index++) {
            if (!(elements.get(index) instanceof RiffleObject element)) {
                continue;
            }
            RiffleValue value = element.find(member).orElse(null);
            if (value == null) {
                continue;
            }
            SchemaNode type = declared == null ? null : declared.resolved();
            Object key = type == null
                    ? SchemaValues.comparisonKey(SchemaNode.Kind.ANY, value)
                    : SchemaValues.comparisonKey(type.kind(), value);
            if (!seen.add(hashable(key))) {
                add(
                        RiffleValidationError.DUPLICATE,
                        path.index(index),
                        element.location().orElse(null),
                        "duplicates an earlier element through `" + member + "`");
            }
        }
    }

    private static boolean isScalar(SchemaNode node) {
        if (node == null) {
            return false;
        }
        return switch (node.kind()) {
            case STRING, INTEGER, DECIMAL, BOOLEAN -> true;
            default -> false;
        };
    }

    private void mapChildren(SchemaNode.MapNode node, RiffleObject value, RifflePath path, Deque<Action> work) {
        List<Action> children = new ArrayList<>();
        for (Map.Entry<String, RiffleValue> entry : value.entries().entrySet()) {
            String key = entry.getKey();
            RifflePath memberPath = path.key(key);
            SchemaNode member = node.keys().get(key);
            if (member != null) {
                children.add(new Visit(member, entry.getValue(), memberPath));
                continue;
            }
            if (node.otherKeys() == null) {
                children.add(new Problem(
                        RiffleValidationError.UNKNOWN_KEY,
                        memberPath,
                        value.keyLocation(key),
                        "is not a declared key"));
                continue;
            }
            if (node.keyPattern() != null
                    && node.keyPattern().compiled()
                    && !node.keyPattern().regexp().matches(key)) {
                children.add(new Problem(
                        RiffleValidationError.PATTERN_MISMATCH,
                        memberPath,
                        value.keyLocation(key),
                        "the key does not match the pattern `"
                                + node.keyPattern().text() + "`"));
            }
            children.add(new Visit(node.otherKeys(), entry.getValue(), memberPath));
        }
        children.add(new MapFinish(node, value, path));
        push(work, children);
    }

    private void mapFinish(SchemaNode.MapNode node, RiffleObject value, RifflePath path) {
        for (Map.Entry<String, SchemaNode> entry : node.keys().entrySet()) {
            if (!entry.getValue().optional() && value.find(entry.getKey()).orElse(null) == null) {
                add(
                        RiffleValidationError.MISSING,
                        path.key(entry.getKey()),
                        value.location().orElse(null),
                        "is required");
            }
        }
        int size = value.entries().size();
        if (node.minKeys() != null && size < node.minKeys()) {
            add(
                    RiffleValidationError.INVALID_SIZE,
                    path,
                    value.location().orElse(null),
                    "expected at least " + node.minKeys() + " entries");
        }
        if (node.maxKeys() != null && size > node.maxKeys()) {
            add(
                    RiffleValidationError.INVALID_SIZE,
                    path,
                    value.location().orElse(null),
                    "expected at most " + node.maxKeys() + " entries");
        }
        for (SchemaNode.Rule rule : node.rules()) {
            checkRule(rule, node, value, path);
        }
    }

    private void checkRule(SchemaNode.Rule rule, SchemaNode.MapNode node, RiffleObject value, RifflePath path) {
        switch (rule.kind()) {
            case "exactlyOne" -> presence(rule, value, path, 1, 1);
            case "atMostOne" -> presence(rule, value, path, 0, 1);
            case "allOrNone" -> allOrNone(rule, value, path);
            case "requires" -> requires(rule, value, path);
            case "memberOf" -> memberOf(rule, node, value, path);
            default -> {}
        }
    }

    private void presence(SchemaNode.Rule rule, RiffleObject value, RifflePath path, int atLeast, int atMost) {
        int present = 0;
        for (String name : rule.keys()) {
            if (value.find(name).orElse(null) != null) {
                present++;
            }
        }
        if (present < atLeast || present > atMost) {
            add(
                    RiffleValidationError.RULE_VIOLATION,
                    path,
                    value.location().orElse(null),
                    reason(rule, atLeast, atMost));
        }
    }

    private static String reason(SchemaNode.Rule rule, int atLeast, int atMost) {
        if (atLeast == atMost) {
            return "exactly " + atLeast + " of " + SchemaValues.describeNames(rule.keys()) + " must be present";
        }
        if (atMost == 1) {
            return "at most one of " + SchemaValues.describeNames(rule.keys()) + " may be present";
        }
        return "either all of or none of " + SchemaValues.describeNames(rule.keys()) + " must be present";
    }

    private void allOrNone(SchemaNode.Rule rule, RiffleObject value, RifflePath path) {
        int present = 0;
        for (String name : rule.keys()) {
            if (value.find(name).orElse(null) != null) {
                present++;
            }
        }
        if (present != 0 && present != rule.keys().size()) {
            add(RiffleValidationError.RULE_VIOLATION, path, value.location().orElse(null), reason(rule, 0, 1));
        }
    }

    private void requires(SchemaNode.Rule rule, RiffleObject value, RifflePath path) {
        if (value.find(rule.key()).orElse(null) == null) {
            return;
        }
        List<String> absent = new ArrayList<>();
        for (String name : rule.keys()) {
            if (value.find(name).orElse(null) == null) {
                absent.add(name);
            }
        }
        if (!absent.isEmpty()) {
            add(
                    RiffleValidationError.RULE_VIOLATION,
                    path,
                    value.location().orElse(null),
                    "`" + rule.key() + "` is present, so " + SchemaValues.describeNames(absent) + " must be present");
        }
    }

    private void memberOf(SchemaNode.Rule rule, SchemaNode.MapNode node, RiffleObject value, RifflePath path) {
        RiffleValue subject = value.find(rule.key()).orElse(null);
        RiffleValue array = value.find(rule.array()).orElse(null);
        if (subject == null || !(array instanceof RiffleArray elements)) {
            return;
        }
        SchemaNode member = node.keys().get(rule.key());
        SchemaNode.Kind kind =
                member == null ? SchemaNode.Kind.STRING : member.resolve().kind();
        Object subjectKey = SchemaValues.comparisonKey(kind, subject);
        for (RiffleValue element : elements.values()) {
            if (SchemaValues.equalKeys(subjectKey, SchemaValues.comparisonKey(kind, element))) {
                return;
            }
        }
        add(
                RiffleValidationError.RULE_VIOLATION,
                path.key(rule.key()),
                value.keyLocation(rule.key()),
                "is not one of the values of " + '`' + rule.array() + '`');
    }

    /** Whether one value is equal to any entry of a `values` list, per the node's type. */
    private static boolean allows(SchemaNode.Kind kind, List<RiffleValue> values, RiffleValue value) {
        Object key = SchemaValues.comparisonKey(kind, value);
        for (RiffleValue allowed : values) {
            if (SchemaValues.equalKeys(key, SchemaValues.comparisonKey(kind, allowed))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(SchemaNode node, RiffleValue value) {
        return switch (node.kind()) {
            case MAP, TAGGED_UNION -> value instanceof RiffleObject;
            case ARRAY -> value instanceof RiffleArray;
            case ANY -> true;
            default -> value instanceof RiffleString;
        };
    }

    private static String expected(SchemaNode node) {
        if (node instanceof SchemaNode.KindUnionNode union) {
            Set<String> kinds = new LinkedHashSet<>();
            if (union.string() != null) {
                kinds.add("a string");
            }
            if (union.map() != null) {
                kinds.add("a map");
            }
            if (union.array() != null) {
                kinds.add("an array");
            }
            return "expected " + String.join(", ", kinds);
        }
        return switch (node.kind()) {
            case MAP -> "expected a map";
            case ARRAY -> "expected an array";
            case TAGGED_UNION -> "expected a map";
            default -> "expected a string";
        };
    }

    private void add(RiffleValidationError category, RifflePath path, RiffleLocation location, String reason) {
        problems.add(new RiffleProblem(category, path, location, reason));
    }

    /**
     * Returns a key that a hash set can hold while keeping the equality the node's type means: a
     * decimal compares by mathematical value (§7), which {@code BigDecimal.equals} does not.
     */
    private static Object hashable(Object key) {
        return key instanceof BigDecimal decimal ? new DecimalKey(decimal) : key;
    }

    private sealed interface Action permits Visit, Problem, ArrayFinish, MapFinish {}

    private record Visit(SchemaNode node, RiffleValue value, RifflePath path) implements Action {}

    private record Problem(RiffleValidationError category, RifflePath path, RiffleLocation location, String reason)
            implements Action {}

    private record ArrayFinish(SchemaNode.ArrayNode node, RiffleArray value, RifflePath path) implements Action {}

    private record MapFinish(SchemaNode.MapNode node, RiffleObject value, RifflePath path) implements Action {}

    /** A decimal compared the way SCHEMA_SPEC.md §7 says two decimals are equal. */
    private static final class DecimalKey {

        private final BigDecimal value;

        private DecimalKey(BigDecimal value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DecimalKey that && value.compareTo(that.value) == 0;
        }

        @Override
        public int hashCode() {
            BigDecimal normalized = value.stripTrailingZeros();
            return Objects.hash(normalized.unscaledValue(), normalized.scale());
        }
    }
}
