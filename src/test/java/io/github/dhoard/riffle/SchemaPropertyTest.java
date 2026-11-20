package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Random documents exercise the validator along paths no fixture takes: validation must always
 * terminate, must always say where a problem is, and must say the same thing however often or
 * however concurrently it is asked (SCHEMA_SPEC.md §12, §14).
 */
class SchemaPropertyTest {

    /** A schema that mixes every node kind the validator dispatches on. */
    private static final String WIDE = """
            riffleSchema : 1
            definitions : {
                node : {
                    type : map
                    keys : {
                        name : {
                            type : string
                            notBlank : true
                            optional : true
                        }
                        port : {
                            type : integer
                            min : 1
                            max : 65535
                            optional : true
                        }
                        ratio : {
                            type : decimal
                            optional : true
                            default : 1
                        }
                        enabled : {
                            type : boolean
                            optional : true
                        }
                        mode : {
                            type : string
                            values : [
                                dev
                                prod
                            ]
                            optional : true
                        }
                        tags : {
                            type : array
                            unique : true
                            items : {
                                type : string
                                maxLength : 8
                                pattern : "[a-z]*"
                            }
                            optional : true
                        }
                        servers : {
                            type : array
                            items : {
                                type : map
                                keys : {
                                    host : {
                                        type : string
                                        notBlank : true
                                    }
                                }
                            }
                            optional : true
                        }
                        shape : {
                            type : taggedUnion
                            discriminator : kind
                            optional : true
                            cases : {
                                circle : {
                                    type : map
                                    keys : {
                                        kind : {
                                            type : string
                                        }
                                        radius : {
                                            type : decimal
                                            min : 0
                                        }
                                    }
                                    otherKeys : {
                                        type : any
                                    }
                                }
                                text : {
                                    type : map
                                    keys : {
                                        kind : {
                                            type : string
                                        }
                                        body : {
                                            type : string
                                            pattern : "[a-z]*"
                                        }
                                    }
                                    otherKeys : {
                                        type : any
                                    }
                                }
                            }
                        }
                        either : {
                            type : kindUnion
                            string : {
                                type : string
                            }
                            map : {
                                type : map
                                otherKeys : {
                                    type : any
                                }
                            }
                            array : {
                                type : array
                                items : {
                                    type : any
                                }
                            }
                            optional : true
                        }
                        child : {
                            type : ref
                            definition : node
                            optional : true
                        }
                    }
                    otherKeys : {
                        type : any
                    }
                    rules : [
                        {
                            rule : requires
                            key : name
                            keys : [
                                port
                            ]
                        }
                    ]
                }
            }
            root : {
                type : ref
                definition : node
            }
            """;

    /** A schema that takes any document at all. */
    private static final String OPEN =
            "riffleSchema : 1\nroot : {\n    type : map\n    otherKeys : {\n" + "        type : any\n    }\n}\n";

    @Test
    void validationNeverThrowsAndAlwaysNamesAPlaceInTheDocument() {
        RiffleSchema schema = RiffleSchema.parse(WIDE);
        Random random = new Random(20261119L);

        for (int sample = 0; sample < 200; sample++) {
            RiffleObject document = new RiffleRandom(random).object();

            List<RiffleProblem> problems = schema.validate(document);

            for (RiffleProblem problem : problems) {
                assertThat(resolves(document, problem.path()))
                        .as("path %s of %s", problem.path(), Riffle.write(document))
                        .isTrue();
                assertThat(problem.reason()).isNotBlank();
            }
        }
    }

    @Test
    void anOpenSchemaAcceptsEveryDocumentItIsGiven() {
        RiffleSchema schema = RiffleSchema.parse(OPEN);
        Random random = new Random(99L);

        for (int sample = 0; sample < 200; sample++) {
            RiffleObject document = new RiffleRandom(random).object();

            assertThat(schema.validate(document))
                    .as("document %s", Riffle.write(document))
                    .isEmpty();
            assertThat(schema.applyDefaults(document)).isEqualTo(document);
        }
    }

