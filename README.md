# Riffle

**Riffle 0.1.0** is a small, deterministic, line-oriented configuration language built on one principle: **the text you write is exactly the text the program receives.** Java 17 is the reference implementation in this repository, but the language is defined independently of Java.

[`LANGUAGE_SPEC.md`](LANGUAGE_SPEC.md) is the normative specification of the language for every implementation, and [`SCHEMA_SPEC.md`](SCHEMA_SPEC.md) is the normative specification of **schemas**: schema documents and schema-validated parsing. This file is the Java binding guide for both. Java type names in the specs appear only in non-normative appendices.

## Why Riffle

- **Values are literal text to the end of the line.** There is no quoting to remember, no escaping, and no type guessing. `password : a#b"c\d` is exactly `a#b"c\d`.
- **The program decides what a value means.** `port : 8080` is a string until the program asks for an integer, and the spec defines exactly which text each accessor accepts.
- **Block comments nest and occupy whole lines**, so you can disable a section that already contains comments.
- **Raw multiline blocks** hold certificates, SQL and scripts with no escaping at all.

```riffle
# Comments occupy whole lines
schemaVersion : 1
provider : chatgpt
endpoint : https://example.org/v1?region=eu#primary

capabilities : {
    tools : true
    contextWindow : 272000
    temperature : 0.7

    reasoning : [
        low
        medium
        high
    ]
}

/#
Disabled for now. Block comments nest.
fallback : {
    provider : other
}
#/

systemPrompt : """
    You are a careful assistant.
    Paths look like C:\temp\new and that is fine.
    """
```

> **A `#` after a value is data.** `name : prod # primary` is the string `prod # primary`. This is the deliberate cost of not requiring escapes: everything after the colon belongs to the value. Put comments on their own line.

### How it compares

| | Riffle | JSON | YAML 1.2 | TOML 1.0 | StrictYAML |
| --- | --- | --- | --- | --- | --- |
| **Type inference** | None; the program picks an accessor | None; syntax marks each type | Plain scalars resolve through a schema | None; syntax marks each type | None; strings unless a schema says otherwise |
| **Quoting and escaping** | Values are bare and literal; quotes only for edge cases | Strings are always quoted, with backslash escapes | Plain, single-quoted and double-quoted styles | Strings are always quoted; literal strings skip escapes | A restricted subset of YAML's styles |
| **Comments** | Whole-line `#`, nested `/#` … `#/` blocks | None | `#` to end of line | `#` to end of line | `#` to end of line |
| **Multiline strings** | Raw `"""` blocks | `\n` escapes only | Literal and folded block scalars | `"""` and `'''` strings | Literal and folded block scalars |
| **Number precision** | Text is kept exactly; decimals read as exact base-10 | Left to the implementation; often IEEE 754 doubles | Left to the implementation | 64-bit integers; floats are IEEE 754 binary64 | Text is kept until a schema converts it |

Riffle is narrower than these formats. It has no inline nonempty collections, no trailing comments, no anchors, aliases or merge keys, and no tags. A Riffle schema is a separate document that says what the values in a configuration file must look like; it never retypes a value, and the text a value holds stays exactly as written.

## Language

### Lines and whitespace
- Files are UTF-8. A byte-order mark at the start is ignored; a BOM anywhere else is an error.
  The writer escapes U+FEFF inside values, so such a value still round-trips; a key cannot hold it.
- Line endings are LF or CRLF. A lone carriage return is an error.
- Spaces and tabs separate tokens and indent lines. Indentation is significant only inside a raw block.
- Blank lines and comment lines are ignored, including inside collections.
- Every entry and array element occupies its own line. A nonempty collection's opener must be the last token on its line and its closer must be on its own line; an empty collection may also be written as an empty block.
- Only a raw block spans lines.

### Comments
- A comment occupies a whole line: a line whose first non-whitespace character is `#` is ignored.
- A `#` after a value is part of the value. `k : hello #world` has the value `hello #world`, and `url : https://example.org/a#fragment` keeps its fragment.
- Because a comment cannot follow a value, `k : "v" # note` and `k : {} # note` are rejected (`TRAILING_TOKENS`). Put the comment on its own line.
- A block comment also occupies whole lines: it opens on a line whose first non-whitespace characters are `/#`, closes one nesting level on a line containing only `#/`, and hides everything between, including invalid Riffle. `k : v /# note #/` is the string `v /# note #/`.
- `//` is never a comment marker: `path//segment` and `a // b` are bare strings.

