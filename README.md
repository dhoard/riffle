# Riffle

**Riffle 0.1.0** is a small, deterministic, end-of-line-terminated configuration language and library for Java 17 and newer. It is a configuration format, not a programming language or a data interchange protocol.

[`LANGUAGE_SPEC.md`](LANGUAGE_SPEC.md) is the normative specification; this file is the guide.

- Every entry is `key = value` and ends at the end of its line.
- Nonempty collections open on their own line and close on their own line; an empty collection may be written inline as `{}` or `[]`. Indentation has no syntactic significance.
- String is the default scalar type; every other scalar says so with an `@type` marker.
- Values are never inferred: `123`, `true`, `null` and `~` are strings.
- Comments are `#` or `//`, on their own line or after whitespace.

## Example

```riffle
schemaVersion = @int 1
provider = chatgpt
model = gpt-6-sol
transport = chatgpt-oauth

capabilities = {
    tools = @boolean true
    streaming = @boolean true
    contextWindow = @int 272000
    maxOutputTokens = @int 128000

    reasoning = [
        none
        low
        medium
        high
        xhigh
        max
    ]

    defaultReasoning = high
    tokenizer = heuristic

    replayRequirements = [
        chatgpt-oauth
    ]
}

options = {
    chatgpt-oauth = {}
}
```

Blank lines group entries for readability; they are allowed and ignored, and the canonical writer
does not emit them.

## Language

### Lines and whitespace
- Files are UTF-8. A byte-order mark at the start is ignored; a BOM anywhere else is an error.
- Line endings are LF or CRLF. A lone carriage return is an error.
- Spaces and tabs separate tokens and indent lines. Indentation is never significant.
- Blank lines and comment lines are ignored, including inside collections.
- Every entry and array element occupies its own line. A nonempty collection's opener must be the last token on its line and its closer must be on its own line; an empty collection may also be written as an empty block.
- Strings do not span lines. Use `\n` inside a quoted string.

### Comments
- A full-line comment begins after optional indentation with `#` or `//`.
- An inline comment begins only when `#` or `//` follows at least one space or tab, and is never inside a quoted string. A `"` opens a quoted string only as the first character of a key or value, so `k = he said "hi" # c` ends at `#`.
- Without preceding whitespace, a marker is part of a bare string: `https://example.org/a#fragment` and `path//segment` are unchanged.
- `name = hello #world` has the value `hello`; write `"hello #world"` for the whole text.

### Root and maps
- The document root is an implicit, insertion-ordered map.
- An empty map may be written `{}` or as `{` and `}` on separate lines; a nonempty map is written in block form.
- Nonempty collections are never written inline, and the canonical writer writes empty ones as `{}` or `[]`.

### Filling an empty collection
An empty collection may be written inline or as an empty block; a populated one is written in block form. The writer normalizes an empty block to the inline form. Turning an empty collection into a populated one moves the content onto its own lines — these are two separate documents:

Empty:

```riffle
options = {
    chatgpt-oauth = {}
}
```

Populated:

```riffle
options = {
    chatgpt-oauth = {
        key = Test value
    }
}
```

Arrays work the same way: `tools = []` becomes `tools = [`, one element per line, then `]`. Writing `chatgpt-oauth = { key = Test value }` is rejected, because a value runs to the end of its line — a nonempty collection cannot be inline.

### Keys
- A **bare key** matches `[A-Za-z0-9_][A-Za-z0-9_.-]*`: `provider`, `chatgpt-oauth`, `1`, `schema.version`.
- Any other key is **quoted**, using the string escapes: `"key with spaces" = value`.
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

- `@int` must fit 32 bits and `@long` 64 bits; `@decimal` is arbitrary precision, base 10.
- Integers are base 10 with an optional `-`; `+`, underscores, hex, leading zeros and decimal points are rejected.
- Decimals use `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`. The writer emits the normalized value, so `@decimal 1.0` is written `@decimal 1`.
- `@null` takes no payload. `@float` and any other unknown type is an error.

