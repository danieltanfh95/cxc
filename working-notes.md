# Corvid Working Notes — Design Rationale and Open Items

**Purpose:** consolidate the reasoning behind the current spec (v0.4) and enumerate what's still open. This document captures *why* decisions were made, not just what they are. The spec is the specification; this is the thinking behind it.

**Companion document:** `spec/spec-v0.7.md` (was `spec-v0.6.md` through v0.6.0; before that `corvid-spec-v0.5.md`).

## Session note — v0.8.0 library-first sugar (Level 2 aliases, 2026-08-08)

Following v0.7.2's unification of class-kind sugar under aliases, user asked: "if `alias` is the sugar system, what if we extend aliases to handle expression-position patterns like `[...]`?" Working through it — this session ("Session 2" originally scoped as collection literals + ranges + interpolation + random) revealed that the aliases-as-sugar-system move had a natural extension into Level 2: pattern-matching alias signatures covering delimited patterns, infix operators, string interpolation.

User pushed for the full commitment. Committed to Level 2 with the framing "sugar is a library" — the compiler ships a minimal bootstrap grammar; the prelude declares standard surface syntax as alias declarations. Version bumped to v0.8.0 (mechanism change, not additive).

**Design decisions in this session:**

- **Bootstrap/prelude split.** Compiler-committed: identifiers, base literals, arithmetic/comparison/logical operators, base expression grammar, control flow keywords, section syntax, class/function/module declarations, the alias mechanism itself. Prelude: type-expression sugars (`T?`, `T!`, `[T]`, etc.), collection literals, range operators (`..<`, `...`), string interpolation (`${expr}`), bitwise operators, nil-coalescing (`??`), class-kind aliases (moved from v0.7.2 hardcoded), section-composition aliases (moved from v0.6.0 hardcoded).
- **Prelude is spec-committed and part of language distribution.** Any change to a prelude alias is a language-level breaking change. Users cannot override or shadow prelude aliases; standard surface syntax is stable across projects.
- **Alias signature pattern language.** Delimited patterns (`[<items>]`, `{<pairs>}`, `{<items>}`), infix/prefix/suffix patterns with precedence and associativity, string-embedded patterns (`${<expr>}`), position markers (five categories), guards (`where <var> is <TypePattern>`).
- **Two-pass compilation with alias resolution.** Pass 1 gathers aliases with bootstrap grammar; Pass 2 re-parses with extended grammar. Aliases cache across incremental compilations.
- **Precedence value scheme.** Unified 1-14 range for prelude+bootstrap operators (following C99 order). User-defined operators MUST use precedence 100+.
- **Grammar-extensibility discipline documented in §4.17.** Bootstrap is fixed; prelude is spec-committed; user aliases are lexically scoped; tooling MUST expose expansions; style discipline is cultural. This is the Clojure/Racket tradeoff applied consistently with §1.2 (intent-not-authority) and §1.5 (runtime-as-substrate).
- **Collection literal conformance protocol.** New §3.10 — `ExpressibleAsArrayLiteral`, `ExpressibleAsMapLiteral`, `ExpressibleAsSetLiteral`. Prelude literal aliases lower to protocol-dispatched constructors; any conforming type (native or Foundation-imported) accepts the same literal syntax.
- **Session 2 items land as prelude declarations from the start.** No language-committed grammar for collection literals, ranges, or interpolation — they're prelude aliases immediately.
- **Random deferred.** Was in original Session 2 scope but it's a stdlib method (`UInt8.random(in: ...)`), not sugar. Not affected by this refactor; can be added as part of a later stdlib session.

**Two things worth remembering:**

- **Corvid the compiler vs Corvid the language.** The compiler is smaller than "the language" — the language distribution is compiler + prelude. Without the prelude, source that looks like ordinary Corvid wouldn't parse. This distinction is real and load-bearing.
- **The style-discipline burden.** The language enables user-defined syntax at all supported positions. Culture and code review are the guardrails against DSL abuse. Style guides SHOULD recommend restraint (domain-specific literals for domain projects; general libraries should not introduce novel syntax).

Resolved friction points: `#W-24` (Map type), `#W-27` (Array literal), `#W-30`/`#W-36`/`#W-55` (string interpolation), `#W-37(a)` (range half-open), `#W-67` (range-based for). Ink cross-exercise gap #22 (string interpolation) resolved as prelude alias.

## Session note — v0.7.2 sugar unification (2026-08-07)

Before starting Session 2 (collection literals + type aliases + random), a drill-down on the alias system surfaced that Corvid had two parallel sugar mechanisms: `alias` (for section-composition/type/value/module names) and §4.19's class-kind sugar layer (for `record`/`enum`/`protocol`/`section` declaration keywords). User asked: "can we go one level higher and say `alias` is the sugar system?"

Working through it revealed that class-kind sugars have a specific shape — header substitution + body prefix injection + body passthrough — that fits naturally under aliases if we add a body-content placeholder. Introduced `...` as the placeholder marker. `alias record Name: class Name: metadata: kind: record; ...` reads as "when `record Name:` appears with a body, expand to `class Name:` with the metadata prepended, then the original body."

Design decisions in this session:

- **Add `...` as body-content placeholder.** Single hole per alias. Multi-hole named placeholders deferred until real need surfaces.
- **Reverse v0.6 §4.19's "no user-defined sugar" stance.** Rationale for reversal: user-defined section/type/value/module aliases were already permitted; refusing user-defined class-kind aliases just for legibility was inconsistent. Legibility responsibility shifts to tooling (mandate: IDEs surface alias expansion on hover and goto-definition).
- **Retire §4.19 as a separate section.** All sugar lives under §4.16. §4.19 becomes a short redirect for readers following old cross-references.
- **Two-pass compilation with alias resolution.** Documented in §4.16.11. Aliases cache across incremental compilations.
- **Keep type-expression sugar language-committed.** `T?`, `T!`, `[T]`, `{K:V}`, `(A,B) -> C` remain compiler-committed grammar rules — pattern-matching over syntax rather than identifier substitution. Lifting them to aliases (via hypothetical `alias suffix '?': ...` forms) would couple parser to name resolution and open the door for user-defined type sugar hurting legibility. Not worth the payoff.
- **Keep closure sugar language-committed.** Closures rewrite the body structurally (into `call` methods) and generate fresh anonymous names — doesn't fit the "body passthrough" model that aliases handle.

Resulting coherent story:
- **`alias`** — pattern-based substitutions at declaration-keyword, section-header, type-name, value-name, module-name positions. `...` for body-content holes.
- **Section classes** — body-level behaviors (§4). What happens inside a section body.
- **Language-committed sugar** — type-expression forms (§3.2) and closures (§5.6). Fixed grammar rules, not user-extensible.

No user-visible behavior change for existing code. All four class-kind sugars work identically; they're just declared in stdlib now rather than hardcoded in the compiler.

## Session note — v0.7.1 Tier 1 sweep (2026-08-07)

Session 1 of the post-nil Category 2 backlog. Direct C inheritance — zero design decisions, filling spec gaps surfaced by the Starscream parity translations. Added §2.7 (Literals — dec/hex/bin/oct integers, floats, char/string literals with C escapes), §3.9 (Operators — arithmetic, bitwise, comparison, logical, precedence table with C99 discipline), §7.1 module-level `let` note, §9.2 `sizeof`/`alignof`/`offsetof` for C interop. Also updated §2.5 keyword list, §3.1 primitive types (added unsigned variants explicit list, primitive-as-value-type note), and §10 grammar productions to match.

