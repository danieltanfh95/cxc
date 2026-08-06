# Corvid Validation: Friction Log — First Parity Pass

**Exercise:** Translate JohnSundell/Ink (Swift Markdown parser) to Corvid v0.5.
**Pass:** Parity — line-by-line, staying close to source structure.
**Files translated:** Markdown.swift (public API), Blockquote.swift (Fragment example), Reader.swift (interface inferred).
**Total original Swift:** ~130 lines across 3 files.
**Total translated Corvid:** ~450 lines (parity + inline friction annotations).

The primary output of this exercise is not the code — it's this log. Every point where translation friction surfaced tells us something about whether the design serves real code.

## Summary of Findings

Translation surfaced **30 distinct friction points** across three small files. Categorized:

- **Structural gaps** (design conflicts): 4 items
- **Spec gaps** (unspecified but needed): 12 items
- **Idiomatic differences** (design choices, not gaps): 8 items
- **Wins** (Corvid does it better than Swift): 6 items

The high count on spec gaps is genuinely useful — these are things §10 (grammar sketch) currently glosses over that real code needs.

## Category 1: Structural Gaps (Design Conflicts)

These are places where our design commitments conflict with Swift idioms. Not spec gaps — real design decisions that make certain Swift patterns unrepresentable.

### 1.1 Value-type-with-reference-storage pattern (FRICTION #1, #7, #15, #20)

Swift's `Markdown` struct wraps a `TitleStorage` class internally to enable a `mutating set` on `title` without full struct copying. This pattern appears throughout Swift value-typed code where mutation is needed.

Our struct discipline says: struct forbids `state:`. No mutation. Users construct new values.

**Impact:** Most Fragment types in Ink use mutating methods on struct fields. Each is unrepresentable in an Corvid struct as-designed. Two paths:

1. **Convert to classes.** Changes value semantics. May be idiomatically right anyway (mutable cursors are reference-shaped).
2. **Accept immutability.** Change all mutation to construction-of-new. Some ergonomic loss but honest.

**Recommendation:** The idiomatic pass should make these classes. Struct is for pure value semantics; mutating parsers aren't that. This isn't a design bug — the struct-forbids-state rule is what we want. It just means Swift structs and Corvid structs aren't the same thing.

### 1.2 Extension-based organization (FRICTION #6)

Swift uses `private extension Markdown { ... }` for organizing private helpers. Corvid has no direct equivalent — sections categorize by role (data/state/methods), not visibility groups.

**Impact:** Swift codebases that lean heavily on extensions (many do) will not translate structurally. Semantics preserve; visual organization does not.

**Recommendation:** Not a design change. Document that Swift extensions don't have a 1:1 mapping; users organize via sections and visibility annotations.

### 1.3 KeyPath-based pattern matching (FRICTION #19)

Swift's `case \.isNewline:` in a switch matches on a projected property. Powerful, ergonomic, uses KeyPath machinery.

Our §3.7 mentions "structural patterns" but doesn't specify predicate-based matching.

**Impact:** For parity we translate to `case c where c.is_newline():`. Verbose but readable. Not a blocker.

**Recommendation:** Consider adding predicate matching to §3.7 or documenting that we deliberately don't have it. Currently ambiguous.

### 1.4 Reader as struct vs class (FRICTION #24)

Swift's Reader is likely a struct (based on `inout` usage). Value semantics + explicit mutation via `inout`. Our design forces Reader to be a class.

**Impact:** Aliasing semantics differ. Swift's `inout` is explicit about who mutates; Corvid's class reference is implicit sharing. Not a correctness issue but a mental-model difference for translators.

**Recommendation:** Document in migration guides. Class is arguably the right choice; Swift's value-type-with-inout is a workaround for lacking clean references.

## Category 2: Spec Gaps (Unspecified in v0.5)

These are places where the parity translation needed syntactic decisions we haven't made. Real gaps in v0.5.

### 2.1 Auto-generated constructors (FRICTION #4)

