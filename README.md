# Riffle

**Riffle 0.1.0** is a small, deterministic, end-of-line-terminated configuration language. Java 17 is the reference implementation in this repository, but the language is defined independently of Java.

[`LANGUAGE_SPEC.md`](LANGUAGE_SPEC.md) is the normative specification for every implementation; this file is the Java binding guide. Java type names in the spec appear only in non-normative appendices.

- Every entry is `key : value` and ends at the end of its line. The canonical writer emits exactly one space before and after the colon.
- Nonempty collections open on their own line and close on their own line; an empty collection may be written inline as `{}` or `[]`. Indentation has no syntactic significance.
- String is the default scalar type; every other scalar says so with an `@type` marker.
- Values are never inferred: `123`, `true`, `null` and `~` are strings.
- Comments occupy whole lines: `#` for a line comment, `/#` … `#/` for a nestable block comment region.

## Example

```riffle
schemaVersion : @int 1
provider : chatgpt
model : gpt-6-sol
transport : chatgpt-oauth

capabilities : {
    tools : @boolean true
    streaming : @boolean true
    contextWindow : @int 272000
    maxOutputTokens : @int 128000

    reasoning : [
        none
        low
        medium
        high
        xhigh
        max
    ]

    defaultReasoning : high
    tokenizer : heuristic

    replayRequirements : [
        chatgpt-oauth
    ]
}

options : {
    chatgpt-oauth : {}
}
```

Blank lines group entries for readability; they are allowed and ignored, and the canonical writer
does not emit them.

## Language

### Lines and whitespace
- Files are UTF-8. A byte-order mark at the start is ignored; a BOM anywhere else is an error.
  The writer escapes U+FEFF inside values and keys, so such a value still round-trips.
- Line endings are LF or CRLF. A lone carriage return is an error.
- Spaces and tabs separate tokens and indent lines. Indentation is never significant.
- Blank lines and comment lines are ignored, including inside collections.
- Every entry and array element occupies its own line. A nonempty collection's opener must be the last token on its line and its closer must be on its own line; an empty collection may also be written as an empty block.
- Strings do not span lines. Use `\n` inside a quoted string.

### Comments
- A comment occupies a whole line: a line whose first non-whitespace character is `#` is ignored.
- A `#` after a value is part of the value. `k : hello #world` has the value `hello #world`, `url : https://example.org/a#fragment` keeps its fragment, and `path//segment` and `a // b` are bare strings.
- Because a comment cannot follow a value, `k : "v" # note` and `k : {} # note` are rejected (`TRAILING_TOKENS`). Put the comment on its own line.
- A block comment also occupies whole lines: it opens on a line whose first non-whitespace characters are `/#`, closes one nesting level on a line containing only `#/`, and hides everything between, including invalid Riffle. A `#` inside a value is never a comment there either: `k : v /# note #/` is the string `v /# note #/`.
- `//` is never a comment marker: `path//segment` and `a // b` are bare strings, and `name : hello //world` has the value `hello //world`.

### Root and maps
- The document root is an implicit, insertion-ordered map.
- An empty map may be written `{}` or as `{` and `}` on separate lines; a nonempty map is written in block form.
- Nonempty collections are never written inline, and the canonical writer writes empty ones as `{}` or `[]`.

### Filling an empty collection
An empty collection may be written inline or as an empty block; a populated one is written in block form. The writer normalizes an empty block to the inline form. Turning an empty collection into a populated one moves the content onto its own lines — these are two separate documents:

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

Arrays work the same way: `tools : []` becomes `tools : [`, one element per line, then `]`. Writing `chatgpt-oauth : { key : Test value }` is rejected, because a value runs to the end of its line — a nonempty collection cannot be inline.

### Keys
- A **bare key** matches `[A-Za-z0-9_][A-Za-z0-9_.-]*`: `provider`, `chatgpt-oauth`, `1`, `schema.version`.
- Any other key is **quoted**, using the string escapes: `"key with spaces" : value`.
- A dot in a key is a literal dot, not a path separator.
- Keys are always strings and are case-sensitive. Duplicate keys in one map are an error.

### Types
String is the default. Every other scalar carries a marker.

