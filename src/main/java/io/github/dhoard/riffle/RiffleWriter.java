package io.github.dhoard.riffle;

import java.util.Map;

/**
 * Writes the canonical form of a document: LF line endings, four-space indentation,
 * {@code key : value} entries, {@code {}} and {@code []} for empty collections, raw blocks for
 * multiline strings, and quotes only where a bare key or value would be read differently. Package-private by design.
 *
 * <p>Writing is bounded like parsing: a value tree that nests deeper than
 * {@link Limits#MAX_DEPTH} collections has no valid document, so it is rejected instead of
 * producing text the parser must refuse or recursing until the stack overflows.</p>
 */
final class RiffleWriter {
    private static final String INDENT = "    ";

    private final StringBuilder out = new StringBuilder();

    private RiffleWriter() {}

    static String write(RiffleObject root) {
        RiffleWriter writer = new RiffleWriter();
        writer.entries(root, 0);
        return writer.out.toString();
    }

    private void entries(RiffleObject object, int level) {
        for (Map.Entry<String, RiffleValue> entry : object.entries().entrySet()) {
            indent(level);
            out.append(key(entry.getKey())).append(" : ");
            value(entry.getValue(), level);
        }
    }

    private void value(RiffleValue value, int level) {
        if (value instanceof RiffleObject object) {
            if (object.entries().isEmpty()) {
                out.append("{}\n");
                return;
            }
            checkDepth(level + 1);
            out.append("{\n");
            entries(object, level + 1);
            indent(level);
            out.append("}\n");
        } else if (value instanceof RiffleArray array) {
            if (array.values().isEmpty()) {
                out.append("[]\n");
                return;
            }
            checkDepth(level + 1);
            out.append("[\n");
            for (RiffleValue element : array.values()) {
                indent(level + 1);
                value(element, level + 1);
            }
            indent(level);
            out.append("]\n");
        } else if (value instanceof RiffleString string) {
            text(string.text(), level);
        }
    }

    private static String key(String key) {
        return Syntax.isBareKey(key) ? key : Syntax.quoteKey(key);
    }

    /** Writes a string bare when possible, then as a raw block, and otherwise quoted. */
    private void text(String text, int level) {
        if (Syntax.isBareValue(text)) {
            out.append(text).append('\n');
        } else if (Syntax.isRawBlockText(text)) {
            rawBlock(text, level + 1);
        } else {
            out.append(Syntax.quoteValue(text)).append('\n');
        }
    }

    /**
     * Writes a raw block whose content and closer sit at {@code level}. Empty lines carry no
     * indentation; every other line, even one of only whitespace, carries all of it.
     */
    private void rawBlock(String text, int level) {
        out.append(Syntax.RAW_BLOCK_DELIMITER).append('\n');
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                indent(level);
                out.append(line);
            }
            out.append('\n');
        }
        indent(level);
        out.append(Syntax.RAW_BLOCK_DELIMITER).append('\n');
    }

    private static void checkDepth(int depth) {
        if (depth > Limits.MAX_DEPTH) {
            throw new IllegalArgumentException("value nesting exceeds the limit of " + Limits.MAX_DEPTH
                    + " collections, so no valid document can express it");
        }
    }

    private void indent(int level) {
        out.append(INDENT.repeat(level));
    }
}
