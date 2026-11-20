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
    private final Map<String, RiffleLocation> keyLocations;
    private final RifflePath path;
    private final RiffleLocation location;

    RiffleObject(
            LinkedHashMap<String, RiffleValue> entries,
            LinkedHashMap<String, RiffleLocation> keyLocations,
            RifflePath path,
            RiffleLocation location) {
        this.entries = Collections.unmodifiableMap(entries);
        this.keyLocations = keyLocations == null ? Map.of() : Collections.unmodifiableMap(keyLocations);
        this.path = path;
        this.location = location;
    }

    /**
     * Creates a map holding a copy of {@code entries}, in their iteration order. The values have
     * no source location and no path, because they were built in code.
     *
     * @param entries the entries
     * @return the map
     * @throws IllegalArgumentException if a key is not a Riffle key, because such a map could not be
     *         written as a document that parses again; a key that holds an unpaired surrogate is
     *         reported as such
     * @throws NullPointerException if the map, a key, or a value is null
     */
    public static RiffleObject of(Map<String, ? extends RiffleValue> entries) {
        Objects.requireNonNull(entries, "entries");
        LinkedHashMap<String, RiffleValue> copy = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            Objects.requireNonNull(key, "key");
            // The key alphabet below is the whole rule: it rejects a surrogate as surely as anything
            // else outside it. This check runs first only to name the likelier cause for a host
            // string that arrived as mojibake, and neither message quotes the offending key.
            if (Syntax.hasUnpairedSurrogate(key)) {
                throw new IllegalArgumentException("key contains an unpaired surrogate");
            }
            if (!Syntax.isValidKey(key)) {
                throw new IllegalArgumentException("key must match [A-Za-z0-9_][A-Za-z0-9_.-]*");
            }
            copy.put(key, Objects.requireNonNull(value, "value"));
        });
        return new RiffleObject(copy, null, null, null);
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
                    RiffleAccessError.MISSING,
                    "missing",
                    RifflePath.orRoot(path).key(key),
                    location);
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

    /**
     * Returns where {@code key} was written, for diagnostics that report a key rather than its
     * value. Keys of a map built in code have no location.
     */
    RiffleLocation keyLocation(String key) {
        return keyLocations.get(key);
    }

    @Override
    public Optional<RiffleLocation> location() {
        return Optional.ofNullable(location);
    }

    @Override
    public Optional<RifflePath> path() {
        return Optional.ofNullable(path);
    }

    /**
     * Maps are equal when they map the same keys to equal values, following the
     * {@link Map#equals(Object)} contract: entry order does not affect equality.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleObject that && ValueEquality.equal(this, that);
    }

    @Override
    public int hashCode() {
        return ValueHash.hash(this);
    }

    @Override
    public String toString() {
        return ValuePrinter.toString(this);
    }
}
