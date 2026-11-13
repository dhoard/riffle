# Riffle Language Specification

**Version:** 0.1.0  
**Status:** Initial normative specification  
**Target:** Human-authored application configuration  
**Baseline implementation:** Java 17  

## 1. Scope and design goals

Riffle is a small, deterministic, **end-of-line (EOL)-terminated** configuration language. It is a configuration format, not a programming language or a data interchange protocol.

Normative terms **MUST**, **MUST NOT**, **SHOULD**, and **MAY** have their usual requirements meanings. This document is the authority for 0.1.0 behavior; an implementation MUST NOT invent additional syntax or silent coercion.

Core properties:

- The document root is an **implicit, insertion-ordered map** of string keys to values.
- A map member uses `key = value`; each member occupies **one logical line**, except that a collection value spans its own block of subsequent lines.
- An array contains one element per logical line, except that a collection element spans its own block.
- `{` and `}` delimit maps; `[` and `]` delimit arrays; indentation has no syntactic significance.
- No commas or semicolons delimit entries. A **newline**, not whitespace alone, terminates each entry.
- String is the default scalar type. Every non-string scalar has an explicit `@type` marker.
- An untyped array can contain values of different types. A typed array (`@type [`) supplies the scalar type or collection-kind constraint for each element.
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

Typed arrays such as `@string [...]` are accepted as authoring sugar, but the canonical
serializer writes untyped arrays with `@type` element markers (§7.3 rule 6, §11), so this
example uses the untyped form. Blank lines are allowed and ignored (§3.5); the canonical
rendering omits them, as shown here.

## 3. Character encoding, lines, and whitespace

1. Files MUST be valid UTF-8. A UTF-8 BOM at the start MAY be accepted and ignored; a BOM elsewhere is invalid.
2. Line endings MAY be LF (`\n`) or CRLF (`\r\n`). A lone CR (`\r`) is invalid.
3. The final line MAY omit its line terminator.
4. Spaces (`U+0020`) and horizontal tabs (`U+0009`) are indentation and horizontal separators. They are not significant outside quoted strings except where a separator is required.
5. Blank lines and comment-only lines are ignored, including inside collections.
6. An entry or close delimiter MUST terminate at EOL, optionally followed by a valid inline comment. No two map members or array elements may occur on the same line.
7. A `{`, `[` or `@type [` opening block MUST be the last syntactic token on its line. Its contents begin on following lines. An empty collection MAY be written inline as `{}`, `[]` or `@type []`, or as an empty block (`{` then `}` on separate lines, likewise `[` and `]`); the canonical serializer writes the inline form (§11). Interior whitespace inside an inline empty collection (`{ }`, `[ ]`, `@type [ ]`) is invalid.
8. A nonempty block's `}` or `]` MUST be on its own line, apart from indentation and an optional inline comment.
   An implementation SHOULD report a nonempty inline collection with a diagnostic that names the block form and points at the first content token. An empty collection written with interior whitespace (`{ }`, `[ ]`, `@type [ ]`) is likewise invalid; the diagnostic SHOULD name `{}`, `[]` or `@type []` rather than describe the collection as nonempty.
9. Strings MUST NOT span physical lines in version 0.1.0. There is no continuation character or multiline-string syntax.

## 4. Types and data model

The value model contains exactly these kinds:

| Kind | Syntax | Semantics / Java 17 reference type |
|---|---|---|
| string | `text` or `"text"` or `@string text` | Unicode string / `String` |
| int | `@int 42` | Signed 32-bit integer / `Integer` |
| long | `@long 42` | Signed 64-bit integer / `Long` |
| decimal | `@decimal 0.25` | Arbitrary-precision base-10 number / `BigDecimal` |
| boolean | `@boolean true` | Boolean / `Boolean` |
| null | `@null` | Null value (dedicated node or Java `null` where supported) |
| map | `{ ... }` | Insertion-ordered string-to-value map / `LinkedHashMap<String, Value>` |
| array | `[ ... ]` | Ordered list / `List<Value>` |

There is **no automatic type inference**. Thus `123`, `true`, `null`, and `~` are all strings; `@int 123`, `@boolean true`, and `@null` are not.

The numeric types are distinct. `@int` MUST fit `[-2147483648, 2147483647]`; `@long` MUST fit `[-9223372036854775808, 9223372036854775807]`. `@decimal` has arbitrary precision and a finite base-10 value, not IEEE 754 NaN or infinity. Lexical scale is not part of the semantic value: `@decimal 1.0` and `@decimal 1` are the same value. The canonical serializer writes the normalized value (§11), so `@decimal 1.0` is written `@decimal 1`.

