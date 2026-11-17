package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Line-oriented parser for Riffle 0.1.0: one entry or element per line, collections on their
 * own lines, {@code @type} markers for non-string scalars. Package-private by design.
 *
 * <p>Comments are resolved at each syntactic position, so a {@code "} inside a bare value is
 * ordinary text and only a marker that follows whitespace starts a comment.</p>
 */
final class RiffleParser {
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

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

    /** Returns whether a comment marker starts at {@code index}. */
    private static boolean isCommentAt(String line, int index) {
        if (index >= line.length()) {
            return false;
        }
        char c = line.charAt(index);
        boolean marker = c == '#' || (c == '/' && index + 1 < line.length() && line.charAt(index + 1) == '/');
        return marker && (index == 0 || Syntax.isHorizontalWhitespace(line.charAt(index - 1)));
    }

    /** Returns the index where a comment starts at or after {@code from}, or the line length. */
    private static int commentStart(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            if (isCommentAt(line, i)) {
                return i;
            }
        }
        return line.length();
    }

    /** Requires that an opener is the last syntactic token on its line. */
    private void openerLast(String line, int from, int lineIndex, char open, char close, String contents) {
        int i = Syntax.firstNonWhitespace(line, from);
        if (i >= line.length() || isCommentAt(line, i)) {
            return;
        }
        if (line.charAt(i) == close && onlyCommentOrEnd(line, i + 1)) {
            throw error(
                    RiffleError.UNSUPPORTED_INLINE_COLLECTION,
                    "an empty collection is written '" + open + close + "' with no spaces, or '" + open + "' and '"
                            + close + "' on separate lines",
                    lineIndex,
                    i);
        }
        throw error(
                RiffleError.UNSUPPORTED_INLINE_COLLECTION,
                "a nonempty collection is written on separate lines: '" + open + "' ends this line, the " + contents
                        + " follow, '" + close + "' is on its own line",
                lineIndex,
                i);
    }

    /** Returns whether only an optional comment follows {@code from}. */
    private static boolean onlyCommentOrEnd(String line, int from) {
        int i = Syntax.firstNonWhitespace(line, from);
        return i >= line.length() || isCommentAt(line, i);
    }

    /** Requires that only whitespace and an optional comment follow {@code from}. */
    private void onlyComment(String line, int from, int lineIndex) {
        int i = Syntax.firstNonWhitespace(line, from);
        if (i >= line.length() || isCommentAt(line, i)) {
            return;
        }
        throw error(RiffleError.TRAILING_TOKENS, "unexpected text after the value", lineIndex, i);
    }

    // ---------------------------------------------------------------- maps

    private RiffleObject readMap(RiffleLocation opener, String path, int depth, boolean root) {
        LinkedHashMap<String, RiffleValue> entries = new LinkedHashMap<>();
        while (next < lines.size()) {
            int index = next++;
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (start >= raw.length() || isCommentAt(raw, start)) {
                continue;
            }
            char first = raw.charAt(start);
            if (first == '}') {
                onlyComment(raw, start + 1, index);
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
                Quoted quoted = quoted(raw, pos, index);
                key = quoted.text;
                pos = Syntax.firstNonWhitespace(raw, quoted.end);
                if (pos >= raw.length() || raw.charAt(pos) != '=') {
                    throw error(
                            RiffleError.MISSING_EQUALS,
                            "expected '=' after the key",
                            index,
                            Math.min(pos, raw.length()));
                }
                pos++;
            } else {
                int equals = raw.indexOf('=', pos);
                if (equals < 0) {
                    throw error(RiffleError.MISSING_EQUALS, "expected 'key = value'", index, start);
                }
                String candidate = Syntax.trim(raw.substring(pos, equals));
                if (!Syntax.isBareKey(candidate)) {
                    throw error(RiffleError.INVALID_KEY, "invalid key; quote it", index, pos);
                }
                key = candidate;
                pos = equals + 1;
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
        while (next < lines.size()) {
            int index = next++;
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (start >= raw.length() || isCommentAt(raw, start)) {
                continue;
            }
            char first = raw.charAt(start);
            if (first == ']') {
                onlyComment(raw, start + 1, index);
                return new RiffleArray(values, path, opener);
            }
            if (first == '}') {
                throw error(RiffleError.MISMATCHED_CLOSER, "expected ']' but found '}'", index, start);
            }
            values.add(value(raw, start, index, Syntax.elementPath(path, values.size()), depth));
        }
        throw new RiffleParseException(RiffleError.UNCLOSED_COLLECTION, "unclosed array; ']' expected", opener);
    }

    private RiffleArray readTypedArray(String kind, RiffleLocation opener, String path, int depth) {
        List<RiffleValue> values = new ArrayList<>();
        while (next < lines.size()) {
            int index = next++;
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (start >= raw.length() || isCommentAt(raw, start)) {
                continue;
            }
            char first = raw.charAt(start);
            if (first == ']') {
                onlyComment(raw, start + 1, index);
                return new RiffleArray(values, path, opener);
            }
            if (first == '}') {
                throw error(RiffleError.MISMATCHED_CLOSER, "expected ']' but found '}'", index, start);
            }
            values.add(element(kind, raw, start, index, Syntax.elementPath(path, values.size()), depth));
        }
        throw new RiffleParseException(RiffleError.UNCLOSED_COLLECTION, "unclosed array; ']' expected", opener);
    }

    // ---------------------------------------------------------------- values

    private RiffleValue value(String line, int from, int lineIndex, String path, int depth) {
        char first = line.charAt(from);
        RiffleLocation location = at(lineIndex, from);
        switch (first) {
            case '"' -> {
                Quoted quoted = quoted(line, from, lineIndex);
                onlyComment(line, quoted.end, lineIndex);
                return new RiffleString(quoted.text, path, location);
            }
            case '@' -> {
                return typed(line, from, lineIndex, path, depth);
            }
            case '{' -> {
                if (from + 1 < line.length() && line.charAt(from + 1) == '}') {
                    onlyComment(line, from + 2, lineIndex);
                    return new RiffleObject(new LinkedHashMap<>(), path, location);
                }
                openerLast(line, from + 1, lineIndex, '{', '}', "entries");
                checkDepth(depth + 1, location);
                return readMap(location, path, depth + 1, false);
            }
            case '[' -> {
                if (from + 1 < line.length() && line.charAt(from + 1) == ']') {
                    onlyComment(line, from + 2, lineIndex);
                    return new RiffleArray(new ArrayList<>(), path, location);
                }
                openerLast(line, from + 1, lineIndex, '[', ']', "elements");
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

    private RiffleValue typed(String line, int from, int lineIndex, String path, int depth) {
        int wordStart = from + 1;
        int wordEnd = wordStart;
        while (wordEnd < line.length() && !Syntax.isHorizontalWhitespace(line.charAt(wordEnd))) {
            wordEnd++;
        }
        String word = line.substring(wordStart, Math.min(wordEnd, line.length()));
        RiffleLocation location = at(lineIndex, from);
        if (word.isEmpty()) {
            throw error(RiffleError.INVALID_TYPED_VALUE, "expected a type name after '@'", lineIndex, from);
        }
        int afterWord = Syntax.firstNonWhitespace(line, wordEnd);
        boolean bracketed = afterWord < line.length() && line.charAt(afterWord) == '[';
        if (bracketed) {
            if (!isElementType(word)) {
                throw error(
                        RiffleError.UNKNOWN_TYPE, "'@" + word + "' cannot open a typed array", lineIndex, wordStart);
            }
            if (afterWord + 1 < line.length() && line.charAt(afterWord + 1) == ']') {
                onlyComment(line, afterWord + 2, lineIndex);
                return new RiffleArray(new ArrayList<>(), path, location);
            }
            openerLast(line, afterWord + 1, lineIndex, '[', ']', "elements");
            checkDepth(depth + 1, location);
            return readTypedArray(word, location, path, depth + 1);
        }
        switch (word) {
            case "null" -> {
                if (afterWord < line.length() && !isCommentAt(line, afterWord)) {
                    throw error(RiffleError.INVALID_TYPED_VALUE, "@null takes no payload", lineIndex, afterWord);
                }
                return new RiffleNull(path, location);
            }
            case "string" -> {
                if (afterWord >= line.length() || isCommentAt(line, afterWord)) {
                    throw error(
                            RiffleError.MISSING_VALUE,
                            "expected a payload after '@string'",
                            lineIndex,
                            Math.min(afterWord, Math.max(line.length() - 1, 0)));
                }
                if (line.charAt(afterWord) == '"') {
                    Quoted quoted = quoted(line, afterWord, lineIndex);
                    onlyComment(line, quoted.end, lineIndex);
                    return new RiffleString(quoted.text, path, location);
                }
                return bareString(line, afterWord, lineIndex, path, location);
            }
            case "int", "long", "decimal", "boolean" -> {
                int payloadEnd = Syntax.trimEnd(line.substring(0, commentStart(line, afterWord)), afterWord);
                if (afterWord >= payloadEnd) {
                    throw error(
                            RiffleError.MISSING_VALUE,
                            "expected a payload after '@" + word + "'",
                            lineIndex,
                            afterWord);
                }
                return scalar(word, line.substring(afterWord, payloadEnd), lineIndex, afterWord, path, location);
            }
            case "map", "array" ->
                throw error(
                        RiffleError.INVALID_TYPED_VALUE, "'@" + word + "' is only allowed before '['", lineIndex, from);
            default -> throw error(RiffleError.UNKNOWN_TYPE, "'@" + word + "' is not a type", lineIndex, wordStart);
        }
    }

    private RiffleValue element(String kind, String line, int from, int lineIndex, String path, int depth) {
        RiffleLocation location = at(lineIndex, from);
        char first = line.charAt(from);
        switch (kind) {
            case "null" -> {
                String text = Syntax.trim(line.substring(from, commentStart(line, from)));
                if (!text.equals("null")) {
                    throw error(
                            RiffleError.INVALID_ARRAY_ELEMENT_TYPE,
                            "@null array elements are written null",
                            lineIndex,
                            from);
                }
                return new RiffleNull(path, location);
            }
            case "string" -> {
                if (first == '"') {
                    Quoted quoted = quoted(line, from, lineIndex);
                    onlyComment(line, quoted.end, lineIndex);
                    return new RiffleString(quoted.text, path, location);
                }
                if (first == '@' || first == '{' || first == '[' || first == '}' || first == ']') {
                    throw error(
                            RiffleError.INVALID_ARRAY_ELEMENT_TYPE,
                            "@string array elements are bare or quoted text",
                            lineIndex,
                            from);
                }
                return bareString(line, from, lineIndex, path, location);
            }
            case "int", "long", "decimal", "boolean" -> {
                if (first == '@' || first == '"' || first == '{' || first == '[' || first == '}' || first == ']') {
                    throw error(
                            RiffleError.INVALID_ARRAY_ELEMENT_TYPE,
                            "@" + kind + " array elements are written without '@" + kind + "'",
                            lineIndex,
                            from);
                }
                int payloadEnd = Syntax.trimEnd(line.substring(0, commentStart(line, from)), from);
                try {
                    return scalar(kind, line.substring(from, payloadEnd), lineIndex, from, path, location);
                } catch (RiffleParseException e) {
                    if (e.category() == RiffleError.INVALID_TYPED_VALUE) {
                        throw new RiffleParseException(
                                RiffleError.INVALID_ARRAY_ELEMENT_TYPE, e.reason(), e.location());
                    }
                    throw e;
                }
            }
            case "map" -> {
                if (from + 1 < line.length() && line.charAt(from + 1) == '}') {
                    onlyComment(line, from + 2, lineIndex);
                    return new RiffleObject(new LinkedHashMap<>(), path, location);
                }
                if (first == '{') {
                    openerLast(line, from + 1, lineIndex, '{', '}', "entries");
                    checkDepth(depth + 1, location);
                    return readMap(location, path, depth + 1, false);
                }
                throw error(RiffleError.INVALID_ARRAY_ELEMENT_TYPE, "expected a map element", lineIndex, from);
            }
            case "array" -> {
                if (from + 1 < line.length() && line.charAt(from + 1) == ']') {
                    onlyComment(line, from + 2, lineIndex);
                    return new RiffleArray(new ArrayList<>(), path, location);
                }
                if (first == '[') {
                    openerLast(line, from + 1, lineIndex, '[', ']', "elements");
                    checkDepth(depth + 1, location);
                    return readArray(location, path, depth + 1);
                }
                if (first == '@') {
                    int wordStart = from + 1;
                    int wordEnd = wordStart;
                    while (wordEnd < line.length() && !Syntax.isHorizontalWhitespace(line.charAt(wordEnd))) {
                        wordEnd++;
                    }
                    String word = line.substring(wordStart, Math.min(wordEnd, line.length()));
                    int afterWord = Syntax.firstNonWhitespace(line, wordEnd);
                    if (!isElementType(word) || afterWord >= line.length() || line.charAt(afterWord) != '[') {
                        throw error(
                                RiffleError.INVALID_ARRAY_ELEMENT_TYPE, "expected an array element", lineIndex, from);
                    }
                    return typed(line, from, lineIndex, path, depth);
                }
                throw error(RiffleError.INVALID_ARRAY_ELEMENT_TYPE, "expected an array element", lineIndex, from);
            }
            default -> throw new IllegalStateException("unknown element type " + kind);
        }
    }

    private RiffleValue scalar(
            String word, String payload, int lineIndex, int column, String path, RiffleLocation location) {
        if (payload.startsWith("\"")) {
            throw error(
                    RiffleError.INVALID_TYPED_VALUE,
                    "'@" + word + "' does not accept a quoted payload",
                    lineIndex,
                    column);
        }
        switch (word) {
            case "int" -> {
                if (!INTEGER.matcher(payload).matches()) {
                    throw error(RiffleError.INVALID_TYPED_VALUE, "expected an integer", lineIndex, column);
                }
                try {
                    return new RiffleInt(Integer.parseInt(payload), path, location);
                } catch (NumberFormatException e) {
                    throw error(RiffleError.NUMBER_OUT_OF_RANGE, "int is out of range", lineIndex, column);
                }
            }
            case "long" -> {
                if (!INTEGER.matcher(payload).matches()) {
                    throw error(RiffleError.INVALID_TYPED_VALUE, "expected an integer", lineIndex, column);
                }
                try {
                    return new RiffleLong(Long.parseLong(payload), path, location);
                } catch (NumberFormatException e) {
                    throw error(RiffleError.NUMBER_OUT_OF_RANGE, "long is out of range", lineIndex, column);
                }
            }
            case "decimal" -> {
                if (!DECIMAL.matcher(payload).matches()) {
                    throw error(RiffleError.INVALID_TYPED_VALUE, "expected a decimal number", lineIndex, column);
                }
                return new RiffleDecimal(decimal(payload, lineIndex, column), path, location);
            }
            case "boolean" -> {
                if (payload.equals("true")) {
                    return new RiffleBoolean(true, path, location);
                }
                if (payload.equals("false")) {
                    return new RiffleBoolean(false, path, location);
                }
                throw error(RiffleError.INVALID_TYPED_VALUE, "expected true or false", lineIndex, column);
            }
            default -> throw new IllegalStateException("unknown scalar type " + word);
        }
    }

    /**
     * Parses a decimal payload. A literal whose value or normalized scale is outside
     * {@link BigDecimal}'s exponent range cannot be represented, so it is reported as a range
     * error rather than as the runtime exception the JDK constructors raise.
     */
    private BigDecimal decimal(String payload, int lineIndex, int column) {
        try {
            return RiffleDecimal.normalize(new BigDecimal(payload));
        } catch (NumberFormatException | ArithmeticException e) {
            throw error(
                    RiffleError.NUMBER_OUT_OF_RANGE,
                    "decimal is outside the supported exponent range",
                    lineIndex,
                    column);
        }
    }

    private RiffleString bareString(String line, int from, int lineIndex, String path, RiffleLocation location) {
        int end = Syntax.trimEnd(line.substring(0, commentStart(line, from)), from);
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

    private static boolean isElementType(String word) {
        return switch (word) {
            case "string", "int", "long", "decimal", "boolean", "null", "map", "array" -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- quoted strings

    private record Quoted(String text, int end) {}

    private Quoted quoted(String line, int start, int lineIndex) {
        StringBuilder out = new StringBuilder();
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"') {
                return new Quoted(out.toString(), i + 1);
            }
            if (c == '\\') {
                if (i + 1 >= line.length()) {
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
                        i = unicodeEscape(line, i, lineIndex, out);
                        continue;
                    }
                    default ->
                        throw error(RiffleError.INVALID_ESCAPE, "invalid escape '\\" + escape + "'", lineIndex, i);
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
        throw error(RiffleError.UNTERMINATED_STRING, "unterminated quoted string", lineIndex, start);
    }

    private int unicodeEscape(String line, int backslash, int lineIndex, StringBuilder out) {
        int i = backslash + 2;
        int codePoint = hex4(line, i, lineIndex, backslash);
        i += 4;
        if (Character.isHighSurrogate((char) codePoint)) {
            if (i + 6 <= line.length() && line.charAt(i) == '\\' && line.charAt(i + 1) == 'u') {
                int low = hex4(line, i + 2, lineIndex, backslash);
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
        out.append((char) codePoint);
        return i;
    }

    private int hex4(String line, int from, int lineIndex, int reportAt) {
        if (from + 4 > line.length()) {
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
