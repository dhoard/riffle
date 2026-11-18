package io.github.dhoard.riffle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Generates values that exercise every kind, quoting rule and multiline spelling. */
final class RiffleRandom {
    private static final String[] PIECES = {
        "a", "b", "Z", "0", " ", "\t", "\"", "#", "/", "//", "|", "!", "@", ":", "=", "{", "}", "[", "]", "\n", "é",
        "💡", "null", "true", "int", "\\", ";", ",", "\uFEFF", "\u0001", "\u001f", "\u007F", "~", "\"\"\"", "/#", "#/",
        "    ", "\n\n", "\r"
    };
    private static final String[] SPECIAL = {
        "",
        " ",
        "null",
        "true",
        "123",
        "~",
        "@reserved",
        "@int 1",
        "a # b",
        "a // b",
        "#x",
        "//x",
        "{",
        "}",
        "[",
        "]",
        "\"",
        "\\",
        "\n",
        "a\nb",
        "\tb",
        "é",
        "💡",
        "\uFEFF",
        "\uFEFFx",
        "x\uFEFFy",
        "\u0001",
        "\u007F",
        "a\n",
        "\na",
        "\n\n",
        "a\n\nb",
        "  a\n\tb  \n   \n",
        "# c\n// d\n/#\n#/",
        "\"\"\"\n",
        "x\n  \"\"\"  \ny",
        "x\n\"\"\"y\n\"\"\" y",
        "a\r\nb",
        "a\n\u0001",
        "a\n\uFEFF",
        "https://x:1/y?a=b#c"
    };
    private static final String[] KEY_PIECES = {"a", "b", "Z", "0", ".", "-", "_", " ", "=", "@", "é", "\n", "\uFEFF"};

    private final Random random;
    private boolean singleLine;

    RiffleRandom(Random random) {
        this.random = random;
    }

    RiffleObject object() {
        return objectValue(3);
    }

    /** A tree with no raw blocks in its canonical form, so every line can be reindented freely. */
    RiffleObject singleLineObject() {
        singleLine = true;
        return objectValue(3);
    }

    private RiffleValue value(int depth) {
        int choice = random.nextInt(depth <= 0 ? 2 : 4);
        return switch (choice) {
            case 0, 1 -> RiffleString.of(text());
            case 2 -> objectValue(depth - 1);
            default -> arrayValue(depth - 1);
        };
    }

    private RiffleObject objectValue(int depth) {
        Map<String, RiffleValue> map = new LinkedHashMap<>();
        int size = random.nextInt(4);
        for (int i = 0; i < size; i++) {
            map.put(key(), value(depth));
        }
        return RiffleObject.of(map);
    }

    private RiffleArray arrayValue(int depth) {
        List<RiffleValue> values = new ArrayList<>();
        int size = random.nextInt(4);
        for (int i = 0; i < size; i++) {
            values.add(value(depth));
        }
        return RiffleArray.of(values);
    }

    private String text() {
        String text = anyText();
        return singleLine ? text.replace("\n", "") : text;
    }

    private String anyText() {
        if (random.nextInt(4) == 0) {
            return SPECIAL[random.nextInt(SPECIAL.length)];
        }
        StringBuilder text = new StringBuilder();
        int pieces = random.nextInt(6);
        for (int i = 0; i < pieces; i++) {
            text.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return text.toString();
    }

    private String key() {
        if (random.nextInt(10) == 0) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        int pieces = 1 + random.nextInt(4);
        for (int i = 0; i < pieces; i++) {
            key.append(KEY_PIECES[random.nextInt(KEY_PIECES.length)]);
        }
        return key.toString();
    }
}
