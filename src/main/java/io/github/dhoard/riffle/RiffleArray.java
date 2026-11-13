package io.github.dhoard.riffle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** An immutable ordered sequence of values. */
public final class RiffleArray implements RiffleContainer {
    private final List<RiffleValue> values;
    private final String path;
    private final RiffleLocation location;

    RiffleArray(List<RiffleValue> values, String path, RiffleLocation location) {
        this.values = List.copyOf(values);
        this.path = path;
        this.location = location;
    }

    /**
     * Creates an array holding a copy of {@code values}.
     *
     * @param values the elements
     * @return the array
     * @throws NullPointerException if the list or an element is null
     */
    public static RiffleArray of(List<? extends RiffleValue> values) {
        Objects.requireNonNull(values, "values");
        return new RiffleArray(List.copyOf(values), null, null);
    }

    /**
     * Returns the element at {@code index}.
     *
     * @param index the zero-based index
     * @return the element, which may be {@link RiffleNull}
     * @throws RiffleAccessException if the index is out of range
     */
    public RiffleValue get(int index) {
        if (index < 0 || index >= values.size()) {
            throw new RiffleAccessException(
                    "index out of range for an array of size " + values.size(),
                    Syntax.elementPath(path, index),
                    location);
        }
        return values.get(index);
    }

    /**
     * Returns the number of elements.
     *
     * @return the size
     */
    public int size() {
        return values.size();
    }

    /**
     * Returns the elements in document order.
     *
     * @return an unmodifiable list
     */
    public List<RiffleValue> values() {
        return values;
    }

    @Override
    public Optional<RiffleLocation> location() {
        return Optional.ofNullable(location);
    }

    @Override
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /** Arrays are equal when their elements are equal in order. */
    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleArray that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "RiffleArray" + values;
    }
}
