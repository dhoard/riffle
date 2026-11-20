package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Load phase three of SCHEMA_SPEC.md §11: what a structurally sound schema document can still get
 * wrong, and the problem each mistake reports. Every schema here passes the meta-schema and fails
 * the semantic walk, so the reported problem is the semantic one rather than a shape error.
 */
class SchemaSemanticTest {

    private static List<RiffleProblem> problems(String schema) {
        RiffleSchemaException failure =
                catchThrowableOfType(RiffleSchemaException.class, () -> RiffleSchema.parse(schema));
        return failure.problems();
    }

    private static String reasonOfSingleProblem(String schema) {
        assertThat(problems(schema)).singleElement();
        return problems(schema).get(0).reason();
    }

    /** A root map whose only declared member is described by {@code member}. */
    private static String memberSchema(String member) {
        return """
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                """ + member.indent(12) + """
                        }
                    }
                }
                """;
    }

    /** A root map declaring {@code keys} and, when not blank, {@code extra} node keywords. */
    private static String mapSchema(String keys, String extra) {
        String tail = extra.isBlank() ? "" : extra.indent(4);
        return """
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                """ + keys.indent(8) + """
                    }
                """ + tail + """
                }
                """;
    }

    @Test
    void anIntegerRangeThatHoldsNothingIsRejected() {
        assertThat(reasonOfSingleProblem(memberSchema("type : integer\nmin : 5\nmax : 3")))
                .isEqualTo("the declared range is empty");
    }

    @Test
    void aRangeClosedByAnExclusiveBoundHoldsNothing() {
        assertThat(reasonOfSingleProblem(memberSchema("type : decimal\nmin : 5\nmax : 5\nminExclusive : true")))
                .isEqualTo("the declared range is empty");
    }

    @Test
    void aDecimalRangeThatHoldsNothingIsRejected() {
        assertThat(reasonOfSingleProblem(memberSchema("type : decimal\nmin : 1.5\nmax : 1.4")))
                .isEqualTo("the declared range is empty");
        assertThat(reasonOfSingleProblem(memberSchema("type : decimal\nmin : 1.5\nmax : 1.5\nmaxExclusive : true")))
                .isEqualTo("the declared range is empty");
    }

    @Test
    void anExclusiveFlagNeedsTheBoundItMakesExclusive() {
        assertThat(reasonOfSingleProblem(memberSchema("type : decimal\nminExclusive : true")))
                .isEqualTo("`minExclusive` needs the bound it makes exclusive");
        assertThat(reasonOfSingleProblem(memberSchema("type : decimal\nmaxExclusive : true")))
                .isEqualTo("`maxExclusive` needs the bound it makes exclusive");
    }

