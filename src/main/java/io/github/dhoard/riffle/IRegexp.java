package io.github.dhoard.riffle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An I-Regexp pattern (RFC 9485) as narrowed by SCHEMA_SPEC.md §6, compiled to a Thompson
 * construction and simulated in lockstep over Unicode scalar values.
 *
 * <p>The match covers the whole string. There is no backtracking: one simulation step considers
 * every state the pattern could be in, so time is proportional to the length of the text times the
 * size of the pattern, and a pattern such as {@code (a+)+b} cannot blow up.</p>
 *
 * <p>Instances are immutable and safe to share between threads; the scratch arrays a match needs
 * are allocated per call.</p>
 */
final class IRegexp {
    /** The largest range-quantifier count the reference implementation accepts (spec §6, §14). */
    static final int MAX_REPEAT = 1000;

    /** The largest expanded pattern the reference implementation compiles (spec §14). */
    static final int MAX_STATES = 10_000;

    /** The deepest group nesting the reference implementation compiles (spec §14). */
    static final int MAX_NESTING = 100;

    /** The code points {@code .} matches: every scalar value except LF and CR. */
    private static final int[] DOT_RANGES = {0x0, 0x9, 0xB, 0xC, 0xE, Character.MAX_CODE_POINT};

    private static final int ACCEPT = 0;
    private static final int CHAR = 1;
    private static final int SPLIT = 2;
    private static final int START = 3;
    private static final int END = 4;

    /** Matches the empty string. */
    private static final Node EMPTY = new Seq(List.of());

    private final int[] kind;
    private final int[] first;
    private final int[] follow;
    private final int[] entry;
    private final int states;
    private final Predicate[] predicates;

    private IRegexp(Compiler compiler, int entry, int states) {
        this.states = states;
        this.entry = new int[] {entry};
        this.kind = new int[states];
        this.first = new int[states];
        this.follow = new int[states];
        for (int state = 0; state < states; state++) {
            int[] instruction = compiler.instructions.get(state);
            kind[state] = instruction[0];
            first[state] = instruction[1];
            follow[state] = instruction[3];
        }
        this.predicates = compiler.predicates.toArray(new Predicate[0]);
    }

    // ------------------------------------------------------------------ matching

    /**
     * Returns whether this pattern matches the whole of {@code text}.
     *
     * @param text the text to match
     * @return {@code true} when the whole text is matched
     */
    boolean matches(String text) {
        int[] current = new int[states];
        int[] reached = new int[states];
        int[] seen = new int[states];
        int generation = 1;
        int size = add(current, 0, seen, generation, entry[0]);
        size = close(current, size, seen, generation, text.length() == 0, true);
        int index = 0;
        while (true) {
            if (index >= text.length()) {
                return accepts(current, size);
            }
            generation++;
            int codePoint = text.codePointAt(index);
            int width = Character.charCount(codePoint);
            int reachedSize = 0;
            for (int i = 0; i < size; i++) {
                int state = current[i];
                if (kind[state] == CHAR && predicates[first[state]].test(codePoint)) {
                    reachedSize = add(reached, reachedSize, seen, generation, follow[state]);
                }
            }
            index += width;
            boolean atEnd = index >= text.length();
            size = close(reached, reachedSize, seen, generation, atEnd, false);
            int[] swap = current;
            current = reached;
            reached = swap;
            if (size == 0) {
                return false;
            }
        }
    }

    /** Returns the number of distinct character predicates this pattern compiled to. */
    int predicateCount() {
        return predicates.length;
    }

    /**
     * Adds every state reachable from the states already in {@code list} without consuming text,
     * which is where the zero-width assertions are settled, and returns the new size.
     */
    private int close(int[] list, int size, int[] seen, int generation, boolean atEnd, boolean atStart) {
        int pending = size;
        for (int read = 0; read < pending; read++) {
            int state = list[read];
            if (kind[state] == SPLIT) {
                pending = add(list, pending, seen, generation, first[state]);
                pending = add(list, pending, seen, generation, follow[state]);
            } else if (kind[state] == START && atStart) {
                pending = add(list, pending, seen, generation, follow[state]);
            } else if (kind[state] == END && atEnd) {
                pending = add(list, pending, seen, generation, follow[state]);
            }
        }
        return pending;
    }

