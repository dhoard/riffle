# Riffle Schema Specification

**Version:** 1
**Status:** Normative schema specification
**Applies to:** Riffle implementations that offer schema validation, independent of host language
**Requires:** Riffle Language Specification 0.1.0
**Reference implementation:** Java 17 (see the non-normative [Appendix A](#appendix-a-reference-java-mapping-non-normative))

## 1. Scope and terminology

A Riffle schema is a **contract for a document**: it states which keys a document may hold, what
kind each value must have, and which constraints each value must satisfy. A schema is itself an
ordinary Riffle document, written in the format defined here and specified by this document.

Riffle schemas follow one principle: **a schema checks a document and never changes it.** A schema
never alters how a document parses, never rewrites a value, and never inserts a value unless a
program asks for defaults explicitly (§13). The text the author writes remains exactly the text the
program receives.

The normative terms **MUST**, **MUST NOT**, **SHOULD**, and **MAY** have their usual requirements
meanings. This document defines schema version 1, identified by the value `1` in the
`riffleSchema` key of a schema document. An implementation MUST NOT invent additional keywords or
silent coercion.

Normative sections describe observable input, observable results, and limits: schema text, data
text, problems, and limits. Host-language type names and host-language APIs appear only in the
non-normative appendices, as required by [LANGUAGE_SPEC.md](LANGUAGE_SPEC.md) §1.

Terminology:

| Term | Meaning |
| --- | --- |
| schema document | A Riffle document that follows §3 to §9; it is the only way to obtain a schema |
| data document, data | An ordinary Riffle document checked against a schema |
| node | A value in a schema document that describes part of a data document (§4) |
| member | A key of a `map` node's `keys`, together with the node that describes its value |
| problem | One violation reported by loading a schema (§11) or by validating data (§12) |
| path | The unambiguous address of a value, as defined by [LANGUAGE_SPEC.md](LANGUAGE_SPEC.md) §10 |
| category | A stable name for the kind of a problem (§10) |

Schema support is optional: a conforming **language** implementation is not required to offer
schemas. An implementation that does offer them MUST behave as specified here (§18). The value
model, key alphabet, host-configurable nesting limits, scalar interpretations, and paths are defined by
`LANGUAGE_SPEC.md` and are cited, not restated, by this document.

Core properties:

- The keyword set is **closed**: an unknown or misplaced keyword is a problem, never an ignored
  extra (§4).
- Every node declares exactly **one** `type`, and only the keywords that type allows (§4).
- A member is **required** unless it declares `optional : true`; `false` is not a value of any flag
  in this format (§4).
- Validation **never inserts** anything. Defaults are applied only by an explicit operation (§13).
- Problems are **ordered**, complete, and deterministic (§10, §12).
- A problem names constraints, never data: no data value appears in a problem (§10).
- Validation time is **linear** in the size of the data document for a fixed schema, and a pattern
  never backtracks (§6, §14).
- A data document holds **no schema syntax**: a legal key such as `riffleSchema` is ordinary
  data there, and a name outside the key alphabet cannot occur in a Riffle document at all (§3).

## 2. Canonical example

The schema:

```riffle
riffleSchema : 1
root : {
    type : map
    keys : {
        name : {
            type : string
            notBlank : true
        }
        port : {
            type : integer
            min : 1
            max : 65535
            optional : true
            default : 8080
        }
        command : {
            type : string
            optional : true
        }
        url : {
            type : string
            pattern : https?://.+
            optional : true
        }
        env : {
            type : map
            optional : true
            keyPattern : "[A-Z_][A-Z0-9_]*"
            otherKeys : {
                type : string
            }
        }
    }
    rules : [
        {
            rule : atMostOne
            keys : [
                command
                url
            ]
        }
    ]
}
```

A data document that satisfies it:

```riffle
name : gateway
port : 8443
url : https://example.org/gateway
env : {
    STAGE : prod
}
```

A data document that does not:

```riffle
port : 0
url : https://example.org
command : run
env : {
    region : eu
    lower_key : x
}
extra : 1
```

It MUST produce exactly the ordered problem list below, where each row is a category, a path, and
the line of the text the problem is reported at (columns are implementation-specific, §10):

| # | Category | Path | Line |
| --- | --- | --- | --- |
| 1 | `OUT_OF_RANGE` | `port` | 1 |
| 2 | `PATTERN_MISMATCH` | `env.region` | 5 |
| 3 | `PATTERN_MISMATCH` | `env.lower_key` | 6 |
| 4 | `UNKNOWN_KEY` | `extra` | 8 |
| 5 | `MISSING` | `name` | 1 |
| 6 | `RULE_VIOLATION` | `(root)` | 1 |

Problems 2 and 3 are reported at the keys of `env`, because `env` constrains its keys with
`keyPattern` and has no declared `keys`. Problem 5 is reported at the line of the map that should
have held `name`, which is the first line of the document, and problem 6 at the document root, in
the order §12 requires.

The schema in the first block of this section is also `examples/service.schema.riffle` in the
reference implementation, and the last document is `examples/service.riffle`.

## 3. Schema documents

A schema document is an ordinary Riffle 0.1.0 document (§3 of `LANGUAGE_SPEC.md`). It is parsed by
the ordinary parser: a syntax error in a schema document is a parse failure, not a schema problem,
and no validation of anything runs on a partially parsed schema.

A schema document may hold only these top-level keys:

| Key | Required | Meaning |
| --- | --- | --- |
| `riffleSchema` | yes | The schema-format version; the string `1` and nothing else (§11) |
| `description` | no | Free text for the reader of the schema; it never affects validation and never appears in a problem |
| `root` | yes | The node that describes the data document (§4) |
| `definitions` | no | A map from a name to a node, for reuse through `ref` (§9) |

Every key in a schema document, and every name a schema declares — a `keys` member name, a
`definitions` name, a `uniqueKey` name, and the keys named by a rule (§8) — is an ordinary Riffle
key and MUST satisfy §6.2 of `LANGUAGE_SPEC.md`. A schema document that holds a key outside that
alphabet is a parse failure, not a schema problem, exactly like any other syntax error.

Any other top-level key is a problem (§11). A key named `riffleSchema`, `version`, `root`, or any
other legal name is ordinary data in a **data** document: only a schema document gives these keys
meaning, and a data document never names the schema that describes it. A name outside the key
alphabet, such as the `$schema` of other formats, cannot occur in a Riffle document at all.

`definitions` maps a name to a node; the names are not constrained beyond the Riffle key alphabet.
A definition that is not reachable from `root` is a problem (§9).

## 4. Nodes

A node is a map with exactly one `type` key plus the keywords that type allows, plus
`description`, plus — only at a member position — `optional` and `default`. A node that is not a
map, a node with no `type`, a node with an unknown `type`, and a node holding a keyword that its
type does not allow are all problems (§11).

| `type` | Allowed keywords |
| --- | --- |
| `string` | `minLength`, `maxLength`, `notBlank`, `pattern`, `values` |
| `integer` | `min`, `max`, `values` |
| `decimal` | `min`, `max`, `minExclusive`, `maxExclusive`, `values` |
| `boolean` | none |
| `map` | `keys`, `otherKeys`, `keyPattern`, `minKeys`, `maxKeys`, `rules` |
| `array` | `items` (required), `minItems`, `maxItems`, `unique`, `uniqueKey` |
| `any` | none |
| `ref` | `definition` (required) |
| `taggedUnion` | `discriminator`, `cases` (both required) |
| `kindUnion` | `string`, `map`, `array` |

`description` is allowed on every node and is documentation only.

A **member node** is a node that is the value of an entry of a `keys` map. Only a member node may
carry:

| Keyword | Meaning |
| --- | --- |
| `optional` | `true`, meaning the key may be absent; its absence is not a problem |
| `default` | Any Riffle value, inserted by the defaults operation (§13) when the key is absent |

A `default` requires `optional : true` on the same node and MUST itself satisfy the node it
belongs to (§11). A member is required unless it declares `optional : true`.

The keywords `optional`, `notBlank`, `unique`, `uniqueKey` … — every keyword this format documents
as a **flag** — accept only the string `true`. `optional : false` is a problem, because omitting
the keyword already says false.

`values` lists the only accepted values of its node. It MUST NOT appear together with any other
constraint keyword of the same node, MUST NOT be empty, and MUST NOT hold two entries that are
equal for the node's type (§5, §11).

## 5. Scalar types

`string`, `integer`, `decimal`, and `boolean` describe a string value. Riffle has one value model
(§3 of `LANGUAGE_SPEC.md`), so all four describe a string and differ only in the interpretation
they require of it.

| Node | Data requirement |
| --- | --- |
| `string` | Any string |
| `integer` | Text accepted by the 64-bit integer interpretation (§13.1 of `LANGUAGE_SPEC.md`) |
| `decimal` | Text accepted by the decimal interpretation (§13.1, §13.2) |
| `boolean` | The text `true` or `false` |

Text that does not match the interpretation is a problem, and a number outside the portable range
of that interpretation is a problem (§10).

A `map`, `array`, `ref`, `taggedUnion`, or `kindUnion` value against one of these nodes is a kind
problem, not a format problem.

### Constraints

- `minLength`, `maxLength`: the number of **Unicode scalar values** (code points) in the string,
  not the number of encoding units of any host language.
- `notBlank : true`: the string MUST contain at least one character that is not `U+0009`, `U+000A`,
  `U+000D`, or `U+0020`. Only those four characters count as blank.
- `pattern`: an I-Regexp that MUST match the whole string (§6).
- `min`, `max` for `integer`: bounds written as 64-bit integer text; both bounds are inclusive.
- `min`, `max` for `decimal`: bounds written as decimal text; inclusive unless `minExclusive` or
  `maxExclusive` is `true`, which makes the corresponding bound exclusive. An exclusive flag
  requires its bound.
- `values`: the accepted values, written as text of the node's type.

### Comparison

Two values are **equal for a type** as follows, and every comparison in this specification that
says "per type" means exactly this:

| Type | Equality |
| --- | --- |
| `string` | Exact code-point sequence equality (§3.1 of `LANGUAGE_SPEC.md`) |
| `integer` | Equality of the interpreted integer |
| `decimal` | Mathematical equality of the interpreted decimal (§13.2 of `LANGUAGE_SPEC.md`) |

So `values : [0]` accepts the data `-0`, and `values : [1]` accepts the data `1.0`, because those
pairs are equal as an integer and as a decimal. `values : [1]` on a `string` node does not accept
`1.0`, because `1` and `1.0` are different strings.

A value that does not match the node's interpretation still participates in `values` comparison as
its own text, after its format problem has been reported (§12).

## 6. Patterns

`pattern` and `keyPattern` hold an **I-Regexp** as defined by RFC 9485, with the clarifications
below. Patterns are matched over Unicode scalar values, and the match MUST cover the **whole**
string: no searching, no partial match, and no implicit anchors. A `keyPattern` narrows which
keys a document may use; it can never widen what a key may be, because a string outside the key
alphabet of §6.2 of `LANGUAGE_SPEC.md` never reaches validation: the document fails to parse.

The profile is the RFC 9485 grammar exactly:

- Alternation `|`, concatenation, groups `( )`, the single-character wildcard `.`, character
  classes `[ ]`, and the quantifiers `?`, `*`, `+`, `{n}`, `{n,}`, `{n,m}`.
- `.` matches any code point except `U+000A` and `U+000D`.
- The only single-character escapes are `\(`, `\)`, `\*`, `\+`, `\-`, `\.`, `\?`, `\[`, `\\`, `\^`, `\]`, `\n`,
  `\r`, `\t`, `\{`, `\|`, `\}`, and `\$`. There is no `\d`, `\w`, `\s` or any other
  multi-character escape. RFC 9485 lists every one of these except `\$`, which this profile adds
  because it reads `$` as an assertion and a literal `($` then has no other spelling; `[$]` also
  matches a literal dollar, exactly as RFC 9485 allows.
- `\p{C}` and `\P{C}` are the only character-property escapes, where `C` is a Unicode general
  category from the RFC 9485 list: `L`, `Ll`, `Lm`, `Lo`, `Lt`, `Lu`, `M`, `Mc`, `Me`, `Mn`, `N`,
  `Nd`, `Nl`, `No`, `P`, `Pc`, `Pd`, `Pe`, `Pf`, `Pi`, `Po`, `Ps`, `S`, `Sc`, `Sk`, `Sm`, `So`, `Z`,
  `Zl`, `Zp`, `Zs`, `C`, `Cc`, `Cf`, `Cn`, `Co`. Blocks, script names, and an `Is` prefix are not
  part of the profile.
- Inside a class, a `-` is a range except as the first or last character, `^` negates only in the
  first position, and `[^]` is not a pattern.
- Reluctant and possessive quantifiers, backreferences, look-around, `\A`, `\z`, `\b`, inline
  flags, and named groups are not part of the profile.

Clarifications, where RFC 9485 leaves a choice open:

- `^` and `$` are zero-width assertions: `^` matches only at the start of the string and `$` only at
  its end. Because the match covers the whole string, both are redundant and SHOULD be omitted.
  Reading them as assertions is what RFC 9485 §5.3 assumes when it maps an I-Regexp to an
  ECMAScript, PCRE, RE2 or Ruby pattern unchanged.
- A range quantifier whose maximum count exceeds the limit documented in §14 is a problem (§11).
  A range quantifier with a maximum smaller than its minimum, and a quantifier with no preceding
  atom, are problems.
- The Unicode version used to resolve `\p{…}` is implementation-defined. Patterns that must behave
  identically everywhere SHOULD use only categories and characters that have been stable since
  Unicode 13.0.0.

A pattern that does not conform to the profile is a problem when the schema is loaded (§11), not
when data is validated.

A pattern is written as an ordinary Riffle string, so a pattern that begins with `[`, `{`, `"`, `@`,
`}`, `]`, `#` or `/#` MUST be quoted, because a Riffle value that begins with one of those
characters is not a bare string (§5.1 of `LANGUAGE_SPEC.md`). The quotes are delimiters and are not
part of the pattern: `"[a-z]+"` and `[a-z]+` where bare are the same pattern.

A pattern engine MUST NOT backtrack: matching MUST take time linear in the length of the matched
string for a fixed pattern (§14). An engine that can exhibit exponential backtracking is not
conformant unless it is restricted or replaced so that it cannot.

## 7. Maps and arrays

### Maps

| Keyword | Meaning |
| --- | --- |
| `keys` | A map from a key name to the node describing that key's value. Each entry is a member node (§4) |
| `otherKeys` | The node describing every key that `keys` does not name |
| `keyPattern` | An I-Regexp that every key described by `otherKeys` MUST match, whole |
| `minKeys`, `maxKeys` | Bounds on the number of entries in the map, counting every entry |

A `map` node describes a **closed** set of keys unless it declares `otherKeys`: a data key that
`keys` does not name is a problem (§10). `keyPattern` requires `otherKeys`, because it constrains
only keys that `otherKeys` describes (§11).

`keys` may be empty or absent. When `otherKeys` is present, `keys` still wins for the names it
declares, and `keyPattern` does not apply to those names.

### Arrays

| Keyword | Meaning |
| --- | --- |
| `items` | The node describing every element; required |
| `minItems`, `maxItems` | Bounds on the number of elements |
| `unique` | `true`: no two elements may be equal |
| `uniqueKey` | The name of a member of map elements whose values must be distinct |

`unique` compares elements **per type** (§5) when the item node is a scalar type, and by semantic
equality of the value model (§3.2 of `LANGUAGE_SPEC.md`) otherwise. `unique` and `uniqueKey` MUST
NOT both appear on one node (§11).

With `uniqueKey`, each element is a map; the value of the named member of each element that has
that member is collected and compared per the type of that member. An element that does not have
the named member takes no part in the comparison.

## 8. Rules

A `map` node may declare `rules`, a nonempty array in which each element is a map holding a `rule`
name and its operands. A rule may name only keys declared in the `keys` of the same map node; a
rule that names anything else is a problem (§11). Rules are checked in the order given (§12).

| `rule` | Operands | Satisfied when |
| --- | --- | --- |
| `exactlyOne` | `keys` | Exactly one of the named keys is present |
| `atMostOne` | `keys` | At most one of the named keys is present |
| `allOrNone` | `keys` | Either every named key is present, or none is |
| `requires` | `key`, `keys` | When `key` is present, every key in `keys` is present |
| `memberOf` | `key`, `array` | When both are present, the value of `key` is equal, per its type, to at least one element of `array` |

`exactlyOne`, `atMostOne` and `allOrNone` need at least two distinct members in `keys`, and every
member they name MUST be optional. `requires` names a `key` and at least one key in `keys`, and
every member named in `keys` MUST be optional; the rule constrains presence only, so `key` itself
may be required. `memberOf` names a scalar `key` member and an `array` member whose item node has
the same type as that `key` member; the rule is checked only when both are present, so presence is
governed by the member declarations alone (§11).

A rule element holds only `rule` and its operands; any other key in a rule element is a problem.

## 9. Unions, references and the root

| Node | Keywords | Meaning |
| --- | --- | --- |
| `any` | none | Any value of any kind |
| `ref` | `definition` | The node named by `definition` in the top-level `definitions` map |
| `taggedUnion` | `discriminator`, `cases` | The data value is a map whose `discriminator` member is a string equal, exactly, to one key of `cases`; the value is then described by that case |
| `kindUnion` | `string`, `map`, `array` | The kind of the data value selects which of these nodes describes it |

- A `taggedUnion` case MUST resolve (§9 `ref`) to a `map` node that declares the discriminator as a
  **required** member, and that member's node MUST have the type `string`. Matching a case performs
  no coercion and no case is tried more than once: the discriminator selects one case or the value
  is a problem (§10).
- A `kindUnion` MUST declare at least two of `string`, `map`, `array`. The node at `string` MUST
  resolve to one of `string`, `integer`, `decimal`, `boolean`; the node at `map` MUST resolve to
  `map` or `taggedUnion`; the node at `array` MUST resolve to `array`. None of the three may be a
  `kindUnion` itself. A `kindUnion` therefore never matches a `boolean`-looking value as a
  separate kind: booleans are strings, and a `kindUnion` has exactly one alternative for strings.
- `ref` names one entry of `definitions`. A `ref` to a name that `definitions` does not hold is a
  problem (§10).
- Every definition MUST be reachable from `root` through `ref` nodes.
- Following `ref` nodes MUST terminate. A cycle of `ref` nodes is allowed only if it passes through
  a position that consumes one level of data: a value of a `keys` map, the `otherKeys` node, or the
  `items` node. A cycle made only of other positions — `cases` values, `definitions` values, and the
  `string`, `map`, and `array` alternatives of a `kindUnion` — never consumes data and is a problem
  (§11).
- The node at `root` MUST resolve to a `map` node or a `taggedUnion` node, because the root of a
  Riffle document is a map. Any other kind of root is a problem (§10).

## 10. Problem contract

A problem has exactly these parts:

| Part | Normative? | Meaning |
| --- | --- | --- |
| category | yes | One of the category names below |
| path | yes | The path (§10 of `LANGUAGE_SPEC.md`) of the value, key, or missing member the problem is about |
| location | yes, when known | The line and column of the offending text; a value built in code, and a key of such a value, have no location |
| reason | no | Human-readable text; wording is implementation-specific |

Columns, the source-name format, and the wording of `reason` are implementation-specific and are
not compared by conformance. Lines, categories, paths, order, and the number of problems are
normative.

A reason MAY quote schema text — a bound, a list of allowed values, a case name, a pattern — but
MUST NOT contain data: no string, key, or scalar from a data document appears in a category, a
path beyond the keys it names, or a reason, because configuration files hold secrets
(§10 of `LANGUAGE_SPEC.md`). A reason MAY name the keys involved in a rule.

### Data categories

Reported while validating data (§12) and while checking a schema document against the meta-schema
(§11). "Reported at" fixes both the location and the path.

| Category | Trigger | Reported at |
| --- | --- | --- |
| `MISSING` | A required member is absent, or a discriminator member is absent | The map that should hold it; the path of the member |
| `WRONG_KIND` | The kind of the value is not the kind the node requires, and no `kindUnion` alternative fits | The value |
| `INVALID_FORMAT` | Text does not match the §13 interpretation required by an `integer`, `decimal`, or `boolean` node | The value |
| `OUT_OF_RANGE` | Text is outside the portable range of the interpretation, or outside `min`, `max` | The value |
| `NOT_ALLOWED` | Not one of `values`, or a discriminator that matches no `cases` entry | The value |
| `PATTERN_MISMATCH` | `pattern` or `keyPattern` does not match | The value, or the key |
| `INVALID_LENGTH` | `minLength` or `maxLength` is violated | The value |
| `BLANK` | `notBlank` is violated | The value |
| `INVALID_SIZE` | `minItems`, `maxItems`, `minKeys`, or `maxKeys` is violated | The collection |
| `DUPLICATE` | `unique` or `uniqueKey` is violated | The later element |
| `UNKNOWN_KEY` | A key that `keys` does not name, when the node has no `otherKeys` | The key |
| `RULE_VIOLATION` | A rule of §8 is not satisfied | The map, except `memberOf`, which reports at the `key` member |

### Schema categories

Reported only while loading a schema (§11). Each is reported at the offending text in the **schema
document**, and its path is the path of that text within the schema document.

| Category | Trigger |
| --- | --- |
| `UNSUPPORTED_VERSION` | `riffleSchema` is not the string `1` |
| `INVALID_PATTERN` | A `pattern` or `keyPattern` is not a valid I-Regexp, or a range quantifier is malformed or too large (§6) |
| `INVALID_CONSTRAINT` | A keyword combination or a declared bound is not usable: an empty allowed range, `values` combined with another constraint, duplicate `values` entries, an exclusive flag without its bound, `keyPattern` without `otherKeys`, `unique` with `uniqueKey`, `optional` or `default` on a node that is not a member, `default` without `optional : true`, a malformed `taggedUnion` or `kindUnion` (§9) |
| `INVALID_DEFAULT` | A `default` does not satisfy the node it belongs to |
| `INVALID_RULE` | A rule names a key that is not a declared member, repeats or requires fewer than two members, requires a member that is not optional, or has operands that do not fit the rule (§8) |
| `UNKNOWN_DEFINITION` | A `ref` names a definition that is not declared |
| `UNUSED_DEFINITION` | A definition is not reachable from `root` |
| `CIRCULAR_DEFINITION` | A cycle of `ref` nodes consumes no data (§9) |
| `INVALID_ROOT` | `root` does not resolve to a `map` node or a `taggedUnion` node |

## 11. Loading a schema

Loading a schema document MUST run these phases in order. A later phase runs only when the earlier
phases produced no problem, because the semantic checks of phase 3 need well-formed structure.

1. **Version.** If the document has no `riffleSchema` key, report `MISSING`. If its value is not
   the string `1`, report `UNSUPPORTED_VERSION`. In either case stop: no other phase runs, and no
   other problem is reported.
2. **Structure.** Validate the schema document as data against the meta-schema of §15, collecting
   **every** problem it reports. Paths and locations are those of the schema document.
3. **Semantics.** Walk the schema in the document order of its nodes and collect every problem from
   the checks below, then the definition checks.

Checks at a node, in this order:

1. Its keyword values are usable together: `values` is not combined with another constraint of the
   same type; every bound pair leaves a non-empty range, including the exclusive flags; an exclusive
   flag has its bound; `keyPattern` has `otherKeys`; `unique` and `uniqueKey` are not both present;
   `optional` and `default` appear only on a member node; `default` has `optional : true`; the
   values listed by `values` are distinct for the node's type. (`INVALID_CONSTRAINT`)
2. Every `pattern` and `keyPattern` is a valid I-Regexp under §6. (`INVALID_PATTERN`)
3. If the node is a `taggedUnion`, each case resolves to a `map` node that declares the
   discriminator as a required `string` member. (`INVALID_CONSTRAINT`)
4. If the node is a `kindUnion`, it declares at least two alternatives and each alternative
   resolves to a node of the kind §9 requires. (`INVALID_CONSTRAINT`)
5. If the node is a `ref`, its definition exists. (`UNKNOWN_DEFINITION`)
6. If the node is `root`, it resolves to a `map` node or a `taggedUnion` node. (`INVALID_ROOT`)
7. If the node is a member node with a `default`, that value satisfies the member node.
   (`INVALID_DEFAULT`)
8. If the node is a `map` node, each rule in `rules` is well-formed under §8. (`INVALID_RULE`)

A node stops after the first problem its own checks report, because that problem already explains
why the node cannot be used; its child nodes are still walked, and other nodes are checked as if
nothing had happened.

The walk visits a node's child nodes in the document order of the entries that hold them: the
entries of `keys` in document order, then `otherKeys`, then `items`, then the entries of `cases` in
document order, then the `string`, `map`, and `array` alternatives of a `kindUnion`, then a `ref`
target only once per definition. The top-level entries `root` and `definitions` are visited in the
document order of the schema document.

After the walk, in the document order of `definitions`:

9. A definition that is not reachable from `root` is `UNUSED_DEFINITION`.
10. A definition that takes part in a `ref` cycle that consumes no data is `CIRCULAR_DEFINITION`.

Loading a schema never reads a file or the network beyond reading the schema document itself, and
never reads the data document.

## 12. Validation

Validation checks a data document against the root node of a loaded schema. It MUST report
**every** problem, in the order below, and MUST NOT stop at the first one. Validation never inserts,
reorders, rewrites, or coerces anything (§1, §13).

For one value against one node:

1. Resolve a `ref` node to its definition and continue with that node.
2. If the node is a `kindUnion`, select the alternative for the kind of the value. If the value has
   no matching alternative, report `WRONG_KIND` at the value and stop.
3. If the node is a `taggedUnion`, the value MUST be a map: if it is not, report `WRONG_KIND` at the
   value and stop. The discriminator member MUST be present: if it is absent, report `MISSING` at
   the map with the path of the discriminator. If it is present but is not a string, report
   `WRONG_KIND` at that member and stop. If its text matches no entry of `cases`, report
   `NOT_ALLOWED` at that member and stop, because the offending text is the discriminator rather
   than the whole map. Otherwise continue with the selected case.
4. Check the kind the node requires. On a mismatch, report `WRONG_KIND` at the value and stop: no
   problem is reported for anything inside a value of the wrong kind.
5. For a scalar node, check the value:
   - `integer`, `decimal`, `boolean`: the interpretation first. If the text does not match the
     interpretation, report `INVALID_FORMAT` and stop; if it matches but is outside the portable
     range, report `OUT_OF_RANGE` and stop. Then `min`/`max` (`OUT_OF_RANGE`), then `values`
     (`NOT_ALLOWED`).
   - `string`: `values` (`NOT_ALLOWED`) when present; otherwise `minLength` and `maxLength`
     (`INVALID_LENGTH`), then `notBlank` (`BLANK`), then `pattern` (`PATTERN_MISMATCH`). Each of
     these checks is independent, so one value can produce more than one problem.
6. For an `array` node: every element, in element order; then `minItems` and `maxItems`
   (`INVALID_SIZE`); then `unique` and `uniqueKey` (`DUPLICATE`), reporting the later element of
   each pair that is equal.
7. For a `map` node:
   - each entry in document order: if `keys` names the key, check the value against the member node;
     otherwise, if `otherKeys` is present, check `keyPattern` against the key (`PATTERN_MISMATCH`
     at the key) and then check the value against `otherKeys`; otherwise report `UNKNOWN_KEY` at the
     key;
   - then, in the document order of `keys`, each required member that is absent (`MISSING`);
   - then `minKeys` and `maxKeys` (`INVALID_SIZE`);
   - then each rule, in the order given in `rules` (`RULE_VIOLATION`).

The path of a problem is computed during this traversal, starting from the path of the value being
validated or from the root when that value has no path. Validating a subtree therefore reports paths
that begin where that subtree begins, and a document assembled in code still gets paths even where
it has no locations.

Two runs of validation on equal inputs MUST produce equal problem lists, in the same order.

## 13. Defaults

Validation never inserts anything, and loading a schema never does either. An implementation that
offers a defaults operation MUST behave as follows:

1. Validate the input. If it produces any problem, report those problems and insert nothing.
2. Otherwise return a new value tree in which every absent optional member that declares a
   `default` holds that value, inserted **after** the entries the input already had and in the
   document order of `keys`.
3. Filling recurses into the values the input already had and into the values it inserted.
4. An inserted value is the value written in the schema, with no location, and with its path
   computed from the position it is inserted at.
5. Nothing else changes: no entry is reordered, removed, renamed, or coerced, and a value that was
   present keeps exactly the value the document gave it, even if it also has a `default`.

Applying defaults to a document that already satisfies every default inserts nothing and therefore
produces an equal tree.

## 14. Resource limits

- A schema document and a data document have no portable nesting limit (§12 of
  `LANGUAGE_SPEC.md`). A host may supply a maximum depth; exceeding it while reading either
  document is reported by the parser as `RESOURCE_LIMIT_EXCEEDED`.
- The number of nested `ref` resolutions is bounded by the depth of the data: §9 forbids the only
  cycles that could grow without consuming data, so validation always terminates.
- Validation time is linear in the size of the data document for a fixed schema, and a fixed
  pattern is matched in time linear in the length of the matched string. An implementation MUST NOT
  let the number of problems grow without bound: there is no mandatory limit on the number of
  problems, because it is bounded by the size of the document.
- A range quantifier (§6) with a maximum count greater than 1000 MUST be rejected as
  `INVALID_PATTERN`; an implementation MAY document a lower limit, and MUST NOT reject any fixture
  of the conformance suite because of it. A pattern whose compiled matcher needs more states than a
  documented limit MAY also be rejected as `INVALID_PATTERN`; nested range quantifiers multiply
  the number of states. The reference implementation documents a limit of 10,000 states, which no
  conformance fixture approaches. It also rejects a pattern whose groups nest more than 100 levels,
  because parser recursion is not bounded by the state count; no conformance fixture approaches
  that limit either.
- An implementation that offers defaults MUST traverse the input and inserted values without
  recursion that can exhaust the host stack. When a host supplies a depth limit, the filled result
  MUST NOT exceed it.

Implementations MAY impose further documented limits, which MUST NOT reject any fixture of the
conformance suite and MUST NOT change the meaning of a conforming document within these limits.

## 15. Meta-schema

The meta-schema below is normative. It is the structure against which phase 2 of loading a schema
runs (§11). The same text MUST be published with an implementation as its schema-validation
contract: the reference implementation keeps byte-identical copies in
`src/main/resources/io/github/dhoard/riffle/meta-schema.riffle` and
`conformance/schema/meta-schema.riffle`.

The meta-schema validates itself: loading it succeeds, and validating it as data against itself
produces no problems.

Rules that the meta-schema cannot express are checked semantically in phase 3 (§11) instead. Those
are: the usability of keyword combinations and bounds, `values` exclusivity and `values`
duplicates, `optional` and `default` appearing only on a member node, `default` requiring
`optional : true`, `default` satisfying its node, the `taggedUnion` and `kindUnion` shape rules of
§9, the well-formedness of rules, `ref` existence, reachability and cycles, the root kind, and
pattern validity. The meta-schema additionally permits `optional` and `default` on every node,
because it cannot express the member-position rule; phase 3 rejects them elsewhere.

```riffle
riffleSchema : 1
description : The meta-schema for Riffle schema version 1; it validates every schema document.
definitions : {
    version : {
        type : string
    }
    typeName : {
        type : string
        values : [
            any
            array
            boolean
            decimal
            integer
            kindUnion
            map
            ref
            string
            taggedUnion
        ]
    }
    ruleName : {
        type : string
    }
    text : {
        type : string
        notBlank : true
    }
    count : {
        type : integer
        min : 0
    }
    integerText : {
        type : integer
    }
    decimalText : {
        type : decimal
    }
    flag : {
        type : string
        values : [
            true
        ]
    }
    documentation : {
        type : string
    }
    anyValue : {
        type : any
    }
    keyList : {
        type : array
        minItems : 1
        items : {
            type : ref
            definition : text
        }
    }
    node : {
        type : taggedUnion
        discriminator : type
        cases : {
            any : {
                type : ref
                definition : anyNode
            }
            array : {
                type : ref
                definition : arrayNode
            }
            boolean : {
                type : ref
                definition : booleanNode
            }
            decimal : {
                type : ref
                definition : decimalNode
            }
            integer : {
                type : ref
                definition : integerNode
            }
            kindUnion : {
                type : ref
                definition : kindUnionNode
            }
            map : {
                type : ref
                definition : mapNode
            }
            ref : {
                type : ref
                definition : referenceNode
            }
            string : {
                type : ref
                definition : stringNode
            }
            taggedUnion : {
                type : ref
                definition : taggedUnionNode
            }
        }
    }
    anyNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    stringNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            minLength : {
                type : ref
                definition : count
                optional : true
            }
            maxLength : {
                type : ref
                definition : count
                optional : true
            }
            notBlank : {
                type : ref
                definition : flag
                optional : true
            }
            pattern : {
                type : ref
                definition : text
                optional : true
            }
            values : {
                type : array
                minItems : 1
                items : {
                    type : string
                }
                optional : true
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    integerNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            min : {
                type : ref
                definition : integerText
                optional : true
            }
            max : {
                type : ref
                definition : integerText
                optional : true
            }
            values : {
                type : array
                minItems : 1
                items : {
                    type : integer
                }
                optional : true
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    decimalNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            min : {
                type : ref
                definition : decimalText
                optional : true
            }
            max : {
                type : ref
                definition : decimalText
                optional : true
            }
            minExclusive : {
                type : ref
                definition : flag
                optional : true
            }
            maxExclusive : {
                type : ref
                definition : flag
                optional : true
            }
            values : {
                type : array
                minItems : 1
                items : {
                    type : decimal
                }
                optional : true
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    booleanNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    mapNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            keys : {
                type : map
                otherKeys : {
                    type : ref
                    definition : node
                }
                optional : true
            }
            otherKeys : {
                type : ref
                definition : node
                optional : true
            }
            keyPattern : {
                type : ref
                definition : text
                optional : true
            }
            minKeys : {
                type : ref
                definition : count
                optional : true
            }
            maxKeys : {
                type : ref
                definition : count
                optional : true
            }
            rules : {
                type : array
                minItems : 1
                items : {
                    type : ref
                    definition : ruleNode
                }
                optional : true
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    arrayNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            items : {
                type : ref
                definition : node
            }
            minItems : {
                type : ref
                definition : count
                optional : true
            }
            maxItems : {
                type : ref
                definition : count
                optional : true
            }
            unique : {
                type : ref
                definition : flag
                optional : true
            }
            uniqueKey : {
                type : ref
                definition : text
                optional : true
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    referenceNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            definition : {
                type : ref
                definition : text
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    taggedUnionNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            discriminator : {
                type : ref
                definition : text
            }
            cases : {
                type : map
                minKeys : 1
                otherKeys : {
                    type : ref
                    definition : node
                }
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    kindUnionNode : {
        type : map
        keys : {
            type : {
                type : ref
                definition : typeName
            }
            string : {
                type : ref
                definition : node
                optional : true
            }
            map : {
                type : ref
                definition : node
                optional : true
            }
            array : {
                type : ref
                definition : node
                optional : true
            }
            description : {
                type : ref
                definition : documentation
                optional : true
            }
            optional : {
                type : ref
                definition : flag
                optional : true
            }
            default : {
                type : ref
                definition : anyValue
                optional : true
            }
        }
    }
    ruleNode : {
        type : taggedUnion
        discriminator : rule
        cases : {
            exactlyOne : {
                type : map
                keys : {
                    rule : {
                        type : ref
                        definition : ruleName
                    }
                    keys : {
                        type : ref
                        definition : keyList
                    }
                }
            }
            atMostOne : {
                type : map
                keys : {
                    rule : {
                        type : ref
                        definition : ruleName
                    }
                    keys : {
                        type : ref
                        definition : keyList
                    }
                }
            }
            allOrNone : {
                type : map
                keys : {
                    rule : {
                        type : ref
                        definition : ruleName
                    }
                    keys : {
                        type : ref
                        definition : keyList
                    }
                }
            }
            requires : {
                type : map
                keys : {
                    rule : {
                        type : ref
                        definition : ruleName
                    }
                    key : {
                        type : ref
                        definition : text
                    }
                    keys : {
                        type : ref
                        definition : keyList
                    }
                }
            }
            memberOf : {
                type : map
                keys : {
                    rule : {
                        type : ref
                        definition : ruleName
                    }
                    key : {
                        type : ref
                        definition : text
                    }
                    array : {
                        type : ref
                        definition : text
                    }
                }
            }
        }
    }
}
root : {
    type : map
    keys : {
        riffleSchema : {
            type : ref
            definition : version
        }
        description : {
            type : ref
            definition : documentation
            optional : true
        }
        root : {
            type : ref
            definition : node
        }
        definitions : {
            type : map
            otherKeys : {
                type : ref
                definition : node
            }
            optional : true
        }
    }
}
```

## 16. Conformance examples

`conformance/schema/` holds the full suite, its manifest, and the contract for running it. The
examples here are the ones a first implementation should pass before opening the suite.

### 16.1 Valid schemas

The schema of §2, the meta-schema of §15, and each schema below MUST load with no problem.

```riffle
riffleSchema : 1
root : {
    type : map
    otherKeys : {
        type : any
    }
}
```

```riffle
riffleSchema : 1
definitions : {
    tree : {
        type : map
        keys : {
            name : {
                type : string
            }
            children : {
                type : array
                optional : true
                items : {
                    type : ref
                    definition : tree
                }
            }
        }
    }
}
root : {
    type : ref
    definition : tree
}
```

```riffle
riffleSchema : 1
root : {
    type : map
    keys : {
        shape : {
            type : taggedUnion
            discriminator : shape
            cases : {
                circle : {
                    type : map
                    keys : {
                        shape : {
                            type : string
                        }
                        radius : {
                            type : decimal
                            min : 0
                        }
                    }
                }
                square : {
                    type : map
                    keys : {
                        shape : {
                            type : string
                        }
                        side : {
                            type : decimal
                            min : 0
                        }
                    }
                }
            }
        }
    }
}
```

### 16.2 Invalid schemas

Each input is one schema document. It MUST load with exactly the listed problem.
| Input | Category | Path |
| --- | --- | --- |
| `root : {\n    type : any\n}` | `MISSING` | `riffleSchema` |
| `riffleSchema : 2\nroot : {\n    type : any\n}` | `UNSUPPORTED_VERSION` | `riffleSchema` |
| `riffleSchema : 1\nroot : {\n    type : any\n}\nversion : 1` | `UNKNOWN_KEY` | `version` |
| `riffleSchema : 1\nroot : {\n    type : string\n    minLength : -1\n}` | `OUT_OF_RANGE` | `root.minLength` |
| `riffleSchema : 1\nroot : {\n    type : integer\n    minLength : 3\n}` | `UNKNOWN_KEY` | `root.minLength` |
| `riffleSchema : 1\nroot : {\n    type : maps\n}` | `NOT_ALLOWED` | `root.type` |
| `riffleSchema : 1\nroot : {\n    type : map\n    keyPattern : x\n}` | `INVALID_CONSTRAINT` | `root` |
| `riffleSchema : 1\nroot : {\n    type : string\n    pattern : "([a-z]"\n}` | `INVALID_PATTERN` | `root.pattern` |
| `riffleSchema : 1\ndefinitions : {\n    unused : {\n        type : any\n    }\n}\nroot : {\n    type : map\n}` | `UNUSED_DEFINITION` | `definitions.unused` |
| `riffleSchema : 1\nroot : {\n    type : integer\n    min : 1\n    max : 0\n}` | `INVALID_CONSTRAINT` | `root` |
| `riffleSchema : 1\nroot : {\n    type : map\n    keys : {\n        a : {\n            type : any\n            default : 1\n        }\n    }\n}` | `INVALID_CONSTRAINT` | `root.keys.a` |
| `riffleSchema : 1\nroot : {\n    type : string\n}` | `INVALID_ROOT` | `root` |

The first two rows show phase one of §11: an unreadable version stops the load, so nothing else the
document gets wrong is reported. `any` cannot be the root node, because the root of a document is a
map (§9); a schema that wants to accept any document says so with `otherKeys`.

### 16.3 Validation cases

Against the schema of §2:

|riffle| Category | Path |
| --- | --- | --- |
| `name : gateway` | — | no problems |
| `name : gateway\nport : 70000` | `OUT_OF_RANGE` | `port` |
| `name : gateway\ncommand : run\nurl : https://x` | `RULE_VIOLATION` | `(root)` |
| `name : gateway\nunknown : 1` | `UNKNOWN_KEY` | `unknown` |
| `name :` | `BLANK` | `name` |

Against `root : { type : map, keys : { mode : { type : kindUnion, string : { type : integer }, map
: { type : map }, array : { type : array } } } }`:

|riffle| Category | Path |
| --- | --- | --- |
| `mode : 3` | — | no problems |
| `mode : {` … `}` | — | no problems |
| `mode : 1.5` | `INVALID_FORMAT` | `mode` |

Against a schema whose root map has `type : array, items : { type : decimal }, unique : true` in
`keys` under the name `values`:

|riffle| Category | Path |
| --- | --- | --- |
| `values : [\n    1\n    1.0\n]` | `DUPLICATE` | `values[1]` |
| `values : [\n    1\n    -0\n]` | — | no problems |

### 16.4 Required test categories

An implementation SHOULD have table-driven tests covering: every node type and every keyword of
that type; each category of §10 from data and from a schema; each phase of §11 and the fact that a
failing phase stops the later ones; the order of §12, including the order inside one map, one
array, and one value; `MISSING` with and without a location; every rule of §8; `taggedUnion` and
`kindUnion` selection and rejection; `ref` reuse, recursion through `items` and `keys`, unknown,
unused and circular definitions; the root rule; `values` equality per type, including `-0` and
`1.0`; `unique` and `uniqueKey`; `keyPattern` with and without `otherKeys`; the closed vocabulary;
secrecy of data in every category; path rendering for keys containing `.`, `[`, `"` and `U+FEFF`,
and for the root; defaults insertion position, order, recursion and absence; whole-string pattern
matching; a pathological pattern such as `(a+)+b` against 100000 `a` characters; and identical
results from repeated or concurrent validation with one schema.

## 17. Non-goals for schema version 1

The following are deliberately excluded:

- Building a schema in a host language instead of writing one; a schema document is the only way to
  obtain a schema.
- Extensions, custom keywords, host-language data types, and named formats such as `email` or
  `date-time`.
- A schema reference written in a data document: a data document never names its schema, and a
  legal key such as `riffleSchema` in data is an ordinary key.
- Imports between schema documents, and sharing nodes across documents.
- Warnings, hints, and any problem severity below a problem.
- Implicit insertion of defaults, computed values, coercion of a string into a number, and any
  rewriting of a document by a schema.
- Rules across different maps, and any computation over values.
- Untagged unions, in which every alternative is tried until one fits.
- Schema-aware serialization, and generating a document from a schema.
- Binding a document to typed records, classes, or generated code.

These may be considered in future versions only through explicit changes to this specification.

## 18. Implementation acceptance criteria

An implementation of schema version 1 is conformant when it:

1. Loads every valid schema of the suite, including the meta-schema, and rejects every invalid one
   with exactly the listed categories, paths and lines (§11).
2. Reports, for every schema-plus-document case of the suite, exactly the listed problem list, in
   the listed order, with no problem added, missing, or reordered (§12).
3. Enforces the closed vocabulary: no unknown or misplaced keyword is accepted, and no extension
   mechanism is offered (§4).
4. Never places a data value in a category, a path beyond the keys it names, or a reason (§10).
5. Matches `pattern` and `keyPattern` against the whole string, over code points, with an engine
   that cannot backtrack (§6).
6. Applies the limits of §14, including the pattern range-quantifier limit and the defaults
   recursion limit.
7. Publishes the meta-schema of §15 unchanged, and shows that it loads and validates itself.
8. Produces identical problem lists for repeated and concurrent runs on one schema and equal
   documents (§12).
9. Applies defaults exactly as §13 states, and never inserts a default in any other situation.
10. Does not introduce undocumented extensions that change the meaning of a conforming version 1
    schema or document.

## Appendix A: Reference Java mapping (non-normative)

This appendix describes the Java reference implementation only. It is informative and does not
define Riffle schemas.

| Spec concept | Java 17 |
| --- | --- |
| schema | `RiffleSchema`, immutable and thread-safe |
| loading | `RiffleSchema.parse(String)`, `parse(Reader)`, `parse(Path)`; a syntax error is `RiffleParseException`, an invalid schema is `RiffleSchemaException` |
| validating | `RiffleSchema.validate(RiffleObject)`, returning an immutable `List<RiffleProblem>` that is empty exactly when the document is valid; and `Riffle.parse(text, schema)`, which is `Riffle.parse(text)` followed by validation and throws `RiffleValidationException` when the document is invalid |
| defaults | `RiffleSchema.applyDefaults(RiffleObject)` |
| problem | `RiffleProblem`: `category()`, `path()`, `location()`, `reason()`, value equality |
| category | `RiffleValidationError`, one constant per category of §10 |
| path | `RifflePath`, which renders §10 of `LANGUAGE_SPEC.md`; `RiffleValue.path()` and `RiffleAccessException.path()` return it |

All three `parse` forms exist for both a schema and a document, so a caller can hold both in memory
and need never touch a file. `parse(Reader)` does not close the reader; `parse(Path)` decodes
strict UTF-8 and names the file in locations.

`RiffleSchemaException` and `RiffleValidationException` are final subclasses of the sealed
`RiffleException`, so a caller can tell a broken schema, which is a programming error, from invalid
configuration, which is a user error. Both expose the same ordered `List<RiffleProblem>`. Their
message is the first problem, followed by `(and N more problems)` when there are more.

Java counts string length in Unicode code points, not UTF-16 code units, so §5 matches the
specification rather than `String.length()`. Decimal comparison uses
`BigDecimal.compareTo`, not the scale-sensitive `BigDecimal.equals`. Java columns count UTF-16
code units, which is implementation-specific per §10. The pattern engine is a Thompson
construction simulated in lockstep over code points, so it never backtracks and does not use
`java.util.regex`. The documented pattern limits are a maximum range-quantifier count of 1000 and
100 nested groups (§6, §14). Nesting is otherwise unbounded; a host can pass
`RiffleOptions.maxDepth(int)` to any parse, write, validation, defaults, or Java-conversion
operation (§12–§14).

## Appendix B: Informative host-language notes (non-normative)

These notes are guidance for other implementations, not requirements.

**Pattern engines.** RFC 9485 §5 maps an I-Regexp onto other dialects: replace every unescaped `.`
outside a character class with `[^\n\r]`, then anchor the whole expression with `^(?:` … `)$` for
ECMAScript, or `\A(?:` … `)\z` for PCRE, RE2 and Ruby. That mapping is enough to *evaluate* an
I-Regexp, but two parts of this specification need care:

- An engine that backtracks can take exponential time on a pattern such as `(a+)+b` (§6, §14),
  which the ECMAScript and PCRE mappings do not prevent. A mapping onto a guaranteed
  linear-time engine — RE2, Go `regexp`, Rust `regex`, or a Thompson construction of your own —
  satisfies §6 and §14 directly.
- The RFC 9485 grammar is narrower than the dialects it maps to. Checking that a pattern conforms
  to the profile of §6, rather than accepting whatever the host dialect parses, needs a small
  parser over the RFC 9485 ABNF.

`\p{…}` needs the Unicode general-category tables of the host platform: `Character.getType` in
Java, `unicode::general_category` in Rust, `unicodedata` in Python, `unicode.IsLetter` and friends
in Go, or the equivalent in another language. The Unicode version behind those tables is
implementation-defined (§6).

**String length.** §5 counts code points: `String.length` in Java counts UTF-16 units,
`String.length` in JavaScript counts UTF-16 units, `len()` in Python counts code points, and a
Rust `str` gives both through `chars().count()` and `len()`. Use code points.

**Decimal equality.** §5 needs mathematical equality: `BigDecimal.compareTo` in Java, `a == b` on
`decimal.Decimal` in Python, `==` on a decimal or rational type in Rust, and a comparison of the
normalized coefficient and exponent in any language that keeps the text and an exponent directly.
Scale-sensitive equality — `BigDecimal.equals` in Java, or comparing two digit strings — is wrong.

**Ordered maps.** Riffle maps preserve insertion order (Appendix B of `LANGUAGE_SPEC.md`), and a
`keys` map, a `cases` map, and a `definitions` map in a schema document are read in that order,
because §11 and §12 walk them in document order.

> **Guidance for an AI implementer:** Load a schema in the three phases of §11 and stop as soon as
> a phase reports anything. Validate with the traversal and the check order of §12, reporting every
> problem rather than the first one, and give each problem the category, path and reported-at
> location of the table in §10. Reuse the ordinary parser and the interpretations of
> `LANGUAGE_SPEC.md` §13; do not invent a second number grammar, do not coerce a string, and never
> insert a value during validation. Keep data out of every message. Use or build a matching engine
> that cannot backtrack, and check a pattern against the RFC 9485 profile of §6 before using it.
> When a case is not specified here, prefer a problem with a regression test over a permissive
> guess.
