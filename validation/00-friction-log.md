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

**Status: partially resolved (v0.6+); doc-audit pending.**

Constructors are auto-generated from a class's data fields — the spec uses this in examples (`Vec2(x: 1.0, y: 2.0)` at §6.2 line 3744) without an explicit `constructor` declaration on records. The auto-generation shape (named parameters mirroring data fields; with-method for updates) is implicit rather than stated. Minor doc fix: §6.2 should explicitly state "records get an auto-generated positional-named constructor from their data fields."

### 2.2 String interpolation syntax (FRICTION #22)

**Status: resolved (v0.8.0, §4.20.1 prelude alias `${expr}`; simple identifier form `$name` shorthand for `${name}`).**

### 2.3 Explicit `try` keyword (FRICTION #17)

**Status: pending.**

§3.5.1 specifies `throws` as a semantic effect. Doesn't specify whether calls to throwing functions need `try` prefix. Grammar §10 doesn't include it.

**Spec fix:** Decide. Swift's `try` is explicit; Rust's `?` is postfix; Go's `err != nil` is manual. Pick one. Legibility argues for explicit `try`.

### 2.4 `old()` in ensures clauses (FRICTION #28)

**Status: pending.**

State-transition ensures need to reference pre-state. Without it, invariants like "index moved forward" can't be expressed.

**Spec fix:** §3.4 should specify `old(expr)` or equivalent syntax for pre-state references.

### 2.5 Throws in ensures clauses (FRICTION #29)

**Status: pending.**

`ensures A or throws B` — conditional postconditions. Common pattern in state-transition code.

**Spec fix:** §3.4 should specify how throws interacts with ensures. Options: implicit ("ensures only holds on normal return"), explicit ("ensures A on success, throws E on failure").

### 2.6 String.Index / position API (FRICTION #25, #26)

**Status: pending (stdlib scope).**

Reader depends on string positions. §6 mentions String but doesn't specify the position API. Real gap for any string-processing code.

**Spec fix:** Standard library scope decision. `core.string` module needs: `start_index`, `end_index`, `index_after(i)`, `character_at(i)`, comparison of indices.

### 2.7 Protocol conformance syntax (FRICTION #13)

**Status: resolved (v0.6+, §3.4 / worked example §11).**

The `class X: Protocol:` form is unambiguous — the first `:` starts the conformance clause; the second `:` opens the class body. Grammar §10 `class_decl := 'expose'? 'class' identifier generic_params? conformance_clause? ':' INDENT ...`. Structural conformance is default (§3.4 line 655), so the conformance clause is optional. Worked example (§11) uses `class DefaultOrderProcessor: OrderProcessor:` and `expose class Order:`.

### 2.8 Static/factory methods on struct types (FRICTION #16a)

**Status: resolved (v0.6+, §4.10.7).**

`class:` section is permitted on `kind: record`, `kind: enum`, `kind: protocol` (§4.10.7 lines 2200/2208/2217). Naming is misleading for records ("class function" on a value type) but the mechanism is uniform.

### 2.9 Leading-dot enum shorthand (FRICTION #14a)

**Status: resolved (v0.8.4 §3.7.2).**

`.name(...)` and `.name` resolve to `<InferredType>.name(...)` in target-typed contexts (switch scrutinee, function parameter, return, field/variable declared type, comparison target). Ambiguous contexts produce a compile error with a suggestion to qualify explicitly.

### 2.10 `if let` optional binding (FRICTION #9)

**Status: resolved (v0.7 single-binding §3.6.1; v0.8.1 multi-binding §3.6.1).**

### 2.11 Optional chaining `?.` (FRICTION #11)

**Status: retired (v0.7 §3.6.1, §5.7).** `?.` removed; `.` operates uniformly on nil receivers (auto-lift return, `on_nil` dispatch). No chaining operator needed under the nil-messaging-inversion model.

### 2.12 Argument labels (FRICTION #21)

**Status: resolved by design decision (v0.5).** Corvid uses parameter names as call-site labels (single-name model). Swift-style dual `externalName internalName` was rejected. See Category 3 note below.

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
1. ~~String interpolation syntax~~ — **resolved v0.8.0** (`${expr}` prelude alias)
2. `try` keyword — **still pending**
3. ~~Protocol conformance syntax~~ — **resolved v0.6+** (`class X: Protocol:` unambiguous)
4. `old()` in ensures clauses — **still pending**
5. String position API in stdlib — **still pending (stdlib scope)**

**Design decisions worth revisiting:**
1. ~~Argument labels~~ — **resolved v0.5** (single-name model committed)
2. Predicate-based pattern matching — extend §3.7? **still pending**
3. ~~Extensions or extension-like mechanism~~ — **resolved via sections + categories** (§4)
4. ~~Whether structs should be usable-in-Swift-style~~ — **rejected by design** (records forbid `state:` per §4.10.7)

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
