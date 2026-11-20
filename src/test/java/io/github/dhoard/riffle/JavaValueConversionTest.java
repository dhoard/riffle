package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Conversion between the Riffle value model and plain Java collections. An empty collection is a
 * value in its own right in both directions, and a nesting cap applies to a tree the host built
 * itself.
 */
class JavaValueConversionTest {

    @Test
    void anEmptyRiffleCollectionBecomesAnEmptyJavaCollection() {
        assertThat(RiffleJavaValues.toJava(RiffleObject.of(Map.of()))).isEqualTo(Map.of());
        assertThat(RiffleJavaValues.toJava(RiffleArray.of(List.of()))).isEqualTo(List.of());
    }

    @Test
    void anEmptyCollectionNestedInsideAnotherIsConvertedToo() {
        LinkedHashMap<String, RiffleValue> entries = new LinkedHashMap<>();
        entries.put("map", RiffleObject.of(Map.of()));
        entries.put("list", RiffleArray.of(List.of()));
        entries.put("arrayOfEmpty", RiffleArray.of(List.of(RiffleArray.of(List.of()))));

        assertThat(RiffleJavaValues.toJava(RiffleObject.of(entries)))
                .isEqualTo(Map.of(
                        "map", Map.of(),
                        "list", List.of(),
                        "arrayOfEmpty", List.of(List.of())));
    }

    @Test
    void anEmptyJavaCollectionBecomesAnEmptyRiffleCollection() {
        Map<String, Object> emptyMap = Map.of();
        List<Object> emptyList = List.of();

        assertThat(RiffleJavaValues.fromJava(emptyMap)).isEqualTo(RiffleObject.of(Map.of()));
        assertThat(RiffleJavaValues.fromJava(emptyList)).isEqualTo(RiffleArray.of(List.of()));
    }

    @Test
    void anEmptyCollectionNestedInsideAJavaCollectionIsConvertedToo() {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("map", Map.of());
        outer.put("list", List.of());
        outer.put("listOfEmpty", List.of(List.of()));

        assertThat(RiffleJavaValues.fromJava(outer))
                .isEqualTo(RiffleObject.of(Map.of(
                        "map", RiffleObject.of(Map.of()),
                        "list", RiffleArray.of(List.of()),
                        "listOfEmpty", RiffleArray.of(List.of(RiffleArray.of(List.of()))))));
    }

    @Test
    void theTwoDirectionsRoundTripThroughTheSameValue() {
        RiffleObject document = Riffle.parse("""
                name : widget
                port : 8080
                list : [
                    a
                    b
                ]
                nested : {
                    flag : true
                }
                """);

        Object java = RiffleJavaValues.toJava(document);
        assertThat(RiffleJavaValues.fromJava(java)).isEqualTo(document);
    }

    @Test
    void aJavaTreeDeeperThanTheCapIsRejected() {
        Map<String, Object> deep = Map.of("a", Map.of("b", Map.of("c", Map.of("d", "x"))));

        assertThatThrownBy(() -> RiffleJavaValues.fromJava(deep, RiffleOptions.maxDepth(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Java value is deeper than the configured limit: 3 > 2");
        assertThatThrownBy(() -> RiffleJavaValues.toJava(Riffle.parse("""
                        a : {
                            b : {
                                c : {
                                    d : x
                                }
                            }
                        }
                        """), RiffleOptions.maxDepth(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value tree is deeper than the configured limit: 3 > 2");
    }

    @Test
    void aJavaValueRiffleCannotHoldIsReportedWithItsType() {
        assertThatThrownBy(() -> RiffleJavaValues.fromJava(new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.Object");
        assertThatThrownBy(() -> RiffleJavaValues.fromJava(Map.of(1, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("map keys must be non-null strings");
    }

    @Test
    void theBooleanInterpretationReadsOnlyTrueAndFalse() {
        RiffleObject document = Riffle.parse("""
                yes : true
                no : false
                other : maybe
                """);

        assertThat(document.get("yes").isBoolean()).isTrue();
        assertThat(document.get("no").isBoolean()).isTrue();
        assertThat(document.get("other").isBoolean()).isFalse();
    }
}
