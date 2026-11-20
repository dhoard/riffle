# Riffle schema conformance suite

This directory is a language-independent test contract for **Riffle schema version 1**. It is
derived from [`SCHEMA_SPEC.md`](../SCHEMA_SPEC.md), not from the Java implementation. A future Go,
Rust, Python, or other implementation can use these fixtures without reading Java source.

## Layout

```text
conformance/schema/
  README.md                 this file
  manifest.json             case list and expectations
  meta-schema.riffle        the meta-schema of spec §15, byte-identical to the published copy
  schemas/valid/            schema documents that must load
  schemas/invalid/          schema documents that must be rejected
  documents/                data documents, each paired with one schema
```

## Manifest schema

`manifest.json` has `schemaVersion: 1` and a `cases` array. Every case has:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Unique, stable identifier such as `document.service.port-zero` |
| `expectation` | yes | `schema-valid`, `schema-invalid`, or `document` |
| `schema` | yes | Schema document, relative to `conformance/schema/`, using `/` separators |
| `input` | `document` only | Data document, relative to `conformance/schema/` |
| `problems` | `schema-invalid`, `document` | Ordered list of the problems that MUST be reported, and the only ones |

A problem has exactly three fields:

| Field | Meaning |
| --- | --- |
| `category` | A category name of spec §10 |
| `path` | The path of the value, key, or missing member the problem is about, rendered with the path syntax of the language spec §10.1; the root renders as `(root)` |
| `line` | The 1-based line of the text the problem is reported at |

Columns, source names, and the wording of a reason are implementation-specific and are not
compared. Categories, paths, lines, the order of the list, and the number of problems are
compared exactly, so a case fails when a run reports a problem that is missing, extra, out of
order, or at another path or line.

## Required implementation behavior

For every `schema-valid` case, load the schema and report no problem.

For every `schema-invalid` case, load the schema and report exactly the listed problems in the
listed order (spec §11).

For every `document` case, load the schema, then validate the data document against it and report
exactly the listed problems in the listed order (spec §12). An empty `problems` list means the
document is valid and no problem may be reported.

`meta-schema.riffle` is published so that an implementation can check the two obligations of spec
§15 without a Java resource: it MUST load as a schema, and the case `document.meta-schema-self`,
which validates it as a data document against itself, MUST report no problems.

Implementations should also demonstrate, with their own tests rather than with these fixtures:

- That the keyword vocabulary is closed and that no extension mechanism exists (spec §4).
- That no data value appears in a category, a path beyond the keys it names, or a reason (spec §10).
- That a pattern is matched against the whole string by an engine that cannot backtrack, including
  a pathological pattern such as `(a+)+b` against 100000 `a` characters (spec §6, §14).
- That repeated and concurrent validation with one schema give identical results (spec §12).
- That defaults are inserted only by an explicit defaults operation, in the position and order of
  spec §13.

## Adding a case

1. Add the schema under `schemas/valid/` or `schemas/invalid/`, and for a `document` case the data
   under `documents/`. Keep each fixture small and name it after the behavior it pins.
2. Add one manifest entry with a stable `id` and the ordered `problems` list the specification
   requires. Derive the list from `SCHEMA_SPEC.md` §10 to §12; do not generate it from one
   implementation and accept whatever that implementation happens to report.
3. Run the Java runner with `./mvnw test -Dtest=SchemaConformanceTest`, then the full
   `./mvnw clean verify`.

The runner reads the manifest at runtime. Adding a manifest entry and its files is sufficient; no
Java fixture list needs to be edited.
