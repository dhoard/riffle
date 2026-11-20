package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The semantic half of load phase three of SCHEMA_SPEC.md §11: the ordered walk over the compiled
 * schema, then the definition checks.
 *
 * <p>The walk reports, at each node it reaches, the checks §11 lists in order and keeps only the
 * first problem that node reports, then visits the child nodes in the order §11 fixes. A {@code ref}
 * node contributes no children of its own beyond its target, and a definition is walked once, at
 * the first point the walk reaches it, so a schema whose definitions refer to each other is walked
 * a finite number of times.</p>
 *
 * <p>Because the checks resolve references, they run only after every definition has been compiled
 * and linked. A reference whose definition does not exist is reported where the name is written and
 * is then left alone: the walk does not try to second-guess what the missing definition would have
 * constrained.</p>
 */
final class SchemaAnalysis {

    /**
     * The owner of references written inside the root node. It is deliberately not a {@link String}:
     * a definition may itself be named {@code root}, and the two owners must not share a namespace
     * (SCHEMA_SPEC.md §11 checks 9 and 10).
     */
    static final Object ROOT_OWNER = new Object() {
        @Override
        public String toString() {
            return "root";
        }
    };

    private final RiffleObject document;
    private final SchemaNode root;
    private final LinkedHashMap<String, SchemaNode> definitions;
    private final Map<String, SchemaNode.Site> definitionSites;
    private final List<SchemaCompiler.Edge> edges;
    private final List<RiffleProblem> problems = new ArrayList<>();
    private final Set<SchemaNode> walked = Collections.newSetFromMap(new IdentityHashMap<>());
    /** While checking one node, only its first problem is kept (§11); -1 outside a node check. */
    private int nodeCheckStart = -1;

    SchemaAnalysis(
            RiffleObject document,
            SchemaNode root,
            LinkedHashMap<String, SchemaNode> definitions,
            Map<String, SchemaNode.Site> definitionSites,
            List<SchemaCompiler.Edge> edges) {
        this.document = document;
        this.root = root;
        this.definitions = definitions;
        this.definitionSites = definitionSites;
        this.edges = edges;
    }

    /**
     * Runs the semantic checks.
     *
     * @return every problem the checks found, in the order §11 requires
     */
    List<RiffleProblem> analyse() {
        link();
        walk();
        unreachableDefinitions();
        definitionCycles();
        return problems;
    }

    private void link() {
        for (SchemaCompiler.Edge edge : edges) {
            edge.reference().link(definitions.get(edge.target()));
        }
    }

    private void walk() {
        if (order(SchemaLoader.ROOT_KEYWORD) <= order(SchemaLoader.DEFINITIONS_KEYWORD)) {
            walk(root);
        }
        for (SchemaNode definition : definitions.values()) {
            walk(definition);
        }
        if (order(SchemaLoader.ROOT_KEYWORD) > order(SchemaLoader.DEFINITIONS_KEYWORD)) {
            walk(root);
        }
    }

    /** Where an entry of the schema document sits, so that {@code root} and {@code definitions} sort. */
    private int order(String keyword) {
        int index = 0;
        for (String key : document.entries().keySet()) {
            if (key.equals(keyword)) {
                return index;
            }
            index++;
        }
        return Integer.MAX_VALUE;
    }

