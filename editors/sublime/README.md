# Riffle for Sublime Text

Syntax highlighting for the [Riffle 0.1.0 configuration language](../../LANGUAGE_SPEC.md).
Requires Sublime Text 3.1 or newer (Sublime Text 4 assumed by the paths below).

| File | Purpose |
| --- | --- |
| `Riffle.sublime-syntax` | the syntax definition, applied to files with the `.riffle` extension |
| `syntax_test_riffle.riffle` | assertions in Sublime's [syntax test format](https://www.sublimetext.com/docs/syntax_tests.html) |

## Install

Sublime discovers packages by directory, and the syntax test refers to the
syntax as `Packages/Riffle/Riffle.sublime-syntax`, so install this directory
under the name `Riffle`:

```shell
# Linux
ln -s "$PWD/editors/sublime" ~/.config/sublime-text/Packages/Riffle

# macOS
ln -s "$PWD/editors/sublime" "$HOME/Library/Application Support/Sublime Text/Packages/Riffle"

# Windows (PowerShell)
New-Item -ItemType Junction -Path "$env:APPDATA\Sublime Text\Packages\Riffle" -Target "$PWD\editors\sublime"
```

Sublime Text 3 keeps its packages in `sublime-text-3` (Linux) and
`Sublime Text 3` (macOS, Windows) instead.

Alternatively, copy the directory there. No build step or dependency is
involved; a change takes effect when the file is saved in Sublime, so the
definition can be edited in place.

## What is highlighted

The definition follows the specification rather than a generic
key/value grammar, so these cases behave as the language defines them:

- Comments occupy whole lines. `#` is a comment only as the first
  non-whitespace character of a line, so `url : https://example.org/a#fragment`,
  `pattern : path//segment` and `hashed : hello#world # data` are all bare
  strings. `//` is never a comment marker.
- A line holding `/#` opens a block comment region and a line holding only
  `#/` closes one nesting level; everything between, including entries and
  invalid Riffle, is `comment.block.riffle`.
- A quoted value is closed by the last quote on its line, so
  `"He said "hello""` contains quotes, and a value cannot be followed by a
  comment: `"a # b"` is the value `a # b`, while `"a" # trailing` is invalid.
- A quoted key ends at the first unescaped quote, because a key ends at `:`.
- Nonempty collections are only accepted in block form, an opener must end its
  line, and a closer must be alone on its line. The inline forms `{ }`,
  `[ one ]` and `@int [ 1 ]` are marked invalid.
- Elements of a typed array are highlighted by the declared element kind
  without a repeated marker, so `@int [` highlights bare integers and
  `@null [` highlights the literal `null`.
- Escapes are highlighted individually: `\" \\ \n \r \t \b \f` and `\uXXXX`,
  with surrogate pairs as two consecutive escapes. Unsupported escapes and
  isolated surrogate escapes are marked invalid.

## Scopes

The root scope is `source.riffle`. Theme authors can rely on:

| Text | Scope |
| --- | --- |
| bare key | `entity.name.tag.riffle` |
| quoted key, quoted value | `string.quoted.double.riffle` |
| quote delimiters | `punctuation.definition.string.begin` / `.end.riffle` |
| bare value | `string.unquoted.riffle` |
| string escape | `constant.character.escape.riffle` |
| `:` | `keyword.operator.assignment.riffle` |
| `@` of a type marker | `punctuation.definition.annotation.riffle` |
| `@int` `@long` `@decimal` `@boolean` marker | `storage.type.numeric.*` / `storage.type.boolean.riffle` |
| `@string` marker, and `@string` `@int` `@long` `@decimal` `@boolean` `@null` `@map` `@array` in a typed array opener | `storage.type.*.riffle` |
| `int`, `long` payload | `constant.numeric.integer.int` / `.long.riffle` |
| `decimal` payload | `constant.numeric.decimal.riffle` |
| `true`, `false` | `constant.language.boolean.riffle` |
| `@null`, and `null` inside `@null [` | `constant.language.null.riffle` |
| `{` `}` | `punctuation.section.mapping.begin` / `.end.riffle` |
| `[` `]` | `punctuation.section.array.begin` / `.end.riffle` |
| line comment | `comment.line.number-sign.riffle` |
| block comment region, including its delimiters | `comment.block.riffle` |
| invalid input | `invalid.illegal.*.riffle` |

## Limits

Highlighting is a lexical pass, not a parser. These are deliberately not
reported, because they are semantic or runtime errors:

- duplicate keys, integer and decimal range overflow, and the nesting limit;
- malformed UTF-8, a lone carriage return, and a BOM inside the document (the
  editor normalizes line endings and strips the BOM before highlighting);
- an unterminated quoted value: the quote is simply never closed, matching how
  other Sublime syntaxes report unterminated strings.

## Run the syntax tests

Open `syntax_test_riffle.riffle` in Sublime and use
*Build With: Syntax Tests* (or run the `syntax_test` build target). The build
system is part of Sublime; no extra package is required.
