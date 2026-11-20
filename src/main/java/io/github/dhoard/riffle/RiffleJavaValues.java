package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
 * locations and key paths, so a round trip cannot recover that metadata. Conversion is iterative,
 * so tree depth is bounded only by memory unless a caller passes {@link RiffleOptions#maxDepth(int)}.</p>
 *
 * <p>Resulting maps and lists are defensive, deeply converted, and unmodifiable, and iteration
 * order is preserved. Riffle has no null: an absent key is the only way to express "no value". A
 * Java map key must be a Riffle key (LANGUAGE_SPEC.md §6.2), because a value holding a key that
 * Riffle cannot spell could not be written out again.</p>
 */
public final class RiffleJavaValues {
    private static final String NULL_VALUE = "null is not a Riffle value; omit the key instead";

    private RiffleJavaValues() {}

    /**
     * Converts a Riffle value to its native Java representation.
     *
     * @param value the value
     * @return a {@link String}, an unmodifiable {@link Map}, or an unmodifiable {@link List}
     */
    public static Object toJava(RiffleValue value) {
        return toJava(value, RiffleOptions.UNBOUNDED);
    }

    /**
     * Converts a Riffle value to Java with caller-supplied resource options.
     *
     * @param value the value
     * @param options the resource options
     * @return a {@link String}, an unmodifiable {@link Map}, or an unmodifiable {@link List}
     * @throws IllegalArgumentException if the value is null or deeper than a configured cap
     */
    public static Object toJava(RiffleValue value, RiffleOptions options) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(options, "options");
        TreeDepth.check(value, options, "value tree is deeper than the configured limit");
        Deque<ToJavaFrame> stack = new ArrayDeque<>();
        stack.push(ToJavaFrame.of(value));
        Object result = null;
        while (!stack.isEmpty()) {
            ToJavaFrame frame = stack.peek();
            if (frame.ready) {
                stack.pop();
                result = frame.result;
                if (frame.parent == null) {
                    return result;
                }
                if (frame.key != null) {
                    frame.parent.map.put(frame.key, result);
                } else {
                    frame.parent.list.add(result);
                }
                continue;
            }
            if (!frame.expanded) {
                frame.expanded = true;
                if (frame.value instanceof RiffleString string) {
                    frame.result = string.text();
                    frame.ready = true;
                } else if (frame.value instanceof RiffleObject map) {
                    frame.children = new ArrayList<Object>(map.entries().entrySet());
                    frame.map = new LinkedHashMap<>();
                    if (map.entries().isEmpty()) {
                        frame.result = Collections.unmodifiableMap(frame.map);
                        frame.ready = true;
                    } else {
                        pushToJava(stack, frame);
                    }
                } else {
                    RiffleArray array = (RiffleArray) frame.value;
                    frame.children = new ArrayList<>(array.size());
                    for (int i = 0; i < array.size(); i++) {
                        frame.children.add(array.get(i));
                    }
                    frame.list = new ArrayList<>(array.size());
                    if (array.size() == 0) {
                        frame.result = Collections.unmodifiableList(frame.list);
                        frame.ready = true;
                    } else {
                        pushToJava(stack, frame);
                    }
                }
            } else {
                frame.result = frame.map == null
                        ? Collections.unmodifiableList(frame.list)
                        : Collections.unmodifiableMap(frame.map);
                frame.ready = true;
            }
        }
        return result;
    }

    private static void pushToJava(Deque<ToJavaFrame> stack, ToJavaFrame parent) {
        for (int i = parent.children.size() - 1; i >= 0; i--) {
            Object child = parent.children.get(i);
            if (child instanceof Map.Entry<?, ?> entry) {
                stack.push(ToJavaFrame.child(parent, (RiffleValue) entry.getValue(), (String) entry.getKey(), -1));
            } else {
                stack.push(ToJavaFrame.child(parent, (RiffleValue) child, null, i));
            }
        }
    }

    /**
     * Converts a Riffle map to an unmodifiable, insertion-ordered Java map.
     *
     * @param object the map
     * @return a defensive copy
     */
    public static Map<String, Object> toJava(RiffleObject object) {
        return toJava(object, RiffleOptions.UNBOUNDED);
    }

    /**
     * Converts a Riffle map to Java with caller-supplied resource options.
     *
     * @param object the map
     * @param options the resource options
     * @return a defensive copy
     */
    public static Map<String, Object> toJava(RiffleObject object, RiffleOptions options) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toJava((RiffleValue) object, options);
        return result;
    }

    /**
     * Converts a Java value to a Riffle value.
     *
     * @param value the Java value
     * @return the corresponding Riffle value, with no source metadata
     */
    public static RiffleValue fromJava(Object value) {
        return fromJava(value, RiffleOptions.UNBOUNDED);
    }

    /**
     * Converts a Java value to a Riffle value with caller-supplied resource options.
     *
     * @param value the Java value
     * @param options the resource options
     * @return the corresponding Riffle value, with no source metadata
     * @throws IllegalArgumentException if the value is unsupported, cyclic, exceeds a configured cap,
     *         or a map key is not a Riffle key
     */
    public static RiffleValue fromJava(Object value, RiffleOptions options) {
        Objects.requireNonNull(options, "options");
        Deque<FromJavaFrame> stack = new ArrayDeque<>();
        Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        stack.push(FromJavaFrame.of(value, active));
        RiffleValue result = null;
        while (!stack.isEmpty()) {
            FromJavaFrame frame = stack.peek();
            if (frame.ready) {
                stack.pop();
                result = frame.result;
                active.remove(frame.source);
                if (frame.parent == null) {
                    return result;
                }
                if (frame.key != null) {
                    frame.parent.map.put(frame.key, result);
                } else {
                    frame.parent.list.add(result);
                }
                continue;
            }
            if (!frame.expanded) {
                frame.expanded = true;
                RiffleValue scalar = scalar(frame.source);
                if (scalar != null) {
                    frame.result = scalar;
                    frame.ready = true;
                    continue;
                }
                if (frame.source instanceof Map<?, ?> map) {
                    checkDepth(frame.depth, options);
                    enter(map, active);
                    frame.map = new LinkedHashMap<>();
                    frame.children = new ArrayList<Object>(map.entrySet());
                    if (map.isEmpty()) {
                        frame.result = RiffleObject.of(frame.map);
                        frame.ready = true;
                    } else {
                        pushFromJava(stack, frame);
                    }
                } else if (frame.source instanceof List<?> list) {
                    checkDepth(frame.depth, options);
                    enter(list, active);
                    frame.list = new ArrayList<>(list.size());
                    frame.children = new ArrayList<Object>(list);
                    if (list.isEmpty()) {
                        frame.result = RiffleArray.of(frame.list);
                        frame.ready = true;
                    } else {
                        pushFromJava(stack, frame);
                    }
                } else {
                    throw new IllegalArgumentException("unsupported Java type "
                            + frame.source.getClass().getName() + "; Riffle accepts strings, integers, longs, decimals,"
                            + " booleans, maps and lists");
                }
            } else {
                frame.result = frame.map == null ? RiffleArray.of(frame.list) : RiffleObject.of(frame.map);
                frame.ready = true;
            }
        }
        return result;
    }

    private static void pushFromJava(Deque<FromJavaFrame> stack, FromJavaFrame parent) {
        for (int i = parent.children.size() - 1; i >= 0; i--) {
            Object child = parent.children.get(i);
            if (child instanceof Map.Entry<?, ?> entry) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("map keys must be non-null strings");
                }
                stack.push(FromJavaFrame.child(parent, entry.getValue(), key, -1));
            } else {
                stack.push(FromJavaFrame.child(parent, child, null, i));
            }
        }
    }

    /**
     * Converts a Java map to a Riffle map with caller-supplied resource options.
     *
     * @param object the Java map
     * @param options the resource options
     * @return the corresponding Riffle map, with no source metadata
     */
    public static RiffleObject fromJava(Map<String, ?> object, RiffleOptions options) {
        if (object == null) {
            throw new IllegalArgumentException(NULL_VALUE);
        }
        return (RiffleObject) fromJava((Object) object, options);
    }

    /**
     * Converts a Java map to a Riffle map.
     *
     * @param object the Java map
     * @return the corresponding Riffle map, with no source metadata
     */
    public static RiffleObject fromJava(Map<String, ?> object) {
        return fromJava(object, RiffleOptions.UNBOUNDED);
    }

    private static RiffleValue scalar(Object value) {
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
        return null;
    }

    /**
     * Renders a decimal as normalized text that the decimal accessor reads back exactly: plain
     * notation up to {@link Limits#MAX_PLAIN_DECIMAL} characters and exponent notation beyond it.
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

    private static void checkDepth(int depth, RiffleOptions options) {
        if (options.exceeds(depth)) {
            throw new IllegalArgumentException(
                    "Java value is deeper than the configured limit: " + depth + " > " + options.depthLimit());
        }
    }

    private static void enter(Object container, Set<Object> active) {
        if (!active.add(container)) {
            throw new IllegalArgumentException("cyclic Java collection graph is not supported");
        }
    }

    private static final class ToJavaFrame {
        private final RiffleValue value;
        private final ToJavaFrame parent;
        private final String key;
        private final int index;
        private List<Object> children;
        private Map<String, Object> map;
        private List<Object> list;
        private boolean expanded;
        private boolean ready;
        private Object result;

        private ToJavaFrame(RiffleValue value, ToJavaFrame parent, String key, int index) {
            this.value = value;
            this.parent = parent;
            this.key = key;
            this.index = index;
        }

        private static ToJavaFrame of(RiffleValue value) {
            return new ToJavaFrame(value, null, null, -1);
        }

        private static ToJavaFrame child(ToJavaFrame parent, RiffleValue value, String key, int index) {
            return new ToJavaFrame(value, parent, key, index);
        }
    }

    private static final class FromJavaFrame {
        private final Object source;
        private final FromJavaFrame parent;
        private final String key;
        private final int index;
        private final int depth;
        private List<Object> children;
        private Map<String, RiffleValue> map;
        private List<RiffleValue> list;
        private boolean expanded;
        private boolean ready;
        private RiffleValue result;

        private FromJavaFrame(Object source, FromJavaFrame parent, String key, int index, int depth) {
            this.source = source;
            this.parent = parent;
            this.key = key;
            this.index = index;
            this.depth = depth;
        }

        private static FromJavaFrame of(Object source, Set<Object> active) {
            return new FromJavaFrame(source, null, null, -1, 0);
        }

        private static FromJavaFrame child(FromJavaFrame parent, Object source, String key, int index) {
            return new FromJavaFrame(source, parent, key, index, parent.depth + 1);
        }
    }
}
