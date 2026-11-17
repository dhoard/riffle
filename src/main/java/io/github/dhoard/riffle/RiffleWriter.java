package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Writes the canonical form of a document: LF line endings, four-space indentation,
 * {@code key = value} entries, {@code @type} markers for non-string scalars, {@code {}} and
 * {@code []} for empty collections, and quotes only where a bare key or value would be read
 * differently. Package-private by design.
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
            out.append(key(entry.getKey())).append(" = ");
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
            out.append(text(string.text())).append('\n');
        } else if (value instanceof RiffleInt number) {
            out.append("@int ").append(number.value()).append('\n');
        } else if (value instanceof RiffleLong number) {
            out.append("@long ").append(number.value()).append('\n');
        } else if (value instanceof RiffleDecimal number) {
            out.append("@decimal ").append(decimal(number.value())).append('\n');
        } else if (value instanceof RiffleBoolean flag) {
            out.append("@boolean ").append(flag.value()).append('\n');
        } else {
            out.append("@null\n");
        }
    }

    private static String key(String key) {
        return Syntax.isBareKey(key) ? key : Syntax.quote(key);
    }

    private static String text(String text) {
        return Syntax.isBareValue(text) ? text : Syntax.quote(text);
    }

    private static String decimal(BigDecimal value) {
        return plainTextLength(value) <= Limits.MAX_PLAIN_DECIMAL ? value.toPlainString() : value.toString();
    }

    /**
     * Returns the length {@code value.toPlainString()} would have, without building that string:
     * a value such as {@code 1E+2147483647} would need billions of characters.
     */
    private static long plainTextLength(BigDecimal value) {
        long precision = value.precision();
        long scale = value.scale();
        long digits;
        if (scale <= 0) {
            // Zero is written "0" however large its exponent: 0E+5 has no digits to pad.
            digits = value.signum() == 0 ? 1 : precision - scale;
        } else {
            digits = Math.max(precision + 1, scale + 2);
        }
        return value.signum() < 0 ? digits + 1 : digits;
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
