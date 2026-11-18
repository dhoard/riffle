package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Configuration can hold secrets, so no diagnostic may repeat a scalar value. */
class DiagnosticsSecrecyTest {
    private static final String SECRET = "SECRET-7f3a";

    static Stream<String> invalidDocuments() {
        return Stream.of(
                "k : @" + SECRET + "\n",
                "k : @int " + SECRET + "\n",
                "k : [" + SECRET + "\n",
                "k : {" + SECRET + "}\n",
                "k : []" + SECRET + "\n",
                "k : {}" + SECRET + "\n",
                "k : }" + SECRET + "\n",
                "k : ]" + SECRET + "\n",
                "k : \"" + SECRET + "\n",
                "k : \"" + SECRET + "\" " + SECRET + "\n",
                "k : \"\\" + SECRET + "\"\n",
                "k : \"\\q" + SECRET + "\"\n",
                "k : \"\\u00" + SECRET + "\"\n",
                "k : \"\\u0000" + SECRET + "\"\n",
                "k : \"\\uD83D" + SECRET + "\"\n",
                "k : " + SECRET + "\u0001\n",
                "k : " + SECRET + "\uFEFF\n",
                "k : " + SECRET + "\rnext : 1\n",
                "k : [\n    @" + SECRET + "\n]\n",
                "k : [\n    " + SECRET + "\n}\n",
                "k : {\n    " + SECRET + "\n}\n",
                SECRET + "\n",
                "/#\n" + SECRET + "\n",
                "k : \"\"\"\n    " + SECRET + "\n",
                "k : \"\"\"\n" + SECRET + "\n    \"\"\"\n",
                "k : \"\"\"\n    " + SECRET + "\u0001\n    \"\"\"\n");
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void parseErrorsNeverContainTheValue(String document) {
        assertThatThrownBy(() -> Riffle.parse(document)).isInstanceOfSatisfying(RiffleParseException.class, e -> {
            assertThat(e.getMessage()).doesNotContain(SECRET);
            assertThat(e.reason()).doesNotContain(SECRET);
        });
    }

    @Test
    void accessErrorsNeverContainTheValue() {
        String tooLarge = "99999999999999999999";
        RiffleObject root = Riffle.parse("k : " + SECRET + "\nbig : " + tooLarge + "\n");
        List<Function<RiffleValue, Object>> accessors = List.of(
                RiffleValue::asInt,
                RiffleValue::asLong,
                RiffleValue::asDecimal,
                RiffleValue::asBoolean,
                RiffleValue::asObject,
                RiffleValue::asArray);
        for (Function<RiffleValue, Object> accessor : accessors) {
            assertThatThrownBy(() -> accessor.apply(root.get("k")))
                    .isInstanceOf(RiffleAccessException.class)
                    .hasMessageNotContaining(SECRET);
        }
        assertThatThrownBy(() -> root.get("big").asLong())
                .isInstanceOf(RiffleAccessException.class)
                .hasMessageNotContaining(tooLarge);
        assertThatThrownBy(() -> root.get("big").asInt())
                .isInstanceOf(RiffleAccessException.class)
                .hasMessageNotContaining(tooLarge);
    }
}
