package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Strict typed getters on parsed and programmatically constructed trees. */
class TypedGetterTest {

    private static final String DOCUMENT = """
            s = text
            i = @int 3
            l = @long 4
            d = @decimal 0.5
            b = @boolean true
            n = @null
            m = {
                child = nested
            }
            a = [
                item
                @int 7
                @long 8
                @decimal 1.25
                @boolean false
                @null
                {}
                []
            ]
            """;

    @Test
    void objectGettersReadEveryKind() {
        RiffleObject root = Riffle.parse(DOCUMENT);

        assertThat(root.getString("s")).isEqualTo("text");
        assertThat(root.getInt("i")).isEqualTo(3);
        assertThat(root.getLong("l")).isEqualTo(4L);
        assertThat(root.getDecimal("d")).isEqualByComparingTo("0.5");
        assertThat(root.getBoolean("b")).isTrue();
        assertThat(root.getObject("m").getString("child")).isEqualTo("nested");
        assertThat(root.getArray("a").getString(0)).isEqualTo("item");
    }

    @Test
    void arrayGettersReadEveryKind() {
        RiffleArray array = Riffle.parse(DOCUMENT).getArray("a");

        assertThat(array.getString(0)).isEqualTo("item");
        assertThat(array.getInt(1)).isEqualTo(7);
        assertThat(array.getLong(2)).isEqualTo(8L);
        assertThat(array.getDecimal(3)).isEqualByComparingTo("1.25");
        assertThat(array.getBoolean(4)).isFalse();
        assertThat(array.getObject(6).entries()).isEmpty();
        assertThat(array.getArray(7).values()).isEmpty();
    }

    @Test
    void gettersDoNotCoerceOrReturnJavaNullForRiffleNull() {
        RiffleObject root = Riffle.parse(DOCUMENT);

        assertThatThrownBy(() -> root.getLong("i"))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("i: expected long but was int"));
        assertThatThrownBy(() -> root.getInt("l"))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("l: expected int but was long"));
        assertThatThrownBy(() -> root.getInt("d"))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("d: expected int but was decimal"));
        assertThatThrownBy(() -> root.getString("n"))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("n: expected string but was null"));
        assertThatThrownBy(() -> root.getObject("n")).isInstanceOf(RiffleAccessException.class);
        assertThatThrownBy(() -> root.getArray("a").getObject(5))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class,
                        e -> assertThat(e.getMessage()).contains("a[5]: expected map but was null"));
    }

    @Test
    void missingKeysAndOutOfRangeIndexesKeepTheirPaths() {
        RiffleObject root = Riffle.parse(DOCUMENT);

        assertThatThrownBy(() -> root.getInt("absent")).isInstanceOfSatisfying(RiffleAccessException.class, e -> {
            assertThat(e.path()).contains("absent");
            assertThat(e.getMessage()).contains("absent: missing");
        });
        assertThatThrownBy(() -> root.getObject("m").getString("absent"))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class, e -> assertThat(e.path()).contains("m.absent"));

        RiffleArray array = root.getArray("a");
        assertThatThrownBy(() -> array.getString(-1)).isInstanceOfSatisfying(RiffleAccessException.class, e -> {
            assertThat(e.path()).contains("a[-1]");
            assertThat(e.getMessage()).contains("index out of range");
        });
        assertThatThrownBy(() -> array.getString(array.size()))
                .isInstanceOfSatisfying(
                        RiffleAccessException.class, e -> assertThat(e.path()).contains("a[8]"));
    }

    @Test
    void wrongKindErrorsKeepValueLocationsAndPaths() {
        RiffleObject root = Riffle.parse(DOCUMENT);

        assertThatThrownBy(() -> root.getString("i")).isInstanceOfSatisfying(RiffleAccessException.class, e -> {
            assertThat(e.path()).contains("i");
            assertThat(e.location()).isPresent();
            assertThat(e.getMessage()).startsWith("line 2, column 5: ");
        });
        assertThatThrownBy(() -> root.getArray("a").getBoolean(1))
                .isInstanceOfSatisfying(RiffleAccessException.class, e -> {
                    assertThat(e.path()).contains("a[1]");
                    assertThat(e.getMessage()).contains("expected boolean but was int");
                });
    }

    @Test
    void constructedTreesUseTheSameStrictGetters() {
        Map<String, RiffleValue> entries = new LinkedHashMap<>();
        entries.put("s", RiffleString.of("text"));
        entries.put("i", RiffleInt.of(3));
        entries.put("l", RiffleLong.of(4));
        entries.put("d", RiffleDecimal.of(new BigDecimal("0.5")));
        entries.put("b", RiffleBoolean.of(true));
        entries.put("n", RiffleNull.INSTANCE);
        entries.put("a", RiffleArray.of(List.of(RiffleString.of("item"), RiffleInt.of(7))));
        RiffleObject root = RiffleObject.of(entries);

        assertThat(root.getString("s")).isEqualTo("text");
        assertThat(root.getInt("i")).isEqualTo(3);
        assertThat(root.getLong("l")).isEqualTo(4L);
        assertThat(root.getDecimal("d")).isEqualByComparingTo("0.5");
        assertThat(root.getBoolean("b")).isTrue();
        assertThat(root.getArray("a").getString(0)).isEqualTo("item");
        assertThat(root.getArray("a").getInt(1)).isEqualTo(7);

        assertThatThrownBy(() -> root.getString("n")).isInstanceOfSatisfying(RiffleAccessException.class, e -> {
            assertThat(e.location()).isEmpty();
            assertThat(e.path()).isEmpty();
            assertThat(e.getMessage()).isEqualTo("expected string but was null");
        });
    }

    @Test
    void existingCollectionsStayUnmodifiable() {
        RiffleObject root = Riffle.parse(DOCUMENT);

        assertThatThrownBy(() -> root.entries().put("x", RiffleNull.INSTANCE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> root.getArray("a").values().add(RiffleNull.INSTANCE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
