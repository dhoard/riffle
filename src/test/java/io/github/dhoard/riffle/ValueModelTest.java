package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The value model of LANGUAGE_SPEC.md §7: two values are equal when their contents are equal,
 * whatever paths or locations they carry, and equal values hash alike. Maps and arrays are hashed
 * and printed iteratively, so a deep tree behaves the same as a shallow one.
 */
class ValueModelTest {

    private static LinkedHashMap<String, RiffleValue> entries(Object... keysAndValues) {
        LinkedHashMap<String, RiffleValue> entries = new LinkedHashMap<>();
        for (int at = 0; at < keysAndValues.length; at += 2) {
            entries.put((String) keysAndValues[at], (RiffleValue) keysAndValues[at + 1]);
        }
        return entries;
    }

    private static RiffleArray array(RiffleValue... values) {
        List<RiffleValue> elements = new ArrayList<>();
        for (RiffleValue value : values) {
            elements.add(value);
        }
        return RiffleArray.of(elements);
    }

    @Test
    void aParsedTreeEqualsTheSameTreeBuiltInCode() {
        RiffleObject parsed = Riffle.parse("""
                list : [
                    a
                    b
                ]
                nested : {
                    deep : {
                        x : 1
                    }
                }
                """);
        RiffleObject built = RiffleObject.of(entries(
                "list", array(RiffleString.of("a"), RiffleString.of("b")),
                "nested", RiffleObject.of(entries("deep", RiffleObject.of(entries("x", RiffleString.of("1")))))));

        assertThat(parsed).isEqualTo(built);
        assertThat(parsed).hasSameHashCodeAs(built);
    }

    @Test
    void anArrayEqualsAndHashesByItsElements() {
        RiffleArray left = array(RiffleString.of("a"), array(RiffleString.of("b"), RiffleString.of("c")));
        RiffleArray right = array(RiffleString.of("a"), array(RiffleString.of("b"), RiffleString.of("c")));

        assertThat(left).isEqualTo(right);
        assertThat(left).hasSameHashCodeAs(right);
        assertThat(left.equals(left)).isTrue();
    }

    @Test
    void anArrayDiffersFromAnotherKindOrShape() {
        RiffleArray one = array(RiffleString.of("a"));

        assertThat(one).isNotEqualTo(RiffleObject.of(entries("a", RiffleString.of("a"))));
        assertThat(one).isNotEqualTo(array());
        assertThat(one).isNotEqualTo(array(RiffleString.of("b")));
        assertThat(one).isNotEqualTo(array(RiffleString.of("a"), RiffleString.of("b")));
    }

    @Test
    void aMapDiffersFromAnotherKindOrEntry() {
        RiffleObject map = RiffleObject.of(entries("a", RiffleString.of("1")));

        assertThat(map).isNotEqualTo(array(RiffleString.of("1")));
        assertThat(map).isNotEqualTo(RiffleObject.of(entries("a", RiffleString.of("2"))));
        assertThat(map).isNotEqualTo(RiffleObject.of(entries("a", array(RiffleString.of("1")))));
        assertThat(map).isNotEqualTo(RiffleObject.of(entries("b", RiffleString.of("1"))));
    }

    @Test
    void aStringCarriesTheHashOfItsText() {
        assertThat(RiffleString.of("a")).isEqualTo(RiffleString.of("a"));
        assertThat(RiffleString.of("a")).hasSameHashCodeAs(RiffleString.of("a"));
        assertThat(RiffleString.of("a")).isNotEqualTo(RiffleString.of("b"));
        assertThat(RiffleString.of("a")).isNotEqualTo(array(RiffleString.of("a")));
    }

