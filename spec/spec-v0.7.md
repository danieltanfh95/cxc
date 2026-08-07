# Corvid Language Specification — v0.7 (Working Draft)

**Status:** Working draft. This revision inverts Swift's nil-safety model: reference types are nil-allowed by default (runtime-honest), `T!` marks non-nil as contract, and force-unwrap plus optional chaining retire. Message sends on nil receivers auto-lift the return type and consult a per-class `on_nil` section (stdlib-provided) for punned returns. Prior structural unification from v0.6.0 (class-as-universal, kind-via-metadata, closures-as-anonymous-classes, everything-is-expression, channel modes) is preserved.

**Name:** `Corvid`. (Historical: named `objc5` throughout the design conversation. Renamed to Corvid when the positioning clarified as "peer of Swift for Apple-ecosystem native development" rather than "next-generation Objective-C.")

**Change log from v0.6.0 → v0.7.0 (nil-messaging inversion):**

Corvid inverts Swift's nil-safety model. Reference types allow nil at runtime (matches the ObjC substrate), the type system marks non-nil expectations as contracts (`T!`), and message sends on nil receivers propagate through instead of trapping. Force-unwrap and optional chaining — the Swift attractors for hidden panics and per-call syntactic tax — retire entirely. A new stdlib section (`on_nil`) lets classes declare per-method nil-response values, and Collection-protocol conformance provides sensible defaults (`count: 0`, `is_empty: true`, `iter: EmptyIterator()`) for free.

- **Reference types nil-allowed by default.** For classes of `kind: regular` (reference-typed), `T` permits nil at runtime. `T!` is contract sugar for `invariant: value != nil`. `T?` on reference types is retired (redundant). Value types (`kind: record`, `kind: enum`, primitives like `Int`, `Bool`) unchanged: `T` never nil, `T?` remains sugar for `Optional<T>` sum type.
- **Force-unwrap `x!` retired at value site.** No sugar for panic-on-nil. Users write `x ?? panic("reason")` when they genuinely need to fail. Verbosity is intentional — panic should look exceptional in source so it stays exceptional in behavior.
- **Optional chaining `?.` retired.** `.` operates uniformly on non-nil and nil receivers. Nil receivers return nil for reference-returning methods; the call-site expression type auto-lifts to `Return?`. For methods returning `Unit`, nil receivers are a silent no-op.
- **`on_nil` stdlib section (new §4.10.9).** Class or protocol body may declare `section on_nil:` as a method-name → nil-time-value table. Compiler generates nil-receiver dispatch to consult the table before falling back to auto-lift. Precedence: class `on_nil` overrides protocol `on_nil`; unlisted methods auto-lift.
- **Collection + String protocols ship default `on_nil`.** `Collection` conformers inherit `count: 0`, `is_empty: true`, `iter: EmptyIterator()` on nil receiver. `String` similarly for `length` / `is_empty` / `chars`. Users writing their own collection types get nil-punning by protocol conformance, not per-class ceremony.
- **`T!` contract violations warn, don't panic.** Runtime observes nil in a `T!`-annotated field or parameter → logs a warning (dev) or configurable action (prod). Nil continues to flow through message sends. `T!` is a debugging aid asserting author expectations; safety comes from nil-punning being safe.
- **Arithmetic and ordering on nil are type errors.** `nil + 3`, `nil < 3` don't propagate — must unwrap or use `??`. Equality (`==`, `!=`) punning is safe: `nil == nil` is true, `nil == x` is false. Ordering with nil has no coherent semantics.
- **ObjC nullability mapping updated (§9.3.2).** ObjC `nullable` maps to Corvid `T`. ObjC `nonnull` maps to `T!`. Un-annotated ObjC types map to `T` (nil-allowed default; matches ObjC runtime truth without warning noise).
- **Grammar (§10).** Type production adds `!` suffix (reference-type contract). `?` suffix narrows to value-type Optional-sum only. Value expressions: `?.` and value-position `!` productions removed.
- **Rationale.** Swift's nil-safety model paid two visible taxes: `?.` at every optional call site (readers stop seeing it) and `!` at value site (attractor for hidden panics as codebases evolve). Corvid inherits the ObjC runtime, which already allows nil for every reference — Swift's type system was fighting this truth and paying the tax. v0.7.0 reverses the direction: nil is the baseline for references, non-nil is the exception marked by contract. The Clojure-style pun-on-nil semantics (via ObjC runtime + `on_nil` for user code) removes the safety motivation for the taxes, so the taxes go too.

Downstream cleanups: §3.1.1 (`Any?` note removed — `Any` allows nil directly), §3.2 (Optionals split into reference vs value cases), §3.4.2 (Connection example — retire `Socket?` and `socket!.send`), new §3.6.1 (Nullability and Nil-Punning), new §4.10.9 (`on_nil` section), new §5.7 (Nil-Receiver Dispatch), §6.14 (Reference Modifiers — retire redundant `?` on `weak`), §8.6 (channel section field types), §9.3.2 (ObjC nullability round-trip), §10 (grammar). Roughly 15-20 substantive edits across the spec.

**Change log from v0.5.2 → v0.6.0 (structural unification):**

Consolidates struct, enumeration, and protocol into a single declaration form: `class + metadata: kind: X`. Sugar keywords (`record`, `enum`, `protocol`) desugar losslessly to the unified core. Extends the concurrency model with channel modes and formalizes closures.

- **Class-as-universal.** Retired `struct` and `enumeration` as top-level type-declaration keywords. All user types declare via `class`, with `metadata: kind:` selecting the variant (`regular` default, `record`, `enum`, `protocol`, `section`). `protocol` was already sugar in v0.5 and remains so; `struct`/`enumeration` disappear entirely as core forms.
- **`metadata:` section (new).** First-class user-writable section on any class declaration. Must appear first if present. Contains spec-reserved keys (`kind:`, `raw_type:`, `mode:`, `capacity:`, `on_full:`) and user-defined keys. Populates the runtime-introspectable class metadata described in §4.18.
- **`record` sugar.** `record Foo:` desugars to `class Foo: metadata: kind: record`. Records opt out of universal extents, disallow `state:`, get structural equality and copy-on-assign.
- **`enum` sugar with raw values.** `enum Foo: case a = 1; case b = 2` desugars to `class + metadata: kind: enum, raw_type: <inferred>` with auto-generated `raw_value(self)` and `class function from_raw(value:)`. Raw type inferred from case values (smallest fit); overridable via `metadata: raw_type:`. Resolves the widest single interop gap in v0.5.
- **`protocol` sugar clarified.** Under `kind: protocol`, `accepts:` section holds required signatures, `methods:` section holds default implementations. `accepts foo(...)` per-method form remains legal as sugar for single-requirement protocols.
- **Type-expression sugar formalized.** `T?` = `Optional<T>`, `[T]` = `Array<T>`, `{K: V}` = `Map<K, V>`, `{T}` = `Set<T>`, `(A, B) -> C` = `Callable<(A,B), C>`. Sugar is lossless; expanded forms always legal. New sugar requires spec commitment.
- **Everything is an expression.** `if`, `switch`, block, and expression-position `do:` produce values. Field/parameter/return initializers accept control-flow expressions directly. Blocks return the value of their final expression unless an explicit `return` is present.
- **Loops return Bool.** `while` and `for` produce `Bool` — `true` if the loop exhausted its condition/iteration naturally, `false` if exited via `break`. Break-with-value is not supported on `while`/`for`; use `loop { ... break value }` (Rust-style infinite loop) if a T-typed return is genuinely needed.
- **Closures are anonymous classes.** A closure literal desugars to an anonymous `class` with a single `call` method. Sugar hierarchy: `function(x: T) -> R: ...` (explicit) → `x => x + 1` (arrow, inferred types) → method reference (`self.foo`). Strong capture by default; `[weak self, ...]` prefix opts into weak capture per variable. Compiler warns on closures capturing `self` strongly and stored in a field of `self` (classic retain-cycle pattern).
- **Channel modes (§8.6).** `channel:` section extends with `metadata: mode:` — `work` (default, CSP-style competing consumers), `broadcast` (all subscribers see all items, slowest sets pace), `pub` (topic-partitioned, requires `topic_fn:`). Direct field entries (`input: Bytes`) declare channel items; composition with nested `state:` retains the v0.5 state-change auto-publish behavior. Unbounded by default; opt into `metadata: capacity: N` + `on_full:` for bounded.
- **Grammar (§10).** `struct_decl` and `enumeration_decl` productions retired. Sugar keywords `record`, `enum` added and specified as desugaring rules; `protocol` sugar clarified. `metadata:` section usage documented alongside `data:`/`state:`/`methods:`. Loop expressions typed `Bool`.
- **Rationale.** The v0.5 spec carried three separate declaration keywords for what is structurally the same construct: a named collection of members with different capability sets. Under kind-as-metadata, one construct handles all four (record, enum, protocol, section) plus regular classes uniformly, and users write the sugar they already recognize. The interop payoff is immediate — enum raw values (the top-frequency interop gap in the Starscream translation pass) collapses to a single spec-committed feature.

Downstream cleanups: §3.3 (defers to §6), §3.4 (protocol via kind), §3.7 (expression form extended), new §3.8 (loop expressions), §4.7 (class body includes `metadata:`), §4.10 (built-in kind section classes), new §4.11 (sugar layer), §6.1 (rewrites type categories as class kinds), §6.2 (record/enum runtime models), §8.6 (channel modes), new §5.6 (closures), §10 grammar. Roughly 40-60 substantive edits across the spec.

**Change log from v0.5 → v0.5.1 (authority cleanup):**

Sharpened §1.2 (intent, not authority) into a strict discipline. Every keyword that expressed enforcement the runtime doesn't provide was removed:

- **Removed `sealed`** — as sum-type marker (§3.4.1) and as dispatch hint (§5). Enums are closed by construction; protocols are open. Compiler devirtualizes automatically where safe.
- **Removed `dynamic`** — meaningless once `sealed` is gone. All methods are semantically dynamic; devirtualization is an optimization the compiler makes without source hints.
- **Removed `final`, `open`** — same problem. "No override allowed" was authority the runtime doesn't enforce.
- **Removed `private`, `package`, `internal`** — as compile-time enforcement. Under intent-not-authority, absence of `expose` communicates internal-by-convention; compiler warns on external access rather than erroring.
- **Renamed `public` → `expose`** — verb form matches the intent-based vocabulary (`accepts`, `requires`, `ensures`). Emphasizes the deliberate act of exposure.
- **Removed module-level dispatch policy** (`sealed module`, `dynamic module`). Optimization policy is a build-tool concern.

Downstream cleanups: §2.5 keyword list, §3.4.1 (closed sum types from enums, not sealed protocols), §3.7 pattern matching, §4.10.1 primitive sections (dropped Sealed/Dynamic/Private/Public/Package, renamed Public→Expose), §4.10.2 built-in aliases (no longer include visibility by default), §5 dispatch model, §7.7 visibility (two states, not three levels), §10 grammar sketch, §11 worked example, §14 Stage 1 primitives list. Roughly 60-80 substantive edits across the spec.

Rationale: §1.2 committed to intent, not authority — but the language kept authority-flavored keywords that pretended to enforce restrictions the runtime blows through anyway. Users writing sealed/private/final got a false sense of protection while any runtime consumer could work around them via metadata queries, categories, or swizzling. Removing them makes the language honest about what it does.

**Change log from v0.4:**

**Metaprogramming unification (§4):**
- All compile-time user-definable constructs are section classes. Primitives, aliases, and behavioral sections differ only in which capabilities they use.
- Aliases are now section classes with `expands_to:` capability. `alias name: ...` syntax is preserved as sugar for the common case.
- Section classes gain **parameters** — type parameters, effect parameters, value parameters. Parameters bound at use site.
- Section class capability vocabulary formalized: configuration, parameters, expands_to, state, generators, processors, validators, describers, invariants, runtime methods.
- Explicit boundary rules: no higher-order sections, no section-generates-section, no cross-section inspection, no arbitrary compile-time computation.
- Bootstrap set: `Data`, `State`, `Methods` sections plus member kinds. Everything else expressible.

**Class metadata as shared artifact (§1.7, §4.18):**
- Class metadata is a first-class compilation output, generated once at compile time.
- Three consumers share the metadata: LSP/IDE tooling (Consumer 1), runtime metadata queries (Consumer 2), external tools via AI-manifest JSON (Consumer 3).
- Reflection is reframed as metadata query, not runtime discovery. Class structure is compile-time-fixed; the runtime exposes but does not create structure.
- Section describers (new capability §4.5.10) declare what each section contributes to the metadata.
- Compiler is architecturally incremental from Stage 1; LSP server ships with the compiler.
- Explicit non-goals: no runtime class creation, no `eval`, no arbitrary structural modification of instances.

**Memory model resolved (§6):**
- Three type categories: struct, enumeration, class. All share the section mechanism.
- One-bit distinction: struct/enumeration disallow `state:` sections; class allows them. Everything else (value vs. reference semantics, static vs. dynamic dispatch, extent participation, ARC) follows from this restriction.
- **Universal extents** for all user-declared classes. No opt-in required. `Class.query()`, `Class.all()`, `Class.count()` universally available.
- **Handle-based references** for all class instances. Compiler optimizes to direct pointers for scope-local references. FilC-style validation on cross-scope references.
- **Tags (`Tag<T>`)** own contiguous storage regions within a class's extent. Iteration is cache-optimal. `dense_storage:` section enables SoA layout.
- **Snapshots (`Snapshot<T>`)** are materialized handle arrays for query results and cross-tag operations.
- ARC as reference lifetime mechanism; `weak`/`unowned` for cycle breaking. Extent-scoped cycle detection as Stage 2+ optional library.
- ECS emerges as a natural pattern from tags + protocol queries. No language-level ECS commitment.
- Explicit non-goals: no SQL, no persistence, no ACID, no mandatory GC, no user opt-out from extent tracking (use struct instead).

**Concurrency resolved (§1.9, §8):**
- New design premise §1.9: **Contracts as coordination primitives.** Methods with `requires`/`ensures` on mutable state have atomic check-and-transition semantics. Correctness contracts, synchronization primitives, and state machine semantics unify into one mechanism.
- No committed concurrency model. Language provides primitives (state machines, channels, signals, async, threads); users compose.
- **Channel section** (Stage 1 built-in) — asynchronous event streams. Instance-level and class-level subscription. Buffered/unbuffered, cancellable, backpressure-aware.
- **Signal section** (Stage 1 built-in) — one-shot completion notifications. Set once; awaiters unblock.
- **Observable section removed.** Users wanting synchronous callbacks use delegate protocols; users wanting change notification use channels; users wanting one-shot completion use signals.
- **State machine synchronization** — `requires`/`ensures` on mutable state get atomic transitions (runtime-enforced Stage 1, compile-time-analyzed Stage 3+). Async methods wait on unmet preconditions; sync methods fail immediately.
- **Structured concurrency** — `async_scope`, `spawn`, structured cancellation, error propagation.
- **Sendable types** — inferred from shape (value types, class handles, function values with sendable captures).
- **Threads and parallelism** — explicit threads via `unsafe:` (Stage 1); `parallel_for` for row-level parallelism (Stage 3+).
- **ObjC interop** — GCD queue bridging, completion handler ↔ async conversion, `@main_actor` for main-thread affinity.

