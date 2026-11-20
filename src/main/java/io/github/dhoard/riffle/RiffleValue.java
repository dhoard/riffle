package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * A value in a Riffle document: a string, a map, or an array.
 *
 * <p>A document never declares a number or a boolean. The program decides how to read a string
 * through the {@code asX()} accessors, each of which accepts exactly the forms defined by the
 * language specification, whether the value was written bare, quoted, or as a multiline block.</p>
 *
 * <p>Each accessor has a matching {@code isX()} predicate that never throws and returns
 * {@code true} exactly when the accessor would succeed.</p>
 */
public sealed interface RiffleValue permits RiffleContainer, RiffleString {

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
     * @return the path; the root path for a document root; empty for values constructed in code
     */
    Optional<RifflePath> path();

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
     * Returns whether {@link #asInt()} succeeds.
     *
     * @return {@code true} for a string holding an integer in the signed 32-bit range
     */
    default boolean isInt() {
        return Conversions.isInt(this);
    }

    /**
     * Returns whether {@link #asLong()} succeeds.
     *
     * @return {@code true} for a string holding an integer in the signed 64-bit range
     */
    default boolean isLong() {
        return Conversions.isLong(this);
    }

    /**
     * Returns whether {@link #asDecimal()} succeeds.
     *
     * @return {@code true} for a string holding a decimal within the exponent range
     */
    default boolean isDecimal() {
        return Conversions.isDecimal(this);
    }

    /**
     * Returns whether {@link #asBoolean()} succeeds.
     *
     * @return {@code true} for the strings {@code true} and {@code false}
     */
    default boolean isBoolean() {
        return Conversions.isBoolean(this);
    }

    /**
     * Returns this value as a map.
     *
     * @return this map
     * @throws RiffleAccessException with {@link RiffleAccessError#WRONG_KIND} if this value is not
     *     a map
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
     * @throws RiffleAccessException with {@link RiffleAccessError#WRONG_KIND} if this value is not
     *     an array
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
     * @throws RiffleAccessException with {@link RiffleAccessError#WRONG_KIND} if this value is not
     *     a string
     */
    default String asString() {
        if (this instanceof RiffleString string) {
            return string.text();
        }
        throw Conversions.wrongKind(this, "string");
    }

    /**
     * Reads this string as a signed 32-bit integer.
     *
     * @return the integer
     * @throws RiffleAccessException with {@link RiffleAccessError#WRONG_KIND} for a map or array,
     *     {@link RiffleAccessError#INVALID_FORMAT} if the text is not an integer, or
     *     {@link RiffleAccessError#OUT_OF_RANGE} if the integer does not fit in 32 bits
     */
    default int asInt() {
        return Conversions.toInt(this);
    }

    /**
     * Reads this string as a signed 64-bit integer.
     *
     * @return the integer
     * @throws RiffleAccessException with {@link RiffleAccessError#WRONG_KIND} for a map or array,
     *     {@link RiffleAccessError#INVALID_FORMAT} if the text is not an integer, or
     *     {@link RiffleAccessError#OUT_OF_RANGE} if the integer does not fit in 64 bits
     */
    default long asLong() {
        return Conversions.toLong(this);
    }

    /**
     * Reads this string as an exact decimal, normalized as by
     * {@link BigDecimal#stripTrailingZeros()}. The text never passes through binary floating point.
     *
     * @return the decimal
     * @throws RiffleAccessException with {@link RiffleAccessError#WRONG_KIND} for a map or array,
     *     {@link RiffleAccessError#INVALID_FORMAT} if the text is not a decimal, or
     *     {@link RiffleAccessError#OUT_OF_RANGE} if the normalized exponent is out of range
     */
    default BigDecimal asDecimal() {
        return Conversions.toDecimal(this);
    }

    /**
     * Reads this string as a boolean. Only the lowercase strings {@code true} and {@code false}
     * are accepted.
     *
     * @return the boolean
     * @throws RiffleAccessException with {@link RiffleAccessError#WRONG_KIND} for a map or array,
     *     or {@link RiffleAccessError#INVALID_FORMAT} for any other text
     */
    default boolean asBoolean() {
        return Conversions.toBoolean(this);
    }
}
