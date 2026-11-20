package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The nesting cap of {@link RiffleOptions} applies to a tree the host built itself, not only to a
 * document being parsed, so a value assembled after a parse cannot evade a caller's limit.
 */
class TreeDepthTest {

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

    /** The root map holds an array, which holds a second array. */
    private static RiffleObject arraysTwoLevelsDeep() {
        return RiffleObject.of(entries("list", array(array(RiffleString.of("a")))));
    }

    @Test
    void depthCountsNonEmptyCollectionsBetweenTheRootMapAndAValue() {
        assertThat(TreeDepth.of(RiffleObject.of(entries()))).isZero();
        assertThat(TreeDepth.of(RiffleObject.of(entries("a", RiffleString.of("1")))))
                .isEqualTo(1);
        assertThat(TreeDepth.of(
                        RiffleObject.of(entries("nested", RiffleObject.of(entries("b", RiffleString.of("1")))))))
                .isEqualTo(2);
        assertThat(TreeDepth.of(arraysTwoLevelsDeep())).isEqualTo(3);
    }

    @Test
    void emptyCollectionsAddNoDepth() {
        RiffleObject document = RiffleObject.of(entries(
                "emptyMap", RiffleObject.of(entries()),
                "emptyArray", array()));

        assertThat(TreeDepth.of(document)).isEqualTo(1);
    }

    @Test
    void aTreeWithinTheCapIsAccepted() {
        assertThatCode(() -> TreeDepth.check(arraysTwoLevelsDeep(), RiffleOptions.maxDepth(3)))
                .doesNotThrowAnyException();
    }

    @Test
    void anArrayDeeperThanTheCapNamesTheDefaultLimit() {
        assertThatThrownBy(() -> TreeDepth.check(arraysTwoLevelsDeep(), RiffleOptions.maxDepth(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value tree is deeper than the configured limit: 3 > 2");
    }

    @Test
    void aCallerNamesTheLimitItApplied() {
        assertThatThrownBy(() -> TreeDepth.check(
                        arraysTwoLevelsDeep(),
                        RiffleOptions.maxDepth(2),
                        "document tree is deeper than the configured limit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document tree is deeper than the configured limit: 3 > 2");
    }

    @Test
    void aMapDeeperThanTheCapIsRejected() {
        RiffleObject deep = RiffleObject.of(entries(
                "outer", RiffleObject.of(entries("inner", RiffleObject.of(entries("x", RiffleString.of("1")))))));

        assertThatThrownBy(() -> TreeDepth.check(deep, RiffleOptions.maxDepth(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value tree is deeper than the configured limit: 3 > 2");
    }
}
