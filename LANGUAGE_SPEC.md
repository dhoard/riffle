# Riffle Language Specification

**Version:** 0.1.0
**Status:** Normative language specification
**Applies to:** All Riffle 0.1.0 implementations, independent of host language
**Reference implementation:** Java 17 (see the non-normative [Appendix A](#appendix-a-reference-java-mapping-non-normative))

## 1. Scope and terminology

Riffle is a small, deterministic, **end-of-line (EOL)-terminated** configuration language. It is a configuration format, not a programming language or a data interchange protocol.

The normative terms **MUST**, **MUST NOT**, **SHOULD**, and **MAY** have their usual requirements meanings. This document defines Riffle 0.1.0. An implementation MUST NOT invent additional syntax or silent coercion.

Normative sections describe observable input, semantic values, canonical output, errors, and limits. Host-language type names appear only in non-normative appendices and in the implementation guide, never as part of the language definition.

Core properties:

- The document root is an **implicit, insertion-ordered map** of string keys to values.
- A map member uses `key = value`; each member occupies **one logical line**, except that a collection value spans its own block of subsequent lines.
- An array contains one element per logical line, except that a collection element spans its own block.
- `{` and `}` delimit maps; `[` and `]` delimit arrays; indentation has no syntactic significance.
- No commas or semicolons delimit entries. A **newline**, not whitespace alone, terminates each entry.
- String is the default scalar type. Every non-string scalar has an explicit `@type` marker.
- An untyped array can contain values of different kinds. A typed array (`@type [`) supplies the kind for each immediate element.
- Keys are always strings; they are never typed.
- The format does not perform variable expansion, environment substitution, includes, computation, merging, or coercion.

## 2. Canonical example

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

Typed arrays such as `@string [...]` are accepted as authoring sugar, but the canonical serializer writes untyped arrays with `@type` element markers (§7.3, §11). Blank lines are allowed and ignored; the canonical rendering omits them.

## 3. Value model

The value model contains exactly these eight kinds:

| Kind | Syntax | Portable semantics |
| --- | --- | --- |
| `string` | `text`, `"text"`, or `@string text` | A finite sequence of Unicode scalar values (§3.1) |
| `int` | `@int 42` | A signed 32-bit integer: `[-2147483648, 2147483647]` |
| `long` | `@long 42` | A signed 64-bit integer: `[-9223372036854775808, 9223372036854775807]` |
| `decimal` | `@decimal 0.25` | A finite, arbitrary-precision base-10 number (§3.3) |
| `boolean` | `@boolean true` | One of `true` or `false` |
| `null` | `@null` | A dedicated null value, distinct from every other value |
| `map` | `{ ... }` | An ordered map from strings to values |
| `array` | `[ ... ]` | An ordered sequence of values |

There is **no automatic type inference**. Thus `123`, `true`, `null`, and `~` are strings; `@int 123`, `@boolean true`, and `@null` are not.

`@int 42` and `@long 42` are **distinct kinds** even when a host language uses one native integer representation. Equality, serialization, and strict accessors preserve that distinction. A conforming implementation MUST NOT widen an `int` to a `long`, narrow a `long` to an `int`, or convert either to or from a `decimal`.

### 3.1 Strings and Unicode

A string is a sequence of Unicode scalar values: Unicode code points excluding the surrogate range `U+D800`–`U+DFFF`. Equality is exact code-point sequence equality. Implementations MUST NOT apply Unicode normalization, case folding, locale-specific transformations, or replacement of unrecognized characters when comparing strings.

Literal input MUST be UTF-8 (§4). Text containing an encoded surrogate code point or an unpaired surrogate in a host-language string is not a valid Riffle string and MUST be rejected with `INVALID_UTF8`.

`U+FEFF` is a Unicode scalar value and MAY occur inside a string. A literal `U+FEFF` is legal only as the first character of a document, where it is a byte-order mark (BOM) and is ignored. Anywhere else a literal `U+FEFF` is `INVALID_CHARACTER`; inside a key or string it MUST be written as the escape `\uFEFF`. The canonical serializer always escapes `U+FEFF` inside keys and values (§11.2).

### 3.2 Integers

Integer lexical syntax is base 10 with an optional leading `-`, then either `0` or a nonzero digit followed by digits. A payload is valid for `@int` exactly when its mathematical value lies in the `int` range; it is valid for `@long` exactly when its value lies in the `long` range. A value outside the range of its declared kind MUST be rejected with `NUMBER_OUT_OF_RANGE`.

### 3.3 Decimals

A decimal denotes a finite mathematical value

```
(-1)^sign * coefficient * 10^exponent
```

where `sign` is `0` or `1`, `coefficient` is a nonnegative base-10 integer, and `exponent` is a base-10 integer. A decimal is **not** a binary floating-point number; implementations MUST NOT convert through a binary floating-point type at any point in parsing, comparison, normalization, or serialization.

Lexical syntax (§5.3) permits leading zeros only as the single digit `0`, fractional digits after `.`, and an optional `e` or `E` exponent with an optional sign. Lexical scale is not part of the semantic value: `@decimal 1`, `@decimal 1.0`, and `@decimal 1.00` have the same value. Decimal equality is mathematical equality; zero has no sign in the semantic value.

For every nonzero value, the normalized coefficient has no leading or trailing zeros. The normalized exponent MUST lie in `[-2147483647, 2147483648]`. This is equivalent to a decimal scale in `[-2147483648, 2147483647]`. A literal whose normalized exponent is outside that interval MUST be rejected with `NUMBER_OUT_OF_RANGE`. Zero is always normalized to coefficient `0`, exponent `0`, and positive sign regardless of its lexical sign or exponent.

Canonical normalization and rendering are fully specified in §11.3.

### 3.4 Maps and arrays

A map preserves insertion order for parsing, semantic iteration, and canonical writing. Two maps are equal when they contain the same keys mapped to equal values; map entry order does not affect equality. This means two maps with equal entries in different orders parse to unequal iteration sequences but equal values.

An array preserves element order for parsing, semantic iteration, canonical writing, and equality. Two arrays are equal only when they contain equal elements at the same indexes.

Keys are compared as exact strings. Duplicate keys within one map MUST be rejected, even if their values are equal. The same key MAY occur in different nested maps.

Source locations, key paths, comments, original quoting, and original typed-array annotations are diagnostic or authoring metadata. They are not part of semantic equality.

## 4. Character encoding, lines, and whitespace

1. Files MUST be valid UTF-8. A UTF-8 BOM at the start MAY be accepted and ignored; the reference implementation accepts and ignores it. A BOM elsewhere is invalid and MUST be reported as `INVALID_CHARACTER`.
2. Line endings MAY be LF (`\n`) or CRLF (`\r\n`). A lone CR (`\r`) is invalid. The final line MAY omit its line terminator.
3. Spaces (`U+0020`) and horizontal tabs (`U+0009`) are indentation and horizontal separators. They are not significant outside quoted strings except where a separator is required.
4. Blank lines and comment-only lines are ignored, including inside collections.
5. An entry or close delimiter MUST terminate at EOL, optionally followed by a valid inline comment. No two map members or array elements may occur on the same line.
6. A `{`, `[`, or `@type [` opening block MUST be the last syntactic token on its line. Its contents begin on following lines. An empty collection MAY be written inline as `{}`, `[]`, or `@type []`, or as an empty block (`{` then `}` on separate lines, likewise `[` and `]`); the canonical serializer writes the inline form (§11). Interior whitespace inside an inline empty collection (`{ }`, `[ ]`, `@type [ ]`) is invalid.
7. A nonempty block's `}` or `]` MUST be on its own line, apart from indentation and an optional inline comment. An implementation SHOULD report a nonempty inline collection with a diagnostic that names the block form and points at the first content token. An empty collection written with interior whitespace is likewise invalid; the diagnostic SHOULD name `{}`, `[]`, or `@type []` rather than describe the collection as nonempty.
8. Strings MUST NOT span physical lines in version 0.1.0. There is no continuation character or multiline-string syntax.

## 5. Scalar values

### 5.1 Bare strings

Unless the value begins with a recognized structural delimiter or `@` typed expression, an unquoted value is a string. Leading and trailing horizontal whitespace are trimmed; interior whitespace is preserved. `key =` (including trailing spaces) denotes the empty string.

Examples:

```riffle
home = ~/projects
url = https://example.org/a#fragment
password = abc=123
message = Hello: world
name = null
blank =
```

The first non-whitespace character of a **bare value or array element** MUST NOT be `@`, `{`, `[`, `}`, `]`, or `"` (these characters are syntactically reserved at that position). Quote strings that begin with them. These characters MAY appear later within a bare string, subject to the comment rules. A bare value consisting of `}` or `]` is always a closing delimiter when it appears alone on a collection line.

A bare string MUST NOT contain an unescaped ASCII control character (`U+0000`–`U+001F` or `U+007F`) or a literal `U+FEFF`. Backslashes in bare strings are ordinary literal characters; there are no bare-string escapes. Quoting is the escape hatch.

### 5.2 Quoted strings

A double-quoted string is allowed anywhere a string value or key is allowed. Its first and last non-whitespace syntactic characters are `"`. It MUST have no trailing characters other than whitespace or a valid inline comment.

Supported escapes are exactly `\"`, `\\`, `\n`, `\r`, `\t`, `\b`, `\f`, and `\uXXXX` (four ASCII hexadecimal digits). A `\uXXXX` escape denotes the code point with that value. An escape for a high surrogate MUST be immediately followed by an escape for a low surrogate; the pair denotes the corresponding supplementary code point. An isolated surrogate escape MUST be rejected with `INVALID_ESCAPE`. Invalid escape sequences and unclosed quotes MUST fail. Literal newlines, literal carriage returns, other unescaped control characters, and literal `U+FEFF` inside quoted strings are invalid; use `\uFEFF` for the last.

Examples:

```riffle
literalMarker = "@int 123"
literalBrace = "{"
literalComment = "# not a comment"
"display name" = "Jane Doe"
```

### 5.3 Typed scalars

Typed scalars begin at the first non-whitespace character with `@` followed by one of the exact lowercase ASCII type identifiers. A horizontal-space separator is REQUIRED between `@int`, `@long`, `@decimal`, `@boolean`, or `@string` and its payload; `@null` has no payload. The payload terminates at the same EOL as the entry.

```riffle
count = @int 123
large = @long 9223372036854775807
fraction = @decimal -0.125
active = @boolean true
text = @string true
missing = @null
```

- Integers are base 10 with optional leading `-`, digits `0` or `[1-9][0-9]*`; `+`, underscores, hex, leading zeros except `0`, and decimal points are invalid.
- Decimal payloads use the grammar `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`. Thus `@decimal 12`, `@decimal -0.25`, and `@decimal 1e3` are valid, but `NaN`, `Infinity`, `.5`, and `1.` are not.
- Booleans are lowercase `true` and `false` only.
- `@null` MUST NOT be followed by a scalar payload. `missing = @null null` is invalid.
- `@string` consumes either a quoted string or the remainder of the line as a bare string, applying the same comment and whitespace rules. It is primarily useful for clarity. The reserved-prefix rule of §5.1 applies to the first character of the value, not to the payload, so `text = @string @int 1` is the string `@int 1`.
- `@int`, `@long`, `@decimal`, and `@boolean` MUST NOT accept quoted payloads or extra tokens.
- Each type marker denotes a value, not a declaration for subsequent lines.

Type identifiers are case-sensitive. Unknown identifiers such as `@float` MUST fail with `UNKNOWN_TYPE`.

## 6. Map keys and assignments

### 6.1 Map form

A root document consists of zero or more map entries. A nested map is opened with `{` and closed with `}`. Every entry has the form:

```riffle
key = value
```

Exactly one `=` separates the key from the value. Additional `=` characters in an unquoted value are ordinary string characters. Spaces and tabs around the delimiter are optional: `key=value`, `key = value`, and `key= value` are equivalent.

### 6.2 Keys

- **Bare key:** matches `[A-Za-z0-9_][A-Za-z0-9_.-]*`. Examples: `provider`, `chatgpt-oauth`, `1`, `schema.version`.
- **Quoted key:** a double-quoted string using the escape rules in §5.2. Use quotes for spaces, `=`, `#`, `/`, `@`, Unicode, or other characters outside the bare-key alphabet.
- A quoted key MAY be empty: `"" = value` has the empty string as its key.
- Keys are always strings. Thus `1 = value` has string key `"1"`; `@int 1 = value` is invalid.
- A dot in a key is a literal dot, not a path separator. `a.b = x` and `a = { b = x }` are structurally different.
- A literal `U+FEFF` in a key is invalid; write `"\uFEFF"`.
- Duplicate keys within the same map MUST be rejected. The same key may occur in different nested maps.
- Key insertion order MUST be preserved in the semantic map model.

## 7. Collections

### 7.1 Maps

A map value is `{}` or a block opened by `{` and closed by `}`. Nested map entries follow exactly the same rules as root entries.

```riffle
empty = {}
server = {
    host = localhost
    port = @int 8080
}
```

`{ a = 1 }` is invalid: nonempty collections are multiline. A standalone map can appear as an array element with `{` on its own line.

### 7.2 Untyped arrays

An untyped array is `[]` or a block opened by `[` and closed by `]`. It contains exactly one value per element line. Elements MAY be mixed kinds or nested collections. A map entry's `=` is not used for array elements.

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

A bare array element `null` is the string `"null"`; a null element requires `@null`.

### 7.3 Typed arrays

A typed array is a homogeneous authoring shorthand and validation constraint, written `@type [` for nonempty arrays or `@type []` for empty arrays. The supported element kinds are `string`, `int`, `long`, `decimal`, `boolean`, `null`, `map`, and `array`.

```riffle
ports = @int [
    8080
    8081
]
flags = @boolean [
    true
    false
]
levels = @string [
    low
    medium
    high
]
maybe = @null [
    null
    null
]
servers = @map [
    {
        host = a.example
    }
    {
        host = b.example
    }
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

Precise rules:

1. `@T [` provides the kind `T` for **immediate elements only**. It does not recursively type nested collections.
2. For scalar `T` (`string`, `int`, `long`, `decimal`, `boolean`, `null`), an element line contains the payload without a repeated `@T`. That payload is parsed using the rules of its specified kind. In `@null [`, the only legal element text is the literal `null` (not `@null`).
3. For `@map [`, each element MUST be `{}` or a map block starting with `{` and closing with `}`. For `@array [`, each element MUST be `[]`, a nested array block, or a nested typed array (for example, `@int [`).
4. Explicit scalar markers inside scalar-typed arrays are invalid, even when the kind agrees: `@int [\n @int 1\n]` MUST fail. Use an untyped array if individual elements need explicit markers.
5. Empty typed arrays are valid for every element kind; their element-kind annotation does not persist as a distinct semantic type.
6. A typed array and an untyped array containing the same typed elements yield **equivalent semantic array values**. For example, `@int [\n 1\n]` and `[\n @int 1\n]` both produce an array containing one `int`.
7. A type mismatch MUST be reported with the source element location and category `INVALID_ARRAY_ELEMENT_TYPE`. No automatic coercion is allowed.
8. `@string [` is permitted although `[ ... ]` with plain unquoted elements is shorter. They differ in validation: a typed string array interprets `true` as a string, but a line starting with `@` still requires quoting to avoid ambiguity.
9. Structural annotations on **single** maps or arrays (`@map { ... }`, `@array [ ... ]` outside the typed-array form) are not supported in 0.1.0. The only collection annotation syntax is an element-kind annotation immediately before `[`.
10. Typed arrays are authoring sugar: the resulting value is an ordinary ordered array of typed elements, and the canonical serializer writes explicit `@type` markers on non-string elements (§11.2).

### 7.4 Collection boundaries

Every opener MUST have a matching closer of the same kind. The parser MUST reject mismatched, missing, unexpected, and extra closing delimiters. Nesting is limited by the portable resource limit in §12.

## 8. Comments

Two line-comment markers are supported: `#` and `//`.

- A full-line comment begins after optional indentation with `#` or `//`.
- An inline comment starts only when `#` or `//` is preceded by at least one ASCII space or tab and is outside a quoted string.
- When no separating whitespace precedes a marker, it remains part of a bare string, including in `https://` URLs and `path#fragment`.
- A `"` opens a quoted string only as the first character of a key or of a value. Elsewhere it is ordinary text, so in `k = he said "hi" # c` the marker starts a comment and the value is `he said "hi"`.
- The characters following a comment marker up to the line ending are ignored.
- Comments MAY follow entries, collection openers, and collection closers.

```riffle
# configuration
url = https://example.com/a#frag
pattern = path//segment
name = hello # comment
port = @int 8080 // comment
servers = [ # comment
    first
] # comment
```

Important: `name = hello #world` has string value `"hello"`; quote it as `"hello #world"` if the entire text is intended. `name = hello#world` remains a bare string as written.

## 9. Formal line-oriented grammar

This grammar describes the syntactic structure after splitting the UTF-8 input into physical lines and removing allowed comments. It is intentionally line-oriented rather than pretending the language is whitespace-delimited. `EOL` is LF, CRLF, or end-of-file, as appropriate. `S` is zero or more spaces/tabs; `H` is one or more spaces/tabs. Additional lexical and semantic constraints in §§3–8 are normative.

```ebnf
Document         = { BlankOrComment | MapEntryLine } ;
MapEntryLine      = S, Key, S, "=", S, ValueStart, S, EOL, [ Body ] ;
Key               = BareKey | QuotedString ;
BareKey           = ( ASCII_LETTER | DIGIT | "_" ),
                    { ASCII_LETTER | DIGIT | "_" | "." | "-" } ;

ValueStart        = Scalar | EmptyMap | EmptyArray | MapOpen | ArrayOpen
                  | TypedArrayOpen | EmptyTypedArray ;
Scalar            = BareString | QuotedString | TypedScalar ;
TypedScalar       = "@null" | "@string", H, StringPayload
                  | "@int", H, Integer
                  | "@long", H, Integer
                  | "@decimal", H, Decimal
                  | "@boolean", H, ( "true" | "false" ) ;

EmptyMap          = "{}" ;
EmptyArray        = "[]" ;
MapOpen           = "{" ;
ArrayOpen         = "[" ;
TypedArrayOpen    = "@", ElementType, H, "[" ;
EmptyTypedArray   = "@", ElementType, H, "[]" ;
ElementType       = "string" | "int" | "long" | "decimal" | "boolean"
                  | "null" | "map" | "array" ;

Body              = MapBody | ArrayBody | TypedArrayBody ;
MapBody           = { BlankOrComment | MapEntryLine }, MapCloseLine ;
ArrayBody         = { BlankOrComment | ArrayElementLine }, ArrayCloseLine ;
TypedArrayBody    = { BlankOrComment | TypedElementLine }, ArrayCloseLine ;
ArrayElementLine  = S, ValueStart, S, EOL, [ Body ] ;
TypedElementLine  = S, ElementOfDeclaredType, S, EOL, [ Body ] ;
MapCloseLine      = S, "}", S, EOL ;
ArrayCloseLine    = S, "]", S, EOL ;

Integer           = [ "-" ], ( "0" | NONZERO_DIGIT, { DIGIT } ) ;
Decimal           = [ "-" ], ( "0" | NONZERO_DIGIT, { DIGIT } ),
                    [ ".", DIGIT, { DIGIT } ],
                    [ ( "e" | "E" ), [ "+" | "-" ], DIGIT, { DIGIT } ] ;

S                 = { " " | TAB } ;
H                 = ( " " | TAB ), S ;
```

`BlankOrComment`, `BareString`, `StringPayload`, `QuotedString`, `ElementOfDeclaredType`, `Body` association, and the lexer behavior for comments are defined by §§3–8 and are not independent token-level productions. A `Body` is present when the opener is the block form (`{`, `[`, or `@type [`); the inline empty forms (`{}`, `[]`, `@type []`) have no `Body`. It is specifically the body matching that opener, not an optional arbitrary suffix. A bare scalar is recognized only when the entire non-comment value fragment is consumed, subject to the reserved-prefix rules. This specification favors unambiguous implementation behavior over a compact standalone EBNF.

### 9.1 Parser algorithm (normative behavior)

A conforming parser can be implemented as a line scanner plus a stack:

1. Decode UTF-8, normalize CRLF to LF for line scanning, and track original line/column positions.
2. Start with a root map context.
3. For each line, identify comments without treating markers inside quotes or adjacent to unquoted non-whitespace characters as comments; skip empty/comment-only lines.
4. In a map context, parse one key, `=`, then a single value start; in an array context, parse one element value start; in a typed-array context, parse an element according to its declared kind.
5. If the value starts a nonempty block, insert its collection node at that position and push its map/array context. Do not complete the parent entry a second time when closing the block.
6. On a standalone matching closer, pop the current context. Reject mismatched and root-level closers.
7. At EOF, require the stack to contain only the root map context.
8. Validate duplicate keys, scalar syntax/ranges, array element constraints, decimal normalization (§11.3), and that no noncomment source characters remain unconsumed on any line.

## 10. Error contract

A parser MUST fail the whole document on invalid syntax or a kind/range violation. It MUST NOT silently ignore malformed lines, normalize invalid values into strings, or accept only a prefix of a line.

Every parse error MUST report a stable category from the table below and a 1-based line and column. When a key or element is involved, the diagnostic SHOULD include its path. The exact message wording, source-name format, and column counting unit are implementation-specific; column values MUST be deterministic for a given implementation and input. Diagnostic messages MUST NOT contain scalar values, because configuration files can hold secrets.

The following categories and their triggering conditions are normative:

| Category | Trigger |
| --- | --- |
| `INVALID_UTF8` | Malformed UTF-8, an encoded surrogate code point, or an unpaired surrogate in decoded text |
| `INVALID_LINE_ENDING` | A carriage return not followed by a line feed |
| `INVALID_CHARACTER` | A control character, literal `U+FEFF` outside the document start, or another disallowed literal character |
| `INVALID_ESCAPE` | An unsupported quoted-string escape, malformed `\uXXXX`, or isolated surrogate escape |
| `UNTERMINATED_STRING` | A quoted string has no closing quote on its line |
| `INVALID_KEY` | A map key is neither a bare key nor a valid quoted key |
| `MISSING_EQUALS` | A map entry has no `=`, or a key is not followed by one |
| `MISSING_VALUE` | A typed value has no payload |
| `TRAILING_TOKENS` | Noncomment characters remain after a complete value or closer |
| `INVALID_VALUE` | A bare value starts with a reserved character |
| `UNKNOWN_TYPE` | A type marker names a type that does not exist |
| `INVALID_TYPED_VALUE` | A typed value or type marker has the wrong form |
| `NUMBER_OUT_OF_RANGE` | An integer, long, or normalized decimal is outside its portable range |
| `DUPLICATE_KEY` | The same key appears twice in one map |
| `INVALID_ARRAY_ELEMENT_TYPE` | A typed-array element does not match its declared kind |
| `UNEXPECTED_CLOSER` | A closing delimiter has no open collection |
| `MISMATCHED_CLOSER` | A closing delimiter does not match the open collection |
| `UNCLOSED_COLLECTION` | The document ended inside a collection |
| `UNSUPPORTED_INLINE_COLLECTION` | A nonempty collection was written inline, or an empty collection had interior whitespace |
| `RESOURCE_LIMIT_EXCEEDED` | The nesting limit in §12 was exceeded |

The error location for a mismatch SHOULD point to the offending element or token; an unclosed collection SHOULD report the location of its opener.

## 11. Canonical serialization

A canonical serializer MUST produce deterministic output satisfying this section. Implementations MAY offer other formatters that preserve comments, whitespace, or typed-array syntax; those formatters are not canonical serializers.

### 11.1 Output bytes

Canonical output bytes MUST be:

- UTF-8 without a BOM.
- LF (`U+000A`) line endings, never CRLF or a lone CR.
- Indented with exactly four spaces (`U+0020`) per collection nesting level.
- A final LF after the last nonempty line; an empty document serializes to zero bytes.
- Free of blank lines and comments; neither is part of the value.

### 11.2 Value rendering

- A map root emits one `key = value` line per entry in insertion order.
- An array emits one element per line in element order.
- `{}` and `[]` are emitted for empty collections; nonempty collections use multiline block syntax.
- Bare strings and bare keys are emitted only when they can be parsed back to the same string without ambiguity. Otherwise the serializer emits a quoted string using exactly the escapes in §5.2.
- A non-string scalar is emitted with `@int`, `@long`, `@decimal`, `@boolean`, or `@null`; strings are emitted without a marker unless the original string was explicitly typed (which has no semantic effect).
- Every literal `U+FEFF` in a key or string is emitted as `\uFEFF`.
- Typed arrays are emitted as ordinary arrays whose non-string elements carry explicit `@type` markers. Element order and kind are preserved; the original `@T [` annotation is not.
- A decimal is emitted from its normalized value using §11.3.
- Canonical output MUST reparse to an equal semantic value tree. Serialization MUST NOT change kind, order, or numeric value.
- The root is a map; a serializer MUST reject an array root rather than emit a document that cannot be reparsed.
- A value tree nesting deeper than §12 has no valid document; a serializer MUST reject it rather than emit text a conforming parser must refuse.

### 11.3 Decimal normalization and rendering

This algorithm is normative and MUST be implemented without binary floating point.

**Step 1 — parse lexical parts.** Given a payload matching §5.3, let `S` be its sign (`-` gives sign 1, otherwise 0), let `D0` be the concatenation of its integer and optional fractional digits, let `F` be the number of fractional digits (zero when absent), and let `X` be the mathematical value of the optional exponent (zero when absent). Compute `E0 = X - F` using arbitrary-precision integer arithmetic.

**Step 2 — form the coefficient.** Remove leading zeros from `D0`, leaving one zero if all digits are zero. If the remaining coefficient is zero, emit `0` and stop; its sign and exponent are discarded.

**Step 3 — normalize trailing zeros.** Remove trailing zeros from the nonzero coefficient. Let `t` be the count removed and set `E = E0 + t`. If `E` is outside `[-2147483647, 2147483648]`, reject the payload with `NUMBER_OUT_OF_RANGE`.

**Step 4 — choose plain or scientific form.** Let `C` be the decimal digit string of the normalized coefficient and `n` its digit count. Let `A = E + n - 1` be the decimal exponent of the most significant digit. Compute the plain token length including a leading minus sign when sign is 1:

- If `E >= 0`, the digit count is `n + E` and there is no decimal point.
- If `E < 0` and `A >= 0`, the digit count is `n + 1` for the inserted decimal point.
- If `A < 0`, the digit count is `n + 1 - A` (`0.`, `-A - 1` zeros, then `C`).

If the plain token length is **256 or fewer characters**, emit plain form. Otherwise emit scientific form. Because the token contains only ASCII digits, `+`, `-`, `.`, and `E`, its character count equals its UTF-8 byte count. This length comparison excludes the three-character `@decimal ` prefix.

**Step 5 — render.** For plain form:

- If `E >= 0`, write `C` followed by `E` zero digits.
- If `E < 0` and `A >= 0`, write the first `A + 1` digits of `C`, a `.`, then the remaining digits.
- If `A < 0`, write `0.`, then `-A - 1` zero digits, then `C`.
- Prefix `-` when sign is 1.

For scientific form, write the first digit of `C`; if `n > 1`, write `.` followed by the remaining digits; write `E`; write `+` when `A >= 0` or `-` when `A < 0`; then write the decimal digits of `|A|` with no leading zeros and no redundant fractional zeros. Zero is never written in scientific form.

Examples:

| Input payload | Normalized `(C, E)` | Canonical token |
| --- | --- | --- |
| `0` | `(0, 0)` | `0` |
| `-0` | `(0, 0)` | `0` |
| `0.0` | `(0, 0)` | `0` |
| `1` | `(1, 0)` | `1` |
| `1.00` | `(1, 0)` | `1` |
| `1000` | `(1, 3)` | `1000` |
| `1E+3` | `(1, 3)` | `1000` |
| `1e-3` | `(1, -3)` | `0.001` |
| `-0.00100` | `(sign 1, C=1, E=-3)` | `-0.001` |
| `1e-254` | `(1, -254)` | `0.` followed by 253 zeros and `1` (256 characters) |
| `1e-255` | `(1, -255)` | `1E-255` |
| `1.23e2147483650` | `(123, 2147483648)` | `1.23E+2147483650` |

The threshold rule was clarified in 0.1.0: a plain decimal longer than 256 characters is now always rendered in scientific form. Previously a very large coefficient with zero scale could still be written in plain form; that output remains valid input but is no longer canonical.

## 12. Resource limits

The following limits are part of the portable 0.1.0 contract:

- **Nesting depth:** at most 128 nonempty collections between the root map and any value. The root map itself does not count. An inline empty collection does not open a block and does not count. A document exceeding the limit MUST be rejected with `RESOURCE_LIMIT_EXCEEDED`; a canonical serializer MUST reject a value tree exceeding it.
- **Decimal exponent:** the normalized exponent range in §3.3 is mandatory for every implementation. A literal outside it MUST be rejected with `NUMBER_OUT_OF_RANGE`.

Implementations MAY impose additional documented limits on total input size, line length, coefficient length, or output size. Such limits MUST NOT reject any fixture in the conformance suite or otherwise change the meaning of a conforming document within the portable limits. There is no mandatory document-size limit.

## 13. JSON interoperability boundaries

Riffle's value model can be mapped to JSON objects, arrays, strings, numbers, booleans, and null. However, exact numeric subtype round trips are not guaranteed because JSON has a single number syntax and no distinct `int`/`long`/`decimal` annotation. Riffle implementations MUST document the numeric mapping strategy if they provide JSON import or export. A JSON `null` maps to Riffle `null`; a JSON string `"null"` maps to a Riffle string. JSON object order can be retained when an ordered JSON representation is used, but JSON semantics do not require meaningful object-key order.

## 14. Conformance examples

### 14.1 Valid cases and expected meaning

```riffle
# blank string, literal strings, explicit null
empty =
stringTrue = true
stringNull = null
stringTilde = ~
booleanTrue = @boolean true
nullValue = @null
literalAt = "@null"

# quoted key and punctuation
"key with spaces" = a=b
url = https://example.org/a#fragment

# empty collections
emptyMap = {}
emptyArray = []
emptyInts = @int []

# nested values and mixed array
complex = {
    items = [
        text
        @long 9000000000
        @decimal 1.25
        @null
        {
            enabled = @boolean false
        }
        [
            nested
        ]
    ]
}

# homogeneous array
ports = @int [
    80
    443
]
```

Expected notable distinctions: `stringTrue` is a string, not a boolean; `stringNull` is a string, not null; `empty` is `""`; `emptyInts` is an empty ordinary array. Typed arrays in these examples are authoring sugar; the canonical serializer writes untyped arrays with `@type` elements.

### 14.2 Invalid cases (each snippet is a separate document)

| Input | Required failure |
| --- | --- |
| `x = 1 y = 2` | One-entry-per-line rule; this is not two entries (it is a single string value). To test two entries on one line use `x = {} y = 2`, which fails `TRAILING_TOKENS`. |
| `x = @int` | Missing typed payload |
| `x = @int 01` | Invalid integer lexical syntax |
| `x = @int 2147483648` | 32-bit overflow |
| `x = @boolean yes` | Invalid boolean |
| `x = @null null` | Extra payload |
| `x = @float 1.0` | Unknown type |
| `x = @int "1"` | Quoted numeric payload forbidden |
| `x = { a = b }` | Nonempty inline map forbidden |
| `x = [ one ]` | Nonempty inline array forbidden |
| `x = @int [ 1 ]` | Nonempty inline typed array forbidden |
| `x = { }` | Empty collection with interior whitespace; write `{}` or a block |
| `x = [\n  @int 1\n}` | Mismatched closing delimiter |
| `x = @int [\n  @int 1\n]` | Repeated explicit scalar annotation in typed array |
| `x = @int [\n  text\n]` | Invalid int element |
| `x = @map [\n  hello\n]` | Expected map element |
| `x = @null [\n  hello\n]` | Expected literal `null` element |
| `x = a\nx = b` | Duplicate key |
| `@int 1 = a` | Invalid map key |
| `x = "abc` | Unterminated string |
| `x = "bad\\q"` | Invalid escape |
| `x = {\n y = z` | Unclosed map at EOF |

### 14.3 Required test categories

Implementations SHOULD include table-driven tests covering UTF-8 and Unicode escapes; LF/CRLF/EOF; comments and URL fragments; all scalar kinds and numeric boundaries; bare versus quoted strings; empty and nested collections; mixed and typed arrays; order preservation; duplicate keys; delimiter mismatch; extra same-line tokens; accurate line/column errors; serializer/parser semantic round trips; and resource-limit failures.

## 15. Non-goals for 0.1.0

The following are deliberately excluded:

- Map keys of kinds other than string.
- Implicit scalar inference and implicit numeric/string coercion.
- Mixed values in a typed array, optional or nullable typed elements (use an untyped array with `@null` for that).
- Multi-entry lines, comma-delimited arrays, semicolon terminators, and nonempty inline collections.
- Multiline strings, heredocs, and line continuations.
- Type unions, generic element kinds, typed nested-array propagation, schemas, or validation beyond syntax and declared element kind.
- Dot-path assignment semantics, duplicate-key merging, substitution, includes, environment expansion, expressions, and conditionals.
- A single unified integer kind; `int` and `long` remain distinct.
- New kinds such as floats, doubles, unsigned integers, dates, durations, or application-specific scalars.

These may be considered in future versions only through explicit changes to this specification.

## 16. Implementation acceptance criteria

A 0.1.0 implementation is conformant when it:

1. Correctly parses every valid example and rejects every invalid example above.
2. Uses one EOL-terminated map entry or array element per line, with block collection closers on separate lines.
3. Preserves exact scalar kinds, map insertion order, and array order.
4. Enforces the strict `@type` rules and typed-array element constraints with no implicit conversion.
5. Produces diagnostics with stable categories and deterministic line/column values.
6. Canonicalizes decimals exactly as specified in §11.3.
7. Can serialize any supported semantic value tree and parse its output into an equal tree.
8. Enforces the portable resource limits in §12.
9. Does not introduce undocumented extensions that change the meaning of conforming 0.1.0 input.

## Appendix A: Reference Java mapping (non-normative)

This appendix describes the Java reference implementation only. It is informative and does not define Riffle.

| Riffle kind | Java 17 type |
| --- | --- |
| `string` | `String` |
| `int` | `int` / `Integer` |
| `long` | `long` / `Long` |
| `decimal` | `BigDecimal`, normalized with `stripTrailingZeros()` |
| `boolean` | `boolean` / `Boolean` |
| `null` | dedicated `RiffleNull` value |
| `map` | immutable insertion-ordered `Map<String, RiffleValue>` |
| `array` | immutable ordered `List<RiffleValue>` |

Java columns count UTF-16 code units; other host languages may count Unicode code points or bytes. Parsing a `Path` uses a strict UTF-8 decoder and reports malformed input as `INVALID_UTF8`. Parsing a Java `String` rejects unpaired surrogates with the same category.

The optional `RiffleJavaValues` adapter maps values to and from ordinary Java `String`, `Integer`, `Long`, `BigDecimal`, `Boolean`, `null`, `Map<String, Object>`, and `List<Object>`. It is an opt-in convenience layer; the strict `RiffleValue` accessors never perform those conversions implicitly.

## Appendix B: Informative host-language mappings (non-normative)

These mappings are examples for future implementations, not requirements of the language. A host with one integer type still preserves `int` and `long` as distinct Riffle kinds.

| Riffle kind | Java | Go | Rust | Python |
| --- | --- | --- | --- | --- |
| `string` | `String` | `string` | `String` | `str` |
| `int` | `int` | `int32` | `i32` | `int` with range check |
| `long` | `long` | `int64` | `i64` | `int` with range check |
| `decimal` | `BigDecimal` | `math/big.Rat` plus exponent or a decimal package | decimal crate | `decimal.Decimal` |
| `boolean` | `boolean` | `bool` | `bool` | `bool` |
| `null` | dedicated sentinel | `nil` in a typed wrapper | `Option` sentinel | `None` in a typed wrapper |
| `map` | ordered map | ordered slice of pairs | `IndexMap` or vec of pairs | `dict` preserving insertion order |
| `array` | ordered list | `[]T` | `Vec<T>` | `list` |

> **Guidance for an AI implementer:** Implement a lexer/line scanner, value model, parser, serializer, and focused tests from this specification. Do not improvise extra syntax. When a corner case is not specified, prefer a clear parse error and a regression test over permissive guessing.
