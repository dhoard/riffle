package io.github.dhoard.riffle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Iterative {@code toString} for value trees, so deep trees cannot exhaust the stack. */
final class ValuePrinter {

    private ValuePrinter() {}

    /** Returns the same textual form the recursive implementations produced. */
    static String toString(RiffleValue value) {
        StringBuilder out = new StringBuilder();
        Deque<Object> pending = new ArrayDeque<>();
        pending.push(value);
        while (!pending.isEmpty()) {
            Object item = pending.pop();
            if (item instanceof String text) {
                out.append(text);
                continue;
            }
            RiffleValue current = (RiffleValue) item;
            if (current instanceof RiffleString string) {
                out.append("RiffleString[").append(string.text()).append(']');
            } else if (current instanceof RiffleObject map) {
                out.append("RiffleObject{");
                pending.push("}");
                List<Map.Entry<String, RiffleValue>> entries =
                        List.copyOf(map.entries().entrySet());
                for (int i = entries.size() - 1; i >= 0; i--) {
                    if (i > 0) {
                        pending.push(", ");
                    }
                    Map.Entry<String, RiffleValue> entry = entries.get(i);
                    pending.push(entry.getValue());
                    pending.push(entry.getKey() + "=");
                }
            } else {
                RiffleArray array = (RiffleArray) current;
                out.append("RiffleArray[");
                pending.push("]");
                for (int i = array.size() - 1; i >= 0; i--) {
                    pending.push(array.get(i));
                    if (i > 0) {
                        pending.push(", ");
                    }
                }
            }
        }
        return out.toString();
    }
}
