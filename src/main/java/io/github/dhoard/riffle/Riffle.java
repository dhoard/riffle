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
 * <p>The document root is always an implicit map. Values declare their own kinds with
 * {@code @type} markers, and a bare value is always a string.</p>
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
        return new RiffleParser(Objects.requireNonNull(text, "text"), null).parse();
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
        Objects.requireNonNull(reader, "reader");
        StringWriter text = new StringWriter();
        reader.transferTo(text);
        return new RiffleParser(text.toString(), null).parse();
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
        Objects.requireNonNull(file, "file");
        byte[] bytes = Files.readAllBytes(file);
        return new RiffleParser(decode(bytes, file.toString()), file.toString()).parse();
    }

    /**
     * Writes the canonical document. Parsing the result yields an equal value.
     *
     * @param container the root map
     * @return the document
     * @throws IllegalArgumentException if {@code container} is an array, which cannot be a root
     */
    public static String write(RiffleContainer container) {
        Objects.requireNonNull(container, "container");
        if (!(container instanceof RiffleObject root)) {
            throw new IllegalArgumentException("the root of a Riffle document is a map");
        }
        return RiffleWriter.write(root);
    }

    /**
     * Writes the canonical document as UTF-8, creating or replacing {@code file}.
     *
     * @param file the file
     * @param container the root map
     * @throws IOException if writing fails
     * @throws IllegalArgumentException if {@code container} is an array
     */
    public static void write(Path file, RiffleContainer container) throws IOException {
        Objects.requireNonNull(file, "file");
        Files.writeString(file, write(container), StandardCharsets.UTF_8);
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
