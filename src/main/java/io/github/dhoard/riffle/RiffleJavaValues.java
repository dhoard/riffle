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
 * <p>These methods never relax {@link RiffleValue}'s strict accessors; they are a separate,
 * explicit boundary. Conversion to Java types discards source locations and key paths, so a
 * round trip cannot recover that metadata. It does preserve all eight semantic kinds for the
 * supported Java types, including the distinction between a missing key and a key present with
 * a null value.</p>
 *
 * <p>Resulting maps and lists are defensive, deeply converted, and unmodifiable. Unlike
 * {@link Map#copyOf(Map)} and {@link List#copyOf(java.util.Collection)}, they accept null
 * elements, because Riffle permits {@code @null}. Iteration order is preserved.</p>
 */
public final class RiffleJavaValues {
    private RiffleJavaValues() {}

    /**
     * Converts a Riffle value to its native Java representation.
     *
     * @param value the value
     * @return a {@link String}, {@link Integer}, {@link Long}, {@link BigDecimal},
     *     {@link Boolean}, an unmodifiable {@link Map}, an unmodifiable {@link List}, or
     *     {@code null} for a Riffle {@code @null}
     * @throws NullPointerException if {@code value} is null; pass {@link RiffleNull#INSTANCE}
     *     to represent Riffle null
     */
    public static Object toJava(RiffleValue value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof RiffleObject object) {
            return toJava(object);
        }
        if (value instanceof RiffleArray array) {
            return toJavaArray(array);
        }
        if (value instanceof RiffleString string) {
            return string.text();
        }
        if (value instanceof RiffleInt number) {
            return number.value();
        }
        if (value instanceof RiffleLong number) {
            return number.value();
        }
        if (value instanceof RiffleDecimal number) {
            return number.value();
        }
        if (value instanceof RiffleBoolean flag) {
            return flag.value();
        }
        if (value instanceof RiffleNull) {
            return null;
        }
        throw new IllegalStateException(
                "unsupported Riffle value " + value.getClass().getName());
    }

    /**
     * Converts a Riffle map to an unmodifiable, insertion-ordered Java map.
     *
     * @param object the map
     * @return a defensive copy whose values may be null
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
     * {@link Long}, {@link BigDecimal}, {@link Boolean}, {@code null}, {@link Map}, and
     * {@link List} are supported; map keys must be non-null strings.
     *
     * <p>Conversion is fully recursive and defensive. Identity cycles in maps and lists are
     * rejected; shared acyclic children are converted independently.</p>
     *
     * @param value the Java value, which may be null
     * @return the corresponding Riffle value, with no source metadata
     * @throws IllegalArgumentException if the type is unsupported, a map key is not a string,
     *     a string contains an unpaired surrogate, a decimal or nesting limit is exceeded,
     *     or a collection graph contains a cycle
     */
    public static RiffleValue fromJava(Object value) {
        if (value == null) {
            return RiffleNull.INSTANCE;
        }
        return convert(value, 0, newActiveSet());
    }

    /**
     * Converts a Java map to a Riffle map. This overload is convenient for parsed documents,
     * whose root is always a map.
     *
     * @param object the map
     * @return the corresponding Riffle map, with no source metadata
     * @throws NullPointerException if {@code object} is null
     * @throws IllegalArgumentException under the same conditions as {@link #fromJava(Object)}
     */
    public static RiffleObject fromJava(Map<String, ?> object) {
        Objects.requireNonNull(object, "object");
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
            return RiffleNull.INSTANCE;
        }
        if (value instanceof String string) {
            return RiffleString.of(string);
        }
        if (value instanceof Integer number) {
            return RiffleInt.of(number);
        }
        if (value instanceof Long number) {
            return RiffleLong.of(number);
        }
        if (value instanceof BigDecimal decimal) {
            try {
                return RiffleDecimal.of(decimal);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("decimal is outside Riffle's exponent range", e);
            }
        }
        if (value instanceof Boolean flag) {
            return RiffleBoolean.of(flag);
        }
        if (value instanceof Map<?, ?> map) {
            return convertMap(map, level, active);
        }
        if (value instanceof List<?> list) {
            return convertList(list, level, active);
        }
        throw new IllegalArgumentException(
                "unsupported Java type " + value.getClass().getName() + "; Riffle accepts only its eight kinds");
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
