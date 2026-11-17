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
        return Syntax.isBareKey(key) ? key : Syntax.quoteKey(key);
    }

    private static String text(String text) {
        return Syntax.isBareValue(text) ? text : Syntax.quoteValue(text);
    }

    /**
     * Renders a normalized decimal using the portable algorithm in {@code LANGUAGE_SPEC.md}, §11.3.
     * Values are normalized before reaching the writer, so a nonzero coefficient has no trailing
     * zeros and every lexical scale has already become a base-10 exponent.
     */
    private static String decimal(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        String digits = value.unscaledValue().abs().toString();
        long exponent = -(long) value.scale();
        long adjusted = exponent + digits.length() - 1L;

        StringBuilder token = new StringBuilder();
        if (value.signum() < 0) {
            token.append('-');
        }
        if (plainTextLength(value, digits.length(), exponent, adjusted) <= Limits.MAX_PLAIN_DECIMAL) {
            if (exponent >= 0) {
                token.append(digits).append("0".repeat((int) exponent));
            } else if (adjusted >= 0) {
                int split = (int) adjusted + 1;
                token.append(digits, 0, split).append('.').append(digits, split, digits.length());
            } else {
                token.append("0.").append("0".repeat((int) -adjusted - 1)).append(digits);
            }
            return token.toString();
        }

        token.append(digits.charAt(0));
        if (digits.length() > 1) {
            token.append('.').append(digits, 1, digits.length());
        }
        token.append('E').append(adjusted >= 0 ? '+' : '-').append(Math.abs(adjusted));
        return token.toString();
    }

    /**
     * Returns the length of the plain token without building it: a value such as
     * {@code 1E+2147483647} would need billions of characters. The token is ASCII, so its
     * character count is also its UTF-8 byte count.
     */
    private static long plainTextLength(BigDecimal value, int digits, long exponent, long adjusted) {
        long length;
        if (exponent >= 0) {
            length = digits + exponent;
        } else if (adjusted >= 0) {
            length = digits + 1L;
        } else {
            length = digits + 1L - adjusted;
        }
        return value.signum() < 0 ? length + 1 : length;
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