### Root and maps
- The document root is an implicit, insertion-ordered map.
- An empty map may be written `{}` or as `{` and `}` on separate lines; a nonempty map is written in block form.
- Nonempty collections are never written inline, and the canonical writer writes empty ones as `{}` or `[]`.

### Filling an empty collection
An empty collection may be written inline or as an empty block; a populated one is written in block form. The writer normalizes an empty block to the inline form. Turning an empty collection into a populated one moves the content onto its own lines. These are two separate documents:

Empty:

```riffle
options : {
    chatgpt-oauth : {}
}
```

Populated:

```riffle
options : {
    chatgpt-oauth : {
        key : Test value
    }
}
```

Arrays work the same way: `tools : []` becomes `tools : [`, one element per line, then `]`. Writing `chatgpt-oauth : { key : Test value }` is rejected, because a value starting with `{` must be quoted or open a block.

### Keys
- A key matches `[A-Za-z0-9_][A-Za-z0-9_.-]*`: letters, digits, `_`, `-` and `.`, starting with a letter, digit or `_`. Examples: `provider`, `chatgpt-oauth`, `1`, `schema.version`.
- A key may be written **quoted**, but quoting changes only the spelling: the decoded text must match the same pattern, so `"schema.version" : value` names the key `schema.version` while `"display name" : value` and `"" : value` are errors.
- Canonical output never quotes a key: `"schema.version" : value` is written `schema.version : value`.
- A dot in a key is a literal dot, not a path separator.
- Keys are always strings and are case-sensitive. Duplicate keys in one map are an error, and a bare spelling collides with the quoted spelling of the same text.

### Values
A value is one of three kinds.

| Kind | Syntax | Java 17 type |
| --- | --- | --- |
| string | `text`, `"text"`, or a `"""` block | `RiffleString` |
| map | `{}` or `{ … }` | `RiffleObject` |
| array | `[]` or `[ … ]` | `RiffleArray` |

There is no null. An absent key is the only way to express "no value".

### Bare strings
- Trimmed at both ends, interior whitespace preserved. `key :` is the empty string.
- The first character must not be `@`, `{`, `[`, `}`, `]` or `"`; quote values that start with them, as in `pkg : "@scope/name"`. `@` is reserved for possible future markers.
- `#`, `//` and backslashes are ordinary data.
- Control characters are invalid in a bare string; quote and escape them.

### Quoted strings
- A quoted **value** is EOL-delimited: its opening and closing `"` are the first and last quotes on the line, after trimming trailing whitespace. Interior `"` characters are literal data; `\"` is still accepted and means the same thing. A closing quote is required on the line, and nothing but whitespace may follow it.
- Escapes are exactly `\"`, `\\`, `\n`, `\r`, `\t`, `\b`, `\f` and `\uXXXX` with surrogate pairs handled correctly.
- Invalid escapes, isolated surrogates, `\u0000`, unclosed quotes and literal control characters are errors.
- Quoted **keys** close at the first unescaped `"`, because a key ends at `:` rather than EOL. An interior quote in a key must be written `\"`, and a key written that way is always an error: a quote is outside the key pattern above.

```riffle
scopedPackage : "@scope/name"
literalComment : "# not a comment"
display_name : Jane Doe
padded : "  keeps spaces  "
a : "He said "hello""
b : "He said \"hello\""
```

Both `a` and `b` above decode to `He said "hello"`. The writer removes quotes that are not needed, so `Jane Doe` is written bare, and it never quotes a key.

### Multiline raw blocks
A value or array element that is exactly `"""` opens a raw block. The block ends at the first line that holds only `"""`.

```riffle
certificate : """
    -----BEGIN CERTIFICATE-----
    MIIBszCCAVmgAwIBAgIU...
    -----END CERTIFICATE-----
    """
query : """
    SELECT * FROM users
    WHERE name LIKE '%\_admin'
    """
```

