package io.github.dhoard.riffle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.groups.Tuple.tuple;

import java.io.FilterReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loading is the part of the contract a host meets most often: the same schema from text, a reader,
 * or a file, and a problem list that stops at the first phase that failed.
 */
class SchemaLoadTest {

    private static final String SERVICE = """
            riffleSchema : 1
            root : {
                type : map
                keys : {
                    port : {
                        type : integer
                        min : 1024
                    }
                }
            }
            """;

    @Test
    void loadsTheSameSchemaFromTextAReaderAndAFile() throws IOException {
        RiffleSchema fromText = RiffleSchema.parse(SERVICE);
        RiffleSchema fromReader = RiffleSchema.parse(new StringReader(SERVICE));
        RiffleSchema fromFile = RiffleSchema.parse(write(Path.of("target", "schema-load-text.riffle"), SERVICE));

        for (RiffleSchema schema : List.of(fromText, fromReader, fromFile)) {
            assertThatCode(() -> Riffle.parse("port : 8080\n", schema)).doesNotThrowAnyException();
            RiffleValidationException failure =
                    catchThrowableOfType(RiffleValidationException.class, () -> Riffle.parse("port : 80\n", schema));
            assertThat(failure.problems())
                    .singleElement()
                    .extracting(RiffleProblem::category)
                    .isEqualTo(RiffleValidationError.OUT_OF_RANGE);
        }
    }