Type identifiers are lowercase ASCII and case-sensitive. Unknown identifiers such as `@float` MUST fail. Explicit `@string` is allowed for scalar strings even though strings are the default.

## 5. Map keys and assignments

### 5.1 Map form

A root document consists of zero or more map entries. A nested map is opened with `{` and closed with `}`. Every entry has the form:

```riffle
key = value
```

Exactly one `=` separates the key from the value. Additional `=` characters in an unquoted value are ordinary string characters. Spaces/tabs around the delimiter are optional: `key=value`, `key = value`, and `key= value` are equivalent.

### 5.2 Keys

- **Bare key:** matches `[A-Za-z0-9_][A-Za-z0-9_.-]*`. Examples: `provider`, `chatgpt-oauth`, `1`, `schema.version`.
- **Quoted key:** a double-quoted string using the escape rules in §6.2. Use quotes for spaces, `=`, `#`, `/`, `@`, Unicode, or other characters outside the bare-key alphabet.
- A quoted key MAY be empty: `"" = value` has the empty string as its key.
- Keys are **always strings**. Thus `1 = value` has string key `"1"`; `@int 1 = value` is invalid.
- A dot in a key is a literal dot, **not a path separator**. `a.b = x` and `a = { b = x }` are structurally different.
- Duplicate keys within the same map MUST be rejected; comparison is exact Unicode code-point sequence equality, without normalization or case folding. The same key may occur in different nested maps.
- Key insertion order MUST be preserved in the semantic map model.

## 6. Scalar values

### 6.1 Bare strings

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

For predictability, a bare string MUST NOT contain an unescaped ASCII control character. Backslashes in bare strings are ordinary literal characters; there are **no** bare-string escapes. Quoting is the escape hatch.

### 6.2 Quoted strings

A double-quoted string is allowed anywhere a string value or key is allowed. Its first and last non-whitespace syntactic characters are `"`. It MUST have no trailing characters other than whitespace or a valid inline comment.

Supported escapes are exactly: `\\"`, `\\\\`, `\\n`, `\\r`, `\\t`, `\\b`, `\\f`, `\\uXXXX` (four hexadecimal digits). JSON-style surrogate-pair handling MUST be implemented correctly for `\\u` escapes; isolated surrogate code points MUST be rejected. Invalid escape sequences and unclosed quotes MUST fail. Literal newlines and unescaped control characters inside quoted strings are invalid.

Examples:

```riffle
literalMarker = "@int 123"
literalBrace = "{"
literalComment = "# not a comment"
"display name" = "Jane Doe"
```

### 6.3 Typed scalars

Typed scalars begin at the first non-whitespace character with `@` followed by one of the exact type identifiers. A horizontal-space separator is REQUIRED between `@int`, `@long`, `@decimal`, `@boolean`, or `@string` and its payload; `@null` has **no** payload. The payload terminates at the same EOL as the entry.

```riffle
count = @int 123
large = @long 9223372036854775807
fraction = @decimal -0.125
active = @boolean true
text = @string true
missing = @null
```

- Integers are base 10 with optional leading `-`, digits `0` or `[1-9][0-9]*`; `+`, underscores, hex, leading zeros except `0`, and decimal points are invalid.
- Decimal values use the grammar `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`. Thus `@decimal 12`, `@decimal -0.25`, and `@decimal 1e3` are valid, but `NaN`, `Infinity`, `.5`, and `1.` are not.
- Booleans are lowercase `true` and `false` only.
- `@null` MUST NOT be followed by a scalar payload. `missing = @null null` is invalid.
- `@string` consumes either a quoted string or the remainder of the line as a bare string, applying the same comment and whitespace rules. It is primarily useful for clarity. The reserved-prefix rule of §6.1 applies to the first character of the value, not to the payload, so `text = @string @int 1` is the string `@int 1`.
- `@int`, `@long`, `@decimal`, `@boolean` MUST NOT accept quoted payloads or extra tokens.
- Each type marker denotes a value, not a declaration for subsequent lines.

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

An untyped array is `[]` or a block opened by `[` and closed by `]`. It contains exactly one value per element line. Elements MAY be mixed types or nested collections. A map entry's `=` is **not** used for array elements.

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

A typed array is a **homogeneous shorthand and validation constraint**, written `@type [` for nonempty arrays or `@type []` for empty arrays. The supported element kinds are `string`, `int`, `long`, `decimal`, `boolean`, `null`, `map`, and `array`.

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