- Content is raw. Backslashes, quotes, `#`, `/#`, `{` and `@` are literal, and no line inside is a comment.
- The closer's indentation is removed from every content line. A line that does not start with exactly that indentation is an `INVALID_INDENTATION` error, and tabs and spaces are not interchangeable. Whitespace-only lines shorter than the indentation become empty.
- Trailing whitespace is preserved, and lines join with LF whatever the file's line endings.
- There is no newline after the last line. To end the value with one, leave an empty last line.
- `x : """abc"""` is an ordinary single-line quoted value.
- A block cannot contain a line that is only `"""`, and it cannot be a key. Use a quoted string with `\n` for such text.
- A raw block is not a Java text block. Only the closer's indentation is removed, so a line indented less than
  the closer is an error rather than something to re-format; no escape is processed, so a backslash and an `n`
  stay a backslash and an `n`; and a line that is only `"""` cannot appear inside one. There is no delimiter to
  choose, so text that holds `"""` on a line of its own has no raw form.

### Arrays
An array holds one value per line:

```riffle
values : [
    hello
    123
    {
        name : example
    }
    [
        one
        two
    ]
]
```

### Rejected forms

| Rejected | Write instead |
| --- | --- |
| `key {` / `key [` | `key : {` / `key : [` |
| `key = value` | `key : value` |
| a root wrapped in `{` … `}` | the implicit root map |
| a root array | a map |
| `x : { a : b }` | the map on following lines |
| `x : { }` | `{}` or a block, with no interior spaces |
| `x : [ one ]` | the elements on following lines |
| `re : [a-z]+` | `re : "[a-z]+"` |
| `port : @int 8080` | `port : 8080` |
| `pkg : @scope/name` | `pkg : "@scope/name"` |
| `x : {} y : 2` | one entry per line |
| a lone CR | LF or CRLF |

### Limits
- Nesting is unbounded by default: the parser, writer, validator, defaults, and Java conversion
  traverse value trees iteratively, so depth is limited only by available memory. A host that reads
  untrusted input can pass `RiffleOptions.maxDepth(n)` to those operations; exceeding the cap gives
  `RESOURCE_LIMIT_EXCEEDED` when parsing and `IllegalArgumentException` elsewhere.
- Parsing and writing keep time and memory proportional to the document; there is no mandatory
  document-size limit. Bound untrusted input yourself, with a size check before `parse(Path)` or
  a bounded `Reader`.
- A schema pattern compiles to at most 10,000 matcher states. A pattern that expands past that bound,
  such as nested `{100}` quantifiers, is an `INVALID_PATTERN` load problem; the 1000-repeat cap below
  applies to each individual quantifier. Groups may nest at most 100 levels.

## Java API

```java
import io.github.dhoard.riffle.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

RiffleObject profile = Riffle.parse(Path.of("profile.riffle"));

String provider = profile.getString("provider");
RiffleObject capabilities = profile.getObject("capabilities");
int contextWindow = capabilities.getInt("contextWindow");
boolean tools = capabilities.getBoolean("tools");
BigDecimal temperature = capabilities.getDecimal("temperature");
List<String> reasoning = capabilities.getArray("reasoning").values().stream()
        .map(RiffleValue::asString)
        .toList();

int retries = profile.find("retries").map(RiffleValue::asInt).orElse(3);

String canonical = Riffle.write(profile);
```

### Values
- `RiffleValue` is sealed: `RiffleObject`, `RiffleArray` (together `RiffleContainer`) and `RiffleString`.
- Values are immutable and thread-safe. Maps keep insertion order; arrays keep element order.
- Equality includes the kind and ignores source locations; map order does not affect equality, array order does. String equality is exact, so `1.0` and `1.00` are different strings. They compare equal only after `asDecimal()`.

### Parsing and writing
- `Riffle.parse` accepts a `String`, a `Reader` (which it does not close), or a UTF-8 `Path`, and returns the root map.
- `Riffle.write` returns the canonical document; parsing it yields an equal value. An array cannot be a root, so writing one is an `IllegalArgumentException`.
- Canonical output is UTF-8 without a BOM, uses LF and four-space indentation, and drops comments and blank lines. It is byte-exact; see the [conformance suite](conformance/README.md).
- The writer picks the first form that fits each string: bare, then a raw block for representable multiline text, then a single-line quoted string.

