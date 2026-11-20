package io.github.dhoard.riffle;

/** Resource limits that are internal to value conversion. */
final class Limits {
    /** The longest plain decimal rendered from Java; longer numbers use exponent notation. */
    static final int MAX_PLAIN_DECIMAL = 256;

    private Limits() {}
}
