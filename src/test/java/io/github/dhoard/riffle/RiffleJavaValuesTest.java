package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Opt-in Java conversion, null handling, immutability, cycles, and round trips. */
class RiffleJavaValuesTest {

    @Test
    void toJavaMapsEveryKindAndKeepsInsertionOrderAndNulls() {
        RiffleObject root = Riffle.parse("""
                s : text
                i : @int 1
                l : @long 2
                d : @decimal 1.25
                b : @boolean true
                n : @null
                m : {
                    x : y
                }
                a : [
                    x
                    @null
                ]
                """);

        Map<String, Object> nativeRoot = RiffleJavaValues.toJava(root);
        assertThat(nativeRoot.keySet()).containsExactly("s", "i", "l", "d", "b", "n", "m", "a");
        assertThat(nativeRoot.get("s")).isEqualTo("text");
        assertThat(nativeRoot.get("i")).isEqualTo(1);
        assertThat(nativeRoot.get("l")).isEqualTo(2L);
        assertThat(nativeRoot.get("d")).isEqualTo(new BigDecimal("1.25"));
        assertThat(nativeRoot.get("b")).isEqualTo(true);
        assertThat(nativeRoot.containsKey("n")).isTrue();
        assertThat(nativeRoot.get("n")).isNull();
        assertThat(nativeRoot.get("m")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) nativeRoot.get("m")).get("x")).isEqualTo("y");
        assertThat(nativeRoot.get("a")).isInstanceOf(List.class);
        List<?> elements = (List<?>) nativeRoot.get("a");
        assertThat(elements.get(0)).isEqualTo("x");
        assertThat(elements.get(1)).isNull();

        assertThat(RiffleJavaValues.toJava((RiffleValue) RiffleInt.of(7))).isEqualTo(7);
        assertThat(RiffleJavaValues.toJava(RiffleNull.INSTANCE)).isNull();
    }

    @Test
    void nativeCollectionsAreDeepCopiesAndUnmodifiable() {
        List<Object> nativeElements = new ArrayList<>();
        nativeElements.add("x");
        nativeElements.add(null);
        Map<String, Object> nativeSource = new LinkedHashMap<>();
        nativeSource.put("list", nativeElements);
        nativeSource.put("null", null);

        Map<String, Object> converted = RiffleJavaValues.toJava(RiffleJavaValues.fromJava(nativeSource));
        assertThat(converted.containsKey("null")).isTrue();
        assertThat(converted.get("null")).isNull();

        nativeElements.add("changed");
        nativeSource.put("added", 1);
        assertThat(converted).doesNotContainKey("added");
        List<?> copiedElements = (List<?>) converted.get("list");
        assertThat(copiedElements).hasSize(2);
        assertThat(copiedElements.get(0)).isEqualTo("x");
        assertThat(copiedElements.get(1)).isNull();
        assertThatThrownBy(() -> converted.put("other", 1)).isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        List<Object> mutableList = (List<Object>) converted.get("list");
        assertThatThrownBy(() -> mutableList.add("other")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fromJavaAcceptsOnlyTheSupportedNativeKinds() {
        List<Object> elements = new ArrayList<>();
        elements.add("text");
        elements.add(null);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("s", "text");
        source.put("i", 1);
        source.put("l", 2L);
        source.put("d", new BigDecimal("1.50"));
        source.put("b", true);
        source.put("n", null);
        source.put("m", Map.of("k", "v"));
        source.put("a", elements);

        RiffleObject root = RiffleJavaValues.fromJava(source);
        assertThat(root.getString("s")).isEqualTo("text");
        assertThat(root.getInt("i")).isEqualTo(1);
        assertThat(root.getLong("l")).isEqualTo(2L);
        assertThat(root.getDecimal("d")).isEqualByComparingTo("1.5");
        assertThat(root.getBoolean("b")).isTrue();
        assertThat(root.get("n").isNull()).isTrue();
        assertThat(root.getObject("m").getString("k")).isEqualTo("v");
        assertThat(root.getArray("a").getString(0)).isEqualTo("text");
        assertThat(root.getArray("a").get(1).isNull()).isTrue();
        assertThat(Riffle.write(root)).isEqualTo("""
                        s : text
                        i : @int 1
                        l : @long 2
                        d : @decimal 1.5
                        b : @boolean true
                        n : @null
                        m : {
                            k : v
                        }
                        a : [
                            text
                            @null
                        ]
                        """);
    }

    @Test
    void roundTripsPreserveKindsOrderAndPresentNullVersusMissing() {
        String document = """
                s : text
                i : @int 1
                l : @long 2
                d : @decimal 0.5
                b : @boolean true
                n : @null
                m : {
                    child : value
                }
                a : [
                    x
                    @int 3
                ]
                """;
        RiffleObject parsed = Riffle.parse(document);

        RiffleObject roundTripped = RiffleJavaValues.fromJava(RiffleJavaValues.toJava(parsed));
        assertThat(roundTripped).isEqualTo(parsed);
        assertThat(Riffle.write(roundTripped)).isEqualTo(Riffle.write(parsed));
        assertThat(roundTripped.find("absent")).isEmpty();
        assertThat(roundTripped.find("n")).isPresent();
        assertThat(roundTripped.find("n").orElseThrow().isNull()).isTrue();
        assertThat(roundTripped.location()).isEmpty();

        RiffleValue longValue = parsed.get("l");
        assertThat(RiffleJavaValues.fromJava(RiffleJavaValues.toJava(longValue)).isLong())
                .isTrue();
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
    void nestingLimitsAreEnforcedBeforeCreatingAnUnwritableTree() {
        assertThatThrownBy(() -> RiffleJavaValues.fromJava(mapChain(130)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
        assertThat(RiffleJavaValues.fromJava(mapChain(129))).isInstanceOf(RiffleObject.class);
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
