# Corvid Design Charter

The nine design premises below constitute the philosophical foundation of Corvid. Every subsequent design decision in the full specification (§2-§14) descends from these premises. Any change to a decision should first check whether it violates a premise; if it does, the premise should be either revised deliberately or preserved as a constraint.

Read this document before the full specification. It's short enough to internalize (~2000 words) and comprehensive enough that the rest of the spec can be understood as consequences of these premises.

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

**Non-consequence:** intent-not-authority does not apply to semantics that change compilation itself. `async` changes the calling convention; mutability annotations change storage semantics; class kinds (`metadata: kind:`) determine memory layout and dispatch. These are semantic in the sense that different values would be produced under different rules, so they cannot be "just hints." See §3.5, §6.1.

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

