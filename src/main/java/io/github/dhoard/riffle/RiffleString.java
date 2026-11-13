package io.github.dhoard.riffle;

import java.util.Objects;
import java.util.Optional;

/** An immutable string value, written bare, quoted, or with {@code @string}. */
public final class RiffleString implements RiffleValue {
    private final String text;
    private final String path;
    private final RiffleLocation location;

    RiffleString(String text, String path, RiffleLocation location) {
        this.text = text;
        this.path = path;
        this.location = location;
    }

    /**
     * Creates a string value.
     *
     * @param text the text; any Unicode text, including control characters, which are escaped
     *     when the value is written
     * @return the value
     * @throws IllegalArgumentException if the text contains an unpaired surrogate
     */
    public static RiffleString of(String text) {
        Objects.requireNonNull(text, "text");
        if (Syntax.hasUnpairedSurrogate(text)) {
            throw new IllegalArgumentException("string contains an unpaired surrogate");
        }
        return new RiffleString(text, null, null);
    }

    /**
     * Returns the text.
     *
     * @return the text
     */
    String text() {
        return text;
    }

    @Override
    public Optional<RiffleLocation> location() {
        return Optional.ofNullable(location);
    }

    @Override
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /** Strings are equal when their text is equal; locations and paths are ignored. */
    @Override
    public boolean equals(Object other) {
        return other instanceof RiffleString that && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return "RiffleString[" + text + "]";
    }
}
