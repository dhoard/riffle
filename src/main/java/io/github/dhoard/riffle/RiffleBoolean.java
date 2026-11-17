package io.github.dhoard.riffle;

import java.util.Optional;

/** An immutable boolean value, written {@code @boolean true}. */
public final class RiffleBoolean implements RiffleValue {
    private final boolean value;
    private final String path;
    private final RiffleLocation location;

    RiffleBoolean(boolean value, String path, RiffleLocation location) {
        this.value = value;
        this.path = path;
        this.location = location;
    }

    /**
     * Creates a boolean value.
     *
     * @param value the value
     * @return the value
     */
    public static RiffleBoolean of(boolean value) {
        return new RiffleBoolean(value, null, null);
    }

    /**
     * Returns the value.
     *
     * @return the value
     */
    public boolean value() {
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

    /** Boolean values are equal when their value is equal; locations and paths are ignored. */
    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleBoolean that && value == that.value;
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(value);
    }

    @Override
    public String toString() {
        return "RiffleBoolean[" + value + "]";
    }
}
