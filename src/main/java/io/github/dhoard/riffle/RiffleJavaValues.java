package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in conversion between Riffle values and ordinary Java values.
 *
 * <p>These methods are a separate, explicit boundary. Conversion to Java types discards source
 * locations and key paths, so a round trip cannot recover that metadata.</p>
 *
 * <p>Resulting maps and lists are defensive, deeply converted, and unmodifiable, and iteration
 * order is preserved. Riffle has no null: an absent key is the only way to express "no value".</p>
 */
public final class RiffleJavaValues {
    private static final String NULL_VALUE = "null is not a Riffle value; omit the key instead";

    private RiffleJavaValues() {}

    /**
     * Converts a Riffle value to its native Java representation.
     *
     * @param value the value
     * @return a {@link String}, an unmodifiable {@link Map}, or an unmodifiable {@link List};
     *     never {@code null}
     * @throws NullPointerException if {@code value} is null
     */
    public static Object toJava(RiffleValue value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof RiffleObject object) {
            return toJava(object);
        }
        if (value instanceof RiffleArray array) {
            return toJavaArray(array);
        }
        return value.asString();
    }

    /**
     * Converts a Riffle map to an unmodifiable, insertion-ordered Java map.
     *
     * @param object the map
     * @return a defensive copy
     * @throws NullPointerException if {@code object} is null
     */
    public static Map<String, Object> toJava(RiffleObject object) {
        Objects.requireNonNull(object, "object");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, RiffleValue> entry : object.entries().entrySet()) {
            copy.put(entry.getKey(), toJava(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Converts a Java value to a Riffle value. Only {@link String}, {@link Integer},
     * {@link Long}, {@link BigDecimal}, {@link Boolean}, {@link Map}, and {@link List} are
     * supported; map keys must be non-null strings. A number or boolean becomes its canonical
     * text, which the matching {@link RiffleValue} accessor accepts.
     *
     * <p>Conversion is fully recursive and defensive. Identity cycles in maps and lists are
     * rejected; shared acyclic children are converted independently.</p>
     *
     * @param value the Java value
     * @return the corresponding Riffle value, with no source metadata
     * @throws IllegalArgumentException if the value is null or its type is unsupported, a map key
     *     is not a string, a string contains an unpaired surrogate or U+0000, a decimal or nesting
     *     limit is exceeded, or a collection graph contains a cycle
     */
    public static RiffleValue fromJava(Object value) {
        return convert(value, 0, newActiveSet());
    }

    /**
     * Converts a Java map to a Riffle map. This overload is convenient for parsed documents,
     * whose root is always a map.
     *
     * @param object the map
     * @return the corresponding Riffle map, with no source metadata
     * @throws IllegalArgumentException under the same conditions as {@link #fromJava(Object)}
     */
    public static RiffleObject fromJava(Map<String, ?> object) {
        if (object == null) {
            throw new IllegalArgumentException(NULL_VALUE);
        }
        return convertMap(object, 0, newActiveSet());
    }

    private static List<Object> toJavaArray(RiffleArray array) {
        List<Object> copy = new ArrayList<>(array.size());
        for (RiffleValue element : array.values()) {
            copy.add(toJava(element));
        }
        return Collections.unmodifiableList(copy);
    }

    private static RiffleValue convert(Object value, int level, Set<Object> active) {
        if (value == null) {
            throw new IllegalArgumentException(NULL_VALUE);
        }
        if (value instanceof String string) {
            return RiffleString.of(string);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Boolean) {
            return RiffleString.of(value.toString());
        }
        if (value instanceof BigDecimal decimal) {
            return RiffleString.of(decimalText(decimal));
        }
        if (value instanceof Map<?, ?> map) {
            return convertMap(map, level, active);
        }
        if (value instanceof List<?> list) {
            return convertList(list, level, active);
        }
        throw new IllegalArgumentException("unsupported Java type "
                + value.getClass().getName() + "; Riffle accepts strings, integers, longs, decimals, booleans, maps"
                + " and lists");
    }

    /**
     * Renders a decimal as normalized text that the decimal accessor reads back exactly: plain
     * notation up to {@link Limits#MAX_PLAIN_DECIMAL} characters and exponent notation beyond it,
     * so that a value such as {@code 1E+2147483647} never materializes billions of digits.
     */
    private static String decimalText(BigDecimal decimal) {
        BigDecimal value;
        try {
            value = decimal.stripTrailingZeros();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("decimal is outside Riffle's exponent range", e);
        }
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
        if (plainLength(value, digits.length(), exponent, adjusted) <= Limits.MAX_PLAIN_DECIMAL) {
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
        return token.append('E')
                .append(adjusted >= 0 ? '+' : '-')
                .append(Math.abs(adjusted))
                .toString();
    }

    /** Returns the length of the plain notation without building it. */
    private static long plainLength(BigDecimal value, int digits, long exponent, long adjusted) {
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

    private static RiffleObject convertMap(Map<?, ?> map, int level, Set<Object> active) {
        checkLevel(level);
        enter(map, active);
        try {
            Map<String, RiffleValue> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("map keys must be non-null strings");
                }
                if (Syntax.hasUnpairedSurrogate(key)) {
                    throw new IllegalArgumentException("map key contains an unpaired surrogate");
                }
                copy.put(key, convert(entry.getValue(), level + 1, active));
            }
            return RiffleObject.of(copy);
        } finally {
            active.remove(map);
        }
    }

    private static RiffleArray convertList(List<?> list, int level, Set<Object> active) {
        checkLevel(level);
        enter(list, active);
        try {
            List<RiffleValue> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(convert(element, level + 1, active));
            }
            return RiffleArray.of(copy);
        } finally {
            active.remove(list);
        }
    }

    private static void checkLevel(int level) {
        if (level > Limits.MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Java value nests deeper than the limit of " + Limits.MAX_DEPTH + " collections");
        }
    }

    private static void enter(Object container, Set<Object> active) {
        if (!active.add(container)) {
            throw new IllegalArgumentException("cyclic Java collection graph is not supported");
        }
    }

    private static Set<Object> newActiveSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
