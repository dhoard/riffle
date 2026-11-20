package io.github.dhoard.riffle;

/** Stable error categories reported by {@link RiffleParseException}. */
public enum RiffleError {
    /** The input is not valid UTF-8. */
    INVALID_UTF8,
    /** A carriage return was not followed by a line feed. */
    INVALID_LINE_ENDING,
    /** A control character or a stray byte-order mark appeared where it is not allowed. */
    INVALID_CHARACTER,
    /** A quoted string contains an invalid escape sequence. */
    INVALID_ESCAPE,
    /** A quoted string is not closed on its line, or a multiline string is never closed. */
    UNTERMINATED_STRING,
    /** A multiline string line does not start with the indentation of the closing delimiter. */
    INVALID_INDENTATION,
    /** A map key is empty or holds a character outside the key alphabet of LANGUAGE_SPEC.md §6.2. */
    INVALID_KEY,
    /** A map entry has no {@code :}, or a key is not followed by one. */
    MISSING_COLON,
    /** Characters remain after a complete value. */
    TRAILING_TOKENS,
    /** A value starts with a character that is reserved at that position. */
    INVALID_VALUE,
    /** The same key appears twice in one map. */
    DUPLICATE_KEY,
    /** A closing delimiter has no open collection. */
    UNEXPECTED_CLOSER,
    /** A closing delimiter does not match the open collection. */
    MISMATCHED_CLOSER,
    /** The document ended inside a collection. */
    UNCLOSED_COLLECTION,
    /** The document ended inside a block comment. */
    UNCLOSED_COMMENT,
    /** A nonempty collection was written inline. */
    UNSUPPORTED_INLINE_COLLECTION,
    /** A documented resource limit, such as nesting depth, was exceeded. */
    RESOURCE_LIMIT_EXCEEDED
}
