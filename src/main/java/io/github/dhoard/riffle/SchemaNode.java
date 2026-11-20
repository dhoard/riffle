package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A compiled schema node: the executable form of one node of a schema document.
 *
 * <p>Nodes are built by {@link SchemaCompiler} and are immutable once loading has linked every
 * {@code ref} to its definition. They do not implement value equality; two schemas are separate
 * objects even when they describe the same contract, and a {@code ref} whose definition refers back
 * to it would make structural equality diverge.</p>
 *
 * <p>Each node remembers where it was written in the schema document, so a problem found while
 * loading can point at the text that caused it, and where each of its keywords was written, so a
 * problem about one keyword points at that keyword rather than at the whole node. A schema is
 * loaded by one thread and only read afterwards, so the one field filled in after construction, the
 * target of a reference, needs no synchronisation.</p>
 */
abstract sealed class SchemaNode {

    /** The ten node types of SCHEMA_SPEC.md §4. */
    enum Kind {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        MAP,
        ARRAY,
        ANY,
        REF,
        TAGGED_UNION,
        KIND_UNION
    }

    /** Where one keyword was written in the schema document. */
    record Site(RifflePath path, RiffleLocation location) {}

    /** One entry of a map node's {@code rules} list. */
    record Rule(String kind, List<String> keys, String key, String array, RifflePath path, RiffleLocation location) {}

    /** A regular expression of the schema, kept with the text it was compiled from. */
    record Pattern(String text, IRegexp regexp, String failure) {

        /** Returns whether the text compiled, which a schema with an invalid pattern does not. */
        boolean compiled() {
            return regexp != null;
        }
    }

    /** Where a node sits in the schema, which decides what it may declare (§4). */
    enum Position {
        /** The root node of the schema. */
        ROOT,
        /** A definition's own node, which describes a shape rather than a member. */
        DEFINITION,
        /** The node describing one declared key of a map, the only position a member can be absent at. */
        KEYS_VALUE,
        /** The node describing the keys a map does not declare. */
        OTHER_KEYS,
        /** The node describing every element of an array. */
        ITEMS,
        /** One alternative of a tagged union. */
        CASE,
        /** One alternative of a union of kinds. */
        KIND_ALTERNATIVE
    }

    private final Kind kind;
    private final Position position;
    private final RifflePath path;
    private final RiffleLocation location;
    private final Map<String, Site> sites;
    private final boolean optional;
    private final RiffleValue defaultValue;

    SchemaNode(
            Kind kind,
            Position position,
            RifflePath path,
            RiffleLocation location,
            Map<String, Site> sites,
            RiffleObject node) {
        this.kind = kind;
        this.position = position;
        this.path = path;
        this.location = location;
        this.sites = Collections.unmodifiableMap(new LinkedHashMap<>(sites));
        this.optional = position == Position.KEYS_VALUE
                && SchemaValues.isTrue(node.find("optional").orElse(null));
        this.defaultValue =
                position == Position.KEYS_VALUE ? node.find("default").orElse(null) : null;
    }

    final Kind kind() {
        return kind;
    }

    final Position position() {
        return position;
    }

    /** Returns where the node itself was written in the schema document. */
    final RifflePath path() {
        return path;
    }

    final RiffleLocation location() {
        return location;
    }

    /** Returns where one keyword of this node was written, or {@code null} when it is absent. */
    final Site site(String keyword) {
        return sites.get(keyword);
    }

    /** Returns the keywords this node declares, in document order. */
    final Set<String> keywords() {
        return sites.keySet();
    }

    /** Returns whether this member node declares {@code optional : true} (§4). */
    final boolean optional() {
        return optional;
    }

    /** Returns the value this member node inserts when its key is absent, or {@code null}. */
    final RiffleValue defaultValue() {
        return defaultValue;
    }

    /** Returns whether this node sits where a member can be absent. */
    final boolean isMember() {
        return position == Position.KEYS_VALUE;
    }

    /** The keywords of SCHEMA_SPEC.md §4, so that a keyword a type does not allow is a problem. */
    static final Map<Kind, Set<String>> KEYWORDS = Map.of(
            Kind.STRING, Set.of("minLength", "maxLength", "notBlank", "pattern", "values"),
            Kind.INTEGER, Set.of("min", "max", "values"),
            Kind.DECIMAL, Set.of("min", "max", "minExclusive", "maxExclusive", "values"),
            Kind.BOOLEAN, Set.of(),
            Kind.MAP, Set.of("keys", "otherKeys", "keyPattern", "minKeys", "maxKeys", "rules"),
            Kind.ARRAY, Set.of("items", "minItems", "maxItems", "unique", "uniqueKey"),
            Kind.ANY, Set.of(),
            Kind.REF, Set.of("definition"),
            Kind.TAGGED_UNION, Set.of("discriminator", "cases"),
            Kind.KIND_UNION, Set.of("string", "map", "array"));

    /** The keywords every node may declare, whatever its type. */
    static final Set<String> UNIVERSAL_KEYWORDS =
            Set.of(SchemaLoader.TYPE_KEYWORD, SchemaLoader.DESCRIPTION_KEYWORD, "optional", "default");