### Access
- `get(key)` for a required key, `find(key)` for an optional one, `entries()` for the map.
- `get(index)`, `size()` and `values()` for arrays.
- `RiffleObject` and `RiffleArray` also provide typed getters: `getString`, `getInt`, `getLong`, `getDecimal`, `getBoolean`, `getObject`, and `getArray`. They are shorthand for `get(key).asX()` or `get(index).asX()` and have the same errors, paths, and source locations.

```java
RiffleArray ports = root.getArray("ports");
int firstPort = ports.getInt(0);
String name = root.getString("name");
RiffleObject nested = root.getObject("nested");
```

### Conversions
Every conversion is strict and follows spec §13. The match is against the whole string, however the value was written, so `port : 8080` and `port : "8080"` both read as 8080.

| Conversion | Accepts | Range |
| --- | --- | --- |
| `asString()` | any string | |
| `asLong()` | `-?(0\|[1-9][0-9]*)` | signed 64-bit |
| `asInt()` | `-?(0\|[1-9][0-9]*)` | signed 32-bit |
| `asDecimal()` | `-?(0\|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?` | normalized exponent in `[-2147483647, 2147483648]` |
| `asBoolean()` | `true` or `false` | |
| `asObject()`, `asArray()` | a map or an array | |

- Rejected for numbers: `+5`, `05`, `1_000`, `0x10`, `" 5"`, `.5`, `1.`, `NaN` and `Infinity`.
- `asDecimal()` accepts `12`, while `asInt()` and `asLong()` reject `12.0`.
- `asDecimal()` returns an exact `BigDecimal` normalized with `stripTrailingZeros()`, and never passes through binary floating point.
- Rejected for booleans: `True`, `yes` and `1`.

Each conversion has a predicate that never throws and is true exactly when the conversion succeeds: `isString()`, `isInt()`, `isLong()`, `isDecimal()`, `isBoolean()`, `isObject()`, `isArray()`.

### Opt-in native Java conversion
`RiffleJavaValues` is an explicit bridge to ordinary Java collections and scalars.

```java
Map<String, Object> nativeProfile = RiffleJavaValues.toJava(profile);
String nativeProvider = (String) nativeProfile.get("provider");
List<?> nativeReasoning = (List<?>) RiffleJavaValues.toJava(capabilities.getArray("reasoning"));

RiffleObject restored = RiffleJavaValues.fromJava(nativeProfile);
RiffleValue port = RiffleJavaValues.fromJava((Object) 8080);
```

| Riffle | `toJava` result |
| --- | --- |
| string | `String` |
| map | unmodifiable, insertion-ordered `Map<String, Object>` |
| array | unmodifiable `List<Object>` |

`toJava` never produces `null`. `fromJava` accepts `String`, `Integer`, `Long`, `BigDecimal`, `Boolean`, `Map` with `String` keys, and `List`. Numbers and booleans become canonical text that the matching accessor accepts. It rejects `null`, other types and cycles with `IllegalArgumentException`; an optional `RiffleOptions` cap can also bound nesting. Source locations and paths are lost in a round trip.

### Errors
- `RiffleParseException` reports invalid documents. It carries a stable `RiffleError` category, a location, and a message of the form `location: reason`.
- `RiffleAccessException` reports a value that is missing or cannot be read as requested, with the key path and source location. `category()` returns a stable `RiffleAccessError`:

| Category | Meaning |
| --- | --- |
| `MISSING` | a required key or index is absent |
| `WRONG_KIND` | a string was requested from a map or array, or the reverse |
| `INVALID_FORMAT` | the string does not match the requested grammar |
| `OUT_OF_RANGE` | the string matches the grammar but is outside the range |
| `INVALID_VALUE` | an application-defined conversion failed |

