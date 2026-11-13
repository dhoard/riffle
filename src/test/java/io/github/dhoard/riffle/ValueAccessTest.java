package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Accessors, conversions, construction and equality. */
class ValueAccessTest {

    private static RiffleObject document() {
        return Riffle.parse("""
                s = text
                i = @int 3
                l = @long 2
                d = @decimal 0.5
                b = @boolean true
                n = @null
                m = {}
                a = [
                    x
                ]
                """);
    }

    @Test
    void getReturnsPresentValuesIncludingNull() {
        RiffleObject root = document();
        assertThat(root.get("s").asString()).isEqualTo("text");
        assertThat(root.get("n").isNull()).isTrue();
    }

    @Test
    void findDistinguishesMissingFromNull() {
        RiffleObject root = document();
        assertThat(root.find("missing")).isEmpty();
        assertThat(root.find("n")).isPresent();
        assertThat(root.find("n").orElseThrow().isNull()).isTrue();
    }

    @Test
    void getRejectsMissingKeysWithPathAndContainerLocation() {
        RiffleObject root = Riffle.parse("a = {\n    b = text\n}\n");
        assertThatThrownBy(() -> root.get("a").asObject().get("c"))
                .isInstanceOfSatisfying(RiffleAccessException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("line 1, column 5: a.c: missing");
                    assertThat(e.path()).contains("a.c");
                });
    }

    @Test
    void entriesKeepDocumentOrderAndAreUnmodifiable() {
        RiffleObject root = Riffle.parse("b = 1\na = 2\n");
        assertThat(root.entries().keySet()).containsExactly("b", "a");
        assertThatThrownBy(() -> root.entries().put("c", RiffleNull.INSTANCE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void arraysAreUnmodifiableAndIndexed() {
        RiffleArray array = Riffle.parse("a = [\nx\ny\n]").get("a").asArray();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(1).asString()).isEqualTo("y");
        assertThat(array.values()).hasSize(2);
        assertThatThrownBy(() -> array.values().add(RiffleNull.INSTANCE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> array.get(2))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("index out of range"));
        assertThatThrownBy(() -> array.get(-1)).isInstanceOf(RiffleAccessException.class);
    }

    @Test
    void accessorsAreStrictAboutKind() {
        RiffleObject root = document();
        assertThat(root.get("i").asInt()).isEqualTo(3);
        assertThat(root.get("l").asLong()).isEqualTo(2L);
        assertThat(root.get("d").asDecimal()).isEqualByComparingTo("0.5");
        assertThat(root.get("b").asBoolean()).isTrue();
        assertThat(root.get("s").asString()).isEqualTo("text");

        assertThatThrownBy(() -> root.get("i").asString()).isInstanceOf(RiffleAccessException.class);
        assertThatThrownBy(() -> root.get("s").asInt()).isInstanceOf(RiffleAccessException.class);
        assertThatThrownBy(() -> root.get("m").asString()).isInstanceOf(RiffleAccessException.class);
        assertThatThrownBy(() -> root.get("n").asString()).isInstanceOf(RiffleAccessException.class);
    }

    @Test
    void numericKindsNeverConvert() {
        RiffleObject root = document();
        assertThatThrownBy(() -> root.get("i").asLong())
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("expected long but was int"));
        assertThatThrownBy(() -> root.get("l").asInt())
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("expected int but was long"));
        assertThatThrownBy(() -> root.get("i").asDecimal()).isInstanceOf(RiffleAccessException.class);
        assertThatThrownBy(() -> root.get("d").asInt()).isInstanceOf(RiffleAccessException.class);
        assertThatThrownBy(() -> root.get("b").asInt()).isInstanceOf(RiffleAccessException.class);
    }

    @Test
    void predicatesAreTrueExactlyWhenTheAccessorSucceeds() {
        RiffleObject root = document();
        for (Map.Entry<String, RiffleValue> entry : root.entries().entrySet()) {
            RiffleValue value = entry.getValue();
            assertThat(value.isInt()).as(entry.getKey()).isEqualTo(canRead(() -> value.asInt()));
            assertThat(value.isLong()).as(entry.getKey()).isEqualTo(canRead(() -> value.asLong()));
            assertThat(value.isDecimal()).as(entry.getKey()).isEqualTo(canRead(() -> value.asDecimal()));
            assertThat(value.isBoolean()).as(entry.getKey()).isEqualTo(canRead(() -> value.asBoolean()));
            assertThat(value.isString()).as(entry.getKey()).isEqualTo(canRead(value::asString));
            assertThat(value.isObject()).as(entry.getKey()).isEqualTo(canRead(value::asObject));
            assertThat(value.isArray()).as(entry.getKey()).isEqualTo(canRead(value::asArray));
            assertThat(value.isNull()).as(entry.getKey()).isEqualTo(value instanceof RiffleNull);
        }
    }

    private static boolean canRead(ThrowingSupplier<?> supplier) {
        try {
            supplier.get();
            return true;
        } catch (RiffleAccessException e) {
            return false;
        }
    }

    private interface ThrowingSupplier<T> {
        T get();
    }

    @Test
    void constructedValuesHaveNoLocationOrPath() {
        RiffleValue value = RiffleInt.of(1);
        assertThat(value.location()).isEmpty();
        assertThat(value.path()).isEmpty();
        assertThat(RiffleObject.of(Map.of("k", value)).location()).isEmpty();
    }

    @Test
    void constructionRejectsUnwritableText() {
        assertThatThrownBy(() -> RiffleString.of("a\uD83Db")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiffleObject.of(Map.of("a\uD83Db", RiffleNull.INSTANCE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RiffleString.of("a\tb\nc").asString()).isEqualTo("a\tb\nc");
        assertThat(RiffleObject.of(Map.of("a b", RiffleNull.INSTANCE)).entries())
                .hasSize(1);
        assertThatThrownBy(() -> RiffleArray.of(List.of(RiffleNull.INSTANCE, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RiffleObject.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructionCopiesItsInput() {
        Map<String, RiffleValue> source = new LinkedHashMap<>();
        source.put("a", RiffleInt.of(1));
        RiffleObject object = RiffleObject.of(source);
        source.put("b", RiffleInt.of(2));
        assertThat(object.entries()).containsOnlyKeys("a");

        List<RiffleValue> values = new ArrayList<>(List.of(RiffleInt.of(1)));
        RiffleArray array = RiffleArray.of(values);
        values.add(RiffleInt.of(2));
        assertThat(array.size()).isEqualTo(1);
    }

    @Test
    void equalityIgnoresLocationButNotKind() {
        RiffleValue parsed = document().get("i");
        assertThat(parsed).isEqualTo(RiffleInt.of(3));
        assertThat(RiffleInt.of(1)).isNotEqualTo(RiffleLong.of(1));
        assertThat(RiffleInt.of(1)).isNotEqualTo(RiffleDecimal.of(BigDecimal.ONE));
        assertThat(RiffleInt.of(1)).isNotEqualTo(RiffleString.of("1"));
        assertThat(RiffleBoolean.of(true)).isEqualTo(RiffleBoolean.of(true));
        assertThat(RiffleNull.INSTANCE).isEqualTo(Riffle.parse("k = @null").get("k"));
    }

    @Test
    void decimalsCompareByValue() {
        assertThat(RiffleDecimal.of(new BigDecimal("1.0"))).isEqualTo(RiffleDecimal.of(new BigDecimal("1.00")));
        assertThat(RiffleDecimal.of(new BigDecimal("1.0")).hashCode())
                .isEqualTo(RiffleDecimal.of(new BigDecimal("1.00")).hashCode());
        assertThat(RiffleDecimal.of(new BigDecimal("1E+3"))).isEqualTo(RiffleDecimal.of(new BigDecimal("1000")));
    }

    @Test
    void objectEqualityIgnoresOrderAndArrayEqualityRespectsIt() {
        assertThat(Riffle.parse("a = 1\nb = 2\n")).isEqualTo(Riffle.parse("b = 2\na = 1\n"));
        assertThat(Riffle.parse("a = [\nx\ny\n]")).isNotEqualTo(Riffle.parse("a = [\ny\nx\n]"));
        assertThat(Riffle.parse("a = {\nb = 1\n}\n")).isNotEqualTo(Riffle.parse("a = {\nb = @long 1\n}\n"));
    }

    @Test
    void applicationConversionFailuresUseTheSameFormat() {
        RiffleValue value = Riffle.parse("timeout = PT30S\n").get("timeout");
        assertThatThrownBy(() -> {
                    throw new RiffleAccessException(value, "expected an ISO-8601 duration such as PT30S");
                })
                .isInstanceOfSatisfying(RiffleAccessException.class, e -> {
                    assertThat(e.getMessage())
                            .isEqualTo("line 1, column 11: timeout: expected an ISO-8601 duration such as PT30S");
                    assertThat(e.path()).contains("timeout");
                });
        assertThatThrownBy(() -> new RiffleAccessException(RiffleNull.INSTANCE, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNamesTheKind() {
        assertThat(RiffleInt.of(1).toString()).isEqualTo("RiffleInt[1]");
        assertThat(RiffleLong.of(1).toString()).isEqualTo("RiffleLong[1]");
        assertThat(RiffleDecimal.of(new BigDecimal("1.5")).toString()).isEqualTo("RiffleDecimal[1.5]");
        assertThat(RiffleBoolean.of(true).toString()).isEqualTo("RiffleBoolean[true]");
        assertThat(RiffleString.of("x").toString()).isEqualTo("RiffleString[x]");
        assertThat(RiffleNull.INSTANCE.toString()).isEqualTo("RiffleNull");
        assertThat(Riffle.parse("a = {}\n").toString()).isEqualTo("RiffleObject{a=RiffleObject{}}");
    }

    @Test
    void accessExceptionsAreRiffleExceptions() {
        assertThatThrownBy(() -> RiffleInt.of(1).asString()).isInstanceOf(RiffleException.class);
    }

    @Test
    void locationsCompareByValue() {
        RiffleLocation first = Riffle.parse("a = 1\n").get("a").location().orElseThrow();
        RiffleLocation second = Riffle.parse("a = 2\n").get("a").location().orElseThrow();
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).hasToString("line 1, column 5");
    }
}