1. `@T [` provides the type `T` for **immediate elements only**. It does not recursively type nested collections.
2. For scalar `T` (`string`, `int`, `long`, `decimal`, `boolean`, `null`), an element line contains the **payload without a repeated `@T`**. That payload is parsed using the rules of its specified type. In `@null [`, the only legal element text is the literal `null` (not `@null`).
3. For `@map [`, each element MUST be `{}` or a map block starting with `{` and closing with `}`. For `@array [`, each element MUST be `[]`, a nested array block, or a nested typed array (e.g., `@int [`).
4. Explicit scalar type markers inside scalar-typed arrays are invalid, even when the type agrees: `@int [\n @int 1\n]` MUST fail. Use an untyped array if individual elements need explicit markers.
5. Empty typed arrays are valid for every element kind; their element-type annotation does not persist as a distinct runtime type.
6. A typed array and an untyped array containing the same typed elements yield **equivalent semantic array values**. For example, `@int [\n 1\n]` and `[\n @int 1\n]` both produce an array containing one integer.
7. Type mismatch MUST be reported as an error, with the source element location. No automatic coercion to another type is allowed.
8. `@string [` is permitted although `[ ... ]` with plain unquoted elements is shorter. They differ in validation: a typed string array interprets `true` as a string, but a line starting with `@` still requires quoting to avoid ambiguity.
9. Structural type annotations on **single** maps or arrays (`@map { ... }`, `@array [ ... ]` outside the typed-array form) are not supported in 0.1.0. The only collection annotation syntax is an element-kind annotation immediately before `[`. This avoids two redundant representations of ordinary collection values.

### 7.4 Collection boundaries

Every opener MUST have a matching closer of the same kind. The parser MUST reject mismatched, missing, unexpected, and extra closing delimiters. Nested collection depth is logically unbounded; implementations MAY enforce a documented resource limit with a clear diagnostic.

## 8. Comments

Two line-comment markers are supported: `#` and `//`.

- A full-line comment begins after optional indentation with `#` or `//`.
- An inline comment starts **only** when `#` or `//` is preceded by at least one ASCII space or tab and is outside a quoted string.
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

This grammar describes the **syntactic structure** after splitting the UTF-8 input into physical lines and removing allowed comments. It is intentionally line-oriented rather than pretending the language is whitespace-delimited. `EOL` is LF, CRLF, or end-of-file, as appropriate. `S` is zero or more spaces/tabs; `H` is one or more spaces/tabs. Additional lexical and semantic constraints in §§3–8 are normative.

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

`BlankOrComment`, `BareString`, `StringPayload`, `QuotedString`, `ElementOfDeclaredType`, `Body` association, and the lexer behavior for comments are defined by §§3–8 and are not independent token-level productions. A `Body` is present when the opener is the block form (`{`, `[` or `@type [`); the inline empty forms (`{}`, `[]`, `@type []`) have no `Body`. It is specifically the body matching that opener, not an optional arbitrary suffix. A bare scalar is recognized only when the entire non-comment value fragment is consumed, subject to the reserved-prefix rules. This specification favors unambiguous implementation behavior over a compact standalone EBNF.

### 9.1 Parser algorithm (normative behavior)

A conforming parser can be implemented as a line scanner plus a stack:

1. Decode UTF-8, normalize CRLF to LF for line scanning, and track original line/column positions.
2. Start with a root map context.
3. For each line, identify comments without treating markers inside quotes or adjacent to unquoted non-whitespace characters as comments; skip empty/comment-only lines.
4. In a map context, parse one key, `=`, then a single value start; in an array context, parse one element value start; in a typed-array context, parse an element according to its declared element type.
5. If the value starts a nonempty block, insert its collection node at that position and push its map/array context. Do not complete the parent entry a second time when closing the block.
6. On a standalone matching closer, pop the current context. Reject mismatched and root-level closers.
7. At EOF, require the stack to contain only the root map context.
8. Validate duplicate keys, scalar syntax/ranges, array element constraints, and that no noncomment source characters remain unconsumed on any line.

## 10. Error handling and diagnostics

A parser MUST fail the whole document on invalid syntax or a type/range violation. It MUST NOT silently ignore malformed lines, normalize invalid values into strings, or accept only a prefix of a line.

Every error MUST have a stable error category and at least a 1-based line and column; the relevant key/path and brief explanation SHOULD be included. Recommended categories:

- `INVALID_UTF8`, `INVALID_LINE_ENDING`, `INVALID_ESCAPE`, `UNTERMINATED_STRING`
- `INVALID_KEY`, `MISSING_EQUALS`, `MISSING_VALUE`, `TRAILING_TOKENS`
- `UNKNOWN_TYPE`, `INVALID_TYPED_VALUE`, `NUMBER_OUT_OF_RANGE`
- `DUPLICATE_KEY`, `INVALID_ARRAY_ELEMENT_TYPE`
- `UNEXPECTED_CLOSER`, `MISMATCHED_CLOSER`, `UNCLOSED_COLLECTION`
- `UNSUPPORTED_INLINE_COLLECTION`, `RESOURCE_LIMIT_EXCEEDED`

