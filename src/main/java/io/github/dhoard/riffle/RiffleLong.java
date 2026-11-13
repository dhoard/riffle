package io.github.dhoard.riffle;

import java.util.Optional;

/** An immutable long value, written {@code long : 9007199254740993}. */
public final class RiffleLong implements RiffleValue {
    private final long value;
    private final String path;
    private final RiffleLocation location;

    RiffleLong(long value, String path, RiffleLocation location) {
        this.value = value;
        this.path = path;
        this.location = location;
    }

    /**
     * Creates a long value.
     *
     * @param value the value
     * @return the value
     */
    public static RiffleLong of(long value) {
        return new RiffleLong(value, null, null);
    }

    /**
     * Returns the value.
     *
     * @return the value
     */
    public long value() {
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

    /** Long values are equal when their number is equal; locations and paths are ignored. */
    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleLong that && value == that.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "RiffleLong[" + value + "]";
    }
}