    @Test
    void aKeyPatternNeedsOtherKeys() {
        assertThat(reasonOfSingleProblem("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        a : {
                            type : string
                        }
                    }
                    keyPattern : a
                }
                """))
                .isEqualTo("`keyPattern` needs `otherKeys`, because it constrains only keys `otherKeys` describes");
    }

    @Test
    void oneArrayNodeCannotDeclareBothUniqueAndUniqueKey() {
        assertThat(reasonOfSingleProblem(
                        memberSchema("type : array\nunique : true\nuniqueKey : id\nitems : {\n    type : string\n}")))
                .isEqualTo("`unique` and `uniqueKey` cannot both be declared by one array node");
    }

    @Test
    void valuesCannotShareANodeWithAnotherConstraint() {
        assertThat(reasonOfSingleProblem(memberSchema("type : integer\nvalues : [\n    1\n]\nmin : 0")))
                .isEqualTo("`values` cannot be combined with another constraint of the same node");
        assertThat(reasonOfSingleProblem(memberSchema("type : string\nvalues : [\n    a\n]\nnotBlank : true")))
                .isEqualTo("`values` cannot be combined with another constraint of the same node");
        assertThat(reasonOfSingleProblem(
                        memberSchema("type : decimal\nvalues : [\n    1.5\n]\nminExclusive : true\nmin : 1")))
                .isEqualTo("`values` cannot be combined with another constraint of the same node");
    }

    @Test
    void optionalAndDefaultBelongToAMemberNode() {
        assertThat(reasonOfSingleProblem("""
                riffleSchema : 1
                root : {
                    type : map
                    optional : true
                    keys : {
                        a : {
                            type : string
                        }
                    }
                }
                """))
                .isEqualTo("`optional` and `default` belong to a member node, not to a node of this kind");
    }

    @Test
    void aDefaultNeedsOptionalOnTheSameNode() {
        assertThat(reasonOfSingleProblem(memberSchema("type : string\ndefault : x")))
                .isEqualTo("`default` needs `optional : true` on the same node");
    }

    @Test
    void aCaseOfATaggedUnionMustDescribeAMap() {
        List<RiffleProblem> found = problems("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : taggedUnion
                            discriminator : kind
                            cases : {
                                a : {
                                    type : string
                                }
                            }
                        }
                    }
                }
                """);

        assertThat(found)
                .singleElement()
                .extracting(RiffleProblem::reason)
                .isEqualTo("the case `a` must describe a map that declares the discriminator `kind`");
    }

    @Test
    void aCaseWhoseDiscriminatorIsNotAStringIsRejected() {
        assertThat(reasonOfSingleProblem("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : taggedUnion
                            discriminator : kind
                            cases : {
                                a : {
                                    type : map
                                    keys : {
                                        kind : {
                                            type : integer
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                """))
                .isEqualTo("the discriminator `kind` of the case `a` must be described by a `string` node");
    }

    @Test
    void aCaseOrAlternativeThatRefersToAMissingDefinitionIsLeftAlone() {
        // The missing name is reported once; the walk does not guess what it would have constrained.
        assertThat(problems("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : taggedUnion
                            discriminator : kind
                            cases : {
                                a : {
                                    type : ref
                                    definition : missing
                                }
                            }
                        }
                    }
                }
                """))
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.UNKNOWN_DEFINITION);
        assertThat(problems("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : kindUnion
                            string : {
                                type : ref
                                definition : missing
                            }
                            map : {
                                type : map
                            }
                        }
                    }
                }
                """))
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.UNKNOWN_DEFINITION);
    }

    @Test
    void aReferenceChainThatNeverEndsIsLeftAloneByTheCaseCheck() {
        assertThat(problems("""
                riffleSchema : 1
                definitions : {
                    middle : {
                        type : ref
                        definition : missing
                    }
                }
                root : {
                    type : map
                    keys : {
                        value : {
                            type : taggedUnion
                            discriminator : kind
                            cases : {
                                a : {
                                    type : ref
                                    definition : middle
                                }
                            }
                        }
                    }
                }
                """))
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.UNKNOWN_DEFINITION);
    }

    @Test
    void aRootThatRefersToAMissingDefinitionIsNotJudgedForItsKind() {
        assertThat(problems("""
                riffleSchema : 1
                root : {
                    type : ref
                    definition : missing
                }
                """))
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.UNKNOWN_DEFINITION);
    }

    @Test
    void aDefaultOnAnUnresolvedMemberIsNotJudgedAgainstItsNode() {
        assertThat(problems("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : ref
                            definition : missing
                            optional : true
                            default : x
                        }
                    }
                }
                """))
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.UNKNOWN_DEFINITION);
        assertThat(problems("""
                riffleSchema : 1
                definitions : {
                    middle : {
                        type : ref
                        definition : missing
                    }
                }
                root : {
                    type : map
                    keys : {
                        value : {
                            type : ref
                            definition : middle
                            optional : true
                            default : x
                        }
                    }
                }
                """))
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.UNKNOWN_DEFINITION);
    }

    @Test
    void aRuleCannotNameTheSameKeyTwice() {
        assertThat(reasonOfSingleProblem(mapSchema("""
                        a : {
                            type : string
                            optional : true
                        }
                        b : {
                            type : string
                            optional : true
                        }""", """
                        rules : [
                            {
                                rule : exactlyOne
                                keys : [
                                    a
                                    a
                                ]
                            }
                        ]"""))).isEqualTo("`exactlyOne` names `a` more than once");
    }

    @Test
    void aRuleCannotNameAKeyTheMapDoesNotDeclare() {
        assertThat(reasonOfSingleProblem(mapSchema("""
                        a : {
                            type : string
                            optional : true
                        }""", """
                        rules : [
                            {
                                rule : exactlyOne
                                keys : [
                                    a
                                    missing
                                ]
                            }
                        ]"""))).contains("`missing`, which is not a declared key");
    }

    @Test
    void memberOfCannotNameAKeyTheMapDoesNotDeclare() {
        List<RiffleProblem> found = problems(mapSchema("""
                arr : {
                    type : array
                    items : {
                        type : string
                    }
                }""", """
                rules : [
                    {
                        rule : memberOf
                        key : missing
                        array : arr
                    }
                ]"""));

        assertThat(found)
                .anySatisfy(problem -> assertThat(problem.reason())
                        .isEqualTo("`memberOf` names `missing`, which is not a declared key"));
    }

    @Test
    void memberOfNeedsAScalarKeyAndAnArrayOfThatSameType() {
        assertThat(reasonOfSingleProblem(mapSchema("""
                        key : {
                            type : map
                        }
                        arr : {
                            type : array
                            items : {
                                type : string
                            }
                        }""", """
                        rules : [
                            {
                                rule : memberOf
                                key : key
                                array : arr
                            }
                        ]""")))
                .isEqualTo("`memberOf` needs a scalar `key` and an `array` whose elements have that same type");
        assertThat(reasonOfSingleProblem(mapSchema("""
                        key : {
                            type : string
                        }
                        arr : {
                            type : array
                            items : {
                                type : integer
                            }
                        }""", """
                        rules : [
                            {
                                rule : memberOf
                                key : key
                                array : arr
                            }
                        ]""")))
                .isEqualTo("`memberOf` needs a scalar `key` and an `array` whose elements have that same type");
    }

    @Test
    void memberOfAgainstAnUnresolvedKeyStillSaysWhatItNeeds() {
        List<RiffleProblem> found = problems(mapSchema("""
                key : {
                    type : ref
                    definition : missing
                }
                arr : {
                    type : array
                    items : {
                        type : string
                    }
                }""", """
                rules : [
                    {
                        rule : memberOf
                        key : key
                        array : arr
                    }
                ]"""));

        assertThat(found).extracting(RiffleProblem::category).contains(RiffleValidationError.UNKNOWN_DEFINITION);
        assertThat(found)
                .anySatisfy(problem -> assertThat(problem.reason())
                        .isEqualTo(
                                "`memberOf` needs a scalar `key` and an `array` whose elements have that same type"));
    }

    @Test
    void aCycleOfDefinitionsThatConsumesNoDataIsRejected() {
        assertThat(problems("""
                riffleSchema : 1
                definitions : {
                    a : {
                        type : ref
                        definition : b
                    }
                    b : {
                        type : ref
                        definition : a
                    }
                }
                root : {
                    type : map
                    keys : {
                        value : {
                            type : ref
                            definition : a
                        }
                    }
                }
                """))
                .extracting(RiffleProblem::category)
                .contains(RiffleValidationError.CIRCULAR_DEFINITION);
    }

    @Test
    void everyInterpretationIsALegalStringAlternativeOfAKindUnion() {
        for (String type : new String[] {"string", "integer", "decimal", "boolean"}) {
            String schema = """
                    riffleSchema : 1
                    root : {
                        type : map
                        keys : {
                            value : {
                                type : kindUnion
                                string : {
                                    type : @TYPE@
                                }
                                map : {
                                    type : map
                                }
                            }
                        }
                    }
                    """.replace("@TYPE@", type);

            assertThat(RiffleSchema.parse(schema)).isNotNull();
        }
    }

    @Test
    void anArrayAlternativeOfAKindUnionMustDescribeAnArray() {
        assertThat(reasonOfSingleProblem("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : kindUnion
                            array : {
                                type : string
                            }
                            map : {
                                type : map
                            }
                        }
                    }
                }
                """))
                .isEqualTo("the `array` alternative of `kindUnion` must describe an array");
    }
}