Struct/class constructors are generated from fields, but nowhere in §6 does the spec make this explicit. Users writing `Markdown(html: ..., ...)` need to know this exists and what it accepts.

**Spec fix:** §6.2 (struct runtime model) and §6.3 (class runtime model) should specify the auto-generated constructor: takes all `data:` fields (structs) or all `data:` + `state:` fields with defaults (classes).

### 2.2 String interpolation syntax (FRICTION #22)

Every parser needs it. Neither §2 (lexical) nor §10 (grammar) specifies interpolation syntax. Real gap.

**Spec fix:** Specify. Options: `"\(expr)"` (Swift), `"${expr}"` (JS/shell), `"{expr}"` (Python f-string). No obvious winner; pick one for consistency.

### 2.3 Explicit `try` keyword (FRICTION #17)

§3.5.1 specifies `throws` as a semantic effect. Doesn't specify whether calls to throwing functions need `try` prefix. Grammar §10 doesn't include it.

**Spec fix:** Decide. Swift's `try` is explicit; Rust's `?` is postfix; Go's `err != nil` is manual. Pick one. Legibility argues for explicit `try`.

### 2.4 `old()` in ensures clauses (FRICTION #28)

State-transition ensures need to reference pre-state. Without it, invariants like "index moved forward" can't be expressed.

**Spec fix:** §3.4 should specify `old(expr)` or equivalent syntax for pre-state references.

### 2.5 Throws in ensures clauses (FRICTION #29)

`ensures A or throws B` — conditional postconditions. Common pattern in state-transition code.

**Spec fix:** §3.4 should specify how throws interacts with ensures. Options: implicit ("ensures only holds on normal return"), explicit ("ensures A on success, throws E on failure").

### 2.6 String.Index / position API (FRICTION #25, #26)

Reader depends on string positions. §6 mentions String but doesn't specify the position API. Real gap for any string-processing code.

**Spec fix:** Standard library scope decision. `core.string` module needs: `start_index`, `end_index`, `index_after(i)`, `character_at(i)`, comparison of indices.

### 2.7 Protocol conformance syntax (FRICTION #13)

Swift's `struct X: Protocol` — colon-separated protocol list. Corvid's `class X: Protocol` conflicts with section header syntax (colon-terminated).

**Spec fix:** §3.4 needs to clarify. Options:
- `class X conforms: Protocol` — explicit
- `class X : Protocol` — Swift-like, requires disambiguation from sections
- `class X <: Protocol` — subtype operator

Currently ambiguous. Real syntactic decision needed.

### 2.8 Static/factory methods on struct types (FRICTION #16a)

`static func read(...)` in Swift. Corvid's grammar §10 shows `class function` on classes. Not clear how to declare a class-level (type-level) function on a struct.

**Spec fix:** §6.2 needs to specify. Probably `class function` works uniformly (name is misleading if applied to struct).

### 2.9 Leading-dot enum shorthand (FRICTION #14a)

`.blockquotes` in Swift relies on type context. Ergonomic but requires type inference.

**Spec fix:** Decide. Consistent with §3.7 pattern matching but not documented.

### 2.10 `if let` optional binding (FRICTION #9)

Standard optional-unwrap-and-scope pattern. §3 doesn't specify.

**Spec fix:** §3 should specify. Options: Swift's `if let x = opt { ... }`, Rust's `if let Some(x) = opt { ... }`.

### 2.11 Optional chaining `?.` (FRICTION #11)

`titleHeading?.plain_text()`. §3 mentions optionals but chaining syntax unspecified.

**Spec fix:** §3.5 or §3.7 should specify.

### 2.12 Argument labels (FRICTION #21)

Swift's `func html(usingURLs urls:)` — labels distinct from parameter names. Corvid grammar §10 shows only single names.

**Spec fix:** Decide. ObjC has selector components; Swift has labels. If we're an ObjC successor, this is worth considering. Currently omitted, which is a design choice, not a gap — but should be stated.

