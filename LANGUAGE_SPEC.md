# Riffle Language Specification

**Version:** 0.1.0
**Status:** Normative language specification
**Applies to:** All Riffle 0.1.0 implementations, independent of host language
**Reference implementation:** Java 17 (see the non-normative [Appendix A](#appendix-a-reference-java-mapping-non-normative))

## 1. Scope and terminology

Riffle is a small, deterministic, **end-of-line (EOL)-terminated** configuration language. It is a configuration format, not a programming language or a data interchange protocol.

Riffle follows one principle: **the text the author writes is exactly the text the program receives.** Riffle does not guess types and does not require type markers. The program reading a value decides how to interpret it, through accessors whose accepted syntax this specification defines exactly (§13).

The normative terms **MUST**, **MUST NOT**, **SHOULD**, and **MAY** have their usual requirements meanings. This document defines Riffle 0.1.0. An implementation MUST NOT invent additional syntax or silent coercion.

Normative sections describe observable input, semantic values, canonical output, errors, and limits. Host-language type names appear only in non-normative appendices and in the implementation guide, never as part of the language definition.

Schema validation is specified separately by [`SCHEMA_SPEC.md`](SCHEMA_SPEC.md), the Riffle Schema Specification. A conforming language implementation need not offer it; an implementation that does offer schema validation MUST conform to that specification as well.

Core properties:

- The document root is an **implicit, insertion-ordered map** of string keys to values.
- A map member uses `key : value`; each member occupies **one logical line**, except that a collection or raw block value spans its own block of subsequent lines.
- An array contains one element per logical line, except that a collection or raw block element spans its own block.
- `{` and `}` delimit maps; `[` and `]` delimit arrays; indentation has no syntactic significance outside raw blocks.
- No commas or semicolons delimit entries. A **newline**, not whitespace alone, terminates each entry.
- `#` starts a line comment as the first non-whitespace character of a line; `//` is ordinary text. A line holding `/#` and a line holding `#/` delimit a nestable block comment region. Comments occupy whole lines and are never part of a value (§8).
- Every scalar is a **string**. A bare value runs to the end of its line with no escaping. `"""` opens a multiline raw block (§5.3).
- Keys are always strings.
- The format does not perform variable expansion, environment substitution, includes, computation, merging, or coercion.

## 2. Canonical example

```riffle
schemaVersion : 1
provider : chatgpt
model : gpt-6-sol
transport : chatgpt-oauth
capabilities : {
    tools : true
    streaming : true
    contextWindow : 272000
    maxOutputTokens : 128000
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

Blank lines are allowed and ignored; the canonical rendering omits them.

## 3. Value model

The value model contains exactly these three kinds:

| Kind | Syntax | Portable semantics |
| --- | --- | --- |
| `string` | `text`, `"text"`, or a raw block | A finite sequence of Unicode scalar values other than `U+0000` (§3.1) |
| `map` | `{ ... }` | An ordered map from strings to values |
| `array` | `[ ... ]` | An ordered sequence of values |

There is **no type inference**. `123`, `true`, `null`, and `~` are strings. A program that wants the number 123 or the boolean true asks for that interpretation (§13). An absent key is the only way to express "no value".

### 3.1 Strings and Unicode

A string is a sequence of Unicode scalar values: Unicode code points excluding the surrogate range `U+D800`–`U+DFFF`. `U+0000` MUST NOT occur in a string or key, so implementations can use NUL-terminated strings safely. Equality is exact code-point sequence equality: `1.0` and `1.00` are different strings, and compare equal only after a program interprets them as decimals. Implementations MUST NOT apply Unicode normalization, case folding, locale-specific transformations, or replacement of unrecognized characters when comparing strings.

Literal input MUST be UTF-8 (§4). Text containing an encoded surrogate code point or an unpaired surrogate in a host-language string is not a valid Riffle string and MUST be rejected with `INVALID_UTF8`.

`U+FEFF` is a Unicode scalar value and MAY occur inside a string. A literal `U+FEFF` is legal only as the first character of a document, where it is a byte-order mark (BOM) and is ignored. Anywhere else a literal `U+FEFF` is `INVALID_CHARACTER`; inside a key or string it MUST be written as the escape `﻿`. The canonical serializer always escapes `U+FEFF` inside keys and values (§11.2).

### 3.2 Maps and arrays

A map preserves insertion order for parsing, semantic iteration, and canonical writing. Two maps are equal when they contain the same keys mapped to equal values; map entry order does not affect equality. This means two maps with equal entries in different orders parse to unequal iteration sequences but equal values.

An array preserves element order for parsing, semantic iteration, canonical writing, and equality. Two arrays are equal only when they contain equal elements at the same indexes.

Keys are compared as exact strings. Duplicate keys within one map MUST be rejected, even if their values are equal. The same key MAY occur in different nested maps.

Source locations, key paths, comments, and original quoting are diagnostic or authoring metadata. They are not part of semantic equality.

## 4. Character encoding, lines, and whitespace

1. Files MUST be valid UTF-8. A UTF-8 BOM at the start MAY be accepted and ignored; the reference implementation accepts and ignores it. A BOM elsewhere is invalid and MUST be reported as `INVALID_CHARACTER`.
2. Line endings MAY be LF (`\n`) or CRLF (`\r\n`). A lone CR (`\r`) is invalid. The final line MAY omit its line terminator.
3. Spaces (`U+0020`) and horizontal tabs (`U+0009`) are indentation and horizontal separators. They are not significant outside quoted strings and raw blocks except where a separator is required.
4. Blank lines and comment-only lines are ignored, including inside collections. Inside a raw block they are content.
5. An entry or close delimiter MUST terminate at EOL, followed only by optional horizontal whitespace. No two map members or array elements may occur on the same line.
6. A `{` or `[` opening block MUST be the last syntactic token on its line. Its contents begin on following lines. An empty collection MAY be written inline as `{}` or `[]`, or as an empty block (`{` then `}` on separate lines, likewise `[` and `]`); the canonical serializer writes the inline form (§11). Interior whitespace inside an inline empty collection (`{ }`, `[ ]`) is invalid.
7. A nonempty block's `}` or `]` MUST be on its own line, apart from indentation and trailing whitespace. An implementation SHOULD report a value that starts with `{` or `[` and is neither an opener nor an empty collection with a diagnostic that first suggests quoting the value, then names the block form. An empty collection written with interior whitespace is likewise invalid; the diagnostic SHOULD name `{}` or `[]` rather than describe the collection as nonempty.
8. A string spans physical lines only through a raw block (§5.3). There is no continuation character.

## 5. Scalar values

### 5.1 Bare strings

Unless the value begins with a reserved character, an unquoted value is a string. Leading and trailing horizontal whitespace are trimmed; interior whitespace is preserved. `key :` (including trailing spaces) denotes the empty string.

Examples:

```riffle
home : ~/projects
url : https://example.org/a#fragment
password : abc=123
message : Hello: world
name : null
blank :
```

> **A `#` after a value is data.** `name : prod # primary` is the string `prod # primary`. This is the deliberate cost of values that need no escaping: everything after the colon, to the end of the line, belongs to the value. Put a comment on its own line.

The first non-whitespace character of a **bare value or array element** MUST NOT be `@`, `{`, `[`, `}`, `]`, or `"` (these characters are syntactically reserved at that position). Quote strings that begin with them: `pkg : "@scope/name"`. These characters MAY appear later within a bare string. `@` is reserved for possible future markers; a bare value that starts with it MUST be rejected with `INVALID_VALUE`, and the diagnostic SHOULD tell the author to quote the value. A `#` after the first character is data: comments occupy whole lines (§8.1). A bare value consisting of `}` or `]` is always a closing delimiter when it appears alone on a collection line.

A bare string MUST NOT contain an unescaped ASCII control character (`U+0000`–`U+001F` or `U+007F`) or a literal `U+FEFF`. Backslashes in bare strings are ordinary literal characters; there are no bare-string escapes. Quoting is the escape hatch.

### 5.2 Quoted strings

A double-quoted string is allowed anywhere a string value or key is allowed. Its first and last non-whitespace syntactic characters are `"`. It MUST have no trailing characters other than whitespace.

A value fragment that is exactly `"""` after trimming horizontal whitespace is always a raw-block opener (§5.3). Any other fragment starting with `"` follows the rules of this section, so `x : """abc"""` is the single-line quoted value `""abc""`.

**Quoted values** (map values and array elements) are EOL-delimited. The opening delimiter is the first `"` of the value fragment. The closing delimiter is the **last `"` of that fragment**, before optional trailing horizontal whitespace. Every `"` inside the two delimiters is literal data, whether written literally or as `\"`; both spellings decode to the same quote character. The closing delimiter is a delimiter even when the immediately preceding character is a backslash, so `x : "abc\"` has a closing quote but an unfinished interior escape and is `INVALID_ESCAPE`. To end a value with a literal quote, write `x : "abc\""` or `x : "abc""`. A quoted value with no closing delimiter on its physical line is `UNTERMINATED_STRING`; escape validation applies only to the interior span. Anything after the closing delimiter other than horizontal whitespace is `TRAILING_TOKENS`, because a comment occupies a whole line (§8.1): `x : "a" # comment` is rejected rather than read as `a`.

**Quoted keys are different.** A key terminates at `:` rather than EOL, so its closing delimiter is the first unescaped `"`; interior literal quotes in a key MUST be written `\"`.

Supported escapes are exactly `\"`, `\\`, `\n`, `\r`, `\t`, `\b`, `\f`, and `\uXXXX` (four ASCII hexadecimal digits). A `\uXXXX` escape denotes the code point with that value. An escape for a high surrogate MUST be immediately followed by an escape for a low surrogate; the pair denotes the corresponding supplementary code point. An isolated surrogate escape MUST be rejected with `INVALID_ESCAPE`, and so MUST `\u0000`. Invalid escape sequences and unclosed quotes MUST fail. Literal newlines, literal carriage returns, other unescaped control characters, and literal `U+FEFF` inside quoted strings are invalid; use `﻿` for the last.

Examples:

```riffle
scopedPackage : "@scope/name"
literalBrace : "{"
literalComment : "# not a comment"
"display name" : "Jane Doe"
```

### 5.3 Multiline raw blocks

A raw block is a multiline string value. It has the same shape as collections and block comments: the opener ends a line and the closer is on its own line.

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

1. **Opener.** A map value or array element whose entire value fragment, after trimming horizontal whitespace, is exactly `"""`. Raw blocks are allowed only for values and array elements, never for keys.
2. **Closer.** The first subsequent physical line whose content, after trimming leading and trailing horizontal whitespace, is exactly `"""`. The content cannot contain such a line, and there is no escape for it.
3. **Content is raw.** No escapes are processed. Backslashes, quotes, `#`, `//`, `/#`, `#/`, `{`, `[`, `@`, and every other character are literal data. Lines inside a raw block are never treated as comments, block-comment delimiters, entries, or closers of a collection.
4. **Indentation stripping.** Let `P` be the exact sequence of leading horizontal whitespace characters on the closer line. Each content line is processed as follows:
   - A line that is empty, or that holds only horizontal whitespace and is shorter than `P`, becomes the empty line.
   - Any other line MUST begin with exactly `P`, character for character, so tabs and spaces are not interchangeable. `P` is removed. A line that does not begin with `P` MUST be rejected with `INVALID_INDENTATION`, reported at that line.
5. **Trailing whitespace** on content lines is preserved.
6. **Line joining.** Content lines are joined with LF, whatever the source line endings. There is no newline after the last content line. An opener immediately followed by a closer is the empty string. To end the value with a newline, leave an empty last content line.
7. **Characters.** Horizontal tab (`U+0009`) is allowed literally in content. Other control characters (`U+0000`–`U+001F` except tab, and `U+007F`) and a literal `U+FEFF` are `INVALID_CHARACTER`. A lone CR is `INVALID_LINE_ENDING`.
8. **Unterminated.** End of document inside a raw block is `UNTERMINATED_STRING`, reported at the opener.
9. **Nesting.** A raw block does not count toward a host-configured nesting limit (§12).

Because the closer decides how content lines are read, a parser locates the closer first. An unterminated block is therefore reported as `UNTERMINATED_STRING` even when its content has other faults. Content faults are then reported in line order, and within one line `INVALID_CHARACTER` takes precedence over `INVALID_INDENTATION`.

## 6. Map keys and assignments

### 6.1 Map form

A root document consists of zero or more map entries. A nested map is opened with `{` and closed with `}`. Every entry has the form:

```riffle
key : value
```

Exactly one `:` separates the key from the value; `=` has no separator meaning. Additional `:` characters in an unquoted value are ordinary string characters. Spaces and tabs around the delimiter are optional: `key:value`, `key : value`, and `key: value` are equivalent.

### 6.2 Keys

- **Bare key:** matches `[A-Za-z0-9_][A-Za-z0-9_.-]*`. Examples: `provider`, `chatgpt-oauth`, `1`, `schema.version`.
- **Quoted key:** a double-quoted string using the escape rules in §5.2. Use quotes for spaces, `:`, `#`, `/`, `@`, Unicode, or other characters outside the bare-key alphabet.
- A quoted key MAY be empty: `"" : value` has the empty string as its key.
- Keys are always strings. Thus `1 : value` has string key `"1"`.
- A dot in a key is a literal dot, not a path separator. `a.b : x` and `a : { b : x }` are structurally different.
- A literal `U+FEFF` in a key is invalid; write `"﻿"`.
- Duplicate keys within the same map MUST be rejected. The same key may occur in different nested maps.
- Key insertion order MUST be preserved in the semantic map model.

## 7. Collections

### 7.1 Maps

A map value is `{}` or a block opened by `{` and closed by `}`. Nested map entries follow exactly the same rules as root entries.

```riffle
empty : {}
server : {
    host : localhost
    port : 8080
}
```

`{ a : 1 }` is invalid: nonempty collections are multiline. A standalone map can appear as an array element with `{` on its own line.

### 7.2 Arrays

An array is `[]` or a block opened by `[` and closed by `]`. It holds one value per line. Elements MAY be strings, raw blocks, or nested collections. A map entry's `:` is not used for array elements.

```riffle
values : [
    hello
    123
    true
    {
        name : example
    }
    [
        one
        two
    ]
]
```

### 7.3 Collection boundaries

Every opener MUST have a matching closer of the same kind. The parser MUST reject mismatched, missing, unexpected, and extra closing delimiters. Nesting is bounded only by available memory unless a host supplies the optional limit in §12.

## 8. Comments

Two comment forms are supported, and both occupy whole lines: **line comments**, marked with `#`, and **block comments**, which delimit a region of whole lines with `/#` and `#/`. `#` is the only line-comment marker, `//` is ordinary text, and a comment never appears after a value.

### 8.1 Line comments

- A line comment occupies a whole line: a line whose first non-whitespace character is `#` is a comment, and every character on it is ignored.
- A `#` that is not the first non-whitespace character of a line is ordinary text. `name : prod # primary` is the string `prod # primary`, `url : https://example.org/a#fragment` keeps its fragment, and `path//segment` and `a // b` are bare strings. Values need no escaping because of this rule, so a comment cannot share a line with a value.
- Because `#` starts a comment at the beginning of a line, an array element that begins with `#` must be quoted.
- Text after a complete non-bare value is `TRAILING_TOKENS`: `k : "v" # note` and `k : {} # note` are rejected rather than read as a value with a comment.
- A line comment ends at the line ending: it does not open or close a block comment, and a `#` on a comment line is not a marker.

### 8.2 Block comments

A block comment is a region of whole lines. Its delimiters are lines, not tokens inside a line, so a block comment never forms part of a key, value, opener, or closer.

- A block comment **opens** on a line whose first non-whitespace characters are `/#`. Everything after those characters on that line is comment text.
- It **closes one level** on a line whose first non-whitespace characters are `#/` and whose remaining characters are only spaces or tabs. A line whose `#/` is followed by any other character is comment text; it does not close the comment.
- Block comments **nest**. Every opening line inside an open block comment opens another level and every closing line closes one; the region ends when the nesting level returns to zero. Each level requires its own closing line.
- Inside a block comment every line is ignored, including lines that look like entries, collection delimiters, line comments, raw-block delimiters, or invalid Riffle. Keys, values, elements, delimiters, quotes, and comment markers are not recognized there, and a document is never rejected for text inside a region.
- Inside a raw block, `/#` and `#/` lines are content and do not open or close a comment (§5.3).
- Document text outside a block comment keeps its usual meaning. A `/#` or `#/` that is not on a line of its own is ordinary text: in `k : value /# note #/` the whole fragment after the colon is the bare string `value /# note #/`. Inline block comments do not exist, and a block comment cannot follow a value.
- A block comment that is still open at the end of the document MUST be rejected with `UNCLOSED_COMMENT`, reported at the location of the outermost open `/#`.
- A leading `#/` line with no open block comment is an ordinary line comment, because `#` starts a line comment as the first character of a line.
- Because a bare string that begins with `/#` cannot be written at the start of a line, the canonical serializer quotes it (§11.2).

```riffle
# configuration
/#
disabled until the port allocation lands
handler : {
#
nested regions need their own closer
/#
inner : 1
#/
#/
url : https://example.com/a#frag
pattern : path//segment
slashes : a // b
name : hello # this is the value, not a comment
port : 8080
servers : [
    /#
    second
    #/ text after the closer keeps the region open
    #/
    third
]
```

Important: `name : hello #world` has the string value `hello #world`, because a `#` after the start of a value is data (§8.1).

## 9. Formal line-oriented grammar

This grammar describes the syntactic structure of the physical lines that remain after the scanner of §9.1 has consumed comments. It is intentionally line-oriented rather than pretending the language is whitespace-delimited. `EOL` is LF, CRLF, or end-of-file, as appropriate. `S` is zero or more spaces/tabs. Additional lexical and semantic constraints in §§3–8 are normative.

```ebnf
Document         = { BlankOrComment | MapEntryLine } ;
MapEntryLine      = S, Key, S, ":", S, ValueStart, S, EOL, [ Body ] ;
Key               = BareKey | QuotedString ;
BareKey           = ( ASCII_LETTER | DIGIT | "_" ),
                    { ASCII_LETTER | DIGIT | "_" | "." | "-" } ;

ValueStart        = Scalar | EmptyMap | EmptyArray | MapOpen | ArrayOpen
                  | RawBlockOpen ;
Scalar            = BareString | QuotedString ;

EmptyMap          = "{}" ;
EmptyArray        = "[]" ;
MapOpen           = "{" ;
ArrayOpen         = "[" ;
RawBlockOpen      = '"""' ;

Body              = MapBody | ArrayBody | RawBlockBody ;
MapBody           = { BlankOrComment | MapEntryLine }, MapCloseLine ;
ArrayBody         = { BlankOrComment | ArrayElementLine }, ArrayCloseLine ;
RawBlockBody      = { RawContentLine }, RawBlockCloseLine ;
ArrayElementLine  = S, ValueStart, S, EOL, [ Body ] ;
MapCloseLine      = S, "}", S, EOL ;
ArrayCloseLine    = S, "]", S, EOL ;
RawBlockCloseLine = S, '"""', S, EOL ;

S                 = { " " | TAB } ;
```

`QuotedString` has two distinct delimiter rules: quoted values use the EOL/last-quote and comment-precedence rule of §5.2, while quoted keys retain the first-unescaped-quote rule of §5.2. `BlankOrComment`, `BareString`, `RawContentLine`, `Body` association, and the scanner behavior for comments are defined by §§3–8 and are not independent token-level productions. A `RawContentLine` is any physical line that is not a `RawBlockCloseLine`. A `Body` is present when the opener is the block form (`{`, `[`, or `"""`); the inline empty forms (`{}`, `[]`) have no `Body`. It is specifically the body matching that opener, not an optional arbitrary suffix. A bare scalar is recognized only when the entire non-comment value fragment is consumed, subject to the reserved-prefix rules. This specification favors unambiguous implementation behavior over a compact standalone EBNF.

### 9.1 Parser algorithm (normative behavior)

A conforming parser can be implemented as a single line scanner plus a stack. The scanner has three modes:

- **Normal:** the usual rules of this specification.
- **Block comment:** tracks nesting depth (§8.2). Every other line, including a `"""` line, means nothing.
- **Raw block:** every line is content until the closer (§5.3). `/#` and `#/` lines mean nothing.

A raw-block opener inside a block comment is commented-out text, and a `/#` line inside a raw block is content. Block comments therefore cannot be removed in a separate pass before parsing.

1. Decode UTF-8, normalize CRLF to LF for line scanning, and track original line/column positions.
2. Start in normal mode with a root map context.
3. For each line in normal mode, skip blank and line-comment lines; a comment line is one whose first non-whitespace character is `#` (§8.1). On a line whose first non-whitespace characters are `/#`, enter block-comment mode until the nesting depth returns to zero; a document that ends first fails with `UNCLOSED_COMMENT`.
4. In a map context, parse one key, `:`, then a single value start; in an array context, parse one element value start.
5. If the value is a raw-block opener, enter raw-block mode, read the block, and complete the entry or element. A document that ends first fails with `UNTERMINATED_STRING`.
6. If the value starts a nonempty block, insert its collection node at that position and push its map/array context. Do not complete the parent entry a second time when closing the block.
7. On a standalone matching closer, pop the current context. Reject mismatched and root-level closers.
8. At EOF, require the stack to contain only the root map context.
9. Validate duplicate keys, reserved first characters, and that no noncomment source characters remain unconsumed on any line.

## 10. Error contract

A parser MUST fail the whole document on invalid syntax. It MUST NOT silently ignore malformed lines, normalize invalid values into strings, or accept only a prefix of a line.

Every parse error MUST report a stable category from the table below and a 1-based line and column. When a key or element is involved, the diagnostic SHOULD include its path. The exact message wording, source-name format, and column counting unit are implementation-specific; column values MUST be deterministic for a given implementation and input. Diagnostic messages MUST NOT contain scalar values, because configuration files can hold secrets. They MAY name keys, paths, categories, expected forms, and locations.

The following categories and their triggering conditions are normative:

| Category | Trigger |
| --- | --- |
| `INVALID_UTF8` | Malformed UTF-8, an encoded surrogate code point, or an unpaired surrogate in decoded text |
| `INVALID_LINE_ENDING` | A carriage return not followed by a line feed |
| `INVALID_CHARACTER` | A control character, literal `U+FEFF` outside the document start, or another disallowed literal character |
| `INVALID_ESCAPE` | An unsupported quoted-string escape, malformed `\uXXXX`, isolated surrogate escape, or `\u0000` |
| `UNTERMINATED_STRING` | A quoted string has no closing quote on its line, or a raw block has no closer |
| `INVALID_INDENTATION` | A raw-block content line does not begin with the closer's indentation |
| `INVALID_KEY` | A map key is neither a bare key nor a valid quoted key |
| `MISSING_COLON` | A map entry has no `:`, or a key is not followed by one |
| `TRAILING_TOKENS` | Characters remain after a complete value or closer, other than horizontal whitespace |
| `INVALID_VALUE` | A bare value starts with a reserved character |
| `DUPLICATE_KEY` | The same key appears twice in one map |
| `UNEXPECTED_CLOSER` | A closing delimiter has no open collection |
| `MISMATCHED_CLOSER` | A closing delimiter does not match the open collection |
| `UNCLOSED_COLLECTION` | The document ended inside a collection |
| `UNCLOSED_COMMENT` | The document ended inside a block comment; reported at the outermost unclosed `/#` |
| `UNSUPPORTED_INLINE_COLLECTION` | A value starting with `{` or `[` is neither an opener nor an empty collection, or an empty collection had interior whitespace |
| `RESOURCE_LIMIT_EXCEEDED` | A host-configured nesting limit in §12 was exceeded |

The error location for a mismatch SHOULD point to the offending element or token; an unclosed collection SHOULD report the location of its opener.

### 10.1 Path syntax

When a diagnostic names the value it is about, it uses a **path**: a sequence of key segments and array-index segments that addresses one value inside the document. The syntax is normative, because conformance manifests and schema problems address values with it too.

- A key segment is written **bare** when it matches `[A-Za-z0-9_][A-Za-z0-9_-]*`, and **quoted** otherwise, using exactly the quoting and escaping the canonical serializer uses for a key (§11.2). A dot is a path separator, so a key that holds a dot is always quoted.
- Key segments after the first are preceded by `.`.
- An array-index segment is `[n]` in decimal and follows the segment that addressed the array.
- The root is the empty path, and is displayed as `(root)`.

A path is therefore unambiguous. `"a.b"` is the single key `a.b`, `a.b` is the key `b` inside the key `a`, and `servers[0]."display name"` is the `display name` entry of the first element of `servers`. Keys that hold a `[`, a `"`, a space, or a character outside the bare alphabet are quoted for the same reason.

Errors raised while reading a parsed value use the access categories of §13.3.

## 11. Canonical serialization

A canonical serializer MUST produce deterministic output satisfying this section. Implementations MAY offer other formatters that preserve comments or whitespace; those formatters are not canonical serializers.

### 11.1 Output bytes

Canonical output bytes MUST be:

- UTF-8 without a BOM.
- LF (`U+000A`) line endings, never CRLF or a lone CR.
- Indented with exactly four spaces (`U+0020`) per collection nesting level.
- A final LF after the last nonempty line; an empty document serializes to zero bytes.
- Free of comments, and of blank lines other than those inside a raw block; neither is part of the value.

### 11.2 Value rendering

- A map root emits one `key : value` line per entry in insertion order, with exactly one space before and one space after the colon.
- An array emits one element per line in element order.
- `{}` and `[]` are emitted for empty collections; nonempty collections use multiline block syntax.
- Bare keys are emitted only when they can be parsed back to the same string. Otherwise the serializer emits a quoted key using exactly the escapes in §5.2, escaping interior quotes as `\"`.
- A string value is emitted in the first of these forms that applies:
  1. **Bare**, when it parses back to the same string. A string that begins with `#` or `/#` is not bare, because an array element is written at the start of its line, where those characters would form a comment line or a block comment (§8).
  2. **Raw block**, when the string contains at least one LF, contains no CR, no control character other than tab and LF, and no `U+FEFF`, and has no line that trims to exactly `"""`. The content lines and the closer are indented one level (four spaces) deeper than the entry or element. Empty lines are written with no indentation. Every other line, including a whitespace-only line, is prefixed with that indentation.
  3. **Single-line quoted**, using exactly the escapes in §5.2. Interior `"` is emitted literally, because the closing delimiter is the last quote on the line. The one-character string `"` is emitted as `"\""`, never as `"""`.
- Every literal `U+FEFF` in a key or string is emitted as `﻿`.
- Canonical output MUST reparse to an equal semantic value tree. Serialization MUST NOT change kind, order, or text.
- The root is a map; a serializer MUST reject an array root rather than emit a document that cannot be reparsed.
- A serializer MUST accept any value tree that fits available memory. When a caller supplies a nesting limit, the serializer MUST reject a tree deeper than that limit.

## 12. Resource limits

Riffle 0.1.0 has no portable nesting limit. A parser and a canonical serializer MUST accept collections nested as deeply as available memory permits, and MUST traverse them without recursion that can exhaust the host stack.

A host MAY supply an optional maximum nesting depth. The limit counts nonempty collections between the root map and a value; the root map itself, an inline empty collection, and a raw block do not count. A document that exceeds a configured limit MUST be rejected with `RESOURCE_LIMIT_EXCEEDED`, and a serializer given the same limit MUST reject a deeper value tree. The way a host supplies and enforces that limit is outside the portable syntax contract.

Implementations MAY impose additional documented limits on total input size, line length, string length, or output size. Such limits MUST NOT reject any fixture in the conformance suite or otherwise change the meaning of a conforming document. There is no mandatory document-size limit.

## 13. Value interpretation

A document holds strings. A program reads a string as a number or boolean through an accessor. Every implementation that offers an interpretation below MUST accept exactly the listed forms and nothing else. Implementations MUST NOT delegate the decision to host-language number or boolean parsers, which commonly accept forms such as `1_000`, `+5`, `yes`, or `True`.

### 13.1 Accepted forms

| Interpretation | Accepted string | Range and semantics |
| --- | --- | --- |
| integer (64-bit) | `-?(0\|[1-9][0-9]*)` | Signed 64-bit: `[-9223372036854775808, 9223372036854775807]` |
| integer (32-bit) | `-?(0\|[1-9][0-9]*)` | Signed 32-bit: `[-2147483648, 2147483647]`; a narrowing of the 64-bit interpretation |
| decimal | `-?(0\|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?` | Exact base-10 (§13.2) |
| boolean | `true` or `false` | Lowercase only |

- The match is against the **whole** decoded string. There is no trimming, no leading `+`, no underscores, no hexadecimal, no leading zeros other than `0`, no `NaN` or `Infinity`, and no `.5` or `1.`.
- `-0` is accepted and denotes zero.
- A decimal accessor accepts integer-looking strings: `12` is a valid decimal. An integer accessor rejects decimal-looking strings: `12.0` is not an integer.
- These rules apply to every string, whether it was written bare, quoted, or as a raw block. `port : 8080`, `port : "8080"`, and a one-line raw block containing `8080` all read as the integer 8080.

### 13.2 Decimals

A decimal denotes a finite mathematical value

```
(-1)^sign * coefficient * 10^exponent
```

where `sign` is `0` or `1`, `coefficient` is a nonnegative base-10 integer, and `exponent` is a base-10 integer. A decimal is **not** a binary floating-point number; implementations MUST NOT convert through a binary floating-point type at any point.

Lexical scale is not part of the interpreted value: `1`, `1.0`, and `1.00` read as the same decimal. Decimal equality is mathematical equality, and zero has no sign.

The value is normalized as follows, using arbitrary-precision integer arithmetic for exponents:

1. Let `D` be the concatenation of the integer and fractional digits, `F` the number of fractional digits, and `X` the value of the optional exponent (zero when absent).
2. If every digit of `D` is zero, the value is zero: coefficient `0`, exponent `0`, positive sign, regardless of the written sign or exponent. Zero is always in range.
3. Otherwise remove leading and trailing zeros from `D` to form the coefficient. Let `t` be the number of trailing zeros removed. The normalized exponent is `E = X - F + t`.

The normalized exponent MUST lie in `[-2147483647, 2147483648]`. A string whose normalized exponent is outside that interval is `OUT_OF_RANGE`.

### 13.3 Access errors

Access errors use these stable categories. The categories are portable; how a host language reports them is an implementation detail.

| Category | Meaning |
| --- | --- |
| `MISSING` | A required key or index is absent |
| `WRONG_KIND` | A string was requested but the value is a map or array, or the reverse |
| `INVALID_FORMAT` | The string does not match the requested interpretation's grammar |
| `OUT_OF_RANGE` | The string matches the grammar but is outside the requested range |
| `INVALID_VALUE` | An application-defined conversion failed |

Access errors report the path of the value in the syntax of §10.1 and, when the value was parsed, its location; a value built in code rather than parsed has neither, and an implementation omits what it does not have. A missing member is reported at the path it would have had.

Access diagnostics MUST NOT contain scalar values.

## 14. JSON interoperability boundaries

Riffle's value model maps to JSON objects, arrays, and strings. JSON numbers and booleans have no distinct Riffle kind: an importer writes them as text that the matching interpretation (§13) accepts, and an exporter decides from application knowledge whether a string becomes a JSON number or boolean. JSON `null` has no Riffle representation; an importer MUST either reject it or omit the member, and MUST document which. Riffle implementations MUST document their mapping strategy if they provide JSON import or export. JSON object order can be retained when an ordered JSON representation is used, but JSON semantics do not require meaningful object-key order.

## 15. Conformance examples

### 15.1 Valid cases and expected meaning

```riffle
# blank string and literal strings
empty :
stringTrue : true
stringNull : null
stringTilde : ~
port : 8080
quotedPort : "8080"
literalAt : "@scope/name"

# quoted key and punctuation
"key with spaces" : a=b
url : https://example.org/a#fragment
note : prod # primary

# empty collections
emptyMap : {}
emptyArray : []

# nested values and mixed array
complex : {
    items : [
        text
        9000000000
        1.25
        {
            enabled : false
        }
        [
            nested
        ]
    ]
}

# multiline raw block
query : """
    SELECT *
      FROM users
    """

# block comment region: whole lines are ignored until the matching `#/`
/#
disabled : 1
#/
```

Expected notable distinctions: `stringTrue` is the string `true`, which a boolean accessor reads as true; `stringNull` is the string `null`; `empty` is `""`; `port` and `quotedPort` are equal strings; `note` is `prod # primary`; `query` is `SELECT *`, LF, `  FROM users`; `disabled` is absent, because a block-comment region removes whole lines (§8.2).

### 15.2 Invalid cases (each snippet is a separate document)

| Input | Required failure |
| --- | --- |
| `x : 1 y : 2` | One-entry-per-line rule; this is not two entries (it is a single string value). To test two entries on one line use `x : {} y : 2`, which fails `TRAILING_TOKENS`. |
| `x : @int 1` | `INVALID_VALUE`: `@` is a reserved first character; write `x : 1` |
| `x : @scope/name` | `INVALID_VALUE`: quote the value |
| `x : { a : b }` | Nonempty inline map forbidden |
| `x : [ one ]` | Nonempty inline array forbidden |
| `x : [a-z]+` | `UNSUPPORTED_INLINE_COLLECTION`: quote the value |
| `x : { }` | Empty collection with interior whitespace; write `{}` or a block |
| `x : [\n  1\n}` | Mismatched closing delimiter |
| `x : a\nx : b` | Duplicate key |
| `@int 1 : a` | Invalid map key |
| `x : "abc` | Unterminated string |
| `x : "bad\q"` | Invalid escape |
| `x : "\u0000"` | `INVALID_ESCAPE`: `U+0000` is banned from strings |
| `x : {\n y : z` | Unclosed map at EOF |
| `/#\n x : y` | Unclosed block comment at EOF; text inside the region is never parsed |
| `x : """\n  text` | `UNTERMINATED_STRING`, reported at the opener |
| `x : """\ntext\n  """` | `INVALID_INDENTATION`: the content line lacks the closer's indentation |
| `x : "a" # comment` | Text after a complete value; a comment cannot follow a value (§8.1) |
| `x : {} # comment` | Text after a closer; a comment cannot follow a value (§8.1) |

### 15.3 Required test categories

Implementations SHOULD include table-driven tests covering UTF-8 and Unicode escapes; LF/CRLF/EOF; line and block comments and URL fragments; bare versus quoted strings; raw blocks and their interaction with block comments; every accepted form, boundary, and rejection of §13; empty and nested collections; order preservation; duplicate keys; delimiter mismatch; extra same-line tokens; accurate line/column errors; serializer/parser semantic round trips; and resource-limit failures.

## 16. Non-goals for 0.1.0

The following are deliberately excluded:

- Map keys of kinds other than string.
- Implicit scalar inference, type markers, and a null kind.
- Multi-entry lines, comma-delimited arrays, semicolon terminators, and nonempty inline collections.
- Multiline strings other than raw blocks, heredocs with custom delimiters, and line continuations.
- Inline block comments: a block-comment region occupies whole lines and never appears inside a logical line (§8.2).
- Alternate line-comment markers such as `//`, which is ordinary text (§8.1).
- Inline or trailing comments of any kind: a comment occupies a whole line, so `k : v # note` gives the string `v # note` and `k : "v" # note` is rejected (§8.1).
- Dot-path assignment semantics, duplicate-key merging, substitution, includes, environment expansion, expressions, and conditionals.
- Interpretations beyond §13, such as dates, durations, or application-specific scalars. Applications define their own over strings.

These may be considered in future versions only through explicit changes to this specification.

## 17. Implementation acceptance criteria

A 0.1.0 implementation is conformant when it:

1. Correctly parses every valid example and rejects every invalid example above.
2. Uses one EOL-terminated map entry or array element per line, with block collection closers on separate lines.
3. Preserves exact string text, map insertion order, and array order.
4. Accepts exactly the forms of §13 for every interpretation it offers, and reports the categories of §13.3.
5. Produces diagnostics with stable categories and deterministic line/column values, and never includes scalar values in them.
6. Implements raw blocks exactly as specified in §5.3 and the three scanner modes of §9.1.
7. Can serialize any supported semantic value tree and parse its output into an equal tree.
8. Enforces the portable resource limit in §12.
9. Does not introduce undocumented extensions that change the meaning of conforming 0.1.0 input.

## Appendix A: Reference Java mapping (non-normative)

This appendix describes the Java reference implementation only. It is informative and does not define Riffle.

| Riffle kind or interpretation | Java 17 type |
| --- | --- |
| `string` | `String` |
| `map` | immutable insertion-ordered `Map<String, RiffleValue>` |
| `array` | immutable ordered `List<RiffleValue>` |
| integer (32-bit) | `int`, through `asInt()` |
| integer (64-bit) | `long`, through `asLong()` |
| decimal | `BigDecimal`, through `asDecimal()`, normalized with `stripTrailingZeros()` |
| boolean | `boolean`, through `asBoolean()` |

Java columns count UTF-16 code units; other host languages may count Unicode code points or bytes. Parsing a `Path` uses a strict UTF-8 decoder and reports malformed input as `INVALID_UTF8`. Parsing a Java `String` rejects unpaired surrogates with the same category. Access errors are `RiffleAccessException`, whose `category()` returns a `RiffleAccessError` with the categories of §13.3. A path of §10.1 is a `RifflePath`: `RiffleValue.path()` and `RiffleAccessException.path()` return an `Optional<RifflePath>`, and `RifflePath.toString()` renders the syntax of §10.1. The schema types are documented in `SCHEMA_SPEC.md`, not here.

The optional `RiffleJavaValues` adapter maps strings, maps, and arrays to `String`, `Map<String, Object>`, and `List<Object>`. In the other direction it also accepts `Integer`, `Long`, `BigDecimal`, and `Boolean`, which become text that the matching accessor accepts. A `BigDecimal` is rendered in plain notation up to 256 characters and in scientific notation beyond that. The adapter rejects `null`.

## Appendix B: Informative host-language mappings (non-normative)

These mappings are examples for future implementations, not requirements of the language.

| Riffle kind or interpretation | Java | Go | Rust | Python | JavaScript / TypeScript | C++ |
| --- | --- | --- | --- | --- | --- | --- |
| `string` | `String` | `string` | `String` | `str` | `string` | `std::string` |
| `map` | ordered map | ordered slice of pairs | `IndexMap` or vec of pairs | `dict` preserving insertion order | `Map` | vector of pairs |
| `array` | ordered list | `[]T` | `Vec<T>` | `list` | `Array` | `std::vector<T>` |
| integer (32-bit) | `int` | `int32` | `i32` | `int` with range check | `number` | `std::int32_t` |
| integer (64-bit) | `long` | `int64` | `i64` | `int` with range check | `BigInt` | `std::int64_t` |
| decimal | `BigDecimal` | digit string plus exponent, or `math/big` | `bigdecimal` crate | `decimal.Decimal` | digit string plus exponent, or a decimal library | digit string plus exponent, or a decimal library |
| boolean | `boolean` | `bool` | `bool` | `bool` | `boolean` | `bool` |

Host-language notes:

- **Decimals:** a parser never does decimal arithmetic. A digit string plus an exponent is a sufficient internal representation, so a bignum library is optional.
- **Rust:** use an arbitrary-precision type such as `bigdecimal`. Fixed-precision crates such as `rust_decimal` (about 28 digits) cannot represent every valid decimal.
- **JavaScript and TypeScript:** 64-bit integers require `BigInt`, because `Number` loses precision above 2^53. A plain object reorders integer-like keys, so use `Map` for Riffle maps.
- **Go and C++:** built-in maps do not preserve insertion order. `std::map` sorts its keys, and `std::unordered_map` and Go's `map` have no defined order, so use an ordered slice or vector of pairs.
- **C:** `U+0000` is banned from strings and keys, so NUL-terminated UTF-8 strings are safe.
- **Python and JavaScript:** their strings can contain lone surrogates, so writers MUST reject them.

> **Guidance for an AI implementer:** Implement a three-mode line scanner, the string, map, and array value model, a parser, a serializer, the interpretations of §13, and focused tests from this specification. Do not delegate §13 to host-language parsers, and do not improvise extra syntax. When a corner case is not specified, prefer a clear parse error and a regression test over permissive guessing.
