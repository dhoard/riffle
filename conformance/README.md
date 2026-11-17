# Riffle 0.1.0 conformance suite

This directory is a language-independent test contract for Riffle 0.1.0. It is derived from
[`LANGUAGE_SPEC.md`](../LANGUAGE_SPEC.md), not from the Java implementation. A future Go, Rust,
Python, or other implementation can use these fixtures without reading Java source.

## Layout

```text
conformance/
  README.md                 this file
  manifest.json             case list and typed expectations
  valid/                    documents that must parse
  invalid/                  documents that must be rejected
  canonical/                expected canonical output plus two named input fixtures
```

## Manifest schema

`manifest.json` has `schemaVersion: 1` and a `cases` array. Every case has:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Unique, stable identifier such as `valid.decimal-normalization` |
| `expectation` | yes | `valid` or `invalid` |
| `input` | yes | Path relative to `conformance/`, using `/` separators |
| `expectedCanonical` | valid only | Path to the exact expected canonical bytes |
| `errorCategory` | invalid only | Required stable `RiffleError` name from §10 of the spec |
| `encoding` | no | Source encoding when it is not the default `UTF-8`; `UTF-8 with BOM` or `invalid UTF-8` are used here |
| `semantics` | no | Typed leaf assertions; `[]` means no assertions beyond canonical bytes |

A semantic assertion has `path`, `kind`, and (except for `null`, `missing`, `map`, and `array`) a
string-valued `value`:

- `kind` is one of `string`, `int`, `long`, `decimal`, `boolean`, `null`, `map`, `array`, or
  `missing`.
- For `int` and `long`, `value` is the signed decimal integer text, not a host-language numeric
  literal.
- For `decimal`, `value` is the **canonical decimal token** from spec §11.3, such as `1.25` or
  `1E-255`; it is not a host-language decimal object.
- `missing` asserts that no key or element exists at the path. A present `@null` is asserted with
  `kind: "null"`, so missing and null remain distinguishable.
- Paths use dot-separated map keys and `[n]` array indexes, for example
  `server.endpoints[0].port`. Map keys containing `.` or `[` are not addressed by this path
  syntax; their meaning is still covered by the byte-exact canonical expectation.

## Source encodings

All `.riffle` files are UTF-8. `valid/bom.riffle` begins with a UTF-8 BOM and is otherwise
UTF-8. `valid/line-endings-crlf.riffle` uses CRLF; all other text fixtures use LF.
`invalid/invalid-utf8.bin` is deliberately not valid UTF-8 and is stored as raw bytes rather than
as a JSON string. `invalid/lone-cr.riffle` contains a raw carriage return.

The Java runner passes fixture paths to `Riffle.parse(Path)`, which performs strict UTF-8
decoding. Implementations that parse from strings or readers must also reject unpaired
surrogates as specified in §3.1.

## Required implementation behavior

For every `valid` case:

1. Parse the input into the eight-kind value model.
2. Produce the canonical output described in spec §11 and compare **bytes exactly** with
   `expectedCanonical`: UTF-8, LF, four-space indentation, final LF (or zero bytes for an empty
   document), no BOM, no comments.
3. Reparse that canonical output and assert semantic equality.
4. Write the reparsed value again and assert byte-identical output.
5. Satisfy every typed semantic assertion.

For every `invalid` case, reject the whole document and report the exact `errorCategory`.
Diagnostic messages, source-name formats, and column-counting units are implementation-specific
and are not asserted here.

Every implementation should also demonstrate:

- No type inference from unmarked strings.
- Distinct `int` and `long` kinds even where the host language has one integer type.
- Decimal equality and output independent of lexical scale.
- Typed arrays normalized to ordinary arrays on canonical output.
- Preservation of map insertion order and array order.

`valid.typed-array-equivalence` and `valid.untyped-array-equivalence` point at the same
canonical file, so byte-identical canonical output is checked without hardcoding fixture names in
the runner.

## Adding a case

1. Add the input file under `valid/` or `invalid/` (use a `.bin` extension for non-UTF-8 bytes).
2. For a valid case, generate the expected bytes with a conforming serializer and add them under
   `canonical/`. Review the bytes; do not blindly accept generated output for a behavior change.
3. Add one manifest entry with a stable `id`, typed `semantics`, and either `expectedCanonical`
   or `errorCategory`.
4. Run the Java runner with `./mvnw test -Dtest=ConformanceTest`, then the full
   `./mvnw clean verify`.

The runner reads the manifest at runtime. Adding a manifest entry and its files is sufficient;
no Java fixture list needs to be edited.
