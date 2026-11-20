package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Value interpretation as defined by LANGUAGE_SPEC.md §13. Each form is matched against the whole
 * string by the grammars below; host-language parsers run only on text that already matched,
 * because they accept forms Riffle rejects, such as {@code +5}.
 */
final class Conversions {
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private static final BigInteger MIN_EXPONENT = BigInteger.valueOf(-2147483647L);
    private static final BigInteger MAX_EXPONENT = BigInteger.valueOf(2147483648L);

    private static final String EXPECTED_INTEGER = "expected a base-10 integer such as 42 or -7";
    private static final String EXPECTED_DECIMAL = "expected a decimal number such as 0.25, -3 or 1e3";
    private static final String EXPECTED_BOOLEAN = "expected true or false";

    /** The text form of the integer expectation, reused by schema problems. */
    static String expectedInteger() {
        return EXPECTED_INTEGER;
    }

    /** The text form of the decimal expectation, reused by schema problems. */
    static String expectedDecimal() {
        return EXPECTED_DECIMAL;
    }

    /** The text form of the boolean expectation, reused by schema problems. */
    static String expectedBoolean() {
        return EXPECTED_BOOLEAN;
    }

    private Conversions() {}

    /** Returns whether {@code text} matches the integer grammar of spec §13.1. */
    static boolean isIntegerText(String text) {
        return INTEGER.matcher(text).matches();
    }

    /** Returns the value of text matching the integer grammar, or {@code null} beyond 64 bits. */
    static Long parseIntegerText(String text) {
        return parseLong(text);
    }

    /** Returns whether {@code text} matches the decimal grammar of spec §13.1. */
    static boolean isDecimalText(String text) {
        return DECIMAL.matcher(text).matches();
    }

    /**
     * Returns the normalized value of text matching the decimal grammar, or {@code null} when the
     * exponent is out of the range of spec §13.2.
     */
    static BigDecimal parseDecimalText(String text) {
        return parseDecimal(text);
    }

    /** Returns the lowercase boolean text, which is the only text the boolean interpretation reads. */
    static boolean isBooleanText(String text) {
        return text.equals("true") || text.equals("false");
    }

    static long toLong(RiffleValue value) {
        String text = text(value, "integer");
        if (!INTEGER.matcher(text).matches()) {
            throw invalidFormat(value, EXPECTED_INTEGER);
        }
        Long number = parseLong(text);
        if (number == null) {
            throw outOfRange(value, "integer is outside the signed 64-bit range");
        }
        return number;
    }

    static boolean isLong(RiffleValue value) {
        return value instanceof RiffleString string
                && INTEGER.matcher(string.text()).matches()
                && parseLong(string.text()) != null;
    }

    static int toInt(RiffleValue value) {
        String text = text(value, "integer");
        if (!INTEGER.matcher(text).matches()) {
            throw invalidFormat(value, EXPECTED_INTEGER);
        }
        Long number = parseLong(text);
        if (number == null || number != number.intValue()) {
            throw outOfRange(value, "integer is outside the signed 32-bit range");
        }
        return number.intValue();
    }

    static boolean isInt(RiffleValue value) {
        if (!isLong(value)) {
            return false;
        }
        long number = Long.parseLong(((RiffleString) value).text());
        return number == (int) number;
    }

    static BigDecimal toDecimal(RiffleValue value) {
        String text = text(value, "decimal");
        if (!DECIMAL.matcher(text).matches()) {
            throw invalidFormat(value, EXPECTED_DECIMAL);
        }
        BigDecimal number = parseDecimal(text);
        if (number == null) {
            throw outOfRange(value, "decimal exponent is outside the supported range");
        }
        return number;
    }

    static boolean isDecimal(RiffleValue value) {
        return value instanceof RiffleString string
                && DECIMAL.matcher(string.text()).matches()
                && parseDecimal(string.text()) != null;
    }

    static boolean toBoolean(RiffleValue value) {
        String text = text(value, "boolean");
        if (text.equals("true")) {
            return true;
        }
        if (text.equals("false")) {
            return false;
        }
        throw invalidFormat(value, EXPECTED_BOOLEAN);
    }

    static boolean isBoolean(RiffleValue value) {
        return value instanceof RiffleString string
                && (string.text().equals("true") || string.text().equals("false"));
    }

    /** Reports that {@code value} has a different kind than the requested one. */
    static RiffleAccessException wrongKind(RiffleValue value, String type) {
        return new RiffleAccessException(
                RiffleAccessError.WRONG_KIND,
                "expected " + type + " but was " + kind(value),
                value.path().orElse(null),
                value.location().orElse(null));
    }

    private static String text(RiffleValue value, String type) {
        if (value instanceof RiffleString string) {
            return string.text();
        }
        throw wrongKind(value, type);
    }

    /** Returns the name of the kind of {@code value}, for diagnostics. */
    private static String kind(RiffleValue value) {
        if (value instanceof RiffleObject) {
            return "map";
        }
        return value instanceof RiffleArray ? "array" : "string";
    }

    /** Returns the value of text matching {@link #INTEGER}, or {@code null} beyond 64 bits. */
    private static Long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the normalized value of text matching {@link #DECIMAL}, or {@code null} when the
     * normalized exponent is out of range. The exponent is computed with arbitrary precision and
     * after normalization: {@code 0e99999999999} is zero, and {@code 10e2147483647} is in range
     * even though {@link BigDecimal#BigDecimal(String)} cannot read every such spelling.
     */
    private static BigDecimal parseDecimal(String text) {
        int exponentAt = Math.max(text.indexOf('e'), text.indexOf('E'));
        String mantissa = exponentAt < 0 ? text : text.substring(0, exponentAt);
        boolean negative = mantissa.charAt(0) == '-';
        String unsigned = negative ? mantissa.substring(1) : mantissa;
        int point = unsigned.indexOf('.');
        String digits = point < 0 ? unsigned : unsigned.substring(0, point) + unsigned.substring(point + 1);

        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        if (end == 0) {
            return BigDecimal.ZERO;
        }

        int fractionDigits = point < 0 ? 0 : unsigned.length() - point - 1;
        BigInteger exponent = exponentAt < 0 ? BigInteger.ZERO : new BigInteger(text.substring(exponentAt + 1));
        BigInteger normalized =
                exponent.subtract(BigInteger.valueOf(fractionDigits)).add(BigInteger.valueOf(digits.length() - end));
        if (normalized.compareTo(MIN_EXPONENT) < 0 || normalized.compareTo(MAX_EXPONENT) > 0) {
            return null;
        }
        BigInteger coefficient = new BigInteger(digits.substring(0, end));
        return new BigDecimal(negative ? coefficient.negate() : coefficient, (int) -normalized.longValue());
    }

    private static RiffleAccessException invalidFormat(RiffleValue value, String reason) {
        return new RiffleAccessException(
                RiffleAccessError.INVALID_FORMAT,
                reason,
                value.path().orElse(null),
                value.location().orElse(null));
    }

    private static RiffleAccessException outOfRange(RiffleValue value, String reason) {
        return new RiffleAccessException(
                RiffleAccessError.OUT_OF_RANGE,
                reason,
                value.path().orElse(null),
                value.location().orElse(null));
    }
}
