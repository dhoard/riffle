package io.github.dhoard.riffle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Iterative structural hash for Riffle values.
 *
 * <p>The result matches the old recursive hashes: a string hashes its text, a map sums
 * {@code key.hashCode() ^ value.hashCode()} over its entries, and an array uses list hashing.</p>
 */
final class ValueHash {

    private ValueHash() {}

    /** Returns the structural hash of {@code value}. */
    static int hash(RiffleValue value) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(value, null));
        int result = 0;
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.value instanceof RiffleString string) {
                frame.hash = string.text().hashCode();
            } else if (frame.value instanceof RiffleObject map) {
                if (frame.keys == null) {
                    frame.keys = new ArrayList<>(map.entries().keySet());
                    frame.children = new ArrayList<>(map.entries().values());
                    frame.partials = new ArrayList<>();
                }
                if (frame.index < frame.children.size()) {
                    stack.push(new Frame(frame.children.get(frame.index++), frame));
                    continue;
                }
                int hash = 0;
                for (int i = 0; i < frame.keys.size(); i++) {
                    hash += frame.keys.get(i).hashCode() ^ frame.partials.get(i);
                }
                frame.hash = hash;
            } else {
                RiffleArray array = (RiffleArray) frame.value;
                if (frame.children == null) {
                    frame.children = new ArrayList<>(array.size());
                    for (int i = 0; i < array.size(); i++) {
                        frame.children.add(array.get(i));
                    }
                    frame.partials = new ArrayList<>();
                }
                if (frame.index < frame.children.size()) {
                    stack.push(new Frame(frame.children.get(frame.index++), frame));
                    continue;
                }
                int hash = 1;
                for (int partial : frame.partials) {
                    hash = 31 * hash + partial;
                }
                frame.hash = hash;
            }
            stack.pop();
            if (frame.parent == null) {
                return frame.hash;
            }
            frame.parent.partials.add(frame.hash);
        }
        return result;
    }

    private static final class Frame {
        private final RiffleValue value;
        private final Frame parent;
        private List<String> keys;
        private List<RiffleValue> children;
        private List<Integer> partials;
        private int index;
        private int hash;

        private Frame(RiffleValue value, Frame parent) {
            this.value = value;
            this.parent = parent;
        }
    }
}
