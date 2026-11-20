package io.github.dhoard.riffle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The meta-schema of SCHEMA_SPEC.md §15, which phase two of loading a schema validates a schema
 * document against.
 *
 * <p>The same text the specification publishes is shipped inside the jar as
 * {@code io/github/dhoard/riffle/meta-schema.riffle}, byte-identical to the copies in the
 * specification and the conformance suite. It is loaded once, lazily, on the first schema that is
 * loaded, and its own load runs the version and semantic phases but not the structure phase, since
 * the structure phase of the meta-schema is exactly what self-validation asks it to check.</p>
 */
final class MetaSchema {

    /** The classpath location of the shipped meta-schema, next to the classes it serves. */
    private static final String RESOURCE = "meta-schema.riffle";

    private MetaSchema() {}

    /** Returns the loaded meta-schema. */
    static RiffleSchema schema() {
        return Holder.SCHEMA;
    }

    /** Returns the root node of the loaded meta-schema. */
    static SchemaNode root() {
        return Holder.SCHEMA.root();
    }

    /** Returns the text the meta-schema was loaded from, for a diagnostic or a test. */
    static String text() {
        return Holder.TEXT;
    }

    /** Reads and loads the resource, once, when it is first needed. */
    private static final class Holder {

        private static final String TEXT = read();
        private static final RiffleSchema SCHEMA = load(TEXT);

        private static String read() {
            try (InputStream resource = MetaSchema.class.getResourceAsStream(RESOURCE)) {
                if (resource == null) {
                    throw new IllegalStateException("the shipped meta-schema `" + RESOURCE + "` is missing");
                }
                return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("the shipped meta-schema `" + RESOURCE + "` cannot be read", e);
            }
        }

        private static RiffleSchema load(String text) {
            return SchemaLoader.load(Riffle.parse(text), false);
        }
    }
}
