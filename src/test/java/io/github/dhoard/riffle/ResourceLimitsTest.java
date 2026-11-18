package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The nesting limit is a single policy: the parser rejects deeper documents and the writer
 * refuses to produce one. Documents that are large only in width or line length are accepted,
 * because the parser's memory use stays proportional to the input.
 */
class ResourceLimitsTest {

    /** A root map whose single member holds {@code blocks} nested maps around {@code leaf}. */
    private static RiffleObject mapChain(int blocks, RiffleValue leaf) {
        RiffleValue value = leaf;
        for (int i = 0; i < blocks; i++) {
            value = RiffleObject.of(Map.of("k" + i, value));
        }
        return RiffleObject.of(Map.of("root", value));
    }

    /** A root map whose single member holds {@code blocks} alternating maps and arrays. */
    private static RiffleObject mixedChain(int blocks) {
        RiffleValue value = RiffleNull.INSTANCE;
        for (int i = 0; i < blocks; i++) {
            value = i % 2 == 0 ? RiffleObject.of(Map.of("k" + i, value)) : RiffleArray.of(List.of(value));
        }
        return RiffleObject.of(Map.of("root", value));
    }

    @Test
    void aTreeAtTheNestingLimitCanBeWrittenAndParsed() {
        RiffleObject value = mapChain(128, RiffleNull.INSTANCE);
        String written = Riffle.write(value);
        assertThat(Riffle.parse(written)).isEqualTo(value);
        assertThat(Riffle.write(Riffle.parse(written))).isEqualTo(written);
    }

    @Test
    void mixedMapsAndArraysAtTheNestingLimitCanBeWrittenAndParsed() {
        RiffleObject value = mixedChain(128);
        assertThat(Riffle.parse(Riffle.write(value))).isEqualTo(value);
    }

    @Test
    void aTreeBeyondTheNestingLimitIsRejectedInsteadOfProducingUnparseableText() {
        assertThatThrownBy(() -> Riffle.write(mapChain(129, RiffleNull.INSTANCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting")
                .hasMessageContaining("128");
        assertThatThrownBy(() -> Riffle.write(mixedChain(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    @Test
    void anInlineEmptyCollectionDoesNotCountTowardsTheLimit() {
        RiffleObject value = mapChain(128, RiffleObject.of(Map.of()));
        String written = Riffle.write(value);
        assertThat(written).contains("k0 : {}");
        assertThat(Riffle.parse(written)).isEqualTo(value);
    }

    @Test
    void aVeryDeepTreeFailsWithoutStackOverflow() {
        assertThatThrownBy(() -> Riffle.write(mapChain(20_000, RiffleNull.INSTANCE)))
                .isInstanceOf(IllegalArgumentException.class);
        RiffleValue value = RiffleNull.INSTANCE;
        for (int i = 0; i < 20_000; i++) {
            value = RiffleArray.of(List.of(value));
        }
        RiffleArray deep = RiffleArray.of(List.of(value));
        assertThatThrownBy(() -> Riffle.write(RiffleObject.of(Map.of("root", deep))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wideDocumentsAreAccepted() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            document.append('k').append(i).append(" : @int ").append(i).append('\n');
        }
        assertThat(Riffle.parse(document.toString()).entries()).hasSize(20_000);
    }

    @Test
    void longLinesAreAccepted() {
        String text = "x".repeat(100_000);
        RiffleObject parsed = Riffle.parse("long : " + text + "\n");
        assertThat(parsed.get("long").asString()).isEqualTo(text);
        assertThat(Riffle.write(parsed)).isEqualTo("long : " + text + "\n");
    }

    @Test
    void manyArrayElementsAreAccepted() {
        StringBuilder document = new StringBuilder("a : [\n");
        for (int i = 0; i < 20_000; i++) {
            document.append("    @int ").append(i).append('\n');
        }
        document.append("]\n");
        assertThat(Riffle.parse(document.toString()).get("a").asArray().size()).isEqualTo(20_000);
    }
}
