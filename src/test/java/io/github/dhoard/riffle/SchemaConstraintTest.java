package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The scalar and collection constraints of SCHEMA_SPEC.md §4 and §12: what each constraint rejects,
 * and how it says so. Every case is a schema a host could write and a document a host could receive.
 */
class SchemaConstraintTest {

    private static List<RiffleProblem> problems(String schema, String document) {
        return RiffleSchema.parse(schema).validate(Riffle.parse(document));
    }

    /** A root map with one member declared by {@code member}. */
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

    private static String reasonOfSingleProblem(List<RiffleProblem> problems) {
        assertThat(problems).singleElement();
        return problems.get(0).reason();
    }

    @Test
    void aStringTooLongForItsNodeIsReportedOnceForItsLength() {
        List<RiffleProblem> problems = problems(memberSchema("type : string\nmaxLength : 3"), "value : abcde\n");

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.INVALID_LENGTH);
        assertThat(reasonOfSingleProblem(problems)).isEqualTo("expected at most 3 characters");
    }

    @Test
    void aStringCountsCharactersNotCodeUnits() {
        // U+1F600 is one character and two code units, so maxLength 1 accepts it.
        assertThat(problems(memberSchema("type : string\nmaxLength : 1"), "value : \"\uD83D\uDE00\"\n"))
                .isEmpty();
        assertThat(problems(memberSchema("type : string\nmaxLength : 1"), "value : \"\uD83D\uDE00\uD83D\uDE00\"\n"))
                .singleElement();
    }

    @Test
    void aDecimalBelowAnInclusiveLowerBoundIsRejected() {
        assertThat(reasonOfSingleProblem(problems(memberSchema("type : decimal\nmin : 1.5"), "value : 1.4\n")))
                .isEqualTo("expected at least 1.5");
    }

    @Test
    void aDecimalAtAnExclusiveLowerBoundIsRejected() {
        assertThat(reasonOfSingleProblem(
                        problems(memberSchema("type : decimal\nmin : 1.5\nminExclusive : true"), "value : 1.5\n")))
                .isEqualTo("expected more than 1.5");
    }

    @Test
    void aDecimalAboveAnInclusiveUpperBoundIsRejected() {
        assertThat(reasonOfSingleProblem(problems(memberSchema("type : decimal\nmax : 2.5"), "value : 2.6\n")))
                .isEqualTo("expected at most 2.5");
    }

    @Test
    void aDecimalAtAnExclusiveUpperBoundIsRejected() {
        assertThat(reasonOfSingleProblem(
                        problems(memberSchema("type : decimal\nmax : 2.5\nmaxExclusive : true"), "value : 2.5\n")))
                .isEqualTo("expected less than 2.5");
    }

    @Test
    void aDecimalTheHostCannotHoldIsReportedAsOutOfRange() {
        List<RiffleProblem> problems = problems(memberSchema("type : decimal"), "value : 1e99999999999\n");

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.OUT_OF_RANGE);
        assertThat(problems.get(0).reason()).isEqualTo("outside the range of a decimal this host can hold");
    }

    @Test
    void aDecimalOutsideItsListOfAllowedValuesIsRejected() {
        List<RiffleProblem> problems =
                problems(memberSchema("type : decimal\nvalues : [\n    1.5\n    2.50\n]"), "value : 3.5\n");

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.NOT_ALLOWED);
        assertThat(problems.get(0).reason()).isEqualTo("expected one of the values 1.5, 2.50");
    }

    @Test
    void allowedDecimalsAreEqualByValueNotBySpelling() {
        assertThat(problems(memberSchema("type : decimal\nvalues : [\n    1.50\n]"), "value : 1.5\n"))
                .isEmpty();
    }

    @Test
    void integersTheHostCannotHoldCompareAsText() {
        String schema = memberSchema("type : array\nunique : true\nitems : {\n    type : integer\n}");

        assertThat(problems(schema, """
                value : [
                    99999999999999999999
                    99999999999999999999
                ]
                """)).extracting(RiffleProblem::category).contains(RiffleValidationError.DUPLICATE);
        assertThat(problems(schema, """
                value : [
                    99999999999999999999
                    99999999999999999998
                ]
                """))
                .extracting(RiffleProblem::category)
                .doesNotContain(RiffleValidationError.DUPLICATE);
    }

    @Test
    void decimalsTheHostCannotHoldCompareAsText() {
        String schema = memberSchema("type : array\nunique : true\nitems : {\n    type : decimal\n}");

        assertThat(problems(schema, """
                value : [
                    1e99999999999
                    1e99999999999
                ]
                """)).extracting(RiffleProblem::category).contains(RiffleValidationError.DUPLICATE);
        assertThat(problems(schema, """
                value : [
                    1e99999999999
                    2e99999999999
                ]
                """))
                .extracting(RiffleProblem::category)
                .doesNotContain(RiffleValidationError.DUPLICATE);
    }

    @Test
    void anOutOfRangeIntegerIsReportedAgainstItsNode() {
        List<RiffleProblem> problems = problems(memberSchema("type : integer"), "value : 99999999999999999999\n");

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.OUT_OF_RANGE);
        assertThat(problems.get(0).reason()).isEqualTo("outside the range of a signed 64-bit integer");
    }

    @Test
    void anArrayShorterOrLongerThanItsNodeAllowsIsRejected() {
        String schema = """
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : array
                            minItems : 2
                            maxItems : 3
                            items : {
                                type : string
                            }
                        }
                    }
                }
                """;

        assertThat(reasonOfSingleProblem(problems(schema, "value : [\n    a\n]")))
                .isEqualTo("expected at least 2 elements");
        assertThat(reasonOfSingleProblem(problems(schema, "value : [\n    a\n    b\n    c\n    d\n]")))
                .isEqualTo("expected at most 3 elements");
    }

    @Test
    void arrayElementsAreDistinctByTheirValueWhenTheNodeAcceptsAnything() {
        String schema = memberSchema("type : array\nunique : true\nitems : {\n    type : any\n}");
        List<RiffleProblem> problems = problems(schema, """
                value : [
                    a
                    b
                    a
                ]
                """);

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.DUPLICATE);
        assertThat(problems.get(0).reason()).isEqualTo("duplicates an earlier element");
    }

    @Test
    void nestedArraysAreDistinctByTheirContents() {
        String schema = memberSchema(
                "type : array\nunique : true\nitems : {\n    type : array\n    items : {\n        type : string\n    }\n}");
        List<RiffleProblem> problems = problems(schema, """
                value : [
                    [
                        a
                    ]
                    [
                        a
                    ]
                ]
                """);

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.DUPLICATE);
    }

    @Test
    void arrayElementsAreDistinctUnderTheTypeTheirNodeDeclares() {
        List<RiffleProblem> problems =
                problems(memberSchema("type : array\nunique : true\nitems : {\n    type : decimal\n}"), """
                value : [
                    7
                    7.0
                ]
                """);

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.DUPLICATE);
    }

    @Test
    void arrayElementsHoldingTheSameDecimalValueAreDuplicates() {
        List<RiffleProblem> problems =
                problems(memberSchema("type : array\nunique : true\nitems : {\n    type : decimal\n}"), """
                value : [
                    1.5
                    1.50
                ]
                """);

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.DUPLICATE);
    }

    @Test
    void aNamedMemberOfArrayElementsDecidesDistinctness() {
        String schema = """
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : array
                            uniqueKey : id
                            items : {
                                type : map
                                keys : {
                                    id : {
                                        type : decimal
                                    }
                                    label : {
                                        type : string
                                        optional : true
                                    }
                                }
                            }
                        }
                    }
                }
                """;

        List<RiffleProblem> problems = problems(schema, """
                value : [
                    {
                        id : 1.5
                        label : first
                    }
                    {
                        id : 1.50
                        label : second
                    }
                ]
                """);

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.DUPLICATE);
        assertThat(problems.get(0).reason()).isEqualTo("duplicates an earlier element through `id`");
    }

    @Test
    void elementsWithoutTheNamedMemberTakeNoPartInDistinctness() {
        String schema = """
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : array
                            uniqueKey : id
                            items : {
                                type : map
                                keys : {
                                    id : {
                                        type : decimal
                                    }
                                    other : {
                                        type : string
                                        optional : true
                                    }
                                }
                            }
                        }
                    }
                }
                """;

        assertThat(problems(schema, """
                        value : [
                            {
                                other : one
                            }
                            notAMap
                            {
                                id : 1.5
                            }
                            {
                                id : 1.50
                            }
                        ]
                        """)).extracting(RiffleProblem::category).contains(RiffleValidationError.DUPLICATE);
    }

    @Test
    void aMapOrAnArraySaysWhatKindItWanted() {
        RiffleSchema array = RiffleSchema.parse(memberSchema("type : array\nitems : {\n    type : string\n}"));
        RiffleSchema union = RiffleSchema.parse(
                memberSchema(
                        "type : taggedUnion\ndiscriminator : kind\ncases : {\n    a : {\n        type : map\n        keys : {\n            kind : {\n                type : string\n                values : [\n                    a\n                ]\n            }\n        }\n    }\n}"));

        assertThat(array.validate(Riffle.parse("value : nope\n")))
                .singleElement()
                .extracting(RiffleProblem::reason)
                .isEqualTo("expected an array");
        assertThat(union.validate(Riffle.parse("value : nope\n")))
                .singleElement()
                .extracting(RiffleProblem::reason)
                .isEqualTo("expected a map");
    }

    @Test
    void aDiscriminatingMemberThatIsNotAStringIsReported() {
        RiffleSchema schema = RiffleSchema.parse("""
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
                                            type : string
                                            values : [
                                                a
                                            ]
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                """);

        List<RiffleProblem> problems = schema.validate(Riffle.parse("value : {\n    kind : [\n        a\n    ]\n}\n"));

        assertThat(problems).singleElement();
        assertThat(problems.get(0).category()).isEqualTo(RiffleValidationError.WRONG_KIND);
        assertThat(problems.get(0).reason()).isEqualTo("expected the discriminating member to be a string");
    }

    @Test
    void aSchemaDocumentWhoseVersionEntryIsAMapIsNotALoadableVersion() {
        RiffleObject document = Riffle.parse("""
                riffleSchema : {
                    x : 1
                }
                root : {
                    type : map
                }
                """);

        RiffleSchemaException failure =
                catchThrowableOfType(RiffleSchemaException.class, () -> SchemaLoader.load(document));

        assertThat(failure.problems())
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.UNSUPPORTED_VERSION);
    }

    @Test
    void anAllowedValueListQuotesWhatNeedsQuoting() {
        // `#tag` is written quoted in the schema because a bare `#` would open a comment.
        List<RiffleProblem> found =
                problems(memberSchema("type : string\nvalues : [\n    \"#tag\"\n    plain\n]"), "value : other\n");

        assertThat(found).singleElement();
        assertThat(found.get(0).reason()).isEqualTo("expected one of the values \"#tag\", plain");
    }

    @Test
    void aDecimalExactlyOnAnInclusiveBoundIsAccepted() {
        assertThat(problems(memberSchema("type : decimal\nmin : 1.5\nmax : 2.5"), "value : 1.5\n"))
                .isEmpty();
        assertThat(problems(memberSchema("type : decimal\nmin : 1.5\nmax : 2.5"), "value : 2.5\n"))
                .isEmpty();
    }

    @Test
    void blankMeansOnlyTheFourBlankCharacters() {
        assertThat(problems(memberSchema("type : string\nnotBlank : true"), "value : \" \\t\\n\\r \"\n"))
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.BLANK);
        assertThat(problems(memberSchema("type : string\nnotBlank : true"), "value : \" x\"\n"))
                .isEmpty();
    }

    @Test
    void anIntegerExactlyOnItsBoundIsAccepted() {
        assertThat(problems(memberSchema("type : integer\nmin : 5\nmax : 5"), "value : 5\n"))
                .isEmpty();
    }

    @Test
    void valuesAndAnUpperBoundOfTheSameKindAreOneProblemNotTwo() {
        RiffleSchemaException failure = catchThrowableOfType(
                RiffleSchemaException.class,
                () -> RiffleSchema.parse(memberSchema("type : integer\nvalues : [\n    1\n]\nmax : 9")));

        assertThat(failure.problems())
                .singleElement()
                .extracting(RiffleProblem::reason)
                .isEqualTo("`values` cannot be combined with another constraint of the same node");
    }

    @Test
    void anElementTheItemNodeTypeCannotInterpretIsComparedAsText() {
        assertThat(problems(memberSchema("type : array\nunique : true\nitems : {\n    type : integer\n}"), """
                        value : [
                            7
                            abc
                        ]
                        """))
                .extracting(RiffleProblem::category)
                .doesNotContain(RiffleValidationError.DUPLICATE);
        assertThat(problems(memberSchema("type : array\nunique : true\nitems : {\n    type : decimal\n}"), """
                        value : [
                            1.5
                            abc
                        ]
                        """))
                .extracting(RiffleProblem::category)
                .doesNotContain(RiffleValidationError.DUPLICATE);
        assertThat(problems(memberSchema("type : array\nunique : true\nitems : {\n    type : decimal\n}"), """
                        value : [
                            abc
                            abc
                        ]
                        """))
                .extracting(RiffleProblem::category)
                .contains(RiffleValidationError.DUPLICATE);
    }
}
