package io.github.dhoard.riffle;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * A one-based position in a Riffle source document.
 *
 * <p>Columns count UTF-16 code units from the start of the physical line. Locations are
 * diagnostic metadata only: they never participate in value equality.</p>
 */
public final class RiffleLocation implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The source name, or {@code null} when unknown. */
    private final String source;
    /** The one-based line. */
    private final int line;
    /** The one-based column. */
    private final int column;

    RiffleLocation(String source, int line, int column) {
        this.source = source;
        this.line = line;
        this.column = column;
    }

    /**
     * Returns the name of the source document, such as a file path, when one is known.
     *
     * @return the source name, or empty for documents parsed from a string or reader
     */
    public Optional<String> source() {
        return Optional.ofNullable(source);
    }

    /**
     * Returns the one-based line number.
     *
     * @return the line number
     */
    public int line() {
        return line;
    }

    /**
     * Returns the one-based column number.
     *
     * @return the column number
     */
    public int column() {
        return column;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleLocation that
                && line == that.line
                && column == that.column
                && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, line, column);
    }

    /**
     * Formats the location as {@code source:line:column}, or {@code line L, column C} when the
     * source name is unknown.
     */
    @Override
    public String toString() {
        return source == null ? "line " + line + ", column " + column : source + ":" + line + ":" + column;
    }
}