**Module system resolved (§7, §4.16 generalized):**
- Three distinct levels: **source** (single file), **module** (compilation unit, possibly multi-file, with unified metadata), **package** (distribution unit, versioned).
- **Package section** (Stage 1 built-in) — manifest as a section. Three forms: full `package.cor` file, embedded in-script header, implicit for scripts without dependencies.
- **Aliases generalized** (§4.16 rewrite) — one construct for all four categories: section-composition, type, value, module. Single mechanism for compile-time text substitution.
- **Implicit resolution with optional explicit imports.** Compiler resolves names against manifest-declared packages; `import` statements optional for disambiguation or readability.
- **Explicit source declaration via `from` clause** in dependencies and imports (local paths, URLs, git refs, private registries).
- **Standard library implicit.** No imports needed for stdlib.
- **Two visibility states:** `expose` (part of the module's stable interface) or absent (internal by convention). Compiler warns on external access to non-exposed members; users configure strictness. No `public`, `private`, `internal`, `fileprivate`, or `public(package)` keywords — see §7.7.
- **Single-file scripts** work with zero ceremony: `oc5 hello.cor` runs a script; embedded `package:` for scripts with dependencies.
- **Deferred to post-v1:** package manager tooling, registry protocol, version resolution algorithm, workspace concept.

**Pre-v1 design stance (§1.8):**
- Explicit commitment that pre-v1 versions may break to simplify or unify the design.
- Post-v1: additions welcome, removals require major version bumps, semantic changes require migration paths.

**Class-level contracts (§3):**
- New `invariants:` section for class and struct bodies. Contains predicate expressions that must hold at every observable point.
- Compiler-enforced in Stage 3+ where analyzable; runtime-enforced in Stage 1.
- Section classes get invariants (they ARE classes).

**State machines emerge from contracts:**
- Session types / state machines are not a separate feature. They emerge from class-level invariants + method-level `requires`/`ensures`.
- Classes with state fields + invariants over those fields + preconditions on methods effectively encode state machines.
- Compile-time state tracking deferred to Stage 3+; runtime tracking in Stage 1.

**Pattern matching / switch (§3):**
- `switch` keyword for pattern matching (not `match`). Extends C/ObjC `switch` with pattern support, exhaustiveness for closed sums, expression form, no fallthrough.
- Closed sum types come from enumerations, not from a `sealed` keyword on protocols. Protocols are always open interfaces; enumerations are always closed.

**Effect polymorphism (§3.5.3):**
- Generic parameters can range over effects (`E: Effect`).
- Explicit form: `function map<T, U, E: Effect>(items: [T], f: (T) -> U with E) -> [U] with E`.
- Inference sugar: when a function takes function-typed parameters and doesn't specify effects, effects are inferred from callback usage.

**RAII (§4.10.4.5):**
- `With` section for scoped resource management. Deinitializers run in reverse declaration order on scope exit.

**Design premises (§1.6 new):**
- **Legibility over brevity** — code is optimized for readers, not authors. Especially load-bearing given AI-authored code and human review workflows.

**Previously-deferred items resolved:**
- Section versioning: semver on section classes (§4.15 expanded).
- Flat composition sugar: removed from deferred; nesting handles it.
- User-alias export: aliases participate in module import/export system.
- Consumer-side effect strictness: `--strict-effects` flag and `#strict` pragma.
- `static:` at module scope removed — bare declarations at module level instead.
- `Computed` no auto-caching. Users memoize with `state:` or use library caching sections.
- Cooperative-only threading for v0.1; preemptive Stage 3+.
- Reflection API surface formalized (§4.17 new).
- Error message and provenance commitments formalized (§4.18 new).
- Blocks, closures, `instancetype` interop rules (§9.3 expanded).
- AI-manifest JSON format shipped in Stage 1 (§9.7 new).

**Change log from v0.3:** *[see v0.4 for details; superseded by unification above]*

---

## 1. Design Premises

Five commitments, from which the rest derives.

### 1.1 Message passing as the semantic primitive

Method calls are messages sent to objects. The runtime knows how to route messages, register handlers, and introspect the message table. Every method is semantically dispatchable at runtime. Static dispatch is a compilation strategy, not a semantic model.

### 1.2 Author intent, not authority

Library authors express intent about how their code will be used. Annotations that describe intent are welcome; annotations that enforce restrictions on downstream users are not.

**What's intent:**
- `expose` — this is part of the interface I designed for external use. Depend on it.
- `throws E` — this method may fail in ways described by `E`. Handle it.
- `async` — this method may suspend. Await it.
- `requires`/`ensures`/`invariants` — these predicates document and enforce correctness at method boundaries.
- Section membership — this declaration participates in the sections named.

**What we removed as authority:**
- No `sealed` or `final` — "you can't extend this" was a lie. The runtime always allows extension (categories, swizzling, dynamic proxies). If the compiler couldn't enforce it, we shouldn't pretend it does.
- No `dynamic` as a source annotation — meaningless without `sealed`. Everything is dispatched dynamically; the compiler devirtualizes when it can prove safety, and deopt machinery handles the surprises.
- No `private` — "you can't touch this" is the same authority pattern. Absence of `expose` communicates internal-by-convention. The compiler warns on external access; users choose whether to respect the warning.

**Downstream code always retains full capability** — to hook, override, extend, introspect, or modify — via the runtime. Metadata queries (§4.18) return everything the compiler knows regardless of `expose`. Categories add methods to any class. Swizzling replaces implementations. This is Objective-C's runtime philosophy carried forward honestly.

**Non-consequence:** intent-not-authority does not apply to semantics that change compilation itself. `async` changes the calling convention; mutability annotations change struct storage semantics; type shapes determine memory layout. These are semantic in the sense that different values would be produced under different rules, so they cannot be "just hints." See §3.5.

### 1.3 Contracts replace nominal types

A "type" is a named contract: a set of accepted messages with signatures, preconditions, postconditions, effects, and (optionally) session constraints. Conformance is structural. Named contracts (`protocol Foo`) reserve identity when the author wants to disambiguate structurally identical concepts.

### 1.4 First-class primitives for what dispatch was faking

Observation, interception, extension, delegation, factories each have dedicated language constructs — many of them expressible as user-defined sections. Runtime dispatch is available but rarely load-bearing.

### 1.5 Runtime as substrate, not as ceiling

The language compiles to native code targeting `libobjc2` (GNUstep's Objective-C runtime, MIT-licensed, cross-platform). The runtime provides dynamic dispatch, method registration, introspection, and call-site invalidation on swizzling. Interop with C, Objective-C, and (via runtime bridges) Swift is first-class.

### 1.6 Legibility over brevity

The language optimizes for readers, not authors. Code that is easy to review, verify, and reason about locally takes priority over code that is minimally typed.

This premise is especially load-bearing given that a substantial portion of Corvid code will be AI-authored. Humans are the primary readers of code they didn't write, and the language's structural signals — sections, contracts, explicit types, invariants, effect annotations — exist to make review tractable. Verbosity at the declaration site is a small cost paid once; opacity at the review site is a cost paid every time.

**Consequences:**
- Public API types are explicit (no inference across module boundaries).
- Sections are explicit at class scope; alias sugar available for common cases.
- Effects are explicit on function types; inference only within a compilation unit.
- Contracts are explicit; runtime enforcement documents intent.
- Section-generated code carries provenance information visible to tooling.

**Non-consequences:**
- Not "verbose everywhere" — local reasoning benefits from concision when the meaning is clear.
- Not "no inference" — inference within a function body is fine; inference at API boundaries is not.

### 1.7 Metadata as shared artifact

Class metadata — the structural description of a class including its sections, generated members, contracts, and provenance — is a first-class compilation output. It exists once, generated by the compiler, and is consumed by three separate systems:

1. **Tooling at authoring time.** Language servers (LSP), IDEs, IntelliSense, AI code assistants. These need to know what methods a class has, what the section instances expose, what contracts must be satisfied — all while the user is editing, before compilation completes.

2. **The runtime itself.** Cross-module lookups ("give me the metadata for class X in module Y"), dynamic dispatch, category application, subclass creation. All of these read metadata generated at compile time; the runtime does not create new class structure.

3. **External tools.** AI-manifest JSON exports, documentation generators, static analyzers, migration tools. Systems that read the metadata to reason about code without executing it.

**Consequence:** the reflection/introspection API is not "look at what exists at runtime" but "query compile-time metadata that the runtime holds in memory." Class structure is compile-time-fixed; nothing structural is created at runtime. This is both a design commitment and a substantial simplification — most reflection APIs collapse together "runtime discovery" and "metadata query" and pay the cost of both.

**Consequence:** the compiler is architecturally an incremental system with a language-server mode from Stage 1. It holds state between invocations, exposes metadata queries against that state, and emits metadata artifacts alongside object files. Batch compilation is a mode; language-server operation is another mode. Both use the same underlying pipeline.

**Consequence:** sections are the mechanism by which metadata is generated. Section describers (§4.5.11) declare what a section contributes to the class's metadata. Built-in sections have known describers; user-definable sections gain describers as a capability in Stage 2+.

This premise justifies the incremental compiler architecture, the unified metadata format, and the framing of "reflection" as metadata query rather than runtime discovery.

### 1.8 Pre-v1 design stance

Until v1.0 ships, the language is pre-stable. Any design decision may be revisited if a bolder or more unifying alternative emerges. Simplification wins over compatibility with earlier drafts. Sections marked *[TENTATIVE]* or *[UNRESOLVED]* are the most likely to change, but no part of the spec is off-limits for reconsideration.

**Once v1.0 ships:**
- Additions are welcome (new sections, new library types, new syntactic sugars).
- Removals require major version bumps and formal deprecation cycles.
- Semantic changes require deliberate migration paths, translation tools, and lead time.

**Consequence for tool developers:** treat pre-v1 spec versions as intentionally unstable. Tools targeting v0.x may need updates between versions. This is the trade-off for getting the design right; the language will hold still once we've earned it.

**Consequence for design work:** if a new insight would improve the design at the cost of breaking earlier decisions, we take the insight. Version numbers are cheap; getting the design wrong is expensive.

### 1.9 Contracts as coordination primitives

The contract system (preconditions, postconditions, class-level invariants) is not solely a documentation or verification feature. It is also the language's concurrency coordination mechanism.

Methods with `requires`/`ensures` clauses on mutable state have atomic check-and-transition semantics: the precondition check, method execution, and postcondition establishment happen as a single atomic operation with respect to other threads. Threads calling a method whose precondition is not yet met either wait (if the method is `async`) or fail (if the method is synchronous and the caller doesn't want to wait).

This unifies three concerns that most languages treat separately:

1. **Correctness contracts** — the type-system-adjacent statement of what a method requires and guarantees.
2. **Synchronization primitives** — locks, condition variables, semaphores, monitors.
3. **State machine semantics** — an object's state determines what operations are valid on it.

Under this design, all three are the same mechanism. `requires connection_state == .opened` documents intent, verifies at runtime, and synchronizes concurrent callers. `ensures status == .shipped` documents the postcondition, provides the state machine transition, and unblocks waiters observing the state change.

**Consequences:**
- Concurrency safety comes from writing contracts, not from separate synchronization annotations.
- Class-level `invariants:` (§3.4.2) hold across concurrent modification.
- Compiler generates lock/CAS machinery to enforce atomicity of contract-carrying methods.
- Optimizer eliminates synchronization for methods whose contracts don't reference mutable state (pure methods, methods on data-only fields).
- Runtime cost is bounded and proportional to contract density in mutating code paths.
- Compile-time race detection is possible where contract-carrying methods can be analyzed statically (Stage 3+).

**Non-consequences:**
- Not "everything is automatically thread-safe." Methods without contracts on mutable state get no synchronization guarantees. Users write contracts where thread safety matters.
- Not a replacement for careful concurrent design. The mechanism prevents data races on contract-guarded state; it does not prevent deadlocks, livelocks, or logical errors.

This premise justifies the concurrency model in §8 and shapes how sections that manage cross-thread coordination (`channel:`, `signal:`) integrate with the rest of the language.

---

## 2. Lexical Structure *[TENTATIVE]*

### 2.1 Encoding
Source is UTF-8. Identifiers may contain non-ASCII characters per Unicode UAX #31.

### 2.2 Comments
- Line: `# comment to end of line`
- Block: `#| block comment |#`
- Documentation: `## doc comment` (attaches to following declaration)

### 2.3 Identifiers
- `snake_case` for methods, functions, variables, module names, section names.
- `PascalCase` for classes, protocols, sum types, effect names, section classes.
- `SCREAMING_SNAKE_CASE` for constants.

### 2.4 Layout
Indentation-delimited blocks (Python-style). Indent must be consistent within a block. No tab/space mixing.

### 2.5 Keywords
```
class protocol accepts requires ensures
throws async allocates blocks
mutable immutable
function constructor destructor generator processor validator
expander invariants
self Self return
section alias
category objc_exposed
if else switch case for in while break continue
import from as expose module
true false nil
Any Effect
with
```

Removed from prior drafts: `sealed`, `dynamic`, `open`, `final`, `public`, `private`. See §1.2, §5, §7.7 for rationale.

Note: `else if` is written as two tokens, not one; there is no `elif`.

### 2.6 Reserved Syntactic Space

The syntactic form `label:` followed by an indented block is reserved language-wide for section usage. This applies at every scope — class bodies, function bodies, method bodies, module bodies, expression contexts — even where the section mechanism is not yet implemented in a given language version. Future versions may enable this syntax in additional contexts without breaking existing code.

---

## 3. Type System (Contract System) *[TENTATIVE]*

### 3.1 Primitive Types
- `Int`, `Int8`, `Int16`, `Int32`, `Int64` (and unsigned variants)
- `Float`, `Double`
- `Bool`
- `Char` (Unicode scalar), `String` (UTF-8, immutable), `Bytes`
- `Unit` (the type with one value, written `()`)
- `Never` (the type with no values, for divergent functions)
- `Any` — dynamically-typed value; the type system knows nothing about it. See §3.1.1.

#### 3.1.1 The Any Type

`Any` represents a value whose concrete type is not known at compile time. Method calls on `Any` are dispatched through the runtime:

```
x: Any = some_dynamic_source()
x.method()   # dispatched via runtime; fails at runtime if no matching method
```

**When to use `Any`:**
- At the Objective-C interop boundary — ObjC APIs returning `id` come into Corvid as `Any` (§9.3.2).
- Reflective code that operates on unknown types.
- Explicit dynamic dispatch when the user knows the concrete type isn't statically determinable.

**Pattern matching narrows `Any`:**

```
match x:
    case Order(o):
        process(o)              # o: Order
    case String(s):
        log(s)                  # s: String
    case _:
        handle_unknown()
```

**`Any` is not a universal supertype.** There is no "everything conforms to Any" claim. Values are typed `Any` when the compiler doesn't know their type, not because they satisfy some contract.

**`Any` allows nil at runtime.** As a reference-shaped type (§3.6.1), an `Any` value may be nil. If a specific call site needs a non-nil-Any contract, write `Any!`.

**Idiomatic use is rare.** In pure Corvid code, contracts and specific types are preferred. `Any` should appear primarily at interop boundaries or in genuinely dynamic code paths. The compiler warns when `Any` is used as a field or return type in public APIs, since callers get no static guidance.

### 3.2 Composite Types
- Tuples: `(Int, String)`, `(x: Int, y: Int)`
- Arrays: `[T]`
- Dictionaries: `{K: V}`
- Sets: `{T}`
- Optionals (value-type only): `T?` sugar for `Optional<T>` sum type. Legal when `T` is a value type (`kind: record`, `kind: enum`, primitive). Rejected for reference-typed `T` (reference types are nil-allowed by default — see §3.6.1).
- Non-nil contract (reference-type only): `T!` sugar for `T` with `invariant: value != nil`. Legal when `T` is a reference type. Rejected for value-typed `T` (trivially true — warning).
- Result: `Result<T, E>` (built-in sum type)
- Function types: `(A, B) -> C`, `(A, B) async throws E -> C`

### 3.3 User-Defined Types

All user types are declared via `class`. The `metadata: kind:` section entry (§4.10.6, §6.1) selects the variant:

- **`kind: regular`** (default, omitted) — reference-typed with `state:`, extent participation, weak refs, dynamic dispatch. Traditional class.
- **`kind: record`** — value-typed, no `state:`, no extent, structural equality, copy-on-assign. Replaces v0.5's `struct` keyword.
- **`kind: enum`** — sealed sum type with `cases:` section and optional integer `raw_type:`. Replaces v0.5's `enumeration` keyword.
- **`kind: protocol`** — no instantiation; `accepts:` section holds required signatures, `methods:` holds default implementations.
- **`kind: section`** — §4 metaprogramming section-class.

Sugar keywords (`record`, `enum`, `protocol`, `section`) desugar losslessly to the corresponding `class + metadata: kind:` form. See §4.11 for sugar rules. Class bodies are described in §4.7; runtime semantics per kind are described in §6.

**Sum types** (kind: enum):
```
# Sugar (typical user-facing form):
enum Result<T, E>:
    case ok(T)
    case err(E)

enum Shape:
    case circle(radius: Float)
    case rectangle(width: Float, height: Float)

# Enum with raw values (interop-friendly form):
enum CloseCode:
    case normal = 1000
    case protocol_error = 1002
    case encoding = 1007
```

For raw-valued enums, `raw_type:` is inferred from the case values (smallest unsigned type that fits) unless overridden via `metadata: raw_type:`. The compiler auto-generates `raw_value(self) -> <raw_type>` and `class function from_raw(value:) -> Self?`.

The desugared form is:

```
class Result<T, E>:
    metadata:
        kind: enum
    cases:
        case ok(T)
        case err(E)

class CloseCode:
    metadata:
        kind: enum
        raw_type: UInt16     # inferred
    cases:
        case normal = 1000
        case protocol_error = 1002
        case encoding = 1007
```

Users rarely write the desugared form; tooling operates on it.

### 3.4 Contracts (Protocols)

A protocol is a class with `metadata: kind: protocol` — a named set of accepted messages with contracts, plus optional default implementations. The `protocol` keyword is sugar for the unified `class + kind:` form (§4.11).

```
protocol OrderProcessor:
    accepts process(Order) -> Result<Receipt, OrderError>
        throws, async
        requires order.is_valid
        ensures result.is_ok implies inventory.contains(order.items)

    accepts cancel(OrderID) -> Bool
        throws

    # Default implementation
    function process_batch(self, orders: [Order]) -> [Result<Receipt, OrderError>]:
        return [self.process(o) for o in orders]
```

Desugared form:

```
class OrderProcessor:
    metadata:
        kind: protocol
    accepts:
        process(Order) -> Result<Receipt, OrderError>
            throws, async
            requires order.is_valid
            ensures result.is_ok implies inventory.contains(order.items)
        cancel(OrderID) -> Bool
            throws
    methods:
        # methods: section on kind: protocol holds default implementations
        function process_batch(self, orders: [Order]) -> [Result<Receipt, OrderError>]:
            return [self.process(o) for o in orders]
```

In the sugar form, `accepts foo(...)` at the class-body level and the `accepts:` section form are both legal — the compiler treats them identically. Use the section form when there are many required methods; use the per-method form when there is one.

**Conformance is structural.** Any type accepting these messages with matching contracts satisfies `OrderProcessor`. Explicit conformance is legal but not required.

#### 3.4.1 Closed Sum Types

Closed sum types come from **enumerations**, not from a keyword on protocols. An enumeration declares its cases; no external code can add cases. Exhaustive matching (§3.7) falls out from this — the switch analyzer sees all cases at declaration and enforces coverage.

```
enumeration Shape:
    case circle(radius: Float)
    case rectangle(width: Float, height: Float)
    case triangle(base: Float, height: Float)

# Switch is exhaustive over all cases.
function area(s: Shape) -> Float:
    switch s:
        case .circle(radius):
            return 3.14159 * radius * radius
        case .rectangle(width, height):
            return width * height
        case .triangle(base, height):
            return 0.5 * base * height
```

**Protocols are always open by design.** Any type in any module may conform to a protocol; that's the point of a protocol as an abstraction. If you want to express "this is a closed set of variants," use an enumeration with associated values.

Previously the spec had `sealed protocol` for enforcing single-module conformance. That's removed. Reasons:
- Enumerations already cover the closed-sum-type case with better ergonomics.
- Sealed protocols enforced a compile-time restriction via authority — matching the philosophy problem with `sealed` on classes (§5.2, §1.2).
- The visibility rules of the module (§7.7) already scope internal-by-convention protocols to their module.

For internal protocols that shouldn't be conformed to externally, just don't `expose` them. External conformance generates a warning; the module can convert conformances to enumerations if needed.

#### 3.4.2 Class-Level Contracts (Invariants)

Classes and structs can carry class-level contracts via the `invariants:` section — predicate expressions that must hold at every observable point (after construction, before/after each method call, before destruction).

```
class Connection:
    data:
        endpoint: URL
    
    state:
        socket: Socket                         # reference type, nil-allowed by default
        connection_state: ConnectionState = .closed
    
    invariants:
        connection_state == .opened implies socket != nil
        connection_state == .closed implies socket == nil
    
    function open(self)
        requires connection_state == .closed
        ensures connection_state == .opened:
        self.socket = Socket.connect(self.endpoint)
        self.connection_state = .opened
    
    function send(self, data: Bytes)
        requires connection_state == .opened:
        self.socket.send(data)                 # invariant ensures non-nil; no force-unwrap needed
```

**Enforcement:**
- **Stage 1**: runtime-enforced. Invariants are checked at method boundaries; violations raise contract errors.
- **Stage 3+**: compile-time-enforced where analyzable (state tracking through control flow for immutable-owned fields).
- Users can suppress runtime checks in release builds via `--no-runtime-invariants` flag.

**State machines emerge from this pattern.** A class with:
- State fields recording current state.
- Invariants over those state fields relating them to other fields.
- Method preconditions requiring specific states.
- Method postconditions transitioning states.

...encodes a state machine. The compiler tracks state transitions via `ensures` clauses and rejects invalid method calls via `requires` clauses. No separate "state machine" language feature is needed; the machinery falls out of contracts.

Sections benefit from this same mechanism. Since section classes ARE classes, they can carry invariants over their own state. See §4.10 for section classes with state machines.

### 3.5 Effects

Effect annotations describe what a method does beyond returning a value. Effects split into three categories under the intent-not-authority principle:

#### 3.5.1 Advisory Effects

Author expresses expected behavior; compiler checks at compile time as authoring aid; **runtime does not enforce**.

- `throws E` — expected to raise error type `E`.
- `allocates` — expected to allocate heap memory.
- `blocks` — expected to block the calling thread.

Consumer-side strictness is available via compile flag (`--strict-effects`) or per-file pragma.

#### 3.5.2 Semantic Effects

These annotations change compilation and cannot be "just hints."

- `async` — changes calling convention (suspendable).
- `mutating` — struct methods only. Modifies `self`. Not applicable to class methods.

#### 3.5.3 Effect Polymorphism

Generic parameters can range over effects, allowing higher-order functions to propagate the effects of their callbacks.

**Explicit form:**

```
function map<T, U, E: Effect>(items: [T], f: (T) -> U with E) -> [U] with E:
    result = []
    for item in items:
        result.append(f(item))
    return result
```

`E: Effect` declares `E` as a member of the Effect kind. `with E` on a function type includes E's effects in the type. `with E` on the return type propagates them.

Callers see effects inferred from their specific callback:

```
result = map(orders, function(o) throws OrderError -> Receipt { ... })
# E is inferred to `throws OrderError`. 
# result: [Receipt] with throws OrderError
# Caller must handle OrderError or propagate.
```

**Inference sugar for the common case:**

Functions taking function-typed parameters can omit the explicit effect polymorphism; effects are inferred from callback usage:

```
function map<T, U>(items: [T], f: (T) -> U) -> [U]:
    # Effects of map = union of effects of f's actual argument at each call site.
    ...
```

Sugar applies only within a single module (source available). Public API across module boundaries requires explicit effect polymorphism for legibility (§1.6).

**Aliases can name effect combinations:**

```
alias fallible<T>: Result<T, Error>
alias networked: throws NetworkError, async
```

Parameterized aliases resolve at use site (see §4.16).

### 3.6 Refinement / Precondition Contracts

Any type can carry a `requires` clause narrowing its inhabitants:

```
type Positive = Int requires self > 0
type NonEmpty<T> = [T] requires self.count > 0
```

Verification: runtime by default. Static verification via SMT is an optional backend.

### 3.6.1 Nullability and Nil-Punning

Corvid's nullability model inverts Swift's: **reference types allow nil at runtime by default; non-nil is the exception marked by contract.** This matches the ObjC runtime substrate (§1.5) — messaging nil is native, not a special case the type system fights to prevent.

**Type-annotation rules:**

| Annotation | Reference type (`kind: regular`) | Value type (`record`, `enum`, primitive) |
|---|---|---|
| `T` | nil-allowed at runtime | never nil |
| `T!` | contract sugar for `invariant: value != nil` | rejected (trivially true; warning) |
| `T?` | rejected (redundant with `T`) | sugar for `Optional<T>` sum type |

`T!` desugars to `T` plus an invariant. The compiler emits a runtime check at each observation point (field write, parameter bind, return); a violation logs a warning in development, configurable action in release. Nil continues to flow through message sends — `T!` documents author expectation, it does not enforce safety.

**Value-site semantics:**

- **Member access on nil.** `nil.method(args)` is legal. Method-return `T` auto-lifts to `T?` at the call site (message send on nil returns nil per the ObjC runtime); method-return `Unit` is a silent no-op. If the receiver's class or a conformed protocol declares `section on_nil:` (§4.10.9) with an entry for this method, that entry's value is returned instead of nil-lift.
- **Nil-coalescing.** `x ?? default` returns `x` if non-nil, else `default`. Types must match; the result type is the non-optional common type.
- **Explicit panic.** `x ?? panic("reason")` is the sanctioned "die if nil" form. There is no shorter sugar. Verbosity is intentional — panic should look exceptional in source so it stays exceptional in behavior.
- **Scoped unwrap.** `if let x = maybe: ...` and `guard let x = maybe else: ...` bind unwrapped `x` for the following scope. Standard patterns from the Swift-family.
- **Arithmetic and ordering on nil are type errors.** `nil + 3`, `nil < 3`, and `nil % 2` do not propagate — the semantics are ambiguous. Unwrap explicitly or coalesce with `??`.
- **Equality.** `nil == nil` is `true`, `nil == x` (for non-nil `x`) is `false`. `!=` inverts. Ordering operators reject nil operands at compile time.

**Retired from prior drafts:**

- **Force-unwrap (`x!` at value site).** The v0.5–v0.6 form matched Swift; retired in v0.7. Users write `x ?? panic("reason")` when they need to fail on nil at a specific site. Removes the attractor for hidden panics as codebases evolve — every nil-panic site is verbose enough to notice in code review.
- **Optional chaining (`x?.method()`).** The v0.5–v0.6 form required `?.` at every optional-receiver call site. Retired in v0.7 — `.` operates uniformly on nil and non-nil receivers (nil-lift return, `on_nil` dispatch), so the marker was ceremony with no semantic content.

**Interaction with contracts (§1.9, §3.4.2):**

`T!` at the type site is a specific case of the general invariant mechanism. The following are equivalent:

```
class Handler:
    accepts:
        logger: Logger!

# desugars to
class Handler:
    accepts:
        logger: Logger
    invariants:
        logger != nil
```

Users needing richer nil-related contracts (e.g., "not nil AND not empty") write the invariant form directly. `T!` is optimized for the common case.

**Userspace nil-punning.** Every user method is automatically nil-safe for message sends (ObjC-inherited). For methods that should return a specific non-nil value on nil receiver — Clojure-style `(count nil)` = `0` semantics — the class or protocol declares a `section on_nil:` (§4.10.9) with a method → value table. Stdlib `Collection` and `String` protocols ship default `on_nil` entries; conformers inherit.

### 3.7 Pattern Matching (`switch`)

`switch` is the pattern-matching construct. It extends C/ObjC `switch` with structural patterns, exhaustiveness for closed sums, expression form, and no fallthrough by default.

**Expression form:**

```
result = switch value:
    case .ok(x):
        process(x)
    case .err(e):
        log(e)
        default_receipt
```

`switch` is an expression; each `case` block produces a value; all values must have compatible types. This is one instance of the general "everything is an expression" commitment (§3.8).

**Pattern types:**

- **Literal patterns:** `case 0:`, `case "hello":`, `case true:`.
- **Enumeration case patterns:** `case .some(let x):`, `case .none:`.
- **Type patterns (for `Any`):** `case Order(o):`, `case String(s):`.
- **Structural patterns (records/data classes):** `case Order(id: some_id, items: []):`.
- **Guard patterns:** `case Order(o) if o.total > 100:`.
- **Wildcard:** `case _:`.

**Exhaustiveness:**

- Switching on a closed sum type (enumeration): all cases must be handled, or a `case _:` wildcard included.
- Switching on a protocol: wildcard required (protocols are open — new conformers may be added).
- Switching on `Any` or an open type: wildcard required.
- Switching on numeric or string types: wildcard required.

Non-exhaustive matches are compile errors.

**No fallthrough:**

Each `case` block runs to completion; no implicit fallthrough to the next case. Multiple values matching the same code:

```
switch color:
    case .red, .orange, .yellow:
        return "warm"
    case .blue, .purple:
        return "cool"
    case .green:
        return "neutral"
```

### 3.8 Expressions and Statements

Corvid commits to **everything is an expression** for control-flow constructs. `if`, `switch`, blocks, and (in a specific form) loops all produce values. This unifies control flow and expression composition — one construct, usable in statement position or expression position depending on context.

#### 3.8.1 If-as-expression

```
port: Int = if is_tls: 443 else: 80

max_size = if payload_length > threshold:
    2 * threshold
else:
    payload_length

# Chained else-if works uniformly:
category = if code < 400:
    "success"
else if code < 500:
    "client_error"
else:
    "server_error"
```

Both branches must have compatible types. A one-armed `if` without `else` returns `Unit`; using it in expression position is legal but rarely useful.

#### 3.8.2 Block-as-expression

A block (`do:` in expression position, or an implicit block inside another expression) evaluates to the value of its final expression:

```
payload: Bytes = do:
    header = read_header()
    body = read_body(header.length)
    Bytes.concat(header, body)     # block's value
```

If the block contains only statements with no trailing expression, its value is `Unit`. An explicit `return` inside a method-level block returns from the enclosing method as usual; inside an expression-position `do:` block, `return` is still a method-level return (not a block-level return).

#### 3.8.3 Loops as expressions — `while` and `for` return `Bool`

Loops in Corvid are expressions producing `Bool`:

- **`true`** — the loop terminated because its condition became false (`while`) or its iteration exhausted (`for`).
- **`false`** — the loop exited via `break`.

This gives a first-class idiom for the "did we complete naturally?" pattern that most languages require a flag variable for:

```
# Search — check whether we exhausted without finding a match
not_found = for item in items:
    if item.matches(target):
        break
    # (loop body may do other work)

if not_found:
    handle_absence()
else:
    handle_present()

# Drain a queue until a sentinel
drained = while queue.not_empty():
    x = queue.pop()
    if x.is_terminator():
        break
    process(x)
# drained == false means we hit the terminator; true means the queue emptied naturally
```

**Break with a value is not supported on `while` and `for`.** The return type is fixed to `Bool` for legibility — a reader seeing `let x = while ...` immediately knows `x` is `Bool`.

For the rare case where a loop needs to return a T-typed value via break, use the infinite `loop { ... }` construct with `break value`:

```
# Rust-style: infinite loop, break-with-value returns T
first_match = loop:
    item = source.next()
    if item.matches(target):
        break item                # returns Match(item)
    if source.is_exhausted():
        break not_found_default    # returns default
```

`loop:` produces the type of its `break value` expressions (all `break value` sites must have compatible types).

#### 3.8.4 Statement position

Any expression can be used as a statement — its value is discarded. Loops, `if`, `switch`, and blocks all work this way; using them in statement position simply discards the Bool/T result.

**Consequence for legibility.** Users can write code the way they think about it: `if condition: run_side_effect()` reads as a statement even though `if` is technically an expression; the discarded `Unit` value doesn't affect meaning.

---

## 4. Sections

The **section mechanism** is the language's general form for labeled semantic blocks. A section is a labeled, indented block whose contents are governed by a section class. Sections appear across scopes:

- **Class and struct bodies** — sections organize fields and methods, contribute cross-cutting behaviors.
- **Function and method bodies** — sections modify how contained statements are compiled or executed.
- **Module bodies** — sections gate or transform declarations.
- **Expression contexts** — expression-form sections produce values (e.g., `quote:` produces `Code`).

Sections unify what other languages handle piecemeal: annotations, attributes, property wrappers, decorators, aspect-oriented weaving, effect-scoping keywords, DSL builders, and compile-time metaprogramming. In Corvid, all of these are expressed via section classes.

### 4.1 The Section Form

```
section_name:
    ...contents...
```

`section_name` names a section class. The compiler resolves the name in the current scope and applies the section class's processing to the contents.

Sections compose by nesting — see §4.6.

Sections can take parameters:

```
platform(linux):
    function get_kernel_version() -> String:
        ...
```

The parameter is passed to the section class as configuration.

### 4.2 Content Type Taxonomy

Each section class declares what its block contains via a `contains:` metadata slot. The vocabulary of content types is fixed by the language:

| Content type | Description | Example sections |
|---|---|---|
| `fields` | Sequence of `name: Type = default` declarations | `data:`, `state:`, `channel:` |
| `methods` | Sequence of method declarations (`function`, `constructor`, etc.) | `methods:`, `traced:` |
| `declarations` | Sequence of top-level items (functions, types, constants) | `pure:`, `test:`, `interop:` |
| `statements` | Sequence of executable statements | `pure:`, `unsafe:`, `validate:` |
| `ast_fragment` | Arbitrary AST, treated as data | `quote:` |
| `expressions` | Single expression (not a sequence) | *(reserved for future use)* |

The compiler enforces that a section's contents match its declared content type. Using `data:` around methods, for example, is a compile error.

### 4.3 Scope Taxonomy

Each section class declares where it can appear via a `scope:` metadata slot. The vocabulary of scopes is fixed by the language:

- `class_body`
- `struct_body`
- `enumeration_body`
- `protocol_body`
- `function_body`
- `method_body`
- `generator_body`
- `module_body`
- `expression_context`

A section can declare multiple scopes: `scope: [function_body, method_body, module_body]` means the section applies in all three. Using a section outside its declared scope is a compile error.

### 4.4 Section Class Definition

A section class is defined with `section class`:

```
section class SectionName:
    data:
        # Configuration constants (compile-time, per-host-class)
        contains: <content_type>
        scope: [<scope>, ...]
        # ... other configuration fields
    
    state:
        # Runtime state (per host class instance)
        # ...
    
    # Compile-time capabilities
    generator name(field: FieldSpec) -> Code: ...
    processor name(contents: Block) -> Block: ...
    validator name(contents: Block): ...
    
    # Runtime methods (available on self.sections.name.method())
    function name(self, ...) -> ...: ...
```

The section class body is itself composed of sections (`data:`, `state:`) and member declarations (`generator`, `processor`, `validator`, `function`). This is **self-hosting**: the section mechanism defines its own definition syntax.

Bootstrap set: the compiler ships with hardcoded understanding of `Data` and `State` section classes as they appear in section class bodies. Users can inspect and use these but not override them.

### 4.5 Capability Vocabulary

The set of capabilities a section class can use is fixed by the language version. Users compose these capabilities but do not invent new ones.

#### 4.5.1 Configuration

The `data:` section inside a section class holds compile-time constants read by the compiler when processing a host. These include mandatory metadata (`contains`, `scope`) and section-specific defaults.

Standard configuration fields (available to any section):
- `contains: <content_type>` — required.
- `scope: [<scope>, ...]` — required.
- `composition_mode: inline | nested` — required. Determines how this section composes with other sections of the same content type. See §4.6.
- `default_mutability: mutable | immutable` — for field-containing sections.
- `default_visibility: exposed | absent` — for field-containing sections.
- `default_existence: runtime | compile_time` — for field-containing sections.
- `contributes_to_equality: bool` — for field-containing sections.
- `contributes_to_hash: bool`
- `contributes_to_copy: bool`
- `contributes_to_display: bool | redacted`
- `contributes_to_serialization: bool`
- `incompatible_with: [SectionClass, ...]` — sections this refuses to compose with.

Section-class-specific configuration fields are declared in the section class's `data:` block and read via generators or processors.

#### 4.5.2 State

The `state:` section inside a section class holds runtime state per host instance. Each instance of the host class gets one instance of the section class as a hidden field, accessible via `self.sections.<name>`.

Sections with empty `state:` blocks are compile-time-only; the compiler elides the runtime instance.

#### 4.5.3 Generators

Generators emit code at specific extension points. They run once per host class at compile time.

Standard generators:
- `field_getter(field: FieldSpec) -> Code?` — override default getter.
- `field_setter(field: FieldSpec) -> Code?` — override default setter.
- `host_init_hook() -> Code?` — emit constructor prologue.
- `host_deinit_hook() -> Code?` — emit destructor epilogue.
- `method_wrapper(method: MethodSpec) -> Code?` — wrap methods (for `methods`-containing sections).

Generators return `Code` or `null` (use default). Code is constructed via `quote:` expressions.

#### 4.5.4 Processors

Processors transform the contents of a block. They run once per section usage at compile time and return the transformed contents.

```
processor name(contents: Block) -> Block: ...
```

Used for sections like `quote:` (that transform contents into an AST value) or `pure:` (that validate and pass through).

#### 4.5.5 Validators

Validators check the contents of a block for compliance without transforming. They emit compile errors on violation.

```
validator name(contents: Block):
    for item in contents:
        if item.has_effect(.io):
            @compile_error("pure section cannot contain I/O")
```

Semantically equivalent to a processor that returns contents unchanged after checking.

#### 4.5.6 Methods

Regular methods on the section class. Called at runtime via `host.sections.<name>.method(...)`. These implement the section's runtime behavior (subscription APIs, storage access, etc.).

#### 4.5.7 Parameters

Section classes can declare compile-time parameters, bound at use site. Three parameter kinds:

- **Type parameters:** `<T>`, `<T: Comparable>`.
- **Effect parameters:** `<E: Effect>`.
- **Value parameters:** `<N: Int>`, `<Format: SerializationFormat>` (compile-time constants).

Parameters are declared on the section class header and referenced in generators, processors, expansions, invariants, and configuration:

```
section class Effect_Aware<E: Effect>:
    data:
        contains: type_signature
        composition_mode: inline
    
    expands_to: with ${E} throws ${E}

section class Bounded<Max: Int>:
    data:
        contains: fields
        composition_mode: nested
    
    invariants:
        self.count <= ${Max}
```

Used with parameter values at the section header:

```
class MyQueue:
    bounded<100>:
        items: [Item]
```

Parameter references in generators use `${param_name}` splicing (same syntax as quote-based code generation).

#### 4.5.8 Expands_to

A section class with `expands_to:` performs textual substitution at parse time. The section is replaced with the specified composition of other sections. Sections with `expands_to:` typically have no state, no generators, no runtime methods — they exist purely to name a composition.

```
section class Data:
    data:
        contains: fields
        composition_mode: inline
    
    expands_to: runtime immutable identity
```

When `data:` appears in a class body, the compiler substitutes `runtime immutable identity:` before further processing. Expansion is applied recursively until no expandable sections remain.

**Expansion boundaries:**
- Expansion runs at parse time, before generator/processor invocation.
- Expansions cannot reference the host class's contents (they operate on section headers, not fields).
- Circular expansion (A expands to B, B expands to A) is a compile error.
- Expansions can reference parameters: `expands_to: with ${E}`.

#### 4.5.9 Invariants

Section classes are classes; they get class-level contracts (§3.4.2). Section class invariants apply to the section instance's state:

```
section class Channel:
    data:
        contains: fields
        composition_mode: nested
    
    state:
        subscribers: [Subscriber] = []
        active: Bool = false
    
    invariants:
        subscribers.count >= 0
        active == false implies subscribers.is_empty()
    
    function subscribe(self, callback)
        requires active:
        subscribers.append(callback)
```

Section invariants are checked at the same points as class invariants (§3.4.2) — Stage 1 runtime-enforced, Stage 3+ compile-time-enforced where analyzable.

#### 4.5.10 Describers

A section can declare a describer that runs at compile time and returns structural metadata about what the section contributed to the host class. Describers feed the class metadata artifact (§4.18) and are consumed by the language server, runtime metadata queries, and external tools.

```
section class Channel:
    data:
        contains: fields
        composition_mode: nested
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value):
                old = self._${field.name}
                self._${field.name} = new_value
                self.sections.channel.notify(name: "${field.name}", old: old, new: new_value)
    
    describer describe(host: ClassMeta) -> SectionMeta:
        wrapped_fields = host.fields_in_section(Channel)
        return SectionMeta:
            section_name: "channel"
            wraps_fields: wrapped_fields
            generated_methods: [
                MethodMeta(
                    name: "set_${f.name}",
                    signature: "(${f.type}) -> Unit",
                    contract: "notifies subscribers on change",
                    provenance: source_location(Channel, "field_setter", f))
                for f in wrapped_fields
            ]
            section_instance_methods: [
                MethodMeta(name: "subscribe", signature: "((String, Any, Any) -> Unit) -> Unit"),
                MethodMeta(name: "unsubscribe", signature: "((String, Any, Any) -> Unit) -> Unit"),
            ]
            section_instance_state: [
                FieldSpec(name: "subscribers", type: "[Subscriber]", visibility: .private)
            ]
```

**Describer semantics:**
- Runs at compile time, after generators have produced code but before code emission.
- Returns a `SectionMeta` structured value describing the section's contribution.
- Contributes to the enclosing class's `ClassMeta` — the class metadata artifact.
- Cannot inspect other sections' generated code (§4.17 boundary).
- Cannot produce code — only metadata.
- Provenance in the returned metadata links back to the section definition and specific generator invocations.

**Built-in sections have hardcoded describers** in Stage 1. `Data`, `State`, `Static`, `Methods`, `Channel`, `Signal`, `Computed`, `Deprecated`, `Category`, `ObjC_Exposed`, `With`, `Package` all describe themselves. Users writing section usages benefit from complete metadata immediately.

**User section classes gain describers as a capability in Stage 2**, when section classes become user-definable. A user-defined section without a describer contributes only its bare structural fact (name and composition mode) to the metadata; a section with a describer contributes full detail.

**Describers are pure compile-time computation.** They can compute over their inputs (the section usage, the host class's public interface) but cannot perform I/O, invoke the runtime, or produce side effects. Same discipline as generators.

#### 4.5.11 Capability Combinations

A section class uses any subset of the capabilities above. Common combinations:

- **Primitive section:** only configuration.
- **Alias section:** configuration + expands_to.
- **Parameterized alias:** configuration + parameters + expands_to.
- **Simple behavioral:** configuration + generators + describer.
- **Stateful behavioral:** configuration + state + generators + methods + describer.
- **State-machine section:** configuration + state + generators + invariants + methods with pre/postconditions + describer.

The compiler processes uniformly; users see only the capabilities each specific section actually uses.

### 4.6 Composition Rules

Sections compose in two modes, determined by the section class's declared `composition_mode`:

- **Inline composition** — for sections that describe properties without wrapping. Multiple sections are named in the same header, separated by whitespace. Order does not affect semantics. Sections applied inline commute.
- **Nested composition** — for sections that wrap behavior. Sections are stacked as nested indented blocks. Outer sections wrap inner sections.

Each section class declares its composition mode; the compiler enforces it.

#### 4.6.1 Inline Composition

A single header may name multiple sections separated by whitespace:

```
class HttpClient:
    compile_time immutable:
        DEFAULT_TIMEOUT_MS: Int = 30000
        MAX_RETRIES: Int = 3
```

The header composes `Compile_time` and `Immutable`. Each contributes its configuration to the enclosed fields. Order is not semantically meaningful — `compile_time immutable:` means the same as `immutable compile_time:`.

Sections composed inline must all declare `composition_mode: inline`. Attempting to compose a nested-mode section inline is a compile error.

Contradictions (`immutable mutable:`, `runtime compile_time:`) are compile errors regardless of order.

#### 4.6.2 Nested Composition

Sections that wrap behavior compose via nesting. Top-to-bottom in source is outer-to-inner in wrapping:

```
class UserPreferences:
    channel:
        persistent:
            mutable:
                theme: Theme = .light
```

Setter execution order when `preferences.theme = .dark`:
1. `channel`'s setter runs (outermost — listed first in the source).
2. Captures old value, delegates inward.
3. `persistent`'s setter runs.
4. Writes to backing store.
5. Returns.
6. `channel`'s setter notifies subscribers.
7. Returns.

The visual structure of the source IS the semantic structure of the wrapping.

Sections composed via nesting must declare `composition_mode: nested`. Nested-mode sections cannot appear in inline composition.

#### 4.6.3 Mixing Modes

Inline-mode sections may appear inside nested-mode sections at the innermost level, applied to the field or method declarations:

```
class UserPreferences:
    channel:
        persistent:
            mutable:                    # inline composition — properties of the field
                theme: Theme = .light
```

The outer nested sections handle wrapping; the inline composition handles the field's descriptive properties. Reader parses it as: `theme` is a public, mutable field, wrapped by persistent behavior, wrapped by channel behavior.

Nested-mode sections cannot appear inside inline composition — nesting is the only expression of wrapping order.

#### 4.6.4 Section Repetition

Sections can repeat in the same class body. Each occurrence governs the fields directly inside it; the section instances merge at the runtime level:

```
class UserPreferences:
    channel:
        persistent:
            mutable:
                theme: Theme = .light
                language: String = "en"
    
    channel:
        mutable:
            connection_state: ConnectionState
    
    persistent:
        mutable:
            last_backup: Timestamp
```

At runtime, this class has one `sections.channel` instance tracking `theme`, `language`, and `connection_state`, and one `sections.persistent` instance tracking `theme`, `language`, and `last_backup`.

#### 4.6.5 Compatibility Checking

The compiler enforces compatibility at parse time:

- **Content type mismatch** — attempting to compose sections that contain different things (e.g., a field-containing section around a method-containing section) is a compile error identifying the mismatch.
- **Semantic incompatibility** — sections declare `incompatible_with: [SectionClass, ...]`; the compiler rejects invalid compositions with a clear message.
- **Mode mismatch** — attempting to compose inline-mode and nested-mode sections in the same header (or nested-mode in inline position) is a compile error.
- **Getter conflicts** — if two composed sections both try to override the same field's getter, the compiler errors. Setter conflicts don't arise because nested setters compose via delegation.

### 4.7 Class and Struct Bodies

Class bodies are sequences of sections. Members appearing directly in a class body without an enclosing section are implicitly wrapped in a `methods:` section.

```
class Order:
    data:
        id: OrderID
        items: [Item]
        placed_at: Timestamp
    
    # Implicit methods: section — these methods appear as if in `methods:`
    function is_valid(self) -> Bool:
        return not self.items.is_empty and self.total() > 0
    
    function total(self) -> Money:
        return self.items.sum(by: .price)
```

Equivalent explicit form:

```
class Order:
    data:
        id: OrderID
        items: [Item]
        placed_at: Timestamp
    
    methods:
        function is_valid(self) -> Bool: ...
        function total(self) -> Money: ...
```

A class body may contain any combination of sections. It may have no `data:` (pure coordinator class), no methods (pure record class), or both.

Struct bodies follow the same structure as class bodies but with value semantics.

### 4.8 Function, Method, and Module Bodies

Sections in function/method bodies modify how contained statements are compiled or executed:

```
function compute(x: Int) -> Int:
    validate:
        require x > 0
        require x < 1000
    
    pure:
        result = x * 2 + 1
    
    return result
```

Sections in module bodies gate or transform declarations:

```
module com.example.utils

pure:
    function factorial(n: Int) -> Int: ...
    function fibonacci(n: Int) -> Int: ...

test:
    function test_factorial(): ...

platform(linux):
    function get_kernel_version() -> String: ...
```

### 4.9 Expression-Form Sections

Some sections appear in expression contexts and evaluate to values:

```
generator field_setter(field: FieldSpec) -> Code:
    return quote:
        function set_${field.name}(self, new_value: ${field.type}):
            self._storage.${field.name} = new_value
```

The `quote:` block is an expression evaluating to a `Code` value. `${expr}` splices values from the surrounding scope into the AST being constructed.

### 4.10 Built-in Section Classes

These section classes ship with the language and are available in every module. They fall into three categories, described below.

#### 4.10.0 Taxonomy — Primitives, Aliases, and Behavioral Sections

The built-in section vocabulary is organized into three tiers:

**Primitive sections** are the atoms of the vocabulary. Each expresses a single characteristic (mutability, visibility, existence, dispatch mode, etc.) with `composition_mode: inline`. They have no runtime state and no wrapping semantics. Their configuration contributes to the enclosing member's properties. Users compose them freely.

**Aliases** are language-provided names for common primitive compositions. `data:` is an alias for `runtime immutable identity`; `state:` is an alias for `runtime mutable package coordination`; `static:` is an alias for `compile_time immutable public`. Aliases are pure naming — they carry no additional semantics beyond the composition they expand to. Users may define their own aliases at module level (§4.16).

**Behavioral sections** have runtime state, wrapping semantics, or content transformation. They compose via nesting (`composition_mode: nested`). `Channel`, `Persistent`, `Computed`, `Deprecated`, `Pure`, `Unsafe`, `Validate`, `Quote`, `Interop`, `Test`, `Platform` all fall in this category.

**Why this structure.** The compile-time/runtime and immutable/mutable dimensions form an orthogonal grid; every combination is meaningful. Rather than pre-blessing specific combinations as sections (`Data`, `State`, `Static`), primitives let users express any combination, and aliases name the common ones. Class-level mutable state (`compile_time mutable`) — previously a "degenerate cell" in v0.4's initial taxonomy — is expressible via composition without needing its own alias:

```
class RequestCounter:
    compile_time mutable:
        total_requests: Int = 0
```

The primitive-and-alias model matches how programmers already read declarations: `public static final int MAX = 100` in Java is characteristic composition of orthogonal properties (visibility, storage, mutability). Corvid makes that composition uniform across the language's section vocabulary — visibility is `expose`, storage is `runtime`/`compile_time`, mutability is `mutable`/`immutable`.

#### 4.10.1 Primitive Sections *(Stage 1)*

All primitives declare `composition_mode: inline`. They may be freely composed with each other in a single header, subject to compatibility (contradictions are rejected).

**Existence:**

- `Runtime` — member exists per-instance at runtime. `default_existence: runtime`.
- `Compile_time` — member exists per-type at compile time, allocated once. `default_existence: compile_time`.

**Mutability:**

- `Immutable` — binding cannot be reassigned after initial value. `default_mutability: immutable`. Provides `field_setter` generator that emits a compile error on assignment.
- `Mutable` — binding may be reassigned. `default_mutability: mutable`.

**Visibility:**

- `Expose` — declaration is part of the module's intended external interface. `default_visibility: exposed`. Absent, declarations are internal-by-convention (§7.7).

No `Private`, `Public`, `Package`, `Sealed`, or `Dynamic` primitives. Reasons:
- `Public` renamed to `Expose` (§7.7).
- `Private` and `Package` removed — under intent-not-authority, absent-`expose` covers the internal-by-convention case; the compiler warns on external access. Type-scoped restriction is convention within a module.
- `Sealed` and `Dynamic` removed as source annotations (§5.2). Devirtualization is a build-tool concern.

**Reference strength:**

- `Weak` — reference does not retain; nils out when target deallocates.
- `Unowned` — non-owning strong reference.

Both apply to `contains: fields` sections where the field type is a class.

**Equality contribution:**

- `Identity` — field participates in equality, hashing, copy-with-modifications, and default serialization. `contributes_to_equality: true`, etc.
- `Coordination` — field is excluded from equality, hashing, and copy. `contributes_to_equality: false`, etc.

**Compatibility.** The compiler enforces obvious contradictions: `immutable mutable`, `runtime compile_time`, `identity coordination`. Composing these produces a clear error.

Some compositions are legal but semantically meaningless: `compile_time weak`, `identity weak` on a class field. The compiler warns rather than errors, on the principle that intent-not-authority extends to the compiler's judgments about "good" combinations.

#### 4.10.2 Aliases *(Stage 1)*

Aliases are language-provided names for common primitive compositions. They are declared using the `alias` construct (§4.16). **Aliases are pure textual expansion** — they add no semantics beyond the primitives they name.

**Built-in aliases:**

```
alias data: runtime immutable identity
alias state: runtime mutable coordination
alias static: compile_time immutable
alias methods: runtime  # implicit container for method declarations
```

Notice none of the built-in aliases include `expose`. Visibility is a separate axis from existence, mutability, and identity contribution. Users add `expose` at the section header when they want the members visible to importers.

Usage:

```
class Order:
    expose data:               # runtime immutable identity, exposed
        id: OrderID
        items: [Item]
        placed_at: Timestamp
    
    data:                      # runtime immutable identity, internal-by-convention
        internal_hash: Bytes
    
    expose state:              # runtime mutable coordination, exposed
        status: OrderStatus
    
    state:                     # runtime mutable coordination, internal
        cached_total: Money?
    
    static:                    # compile_time immutable, internal
        MAX_ITEMS: Int = 100
    
    expose methods:
        function ship(self) throws ShipError: ...
    
    methods:
        function _recompute_total(self): ...
```

**Aliases do not override.** Composing an alias with additional primitives is equivalent to composing the alias's expansion with those primitives. Contradictions produce compile errors, not silent overrides. Since visibility is separate from the data alias's expansion, `expose data:` composes cleanly — it means "runtime immutable identity, plus exposed."

Users who prefer shorter forms can define composition aliases for their common patterns:

```
alias exposed_data: runtime immutable identity expose
alias exposed_state: runtime mutable coordination expose

class Order:
    exposed_data:
        id: OrderID
    
    data:                    # absent expose — internal by convention
        internal_signature: Bytes
```

#### 4.10.3 Methods Section

The implicit section for undecorated method declarations in class and struct bodies:

```
section class Methods:
    data:
        contains: methods
        scope: [class_body, struct_body]
        composition_mode: nested    # methods can be wrapped by traced:, logged:, etc.
```

Applies no transformations — an identity container. Methods appearing directly in a class body without an enclosing section are implicitly wrapped in a `methods:` section (§4.7).

#### 4.10.4 Behavioral Sections *(Stage 1)*

Sections with runtime state, generators, or transformation power. Compose via nesting.

##### 4.10.4.1 Computed

Read-only derived properties computed from other fields on access.

```
section class Computed:
    data:
        contains: fields
        scope: [class_body, struct_body]
        composition_mode: nested
        default_visibility: public
        contributes_to_equality: false
        contributes_to_hash: false
        contributes_to_copy: false
        incompatible_with: [Mutable, Persistent, Channel]
    
    generator field_getter(field: FieldSpec) -> Code:
        return quote:
            function ${field.name}(self) -> ${field.type}:
                return ${field.default_expression}
    
    generator field_setter(field: FieldSpec) -> Code?:
        return @compile_error_setter(
            "cannot assign to computed field '${field.name}'")
```

Usage:

```
class Circle:
    data:
        radius: Float
    
    computed:
        area: Float = 3.14159 * self.radius ** 2
        circumference: Float = 2 * 3.14159 * self.radius
```

Computed fields recompute on every access. Users who want caching wrap with a library `Cache` section or memoize manually.

##### 4.10.4.2 Deprecated

Marks contained members as deprecated with migration guidance and tooling integration.

```
section class Deprecated:
    data:
        contains: methods
        scope: [class_body, struct_body, module_body]
        composition_mode: nested
        parameters:
            reason: String
            since_version: String
            removed_in_version: String?
    
    generator method_wrapper(method: MethodSpec) -> Code:
        return quote:
            @deprecation_warning(
                reason: ${self.parameters.reason},
                since: ${self.parameters.since_version},
                removed_in: ${self.parameters.removed_in_version})
            function ${method.name}(${method.parameters}) -> ${method.return_type}:
                ${method.body}
```

Usage:

```
class OldAPI:
    methods:
        function new_method(self):
            ...
    
    deprecated(reason: "Use new_method instead", since_version: "2.0"):
        function old_method(self):
            ...
```

Callers of `old_method` receive compiler warnings. Tooling surfaces the deprecation reason and migration path.

##### 4.10.4.3 Category

Extends an existing class with additional methods. Works on both Corvid and Objective-C classes uniformly — the runtime does not distinguish.

```
section class Category:
    data:
        contains: methods
        scope: [module_body]
        composition_mode: nested
        parameters:
            target_class: ClassSpec
    
    generator method_wrapper(method: MethodSpec) -> Code:
        # Method is added to target_class's runtime method table.
        return quote:
            @runtime_add_method(class: ${self.parameters.target_class})
            function ${method.name}(${method.parameters}) -> ${method.return_type}:
                ${method.body}
```

Usage:

```
category(target_class: NSString):
    function shout(self) -> NSString:
        return self.upper_case
    
    function is_empty_or_whitespace(self) -> Bool:
        return self.trimmed.length == 0
```

The methods are added to `NSString` at load time via the runtime's `class_addMethod` API. From either language, `str.shout()` (Corvid) or `[str shout]` (ObjC) works on any `NSString` instance.

**Automatic devirtualization** — if the compiler can prove the category's target class is not further extended in the compilation unit, the added methods can be direct-dispatched. Deopt machinery reverts to dynamic dispatch if runtime modification appears later. This is the same optimization pass that applies to all methods (§5.2); categories are not special.

**Categories participate in reflection.** `class_getMethodList(NSString)` returns all methods, including those added by Corvid categories. Category origin is tracked in the runtime for debugging tools.

##### 4.10.4.4 ObjC_Exposed

Explicit control over which methods are exposed to Objective-C callers, and in what form. Alternative to auto-derivation (§9.3.3) for public APIs.

```
section class ObjC_Exposed:
    data:
        contains: methods
        scope: [class_body, struct_body]
        composition_mode: nested
    
    generator method_wrapper(method: MethodSpec) -> Code:
        # Method is emitted with ObjC-compatible signature in generated header.
        # Corvid callers see the original signature.
        return quote:
            @objc_visible
            @generate_header_entry(signature: ${method.objc_signature()})
            function ${method.name}(${method.parameters}) -> ${method.return_type}:
                ${method.body}
```

Usage:

```
expose class OrderProcessor:
    data:
        id: ProcessorID
    
    # Auto-derived for ObjC — visible with default ObjC translation
    methods:
        function get_status(self) -> String: ...
    
    # Explicitly ObjC-exposed with controlled signature
    objc_exposed:
        function processOrder(self, order: Order, error: NSError**) -> Bool: ...
        function cancelOrder(self, orderID: OrderID) -> Bool: ...
```

Methods in `objc_exposed:` sections replace their auto-derived ObjC signature with the explicitly written form. Useful when the auto-derived signature doesn't match ObjC calling conventions (e.g., preferring `NSError **` out-params over `Result<T, E>` returns for consistency with existing ObjC APIs).

Methods NOT in `objc_exposed:` are still auto-derived from the class's normal `methods:` section unless the user explicitly opts out via a class-level annotation (deferred to Stage 2).

##### 4.10.4.5 With

Scoped resource management. Contents are statements including resource-binding declarations; the section ensures deinitializers run in reverse declaration order when the scope exits (RAII pattern).

```
section class With:
    data:
        contains: statements
        scope: [function_body, method_body]
        composition_mode: nested
    
    processor process(contents: Block) -> Block:
        # For each variable declaration whose type conforms to Resource,
        # register a scope-exit handler that calls the destructor.
        # Handlers run in reverse declaration order.
        # Handlers run even if the block exits via error propagation.
        return contents.with_scope_exit_handlers()
```

Usage:

```
function process_file(path: String) throws IOError -> String:
    with:
        f = File.open(path)             # File conforms to Resource
        buffer = Buffer.allocate(1024)
        
        contents = f.read()
        buffer.append(contents)
        return buffer.to_string()
    # buffer's destructor runs, then f's destructor runs
    # Cleanup runs even if any operation throws
```

Resources bound in a `with:` block are automatically cleaned up on scope exit. Unlike Python's `with` (which requires context manager objects) or Java's try-with-resources, Corvid's `with:` works with any type that has a destructor, and multiple resources bind naturally in one block.

#### 4.10.5 Behavioral Sections *(Stage 3)* — Statement-Scope

##### 4.10.5.1 Pure

Restricts contained code to pure computation — no I/O, no mutation of external state, no runtime dispatch of unknown methods.

```
section class Pure:
    data:
        contains: statements
        scope: [function_body, method_body]
        composition_mode: nested
        incompatible_with: [Unsafe]
    
    validator check(contents: Block):
        for statement in contents:
            if statement.has_effect(.io, .external_mutation, .unknown_dispatch):
                @compile_error(
                    "pure section cannot contain '${statement.effect_name}' at ${statement.location}")
```

Usage:

```
function compute(x: Int) -> Int:
    pure:
        result = x * 2 + 1
        result = result * result
    return result
```

##### 4.10.5.2 Unsafe

Enables restricted operations: raw pointer dereferencing, uninitialized memory access, calling unsafe functions.

```
section class Unsafe:
    data:
        contains: statements
        scope: [function_body, method_body]
        composition_mode: nested
        incompatible_with: [Pure]
    
    processor process(contents: Block) -> Block:
        contents.enable_capability(.raw_pointer_access)
        contents.enable_capability(.uninitialized_memory)
        contents.enable_capability(.unsafe_function_calls)
        return contents
```

##### 4.10.5.3 Validate

Precondition and assertion blocks at method prologues.

```
section class Validate:
    data:
        contains: statements
        scope: [method_body, function_body]
        composition_mode: nested
    
    processor process(contents: Block) -> Block:
        return quote:
            for check in contents:
                if not ${check.expression}:
                    @raise_contract_violation(
                        "${check.expression_source}",
                        location: ${check.location})
```

Usage:

```
function process(self, order: Order) -> Receipt:
    validate:
        require order.is_valid()
        require order.total > 0
    
    # ... method body ...
```

##### 4.10.5.4 Quote

AST construction. Available inside generator bodies from Stage 1; generalized to any expression context in Stage 3.

```
section class Quote:
    data:
        contains: ast_fragment
        scope: [expression_context]
        composition_mode: nested
        returns: Code
    
    processor process(contents: AST) -> Code:
        return Code.from_ast(contents)
```

Splice values from the surrounding scope via `${expression}`.

#### 4.10.6 Behavioral Sections *(Stage 4)* — Module-Scope

##### 4.10.6.1 Interop

Enables C ABI features within the module.

```
section class Interop:
    data:
        contains: declarations
        scope: [module_body]
        composition_mode: nested
    
    processor process(contents: Block) -> Block:
        contents.enable_capability(.c_abi_functions)
        contents.enable_capability(.raw_type_layout)
        return contents
```

##### 4.10.6.2 Test

Test-only declarations, excluded from release builds.

```
section class Test:
    data:
        contains: declarations
        scope: [module_body]
        composition_mode: nested
    
    processor process(contents: Block) -> Block:
        if compile_mode == .release:
            return Block.empty()
        return contents
```

##### 4.10.6.3 Platform

Conditional compilation by platform.

```
section class Platform:
    data:
        contains: declarations
        scope: [module_body]
        composition_mode: nested
        parameters:
            target: PlatformSpec
    
    processor process(contents: Block) -> Block:
        if compile_target != self.parameters.target:
            return Block.empty()
        return contents
```

#### 4.10.7 Kind Section Classes *(Stage 1)* — `Regular`, `Record`, `Enum`, `Protocol`, `Section`

Class kinds are implemented as built-in section classes. The `metadata: kind: X` entry on a class dispatches to the corresponding section class, which validates the class body and installs kind-specific compilation behavior.

**`Regular`** — the default kind, applied when no `metadata: kind:` is present. Permits all sections. Produces reference-typed classes with universal extents, weak references, dynamic dispatch, and ARC (§6.3).

**`Record`** — value-typed data records.

- **Permits:** `data:`, `methods:`, `class:`, `invariants:`, `computed:`, `metadata:`.
- **Forbids:** `state:`, `channel:`, `signal:`, and any behavioral section requiring per-instance mutable state.
- **Generates:** structural `==` from `data:` fields; `with(...)` modification method; copy-on-assignment.
- **Opts out of:** universal extent registration; weak refs; ARC.
- **Runtime shape:** stack-allocated when scope-local; inline in containers; passed by value.

**`Enum`** — sealed sum types.

- **Permits:** `cases:` (required), `methods:`, `class:`, `metadata:`.
- **Forbids:** `state:`, `data:` at class scope (variants carry their own associated data inside `cases:`).
- **Reads from `metadata:`:** optional `raw_type:` for integer-backed enums.
- **Generates:** for each `case name(field: Type, ...)`, a constructor `EnumName.name(field: value, ...)`. For raw-valued enums (all cases have `= value`), auto-generates `raw_value(self) -> raw_type` and `class function from_raw(value: raw_type) -> Self?`.
- **Requires:** if any case has `= value`, all cases must; the raw type is inferred as the smallest unsigned or signed type that fits all values unless `metadata: raw_type:` overrides.
- **Runtime shape:** discriminated union; tag + associated values.

**`Protocol`** — named contracts, no instantiation.

- **Permits:** `accepts:` (declares required signatures with their contracts), `methods:` (default implementations), `class:`, `metadata:`.
- **Forbids:** `data:`, `state:`, `cases:` (protocols hold no data).
- **Behavior:** structural conformance by default; explicit `class Foo: SomeProtocol:` declaration legal but optional. Runtime is `@protocol` at the ObjC boundary.
- **`methods:` semantics under `kind: protocol`:** each method is a *default implementation* — used when a conforming class does not provide the message. Method signatures alone (without bodies) may also appear in `methods:` and are equivalent to entries in `accepts:` (the compiler folds them).

**`Section`** — §4 metaprogramming section-classes.

- **Permits:** whatever the specific section class's role requires — typically `data:` (for configuration/parameters), `state:` (for section instance state), `generator:`, `processor:`, `validator:`, `describer:`.
- **Runtime shape:** compile-time only. Section classes have no runtime instances at the user-code level; their state exists inside the compiled class metadata (§4.18).
- **Detail:** section-class internals are documented throughout §4 and are the same forms already used for built-in and user-defined sections. `metadata: kind: section` is the surface-level marker; `section class Foo:` is the sugar for it.

**Reserved metadata keys.** The following keys under `metadata:` are reserved by the language:

| Key | Applies to | Meaning |
|---|---|---|
| `kind` | Any class | Selects the kind section class; values from the spec-defined set above |
| `raw_type` | `kind: enum` | Integer type backing enum cases; inferred if omitted |
| `mode` | `channel:` section (§8.6) | `work` \| `broadcast` \| `pub` — coordination semantics |
| `capacity` | `channel:` section | Positive int; if present, channel is bounded |
| `on_full` | `channel:` section | `block` \| `error` \| `drop_newest` \| `drop_oldest`; required when `capacity` is set |
| `topic_fn` | `channel:` section with `mode: pub` | Function from item to topic identifier |

Users may add arbitrary keys under `metadata:` for tool/documentation use (e.g. `since:`, `platforms:`, `stability:`). Non-reserved keys are ignored by the compiler and surfaced via §4.18 class-metadata queries.

#### 4.10.8 The `metadata:` Section *(Stage 1)*

`metadata:` is a user-writable declaration section that carries compile-time metadata about the enclosing class. It is spec-committed as a first-class section and processed by the compiler before any other section on the same class.

**Composition:**

```
class Foo:
    metadata:
        kind: record
        since: "0.6.0"
        platforms: [macos, ios]
    data:
        x: Int
```

**Rules:**

1. **Position.** If present, `metadata:` must appear first in the class body. This is enforced at parse time. Rationale: subsequent sections' legality depends on `kind:` (§4.10.7), so the compiler must see it before processing the rest of the body.
2. **Uniqueness.** At most one `metadata:` section per class.
3. **Reserved keys.** See table in §4.10.7. The compiler validates reserved-key values; unknown values are compile errors. Non-reserved keys are accepted verbatim.
4. **Compile-time only.** `metadata:` entries do not create runtime fields on instances. They populate the class-metadata artifact (§4.18) and are queryable via `Class.by_qualified_name(...).metadata_entry(name)`.
5. **Not required.** A class with no `metadata:` section defaults to `kind: regular`. Explicit `metadata: kind: regular` is legal but rarely written.

**Distinction from §4.18 Class Metadata.** §4.18 describes the runtime-introspectable class-metadata *artifact* that the compiler generates for every class. The `metadata:` section is one of several sources feeding that artifact — user-written declarative entries. Other sources include field types, method signatures, generated members from behavioral sections, provenance, and conformance information. `metadata:` is where the user contributes; §4.18 is where consumers read.

#### 4.10.9 The `on_nil` Section *(Stage 1)*

`on_nil:` is a user-writable declaration section that specifies per-method return values when the receiver is nil. It participates in nil-receiver dispatch (§5.7).

**Composition:**

```
class UserList:
    accepts:
        entries: [User]
    methods:
        function count(self) -> Int:
            return self.entries.length
        function is_empty(self) -> Bool:
            return self.entries.length == 0
        function first(self) -> User:
            return self.entries[0]
    section on_nil:
        count: 0
        is_empty: true
        first: nil          # explicit — otherwise auto-lifts to User? = nil
```

**Rules:**

1. **Entries.** Each entry is `method_name: value_expression`. The `method_name` must match a method declared on the class or one of its conformed protocols. The `value_expression` must be a compile-time constant, and its type must be assignable to the method's declared return type.
2. **Applies to nil-receiver call sites only.** The generated dispatch consults `on_nil` only when the receiver is nil at call time. Non-nil receivers dispatch to the method body as normal.
3. **Legal on any class kind that permits nil.** `kind: regular` classes (reference-typed) may declare `on_nil`. `kind: record`, `kind: enum` (value-typed) reject `on_nil` — value types cannot be nil, the section is meaningless.
4. **Legal on protocols.** A protocol may declare `on_nil` entries. Conformers inherit the entries unless they override them at the class level. Precedence rules per §5.7.
5. **Unlisted methods auto-lift.** Methods not listed in `on_nil` follow default nil-receiver semantics — return-type `T` auto-lifts to `T?`, return-type `Unit` no-ops.
6. **Composition.** `on_nil` composes inline like other sections. A class may declare at most one `on_nil` section directly; multiple protocol-inherited `on_nil` entries for the same method are a compile error unless resolved by a class-level entry (§5.7).

**Stdlib protocols shipping default `on_nil`:**

- **`Collection`** — `count: 0`, `is_empty: true`, `iter: EmptyIterator()`.
- **`String`** — `length: 0`, `is_empty: true`, `chars: EmptyIterator()`.
- **`Numeric`** — no `on_nil` entries. Arithmetic on nil is a type error (§3.6.1); no punning.

**Rationale.** ObjC's nil-messaging gives Corvid safe nil-propagation for free. `on_nil` is the mechanism for the Clojure-style extension: instead of "nil.count() returns nil-lifted-to-Int?", authors can declare "nil.count() returns 0" and get the collection-punning idiom. Because the mechanism is a section (§4), users write it once per class or protocol; it composes with everything else via the standard section rules.

### 4.11 User-Defined Section Classes

Users define section classes using the same syntax as built-in ones. Example — an `Channel` section:

```
section class Channel:
    data:
        contains: fields
        scope: [class_body]
        default_mutability: mutable
        contributes_to_equality: false
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value: ${field.type}):
                old = self._storage.${field.name}
                self._storage.${field.name} = new_value
                self.sections.channel.notify(
                    field: ${field.name.as_string},
                    old: old,
                    new: new_value)
    
    function subscribe(self, callback: (String, Any, Any) -> Unit) -> Subscription:
        subscription = Subscription(callback)
        self.subscribers.append(subscription)
        return subscription
    
    function notify(self, field: String, old: Any, new: Any):
        for sub in self.subscribers:
            sub.callback(field, old, new)
```

### 4.12 Runtime Access to Sections

Every host class instance has an implicit `sections` namespace:

```
counter = Counter()
counter.sections.channel.subscribe(function(name, old, new):
    print("$name: $old → $new")
)
counter.value = 5  # triggers notification via generated setter
```

Sections without runtime state (empty `state:`) are still accessible via `sections` for introspection, but there is no per-instance section object.

The `sections` namespace supports reflection:

```
for section_name in counter.sections.list():
    print(section_name)

fields_in_data = counter.sections.data.fields()  # [FieldSpec]
```

### 4.13 Author Intent, Not Authority

Sections express intent about how code should be structured and processed. They do not create restrictions the runtime enforces.

- A field in a `data:` section is compile-time immutable, but can be modified via reflection (`class_setIvar`) at runtime.
- A section's generated setter can be bypassed by direct reflection access; the observation doesn't fire, but the write succeeds.
- Users can subscribe to a section instance's stream even if the host author didn't expose subscription publicly.
- Runtime code can add methods to section instances via the standard runtime API.

Sections are a code-organization and code-generation mechanism, not a security boundary. The runtime capability principle from §1.2 applies uniformly.

### 4.14 Error Messages and Tooling

Sections generate code; when generated code has errors, the compiler must attribute errors to:
1. The user's usage site (where the section label appears).
2. The specific field or content that caused the error.
3. The section class's generator that produced the error.
4. The specific error in the generated code.

Every generated AST node carries provenance (source location in the generator that produced it plus source location in the user's code that triggered generation). Compiler errors show both the "expanded" view (what the compiler saw after generation) and the "source" view (what the user wrote), with clear navigation between them.

Tooling (IDEs, formatters, linters) understands sections. IDEs can load user-defined section classes to provide accurate autocompletion and diagnostics. This affects the tooling architecture: sections aren't just a compiler feature.

### 4.15 Versioning

Section classes shipped in libraries participate in downstream compilation. Changes to a section class's generated code are breaking changes for host classes that use it.

Section classes declare semantic version compatibility explicitly. Breaking changes require major version bumps. Compilers verify version compatibility at compile time.

*[UNRESOLVED]:* precise versioning scheme.

### 4.16 Aliases

Aliases bind a name to a compile-time substitution. They are the language's general text-substitution mechanism at declaration sites. The `alias` keyword desugars to a section class with only the `expands_to:` capability; different alias categories are section classes with different content types.

Aliases serve four distinct purposes, all expressed through the same construct:

- **Section-composition aliases** — name a composition of section primitives.
- **Type aliases** — shorten a qualified type reference or name a parameterized type.
- **Value aliases** — name a compile-time constant expression.
- **Module aliases** — shorten a qualified module path.

Users write `alias name: substitution`; the compiler determines the category from the substitution's shape.

#### 4.16.1 Section-Composition Aliases

Name a composition of section primitives:

```
alias data: runtime immutable identity
alias state: runtime mutable coordination
alias exposed_data: runtime immutable identity expose
```

Used in section headers:

```
class Order:
    data:              # expands to: runtime immutable identity:
        id: OrderID
```

Under the hood:

```
section class Data:
    data:
        contains: fields
        composition_mode: inline
    
    expands_to: runtime immutable identity
```

#### 4.16.2 Type Aliases

Shorten a qualified type reference or name a parameterized type:

```
alias Order: com.example.orders.Order
alias StringDict<V>: Dict<String, V>
alias Callback<T>: (T, Error?) -> Void
```

Used in type positions:

```
class MyController:
    state:
        current_order: Order              # expands to com.example.orders.Order
        cache: StringDict<CachedValue>    # expands to Dict<String, CachedValue>
        handler: Callback<Receipt>        # expands to (Receipt, Error?) -> Void
```

Under the hood:

```
section class Order:
    data:
        contains: qualified_reference
        composition_mode: inline
    
    expands_to: com.example.orders.Order
```

Type aliases can be parameterized. Parameters are bound at the use site and substituted in the expansion.

#### 4.16.3 Value Aliases

Name a compile-time constant expression:

```
alias BUFFER_SIZE: 4096
alias DEFAULT_TIMEOUT_MS: 30_000
alias GREETING: "Hello, world"
```

Used in expression positions:

```
data:
    buffer: [Byte] = [Byte].filled(size: BUFFER_SIZE)

function timeout() -> Duration:
    return Duration.milliseconds(DEFAULT_TIMEOUT_MS)
```

Under the hood:

```
section class BUFFER_SIZE:
    data:
        contains: expression
        composition_mode: inline
    
    expands_to: 4096
```

Value aliases must be compile-time constants. Runtime-computed values are not permissible in aliases.

#### 4.16.4 Module Aliases

Shorten a qualified module path:

```
alias orders: com.example.orders
alias core: com.example.core
```

Used to qualify names without writing the full module path:

```
class MyController:
    state:
        client: core.HttpClient
        current: orders.Order
```

Under the hood:

```
section class orders:
    data:
        contains: qualified_module_path
        composition_mode: inline
    
    expands_to: com.example.orders
```

#### 4.16.5 Common Rules

All alias categories share these properties:

- **Compile-time substitution.** The compiler replaces the alias name with its expansion during processing.
- **Recursive expansion.** Aliases may reference other aliases; the compiler expands until no aliases remain.
- **Circular aliases are errors.** Cycles are detected at compile time.
- **No runtime effect.** Aliases exist only during compilation. There is no runtime cost.
- **Parameterizable.** All categories support type, effect, and value parameters.
- **Exportable.** Aliases marked `expose` are visible to importers of the containing module.
- **No name conflicts.** An alias name that conflicts with a section class name, an imported name, or a top-level declaration is a compile error.

#### 4.16.6 Built-in Aliases

Language-provided aliases (in the prelude, always available):

```
alias data: runtime immutable identity
alias state: runtime mutable coordination
alias static: compile_time immutable
alias methods: runtime
```

None include `expose`. Visibility is a separate axis added at the section header when needed.

Users may define aliases at module level (all categories) or inside class bodies (section-composition aliases only, for local naming).

#### 4.16.7 Semantics of Alias Composition

Aliases behave exactly as their expansion. Composing an alias with additional primitives or references is equivalent to composing the expansion with those elements — no override precedence, no warnings.

```
data expose:    # expands to: runtime immutable identity expose
                # composes cleanly — visibility is orthogonal to the alias.
```

Users who need a variant of a common alias define their own alias rather than override at usage sites. This keeps aliases transparent shortcuts, not magical substitutions with hidden rules.

#### 4.16.8 Diagnostic Quality

When alias expansion produces an error, the compiler shows both the alias's expansion and the additional context, so users see exactly what went wrong:

```
error: contradictory composition — `immutable` and `mutable` cannot both apply
  --> Order.cor:14:5
   |
14 |     data mutable:
   |     ^^^^^^^^^^^^^
   |
note: `data` expands to `runtime immutable identity`
note: composing with `mutable` produces `runtime immutable identity mutable`
help: define a specialized alias for this pattern:
      alias data_only: runtime immutable identity
```

### 4.17 Metaprogramming Boundaries

The section mechanism is the language's ONE user-facing metaprogramming mechanism. All compile-time user-definable constructs are section classes. This unification is a deliberate design commitment (§1.6, legibility over brevity) — a single mechanism is easier to reason about than several overlapping ones.

**Explicit boundaries — what sections CAN do:**

- Emit code at declared extension points (accessors, lifecycle hooks, method wrappers).
- Transform block contents (statements, declarations, AST fragments).
- Validate block contents against constraints.
- Substitute themselves for other sections via expansion.
- Have runtime state per host instance.
- Have runtime methods.
- Have compile-time parameters (type, effect, value).
- Carry invariants over their own state.

**Explicit boundaries — what sections CANNOT do:**

- **No higher-order sections.** A section cannot take another section as a parameter. Section parameters are types, effects, or values — not sections. (Syntactic space reserved for future extension.)
- **No section-generates-section.** Section generators produce methods and fields on the host class; they cannot produce new section classes.
- **No cross-section inspection.** A section can see its own contents and the host class's public interface; not other sections' internals.
- **No arbitrary compile-time computation.** Sections compute over their declared inputs (fields, block contents, parameters). There is no general-purpose compile-time execution environment.
- **No section-defines-language-primitive.** Sections cannot introduce new class kinds, protocol kinds, or fundamental language forms.

**Why these boundaries.**

Metaprogramming systems that fail — C++ templates evolving into template metaprogramming, Java reflection + annotations + AspectJ, Python descriptors + decorators + metaclasses — fail by accretion. Each new mechanism tries to fix limitations of the previous. Overlapping features multiply. The result is a system that's hard to reason about, hard to debug, and hard to remove features from.

Corvid's answer: **design the boundary once**, ship the mechanism that fits within it, and refuse to expand it incrementally. Future needs that don't fit within sections require an explicit language-level decision, not a library workaround that stretches the mechanism.

**The bootstrap set** (what the compiler ships with hardcoded knowledge of):

- `Data` and `State` section classes (for use inside section class definitions).
- `Methods` section class (implicit container in class bodies).
- Member kinds: `generator`, `processor`, `validator`, `expander`, `function`, `constructor`, `destructor`.
- Block forms: `expands_to:`, `invariants:`, `parameters:`.

Every other section class, including all primitives and behavioral sections, is expressible in the bootstrap.

### 4.18 Class Metadata

Class metadata is a structural description of every class in a compilation unit — its fields, methods, section usages, contracts, invariants, provenance — generated at compile time and consumed by three separate systems:

- **Language server / IDE tooling** at authoring time (LSP protocol).
- **Runtime metadata queries** at program execution time.
- **External tools** (AI assistants, documentation generators, static analyzers) via manifest export.

All three consume the same underlying artifact. The compiler generates it once; each consumer reads it through an interface appropriate to its use case.

#### 4.18.1 Metadata Contents

For each class, the metadata artifact contains:

**Structural information:**
- Fully qualified class name.
- Module and source file.
- Superclass and protocol conformances (declared and inferred).
- Sections used on the class, with parameters if any.
- Fields per section: name, type, mutability, visibility, contribution flags.
- Methods: name, signature, effects, contracts (pre/postconditions).
- Class-level invariants.

**Generated content (from section describers):**
- Methods added by each section, attributed to the generating section.
- Fields added by each section (backing fields, section instance state).
- Section instance methods (what's available at `instance.sections.name`).
- Contracts inherited or synthesized.

**Provenance:**
- Source location of each declaration (file, line, column).
- Source location of each generated member, linking to the generator and the section usage.
- Version information (which section class version produced each generated member).

**Cross-references:**
- Modules this class depends on.
- Classes referenced in this class's field types or method signatures.
- Sections and section classes used.

#### 4.18.2 Consumer 1 — Language Server / IDE

The compiler operates in language-server mode by exposing an LSP interface backed by its incremental compilation state. When a client (VS Code, Zed, Neovim, Xcode) needs information about a class, it queries the compiler; the compiler returns metadata from its current state.

Standard LSP queries answered from metadata:
- **Hover** — type of an identifier, method signatures, contracts.
- **Completion** — methods available on an expression's type, including section instance methods.
- **Go-to-definition** — source location including generated members (jumps to the generator or the field declaration, per user configuration).
- **Find-references** — usages across the workspace.
- **Rename** — safe renames aware of generated code (renaming a field renames the section-generated accessors).
- **Diagnostic** — errors, warnings, contract violations detected at compile time.

Because metadata includes provenance, IDE features can distinguish user-written code from section-generated code, showing generated members with attribution ("added by channel section") and offering navigation to the generator source.

#### 4.18.3 Consumer 2 — Runtime Metadata Queries

At link time, metadata artifacts are loaded as data segments into the compiled binary. The runtime holds an in-memory representation queryable via a typed API:

```
# Class lookup
cls: Class = OrderProcessor.class_metadata
cls_by_name: Class? = Class.by_qualified_name("com.example.orders.OrderProcessor")
all_classes_conforming: [Class] = Class.all_conforming_to(OrderProcessor.protocol_metadata)

# Class structure
sections: [SectionMeta] = cls.sections()
data_fields: [FieldSpec] = cls.section("data").fields()
methods: [MethodMeta] = cls.methods()
invariants: [Invariant] = cls.invariants()

# Section instance access on a specific class instance
channel_instance: SectionInstance = instance.sections.channel
channel_methods: [MethodMeta] = channel_instance.available_methods()

# Method invocation via metadata
method_meta: MethodMeta = cls.method_named("process")
result: Any = method_meta.invoke(instance, [order])

# Contract introspection
conformed: [Protocol] = cls.conformed_protocols()
protocol_methods: [MethodMeta] = protocol.required_methods()
```

**Key property:** the runtime does not generate class structure at runtime. It exposes structure that was baked in at compile time (possibly in a different module, loaded from a different object file). Queries lookup structure; they never create it.

**Method invocation via `method_meta.invoke`** dispatches through the runtime's method table — the same mechanism as any dynamic message send. The method's implementation was compiled; metadata invocation is a typed wrapper over `objc_msgSend`.

**Dynamic method addition** (`class.add_method(selector, implementation)`) remains available as a runtime API for advanced use cases (mocking, tooling, categories loaded from dylibs). The `implementation` argument is a function pointer to compiled code; the runtime associates it with the class. This is class registration, not code generation.

**Explicit non-goals:**
- No runtime creation of new classes with novel field layouts.
- No runtime creation of new method implementations from source strings.
- No `eval` or equivalent.
- No arbitrary structural modification of instances after construction.

These are excluded not because they're impossible on libobjc2 (some are; some aren't) but because they violate the "class structure is compile-time-fixed" principle that makes metadata coherent.

#### 4.18.4 Consumer 3 — External Tools (AI-Manifest Export)

The compiler emits a JSON representation of the metadata as a build artifact (`ModuleName.cor-manifest.json`). This is the metadata in a language-agnostic format for external tools that don't speak LSP or don't want to embed a compiler.

Structure (illustrative):

```json
{
  "module": "com.example.orders",
  "classes": [
    {
      "name": "OrderProcessor",
      "qualified_name": "com.example.orders.OrderProcessor",
      "source_file": "OrderProcessor.cor",
      "source_line": 12,
      "superclass": null,
      "conforms_to": ["OrderProcessor"],
      "sections": {
        "data": {
          "fields": [
            {"name": "id", "type": "ProcessorID", "mutability": "immutable"}
          ]
        },
        "state": {
          "fields": [
            {"name": "pending", "type": "[Order]", "mutability": "mutable"}
          ]
        },
        "channel": {
          "wraps_fields": ["connection_state"],
          "generated_methods": [
            {"name": "set_connection_state", "signature": "(ConnectionState) -> Unit",
             "provenance": "Channel/field_setter"}
          ],
          "section_instance_methods": [
            {"name": "subscribe", "signature": "((String, Any, Any) -> Unit) -> Unit"}
          ]
        }
      },
      "methods": [
        {"name": "process", "signature": "(Order) async throws -> Result<Receipt, OrderError>",
         "contracts": {"requires": ["order.is_valid"]}}
      ],
      "invariants": [
        "connection_state == .opened implies socket != nil"
      ]
    }
  ]
}
```

**Uses:**
- AI code assistants query the manifest to understand code structure without parsing Corvid source.
- Documentation generators produce API docs from the manifest.
- Static analyzers reason about types and contracts without integrating with the compiler.
- Cross-language tooling (e.g., generating TypeScript bindings for a web frontend calling into Corvid backend).

**Format guarantee:** the JSON schema is versioned and stable within a major version. Additive changes (new fields) are minor version bumps; removals or renames are major.

#### 4.18.5 Incremental Compilation

The compiler is architecturally incremental from Stage 1. It holds state between invocations: parsed ASTs, resolved types, expanded sections, generated code, produced metadata. When a source file changes, only affected units recompile.

**Consequences:**
- Language-server queries are cheap. When the user types a `.`, the LSP asks the compiler for available methods on the receiver's type; the compiler answers from cached metadata without re-parsing.
- Section expansion is cached. Changing a class's field list re-expands its sections; changing an unrelated class doesn't.
- Metadata artifacts are updated in place. Consumers see fresh data without full rebuilds.
- Batch compilation is a mode: `compile all sources fresh, no incremental state`. Same code paths, different scheduling.

**Compilation state persistence:**
- In-memory during a language-server session.
- On-disk cache for build systems that reuse state across invocations (analogous to Rust's incremental cache directory, Kotlin's `build/kotlin-classes/incrementalCache`).
- Invalidation: file mtime + content hash. Cross-file dependencies tracked explicitly.

**Stage 1 delivers:** incremental compilation for Corvid sources, LSP server, metadata artifact emission, AI-manifest export. All shipped together — the tooling story is not a Stage 2 concern.

#### 4.18.6 Class Metadata and Author Intent

Metadata surfaces all structural information regardless of visibility annotations. Private fields appear in metadata. Sealed methods appear. Section internal state appears.

This is consistent with the intent-not-authority premise (§1.3): visibility annotations express intent for consumers to respect; they do not gate runtime access. Metadata queries return everything the compiler knows.

Consumers of metadata are expected to respect intent even though the compiler doesn't enforce. An LSP might hide private members from completion by default; a debugger shows them. Both read the same metadata; each uses it appropriately for its context.

### 4.19 Sugar Layer for Class Kinds

Corvid provides surface-syntax sugar for the four non-default class kinds. Sugar is compile-time-only; it desugars losslessly to `class + metadata: kind:` before the rest of the compiler pipeline runs. Every construct expressible in sugar is equivalently expressible in the desugared form; users typically write the sugar.

**Sugar keywords:**

| Keyword | Desugars to | Kind |
|---|---|---|
| `record Foo:` | `class Foo:` + `metadata: kind: record` | Value type |
| `enum Foo:` | `class Foo:` + `metadata: kind: enum` | Sum type |
| `protocol Foo:` | `class Foo:` + `metadata: kind: protocol` | Contract |
| `section class Foo:` | `class Foo:` + `metadata: kind: section` | §4 section class (existing syntax; equivalent) |

**Retired keywords.** `struct` and `enumeration` (v0.5) are retired. Their v0.5 semantics are recovered by `record` and `enum` respectively. Grammar §10 no longer parses them.

**Sugar for `enum` cases and raw values:**

```
# Sugar — inline case declarations:
enum FrameOpCode:
    case continue_frame
    case text_frame
    case connection_close

# Sugar — enum with raw values (compiler infers raw_type: UInt16):
enum CloseCode:
    case normal = 1000
    case protocol_error = 1002
    case encoding = 1007

# Sugar — enum with associated data:
enum WSFrame:
    case text(String)
    case binary(Bytes)
    case close(code: UInt16, reason: String)
```

Desugars to:

```
class FrameOpCode:
    metadata:
        kind: enum
    cases:
        case continue_frame
        case text_frame
        case connection_close

class CloseCode:
    metadata:
        kind: enum
        raw_type: UInt16                 # inferred (smallest fit)
    cases:
        case normal = 1000
        case protocol_error = 1002
        case encoding = 1007
    # + auto-generated raw_value(self) and class function from_raw(value:)

class WSFrame:
    metadata:
        kind: enum
    cases:
        case text(String)
        case binary(Bytes)
        case close(code: UInt16, reason: String)
```

**Sugar for `protocol` requirements and defaults:**

```
# Sugar — per-method accepts and default implementations mixed:
protocol OrderProcessor:
    accepts process(Order) -> Result<Receipt, OrderError>
        throws, async
        requires order.is_valid

    accepts cancel(OrderID) -> Bool
        throws

    function process_batch(self, orders: [Order]) -> [Result<Receipt, OrderError>]:
        return [self.process(o) for o in orders]
```

Desugars to (`accepts` entries collected into a section, methods with bodies collected into `methods:`):

```
class OrderProcessor:
    metadata:
        kind: protocol
    accepts:
        process(Order) -> Result<Receipt, OrderError>
            throws, async
            requires order.is_valid
        cancel(OrderID) -> Bool
            throws
    methods:
        function process_batch(self, orders: [Order]) -> [Result<Receipt, OrderError>]:
            return [self.process(o) for o in orders]
```

**Sugar is lossless.** Every sugared form and its desugared form produce identical class metadata, generated members, and runtime behavior. Tooling always operates on the desugared form; error messages and generated code reference the desugared form; the sugar keyword is preserved only in source-location provenance.

**Explicit form always legal.** Users may write the desugared form directly for any of these kinds. This is useful when:
- Additional `metadata:` entries beyond `kind:` are needed (e.g., `since:`, `platforms:`).
- Explicit `metadata: raw_type:` overrides the inferred type on an enum.
- The class body composes with a `metadata:` section for tool integration.

**No user-defined sugar.** Users cannot introduce new sugar keywords. Extensions to the sugar layer require spec commitment. Rationale: sugar changes the shape of what readers see; introducing project-local sugar would fragment reading conventions across codebases. Users needing declarative shorthand for common patterns use `alias` (§4.16) instead.

---

## 5. Dispatch Model

### 5.1 Semantic Dispatch (Foundational)

All methods are semantically dynamically dispatchable at runtime. The runtime maintains method tables for every class. Any method can be observed, replaced, extended, or hooked via the runtime API. This is the ObjC runtime substrate carried forward without modification.

### 5.2 Compilation Strategy

The compiler may lower a method call to direct dispatch or dynamic dispatch. The choice is a performance optimization; semantics are identical provided runtime state matches compile-time assumptions.

The compiler devirtualizes automatically wherever it can prove safety:
- Same-module calls where the target is unambiguous.
- Whole-module optimization when configured.
- Cross-module calls in Stage 3+ via deopt-backed optimistic compilation.

**No source-level dispatch annotations.** The current spec had `sealed:` and `dynamic:` as author-facing hints. Both are removed. Reasons:

- `sealed` claimed "no subclassing / no overriding" — a claim the runtime doesn't enforce. Categories, swizzling, and dynamic proxies all remain available regardless. The keyword named an unenforceable prohibition, which under §1.2 (intent-not-authority) is dishonest naming for what was really an optimization request.
- `dynamic` was meaningful only in contrast to `sealed`. Without sealed, everything is already dispatched dynamically at the semantic level; the keyword is redundant.

Devirtualization is the compiler's responsibility, not the author's declaration. Where authors want to communicate "this class isn't designed for extension," they write documentation comments.

### 5.3 Deopt Machinery

When the compiler emits an optimistic direct call and runtime state later diverges (a method is swizzled, a category adds a competing implementation, a subclass overrides), the runtime patches affected call sites back to dynamic dispatch. The user-visible semantics are preserved; the performance changes locally.

Deopt operates on individual call sites, not whole methods or classes. A method may be devirtualized at one call site and dispatched dynamically at another. The compiler's optimism at each site is independent.

### 5.4 Optimization Policy

Optimization policy (how aggressively to devirtualize, how much whole-program analysis to perform, how much deopt overhead to accept) is a build-tool concern, not a source-language concern. Users configure this via build flags or package manifest settings; source code doesn't carry annotations for it.

Rationale: optimization policy is a deployment decision, not a semantic property of the code. Development builds might prefer flexibility (all dynamic dispatch, no devirtualization); release builds might prefer speed (aggressive devirtualization with deopt backing). The same source code compiles both ways.

### 5.5 Guarantees Summary

| Property | Guaranteed |
|---|---|
| A user can always hook any method | Yes |
| A user can always add methods to any class | Yes |
| A user can always subclass any class | Yes |
| A user can always introspect any class or method | Yes |
| Devirtualization is preserved when no runtime modification occurs | Yes (v0.5+) |
| Runtime modification triggers deopt on affected call sites | Yes (v1.0+) |
| Compiled code is safe from author-declared "sealing" | N/A — no such annotation exists |
| Members marked non-exposed are unreachable | No — external access generates warnings, not errors |

### 5.6 Closures and Callable Values

A **closure** in Corvid is an anonymous class with a single `call` method. Closures are first-class values; they can be passed as arguments, returned from functions, stored in fields, and dispatched dynamically like any other class instance. The runtime representation at the ObjC interop boundary is an ObjC block; the compiler generates the block wrapper automatically.

Semantically, closures are not a separate construct — they inherit all the properties of the class-as-universal design (§6.1). Kind is `regular` (the default) for closures that need to escape their construction site; the compiler is free to specialize to `kind: record` or eliminate the class entirely when analysis proves the closure does not escape.

#### 5.6.1 Type Expression — `Callable<Args, Return>`

Function types (§3.2) desugar to the wrapper type `Callable<Args, Return>`:

```
handler: (Bytes) -> Int                 # sugar
handler: Callable<(Bytes,), Int>        # expanded

comparator: (String, String) -> Int     # sugar
comparator: Callable<(String, String), Int>  # expanded
```

`Callable<Args, Return>` is a standard-library type; `(...) -> R` is spec-committed sugar. Effects on the callable use the same `with` clause as method types (§3.5.3):

```
f: (Order) -> Receipt with throws OrderError
f: Callable<(Order,), Receipt> with throws OrderError
```

#### 5.6.2 Sugar Hierarchy for Closure Literals

**Explicit function-literal form** — full types, unambiguous.

```
array.map(function(x: Int) -> Int:
    return x + 1
)
```

**Arrow form** — inferred parameter and return types, single-line body.

```
array.map(x => x + 1)
array.sorted((a, b) => a.count > b.count)
first_upper = names.first(where: n => n.starts_with_uppercase())
```

Multi-parameter form uses parentheses on the left of `=>`. Zero-parameter form: `() => value`. Ignored parameter: `_ => 42`.

**Method reference** — pass an existing method by name.

```
class Processor:
    methods:
        function transform(self, x: Int) -> Int: return x * 2
        function transform_all(self):
            self.numbers.map(self.transform)   # method reference
```

Method references bind `self` at the reference site — `self.transform` is a `Callable<(Int,), Int>` with `self` already captured.

**Trailing-block form is not supported.** Swift's `array.map { $0 + 1 }` is not part of the sugar layer. Rationale: the arrow form is as terse without the parsing complexity of "closure body outside parens." If a hard-line preference emerges, it can be added later as additive sugar.

#### 5.6.3 Desugaring — Closure Literals to Anonymous Classes

Every closure literal desugars, at compile time, to an anonymous class whose only method is `call`. The compiler generates one class per unique closure body at a source location; different literals at different sites are different classes.

Sugar:
```
array.map(x => x + 1)
```

Desugared (illustrative; the anonymous class name is compiler-generated):

```
class __closure_at_L47:
    methods:
        function call(self, x: Int) -> Int:
            return x + 1

array.map(__closure_at_L47())
```

Closures with captured environments carry those captures as `data:` fields on the anonymous class:

```
# Source:
threshold = 5
filtered = array.filter(x => x > threshold)

# Desugared:
class __closure_at_L52:
    data:
        threshold: Int
    methods:
        function call(self, x: Int) -> Bool:
            return x > self.threshold

filtered = array.filter(__closure_at_L52(threshold: threshold))
```

Stateful closures (rare) use `state:` on the anonymous class:

```
# Source — counter closure:
counter = do:
    n: Int = 0
    () => do:
        n = n + 1
        n

# Desugared:
class __closure_at_L60:
    state:
        n: Int = 0
    methods:
        function call(self) -> Int:
            self.n = self.n + 1
            return self.n

counter = __closure_at_L60()
```

#### 5.6.4 Capture Rules

**Strong capture by default.** A closure captures any free variable it references, with a strong reference. This is the common case — the closure keeps its captured environment alive.

```
config = load_config()
handler = event => process(event, config)   # config captured strongly
# handler holds config alive as long as handler exists
```

**Explicit capture list for non-default capture modes.** Prefix the closure literal with `[weak name, unowned name, name: mode]` when a variable should be captured differently:

```
# Weak self capture — closure does not keep self alive:
subscription = channel.subscribe([weak self] event => self.handle(event))
# When self has been deallocated (weak → nil), the message send is a no-op (§3.6.1).
```

Multiple entries are comma-separated. Legal modes: `weak`, `unowned`, `copy` (for value types).

**Rules:**

1. **Non-escaping closures never need `[weak]`.** A closure passed to `map`/`filter`/`sorted`/etc. and consumed synchronously by the callee cannot outlive the current frame. The compiler recognizes non-escaping closures and elides the capture-list requirement.
2. **Escaping closures may.** A closure stored in a field, returned from a function, or passed to a callee that stores it (e.g., channel subscribers, delegates) can outlive its construction context. `[weak self]` is required to avoid retain cycles when the closure captures `self` and is stored on `self` transitively.
3. **The compiler warns on likely retain cycles.** If a closure captures `self` strongly AND is stored in a `state:` field of `self` (directly or through a channel subscription), the compiler emits a warning. Not an error — sometimes intentional — but flagged.

#### 5.6.5 Compilation and Runtime Cost

The compiler is free to specialize closure representations under the "as if" rule — semantic behavior is preserved, runtime shape is optimized:

| Situation | Runtime cost |
|---|---|
| Closure passed to an inlinable function (map/filter/sorted/etc.), no capture | Zero — inlined at call site |
| Closure with capture, inlinable call site | Captures unpacked into local vars; closure object elided |
| Escaping closure (stored, returned, passed to opaque callee) | One anonymous-class allocation + virtual `call` dispatch |
| Closure passed across ObjC boundary | Wrapped as an ObjC block; standard block calling convention |

Users write the sugar; the compiler chooses the representation. The `Callable<Args, Return>` wrapper type is what appears in type signatures; the concrete anonymous class is a compiler artifact.

### 5.7 Nil-Receiver Dispatch

Message sends on nil receivers are legal (§3.6.1). This section specifies how the runtime resolves them.

**Lookup order for a call `receiver.method(args)` when `receiver` is nil:**

1. **Class `on_nil` section (§4.10.9).** If the receiver's static type declares `section on_nil:` with an entry for `method`, that entry's value is returned. No method body runs.
2. **Conformed protocols' `on_nil` sections.** If no class-level entry matches, walk the receiver type's conformed protocols in declaration order; the first protocol declaring `method` in its `on_nil` section supplies the return.
3. **Auto-lift default.** If no `on_nil` entry is found, the runtime returns nil, and the call-site expression type auto-lifts from `Return` to `Return?`. For methods returning `Unit`, the call is a silent no-op (expression type remains `Unit`).

**Class overrides protocol.** A class-level `on_nil` entry shadows the same entry inherited via protocol conformance. This mirrors normal method dispatch precedence.

**Multiple protocols supplying the same entry.** Ambiguity is a compile error at conformance-declaration time — the class must declare a class-level `on_nil` entry to disambiguate. The compiler surfaces which protocols conflicted and the offending method name.

**Codegen consequences.**

- If no `on_nil` entry exists anywhere in the receiver type's hierarchy for the called method, no runtime branch is generated. Message send goes through the ObjC runtime directly; runtime returns nil natively (no Corvid-generated branch).
- If an `on_nil` entry exists, the compiler generates a nil-check-and-substitute at the call site (fast path) or a runtime consultation (slow path, when dynamic dispatch precludes static resolution).
- Auto-lift is a type-system operation only, not runtime. The bytecode is identical to the non-nil case; the type checker adjusts the observed return type.

**Interaction with `!` methods.** A method declared to return `T!` (non-nil reference) on nil receiver returns nil regardless — but this observation triggers the `T!` invariant warning at the call site. Authors declaring `T!`-return methods should also declare `on_nil` entries to preserve the invariant on nil-receiver call sites.

**Example dispatch trace:**

```
protocol Collection:
    accepts count() -> Int
    accepts is_empty() -> Bool
    section on_nil:
        count: 0
        is_empty: true

class UserList: Collection:
    accepts:
        entries: [User]
    methods:
        function count(self) -> Int:
            return self.entries.length
        function is_empty(self) -> Bool:
            return self.entries.length == 0
    section on_nil:
        count: 0        # matches protocol; explicit for clarity
        # is_empty: not declared → inherits protocol's `true`

users: UserList = maybe_get_users()   # may be nil

n = users.count()        # nil → class on_nil returns 0. type: Int
empty = users.is_empty() # nil → protocol on_nil returns true. type: Bool
name = users.first_name  # no on_nil entry; auto-lift. type: String?
```

---

## 6. Memory Model

The memory model organizes around **class kinds** — variants of a single unified `class` construct differentiated by the `metadata: kind:` entry — plus a universal handle-based reference model for identity-typed classes, extent-based storage with tag-organized regions, and ARC as the reference lifetime mechanism. This chapter details each kind and how they compose.

### 6.1 Class Kinds

All user types are declared via `class`. The `metadata: kind:` section entry (§4.10.6) selects the variant. Five kinds are spec-defined:

| `kind:` value | Purpose | `state:` allowed? | Extent? | Semantics |
|---|---|---|---|---|
| `regular` (default) | Reference-typed identity object | Yes | Yes | Reference on assignment; dynamic dispatch; ARC-managed |
| `record` | Value-typed data record | No | No | Copy on assignment; structural equality; static dispatch |
| `enum` | Sealed sum type | No | No | Discriminated union of `cases:`; exhaustive matching |
| `protocol` | Named contract, no instantiation | N/A | N/A | Structural conformance; `accepts:` required, `methods:` defaults |
| `section` | §4 metaprogramming section-class | Per kind's own rules | No | Compile-time only, no runtime artifact |

Sugar keywords (`record`, `enum`, `protocol`, `section`) desugar losslessly to the corresponding `class + metadata: kind:` form (§4.11). Users typically write the sugar; tooling and generated code operate on the desugared form.

**The "no `state:`" restriction on record and enum is the source of their value semantics.** Without mutable state, there's nothing to share across bindings — value semantics falls out. Without runtime-varying behavior, methods can dispatch statically. Without runtime identity, no extent tracking is meaningful. Without shared references, no ARC is needed. The kind selector is the switch that opts in or out of all four properties together.

**Kind determines section legality.** The compiler validates that only sections legal for a class's kind appear in its body:
- `kind: protocol` + `state:` → error (protocols have no runtime state)
- `kind: record` + `state:` → error (records forbid mutable state)
- `kind: enum` + `state:` → error; `enum` uses `cases:` for its variants
- `kind: regular` + `cases:` → error (cases belong to `kind: enum`)
- `kind: section` — validated per the section-class rules in §4

Validation occurs at compile time (§4 section-class implementations enforce their kind's rules). Error messages name the kind and the offending section.

### 6.2 Value-Typed Class Runtime Models — `kind: record` and `kind: enum`

Classes with `kind: record` and `kind: enum` are value types with these properties:

**Storage:**
- Stack-allocated when scoped to a function.
- Inline in containers (an `[Vec2]` array holds Vec2 values directly, packed).
- Inline in class fields (a class with `record` fields holds them contiguously in the class's storage).
- Passed by value on function calls (may be optimized to pass-by-reference for large records at ABI level; semantic model is copy).

**Assignment:**
- Copies the entire value.
- Two bindings of the same record value are independent; mutating one does not affect the other.

**Mutation:**
- Records are immutable to their bindings by default when `immutable` is applied to the binding.
- A `mutable` binding permits reassigning the binding to a new record value.
- Individual fields of a record cannot be modified in place through a binding; users construct a new record with the desired changes.

**"Modification" pattern:**
```
p1: Vec2 = Vec2(x: 1.0, y: 2.0)
p2: Vec2 = p1.with(x: 5.0)     # new Vec2, p1 unchanged
```

Records may expose a `with(field: value, ...)` method (auto-generated by the compiler from the class's data fields) that returns a new instance with the specified fields replaced. This is the primary modification pattern.

**Dispatch:**
- Record methods dispatch statically. No method table, no `objc_msgSend`.
- Compiler generates direct calls, allowing inlining and optimization.
- Record methods cannot be swizzled (no runtime method table exists for records).

**Identity:**
- Records have only structural identity — `==` compares field values.
- There is no `===` (identity comparison) for records; the concept doesn't apply.

**Memory management:**
- No ARC. Records live where they're stored; when their scope ends or the containing object is freed, they go with it.
- No heap allocation implied by the type. A record held in a class field lives in the class's heap allocation; a record in a local variable lives on the stack.

**`kind: enum`** additionally provides:
- Variants declared in the `cases:` section: `Result.ok(value: x)`, `Result.err(error: e)`.
- Exhaustive matching via `switch` (§3.7).
- Discriminated storage: the runtime representation is a tag + associated values.
- Optional integer backing via `metadata: raw_type:` — when specified (or inferred from `case name = value` syntax), the compiler auto-generates `raw_value(self) -> <raw_type>` and `class function from_raw(value:) -> Self?`. See §3.3 and §4.11 for sugar.

### 6.3 Class Runtime Model

Classes are reference types with these properties:

**Storage:**
- Heap-allocated in per-class extents (§6.4).
- Instances persist as long as any strong reference (handle) points to them.
- Storage layout is runtime-managed within the extent.

**Assignment:**
- Copies the handle, not the target. Two bindings to the same class instance share state.
- Mutation through one binding is visible through the other.

**Mutation:**
- `state:` fields are mutable through the reference.
- `data:` fields are immutable after construction.
- Handle indirection means the compiler cannot assume field values remain stable across method calls (aliasing is possible).

**Dispatch:**
- Methods dispatch dynamically through the runtime method table (`objc_msgSend`).
- Compiler devirtualizes automatically where safe; call sites can be deopted on runtime modification (§5).

**Identity:**
- Class instances have runtime identity independent of field values.
- `==` compares equality (per the class's semantics, typically field-based); `===` compares identity (same instance).
- Two classes with identical field values may still be different instances.

**Memory management:**
- ARC: strong references (handles) increment a reference count on the target. Dropping a reference decrements. Reaching zero triggers destruction.
- `weak` and `unowned` references (§6.7) do not participate in the ref count.
- Cycles remain possible; users mark cycle-breaking references as `weak`.

### 6.4 Extents and the Query API

Every user-declared class has an **extent**: the set of all live instances of that class. Extents are implicit; users do not declare them.

Compiler-generated classes (closures, iterator states, generator frames, section internal state) do not participate in extents. These are compiler machinery, not user constructs.

**Extent structure:**

An extent is the union of contiguous storage regions:
- A **default region** for untagged instances.
- One region per **tag** (§6.5) referencing this class.

Each region maintains its own storage layout. Iteration walks a region contiguously.

**Query API on class metadata:**

```
# Every user class supports these operations:
Order.all() -> Snapshot<Order>                            # every live Order instance
Order.count() -> Int                                       # number of live instances
Order.query(where: (Order) -> Bool) -> Snapshot<Order>    # filtered snapshot
Order.query(where: ..., sort_by: ...) -> Snapshot<Order>  # filtered and sorted
Order.by_id(id: OrderID) -> Order?                        # lookup by identity field
Order.exists(id: OrderID) -> Bool                          # existence check
```

**Query API on protocol metadata:**

```
# Protocols support queries over all conformers:
Renderable.query() -> Snapshot<Renderable>                    # every instance of any conforming class
Renderable.query(where: ...) -> Snapshot<Renderable>          # filtered
(Position, Velocity).query() -> Snapshot<...>                 # multi-protocol query
```

Multi-protocol queries iterate all classes conforming to all listed protocols. The resulting snapshot holds handles typed as the intersection contract (usable as any of the queried protocols).

### 6.5 Handles

Every class reference is a **handle**: an indexed reference into the target's extent. Handles carry:

- The target's extent (implicit via the handle type).
- The target's identity (position in the extent).
- A generation counter for use-after-free detection.

**Handle operations:**

- **Dereference:** access fields or call methods. The runtime resolves the handle to the current storage location, validates the generation counter, and performs the access. On mismatch, raises a use-after-free error (in checked builds).
- **ARC:** creating a strong handle increments the target's reference count; dropping it decrements.
- **Equality:** two handles compare `===` equal if they refer to the same instance.

**Compiler optimizations:**

For handles whose lifetime is scope-local and provably valid, the compiler eliminates:
- Generation checks (validation).
- Extent lookup indirection (uses a direct pointer).
- ARC operations (when the reference count would remain positive throughout the scope).

The result is that scope-local reference access has zero overhead beyond a normal pointer dereference. Cross-scope references pay the full handle cost.

**FilC-style safety:**

Handle validation catches:
- Use after the target has been destroyed.
- Access to instances relocated within the extent (rare, but possible under storage layout changes).
- Cross-extent references where the target extent has been freed.

Users can disable validation in `unsafe:` sections (§4.10.5.2) when profiling shows it as overhead in hot paths.

### 6.6 Tags and Snapshots

Two collection types organize class instances:

**`Tag<T>` — named persistent subset of T's extent.**

Tags own a contiguous storage region within their target class's extent. Instances tagged with a `Tag<Order>` live in that tag's storage region.

```
pending: Tag<Order> = Tag.new("pending")
shipped: Tag<Order> = Tag.new("shipped")

order = Order(id: OrderID.new(), items: [...])
pending.insert(order)          # order now lives in pending's storage region

# Later, transition the order
pending.remove(order)
shipped.insert(order)          # order physically relocated to shipped's region

# Tag iteration is contiguous
for o in pending.query():
    process(o)                 # cache-optimal iteration
```

**Tag semantics:**
- Tag membership counts as a strong reference for ARC purposes.
- Removing from a tag drops the reference; if it was the last, the instance is destroyed.
- Moving between tags relocates storage physically (handles remain valid via indirection).
- Tags can be composed via set operations: `pending.union(shipped)`, `pending.intersect(customer_a)`. Results are snapshots.

**Contiguous storage benefit:**
- Iteration over a tag is a linear walk of contiguous memory.
- SoA layout (§6.7) enables SIMD-friendly access patterns.
- Cache locality is excellent for tag-scoped operations.

**`Snapshot<T>` — materialized query result.**

Snapshots hold handles into wherever their instances live (default region, various tags, other classes' extents via protocol queries).

```
top_orders: Snapshot<Order> = Order.query(where: self.total > 1000)
                                   .sort_by(self.total)
                                   .take(10)

for o in top_orders:
    process(o)
```

**Snapshot semantics:**
- Handles held in a snapshot count as strong references.
- Dropping the snapshot drops those references.
- Snapshots do not have contiguous storage; iteration follows handles to their actual storage locations.
- Snapshots are typically used for local, ephemeral queries.

**When to use which:**
- Persistent grouping with lifetime implications → `Tag<T>`.
- Cache-critical iteration over a stable subset → `Tag<T>` (contiguous storage).
- One-off queries, filters, ordered results → `Snapshot<T>`.
- Cross-class or cross-tag queries → `Snapshot<T>` (can't be contiguous by design).

**Array literals `[T]`:**

For non-class T (structs, primitives, enumerations), `[T]` is a packed value array.

For class T, `[T]` is `Snapshot<T>`. The literal syntax constructs a snapshot from the given handles. This preserves the "snapshot for locals, tag for persistence" distinction while giving users familiar syntax.

### 6.7 Storage Layout Hints

The `dense_storage:` section (Stage 1 built-in) hints that a class should use Structure-of-Arrays layout when stored in tags:

```
class Particle:
    dense_storage:
    data:
        color: Color
        radius: Float
    state:
        position: Vec3
        velocity: Vec3
```

Under SoA layout in a tag:
```
Tag<Particle>:
    color:    [Color, Color, Color, ...]     # packed color array
    radius:   [Float, Float, Float, ...]     # packed float array
    position: [Vec3,  Vec3,  Vec3,  ...]     # packed vec3 array
    velocity: [Vec3,  Vec3,  Vec3,  ...]     # packed vec3 array
```

Iteration walks the arrays in parallel; field access loads from the appropriate array. SIMD operations become natural. Cache behavior is optimal for field-parallel processing.

**Default layout:**
Without `dense_storage:`, the runtime uses AoS (Array of Structures) — each instance's fields laid out contiguously in memory, with instances packed sequentially. AoS is better for whole-instance operations; SoA is better for field-parallel operations.

**Runtime decides:**
Both layouts are always available. The runtime picks based on the hint plus observed access patterns (Stage 3+ may include adaptive layout switching). Users write the same code either way.

**Non-tagged instances** (in the default region) use AoS by default. `dense_storage:` primarily affects tagged instances.

### 6.8 Cycles and Cycle Detection

ARC does not automatically collect cycles. Two classes referencing each other via strong handles form a leak.

**Manual cycle breaking:**

Users mark cycle-breaking references as `weak` or `unowned`:

```
class Parent:
    state:
        children: [Child]      # strong

class Child:
    state:
        parent: weak Parent?   # weak — does not retain
```

Weak references become `nil` when the target is destroyed. Unowned references assume the target outlives the reference; violating this assumption is undefined behavior.

**Query-based alternative to bidirectional storage:**

Many cycles in traditional OOP come from bidirectional relationships stored redundantly. The extent model offers an alternative: store one direction, query the other.

```
class Node:
    state:
        parent: Node?           # one direction stored

# Query children instead of storing
function children_of(node: Node) -> Snapshot<Node>:
    return Node.query(where: self.parent === node)
```

No cycle possible — there's no children field to complete the cycle. This pattern generalizes to many owner-owned and category-member relationships.

**Extent-scoped cycle detection (Stage 2+):**

Optional runtime feature. Users mark object graphs as "may cycle"; the collector runs periodically on those graphs. Because it operates per-extent rather than heap-wide, cost is bounded and predictable.

The collector is a library, not a built-in runtime feature. Users who don't need it pay nothing.

### 6.9 Cross-Language Interop

**ObjC classes:**
- Do not participate in Corvid extents. The ObjC runtime does not provide per-class instance tracking suitable for extent-style queries.
- `NSString.query()` and similar calls on ObjC-imported classes return an error at compile time.
- ObjC classes bridged into Corvid are handle-referenced for consistency; the handle wraps the ObjC pointer with validation metadata added by the Corvid runtime layer.
- ARC semantics interoperate: ObjC's ARC and Corvid's ARC operate on the same reference count for shared instances.

**Structs to/from C:**
- Structs with only `data:` fields use standard C layout (identical to a C `struct` with the same field types).
- Passable to C functions directly.
- Bidirectional interop: C structs imported into Corvid as Corvid structs; Corvid structs exported to C as C structs.

**Structs with references to classes (in `data:` fields):**
- Struct copy semantics apply to the struct portion; class references are shared.
- Passing to C: the class reference becomes an opaque pointer (ObjC object).
- Users should be aware that struct value semantics and class reference semantics coexist.

**Enumerations to/from C:**
- Enumerations with only simple cases (no associated values) map to C enums.
- Enumerations with associated values do not have a direct C representation; they're Corvid-only unless the user provides an explicit bridge.

### 6.10 The `with:` Section (RAII)

Deterministic resource cleanup via scoped destructors (§4.10.4.5). Recap for memory-model context:

- Resources bound in a `with:` block are automatically destroyed on scope exit.
- Cleanup runs in reverse declaration order.
- Cleanup runs even if the block exits via error propagation.
- Combines with ARC: if the resource is a class instance, `with:` ensures the last strong reference is dropped when the scope ends, triggering destruction deterministically.

```
function process_file(path: String) throws IOError -> String:
    with:
        f = File.open(path)
        buffer = Buffer.allocate(1024)
        return process(f, buffer)
    # buffer destroyed, then f destroyed
```

`with:` is the standard pattern for file handles, network connections, transactions, locks, and any resource requiring deterministic release.

### 6.11 The `unsafe:` Section

Opt-out from runtime safety checks (§4.10.5.2). Recap for memory-model context:

- Disables handle validation (no generation checks).
- Enables raw pointer operations.
- Permits accessing uninitialized memory.
- Allows calling functions marked `unsafe`.

`unsafe:` is for interop code, hot paths where profiling shows validation overhead, and low-level runtime implementation. Regular application code does not need it.

### 6.12 ECS as an Emergent Pattern

The extent + tag + query mechanism supports Entity-Component-System patterns as a natural coding style, not as a distinct language feature.

**Traditional ECS terminology mapped to Corvid:**

| ECS concept | Corvid mechanism |
|---|---|
| Entity | Class instance |
| Component | Protocol or field-carrying protocol |
| Archetype | Class with specific protocol conformances |
| System | Function taking a protocol query and operating on results |
| Component storage | Tag<T> with dense_storage |
| Archetype query | Protocol query |

**Example:**

```
protocol Positioned:
    accepts position(self) -> Vec3
    accepts set_position(self, p: Vec3)

protocol Moving:
    accepts velocity(self) -> Vec3

class Projectile: Positioned, Moving:
    dense_storage:
    state:
        pos: Vec3
        vel: Vec3
    methods:
        function position(self): return self.pos
        function set_position(self, p): self.pos = p
        function velocity(self): return self.vel

# System: physics update over all moving-positioned entities
function update_physics(dt: Float):
    for entity in (Positioned, Moving).query():
        entity.set_position(entity.position() + entity.velocity() * dt)
```

The compiler statically knows Projectile conforms to both protocols; the query resolves to iterate Projectile's extent (and any other conforming class's extent) in dense storage. Iteration is cache-optimal, SIMD-friendly, and requires no ECS library.

Users writing business logic get the same benefits transparently — cross-cutting queries over domain entities work without opting into ECS-specific patterns.

### 6.13 Non-Goals

Explicit non-goals for the memory model:

- **No SQL parser.** Queries are method calls in Corvid syntax, not a query DSL.
- **No persistence.** Extents live in memory; process termination destroys them. Persistence is library territory.
- **No ACID transactions.** Concurrent mutation during query iteration is implementation-defined. Users needing transaction semantics use library facilities.
- **No garbage collector as default.** ARC is the reference lifetime mechanism. Optional cycle collector as a library (Stage 2+).
- **No arbitrary structural modification at runtime.** Class layout is compile-time-fixed. `class_addMethod` and category loading remain available as runtime APIs, but do not create new field layouts (§4.18.3).
- **No mandatory ownership annotations.** ARC handles the common case. Ownership types (Rust-style borrow/move) available as Stage 3+ opt-in via a section.
- **No user opt-out from extent tracking for user classes.** All user-declared classes are queryable. Struct is the escape hatch for zero-overhead value semantics.
- **No user opt-out from handle indirection.** All class references are handles. Compiler optimizes to direct pointers where scope permits.

These non-goals bound the design and prevent scope creep into database-like functionality that would be better served by dedicated libraries.

### 6.14 Reference Modifiers

- `weak` — weak reference (nils out when target deallocates). Does not participate in ARC.
- `unowned` — non-owning strong reference. Assumes target outlives reference; undefined behavior if violated.

Applied to fields. `weak` implies nil-observability regardless — the type is a reference type and reference types are nil-allowed by default (§3.6.1), so no `?` suffix is needed or accepted.

```
class Child:
    state:
        parent: weak Parent
```

Applied to locals (rare). Under nil-punning semantics (§3.6.1), `if let` is used only when the unwrapped value must be bound for repeated use in the following scope; single-call cases just dispatch through:

```
function callback():
    weak self_ref = self
    schedule_later(function():
        self_ref.do_thing()    # no-op if self was deallocated (weak → nil)
    )
```

### 6.15 Mutability

Mutability applies to bindings, not referenced objects.

- **Fields** — default mutability follows the enclosing section's `default_mutability` configuration. `data:` fields are immutable; `state:` fields are mutable. Individual fields can override with `mutable` or `immutable`.
- **Locals** — mutable by default. First assignment declares. `immutable` opts into single-assignment.

For class instances (reference types), an `immutable` binding still permits mutating the referenced object. For struct instances (value types), `immutable` prevents any modification through that binding.

### 6.16 Local Declaration Rules

Locals are function-scoped. First assignment declares; subsequent assignments reassign. Blocks (`if`, `for`, etc.) do not introduce new scopes for name resolution. Shadowing requires explicit `immutable`.

---
## 7. Modules, Packages, and Sources

The language distinguishes three levels of code organization:

- **Source** — a single `.cor` file.
- **Module** — a named compilation unit that may span multiple sources, produces a unified metadata artifact, and is the boundary for public/internal visibility.
- **Package** — a distribution unit containing one or more modules, versioned, and the boundary for external dependencies.

Users write sources. The compiler assembles sources into modules based on module declarations. Modules assemble into packages via manifests. Each level exists for a different concern: sources are for humans (files they edit), modules are for compilation (units the compiler processes together), packages are for distribution (units published, versioned, depended upon).

### 7.1 Sources

A source is a single `.cor` file. Sources contain declarations: classes, structs, enumerations, protocols, functions, aliases, section usages.

Sources are the granularity of editing but not the granularity of compilation. Multiple sources can share a module; the compiler processes them together.

**Single-file scripts** are sources that compile and run without any surrounding structure:

```
# hello.cor
function main():
    print("Hello, world")
```

Invoked as `oc5 hello.cor`. No manifest required; no module declaration required (the source is treated as its own implicit module). Standard library available implicitly.

### 7.2 Modules

A **module** is a named compilation unit. Modules:

- Have a reverse-DNS name (`com.example.orders`).
- May span multiple sources declaring the same module name.
- Produce a unified metadata artifact (§4.18) shared across the module.
- Define the boundary for `internal` visibility (§7.7).
- Compile as a unit — cross-file references within the module resolve during a single compilation pass.

**Declaration:**

```
# In every source file that belongs to this module:
module com.example.orders
```

All source files declaring `module com.example.orders` compose into that module. Their order of processing is determined by dependency (a source that references types defined in another source of the same module compiles after the defining source).

**Single-source modules** need no explicit declaration if the source is compiled standalone; the compiler treats the source as an implicit module named after the file.

**Multi-source modules** benefit from shared metadata (LSP sees the whole module structure), cross-source visibility of internal-by-convention declarations, and unified exposed-interface export.

### 7.3 Packages

A **package** is a distribution unit containing one or more modules. Packages:

- Have a name (typically the top-level portion of the qualified module names within, e.g. `com.example.myapp`).
- Have a version (semver).
- Declare their dependencies on other packages.
- Serve as the unit of publishing, caching, and versioning.
- Contain metadata declaring their modules, entry points, and settings.

Packages are declared via a manifest section (§7.4).

### 7.4 The Package Section

Package manifests are declared using the `package:` section — a Stage 1 built-in section for module-body scope. Manifests may appear in three forms.

**Section class:**

```
section class Package:
    data:
        contains: manifest_data
        scope: [source_file_top, module_body]
        composition_mode: inline  # appears once, not composed
    
    processor process(contents: ManifestData):
        # Validates the manifest structure.
        # Registers the package with the compilation context.
        # Feeds dependency information to the resolver.
        register_package(contents)
```

**Form 1: Full manifest in package.cor.**

Real projects place a `package.cor` file at the package root:

```
# package.cor
package:
    name: com.example.myapp
    version: 1.0.0
    
    modules:
        - com.example.myapp
        - com.example.myapp.orders
        - com.example.myapp.core
    
    dependencies:
        - com.example.data_tools ^= 1.2
        - com.mystartup.private_lib from "https://internal.mystartup.com/pkg"
    
    entry_point: com.example.myapp.main
```

The `package.cor` file declares one package. Its `modules:` list declares which modules this package provides. Its `dependencies:` list declares external packages required.

**Form 2: Embedded manifest in a script.**

Single-file scripts with dependencies embed the manifest at the top of the source:

```
# analyze.cor
package:
    name: com.local.analyze
    version: 0.0.1
    dependencies:
        - com.example.data_tools ^= 1.2

import com.example.data_tools

function main():
    ...
```

The `package:` section appears at the top of the source. Everything after it is the script body. Compiler processes the manifest, resolves dependencies, then compiles the script.

**Form 3: Implicit manifest.**

Sources with no dependencies beyond the standard library need no manifest:

```
# hello.cor
function main():
    print("Hello")
```

The compiler assumes an implicit manifest: name derived from filename, version `0.0.0`, no dependencies. Standard library available.

### 7.5 Dependency Sources

Package dependencies may specify their source explicitly for cases where default resolution is insufficient:

**In the manifest:**

```
package:
    dependencies:
        - com.example.data_tools ^= 1.2                                    # default source (registry)
        - com.mystartup.private from "https://internal.mystartup.com/pkg"  # private URL
        - com.local.experiment from "/Users/me/dev/experiment"             # local directory
        - com.example.legacy from "git+https://github.com/x/legacy@v3"     # git ref
```

The `from` clause specifies where the compiler finds the package. Supported source types:
- **Registry URL** (default when omitted) — the compiler's configured package registry.
- **HTTPS URL** — direct URL to a package archive.
- **Local path** — filesystem path to a package directory (for local development).
- **Git URL with ref** — git repository at a specific commit, tag, or branch.

**In imports (per-file override):**

Individual source files can override the manifest's source resolution:

```
import com.example.data_tools from "https://internal-mirror.example.com/data_tools/1.2.0"
```

Per-file overrides are rare — they exist for cases where a specific source needs a different version or origin than the rest of the module. Most files rely on manifest resolution.

### 7.6 Imports and Resolution

**Implicit resolution.** By default, the compiler resolves references implicitly against all packages declared in the manifest (plus the standard library, always implicit).

```
# No explicit imports needed
class MyController:
    state:
        orders: [Order]   # compiler resolves Order against available packages
        client: HttpClient
```

If `Order` is unambiguously defined in exactly one dependency, the compiler uses it. If ambiguous, compile error asking the user to disambiguate.

**Explicit imports.** Users can add explicit `import` statements for disambiguation, per-file source override, or readability:

```
import com.example.orders                    # brings all exposed names into resolution scope
from com.example.orders import Order         # brings only Order
import com.example.orders as orders          # brings module accessible as `orders.Order`
import com.example.orders from "..."         # explicit source
import objc "Foundation"                     # ObjC framework
import c "stdio.h"                           # C header
```

Explicit imports do not add functionality beyond what implicit resolution provides. They exist for clarity and disambiguation.

**Standard library resolution.** The standard library is always implicit. Users never need to import it. Standard library modules include core types (`String`, `Array`, `Dict`), I/O (`File`, `Print`), and common utilities.

**Ambiguity handling.** When a name resolves to multiple candidates, the compiler emits an error and suggests explicit disambiguation:

```
error: ambiguous reference to 'Order'
  --> Controller.cor:14:12
   |
14 |     orders: [Order]
   |             ^^^^^ 
   |
note: 'Order' is defined in both:
      com.example.orders.Order
      com.otherlib.orders.Order
help: qualify the reference:
      com.example.orders.Order
      or add an import:
      import com.example.orders
      or an alias:
      alias Order: com.example.orders.Order
```

### 7.7 Visibility

Two visibility states, not levels:

- **`expose`** — this declaration is part of the interface I designed for external use. Depend on it.
- **Absent (default)** — internal to the module by convention. Not part of the exposed interface.

**No `public`, no `private`, no `internal` keyword.** The previous spec had three levels with hierarchical access control. That was authority framing: `private` said "you can't touch this," `public` said "you may touch this." Both were enforced at compile time, then blown open by runtime metadata queries (§4.18).

Under intent-not-authority (§1.2), the compiler doesn't enforce access restrictions. It enforces intent:

- `expose` on a declaration adds it to the module's stable public surface. Metadata artifacts (§4.18) list it as intended-for-external-use. IDE completions surface it prominently.
- Absent `expose`, the declaration is internal to the module by convention. The compiler emits a warning when external code references it. The warning can be suppressed per-reference (for legitimate boundary-crossing) or upgraded to an error via build config (for stricter enforcement).

**Runtime access is unrestricted.** Metadata queries return everything the compiler knows about a class regardless of `expose`. KVC-style access reaches non-exposed fields. Categories add methods to any class. This is the Objective-C runtime philosophy carried forward honestly: capability is preserved; intent is documented.

**Type-scoped visibility (like Java's `private`) doesn't exist as a keyword.** Members inside a class declaration that aren't `expose`d are internal to the module — and by convention to the type. Same warning behavior for external access. If real usage shows need for type-scoped enforcement, we add it later; current position is that the convention is enough.

**Scope of `expose`:**
- On a class or struct: the type name is exposed as part of the module's interface.
- On a method or field within a type: the member is exposed even when the containing type's members are otherwise internal.
- On a function, alias, or constant at module scope: same as class — added to the exposed surface.

**Example:**

```
class Order:
    expose data:
        id: OrderID
        placed_at: Timestamp
    
    data:
        internal_hash: Bytes    # not exposed; external code gets a warning
    
    expose methods:
        function ship(self) throws ShipError: ...
    
    methods:
        function _validate(self): ...  # not exposed; convention indicates internal
```

Consumers reading this see the intended interface (`id`, `placed_at`, `ship`) clearly. They technically can reach `internal_hash` or `_validate` but on notice that they've stepped off the stable surface.

### 7.8 Public Interface

A module's exposed interface consists of all declarations marked `expose`. This includes:

- Public types (classes, structs, enumerations).
- Public functions and constants.
- Public aliases (all four categories).
- Public sections and section classes.

Section usages on exposed classes are part of the exposed interface (§4.18). Consumers see the class's structure including sections. This means adding a section to an exposed class is a semver minor change if the section is externally observable; non-exposed sections don't affect the interface.

Public interfaces have compatibility guarantees per semver (§7.11).

### 7.9 Compilation and Dependency Model

Modules compile in dependency order. A module's dependencies (other modules within the same package or external packages) compile first.

**Within a package:**
- Modules are compiled in topological order of dependencies.
- Circular dependencies between modules are compile errors.
- Circular references between types within a single module are permitted.

**Across packages:**
- Dependency packages compile independently.
- Their metadata artifacts and object files are cached.
- Dependent packages consume the cached outputs.

**Incremental compilation (§4.18.5):**
- Modules are compilation units. Incremental state is per-module.
- Cross-source changes within a module reprocess only affected sources.
- Cross-module changes trigger recompilation of dependent modules.

### 7.10 Cross-Language Modules

Sources may mix `.cor` and `.m` (Objective-C) files within the same module. Both compile to libobjc2-compatible object files that link together (§9.4).

**Metadata coverage:**
- Corvid sources produce rich metadata (sections, contracts, invariants).
- ObjC sources produce basic runtime class info (via libobjc2's format).
- The module's metadata artifact contains both.

**Cross-language references:**
- Corvid sources reference ObjC classes via `import objc "Framework"`.
- ObjC sources reference Corvid classes via auto-generated `-Corvid.h` headers.
- Runtime class lookup works uniformly (both languages register with libobjc2's class table).

### 7.11 Version Compatibility

Packages follow semver:

- **Major (X.0.0)** — breaking changes to exposed interface. Consumers may need code changes.
- **Minor (1.X.0)** — additive changes. New exposed declarations; no removals or signature changes.
- **Patch (1.0.X)** — internal changes only. No exposed interface change.

**What counts as breaking:**
- Removing an exposed declaration.
- Changing an exposed function's signature (parameters, return type, effects).
- Removing an exposed field from a class.
- Changing a section class's generator output (affects all consumers of that section).
- Adding a required protocol method.

**What is additive:**
- New public declarations.
- New optional parameters with defaults.
- New protocol methods with default implementations.
- New sections on a public class (if the section's contribution to the public interface is additive).

**Section class versioning:** section classes ship as part of a module. Section class changes follow the containing module's semver. Because section class changes affect generated code in consumer modules, section class semver is strict — any generator output change is a major bump.

### 7.12 Dylib Loading and Runtime Extension

libobjc2 supports dylib loading at runtime. Corvid modules compile to dylibs (or static libraries); loading a dylib registers its classes and metadata with the runtime.

**Runtime plugin patterns work:**
- `dlopen("plugin.dylib")` loads the plugin.
- Plugin's classes register with the shared libobjc2 class table.
- Plugin's metadata artifact loads into the runtime metadata registry.
- Runtime queries (§4.18.3) can now find plugin classes.

**Limitations:**
- Cross-dylib section class version compatibility is checked at load time; mismatches error before loading completes.
- Handle validation across dylib boundaries requires the shared metadata registry.
- Unloading a dylib whose classes have live instances is undefined behavior (matches libobjc2's model).

### 7.13 Circular Dependencies

**Between modules within a package:** compile error. Users refactor into a shared module or split the circularity via protocols.

**Between packages:** compile error. Cannot occur in practice because packages are DAG-linked.

**Between types within a module:** permitted. Two classes may reference each other via handles, protocols, or types.

**Between sources within a module:** permitted. The compiler resolves cross-source references during module compilation.

### 7.14 Non-Goals and Deferred

**Non-goals:**

- **No header files.** All declarations are in `.cor` sources; no separate interface files.
- **No preprocessor imports.** `import` is semantic, not textual.
- **No global namespace at the source level.** Types are qualified by module.
- **No runtime module unloading beyond libobjc2's dylib model.**
- **No distributed modules or URL-referenced imports at compile time** (imports may specify HTTPS URLs, but only for fetching packages, not for referencing types across the network at compile time).

**Deferred to post-v1:**

- **Package manager as a tool.** v1 ships with a working compilation model and manifest support; the package manager tool (fetching, caching, dependency resolution) comes as a separate tool in v1.0 or later.
- **Registry protocol.** Specification for the package registry that clients speak.
- **Version resolution algorithm.** How the tool picks versions when multiple constraints exist.
- **Workspace concept.** Multiple related packages developed together (Cargo workspaces).
- **Fileprivate visibility.** Add if real usage shows need.

---

## 8. Concurrency

Corvid does not commit to a single concurrency model. It provides coordination primitives (state machines via contracts, channels, signals, structured async) that support multiple threading and communication styles. Users pick per-task; the language ensures the coordination primitives compose safely.

### 8.1 Concurrency Primitives, Not a Model

Traditional language design picks one concurrency model — actors (Erlang, Akka), CSP (Go, Clojure core.async), async futures (Rust, JavaScript), threads with locks (C++, Java) — and makes everything else awkward. Corvid rejects the premise. Different problems benefit from different models; a real system often uses several.

The language provides:

- **State machines as synchronization** (§8.4) — contract-carrying methods on mutable state are atomic transitions. This is where actor-like semantics come from.
- **Channels** (§8.6) — asynchronous event streams between producer and consumer contexts. This is where CSP comes from.
- **Signals** (§8.7) — one-shot completion notifications. This is where futures/promises come from.
- **Structured async** (§8.3) — scoped task lifetimes with automatic cancellation.
- **Threads and parallelism** (§8.8) — explicit OS threads (advanced use); `parallel_for` for row-level parallel iteration (Stage 3+).

Users compose these to build actors, pipelines, event loops, work queues, reactive systems, or whatever their problem needs.

### 8.2 Async Effect (Cross-Reference)

`async` is a semantic effect (§3.5.2) that changes calling convention. Async functions can suspend and resume; sync functions cannot. Async functions can only be called from other async functions or from an async context (structured scope, task spawner).

```
async function fetch_data(url: URL) -> Bytes throws NetworkError:
    response = await http.get(url)
    return response.body
```

`await` is only valid inside `async` functions. `async` is not advisory — a method's async-ness is part of its calling convention and cannot be "just a hint."

### 8.3 Structured Concurrency

Async tasks live in structured scopes. A scope guarantees all tasks spawned within it complete (or are cancelled) before the scope exits.

```
async function process_batch(orders: [Order]):
    async_scope:
        for order in orders:
            spawn process_one(order)
        # scope exit: waits for all spawned tasks to complete
    
    # By here, all process_one tasks have finished
```

**Cancellation:** if the scope exits abnormally (error propagation, explicit cancellation), all still-running tasks are cancelled. Cancellation is cooperative — tasks check cancellation points at await sites and abort cleanly.

**Return values:** `spawn` returns a `Task<T>` handle. `await task` retrieves the result:

```
async function fetch_and_combine():
    async_scope:
        task_a = spawn fetch(url_a)
        task_b = spawn fetch(url_b)
        result_a = await task_a
        result_b = await task_b
        return combine(result_a, result_b)
```

**Error handling:** if a spawned task raises, the error propagates out through the scope. Other tasks in the scope are cancelled. Structured error propagation.

**Scope nesting:** scopes can nest. Inner scope errors propagate to outer scope. Cancellation cascades down.

### 8.4 State Machines as Synchronization

Under §1.9, methods with `requires`/`ensures` on mutable state get atomic check-and-transition semantics. This is the language's primary synchronization mechanism.

**Semantics:**

For a method:
```
async function ship(self)
    requires status == .pending
    ensures status == .shipped:
    ...
    self.status = .shipped
```

The runtime guarantees:
1. The precondition check and the postcondition establishment are atomic with respect to other threads accessing `self.status`.
2. If the precondition is not met, the caller either waits (if awaiting) or fails immediately (if not).
3. Once the precondition is verified, no other thread can invalidate it before the postcondition is established.

**Implementation:**

- **Stage 1**: runtime-enforced via CAS or lightweight locks per contract-carrying method. The runtime maintains a lock per instance for methods that need synchronization; methods without contracts on mutable state skip the lock entirely.
- **Stage 3+**: compile-time analysis eliminates locks where the runtime state can be proven not to have concurrent access. Optimistic concurrency where profitable.

**Waiting on preconditions:**

Async methods with unmet preconditions wait on a state change signal (§8.7). When the state field is next modified (through any method), waiters re-check their preconditions. This means `requires` clauses on mutable state serve as condition variables.

```
class Buffer:
    state:
        items: [Item] = []
    
    invariants:
        items.count <= max_size
    
    methods:
        async function pop(self) -> Item
            requires items.count > 0:
            item = self.items[0]
            self.items = self.items.drop_first()
            return item
        
        async function push(self, item: Item)
            requires items.count < max_size:
            self.items.append(item)

# Producer
async function producer():
    for i in 0..1000:
        await buffer.push(Item(i))  # waits if buffer full

# Consumer
async function consumer():
    while true:
        item = await buffer.pop()  # waits if buffer empty
        process(item)
```

The precondition semantics automatically implement a bounded queue with backpressure. No explicit locks, condition variables, or synchronization primitives written by the user.

**Non-async methods with unmet preconditions fail immediately** rather than waiting. This preserves the sync/async distinction — sync code doesn't unexpectedly block.

### 8.5 Sendable Types

Types that can safely cross task boundaries. Determined by shape, not annotation:

- **Value types** (struct, enumeration) — always Sendable. Copied on transfer.
- **Class handles** — always Sendable. The extent lives globally; handles point into it. Access is coordinated by the extent's state machine.
- **Function values** — Sendable if their captured environment is Sendable.
- **Types with mutable global state** — not Sendable. Rarely encountered because Corvid has no true globals (module-level constants are immutable; module-level state doesn't exist).

The runtime enforces Sendable at task boundaries. Attempting to pass a non-Sendable value into a spawned task raises a compile error (Stage 3+) or runtime error (Stage 1).

### 8.6 The Channel Section

The `channel:` section is Stage 1 built-in behavioral, providing CSP-style asynchronous coordination. A channel is a first-class coordination primitive with two composition patterns and three distribution modes.

**Two composition patterns:**

1. **Direct field entries** — declare typed channel items that consumers `send` and receive. Analogous to Go/Clojure channels used as work queues, broadcasters, or topic streams.
2. **Nested `state:` composition** — wrap declared state fields so that setting them auto-publishes `StateChange` events. This is the v0.5 pattern preserved.

Both patterns coexist under the same section; a class may use either or both.

**Three distribution modes** (via `metadata: mode:`):

- **`work` (default)** — CSP-style competing consumers. Each item is received by exactly one subscriber. FIFO delivery. Analogous to Go's `chan T`, Clojure's `chan`.
- **`broadcast`** — every subscriber receives every item. Slowest subscriber sets the pace (synchronous fan-out). Analogous to Clojure's `mult`/`tap`.
- **`pub`** — topic-partitioned broadcast. Requires `metadata: topic_fn:` mapping items to topic identifiers. Subscribers pick specific topics. Analogous to Clojure's `pub`/`sub`.

```
section class Channel:
    data:
        contains: fields
        composition_mode: nested
        parameters:
            mode: DistributionMode = .work
            capacity: Int? = nil          # Int is value-type; T? is Optional<Int> sum. nil = unbounded (default).
            on_full: OverflowPolicy = .block   # required when capacity is set
            topic_fn: TopicFn = nil       # TopicFn is a Callable (reference); nil-allowed by default. Required when mode = .pub.
    
    state:
        instance_subscribers: [Subscriber] = []
        buffer: RingBuffer<Item> = RingBuffer.new(bounded: capacity)
    
    generator field_setter(field: FieldSpec) -> Code:
        # Used when the channel composes with a nested `state:` section.
        # Publishes StateChange events on set. Semantics as in v0.5.
        ...
    
    function send(self, item) -> ...
    function subscribe(self) -> AsyncIterator<Item>: ...
    class function subscribe() -> AsyncIterator<Item>: ...
```

#### 8.6.1 Direct-Entry Channels

Declare typed channel items as fields inside `channel:`. The compiler generates `send`/`subscribe` machinery for each entry.

```
class WSFramer:
    data:
        is_server: Bool
    state:
        buffer: Bytes = Bytes()
    channel:
        input: Bytes                              # work mode (default)
        output: Frame
            metadata:
                mode: broadcast                   # every subscriber sees every frame
        errors: WSError
            metadata:
                capacity: 32                      # bounded
                on_full: drop_oldest
        events: DomainEvent
            metadata:
                mode: pub
                topic_fn: e => e.kind             # topic-partitioned
    methods:
        expose function add(self, data: Bytes):
            self.sections.channel.input.send(data)
        
        async function process_loop(self):
            for data in self.sections.channel.input.subscribe():
                self.buffer.append(data)
                ...
```

**Backpressure defaults.** Direct-entry channels default to **unbounded** capacity. This matches DispatchQueue behavior at the Apple-ecosystem substrate layer — unbounded → memory grows on lagging consumer, but memory pressure is diagnosable with standard Apple tooling (Instruments, xctrace). Users needing bounded semantics opt in via `metadata: capacity:` + `on_full:`.

**Consumer lifecycle.** Consumer loops are bound to their owning entity:

- **Per-instance loops** — spawn from the constructor: `spawn self.process_loop()`. When the instance is deallocated, the channel closes; the `for ... in .subscribe()` loop terminates naturally; the task ends. No `[weak self]` dance required — the channel-closure semantics handle instance lifetime.
- **Per-program loops** — spawn from the program entry point; terminate at program exit via structured cancellation.

#### 8.6.2 State-Composition Channels

The v0.5 pattern — wrapping declared state fields for auto-publish — is preserved via section composition:

```
class Order:
    state:
        status: OrderStatus = .pending
    channel:
        state:
            status                # setting order.status auto-publishes StateChange event

# Consumers subscribe to the state-change stream:
for change in specific_order.sections.channel.subscribe():
    handle(change)               # StateChange { instance, field, old, new }
```

The channel's `field_setter` generator wraps assignments to nested-`state:` fields, producing `StateChange` events on set. Distribution mode defaults to `broadcast` for state-composition channels (state changes are inherently pub/sub) but can be overridden via `metadata: mode:`.

#### 8.6.3 Buffering and Backpressure

Bounded channels use `metadata: capacity: N` with an `on_full:` policy:

| `on_full:` | Behavior when buffer full |
|---|---|
| `block` (default when bounded) | Sender blocks (async) or fails (sync) until space available |
| `error` | Sender throws immediately |
| `drop_oldest` | Oldest buffered item is discarded to make room |
| `drop_newest` | Incoming item is discarded |

Buffering interacts with state-machine synchronization: if a setter blocks on channel backpressure, its `ensures` postcondition is not yet established. Other threads waiting on the state remain waiting until the channel makes progress.

#### 8.6.4 Cancellation and Multiple Subscribers

**Cancellation.** Subscribers cancel by dropping the iterator. The channel deregisters them. If all subscribers drop and the mode is `work`, the channel accumulates items in the buffer (up to capacity if bounded); if the mode is `broadcast` or `pub`, items are dropped when no subscribers exist (Clojure `mult` semantics).

**Multiple subscribers by mode:**

- `mode: work` — subscribers compete; each item goes to exactly one subscriber. Under contention, distribution is FIFO across arrivals but items are consumed exclusively.
- `mode: broadcast` — every subscriber receives every item synchronously. Slowest subscriber determines throughput.
- `mode: pub` — every subscriber sees items whose `topic_fn(item)` matches its subscribed topic. Multiple subscribers to the same topic each receive every matching item (broadcast within topic).

### 8.7 The Signal Section

The `signal:` section is Stage 1 built-in behavioral, providing one-shot completion notifications.

```
section class Signal:
    data:
        contains: fields
        composition_mode: nested
    
    state:
        set: Bool = false
        value: SignalValue?
        waiters: [Continuation] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value):
                if self.sections.signal.set:
                    @compile_or_runtime_error("signal '${field.name}' already set")
                self._${field.name} = new_value
                self.sections.signal.set = true
                self.sections.signal.value = new_value
                for waiter in self.sections.signal.waiters:
                    waiter.resume(new_value)
                self.sections.signal.waiters = []
    
    function await(self) -> SignalValue:
        if self.set:
            return self.value
        return suspend { cont in
            self.waiters.append(cont)
        }
```

**Semantics:**

- Signal-marked fields can be set exactly once. Subsequent sets are errors.
- Awaiters block until the signal is set; then all receive the value simultaneously.
- After being set, awaiting a signal returns immediately with the value.

**Usage:**

```
class Server:
    signal:
        state:
            ready_endpoint: URL

# One task sets it
async function start_server():
    endpoint = await bind_socket()
    server.ready_endpoint = endpoint  # signals completion

# Other tasks wait for it
async function client_connect():
    endpoint = await server.ready_endpoint  # blocks until set
    await connect(endpoint)
```

**When to use signal vs channel:**

- **Signal**: one-shot events. Task completion, resource initialization, ready flags, cancellation tokens.
- **Channel**: recurring events. State changes, message streams, event logs.

### 8.8 Threads and Parallelism

**Stage 1 — Explicit Threads (Restricted):**

Direct OS thread creation is available but restricted to `unsafe:` contexts:

```
unsafe:
    thread = spawn_thread(function() {
        # runs on a new OS thread
        long_running_work()
    })
    thread.join()
```

The restriction is deliberate: explicit threads bypass the async runtime's scheduling and can introduce subtle bugs. Users needing OS threads must acknowledge the risk.

**Stage 3+ — Row-Level Parallelism:**

`parallel_for` iterates a query result in parallel across worker threads:

```
parallel_for particle in Particle.query():
    particle.position = particle.position + particle.velocity * dt
```

The runtime distributes iterations across a thread pool. Each iteration operates on one row; the extent guarantees no two iterations touch the same row simultaneously.

**Constraints on parallel_for bodies:**
- The body cannot reference mutable state outside the iterated row (compile error).
- Method calls with `requires`/`ensures` on other objects are permitted but may introduce serialization if contended.
- Side effects (I/O, logging) are permitted but not ordered.

**Data-parallel operations:**

For dense-storage tags (§6.7), the runtime can vectorize simple parallel_for bodies to SIMD instructions where applicable. Stage 3+ optimization.

### 8.9 ObjC Interop

Objective-C's concurrency primitives (GCD, `NSOperation`, completion handlers) bridge to Corvid as follows:

**Dispatch queues → task executors:**

```
main_queue = DispatchQueue.main  # Corvid-typed
async function update_ui():
    await main_queue.run:
        # runs on main thread
        label.text = "updated"
```

**Completion handlers ↔ async functions:**

ObjC methods with completion handlers are imported as `async` functions:

```
# ObjC declaration:
# - (void)fetchDataWithCompletion:(void (^)(NSData *, NSError *))completion;

# Corvid usage:
data = await client.fetch_data()  # async, throws NSError
```

The compiler wraps completion-handler methods automatically. Errors passed as the second parameter map to `throws`.

**Corvid async methods exposed to ObjC:**

Auto-derivation (§9.3.3) exposes Corvid async methods to ObjC via completion-handler convention:

```
# Corvid:
async function process(order: Order) throws OrderError -> Receipt: ...

# ObjC sees:
- (void)processOrder:(Order *)order completion:(void (^)(Receipt *, NSError *))completion;
```

**Main-thread affinity:**

UIKit/AppKit callbacks must run on the main thread. Corvid provides a `@main_actor` marker (annotation, not a section) that ensures methods run on the main thread:

```
class ViewController:
    @main_actor
    methods:
        async function refresh(self):
            data = await fetch()
            self.label.text = data.title  # guaranteed main-thread
```

Under the hood, `@main_actor` schedules the method on the main dispatch queue.

### 8.10 Non-Goals

Explicit non-goals for the concurrency model:

- **No committed concurrency model.** The language does not pick actors, CSP, or futures as "the model." Users choose.
- **No built-in transactions.** Multi-object atomic updates are not a language feature. Users compose them via explicit intermediate states or library-provided STM.
- **No distributed protocols.** Networked coordination is a library concern.
- **No lock-free data structures as language primitives.** Standard library may provide some; language provides the state-machine mechanism.
- **No mandatory `Sendable` annotations.** The compiler infers sendability from shape (Stage 1 runtime-checked; Stage 3+ compile-time).
- **No preemptive multithreading in Stage 1 outside `unsafe:`.** Cooperative async on a work-stealing pool covers most needs.
- **No shared mutable global state.** Module-level state does not exist; static fields are compile-time constants (§4.10.1).

These non-goals bound the design and prevent scope creep into distributed-systems-in-the-language territory.

---
## 9. Interop

Corvid's interop story is grounded in a specific compilation model: Corvid shares its runtime substrate (`libobjc2`) with Objective-C. Both languages produce the same class metadata, use the same method dispatch, and interoperate at the runtime level rather than through a bridge. This makes Objective-C interop **native** rather than mediated.

### 9.1 Compilation Model

**Primary compilation target: LLVM IR.** The Corvid compiler produces LLVM IR that speaks the libobjc2 ABI directly. Object files are standard LLVM target format, compatible with any linker that handles Clang output.

**Alternative compilation target: Corvid → Objective-C source.** For bootstrap purposes and specific interop scenarios (debugging generated code, working with ObjC-only toolchains, platforms without LLVM), the compiler can emit `.m` files that any Objective-C compiler can process. This path is preserved as a permanent alternative output, not just a bootstrap artifact.

**No Clang fork.** Corvid's compiler is standalone. It reuses none of Clang's front-end code. It emits object files that the standard linker combines with output from Clang, GCC-ObjC, or any other libobjc2-compatible compiler.

**Rationale.** LLVM IR direct gives us maximum flexibility (not tied to Clang's evolution), acceptable engineering cost (libobjc2 ABI is well-documented and stable), and clean integration with the LLVM ecosystem (LTO across languages, standard debug info, shared object format). Forking Clang would couple us to Clang's release cycle and front-end assumptions permanently for no compensating benefit — we don't need Clang's ObjC codegen because we can emit libobjc2-compatible IR directly.

### 9.2 C Interop

Direct via `import c "header.h"`. The compiler parses C headers, exposes declared types and functions to Corvid code.

```
import c "stdio.h"

function main():
    c.printf("hello\n")
```

C types map to Corvid types via standard equivalences (`int` → `Int32`, `char *` → `String` or `RawPointer` depending on context, etc.). Full mapping table in the standard library documentation.

### 9.3 Objective-C Interop

**Native — same runtime, direct calls, no bridge layer.**

#### 9.3.1 Importing Objective-C

```
import objc "Foundation"
import objc "UIKit"

class MyViewController: UIViewController:
    state:
        table_view: UITableView?
    
    dynamic function viewDidLoad(self):
        super.viewDidLoad()
        self.table_view = UITableView()
```

Objective-C classes are usable as Corvid classes. Method calls compile to the same `objc_msgSend` invocations Clang would emit. Subclassing an ObjC class produces a subclass the ObjC runtime treats as any other. Categories work uniformly. KVO works. Swizzling works.

#### 9.3.2 Type Boundary Rules

Objective-C's type system is less precise than Corvid's. Values crossing the boundary follow these rules:

**`id` → `Any`.** ObjC APIs returning `id` come into Corvid as `Any`. Method calls on `Any` are runtime-dispatched:

```
result: Any = some_objc_api()
# result.method() dispatches through the runtime; runtime error if method not found.

match result:
    case String(s): use_string(s)
    case Int(i): use_int(i)
    case _: handle_unknown()
```

Users destructure explicitly when concrete types are needed. `Any` is the honest representation of "the type system doesn't know."

**`NSError **` ↔ `throws E`.** ObjC's error out-param convention bridges to Corvid's `throws`. ObjC methods declared with `NSError **` are imported as `throws NSError`. Corvid methods with `throws E` are exposed to ObjC via `NSError **` out-params (with automatic error boxing when `E` is not `NSError`).

**Nullability round-trip.** ObjC nullability annotations map to Corvid reference-type annotations (§3.6.1): ObjC `nullable` → Corvid `T` (nil-allowed by default). ObjC `nonnull` → Corvid `T!` (non-nil contract, warns on runtime violation). Un-annotated ObjC types come into Corvid as `T` (nil-allowed) — the safe default that matches ObjC runtime truth without adding warning noise. Callers who want stricter contracts add `T!` at the Corvid call site.

**Type equivalences.** Standard mappings:
- `NSInteger` = `Int`
- `NSUInteger` = `UInt`
- `CGFloat` = `Float` (or `Double`, platform-dependent)
- `BOOL` = `Bool`
- `NSString *` = `String` (references implicit)
- Blocks (`^returnType(paramTypes)`) = function types `(paramTypes) -> returnType`
- `NSArray<T> *` = `[T]` (generics preserved where expressible)

#### 9.3.3 Exposing Corvid to Objective-C

Public Corvid APIs are **auto-derived** for Objective-C consumption. The compiler emits `-Corvid.h` files describing Corvid classes in Objective-C syntax, using the ObjC-expressible subset of the API.

Auto-derivation rules:

- Public classes with public methods are exposed as ObjC classes.
- Contracts (protocols) map to `@protocol` declarations. Default implementations become `@optional` methods.
- Sum types degrade to `NSObject` subclasses with tagged discrimination, or to `NSInteger`-tagged enums for simple sums.
- Generics preserve where expressible (single type parameter, no constraints); otherwise degrade to `id`.
- Contract preconditions/postconditions become runtime checks in method bodies (not visible in the header).
- `throws E` methods expose as `NSError **` out-param.
- `async` methods expose via completion-handler convention.

**Warnings on non-round-tripping features.** When a public API uses features that don't cleanly express in ObjC, the compiler warns:

```
warning: method `process(order:)` uses sum type `OrderError`
         ObjC callers will receive this as `NSError *` with domain-encoded case
```

Users can accept the warning (auto-derivation continues), suppress with an explicit acknowledgment, or use the `objc_exposed:` section (§4.10.4.4) for fine-grained control over the ObjC-visible API surface.

#### 9.3.4 Explicit Control

For fine-grained control, use the `objc_exposed:` section:

```
expose class OrderProcessor:
    data:
        id: ProcessorID
    
    state:
        internal_state: ProcessorState
    
    # Auto-derived — visible to ObjC via generated header
    methods:
        function process(self, order: Order) -> Result<Receipt, OrderError>: ...
    
    # Only these methods are visible to ObjC, in the specified form
    objc_exposed:
        function processOrder(self, order: Order, error: NSError**) -> Bool: ...
```

The `objc_exposed:` section replaces auto-derivation for its enclosed methods, giving users precise control over ObjC signatures.

### 9.4 File-Level Mixing

**Corvid and Objective-C files can coexist in the same project.**

Build system integration:
- `.m` files compile via any Objective-C toolchain (typically Clang).
- `.cor` files compile via the Corvid compiler.
- Both produce libobjc2-compatible object files.
- Standard linker combines them.
- Runtime is shared (`libobjc2`); all interop happens through the runtime.

Cross-file references:
- `.cor` files `import objc "path/to/header.h"` to consume ObjC classes.
- `.m` files `#import "path/to/-Corvid.h"` to consume Corvid classes (auto-generated header).
- No bridging headers, no thunks, no ABI translation.

**Runtime transparency:**
- ObjC categories added to Corvid classes: work.
- Corvid categories added to ObjC classes (via `category:` section §4.10.4.3): work.
- ObjC KVO on Corvid fields: works.
- Swizzling across the language boundary: works.
- Subclassing across the boundary (either direction): works.

The runtime does not distinguish between the languages. Both produce the same class metadata, register in the same method tables, participate in the same dispatch mechanism.

### 9.5 Swift Interop

Via C ABI bridge, similar to how Objective-C interops with Swift. Not first-class in v0.1.

Swift/Corvid interop is bounded by Swift's own ABI stability and the C ABI's expressiveness. Where Swift and Corvid share ObjC as a substrate (both descend from `NSObject`, both use `@objc` machinery), interop is straightforward. Beyond that (pure Swift structs, Swift generics, Swift protocols with associated types), no direct interop is planned.

### 9.6 Migration Paths

**Adding Corvid to a legacy ObjC project:**
1. Install Corvid compiler alongside existing ObjC toolchain.
2. Add `.cor` files to the project. They can import and be imported by existing ObjC code.
3. Optionally translate specific `.m` files to `.cor` using the translation tool (§12).
4. Existing `.m` files remain unchanged.

**Starting a new project using ObjC frameworks:**
1. Write all code as `.cor`.
2. Import ObjC frameworks via `import objc "Foundation"` etc.
3. Framework classes are usable directly. No wrappers needed.
4. Final binary links `libobjc2` and framework binaries.

**Full migration from ObjC to Corvid:**
1. Run translation tool on all `.m` files.
2. Review generated `.cor` files. Translator handles mechanical bits (syntax, headers merging, `@property` to field declarations); users refine (which fields become `data:` vs `state:`, which methods should carry contracts, which declarations to `expose`).
3. Delete original `.m` files when translations are validated.
4. Codebase is now pure Corvid.

None of these paths require rewriting existing code. Corvid adoption is additive.

---

## 10. Grammar Sketch (Subset)

```
program := (module_decl)? import* top_level_item*

module_decl := 'module' qualified_name

top_level_item := section_usage | declaration | alias_decl

alias_decl := 'expose'? 'alias' identifier ':' section_name (WS section_name)*

declaration := class_decl | sugar_class_decl
             | function_decl | constant_decl | type_decl

# Sugar keywords all desugar to `class + metadata: kind:`.
sugar_class_decl := 'expose'? sugar_kind identifier
                    generic_params? conformance_clause? ':' INDENT
                        class_member*
                    DEDENT

sugar_kind := 'record'          # → metadata: kind: record
            | 'enum'            # → metadata: kind: enum
            | 'protocol'        # → metadata: kind: protocol
            | 'section' 'class' # → metadata: kind: section

section_usage := section_header ':' INDENT section_contents DEDENT

section_header := section_name (WS section_name)*
                # Multiple section names in one header = inline composition.
                # All named sections must have composition_mode: inline.
                # Nested composition is expressed via indented nested section_usage.

section_name := identifier ('(' arg_list ')')?
              # identifier may name a section class OR an alias.

section_contents := field_decl* | method_decl* | statement* | declaration*
                  | expression | ast_content
                  | metadata_entry* | case_decl* | accepts_decl*

class_decl := 'expose'? 'class' identifier
              generic_params? conformance_clause? ':' INDENT
                  class_member*
              DEDENT

class_member := metadata_section | section_usage
              | method_decl | constructor_decl | destructor_decl
              | case_decl | accepts_decl
              # methods without enclosing section are in implicit `methods:` section
              # metadata_section must appear first if present (§4.10.8)

metadata_section := 'metadata' ':' INDENT metadata_entry+ DEDENT

metadata_entry := identifier ':' metadata_value
metadata_value := expr | identifier | list_expr    # e.g. `record`, `[macos, ios]`, `"0.6.0"`

# `cases:` section content and `accepts:` section content.
case_decl := 'case' identifier ( '(' associated_data ')' )? ( '=' expr )?
             # `case name` | `case name(field: Type, ...)` | `case name = 1000`

accepts_decl := 'accepts' identifier '(' parameter_list ')' ('->' type)?
                effect_clause? contract_clause?
              # inside `kind: protocol`. Body-less; contracts allowed.

member_decl := generator_decl | processor_decl | validator_decl
             | method_decl | constructor_decl | destructor_decl

generator_decl := 'generator' identifier '(' parameter_list ')' '->' type
                  ':' INDENT statement* DEDENT

processor_decl := 'processor' identifier '(' parameter_list ')' '->' type
                  ':' INDENT statement* DEDENT

validator_decl := 'validator' identifier '(' parameter_list ')'
                  ':' INDENT statement* DEDENT

method_decl := 'expose'? ('mutating')? 'function' identifier
               generic_params? '(' parameter_list ')' ('->' type)?
               effect_clause? contract_clause?
               ':' INDENT statement* DEDENT

field_decl := 'expose'? ('immutable' | 'mutable')? ('weak' | 'unowned')?
              identifier ':' type ('=' expr)?

# Expression forms (§3.8).
expr := ... | if_expr | switch_expr | block_expr | while_expr | for_expr | loop_expr
      | closure_literal | function_literal | arrow_literal

if_expr := 'if' expr ':' expr_body ('else if' expr ':' expr_body)* ('else' ':' expr_body)?
switch_expr := 'switch' expr ':' INDENT case_arm+ DEDENT
block_expr := 'do' ':' INDENT statement* DEDENT
while_expr := 'while' expr ':' expr_body                # returns Bool
for_expr := 'for' identifier 'in' expr ':' expr_body     # returns Bool
loop_expr := 'loop' ':' INDENT statement* DEDENT         # returns type of `break value`

# Closures (§5.6). All desugar to anonymous classes with a `call` method.
closure_literal := capture_list? function_literal
                 | capture_list? arrow_literal
                 | method_reference

capture_list := '[' capture_entry (',' capture_entry)* ']'
capture_entry := ('weak' | 'unowned')? identifier
               | identifier ':' capture_mode              # e.g. `x: copy`

function_literal := 'function' '(' parameter_list ')' ('->' type)?
                    effect_clause? ':' expr_body

arrow_literal := ( identifier | '(' identifier (',' identifier)* ')' | '_' )
                 '=>' expr

method_reference := identifier ('.' identifier)+   # e.g. `self.foo`, `MyType.bar`

quote_expr := 'quote' ':' INDENT ast_content DEDENT

ast_content := <arbitrary AST with ${expr} splice points>

# Type expressions (§3.2, §3.6.1). Nullability suffixes are kind-restricted.
type := base_type
      | base_type '!'                # reference-type non-nil contract (§3.6.1)
      | base_type '?'                # value-type Optional<T> sum (§3.6.1)
      | '[' type ']'                 # Array<T>
      | '{' type ':' type '}'        # Map<K, V>
      | '{' type '}'                 # Set<T>
      | '(' type_list ')' '->' type  # Callable<Args, Return>

base_type := qualified_name generic_args?
           | '(' type_list ')'       # tuple type

# `!` on value-typed base is a semantic error (warning: trivially true).
# `?` on reference-typed base is a semantic error (redundant with base).

# Value-expression operators. Nil handling is via `.` (uniform) and `??`.
member_access := expr '.' identifier
              # `.` operates on both non-nil and nil receivers (§3.6.1, §5.7).
              # Nil receivers auto-lift return type or dispatch via on_nil section.

nil_coalesce := expr '??' expr
              # returns LHS if non-nil, else RHS.
```

**Retired in v0.7** (v0.6 → v0.7): value-position `!` (force-unwrap) and `?.` (optional chaining) productions removed. `.` is the only member-access operator. Explicit panic on nil uses `x ?? panic("...")`.

**Retired from prior drafts** (v0.5 → v0.6): `struct`, `enumeration`. Sugar keywords `record`, `enum` (plus preserved `protocol`, `section class`) desugar to `class + metadata: kind:` (§4.19).

**Retired earlier** (v0.5.0 → v0.5.1): `sealed`, `dynamic`, `open`, `public`, `private`, `package` visibility. Modules no longer carry dispatch policy annotations (§5.4).

---

## 11. Worked Example

A complete small program demonstrating sections at multiple scopes:

```
module com.example.orders

from com.example.inventory import Inventory, InventoryError

## An order is data — pure data class.
class Order:
    data:
        id: OrderID
        items: [Item]
        placed_at: Timestamp
    
    function is_valid(self) -> Bool:
        return not self.items.is_empty and self.total() > 0
    
    function total(self) -> Money:
        return self.items.sum(by: .price)

enumeration OrderError:
    case invalid_order
    case out_of_stock(item: Item)
    case payment_failed(reason: String)

protocol OrderProcessor:
    accepts process(Order) -> Result<Receipt, OrderError>
        async, throws
        requires order.is_valid()

## OrderProcessor is behavior — mixed class with data and state.
## `expose` on the class adds the type name to the module's public surface;
## members opt in individually.
expose class DefaultOrderProcessor: OrderProcessor:
    expose data:
        id: ProcessorID
        created_at: Timestamp
    
    state:
        inventory: Inventory              # internal by convention (no `expose`)
        weak delegate: OrderProcessorDelegate?   # exposed via the `expose state:` header below
        pending: [Order] = []
    
    expose state:
        # Alternative shape: an entire section header exposed.
        # Fields defined here are all exposed.
        # In this example we've kept inventory internal; delegate could move here.
        current_load: Int = 0
    
    function constructor(self, id: ProcessorID, inventory: Inventory):
        self.id = id
        self.created_at = Timestamp.now()
        self.inventory = inventory
    
    # Implicit methods: section for these methods.
    
    expose async function process(self, order: Order) -> Result<Receipt, OrderError>
            requires order.is_valid():
        self.notify_will_process(order)
        
        match await self.inventory.reserve(order.items):
            case .err(e):
                return .err(.out_of_stock(item: e.item))
            case .ok:
                receipt = await self.charge_and_ship(order)
                self.notify_did_process(order, receipt)
                return .ok(receipt)
    
    expose function cancel(self, id: OrderID) -> Bool throws:
        return self.inventory.release_reservation(id)
    
    # Not exposed — internal implementation detail. External callers get a
    # warning if they reference this from another module.
    async function charge_and_ship(self, order: Order) -> Receipt:
        ...
    
    function notify_will_process(self, order: Order):
        self.delegate.order_processor(self, will_process: order)
    
    function notify_did_process(self, order: Order, receipt: Receipt):
        self.delegate.order_processor(self, did_process: order, receipt: receipt)

expose protocol OrderProcessorDelegate:
    accepts order_processor(Self, will_process: Order)
    accepts order_processor(Self, did_process: Order, receipt: Receipt)
```

Example with primitive composition (expanded from aliases):

```
# `data:` is an alias for `runtime immutable identity`. These are equivalent:

class Order:
    data:
        id: OrderID
        items: [Item]

class Order:  # equivalent to above
    runtime immutable identity:
        id: OrderID
        items: [Item]

# Non-standard compositions use primitives directly:

class RequestCounter:
    compile_time mutable:              # class-level mutable counter
        total_requests: Int = 0
        active_requests: Int = 0
    
    static:                            # class-level constants (compile_time immutable)
        MAX_CONCURRENT: Int = 100

class SecureVault:
    # Internal-by-convention identity fields.
    # No `expose` — external code gets a warning on reference.
    runtime immutable identity:
        internal_seed: Bytes
        vault_id: VaultID
```

Example with user-defined alias:

```
module com.example.myapp

alias exposed_data: runtime immutable identity expose

expose class PublicRecord:
    exposed_data:
        display_name: String
        record_id: UUID

class InternalRecord:
    data:                    # no `expose` — internal by convention
        secret: Bytes
        internal_id: UUID
```

Example with user-defined sections:

```
from com.example.observability import Channel

class Counter:
    data:
        id: CounterID
    
    channel:
        value: Int = 0
        max: Int = 100

# Usage:
counter = Counter(id: CounterID.generate())
counter.sections.channel.subscribe(function(name, old, new):
    print("$name: $old → $new")
)
counter.value = 5  # publishes notification via generated setter
```

Example with function-body sections (implemented in later stages):

```
function compute_something(x: Int) -> Int:
    validate:
        require x > 0
        require x < 1000
    
    pure:
        result = x * 2 + 1
        result = result * result
    
    return result
```

Example section class definition:

```
section class Channel:
    data:
        contains: fields
        scope: [class_body]
        default_mutability: mutable
        contributes_to_equality: false
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value: ${field.type}):
                old = self._storage.${field.name}
                self._storage.${field.name} = new_value
                self.sections.channel.notify(
                    field: ${field.name.as_string},
                    old: old,
                    new: new_value)
    
    function subscribe(self, callback: (String, Any, Any) -> Unit) -> Subscription:
        subscription = Subscription(callback)
        self.subscribers.append(subscription)
        return subscription
    
    function notify(self, field: String, old: Any, new: Any):
        for sub in self.subscribers:
            sub.callback(field, old, new)
```

---

## 12. Objective-C Compatibility

**Answer:** New language with **file-level compatibility**, backed by shared runtime substrate and a translation tool.

The intent-not-authority principle makes deep interop clean. Objective-C's runtime semantics are already fully dynamic; Corvid's are also fully dynamic. Both languages target the same runtime (`libobjc2`), produce the same class metadata, and share the same dispatch mechanism. There is no bridge layer.

**Practical shape:**

- **Same project, mixed files.** `.m` and `.cor` files coexist. Each is compiled by the appropriate toolchain; both produce libobjc2-compatible object files; the standard linker combines them.
- **No runtime translation cost.** Cross-language calls are just runtime method dispatch. There is no boxing, no unboxing, no type marshalling, no ABI bridging.
- **Auto-generated headers.** Corvid classes are exposed to ObjC via auto-generated `-Corvid.h` files. Public APIs are visible to ObjC consumers automatically, with warnings when features don't round-trip.
- **Translation tool available.** Users who want to convert `.m` to `.cor` run a translator that handles the mechanical work. Not required for adoption.

**Rejected alternatives:**

- **Full source-level superset of Objective-C.** Would preserve `[[NSMutableArray alloc] init]`, `@interface`/`@implementation` split, header files, and `@property` storage-attribute soup. Constrains syntax irreversibly.
- **Corvid → ObjC source as the primary compilation strategy.** Used as bootstrap and alternative output path but not primary; LLVM IR direct is the production target.
- **Corvid → ObjC downgrade for library consumers without Corvid compiler.** Would let ObjC consumers use libraries written in Corvid without adopting the compiler, but creates ecosystem fragmentation and disincentivizes adoption. Libraries requiring Corvid features require the Corvid compiler.

**Section-mediated translation.** The translation tool has non-trivial semantic decisions to make when converting `.m` files:

- **`@property` decomposition.** ObjC properties are mutable, public, sometimes copy-on-set. Translator emits them as `state:` section fields unless the user hints otherwise.
- **`_ivar` fields.** Direct instance variables (not property-backed) become `state:` section fields marked as `private` unless the source file marked them `@public`.
- **Class methods (`+` prefixed).** Become methods in a `static:` section.
- **Categories.** Become `category:` sections applied to the target class.
- **`@protocol` declarations.** Become `protocol` declarations. Optional methods become default implementations returning appropriate defaults, with a warning noting the semantic shift.
- **Nullability.** Preserved where annotated; warned where implicit.
- **Method families (`init`, `copy`, `mutableCopy`).** Translated to constructor and copy protocols where possible.

The translator produces output that compiles cleanly under the Corvid compiler but is not necessarily idiomatic. Users review and refine — typically moving stable-identity fields from `state:` to `data:`, adding contracts to methods, marking the module's stable interface with `expose`.

---

## 13. Explicitly Deferred *[UNRESOLVED]*

**Resolved in v0.5** *(previously deferred, now specified):*
- Session types → emerge from class-level invariants + method contracts (§3.4.2). Not a separate feature.
- Effect polymorphism → generic parameters with `E: Effect` (§3.5.3). Explicit form + inference sugar.
- Pattern matching → `switch` with structural patterns and exhaustiveness (§3.7). Sealed protocols enable exhaustive matching over conformers.
- User-alias export → aliases participate in module import/export (§4.16). Public aliases visible to importers.
- Flat composition sugar → removed from deferred; nesting handles the semantic space.
- Reflection API surface → resolved as class metadata (§4.18) with three consumers (LSP, runtime queries, AI-manifest).
- Memory model → resolved as extent-based storage with tags, snapshots, universal handles, ARC baseline, FilC-style safety (§6).

**Still deferred:**

1. **Full comptime metaprogramming** — beyond section generators. Sections + contracts cover the common metaprogramming needs. Full comptime (Zig-style arbitrary compile-time execution) is additive if future needs demand it. Deferring costs nothing given the boundary in §4.17.

2. **Ownership types** — beyond ARC. Rust-like `borrow`/`move` for zero-overhead cases could come in Stage 3+ as an opt-in section (`borrowed:` restricting field access). ARC handles the common case.

3. **Standard library scope** — built-in vs. package boundary. Post-language-ship decision. `core` package always available; everything else via package manager. `Tag<T>` and `Snapshot<T>` in core.

4. **Package manager and build system** — implementation concern; steal from Cargo/npm when we ship v1.0.

5. **`Self` in protocols** — nominal (Rust-style) recommended (§10.2 recommendation); confirmation pending.

6. **Preemptive threading beyond `unsafe:`** — Stage 1 restricts explicit threads to `unsafe:` contexts. Stage 3+ may add safe explicit-thread APIs. Cooperative async covers most needs.

7. **Section versioning scheme** — semver on section classes; precise mechanics pending.

8. **Higher-order sections** — sections taking other sections as parameters. Deliberately excluded from v0.5 (§4.17 boundary). Syntactic space reserved.

9. **Compile-time state tracking for invariants** — Stage 1 uses runtime enforcement; compile-time enforcement Stage 3+ (§3.4.2).

10. **Extent-scoped cycle detection** — Stage 2+ optional library. Precise scheduling and API design pending real usage patterns.

11. **Adaptive storage layout switching** — Stage 3+ optimization. Runtime picks AoS/SoA based on observed access patterns, not just `dense_storage:` hint.

---

## 14. Implementation Stages

The language ships usable at each stage. Programs written for earlier stages benefit from later stages without source changes.

### Stage 0 (Bootstrap) — Corvid → Objective-C Source
- Compiler is written in Objective-C, produces `.m` files from `.cor` input.
- No LLVM IR generation yet.
- Sections implemented for the class-body-only subset. Primitives, aliases, and stage-1 behavioral sections.
- Output `.m` files are ugly but semantically correct. Users compile them with Clang for actual execution.
- Purpose: bootstrap a working compiler quickly so we can write the production compiler in Corvid itself.
- Not shipped as a production tool. Alternative output path preserved permanently (for debugging, ObjC-only-toolchain environments).
- Estimated engineering: 3-6 months for a small team.

### Stage 1 (v0.1) — All Dynamic, LLVM IR Direct, Incremental with LSP
- Compiler is rewritten in Corvid using the Stage 0 bootstrap.
- **Architecturally incremental** — holds state between invocations, supports language-server mode as first-class operation (not a Stage 2+ afterthought).
- Compiler produces LLVM IR that speaks libobjc2 ABI directly.
- Compiler emits **class metadata artifacts** alongside object files (§4.18). Both the runtime metadata format and AI-manifest JSON export.
- **LSP server** shipped as part of the compiler. VS Code, Zed, Neovim, Xcode, JetBrains all get support via LSP client.
- All methods compile to message sends by default. The compiler devirtualizes automatically where safe (§5.2).
- Runtime uses `libobjc2` unmodified plus a small Corvid-metadata wrapper layer.
- Built-in **primitive sections**: `Runtime`, `Compile_time`, `Immutable`, `Mutable`, `Expose`, `Weak`, `Unowned`, `Identity`, `Coordination`. All inline-composable. (Removed from earlier drafts: `Public`, `Private`, `Package`, `Sealed`, `Dynamic` — see §1.2, §5, §7.7.)
- Built-in **aliases**: `data`, `state`, `static`, `methods`. Language-provided; user aliases also supported at module level.
- Built-in **behavioral sections**: `Computed`, `Deprecated`, `Category`, `ObjC_Exposed`, `With`, `Channel`, `Signal`, `Package`. Class-body scope for most; `Package` at source-file-top and module-body scope.
- Built-in sections have hardcoded describers (feed metadata artifacts).
- Section classes not user-definable in Stage 1; built-ins are hardcoded.
- `label:` syntactic space reserved at all scopes (compile error if used outside class bodies).
- Class-level `invariants:` supported; **runtime-enforced** in Stage 1. Contract-carrying methods on mutable state get atomic transition semantics (§1.9, §8.4).
- **Structured async** — `async_scope`, `spawn`, cancellation, error propagation. Work-stealing thread pool for scheduling.
- **State machine synchronization** — `requires`/`ensures` on mutable state enforce atomicity via runtime locks. Async methods wait on unmet preconditions; sync methods fail immediately.
- **Explicit threads via `unsafe:` only** — no safe explicit-thread API in Stage 1.
- File-level ObjC compatibility working (import, auto-derived headers, category support).
- ObjC concurrency interop: GCD dispatch queue bridging, completion-handler ↔ async conversion, `@main_actor` for main-thread affinity.
- Translation tool (`.m` → `.cor`) shipped as separate binary.
- Runtime metadata query API (§4.18.3) for cross-module lookups.
- Estimated engineering: 18-24 months (incremental compilation and LSP are load-bearing additions to earlier 12-18 month estimate).

### Stage 2 (v0.5) — Whole-Module Devirtualization + User-Definable Sections
- Compiler devirtualizes methods with whole-module analysis; call sites deopt on runtime modification.
- **User-definable section classes** for class-body scope. `Data`, `State`, `Methods` reimplemented as ordinary section classes.
- **User-definable describers** — user section classes can contribute to class metadata artifacts. LSP/AI tooling gains full support for user-defined sections.
- `quote:` available inside generators as a specific block form (not yet a general section).
- Ecosystem sections (`channel:`, `persistent:`, etc.) become writable by library authors.
- Estimated engineering: additional 6–9 months.

### Stage 3 (v1.0) — Cross-Module Deopt + Function/Method Body Sections
- Runtime call-site invalidation machinery. Sealed call sites patched on swizzle.
- Function-body and method-body sections. Built-in: `pure:`, `unsafe:`, `validate:`, `quote:` (generalized from generator-body use).
- Section-class-to-section-class composition (self-hosting fully enabled).
- Performance parity with Swift for whole-module-optimized workloads (equivalent of Swift's whole-module optimization pass).
- Estimated engineering: additional 9–15 months.

### Stage 4 (v1.5+) — Module-Body Sections, Optimizations
- Module-body sections. Built-in: `interop:`, `test:`, `platform(...):`.
- Devirtualization across generic instantiations.
- Inline caching improvements.
- Optional SMT-backed contract verification.

The critical property: **user code doesn't change between stages.** The semantic model is fixed at v0.1; later stages only add capabilities and optimize.
