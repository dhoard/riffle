package io.github.dhoard.riffle;

/** Lexical rules shared by the parser and the writer. */
final class Syntax {
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
        if (key.isEmpty()) {
            return false;
        }
        char first = key.charAt(0);
        if (!(isAsciiLetter(first) || isAsciiDigit(first) || first == '_')) {
            return false;
        }
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '_' || c == '.' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether {@code text} can be written as a bare value without changing meaning. */
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
        if (isHorizontalWhitespace(first) || isHorizontalWhitespace(text.charAt(text.length() - 1))) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isControl(c)) {
                return false;
            }
            if (c == '#' || (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/')) {
                if (i == 0 || isHorizontalWhitespace(text.charAt(i - 1))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Returns {@code text} as a quoted string with the supported escapes. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (isControl(c)) {
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

    /** Builds the path of a map member. */
    static String memberPath(String parent, String key) {
        return parent == null || parent.isEmpty() ? key : parent + "." + key;
    }

    /** Builds the path of an array element. */
    static String elementPath(String parent, int index) {
        return (parent == null ? "" : parent) + "[" + index + "]";
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
