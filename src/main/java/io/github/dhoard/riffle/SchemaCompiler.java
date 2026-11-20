package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles the nodes of a schema document into the form {@link SchemaValidator} walks.
 *
 * <p>Compilation runs after phase two has checked the document against the meta-schema, so a
 * structural surprise here is an implementation fault rather than user input. Children are compiled
 * with an explicit stack, so a deeply nested schema document needs no Java stack depth.</p>
 *
 * <p>Every reference is recorded with the definition or root that contains it and whether the
 * position consumes one level of data. {@link SchemaAnalysis} uses those edges to link references
 * and to decide whether a cycle can terminate (§9).</p>
 */
final class SchemaCompiler {

    /** One reference edge, as {@link SchemaAnalysis} needs it. */
    record Edge(Object owner, String target, boolean productive, SchemaNode.RefNode reference) {}

    private static final String OTHER_KEYS = "\u0000otherKeys";
    private static final String ITEMS = "\u0000items";

    private final RiffleObject document;
    private final LinkedHashMap<String, SchemaNode> definitions = new LinkedHashMap<>();
    private final Map<String, SchemaNode.Site> definitionSites = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    SchemaCompiler(RiffleObject document) {
        this.document = document;
    }

    /** Compiles definitions first, then the root, each in document order. */
    SchemaNode compile() {
        RifflePath definitionsPath = SchemaLoader.definitionsPath();
        for (Map.Entry<String, RiffleValue> entry :
                mapEntries(document.find(SchemaLoader.DEFINITIONS_KEYWORD).orElse(null))) {
            RifflePath nodePath = definitionsPath.key(entry.getKey());
            definitions.put(
                    entry.getKey(),
                    compileOne(entry.getValue(), nodePath, SchemaNode.Position.DEFINITION, entry.getKey()));
            definitionSites.put(
                    entry.getKey(),
                    new SchemaNode.Site(nodePath, entry.getValue().location().orElse(null)));
        }
        return compileOne(
                document.find(SchemaLoader.ROOT_KEYWORD).orElse(null),
                SchemaLoader.rootPath().key(SchemaLoader.ROOT_KEYWORD),
                SchemaNode.Position.ROOT,
                SchemaAnalysis.ROOT_OWNER);
    }

    LinkedHashMap<String, SchemaNode> definitions() {
        return definitions;
    }

    Map<String, SchemaNode.Site> definitionSites() {
        return definitionSites;
    }

    List<Edge> edges() {
        return edges;
    }

