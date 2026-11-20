package io.github.dhoard.riffle;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * One violation of a schema: of a document against a schema, or of a schema document itself.
 *
 * <p>A problem names the category, the path of the value, key, or missing member it is about, the
 * source location when one is known, and a human-readable reason. The reason never holds a data
 * value, because configuration holds secrets; it may quote the schema.</p>
 *
 * <p>Problems are immutable and compare by value, so a test can compare an expected problem list
 * directly. The message has the form {@code location: path: reason}, omitting a part that is not
 * known.</p>
 */
public final class RiffleProblem implements Serializable {

    private static final long serialVersionUID = 1L;
    /** The stable category. */
    private final RiffleValidationError category;
    /** The path of the value, key, or missing member. */
    private final RifflePath path;
    /** The source location, or {@code null} for a value built in code. */
    private final RiffleLocation location;
    /** The reason, which never holds a data value. */
    private final String reason;

    RiffleProblem(RiffleValidationError category, RifflePath path, RiffleLocation location, String reason) {
        this.category = Objects.requireNonNull(category, "category");
        this.path = Objects.requireNonNull(path, "path");
        this.location = location;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the stable category of the problem.
     *
     * @return the category
     */
    public RiffleValidationError category() {
        return category;
    }

    /**
     * Returns the path of the value, key, or missing member the problem is about.
     *
     * @return the path, which is the root path for a problem about the document itself
     */
    public RifflePath path() {
        return path;
    }

    /**
     * Returns where the offending text was written.
     *
     * @return the location, or empty for a value or key that was built in code
     */
    public Optional<RiffleLocation> location() {
        return Optional.ofNullable(location);
    }

    /**
     * Returns the reason, which names the constraint that failed and never repeats a data value.
     *
     * @return the reason
     */
    public String reason() {
        return reason;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleProblem that
                && category == that.category
                && path.equals(that.path)
                && Objects.equals(location, that.location)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, path, location, reason);
    }

    /**
     * Formats the problem as {@code location: path: reason}, omitting the location when it is not
     * known. The path always appears, and the root renders as {@code (root)}.
     */
    @Override
    public String toString() {
        StringBuilder message = new StringBuilder();
        if (location != null) {
            message.append(location).append(": ");
        }
        return message.append(path).append(": ").append(reason).toString();
    }
}