- Messages never include scalar values, so secrets in configuration do not reach logs.
- A schema adds two more failure types, `RiffleSchemaException` for a broken schema and
  `RiffleValidationException` for invalid configuration, each carrying an ordered list of `RiffleProblem`
  values; see [Schemas](#schemas).
- Construction errors are `IllegalArgumentException`s; I/O failures are `IOException`s. Malformed UTF-8 in a file is a parse error with category `INVALID_UTF8`.

### Constructing values
`RiffleObject.of(Map)`, `RiffleArray.of(List)` and `RiffleString.of(String)`. Text containing U+0000 or an unpaired surrogate is rejected, as is a key outside the key pattern above, so a value built in code can always be written as a document that parses again.

### Application-specific types
Conversions for durations, URIs, colors or enums belong in the application as functions over `asString()`. Throw `new RiffleAccessException(value, reason)` to report them in the same form as Riffle's own errors; the category is `INVALID_VALUE`.

## Schemas

A **schema** is a Riffle document that says what a configuration document must look like: which keys it may
hold, what each value may be, which members are optional, and which combinations are allowed. Validating
against a schema never changes a value and never stops at the first mistake: it reports every problem in one
deterministic order, and each problem names a category, a path, and a line.

[`examples/service.schema.riffle`](examples/service.schema.riffle) is the schema SCHEMA_SPEC.md §2 works
through; three members of it:

```riffle
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
env : {
    type : map
    optional : true
    keyPattern : "[A-Z_][A-Z0-9_]*"
    otherKeys : {
        type : string
    }
}
```

A node says which kind it is with `type` - `string`, `integer`, `decimal`, `boolean`, `map`, `array`, `ref`,
`taggedUnion`, `kindUnion`, or `any` - and constrains it with the keywords that belong to that type:
`minLength`, `pattern`, `values`, `notBlank` for a string; `min`, `max`, `minExclusive` for a number; `keys`,
`otherKeys`, `keyPattern`, `minKeys`, `rules` for a map; `items`, `minItems`, `unique`, `uniqueKey` for an
array. A member node may add `optional : true` and a `default`. `definitions` holds reusable nodes addressed
by `type : ref`, and `rules` say what one member cannot say alone, such as `atMostOne`, `requires`, or
`memberOf`. A `keyPattern` or `pattern` is matched by the I-Regexp engine of SCHEMA_SPEC.md §6, whole-string,
and never by `java.util.regex`. The whole vocabulary is in [`SCHEMA_SPEC.md`](SCHEMA_SPEC.md).

```java
import io.github.dhoard.riffle.*;
import java.nio.file.Path;
import java.util.List;

RiffleSchema schema = RiffleSchema.parse(Path.of("examples/service.schema.riffle"));

// optional resource cap for untrusted input
RiffleOptions options = RiffleOptions.maxDepth(128);
RiffleObject bounded = Riffle.parse(text, schema, options);

// parse and validate in one step; an invalid document throws
RiffleObject service = Riffle.parse(Path.of("service.riffle"), schema);

// or validate a document you already hold and read every problem
List<RiffleProblem> problems = schema.validate(Riffle.parse(Path.of("examples/service.riffle")));
for (RiffleProblem problem : problems) {
    System.err.println(problem);
}

// defaults are a separate operation, because validation never inserts anything
RiffleObject filled = schema.applyDefaults(Riffle.parse("name : gateway\nurl : https://example.org/x\n"));
```

`examples/service.riffle` is invalid against that schema, and the loop above prints:

```
examples/service.riffle:1:8: port: expected at least 1
examples/service.riffle:5:5: env.region: the key does not match the pattern `[A-Z_][A-Z0-9_]*`
examples/service.riffle:6:5: env.lower_key: the key does not match the pattern `[A-Z_][A-Z0-9_]*`
examples/service.riffle:8:1: extra: is not a declared key
examples/service.riffle:1:1: name: is required
examples/service.riffle:1:1: (root): at most one of `command`, `url` may be present
```

- The categories are the stable ones listed in SCHEMA_SPEC.md §10. The category, path, line, and the number of
  problems are normative; the column and the wording of a reason are not. Paths render in the syntax of
  LANGUAGE_SPEC.md §10.1, and the root is `(root)`.
- `RiffleSchema` is immutable and thread-safe, so one schema can validate every document a program reads. The
  three forms load one schema - a `String`, a `Reader` (which it does not close), or a UTF-8 `Path` - and the
  same three forms parse a document. A broken schema is a `RiffleSchemaException`; invalid configuration is a
  `RiffleValidationException`; both carry the same ordered problem list.
- Loading a schema is three phases - the version, then structure against the meta-schema, then semantics - and
  a failing phase stops the later ones. A problem in a schema document names a path **in the schema**, such as
  `root.keys.port.default`, not a path in any configuration file.
- `applyDefaults` inserts an absent optional member's `default` after the entries the document already had, in
  the order the schema declares them, and recurses into the values it inserted. It inserts nothing into a
  document that has any problem, and it never reorders, removes, or coerces an entry that was present.
- A reason may quote schema text - a bound, an allowed value, a case name - but no category, path beyond the
  keys it names, or reason ever holds a value from a configuration document: `port: expected at least 1`,
  never `port: 0 is less than 1`.
- Three documented limits belong to schema patterns. A range quantifier whose maximum count exceeds 1000, such as
  `a{0,1001}`, is rejected (`INVALID_PATTERN`), because patterns are matched by a Thompson engine that never
  backtracks; a pattern whose expanded form needs more than 10,000 matcher states is rejected for the same
  category, so nested quantifiers cannot exhaust the loader; and groups nested more than 100 levels are rejected
  so parser recursion cannot exhaust the stack. Nesting is otherwise unbounded; pass
  `RiffleOptions.maxDepth(n)` if your application needs a cap on parsed documents, written trees,
  validation, or defaults.

## Conformance

[`conformance/`](conformance/README.md) contains language-neutral fixtures and JSON manifests, and Java runners
that discover the cases at runtime. `conformance/manifest.json` holds the language suite - semantic and
accessor expectations and expected canonical bytes - and `conformance/schema/` holds the schema suite: valid and
invalid schema documents, documents validated against them, and the ordered category, path, and line each one
must report. The schema suite also pins the meta-schema of SCHEMA_SPEC.md §15: the shipped resource, the
fixture copy, and the specification text are byte-identical, and that meta-schema validates itself. These
suites are the contract for future non-Java implementations; they do not imply that Go, Rust, or Python
implementations already exist.

```shell
./mvnw test -Dtest=ConformanceTest
./mvnw test -Dtest=SchemaConformanceTest
```

## Editor support

[`editors/sublime/`](editors/sublime/README.md) is a Sublime Text package: a
`sublime-syntax` definition for `.riffle` files that follows this specification, together
with a syntax test file. It is tooling, not part of the language definition.

## Portability

Host-language mappings for Go, Rust, Python, JavaScript, C and C++ are in
[Appendix B of the specification](LANGUAGE_SPEC.md#appendix-b-informative-host-language-mappings-non-normative).

## Build

Requires JDK 17+ and Maven 3.9+.

```shell
./mvnw clean verify
```

Formatting is enforced during `verify` by Spotless using Palantir Java Format; run
`./mvnw spotless:apply` after editing Java sources. The `release` profile adds signing and
publishing. Main sources have no runtime dependencies. Tests use JUnit 6 and AssertJ.

## Scope and limitations
- Human-authored application configuration only; not a data interchange or protocol format.
- No type inference in the language itself: a schema constrains values from outside the language and never
  retypes them.
- No variable expansion, include, merge, substitution or computation.
- Comments, blank lines and original quoting are not preserved by the canonical writer.
- Native conversions drop source metadata and do not provide POJO/record binding, reflection mapping, or
  application object construction.
- `Riffle.write(Path, …)` truncates the file and then writes it, so an interrupted write can
  leave a partial document; write a temporary file and move it if that matters.
- A schema constrains strings, maps, and arrays. It has no dates, durations, or enum kind, it cannot reach
  across documents, and it never coerces a value into another kind. Schema version 1 has no extension
  mechanism, and an unknown keyword is a problem rather than a thing to ignore.
- Version 0.1.0 is unreleased; the language, the schemas, and the API may still change.

## License

MIT, see [LICENSE](LICENSE).
