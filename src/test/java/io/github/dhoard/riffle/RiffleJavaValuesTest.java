package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Opt-in Java conversion, canonical scalar text, immutability, cycles, and round trips. */
class RiffleJavaValuesTest {

    @Test
    void toJavaMapsEveryKindAndKeepsInsertionOrder() {
        RiffleObject root = Riffle.parse("""
                s : text
                n : 1
                m : {
                    x : y
                }
                a : [
                    x
                    2
                ]
                """);

        Map<String, Object> nativeRoot = RiffleJavaValues.toJava(root);
        assertThat(nativeRoot.keySet()).containsExactly("s", "n", "m", "a");
        assertThat(nativeRoot.get("s")).isEqualTo("text");
        assertThat(nativeRoot.get("n")).isEqualTo("1");
        assertThat(((Map<?, ?>) nativeRoot.get("m")).get("x")).isEqualTo("y");
        assertThat(nativeRoot.get("a")).isEqualTo(List.of("x", "2"));
        assertThat(RiffleJavaValues.toJava((RiffleValue) RiffleString.of("7"))).isEqualTo("7");
    }

    @Test
    void nativeCollectionsAreDeepCopiesAndUnmodifiable() {
        List<Object> nativeElements = new ArrayList<>(List.of("x"));
        Map<String, Object> nativeSource = new LinkedHashMap<>();
        nativeSource.put("list", nativeElements);

        Map<String, Object> converted = RiffleJavaValues.toJava(RiffleJavaValues.fromJava(nativeSource));
        nativeElements.add("changed");
        nativeSource.put("added", 1);
        assertThat(converted).doesNotContainKey("added");
        assertThat(converted.get("list")).isEqualTo(List.of("x"));
        assertThatThrownBy(() -> converted.put("other", 1)).isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        List<Object> mutableList = (List<Object>) converted.get("list");
        assertThatThrownBy(() -> mutableList.add("other")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fromJavaWritesScalarsAsTextTheMatchingAccessorAccepts() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("s", "text");
        source.put("i", -1);
        source.put("l", Long.MAX_VALUE);
        source.put("d", new BigDecimal("1.50"));
        source.put("b", true);
        source.put("m", Map.of("k", "v"));
        source.put("a", List.of("text", false));

        RiffleObject root = RiffleJavaValues.fromJava(source);
        assertThat(root.getString("s")).isEqualTo("text");
        assertThat(root.getInt("i")).isEqualTo(-1);
        assertThat(root.getLong("l")).isEqualTo(Long.MAX_VALUE);
        assertThat(root.getDecimal("d")).isEqualByComparingTo("1.5");
        assertThat(root.getBoolean("b")).isTrue();
        assertThat(root.getObject("m").getString("k")).isEqualTo("v");
        assertThat(root.getArray("a").getBoolean(1)).isFalse();
        assertThat(Riffle.write(root)).isEqualTo("""
                s : text
                i : -1
                l : 9223372036854775807
                d : 1.5
                b : true
                m : {
                    k : v
                }
                a : [
                    text
                    false
                ]
                """);
    }

    static Stream<Arguments> decimalTexts() {
        return Stream.of(
                Arguments.of("0.25", "0.25"),
                Arguments.of("-0.0001", "-0.0001"),
                Arguments.of("1.0", "1"),
                Arguments.of("0.000", "0"),
                Arguments.of("1e3", "1000"),
                Arguments.of("12.34", "12.34"),
                Arguments.of("1" + "0".repeat(255), "1" + "0".repeat(255)),
                Arguments.of("1" + "0".repeat(256), "1E+256"),
                Arguments.of("1e-254", "0." + "0".repeat(253) + "1"),
                Arguments.of("1e-255", "1E-255"),
                Arguments.of("1e2147483647", "1E+2147483647"),
                Arguments.of("1e-2147483646", "1E-2147483646"),
                Arguments.of("-1.5e400", "-1.5E+400"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decimalTexts")
    void decimalsBecomeNormalizedTextThatReadsBackExactly(String literal, String expected) {
        BigDecimal decimal = new BigDecimal(literal);
        RiffleValue value = RiffleJavaValues.fromJava((Object) decimal);
        assertThat(value.asString()).isEqualTo(expected);
        assertThat(value.isDecimal()).isTrue();
        assertThat(value.asDecimal()).isEqualTo(decimal.stripTrailingZeros());
    }

    @Test
    void aDecimalWhoseNormalizedFormIsOutOfRangeIsRejected() {
        BigDecimal unrepresentable = new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE);
        assertThatThrownBy(() -> RiffleJavaValues.fromJava((Object) unrepresentable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exponent");
    }

    @Test
    void roundTripsPreserveTextAndOrder() {
        RiffleObject parsed = Riffle.parse("""
                s : text
                n : 1.50
                m : {
                    child : value
                }
                a : [
                    x
                    3
                ]
                """);
        RiffleObject roundTripped = RiffleJavaValues.fromJava(RiffleJavaValues.toJava(parsed));
        assertThat(roundTripped).isEqualTo(parsed);
        assertThat(Riffle.write(roundTripped)).isEqualTo(Riffle.write(parsed));
        assertThat(roundTripped.getString("n")).isEqualTo("1.50");
        assertThat(roundTripped.location()).isEmpty();
    }

    @Test
    void nullIsRejectedEverywhere() {
        assertThatThrownBy(() -> RiffleJavaValues.fromJava((Object) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiffleJavaValues.fromJava((Map<String, ?>) null))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> nullMember = new LinkedHashMap<>();
        nullMember.put("k", null);
        assertThatThrownBy(() -> RiffleJavaValues.fromJava(nullMember)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiffleJavaValues.fromJava((Object) Arrays.asList("x", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedTypesAndBadKeysAreRejectedWithoutEchoingValues() {
        assertThatThrownBy(() -> RiffleJavaValues.fromJava(new Secret("s3cr3t")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported Java type")
                .hasMessageNotContaining("s3cr3t");
        for (Object unsupported : List.of((short) 1, (byte) 1, BigInteger.ONE, 1.0f, 1.0d)) {
            assertThatThrownBy(() -> RiffleJavaValues.fromJava(unsupported))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported Java type");
        }

        Map<Object, Object> badKey = new LinkedHashMap<>();
        badKey.put(1, "value");
        assertThatThrownBy(() -> RiffleJavaValues.fromJava((Object) badKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("map keys must be non-null strings");
        badKey.clear();
        badKey.put(null, "value");
        assertThatThrownBy(() -> RiffleJavaValues.fromJava((Object) badKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("map keys must be non-null strings");
        badKey.clear();
        badKey.put("a\ud83Db", "value");
        assertThatThrownBy(() -> RiffleJavaValues.fromJava((Object) badKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogate")
                .hasMessageNotContaining("\ud83D");
        assertThatThrownBy(() -> RiffleJavaValues.fromJava((Object) "a\u0000b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("U+0000");
    }

    @Test
    void cyclesAreRejectedButSharedAcyclicChildrenAreDuplicated() {
        Map<String, Object> mapCycle = new LinkedHashMap<>();
        mapCycle.put("self", mapCycle);
        assertThatThrownBy(() -> RiffleJavaValues.fromJava(mapCycle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cyclic");

        List<Object> listCycle = new ArrayList<>();
        listCycle.add(listCycle);
        assertThatThrownBy(() -> RiffleJavaValues.fromJava(listCycle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cyclic");

        List<Object> shared = new ArrayList<>();
        shared.add("x");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a", shared);
        source.put("b", shared);
        RiffleObject root = RiffleJavaValues.fromJava(source);
        assertThat(root.getArray("a")).isEqualTo(root.getArray("b"));
        shared.add("changed");
        assertThat(root.getArray("a").size()).isEqualTo(1);
        assertThat(root.getArray("b").size()).isEqualTo(1);
    }

    @Test
    void deepJavaTreesConvertAndAConfiguredCapIsEnforced() {
        RiffleObject deep = RiffleJavaValues.fromJava(mapChain(1_000));
        assertThat(RiffleJavaValues.toJava(deep)).isInstanceOf(Map.class);
        assertThatThrownBy(() -> RiffleJavaValues.fromJava(mapChain(130), RiffleOptions.maxDepth(128)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    private static Map<String, Object> mapChain(int depth) {
        Object value = "leaf";
        for (int i = 0; i < depth; i++) {
            Map<String, Object> parent = new LinkedHashMap<>();
            parent.put("k", value);
            value = parent;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) value;
        return root;
    }

    private record Secret(String value) {}
}
