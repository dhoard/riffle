package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The documents that represent the real consumer data. */
class AmandaFixturesTest {

    private static Path resource(String name) throws Exception {
        return Path.of(
                AmandaFixturesTest.class.getClassLoader().getResource(name).toURI());
    }

    @ParameterizedTest
    @ValueSource(strings = {"amanda/profile.riffle", "amanda/theme.riffle"})
    void fixturesAreCanonical(String name) throws Exception {
        String document = Files.readString(resource(name), StandardCharsets.UTF_8);
        assertThat(Riffle.write(Riffle.parse(document))).isEqualTo(document);
    }

    @Test
    void profileHasTheExpectedShape() throws Exception {
        RiffleObject profile = Riffle.parse(resource("amanda/profile.riffle"));
        assertThat(profile.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(profile.get("provider").asString()).isEqualTo("chatgpt");
        assertThat(profile.get("transport").asString()).isEqualTo("chatgpt-oauth");

        RiffleObject capabilities = profile.get("capabilities").asObject();
        assertThat(capabilities.get("tools").asBoolean()).isTrue();
        assertThat(capabilities.get("contextWindow").asInt()).isEqualTo(272000);
        assertThat(capabilities.get("maxOutputTokens").asInt()).isEqualTo(128000);
        assertThat(capabilities.get("reasoning").asArray().values())
                .extracting(RiffleValue::asString)
                .containsExactly("none", "low", "medium", "high", "xhigh", "max");
        assertThat(capabilities.get("defaultReasoning").asString()).isEqualTo("high");
        assertThat(capabilities.get("replayRequirements").asArray().get(0).asString())
                .isEqualTo("chatgpt-oauth");

        assertThat(profile.get("options")
                        .asObject()
                        .get("chatgpt-oauth")
                        .asObject()
                        .entries())
                .isEmpty();
    }

    @Test
    void themeIsAFlatMapOfQuotedColorStrings() throws Exception {
        RiffleObject theme = Riffle.parse(resource("amanda/theme.riffle"));
        RiffleObject colors = theme.get("colors").asObject();
        assertThat(theme.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(colors.entries()).hasSizeGreaterThan(30);
        assertThat(colors.get("accent").asString()).isEqualTo("#8abeb7");
        assertThat(colors.get("background").asString()).isEqualTo("#000000");
        assertThat(colors.entries().values()).allSatisfy(value -> {
            assertThat(value.isString()).isTrue();
            assertThat(value.asString()).startsWith("#").hasSize(7);
        });
    }
}