## Category 3: Idiomatic Differences (Design Choices)

Places where Corvid differs from Swift by design. Not bugs.

- **Explicit `return`** (FRICTION #23) — aligned with §1.6 (legibility). Swift's shorthand rejected correctly.
- **Explicit visibility on struct fields** (FRICTION #12) — Corvid defaults are internal; Swift defaults to internal for struct but requires public marking for exposure. Equivalent semantics, different defaults.
- **Section-based method grouping** (implicit throughout) — Corvid's sections replace Swift's extensions for role-based organization.
- **Contracts on Reader operations** (FRICTION #27) — Corvid wins here: preconditions are explicit and enforceable. Swift relies on runtime crash.
- **Named error types** (FRICTION #30) — Corvid requires `throws ErrorType`; Swift has untyped throws. Corvid's more precise, aligned with §1.6.
- **Struct forbids state** (FRICTION #1, #15) — strict discipline. Structs are pure values.
- **Class methods vs static methods** — Corvid's `class function` is the mechanism.
- **Reader as class not struct** (FRICTION #24) — semantic difference driven by our value/reference discipline.

## Category 4: Design Wins (Corvid Does It Better)

- **Contracts on Reader operations** (FRICTION #27) — Reader's preconditions are enforceable and self-documenting.
- **Invariants on state** (FRICTION #26) — Reader's position bounds are declared, not just implicit.
- **Named error types** (FRICTION #30) — throws clauses are precise.
- **Explicit return** (FRICTION #23) — legibility win.
- **State machine semantics on Reader** — advance_index/current_character/read all coordinate through state transitions (§1.9). Concurrent access to a Reader would be safe by construction. Swift needs manual synchronization.
- **Universal extents for Reader instances** — could query "all readers with position beyond X" for debugging. Free consequence of §6.

## Overall Assessment

Two files (~130 lines Swift) → **30 friction points**. Most are spec gaps, not design bugs. This is what we'd hope to find:

- The design commitments (struct discipline, contracts, sections) hold up.
- The gaps are in syntactic details (interpolation, `try`, `if let`) that any real language must specify.
- The wins are substantive (contracts, invariants, state-machine sync).

**Estimated ratio for full Ink translation:** ~15-20 files × ~150 lines each = ~2500 Swift lines. If friction density holds, ~400-500 friction points total. Most will be repeats of the same underlying gaps (string interpolation appears everywhere; mutating-methods-on-value-types appears in every Fragment). The unique friction count would be closer to 40-50.

## Priorities Emerging From First Pass

**Must-fix spec gaps before further translation:**
1. String interpolation syntax
2. `try` keyword
3. Protocol conformance syntax  
4. `old()` in ensures clauses
5. String position API in stdlib

**Design decisions worth revisiting:**
1. Argument labels — do we want them or not?
2. Predicate-based pattern matching — extend §3.7?
3. Extensions or extension-like mechanism for organization?
4. Whether structs should be usable-in-Swift-style (with state) — currently forbidden; Ink's usage suggests this is limiting

**Non-issues (design working as intended):**
- Legibility premise driving explicit returns and typed throws
- Sections replacing Swift extensions
- Contracts and invariants earning their keep
- State machine semantics providing safety wins

## Next Steps

Two possible paths:

**Path A: Fix spec gaps, translate more files.**
Prioritize: string interpolation, try, protocol conformance, old(), position API. Then translate FormattedText, MarkdownParser. Each new file will surface fresh friction; the spec matures.

**Path B: Start idiomatic pass on the three translated files.**
Use current parity as the raw material. Rewrite Markdown, Blockquote, Reader as Corvid-idiomatic: classes where appropriate, sections for organization, contracts for state, extents where they help. Compare parity vs idiomatic and see what actually improved.

**Recommendation:** Path B first. Understanding what our design enables (over a small controlled sample) is more valuable than covering more surface area. Path A can follow once we know what idiomatic looks like.