    @Test
    void valuesPrintTheirStructure() {
        assertThat(array(RiffleString.of("a"), RiffleString.of("b")))
                .hasToString("RiffleArray[RiffleString[a], RiffleString[b]]");
        assertThat(array()).hasToString("RiffleArray[]");
        assertThat(array(array(RiffleString.of("a")))).hasToString("RiffleArray[RiffleArray[RiffleString[a]]]");
        assertThat(RiffleObject.of(entries("a", RiffleString.of("1"), "b", RiffleString.of("2"))))
                .hasToString("RiffleObject{a=RiffleString[1], b=RiffleString[2]}");
        assertThat(RiffleObject.of(entries())).hasToString("RiffleObject{}");
    }

    @Test
    void pathsCompareByTheirSegments() {
        RifflePath keyed = RifflePath.root().key("a");
        RifflePath indexed = RifflePath.root().index(0);

        assertThat(keyed).isEqualTo(RifflePath.root().key("a"));
        assertThat(keyed).isNotEqualTo(indexed);
        assertThat(keyed).isNotEqualTo(RifflePath.root().key("a").key("b"));
        assertThat(keyed).isNotEqualTo("a");
        assertThat(keyed).isNotEqualTo(null);
        assertThat(keyed).hasSameHashCodeAs(RifflePath.root().key("a"));
        assertThat(RifflePath.root()).isNotEqualTo(keyed);
    }

    @Test
    void pathsRenderInThePathSyntaxOfTheSpecification() {
        assertThat(RifflePath.root()).hasToString("(root)");
        assertThat(RifflePath.root().key("a").index(2).key("b")).hasToString("a[2].b");
        assertThat(RifflePath.root().key("not a key")).hasToString("\"not a key\"");
    }

    @Test
    void locationsCompareBySourceLineAndColumn() {
        RiffleLocation file = new RiffleLocation("a.riffle", 2, 3);
        RiffleLocation sameFile = new RiffleLocation("a.riffle", 2, 3);
        RiffleLocation otherLine = new RiffleLocation("a.riffle", 3, 3);
        RiffleLocation otherColumn = new RiffleLocation("a.riffle", 2, 4);
        RiffleLocation noSource = new RiffleLocation(null, 2, 3);

        assertThat(file).isEqualTo(sameFile).hasSameHashCodeAs(sameFile);
        assertThat(file).isNotEqualTo(otherLine);
        assertThat(file).isNotEqualTo(otherColumn);
        assertThat(file).isNotEqualTo(noSource);
        assertThat(noSource).isNotEqualTo(file);
        assertThat(file).isNotEqualTo("a.riffle:2:3");
        assertThat(file).hasToString("a.riffle:2:3");
        assertThat(noSource).hasToString("line 2, column 3");
    }

    @Test
    void optionsCompareByTheirCapAndSayWhichTheyAre() {
        assertThat(RiffleOptions.maxDepth(3)).isEqualTo(RiffleOptions.maxDepth(3));
        assertThat(RiffleOptions.maxDepth(3)).hasSameHashCodeAs(RiffleOptions.maxDepth(3));
        assertThat(RiffleOptions.maxDepth(3)).isNotEqualTo(RiffleOptions.maxDepth(4));
        assertThat(RiffleOptions.maxDepth(3)).isNotEqualTo(RiffleOptions.UNBOUNDED);
        assertThat(RiffleOptions.maxDepth(3)).isNotEqualTo("maxDepth=3");
        assertThat(RiffleOptions.UNBOUNDED).isNotEqualTo(RiffleOptions.maxDepth(3));
        assertThat(RiffleOptions.maxDepth(3)).hasToString("RiffleOptions[maxDepth=3]");
        assertThat(RiffleOptions.UNBOUNDED).hasToString("RiffleOptions[UNBOUNDED]");
    }

    @Test
    void anEmptyArrayAndAnEmptyMapAddNoDepth() {
        RiffleObject empties = Riffle.parse("""
                emptyMap : {
                }
                emptyArray : [
                ]
                """);
        RiffleObject scalars = Riffle.parse("""
                a : 1
                b : two
                """);

        assertThat(TreeDepth.of(empties)).isEqualTo(TreeDepth.of(scalars));
        assertThat(RiffleOptions.maxDepth(1).exceeds(TreeDepth.of(empties))).isFalse();
    }
}
