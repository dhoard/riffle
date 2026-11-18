package io.github.dhoard.riffle;

import java.math.BigDecimal;
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
     * @return the element
     * @throws RiffleAccessException if the index is out of range
     */
    public RiffleValue get(int index) {
        if (index < 0 || index >= values.size()) {
            throw new RiffleAccessException(
                    RiffleAccessError.MISSING,
                    "index out of range for an array of size " + values.size(),
                    Syntax.elementPath(path, index),
                    location);
        }
        return values.get(index);
    }

    /**
     * Returns the string at {@code index}.
     *
     * @param index the zero-based index
     * @return the string
     * @throws RiffleAccessException if the index is out of range or the element is not a string
     */
    public String getString(int index) {
        return get(index).asString();
    }

    /**
     * Reads the string at {@code index} as a signed 32-bit integer.
     *
     * @param index the zero-based index
     * @return the int
     * @throws RiffleAccessException if the index is out of range or the element cannot be read as an int
     */
    public int getInt(int index) {
        return get(index).asInt();
    }

    /**
     * Reads the string at {@code index} as a signed 64-bit integer.
     *
     * @param index the zero-based index
     * @return the long
     * @throws RiffleAccessException if the index is out of range or the element cannot be read as a long
     */
    public long getLong(int index) {
        return get(index).asLong();
    }

    /**
     * Reads the string at {@code index} as an exact decimal.
     *
     * @param index the zero-based index
     * @return the decimal
     * @throws RiffleAccessException if the index is out of range or the element cannot be read as a decimal
     */
    public BigDecimal getDecimal(int index) {
        return get(index).asDecimal();
    }

    /**
     * Reads the string at {@code index} as a boolean.
     *
     * @param index the zero-based index
     * @return the boolean
     * @throws RiffleAccessException if the index is out of range or the element cannot be read as a boolean
     */
    public boolean getBoolean(int index) {
        return get(index).asBoolean();
    }

    /**
     * Returns the map at {@code index}.
     *
     * @param index the zero-based index
     * @return the map
     * @throws RiffleAccessException if the index is out of range or the element is not a map
     */
    public RiffleObject getObject(int index) {
        return get(index).asObject();
    }

    /**
     * Returns the array at {@code index}.
     *
     * @param index the zero-based index
     * @return the array
     * @throws RiffleAccessException if the index is out of range or the element is not an array
     */
    public RiffleArray getArray(int index) {
        return get(index).asArray();
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
