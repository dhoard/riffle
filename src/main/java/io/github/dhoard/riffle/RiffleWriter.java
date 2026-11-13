package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Writes the canonical form of a document: LF line endings, four-space indentation,
 * {@code key = value} entries, {@code @type} markers for non-string scalars, {@code {}} and
 * {@code []} for empty collections, and quotes only where a bare key or value would be read
 * differently. Package-private by design.
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
            out.append("{\n");
            entries(object, level + 1);
            indent(level);
            out.append("}\n");
        } else if (value instanceof RiffleArray array) {
            if (array.values().isEmpty()) {
                out.append("[]\n");
                return;
            }
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
        String plain = value.toPlainString();
        return plain.length() <= 256 ? plain : value.toString();
    }

    private void indent(int level) {
        out.append(INDENT.repeat(level));
    }
}
