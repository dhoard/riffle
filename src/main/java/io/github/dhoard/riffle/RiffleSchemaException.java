package io.github.dhoard.riffle;

import java.util.List;
import java.util.Objects;

/**
 * A schema document cannot be used: its version is not supported, or it declares something the
 * schema format cannot express.
 *
 * <p>This is a programming error rather than a configuration error, and it is distinct from
 * {@link RiffleValidationException}, which reports data that does not satisfy a usable schema. The
 * message is the first problem, followed by {@code (and N more problems)} when the schema reports
 * more than one, because a broken schema is fixed one problem at a time but a full list is cheaper
 * to repair than one round trip per problem.</p>
 *
 * <p>Problems point into the schema document, so their paths and locations address the schema, not
 * any data document.</p>
 */
public final class RiffleSchemaException extends RiffleException {
    private static final long serialVersionUID = 1L;

    /**
     * The problems, in document order. The field is an array because a serializable class may not
     * hold a field of a non-serializable type; {@link #problems()} hands out an immutable list.
     */
    private final RiffleProblem[] problems;

    RiffleSchemaException(List<RiffleProblem> problems) {
        super(message(problems), first(problems).reason());
        this.problems = problems.toArray(RiffleProblem[]::new);
    }

    /**
     * Returns every problem with the schema document, in the order SCHEMA_SPEC.md §11 requires.
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