The error location for a mismatch SHOULD point to the offending element or token; an unclosed collection SHOULD report the location of its opener.

## 11. Canonical serialization

A serializer SHOULD produce deterministic, readable output:

- UTF-8 with LF line endings and a final newline for nonempty output.
- Four spaces of indentation per nesting level.
- `key = value` for map entries, one per line; one array element per line.
- `{}` and `[]` for empty collections; multiline block syntax for nonempty collections.
- Original map insertion order and array order.
- No blank lines and no comments; both are accepted on input and are not part of the value.
- Bare strings/keys only when they can be parsed without ambiguity; otherwise use quoted strings.
- `@int`, `@long`, `@decimal`, `@boolean`, `@null` for non-string scalars.
- Typed arrays are authoring sugar and not part of the semantic value model, so the canonical serializer writes untyped arrays with `@type` element markers. A formatter operating on a syntax tree MAY preserve typed-array syntax if it retains that source information.
- A decimal is written from its normalized value (§4), so `@decimal 1.0` is written `@decimal 1`.
- The document root is a map (§1), so a serializer MUST reject an array root rather than emit a document that cannot be reparsed.

Serialization MUST produce text that reparses to the same semantic value tree. It need not preserve comments, spacing, original quoting, or original typed-array annotations unless a separate lossless syntax-tree API is explicitly offered.

## 12. JSON interoperability boundaries

Riffle's value model can be mapped to JSON objects/arrays, strings, numbers, booleans, and null. However, **exact numeric subtype round trips are not guaranteed** because JSON has a single number syntax and no distinct `int`/`long`/`decimal` annotation. Riffle implementations MUST document the numeric mapping strategy if they provide JSON import/export. A JSON `null` maps to `@null` in Riffle; a JSON string `"null"` maps to a Riffle string `null` or `"null"`. JSON object order can be retained when an ordered JSON representation is used, but JSON semantics do not require meaningful object-key order.

## 13. Conformance examples

### 13.1 Valid cases and expected meaning

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

Expected notable distinctions: `stringTrue` is a string, not a boolean; `stringNull` is a string, not null; `empty` is `""`; `emptyInts` is an empty ordinary array at runtime. Typed arrays in these examples are authoring sugar; the canonical serializer writes untyped arrays with `@type` elements (§11).

### 13.2 Invalid cases (each snippet is a separate document)

| Input | Required failure |
|---|---|
| `x = 1 y = 2` | One-entry-per-line rule; this is **not** two entries (it is a single string value). To test two entries on one line use `x = {} y = 2`, which fails `TRAILING_TOKENS`. |
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

### 13.3 Required test categories

Implementations SHOULD include table-driven tests covering: UTF-8 and Unicode escapes; LF/CRLF/EOF; comments and URL fragments; all scalar types and numeric boundaries; bare versus quoted strings; empty and nested collections; mixed and typed arrays; order preservation; duplicate keys; delimiter mismatch; extra same-line tokens; accurate line/column errors; serializer/parser semantic round trips; and resource-limit failures where limits are configured.

## 14. Non-goals for 0.1.0

The following are deliberately excluded:

- Map keys of types other than string.
- Implicit scalar inference and implicit numeric/string coercion.
- Mixed values in a typed array, optional or nullable typed elements (use an untyped array with `@null` for that).
- Multi-entry lines, comma-delimited arrays, semicolon terminators, and nonempty inline collections.
- Multiline strings, heredocs, and line continuations.
- Type unions, generic element types, typed nested-array propagation, schemas, or validation beyond syntax and declared element kind.
- Dot-path assignment semantics, duplicate-key merging, substitution, includes, environment expansion, expressions, and conditionals.

These may be considered in future versions only through explicit changes to the specification.

## 15. Implementation acceptance criteria

A 0.1.0 implementation is conformant when it:

1. Correctly parses every valid example and rejects every invalid example above.
2. Uses one EOL-terminated map entry or array element per line, with block collection closers on separate lines.
3. Preserves exact scalar value kinds, map insertion order, and array order.
4. Enforces the strict `@type` rules and typed-array element constraints with no implicit conversion.
5. Produces useful diagnostics with line/column and stable categories.
6. Can serialize any supported semantic value tree and parse its output into an equivalent tree.
7. Does not introduce undocumented extensions that change the meaning of conforming 0.1.0 input.

**Guidance for an AI implementer:** Implement a lexer/line scanner, AST/value model, parser, serializer, and focused unit tests from this spec. Do not improvise extra syntax. When a corner case is not specified, prefer a clear parse error and a regression test over permissive guessing.
