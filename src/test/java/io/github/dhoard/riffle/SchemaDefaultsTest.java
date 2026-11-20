package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Defaults are a separate operation because validation never inserts anything (SCHEMA_SPEC.md §13).
 * An inserted value carries no location, and the recursion is bounded by the document nesting limit
 * (§14).
 */
class SchemaDefaultsTest {

    private static final String SERVICE = """
            riffleSchema : 1
            root : {
                type : map
                keys : {
                    name : {
                        type : string
                        notBlank : true
                    }
                    port : {
                        type : integer
                        min : 1
                        optional : true
                        default : 8080
                    }
                    options : {
                        type : map
                        optional : true
                        default : {}
                        keys : {
                            verbose : {
                                type : boolean
                                optional : true
                                default : false
                            }
                            level : {
                                type : integer
                                optional : true
                                default : 3
                            }
                        }
                    }
                    tags : {
                        type : array
                        optional : true
                        items : {
                            type : map
                            keys : {
                                kind : {
                                    type : string
                                }
                                weight : {
                                    type : integer
                                    optional : true
                                    default : 1
                                }
                            }
                        }
                    }
                }
            }
            """;

    @Test
    void insertsAbsentMembersAfterTheOnesTheDocumentHad() {
        RiffleSchema schema = RiffleSchema.parse(SERVICE);

        RiffleObject filled = schema.applyDefaults(Riffle.parse("name : gateway\nport : 9090\n"));

        assertThat(filled.entries().keySet()).containsExactly("name", "port", "options");
        assertThat(filled.getString("port")).isEqualTo("9090");
        assertThat(filled.getObject("options").entries().keySet()).containsExactly("verbose", "level");
        assertThat(filled.getObject("options").getBoolean("verbose")).isFalse();
        assertThat(filled.getObject("options").getInt("level")).isEqualTo(3);
    }

    @Test
    void changesNothingWhenEveryDefaultIsAlreadyPresent() {
        RiffleSchema schema = RiffleSchema.parse(SERVICE);
        RiffleObject document =
                Riffle.parse("name : gateway\nport : 1\noptions : {\n    verbose : true\n    level : 9\n}\n");

        assertThat(schema.applyDefaults(document)).isEqualTo(document);
    }

    @Test
    void fillsInsideTheValuesItInsertsAndTheOnesTheDocumentHad() {
        RiffleSchema schema = RiffleSchema.parse(SERVICE);

        RiffleObject filled = schema.applyDefaults(Riffle.parse("name : gateway\noptions : {\n    level : 9\n}\n"));

        assertThat(filled.getObject("options").entries().keySet()).containsExactly("level", "verbose");
        assertThat(filled.getObject("options").getString("verbose")).isEqualTo("false");
    }

    @Test
    void fillsEveryElementOfAnArray() {
        RiffleSchema schema = RiffleSchema.parse(SERVICE);

        RiffleObject filled = schema.applyDefaults(Riffle.parse("name : gateway\ntags : [\n    {\n        kind : a\n"
                + "    }\n    {\n        kind : b\n        weight : 5\n    }\n]\n"));

        assertThat(filled.getArray("tags").getObject(0).entries().keySet()).containsExactly("kind", "weight");
        assertThat(filled.getArray("tags").getObject(0).getInt("weight")).isEqualTo(1);
        assertThat(filled.getArray("tags").getObject(1).entries().keySet()).containsExactly("kind", "weight");
        assertThat(filled.getArray("tags").getObject(1).getInt("weight")).isEqualTo(5);
    }

    @Test
    void givesAnInsertedValueNoLocationAndAPathOfWhereItLanded() {
        RiffleSchema schema = RiffleSchema.parse(SERVICE);

        RiffleObject filled = schema.applyDefaults(Riffle.parse("name : gateway\n"));

        assertThat(filled.keyLocation("port")).isNull();
        assertThat(filled.get("port").location()).isEmpty();
        assertThat(filled.get("port").path()).contains(RifflePath.root().key("port"));
        assertThat(filled.getObject("options").get("level").path())
                .contains(RifflePath.root().key("options").key("level"));
    }

