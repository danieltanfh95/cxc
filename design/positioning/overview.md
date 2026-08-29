# Corvid — Positioning Statement

> **v1.0-consolidation supersession note (2026-08-29).** Bet #1
> below ("Sections as unified metaprogramming") describes the
> pre-v1.0 F6 metaprogramming + F7 aliases + F8 user-section-class
> model, retired at the 2026-08-25 v1.0 user-surface consolidation.
> The stage roadmap ("built-in sections" / "user-definable sections
> and describers" through v1.0/v1.5) reflects the pre-consolidation
> plan. Ratified v1.0 mechanism: `use <name>: <T>` capability
> atoms, direct fields, `@name(...)` annotations, `|:`
> propositions. The positioning claims (native-first, Apple-
> ecosystem interop, correctness by construction) stand; the
> specific vocabulary describing HOW does not. See
> [../spec/spec-cxc-v1.0.md](../spec/spec-cxc-v1.0.md) and
> [../../compiler/design/plans/archive/v1-user-surface-retirement-landed.md](../../compiler/design/plans/archive/v1-user-surface-retirement-landed.md).

## What Corvid is

Corvid is a native-first programming language for the Apple ecosystem. It targets macOS, iOS, and Foundation-Swift-compatible servers. It compiles to the same runtime substrate as Objective-C and Swift (libobjc2 / objc4) and interoperates directly with Foundation, AppKit, and UIKit code without a bridge layer.

Corvid is not a research language. It's a working language designed for building shipping applications — Mac apps, iOS apps, backend services that share code with clients — with an emphasis on correctness by construction rather than convention. Contracts, invariants, and state machines are first-class language features that carry documented predicates through the type surface, enforced at method boundaries.

Corvid is not "the next version of Objective-C." The runtime lineage is preserved because it works and enables interop; the source language above that runtime is largely new. Users coming from Swift will find Corvid closer to their experience than users coming from ObjC.

## Who Corvid is for

**Primary audience:** developers building native Apple-ecosystem applications who want more design leverage than Swift provides — specifically, developers who feel Swift's value-type-with-mutating-methods, actor-isolation ceremony, and generic-heavy standard library are working against them rather than for them.

**Secondary audience:** developers building shared-code cross-target systems (Mac + iOS + server) who want a single language across all three tiers without SwiftPM-shape complexity.

**Not the audience:** developers building portable/web/embedded systems (Corvid depends on libobjc2, so effectively Apple-ecosystem-plus-Linux-Foundation-Swift). Developers wanting to modernize existing ObjC codebases in place (Corvid is a source-level replacement, not a modernization of ObjC syntax).

## Competitive positioning

**Vs. Swift:**
- Corvid preserves message-passing dispatch and runtime openness that Swift has been moving away from. If you liked ObjC's "everything is queryable, everything is hookable" and miss it in Swift, Corvid is designed for you.
- Corvid unifies metaprogramming into a single mechanism (sections). Swift has property wrappers, result builders, macros, code generation — several overlapping systems. Corvid has sections.
- Corvid replaces Swift's mutating-methods-on-value-types pattern with the two-type pattern (struct-value + class-builder). More types, more honest about what each does.
- Corvid takes contracts (requires/ensures/invariants) as first-class language features, not comments. Predicates that matter for correctness are enforced.

**Vs. Objective-C:**
- Corvid is not source-compatible with ObjC. You can't take a `.m` file and rename it to `.cor`.
- Corvid has modern syntax (Python-like indentation, no square brackets, no header/impl split).
- Corvid has generics, enumerations with associated values, and pattern matching.
- Corvid preserves ObjC's runtime — categories, KVC, swizzling, dynamic dispatch — as design commitments, not as legacy behavior.

**Vs. Rust:**
- Corvid uses ARC (via libobjc2), not ownership types. Simpler cognitive model for most code; less compile-time safety for concurrent shared-mutable-state code.
- Corvid targets the Apple ecosystem directly. Rust interops with C; Corvid interops with ObjC/Swift.
- Corvid's contracts are runtime-enforced (Stage 1) with compile-time analysis Stage 3+. Rust's borrow checker is compile-time from day one.

## The distinctive technical bets

Six commitments that define Corvid vs. its neighbors:

1. **Sections as unified metaprogramming.** Aliases, primitive attributes, and behavioral extensions are all instances of one mechanism: section classes. Users learn one construct rather than several overlapping ones.

2. **Contracts as coordination primitives.** `requires`/`ensures`/`invariants` document correctness constraints, enforce them at method boundaries, AND provide state-machine synchronization semantics for concurrent access. Three concerns unified into one mechanism.

3. **Universal extents.** Every class instance is trackable and queryable. `MyClass.query()`, `MyClass.count()`, `MyClass.all()` work for any user-declared class without opt-in. Cross-class queries via protocols compose naturally into ECS-style code.

4. **Two-type pattern for construction.** When "under construction" and "constructed" have different runtime shapes, they become two types: a struct for the value, a class for the builder. Swift's mutating-methods-on-value-types pattern is unnecessary.

5. **Intent, not authority.** Every visibility and dispatch annotation expresses author intent, not runtime enforcement. `expose` communicates "part of my stable interface"; the runtime still allows access. This matches Objective-C's runtime philosophy honestly.

6. **The runtime is a shared substrate.** ObjC, Swift, and Corvid all target libobjc2. Corvid classes register with the same class table, dispatch via the same `objc_msgSend`, and participate in the same category/swizzling mechanisms. There is no bridge layer.

## The deployment story

Corvid targets three deployment shapes with a single language:

1. **Native Mac applications** — bundle format, AppKit/SwiftUI interop, Info.plist, code signing. Everything a shipping Mac app needs.

2. **iOS applications** — bundle format, UIKit/SwiftUI interop, App Store submission. Same source language; different target framework surface.

3. **Server-side services** — headless Foundation-Swift on Linux (or macOS). Same language for the API layer that talks to the mobile and desktop clients.

The multiplayer Game of Life reference implementation exercises all three shapes with a shared protocol layer. It's the smallest example that stress-tests concurrency, extents, cross-target code sharing, and Apple framework interop simultaneously.

## The bet Corvid makes

Corvid bets that Apple-ecosystem developers want a language that gives them Swift-level modern conveniences (sum types, pattern matching, generics, structured async) while restoring ObjC-level runtime openness (queryable everything, hookable everything, dynamic dispatch) and adding correctness-by-construction (contracts, invariants) as first-class features.

If that bet is right, Corvid becomes the language people reach for when Swift's abstractions feel like they're getting in the way. If it's wrong, Corvid ends up a curiosity — a well-designed language nobody uses because Swift is good enough.

The Game of Life reference implementation is the empirical test. If it can be built cleanly and demonstrates the runtime and concurrency stories under real load, the bet has landed. If it can't, we've learned what needs to change.

## Timeline and stages

- **Stage 0** — bootstrap (Corvid→ObjC translator in ObjC, 3-6mo).
- **Stage 1** (v0.1) — LLVM IR direct, incremental compilation with LSP, dynamic dispatch, built-in sections. 18-24mo.
- **Stage 2** (v0.5) — whole-module devirtualization, user-definable sections and describers.
- **Stage 3** (v1.0) — cross-module deopt, function-body sections, compile-time invariant enforcement.
- **Stage 4** (v1.5+) — module-body sections, advanced optimizations.

Public v1.0 is roughly 3-4 years of engineering from a standing start. Stage 0 is small enough that a single engineer can drive it in a few months if the design holds up.

## What's undecided

- **Standard library scope.** How much ships with the compiler; how much is separately packaged. Post-v1 concern.
- **Package registry.** No registry exists; deferred to post-v1.
- **Full concurrency model refinements.** State-machine-as-synchronization is committed; specific runtime cost model is Stage 1 implementation work.
- **The name.** "Corvid" is the current working name. Could stick or could change based on trademark research and community response.
