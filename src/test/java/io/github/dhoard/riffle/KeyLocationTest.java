package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A missing member is reported at the key it would have had, so the parser has to remember where
 * each key was written (LANGUAGE_SPEC.md §10.1, SCHEMA_SPEC.md §10).
 */
class KeyLocationTest {

    @Test
    void reportsWhereAParsedKeyWasWritten() {
        RiffleObject document = Riffle.parse("port : 80\nname : gateway\n");

        assertThat(document.keyLocation("port")).isEqualTo(new RiffleLocation(null, 1, 1));
        assertThat(document.keyLocation("name")).isEqualTo(new RiffleLocation(null, 2, 1));
    }

    @Test
    void distinguishesTheKeyFromTheValueItHolds() {
        RiffleObject document = Riffle.parse("port : 80\n");

        assertThat(document.keyLocation("port")).isEqualTo(new RiffleLocation(null, 1, 1));
        assertThat(document.get("port").location()).contains(new RiffleLocation(null, 1, 8));
    }

    @Test
    void reportsAKeyOfANestedMapAtItsOwnPlace() {
        RiffleObject document = Riffle.parse("env : {\n    region : EU\n}\n");

        assertThat(document.getObject("env").keyLocation("region")).isEqualTo(new RiffleLocation(null, 2, 5));
    }

    @Test
    void countsTheOpeningQuoteOfAQuotedKey() {
        RiffleObject document = Riffle.parse("\"display.name\" : Jane\n");

        assertThat(document.keyLocation("display.name")).isEqualTo(new RiffleLocation(null, 1, 1));
    }

    @Test
    void reportsNothingForAKeyThatIsAbsent() {
        RiffleObject document = Riffle.parse("port : 80\n");

        assertThat(document.keyLocation("name")).isNull();
    }

    @Test
    void reportsNothingForAKeyOfAValueBuiltInCode() {
        Map<String, RiffleValue> entries = new LinkedHashMap<>();
        entries.put("port", RiffleString.of("80"));

        assertThat(RiffleObject.of(entries).keyLocation("port")).isNull();
    }

    @Test
    void keepsTheKeyOfAMemberWhoseValueHasNoLocation() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        port : {\n            type : integer\n            min : 1024\n        }\n    }\n}\n");
        RiffleObject document = RiffleObject.of(Map.of("port", RiffleString.of("80")));

        assertThat(schema.validate(document))
                .singleElement()
                .extracting(RiffleProblem::category, RiffleProblem::path)
                .containsExactly(
                        RiffleValidationError.OUT_OF_RANGE, RifflePath.root().key("port"));
        assertThat(schema.validate(document))
                .allSatisfy(problem -> assertThat(problem.location()).isEmpty());
    }
}