    /** Returns the keywords {@code kind} allows, including the ones every node may declare. */
    static Set<String> allowedKeywords(Kind kind) {
        Set<String> allowed = new LinkedHashSet<>(KEYWORDS.get(kind));
        allowed.addAll(UNIVERSAL_KEYWORDS);
        return allowed;
    }

    /**
     * Follows {@code ref} nodes as far as the chain goes. A chain that names a definition which is
     * not declared, or that comes back around to a node it has already visited, stops where it is,
     * which leaves a {@code ref} node for the caller: a ref node constrains nothing, so the schema
     * says nothing further about the value it stands for.
     */
    SchemaNode resolve() {
        SchemaNode node = this;
        Map<SchemaNode, Boolean> visited = null;
        while (node instanceof RefNode reference) {
            SchemaNode target = reference.target();
            if (target == null) {
                return node;
            }
            if (visited == null) {
                visited = new IdentityHashMap<>();
                visited.put(this, Boolean.TRUE);
            }
            if (visited.containsKey(target)) {
                return node;
            }
            visited.put(node, Boolean.TRUE);
            node = target;
        }
        return node;
    }

    /**
     * Returns the node a reference chain ends at, or {@code null} when the chain never ends. A loaded
     * schema has no chain that fails, because SCHEMA_SPEC.md §9 rejects the only cycles that consume
     * no data and §10 reports a name that is not declared; a schema that is still being checked can
     * have both, and its checks then say nothing about what the missing or circular definition would
     * have constrained.
     */
    SchemaNode resolved() {
        SchemaNode node = resolve();
        return node instanceof RefNode ? null : node;
    }

    /**
     * Follows references and unions down to the node that describes the structure of {@code value}.
     * A union with no alternative for the value, or a tagged union whose discriminator is absent or
     * names no case, returns the union itself: the validator reports what is wrong with such a value,
     * and the defaults operation then has nothing to fill.
     */
    SchemaNode select(RiffleValue value) {
        SchemaNode node = resolve();
        while (true) {
            if (node instanceof KindUnionNode union) {
                SchemaNode alternative = union.forValue(value);
                if (alternative == null) {
                    return node;
                }
                node = alternative.resolve();
                continue;
            }
            if (node instanceof TaggedUnionNode union && value instanceof RiffleObject map) {
                RiffleValue discriminator = map.find(union.discriminator()).orElse(null);
                SchemaNode selected =
                        discriminator == null ? null : union.cases().get(SchemaValues.text(discriminator));
                if (selected == null) {
                    return node;
                }
                node = selected.resolve();
                continue;
            }
            return node;
        }
    }

    /** A {@code string} node. */
    static final class StringNode extends SchemaNode {
        private final Long minLength;
        private final Long maxLength;
        private final boolean notBlank;
        private final Pattern pattern;
        private final List<RiffleValue> values;

        StringNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node,
                Long minLength,
                Long maxLength,
                boolean notBlank,
                Pattern pattern,
                List<RiffleValue> values) {
            super(Kind.STRING, position, path, location, sites, node);
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.notBlank = notBlank;
            this.pattern = pattern;
            this.values = values;
        }

        Long minLength() {
            return minLength;
        }

        Long maxLength() {
            return maxLength;
        }

        boolean notBlank() {
            return notBlank;
        }

        Pattern pattern() {
            return pattern;
        }

