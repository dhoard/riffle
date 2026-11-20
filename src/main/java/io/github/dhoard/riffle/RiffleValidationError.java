package io.github.dhoard.riffle;

/**
 * Stable problem categories of the Riffle schema format, as listed by SCHEMA_SPEC.md §10.
 *
 * <p>The first twelve categories are reported while validating a document against a schema and
 * while checking a schema document against the meta-schema. The remaining categories are reported
 * only while loading a schema.</p>
 */
public enum RiffleValidationError {
    /** A required member is absent, or a discriminator member is absent. */
    MISSING,
    /** The kind of the value is not the kind the node requires, and no {@code kindUnion} fits. */
    WRONG_KIND,
    /** Text does not match the integer, decimal, or boolean interpretation the node requires. */
    INVALID_FORMAT,
    /** Text is outside the portable range of an interpretation, or outside {@code min} or {@code max}. */
    OUT_OF_RANGE,
    /** The value is not one of {@code values}, or a discriminator matches no {@code cases} entry. */
    NOT_ALLOWED,
    /** A {@code pattern} or {@code keyPattern} does not match. */
    PATTERN_MISMATCH,
    /** {@code minLength} or {@code maxLength} is violated. */
    INVALID_LENGTH,
    /** {@code notBlank} is violated. */
    BLANK,
    /** {@code minItems}, {@code maxItems}, {@code minKeys}, or {@code maxKeys} is violated. */
    INVALID_SIZE,
    /** {@code unique} or {@code uniqueKey} is violated. */
    DUPLICATE,
    /** A key that {@code keys} does not name, where the node has no {@code otherKeys}. */
    UNKNOWN_KEY,
    /** A rule of the schema's {@code rules} list is not satisfied. */
    RULE_VIOLATION,
    /** The {@code riffleSchema} key of a schema document is not the string {@code 1}. */
    UNSUPPORTED_VERSION,
    /** A {@code pattern} or {@code keyPattern} is not a valid I-Regexp. */
    INVALID_PATTERN,
    /** Declared keywords cannot be used together, or a declared bound leaves no allowed range. */
    INVALID_CONSTRAINT,
    /** A {@code default} does not satisfy the node it belongs to. */
    INVALID_DEFAULT,
    /** A rule names a key that is not a declared member, or has operands that do not fit it. */
    INVALID_RULE,
    /** A {@code ref} names a definition that is not declared. */
    UNKNOWN_DEFINITION,
    /** A definition is not reachable from the root of the schema. */
    UNUSED_DEFINITION,
    /** A cycle of definitions consumes no data, so validation could not terminate. */
    CIRCULAR_DEFINITION,
    /** The {@code root} of a schema does not resolve to a map or a tagged union. */
    INVALID_ROOT
}
