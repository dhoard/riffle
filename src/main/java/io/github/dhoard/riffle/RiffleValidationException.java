package io.github.dhoard.riffle;

import java.util.List;
import java.util.Objects;

/**
 * A document does not satisfy the schema it was parsed with.
 *
 * <p>This is a configuration error rather than a programming error, and it is distinct from
 * {@link RiffleSchemaException}, which reports a schema that cannot be used at all. The message is
 * the first problem, followed by {@code (and N more problems)} when there are more; the document
 * author has to fix every one of them, so {@link #problems()} carries the whole ordered list.</p>
 */
public final class RiffleValidationException extends RiffleException {
    private static final long serialVersionUID = 1L;

    /**
     * The problems, in document order. The field is an array because a serializable class may not
     * hold a field of a non-serializable type; {@link #problems()} hands out an immutable list.
     */
    private final RiffleProblem[] problems;

    RiffleValidationException(List<RiffleProblem> problems) {
        super(message(problems), first(problems).reason());
        this.problems = problems.toArray(RiffleProblem[]::new);
    }

    /**
     * Returns every violation of the document, in the order SCHEMA_SPEC.md §12 requires.
     *
     * @return an immutable list with at least one problem
     */
    public List<RiffleProblem> problems() {
        return List.of(problems);
    }

    private static RiffleProblem first(List<RiffleProblem> problems) {
        Objects.requireNonNull(problems, "problems");
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("problems is empty");
        }
        return problems.get(0);
    }

    private static String message(List<RiffleProblem> problems) {
        RiffleProblem first = first(problems);
        return problems.size() == 1 ? first.toString() : first + " (and " + (problems.size() - 1) + " more problems)";
    }
}
