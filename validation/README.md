# Corvid v0.5 Empirical Validation

Real Swift codebases translated to Corvid to test whether the design supports actual working code.

## Exercises

| Exercise | Location | Scope | Status |
|---|---|---|---|
| **Ink** (Markdown parser) | this directory (files 00–05) | 9 files, ~245 Swift lines, parity + idiomatic passes | Complete |
| **Starscream** (WebSocket frame parser + handshake) | [websocket/](websocket/) | 4 files, ~746 Swift lines, parity pass planned | Not started |

The Ink exercise validated the syntactic surface (structural types, protocols, sealed enums, two-type pattern). The Starscream exercise is scoped to cover ground Ink couldn't: real state machines, contracts on protocol invariants, error propagation across layers, and Foundation interop at depth.

---

## Ink Markdown Parser

**Exercise:** Translate a real Swift codebase (JohnSundell/Ink) to Corvid v0.5 to test whether the design supports actual working code.

**Source:** [JohnSundell/Ink](https://github.com/JohnSundell/Ink), a pure-Swift Markdown parser (~2500 LOC total). Chosen because:
- Pure Swift, no dependencies (no interop noise).
- Well-architected: `Reader` + `Fragment` protocols + rule-based dispatch.
- Modest scope: exercises pattern matching, enums with variants, struct vs class distinction, error handling.

## Files in this directory

| File | Purpose |
|---|---|
| `00-friction-log.md` | First-pass friction log (Parity translation). |
| `01-Markdown.source.swift` | Original Swift (public API type). ~58 lines. |
| `01-Markdown.parity.cor` | Parity translation with inline `FRICTION:` notes. |
| `02-Blockquote.source.swift` | Original Swift (Fragment example). ~35 lines. |
| `02-Blockquote.parity.cor` | Parity translation with inline `FRICTION:` notes. |
| `03-Reader.inferred.cor` | Reader interface reconstructed from usage. |
| `04-idiomatic-pass.md` | **Idiomatic pass (Path B).** Three files rewritten idiomatically. Introduces two-type pattern. |
| `05-fragment-coverage.md` | **Extended coverage.** MarkdownParser (real source) + Paragraph, HorizontalLine, Heading, List (approximated). Confirms two-type pattern across structural shapes. |

## Reading order for mobile

1. Skim `README.md` (this file) for the overall shape.
2. Read `04-idiomatic-pass.md` for the core design findings — introduces the two-type pattern.
3. Read `05-fragment-coverage.md` for the pattern applied across 6 more types, with 8 additional friction points cumulative.

Skip the `.cor` and `.swift` files unless you want the raw parity artifacts.

## What the exercise found

- **The two-type pattern.** When Swift uses value-type-with-mutating-methods, Corvid splits into a struct (the value) and a class (the builder/machinery). Applied cleanly to 5 Fragment types plus a variant (Class+Runner) on MarkdownParser.
- **Enumerations for closed sum types earn their keep.** HeadingLevel, ListStyle, ListItemContent, ParseError — each gets compile-time exhaustive matching where Swift uses runtime constraints.
- **Contracts and invariants document real bugs.** Reader position bounds, HorizontalLine marker count, Heading pound count, List non-emptiness — each catches something Swift only catches at runtime.
- **~38 friction points cumulative,** most syntactic gaps addressable by focused §2, §3, §3.7, and §10 revisions.

## Coverage

Nine files translated to idiomatic Corvid:

- Reader (mutable cursor — class)
- Markdown (public API — class with universal extent)
- Blockquote (Fragment — struct + BlockquoteReader class)
- MarkdownParser (orchestrator — class + ParseRun class + ParsedFragment struct)
- Fragment protocol (open, method-dispatched)
- Paragraph (fallback fragment — struct + ParagraphReader)
- HorizontalLine (marker fragment — struct + HorizontalLineReader)
- Heading (leveled fragment — struct + HeadingReader + HeadingLevel sealed enum)
- List (recursive fragment — struct + ListReader + sealed protocols for style and content)

This covers essentially every structural shape in Ink. Remaining fragments (Image, HTML, CodeBlock, Table, FormattedText) would follow the same patterns.

## What's next

Two options ranked:
1. **Fix top syntactic spec gaps** based on the 38 friction data points. Focus on §3.7 (pattern matching), §3 (optionals + control flow), §2 (string interpolation), §10 (grammar).
2. **AI harness** for concurrency axes markdown didn't reach.

Ranked A first, then B, to close syntactic story before testing concurrency with clean syntax.