| Kind | Syntax | Java 17 type |
| --- | --- | --- |
| string | `text`, `"text"`, `@string text` | `String` |
| int | `@int 42` | `Integer` |
| long | `@long 42` | `Long` |
| decimal | `@decimal 0.25` | `BigDecimal` |
| boolean | `@boolean true` | `Boolean` |
| null | `@null` | dedicated null value |
| map | `{}` or `{ … }` | `RiffleObject` |
| array | `[]` or `[ … ]` | `RiffleArray` |

The Java column is informative. The portable definitions and ranges are in [`LANGUAGE_SPEC.md`](LANGUAGE_SPEC.md).

- `@int` is a signed 32-bit kind and `@long` is a signed 64-bit kind. They stay distinct even where a host language has one integer type.
- Integers are base 10 with an optional `-`; `+`, underscores, hex, leading zeros and decimal points are rejected.
- Decimals use `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`. The writer emits the normalized value, so `@decimal 1.0` is written `@decimal 1`. Canonical rendering uses the fixed algorithm in spec §11.3.
- `@null` takes no payload. `@float` and any other unknown type is an error.

### Bare strings
- Trimmed at both ends, interior whitespace preserved. `key :` is the empty string.
- The first character must not be `@`, `{`, `[`, `}`, `]` or `"`; quote values that start with them.
- Control characters are invalid in a bare string; quote and escape them.

### Quoted strings
- A quoted **value** is EOL-delimited: its opening and closing `"` are the first and last quotes on the line, after trimming trailing whitespace. Interior `"` characters are literal data; `\"` is still accepted and means the same thing. A closing quote is required on the line, and nothing but whitespace may follow it.
- Escapes are exactly `\"`, `\\`, `\n`, `\r`, `\t`, `\b`, `\f` and `\uXXXX` with surrogate pairs handled correctly.
- Invalid escapes, isolated surrogates, unclosed quotes and literal control characters are errors.
- Quoted **keys** keep their old rule: the first unescaped `"` closes the key, because a key ends at `:` rather than EOL. Interior quotes in keys must be written `\"`.

```riffle
literalMarker : "@int 123"
literalComment : "# not a comment"
"display name" : Jane Doe
padded : "  keeps spaces  "
multiline : "line one\nline two"
a : "He said "hello""
b : "He said \"hello\""
```

Both `a` and `b` above decode to `He said "hello"`. When a value must be quoted, the canonical writer prefers the literal form and keeps interior quotes literal, because the closing delimiter is the last quote on the line. Because a comment occupies a whole line, `k : "a # b"` is the value `a # b` while `k : "a" # comment` is rejected. Keys keep emitting escaped interior quotes.

The writer removes quotes that are not needed (`"display name"` keeps them, `Jane Doe` does not) and escapes line breaks.

### Untyped arrays
An untyped array holds one value per line and may mix kinds:

```riffle
values : [
    hello
    @int 123
    @boolean true
    @null
    {
        name : example
    }
    [
        one
        two
    ]
]
```

A bare `null` element is the string `"null"`; the null value is `@null`.

### Typed arrays
`@type [` supplies the element type for immediate elements only, and `@type []` is an empty typed array. Element kinds are `string`, `int`, `long`, `decimal`, `boolean`, `null`, `map` and `array`.

```riffle
ports : @int [
    8080
    8081
]
flags : @boolean [
    true
    false
]
servers : @map [
    {
        host : a.example
    }
    {}
]
rows : @array [
    [
        @int 1
    ]
    @int [
        2
        3
    ]
]
```

Inside a typed array, elements are written without repeating the marker: `@int [` elements are integers, `@null [` elements are the literal word `null`, `@map [` elements are maps, and `@array [` elements are arrays or nested typed arrays. A typed array and an untyped array of the same typed elements are the same value, so `@int [\n 1\n]` equals `[\n @int 1\n]`.

Typed arrays are authoring sugar: the canonical writer emits untyped arrays with `@type` elements, so `ports : @int [ … ]` is written as `ports : [` with `@int 8080` elements.

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
| `@int` with no payload | `@int 42` |
| `@null null` | `@null` |
| `@float 1.0` | `@decimal 1.0` |
| `@int "1"` | `@int 1` |
| `x : {} y : 2` | one entry per line |
| a lone CR | LF or CRLF |