    @Test
    void reportsOnlyTheFirstProblemAtEachNode() {
        RiffleSchemaException failure =
                catchThrowableOfType(RiffleSchemaException.class, () -> RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        x : {
                            type : string
                            values : [
                                a
                                a
                            ]
                            minLength : 1
                        }
                        y : {
                            type : string
                            values : [
                                b
                                b
                            ]
                            minLength : 1
                        }
                    }
                }
                """));

        assertThat(failure.problems())
                .extracting(RiffleProblem::path, RiffleProblem::category)
                .containsExactly(
                        tuple(
                                RifflePath.root().key("root").key("keys").key("x"),
                                RiffleValidationError.INVALID_CONSTRAINT),
                        tuple(
                                RifflePath.root().key("root").key("keys").key("y"),
                                RiffleValidationError.INVALID_CONSTRAINT));
        assertThat(failure.problems().get(0).reason()).contains("cannot be combined");
    }

    @Test
    void leavesTheReaderOpenForTheHost() throws IOException {
        ClosingReader reader = new ClosingReader(new PushbackReader(new StringReader(SERVICE), 1));

        assertThatCode(() -> RiffleSchema.parse(reader)).doesNotThrowAnyException();

        assertThat(reader.closed).isFalse();
    }

    @Test
    void reportsAMalformedFileAsAParseProblem(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("broken.riffle");
        Files.write(file, new byte[] {
            'r',
            'i',
            'f',
            'f',
            'l',
            'e',
            'S',
            'c',
            'h',
            'e',
            'm',
            'a',
            ' ',
            ':',
            ' ',
            1,
            '\n',
            'r',
            'o',
            'o',
            't',
            ' ',
            ':',
            ' ',
            '{',
            '\n',
            '}',
            '\n',
            (byte) 0xC3,
            (byte) 0x28
        });

        RiffleParseException failure = catchThrowableOfType(RiffleParseException.class, () -> RiffleSchema.parse(file));

        assertThat(failure).isNotNull();
    }

    @Test
    void reportsAMissingVersionAndNothingElse() throws IOException {
        RiffleSchemaException failure = catchThrowableOfType(
                RiffleSchemaException.class, () -> RiffleSchema.parse("root : {\n    type : map\n    tyep : map\n}\n"));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.MISSING);
            assertThat(problem.path()).isEqualTo(RifflePath.root().key("riffleSchema"));
            assertThat(problem.location()).contains(new RiffleLocation(null, 1, 1));
        });
    }

    @Test
    void reportsAnUnsupportedVersionAndNothingElse() throws IOException {
        RiffleSchemaException failure = catchThrowableOfType(
                RiffleSchemaException.class,
                () -> RiffleSchema.parse("riffleSchema : 2\nroot : {\n    type : string\n    tyep : map\n}\n"));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.UNSUPPORTED_VERSION);
            assertThat(problem.path()).isEqualTo(RifflePath.root().key("riffleSchema"));
        });
    }

    @Test
    void readsTheVersionAsTextBecauseThatIsWhatADocumentHolds() {
        assertThatCode(() -> RiffleSchema.parse("riffleSchema : \"1\"\nroot : {\n    type : map\n}\n"))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsAMissingRootAndNothingElse() {
        RiffleSchemaException failure = catchThrowableOfType(
                RiffleSchemaException.class,
                () -> RiffleSchema.parse(
                        "riffleSchema : 1\ndefinitions : {\n    unused : {\n        type : any\n" + "    }\n}\n"));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.MISSING);
            assertThat(problem.path()).isEqualTo(RifflePath.root().key("root"));
        });
    }

    @Test
    void stopsAfterTheStructurePhase(@TempDir Path directory) throws IOException {
        String schema = """
                riffleSchema : 1
                definitions : {
                    unused : {
                        type : maps
                    }
                }
                root : {
                    type : map
                }
                """;

        RiffleSchemaException failure = catchThrowableOfType(
                RiffleSchemaException.class,
                () -> RiffleSchema.parse(write(directory.resolve("phase.riffle"), schema)));

        assertThat(failure.problems())
                .singleElement()
                .extracting(RiffleProblem::category)
                .isEqualTo(RiffleValidationError.NOT_ALLOWED);
    }

    @Test
    void reportsOnlyTheUnusedDefinitionItCannotReach() {
        String schema = """
                riffleSchema : 1
                definitions : {
                    point : {
                        type : map
                        keys : {
                            x : {
                                type : integer
                            }
                        }
                    }
                }
                root : {
                    type : map
                }
                """;

        RiffleSchemaException failure =
                catchThrowableOfType(RiffleSchemaException.class, () -> RiffleSchema.parse(schema));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.UNUSED_DEFINITION);
            assertThat(problem.path())
                    .isEqualTo(RifflePath.root().key("definitions").key("point"));
        });
    }

    @Test
    void reportsAnUnusedDefinitionNamedRoot() {
        String schema = """
                riffleSchema : 1
                definitions : {
                    root : {
                        type : any
                    }
                }
                root : {
                    type : map
                }
                """;

        RiffleSchemaException failure =
                catchThrowableOfType(RiffleSchemaException.class, () -> RiffleSchema.parse(schema));

        assertThat(failure).isNotNull();
        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.UNUSED_DEFINITION);
            assertThat(problem.path())
                    .isEqualTo(RifflePath.root().key("definitions").key("root"));
        });
    }

    @Test
    void loadsAReachableDefinitionNamedRoot() {
        String schema = """
                riffleSchema : 1
                definitions : {
                    root : {
                        type : map
                    }
                }
                root : {
                    type : ref
                    definition : root
                }
                """;

        assertThatCode(() -> RiffleSchema.parse(schema)).doesNotThrowAnyException();
    }

    @Test
    void quotesAKeyThatIsNotBareWhenItReportsAProblem() {
        RiffleSchema schema = RiffleSchema.parse("riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n"
                + "        \"display name\" : {\n            type : string\n            notBlank : true\n        }\n"
                + "    }\n}\n");

        RiffleValidationException failure = catchThrowableOfType(
                RiffleValidationException.class, () -> Riffle.parse("\"display name\" :\n", schema));

        assertThat(failure.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.category()).isEqualTo(RiffleValidationError.BLANK);
            assertThat(problem.path().toString()).isEqualTo("\"display name\"");
        });
    }

    @Test
    void loadsADeeplyNestedSchemaWithoutACap() {
        StringBuilder deep = new StringBuilder("riffleSchema : 1\nroot : {\n");
        for (int level = 0; level < 500; level++) {
            deep.append("    type : map\n    keys : {\n        child : {\n");
        }
        deep.append("            type : any\n");
        for (int level = 0; level < 500; level++) {
            deep.append("        }\n    }\n");
        }
        deep.append("}\n");

        assertThatCode(() -> RiffleSchema.parse(deep.toString())).doesNotThrowAnyException();
    }

    @Test
    void appliesAConfiguredNestingCapToASchemaDocument(@TempDir Path directory) throws IOException {
        StringBuilder deep = new StringBuilder("riffleSchema : 1\nroot : {\n");
        for (int level = 0; level <= 100; level++) {
            deep.append("    items : {\n");
        }
        deep.append("        type : any\n");
        for (int level = 0; level <= 100; level++) {
            deep.append("    }\n");
        }
        deep.append("}\n");
        Path file = write(directory.resolve("deep.riffle"), deep.toString());

        RiffleParseException failure = catchThrowableOfType(
                RiffleParseException.class, () -> RiffleSchema.parse(file, RiffleOptions.maxDepth(100)));

        assertThat(failure.category()).isEqualTo(RiffleError.RESOURCE_LIMIT_EXCEEDED);
    }

    @Test
    void handsTheHostAnImmutableProblemList() throws IOException {
        String schema = """
                riffleSchema : 1
                root : {
                    type : string
                }
                """;

        RiffleSchemaException failure =
                catchThrowableOfType(RiffleSchemaException.class, () -> RiffleSchema.parse(schema));

        List<RiffleProblem> problems = failure.problems();
        assertThat(catchThrowableOfType(
                        UnsupportedOperationException.class,
                        () -> problems.add(
                                new RiffleProblem(RiffleValidationError.BLANK, RifflePath.root(), null, "x"))))
                .isNotNull();
        assertThat(problems).hasSize(failure.problems().size());
    }

    /** Writes {@code text} to {@code file} and returns the file. */
    private static Path write(Path file, String text) throws IOException {
        Files.writeString(file, text, UTF_8);
        return file;
    }

    /** A reader that records whether the code it was handed closed it. */
    private static final class ClosingReader extends FilterReader {

        private boolean closed;

        private ClosingReader(PushbackReader reader) {
            super(reader);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