    /** Appends {@code state} unless it is already in this generation, and returns the new size. */
    private static int add(int[] list, int size, int[] seen, int generation, int state) {
        if (seen[state] == generation) {
            return size;
        }
        seen[state] = generation;
        list[size] = state;
        return size + 1;
    }

    private boolean accepts(int[] list, int size) {
        for (int i = 0; i < size; i++) {
            if (kind[list[i]] == ACCEPT) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ compiling

    /**
     * Compiles an I-Regexp under the profile of SCHEMA_SPEC.md §6.
     *
     * @param pattern the pattern text
     * @return the compiled pattern
     * @throws IllegalArgumentException if the text is not a pattern of that profile, with a message
     *     that names the position and the reason
     */
    static IRegexp compile(String pattern) {
        Node parsed = new Parser(pattern).parse();
        Compiler compiler = new Compiler();
        int accept = compiler.add(ACCEPT, 0, 0);
        int entry = compiler.build(parsed, accept);
        return new IRegexp(compiler, entry, compiler.instructions.size());
    }

    /** Emits states. A pattern is built back to front, so each fragment knows its continuation. */
    private static final class Compiler {
        private final List<int[]> instructions = new ArrayList<>();
        private final List<Predicate> predicates = new ArrayList<>();

        /** Appends a state and returns its index, refusing a pattern that expands beyond the cap. */
        int add(int kind, int first, int follow) {
            if (instructions.size() >= MAX_STATES) {
                throw new IllegalArgumentException("a pattern compiles to more than " + MAX_STATES + " states");
            }
            instructions.add(new int[] {kind, first, 0, follow});
            return instructions.size() - 1;
        }

        void set(int state, int kind, int first, int follow) {
            int[] instruction = instructions.get(state);
            instruction[0] = kind;
            instruction[1] = first;
            instruction[3] = follow;
        }

        int predicate(Predicate predicate) {
            int found = predicates.indexOf(predicate);
            if (found >= 0) {
                return found;
            }
            predicates.add(predicate);
            return predicates.size() - 1;
        }

        /** Returns the state that matches {@code node} and then continues at {@code next}. */
        int build(Node node, int next) {
            if (node instanceof Seq sequence) {
                int tail = next;
                for (int i = sequence.items().size() - 1; i >= 0; i--) {
                    tail = build(sequence.items().get(i), tail);
                }
                return tail;
            }
            if (node instanceof Alt alternation) {
                List<Node> branches = alternation.branches();
                int tail = build(branches.get(branches.size() - 1), next);
                for (int i = branches.size() - 2; i >= 0; i--) {
                    int branch = build(branches.get(i), next);
                    int split = add(SPLIT, 0, 0);
                    set(split, SPLIT, branch, tail);
                    tail = split;
                }
                return tail;
            }
            if (node instanceof Repeat repeat) {
                return repeat(repeat, next);
            }
            if (node instanceof Literal literal) {
                int test = predicate(
                        new Predicate(new int[] {literal.codePoint(), literal.codePoint()}, List.of(), false));
                return add(CHAR, test, next);
            }
            if (node instanceof ClassNode classNode) {
                return add(CHAR, predicate(classNode.predicate()), next);
            }
            if (node instanceof Dot) {
                return add(CHAR, predicate(new Predicate(DOT_RANGES, List.of(), false)), next);
            }
            if (node instanceof AssertStart) {
                return add(START, 0, next);
            }
            if (node instanceof AssertEnd) {
                return add(END, 0, next);
            }
            throw new IllegalStateException("unknown pattern node " + node.getClass());
        }

        private int repeat(Repeat repeat, int next) {
            int min = repeat.min();
            int max = repeat.max();
            int tail = next;
            if (max < 0) {
                int loop = add(SPLIT, 0, 0);
                int body = build(repeat.inner(), loop);
                set(loop, SPLIT, body, tail);
                tail = loop;
            } else {
                for (int i = min; i < max; i++) {
                    int optional = add(SPLIT, 0, 0);
                    int body = build(repeat.inner(), tail);
                    set(optional, SPLIT, body, tail);
                    tail = optional;
                }
            }
            for (int i = 0; i < min; i++) {
                tail = build(repeat.inner(), tail);
            }
            return tail;
        }
    }

    // ------------------------------------------------------------------ the pattern tree

    private sealed interface Node permits Seq, Alt, Repeat, Literal, ClassNode, Dot, AssertStart, AssertEnd {}

    private record Seq(List<Node> items) implements Node {}

    private record Alt(List<Node> branches) implements Node {}

    private record Repeat(Node inner, int min, int max) implements Node {}

    private record Literal(int codePoint) implements Node {}

    private record Dot() implements Node {}

    private record AssertStart() implements Node {}

    private record AssertEnd() implements Node {}

    /** One general-category alternative of a character class, as written. */
    private record Category(long mask, boolean complemented) {}

    /**
     * The set of code points one {@code CHAR} state accepts: explicit ranges, category alternatives,
     * and an optional negation of the whole thing.
     */
    private record Predicate(int[] ranges, List<Category> categories, boolean inverted) {

        /** Compares the ranges and categories by content, so equal sets share one predicate. */
        @Override
        public boolean equals(Object other) {
            return other instanceof Predicate that
                    && Arrays.equals(ranges, that.ranges)
                    && categories.equals(that.categories)
                    && inverted == that.inverted;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(ranges), categories, inverted);
        }

        boolean test(int codePoint) {
            boolean matched = inRanges(codePoint);
            if (!matched && !categories.isEmpty()) {
                long bit = 1L << Character.getType(codePoint);
                for (Category category : categories) {
                    boolean hit =
                            category.complemented() ? (category.mask() & bit) == 0L : (category.mask() & bit) != 0L;
                    if (hit) {
                        matched = true;
                        break;
                    }
                }
            }
            return inverted != matched;
        }

        private boolean inRanges(int codePoint) {
            for (int i = 0; i + 1 < ranges.length; i += 2) {
                if (codePoint >= ranges[i] && codePoint <= ranges[i + 1]) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A character class while it is being read. */
    private static final class ClassNode implements Node {
        private final List<int[]> ranges = new ArrayList<>();
        private final List<Category> categories = new ArrayList<>();
        private boolean inverted;

        void addRange(int low, int high) {
            ranges.add(new int[] {low, high});
        }

        void addCategory(long mask, boolean complement) {
            categories.add(new Category(mask, complement));
        }

        void invert() {
            inverted = true;
        }

        Predicate predicate() {
            int[] flat = new int[ranges.size() * 2];
            int at = 0;
            for (int[] range : ranges) {
                flat[at++] = range[0];
                flat[at++] = range[1];
            }
            return new Predicate(flat, List.copyOf(categories), inverted);
        }
    }

    // ------------------------------------------------------------------ the parser

    /**
     * A recursive-descent parser for the RFC 9485 ABNF, narrowed by SCHEMA_SPEC.md §6: {@code ^}
     * and {@code $} are assertions, a range quantifier is bounded by {@link #MAX_REPEAT}, and group
     * nesting is bounded by {@link #MAX_NESTING}.
     */
    private static final class Parser {
        private final String source;
        private int index;
        private int depth;

        Parser(String source) {
            this.source = source;
        }

        Node parse() {
            Node node = alternation();
            if (index < source.length()) {
                throw error("unmatched ')'");
            }
            return node;
        }

        private Node alternation() {
            List<Node> branches = new ArrayList<>();
            branches.add(branch());
            while (index < source.length() && source.charAt(index) == '|') {
                index++;
                branches.add(branch());
            }
            return branches.size() == 1 ? branches.get(0) : new Alt(branches);
        }

        private Node branch() {
            List<Node> items = new ArrayList<>();
            while (index < source.length() && source.charAt(index) != '|' && source.charAt(index) != ')') {
                items.add(piece());
            }
            if (items.isEmpty()) {
                return EMPTY;
            }
            return items.size() == 1 ? items.get(0) : new Seq(items);
        }

        private Node piece() {
            Node atom = atom();
            int[] quantifier = quantifier();
            return quantifier == null ? atom : new Repeat(atom, quantifier[0], quantifier[1]);
        }

        private Node atom() {
            if (index >= source.length()) {
                throw error("a quantifier needs something to repeat");
            }
            char c = source.charAt(index);
            switch (c) {
                case '.':
                    index++;
                    return new Dot();
                case '^':
                    index++;
                    return new AssertStart();
                case '$':
                    index++;
                    return new AssertEnd();
                case '(': {
                    if (depth >= MAX_NESTING) {
                        throw error("groups may nest at most " + MAX_NESTING + " levels");
                    }
                    index++;
                    depth++;
                    try {
                        Node inner = alternation();
                        if (index >= source.length() || source.charAt(index) != ')') {
                            throw error("')' is missing");
                        }
                        index++;
                        return inner;
                    } finally {
                        depth--;
                    }
                }
                case '[':
                    return characterClass();
                case '\\': {
                    ClassNode category = categoryEscape();
                    return category != null ? category : new Literal(escapeCharacter());
                }
                case '{':
                    throw error("a range quantifier needs something to repeat");
                case '}':
                    throw error("'}' closes no range quantifier");
                default: {
                    int codePoint = source.codePointAt(index);
                    if (!isNormalChar(codePoint)) {
                        throw error("'" + c + "' is not a pattern character of this profile; escape it");
                    }
                    index += Character.charCount(codePoint);
                    return new Literal(codePoint);
                }
            }
        }

        /**
         * Reads a {@code \p{…}} or {@code \P{…}} escape at the current position, returning its
         * class, or {@code null} when the escape is an ordinary single-character escape.
         */
        private ClassNode categoryEscape() {
            if (index + 1 >= source.length() || source.charAt(index) != '\\') {
                return null;
            }
            char which = source.charAt(index + 1);
            if (which != 'p' && which != 'P') {
                return null;
            }
            index += 2;
            ClassNode node = new ClassNode();
            node.addCategory(category(), which == 'P');
            return node;
        }

        /** Reads {@code {name}} and returns the mask of the category it names. */
        private long category() {
            if (index >= source.length() || source.charAt(index) != '{') {
                throw error("'{' must follow the category escape");
            }
            int start = ++index;
            while (index < source.length() && source.charAt(index) != '}') {
                index++;
            }
            if (index >= source.length()) {
                throw error("'}' is missing");
            }
            String name = source.substring(start, index++);
            long mask = categoryMask(name);
            if (mask == 0L) {
                throw error("{" + name + "} is not a category of this profile");
            }
            return mask;
        }

        /** Reads a single-character escape at the current position and returns its character. */
        private int escapeCharacter() {
            index++;
            if (index >= source.length()) {
                throw error("the pattern ends with a backslash");
            }
            char c = source.charAt(index++);
            return switch (c) {
                case '(' -> '(';
                case ')' -> ')';
                case '*' -> '*';
                case '+' -> '+';
                case '-' -> '-';
                case '.' -> '.';
                case '?' -> '?';
                case '[' -> '[';
                case '\\' -> '\\';
                case '^' -> '^';
                case '$' -> '$';
                case ']' -> ']';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '{' -> '{';
                case '|' -> '|';
                case '}' -> '}';
                default -> throw error("unsupported escape \\" + c);
            };
        }

        /** Reads a character class, starting at its opening bracket. */
        private Node characterClass() {
            ClassNode node = new ClassNode();
            index++;
            if (index < source.length() && source.charAt(index) == '^') {
                index++;
                node.invert();
                if (index < source.length() && source.charAt(index) == ']') {
                    throw error("[^] is not a character class of this profile");
                }
            }
            boolean first = true;
            boolean any = false;
            while (true) {
                if (index >= source.length()) {
                    throw error("']' is missing");
                }
                if (source.charAt(index) == ']') {
                    if (!any) {
                        throw error("a character class needs at least one character");
                    }
                    index++;
                    break;
                }
                boolean plainDash = source.charAt(index) == '-' && (first || peek(1) == ']');
                if (plainDash) {
                    index++;
                    node.addRange('-', '-');
                    any = true;
                    first = false;
                    continue;
                }
                long[] category = categoryItem();
                if (category != null) {
                    node.addCategory(category[0], category[1] == 1L);
                    any = true;
                    first = false;
                    continue;
                }
                int low = classCharacter();
                any = true;
                if (peek(0) == '-' && peek(1) != ']') {
                    index++;
                    if (categoryItem() != null) {
                        throw error("a range cannot end with a category escape");
                    }
                    int high = classCharacter();
                    if (high < low) {
                        throw error("a character range counts up");
                    }
                    node.addRange(low, high);
                } else {
                    node.addRange(low, low);
                }
                first = false;
            }
            return node;
        }

        /** Returns the category escape at the position, or {@code null} when there is none. */
        private long[] categoryItem() {
            if (index + 2 >= source.length() || source.charAt(index) != '\\') {
                return null;
            }
            char which = source.charAt(index + 1);
            if (which != 'p' && which != 'P') {
                return null;
            }
            index += 2;
            long mask = category();
            return new long[] {mask, which == 'P' ? 1L : 0L};
        }

        /** Reads one class character, which may be a single-character escape. */
        private int classCharacter() {
            if (index >= source.length()) {
                throw error("']' is missing");
            }
            char c = source.charAt(index);
            if (c == '\\') {
                return escapeCharacter();
            }
            int codePoint = source.codePointAt(index);
            if (!isClassCharacter(codePoint)) {
                throw error("'" + c + "' must be escaped in a character class");
            }
            index += Character.charCount(codePoint);
            return codePoint;
        }

        /** Reads the quantifier after an atom, or returns {@code null} when there is none. */
        private int[] quantifier() {
            char c = peek(0);
            switch (c) {
                case '?':
                    index++;
                    return new int[] {0, 1};
                case '*':
                    index++;
                    return new int[] {0, -1};
                case '+':
                    index++;
                    return new int[] {1, -1};
                case '{': {
                    index++;
                    int min = count();
                    if (min < 0) {
                        throw error("'{' must be followed by a count");
                    }
                    int max = min;
                    if (peek(0) == ',') {
                        index++;
                        int bound = count();
                        max = bound < 0 ? -1 : bound;
                    }
                    if (peek(0) != '}') {
                        throw error("'}' is missing");
                    }
                    index++;
                    if (max >= 0 && min > max) {
                        throw error("the range {" + min + "," + max + "} is empty");
                    }
                    return new int[] {min, max};
                }
                default:
                    return null;
            }
        }

        /** Reads a run of decimal digits, rejecting a count above {@link #MAX_REPEAT}. */
        private int count() {
            int start = index;
            int value = 0;
            while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') {
                value = value * 10 + (source.charAt(index) - '0');
                if (value > MAX_REPEAT) {
                    throw error("a range quantifier may repeat at most " + MAX_REPEAT + " times");
                }
                index++;
            }
            return index == start ? -1 : value;
        }

        private char peek(int ahead) {
            int at = index + ahead;
            return at < source.length() ? source.charAt(at) : 0;
        }

        private IllegalArgumentException error(String reason) {
            return new IllegalArgumentException("column " + (index + 1) + ": " + reason);
        }
    }

    private static boolean isNormalChar(int codePoint) {
        if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
            return false;
        }
        return codePoint <= 0x27
                || codePoint == 0x2C
                || codePoint == 0x2D
                || (codePoint >= 0x2F && codePoint <= 0x3E)
                || (codePoint >= 0x40 && codePoint <= 0x5A)
                || (codePoint >= 0x5E && codePoint <= 0x7A)
                || (codePoint >= 0x7E && codePoint <= 0xD7FF)
                || codePoint >= 0xE000;
    }

    /** Class characters exclude the bracket that closes the class, the backslash, and the dash. */
    private static boolean isClassCharacter(int codePoint) {
        if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
            return false;
        }
        return (codePoint <= 0x2C || (codePoint >= 0x2E && codePoint <= 0x5A) || codePoint >= 0x5E)
                && codePoint != '['
                && codePoint != '\\';
    }

    /**
     * Returns the mask of the RFC 9485 category {@code name}, or {@code 0} when the name is not one
     * of the general categories this profile allows.
     */
    private static long categoryMask(String name) {
        return switch (name) {
            case "L" ->
                bit(Character.UPPERCASE_LETTER)
                        | bit(Character.LOWERCASE_LETTER)
                        | bit(Character.TITLECASE_LETTER)
                        | bit(Character.MODIFIER_LETTER)
                        | bit(Character.OTHER_LETTER);
            case "Ll" -> bit(Character.LOWERCASE_LETTER);
            case "Lm" -> bit(Character.MODIFIER_LETTER);
            case "Lo" -> bit(Character.OTHER_LETTER);
            case "Lt" -> bit(Character.TITLECASE_LETTER);
            case "Lu" -> bit(Character.UPPERCASE_LETTER);
            case "M" ->
                bit(Character.NON_SPACING_MARK) | bit(Character.COMBINING_SPACING_MARK) | bit(Character.ENCLOSING_MARK);
            case "Mc" -> bit(Character.COMBINING_SPACING_MARK);
            case "Me" -> bit(Character.ENCLOSING_MARK);
            case "Mn" -> bit(Character.NON_SPACING_MARK);
            case "N" ->
                bit(Character.DECIMAL_DIGIT_NUMBER) | bit(Character.LETTER_NUMBER) | bit(Character.OTHER_NUMBER);
            case "Nd" -> bit(Character.DECIMAL_DIGIT_NUMBER);
            case "Nl" -> bit(Character.LETTER_NUMBER);
            case "No" -> bit(Character.OTHER_NUMBER);
            case "P" ->
                bit(Character.CONNECTOR_PUNCTUATION)
                        | bit(Character.DASH_PUNCTUATION)
                        | bit(Character.START_PUNCTUATION)
                        | bit(Character.END_PUNCTUATION)
                        | bit(Character.INITIAL_QUOTE_PUNCTUATION)
                        | bit(Character.FINAL_QUOTE_PUNCTUATION)
                        | bit(Character.OTHER_PUNCTUATION);
            case "Pc" -> bit(Character.CONNECTOR_PUNCTUATION);
            case "Pd" -> bit(Character.DASH_PUNCTUATION);
            case "Pe" -> bit(Character.END_PUNCTUATION);
            case "Pf" -> bit(Character.FINAL_QUOTE_PUNCTUATION);
            case "Pi" -> bit(Character.INITIAL_QUOTE_PUNCTUATION);
            case "Po" -> bit(Character.OTHER_PUNCTUATION);
            case "Ps" -> bit(Character.START_PUNCTUATION);
            case "S" ->
                bit(Character.MATH_SYMBOL)
                        | bit(Character.CURRENCY_SYMBOL)
                        | bit(Character.MODIFIER_SYMBOL)
                        | bit(Character.OTHER_SYMBOL);
            case "Sc" -> bit(Character.CURRENCY_SYMBOL);
            case "Sk" -> bit(Character.MODIFIER_SYMBOL);
            case "Sm" -> bit(Character.MATH_SYMBOL);
            case "So" -> bit(Character.OTHER_SYMBOL);
            case "Z" ->
                bit(Character.SPACE_SEPARATOR) | bit(Character.LINE_SEPARATOR) | bit(Character.PARAGRAPH_SEPARATOR);
            case "Zl" -> bit(Character.LINE_SEPARATOR);
            case "Zp" -> bit(Character.PARAGRAPH_SEPARATOR);
            case "Zs" -> bit(Character.SPACE_SEPARATOR);
            case "C" ->
                bit(Character.CONTROL) | bit(Character.FORMAT) | bit(Character.UNASSIGNED) | bit(Character.PRIVATE_USE);
            case "Cc" -> bit(Character.CONTROL);
            case "Cf" -> bit(Character.FORMAT);
            case "Cn" -> bit(Character.UNASSIGNED);
            case "Co" -> bit(Character.PRIVATE_USE);
            default -> 0L;
        };
    }

    private static long bit(int category) {
        return 1L << category;
    }
}
