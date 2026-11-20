package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The examples of `LANGUAGE_SPEC.md` §15, used directly as conformance tests. */
class SpecConformanceTest {

    private static final String VALID = """
            # blank string and literal strings
            empty :
            stringTrue : true
            stringNull : null
            stringTilde : ~
            port : 8080
            quotedPort : "8080"
            literalAt : "@scope/name"

            # quoted key spelling and punctuation
            "dotted.key" : a=b
            url : https://example.org/a#fragment
            note : prod # primary

            # empty collections
            emptyMap : {}
            emptyArray : []

            # nested values and mixed array
            complex : {
                items : [
                    text
                    9000000000
                    1.25
                    {
                        enabled : false
                    }
                    [
                        nested
                    ]
                ]
            }

            # multiline raw block
            query : \"""
                SELECT *
                  FROM users
                \"""

            # block comment region: whole lines are ignored until the matching `#/`
            /#
            disabled : 1
            #/
            """;

    @Test
    void theSpecificationValidExampleParsesToTheDocumentedMeaning() {
        RiffleObject root = Riffle.parse(VALID);
        assertThat(root.get("empty").asString()).isEmpty();
        assertThat(root.get("stringTrue").asString()).isEqualTo("true");
        assertThat(root.get("stringTrue").asBoolean()).isTrue();
        assertThat(root.get("stringNull").asString()).isEqualTo("null");
        assertThat(root.get("stringTilde").asString()).isEqualTo("~");
        assertThat(root.get("port").asInt()).isEqualTo(8080);
        assertThat(root.get("quotedPort").asInt()).isEqualTo(8080);
        assertThat(root.get("literalAt").asString()).isEqualTo("@scope/name");
        assertThat(root.get("dotted.key").asString()).isEqualTo("a=b");
        assertThat(root.get("url").asString()).isEqualTo("https://example.org/a#fragment");
        assertThat(root.get("note").asString()).isEqualTo("prod # primary");
        assertThat(root.get("emptyMap").asObject().entries()).isEmpty();
        assertThat(root.get("emptyArray").asArray().values()).isEmpty();

        RiffleArray items = root.get("complex").asObject().get("items").asArray();
        assertThat(items.size()).isEqualTo(5);
        assertThat(items.get(1).asLong()).isEqualTo(9000000000L);
        assertThat(items.get(2).asDecimal()).isEqualByComparingTo("1.25");
        assertThat(items.get(3).asObject().get("enabled").asBoolean()).isFalse();
        assertThat(items.get(4).asArray().get(0).asString()).isEqualTo("nested");
        assertThat(root.get("query").asString()).isEqualTo("SELECT *\n  FROM users");
        assertThat(root.entries()).doesNotContainKey("disabled");
    }

    @Test
    void theValidExampleIsCanonicalApartFromComments() {
        String written = Riffle.write(Riffle.parse(VALID));
        assertThat(written).doesNotContain("blank string");
        assertThat(Riffle.parse(written)).isEqualTo(Riffle.parse(VALID));
        assertThat(Riffle.write(Riffle.parse(written))).isEqualTo(written);
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("x : @int 1", RiffleError.INVALID_VALUE),
                Arguments.of("x : @scope/name", RiffleError.INVALID_VALUE),
                Arguments.of("x : @", RiffleError.INVALID_VALUE),
                Arguments.of("x : { a : b }", RiffleError.UNSUPPORTED_INLINE_COLLECTION),
                Arguments.of("x : [ one ]", RiffleError.UNSUPPORTED_INLINE_COLLECTION),
                Arguments.of("x : [a-z]+", RiffleError.UNSUPPORTED_INLINE_COLLECTION),
                Arguments.of("x : { }", RiffleError.UNSUPPORTED_INLINE_COLLECTION),
                Arguments.of("x : [\n  1\n}", RiffleError.MISMATCHED_CLOSER),
                Arguments.of("x : a\nx : b", RiffleError.DUPLICATE_KEY),
                Arguments.of("@int 1 : a", RiffleError.INVALID_KEY),
                Arguments.of("\"display name\" : a", RiffleError.INVALID_KEY),
                Arguments.of("\"\" : a", RiffleError.INVALID_KEY),
                Arguments.of("\".hidden\" : a", RiffleError.INVALID_KEY),
                Arguments.of("a.b : x\n\"a.b\" : y", RiffleError.DUPLICATE_KEY),
                Arguments.of("x : \"abc", RiffleError.UNTERMINATED_STRING),
                Arguments.of("x : \"bad\\q\"", RiffleError.INVALID_ESCAPE),
                Arguments.of("x : \"\\u0000\"", RiffleError.INVALID_ESCAPE),
                Arguments.of("x : {\n y : z", RiffleError.UNCLOSED_COLLECTION),
                Arguments.of("/#\nx : y", RiffleError.UNCLOSED_COMMENT),
                Arguments.of("x : \"\"\"\n  text", RiffleError.UNTERMINATED_STRING),
                Arguments.of("x : \"\"\"\ntext\n  \"\"\"", RiffleError.INVALID_INDENTATION),
                Arguments.of("x : {} y : 2", RiffleError.TRAILING_TOKENS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void theSpecificationInvalidExamplesFail(String document, RiffleError category) {
        assertThatThrownBy(() -> Riffle.parse(document))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(category));
    }

    @Test
    void quotedValueExamplesFromSection5_2() {
        RiffleObject root = Riffle.parse("""
                literal : "He said "hello""
                escaped : "He said \\"hello\\""
                padded : "  padded  "
                quotes : ""quoted""
                hash : "a # b"
                slashes : "a // b"
                interiorQuote : "a\\" # text"
                bare : He said "hello" # not a comment
                backslash : "line \\\\n"
                newline : "line \\n"
                """);
        assertThat(root.get("literal").asString()).isEqualTo("He said \"hello\"");
        assertThat(root.get("escaped").asString()).isEqualTo(root.get("literal").asString());
        assertThat(root.get("padded").asString()).isEqualTo("  padded  ");
        assertThat(root.get("quotes").asString()).isEqualTo("\"quoted\"");
        assertThat(root.get("hash").asString()).isEqualTo("a # b");
        assertThat(root.get("slashes").asString()).isEqualTo("a // b");
        assertThat(root.get("interiorQuote").asString()).isEqualTo("a\" # text");
        assertThat(root.get("bare").asString()).isEqualTo("He said \"hello\" # not a comment");
        assertThat(root.get("backslash").asString()).isEqualTo("line \\n");
        assertThat(root.get("newline").asString()).isEqualTo("line \n");
        assertThat(Riffle.write(root)).isEqualTo(Riffle.write(Riffle.parse(Riffle.write(root))));
    }

    @Test
    void textAfterACompleteValueIsRejectedBecauseCommentsAreWholeLines() {
        assertThatThrownBy(() -> Riffle.parse("k : \"a\" # comment"))
                .isInstanceOfSatisfying(
                        RiffleParseException.class,
                        e -> assertThat(e.category()).isEqualTo(RiffleError.TRAILING_TOKENS));
    }

    @Test
    void twoEntriesOnOneLineAreOneStringValue() {
        assertThat(Riffle.parse("x : 1 y : 2").get("x").asString()).isEqualTo("1 y : 2");
    }
}
