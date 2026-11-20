package io.github.dhoard.riffle;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A loaded schema: the contract of SCHEMA_SPEC.md that a data document is checked against.
 *
 * <p>A schema is loaded once from a schema document and then used any number of times, from any
 * thread. Loading reports every problem with the schema document at once, in the order the
 * specification fixes, so a schema with three mistakes yields three problems rather than a
 * dialogue. Validation behaves the same way about a data document: it reports every violation and
 * never inserts, reorders or coerces anything.</p>
 *
 * <p>Like the values it checks, a schema says what is wrong without repeating what it found. No
 * string, key, or scalar of a data document appears in a category, a path beyond the keys it names,
 * or a reason, because configuration files hold secrets.</p>
 *
 * @see <a href="https://dhoard.github.io/riffle/spec/SCHEMA_SPEC.html">SCHEMA_SPEC.md</a>
 */
public final class RiffleSchema {

    private final SchemaNode root;

    RiffleSchema(SchemaNode root) {
        this.root = root;
    }

    /**
     * Loads a schema from schema text.
     *
     * @param schema the schema document
     * @return the loaded schema
     * @throws RiffleParseException if the schema text is not a Riffle document
     * @throws RiffleSchemaException if the schema document is not a usable schema, carrying every
     *     problem the load found
     */
    public static RiffleSchema parse(String schema) {
        return parse(schema, RiffleOptions.UNBOUNDED);
    }

    /**
     * Loads a schema from text with caller-supplied resource options.
     *
     * @param schema the schema document
     * @param options the resource options
     * @return the loaded schema
     * @throws RiffleParseException if the schema text is not a Riffle document, or exceeds a cap
     * @throws RiffleSchemaException if the schema document is not a usable schema
     */
    public static RiffleSchema parse(String schema, RiffleOptions options) {
        return SchemaLoader.load(Riffle.parse(schema, options));
    }

    /**
     * Loads a schema from a reader over schema text.
     *
     * @param reader the reader to consume
     * @return the loaded schema
     * @throws IOException if the reader fails
     * @throws RiffleParseException if the schema text is not a Riffle document
     * @throws RiffleSchemaException if the schema document is not a usable schema
     */
    public static RiffleSchema parse(Reader reader) throws IOException {
        return parse(reader, RiffleOptions.UNBOUNDED);
    }

    /**
     * Loads a schema from a reader with caller-supplied resource options.
     *
     * @param reader the reader to consume
     * @param options the resource options
     * @return the loaded schema
     * @throws IOException if the reader fails
     * @throws RiffleParseException if the schema text is invalid, or exceeds a cap
     * @throws RiffleSchemaException if the schema document is not a usable schema
     */
    public static RiffleSchema parse(Reader reader, RiffleOptions options) throws IOException {
        return SchemaLoader.load(Riffle.parse(reader, options));
    }

    /**
     * Loads a schema from a file of schema text.
     *
     * @param file the file to read
     * @return the loaded schema
     * @throws IOException if the file cannot be read
     * @throws RiffleParseException if the schema text is not a Riffle document
     * @throws RiffleSchemaException if the schema document is not a usable schema
     */
    public static RiffleSchema parse(Path file) throws IOException {
        return parse(file, RiffleOptions.UNBOUNDED);
    }

    /**
     * Loads a schema from a file with caller-supplied resource options.
     *
     * @param file the file to read
     * @param options the resource options
     * @return the loaded schema
     * @throws IOException if the file cannot be read
     * @throws RiffleParseException if the schema text is invalid, or exceeds a cap
     * @throws RiffleSchemaException if the schema document is not a usable schema
     */
    public static RiffleSchema parse(Path file, RiffleOptions options) throws IOException {
        return SchemaLoader.load(Riffle.parse(file, options));
    }

    /**
     * Validates an already parsed document against this schema, changing nothing. Validation never
     * stops at the first problem and never inserts, reorders, or coerces anything.
     *
     * @param document the document to validate
     * @return every problem, in the order SCHEMA_SPEC.md §12 requires, or an empty list when the
     *     document is valid
     */
    public List<RiffleProblem> validate(RiffleObject document) {
        return validate(document, RiffleOptions.UNBOUNDED);
    }

