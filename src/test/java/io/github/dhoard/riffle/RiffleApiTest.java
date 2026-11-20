package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every way of supplying a document, a schema, and resource options reaches the same parse and
 * validate, whichever overload a caller starts from.
 */
class RiffleApiTest {

    private static final String SCHEMA = """
            riffleSchema : 1
            root : {
                type : map
                keys : {
                    name : {
                        type : string
                        notBlank : true
                    }
                }
            }
            """;

    private static final String DOCUMENT = """
            name : widget
            """;

    @TempDir
    Path directory;

    private RiffleObject writeAndRead(RiffleObject document, RiffleOptions options) throws IOException {
        Path file = directory.resolve("document.riffle");
        Riffle.write(file, document, options);
        return Riffle.parse(file, options);
    }

    @Test
    void aReaderAndASchemaComeTogetherInOneCall() throws IOException {
        RiffleSchema schema = RiffleSchema.parse(SCHEMA);

        assertThat(Riffle.parse(new StringReader(DOCUMENT), schema).entries()).containsOnlyKeys("name");
        assertThat(Riffle.parse(new StringReader(DOCUMENT), schema, RiffleOptions.maxDepth(4))
                        .entries())
                .containsOnlyKeys("name");
    }

    @Test
    void aFileAndASchemaComeTogetherInOneCall() throws IOException {
        RiffleSchema schema = RiffleSchema.parse(SCHEMA);
        Path file = directory.resolve("document.riffle");
        Files.writeString(file, DOCUMENT, StandardCharsets.UTF_8);

        assertThat(Riffle.parse(file, schema).entries()).containsOnlyKeys("name");
        assertThat(Riffle.parse(file, schema, RiffleOptions.maxDepth(4)).entries())
                .containsOnlyKeys("name");
    }

    @Test
    void aReaderAndASchemaThatTheDocumentViolatesReportTheViolation() {
        RiffleSchema schema = RiffleSchema.parse(SCHEMA);

        RiffleValidationException failure = catchThrowableOfType(
                RiffleValidationException.class, () -> Riffle.parse(new StringReader("name :\n"), schema));

        assertThat(failure.problems()).extracting(RiffleProblem::category).containsExactly(RiffleValidationError.BLANK);
    }

    @Test
    void aSchemaRejectsADocumentBeyondTheConfiguredDepth() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        outer : {
                            type : map
                            keys : {
                                inner : {
                                    type : string
                                }
                            }
                        }
                    }
                }
                """);

        RiffleObject document = Riffle.parse("""
                outer : {
                    inner : {
                        deeper : {
                            x : 1
                        }
                    }
                }
                """);

        assertThatThrownBy(() -> schema.validate(document, RiffleOptions.maxDepth(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document tree is deeper than the configured limit: 3 > 2");
    }

    @Test
    void writingWithoutOptionsUsesTheDefaultLimits() throws IOException {
        RiffleObject document = Riffle.parse(DOCUMENT);

        assertThat(Riffle.write(document)).isEqualTo(DOCUMENT);
        assertThat(writeAndRead(document, RiffleOptions.maxDepth(4))).isEqualTo(document);
    }

    @Test
    void writingAFileRoundTripsThroughTheSameValue() throws IOException {
        RiffleObject document = Riffle.parse("""
                name : widget
                list : [
                    a
                    b
                ]
                """);
        Path file = directory.resolve("written.riffle");

        Riffle.write(file, document);

        assertThat(Riffle.parse(file)).isEqualTo(document);
    }

    @Test
    void anOperationWithoutItsArgumentsSaysWhichOneIsMissing() {
        RiffleSchema schema = RiffleSchema.parse(SCHEMA);

        assertThatThrownBy(() -> Riffle.parse(DOCUMENT, (RiffleSchema) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("schema");
        assertThatThrownBy(() -> Riffle.parse(DOCUMENT, schema, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options");
        assertThatThrownBy(() -> Riffle.parse(new StringReader(DOCUMENT), (RiffleSchema) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("schema");
        assertThatThrownBy(() -> Riffle.parse(new StringReader(DOCUMENT), schema, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options");
        assertThatThrownBy(() -> Riffle.parse(directory.resolve("absent.riffle"), (RiffleSchema) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("schema");
        assertThatThrownBy(() -> Riffle.parse(directory.resolve("absent.riffle"), schema, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options");
    }

    @Test
    void validatingADocumentReturnsItOnlyWhenItIsUsable() {
        RiffleSchema schema = RiffleSchema.parse(SCHEMA);
        RiffleObject document = Riffle.parse(DOCUMENT);

        assertThat(schema.validated(document)).isSameAs(document);
        assertThat(schema.validate(document)).isEmpty();
        assertThatThrownBy(() -> schema.validated(Riffle.parse("name :\n")))
                .isInstanceOf(RiffleValidationException.class);
    }

    @Test
    void aSchemaSaysWhichKindItsRootDeclares() {
        assertThat(RiffleSchema.parse(SCHEMA)).hasToString("RiffleSchema[root=map]");
    }

    @Test
    void aProblemListIsNeverEmpty() {
        assertThatThrownBy(() -> new RiffleValidationException(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("problems is empty");
        assertThatThrownBy(() -> new RiffleSchemaException(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("problems is empty");
        assertThatThrownBy(() -> new RiffleValidationException(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RiffleSchemaException(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theShippedMetaSchemaIsLoadedFromTheJar() {
        assertThat(MetaSchema.text()).startsWith("riffleSchema : 1");
        assertThat(MetaSchema.schema()).isNotNull();
        assertThat(MetaSchema.root().kind()).isEqualTo(SchemaNode.Kind.MAP);
    }
}
