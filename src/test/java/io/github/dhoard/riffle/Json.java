package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small test-only JSON reader for the conformance manifest. It keeps the conformance suite
 * dependency-free; it is not part of the Java API.
 */
final class Json {
    private final String source;
    private int at;

    private Json(String source) {
        this.source = source;
    }

    static Object parse(String source) {
        Json parser = new Json(source);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.at != parser.source.length()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (at >= source.length()) {
            throw error("expected a value");
        }
        char c = source.charAt(at);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw error("unexpected character");
            }
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            return result;
        }
        while (true) {
            skipWhitespace();
            if (at >= source.length() || source.charAt(at) != '"') {
                throw error("expected an object key");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            if (result.containsKey(key)) {
                throw error("duplicate object key");
            }
            result.put(key, value);
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (at < source.length()) {
            char c = source.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("control character in a string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= source.length()) {
                throw error("unterminated escape");
            }
            char escape = source.charAt(at++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(readUnicodeEscape());
                default -> throw error("invalid escape");
            }
        }
        throw error("unterminated string");
    }

    private char readUnicodeEscape() {
        if (at + 4 > source.length()) {
            throw error("short unicode escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(source.charAt(at++), 16);
            if (digit < 0) {
                throw error("invalid unicode escape");
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private BigDecimal readNumber() {
        int start = at;
        if (consume('-')) {
            // Sign consumed.
        }
        requireDigits();
        if (consume('.')) {
            requireDigits();
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) {
                consume('-');
            }
            requireDigits();
        }
        try {
            return new BigDecimal(source.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("invalid number");
        }
    }

    private void requireDigits() {
        int start = at;
        while (at < source.length() && source.charAt(at) >= '0' && source.charAt(at) <= '9') {
            at++;
        }
        if (start == at) {
            throw error("expected a digit");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!source.startsWith(literal, at)) {
            throw error("invalid literal");
        }
        at += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (at < source.length()) {
            char c = source.charAt(at);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                at++;
            } else {
                return;
            }
        }
    }

    private boolean consume(char expected) {
        if (at < source.length() && source.charAt(at) == expected) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private IllegalArgumentException error(String reason) {
        return new IllegalArgumentException("manifest JSON at offset " + at + ": " + reason);
    }
}
