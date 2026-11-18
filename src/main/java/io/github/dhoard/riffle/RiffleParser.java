package io.github.dhoard.riffle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Line-oriented parser for Riffle 0.1.0: one entry or element per line, collections on their
 * own lines, and every scalar a string. Package-private by design.
 *
 * <p>Lines are scanned in one pass with three modes (LANGUAGE_SPEC.md §9.1). In normal mode a line
 * is blank, a comment, an entry, an element, or a closer. In block-comment mode only comment
 * delimiters mean anything, and in raw-block mode only the closing {@code """} does. A {@code #}
 * after a value is part of that value, so no key, value, opener, or closer can contain a comment
 * (§8).</p>
 */
final class RiffleParser {
    private final String source;
    private final List<String> lines = new ArrayList<>();
    private int next;

    RiffleParser(String text, String source) {
        this.source = source;
        String body = !text.isEmpty() && text.charAt(0) == Syntax.BYTE_ORDER_MARK ? text.substring(1) : text;
        validateCodePoints(body);
        splitLines(body);
    }

    RiffleObject parse() {
        next = 0;
        return readMap(at(0, 0), "", 0, true);
    }

    // ---------------------------------------------------------------- lexical

    private void validateCodePoints(String text) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            int count = 1;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    count = 2;
                } else {
                    throw new RiffleParseException(
                            RiffleError.INVALID_UTF8, "unpaired surrogate", new RiffleLocation(source, line, column));
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new RiffleParseException(
                        RiffleError.INVALID_UTF8, "unpaired surrogate", new RiffleLocation(source, line, column));
            } else if (c == Syntax.BYTE_ORDER_MARK) {
                throw new RiffleParseException(
                        RiffleError.INVALID_CHARACTER,
                        "byte-order mark outside the start of the document",
                        new RiffleLocation(source, line, column));
            }
            for (int k = 0; k < count; k++) {
                if (text.charAt(i + k) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            i += count;
        }
    }

    private void splitLines(String text) {
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            } else if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    lines.add(text.substring(start, i));
                    i++;
                    start = i + 1;
                } else {
                    throw new RiffleParseException(
                            RiffleError.INVALID_LINE_ENDING,
                            "a carriage return must be followed by a line feed",
                            new RiffleLocation(source, lines.size() + 1, i - start + 1));
                }
            }
        }
        lines.add(text.substring(start));
    }

    /**
     * Advances to the next line that holds an entry, element, or closer, and returns its index, or
     * {@code -1} at the end of the document. Blank lines, line comments, and block comments are
     * consumed here.
     */
    private int nextContentLine() {
        while (next < lines.size()) {
            int index = next++;
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (start >= raw.length()) {
                continue;
            }
            if (isBlockCommentOpener(raw, start)) {
                skipBlockComment(index, start);
            } else if (raw.charAt(start) != '#') {
                return index;
            }
        }
        return -1;
    }

    /**
     * Consumes a block comment whose opener has just been read. Every line is ignored except the
     * delimiters that track nesting (LANGUAGE_SPEC.md §8.2).
     */
    private void skipBlockComment(int openerLine, int openerColumn) {
        int depth = 1;
        while (next < lines.size()) {
            String raw = lines.get(next++);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (isBlockCommentOpener(raw, start)) {
                depth++;
            } else if (isBlockCommentCloser(raw, start) && --depth == 0) {
                return;
            }
        }
        throw error(
                RiffleError.UNCLOSED_COMMENT,
                "unclosed block comment; a line containing only '#/' is expected",
                openerLine,
                openerColumn);
    }

    /** Returns whether a line opens a block comment: {@code /#} followed by anything. */
    private static boolean isBlockCommentOpener(String line, int start) {
        return start + 1 < line.length() && line.charAt(start) == '/' && line.charAt(start + 1) == '#';
    }

    /** Returns whether a line closes a block comment: {@code #/} and nothing after it but whitespace. */
    private static boolean isBlockCommentCloser(String line, int start) {
        return start + 1 < line.length()
                && line.charAt(start) == '#'
                && line.charAt(start + 1) == '/'
                && Syntax.firstNonWhitespace(line, start + 2) >= line.length();
    }

    /** Requires that an opener is the last syntactic token on its line. */
    private void openerLast(
            String line, int from, int lineIndex, char open, char close, String collection, String contents) {
        int i = Syntax.firstNonWhitespace(line, from);
        if (i >= line.length()) {
            return;
        }
        if (line.charAt(i) == close && Syntax.firstNonWhitespace(line, i + 1) >= line.length()) {
            throw error(
                    RiffleError.UNSUPPORTED_INLINE_COLLECTION,
                    "an empty collection is written '" + open + close + "' with no spaces, or '" + open + "' and '"
                            + close + "' on separate lines",
                    lineIndex,
                    i);
        }
        throw error(
                RiffleError.UNSUPPORTED_INLINE_COLLECTION,
                "a value starting with '" + open + "' must be quoted; to write " + collection + ", put '" + open
                        + "' at the end of the line, the " + contents + " on the following lines, and '" + close
                        + "' on its own line",
                lineIndex,
                i);
    }

    /** Requires that an empty collection is the whole value; other text means an unquoted value. */
    private void afterEmptyCollection(String line, int from, int lineIndex, String empty, String kind) {
        int i = Syntax.firstNonWhitespace(line, from);
        if (i < line.length()) {
            throw error(
                    RiffleError.TRAILING_TOKENS,
                    "a value starting with '" + empty.charAt(0) + "' must be quoted; an empty " + kind + " is written '"
                            + empty + "' alone after the key",
                    lineIndex,
                    i);
        }
    }

    /** Requires that only whitespace follows {@code from} on its line. */
    private void onlyWhitespace(String line, int from, int lineIndex) {
        int i = Syntax.firstNonWhitespace(line, from);
        if (i < line.length()) {
            throw error(RiffleError.TRAILING_TOKENS, "unexpected text after the value", lineIndex, i);
        }
    }

    // ---------------------------------------------------------------- maps

    private RiffleObject readMap(RiffleLocation opener, String path, int depth, boolean root) {
        LinkedHashMap<String, RiffleValue> entries = new LinkedHashMap<>();
        for (int index = nextContentLine(); index >= 0; index = nextContentLine()) {
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            char first = raw.charAt(start);
            if (first == '}') {
                onlyWhitespace(raw, start + 1, index);
                if (root) {
                    throw error(RiffleError.UNEXPECTED_CLOSER, "unexpected '}'", index, start);
                }
                return new RiffleObject(entries, path, opener);
            }
            if (first == ']') {
                throw error(RiffleError.MISMATCHED_CLOSER, "expected '}' but found ']'", index, start);
            }

            int pos = start;
            String key;
            if (first == '"') {
                Quoted quoted = quotedKey(raw, pos, index);
                key = quoted.text;
                pos = Syntax.firstNonWhitespace(raw, quoted.end);
                if (pos >= raw.length() || raw.charAt(pos) != ':') {
                    throw error(
                            RiffleError.MISSING_COLON,
                            "expected ':' after the key",
                            index,
                            Math.min(pos, raw.length()));
                }
                pos++;
            } else {
                int colon = raw.indexOf(':', pos);
                if (colon < 0) {
                    throw error(RiffleError.MISSING_COLON, "expected 'key : value'", index, start);
                }
                String candidate = Syntax.trim(raw.substring(pos, colon));
                if (!Syntax.isBareKey(candidate)) {
                    throw error(RiffleError.INVALID_KEY, "invalid key; quote it", index, pos);
                }
                key = candidate;
                pos = colon + 1;
            }
            if (entries.containsKey(key)) {
                throw error(RiffleError.DUPLICATE_KEY, "duplicate key", index, start);
            }

            int valueStart = Syntax.firstNonWhitespace(raw, pos);
            String memberPath = Syntax.memberPath(path, key);
            RiffleValue value = valueStart >= raw.length()
                    ? new RiffleString("", memberPath, at(index, pos))
                    : value(raw, valueStart, index, memberPath, depth);
            entries.put(key, value);
        }
        if (!root) {
            throw new RiffleParseException(RiffleError.UNCLOSED_COLLECTION, "unclosed map; '}' expected", opener);
        }
        return new RiffleObject(entries, path, opener);
    }

    // ---------------------------------------------------------------- arrays

    private RiffleArray readArray(RiffleLocation opener, String path, int depth) {
        List<RiffleValue> values = new ArrayList<>();
        for (int index = nextContentLine(); index >= 0; index = nextContentLine()) {
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            char first = raw.charAt(start);
            if (first == ']') {
                onlyWhitespace(raw, start + 1, index);
                return new RiffleArray(values, path, opener);
            }
            if (first == '}') {
                throw error(RiffleError.MISMATCHED_CLOSER, "expected ']' but found '}'", index, start);
            }
            values.add(value(raw, start, index, Syntax.elementPath(path, values.size()), depth));
        }
        throw new RiffleParseException(RiffleError.UNCLOSED_COLLECTION, "unclosed array; ']' expected", opener);
    }

    // ---------------------------------------------------------------- values

    private RiffleValue value(String line, int from, int lineIndex, String path, int depth) {
        char first = line.charAt(from);
        RiffleLocation location = at(lineIndex, from);
        switch (first) {
            case '"' -> {
                if (line.substring(from, Syntax.trimEnd(line, from)).equals(Syntax.RAW_BLOCK_DELIMITER)) {
                    return rawBlock(lineIndex, from, path, location);
                }
                Quoted quoted = quotedValue(line, from, lineIndex);
                onlyWhitespace(line, quoted.end, lineIndex);
                return new RiffleString(quoted.text, path, location);
            }
            case '@' ->
                throw error(RiffleError.INVALID_VALUE, "a value starting with '@' must be quoted", lineIndex, from);
            case '{' -> {
                if (from + 1 < line.length() && line.charAt(from + 1) == '}') {
                    afterEmptyCollection(line, from + 2, lineIndex, "{}", "map");
                    return new RiffleObject(new LinkedHashMap<>(), path, location);
                }
                openerLast(line, from + 1, lineIndex, '{', '}', "a map", "entries");
                checkDepth(depth + 1, location);
                return readMap(location, path, depth + 1, false);
            }
            case '[' -> {
                if (from + 1 < line.length() && line.charAt(from + 1) == ']') {
                    afterEmptyCollection(line, from + 2, lineIndex, "[]", "array");
                    return new RiffleArray(new ArrayList<>(), path, location);
                }
                openerLast(line, from + 1, lineIndex, '[', ']', "an array", "elements");
                checkDepth(depth + 1, location);
                return readArray(location, path, depth + 1);
            }
            case '}', ']' ->
                throw error(
                        RiffleError.INVALID_VALUE,
                        "a bare value cannot start with '" + first + "'; quote it",
                        lineIndex,
                        from);
            default -> {
                return bareString(line, from, lineIndex, path, location);
            }
        }
    }

    /**
     * Reads a raw block whose opener has just been read (LANGUAGE_SPEC.md §5.3). The closer is found
     * first, because its indentation decides how every content line is read.
     */
    private RiffleString rawBlock(int openerLine, int openerColumn, String path, RiffleLocation location) {
        int closer = next;
        while (closer < lines.size() && !Syntax.isRawBlockDelimiter(lines.get(closer))) {
            closer++;
        }
        if (closer >= lines.size()) {
            throw error(
                    RiffleError.UNTERMINATED_STRING,
                    "unterminated multiline string; a line containing only '"
                            + Syntax.RAW_BLOCK_DELIMITER
                            + "' is expected",
                    openerLine,
                    openerColumn);
        }
        String closerLine = lines.get(closer);
        String indent = closerLine.substring(0, Syntax.firstNonWhitespace(closerLine, 0));
        StringBuilder text = new StringBuilder();
        for (int index = next; index < closer; index++) {
            if (index > next) {
                text.append('\n');
            }
            text.append(rawContent(lines.get(index), indent, index));
        }
        next = closer + 1;
        return new RiffleString(text.toString(), path, location);
    }

    /** Validates one raw-block content line and removes the closer's indentation from it. */
    private String rawContent(String line, String indent, int lineIndex) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != '\t' && Syntax.isControl(c)) {
                throw error(RiffleError.INVALID_CHARACTER, "control character in a multiline string", lineIndex, i);
            }
        }
        if (line.startsWith(indent)) {
            return line.substring(indent.length());
        }
        if (line.length() < indent.length() && Syntax.firstNonWhitespace(line, 0) >= line.length()) {
            return "";
        }
        int column = 0;
        while (column < line.length() && line.charAt(column) == indent.charAt(column)) {
            column++;
        }
        throw error(
                RiffleError.INVALID_INDENTATION,
                "a line of a multiline string must start with the indentation of the closing '"
                        + Syntax.RAW_BLOCK_DELIMITER
                        + "'",
                lineIndex,
                column);
    }

    private RiffleString bareString(String line, int from, int lineIndex, String path, RiffleLocation location) {
        int end = Syntax.trimEnd(line, from);
        String text = line.substring(from, end);
        for (int i = 0; i < text.length(); i++) {
            if (Syntax.isControl(text.charAt(i))) {
                throw error(
                        RiffleError.INVALID_CHARACTER,
                        "control character in a bare string; quote and escape it",
                        lineIndex,
                        from + i);
            }
        }
        return new RiffleString(text, path, location);
    }

    // ---------------------------------------------------------------- quoted strings

    private record Quoted(String text, int end) {}

    /**
     * Parses a quoted key. A key ends at {@code :}, so the first unescaped quote closes it;
     * values use {@link #quotedValue}, whose delimiter is EOL-relative. Escape sequences never
     * contain a literal quote outside of {@code \"}, so skipping one character after a backslash
     * is sufficient to find the key's delimiter. Any malformed escape is then reported by
     * {@link #decodeBody} at its original position.
     */
    private Quoted quotedKey(String line, int start, int lineIndex) {
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"') {
                return new Quoted(decodeBody(line, start + 1, i, lineIndex), i + 1);
            }
            if (c == '\\') {
                if (i + 1 >= line.length()) {
                    throw error(RiffleError.INVALID_ESCAPE, "unterminated escape", lineIndex, i);
                }
                i++;
            }
            i++;
        }
        throw error(RiffleError.UNTERMINATED_STRING, "unterminated quoted string", lineIndex, start);
    }

    /**
     * Parses an EOL-delimited quoted value. The closing delimiter is the last quote on the line, so
     * interior quotes are literal data (LANGUAGE_SPEC.md §5.2). Nothing may follow the closing
     * quote but whitespace, because a comment occupies a whole line (§8.1).
     */
    private Quoted quotedValue(String line, int start, int lineIndex) {
        int closing = lastQuote(line, start + 1, line.length());
        if (closing < 0) {
            throw error(RiffleError.UNTERMINATED_STRING, "unterminated quoted string", lineIndex, start);
        }
        return new Quoted(decodeBody(line, start + 1, closing, lineIndex), closing + 1);
    }

    /** Returns the last quote in {@code [from, to)}, or {@code -1}. */
    private static int lastQuote(String line, int from, int to) {
        int index = line.lastIndexOf('"', to - 1);
        return index >= from ? index : -1;
    }

    /** Decodes the shared escape vocabulary in {@code [from, to)}; quotes in the span are literal. */
    private String decodeBody(String line, int from, int to, int lineIndex) {
        StringBuilder out = new StringBuilder();
        int i = from;
        while (i < to) {
            char c = line.charAt(i);
            if (c == '\\') {
                if (i + 1 >= to) {
                    throw error(RiffleError.INVALID_ESCAPE, "unterminated escape", lineIndex, i);
                }
                char escape = line.charAt(i + 1);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        i = unicodeEscape(line, i, to, lineIndex, out);
                        continue;
                    }
                    default -> throw error(RiffleError.INVALID_ESCAPE, "unsupported escape sequence", lineIndex, i);
                }
                i += 2;
                continue;
            }
            if (Syntax.isControl(c)) {
                throw error(
                        RiffleError.INVALID_CHARACTER, "control character in a quoted string; escape it", lineIndex, i);
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private int unicodeEscape(String line, int backslash, int to, int lineIndex, StringBuilder out) {
        int i = backslash + 2;
        int codePoint = hex4(line, i, to, lineIndex, backslash);
        i += 4;
        if (Character.isHighSurrogate((char) codePoint)) {
            if (i + 6 <= to && line.charAt(i) == '\\' && line.charAt(i + 1) == 'u') {
                int low = hex4(line, i + 2, to, lineIndex, backslash);
                if (Character.isLowSurrogate((char) low)) {
                    out.appendCodePoint(Character.toCodePoint((char) codePoint, (char) low));
                    return i + 6;
                }
            }
            throw error(RiffleError.INVALID_ESCAPE, "isolated surrogate in a \\u escape", lineIndex, backslash);
        }
        if (Character.isLowSurrogate((char) codePoint)) {
            throw error(RiffleError.INVALID_ESCAPE, "isolated surrogate in a \\u escape", lineIndex, backslash);
        }
        if (codePoint == 0) {
            throw error(RiffleError.INVALID_ESCAPE, "U+0000 is not allowed in a string", lineIndex, backslash);
        }
        out.append((char) codePoint);
        return i;
    }

    private int hex4(String line, int from, int to, int lineIndex, int reportAt) {
        if (from + 4 > to) {
            throw error(RiffleError.INVALID_ESCAPE, "expected four hexadecimal digits", lineIndex, reportAt);
        }
        int value = 0;
        for (int k = 0; k < 4; k++) {
            int digit = hexDigit(line.charAt(from + k));
            if (digit < 0) {
                throw error(RiffleError.INVALID_ESCAPE, "expected four hexadecimal digits", lineIndex, reportAt);
            }
            value = value * 16 + digit;
        }
        return value;
    }

    /**
     * Returns the value of an ASCII hexadecimal digit, or {@code -1}. Only ASCII digits are legal:
     * {@link Character#digit(char, int)} would also accept non-ASCII numerals such as U+0660.
     */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    // ---------------------------------------------------------------- helpers

    private void checkDepth(int depth, RiffleLocation location) {
        if (depth > Limits.MAX_DEPTH) {
            throw new RiffleParseException(
                    RiffleError.RESOURCE_LIMIT_EXCEEDED,
                    "maximum nesting depth of " + Limits.MAX_DEPTH + " exceeded",
                    location);
        }
    }

    private RiffleLocation at(int lineIndex, int column) {
        return new RiffleLocation(source, lineIndex + 1, column + 1);
    }

    private RiffleParseException error(RiffleError category, String reason, int lineIndex, int column) {
        return new RiffleParseException(category, reason, at(lineIndex, column));
    }
}