    @Test
    void fillingDefaultsLeavesADocumentThatValidatesAndFillsNoFurther() {
        RiffleSchema schema = RiffleSchema.parse(WIDE);
        Random random = new Random(4711L);
        int filled = 0;

        for (int sample = 0; sample < 200; sample++) {
            RiffleObject document = new RiffleRandom(random).object();

            RiffleValidationException failure =
                    catchThrowableOfType(RiffleValidationException.class, () -> schema.applyDefaults(document));
            if (failure != null) {
                assertThat(failure.problems())
                        .as("rejected %s", Riffle.write(document))
                        .isNotEmpty();
                continue;
            }
            RiffleObject once = schema.applyDefaults(document);

            assertThat(schema.validate(once))
                    .as("filled %s", Riffle.write(once))
                    .isEmpty();
            assertThat(schema.applyDefaults(once)).isEqualTo(once);
            if (!once.equals(document)) {
                filled++;
            }
        }

        assertThat(filled).as("documents that actually took a default").isPositive();
    }

    @Test
    void aDocumentThatValidatesStillValidatesAfterItIsWrittenAndReadBack() {
        RiffleSchema schema = RiffleSchema.parse(WIDE);
        Random random = new Random(616L);
        int checked = 0;

        for (int sample = 0; sample < 150; sample++) {
            RiffleObject document = new RiffleRandom(random).object();
            List<RiffleProblem> problems = schema.validate(document);

            assertThat(schema.validate(document)).isEqualTo(problems);
            if (!problems.isEmpty()) {
                continue;
            }
            checked++;
            assertThat(schema.validate(Riffle.parse(Riffle.write(document)))).isEmpty();
        }

        assertThat(checked).as("documents that satisfied the schema").isPositive();
    }

    @Test
    void oneSchemaServesManyThreadsIdentically() throws Exception {
        RiffleSchema schema = RiffleSchema.parse(WIDE);
        Random random = new Random(31337L);
        List<RiffleObject> documents = new ArrayList<>();
        for (int sample = 0; sample < 24; sample++) {
            documents.add(new RiffleRandom(random).object());
        }
        List<List<RiffleProblem>> sequential =
                documents.stream().map(schema::validate).toList();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<RiffleProblem>>> running = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                for (RiffleObject document : documents) {
                    running.add(executor.submit(() -> schema.validate(document)));
                }
            }
            List<List<RiffleProblem>> concurrent = new ArrayList<>();
            for (Future<List<RiffleProblem>> future : running) {
                concurrent.add(future.get());
            }
            for (int round = 0; round < 3; round++) {
                assertThat(concurrent.subList(round * documents.size(), (round + 1) * documents.size()))
                        .isEqualTo(sequential);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Test
    void aHundredThousandElementsAreValidatedInTime() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        values : {\n            type : array\n            unique : true\n            items : {\n"
                + "                type : decimal\n            }\n        }\n    }\n}\n");
        StringBuilder document = new StringBuilder("values : [\n");
        for (int index = 0; index < 100_000; index++) {
            document.append("    ").append(index).append(".0\n");
        }
        document.append("    1\n]\n");

        List<RiffleProblem> problems = schema.validate(Riffle.parse(document.toString()));

        // `1` is the same decimal as the `1.0` at element 1 (line 3), and nothing else repeats
        assertThat(problems)
                .singleElement()
                .extracting(RiffleProblem::category, SchemaPropertyTest::pathText)
                .containsExactly(RiffleValidationError.DUPLICATE, "values[100000]");
    }

    /** Returns whether a problem's path addresses a value, or a member a present map lacks. */
    private static boolean resolves(RiffleObject root, RifflePath path) {
        List<RifflePath.Segment> segments = path.segments();
        RiffleValue current = root;
        for (int position = 0; position < segments.size(); position++) {
            RifflePath.Segment segment = segments.get(position);
            boolean last = position == segments.size() - 1;
            if (segment instanceof RifflePath.Key key && current instanceof RiffleObject map) {
                RiffleValue member = map.entries().get(key.key());
                if (member == null) {
                    return last;
                }
                current = member;
            } else if (segment instanceof RifflePath.Index index
                    && current instanceof RiffleArray array
                    && index.index() >= 0
                    && index.index() < array.size()) {
                current = array.get(index.index());
            } else {
                return false;
            }
        }
        return true;
    }

    private static String pathText(RiffleProblem problem) {
        return problem.path().toString();
    }
}
