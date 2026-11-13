package io.github.dhoard.riffle;

/** An object or an array: the only values that can be the root of a document. */
public sealed interface RiffleContainer extends RiffleValue permits RiffleObject, RiffleArray {}