Design notes worth remembering:
- **Integer overflow is defined behavior** in Corvid — signed overflow wraps (two's-complement modular), against C99's UB. Users wanting trap or saturation use `checked_*` / `saturating_*` methods returning `Result` / clamped values. Consistent with §1.5 (runtime as substrate, not as ceiling) — Corvid tightens C's slack in favor of predictable behavior.
- **Bit-shift out-of-range is a contract violation** — runtime warning + deterministic wrap in release. Same discipline as overflow.
- **Logical operators are word-form** (`and`/`or`/`not`), not C's `&&`/`||`/`!`. Reserves `&`/`|` unambiguously for bitwise; matches §1.6 legibility premise.
- **Module-level `let` requires type annotation** — no cross-module inference. Determinism at the interface boundary.
- **`var` is not a keyword** — mutable-by-default with `immutable` opt-in per §6.15. Parity translations use `var` inconsistently (Swift habit); noted as drift to sweep in a later idiomatic pass.

Resolved friction points: `#W-17` (`else if`), `#W-54` (bitwise), `#W-64` (compound bit-assign), `#W-68` (`%`), `#W-73` (runtime shift amount).

## Session note — v0.7.0 nil-messaging inversion (2026-08-07)

Following the Starscream parity exercise (Category 2 spec gaps), a drill-down on nil handling surfaced that Corvid v0.6.0 had inherited Swift's optional-explicit model wholesale — `T?` everywhere, `?.` chaining required, `!` force-unwrap available. The parity translations showed this creating substantial ceremony (`?.` at every delegate call, nested `if let` for cookie iteration) that the underlying ObjC runtime doesn't require.

The design shift: **reference types allow nil by default (runtime-honest); the type system marks non-nil expectations via contract sugar `T!`.** `?.` retires; `.` operates on nil receivers uniformly (auto-lift return, or dispatch via new `on_nil` section for punned returns). Force-unwrap `x!` retires at value site — explicit panic via `x ?? panic("...")` is the sanctioned form, verbosity intentional to remove the Rust-style attractor for hidden panics.

Value-type Optional (`Int?` = `Optional<Int>` sum) is preserved — value types can't be nil at runtime, so `T?` retains its sum-type role for them. `T!` on value types is rejected (trivially true).

The `on_nil` section is a stdlib-provided section (built on §4 metaprogramming primitives). Users declare a method-name → nil-time-value table; the compiler generates nil-receiver dispatch. Stdlib `Collection` and `String` protocols ship default `on_nil` entries (`count: 0`, `is_empty: true`, `iter: EmptyIterator()`), so any conforming user type gets Clojure-style nil-punning by conformance rather than per-class ceremony.

Rationale in one line: Swift's nil-safety model paid ceremony taxes (`?.` at call sites, `!` as panic attractor) to fight the ObjC runtime substrate that Corvid targets — inverting the annotation direction removes the taxes and makes the type system honest about runtime truth.

Impact: retired `T?` for reference types (~15 sites in parity translations), retired `?.` (~22 sites), reduced nested `if let` chains, added §3.6.1 (nullability), §4.10.9 (on_nil section), §5.7 (nil-receiver dispatch). Charter unchanged — the shift descends from §1.5 (runtime as substrate) and §1.9 (contracts as coordination primitives) already committed.

---

## Part 1 — The Reasoning Chain

The design emerged from a sequence of framings, each of which reshaped what came before. Reading the design as a static specification obscures the moves that produced it. This section walks through the framings in order.

### 1.1 Origin — comparing Vala, D, and Objective-C

The exploration started as a comparison of three "C with objects" languages, each of which took a different approach:

- **Vala** — transpiles to C, uses GObject as the object system substrate. The language is a syntactic layer over an existing runtime.
- **D** — replaces C entirely with a new language that has C ABI compatibility. Full metaprogramming, algebraic types, comptime.
- **Objective-C** — preserves C exactly, adds Smalltalk-style messaging as a second orthogonal language. Dynamic dispatch as first-class primitive.

The framing observation: these are three different answers to "how do you add objects to C." Each is defined by what it *refuses to change*.

Objective-C's design decision — dynamic dispatch as language primitive, preserved runtime, C interop by identity — turned out to be the most interesting to modernize. Vala is trapped by GObject. D solved the wrong problem (a better C++). Objective-C's semantic model is genuinely distinctive and hasn't been properly modernized.

### 1.2 OOP evolution and the encapsulation axis

The next framing came from thinking about how OOP has evolved: inheritance → composition → actors. What's monotonic across this evolution isn't "OOP getting better" — it's the encapsulation boundary tightening and what crosses that boundary getting more restricted.

| | Boundary | Shared | Crosses |
|---|---|---|---|
| Inheritance | Class (leaky) | Code, memory layout | Method calls, direct field access |
| Composition | Object | Nothing structurally | Method calls (sync) |
| Actors | Actor (mailbox) | Nothing | Messages (async) |

Related patterns don't fit the linear evolution — ECS, prototypes, mixins, traits are variations on how encapsulation is drawn. This surfaced that **classes conflate six things**: state bundling, behavior binding, subtyping, code reuse, dispatch, identity/lifetime. Modern language work is about unbundling these.

### 1.3 Deconstructing OOP as six axes

Any modern OOP language works by unbundling the six axes above. Different languages unbundle differently — Rust unbundles state (structs) from behavior (impl) from subtyping (traits); Swift unbundles value types from reference types; Zig unbundles almost everything via comptime.

This was the setup for the Corvid design: pick which axes to unbundle and how.

### 1.4 The Objective-C successor question

Rather than build a new language from scratch, the question became: **what does Objective-C become if you deconstruct it properly?**

Objective-C already had two key properties nothing else has combined:
1. Fully dynamic dispatch as a language primitive (message send).
2. Runtime as a separate C library (`libobjc`) with stable ABI, cross-platform-portable.

What Objective-C got wrong:
- Every method goes through `objc_msgSend`. No opt-out for hot paths.
- `id` gives up static checking entirely.
- No sum types, pattern matching, comptime.
- Bracket syntax alienates C-family users.
- Header/impl split.

The design bet: **Objective-C's dispatch model, unbundled from Objective-C's ergonomics, targeting the cross-platform native-app space that Swift ceded by staying Apple-tied.**

Runtime substrate: **libobjc2 (GNUstep's runtime)** rather than Apple's objc4. MIT-licensed, cross-platform, modifiable.

### 1.5 Dispatch — sealed vs dynamic vs "cake and eat it"

The first substantive design question was dispatch: sealed-by-default (Swift/Rust style) or dynamic-by-default (Objective-C tradition)?

**Sealed-by-default problem:** library authors have to predict every extension point. Users can't hook what wasn't marked open. This is the Swift `open` problem — framework authors under-mark.

**Dynamic-by-default problem:** every call site pays for capability nobody uses. Compiler can't devirtualize without whole-program analysis.

**Neither fully worked. The resolution:** decouple semantics from performance.

**Committed model:**
- All methods are semantically dynamic. Everything hookable via runtime API.
- `sealed` is a **performance hint**, not an authority claim.
- Compiler assumes sealed methods aren't hooked; if they are, runtime patches call sites (deopt).
- Library authors can't lock users out; users pay cost only where they invalidate assumptions.

This is Option C from the earlier analysis. Requires deopt machinery in libobjc2, but the mental model is clean and it preserves the intent-not-authority principle uniformly.

### 1.6 Author intent, not authority — as foundational premise

The dispatch resolution generalized into a **foundational design premise**: library authors express *intent* about how their code will be used; downstream code always retains capability.

This applies uniformly:
- Sealed methods still swizzlable; sealed call sites deopt.
- Private members still reachable via reflection.
- Sealed classes still subclassable via runtime API.
- Advisory effects (`throws`, `allocates`, `blocks`) checked at compile time; runtime doesn't enforce.

**Non-consequence:** doesn't apply to semantics that change compilation (`async` calling convention, `mutating` struct storage). Those must be enforced because they produce different values under different rules.

This premise made the language philosophically coherent and shaped several subsequent decisions.

### 1.7 Contracts replace nominal types

Given message-passing as the primitive, nominal type systems don't fit well. The natural question at a call site isn't "what type is this object" but "does this object accept these messages."

**Committed:** types become **contracts** — sets of accepted messages with signatures, preconditions, postconditions, effects, and (optionally) session constraints. Conformance is structural.

This subsumes: interfaces, generic constraints, refinement types, effect polymorphism, session types. Not five separate features; one contract language expressed at different verification depths.

### 1.8 Mutability defaults — the split by declaration site

Multiple false starts here.

**First attempt:** immutable by default (Rust/Swift style). Rejected because Objective-C's world is mutable objects.

**Second attempt:** mutable by default everywhere. Ended up with everything getting `immutable` marks, so the wrong case was default.

**Third attempt (from the reframe):** split by declaration site. Fields default immutable (identity is stable); locals default mutable (accumulators are common). Marked exceptions in each case.

**Fourth attempt (reframe again):** the per-declaration-site split is really about **compile-time vs runtime**, not "fields vs locals." Constants are compile-time immutable; class-level counters are compile-time mutable; identity fields are runtime immutable; coordination fields are runtime mutable.

### 1.9 Records vs classes — and the fold into `data:` sections

The record-vs-class question surfaced: should the language have separate `record` and `class` constructs (Kotlin-style)?

**Rejected because of the preemptive-optimization problem:** users can't know at declaration time whether something is "data" or "behavior." Refactoring cost when the shape changes is high.

**Committed:** single `class` construct with **declared regions** for data and state. This became the `data:` and `state:` section syntax — you don't pick at construct level; you organize fields into roles inside the class.

### 1.10 Sections as general mechanism

The `data:` and `state:` sections looked initially like class-body organization sugar. Extending the question — "why not more sections?" — led to the discovery that sections could be a **general language mechanism**.

Enumeration of labeled-block constructs across scopes:
- Class body: `data:`, `state:`, `observable:`, `persistent:`, `secrets:`
- Function body: `quote:`, `pure:`, `unsafe:`, `validate:`, `atomic:`, `checked:`
- Module body: `pure:`, `interop:`, `test:`, `platform(...)`
- Expression: `quote:` (produces `Code`)

**Audit revealed:** not everything fits. `checked:` and `unchecked:` want expression-level rewriting (would make it a full macro system). `catch:` interacts with control flow. `atomic:` has runtime dependencies. Sections work for **block-level compile-time processing of contents, with optional runtime state.**

**Committed model:** sections as general mechanism, cross-scope, with `contains:` (content type) and `scope:` metadata. Class-body-first implementation, other scopes staged.

### 1.11 Sections are classes

The question "should section definitions be class definitions" led to substantive design work. Working through it:

**Reading 1:** section declarations ARE class declarations.
**Reading 2:** section instances are attached to host classes as hidden fields.
**Reading 3:** sections are composed into hosts.

All three are true simultaneously. Sections have **configuration** (compile-time constants), **state** (runtime storage per host instance), **generators** (compile-time code emission), and **methods** (runtime behavior). This is elegantly expressed as a class with self-hosting section-class-body syntax:

```
section class Observable:
    data:
        contains: fields
        composition_mode: nested
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote: ...
    
    function subscribe(self, callback): ...
```

The section mechanism defines itself. `data:` and `state:` are used in section class bodies with the same meaning they have in user class bodies.

### 1.12 Composition — nesting, then hybrid

**First attempt:** comma composition (`persistent, observable:`). Ambiguous — nobody knows which direction wraps.

**Second attempt:** nesting only. Top-to-bottom = outer-to-inner. Clean but verbose for combining characteristics.

**Third attempt (from the reframe):** **hybrid — inline for characteristics, nested for behavior**. Characteristics commute; behavior wraps. This matches traditional programming syntax (`public static final int MAX`) where modifiers compose inline in any order.

**Fourth attempt (from the final reframe):** all of these are sections. Sections declare `composition_mode: inline | nested`. Characteristics like `public`, `private`, `mutable`, `immutable` are inline-mode sections that compose commutatively. Behavior like `observable:`, `persistent:` are nested-mode sections that wrap.

### 1.13 Primitives and aliases

Committing to sections-are-everything raised: what happens to `data:`, `state:`, `static:`?

**Committed:** they become **aliases** for common primitive compositions.

```
alias data: runtime immutable public identity
alias state: runtime mutable package coordination
alias static: compile_time immutable public
```

Aliases are **pure textual expansion**. No override semantics — composing `data private:` is a contradiction (public + private) and produces a compile error. Users who need variants define their own aliases.

This preserves aliases as transparent shortcuts and pushes users to name their patterns explicitly.

### 1.14 Built-in section vocabulary

Three tiers of built-ins:

**Primitives (13, Stage 1):** `Runtime`, `Compile_time`, `Immutable`, `Mutable`, `Public`, `Private`, `Package`, `Sealed`, `Dynamic`, `Weak`, `Unowned`, `Identity`, `Coordination`.

**Aliases (4, Stage 1):** `data`, `state`, `static`, `methods`.

**Behavioral sections:**
- Stage 1: `Computed`, `Deprecated`.
- Stage 3: `Pure`, `Unsafe`, `Validate`, `Quote`.
- Stage 4: `Interop`, `Test`, `Platform`.

**Deliberately not built-in:** `Observable`, `Persistent`, `Inject`, `Secret`, `Traced`, `Cache`. These have competing designs, aren't universal enough, or are contested territory. Library ecosystem, not language commitment.

---

## Part 2 — Foundational Premises (Committed)

These are the design commitments that shape everything else. They are stable.

### 2.1 Message passing as the semantic primitive

Method calls are messages sent to objects. Every method is semantically dispatchable at runtime. Static dispatch is a compilation strategy, not a semantic model. The runtime maintains method tables for every class; introspection works on everything.

### 2.2 Author intent, not authority

Library authors express intent via annotations (`sealed`, `public`, `private`, effect declarations, section membership). These express expectations, not restrictions. Downstream code always retains full runtime capability.

The compiler acts on intent to optimize the default case; the runtime preserves user power at the cost of local deoptimization when assumptions are invalidated.

### 2.3 Contracts replace nominal types

Types are named contracts — sets of accepted messages with signatures and semantic guarantees. Conformance is structural. Nominal types exist as named contract references but don't gate conformance.

### 2.4 First-class primitives for what dispatch was faking

Observation, interception, extension, delegation, factories — each gets dedicated language constructs, often expressible as user-defined sections. Runtime dispatch is available but rarely load-bearing.

### 2.5 Runtime as substrate, not ceiling

Compiles to native code targeting `libobjc2` (GNUstep's runtime — MIT-licensed, cross-platform). The runtime is a small C library with stable ABI, not a whole VM.

### 2.6 Sections as general mechanism

Labeled semantic blocks are a first-class language feature, uniform across scopes. Sections have `contains:` (content type), `scope:` (applicability), and `composition_mode:` (inline or nested). User-definable via the fixed capability vocabulary.

### 2.7 Compile-time vs runtime as taxonomic axis

The distinction between "per-class" and "per-instance" is compile-time vs runtime existence. This organizes the section vocabulary — primitives compose along orthogonal dimensions (existence × mutability × visibility × dispatch × reference × contribution).

---

## Part 3 — Committed Design Decisions

Concrete, decided, in-spec.

### 3.1 Syntax and lexical structure

- Full words instead of abbreviations (`function`, `dynamic`, `public`, `private`, `enumeration`, `constructor`, `destructor`).
- Explicit `return` (no implicit last-expression return).
- Indentation-delimited blocks (Python-style).
- Keywords: `class open sealed dynamic protocol accepts requires ensures throws async allocates blocks mutable immutable function constructor destructor generator processor validator self Self return section alias if elif else match case for in while break continue import from as public private module true false nil`.

### 3.2 Object model

- `class` — reference type, heap-allocated, ARC.
- `struct` — value type, stack-allocated, copied on assignment.
- `enumeration` — value type, tagged union.
- Single inheritance, structural protocol conformance.
- Class bodies composed of sections.

### 3.3 Dispatch

- All methods semantically dynamic. Runtime maintains method tables.
- `sealed` and `dynamic` are compiler hints for lowering strategy.
- Sealed methods can still be swizzled at runtime; runtime patches sealed call sites (deopt).
- Module-level defaults: `sealed module` or `dynamic module`; default is `dynamic`.

### 3.4 Type system / contracts

- Nominal contracts (`protocol Foo`) name a set of accepted messages.
- Conformance is structural. Explicit `class Foo: MyProtocol` is optional but preferred for public API (early-fails).
- Contracts carry: signatures, preconditions, postconditions, advisory effects, default implementations.
- Sum types (`enumeration`), pattern matching, refinement types via `requires` clauses.

### 3.5 Effects

- **Advisory effects** (compile-time-checked, runtime-permissive): `throws`, `allocates`, `blocks`.
- **Semantic effects** (compile-time-enforced): `async`, `mutating`.
- Strictness (whether advisory become errors) is consumer-side, not library-side (compile flag or per-file pragma).

### 3.6 Memory / mutability

- ARC for classes. Value semantics for structs. `weak`/`unowned` for cycle breaking.
- Mutability applies to bindings, not referenced objects (Swift model).
- Locals default mutable; first-use declares; `immutable` opts into single-assignment.
- Field mutability governed by the section they're in (via primitive composition).

### 3.7 Sections — the general mechanism

- Labeled semantic blocks (`label:` followed by indented content).
- Each section class declares `contains:` (content type), `scope:` (applicability), `composition_mode:` (inline or nested).
- Six content types: `fields`, `methods`, `declarations`, `statements`, `ast_fragment`, `expressions`.
- Nine scopes: `class_body`, `struct_body`, `enumeration_body`, `protocol_body`, `function_body`, `method_body`, `generator_body`, `module_body`, `expression_context`.
- Composition: inline (whitespace-separated names, commuting) or nested (indented blocks, wrapping).
- Section classes self-host — they're defined with `data:` and `state:` sections and `generator`/`processor`/`validator`/`function` members.

### 3.8 Section vocabulary

- **13 primitive sections** (inline-composable characteristics).
- **4 aliases** (`data`, `state`, `static`, `methods`) as pure textual expansion of primitive compositions.
- **User-defined aliases** at module level.
- **Behavioral sections** (nested-composable, with generators and state): `Computed`, `Deprecated` in Stage 1; `Pure`, `Unsafe`, `Validate`, `Quote` in Stage 3; `Interop`, `Test`, `Platform` in Stage 4.

### 3.9 Runtime access

- Every host class instance has an implicit `sections` namespace.
- Section instances accessed via `instance.sections.<name>.method(...)`.
- Introspection via `sections.list()` and per-section `fields()`.

### 3.10 Interop

- **C interop**: direct via `import c "header.h"`.
- **Objective-C interop**: native — same runtime, ObjC classes accessible directly.
- **Swift interop**: via C ABI bridge; not first-class in v0.1.

### 3.11 Concurrency

- `async`/`await` with structured concurrency.
- `async` is a semantic effect (changes calling convention).
- Actors are library patterns, not language primitives.

### 3.12 Implementation stages

- **Stage 1 (v0.1)**: all-dynamic runtime, class-body sections only, built-ins hardcoded.
- **Stage 2 (v0.5)**: whole-module devirtualization, user-definable section classes.
- **Stage 3 (v1.0)**: cross-module deopt machinery, function/method-body sections.
- **Stage 4 (v1.5+)**: module-body sections, further optimizations.
- Semantic model fixed at v0.1; later stages only optimize and expand capabilities.

---

## Part 4 — Open Items

Enumerated by category. Numbers correspond to spec §13 where applicable, plus additional items from working notes.

### 4.1 Highest-priority

**A. Objective-C compatibility strategy** — **RESOLVED (spec §9, §12).**

Committed: file-level compatibility with shared runtime substrate (`libobjc2`). `.m` and `.cor` files coexist in the same project. Native ObjC interop (no bridge layer). Auto-generated ObjC headers for Corvid public APIs. Compilation strategy: LLVM IR direct as primary target; Corvid → ObjC source as bootstrap phase (Stage 0) and permanent alternate output path. No Clang fork. New Stage 1 built-in sections: `Category` for extending classes, `ObjC_Exposed` for explicit ObjC-visible API control. `Any` type formalized for the dynamic boundary.

**B. Reflection API surface** — **RESOLVED (spec §1.7, §4.18).**

Committed: reframed as **class metadata** rather than "reflection." Metadata is a first-class compilation artifact with three consumers: LSP/IDE tooling, runtime metadata queries, external tools via AI-manifest JSON. Class structure is compile-time-fixed; the runtime exposes metadata but does not create new structure. Section describers (new capability §4.5.10) declare each section's contribution to the metadata. Compiler is architecturally incremental from Stage 1 with LSP server as first-class operational mode.

Key reframe: what looks like "runtime discovery" is really "cross-module compile-time metadata query." A serialization framework querying unknown types is really querying types that WERE statically known in some other module. This lets the API be structured around lookups rather than free-form discovery, and lets tooling share the same underlying artifact as the runtime.

**C. Memory model** — **RESOLVED (spec §6).**

Committed: three type categories (struct, enumeration, class) sharing the section mechanism, with a one-bit distinction (state: allowed or not). Struct/enumeration disallow state; class allows it. All downstream properties (value vs reference semantics, static vs dynamic dispatch, extent participation, ARC) follow from this restriction.

Universal extents for all user-declared classes (no opt-in). Class references are universal handles with generation validation (FilC-style safety). Tags own contiguous storage regions within an extent; snapshots are materialized handle arrays. `dense_storage:` section hints SoA layout for cache-optimal iteration.

ARC as reference lifetime mechanism; `weak`/`unowned` for cycle breaking. Extent-scoped cycle detection as Stage 2+ optional library. ECS emerges naturally from tags + protocol queries; no ECS-specific language feature.

Explicit non-goals: no SQL, no persistence, no ACID, no mandatory GC, no user opt-out from extent tracking (use struct instead), no arbitrary structural modification at runtime.

Key reframe: "collections as tags" — traditional OOP conflates ownership with grouping. Under the extent model, extents own storage; tags name subsets; snapshots capture query results. Ownership contribution vs. non-contribution is visible in the type. Bidirectional relationships (parent-child, publisher-subscriber) often simplify: store one direction, query the other. Cycles dissolve because they were artifacts of redundant storage.

### 4.2 Structural design gaps

**D. Session types** (spec §13 item 1)

Contracts could carry message ordering constraints (`open` before `send` before `close`). Attractive because it fits the contract model naturally. Complex to design and implement. Deferred.

**E. Effect polymorphism** (spec §13 item 2)

Generic functions that inherit effects from their arguments. `map` should be `throws` if the mapping function throws. Currently deferred; may constrain what contracts can express if left too long.

**F. Full comptime metaprogramming** (spec §13 item 3)

Beyond section generators. Currently sections cover most of the metaprogramming need. Full comptime (Zig-style) is not committed; might be added later if section generators prove insufficient.

**G. Ownership types** (spec §13 item 4)

Beyond ARC. Could add `borrow`/`move` for zero-overhead cases. Currently deferred as ARC covers the common case.

**H. Pattern matching completeness** (spec §13 item 5)

Under structural conformance, exhaustive matching interacts with open sum types. Design work needed.

### 4.3 Language features

**I. Threading model** (spec §13 item 10)

Cooperative-only? Preemptive? OS threads? Structured concurrency scope rules? Unresolved.

**J. `Self` in protocols** (spec §13 item 9)

Nominal (Rust-style) or existential (Swift-style)? Different implications for what contracts can express.

**K. Standard library scope** (spec §13 item 7)

What ships with the language vs. what's a package? Affects positioning and the "batteries included" story.

**L. Package manager and build system** (spec §13 item 8)

Not a language design question but load-bearing for adoption. Deferred as implementation concern.

### 4.4 Section mechanism specifics

**M. Section versioning scheme** (spec §13 item 11, also §4.15)

Sections shipped in libraries participate in downstream compilation. Changes to a section class's generated code are breaking changes for host classes. Semantic versioning is committed in principle; precise scheme unresolved.

**N. Flat composition sugar** (spec §13 item 12)

Whether to add `>` or similar syntax for multi-section nested composition on one line. Currently deferred; nesting handles the semantic space. Revisit if real usage shows frequent deep compositions.

**O. User-alias export semantics** (spec §13 item 13)

Whether user-defined aliases should be exportable via `import` and how versioning interacts with alias evolution. Related to M.

**P. `strict module` semantics** (previously flagged)

Removed from v0.3. Strictness for advisory effects is consumer-side (compile flag). But: which advisory effects become errors, which stay warnings, how per-file pragmas work — not fully specified.

### 4.5 Stage 3+ built-in sections

Each of these has a spec sketch but needs implementation-level design before Stage 3 lands:

**Q. `Pure` semantics** — precise definition of what effects it prohibits. Different codebases mean different things by "pure."

**R. `Unsafe` capabilities** — precise list of what capabilities it enables. Intersects with memory model.

**S. `Validate` details** — what `require`/`assert` statements look like, contract violation propagation.

**T. `Test` details** — compile-mode detection, test discovery, test running.

**U. `Platform` details** — supported platforms and spellings.

### 4.6 Tooling and ecosystem

**V. Error message infrastructure**

Sections that produce bad error messages will be unusable. Source location tracking through generator emission is committed in principle but is nontrivial engineering.

**W. IDE support**

User-defined sections need to be loadable by IDEs for accurate diagnostics and completion. Affects tooling architecture — sections aren't just a compiler feature.

**X. Debugger provenance**

Generated code needs to be debuggable with clear attribution to the section that produced it.

### 4.7 Working-session items

Items raised in conversation that we haven't fully resolved:

**Y. `static:` at module scope** — currently allowed for module-level compile-time-existing declarations. But module bodies also allow bare declarations (implicit `methods:`). Two ways to write module-level functions. Redundancy might mean `static:` should only apply inside class/struct/enumeration bodies.

**Z. `Computed` interaction with dependencies** — if a computed field references a mutable field, does the compiler track the dependency for invalidation, or does the user manage it? Currently no auto-caching; every access recomputes. Real usage may show this is unacceptable.

---

## Part 5 — What's Been Rejected

Important negative space — decisions we've made *not* to do, and why.

- **Full comptime metaprogramming** as first-class (like Zig). Sections cover the need; general comptime is a bigger commitment we're not making now.
- **Ownership types by default**. ARC is simpler and matches Objective-C mental model.
- **Actors as language primitive**. Library pattern instead.
- **Full source-level Objective-C compatibility.** Rejected in v0.4 §12. But *file-level* compatibility (mixed projects) is open.
- **Homoiconicity / Lisp syntax**. Considered as part of the "sections are classes" reasoning; rejected as too big a commitment for what it buys.
- **Section inheritance**. Sections are flat; composition happens at the host class level.
- **Section method injection into host class** (v0.4 scope). Sections can't add methods to the host; users access section methods via `host.sections.name.method()`.
- **`strict module` construct.** Removed in v0.4. Strictness is consumer-side.
- **Alias override semantics.** Removed in favor of strict composition. Contradictions are compile errors, not warnings.
- **Comma-separated composition on section headers.** Removed in favor of nesting for wrapping and whitespace for inline characteristics.
- **`Constants` and `Companion` as distinct built-in sections.** Folded into `Static` alias, then further folded into primitive composition (`compile_time immutable public`).
- **Method injection via sections** (v0.4). Sections modify accessors and hooks; they don't add methods to host classes.

---

## Part 6 — Where the Design Is Distinctive

Positioning statement — what makes this language unlike others.

**Combines four unusual commitments:**

1. Dynamic dispatch as first-class semantic primitive (Objective-C heritage).
2. Compile-time performance via deopt-backed sealed hints (novel implementation of "cake and eat it").
3. Sections as general labeled-block mechanism (no mainstream language has this).
4. Contracts as replacement for nominal types (Erlang/Elixir-adjacent, but statically-checked).

**No mainstream language has this combination.** Rust has 2 and pieces of 4. Swift has 2 and 4. Objective-C has 1. Kotlin/Scala have pieces of 3 via DSL builders (runtime only). Nobody has all four.

**Section mechanism, specifically**, collapses what other languages handle piecemeal:
- Attributes / annotations (Java, Rust, Python).
- Property wrappers (Swift).
- Decorators (Python).
- Aspect-oriented weaving (Java AspectJ).
- Effect-scoping keywords (Rust `unsafe`, C# `checked`).
- DSL builders (Kotlin, Groovy).
- Compile-time metaprogramming (Zig, Rust macros).

If sections work well, this is the language's headline feature — not "modern Objective-C" but "the language where cross-cutting concerns are first-class and composable."

---

## Part 7 — Next Steps (Working Sequence)

From the current conversation:

1. **§4.6 composition rules** — RESOLVED. Nesting for behavioral wrapping; inline for characteristics.
2. **Additional built-in sections** — RESOLVED. Three-tier built-in vocabulary specified.
3. **File-level Objective-C compatibility** — RESOLVED. Shared runtime, LLVM IR direct, `.m` and `.cor` files coexist, auto-generated ObjC headers, Stage 0 bootstrap via Corvid → ObjC.
4. **Reflection API surface** — RESOLVED. Reframed as class metadata (§4.18) with three consumers (LSP, runtime queries, AI-manifest). Incremental compiler with LSP as Stage 1 commitment.
5. **Memory model** — RESOLVED. Three type categories with one-bit distinction. Universal extents. Handles. Tags with contiguous storage. Snapshots for query results. FilC-style safety.
6. **Concurrency model** — RESOLVED (§1.9, §8). Contracts as coordination primitives. No committed concurrency model. Channel and Signal sections. State machines as synchronization. Structured async.
7. **Module system** — RESOLVED (§7, §4.16 generalized). Three levels: source, module, package. Package section for manifest. Aliases generalized to four categories. Implicit resolution with explicit imports for overrides. Single-file scripts work with zero ceremony.
8. **Empirical validation** — later. Take real Objective-C code, translate to v0.5, see what breaks.

Additional items surfaced during working sessions:

- Working through Stage 3 built-ins (Pure, Unsafe, Validate, Quote) at implementation-level detail.
- Ecosystem section examples (Persistent, third-party sections) as library sections — writing full section-class definitions to prove the mechanism can handle them.
- **AI-authoring implications** — spec now commits to incremental compiler with LSP server in Stage 1. AI tooling can query class structure, contracts, and sections via LSP or via AI-manifest JSON. Legibility-over-brevity is a foundational design premise (§1.6).
- **Package manager tooling** — post-v1 concern. v1 ships with compilation model and manifest support; the package manager tool (fetching, caching, dependency resolution) comes as a separate later effort.

---

## Appendix — Version History

- **v0.1** (initial spec): sealed-by-default, `let`/`var`, `def` for methods, `record` vs `class` construct question, `@interface`/`@implementation` alternatives.
- **v0.2**: "author intent, not authority" elevated to foundational premise. Dispatch model rewritten. Effects split into advisory/semantic.
- **v0.3**: full words instead of abbreviations. Mutable by default (fields). Removed `strict module`. Explicit return.
- **v0.4**: sections as general mechanism. Cross-scope reserved. Content type and scope taxonomies. Primitives + aliases + behavioral three-tier vocabulary. Alias composition strict (no override). ObjC compatibility resolved (file-level mixing, shared libobjc2, LLVM IR + Corvid→ObjC, auto-derived headers). `Any` type. `Category` and `ObjC_Exposed` sections. Stage 0 bootstrap phase. AI-authoring implications.
- **v0.5.1** (authority cleanup): every keyword expressing enforcement the runtime doesn't provide was removed. `sealed`, `dynamic`, `final`, `open`, `private`, `package`, `internal` gone. `public` renamed to `expose`. Closed sum types come from enumerations, not from sealed protocols. Compiler devirtualizes automatically without source hints. Two visibility states (expose / absent) replacing three levels. Ties §1.2 (intent, not authority) into strict discipline across the entire spec.
- **v0.5**: metaprogramming unified — all compile-time user constructs are section classes. Aliases become section classes with `expands_to`. Section classes gain parameters (type/effect/value). Class-level contracts via `invariants:`. State machines emerge from invariants + method contracts (not a separate feature). `switch` for pattern matching with sealed protocols enabling exhaustiveness. Effect polymorphism via generic parameters. `With` section for RAII. Legibility-over-brevity design premise. Explicit metaprogramming boundaries (§4.17): no higher-order sections, no section-generates-section, no cross-section inspection. Class metadata as shared artifact (§1.7, §4.18) — three consumers (LSP, runtime queries, AI-manifest). Incremental compilation with LSP as Stage 1 architectural commitment. Memory model (§6): three type categories with one-bit distinction (struct/enum disallow state, class allows it). Universal extents for classes. Universal handle-based references with FilC-style validation. Tags own contiguous storage regions; snapshots are materialized queries. `dense_storage:` for SoA. ECS emerges from tags + protocol queries. Pre-v1 design stance (§1.8) — bold changes okay until v1.0. Concurrency model (§1.9, §8): contracts as coordination primitives. No committed concurrency model. `Channel` section for async event streams (unifies observables). `Signal` section for one-shot completions. State machines as synchronization via `requires`/`ensures`. Structured async with scopes. Observable section removed — users pick channel, signal, or delegate protocols. **Module system (§7, §4.16 generalized)**: three distinct levels — source, module, package. Package section for manifest (full, embedded, or implicit). Aliases generalized to four categories (section/type/value/module) via one construct. Implicit resolution with explicit imports and `from` clauses for overrides. Standard library implicit. Single-file scripts work with zero ceremony.
