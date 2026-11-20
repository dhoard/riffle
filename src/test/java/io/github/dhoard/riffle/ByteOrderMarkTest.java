package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * U+FEFF is legal only as the document's leading byte-order mark, so the writer must escape it
 * wherever it occurs inside a value. A key cannot hold it at all: it is outside the key alphabet
 * of LANGUAGE_SPEC.md §6.2, in either spelling.
 */
class ByteOrderMarkTest {

    private static final String BOM = "\uFEFF";

    private static RiffleObject object(String key, String text) {
        Map<String, RiffleValue> map = new LinkedHashMap<>();
        map.put(key, RiffleString.of(text));
        return RiffleObject.of(map);
    }

    @Test
    void aByteOrderMarkInTheMiddleOfAValueSurvivesARoundTrip() {
        RiffleObject value = object("k", "a" + BOM + "b");
        String written = Riffle.write(value);
        assertThat(written).doesNotContain(BOM).contains("\\uFEFF");
        assertThat(Riffle.parse(written)).isEqualTo(value);
    }

    @Test
    void aByteOrderMarkAtTheStartOfAValueSurvivesARoundTrip() {
        RiffleObject value = object("k", BOM + "abc");
        String written = Riffle.write(value);
        assertThat(written).doesNotContain(BOM);
        assertThat(Riffle.parse(written)).isEqualTo(value);
    }

    @Test
    void aValueThatIsOnlyAByteOrderMarkSurvivesARoundTrip() {
        RiffleObject value = object("k", BOM);
        String written = Riffle.write(value);
        assertThat(written).doesNotContain(BOM);
        assertThat(Riffle.parse(written)).isEqualTo(value);
    }

    @Test
    void aByteOrderMarkInAKeyIsRejectedInBothSpellings() {
        assertThatThrownBy(() -> object("a" + BOM + "b", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Riffle.parse("\"a\\uFEFFb\" : v\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.INVALID_KEY));
        // A literal byte-order mark away from the start of the document is rejected as a character
        // before any key is read, so the spelling that shows the character reports that.
        assertThatThrownBy(() -> Riffle.parse("a" + BOM + "b : v\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.INVALID_CHARACTER));
    }

    @Test
    void theCanonicalSpellingOfAByteOrderMarkIsAnEscape() {
        assertThat(Riffle.write(object("k", "a" + BOM + "b"))).isEqualTo("k : \"a\\uFEFFb\"\n");
    }

    @Test
    void parsingAByteOrderMarkEscapeProducesTheCharacter() {
        assertThat(Riffle.parse("k : \"a\\uFEFFb\"\n").get("k").asString()).isEqualTo("a" + BOM + "b");
    }

    @Test
    void canonicalOutputContainingAByteOrderMarkIsIdempotent() {
        RiffleObject value = object("k", "a" + BOM + "b");
        String once = Riffle.write(value);
        assertThat(once).isEqualTo("k : \"a\\uFEFFb\"\n");
        assertThat(Riffle.parse(once)).isEqualTo(value);
        assertThat(Riffle.write(Riffle.parse(once))).isEqualTo(once);
    }

    @Test
    void aLeadingDocumentByteOrderMarkIsIgnoredButALaterOneIsRejected() {
        assertThat(Riffle.parse(BOM + "k : v\n")).isEqualTo(object("k", "v"));
        assertThatThrownBy(() -> Riffle.parse("k : a" + BOM + "b\n"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.INVALID_CHARACTER));
    }
}
