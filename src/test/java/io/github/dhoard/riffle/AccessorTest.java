package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Value interpretation: every accepted form, boundary and rejection of LANGUAGE_SPEC.md §13. */
class AccessorTest {

    private static RiffleValue s(String text) {
        return RiffleString.of(text);
    }

    private static void fails(Function<RiffleValue, ?> accessor, String text, RiffleAccessError error) {
        assertThatThrownBy(() -> accessor.apply(s(text)))
                .as(text)
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.category()).isEqualTo(error));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "-0",
                "7",
                "-7",
                "8080",
                "9223372036854775807",
                "-9223372036854775808",
            })
    void asLongAcceptsTheIntegerGrammar(String text) {
        assertThat(s(text).asLong()).isEqualTo(Long.parseLong(text));
        assertThat(s(text).isLong()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "99999999999999999999999999"})
    void asLongReportsValuesBeyond64BitsAsOutOfRange(String text) {
        fails(RiffleValue::asLong, text, RiffleAccessError.OUT_OF_RANGE);
        assertThat(s(text).isLong()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483647", "-2147483648", "0", "-0"})
    void asIntAcceptsThe32BitRange(String text) {
        assertThat(s(text).asInt()).isEqualTo(Integer.parseInt(text));
        assertThat(s(text).isInt()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "9223372036854775807", "9223372036854775808"})
    void asIntReportsValuesBeyond32BitsAsOutOfRange(String text) {
        fails(RiffleValue::asInt, text, RiffleAccessError.OUT_OF_RANGE);
        assertThat(s(text).isInt()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "", " ", "+5", "05", "-05", "00", "1_000", "0x10", " 5", "5 ", "5\n", "12.0", "1e3", "-", "--1", "abc",
                "\u0661", "\uFF11",
            })
    void integerAccessorsRejectEverythingOutsideTheGrammar(String text) {
        fails(RiffleValue::asLong, text, RiffleAccessError.INVALID_FORMAT);
        fails(RiffleValue::asInt, text, RiffleAccessError.INVALID_FORMAT);
        assertThat(s(text).isLong()).isFalse();
        assertThat(s(text).isInt()).isFalse();
    }

    static Stream<Arguments> decimals() {
        return Stream.of(
                Arguments.of("12", "12"),
                Arguments.of("0", "0"),
                Arguments.of("-0", "0"),
                Arguments.of("0.000", "0"),
                Arguments.of("1.50", "1.5"),
                Arguments.of("-0.00100", "-0.001"),
                Arguments.of("1e3", "1000"),
                Arguments.of("1E3", "1000"),
                Arguments.of("1E+3", "1000"),
                Arguments.of("1e-3", "0.001"),
                Arguments.of("0.1", "0.1"),
                Arguments.of("12345678901234567890.123456789", "12345678901234567890.123456789"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decimals")
    void asDecimalIsExactAndNormalized(String text, String expected) {
        BigDecimal value = s(text).asDecimal();
        assertThat(value).isEqualByComparingTo(expected);
        assertThat(value).isEqualTo(new BigDecimal(expected).stripTrailingZeros());
        assertThat(s(text).isDecimal()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " 1",
                "1 ",
                "+1",
                "01",
                "-01",
                ".5",
                "1.",
                "1.e3",
                "1e",
                "1e+",
                "1_000.5",
                "0x10",
                "NaN",
                "Infinity",
                "-Infinity",
                "1.5f",
                "1,5",
            })
    void asDecimalRejectsEverythingOutsideTheGrammar(String text) {
        fails(RiffleValue::asDecimal, text, RiffleAccessError.INVALID_FORMAT);
        assertThat(s(text).isDecimal()).isFalse();
    }

    @Test
    void theDecimalExponentBoundariesApplyToTheNormalizedValue() {
        assertThat(s("1e2147483648").asDecimal().scale()).isEqualTo(Integer.MIN_VALUE);
        assertThat(s("10e2147483647").asDecimal().scale()).isEqualTo(Integer.MIN_VALUE);
        assertThat(s("1e-2147483647").asDecimal().scale()).isEqualTo(Integer.MAX_VALUE);
        assertThat(s("100e-2147483649").asDecimal().scale()).isEqualTo(Integer.MAX_VALUE);
        assertThat(s("0.1e-2147483646").asDecimal().scale()).isEqualTo(Integer.MAX_VALUE);

        fails(RiffleValue::asDecimal, "1e2147483649", RiffleAccessError.OUT_OF_RANGE);
        fails(RiffleValue::asDecimal, "10e2147483648", RiffleAccessError.OUT_OF_RANGE);
        fails(RiffleValue::asDecimal, "1e-2147483648", RiffleAccessError.OUT_OF_RANGE);
        fails(RiffleValue::asDecimal, "0.1e-2147483647", RiffleAccessError.OUT_OF_RANGE);
        fails(RiffleValue::asDecimal, "1e99999999999999999999999", RiffleAccessError.OUT_OF_RANGE);
        assertThat(s("1e2147483649").isDecimal()).isFalse();
    }

    @Test
    void zeroIsInRangeWhateverItsExponent() {
        assertThat(s("0e99999999999999999999999").asDecimal()).isEqualTo(BigDecimal.ZERO);
        assertThat(s("-0.00e-99999999999999999999999").asDecimal()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void decimalsNeverPassThroughBinaryFloatingPoint() {
        assertThat(s("0.1").asDecimal()).isEqualTo(new BigDecimal(BigInteger.ONE, 1));
        String digits = "1234567890".repeat(10_000) + "1";
        assertThat(s(digits).asDecimal().unscaledValue()).isEqualTo(new BigInteger(digits));
    }

    @Test
    void asBooleanAcceptsOnlyLowercaseTrueAndFalse() {
        assertThat(s("true").asBoolean()).isTrue();
        assertThat(s("false").asBoolean()).isFalse();
        assertThat(s("true").isBoolean()).isTrue();
        for (String text : List.of("True", "TRUE", "False", "yes", "no", "on", "off", "1", "0", "", " true", "true ")) {
            fails(RiffleValue::asBoolean, text, RiffleAccessError.INVALID_FORMAT);
            assertThat(s(text).isBoolean()).as(text).isFalse();
        }
    }

    @Test
    void theSpellingOfAValueDoesNotChangeItsInterpretation() {
        RiffleObject root = Riffle.parse("""
                bare : 8080
                quoted : "8080"
                escaped : "\\u0038080"
                """);
        assertThat(root.getInt("bare")).isEqualTo(8080);
        assertThat(root.getInt("quoted")).isEqualTo(8080);
        assertThat(root.getInt("escaped")).isEqualTo(8080);
    }

    @Test
    void aQuotedLeadingSpaceIsNotTrimmedByAnAccessor() {
        RiffleValue value = Riffle.parse("k : \" 5\"\n").get("k");
        assertThat(value.asString()).isEqualTo(" 5");
        assertThatThrownBy(value::asInt)
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleAccessError.INVALID_FORMAT));
    }

    @Test
    void stringEqualityStaysExact() {
        assertThat(s("1.0")).isNotEqualTo(s("1.00"));
        assertThat(s("1.0").asDecimal()).isEqualTo(s("1.00").asDecimal());
    }

    @Test
    void collectionsAreTheWrongKindForEveryScalarAccessor() {
        for (RiffleValue collection : List.of(RiffleObject.of(Map.of()), RiffleArray.of(List.of()))) {
            for (Function<RiffleValue, Object> accessor : List.<Function<RiffleValue, Object>>of(
                    RiffleValue::asString,
                    RiffleValue::asInt,
                    RiffleValue::asLong,
                    RiffleValue::asDecimal,
                    RiffleValue::asBoolean)) {
                assertThatThrownBy(() -> accessor.apply(collection))
                        .isInstanceOfSatisfying(
                                RiffleAccessException.class,
                                e -> assertThat(e.category()).isEqualTo(RiffleAccessError.WRONG_KIND));
            }
            assertThat(collection.isInt()).isFalse();
            assertThat(collection.isLong()).isFalse();
            assertThat(collection.isDecimal()).isFalse();
            assertThat(collection.isBoolean()).isFalse();
            assertThat(collection.isString()).isFalse();
        }
        fails(RiffleValue::asObject, "text", RiffleAccessError.WRONG_KIND);
        fails(RiffleValue::asArray, "text", RiffleAccessError.WRONG_KIND);
    }

    @Test
    void gettersReadEveryInterpretationAndKeepPathsAndLocations() {
        RiffleObject root = Riffle.parse("""
                s : text
                n : 3
                d : 0.5
                b : true
                big : 2147483648
                m : {
                    child : nested
                }
                a : [
                    7
                    1.25
                    false
                    {}
                    []
                ]
                """);
        assertThat(root.getString("s")).isEqualTo("text");
        assertThat(root.getInt("n")).isEqualTo(3);
        assertThat(root.getLong("n")).isEqualTo(3L);
        assertThat(root.getDecimal("d")).isEqualByComparingTo("0.5");
        assertThat(root.getBoolean("b")).isTrue();
        assertThat(root.getObject("m").getString("child")).isEqualTo("nested");

        RiffleArray array = root.getArray("a");
        assertThat(array.getString(0)).isEqualTo("7");
        assertThat(array.getInt(0)).isEqualTo(7);
        assertThat(array.getLong(0)).isEqualTo(7L);
        assertThat(array.getDecimal(1)).isEqualByComparingTo("1.25");
        assertThat(array.getBoolean(2)).isFalse();
        assertThat(array.getObject(3).entries()).isEmpty();
        assertThat(array.getArray(4).values()).isEmpty();

        assertThatThrownBy(() -> root.getInt("big")).isInstanceOfSatisfying(RiffleAccessException.class, e -> {
            assertThat(e.category()).isEqualTo(RiffleAccessError.OUT_OF_RANGE);
            assertThat(e.path()).contains("big");
            assertThat(e.getMessage()).startsWith("line 5, column 7: big: ");
        });
        assertThatThrownBy(() -> array.getBoolean(0)).isInstanceOfSatisfying(RiffleAccessException.class, e -> {
            assertThat(e.category()).isEqualTo(RiffleAccessError.INVALID_FORMAT);
            assertThat(e.path()).contains("a[0]");
        });
        assertThatThrownBy(() -> root.getString("m")).isInstanceOfSatisfying(RiffleAccessException.class, e -> {
            assertThat(e.category()).isEqualTo(RiffleAccessError.WRONG_KIND);
            assertThat(e.getMessage()).contains("m: expected string but was map");
        });
        assertThatThrownBy(() -> root.getInt("absent")).isInstanceOfSatisfying(RiffleAccessException.class, e -> {
            assertThat(e.category()).isEqualTo(RiffleAccessError.MISSING);
            assertThat(e.path()).contains("absent");
        });
        assertThatThrownBy(() -> array.getString(array.size()))
                .isInstanceOfSatisfying(RiffleAccessException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleAccessError.MISSING);
                    assertThat(e.path()).contains("a[5]");
                });
    }

    @Test
    void applicationConversionsDefaultToInvalidValue() {
        RiffleValue value = Riffle.parse("timeout : soon\n").get("timeout");
        RiffleAccessException exception = new RiffleAccessException(value, "expected a duration");
        assertThat(exception.category()).isEqualTo(RiffleAccessError.INVALID_VALUE);
        assertThat(exception.getMessage()).isEqualTo("line 1, column 11: timeout: expected a duration");
    }
}
