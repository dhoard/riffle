package io.github.dhoard.riffle;

import java.util.ArrayDeque;
import java.util.Deque;

/** Iterative measurement of a value tree's nesting depth, for caller-supplied caps. */
final class TreeDepth {

    private TreeDepth() {}

    /**
     * Returns the depth of the deepest nonempty collection in {@code value}. The value itself is at
     * depth zero; a nonempty child collection is at depth one.
     */
    static int of(RiffleValue value) {
        return measure(value, RiffleOptions.UNBOUNDED);
    }

    /** Throws when {@code value} is deeper than the option permits. */
    static void check(RiffleValue value, RiffleOptions options) {
        measure(value, options);
    }

    /** Throws with {@code message} when {@code value} is deeper than the option permits. */
    static void check(RiffleValue value, RiffleOptions options, String message) {
        measure(value, options, message);
    }

    private static int measure(RiffleValue value, RiffleOptions options) {
        return measure(value, options, null);
    }

    private static int measure(RiffleValue value, RiffleOptions options, String message) {
        Deque<RiffleValue> pending = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        pending.push(value);
        depths.push(0);
        int deepest = 0;
        while (!pending.isEmpty()) {
            RiffleValue current = pending.pop();
            int depth = depths.pop();
            if (current instanceof RiffleObject map) {
                if (map.entries().isEmpty()) {
                    continue;
                }
                int childDepth = depth + 1;
                deepest = Math.max(deepest, childDepth);
                if (options.exceeds(childDepth)) {
                    throw tooDeep(options, childDepth, message);
                }
                for (RiffleValue child : map.entries().values()) {
                    pending.push(child);
                    depths.push(childDepth);
                }
            } else if (current instanceof RiffleArray array) {
                if (array.values().isEmpty()) {
                    continue;
                }
                int childDepth = depth + 1;
                deepest = Math.max(deepest, childDepth);
                if (options.exceeds(childDepth)) {
                    throw tooDeep(options, childDepth, message);
                }
                for (int index = array.size() - 1; index >= 0; index--) {
                    pending.push(array.get(index));
                    depths.push(childDepth);
                }
            }
        }
        return deepest;
    }

    private static IllegalArgumentException tooDeep(RiffleOptions options, int depth, String message) {
        String detail = message == null ? "value tree is deeper than the configured limit" : message;
        return new IllegalArgumentException(detail + ": " + depth + " > " + options.depthLimit());
    }
}
