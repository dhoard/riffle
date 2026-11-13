package io.github.dhoard.riffle;

import java.util.Optional;

/**
 * The null value, written {@code null}.
 *
 * <p>All null values are equal. Code should use {@link #INSTANCE}; parsed nulls are distinct
 * instances only so that they can report their source location.</p>
 */
public final class RiffleNull implements RiffleValue {
    /** The null value for use in code. */
    public static final RiffleNull INSTANCE = new RiffleNull(null, null);

    private final String path;
    private final RiffleLocation location;

    RiffleNull(String path, RiffleLocation location) {
        this.path = path;
        this.location = location;
    }

    @Override
    public Optional<RiffleLocation> location() {
        return Optional.ofNullable(location);
    }

    @Override
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleNull;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "RiffleNull";
    }
}
