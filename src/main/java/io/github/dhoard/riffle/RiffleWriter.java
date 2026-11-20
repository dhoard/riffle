package io.github.dhoard.riffle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Writes the canonical document form of LANGUAGE_SPEC.md §11.2, in which every key is bare. */
final class RiffleWriter {

    private static final String INDENT = "    ";

    private final RiffleOptions options;
    private final StringBuilder out = new StringBuilder();

    private RiffleWriter(RiffleOptions options) {
        this.options = options;
    }

    /** Writes {@code root} with the default options. */
    static String write(RiffleObject root) {
        return write(root, RiffleOptions.UNBOUNDED);
    }

    /** Writes {@code root}, applying a caller-supplied nesting cap if one is configured. */
    static String write(RiffleObject root, RiffleOptions options) {
        RiffleWriter writer = new RiffleWriter(options);
        writer.emit(root);
        return writer.out.toString();
    }

    private void emit(RiffleObject root) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(Frame.root(root));
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.index == (frame.map ? frame.entries.size() : frame.values.size())) {
                stack.pop();
                if (!frame.root) {
                    out.append(indent(frame.level - 1)).append(frame.close).append('\n');
                }
                continue;
            }
            if (frame.map) {
                Map.Entry<String, RiffleValue> entry = frame.entries.get(frame.index++);
                // A key is always written bare: the key alphabet of §6.2 is exactly the bare form, so
                // no key a value tree can hold needs quoting.
                out.append(indent(frame.level)).append(entry.getKey()).append(" : ");
                emitValue(entry.getValue(), frame.level, stack);
            } else {
                RiffleValue element = frame.values.get(frame.index++);
                out.append(indent(frame.level));
                emitValue(element, frame.level, stack);
            }
        }
    }

    private void emitValue(RiffleValue value, int level, Deque<Frame> stack) {
        if (value instanceof RiffleString string) {
            out.append(text(string.text(), level));
        } else if (value instanceof RiffleObject map) {
            if (map.entries().isEmpty()) {
                out.append("{}\n");
                return;
            }
            checkDepth(level + 1);
            out.append("{\n");
            stack.push(Frame.map(map, level + 1));
        } else {
            RiffleArray array = (RiffleArray) value;
            if (array.values().isEmpty()) {
                out.append("[]\n");
                return;
            }
            checkDepth(level + 1);
            out.append("[\n");
            stack.push(Frame.array(array, level + 1));
        }
    }

    /** Writes a string bare when possible, then as a raw block, and otherwise quoted. */
    private static String text(String text, int level) {
        if (Syntax.isBareValue(text)) {
            return text + "\n";
        }
        if (Syntax.isRawBlockText(text)) {
            return rawBlock(text, level + 1);
        }
        return Syntax.quoteValue(text) + "\n";
    }

    /**
     * Writes a raw block whose content and closer sit at {@code level}. Empty lines carry no
     * indentation; every other line, even one of only whitespace, carries all of it.
     */
    private static String rawBlock(String text, int level) {
        StringBuilder block = new StringBuilder(Syntax.RAW_BLOCK_DELIMITER).append('\n');
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                block.append(indent(level)).append(line);
            }
            block.append('\n');
        }
        block.append(indent(level)).append(Syntax.RAW_BLOCK_DELIMITER).append('\n');
        return block.toString();
    }

    private void checkDepth(int depth) {
        if (options.exceeds(depth)) {
            throw new IllegalArgumentException(
                    "value tree is deeper than the configured limit: " + depth + " > " + options.depthLimit());
        }
    }

    private static String indent(int level) {
        return INDENT.repeat(level);
    }

    /** One open collection in the output, used instead of recursion over tree depth. */
    private static final class Frame {
        private final boolean map;
        private final boolean root;
        private final int level;
        private final List<Map.Entry<String, RiffleValue>> entries;
        private final List<RiffleValue> values;
        private final char close;
        private int index;

        private Frame(
                boolean map,
                boolean root,
                int level,
                List<Map.Entry<String, RiffleValue>> entries,
                List<RiffleValue> values) {
            this.map = map;
            this.root = root;
            this.level = level;
            this.entries = entries;
            this.values = values;
            this.close = map ? '}' : ']';
        }

        private static Frame root(RiffleObject object) {
            return new Frame(true, true, 0, new ArrayList<>(object.entries().entrySet()), List.of());
        }

        private static Frame map(RiffleObject object, int level) {
            return new Frame(
                    true, false, level, new ArrayList<>(object.entries().entrySet()), List.of());
        }

        private static Frame array(RiffleArray array, int level) {
            return new Frame(false, false, level, List.of(), new ArrayList<>(array.values()));
        }
    }
}
