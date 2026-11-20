package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Tests the public resource-options value that replaces the old fixed nesting limit. */
class RiffleOptionsTest {

    @Test
    void theDefaultIsUnbounded() {
        assertThat(RiffleOptions.UNBOUNDED.maxDepth()).isEmpty();
        assertThat(RiffleOptions.maxDepth(Integer.MAX_VALUE)).isSameAs(RiffleOptions.UNBOUNDED);
        assertThat(RiffleOptions.UNBOUNDED.exceeds(Integer.MAX_VALUE)).isFalse();
    }

    @Test
    void aConfiguredCapHasValueSemantics() {
        RiffleOptions first = RiffleOptions.maxDepth(128);
        RiffleOptions second = RiffleOptions.maxDepth(128);

        assertThat(first.maxDepth()).hasValue(128);
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.exceeds(128)).isFalse();
        assertThat(first.exceeds(129)).isTrue();
        assertThat(first.toString()).contains("128");
    }

    @Test
    void aNegativeCapIsRejected() {
        assertThatThrownBy(() -> RiffleOptions.maxDepth(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }
}
