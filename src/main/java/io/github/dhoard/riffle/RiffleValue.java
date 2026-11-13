package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * A value in a Riffle document: a map, an array, a string, an int, a long, a decimal, a
 * boolean, or null.
 *
 * <p>Every scalar carries the kind the document declared, so a document states its own types.
 * The {@code asX()} accessors are strict: each accepts exactly its own kind, and no conversion
 * between kinds is performed. {@code asInt()} on a long fails, and {@code asString()} on a
 * number fails.</p>
 *
 * <p>Each accessor has a matching {@code isX()} predicate that never throws and returns
 * {@code true} exactly when the accessor would succeed.</p>
 */
public sealed interface RiffleValue
        permits RiffleContainer, RiffleString, RiffleInt, RiffleLong, RiffleDecimal, RiffleBoolean, RiffleNull {

    /**
     * Returns where this value was parsed.
     *
     * @return the location, or empty for values constructed in code
     */
    Optional<RiffleLocation> location();

    /**
     * Returns the key path of this value within its parsed document, such as
     * {@code capabilities.reasoning[2]}.
     *
     * @return the path; the root is the empty string; empty for values constructed in code
     */
    Optional<String> path();

    /**
     * Returns whether this value is null ({@code @null} in a document).
     *
     * @return {@code true} for null
     */
    default boolean isNull() {
        return this instanceof RiffleNull;
    }

    /**
     * Returns whether this value is a map, so that {@link #asObject()} succeeds.
     *
     * @return {@code true} for a map
     */
    default boolean isObject() {
        return this instanceof RiffleObject;
    }

    /**
     * Returns whether this value is an array, so that {@link #asArray()} succeeds.
     *
     * @return {@code true} for an array
     */
    default boolean isArray() {
        return this instanceof RiffleArray;
    }

    /**
     * Returns whether this value is a string, so that {@link #asString()} succeeds.
     *
     * @return {@code true} for a string
     */
    default boolean isString() {
        return this instanceof RiffleString;
    }

    /**
     * Returns whether this value is an int, so that {@link #asInt()} succeeds.
     *
     * @return {@code true} for an int
     */
    default boolean isInt() {
        return this instanceof RiffleInt;
    }

    /**
     * Returns whether this value is a long, so that {@link #asLong()} succeeds.
     *
     * @return {@code true} for a long
     */
    default boolean isLong() {
        return this instanceof RiffleLong;
    }

    /**
     * Returns whether this value is a decimal, so that {@link #asDecimal()} succeeds.
     *
     * @return {@code true} for a decimal
     */
    default boolean isDecimal() {
        return this instanceof RiffleDecimal;
    }

    /**
     * Returns whether this value is a boolean, so that {@link #asBoolean()} succeeds.
     *
     * @return {@code true} for a boolean
     */
    default boolean isBoolean() {
        return this instanceof RiffleBoolean;
    }

    /**
     * Returns this value as a map.
     *
     * @return this map
     * @throws RiffleAccessException if this value is not a map
     */
    default RiffleObject asObject() {
        if (this instanceof RiffleObject object) {
            return object;
        }
        throw Conversions.wrongKind(this, "map");
    }

    /**
     * Returns this value as an array.
     *
     * @return this array
     * @throws RiffleAccessException if this value is not an array
     */
    default RiffleArray asArray() {
        if (this instanceof RiffleArray array) {
            return array;
        }
        throw Conversions.wrongKind(this, "array");
    }

    /**
     * Returns the text of this string value.
     *
     * @return the string
     * @throws RiffleAccessException if this value is not a string
     */
    default String asString() {
        if (this instanceof RiffleString string) {
            return string.text();
        }
        throw Conversions.wrongKind(this, "string");
    }

    /**
     * Returns this value as an {@code int}.
     *
     * @return the int
     * @throws RiffleAccessException if this value is not an int
     */
    default int asInt() {
        if (this instanceof RiffleInt value) {
            return value.value();
        }
        throw Conversions.wrongKind(this, "int");
    }

    /**
     * Returns this value as a {@code long}.
     *
     * @return the long
     * @throws RiffleAccessException if this value is not a long
     */
    default long asLong() {
        if (this instanceof RiffleLong value) {
            return value.value();
        }
        throw Conversions.wrongKind(this, "long");
    }

    /**
     * Returns this value as a {@code BigDecimal}.
     *
     * @return the decimal
     * @throws RiffleAccessException if this value is not a decimal
     */
    default BigDecimal asDecimal() {
        if (this instanceof RiffleDecimal value) {
            return value.value();
        }
        throw Conversions.wrongKind(this, "decimal");
    }

    /**
     * Returns this value as a {@code boolean}.
     *
     * @return the boolean
     * @throws RiffleAccessException if this value is not a boolean
     */
    default boolean asBoolean() {
        if (this instanceof RiffleBoolean value) {
            return value.value();
        }
        throw Conversions.wrongKind(this, "boolean");
    }
}
