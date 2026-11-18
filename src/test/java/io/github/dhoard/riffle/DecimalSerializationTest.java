package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Decimal writing never materializes a plain notation longer than
 * {@code Limits.MAX_PLAIN_DECIMAL}, and decimals whose exponent or normalized form is outside
 * {@link BigDecimal}'s range are reported as parse errors rather than runtime exceptions.
 */
class DecimalSerializationTest {

    private static RiffleObject wrap(BigDecimal value) {
        Map<String, RiffleValue> map = new LinkedHashMap<>();
        map.put("d", RiffleDecimal.of(value));
        return RiffleObject.of(map);
    }

    private static String write(BigDecimal value) {
        return Riffle.write(wrap(value));
    }

    @Test
    void ordinaryDecimalsKeepPlainNotation() {
        assertThat(write(new BigDecimal("0.25"))).isEqualTo("d : @decimal 0.25\n");
        assertThat(write(new BigDecimal("-0.0001"))).isEqualTo("d : @decimal -0.0001\n");
        assertThat(write(new BigDecimal("1"))).isEqualTo("d : @decimal 1\n");
        assertThat(write(new BigDecimal("1.0"))).isEqualTo("d : @decimal 1\n");
        assertThat(write(new BigDecimal("0.000"))).isEqualTo("d : @decimal 0\n");
        assertThat(write(new BigDecimal("1e3"))).isEqualTo("d : @decimal 1000\n");
        assertThat(write(BigDecimal.valueOf(1234, 2))).isEqualTo("d : @decimal 12.34\n");
    }

    /**
     * The smallest and largest exponents {@link BigDecimal} can represent, reached through
     * {@code @decimal} tokens. Materializing either as a plain decimal would need more than
     * {@code Integer.MAX_VALUE} characters, so a regression fails at once instead of allocating.
     */
    @Test
    void extremeExponentsAreWrittenInExponentNotation() {
        assertThat(write(new BigDecimal("1e2147483647"))).isEqualTo("d : @decimal 1E+2147483647\n");
        assertThat(write(new BigDecimal("1e-2147483646"))).isEqualTo("d : @decimal 1E-2147483646\n");
        assertThat(write(new BigDecimal("10e2147483647"))).isEqualTo("d : @decimal 1E+2147483648\n");
    }

    @Test
    void thePlainThresholdCountsEveryCharacterOfTheDecimalToken() {
        String digits255 = "1".repeat(255);
        String digits256 = "1".repeat(256);
        String digits257 = "1".repeat(257);

        assertThat(write(new BigDecimal(digits255))).isEqualTo("d : @decimal " + digits255 + "\n");
        assertThat(write(new BigDecimal(digits256))).isEqualTo("d : @decimal " + digits256 + "\n");
        assertThat(write(new BigDecimal(digits257))).isEqualTo("d : @decimal 1." + "1".repeat(256) + "E+256\n");
        assertThat(write(new BigDecimal("-" + digits256))).isEqualTo("d : @decimal -1." + "1".repeat(255) + "E+255\n");
    }

    @Test
    void smallValuesUseTheSamePlainThreshold() {
        assertThat(write(new BigDecimal("1e-254"))).isEqualTo("d : @decimal 0." + "0".repeat(253) + "1\n");
        assertThat(write(new BigDecimal("1e-255"))).isEqualTo("d : @decimal 1E-255\n");
    }

    static Stream<Arguments> extremeDecimals() {
        return Stream.of(
                Arguments.of("largest exponent", "1e2147483647"),
                Arguments.of("smallest exponent", "1e-2147483646"),
                Arguments.of("large negative value", "-1e2147483647"),
                Arguments.of("many significant digits", "123456789012345678901234567890.5"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extremeDecimals")
    void extremeDecimalsRoundTripAndWriteIdempotently(String description, String literal) {
        RiffleObject value = wrap(new BigDecimal(literal));
        String written = Riffle.write(value);
        assertThat(Riffle.parse(written)).as(description).isEqualTo(value);
        assertThat(Riffle.write(Riffle.parse(written))).as(description).isEqualTo(written);
    }

    @Test
    void parsingAnExtremeExponentTokenRoundTrips() {
        RiffleObject parsed = Riffle.parse("d : @decimal 1e2147483647\n");
        assertThat(Riffle.write(parsed)).isEqualTo("d : @decimal 1E+2147483647\n");
        assertThat(Riffle.parse(Riffle.write(parsed))).isEqualTo(parsed);

        RiffleObject normalizedFromTrailingZeros = Riffle.parse("d : @decimal 10e2147483647\n");
        assertThat(Riffle.write(normalizedFromTrailingZeros)).isEqualTo("d : @decimal 1E+2147483648\n");
        assertThat(Riffle.parse(Riffle.write(normalizedFromTrailingZeros))).isEqualTo(normalizedFromTrailingZeros);
    }

    @Test
    void anExponentBeyondTheRepresentableRangeIsAParseError() {
        assertThatThrownBy(() -> Riffle.parse("d : @decimal 1e-2147483648\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.NUMBER_OUT_OF_RANGE));
        assertThatThrownBy(() -> Riffle.parse("d : @decimal 1e2147483649\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.NUMBER_OUT_OF_RANGE));
    }

    @Test
    void aDecimalWhoseNormalizedFormIsOutOfRangeIsAParseError() {
        assertThatThrownBy(() -> Riffle.parse("d : @decimal 10e2147483648\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.NUMBER_OUT_OF_RANGE));
    }

    @Test
    void aDecimalWhoseNormalizedFormIsOutOfRangeCannotBeConstructed() {
        // Constructed through BigDecimal's unscaled/scale constructor because the equivalent
        // literal "10e2147483648" is rejected by the JDK 17 constructor but accepted by 21 and 25.
        BigDecimal unrepresentable = new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE);
        assertThatThrownBy(() -> RiffleDecimal.of(unrepresentable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exponent");
    }

    @Test
    void parseErrorsDoNotEchoTheDecimalPayload() {
        assertThatThrownBy(() -> Riffle.parse("d : @decimal 1e-2147483648\n"))
                .isInstanceOf(RiffleParseException.class)
                .hasMessageNotContaining("2147483648");
    }
}