    private void walk(SchemaNode node) {
        Deque<SchemaNode> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            walkOne(pending.pop(), pending);
        }
    }

    private void walkOne(SchemaNode node, Deque<SchemaNode> pending) {
        if (!walked.add(node)) {
            return;
        }
        nodeCheckStart = problems.size();
        checkKeywords(node);
        checkCombinations(node);
        checkPatterns(node);
        if (node instanceof SchemaNode.TaggedUnionNode union) {
            checkCases(union);
        }
        if (node instanceof SchemaNode.KindUnionNode union) {
            checkAlternatives(union);
        }
        if (node instanceof SchemaNode.RefNode reference && reference.target() == null) {
            add(
                    RiffleValidationError.UNKNOWN_DEFINITION,
                    typeSite(reference),
                    "the definition `" + reference.definition() + "` is not declared");
        }
        if (node.position() == SchemaNode.Position.ROOT) {
            checkRootKind(node);
        }
        checkDefault(node);
        if (node instanceof SchemaNode.MapNode map) {
            checkRules(map);
        }
        nodeCheckStart = -1;
        List<SchemaNode> children = childrenOf(node);
        for (int index = children.size() - 1; index >= 0; index--) {
            pending.push(children.get(index));
        }
    }

    /** Returns a node's children in the document order fixed by §11. */
    private static List<SchemaNode> childrenOf(SchemaNode node) {
        List<SchemaNode> children = new ArrayList<>();
        if (node instanceof SchemaNode.MapNode map) {
            children.addAll(map.keys().values());
            if (map.otherKeys() != null) {
                children.add(map.otherKeys());
            }
        }
        if (node instanceof SchemaNode.ArrayNode array && array.items() != null) {
            children.add(array.items());
        }
        if (node instanceof SchemaNode.TaggedUnionNode union) {
            children.addAll(union.cases().values());
        }
        if (node instanceof SchemaNode.KindUnionNode union) {
            for (SchemaNode alternative : new SchemaNode[] {union.string(), union.map(), union.array()}) {
                if (alternative != null) {
                    children.add(alternative);
                }
            }
        }
        if (node instanceof SchemaNode.RefNode reference && reference.target() != null) {
            children.add(reference.target());
        }
        return children;
    }

    /** A keyword the node type does not allow (§4), which the meta-schema has usually reported first. */
    private void checkKeywords(SchemaNode node) {
        Set<String> allowed = SchemaNode.allowedKeywords(node.kind());
        for (String keyword : node.keywords()) {
            if (!allowed.contains(keyword)) {
                add(
                        RiffleValidationError.INVALID_CONSTRAINT,
                        node.path(),
                        node.location(),
                        "the `" + SchemaLoader.typeName(node.kind()) + "` node does not allow the keyword `" + keyword
                                + "`");
            }
        }
    }

    /** The keyword combinations of SCHEMA_SPEC.md §11 check 1. */
    private void checkCombinations(SchemaNode node) {
        checkUnreadableBounds(node);
        if (node.kind() == SchemaNode.Kind.STRING) {
            SchemaNode.StringNode string = (SchemaNode.StringNode) node;
            checkValuesExclusivity(
                    node,
                    string.values(),
                    string.minLength() != null
                            || string.maxLength() != null
                            || string.notBlank()
                            || string.pattern() != null);
            checkRange(node, string.minLength(), string.maxLength(), false, false);
        } else if (node.kind() == SchemaNode.Kind.INTEGER) {
            SchemaNode.IntegerNode integer = (SchemaNode.IntegerNode) node;
            checkValuesExclusivity(node, integer.values(), integer.min() != null || integer.max() != null);
            checkRange(node, integer.min(), integer.max(), false, false);
        } else if (node.kind() == SchemaNode.Kind.DECIMAL) {
            SchemaNode.DecimalNode decimal = (SchemaNode.DecimalNode) node;
            checkValuesExclusivity(
                    node,
                    decimal.values(),
                    decimal.min() != null || decimal.max() != null || decimal.minExclusive() || decimal.maxExclusive());
            checkDecimalRange(decimal);
            checkExclusiveFlag(node, "minExclusive", decimal.min());
            checkExclusiveFlag(node, "maxExclusive", decimal.max());
        } else if (node.kind() == SchemaNode.Kind.MAP) {
            SchemaNode.MapNode map = (SchemaNode.MapNode) node;
            checkRange(node, map.minKeys(), map.maxKeys(), false, false);
            if (map.keyPattern() != null && map.otherKeys() == null) {
                add(
                        RiffleValidationError.INVALID_CONSTRAINT,
                        node.path(),
                        node.location(),
                        "`keyPattern` needs `otherKeys`, because it constrains only keys `otherKeys` describes");
            }
        } else if (node.kind() == SchemaNode.Kind.ARRAY) {
            SchemaNode.ArrayNode array = (SchemaNode.ArrayNode) node;
            checkRange(node, array.minItems(), array.maxItems(), false, false);
            if (array.unique() && array.uniqueKey() != null) {
                add(
                        RiffleValidationError.INVALID_CONSTRAINT,
                        node.path(),
                        node.location(),
                        "`unique` and `uniqueKey` cannot both be declared by one array node");
            }
        }
        checkMemberKeywords(node);
    }

    /** A bound whose text is of the right shape but too large for this host to compare (§10). */
    private void checkUnreadableBounds(SchemaNode node) {
        List<String> keywords = List.of();
        if (node instanceof SchemaNode.IntegerNode integer) {
            keywords = integer.unreadableBounds();
        } else if (node instanceof SchemaNode.DecimalNode decimal) {
            keywords = decimal.unreadableBounds();
        }
        for (String keyword : keywords) {
            SchemaNode.Site site = node.site(keyword);
            add(
                    RiffleValidationError.OUT_OF_RANGE,
                    site.path(),
                    site.location(),
                    "the bound is outside the range this host can compare");
        }
    }

    private void checkValuesExclusivity(SchemaNode node, List<RiffleValue> values, boolean hasAnotherConstraint) {
        if (values == null) {
            return;
        }
        if (hasAnotherConstraint) {
            add(
                    RiffleValidationError.INVALID_CONSTRAINT,
                    node.path(),
                    node.location(),
                    "`values` cannot be combined with another constraint of the same node");
        }
        checkValuesDistinct(node, values);
    }

    /** The entries of `values` are distinct for the node's type (§4). */
    private void checkValuesDistinct(SchemaNode node, List<RiffleValue> values) {
        List<Object> keys = new ArrayList<>();
        for (RiffleValue value : values) {
            Object key = SchemaValues.comparisonKey(node.kind(), value);
            for (Object other : keys) {
                if (SchemaValues.equalKeys(key, other)) {
                    add(
                            RiffleValidationError.INVALID_CONSTRAINT,
                            node.path(),
                            node.location(),
                            "`values` lists `" + SchemaValues.text(value) + "` more than once for this type");
                    break;
                }
            }
            keys.add(key);
        }
    }

    private void checkRange(SchemaNode node, Long min, Long max, boolean minExclusive, boolean maxExclusive) {
        if (min == null || max == null) {
            return;
        }
        boolean empty = min > max || (min.longValue() == max.longValue() && (minExclusive || maxExclusive));
        if (empty) {
            add(RiffleValidationError.INVALID_CONSTRAINT, node.path(), node.location(), "the declared range is empty");
        }
    }

    private void checkDecimalRange(SchemaNode.DecimalNode node) {
        if (node.min() == null || node.max() == null) {
            return;
        }
        int comparison = node.min().compareTo(node.max());
        if (comparison > 0 || (comparison == 0 && (node.minExclusive() || node.maxExclusive()))) {
            add(RiffleValidationError.INVALID_CONSTRAINT, node.path(), node.location(), "the declared range is empty");
        }
    }

    private void checkExclusiveFlag(SchemaNode node, String keyword, BigDecimal bound) {
        boolean declared = node.site(keyword) != null;
        if (declared && bound == null) {
            add(
                    RiffleValidationError.INVALID_CONSTRAINT,
                    node.path(),
                    node.location(),
                    "`" + keyword + "` needs the bound it makes exclusive");
        }
    }

    /** `optional` and `default` only appear on a member node, and `default` needs `optional` (§4). */
    private void checkMemberKeywords(SchemaNode node) {
        boolean declaresOptional = node.site("optional") != null;
        boolean declaresDefault = node.site("default") != null;
        if (!node.isMember() && (declaresOptional || declaresDefault)) {
            add(
                    RiffleValidationError.INVALID_CONSTRAINT,
                    node.path(),
                    node.location(),
                    "`optional` and `default` belong to a member node, not to a node of this kind");
        }
        if (declaresDefault && node.isMember() && !node.optional()) {
            add(
                    RiffleValidationError.INVALID_CONSTRAINT,
                    node.path(),
                    node.location(),
                    "`default` needs `optional : true` on the same node");
        }
    }

    private void checkPatterns(SchemaNode node) {
        SchemaNode.Pattern pattern = null;
        String keyword = null;
        if (node instanceof SchemaNode.StringNode string) {
            pattern = string.pattern();
            keyword = "pattern";
        } else if (node instanceof SchemaNode.MapNode map) {
            pattern = map.keyPattern();
            keyword = "keyPattern";
        }
        if (pattern == null || pattern.compiled()) {
            return;
        }
        SchemaNode.Site site = node.site(keyword);
        add(
                RiffleValidationError.INVALID_PATTERN,
                site.path(),
                site.location(),
                "the pattern is not a valid I-Regexp: " + pattern.failure());
    }

    /** Each case resolves to a map that declares the discriminator as a required string member (§9). */
    private void checkCases(SchemaNode.TaggedUnionNode union) {
        for (Map.Entry<String, SchemaNode> entry : union.cases().entrySet()) {
            SchemaNode caseNode = entry.getValue();
            if (caseNode instanceof SchemaNode.RefNode reference && reference.target() == null) {
                continue;
            }
            SchemaNode resolved = caseNode.resolved();
            if (resolved == null) {
                continue;
            }
            if (!(resolved instanceof SchemaNode.MapNode map)) {
                add(
                        RiffleValidationError.INVALID_CONSTRAINT,
                        caseNode.path(),
                        caseNode.location(),
                        "the case `"
                                + entry.getKey() + "` must describe a map that declares the discriminator `"
                                + union.discriminator() + "`");
                continue;
            }
            SchemaNode discriminator = map.keys().get(union.discriminator());
            if (discriminator == null) {
                add(
                        RiffleValidationError.INVALID_CONSTRAINT,
                        caseNode.path(),
                        caseNode.location(),
                        "the case `" + entry.getKey() + "` does not declare the discriminator `" + union.discriminator()
                                + "`");
            } else if (discriminator.optional()) {
                add(
                        RiffleValidationError.INVALID_CONSTRAINT,
                        caseNode.path(),
                        caseNode.location(),
                        "the case `" + entry.getKey() + "` declares the discriminator `" + union.discriminator()
                                + "` as optional, so it cannot select the case");
            } else if (discriminator.resolve().kind() != SchemaNode.Kind.STRING) {
                add(
                        RiffleValidationError.INVALID_CONSTRAINT,
                        caseNode.path(),
                        caseNode.location(),
                        "the discriminator `"
                                + union.discriminator() + "` of the case `" + entry.getKey()
                                + "` must be described by a `string` node");
            }
        }
    }

    /** A kind union declares at least two alternatives, each of the kind §9 requires. */
    private void checkAlternatives(SchemaNode.KindUnionNode union) {
        Map<String, SchemaNode> declared = new LinkedHashMap<>();
        if (union.string() != null) {
            declared.put("string", union.string());
        }
        if (union.map() != null) {
            declared.put("map", union.map());
        }
        if (union.array() != null) {
            declared.put("array", union.array());
        }
        if (declared.size() < 2) {
            add(
                    RiffleValidationError.INVALID_CONSTRAINT,
                    union.path(),
                    union.location(),
                    "`kindUnion` needs at least two of `string`, `map` and `array`");
        }
        for (Map.Entry<String, SchemaNode> entry : declared.entrySet()) {
            SchemaNode alternative = entry.getValue();
            if (alternative instanceof SchemaNode.RefNode reference && reference.target() == null) {
                continue;
            }
            SchemaNode resolved = alternative.resolved();
            if (resolved != null && !fits(entry.getKey(), resolved)) {
                add(
                        RiffleValidationError.INVALID_CONSTRAINT,
                        alternative.path(),
                        alternative.location(),
                        "the `" + entry.getKey() + "` alternative of `kindUnion` must describe "
                                + description(entry.getKey()));
            }
        }
    }

    private static boolean fits(String alternative, SchemaNode node) {
        SchemaNode.Kind kind = node.kind();
        return switch (alternative) {
            case "string" ->
                kind == SchemaNode.Kind.STRING
                        || kind == SchemaNode.Kind.INTEGER
                        || kind == SchemaNode.Kind.DECIMAL
                        || kind == SchemaNode.Kind.BOOLEAN;
            case "map" -> kind == SchemaNode.Kind.MAP || kind == SchemaNode.Kind.TAGGED_UNION;
            default -> kind == SchemaNode.Kind.ARRAY;
        };
    }

    private static String description(String alternative) {
        return switch (alternative) {
            case "string" -> "a string, which includes the integer, decimal and boolean interpretations";
            case "map" -> "a map or a tagged union";
            default -> "an array";
        };
    }

    /** The root resolves to a map or a tagged union, because the root of a document is a map (§9). */
    private void checkRootKind(SchemaNode node) {
        if (node instanceof SchemaNode.RefNode reference && reference.target() == null) {
            return;
        }
        SchemaNode resolved = node.resolved();
        if (resolved == null) {
            return;
        }
        SchemaNode.Kind kind = resolved.kind();
        if (kind != SchemaNode.Kind.MAP && kind != SchemaNode.Kind.TAGGED_UNION) {
            add(
                    RiffleValidationError.INVALID_ROOT,
                    node.path(),
                    node.location(),
                    "the root of a document is a map, so `" + SchemaLoader.typeName(kind)
                            + "` cannot be the root node");
        }
    }

    /** A member default satisfies the node it belongs to (§4, §11 check 7). */
    private void checkDefault(SchemaNode node) {
        if (node.defaultValue() == null) {
            return;
        }
        if (node instanceof SchemaNode.RefNode reference && reference.target() == null) {
            return;
        }
        SchemaNode.Site site = node.site("default");
        SchemaNode member = node.resolved();
        if (member == null) {
            return;
        }
        List<RiffleProblem> found = SchemaValidator.problems(member, node.defaultValue(), site.path());
        if (!found.isEmpty()) {
            add(
                    RiffleValidationError.INVALID_DEFAULT,
                    site.path(),
                    site.location(),
                    "the default does not satisfy the node: " + found.get(0).reason());
        }
    }

    /** Each rule is well-formed under SCHEMA_SPEC.md §8. */
    private void checkRules(SchemaNode.MapNode map) {
        for (SchemaNode.Rule rule : map.rules()) {
            switch (rule.kind()) {
                case "exactlyOne", "atMostOne", "allOrNone" -> checkPresenceRule(map, rule, true);
                case "requires" -> checkRequires(map, rule);
                case "memberOf" -> checkMemberOf(map, rule);
                default -> report(rule, "the rule `" + rule.kind() + "` is not one of the rules of §8");
            }
        }
    }

    private void checkPresenceRule(SchemaNode.MapNode map, SchemaNode.Rule rule, boolean atLeastTwo) {
        if (rule.keys().isEmpty()) {
            report(rule, "`" + rule.kind() + "` names no keys");
            return;
        }
        if (atLeastTwo && rule.keys().size() < 2) {
            report(rule, "`" + rule.kind() + "` names fewer than two keys");
        }
        Set<String> distinct = new HashSet<>();
        for (String name : rule.keys()) {
            if (!distinct.add(name)) {
                report(rule, "`" + rule.kind() + "` names `" + name + "` more than once");
            }
            checkOptionalMember(map, rule, name);
        }
    }

    private void checkRequires(SchemaNode.MapNode map, SchemaNode.Rule rule) {
        if (rule.key() == null) {
            report(rule, "`requires` names no `key`");
        } else if (!map.keys().containsKey(rule.key())) {
            report(rule, "`requires` names `" + rule.key() + "`, which is not a declared key");
        }
        if (rule.keys().isEmpty()) {
            report(rule, "`requires` names no keys that the key brings with it");
        }
        for (String name : rule.keys()) {
            checkOptionalMember(map, rule, name);
        }
    }

    private void checkMemberOf(SchemaNode.MapNode map, SchemaNode.Rule rule) {
        if (rule.key() == null || rule.array() == null) {
            report(rule, "`memberOf` needs both a `key` and an `array`");
            return;
        }
        SchemaNode keyMember = map.keys().get(rule.key());
        SchemaNode arrayMember = map.keys().get(rule.array());
        if (keyMember == null) {
            report(rule, "`memberOf` names `" + rule.key() + "`, which is not a declared key");
        }
        if (arrayMember == null) {
            report(rule, "`memberOf` names `" + rule.array() + "`, which is not a declared key");
        }
        if (keyMember == null || arrayMember == null) {
            return;
        }
        SchemaNode.Kind keyKind = resolvedKind(keyMember);
        SchemaNode arrayNode = arrayMember.resolved();
        SchemaNode items =
                arrayNode.kind() == SchemaNode.Kind.ARRAY ? ((SchemaNode.ArrayNode) arrayNode).items() : null;
        if (items == null || !isScalar(keyKind) || resolvedKind(items) != keyKind) {
            report(rule, "`memberOf` needs a scalar `key` and an `array` whose elements have that same type");
        }
    }

    private void checkOptionalMember(SchemaNode.MapNode map, SchemaNode.Rule rule, String name) {
        SchemaNode member = map.keys().get(name);
        if (member == null) {
            report(rule, "the rule names `" + name + "`, which is not a declared key");
        } else if (!member.optional()) {
            report(rule, "the rule names `" + name + "`, which is not optional and so is always present");
        }
    }

    private static SchemaNode.Kind resolvedKind(SchemaNode node) {
        SchemaNode resolved = node.resolved();
        return resolved == null ? SchemaNode.Kind.REF : resolved.kind();
    }

    private static boolean isScalar(SchemaNode.Kind kind) {
        return kind == SchemaNode.Kind.STRING
                || kind == SchemaNode.Kind.INTEGER
                || kind == SchemaNode.Kind.DECIMAL
                || kind == SchemaNode.Kind.BOOLEAN;
    }

    private void report(SchemaNode.Rule rule, String reason) {
        add(RiffleValidationError.INVALID_RULE, rule.path(), rule.location(), reason);
    }

    /** Every definition that the root cannot reach through `ref` nodes (§9). */
    private void unreachableDefinitions() {
        Set<Object> reachable = new LinkedHashSet<>();
        reachable.add(ROOT_OWNER);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (SchemaCompiler.Edge edge : edges) {
                if (reachable.contains(edge.owner()) && reachable.add(edge.target())) {
                    grew = true;
                }
            }
        }
        for (Map.Entry<String, SchemaNode.Site> entry : definitionSites.entrySet()) {
            if (!reachable.contains(entry.getKey())) {
                add(
                        RiffleValidationError.UNUSED_DEFINITION,
                        entry.getValue().path(),
                        entry.getValue().location(),
                        "the definition `" + entry.getKey() + "` is never reached from the root");
            }
        }
    }

    /** Every cycle of references that consumes no data, reported once per cycle (§9). */
    private void definitionCycles() {
        Map<Object, Set<String>> unproductive = new LinkedHashMap<>();
        for (SchemaCompiler.Edge edge : edges) {
            if (!edge.productive()) {
                unproductive
                        .computeIfAbsent(edge.owner(), ignored -> new LinkedHashSet<>())
                        .add(edge.target());
            }
        }
        Map<Object, Set<String>> reach = transitiveClosure(unproductive);
        Set<String> reported = new HashSet<>();
        for (String name : definitions.keySet()) {
            if (reported.contains(name) || !reach.getOrDefault(name, Set.of()).contains(name)) {
                continue;
            }
            Set<String> cycle = new LinkedHashSet<>();
            cycle.add(name);
            for (String other : definitions.keySet()) {
                if (reach.getOrDefault(name, Set.of()).contains(other)
                        && reach.getOrDefault(other, Set.of()).contains(name)) {
                    cycle.add(other);
                }
            }
            reported.addAll(cycle);
            for (SchemaCompiler.Edge edge : edges) {
                if (cycle.contains(edge.owner()) && cycle.contains(edge.target())) {
                    SchemaNode.Site site = typeSite(edge.reference());
                    add(
                            RiffleValidationError.CIRCULAR_DEFINITION,
                            site.path(),
                            site.location(),
                            "the definition `"
                                    + edge.owner() + "` refers to `" + edge.target()
                                    + "` without consuming any data, so the"
                                    + " reference never ends");
                    break;
                }
            }
        }
    }

    private static Map<Object, Set<String>> transitiveClosure(Map<Object, Set<String>> edges) {
        Map<Object, Set<String>> reach = new LinkedHashMap<>();
        for (Map.Entry<Object, Set<String>> entry : edges.entrySet()) {
            reach.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Set<String> targets : reach.values()) {
                Set<String> additions = new LinkedHashSet<>();
                for (String target : targets) {
                    additions.addAll(reach.getOrDefault(target, Set.of()));
                }
                if (!targets.containsAll(additions)) {
                    targets.addAll(additions);
                    grew = true;
                }
            }
        }
        return reach;
    }

    /** Where the name of a referenced definition is written, which is where §10 reports it. */
    private static SchemaNode.Site typeSite(SchemaNode.RefNode reference) {
        SchemaNode.Site site = reference.site(SchemaLoader.TYPE_KEYWORD);
        return site == null ? new SchemaNode.Site(reference.path(), reference.location()) : site;
    }

    private void add(RiffleValidationError category, RifflePath path, RiffleLocation location, String reason) {
        if (nodeCheckStart >= 0 && problems.size() > nodeCheckStart) {
            return;
        }
        problems.add(new RiffleProblem(category, path, location, reason));
    }

    private void add(RiffleValidationError category, SchemaNode.Site site, String reason) {
        add(category, site.path(), site.location(), reason);
    }
}