### Limits
- Nesting deeper than 128 collections is a resource-limit error. The writer applies the same
  bound: a value tree that deep has no valid document, so `Riffle.write` throws
  `IllegalArgumentException` instead of producing text the parser would reject.
- Parsing and writing keep time and memory proportional to the document; there is no
  document-size limit. Bound untrusted input yourself, with a size check before `parse(Path)` or
  a bounded `Reader`.
- A decimal's normalized base-10 exponent must be in `[-2147483647, 2147483648]`. A literal whose normalized form falls outside that range is a `NUMBER_OUT_OF_RANGE` parse error.
- Canonical decimal output chooses plain notation when the decimal token is at most 256 characters and scientific notation otherwise. Very large coefficients with zero scale now use scientific form; their old plain spelling still parses but is no longer canonical.

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
BigDecimal temperature = profile.getObject("model").getDecimal("temperature");
List<String> reasoning = capabilities.getArray("reasoning").values().stream()
        .map(RiffleValue::asString)
        .toList();

int retries = profile.find("retries").map(RiffleValue::asInt).orElse(3);
boolean pluginIsNull = profile.find("plugin").map(RiffleValue::isNull).orElse(false);

String canonical = Riffle.write(profile);
```

### Values
- `RiffleValue` is sealed: `RiffleObject`, `RiffleArray` (together `RiffleContainer`), `RiffleString`, `RiffleInt`, `RiffleLong`, `RiffleDecimal`, `RiffleBoolean`, `RiffleNull`.
- Values are immutable and thread-safe. Maps keep insertion order; arrays keep element order.
- Equality includes the kind and ignores source locations; map order does not affect equality, array order does. Decimals compare by numeric value, so `1.0` equals `1.00`.

### Parsing and writing
- `Riffle.parse` accepts a `String`, a `Reader` (which it does not close), or a UTF-8 `Path`, and returns the root map.
- `Riffle.write` returns the canonical document; parsing it yields an equal value. An array cannot be a root, so writing one is an `IllegalArgumentException`.
- Canonical output is UTF-8 without a BOM, uses LF and four-space indentation, and drops comments, blank lines, and typed-array annotations. It is byte-exact; see the [conformance suite](conformance/README.md).

### Access
- `get(key)` for a required key, `find(key)` for an optional one, `entries()` for the map.
- `get(index)`, `size()` and `values()` for arrays.
- `RiffleObject` and `RiffleArray` also provide strict typed getters: `getString`, `getInt`, `getLong`, `getDecimal`, `getBoolean`, `getObject`, and `getArray`. They are shorthand for `get(key).asX()` or `get(index).asX()` and have the same errors, paths, and source locations.

```java
RiffleArray ports = root.getArray("ports");
int firstPort = ports.getInt(0);          // no widening or narrowing
String name = root.getString("name");     // no parsing of strings as numbers
RiffleObject nested = root.getObject("nested");
```

A missing key throws `RiffleAccessException`; `find(key)` is the optional-key API. A present `@null` is returned by `get(key)` as `RiffleNull`, never Java `null`, and strict typed getters reject it with a wrong-kind error. `find("x")` being empty and `get("x").isNull()` being true are different states.

### Opt-in native Java conversion
`RiffleJavaValues` is an explicit bridge to ordinary Java collections and scalars. It does not change the strict `RiffleValue` accessors.

```java
Map<String, Object> nativeProfile = RiffleJavaValues.toJava(profile);
String nativeProvider = (String) nativeProfile.get("provider");
boolean pluginPresent = nativeProfile.containsKey("plugin");
boolean pluginIsJavaNull = pluginPresent && nativeProfile.get("plugin") == null;
List<?> nativeReasoning = (List<?>) RiffleJavaValues.toJava(capabilities.getArray("reasoning"));

