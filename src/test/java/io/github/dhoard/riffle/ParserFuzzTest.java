package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Random token soup only ever fails as {@link RiffleParseException}, and every document the
 * parser accepts is written back in canonical form, which parses to an equal value.
 */
class ParserFuzzTest {

    private static final String[] TOKENS = {
        "a",
        "k",
        "key",
        "=",
        " ",
        "\t",
        "\n",
        "\r\n",
        "#",
        "//",
        "\"",
        "\\",
        "\\u0041",
        "\\uD83D",
        "\\uDCA1",
        "{",
        "}",
        "[",
        "]",
        "{}",
        "[]",
        "@int",
        "@long",
        "@decimal",
        "@boolean",
        "@null",
        "@string",
        "@map",
        "@array",
        "@float",
        "1",
        "-1",
        "0.5",
        "1e3",
        "true",
        "false",
        "null",
        "~",
        "é",
        "💡",
        "\uFEFF",
        "\u0001",
        "@int [",
        "@string [",
        "k = v",
        "k =",
        "k = @int 1",
        "k = {",
        "k = [",
        "\"k\" = v",
        "k = \"v\""
    };

    @Test
    void randomTokenSoupFailsOnlyWithParseErrorsAndAcceptedDocumentsRoundTrip() {
        Random random = new Random(20260101);
        int accepted = 0;
        for (int i = 0; i < 5_000; i++) {
            String document = randomDocument(random);
            RiffleObject parsed;
            try {
                parsed = Riffle.parse(document);
            } catch (RiffleParseException expected) {
                continue;
            }
            accepted++;
            String written = Riffle.write(parsed);
            assertThat(Riffle.parse(written)).as(document).isEqualTo(parsed);
            assertThat(Riffle.write(Riffle.parse(written))).as(document).isEqualTo(written);
        }
        assertThat(accepted).as("the corpus must accept some documents").isPositive();
    }

    private static String randomDocument(Random random) {
        StringBuilder document = new StringBuilder();
        int tokens = 1 + random.nextInt(8);
        for (int i = 0; i < tokens; i++) {
            document.append(TOKENS[random.nextInt(TOKENS.length)]);
        }
        return document.toString();
    }
}
