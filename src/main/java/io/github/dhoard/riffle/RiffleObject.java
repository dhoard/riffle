package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** An immutable, insertion-ordered map from string keys to values. */
public final class RiffleObject implements RiffleContainer {
    private final Map<String, RiffleValue> entries;
    private final String path;
    private final RiffleLocation location;

    RiffleObject(LinkedHashMap<String, RiffleValue> entries, String path, RiffleLocation location) {
        this.entries = Collections.unmodifiableMap(entries);
        this.path = path;
        this.location = location;
    }

    /**
     * Creates a map holding a copy of {@code entries}, in their iteration order.
     *
     * @param entries the entries
     * @return the map
     * @throws IllegalArgumentException if a key contains an unpaired surrogate or U+0000
     * @throws NullPointerException if the map, a key, or a value is null
     */
    public static RiffleObject of(Map<String, ? extends RiffleValue> entries) {
        Objects.requireNonNull(entries, "entries");
        LinkedHashMap<String, RiffleValue> copy = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            Objects.requireNonNull(key, "key");
            if (Syntax.hasUnpairedSurrogate(key)) {
                throw new IllegalArgumentException("key contains an unpaired surrogate");
            }
            if (key.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("key contains U+0000");
            }
            copy.put(key, Objects.requireNonNull(value, "value"));
        });
        return new RiffleObject(copy, null, null);
    }

    /**
     * Returns the value of a required key.
     *
     * @param key the key
     * @return the value
     * @throws RiffleAccessException if the key is missing
     */
    public RiffleValue get(String key) {
        Objects.requireNonNull(key, "key");
        RiffleValue value = entries.get(key);
        if (value == null) {
            throw new RiffleAccessException(
                    RiffleAccessError.MISSING, "missing", Syntax.memberPath(path, key), location);
        }
        return value;
    }

    /**
     * Returns the value of an optional key.
     *
     * @param key the key
     * @return the value, or empty when the key is missing
     */
    public Optional<RiffleValue> find(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Returns the string value of a required key.
     *
     * @param key the key
     * @return the string
     * @throws RiffleAccessException if the key is missing or its value is not a string
     */
    public String getString(String key) {
        return get(key).asString();
    }

    /**
     * Reads the string value of a required key as a signed 32-bit integer.
     *
     * @param key the key
     * @return the int
     * @throws RiffleAccessException if the key is missing or its value cannot be read as an int
     */
    public int getInt(String key) {
        return get(key).asInt();
    }

    /**
     * Reads the string value of a required key as a signed 64-bit integer.
     *
     * @param key the key
     * @return the long
     * @throws RiffleAccessException if the key is missing or its value cannot be read as a long
     */
    public long getLong(String key) {
        return get(key).asLong();
    }

    /**
     * Reads the string value of a required key as an exact decimal.
     *
     * @param key the key
     * @return the decimal
     * @throws RiffleAccessException if the key is missing or its value cannot be read as a decimal
     */
    public BigDecimal getDecimal(String key) {
        return get(key).asDecimal();
    }

    /**
     * Reads the string value of a required key as a boolean.
     *
     * @param key the key
     * @return the boolean
     * @throws RiffleAccessException if the key is missing or its value cannot be read as a boolean
     */
    public boolean getBoolean(String key) {
        return get(key).asBoolean();
    }

    /**
     * Returns the map value of a required key.
     *
     * @param key the key
     * @return the map
     * @throws RiffleAccessException if the key is missing or its value is not a map
     */
    public RiffleObject getObject(String key) {
        return get(key).asObject();
    }

    /**
     * Returns the array value of a required key.
     *
     * @param key the key
     * @return the array
     * @throws RiffleAccessException if the key is missing or its value is not an array
     */
    public RiffleArray getArray(String key) {
        return get(key).asArray();
    }

    /**
     * Returns the entries in document order.
     *
     * @return an unmodifiable map
     */
    public Map<String, RiffleValue> entries() {
        return entries;
    }

    @Override
    public Optional<RiffleLocation> location() {
        return Optional.ofNullable(location);
    }

    @Override
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /**
     * Maps are equal when they map the same keys to equal values, following the
     * {@link Map#equals(Object)} contract: entry order does not affect equality.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleObject that && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "RiffleObject" + entries;
    }
}
