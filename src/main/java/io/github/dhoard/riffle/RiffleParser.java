package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Line-oriented parser for Riffle 0.1.0: one entry or element per line, collections on their
 * own lines, {@code @type} markers for non-string scalars. Package-private by design.
 *
 * <p>Comments occupy whole lines: a line whose first non-whitespace character is {@code #} is a
 * line comment, and a block-comment region is removed before parsing starts. A {@code #} after a
 * value is therefore part of that value, so no key, value, opener, or closer can contain a comment
 * (LANGUAGE_SPEC.md §8).</p>
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
        stripBlockComments();
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
     * Removes block-comment regions (LANGUAGE_SPEC.md §8). A line whose first non-whitespace
     * characters are {@code /#} opens a region, and anything after them on that line is comment
     * text. A line whose first non-whitespace characters are {@code #/} and whose remaining
     * characters are only spaces or tabs closes one nesting level. Every other line of a region is
     * ignored, including lines that look like entries, delimiters, or invalid Riffle.
     *
     * <p>Region lines are replaced by empty lines rather than removed, so that every later
     * diagnostic still reports the source's own line.</p>
     */
    private void stripBlockComments() {
        int depth = 0;
        int openerLine = -1;
        int openerColumn = -1;
        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (isBlockCommentOpener(raw, start)) {
                if (depth == 0) {
                    openerLine = index;
                    openerColumn = start;
                }
                depth++;
                lines.set(index, "");
                continue;
            }
            if (depth == 0) {
                continue;
            }
            if (isBlockCommentCloser(raw, start)) {
                depth--;
            }
            lines.set(index, "");
        }
        if (depth > 0) {
            throw error(
                    RiffleError.UNCLOSED_COMMENT,
                    "unclosed block comment; a line containing only '#/' is expected",
                    openerLine,
                    openerColumn);
        }
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

    /**
     * Returns whether {@code line} is a comment line: its first non-whitespace character is
     * {@code #}. A comment occupies a whole line, so a {@code #} after a value is part of that
     * value (LANGUAGE_SPEC.md §8.1).
     */
    private static boolean isCommentLine(String line) {
        int start = Syntax.firstNonWhitespace(line, 0);
        return start < line.length() && line.charAt(start) == '#';
    }

    /** Requires that an opener is the last syntactic token on its line. */
    private void openerLast(String line, int from, int lineIndex, char open, char close, String contents) {
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
                "a nonempty collection is written on separate lines: '" + open + "' ends this line, the " + contents
                        + " follow, '" + close + "' is on its own line",
                lineIndex,
                i);
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
        while (next < lines.size()) {
            int index = next++;
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (start >= raw.length() || isCommentLine(raw)) {
                continue;
            }
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
        while (next < lines.size()) {
            int index = next++;
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (start >= raw.length() || isCommentLine(raw)) {
                continue;
            }
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

    private RiffleArray readTypedArray(String kind, RiffleLocation opener, String path, int depth) {
        List<RiffleValue> values = new ArrayList<>();
        while (next < lines.size()) {
            int index = next++;
            String raw = lines.get(index);
            int start = Syntax.firstNonWhitespace(raw, 0);
            if (start >= raw.length() || isCommentLine(raw)) {
                continue;
            }
            char first = raw.charAt(start);
            if (first == ']') {
                onlyWhitespace(raw, start + 1, index);
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
                Quoted quoted = quotedValue(line, from, lineIndex);
                onlyWhitespace(line, quoted.end, lineIndex);
                return new RiffleString(quoted.text, path, location);
            }
            case '@' -> {
                return typed(line, from, lineIndex, path, depth);
            }
            case '{' -> {
                if (from + 1 < line.length() && line.charAt(from + 1) == '}') {
                    onlyWhitespace(line, from + 2, lineIndex);
                    return new RiffleObject(new LinkedHashMap<>(), path, location);
                }
                openerLast(line, from + 1, lineIndex, '{', '}', "entries");
                checkDepth(depth + 1, location);
                return readMap(location, path, depth + 1, false);
            }
            case '[' -> {
                if (from + 1 < line.length() && line.charAt(from + 1) == ']') {
                    onlyWhitespace(line, from + 2, lineIndex);
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
                onlyWhitespace(line, afterWord + 2, lineIndex);
                return new RiffleArray(new ArrayList<>(), path, location);
            }
            openerLast(line, afterWord + 1, lineIndex, '[', ']', "elements");
            checkDepth(depth + 1, location);
            return readTypedArray(word, location, path, depth + 1);
        }
        switch (word) {
            case "null" -> {
                if (afterWord < line.length()) {
                    throw error(RiffleError.INVALID_TYPED_VALUE, "@null takes no payload", lineIndex, afterWord);
                }
                return new RiffleNull(path, location);
            }
            case "string" -> {
                if (afterWord >= line.length()) {
                    throw error(
                            RiffleError.MISSING_VALUE,
                            "expected a payload after '@string'",
                            lineIndex,
                            Math.min(afterWord, Math.max(line.length() - 1, 0)));
                }
                if (line.charAt(afterWord) == '"') {
                    Quoted quoted = quotedValue(line, afterWord, lineIndex);
                    onlyWhitespace(line, quoted.end, lineIndex);
                    return new RiffleString(quoted.text, path, location);
                }
                return bareString(line, afterWord, lineIndex, path, location);
            }
            case "int", "long", "decimal", "boolean" -> {
                int payloadEnd = Syntax.trimEnd(line, afterWord);
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
                String text = line.substring(from, Syntax.trimEnd(line, from));
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
                    Quoted quoted = quotedValue(line, from, lineIndex);
                    onlyWhitespace(line, quoted.end, lineIndex);
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
                int payloadEnd = Syntax.trimEnd(line, from);
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
                    onlyWhitespace(line, from + 2, lineIndex);
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
                    onlyWhitespace(line, from + 2, lineIndex);
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
            return RiffleDecimal.normalize(parseDecimal(payload));
        } catch (NumberFormatException | ArithmeticException e) {
            throw error(
                    RiffleError.NUMBER_OUT_OF_RANGE,
                    "decimal is outside the supported exponent range",
                    lineIndex,
                    column);
        }
    }

    /**
     * Parses a validated decimal token. Java 17's {@code BigDecimal(String)} rejects exponent
     * {@code 2147483648} even though the equivalent scale {@code Integer.MIN_VALUE} is legal and
     * the canonical writer emits that exponent for {@code 10e2147483647}. On that failure, rebuild
     * the value from its unscaled digits and scale; genuinely out-of-range scales still fail.
     */
    private static BigDecimal parseDecimal(String payload) {
        try {
            return new BigDecimal(payload);
        } catch (NumberFormatException original) {
            int exponentAt = payload.indexOf('e');
            if (exponentAt < 0) {
                exponentAt = payload.indexOf('E');
            }
            if (exponentAt < 0) {
                throw original;
            }
            long exponent;
            try {
                exponent = Long.parseLong(payload.substring(exponentAt + 1));
            } catch (NumberFormatException e) {
                throw original;
            }
            String mantissa = payload.substring(0, exponentAt);
            int dot = mantissa.indexOf('.');
            int fractionDigits = dot < 0 ? 0 : mantissa.length() - dot - 1;
            long scale = fractionDigits - exponent;
            if (scale < Integer.MIN_VALUE || scale > Integer.MAX_VALUE) {
                throw original;
            }
            String digits = dot < 0 ? mantissa : mantissa.substring(0, dot) + mantissa.substring(dot + 1);
            return new BigDecimal(new BigInteger(digits), (int) scale);
        }
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

    private static boolean isElementType(String word) {
        return switch (word) {
            case "string", "int", "long", "decimal", "boolean", "null", "map", "array" -> true;
            default -> false;
        };
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
