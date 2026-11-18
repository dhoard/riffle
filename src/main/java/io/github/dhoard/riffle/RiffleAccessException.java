package io.github.dhoard.riffle;

import java.util.Optional;

/**
 * A value is missing or cannot be read as the requested type.
 *
 * <p>The message has the form {@code location: path: reason}; the location is omitted for
 * values that were constructed in code rather than parsed, and the path is omitted when it
 * is unknown. The root path is shown as {@code (root)}.</p>
 */
public final class RiffleAccessException extends RiffleException {
    private static final long serialVersionUID = 1L;

    /** The stable error category. */
    private final RiffleAccessError category;
    /** The key path, or {@code null} when unknown. */
    private final String path;
    /** The source location, or {@code null} for constructed values. */
    private final RiffleLocation location;

    /**
     * Reports that {@code value} cannot be used, taking the path and source location from the
     * value so the message has the same form as Riffle's own access errors. The category is
     * {@link RiffleAccessError#INVALID_VALUE}. Applications use this for their own conversions,
     * such as durations or colors:
     *
     * <pre>{@code
     * static Duration duration(RiffleValue value) {
     *     try {
     *         return Duration.parse(value.asString());
     *     } catch (DateTimeParseException e) {
     *         throw new RiffleAccessException(value, "expected an ISO-8601 duration such as PT30S");
     *     }
     * }
     * }</pre>
     *
     * <p>Like Riffle's own messages, {@code reason} should describe the expected form without
     * repeating the value, so that secrets held in configuration do not reach logs.</p>
     *
     * @param value the value that could not be used
     * @param reason what was expected, without the value itself
     * @throws NullPointerException if {@code value} or {@code reason} is null
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public RiffleAccessException(RiffleValue value, String reason) {
        this(
                RiffleAccessError.INVALID_VALUE,
                checkReason(reason),
                value.path().orElse(null),
                value.location().orElse(null));
    }

    RiffleAccessException(RiffleAccessError category, String reason, String path, RiffleLocation location) {
        super(format(reason, path, location), reason);
        this.category = category;
        this.path = path;
        this.location = location;
    }

    /**
     * Returns the stable error category.
     *
     * @return the category
     */
    public RiffleAccessError category() {
        return category;
    }

    /**
     * Returns the key path of the value, such as {@code capabilities.reasoning[2]}.
     *
     * @return the path; the root is the empty string; empty when unknown
     */
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /**
     * Returns the source location of the value, or of the container for a missing value.
     *
     * @return the location, or empty for values constructed in code
     */
    public Optional<RiffleLocation> location() {
        return Optional.ofNullable(location);
    }

    private static String checkReason(String reason) {
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason is blank");
        }
        return reason;
    }

    private static String format(String reason, String path, RiffleLocation location) {
        StringBuilder message = new StringBuilder();
        if (location != null) {
            message.append(location).append(": ");
        }
        if (path != null) {
            message.append(path.isEmpty() ? "(root)" : path).append(": ");
        }
        return message.append(reason).toString();
    }
}
