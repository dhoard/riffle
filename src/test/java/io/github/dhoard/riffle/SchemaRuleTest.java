package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of SCHEMA_SPEC.md §8 and the unions of §9 as a host uses them: which documents satisfy
 * a rule, which documents a rule skips, and what a union says when a value is of the wrong kind.
 */
class SchemaRuleTest {

    private static List<RiffleProblem> problems(String schema, String document) {
        return RiffleSchema.parse(schema).validate(Riffle.parse(document));
    }

    /** A root map declaring {@code keys} and the {@code rules} list {@code rules}. */
    private static String rulesSchema(String keys, String rules) {
        return """
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                """ + keys.indent(8) + """
                    }
                """ + rules.indent(4) + """
                }
                """;
    }

    private static final String TWO_OPTIONALS = """
            a : {
                type : string
                optional : true
            }
            b : {
                type : string
                optional : true
            }""";

    @Test
    void allOrNoneAcceptsBothKeysOrNeither() {
        String schema = rulesSchema(TWO_OPTIONALS, """
                rules : [
                    {
                        rule : allOrNone
                        keys : [
                            a
                            b
                        ]
                    }
                ]""");

        assertThat(problems(schema, "\n")).isEmpty();
        assertThat(problems(schema, "a : x\nb : y\n")).isEmpty();
    }

    @Test
    void allOrNoneRejectsHalfOfThePair() {
        String schema = rulesSchema(TWO_OPTIONALS, """
                rules : [
                    {
                        rule : allOrNone
                        keys : [
                            a
                            b
                        ]
                    }
                ]""");

        assertThat(problems(schema, "a : x\n")).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.RULE_VIOLATION);
            assertThat(problem.path()).isEqualTo(RifflePath.root());
        });
    }

    @Test
    void memberOfAcceptsAKeyDrawnFromTheArray() {
        String schema = rulesSchema("""
                colour : {
                    type : string
                }
                allowed : {
                    type : array
                    items : {
                        type : string
                    }
                }""", """
                rules : [
                    {
                        rule : memberOf
                        key : colour
                        array : allowed
                    }
                ]""");

        assertThat(problems(schema, "colour : red\nallowed : [\n    red\n    blue\n]\n"))
                .isEmpty();
        assertThat(problems(schema, "colour : green\nallowed : [\n    red\n    blue\n]\n"))
                .singleElement()
                .satisfies(problem -> {
                    assertThat(problem.category()).isEqualTo(RiffleValidationError.RULE_VIOLATION);
                    assertThat(problem.reason()).isEqualTo("is not one of the values of `allowed`");
                });
    }

    @Test
    void memberOfComparesUnderTheTypeTheKeyDeclares() {
        String schema = rulesSchema("""
                amount : {
                    type : decimal
                }
                allowed : {
                    type : array
                    items : {
                        type : decimal
                    }
                }""", """
                rules : [
                    {
                        rule : memberOf
                        key : amount
                        array : allowed
                    }
                ]""");

        assertThat(problems(schema, "amount : 1.5\nallowed : [\n    1.50\n]\n")).isEmpty();
    }

    @Test
    void memberOfTakesNoPartWhenEitherSideIsAbsentOrWronglyShaped() {
        String schema = rulesSchema("""
                colour : {
                    type : string
                    optional : true
                }
                allowed : {
                    type : array
                    items : {
                        type : string
                    }
                    optional : true
                }""", """
                rules : [
                    {
                        rule : memberOf
                        key : colour
                        array : allowed
                    }
                ]""");

        // The key is absent, and the array side is a bare value rather than an array; neither is a
        // rule violation, whatever else the document gets wrong.
        assertThat(problems(schema, "allowed : red\n"))
                .extracting(RiffleProblem::category)
                .doesNotContain(RiffleValidationError.RULE_VIOLATION);
        assertThat(problems(schema, "colour : red\nallowed : red\n"))
                .extracting(RiffleProblem::category)
                .doesNotContain(RiffleValidationError.RULE_VIOLATION);
    }

    @Test
    void requiresAddsTheKeysItsKeyBringsWithIt() {
        String schema = rulesSchema("""
                host : {
                    type : string
                    optional : true
                }
                port : {
                    type : string
                    optional : true
                }""", """
                rules : [
                    {
                        rule : requires
                        key : host
                        keys : [
                            port
                        ]
                    }
                ]""");

        assertThat(problems(schema, "\n")).isEmpty();
        assertThat(problems(schema, "host : example\nport : 80\n")).isEmpty();
        assertThat(problems(schema, "host : example\n"))
                .singleElement()
                .extracting(RiffleProblem::reason)
                .isEqualTo("`host` is present, so `port` must be present");
    }

    @Test
    void aUnionOfKindsNamesTheAlternativesItDeclares() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : kindUnion
                            map : {
                                type : map
                            }
                            array : {
                                type : array
                                items : {
                                    type : string
                                }
                            }
                        }
                    }
                }
                """);

        assertThat(schema.validate(Riffle.parse("value : nope\n")))
                .singleElement()
                .extracting(RiffleProblem::reason)
                .isEqualTo("expected a map, an array");
    }

    @Test
    void aUnionAcceptsTheInterpretationsItsAlternativeDescribes() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : kindUnion
                            string : {
                                type : integer
                                min : 1
                            }
                            map : {
                                type : taggedUnion
                                discriminator : kind
                                cases : {
                                    one : {
                                        type : map
                                        keys : {
                                            kind : {
                                                type : string
                                                values : [
                                                    one
                                                ]
                                            }
                                        }
                                    }
                                }
                            }
                            array : {
                                type : array
                                items : {
                                    type : string
                                }
                            }
                        }
                    }
                }
                """);

        assertThat(schema.validate(Riffle.parse("value : 7\n"))).isEmpty();
        assertThat(schema.validate(Riffle.parse("value : {\n    kind : one\n}\n")))
                .isEmpty();
        assertThat(schema.validate(Riffle.parse("value : [\n    a\n]\n"))).isEmpty();
    }

    @Test
    void aTaggedUnionMayBeTheRootOfASchema() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : taggedUnion
                    discriminator : kind
                    cases : {
                        one : {
                            type : map
                            keys : {
                                kind : {
                                    type : string
                                    values : [
                                        one
                                    ]
                                }
                            }
                        }
                    }
                }
                """);

        assertThat(schema).hasToString("RiffleSchema[root=taggedUnion]");
        assertThat(schema.validate(Riffle.parse("kind : one\n"))).isEmpty();
    }

    @Test
    void everyKindIsNamedAsTheSpecificationWritesIt() {
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.STRING)).isEqualTo("string");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.INTEGER)).isEqualTo("integer");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.DECIMAL)).isEqualTo("decimal");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.BOOLEAN)).isEqualTo("boolean");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.MAP)).isEqualTo("map");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.ARRAY)).isEqualTo("array");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.ANY)).isEqualTo("any");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.REF)).isEqualTo("ref");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.TAGGED_UNION)).isEqualTo("taggedUnion");
        assertThat(SchemaLoader.typeName(SchemaNode.Kind.KIND_UNION)).isEqualTo("kindUnion");
    }
}
