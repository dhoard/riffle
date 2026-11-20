package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation reports every problem in the order SCHEMA_SPEC.md §12 fixes. The conformance suite holds
 * the per-category matrix; these cases pin the ordering and the API surface around it.
 */
class SchemaValidationTest {

    private static final String MAP_SCHEMA = """
            riffleSchema : 1
            root : {
                type : map
                keys : {
                    port : {
                        type : integer
                        min : 1024
                        max : 65535
                    }
                    name : {
                        type : string
                        notBlank : true
                        maxLength : 32
                    }
                    mode : {
                        type : string
                        values : [
                            dev
                            prod
                        ]
                        optional : true
                        default : prod
                    }
                    a : {
                        type : string
                        optional : true
                    }
                    b : {
                        type : string
                        optional : true
                    }
                }
                otherKeys : {
                    type : any
                }
                rules : [
                    {
                        rule : atMostOne
                        keys : [
                            a
                            b
                        ]
                    }
                ]
            }
            """;

    @Test
    void reportsTheEntryProblemsThenTheAbsentMembersThenTheRules() {
        RiffleSchema schema = RiffleSchema.parse(MAP_SCHEMA);
        String document = """
                port : 80
                extra : 1
                a : x
                b : y
                """;

        RiffleValidationException failure =
                catchThrowableOfType(RiffleValidationException.class, () -> Riffle.parse(document, schema));

        assertThat(failure.problems())
                .extracting(RiffleProblem::category, SchemaValidationTest::pathText)
                .containsExactly(
                        tuple(RiffleValidationError.OUT_OF_RANGE, "port"),
                        tuple(RiffleValidationError.MISSING, "name"),
                        tuple(RiffleValidationError.RULE_VIOLATION, "(root)"));
    }

    @Test
    void checksTheEntriesItHoldsBeforeTheKeysThatAreAbsent() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        late : {\n            type : integer\n        }\n        early : {\n            type"
                + " : integer\n        }\n    }\n}\n");
        String document = "early : not-a-number\n";

        RiffleValidationException failure =
                catchThrowableOfType(RiffleValidationException.class, () -> Riffle.parse(document, schema));

