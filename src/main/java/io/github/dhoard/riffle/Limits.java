package io.github.dhoard.riffle;

/**
 * Documented resource limits shared by the parser and the writer, so that the two cannot drift
 * apart: the parser rejects a document nested deeper than {@link #MAX_DEPTH}, and the writer
 * refuses to produce such a document in the first place.
 */
final class Limits {
    /**
     * The maximum number of nested nonempty collections. A collection written inline as
     * {@code {}} or {@code []} does not open a block and therefore does not count.
     */
    static final int MAX_DEPTH = 128;

    /** The longest plain decimal the writer emits; longer numbers use exponent notation. */
    static final int MAX_PLAIN_DECIMAL = 256;

    private Limits() {}
}
