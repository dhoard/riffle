package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A problem is a value that a host compares and stores, so its four parts define its identity while
 * its text stays host-facing.
 */
class RiffleProblemTest {

    private static final RifflePath PATH = RifflePath.root().key("port");

    @Test
    void holdsTheFourParts() {
        RiffleProblem problem = new RiffleProblem(
                RiffleValidationError.OUT_OF_RANGE, PATH, new RiffleLocation(null, 3, 5), "expected at least 1024");

        assertThat(problem.category()).isEqualTo(RiffleValidationError.OUT_OF_RANGE);
        assertThat(problem.path()).isEqualTo(PATH);
        assertThat(problem.location()).contains(new RiffleLocation(null, 3, 5));
        assertThat(problem.reason()).isEqualTo("expected at least 1024");
    }

    @Test
    void omitsALocationItDoesNotHave() {
        assertThat(new RiffleProblem(RiffleValidationError.MISSING, PATH, null, "is required").location())
                .isEmpty();
    }

    @Test
    void comparesAllFourParts() {
        RiffleProblem problem =
                new RiffleProblem(RiffleValidationError.MISSING, PATH, new RiffleLocation(null, 4, 1), "is required");

        assertThat(problem)
                .isEqualTo(new RiffleProblem(
                        RiffleValidationError.MISSING, PATH, new RiffleLocation(null, 4, 1), "is required"));
        assertThat(problem)
                .hasSameHashCodeAs(new RiffleProblem(
                        RiffleValidationError.MISSING, PATH, new RiffleLocation(null, 4, 1), "is required"));
        assertThat(problem)
                .isNotEqualTo(new RiffleProblem(
                        RiffleValidationError.BLANK, PATH, new RiffleLocation(null, 4, 1), "is required"));
        assertThat(problem)
                .isNotEqualTo(new RiffleProblem(
                        RiffleValidationError.MISSING,
                        RifflePath.root().key("name"),
                        new RiffleLocation(null, 4, 1),
                        "is required"));
        assertThat(problem)
                .isNotEqualTo(new RiffleProblem(
                        RiffleValidationError.MISSING, PATH, new RiffleLocation(null, 5, 1), "is required"));
        assertThat(problem).isNotEqualTo(new RiffleProblem(RiffleValidationError.MISSING, PATH, null, "is required"));
        assertThat(problem)
                .isNotEqualTo(new RiffleProblem(
                        RiffleValidationError.MISSING, PATH, new RiffleLocation(null, 4, 1), "is missing"));
        assertThat(problem).isNotEqualTo("port");
    }

    @Test
    void groupsProblemsWithTheSameParts() {
        Set<RiffleProblem> problems = new HashSet<>();
        problems.add(
                new RiffleProblem(RiffleValidationError.MISSING, PATH, new RiffleLocation(null, 4, 1), "is required"));
        problems.add(
                new RiffleProblem(RiffleValidationError.MISSING, PATH, new RiffleLocation(null, 4, 1), "is required"));

        assertThat(problems).hasSize(1);
    }

    @Test
    void formatsThePartsItHas() {
        assertThat(new RiffleProblem(
                        RiffleValidationError.OUT_OF_RANGE,
                        PATH,
                        new RiffleLocation(null, 3, 5),
                        "expected at least 1024"))
                .hasToString("line 3, column 5: port: expected at least 1024");
        assertThat(new RiffleProblem(
                        RiffleValidationError.RULE_VIOLATION, RifflePath.root(), null, "exactlyOne requires name"))
                .hasToString("(root): exactlyOne requires name");
    }

    @Test
    void requiresACategoryAPathAndAReason() {
        RiffleProblem problem = new RiffleProblem(RiffleValidationError.MISSING, PATH, null, "is required");

        assertThat(catchThrowableOfType(
                        NullPointerException.class, () -> new RiffleProblem(null, PATH, null, "is required")))
                .isNotNull();
        assertThat(catchThrowableOfType(
                        NullPointerException.class,
                        () -> new RiffleProblem(RiffleValidationError.MISSING, null, null, "is required")))
                .isNotNull();
        assertThat(catchThrowableOfType(
                        NullPointerException.class,
                        () -> new RiffleProblem(RiffleValidationError.MISSING, PATH, null, null)))
                .isNotNull();
        assertThat(problem.reason()).isEqualTo("is required");
    }
}