        assertThat(failure.problems())
                .extracting(RiffleProblem::category, SchemaValidationTest::pathText)
                .containsExactly(
                        tuple(RiffleValidationError.INVALID_FORMAT, "early"),
                        tuple(RiffleValidationError.MISSING, "late"));
    }

    @Test
    void saysNothingAboutAnAbsentMemberThatIsOptional() {
        RiffleSchema schema = RiffleSchema.parse(MAP_SCHEMA);

        assertThatCode(() -> Riffle.parse("port : 8080\nname : gateway\n", schema))
                .doesNotThrowAnyException();
    }

    @Test
    void keepsTheDocumentItValidated() {
        RiffleSchema schema = RiffleSchema.parse(MAP_SCHEMA);
        String text = "port : 8080\nname : gateway\n";

        RiffleObject validated = Riffle.parse(text, schema);

        assertThat(validated).isEqualTo(Riffle.parse(text));
        assertThat(validated.entries().keySet()).containsExactly("port", "name");
    }

    @Test
    void reportsTheLaterValueOfADuplicate() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        codes : {\n            type : array\n            unique : true\n            items : {\n"
                + "                type : string\n            }\n        }\n    }\n}\n");

        RiffleValidationException failure = catchThrowableOfType(
                RiffleValidationException.class, () -> Riffle.parse("codes : [\n    a\n    b\n    a\n]\n", schema));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.DUPLICATE);
            assertThat(problem.path().toString()).isEqualTo("codes[2]");
            assertThat(problem.location()).map(RiffleLocation::line).contains(4);
        });
    }

    private static final String UNDECLARED_KEY_SCHEMA = """
            riffleSchema : 1
            root : {
                type : map
                keys : {
                    values : {
                        type : array
                        uniqueKey : id
                        items : {
                            type : map
                            otherKeys : {
                                type : any
                            }
                        }
                    }
                }
            }
            """;

    @Test
    void comparesUndeclaredUniqueKeyValuesByValueModel() {
        RiffleSchema schema = RiffleSchema.parse(UNDECLARED_KEY_SCHEMA);
        String distinct = """
                values : [
                    {
                        id : {
                            a : 1
                        }
                    }
                    {
                        id : {
                            a : 2
                        }
                    }
                ]
                """;
        String equal = """
                values : [
                    {
                        id : {
                            a : 1
                        }
                    }
                    {
                        id : {
                            a : 1
                        }
                    }
                ]
                """;

        assertThat(schema.validate(Riffle.parse(distinct))).isEmpty();
        assertThat(schema.validate(Riffle.parse(equal))).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.DUPLICATE);
            assertThat(problem.path().toString()).isEqualTo("values[1]");
        });
    }

    @Test
    void walksIntoEveryElementOfAnArray() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        servers : {\n            type : array\n            items : {\n                type : map\n"
                + "                keys : {\n                    port : {\n                        type : integer\n"
                + "                        min : 1024\n                    }\n                }\n            }\n"
                + "        }\n    }\n}\n");
        String document = "servers : [\n    {\n        port : 8080\n    }\n    {\n        port : 80\n    }\n]\n";

        RiffleValidationException failure =
                catchThrowableOfType(RiffleValidationException.class, () -> Riffle.parse(document, schema));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.OUT_OF_RANGE);
            assertThat(problem.path().toString()).isEqualTo("servers[1].port");
            assertThat(problem.location()).map(RiffleLocation::line).contains(6);
        });
    }

    @Test
    void stopsInsideAValueOfTheWrongKind() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        env : {\n            type : map\n            keys : {\n                region : {\n"
                + "                    type : string\n                    notBlank : true\n                }\n"
                + "            }\n        }\n    }\n}\n");

        RiffleValidationException failure =
                catchThrowableOfType(RiffleValidationException.class, () -> Riffle.parse("env : 1\n", schema));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.WRONG_KIND);
            assertThat(problem.path().toString()).isEqualTo("env");
        });
    }

    @Test
    void selectsTheCaseThatTheDiscriminatorNames() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        shape : {\n            type : taggedUnion\n            discriminator : kind\n"
                + "            cases : {\n                circle : {\n                    type : map\n"
                + "                    keys : {\n                        kind : {\n                            type : string\n"
                + "                        }\n                        radius : {\n                            type : decimal\n"
                + "                            min : 0\n                        }\n                    }\n"
                + "                }\n                square : {\n                    type : map\n                    keys : {\n"
                + "                        kind : {\n                            type : string\n                        }\n"
                + "                        side : {\n                            type : decimal\n"
                + "                            min : 0\n                            minExclusive : true\n"
                + "                        }\n                    }\n"
                + "                }\n            }\n        }\n    }\n}\n");

        RiffleValidationException failure = catchThrowableOfType(
                RiffleValidationException.class,
                () -> Riffle.parse("shape : {\n    kind : square\n    side : 0\n}\n", schema));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.OUT_OF_RANGE);
            assertThat(problem.path().toString()).isEqualTo("shape.side");
        });
    }

    @Test
    void asksTheUnionForTheKindTheValueHas() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        label : {\n            type : kindUnion\n            string : {\n                type : string\n"
                + "                pattern : \"[A-Za-z0-9]+\"\n            }\n            map : {\n                type : map\n"
                + "                otherKeys : {\n                    type : any\n                }\n            }\n"
                + "        }\n    }\n}\n");

        assertThatCode(() -> Riffle.parse("label : plain\n", schema)).doesNotThrowAnyException();
        assertThatCode(() -> Riffle.parse("label : 12\n", schema)).doesNotThrowAnyException();
        assertThatCode(() -> Riffle.parse("label : {\n    a : 1\n}\n", schema)).doesNotThrowAnyException();

        RiffleValidationException pattern =
                catchThrowableOfType(RiffleValidationException.class, () -> Riffle.parse("label : 1 2\n", schema));
        assertThat(pattern.problems())
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.PATTERN_MISMATCH);

        RiffleValidationException kind = catchThrowableOfType(
                RiffleValidationException.class, () -> Riffle.parse("label : [\n    a\n]\n", schema));
        assertThat(kind.problems())
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.WRONG_KIND);
    }

    @Test
    void collectsTheProblemsOfEveryMemberOfAMap() {
        RiffleSchema schema = RiffleSchema.parse(MAP_SCHEMA);
        String document = "port : 80\nname :\nmode : staging\na : x\nb : y\n";

        RiffleValidationException failure =
                catchThrowableOfType(RiffleValidationException.class, () -> Riffle.parse(document, schema));

        assertThat(failure.problems())
                .extracting(RiffleProblem::category, SchemaValidationTest::pathText)
                .containsExactly(
                        tuple(RiffleValidationError.OUT_OF_RANGE, "port"),
                        tuple(RiffleValidationError.BLANK, "name"),
                        tuple(RiffleValidationError.NOT_ALLOWED, "mode"),
                        tuple(RiffleValidationError.RULE_VIOLATION, "(root)"));
    }

    @Test
    void handsTheHostAnImmutableListOfProblems() {
        RiffleSchema schema = RiffleSchema.parse(MAP_SCHEMA);

        List<RiffleProblem> problems = schema.validate(Riffle.parse("port : 80\nname : gateway\n"));

        assertThat(problems).hasSize(1);
        assertThat(catchThrowableOfType(
                        UnsupportedOperationException.class,
                        () -> problems.add(
                                new RiffleProblem(RiffleValidationError.BLANK, RifflePath.root(), null, "x"))))
                .isNotNull();
    }

    @Test
    void reportsTheSameProblemsForTheSameTreeBuiltTwice() {
        RiffleSchema schema = RiffleSchema.parse(MAP_SCHEMA);
        String document = "port : 80\nname : gateway\n";

        List<RiffleProblem> first = catchThrowableOfType(
                        RiffleValidationException.class, () -> Riffle.parse(document, schema))
                .problems();
        List<RiffleProblem> second = catchThrowableOfType(
                        RiffleValidationException.class, () -> Riffle.parse(document, schema))
                .problems();

        assertThat(second).isEqualTo(first);
    }

    private static String pathText(RiffleProblem problem) {
        return problem.path().toString();
    }
}