    /**
     * Validates with caller-supplied resource options.
     *
     * @param document the document to validate
     * @param options the resource options
     * @return every problem, in the order SCHEMA_SPEC.md §12 requires
     * @throws IllegalArgumentException if the document is deeper than a configured cap
     */
    public List<RiffleProblem> validate(RiffleObject document, RiffleOptions options) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(options, "options");
        List<RiffleProblem> problems = new ArrayList<>();
        SchemaValidator.validate(root, document, problems, options);
        return List.copyOf(problems);
    }

    /**
     * Returns {@code document} when it is valid against this schema, and throws when it is not.
     *
     * @param document the document to validate
     * @return the document
     * @throws RiffleValidationException if the document violates this schema
     */
    RiffleObject validated(RiffleObject document) {
        return validated(document, RiffleOptions.UNBOUNDED);
    }

    RiffleObject validated(RiffleObject document, RiffleOptions options) {
        List<RiffleProblem> problems = validate(document, options);
        if (!problems.isEmpty()) {
            throw new RiffleValidationException(problems);
        }
        return document;
    }

    /**
     * Validates a document and fills in the defaults this schema declares, as SCHEMA_SPEC.md §13
     * defines the operation.
     *
     * <p>The input is validated first and nothing is inserted into a document that has any problem.
     * An absent optional member whose node declares a {@code default} gains that value, inserted
     * after the entries the document already holds and in the document order of {@code keys}. The
     * fill visits the values the input had and the values it inserted. Every value that was present
     * is kept exactly as it was, with its location, and an inserted value carries the text the
     * schema wrote with no location of its own.</p>
     *
     * @param document the document to fill
     * @return the document, or a new tree holding the inserted defaults
     * @throws RiffleValidationException if the document violates this schema, carrying every problem
     *     it has, in which case nothing is inserted
     */
    public RiffleObject applyDefaults(RiffleObject document) {
        return applyDefaults(document, RiffleOptions.UNBOUNDED);
    }

    /**
     * Validates a document and fills defaults with caller-supplied resource options.
     *
     * @param document the document to fill
     * @param options the resource options
     * @return the document, or a new tree holding the inserted defaults
     * @throws RiffleValidationException if the document violates this schema
     * @throws IllegalArgumentException if the document or the filled tree exceeds a configured cap
     */
    public RiffleObject applyDefaults(RiffleObject document, RiffleOptions options) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(options, "options");
        validated(document, options);
        return (RiffleObject) fill(root, document, 0, options);
    }

    private static RiffleValue fill(SchemaNode node, RiffleValue value, int depth, RiffleOptions options) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(Frame.of(node, value, depth, null));
        RiffleValue result = null;
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.phase == 0) {
                if (!frame.prepare(options)) {
                    complete(stack, frame);
                    continue;
                }
                frame.phase = 1;
                if (frame.pushChildren(stack)) {
                    continue;
                }
            }
            if (frame.phase == 1) {
                frame.insertDefaults(options);
                frame.phase = 2;
                if (frame.pushChildren(stack)) {
                    continue;
                }
            }
            frame.result = frame.assemble();
            result = frame.result;
            complete(stack, frame);
        }
        return result;
    }

    private static void complete(Deque<Frame> stack, Frame frame) {
        stack.pop();
        if (frame.parent == null) {
            return;
        }
        if (frame.key != null) {
            frame.parent.entries.put(frame.key, frame.result);
            if (frame.result != frame.original) {
                frame.parent.changed = true;
            }
        } else {
            frame.parent.values.set(frame.index, frame.result);
            if (frame.result != frame.original) {
                frame.parent.changed = true;
            }
        }
    }

    /**
     * Returns the value a schema wrote for insertion at {@code path}: the same text, order, and
     * shape, with no location and with each value carrying the path of the place it is inserted at.
     */
    private static RiffleValue atPath(RiffleValue value, RifflePath path) {
        if (value instanceof RiffleString string) {
            return new RiffleString(string.text(), path, null);
        }
        if (value instanceof RiffleObject map) {
            LinkedHashMap<String, RiffleValue> entries = new LinkedHashMap<>();
            for (Map.Entry<String, RiffleValue> entry : map.entries().entrySet()) {
                entries.put(entry.getKey(), atPath(entry.getValue(), path.key(entry.getKey())));
            }
            return new RiffleObject(entries, new LinkedHashMap<>(), path, null);
        }
        if (value instanceof RiffleArray array) {
            List<RiffleValue> values = new ArrayList<>();
            for (int index = 0; index < array.size(); index++) {
                values.add(atPath(array.get(index), path.index(index)));
            }
            return new RiffleArray(values, path, null);
        }
        return value;
    }

    /** One value being filled; children are pushed onto the stack instead of recursing. */
    private static final class Frame {
        private final SchemaNode node;
        private final RiffleValue original;
        private final int depth;
        private final Frame parent;
        private final String key;
        private final int index;
        private final List<Child> children = new ArrayList<>();
        private int phase;
        private boolean changed;
        private LinkedHashMap<String, RiffleValue> entries;
        private LinkedHashMap<String, RiffleLocation> keyLocations;
        private List<RiffleValue> values;
        private RiffleValue result;

        private Frame(SchemaNode node, RiffleValue original, int depth, Frame parent, String key, int index) {
            this.node = node;
            this.original = original;
            this.depth = depth;
            this.parent = parent;
            this.key = key;
            this.index = index;
        }

        private static Frame of(SchemaNode node, RiffleValue value, int depth, Frame parent) {
            return new Frame(node, value, depth, parent, null, -1);
        }

        private static Frame child(Frame parent, SchemaNode node, RiffleValue value, String key, int index) {
            return new Frame(node, value, parent.depth + 1, parent, key, index);
        }

        /** Prepares existing children. Returns false when this value needs no filling at all. */
        private boolean prepare(RiffleOptions options) {
            SchemaNode effective = node.select(original);
            if (original instanceof RiffleArray array) {
                if (!(effective instanceof SchemaNode.ArrayNode list) || list.items() == null || array.size() == 0) {
                    result = original;
                    return false;
                }
                checkDepth(options);
                values = new ArrayList<>(array.values());
                for (int at = 0; at < values.size(); at++) {
                    children.add(new Child(list.items(), values.get(at), null, at));
                }
                return true;
            }
            if (!(original instanceof RiffleObject map) || !(effective instanceof SchemaNode.MapNode object)) {
                result = original;
                return false;
            }
            if (!map.entries().isEmpty()) {
                checkDepth(options);
            }
            entries = new LinkedHashMap<>(map.entries());
            keyLocations = new LinkedHashMap<>();
            for (String name : map.entries().keySet()) {
                keyLocations.put(name, map.keyLocation(name));
            }
            for (Map.Entry<String, RiffleValue> entry : map.entries().entrySet()) {
                SchemaNode member = object.keys().get(entry.getKey());
                if (member == null) {
                    member = object.otherKeys();
                }
                if (member != null) {
                    children.add(new Child(member, entry.getValue(), entry.getKey(), -1));
                }
            }
            return true;
        }

        /** Inserts absent defaults after the entries the document had, in schema key order. */
        private void insertDefaults(RiffleOptions options) {
            if (entries == null) {
                return;
            }
            SchemaNode effective = node.select(original);
            if (!(effective instanceof SchemaNode.MapNode object)) {
                return;
            }
            RifflePath position = original.path().orElse(RifflePath.root());
            boolean checked = false;
            for (Map.Entry<String, SchemaNode> declared : object.keys().entrySet()) {
                String name = declared.getKey();
                SchemaNode member = declared.getValue();
                if (entries.containsKey(name) || !member.optional() || member.defaultValue() == null) {
                    continue;
                }
                if (!checked) {
                    checkDepth(options);
                    checked = true;
                }
                RiffleValue inserted = atPath(member.defaultValue(), position.key(name));
                entries.put(name, inserted);
                keyLocations.put(name, null);
                changed = true;
                children.add(new Child(member, inserted, name, -1));
            }
        }

        private boolean pushChildren(Deque<Frame> stack) {
            if (children.isEmpty()) {
                return false;
            }
            for (int at = children.size() - 1; at >= 0; at--) {
                Child child = children.get(at);
                stack.push(child(this, child.node(), child.value(), child.key(), child.index()));
            }
            children.clear();
            return true;
        }

        private RiffleValue assemble() {
            if (result != null) {
                return result;
            }
            if (!changed) {
                return original;
            }
            if (entries != null) {
                RiffleObject map = (RiffleObject) original;
                return new RiffleObject(
                        entries,
                        keyLocations,
                        map.path().orElse(null),
                        map.location().orElse(null));
            }
            RiffleArray array = (RiffleArray) original;
            return new RiffleArray(
                    values, array.path().orElse(null), array.location().orElse(null));
        }

        private void checkDepth(RiffleOptions options) {
            if (options.exceeds(depth)) {
                throw new IllegalArgumentException(
                        "value tree is deeper than the configured limit: " + depth + " > " + options.depthLimit());
            }
        }
    }

    private record Child(SchemaNode node, RiffleValue value, String key, int index) {}

    /**
     * Returns the root node of this schema, as the value a document is validated against.
     *
     * @return the root node
     */
    SchemaNode root() {
        return root;
    }

    /**
     * Returns a description of this schema, naming its root kind rather than anything it would
     * accept, so that a host can log which schema was in use.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "RiffleSchema[root=" + SchemaLoader.typeName(root.resolve().kind()) + "]";
    }
}
