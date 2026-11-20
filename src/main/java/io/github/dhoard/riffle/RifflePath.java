package io.github.dhoard.riffle;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The location of a value inside a parsed document, as the path syntax of LANGUAGE_SPEC.md §10.1.
 *
 * <p>A path is a key sequence and index sequence applied to an implicit root: {@code a.b[2].c}.
 * It has no value semantics of its own, and the segments it holds need not exist in any document;
 * a path that names a missing member simply fails to access.</p>
 *
 * <p>Paths are printed inside schema and host-facing diagnostics, which is the only place their
 * syntax is defined. Every segment that can be written without quoting prints bare, so a
 * diagnostic never prints an empty quoted segment, and a segment that cannot prints quoted. A path
 * is not a key, so printing one in a document context is not always valid Riffle.</p>
 *
 * <p>Segments are linked to their parent, so extending a path is constant time even at great depth.
 * The rendered text and segment list are cached after first use.</p>
 *
 * <p>Instances are immutable and safe for concurrent use.</p>
 *
 * @see <a href="https://dhoard.github.io/riffle/spec/LANGUAGE_SPEC.html#paths">LANGUAGE_SPEC.md §10.1</a>
 */
public final class RifflePath implements Serializable {

    private static final long serialVersionUID = 2L;

    /** The path of the value a parse produced, which has no segments. */
    public static final RifflePath ROOT = new RifflePath(null, null, 0);

    /** The path one segment shorter, or null at the root. */
    private final RifflePath parent;
    /** The final segment, or null at the root. */
    private final Segment segment;
    /** The number of segments. */
    private final int depth;

    private transient List<Segment> segments;
    private transient String rendered;

    private RifflePath(RifflePath parent, Segment segment, int depth) {
        this.parent = parent;
        this.segment = segment;
        this.depth = depth;
    }

    /**
     * Returns the root path, the path of the value a parse produced.
     *
     * @return the root path, which has no segments
     */
    public static RifflePath root() {
        return ROOT;
    }

    /**
     * Returns a path with one more key segment.
     *
     * @param name the key name, which may be empty and may contain any character
     * @return the extended path
     */
    public RifflePath key(String name) {
        Objects.requireNonNull(name, "name");
        return new RifflePath(this, new Key(name), depth + 1);
    }

    /**
     * Returns a path with one more index segment.
     *
     * @param index the element index, which may be negative when it names an out-of-range element
     * @return the extended path
     */
    public RifflePath index(int index) {
        return new RifflePath(this, new Index(index), depth + 1);
    }

    /**
     * Returns this path, used when a path is taken from a value that has none.
     *
     * @param path the path a value carries, or {@code null} for a value that has none
     * @return the given path, or the root path when it is {@code null}
     */
    public static RifflePath orRoot(RifflePath path) {
        return path == null ? ROOT : path;
    }

    /**
     * Returns the segments of this path, outermost first.
     *
     * @return an unmodifiable list of segments, empty for the root path
     */
    public List<Segment> segments() {
        List<Segment> result = segments;
        if (result == null) {
            RifflePath[] chain = new RifflePath[depth];
            RifflePath current = this;
            for (int i = depth - 1; i >= 0; i--) {
                chain[i] = current;
                current = current.parent;
            }
            List<Segment> built = new ArrayList<>(depth);
            for (RifflePath step : chain) {
                built.add(step.segment);
            }
            result = Collections.unmodifiableList(built);
            segments = result;
        }
        return result;
    }

    /**
     * Returns whether this is the root path, which has no segments.
     *
     * @return true when there are no segments
     */
    public boolean isRoot() {
        return depth == 0;
    }

    /**
     * Returns the number of segments.
     *
     * @return the depth of the path
     */
    public int depth() {
        return depth;
    }

    /**
     * Returns this path with its last segment removed.
     *
     * @return the parent path
     * @throws IllegalStateException if this is the root path
     */
    public RifflePath parent() {
        if (depth == 0) {
            throw new IllegalStateException("the root path has no parent");
        }
        return parent;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RifflePath that) || depth != that.depth) {
            return false;
        }
        return segments().equals(that.segments());
    }

    @Override
    public int hashCode() {
        return segments().hashCode();
    }

    /**
     * Returns this path in the path syntax of LANGUAGE_SPEC.md §10.1.
     *
     * @return the path as text, or {@code (root)} for the root path
     */
    @Override
    public String toString() {
        String result = rendered;
        if (result == null) {
            if (depth == 0) {
                result = "(root)";
            } else {
                StringBuilder text = new StringBuilder();
                for (Segment part : segments()) {
                    render(part, text);
                }
                result = text.toString();
            }
            rendered = result;
        }
        return result;
    }

    /** Renders one segment in the syntax of LANGUAGE_SPEC.md §10.1, given the text before it. */
    private static void render(Segment segment, StringBuilder text) {
        if (segment instanceof Key key) {
            if (text.length() > 0) {
                text.append('.');
            }
            if (Syntax.isBarePathKey(key.key())) {
                text.append(key.key());
            } else {
                text.append(Syntax.quoteKey(key.key()));
            }
        } else if (segment instanceof Index index) {
            text.append('[').append(index.index()).append(']');
        }
    }

    /**
     * One segment of a path: either a key name or an element index.
     *
     * @see <a href="https://dhoard.github.io/riffle/spec/LANGUAGE_SPEC.html#paths">LANGUAGE_SPEC.md §10.1</a>
     */
    public sealed interface Segment extends Serializable permits Key, Index {}

    /**
     * A key segment.
     *
     * @param key the key name, which may be empty and may contain any character
     */
    public record Key(String key) implements Segment {

        private static final long serialVersionUID = 1L;

        /**
         * Returns this segment in the syntax of LANGUAGE_SPEC.md §10.1, on its own.
         *
         * @return the segment as text
         */
        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            render(this, text);
            return text.toString();
        }
    }

    /**
     * An element index segment.
     *
     * @param index the element index, which may be negative when it names an out-of-range element
     */
    public record Index(int index) implements Segment {

        private static final long serialVersionUID = 1L;

        /**
         * Returns this segment in the syntax of LANGUAGE_SPEC.md §10.1, on its own.
         *
         * @return the segment as text
         */
        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            render(this, text);
            return text.toString();
        }
    }
}