        List<RiffleValue> values() {
            return values;
        }
    }

    /** An {@code integer} node. */
    static final class IntegerNode extends SchemaNode {
        private final Long min;
        private final Long max;
        private final List<String> unreadableBounds;
        private final List<RiffleValue> values;

        IntegerNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node,
                Long min,
                Long max,
                List<String> unreadableBounds,
                List<RiffleValue> values) {
            super(Kind.INTEGER, position, path, location, sites, node);
            this.min = min;
            this.max = max;
            this.unreadableBounds = unreadableBounds;
            this.values = values;
        }

        Long min() {
            return min;
        }

        Long max() {
            return max;
        }

        /** The bound keywords whose text is integer text this host cannot interpret (§10). */
        List<String> unreadableBounds() {
            return unreadableBounds;
        }

        List<RiffleValue> values() {
            return values;
        }
    }

    /** A {@code decimal} node. */
    static final class DecimalNode extends SchemaNode {
        private final BigDecimal min;
        private final boolean minExclusive;
        private final BigDecimal max;
        private final boolean maxExclusive;
        private final List<String> unreadableBounds;
        private final List<RiffleValue> values;

        DecimalNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node,
                BigDecimal min,
                boolean minExclusive,
                BigDecimal max,
                boolean maxExclusive,
                List<String> unreadableBounds,
                List<RiffleValue> values) {
            super(Kind.DECIMAL, position, path, location, sites, node);
            this.min = min;
            this.minExclusive = minExclusive;
            this.max = max;
            this.maxExclusive = maxExclusive;
            this.unreadableBounds = unreadableBounds;
            this.values = values;
        }

        BigDecimal min() {
            return min;
        }

        boolean minExclusive() {
            return minExclusive;
        }

        BigDecimal max() {
            return max;
        }

        boolean maxExclusive() {
            return maxExclusive;
        }

        /** The bound keywords whose text is decimal text this host cannot interpret (§10). */
        List<String> unreadableBounds() {
            return unreadableBounds;
        }

        List<RiffleValue> values() {
            return values;
        }
    }

    /** A {@code boolean} node, which has no constraint keywords of its own (§4). */
    static final class BooleanNode extends SchemaNode {
        BooleanNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node) {
            super(Kind.BOOLEAN, position, path, location, sites, node);
        }
    }

    /** A {@code map} node. */
    static final class MapNode extends SchemaNode {
        private final LinkedHashMap<String, SchemaNode> keys;
        private final SchemaNode otherKeys;
        private final Pattern keyPattern;
        private final Long minKeys;
        private final Long maxKeys;
        private final List<Rule> rules;

        MapNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node,
                LinkedHashMap<String, SchemaNode> keys,
                SchemaNode otherKeys,
                Pattern keyPattern,
                Long minKeys,
                Long maxKeys,
                List<Rule> rules) {
            super(Kind.MAP, position, path, location, sites, node);
            this.keys = keys;
            this.otherKeys = otherKeys;
            this.keyPattern = keyPattern;
            this.minKeys = minKeys;
            this.maxKeys = maxKeys;
            this.rules = rules;
        }

        LinkedHashMap<String, SchemaNode> keys() {
            return keys;
        }

        SchemaNode otherKeys() {
            return otherKeys;
        }

        Pattern keyPattern() {
            return keyPattern;
        }

        Long minKeys() {
            return minKeys;
        }

        Long maxKeys() {
            return maxKeys;
        }

        List<Rule> rules() {
            return rules;
        }
    }

    /** An {@code array} node. */
    static final class ArrayNode extends SchemaNode {
        private final SchemaNode items;
        private final Long minItems;
        private final Long maxItems;
        private final boolean unique;
        private final String uniqueKey;

        ArrayNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node,
                SchemaNode items,
                Long minItems,
                Long maxItems,
                boolean unique,
                String uniqueKey) {
            super(Kind.ARRAY, position, path, location, sites, node);
            this.items = items;
            this.minItems = minItems;
            this.maxItems = maxItems;
            this.unique = unique;
            this.uniqueKey = uniqueKey;
        }

        SchemaNode items() {
            return items;
        }

        Long minItems() {
            return minItems;
        }

        Long maxItems() {
            return maxItems;
        }

        boolean unique() {
            return unique;
        }

        String uniqueKey() {
            return uniqueKey;
        }
    }

    /** An {@code any} node, which accepts every value. */
    static final class AnyNode extends SchemaNode {
        AnyNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node) {
            super(Kind.ANY, position, path, location, sites, node);
        }
    }

    /** A {@code ref} node; the target is linked once every definition has been compiled. */
    static final class RefNode extends SchemaNode {
        private final String definition;
        private SchemaNode target;

        RefNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node,
                String definition) {
            super(Kind.REF, position, path, location, sites, node);
            this.definition = definition;
        }

        String definition() {
            return definition;
        }

        void link(SchemaNode target) {
            this.target = target;
        }

        SchemaNode target() {
            return target;
        }
    }

    /** A {@code taggedUnion} node. */
    static final class TaggedUnionNode extends SchemaNode {
        private final String discriminator;
        private final LinkedHashMap<String, SchemaNode> cases;

        TaggedUnionNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node,
                String discriminator,
                LinkedHashMap<String, SchemaNode> cases) {
            super(Kind.TAGGED_UNION, position, path, location, sites, node);
            this.discriminator = discriminator;
            this.cases = cases;
        }

        String discriminator() {
            return discriminator;
        }

        LinkedHashMap<String, SchemaNode> cases() {
            return cases;
        }
    }

    /** A {@code kindUnion} node; an alternative that was not written is {@code null}. */
    static final class KindUnionNode extends SchemaNode {
        private final SchemaNode string;
        private final SchemaNode map;
        private final SchemaNode array;

        KindUnionNode(
                Position position,
                RifflePath path,
                RiffleLocation location,
                Map<String, Site> sites,
                RiffleObject node,
                SchemaNode string,
                SchemaNode map,
                SchemaNode array) {
            super(Kind.KIND_UNION, position, path, location, sites, node);
            this.string = string;
            this.map = map;
            this.array = array;
        }

        SchemaNode string() {
            return string;
        }

        SchemaNode map() {
            return map;
        }

        SchemaNode array() {
            return array;
        }

        /** Returns the alternative for the kind of {@code value}, or {@code null} when there is none. */
        SchemaNode forValue(RiffleValue value) {
            if (value instanceof RiffleString) {
                return string;
            }
            if (value instanceof RiffleObject) {
                return map;
            }
            return value instanceof RiffleArray ? array : null;
        }
    }
}
