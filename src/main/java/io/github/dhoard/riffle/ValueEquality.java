package io.github.dhoard.riffle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** Iterative structural equality for Riffle values, independent of tree depth. */
final class ValueEquality {

    private ValueEquality() {}

    /** Returns whether two values have the same value-model contents. */
    static boolean equal(RiffleValue left, RiffleValue right) {
        Deque<RiffleValue> pending = new ArrayDeque<>();
        pending.push(left);
        pending.push(right);
        while (!pending.isEmpty()) {
            RiffleValue second = pending.pop();
            RiffleValue first = pending.pop();
            if (first == second) {
                continue;
            }
            if (first instanceof RiffleObject firstMap) {
                if (!(second instanceof RiffleObject secondMap)
                        || !firstMap.entries()
                                .keySet()
                                .equals(secondMap.entries().keySet())) {
                    return false;
                }
                for (Map.Entry<String, RiffleValue> entry : firstMap.entries().entrySet()) {
                    pending.push(entry.getValue());
                    pending.push(secondMap.entries().get(entry.getKey()));
                }
            } else if (first instanceof RiffleArray firstArray) {
                if (!(second instanceof RiffleArray secondArray) || firstArray.size() != secondArray.size()) {
                    return false;
                }
                for (int index = 0; index < firstArray.size(); index++) {
                    pending.push(firstArray.get(index));
                    pending.push(secondArray.get(index));
                }
            } else if (!(first instanceof RiffleString string)
                    || !(second instanceof RiffleString other)
                    || !string.text().equals(other.text())) {
                return false;
            }
        }
        return true;
    }
}
