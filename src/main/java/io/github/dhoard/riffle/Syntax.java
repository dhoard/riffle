package io.github.dhoard.riffle;

/** Lexical rules shared by the parser and the writer. */
final class Syntax {
    /**
     * The byte-order mark. It is legal only as the document's first character, so it is escaped
     * wherever the writer would otherwise emit it inside a value or key.
     */
    static final char BYTE_ORDER_MARK = '\uFEFF';

    /** Opens and closes a multiline raw block; it is always alone on its line. */
    static final String RAW_BLOCK_DELIMITER = "\"\"\"";

    private Syntax() {}

    /** Riffle horizontal whitespace is space and tab. */
    static boolean isHorizontalWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    /** Control characters are invalid in bare and quoted strings. */
    static boolean isControl(char c) {
        return c < 0x20 || c == 0x7F;
    }

    /** Returns the index of the first non-whitespace character, or {@code length}. */
    static int firstNonWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && isHorizontalWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Returns the end index of {@code s} after removing trailing whitespace. */
    static int trimEnd(String s, int from) {
        int end = s.length();
        while (end > from && isHorizontalWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    /** Removes leading and trailing horizontal whitespace. */
    static String trim(String s) {
        int start = firstNonWhitespace(s, 0);
        return s.substring(start, trimEnd(s, start));
    }

    /** Returns whether {@code key} can be written without quotes. */
    static boolean isBareKey(String key) {
        return isBare(key, true);
    }

    /**
     * Returns whether {@code key} can be written without quotes inside a path (LANGUAGE_SPEC.md
     * §10.1). A dot is the path separator there, so a key holding a dot is always quoted even
     * though a map key may hold one.
     */
    static boolean isBarePathKey(String key) {
        return isBare(key, false);
    }

    private static boolean isBare(String key, boolean dotted) {
        if (key.isEmpty()) {
            return false;
        }
        char first = key.charAt(0);
        if (!(isAsciiLetter(first) || isAsciiDigit(first) || first == '_')) {
            return false;
        }
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '_' || c == '-' || (dotted && c == '.'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether {@code text} can be written as a bare value without changing meaning. A
     * byte-order mark cannot: the parser accepts one only at the start of a document.
     */
    static boolean isBareValue(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        switch (first) {
            case '@', '{', '[', '}', ']', '"' -> {
                return false;
            }
            default -> {}
        }
        // A string that begins with `#` would be a comment line, and one that begins with `/#`
        // would open a block comment, when it is written at the start of a line, which is how every
        // array element is written (LANGUAGE_SPEC.md §8).
        if (first == '#' || text.startsWith("/#")) {
            return false;
        }
        if (isHorizontalWhitespace(first) || isHorizontalWhitespace(text.charAt(text.length() - 1))) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isControl(c) || c == BYTE_ORDER_MARK) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether {@code line} holds only the raw-block delimiter and horizontal whitespace. */
    static boolean isRawBlockDelimiter(String line) {
        return trim(line).equals(RAW_BLOCK_DELIMITER);
    }

    /**
     * Returns whether {@code text} is written as a raw block: it spans lines, and every character
     * and line can appear literally inside one (LANGUAGE_SPEC.md §11.2).
     */
    static boolean isRawBlockText(String text) {
        if (text.indexOf('\n') < 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\t' && (isControl(c) || c == BYTE_ORDER_MARK)) {
                return false;
            }
        }
        for (String line : text.split("\n", -1)) {
            if (isRawBlockDelimiter(line)) {
                return false;
            }
        }
        return true;
    }

    /** Returns {@code text} as a quoted map key; keys still close at the first unescaped quote. */
    static String quoteKey(String text) {
        return quote(text, true);
    }

    /**
     * Returns {@code text} as an EOL-delimited quoted value with literal interior quotes. The
     * one-character string {@code "} escapes its quote, because {@code """} opens a raw block.
     */
    static String quoteValue(String text) {
        return text.equals("\"") ? "\"\\\"\"" : quote(text, false);
    }

    /**
     * Returns {@code text} as a quoted string with the supported escapes. Control characters and
     * byte-order marks are escaped, because neither may appear literally in a quoted string. A
     * value's interior quotes stay literal because the closing delimiter is the last quote on the
     * line; a quoted key escapes them because a key ends at the first unescaped quote
     * (LANGUAGE_SPEC.md §5.2).
     */
    private static String quote(String text, boolean key) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append(key ? "\\\"" : "\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (isControl(c) || c == BYTE_ORDER_MARK) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Returns whether {@code text} contains an unpaired surrogate. */
    static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
