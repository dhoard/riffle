package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The comparison of values that SCHEMA_SPEC.md §12 needs, for allowed-value lists, for rules and
 * for duplicates.
 *
 * <p>Riffle has one value model, so a value is compared under the type a node requires of it: the
 * text of a string, the interpreted number of an integer or decimal, and the text of anything the
 * host cannot interpret. A number too large to interpret is therefore equal only to itself, which
 * keeps a host that stores the value as text agreeing with one that does not.</p>
 */
final class SchemaValues {

    private SchemaValues() {}

    /**
     * Returns the key that decides whether {@code value} equals another value under {@code kind}.
     *
     * @param kind the kind of node the value is compared under
     * @param value the value
     * @return the key, a {@link String}, a {@link Long} or a {@link BigDecimal}
     */
    static Object comparisonKey(SchemaNode.Kind kind, RiffleValue value) {
        if (!(value instanceof RiffleString string)) {
            return value;
        }
        String text = string.text();
        return switch (kind) {
            case INTEGER -> {
                Long number = Conversions.isIntegerText(text) ? Conversions.parseIntegerText(text) : null;
                yield number != null ? number : text;
            }
            case DECIMAL -> {
                BigDecimal number = Conversions.isDecimalText(text) ? Conversions.parseDecimalText(text) : null;
                yield number != null ? number.stripTrailingZeros() : text;
            }
            default -> text;
        };
    }

    /**
     * Returns whether two comparison keys are equal, comparing decimals mathematically.
     *
     * @param first the first key
     * @param second the second key
     * @return true when the values are equal
     */
    static boolean equalKeys(Object first, Object second) {
        if (first instanceof BigDecimal firstDecimal && second instanceof BigDecimal secondDecimal) {
            return firstDecimal.compareTo(secondDecimal) == 0;
        }
        return Objects.equals(first, second);
    }

    /**
     * Returns whether a value is the flag value {@code true} of SCHEMA_SPEC.md §4.
     *
     * @param value the value a flag keyword holds, or {@code null} when it is absent
     * @return true only for the value {@code true}
     */
    static boolean isTrue(RiffleValue value) {
        return value instanceof RiffleString string
                && Conversions.isBooleanText(string.text())
                && Boolean.parseBoolean(string.text());
    }

    /**
     * Returns the text of a value, or an empty string for a map or an array, whose text is a
     * rendering rather than a value.
     *
     * @param value the value
     * @return the text
     */
    static String text(RiffleValue value) {
        return value instanceof RiffleString string ? string.text() : "";
    }

    /**
     * Renders the text of some schema values for a diagnostic, quoting each one so that a keyword
     * and a value read differently.
     *
     * @param values the values, as they were written in the schema
     * @return the values separated by commas
     */
    static String describeValues(List<RiffleValue> values) {
        StringBuilder text = new StringBuilder();
        for (RiffleValue value : values) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(describeText(text(value)));
        }
        return text.toString();
    }

    /** Returns schema text as it was written where that is unambiguous, and quoted where it is not. */
    static String describeText(String text) {
        return Syntax.isBareValue(text) ? text : Syntax.quoteValue(text);
    }

    /**
     * Renders the names a rule may name for a diagnostic, which SCHEMA_SPEC.md §10 allows because a
     * key name is schema text rather than data.
     *
     * @param names the names, in the order they were written
     * @return the names separated by commas
     */
    static String describeNames(List<String> names) {
        StringBuilder text = new StringBuilder();
        for (String name : names) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append('`').append(name).append('`');
        }
        return text.toString();
    }
}
