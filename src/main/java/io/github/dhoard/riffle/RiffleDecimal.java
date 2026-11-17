package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable arbitrary-precision decimal value, written {@code @decimal 0.25}.
 *
 * <p>Values are normalized to their numeric value, so {@code 1.0} and {@code 1.00} are equal
 * and are written identically.</p>
 */
public final class RiffleDecimal implements RiffleValue {
    private final BigDecimal value;
    private final String path;
    private final RiffleLocation location;

    RiffleDecimal(BigDecimal value, String path, RiffleLocation location) {
        this.value = value;
        this.path = path;
        this.location = location;
    }

    /**
     * Creates a decimal value.
     *
     * @param value the value
     * @return the value
     * @throws NullPointerException if the value is null
     * @throws IllegalArgumentException if {@code value} cannot be normalized within
     *     {@link BigDecimal}'s exponent range, as when stripping trailing zeros would move the
     *     scale past {@link Integer#MIN_VALUE}
     */
    public static RiffleDecimal of(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        try {
            return new RiffleDecimal(normalize(value), null, null);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("decimal value is outside the supported exponent range", e);
        }
    }

    /**
     * Returns {@code value} without trailing zeros, so that equal numbers are equal values.
     *
     * @throws ArithmeticException if the normalized scale is outside {@code int} range
     */
    static BigDecimal normalize(BigDecimal value) {
        // stripTrailingZeros can produce a negative scale (1E+3); keep the numeric value.
        return value.stripTrailingZeros();
    }

    /**
     * Returns the value.
     *
     * @return the value
     */
    public BigDecimal value() {
        return value;
    }

    @Override
    public Optional<RiffleLocation> location() {
        return Optional.ofNullable(location);
    }

    @Override
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /** Decimal values are equal when their number is equal; locations and paths are ignored. */
    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleDecimal that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "RiffleDecimal[" + value + "]";
    }
}
