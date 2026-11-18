package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The examples of `LANGUAGE_SPEC.md` §13, used directly as conformance tests. */
class SpecConformanceTest {

    private static final String VALID = """
            # blank string, literal strings, explicit null
            empty :
            stringTrue : true
            stringNull : null
            stringTilde : ~
            booleanTrue : @boolean true
            nullValue : @null
            literalAt : "@null"

            # quoted key and punctuation
            "key with spaces" : a=b
            url : https://example.org/a#fragment

            # empty collections
            emptyMap : {}
            emptyArray : []
            emptyInts : @int []

            # nested values and mixed array
            complex : {
                items : [
                    text
                    @long 9000000000
                    @decimal 1.25
                    @null
                    {
                        enabled : @boolean false
                    }
                    [
                        nested
                    ]
                ]
            }

            # homogeneous array
            ports : @int [
                80
                443
            ]

            # block comment region: whole lines are ignored until the matching `#/`
            /#
            disabled : @int 1
            #/
            """;

    @Test
    void theSpecificationValidExampleParsesToTheDocumentedMeaning() {
        RiffleObject root = Riffle.parse(VALID);
        assertThat(root.get("empty").asString()).isEmpty();
        assertThat(root.get("stringTrue").asString()).isEqualTo("true");
        assertThat(root.get("stringNull").asString()).isEqualTo("null");
        assertThat(root.get("stringTilde").asString()).isEqualTo("~");
        assertThat(root.get("booleanTrue").asBoolean()).isTrue();
        assertThat(root.get("nullValue").isNull()).isTrue();
        assertThat(root.get("literalAt").asString()).isEqualTo("@null");
        assertThat(root.get("key with spaces").asString()).isEqualTo("a=b");
        assertThat(root.get("url").asString()).isEqualTo("https://example.org/a#fragment");
        assertThat(root.get("emptyMap").asObject().entries()).isEmpty();
        assertThat(root.get("emptyArray").asArray().values()).isEmpty();
        assertThat(root.get("emptyInts").asArray().values()).isEmpty();

        RiffleArray items = root.get("complex").asObject().get("items").asArray();
        assertThat(items.size()).isEqualTo(6);
        assertThat(items.get(1).asLong()).isEqualTo(9000000000L);
        assertThat(items.get(2).asDecimal()).isEqualByComparingTo("1.25");
        assertThat(items.get(3).isNull()).isTrue();
        assertThat(items.get(4).asObject().get("enabled").asBoolean()).isFalse();
        assertThat(items.get(5).asArray().get(0).asString()).isEqualTo("nested");
        assertThat(root.get("ports").asArray().values())
                .extracting(RiffleValue::asInt)
                .containsExactly(80, 443);
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
                Arguments.of("x : @int", RiffleError.MISSING_VALUE),
                Arguments.of("x : @int 01", RiffleError.INVALID_TYPED_VALUE),
                Arguments.of("x : @int 2147483648", RiffleError.NUMBER_OUT_OF_RANGE),
                Arguments.of("x : @boolean yes", RiffleError.INVALID_TYPED_VALUE),
                Arguments.of("x : @null null", RiffleError.INVALID_TYPED_VALUE),
                Arguments.of("x : @float 1.0", RiffleError.UNKNOWN_TYPE),
                Arguments.of("x : @int \"1\"", RiffleError.INVALID_TYPED_VALUE),
                Arguments.of("x : { a : b }", RiffleError.UNSUPPORTED_INLINE_COLLECTION),
                Arguments.of("x : [ one ]", RiffleError.UNSUPPORTED_INLINE_COLLECTION),
                Arguments.of("x : @int [ 1 ]", RiffleError.UNSUPPORTED_INLINE_COLLECTION),
                Arguments.of("x : { }", RiffleError.UNSUPPORTED_INLINE_COLLECTION),
                Arguments.of("x : [\n  @int 1\n}", RiffleError.MISMATCHED_CLOSER),
                Arguments.of("x : @int [\n  @int 1\n]", RiffleError.INVALID_ARRAY_ELEMENT_TYPE),
                Arguments.of("x : @int [\n  text\n]", RiffleError.INVALID_ARRAY_ELEMENT_TYPE),
                Arguments.of("x : @map [\n  hello\n]", RiffleError.INVALID_ARRAY_ELEMENT_TYPE),
                Arguments.of("x : @null [\n  hello\n]", RiffleError.INVALID_ARRAY_ELEMENT_TYPE),
                Arguments.of("x : a\nx : b", RiffleError.DUPLICATE_KEY),
                Arguments.of("@int 1 : a", RiffleError.INVALID_KEY),
                Arguments.of("x : \"abc", RiffleError.UNTERMINATED_STRING),
                Arguments.of("x : \"bad\\q\"", RiffleError.INVALID_ESCAPE),
                Arguments.of("x : {\n y : z", RiffleError.UNCLOSED_COLLECTION),
                Arguments.of("/#\nx : y", RiffleError.UNCLOSED_COMMENT),
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