    private SchemaNode compileOne(RiffleValue value, RifflePath path, SchemaNode.Position position, Object owner) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(value, path, position, owner, null, null));
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.result != null) {
                stack.pop();
                if (frame.parent == null) {
                    return frame.result;
                }
                if (frame.name == null) {
                    frame.parent.single = frame.result;
                } else {
                    frame.parent.compiled.put(frame.name, frame.result);
                }
                continue;
            }
            if (!frame.expanded) {
                frame.expanded = true;
                if (!(frame.value instanceof RiffleObject node)) {
                    throw new IllegalStateException("the schema document holds something other than a node at " + path);
                }
                frame.node = node;
                frame.location = node.location().orElse(null);
                frame.sites = sites(node, frame.path);
                frame.type = text(node, SchemaLoader.TYPE_KEYWORD);
                frame.prepareChildren();
                if (frame.children.isEmpty()) {
                    frame.result = frame.build();
                    continue;
                }
                for (int i = frame.children.size() - 1; i >= 0; i--) {
                    Child child = frame.children.get(i);
                    stack.push(new Frame(child.value(), child.path(), child.position(), owner, frame, child.name()));
                }
                continue;
            }
            frame.result = frame.build();
        }
        throw new IllegalStateException("the compiler's frame was lost at " + path);
    }

    /** One schema node being compiled, with the children it is waiting for. */
    private final class Frame {
        private final RiffleValue value;
        private final RifflePath path;
        private final SchemaNode.Position position;
        private final Object owner;
        private final Frame parent;
        private final String name;
        private final List<Child> children = new ArrayList<>();
        private final Map<String, SchemaNode> compiled = new LinkedHashMap<>();
        private RiffleObject node;
        private RiffleLocation location;
        private Map<String, SchemaNode.Site> sites;
        private String type;
        private SchemaNode single;
        private SchemaNode result;
        private boolean expanded;

        private Frame(
                RiffleValue value,
                RifflePath path,
                SchemaNode.Position position,
                Object owner,
                Frame parent,
                String name) {
            this.value = value;
            this.path = path;
            this.position = position;
            this.owner = owner;
            this.parent = parent;
            this.name = name;
        }

        private void add(RiffleValue child, RifflePath childPath, SchemaNode.Position childPosition, String childName) {
            children.add(new Child(child, childPath, childPosition, childName));
        }

        private void prepareChildren() {
            switch (type) {
                case SchemaLoader.TYPE_MAP -> {
                    for (Map.Entry<String, RiffleValue> entry :
                            mapEntries(node.find("keys").orElse(null))) {
                        add(
                                entry.getValue(),
                                path.key("keys").key(entry.getKey()),
                                SchemaNode.Position.KEYS_VALUE,
                                entry.getKey());
                    }
                    RiffleValue other = node.find("otherKeys").orElse(null);
                    if (other != null) {
                        add(other, path.key("otherKeys"), SchemaNode.Position.OTHER_KEYS, OTHER_KEYS);
                    }
                }
                case SchemaLoader.TYPE_ARRAY -> {
                    RiffleValue items = node.find("items").orElse(null);
                    if (items != null) {
                        add(items, path.key("items"), SchemaNode.Position.ITEMS, ITEMS);
                    }
                }
                case SchemaLoader.TYPE_TAGGED_UNION -> {
                    for (Map.Entry<String, RiffleValue> entry :
                            mapEntries(node.find("cases").orElse(null))) {
                        add(
                                entry.getValue(),
                                path.key("cases").key(entry.getKey()),
                                SchemaNode.Position.CASE,
                                entry.getKey());
                    }
                }
                case SchemaLoader.TYPE_KIND_UNION -> {
                    for (String name : List.of("string", "map", "array")) {
                        RiffleValue alternative = node.find(name).orElse(null);
                        if (alternative != null) {
                            add(alternative, path.key(name), SchemaNode.Position.KIND_ALTERNATIVE, name);
                        }
                    }
                }
                default -> {}
            }
        }

        private SchemaNode build() {
            return switch (type) {
                case SchemaLoader.TYPE_STRING ->
                    new SchemaNode.StringNode(
                            position,
                            path,
                            location,
                            sites,
                            node,
                            count(node, "minLength"),
                            count(node, "maxLength"),
                            SchemaValues.isTrue(node.find("notBlank").orElse(null)),
                            pattern(node, "pattern"),
                            values(node));
                case SchemaLoader.TYPE_INTEGER -> {
                    List<String> unreadable = new ArrayList<>();
                    yield new SchemaNode.IntegerNode(
                            position,
                            path,
                            location,
                            sites,
                            node,
                            integerBound(node, "min", unreadable),
                            integerBound(node, "max", unreadable),
                            unreadable,
                            values(node));
                }
                case SchemaLoader.TYPE_DECIMAL -> {
                    List<String> unreadable = new ArrayList<>();
                    yield new SchemaNode.DecimalNode(
                            position,
                            path,
                            location,
                            sites,
                            node,
                            decimalBound(node, "min", unreadable),
                            SchemaValues.isTrue(node.find("minExclusive").orElse(null)),
                            decimalBound(node, "max", unreadable),
                            SchemaValues.isTrue(node.find("maxExclusive").orElse(null)),
                            unreadable,
                            values(node));
                }
                case SchemaLoader.TYPE_BOOLEAN -> new SchemaNode.BooleanNode(position, path, location, sites, node);
                case SchemaLoader.TYPE_MAP ->
                    new SchemaNode.MapNode(
                            position,
                            path,
                            location,
                            sites,
                            node,
                            members(),
                            compiled.get(OTHER_KEYS),
                            pattern(node, "keyPattern"),
                            count(node, "minKeys"),
                            count(node, "maxKeys"),
                            rules());
                case SchemaLoader.TYPE_ARRAY ->
                    new SchemaNode.ArrayNode(
                            position,
                            path,
                            location,
                            sites,
                            node,
                            compiled.get(ITEMS),
                            count(node, "minItems"),
                            count(node, "maxItems"),
                            SchemaValues.isTrue(node.find("unique").orElse(null)),
                            textOrNull(node, "uniqueKey"));
                case SchemaLoader.TYPE_ANY -> new SchemaNode.AnyNode(position, path, location, sites, node);
                case SchemaLoader.TYPE_REF -> reference();
                case SchemaLoader.TYPE_TAGGED_UNION ->
                    new SchemaNode.TaggedUnionNode(
                            position, path, location, sites, node, text(node, "discriminator"), cases());
                case SchemaLoader.TYPE_KIND_UNION ->
                    new SchemaNode.KindUnionNode(
                            position,
                            path,
                            location,
                            sites,
                            node,
                            compiled.get("string"),
                            compiled.get("map"),
                            compiled.get("array"));
                default ->
                    throw new IllegalStateException("the schema document declares the unknown type `" + type + "`");
            };
        }

        private LinkedHashMap<String, SchemaNode> members() {
            LinkedHashMap<String, SchemaNode> members = new LinkedHashMap<>();
            for (Map.Entry<String, RiffleValue> entry :
                    mapEntries(node.find("keys").orElse(null))) {
                members.put(entry.getKey(), compiled.get(entry.getKey()));
            }
            return members;
        }

        private LinkedHashMap<String, SchemaNode> cases() {
            LinkedHashMap<String, SchemaNode> cases = new LinkedHashMap<>();
            for (Map.Entry<String, RiffleValue> entry :
                    mapEntries(node.find("cases").orElse(null))) {
                cases.put(entry.getKey(), compiled.get(entry.getKey()));
            }
            return cases;
        }

        private SchemaNode reference() {
            String definition = text(node, "definition");
            SchemaNode.RefNode reference = new SchemaNode.RefNode(position, path, location, sites, node, definition);
            edges.add(new Edge(
                    owner,
                    definition,
                    position == SchemaNode.Position.KEYS_VALUE
                            || position == SchemaNode.Position.OTHER_KEYS
                            || position == SchemaNode.Position.ITEMS,
                    reference));
            return reference;
        }

        private List<SchemaNode.Rule> rules() {
            List<SchemaNode.Rule> rules = new ArrayList<>();
            RiffleValue rulesValue = node.find("rules").orElse(null);
            if (rulesValue != null) {
                int index = 0;
                for (RiffleValue rule : arrayValues(rulesValue)) {
                    RifflePath rulePath = path.key("rules").index(index);
                    if (!(rule instanceof RiffleObject ruleMap)) {
                        throw new IllegalStateException(
                                "the schema document holds something other than a rule at " + rulePath);
                    }
                    rules.add(new SchemaNode.Rule(
                            text(ruleMap, "rule"),
                            names(ruleMap, "keys"),
                            textOrNull(ruleMap, "key"),
                            textOrNull(ruleMap, "array"),
                            rulePath,
                            rule.location().orElse(null)));
                    index++;
                }
            }
            return rules;
        }
    }

    private record Child(RiffleValue value, RifflePath path, SchemaNode.Position position, String name) {}

    /** Every keyword the node declares, and where each one's value was written. */
    private static Map<String, SchemaNode.Site> sites(RiffleObject node, RifflePath path) {
        Map<String, SchemaNode.Site> sites = new LinkedHashMap<>();
        for (Map.Entry<String, RiffleValue> entry : node.entries().entrySet()) {
            sites.put(
                    entry.getKey(),
                    new SchemaNode.Site(
                            path.key(entry.getKey()),
                            entry.getValue().location().orElse(null)));
        }
        return sites;
    }

    /** A pattern keyword, compiled where the text allows it and kept with its text either way. */
    private static SchemaNode.Pattern pattern(RiffleObject node, String keyword) {
        String text = textOrNull(node, keyword);
        if (text == null) {
            return null;
        }
        try {
            return new SchemaNode.Pattern(text, IRegexp.compile(text), null);
        } catch (IllegalArgumentException e) {
            return new SchemaNode.Pattern(text, null, e.getMessage());
        }
    }

    private static List<Map.Entry<String, RiffleValue>> mapEntries(RiffleValue value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof RiffleObject object) {
            return new ArrayList<>(object.entries().entrySet());
        }
        throw new IllegalStateException("the schema document holds something other than a map");
    }

    private static List<RiffleValue> arrayValues(RiffleValue value) {
        if (value instanceof RiffleArray array) {
            return array.values();
        }
        throw new IllegalStateException("the schema document holds something other than an array");
    }

    private static List<RiffleValue> values(RiffleObject node) {
        return node.find("values").orElse(null) == null
                ? null
                : arrayValues(node.find("values").orElse(null));
    }

    private static List<String> names(RiffleObject node, String keyword) {
        if (node.find(keyword).orElse(null) == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (RiffleValue name : arrayValues(node.find(keyword).orElse(null))) {
            names.add(SchemaValues.text(name));
        }
        return names;
    }

    private static String text(RiffleObject node, String keyword) {
        String value = textOrNull(node, keyword);
        if (value == null) {
            throw new IllegalStateException("the schema document omits the keyword `" + keyword + "`");
        }
        return value;
    }

    private static String textOrNull(RiffleObject node, String keyword) {
        RiffleValue value = node.find(keyword).orElse(null);
        if (value == null) {
            return null;
        }
        if (value instanceof RiffleObject || value instanceof RiffleArray) {
            throw new IllegalStateException(
                    "the schema document holds something other than text for the keyword `" + keyword + "`");
        }
        return SchemaValues.text(value);
    }

    /** A counting keyword, which the meta-schema has already confined to a countable integer. */
    private static Long count(RiffleObject node, String keyword) {
        RiffleValue value = node.find(keyword).orElse(null);
        if (value == null) {
            return null;
        }
        if (!Conversions.isIntegerText(SchemaValues.text(value))) {
            throw new IllegalStateException(
                    "the schema document holds something other than a count for the keyword `" + keyword + "`");
        }
        Long number = Conversions.parseIntegerText(SchemaValues.text(value));
        if (number == null) {
            throw new IllegalStateException(
                    "the schema document holds a count this host cannot compare for the keyword `" + keyword + "`");
        }
        return number;
    }

    private static Long integerBound(RiffleObject node, String keyword, List<String> unreadable) {
        RiffleValue value = node.find(keyword).orElse(null);
        if (value == null) {
            return null;
        }
        Long number = Conversions.isIntegerText(SchemaValues.text(value))
                ? Conversions.parseIntegerText(SchemaValues.text(value))
                : null;
        if (number == null) {
            unreadable.add(keyword);
        }
        return number;
    }

    private static BigDecimal decimalBound(RiffleObject node, String keyword, List<String> unreadable) {
        RiffleValue value = node.find(keyword).orElse(null);
        if (value == null) {
            return null;
        }
        BigDecimal number = Conversions.isDecimalText(SchemaValues.text(value))
                ? Conversions.parseDecimalText(SchemaValues.text(value))
                : null;
        if (number == null) {
            unreadable.add(keyword);
        }
        return number;
    }
}
