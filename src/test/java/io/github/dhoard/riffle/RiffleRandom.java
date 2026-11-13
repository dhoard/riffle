package io.github.dhoard.riffle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Generates values that exercise every scalar kind, collection and quoting rule. */
final class RiffleRandom {
    private static final String[] PIECES = {
        "a", "b", "Z", "0", " ", "\t", "\"", "#", "/", "//", "|", "!", "@", ":", "=", "{", "}", "[", "]", "\n", "é",
        "💡", "null", "true", "int", "\\", ";", ","
    };
    private static final String[] SPECIAL = {
        "",
        " ",
        "null",
        "true",
        "123",
        "~",
        "@null",
        "@int 1",
        "@boolean true",
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
        "https://x:1/y?a=b#c"
    };
    private static final String[] KEY_PIECES = {"a", "b", "Z", "0", ".", "-", "_", " ", "=", "@", "é"};

    private final Random random;

    RiffleRandom(Random random) {
        this.random = random;
    }

    RiffleObject object() {
        return objectValue(3);
    }

    private RiffleValue value(int depth) {
        int choice = random.nextInt(depth <= 0 ? 7 : 9);
        return switch (choice) {
            case 0 -> RiffleString.of(text());
            case 1 -> RiffleInt.of(random.nextInt());
            case 2 -> RiffleLong.of(random.nextLong());
            case 3 -> RiffleDecimal.of(decimalValue());
            case 4 -> RiffleBoolean.of(random.nextBoolean());
            case 5 -> RiffleNull.INSTANCE;
            case 6 -> RiffleString.of(text());
            case 7 -> objectValue(depth - 1);
            default -> arrayValue(depth - 1);
        };
    }

    private BigDecimal decimalValue() {
        return switch (random.nextInt(6)) {
            case 0 -> new BigDecimal("0");
            case 1 -> new BigDecimal("-0.0001");
            case 2 -> new BigDecimal("1E+20");
            case 3 -> new BigDecimal("12345678901234567890.123456789");
            default -> BigDecimal.valueOf(random.nextInt(2000) - 1000, random.nextInt(4));
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
