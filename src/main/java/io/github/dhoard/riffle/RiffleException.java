package io.github.dhoard.riffle;

/**
 * Base type for every configuration error raised by Riffle.
 *
 * <p>{@link RiffleParseException} reports a document that is not valid Riffle;
 * {@link RiffleAccessException} reports a value that is missing or cannot be read as the
 * requested type; {@link RiffleSchemaException} reports a schema document that cannot be used;
 * and {@link RiffleValidationException} reports a document that does not satisfy a schema.
 * Messages identify locations and key paths but never include scalar
 * values, so secrets held in configuration do not leak into diagnostics.</p>
 */
public abstract sealed class RiffleException extends RuntimeException
        permits RiffleParseException, RiffleAccessException, RiffleSchemaException, RiffleValidationException {
    private static final long serialVersionUID = 1L;

    /** The problem description without location or path. */
    private final String reason;

    RiffleException(String message, String reason) {
        super(message);
        this.reason = reason;
    }

    /**
     * Returns the problem description without location or path information.
     *
     * @return the reason
     */
    public String reason() {
        return reason;
    }
}