    @Test
    void leavesTheDocumentItWasGivenAlone() {
        RiffleSchema schema = RiffleSchema.parse(SERVICE);
        RiffleObject document = Riffle.parse("name : gateway\n");

        RiffleObject filled = schema.applyDefaults(document);

        assertThat(document.entries().keySet()).containsExactly("name");
        assertThat(filled.entries().keySet()).containsExactly("name", "port", "options");
    }

    @Test
    void insertsNothingAndReportsTheProblemsWhenTheInputIsInvalid() {
        RiffleSchema schema = RiffleSchema.parse(SERVICE);

        RiffleValidationException failure = catchThrowableOfType(
                RiffleValidationException.class, () -> schema.applyDefaults(Riffle.parse("name :\nport : 0\n")));

        assertThat(failure.problems()).hasSize(2);
        assertThat(failure.problems())
                .extracting(RiffleProblem::category)
                .containsExactly(RiffleValidationError.BLANK, RiffleValidationError.OUT_OF_RANGE);
    }

    @Test
    void reportsADefaultThatDoesNotSatisfyItsOwnNode() {
        RiffleSchemaException failure = catchThrowableOfType(
                RiffleSchemaException.class,
                () -> RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n        port : {\n"
                        + "            type : integer\n            min : 1024\n            optional : true\n"
                        + "            default : 80\n        }\n    }\n}\n"));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.INVALID_DEFAULT);
            assertThat(problem.path().toString()).isEqualTo("root.keys.port.default");
        });
    }

    @Test
    void fillsDeepTreesAndAppliesAConfiguredCapToTheResult() {
        RiffleSchema schema = RiffleSchema.parse(chain(200));
        RiffleObject document = Riffle.parse("leaf : x\n");

        RiffleObject filled = schema.applyDefaults(document);

        assertThat(depthOf(filled, "next")).isGreaterThan(100);
        assertThat(filled.get("leaf")).isNotNull();
        assertThatThrownBy(() -> schema.applyDefaults(document, RiffleOptions.maxDepth(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
        assertThatThrownBy(() -> schema.validate(filled, RiffleOptions.maxDepth(100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fillingIsIdempotent() {
        RiffleSchema schema = RiffleSchema.parse(SERVICE);
        RiffleObject once = schema.applyDefaults(Riffle.parse("name : gateway\n"));

        assertThat(schema.applyDefaults(once)).isEqualTo(once);
    }

    /**
     * A schema that chains {@code levels} definitions, each a map holding an optional {@code next}
     * member whose default is another map of the same shape.
     */
    private static String chain(int levels) {
        List<String> definitions = new ArrayList<>();
        for (int level = 0; level < levels; level++) {
            definitions.add(String.format(
                    Locale.ROOT,
                    "    d%d : {\n        type : map\n"
                            + "        keys : {\n            leaf : {\n                type : string\n"
                            + "                optional : true\n                default : filled\n            }\n"
                            + "            next : {\n                type : ref\n                definition : d%d\n"
                            + "                optional : true\n                default : {\n                }\n            }\n"
                            + "        }\n    }\n",
                    level,
                    level + 1));
        }
        String last = String.format(
                Locale.ROOT,
                "    d%d : {\n        type : map\n"
                        + "        keys : {\n            leaf : {\n                type : string\n                optional : true\n"
                        + "                default : filled\n            }\n        }\n    }\n",
                levels);
        return "riffleSchema : 1\ndefinitions : {\n"
                + String.join("", definitions) + last + "}\nroot : {\n    type : ref\n"
                + "    definition : d0\n}\n";
    }

    /** Counts how deep the {@code key} chain reaches in a filled document. */
    private static int depthOf(RiffleObject value, String key) {
        int depth = 0;
        RiffleObject current = value;
        while (current.find(key).orElse(null) instanceof RiffleObject next) {
            current = next;
            depth++;
        }
        return depth;
    }
}
