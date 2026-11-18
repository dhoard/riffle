# Riffle 0.1.0 conformance suite

This directory is a language-independent test contract for Riffle 0.1.0. It is derived from
[`LANGUAGE_SPEC.md`](../LANGUAGE_SPEC.md), not from the Java implementation. A future Go, Rust,
Python, or other implementation can use these fixtures without reading Java source.

## Layout

```text
conformance/
  README.md                 this file
  manifest.json             case list and expectations
  valid/                    documents that must parse
  invalid/                  documents that must be rejected
  canonical/                expected canonical output
```

## Manifest schema

`manifest.json` has `schemaVersion: 1` and a `cases` array. Every case has:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Unique, stable identifier such as `valid.raw-blocks` |
| `expectation` | yes | `valid` or `invalid` |
| `input` | yes | Path relative to `conformance/`, using `/` separators |
| `expectedCanonical` | valid only | Path to the exact expected canonical bytes |
| `errorCategory` | invalid only | Required stable `RiffleError` name from §10 of the spec |
| `encoding` | no | Source encoding when it is not the default `UTF-8`; `UTF-8 with BOM` or `invalid UTF-8` are used here |
| `semantics` | no | Leaf assertions on the parsed tree; `[]` means no assertions beyond canonical bytes |
| `accessors` | no | Value-interpretation assertions (spec §13) |

A semantic assertion has `path`, `kind`, and (for `string` only) a string-valued `value`:

- `kind` is one of `string`, `map`, `array`, or `missing`.
- `missing` asserts that no key or element exists at the path.
- Paths use dot-separated map keys and `[n]` array indexes, for example
  `server.endpoints[0].port`. Map keys containing `.` or `[` are not addressed by this path
  syntax; their meaning is still covered by the byte-exact canonical expectation.

An accessor assertion reads the value at `path` as the interpretation named by `as`, and has
either an expected `value` or an expected `error`:

```json
{ "path": "port", "as": "integer64", "value": "8080" }
{ "path": "big", "as": "integer32", "error": "OUT_OF_RANGE" }
```

| `as` | Interpretation | Form of `value` |
| --- | --- | --- |
| `string` | the string itself | the exact text |
| `integer32` | signed 32-bit integer | base-10 text, with `-` for negatives and no `-0` |
| `integer64` | signed 64-bit integer | base-10 text, with `-` for negatives and no `-0` |
| `decimal` | exact decimal | normalized `<coefficient>E<exponent>`, described below |
| `boolean` | boolean | `true` or `false` |
| `map`, `array` | the collection itself | no `value`; the read must succeed |

- Expected values are always JSON strings, never JSON numbers, so no JSON parser can round them.
- A decimal `value` is the normalized form of spec §13.2: an optional `-`, the coefficient digits
  with no leading or trailing zeros, `E`, and the normalized exponent in base 10 with no `+`. For
  example `1.50` is `15E-1`, `8080` is `808E1`, `1e3` is `1E3`, and zero is `0E0`.
- `error` is an access category from spec §13.3: `MISSING`, `WRONG_KIND`, `INVALID_FORMAT`, or
  `OUT_OF_RANGE`.

## Source encodings

All `.riffle` files are UTF-8. `valid/bom.riffle` begins with a UTF-8 BOM and is otherwise
UTF-8. `valid/line-endings-crlf.riffle` and `valid/raw-block-crlf.riffle` use CRLF; all other text
fixtures use LF.
`invalid/invalid-utf8.bin` is deliberately not valid UTF-8 and is stored as raw bytes rather than
as a JSON string. `invalid/lone-cr.riffle` and `invalid/raw-block-lone-cr.riffle` contain a raw carriage return.

The Java runner passes fixture paths to `Riffle.parse(Path)`, which performs strict UTF-8
decoding. Implementations that parse from strings or readers must also reject unpaired
surrogates as specified in §3.1.

## Required implementation behavior

For every `valid` case:

1. Parse the input into the string, map, and array value model.
2. Produce the canonical output described in spec §11 and compare **bytes exactly** with
   `expectedCanonical`: UTF-8, LF, four-space indentation, final LF (or zero bytes for an empty
   document), no BOM, no comments.
3. Reparse that canonical output and assert semantic equality.
4. Write the reparsed value again and assert byte-identical output.
5. Satisfy every semantic assertion and every accessor assertion.

For every `invalid` case, reject the whole document and report the exact `errorCategory`.
Diagnostic messages, source-name formats, and column-counting units are implementation-specific
and are not asserted here.

Every implementation should also demonstrate:

- No type inference: every scalar is a string until a program interprets it.
- The same interpretation for a value written bare, quoted, or as a raw block.
- Exactly the forms of spec §13, without delegating to host-language number or boolean parsers.
- Raw-block content that is never read as comments, and block comments that never read raw-block
  delimiters.
- Preservation of map insertion order and array order.

## Adding a case

1. Add the input file under `valid/` or `invalid/` (use a `.bin` extension for non-UTF-8 bytes).
2. For a valid case, generate the expected bytes with a conforming serializer and add them under
   `canonical/`. Review the bytes; do not blindly accept generated output for a behavior change.
3. Add one manifest entry with a stable `id`, `semantics`, optional `accessors`, and either
   `expectedCanonical` or `errorCategory`.
4. Run the Java runner with `./mvnw test -Dtest=ConformanceTest`, then the full
   `./mvnw clean verify`.

The runner reads the manifest at runtime. Adding a manifest entry and its files is sufficient;
no Java fixture list needs to be edited.
