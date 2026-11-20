package io.github.dhoard.riffle;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Optional resource controls for operations that read or build a Riffle value tree.
 *
 * <p>By default Riffle has no nesting limit: an implementation must traverse a tree iteratively so
 * that depth is bounded only by available memory. A host that reads untrusted documents can pass
 * {@link #maxDepth(int)} to reject trees deeper than it is willing to hold. The same option applies
 * to writing, validation, defaults, and Java-value conversion, so a tree built after parsing cannot
 * evade a caller's limit.</p>
 *
 * <p>The cap counts nonempty collections between the root map and a value. The root map itself, an
 * empty collection, and a raw block do not count, matching the counting rule formerly used by the
 * language specification. Instances are immutable and safe to share between threads.</p>
 */
public final class RiffleOptions {

    /** The default: no nesting cap. */
    public static final RiffleOptions UNBOUNDED = new RiffleOptions(Integer.MAX_VALUE);

    private final int maxDepth;

    private RiffleOptions(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /**
     * Returns options with a maximum nesting depth.
     *
     * @param maxDepth the largest number of nonempty collections between the root map and a value;
     *     zero permits only scalar members of the root map
     * @return the options
     * @throws IllegalArgumentException if {@code maxDepth} is negative
     */
    public static RiffleOptions maxDepth(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative");
        }
        return maxDepth == Integer.MAX_VALUE ? UNBOUNDED : new RiffleOptions(maxDepth);
    }

    /**
     * Returns the configured cap, or empty when nesting is unbounded.
     *
     * @return the cap
     */
    public OptionalInt maxDepth() {
        return maxDepth == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(maxDepth);
    }

    /** Returns whether {@code depth} exceeds the configured cap. */
    boolean exceeds(int depth) {
        return depth > maxDepth;
    }

    /** Returns the cap as an int, with {@link Integer#MAX_VALUE} meaning unbounded. */
    int depthLimit() {
        return maxDepth;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleOptions that && maxDepth == that.maxDepth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDepth);
    }

    @Override
    public String toString() {
        return maxDepth == Integer.MAX_VALUE ? "RiffleOptions[UNBOUNDED]" : "RiffleOptions[maxDepth=" + maxDepth + "]";
    }
}
