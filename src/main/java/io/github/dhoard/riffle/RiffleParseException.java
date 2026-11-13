package io.github.dhoard.riffle;

import java.util.Objects;

/**
 * A document is not valid Riffle. The message has the form {@code location: reason}, and the
 * category is one of the stable {@link RiffleError} values.
 */
public final class RiffleParseException extends RiffleException {
    private static final long serialVersionUID = 1L;

    /** The stable error category. */
    private final RiffleError category;
    /** Where the problem was found. */
    private final RiffleLocation location;

    RiffleParseException(RiffleError category, String reason, RiffleLocation location) {
        super(Objects.requireNonNull(location, "location") + ": " + reason, reason);
        this.category = Objects.requireNonNull(category, "category");
        this.location = location;
    }

    /**
     * Returns the stable error category.
     *
     * @return the category
     */
    public RiffleError category() {
        return category;
    }

    /**
     * Returns where the problem was found.
     *
     * @return the location
     */
    public RiffleLocation location() {
        return location;
    }
}
