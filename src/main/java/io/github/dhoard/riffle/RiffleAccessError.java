package io.github.dhoard.riffle;

/** Stable error categories reported by {@link RiffleAccessException}. */
public enum RiffleAccessError {
    /** A required key or index is absent. */
    MISSING,
    /** A string was requested from a map or an array, or a collection from a string. */
    WRONG_KIND,
    /** The string does not match the grammar of the requested interpretation. */
    INVALID_FORMAT,
    /** The string matches the grammar but is outside the requested range. */
    OUT_OF_RANGE,
    /** An application-defined conversion failed. */
    INVALID_VALUE
}
