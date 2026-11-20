package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Configuration can hold secrets, so no diagnostic may repeat a scalar value. */
class DiagnosticsSecrecyTest {
    private static final String SECRET = "SECRET-7f3a";

    static Stream<String> invalidDocuments() {
        return Stream.of(
                "k : @" + SECRET + "\n",
                "k : @int " + SECRET + "\n",
                "k : [" + SECRET + "\n",
                "k : {" + SECRET + "}\n",
                "k : []" + SECRET + "\n",
                "k : {}" + SECRET + "\n",
                "k : }" + SECRET + "\n",
                "k : ]" + SECRET + "\n",
                "k : \"" + SECRET + "\n",
                "k : \"" + SECRET + "\" " + SECRET + "\n",
                "k : \"\\" + SECRET + "\"\n",
                "k : \"\\q" + SECRET + "\"\n",
                "k : \"\\u00" + SECRET + "\"\n",
                "k : \"\\u0000" + SECRET + "\"\n",
                "k : \"\\uD83D" + SECRET + "\"\n",
                "k : " + SECRET + "\u0001\n",
                "k : " + SECRET + "\uFEFF\n",
                "k : " + SECRET + "\rnext : 1\n",
                "k : [\n    @" + SECRET + "\n]\n",
                "k : [\n    " + SECRET + "\n}\n",
                "k : {\n    " + SECRET + "\n}\n",
                SECRET + "\n",
                "/#\n" + SECRET + "\n",
                "k : \"\"\"\n    " + SECRET + "\n",
                "k : \"\"\"\n" + SECRET + "\n    \"\"\"\n",
                "k : \"\"\"\n    " + SECRET + "\u0001\n    \"\"\"\n");
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void parseErrorsNeverContainTheValue(String document) {
        assertThatThrownBy(() -> Riffle.parse(document)).isInstanceOfSatisfying(RiffleParseException.class, e -> {
            assertThat(e.getMessage()).doesNotContain(SECRET);
            assertThat(e.reason()).doesNotContain(SECRET);
        });
    }

    /**
     * A schema that exercises the categories of SCHEMA_SPEC.md §10 which have to talk about a value:
     * each holds the value it rejects, so none may print it.
     */
    private static final String CONSTRAINTD_SCHEMA = """
            riffleSchema : 1
            root : {
                type : map
                keys : {
                    text : {
                        type : string
                        pattern : "[a-z]+"
                        notBlank : true
                        optional : true
                    }
                    number : {
                        type : integer
                        min : 1024
                        optional : true
                    }
                    amount : {
                        type : decimal
                        optional : true
                    }
                    flag : {
                        type : boolean
                        optional : true
                    }
                    choice : {
                        type : string
                        values : [
                            alpha
                            beta
                        ]
                        optional : true
                    }
                    list : {
                        type : array
                        unique : true
                        items : {
                            type : string
                        }
                        optional : true
                    }
                    nested : {
                        type : map
                        keys : {
                            inner : {
                                type : string
                                notBlank : true
                            }
                        }
                        optional : true
                    }
                }
                minKeys : 2
                rules : [
                    {
                        rule : requires
                        key : text
                        keys : [
                            choice
                        ]
                    }
                ]
            }
            """;

    static List<String> documentsHoldingARejectedValue() {
        return List.of(
                "text : " + SECRET + "\nflag : true\n",
                "number : " + SECRET + "\nflag : true\n",
                "number : 99999999999999999999999\nflag : true\n",
                "amount : " + SECRET + "\nflag : true\n",
                "flag : " + SECRET + "\ntext : lower\n",
                "choice : " + SECRET + "\nflag : true\n",
                "list : [\n    " + SECRET + "\n    " + SECRET + "\n]\nflag : true\n",
                "nested : {}\nflag : true\n",
                "unknown : " + SECRET + "\nflag : true\n",
                "text : " + SECRET + "\n",
                "flag : not-a-boolean\n");
    }

    @ParameterizedTest
    @MethodSource("documentsHoldingARejectedValue")
    void validationProblemsNeverRepeatTheValue(String document) {
        RiffleSchema schema = RiffleSchema.parse(CONSTRAINTD_SCHEMA);
        RiffleObject parsed = Riffle.parse(document);

        List<RiffleProblem> problems = schema.validate(parsed);

        assertThat(problems).as("problems with %s", document).isNotEmpty();
        for (RiffleProblem problem : problems) {
            assertThat(problem.reason()).doesNotContain(SECRET);
            assertThat(problem.path().toString()).doesNotContain(SECRET);
            assertThat(problem.toString()).doesNotContain(SECRET);
        }
        assertThatThrownBy(() -> Riffle.parse(document, schema))
                .isInstanceOf(RiffleValidationException.class)
                .hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> schema.applyDefaults(parsed))
                .isInstanceOf(RiffleValidationException.class)
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void accessErrorsNeverContainTheValue() {
        String tooLarge = "99999999999999999999";
        RiffleObject root = Riffle.parse("k : " + SECRET + "\nbig : " + tooLarge + "\n");
        List<Function<RiffleValue, Object>> accessors = List.of(
                RiffleValue::asInt,
                RiffleValue::asLong,
                RiffleValue::asDecimal,
                RiffleValue::asBoolean,
                RiffleValue::asObject,
                RiffleValue::asArray);
        for (Function<RiffleValue, Object> accessor : accessors) {
            assertThatThrownBy(() -> accessor.apply(root.get("k")))
                    .isInstanceOf(RiffleAccessException.class)
                    .hasMessageNotContaining(SECRET);
        }
        assertThatThrownBy(() -> root.get("big").asLong())
                .isInstanceOf(RiffleAccessException.class)
                .hasMessageNotContaining(tooLarge);
        assertThatThrownBy(() -> root.get("big").asInt())
                .isInstanceOf(RiffleAccessException.class)
                .hasMessageNotContaining(tooLarge);
    }
}
