package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Access to parsed and constructed trees, construction rules and equality. */
class ValueAccessTest {

    private static RiffleObject document() {
        return Riffle.parse("""
                s : text
                i : 3
                l : 9000000000
                d : 0.5
                b : true
                q : "12"
                m : {}
                a : [
                    x
                ]
                """);
    }

    @Test
    void findReturnsPresentValuesAndEmptyForAbsentKeys() {
        RiffleObject root = document();
        assertThat(root.find("s").orElseThrow().asString()).isEqualTo("text");
        assertThat(root.find("missing")).isEmpty();
    }

    @Test
    void getRejectsMissingKeysWithPathAndContainerLocation() {
        RiffleObject root = Riffle.parse("a : {\n    b : text\n}\n");
        assertThatThrownBy(() -> root.get("a").asObject().get("c"))
                .isInstanceOfSatisfying(RiffleAccessException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("line 1, column 5: a.c: missing");
                    assertThat(e.path()).contains(RifflePath.root().key("a").key("c"));
                    assertThat(e.category()).isEqualTo(RiffleAccessError.MISSING);
                });
    }

    @Test
    void entriesKeepDocumentOrderAndAreUnmodifiable() {
        RiffleObject root = Riffle.parse("b : 1\na : 2\n");
        assertThat(root.entries().keySet()).containsExactly("b", "a");
        assertThatThrownBy(() -> root.entries().put("c", RiffleString.of("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void arraysAreUnmodifiableAndIndexed() {
        RiffleArray array = Riffle.parse("a : [\nx\ny\n]").get("a").asArray();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(1).asString()).isEqualTo("y");
        assertThat(array.values()).hasSize(2);
        assertThatThrownBy(() -> array.values().add(RiffleString.of("z")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> array.get(2))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("index out of range"));
        assertThatThrownBy(() -> array.get(-1)).isInstanceOf(RiffleAccessException.class);
    }

    @Test
    void predicatesAreTrueExactlyWhenTheAccessorSucceeds() {
        RiffleObject root = document();
        for (Map.Entry<String, RiffleValue> entry : root.entries().entrySet()) {
            RiffleValue value = entry.getValue();
            assertThat(value.isInt()).as(entry.getKey()).isEqualTo(canRead(value::asInt));
            assertThat(value.isLong()).as(entry.getKey()).isEqualTo(canRead(value::asLong));
            assertThat(value.isDecimal()).as(entry.getKey()).isEqualTo(canRead(value::asDecimal));
            assertThat(value.isBoolean()).as(entry.getKey()).isEqualTo(canRead(value::asBoolean));
            assertThat(value.isString()).as(entry.getKey()).isEqualTo(canRead(value::asString));
            assertThat(value.isObject()).as(entry.getKey()).isEqualTo(canRead(value::asObject));
            assertThat(value.isArray()).as(entry.getKey()).isEqualTo(canRead(value::asArray));
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
        RiffleValue value = RiffleString.of("1");
        assertThat(value.location()).isEmpty();
        assertThat(value.path()).isEmpty();
        assertThat(RiffleObject.of(Map.of("k", value)).location()).isEmpty();
        assertThatThrownBy(() -> RiffleObject.of(Map.of()).asString())
                .isInstanceOfSatisfying(RiffleAccessException.class, e -> {
                    assertThat(e.location()).isEmpty();
                    assertThat(e.path()).isEmpty();
                    assertThat(e.getMessage()).isEqualTo("expected string but was map");
                });
    }

    @Test
    void constructionRejectsUnwritableText() {
        RiffleString value = RiffleString.of("v");
        assertThatThrownBy(() -> RiffleString.of("a\uD83Db")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiffleString.of("a\u0000b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiffleObject.of(Map.of("a\uD83Db", value)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiffleObject.of(Map.of("a\u0000b", value)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RiffleString.of("a\tb\nc").asString()).isEqualTo("a\tb\nc");
        assertThat(RiffleObject.of(Map.of("a b", value)).entries()).hasSize(1);
        assertThatThrownBy(() -> RiffleArray.of(java.util.Arrays.asList(value, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RiffleObject.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructionCopiesItsInput() {
        Map<String, RiffleValue> source = new LinkedHashMap<>();
        source.put("a", RiffleString.of("1"));
        RiffleObject object = RiffleObject.of(source);
        source.put("b", RiffleString.of("2"));
        assertThat(object.entries()).containsOnlyKeys("a");

        List<RiffleValue> values = new ArrayList<>(List.of(RiffleString.of("1")));
        RiffleArray array = RiffleArray.of(values);
        values.add(RiffleString.of("2"));
        assertThat(array.size()).isEqualTo(1);
    }

    @Test
    void equalityIgnoresLocationAndSpellingButNotText() {
        assertThat(document().get("i")).isEqualTo(RiffleString.of("3"));
        assertThat(document().get("q")).isEqualTo(RiffleString.of("12"));
        assertThat(RiffleString.of("1.0")).isNotEqualTo(RiffleString.of("1.00"));
        assertThat(RiffleString.of("a")).isNotEqualTo(RiffleArray.of(List.of(RiffleString.of("a"))));
    }

    @Test
    void objectEqualityIgnoresOrderAndArrayEqualityRespectsIt() {
        assertThat(Riffle.parse("a : 1\nb : 2\n")).isEqualTo(Riffle.parse("b : 2\na : 1\n"));
        assertThat(Riffle.parse("a : [\nx\ny\n]")).isNotEqualTo(Riffle.parse("a : [\ny\nx\n]"));
    }

    @Test
    void applicationConversionFailuresUseTheSameFormat() {
        RiffleValue value = Riffle.parse("timeout : PT30S\n").get("timeout");
        assertThatThrownBy(() -> {
                    throw new RiffleAccessException(value, "expected an ISO-8601 duration such as PT30S");
                })
                .isInstanceOfSatisfying(RiffleAccessException.class, e -> {
                    assertThat(e.getMessage())
                            .isEqualTo("line 1, column 11: timeout: expected an ISO-8601 duration such as PT30S");
                    assertThat(e.path()).contains(RifflePath.root().key("timeout"));
                });
        assertThatThrownBy(() -> new RiffleAccessException(value, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNamesTheKind() {
        assertThat(RiffleString.of("x").toString()).isEqualTo("RiffleString[x]");
        assertThat(Riffle.parse("a : {}\n").toString()).isEqualTo("RiffleObject{a=RiffleObject{}}");
    }

    @Test
    void accessExceptionsAreRiffleExceptions() {
        assertThatThrownBy(() -> RiffleArray.of(List.of()).asString()).isInstanceOf(RiffleException.class);
    }

    @Test
    void locationsCompareByValue() {
        RiffleLocation first = Riffle.parse("a : 1\n").get("a").location().orElseThrow();
        RiffleLocation second = Riffle.parse("a : 2\n").get("a").location().orElseThrow();
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).hasToString("line 1, column 5");
    }
}