RiffleObject restored = RiffleJavaValues.fromJava(nativeProfile);
```

| Riffle | Java |
| --- | --- |
| string | `String` |
| int | `Integer` |
| long | `Long` |
| decimal | `BigDecimal` |
| boolean | `Boolean` |
| null | Java `null` |
| map | unmodifiable, insertion-ordered `Map<String, Object>` |
| array | unmodifiable `List<Object>` |

Results are deep defensive copies. Unlike `Map.copyOf` and `List.copyOf`, they accept null values, so present-null remains distinguishable from a missing key. `fromJava` accepts only the types above, requires `String` map keys, rejects cycles and unsupported numeric subtypes, and enforces the same nesting bound. A round trip preserves the eight kinds and order, but source locations and paths are lost and cannot be recovered.

### Conversions
Value-level accessors are strict: each accepts exactly one kind, and no coercion happens.

| Conversion | Accepts |
| --- | --- |
| `asString()` | a string |
| `asInt()` | an int |
| `asLong()` | a long |
| `asDecimal()` | a decimal |
| `asBoolean()` | a boolean |
| `asObject()`, `asArray()` | a map or an array |

Each has a predicate that never throws and is true exactly when the conversion succeeds: `isString()`, `isInt()`, `isLong()`, `isDecimal()`, `isBoolean()`, `isObject()`, `isArray()`, `isNull()`.

### Errors
- `RiffleParseException` reports invalid documents. It carries a stable `RiffleError` category, a location, and a message of the form `location: reason`.
- `RiffleAccessException` reports a missing key or a value of the wrong kind, with the key path and source location.
- Messages never include scalar values, so secrets in configuration do not reach logs.
- Construction errors are `IllegalArgumentException`s; I/O failures are `IOException`s. Malformed UTF-8 in a file is a parse error with category `INVALID_UTF8`.

### Constructing values
`RiffleObject.of(Map)`, `RiffleArray.of(List)`, `RiffleString.of(String)`, `RiffleInt.of(int)`, `RiffleLong.of(long)`, `RiffleDecimal.of(BigDecimal)`, `RiffleBoolean.of(boolean)`, `RiffleNull.INSTANCE`.

### Application-specific types
Conversions for durations, URIs, colors or enums belong in the application as functions over `asString()`. Throw `new RiffleAccessException(value, reason)` to report them in the same form as Riffle's own errors.

## Conformance

[`conformance/`](conformance/README.md) contains language-neutral fixtures, a JSON manifest with typed expectations and expected canonical bytes, and a Java runner that discovers cases at runtime. It is the contract for future non-Java implementations; it does not imply that Go, Rust, or Python implementations already exist.

```shell
./mvnw test -Dtest=ConformanceTest
```

## Editor support

[`editors/sublime/`](editors/sublime/README.md) is a Sublime Text package: a
`sublime-syntax` definition for `.riffle` files that follows this specification, together
with a syntax test file. It is tooling, not part of the language definition.

## Portability mappings (informative)

These mappings are illustrative only. The language definition is in [`LANGUAGE_SPEC.md`](LANGUAGE_SPEC.md); host-language types are not normative, and a host with one integer type must still preserve the distinct `int` and `long` Riffle kinds.

| Riffle kind | Java (reference) | Go (illustrative) | Rust (illustrative) | Python (illustrative) |
| --- | --- | --- | --- | --- |
| string | `String` | `string` | `String` | `str` |
| int | `int` | `int32` | `i32` | `int` with range check |
| long | `long` | `int64` | `i64` | `int` with range check |
| decimal | `BigDecimal` | decimal package | decimal crate | `decimal.Decimal` |
| boolean | `boolean` | `bool` | `bool` | `bool` |
| null | `RiffleNull` | typed nil sentinel | `Option` sentinel | `None` in a typed wrapper |
| map | ordered `Map` | ordered pair slice | `IndexMap` / vec | insertion-ordered `dict` |
| array | ordered `List` | `[]T` | `Vec<T>` | `list` |

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
- No implicit typing, no variable expansion, include, merge, substitution or computation.
- Comments, blank lines, original quoting, and typed-array annotations are not preserved by the canonical writer. Typed arrays are authoring sugar: their elements are written with explicit `@type` markers.
- Native conversions drop source metadata and do not provide POJO/record binding, reflection mapping, schemas, or application object construction.
- `Riffle.write(Path, …)` truncates the file and then writes it, so an interrupted write can
  leave a partial document; write a temporary file and move it if that matters.
- Version 0.1.0 is unreleased; the language and API may still change.

## License

MIT, see [LICENSE](LICENSE).