### Bare strings
- Trimmed at both ends, interior whitespace preserved. `key =` is the empty string.
- The first character must not be `@`, `{`, `[`, `}`, `]` or `"`; quote values that start with them.
- Control characters are invalid in a bare string; quote and escape them.

### Quoted strings
- Escapes are exactly `\"`, `\\`, `\n`, `\r`, `\t`, `\b`, `\f` and `\uXXXX` with surrogate pairs handled correctly.
- Invalid escapes, isolated surrogates, unclosed quotes and literal control characters are errors.

```riffle
literalMarker = "@int 123"
literalComment = "# not a comment"
"display name" = Jane Doe
padded = "  keeps spaces  "
multiline = "line one\nline two"
```

The writer removes quotes that are not needed (`"display name"` keeps them, `Jane Doe` does not) and escapes line breaks.

### Untyped arrays
An untyped array holds one value per line and may mix kinds:

```riffle
values = [
    hello
    @int 123
    @boolean true
    @null
    {
        name = example
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
ports = @int [
    8080
    8081
]
flags = @boolean [
    true
    false
]
servers = @map [
    {
        host = a.example
    }
    {}
]
rows = @array [
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

Typed arrays are authoring sugar: the canonical writer emits untyped arrays with `@type` elements, so `ports = @int [ … ]` is written as `ports = [` with `@int 8080` elements.

### Rejected forms

| Rejected | Write instead |
| --- | --- |
| `key {` / `key [` | `key = {` / `key = [` |
| `key : value` | `key = value` |
| a root wrapped in `{` … `}` | the implicit root map |
| a root array | a map |
| `x = { a = b }` | the map on following lines |
| `x = { }` | `{}` or a block, with no interior spaces |
| `x = [ one ]` | the elements on following lines |
| `@int` with no payload | `@int 42` |
| `@null null` | `@null` |
| `@float 1.0` | `@decimal 1.0` |
| `@int "1"` | `@int 1` |
| `x = {} y = 2` | one entry per line |
| a lone CR | LF or CRLF |

### Limits
Nesting deeper than 128 collections is a resource-limit error.

## Java API

```java
import io.github.dhoard.riffle.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

RiffleObject profile = Riffle.parse(Path.of("profile.riffle"));

String provider = profile.get("provider").asString();
RiffleObject capabilities = profile.get("capabilities").asObject();
int contextWindow = capabilities.get("contextWindow").asInt();
boolean tools = capabilities.get("tools").asBoolean();
BigDecimal temperature = profile.get("model").asObject().get("temperature").asDecimal();
List<String> reasoning = capabilities.get("reasoning").asArray().values().stream()
        .map(RiffleValue::asString)
        .toList();

int retries = profile.find("retries").map(RiffleValue::asInt).orElse(3);
boolean pluginIsNull = profile.get("plugin").isNull();

String canonical = Riffle.write(profile);
```

### Values
- `RiffleValue` is sealed: `RiffleObject`, `RiffleArray` (together `RiffleContainer`), `RiffleString`, `RiffleInt`, `RiffleLong`, `RiffleDecimal`, `RiffleBoolean`, `RiffleNull`.
- Values are immutable and thread-safe. Maps keep insertion order; arrays keep element order.
- Equality includes the kind and ignores source locations; map order does not affect equality, array order does. Decimals compare by numeric value, so `1.0` equals `1.00`.

### Parsing and writing
- `Riffle.parse` accepts a `String`, a `Reader` (which it does not close), or a UTF-8 `Path`, and returns the root map.
- `Riffle.write` returns the canonical document; parsing it yields an equal value. An array cannot be a root, so writing one is an `IllegalArgumentException`.

### Access
- `get(key)` for a required key, `find(key)` for an optional one, `entries()` for the map.
- `get(index)`, `size()` and `values()` for arrays.

### Conversions
Accessors are strict: each accepts exactly one kind, and no coercion happens.

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
- Comments and blank lines are not preserved by the canonical writer.
- Version 0.1.0 is unreleased; the language and API may still change.

## License

MIT, see [LICENSE](LICENSE).
