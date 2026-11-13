package io.github.dhoard.riffle;

/** Shared helpers for the {@link RiffleValue} accessors. */
final class Conversions {
    private Conversions() {}

    /** Returns the name of the kind of {@code value}, for diagnostics. */
    static String kind(RiffleValue value) {
        if (value instanceof RiffleObject) {
            return "map";
        }
        if (value instanceof RiffleArray) {
            return "array";
        }
        if (value instanceof RiffleString) {
            return "string";
        }
        if (value instanceof RiffleInt) {
            return "int";
        }
        if (value instanceof RiffleLong) {
            return "long";
        }
        if (value instanceof RiffleDecimal) {
            return "decimal";
        }
        if (value instanceof RiffleBoolean) {
            return "boolean";
        }
        return "null";
    }

    /** Reports that {@code value} has a different kind than the requested one. */
    static RiffleAccessException wrongKind(RiffleValue value, String type) {
        return new RiffleAccessException(value, "expected " + type + " but was " + kind(value));
    }
}
