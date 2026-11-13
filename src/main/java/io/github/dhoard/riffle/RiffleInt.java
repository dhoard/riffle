package io.github.dhoard.riffle;

import java.util.Optional;

/** An immutable int value, written {@code int : 42}. */
public final class RiffleInt implements RiffleValue {
    private final int value;
    private final String path;
    private final RiffleLocation location;

    RiffleInt(int value, String path, RiffleLocation location) {
        this.value = value;
        this.path = path;
        this.location = location;
    }

    /**
     * Creates an int value.
     *
     * @param value the value
     * @return the value
     */
    public static RiffleInt of(int value) {
        return new RiffleInt(value, null, null);
    }

    /**
     * Returns the value.
     *
     * @return the value
     */
    public int value() {
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

    /** Int values are equal when their number is equal; locations and paths are ignored. */
    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleInt that && value == that.value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "RiffleInt[" + value + "]";
    }
}
