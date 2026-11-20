package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Nesting is unbounded by default; a caller can opt into a cap that the parser and writer enforce.
 * Documents that are large only in width or line length are accepted, because time and memory stay
 * proportional to the input.
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
        RiffleValue value = RiffleString.of("leaf");
        for (int i = 0; i < blocks; i++) {
            value = i % 2 == 0 ? RiffleObject.of(Map.of("k" + i, value)) : RiffleArray.of(List.of(value));
        }
        return RiffleObject.of(Map.of("root", value));
    }

    @Test
    void aDeepTreeRoundTripsWithoutAConfiguredCap() {
        RiffleObject value = mapChain(10_000, RiffleString.of("leaf"));

        String written = Riffle.write(value);
        RiffleObject parsed = Riffle.parse(written);

        assertThat(parsed).isEqualTo(value);
        assertThat(parsed.hashCode()).isEqualTo(value.hashCode());
        assertThat(parsed.toString()).isEqualTo(value.toString());
        assertThat(Riffle.write(parsed)).isEqualTo(written);
    }

    @Test
    void mixedMapsAndArraysRoundTripWithoutACap() {
        RiffleObject value = mixedChain(2_000);
        assertThat(Riffle.parse(Riffle.write(value))).isEqualTo(value);
    }

    @Test
    void aConfiguredCapIsEnforcedByTheParserAndTheWriter() {
        RiffleObject value = mapChain(128, RiffleString.of("leaf"));
        String written = Riffle.write(value);

        assertThat(Riffle.parse(written, RiffleOptions.maxDepth(128))).isEqualTo(value);
        assertThatThrownBy(() -> Riffle.write(mapChain(129, RiffleString.of("leaf")), RiffleOptions.maxDepth(128)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
        assertThatThrownBy(() ->
                        Riffle.parse(Riffle.write(mapChain(129, RiffleString.of("leaf"))), RiffleOptions.maxDepth(128)))
                .isInstanceOfSatisfying(RiffleParseException.class, e -> {
                    assertThat(e.category()).isEqualTo(RiffleError.RESOURCE_LIMIT_EXCEEDED);
                    assertThat(e.location().line()).isEqualTo(129);
                });
    }

    @Test
    void anInlineEmptyCollectionDoesNotCountTowardACap() {
        RiffleObject value = mapChain(128, RiffleObject.of(Map.of()));
        String written = Riffle.write(value, RiffleOptions.maxDepth(128));
        assertThat(written).contains("k0 : {}");
        assertThat(Riffle.parse(written, RiffleOptions.maxDepth(128))).isEqualTo(value);
    }

    @Test
    void wideDocumentsAreAccepted() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            document.append('k').append(i).append(" : ").append(i).append('\n');
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
            document.append("    ").append(i).append('\n');
        }
        document.append("]\n");
        assertThat(Riffle.parse(document.toString()).get("a").asArray().size()).isEqualTo(20_000);
    }
}
