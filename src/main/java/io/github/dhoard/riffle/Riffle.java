package io.github.dhoard.riffle;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Entry point for parsing and writing Riffle documents.
 *
 * <p>The document root is always an implicit map, and every scalar is a string. The program
 * decides how to read a string through the {@link RiffleValue} accessors.</p>
 *
 * <p>Parsing reads the whole document into memory and has no fixed nesting limit; time and memory
 * stay proportional to the input. Applications that read untrusted documents can pass
 * {@link RiffleOptions#maxDepth(int)} and should bound their size themselves, for example with a
 * size check before {@link #parse(Path, RiffleOptions)} or a bounded {@link Reader}.</p>
 */
public final class Riffle {
    private Riffle() {}

    /**
     * Parses a document.
     *
     * @param text the document
     * @return the root map
     * @throws RiffleParseException if the document is not valid Riffle
     */
    public static RiffleObject parse(String text) {
        return parse(text, RiffleOptions.UNBOUNDED);
    }

    /**
     * Parses a document with caller-supplied resource options.
     *
     * @param text the document
     * @param options the resource options
     * @return the root map
     * @throws RiffleParseException if the document is not valid Riffle, or exceeds a configured cap
     */
    public static RiffleObject parse(String text, RiffleOptions options) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(options, "options");
        return new RiffleParser(text, null, options).parse();
    }

    /**
     * Reads all characters from {@code reader} and parses them. The reader is not closed.
     *
     * @param reader the source
     * @return the root map
     * @throws IOException if reading fails
     * @throws RiffleParseException if the document is not valid Riffle
     */
    public static RiffleObject parse(Reader reader) throws IOException {
        return parse(reader, RiffleOptions.UNBOUNDED);
    }

    /**
     * Reads all characters from {@code reader} and parses them with caller-supplied options. The
     * reader is not closed.
     *
     * @param reader the source
     * @param options the resource options
     * @return the root map
     * @throws IOException if reading fails
     * @throws RiffleParseException if the document is not valid Riffle, or exceeds a configured cap
     */
    public static RiffleObject parse(Reader reader, RiffleOptions options) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(options, "options");
        StringWriter text = new StringWriter();
        reader.transferTo(text);
        return new RiffleParser(text.toString(), null, options).parse();
    }

    /**
     * Reads a UTF-8 file and parses it. Diagnostics name the file.
     *
     * @param file the file
     * @return the root map
     * @throws IOException if reading fails
     * @throws RiffleParseException if the file is not valid UTF-8 or not valid Riffle
     */
    public static RiffleObject parse(Path file) throws IOException {
        return parse(file, RiffleOptions.UNBOUNDED);
    }

    /**
     * Reads a UTF-8 file and parses it with caller-supplied options. Diagnostics name the file.
     *
     * @param file the file to read
     * @param options the resource options
     * @return the root map
     * @throws IOException if the file cannot be read
     * @throws RiffleParseException if the document is invalid, or exceeds a configured cap
     */
    public static RiffleObject parse(Path file, RiffleOptions options) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        byte[] bytes = Files.readAllBytes(file);
        return new RiffleParser(decode(bytes, file.toString()), file.toString(), options).parse();
    }

    /**
     * Parses a document and validates it against a schema, so that a host that has a schema for its
     * configuration needs one call. A document that violates the schema is still a complete document:
     * the exception carries every violation, and nothing is inserted, reordered or coerced.
     *
     * @param text the document
     * @param schema the schema to validate the document against
     * @return the root map
     * @throws NullPointerException if {@code schema} is null
     * @throws RiffleParseException if the document is not valid Riffle
     * @throws RiffleValidationException if the document violates {@code schema}
     */
    public static RiffleObject parse(String text, RiffleSchema schema) {
        return parse(text, schema, RiffleOptions.UNBOUNDED);
    }

    /**
     * Parses and validates with caller-supplied resource options.
     *
     * @param text the document
     * @param schema the schema to validate against
     * @param options the resource options
     * @return the root map
     * @throws RiffleParseException if the document is invalid, or exceeds a configured cap
     * @throws RiffleValidationException if the document violates {@code schema}
     */
    public static RiffleObject parse(String text, RiffleSchema schema, RiffleOptions options) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(options, "options");
        return schema.validated(parse(text, options), options);
    }

    /**
     * Reads all characters from {@code reader}, parses them, and validates the document against a
     * schema. The reader is not closed.
     *
     * @param reader the source
     * @param schema the schema to validate the document against
     * @return the root map
     * @throws NullPointerException if {@code schema} is null
     * @throws IOException if reading fails
     * @throws RiffleParseException if the document is not valid Riffle
     * @throws RiffleValidationException if the document violates {@code schema}
     */
    public static RiffleObject parse(Reader reader, RiffleSchema schema) throws IOException {
        return parse(reader, schema, RiffleOptions.UNBOUNDED);
    }

    /**
     * Parses and validates a reader with caller-supplied resource options. The reader is not closed.
     *
     * @param reader the source
     * @param schema the schema to validate against
     * @param options the resource options
     * @return the root map
     * @throws IOException if reading fails
     * @throws RiffleParseException if the document is invalid, or exceeds a configured cap
     * @throws RiffleValidationException if the document violates {@code schema}
     */
    public static RiffleObject parse(Reader reader, RiffleSchema schema, RiffleOptions options) throws IOException {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(options, "options");
        return schema.validated(parse(reader, options), options);
    }

    /**
     * Reads a UTF-8 file, parses it, and validates the document against a schema. Diagnostics name
     * the file.
     *
     * @param file the file
     * @param schema the schema to validate the document against
     * @return the root map
     * @throws NullPointerException if {@code schema} is null
     * @throws IOException if reading fails
     * @throws RiffleParseException if the file is not valid UTF-8 or not valid Riffle
     * @throws RiffleValidationException if the document violates {@code schema}
     */
    public static RiffleObject parse(Path file, RiffleSchema schema) throws IOException {
        return parse(file, schema, RiffleOptions.UNBOUNDED);
    }

    /**
     * Parses and validates a file with caller-supplied resource options.
     *
     * @param file the file to read
     * @param schema the schema to validate against
     * @param options the resource options
     * @return the root map
     * @throws IOException if the file cannot be read
     * @throws RiffleParseException if the document is invalid, or exceeds a configured cap
     * @throws RiffleValidationException if the document violates {@code schema}
     */
    public static RiffleObject parse(Path file, RiffleSchema schema, RiffleOptions options) throws IOException {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(options, "options");
        return schema.validated(parse(file, options), options);
    }

    /**
     * Writes the canonical document. Parsing the result yields an equal value.
     *
     * @param container the root map
     * @return the document
     * @throws IllegalArgumentException if {@code container} is an array, which cannot be a root,
     *     or if the value is deeper than a configured cap
     */
    public static String write(RiffleContainer container) {
        return write(container, RiffleOptions.UNBOUNDED);
    }

    /**
     * Writes the canonical document with caller-supplied resource options.
     *
     * @param container the root map
     * @param options the resource options
     * @return the document
     * @throws IllegalArgumentException if {@code container} is an array, which cannot be a root, or
     *     if the value is deeper than a configured cap
     */
    public static String write(RiffleContainer container, RiffleOptions options) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(options, "options");
        if (!(container instanceof RiffleObject root)) {
            throw new IllegalArgumentException("the root of a Riffle document is a map");
        }
        return RiffleWriter.write(root, options);
    }

    /**
     * Writes the canonical document as UTF-8, creating or replacing {@code file}.
     *
     * <p>The file is truncated and then written directly, so an interrupted write can leave a
     * partial document behind. Callers that need the previous contents preserved should write a
     * temporary file and move it into place.</p>
     *
     * @param file the file
     * @param container the root map
     * @throws IOException if writing fails
     * @throws IllegalArgumentException if {@code container} is an array or exceeds a configured cap
     */
    public static void write(Path file, RiffleContainer container) throws IOException {
        write(file, container, RiffleOptions.UNBOUNDED);
    }

    /**
     * Writes the canonical document as UTF-8 with caller-supplied resource options.
     *
     * @param file the file to write
     * @param container the root map
     * @param options the resource options
     * @throws IOException if writing fails
     * @throws IllegalArgumentException if {@code container} is an array or exceeds a configured cap
     */
    public static void write(Path file, RiffleContainer container, RiffleOptions options) throws IOException {
        Objects.requireNonNull(file, "file");
        Files.writeString(file, write(container, options), StandardCharsets.UTF_8);
    }

    private static String decode(byte[] bytes, String source) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            return decoder.decode(buffer).toString();
        } catch (CharacterCodingException e) {
            int position = Math.max(buffer.position(), 0);
            String prefix = new String(bytes, 0, position, StandardCharsets.UTF_8);
            int line = 1;
            int column = 1;
            for (int i = 0; i < prefix.length(); i++) {
                if (prefix.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            throw new RiffleParseException(
                    RiffleError.INVALID_UTF8, "malformed UTF-8", new RiffleLocation(source, line, column));
        }
    }
}
