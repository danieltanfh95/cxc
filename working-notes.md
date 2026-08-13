# cxc Working Notes — Design Rationale and Open Items

> **Name.** The language is now **cxc** (pronounced letter-by-letter — "channels-C", positioned in the naming lineage of **objc** / Objective-C). **Corvid** was the codename used throughout the design conversation and is retained as the internal/historical codename; the directory `corvid/` and the `corvid-handoff.zip` archive preserve that history. Older notes in this document that say "Corvid" refer to the language now called cxc — they are historical and left intact.
>
> **Naming history:** `objc5` (initial working name) → **Corvid** (codename during the design conversation, when the positioning as "Objective-C's spiritual successor" clarified) → **cxc** (current public name, after the channels-C framing was validated in outreach as parallel to objc / objective-C).

**Purpose:** consolidate the reasoning behind the current spec and enumerate what's still open. This document captures *why* decisions were made, not just what they are. The spec is the specification; this is the thinking behind it.

**Companion document:** `spec/spec-cxc-v1.0.md` (was `spec-v0.11.md`; earlier lineage — `spec-v0.10.md`, `spec-v0.9.md` through v0.9.x, `spec-v0.8.md` through v0.8.x, `spec-v0.7.md` through v0.7.x, `spec-v0.6.md`, `corvid-spec-v0.5.md`).

## Session note — Phase 6 compiler implementation choices: Clojure + ObjC source emission + libobjc2/libdispatch stack (2026-08-13)

**Trigger.** With the substrate pinned through Phase 4a–4e and the spec swept for consistency in Phase 5-lite, Phase 6 was to decide compiler implementation. Initial framing offered eight sub-threads. Two threads (runtime target, emission target) turned out to be the load-bearing ones, and the decisions there simplified the remaining threads substantially.

**Load-bearing correction during conversation.** I initially framed "target ObjC" as one option among four (compile-to-ObjC-source, link-with-libobjc, own-runtime, semantic-heritage-only) and asserted that Mac-first → Linux-later was expensive because GNUstep was thin. **This was wrong.** Verified against 2026 state: GNUstep's libobjc2 v2.2.1 (March 2026) is superset-parity with Apple's libobjc, supports blocks/properties/ARC, and has expanded platform coverage (RISC-V, Windows ARM64, MinGW). This substantially cheapens Mac-first → Linux-later — the runtime is portable, only AppKit/UIKit is Apple-locked.

**Second load-bearing correction.** User asked "do we actually need LLVM? Could we just emit to C or ObjC?" This was a much better instinct than my starting framing. Analysis: cxc's design is *isomorphic* to ObjC runtime semantics — nil-punning IS ObjC nil-messaging, message dispatch IS `objc_msgSend`, ARC IS ARC, closures ARE blocks, method tables ARE method tables. Not accidental alignment — the design was informed by ObjC. Emitting ObjC source lets clang do all lowering/optimization/platform work; we save 40-80k LOC of LLVM backend and 12-18 months of engineering. Source-to-source is a proven modern strategy (Nim, Vala, Haxe, Chicken, ClojureScript, TypeScript, Elm all do this). Decided: emit ObjC source, not LLVM IR.

**Decisions pinned:**

| # | Decision | Rationale |
|---|---|---|
| 6a | Bootstrap language: **Clojure** | User's declared philosophical alignment; REPL-driven iteration matches design-experiment mode; JVM distribution is fine for a compiler tool. |
| 6b | Emission target: **ObjC source → clang** (NOT LLVM IR) | cxc-to-ObjC-runtime alignment is isomorphic; clang handles optimization/platform/interop free; ~40-80k LOC and 12-18 months saved. |
| 6c | Runtime stack: **libobjc2 (dispatch/ARC) + libdispatch (concurrency) + libuv (I/O) + cxc runtime lib** | libobjc2 portable to Linux/Windows/embedded; libdispatch is what Swift uses cross-platform; libuv handles async I/O. Server-capable v1.0 out of the box. |
| 6d | Compilation strategy: **per-atom to ObjC classes/structs; clang handles LTO** | Atom is the natural unit; manifest as summary; clang cross-file optimization at link time. |
| 6e | Inference: **local within atom; context → smallest-fitting → Int32 canonical** | No cross-atom principal-type inference (§1.11); bare-literal resolution rules pinned. |
| 6f | Contract-check: **local static discharge; runtime check otherwise; SMT deferred to v1.5+** | v1.0 must be buildable without external solvers. Two-construction-form (`Foo{raw:n}` abort + `Foo.try(...)` Result) per §3.6 pinned. |
| 6g | Kind-based lowering: **regular=ObjC class, record=C typedef/struct, enum=tagged union, protocol=`@protocol`, primitive=erased typedef** | Follows Swift's class/struct split. Wrapper elision (`UInt8 = typedef uint8_t`) is zero-cost. |
| 6h | Manifest tooling: **`.h` + `.cxc-manifest`, both auto-generated + checked in** | `.h` for ObjC/Swift interop; `.cxc-manifest` for cxc-specific facts (contracts, `use:` closure, transitions, invariants, receive-glyph subs). |

**Concurrency runtime specifics.** Atom = plain object + shared scheduler (BEAM-style, not one-thread-per-atom). Scheduler built on top of libdispatch's work-stealing thread pool. Mailboxes are lock-free MPSC queues. Preemption at receive-glyph/message boundaries. Millions of atoms per process supported.

**Distribution shape.** ObjC-compatible frameworks. Both ObjC and Swift devs consume the same artifact via Swift's ObjC-import machinery. No separate `.swiftmodule` story required. Executables and libraries both fall out of the same compiler.

**BEAM deferred to v1.5+.** Motivation: BEAM's unique wins (millions-of-processes scale, hot code reload, distributed nodes, supervisor trees) matter for a specific niche (telecom, high-availability streaming). Most server work is fine on libdispatch. Adding BEAM later is a compilation-strategy change, not a language change — provided we keep §5 (Dispatch) host-abstract, which it largely is.

**Optics guard on BEAM discussion.** If cxc marketing leads with "runs on BEAM", it invites "why not Elixir?" comparisons that anchor cxc as niche. Correct framing: cxc is an atom-substrate language with contracts, primarily targeting Apple platforms via libobjc2; runtime is implementation detail. Kotlin/Clojure pattern of language-first, host-as-deployment-target. Sequencing (Apple identity first, BEAM backend later) minimizes the optics risk.

**Retracted framings:**

- "GNUstep is thin, Linux port is expensive" — factually wrong per verified 2026 state.
- "LLVM IR emission is the real compiler target; ObjC source is bootstrap-only" — over-engineering for cxc specifically; ObjC source is the natural long-term target given design alignment.
- "Rust is the obvious bootstrap choice for compiler perf" — Clojure matches the design philosophy better; compiler perf is not v1.0-critical.

**Estimated v1.0 scope:** ~60-110k LOC across compiler (Clojure, 30-50k) + runtime library (ObjC+C, 10-20k) + stdlib (cxc + prelude bindings, 20-40k). 12-18 months for a small team.

**Estimated LOC savings from ObjC-source vs LLVM-IR strategy:** 40-80k LOC and 12-18 months of engineering.

**Deferred beyond v1.0:**

- SMT backend for static contract discharge (v1.5+)
- BEAM backend as second host (v1.5+ or beyond)
- C-only emission target for embedded/WASM without libobjc2 (v2.0+)
- Custom LLVM passes for hot-path optimization (v2.0+ if needed)
- `cxc show-graph <package>` composition visualizer (v1.5+)
- Self-hosting cxc compiler in cxc (v1.5+ target)

**Open items for Phase 7 (build):**

- Grammar formalization for the Clojure parser (Instaparse likely)
- Contract-inference pass architecture
- ObjC AST representation in Clojure (data structure design)
- Runtime library API design (scheduler, mailboxes, contract-check helpers)
- Test strategy — how do we prove the compiler is correct?
- Bootstrap sequence — order-of-attack for implementation

---

## Session note — Phase 5-lite consistency sweep: supersession banners on §3.5, §3.6, §4, §6, §8, plus §5.6.1 generic-bracket migration (2026-08-13)

**Trigger.** After Phase 4a–4e pinned the substrate (atom-substrate concurrency, wire retirement, contracts-on-shapes, `use:` as effect signature), §3–§9 of the spec still described the v0.11 intermediate shape (class/record/enum/protocol as primary declaration forms; `state:`/`channels:`/`entrypoints:`/`wires:`/`handlers:` sections; `async` semantic effect; `async_scope`/`spawn`/`await`/`Task<T>` concurrency; `type X = Y requires ...` refinement syntax; `Callable<Args, Return>` generic-bracket). Full restructure into atom sub-forms is a big documentation lift; the immediate goal was to prevent a reader who lands on §3–§9 without reading §1.11 first from being actively misled.

**Chosen approach.** Phase 5-lite = supersession banners at the top of affected sections + one migration note where the syntax has changed. Full restructure deferred to a v1.1 documentation pass. Rationale: banners are cheap, load-bearing (they redirect readers to the current authoritative section), and don't lock in a specific new prose structure prematurely. The retirement lists in §10 grammar were already comprehensive; the sweep adds forward pointers from the old sections to the current substrate.

**Edits applied (spec-cxc-v1.0.md):**

1. **§3.5 (Effects)** — added supersession banner pointing to §1.12 use:-as-effect. States: advisory/semantic effect framing (`allocates`, `blocks`, `async`, `with E`) is superseded; real effects enter through Extern → prelude capability atoms → transitive `use:` closure; no separate effect labels; effect polymorphism = capability substitution; effect handlers deferred. §3.5.4 (failure via nil-punning / domain enums, no `throws`) carries unchanged.

2. **§3.6 (Refinement)** — added v1.0 note that the canonical form is `def X: Y where <predicate>` (§1.11 contracts-on-shapes), not `type X = Y requires ...`. Kept old form parseable through v0.15 grace period. Added a paired v1.0-canonical example alongside the v0.11 example. Added the invariant-check policy (runtime at construction with `Age{raw:n}` abort + `Age.try(...)` Result path; static discharge via SMT is optional).

3. **§4 (Sections)** — added v1.0 note listing which body sections are retired under the atom substrate (`state:`, `channels:`, `entrypoints:`, `wires:`, `handlers:` as grouping container) and which are primary for atoms (`data:`, `use:`, `invariants:`, `contracts:`, `constructor:`, `metadata:` + per-transition `then:` / `do:`). The sections MECHANISM itself carries unchanged; only the vocabulary of meaningful body sections changed.

4. **§5.6.1 (Callable type expression)** — migrated header + example from `Callable<Args, Return>` generic-bracket to `Callable of (Args, Return)`. Added grace-period note. Retired the `with async` example in the effects paragraph; replaced with note that effects on callables = transitive `use:` closure of the target atom.

5. **§6 (Memory Model)** — added v1.0 note that class-kinds framing carries but the "no `state:`" table entries are moot because there is no `state:` for any kind. `regular` = stateful atom (handle + ARC); `record` = value-typed atom (only `data:`, no transitions); `enum` = value-typed atom with `cases:`; `protocol` = named contract. Handle-based reference model + ARC unchanged for regular-kind.

6. **§8 (Concurrency)** — added supersession banner. States: v1.0 model is atom-per-actor with cooperative work-stealing, sender/receiver blocking as independent axes, `|>` = processing-sync ordering, `<|` = blocking consumer, `->`/`:>` = non-blocking. Retired: `async_scope`, `spawn task`, `await task`, `Task<T>`, `async function`. What carries: the general no-single-scheduling-regime policy, reader/writer classification concept (re-expressed as which transitions read vs write-transition an atom's `data:`), the "one class owns shared state" argument (now trivially true — atoms are the sole state carriers).

**Retracted framings:**

- Full §3–§9 rewrite in one lift — rejected as over-scoped for this pass; deferred.
- Editing every `state:` / `channels:` / `Callable<>` reference in place — rejected; banners plus §10 retirement lists are sufficient for a lite pass. Full sweep in the v1.1 restructure.

**Deferred to v1.1 documentation pass:**

- Full atom-first rewrite of §3 (Type System) — lead with `def` atom shapes; present class/record/enum/protocol as sugar-keyword equivalents.
- Full rewrite of §3.5 (Effects) as pointer only — remove §3.5.1–§3.5.3 body entirely; §3.5.4 (failure) stays or moves.
- Full rewrite of §8 (Concurrency) — replace body with atom-substrate concurrency reference; keep §8.4.1 reader/writer classification re-expressed for transitions.
- Sweep in-body examples that still use `state:` / `channels:` / `entrypoints:` / `Callable<T>` / `Future<T>` / `Signal<T>` / `async function` — several dozen sites.
- Reconcile §4.10 built-in section classes catalog with retired-section list.

**Open items after Phase 5-lite:**

- Phase 6 (compiler implementation choices) — bootstrap language, runtime model, whole-program vs modular vs hybrid, manifest tooling, wrapper-elision aggressiveness, bare-`hp = 100` inference strategy, contract-check discharge policy.
- Phase 7 (build the compiler) — depends on Phase 6.

---

## Session note — Phase 4e effects: `use:` closure IS the effect signature; no separate effect system; pure-by-default falls out structurally (2026-08-13)

**Trigger.** Phase 4e initially framed as "should we ship effect labels?" — after 4c pinned contracts-are-types, the framing changed. User pointed out: real-world effects can only come from FFI; FFI is `Extern`-provisioned prelude shims wrapped as capability atoms; user code accesses via `use:`; therefore effect tracking is already done for free by the substrate.

**Decisions pinned:**

1. **No separate effect system.** cxc's effect story is structural: atoms have no OS access; the only path is via `Extern`-backed capability atoms in prelude; user code uses these via `use:`. An atom's transitive `use:` closure IS its effect signature.

2. **An atom with no `use:` is structurally pure over its inputs.** No unsafe backdoor exists — no path to the OS without a `use:`-declared capability. Stronger purity guarantee than most languages' `@Pure` annotations.

3. **Effect polymorphism = capability substitution.** `use: clock: Clock` accepts any atom conforming to `Clock`. Structural conformance handles it. No effect algebra machinery.

4. **Effect inference = `use:` closure computation.** Compiler walks the transitive `use:` set. Zero annotation cost.

5. **Adding new effects = shipping new prelude capability atoms.** Extending the effect vocabulary is stdlib work (new capability atoms backed by FFI), not language work.

6. **Retired earlier proposal — `can: clock, can: random, can: io` labels.** Redundant; the `use:` names ARE the labels.

**Retracted framings:**

- "Ship effect labels for documentation value" — retired; `use:` names already carry the same info with no parallel system.
- Effect polymorphism as a v1.0 concern — dissolves under structural conformance.

**Spec edits applied:**

- Spec §1.12 (Stdlib capability shape drafts) — new subsection "Effects — `use:` closure is the effect signature" after Stdlib catalog. States the reasoning chain (no OS access → Extern only in shims → capabilities via prelude → `use:` propagation → effect closure derived) and consequences (pure-by-default, polymorphism via substitution, inference via closure, no `pure:` / `can:` markers).

**Deferred beyond v1.0:**

- Termination guarantees (Idris-style totality) — a transition body could loop; not required for v1.0.
- Effect handlers with resume (Koka-style algebraic effects with continuation capture) — env substitution + `Result` types cover most needs; full handler algebra is v2.0+ if real usage demands.
- Phase-scoped `use:` (e.g., "clock only during `running` phase") — `use:` is currently atom-scoped; refinement reservable if needed.

**Note on how this compares to other languages:**

| Language | Effect tracking | Cost |
|---|---|---|
| C / Python / JS / Ruby | None | Runtime surprises, hidden dependencies |
| Java | Checked exceptions (narrow) | Verbose, oft-disabled with unchecked |
| Rust | `unsafe`, `async` markers | Local; no general effect vocabulary |
| Haskell | IO monad, mtl / freer / polysemy | Monad transformer stack tax |
| Koka / Eff / Frank | Full algebraic effects with handlers | Learning curve; research-adjacent |
| Idris / Agda | Totality + type-level purity | Research-tier, not mainstream |
| Racket | Parameterize (dynamic scope) | Dynamic dispatch cost |
| Clojure | Convention + `!` naming | No enforcement |
| **cxc** | **Capability atoms + `use:` propagation** | **Zero annotation tax; structural guarantee** |

Corvid's approach reaches most of the practical value of full effect systems (know-what-a-function-touches, mock for tests, capability-based security, refactor safety) without the ergonomic tax that keeps Haskell/Koka out of mainstream use. Traded away: effect handlers with continuation capture (advanced control flow), effect-polymorphic library authoring beyond structural conformance. Both v2.0 possibilities if real usage surfaces demand.

## Session note — Phase 4c contract inference: types are named contracts on shapes; interpretation via `value :<` observables on derived types; bit_ops prelude for pure bit primitives; aggressive within-atom inference, no cross-atom principal-type inference; invariants checked at construction (statically proved or runtime); worked derivation in explorations/integers-from-bits.cxc (2026-08-13)

**Trigger.** Phase 4c set out to pin the type-inference story. Initial draft carried "declare at boundaries, infer inside" (ML-flavored). User pushed back:
- `:>` already declares emissions; no need for a second `emits:` section
- Aggressive inference should be the goal, not annotation walls
- cxc uses contracts, not types (§1.3) — reframe accordingly

Follow-up session (same day) went one level deeper: types aren't primitives with refinements bolted on. **Types ARE contracts on shapes, all the way down to Bit.** UInt8, Int8, Char are different interpretations of the same 8 bits, spelled as different `value :<` observables and different invariants. Same mechanism at every level (Bit → Byte → UInt8 → Age); no primitive/refined distinction.

Worked out the derivation in a new exploration file (`explorations/integers-from-bits.cxc`) — Bit → Byte → UInt8/Int8/Char → Nat/Age → UInt16/UInt32 — with operations as contract choices (plus vs wrapping_plus vs saturating_plus), conversion patterns (same-width reinterpretation via wrap, narrow/widen via prelude), and invariant-check semantics (statically proved when derivable, runtime check at construction otherwise).

**Decisions pinned:**

1. **Types are named contracts on shapes.** No primitive/refined distinction. Every type is `def <name>: <underlying>` with optional `value :<` observable (interpretation) and optional `invariants:` (predicate). One mechanism at every level.

2. **Value-typed atoms are the substrate for values.** An atom with only `data:` (no phases/transitions) is value-typed. `data:` is immutable per-instance. Every integer, char, enum, record is a value-typed atom.

3. **Interpretation lives on the derived type, not the underlying shape.** Byte is 8 bits with a count invariant, no interpretation. UInt8/Int8/Char each wrap Byte and provide their own `value :<`. Adding new interpretations (`Fixed48Point`, `IPv4Octet`) defines new types; Byte stays uninterpreted.

4. **`bit_ops` prelude module carries pure bit primitives.** `decode_uint`, `decode_int_2c`, `ascii_decode`, `add_bytes`, `narrow_*`, `widen_*` — pure functions with contracts. Not language substrate; compiler may special-case for perf but surface is functions.

5. **One container type — `List of T` with count invariants.** No separate `Array`. `invariants: xs.count == N` → fixed-length packed. `invariants: xs.count <= N` → bounded capacity. No count invariant → dynamic growable. Compiler picks representation.

6. **Operations are contract choices, not type choices.** `plus` / `wrapping_plus` / `saturating_plus` are different `requires`/`ensures` on the same shape. Author picks by name.

7. **Conversion is explicit at the call site.** Same-width bit-reinterpretation via wrap (`Int8{raw: u8.raw}` — native, no function). Value-preserving narrow/widen via `bit_ops.narrow_*` / `bit_ops.widen_*` with contracts. No auto-conversion, no `.into()` magic, no implicit coercion.

8. **Aggressive contract inference within an atom.** Author writes names; compiler derives contracts from usage. Annotate only what you want to CONSTRAIN beyond what usage implies.

9. **No cross-atom principal-type inference.** Each atom's public surface is derived from its own body only. Callers check against the derived surface at composition sites. Reason: stability; local surfaces bound blast radius of body changes.

10. **`where` is the sole author-written contract on interfaces.** Everywhere else: inference + manifest.

11. **Invariants checked once at construction.** Observables are pure over immutable data; re-checking on read wastes cycles. Two paths: statically proved (UInt8 case — invariant derives from upstream) → no runtime cost; runtime check at construction (Age case — invariant narrower than upstream) → check emits at `Age{raw: nat}`. Contract violation → abort. No disable-in-release.

12. **Two construction forms per type — direct and try.** Compiler auto-generates: `Age{raw: n}` asserts invariant; `Age.try(raw: n) -> Result of Age` returns Result. Same underlying check, different failure modes.

13. **Compiler generates a manifest per atom.** Derived surface (I/O, emissions, phases, observables, inferred constraints) as inspectable artifact — IDE overlay or `cxc show-manifest <path>`. Reader's model of an atom's public shape without reading the body. Manifest diffs surface API drift when body edits ripple through inferred contracts.

14. **No `emits:` section.** `:>` IS the declaration. `data: field = value` (no type) is enough — shape derived from RHS. No `Int(0..255)` refinement sugar — `def <name>: <underlying> where <predicate>` is uniform at every level.

**Retracted from earlier same-session drafts:**

- "Declare at boundaries, infer inside" — retracted; replaced with aggressive inference + explicit `where` for constraints.
- `Int(0..255)` / `Int[0..255]` refinement sugar — retracted; the general `def name: underlying where predicate` mechanism handles all levels.
- `emits:` section proposal — retracted; `:>` already IS the declaration.
- `Array(of: T, len: N)` distinct container type — retracted; `List of T` with count invariant handles fixed/bounded/dynamic uniformly.
- Interpretation observables (`as_uint`, `as_int_2c`, `as_char`) on Byte — retracted; interpretations move to derived types.

**Spec / example edits applied:**

- Spec §1.11 — new subsection "Contracts on shapes — types as named contracts" summarizing the derivation with UInt8/Int8 example, `bit_ops` prelude, invariant-check paths, two construction forms, manifest tool, and deferred-to-Phase-6 items.
- Spec §1.3 — cross-reference forward to §1.11 subsection + note distinguishing full `def` atoms with `data:` + `invariants:` (legitimate) from terse `def Nat: Int where value >= 0` type-alias-with-refinement (retracted design; still banned).
- explorations/integers-from-bits.cxc — new exploration file with the full bits-up derivation, worked through in this session.

**Deferred to Phase 6 (compiler implementation):**

- How much whole-program analysis (per-atom local vs LTO across statically-wired subgraphs vs JIT specialization for hot paths). Semantic model above holds regardless.
- Wrapper-elision aggressiveness. Compiler should eliminate the layer for value-typed atoms; how aggressively is impl choice.
- Manifest tooling ship shape (`cxc show-manifest` CLI, IDE overlay format, diff-mode for CI, etc.). Required for v1.0 launch — reasoning about performance from source alone is impossible without it.
- Bare `hp = 100` inference — smallest-fitting type vs canonical-wider type vs defer-to-usage. Semantics allow any; performance profile differs.
- Contract-violation error message quality at composition sites.
- Endian choice (per-target default vs per-type UInt16LE/BE vs per-arg `endian:`). Exploration file uses per-arg for now.

**Phase 4d absorbed into 4c (2026-08-13).** Contract propagation across the wire graph is not a separate design layer — it IS the composition check established here. Because types ARE contracts on shapes, and every composition site (wire, method call, construction, sub-atom emission) checks sender's derived output against receiver's derived input, contract propagation falls out of the same machinery already spec'd:

- **Wire sites** (`<phase> <: source.event{args} -> ...`) — sender's derived output contract must satisfy receiver's declared/derived input contract. Statically proved when possible; runtime check emitted at the wire otherwise.
- **Operation calls** — `plus(x, y)`: x/y's contracts feed `plus`'s `requires`; `plus`'s `ensures` determines the result's contract.
- **Construction** — `UInt8{raw: byte}`: byte's contracts must satisfy UInt8's `data:` spec + `invariants:`.
- **Sub-atom emission (fan-in on containers)** — same rule at the fan-in stream's endpoints.

**Bounded to one hop.** Receiver sees the sender's DECLARED/INFERRED surface, not chains from further upstream. In `A → B → C`, A's `ensures` feeds B's `requires`; B's OUTPUT contract is B's declared/inferred `ensures`, not derived from A's input. Otherwise contracts become uncomputable across arbitrary graphs and refactoring B silently breaks C. Same reason cross-atom principal-type inference is forbidden (see decision #9 above).

**Same runtime/static discharge as construction-time checks.** Compiler proves at composition site when it can; emits runtime check at the wire when it can't. Contract violation → abort (or Result via `try_*` form at the sender's construction point). No new machinery.

**Nothing new to spec.** The §1.11 "Contracts on shapes" subsection already covers this pattern for construction; wire sites are the same pattern applied to composition. No separate "propagation" section needed. Diagnostic quality at wire failure sites remains as a Phase 6 deferred item (compiler tooling).

## Session note — Phase 4a fan-in extension: dotting into nested-atom emissions works to any depth; container atoms expose fan-in outputs synthesized from element type's emissions; no encapsulation gate (2026-08-13, same-day follow-up to Phase 4a grammar completion below)

**Trigger.** Phase 4a left one deferred item — "dynamic per-item subscriptions" — with a parent-sink workaround (child atom takes an explicit sink parameter; child sends to sink on the relevant event; parent receives normally). Working through the pattern, the user surfaced that this is authority-based encapsulation dressed up as design — retracted in favor of the more general dotting rule below.

**Decisions pinned:**

1. **Container atoms have fan-in outputs.** `List of T`, `Map of (K, V)`, `Set of T`, `Tree`, etc. expose two kinds of emissions on their output surface:
   - **Own collection-lifecycle events**: `element_added(e: T)`, `element_removed(e: T)`, `size_changed(new_size: Int)`.
   - **Fan-in of element emissions**: for each `E{args}` the element type emits, container synthesizes `E{e: T, ...args}` — element identity plus original event payload.
   
   Not language magic. Part of what "container atom" means as a type shape. Compiler synthesizes fan-in signatures from element type's emission signature via the parametric-atom machinery `List of T` already needs for its element-typed API.

2. **Dotting into nested atom emissions works to any depth. No encapsulation gate.** Receive-glyph source expressions can chain through nested atom fields: `spawner.active_enemies.died{e: Enemy}`, `game.spawner.active_enemies.died{e: Enemy}`, etc. The compiler checks the chain resolves to a valid emission source at the leaf. Nested atom fields are NOT implicitly private; no forced middleman re-exposure at each layer.

    Retraction: an earlier draft of this rule had a "one-hop only, must re-expose via own emission at each layer" restriction. That was OOP-encapsulation reflex — private fields, getter/setter walls — retracted for violating §1.2 (author intent, not authority). cxc inherits the Clojure "if you can see it, you can use it" stance, same as `get-in` into a nested map.

3. **Refactor trade-off is the author's call, not the language's.** Direct dotting into deep chains is fragile: if an intermediate type refactors (e.g., `List of Enemy` → `Map of (EnemyId, Enemy)`), subscribers break. Re-exposing at each layer via own emissions is stable across such refactors but more code. Language stays out of the trade-off; author picks per situation.

4. **Ambiguity resolution — own event wins over fan-in.** If a container has its own event with the same name as an element event, container's own event wins. Explicit `.any.<event>` form disambiguates when needed:
   ```
   active_enemies.died         # own if declared, fan-in otherwise
   active_enemies.any.died     # always fan-in, never ambiguous
   ```
   Compiler warns on the collision to force explicit disambiguation.

5. **No `private` marker at v1.0.** Two workarounds available for authors who want opacity: wrap the collection in an opaque atom type that omits the underlying outputs, or discipline (don't dot into things you don't want to depend on). Clojure ships fine without `private`; §1.2 says author intent decides. If real usage surfaces demand for enforced hiding, add opt-in later.

6. **Deferred item "dynamic per-item subscriptions" resolved.** Parent-sink workaround retired. Enemy no longer needs a `death_sink` parameter; parent subscribes to `active_enemies.died` directly. Coupling stays pull-based; child stays oblivious.

**Spec / PATTERNS / examples edits applied:**

- Spec §1.11 — new "Dotting into nested atom emissions" subsection: general rule (chain to any depth), containers-as-atoms with fan-in outputs (own emissions + synthesized element fan-in), ambiguity resolution (own wins; `.any.<event>` disambiguates), refactor trade-off called out in prose. Concrete before/after example of dropping the parent-sink workaround.
- examples/game-toys.cxc — Enemy loses `death_sink` parameter; emits `:> died` and cascades to `end`. EnemySpawner subscribes to `active_enemies.died` fan-in directly; spawn transition uses `then:` to add the new enemy to the collection. Header comment on EnemySpawner rewritten to describe the fan-in pattern.

**Retired framings:**

- Parent-sink workaround (`death_sink: DeathSink` parameter on child atoms).
- "Encapsulation gate" one-hop dotting rule (from earlier same-session draft — never landed in spec).
- Middleman re-exposure requirement.

**Open items — stdlib collection semantics (forward work):**

- Concrete emission shapes for `List of T`, `Map of (K, V)`, `Set of T`, `Tree` — own-lifecycle events + fan-in surface + probably ordering guarantees for the fan-in stream (e.g., when two elements emit "simultaneously," what does the fan-in stream see?). We had earlier discussion about collection semantics that touches this — revisit when doing stdlib design work.
- `.any` explicit fan-in form — grammar reserved; wording in §1.11 says "if you want to disambiguate." Concrete grammar productions for `.any.<event>`, `.first.<event>`, etc. deferred.
- Cascade cleanup ordering when a child dies mid-emission through the fan-in stream — spec says child's `dead -> end` cascades and List's `element_removed` fires; exact interleaving with the `died` fan-in event needs a pinning pass.

## Session note — Phase 4a grammar completion: free-standing wires retired; `~>` retired; `|>` roles collapse to (stream composition inside receive-glyph source expr) + (sync send in `do:`); nil-punning on sub-atom slots (2026-08-13)

**Trigger.** Phase 4a set out to pin three grammar gaps (sub-atom slot declarations, standing-wire syntax details, modifier reach). Working through examples, the user surfaced two structural collapses that removed most of the problem.

**Decisions pinned:**

1. **Sub-atom slots use nil-punning; no `?` on the type.** `action: ActionMachine = nil` — atom-typed fields can hold `nil` without an explicit optional wrapper (§3.6.1). Population via `then: action => ActionMachine{...}`; auto-nil on sub-atom's `end` restores `nil`; `is nil` guard reads it back. No slot-specific declaration form beyond ordinary `data:` fields.

2. **Free-standing wires at atom-body top-level are retired.** No top-level `expr |> target`. Every subscription is a receive-glyph transition (`<:` / `<|`) whose source expression may address a capability, sub-atom emission, or extern stream, and optionally compose transformers.

3. **Stream composition lives inside receive-glyph source expressions.** `|>` in the source position composes anonymous transformer atoms:
   ```
   running <: mouse.moves |> Debounce{duration: 200ms} {pos: Position} -> running
       do: cursor :> update(pos: pos)
   ```
   Each `|> Transformer{args}` is an anonymous sub-atom bound to the enclosing phase's lifecycle — instantiated on entry, cascaded to `end` on exit, fresh state on re-entry. Compiler generates the equivalent slot + forwarding behind the scenes.

4. **Args block position — end of source expression, required.** `{arg: Type, ...}` attaches after all transformers, describes the message shape at the receive point. Passthrough transformers (Debounce, Throttle, Filter) preserve the source's shape; reshaping ones (Map, Reduce) determine their own output shape. No elision from source signature — explicit at the boundary.

5. **Two spellings for stream transformation, one runtime mechanism:**
   - **Anonymous inline** (`|>` in source expr) — throwaway transformers, short chains.
   - **Named sub-atom slot** — long-lived, inspectable, or emits multiple things beyond the transformed stream.

6. **`~>` glyph retired.** Time-deferred wire composition is retired. Delayed / time-shaped behavior is either a time-guarded transition (`<phase> when: clock.after(deadline) -> <target>`) or a Delay/Throttle transformer inside a receive-glyph source expression. No dedicated wire-with-delay glyph.

7. **Handler-body one-liner forms retired.** `-> handler |> wire_expr` and `-> handler ~> wire_expr` retired. Handler bodies are multi-line blocks only.

8. **Glyph inventory shrinks from seven to six.** Retained: `->`, `|>`, `:<`, `:>`, `<:`, `<|`. Retired: `~>`. `|>` positions are cleanly separable: source-expression composition inside `<:` / `<|` headers, and sync send inside `do:` blocks.

**Rationale — why free wires died.**

The pattern `source |> handler` is equivalent to a receive-glyph transition on the same source: `<phase> <: source{args} -> <phase> do: handler(args)`. Two spellings for the same graph edge; keeping both is redundant. The receive-glyph form is more explicit (phase context, args binding) and composes with the substrate's phase-scoping (subscription lifecycle is phase lifecycle). Removing free wires collapses one concept, one grammar production, and one class of ambiguity ("when does a top-level wire fire during construction?"). The `|>` glyph doesn't disappear — it moves into the source expression where it has the same "compose transformers" role, just spelled inline.

**Consequences for existing patterns:**

- **Multi-phase atoms with per-phase subscriptions** — declare receive-glyph transitions in each phase's source-and-trigger form. No shared "atom-lifetime" wire to duplicate.
- **Transformer chains (debounce, throttle, batch, filter, map)** — inline `|> Transformer{args}` in receive-glyph source expressions, or slot out as named sub-atoms when inspection / long-lived state matters.
- **Time-deferred dispatch** — `when: clock.after(deadline)` guard on a transition, or Delay transformer in source expression.
- **Dynamic per-item subscriptions (e.g., "subscribe to each newly-spawned enemy's `died` event")** — **resolved same session (2026-08-13) by the fan-in extension below.** Container atoms (`List of T`, `Map of (K, V)`, `Set of T`, `Tree`, ...) expose fan-in outputs synthesized from element type's emissions: `active_enemies.died{e: Enemy}` reads as "any enemy in the list emitted `died`; `e` binds to which one." Fully replaces the parent-sink workaround; child atom stays oblivious to its parent.

**Spec / PATTERNS / examples edits applied:**

- Spec §1.11 wiring-glyph table trimmed to six rows; `|>` role rewritten (source-expression composition + sync send); `~>` retired with rationale + migration; new "Stream composition in receive-glyph source expressions" subsection with grammar sketch, semantics, and two-spellings table.
- Spec §1.11 standing-subscription references throughout — retired or rewritten to receive-glyph form. Sub-atom "standing wire" coordination pattern rewritten as "continuous stream from sub to parent" with receive-glyph example.
- Spec §1.12 debounce example rewritten as a proper 2-phase state machine (idle/waiting) with time-guarded transition — no more `clock.after(duration) |> handler` one-shot wire inside `do:`.
- Spec §1.14 WallClock example rewritten: outbound `after` handler uses receive-glyph on self; inbound `timer_callbacks` consumed via receive-glyph transition — no more `timer_callbacks |> self.timer_fired` top-level wire.
- Spec §2.6 reserved-glyphs table trimmed; disambiguation rewritten (source-expression position vs `do:` position); retired-forms list adds top-level `|>`, `~>`, one-liner handler bodies.
- Spec §10 grammar: retired `standing_wire_decl` and `wire_expr`; extended `transition_trigger` with `receive_source` production supporting transformer chains; retired one-liner handler-body forms; retired `~>` productions.
- PATTERNS.md: "I need blocking vs non-blocking message sends" section unchanged from 4b. "I need mixed `->` and `|>`" section replaced with "I need to compose transformers on a stream" showing inline-transformer syntax. "I need a `wires:` section" section rewritten with receive-glyph pattern; top-level wires marked retired. `~>` occurrences retired. Handler one-liner form migrated to multi-line body.
- examples/game-toys.cxc header rewritten with the "no free wires" convention. Paddle, Player, EnemySpawner, Game all migrated: `input.key_events |> self` → per-event receive-glyph transitions with `input.pressed{key: Key} when: key == ...`; `clock.every(rate) |> ...` → `self <: clock.every(rate){_: Instant}` receive-glyph transition; `e.died |> ...` (dynamic per-enemy wire) → death-sink pattern with Enemy taking a `death_sink` parameter.

**Retired framings (do not carry forward):**

- Free-standing wires at atom-body top-level (`source |> target` as a bare declaration).
- `~>` time-deferred wire glyph.
- `-> handler |> wire_expr` and `-> handler ~> wire_expr` one-liner handler bodies.
- "Seven wiring glyphs" — now six.
- The framing that wires are a distinct composition primitive separate from subscription — they're not; subscription is a receive-glyph transition and stream transformation is inline `|>` inside the source expression.

**Open questions deferred:**

- **~~Dynamic-collection subscriptions~~** — resolved. See fan-in extension note below.
- **Args-block elision from source signature** — currently required always. Whether inference from source's declared emission shape should elide the args block is deferred; explicit-required is the safe default.
- **Transformer library shape** — Debounce, Throttle, Filter, Map, Reduce, Batch, DistinctUntilChanged shipped as prelude atoms with a standard `input` / `output` shape. Detailed prelude definitions deferred.

## Session note — Phase 4b runtime concurrency model pinned: actor-per-atom, cooperative work-stealing, sender/receiver blocking as independent axes, `|>` as processing-sync (no reply value) (2026-08-12)

**Trigger.** Phase 4 (substrate gap closure) started with the runtime concurrency model. The prior spec text framed send/receive as symmetric mirrors that "must match" (`:>` ↔ `<:`, `|>` ↔ `<|`), and described `|>` as "sender blocks until receiver accepts" — RPC-flavored language that quietly imported a reply obligation that was never designed. User pressure ("is there actually a reply?") surfaced that the reply framing was smuggled priors, not derived.

**Decisions pinned:**

1. **Transitions atomic per atom, concurrent across atoms.** Classic actor model. Each atom serializes its own transitions on its own mailbox; different atoms run concurrently. No shared mutable state — state is replaced per transition, not mutated — so no cross-atom synchronization primitives needed.

2. **Memory model: value-based, no torn reads.** Cross-atom reads (observables, standing wires) see a coherent snapshot because there is nothing being mutated in place. Snapshot at wire-propagation time is the value; subsequent transitions produce new values.

3. **Scheduling: cooperative work-stealing pool.** Worker picks up any atom with pending work, runs one transition to completion (or to a `|>` suspension point), releases. No preemption inside a transition. Worker count = runtime knob (default hardware concurrency).

4. **Sender and receiver blocking are independent axes.** Old "must match" constraint retired. Any sender glyph (`:>` / `|>`) pairs with any receiver glyph (`<:` / `<|`). Four legal combinations: async-async (actor), async-sync (producer-consumer), sync-sync (rendezvous), sync-async (sender waits, receiver interleaves). The wire is always mailbox-buffered; each endpoint declares its own local behavior.

5. **`|>` is processing sync — pure ordering guarantee, no reply value.** Sender's transition suspends until receiver's transition on this message has fired. Sender knows the state advance happened. If sender needs data back, wire an explicit reply channel using the two-message pattern (`:>` request, `<:` response). No implicit reply channel exists.

    **Why no reply:** receive sites are phase-scoped. A reply-message R has a `<:` on the sender's side that exists only in whichever phase declares it. If `|>` baked in a reply obligation, the compiler would have to prove sender's reply channel is active at reply-arrival time — phase-dependent, hard to check statically without threading phase information through the wire graph. Keeping `|>` as ordering-only sidesteps the whole class of check. This is a case where a substrate constraint (phase-scoped receivers) killed a bad design option before it could be considered.

6. **`|>` suspends the sending atom, not just the sending transition.** Correction to earlier spec text that said "blocks the sending transition; other transitions on the atom may still fire." Under actor-per-atom, an atom is single-threaded — one transition at a time. When a `|>` suspends, the worker moves to other atoms; the sending atom's mailbox accumulates; no new transitions on that atom fire until the parked one resumes and completes.

7. **Circular sync sends deadlock.** Same failure mode as CSP everywhere. No language-level cycle detection; user discipline. Recommendation: default to `:>`; reach for `|>` only when ordering-with-processing is genuinely required.

**Spec / PATTERNS edits applied:**

- Spec §1.11 "Send/receive glyph mirrors" section rewritten as "Send/receive glyph semantics — two independent axes"; four-combination table added; "Processing sync — no reply value" subsection added.
- Spec §1.11 new "Concurrency model" subsection added before "Sync send corner cases."
- Spec §1.11 "Sync send corner cases" rewritten: atom-blocks-not-transition-blocks correction; no-reply-value clarification.
- Spec §1.11 wiring-glyph table entries for `<:` / `<|` updated to remove "matches `:>` sends" / "matches `|>` sends" phrasing.
- Spec §2.6 reserved-glyphs table updated similarly.
- Spec §10 grammar comment on `receive_glyph` updated: legal with any sender glyph.
- PATTERNS.md "blocking vs non-blocking" section rewritten with the four-combination matrix and no-reply rationale.

**Deferred to later phases:**

- **4a (grammar completion)**: sub-atom slot declarations, standing wire syntax, modifier reach across all sites.
- **4c (type inference)**: declare-at-boundaries / infer-inside rule.
- **4d (contract propagation)**: static one-hop propagation along declared wire graph.
- **4e (effect polymorphism)**: skip full system at v1.0; ship informational effect labels only.

**Superseded framing (do not carry forward):**

- "Sender and receiver glyphs must match" — retired.
- "`|>` blocks until receiver accepts" (RPC framing) — retired in favor of "sender's transition suspends until processing sync."
- "`|>` blocks the sending transition, not the whole sending atom" — retired; atom is single-threaded.
- Any implicit reply channel or return value on `|>` — never designed; retired if any doc suggested it.

## Session note — Compiler-blocker semantic decisions pinned: cascade destruction, auto-nil timing, receive modifiers across phases, `before_destroy` retirement, `enter`/`setup` retirement, glyph-count corrections; grammar productions added for receive glyphs, wildcard on-enter/on-exit, universal `init` and `end` phases (2026-08-12, no version bump; follows same-day sub-atom-as-state-machine note below)

**Trigger.** Doc audit after the sub-atom-as-state-machine session revealed that several load-bearing semantic questions were left underspecified (blockers for a compiler implementer) and multiple examples in §1.12 still used pre-pivot syntax (self-mutation via assignment, `-> before_destroy:` lifecycle handlers, tautology invariants). This addendum pins the semantic decisions and applies the mechanical cleanup.

**Semantic decisions pinned (each was previously ambiguous or unspecified):**

1. **Cascade destruction is synchronous and post-order.** When a parent atom transitions to `end`: parent's `do:` block runs first (final emissions while sub-atoms are still alive and reachable); each sub-atom in a slot recursively transitions to `end` in declaration order; all sub-atoms are destroyed; parent is destroyed. Rationale: parent gets a last chance to notify siblings before losing access to its sub-atoms; declaration order gives deterministic destruction sequence.

2. **Sub-atom slot auto-nil is atomic at end of the sub-atom's `end`-transition.** Specifically, after the sub-atom's `do:` block completes and before any other transition on the parent evaluates its `when:` guard. Parent transition guards evaluated after the sub-atom's `end` fires WILL see the slot as nil. Rationale: transitions are atomic units; observers never see intermediate "sub-atom destroyed but slot not yet nil" state. Enables the canonical `action is nil` guard pattern to be correct without additional coordination.

3. **Receive-glyph modifiers are per-receive-site with independent buffers across phases.** Two `<:` transitions for the same message in different source phases have independent buffers with independent capacities and disciplines. Messages arriving while in phase X land in X's buffer; on transition out of X, X's buffered messages are dropped. Rationale: simplest consistent reading; users who want a shared queue declare the receive at compound level (self-transitions). Within a single phase, multiple receive sites for the same message must use the same modifier (compile error otherwise).

4. **`-> before_destroy:` lifecycle handler retired in favor of universal `end` phase.** Any `<phase> -> end` transition's `do:` block runs before the atom is destroyed and serves the same RAII purpose. Multiple states can have their own `-> end` transitions with per-state cleanup logic — which the single-handler pattern couldn't express cleanly. Migration: replace `def opened: ... -> before_destroy: cleanup()` with `opened -> end when: destruction_requested() do: cleanup()`.

5. **`-> setup:` / `-> enter:` lifecycle handlers retired in favor of `init` phase + top-level standing wires.** Field initialization happens in wildcard on-enter (`-> <first phase>`) transition's `then:` block; standing wires are bare `|>` expressions at atom-body top-level (set up automatically at `init`, torn down at `end`). No separate lifecycle handler section.

**Grammar productions added to spec §10:**

- **`transition_decl`** extended with optional `transition_trigger` (receive-glyph header): `phase_pattern transition_trigger? '->' phase_target`. `transition_trigger := receive_glyph message_name message_args?`, `receive_glyph := '<:' | '<:' channel_modifier | '<|' | '<|' channel_modifier`, `channel_modifier := 'fifo' '(' 'cap' ':' int_literal ')' | 'priority' | 'sliding' '(' 'cap' ':' int_literal ')' | 'unbounded'`.
- **`wildcard_enter_decl := '->' phase_target block_transition_body`** — matches every entry to phase.
- **`wildcard_exit_decl := phase_pattern '->' block_transition_body`** — matches every exit from phase.
- **`phase_target`** extended: adds `'end'` as a legal target (universal terminal).
- **Universal implicit phases `init` and `end`** — reserved phase names; `init` appears only as implicit source, `end` only as target. `then:` sub-block in `-> end` is a compile error.
- **Ordering when multiple transitions match one event**: wildcard on-exit for source phase → wildcard on-enter for target phase → specific transition (specific wins on `then:` field conflicts).

**Retired glyph usages / conventions:**

- `when: msg_name(args)` as message-triggered transition trigger — retired in favor of receive-glyph header. `when:` remains for state/time guards and additional narrowing composed with receive glyph.
- Direct field assignment (`x = y`, `x += y`) anywhere in the language — was already retired at the immutable-FSM sharpening; the debounce example in §1.12 still had it, now rewritten.
- Compound-level exhaustiveness invariants like `self is unopened or self is opened or self is closed` — tautologies given phase inference; File example rewrote without them.

**"Five glyphs" → "seven" everywhere.** Three places said "five glyphs" (spec §1.11 line 1032, spec §2.6 lines 2101-2107, PATTERNS.md line 729); all updated with `<:` and `<|` rows. `->` role updated in the reserved-glyphs table to include "transition arrow" alongside "return-type marker" and "handler prefix." `|>` role updated to include both "wire composition" and "sync send" with grammar-position disambiguation notes.

**Examples rewritten to current syntax:**

- **spec §1.12 debounce** — was using `x = event`, `x += 1`, `pending = nil` assignments. Rewrote with `<: input{event}` / `<: fire_pending{token}` receive-glyph transitions, all field updates in `then:`, all side effects in `do:`.
- **spec §1.12 File** — was using imperative `-> read(...) -> Stream:` handlers with no receive glyphs, plus `-> before_destroy:` cleanup. Rewrote with `unopened <: open`, `opened <| read{...}`, `opened <| write{...}` (sync `<|` for read/write to return values via emit), and `opened -> end when: destruction_requested()` for RAII cleanup. Retired the tautology invariant.
- **game-toys.cor Main** — was using `-> enter:` lifecycle handler. Rewrote as wildcard on-enter `-> running` transition.
- **game-toys.cor** — three "Kind 2" comments removed; replaced with plain-English "continuous accumulator" / "self-transition updates" wording.
- **PATTERNS.md invariants example** — was using per-phase field declarations (`def alive(hp: Int, mana: Int)`) and Kind 1a/1b labels. Rewrote with compound-scope fields and phases inferred from arrows.

**SUPERSEDED banners added.** Two prior session notes that introduced the Kind 1a/1b/2 taxonomy (immutable-FSM sharpening; unified-`then:`) got a `⚠️ PARTIALLY SUPERSEDED 2026-08-12` banner at the start, distinguishing which content is still current (the `then:` mechanics, transition block form) from what was retired (the Kind taxonomy, per-phase field declarations, `def phase:` with body).

**Open items still deferred (see prior session note for full list):**

- Advanced channel modifier semantics (`<:priority`, `<:sliding`) — reserved but may require stdlib mailbox atoms rather than compiler-primitive buffering.
- Cross-atom deadlock detection for `|>` sync sends — user's problem for now (CSP-standard behavior); static analysis deferred.
- Effect polymorphism through the atom graph — §3.5.3 exists but doesn't touch the substrate.
- Runtime scheduling semantics for concurrent transitions across atoms — nothing spec'd on memory model, ordering guarantees, or interleaving.
- Type inference through atom bodies — planned v0.12+ per §1.11 line 985; not spec'd.

**What this addendum unblocks.** A compiler implementer targeting the v1.0 substrate can now parse receive-glyph transitions (grammar has the production), reconcile lifecycle names to a single mechanism (init/end), implement cascade destruction with deterministic ordering, implement sub-atom slot semantics with correct auto-nil timing, and implement per-phase receive modifiers with the specified independent-buffer semantics. The design questions that were blocking compiler work are pinned; the remaining open items are policy questions about advanced features (priority queues, static deadlock detection, effect polymorphism) that a v1.0 compiler can defer or leave as stdlib work.

**What this addendum does NOT unblock.** The spec is still v0.11 — an approximation of the substrate, not the substrate. A v0.11 compiler can be built against most of the spec; a full v1.0 substrate compiler still needs (a) type inference formalization, (b) contract propagation formalization, (c) runtime scheduling and memory model, (d) grammar completion for sub-atom slots and modifier syntax beyond receive glyphs. Decision point: build v0.11 first (closer to shippable; Swift-competitor positioning) or jump straight to v1.0 substrate (higher risk, research-language positioning). Answer needed before further compiler-oriented spec work.

---

## Session note — Sub-atom-as-state-machine pattern; `init` and `end` as universal implicit phases; receive glyphs `<:` (async) / `<|` (sync); phases inferred from arrows; substrate coordinates mutation, not eliminates it (2026-08-12, no version bump; follows same-day unified-`then:` below)

> **⚠️ PARTIALLY SUPERSEDED 2026-08-12** — The "sender's glyph MUST match receiver's glyph" claim in this note (Collapse 2) has been RETIRED by the Phase 4b concurrency-model decision at the top of the file. Sender-blocking and receiver-blocking are now independent axes: all four sender/receiver glyph combinations are legal. `|>` semantics also refined: sender's transition suspends until **processing sync** (receiver's transition on this message has fired), with no reply value transported. Receive-glyph introduction, modifier syntax, sub-atom-as-state-machine pattern, universal `init`/`end` phases, phases-inferred-from-arrows, and substrate-as-coordinator framing all remain current.

**Trigger.** Toy-example iteration on Player's fireball casting exposed a deeper structural question. Earlier drafts modeled `firing_fireball` as a phase of Player with per-phase fields (`fireball_target`, `cast_started_at`) declared inside the phase. User pushed on multiple fronts:

1. **Per-phase field declarations dress mutation up as staticness.** Writing `def alive: velocity: Float = 0.0` reads like a structural declaration, but the value changes when the phase changes — that's a mutation coordinated by the substrate on phase transition. The compiler-sugar framing ("`def phase: field: T = V` desugars to `field :< switch self: case phase: V ...`") was wrong. It hid the mutation behind syntactic disguise.

2. **Union-typed slots for state discrimination are state-machines-in-disguise.** Modeling `action: Idle | FireballCasting | MeleeAttack` invents queue-fields, cancel-flags, and interruption-logic outside the substrate. What the pattern is REALLY reaching for is a state machine (an atom) whose channel mailbox handles queueing intrinsically. Union types remain for genuinely-polymorphic disjoint values (message payloads, result outcomes) where variants have no natural progression.

3. **Coordination is the substrate's job.** User frame: "observables are lightweight atoms with their own state machines; coordinating changes is part of what makes this design work without managing mutability." Not eliminating mutability — DELEGATING the coordination to the substrate. Every declaration form (data field, observable, transition, wire, sub-atom slot) is a way of specifying WHAT should be coordinated; the substrate authors the actual writes.

**Four collapses landed this addendum.**

**Collapse 1 — `init` and `end` as universal implicit phases.**

Every atom has two implicit phases the user never declares:

- `init` — constructor. Fires on instantiation. Implicit transition `init -> [first-mentioned target phase]` runs, populating fields via its `then:` block. Wildcard on-enter (`-> phase`) for the first phase catches this transition.
- `end` — deconstructor. When any transition targets `end`, the atom's `do:` block runs (last chance to emit signals), then the atom is destroyed. Any nullable slot holding the atom auto-nils.

Symmetric with the substrate's transition-first commitment: instantiation IS a transition; deconstruction IS a transition. No separate constructor/destructor mechanism.

Corollaries: `end` transitions may have `when:` and `do:` but not `then:` (state being discarded). Parent atoms detect sub-atom completion via `is nil` on the slot, or by listening for the emit-in-`do:` before destruction. Cascade behavior: parent `-> end` recursively transitions sub-atoms in slots to `end`.

**Collapse 2 — Receive glyphs `<:` (async) / `<|` (sync); send glyph clarification `:>` async / `|>` sync.**

Sender-side glyphs (`:>` / `|>`) get receive-side counterparts (`<:` / `<|`), mirroring direction of the colon/bar:

- `:>` async send ↔ `<:` async receive
- `|>` sync send ↔ `<|` sync receive

Receive glyph goes INSIDE the transition header (between source and arrow), replacing the earlier `when: msg_name(...)` pattern for message-triggered transitions:

```
alive <: cast_fireball
-> alive
    when: mana >= cost
    then: mana => mana - cost
    do:   :> fireball_launched
```

Sender's glyph MUST match receiver's glyph on the same message name — compile-checked. Cross-glyph mismatch is a compile error. Bare `<:` = drop-if-no-matching-transition; bare `<|` = pure rendezvous. Modifier syntax attaches non-default semantics per receive site: `<:fifo(cap: 8)`, `<:priority`, `<:sliding(cap: 1)`.

`|>` retains its wire-composition role at top-level (standing subscription). Grammar disambiguates: `expr |> target` is wire; `atom |> msg(args)` inside `do:` is sync send.

**Collapse 3 — Sub-atom-as-state-machine pattern; retire per-phase field declarations and union-typed state slots.**

For state that has its own lifecycle — phases, transitions, potential for queueing, interruption, or independent activity — encapsulate as a **sub-atom in a nullable slot**:

```
def Player:
    data:
        action: ActionMachine? = nil    # sub-atom slot
    
    alive <: cast_fireball
    -> alive
        when: action.is_idle and mana >= cost
        then:
            mana   => mana - cost
            action => ActionMachine(clock: clock)
        do:
            action :> start_cast(target: cursor_pos, cast_time: 500ms)
```

The sub-atom carries its own transitions, fields, lifecycle. When it reaches `end`, it deconstructs; slot auto-nils. Parent's next `action is nil` check is true again.

Retired:
- Per-phase field declarations inside `def phase:` blocks (were hiding mutation as staticness)
- Union-typed slots for state discrimination (were state-machines-in-disguise)
- Empty `def phase` declarations (phases inferred from transition arrows)

Sub-atom coordination happens via four patterns:
- **Command down**: `action :> start_cast(args)` (method-call syntax; nil-safe)
- **Event up**: `self <: action.cast_completed{...} -> ...` (receive-glyph header)
- **State query**: `action.is_idle` via observable predicate on the sub-atom (NOT raw phase name — insulates parent from internal renames)
- **Standing wire**: `action.cast_progress |> self.update_ui` (durable subscription)

**Collapse 4 — Substrate coordinates mutation; it doesn't eliminate it.**

Corvid doesn't remove mutability. It centralizes coordination in the substrate. Users declare intent (what depends on what, what phases exist, what fires when); the substrate authors the actual writes and their ordering.

Every declaration form is a specification of what to coordinate:
- Data fields declare shape; init transition sets values
- Transitions specify state-to-state functions the substrate applies atomically
- Observables specify derivations the substrate recomputes on input change
- Wires specify subscriptions the substrate maintains
- Sub-atom slots specify lifecycle-owning nested machines the substrate hosts

This is what makes the language safe without locks, without invariant-management-by-convention, without race conditions across coordinated fields. Not because nothing changes, but because ONE coordinator (the substrate) orchestrates all changes.

**What retires.**

- Per-phase field declarations (`def alive: hp: Int = 100`) — hid mutation behind syntactic disguise
- Empty `def phase` declarations — phases inferred from transition arrows
- Union-typed slots for state discrimination — state-machines-in-disguise
- Separate `channel X: fifo(...)` receiver declaration — replaced by modifier on receive glyph (`<:fifo(8)`)
- The "when: msg(args)" pattern for message-triggered transitions (in favor of receive-glyph header) — but `when:` remains for state/time guards

**What lands.**

- **Universal implicit phases**: `init` (constructor) and `end` (deconstructor); every atom has both.
- **Receive glyphs**: `<:` async / `<|` sync inside transition headers; must match sender's `:>` / `|>`.
- **Modifier syntax** on receive glyphs for non-default channel semantics: `<:fifo(cap: N)`, `<:priority`, `<:sliding(cap: N)`.
- **Sub-atom slots**: nullable fields typed as a specific sub-atom (`action: ActionMachine?`); lifecycle managed by sub-atom's own transitions; slot auto-nils on sub-atom `end`.
- **Wildcard on-enter**: `-> phase` transitions match any entry to `<phase>` (including init); on-enter's `then:` runs before specific transition's `then:` (specific-wins on conflicts).
- **Four sub-atom coordination patterns**: command-down (`:>`), event-up (`<:`), state-query (observable predicate), standing-wire (`|>`).
- **Substrate-as-coordinator framing**: users declare intent; substrate orchestrates writes.

**Open items (deferred).**

- Advanced channel modifier semantics (`<:priority`, `<:sliding(cap: N)`) — syntax reserved but may require stdlib mailbox atoms rather than compiler-primitive buffering.
- Wildcard on-exit form `<phase> ->` — noted symmetric to on-enter; corner cases (fires-vs-cascade-to-end, ordering) need pinning.
- Explicit `init -> phase` transition declarations — currently implicit-from-first-mention; whether to allow explicit form for clarity is deferred.
- Whether wire composition (`A |> B`) implies sync or async semantics at each emission — treated as "async subscription; sender doesn't wait" for now; sync-wire semantics may need `A <\|> B` or similar if it becomes a common pattern.
- Compile-time enforcement of the "first-mentioned target phase is initial" rule when multiple wildcard on-enters could plausibly claim init — needs precise diagnostic wording.
- Cross-atom deadlock detection for `|>` sync sends — CSP-in-general has this problem; whether Corvid provides static analysis is deferred.

**Larger frame this session confirms.** The atom substrate does not eliminate mutation — it centralizes coordination in one system-level component (the substrate) rather than distributing it across user code. Every declaration form is a specification of what to coordinate; every glyph carries a distinct role in that coordination. Sub-atoms give us cheap state-machines-in-slots, which subsume both "per-phase fields" (retired: hid mutation) and "union-typed slots" (retired: state-machines-in-disguise). The receive glyphs (`<:` / `<|`) mirror send glyphs (`:>` / `|>`) exactly, and the sender-receiver-must-match compile check makes channel semantics a first-class invariant. Coordination is the substrate's job; user declares intent.

---

## Session note — Unified `then:`: constructor args on transition targets retire; all field updates use `then:` with lexical name resolution across target and compound scopes; explicit path escape for disambiguation; shadowing warning (2026-08-12, no version bump; follows same-day immutable-FSM sharpening below)

> **⚠️ PARTIALLY SUPERSEDED 2026-08-12** — The Kind 1a / Kind 1b / Kind 2 taxonomy referenced here (and in the immutable-FSM sharpening note below) has been RETIRED. See the top-of-file session note (sub-atom-as-state-machine pattern) for the replacement: one question ("does this state need its own lifecycle?") with three shapes (sub-atom-in-slot / phases + wildcard on-enter / self-transition with `then:`). The `then:` lexical resolution, explicit path escape, and shadowing warning content in this note are still current; only the taxonomy references are outdated.

**Trigger.** Toy-example iteration after the immutable-FSM sharpening left two field-update mechanisms in play:
- Constructor args on transition targets: `alive -> firing_fireball(hp, mana - cost)` — set target-state-atom fields
- `then:` block: `then: last_cast_at => clock.now` — set compound-scope fields

User asked: "why do we need the constructor args?" That surfaced the inconsistency: two mechanisms for the same conceptual operation (functional field update with carry-forward), differing only in which scope they targeted. Reader had to know which scope each field lived on to pick the right syntax.

**Collapse — unify field updates under `then:` with lexical scope resolution.**

New rule: inside `then:`, unqualified field names resolve by lexical scope:
1. Target state atom's data fields (innermost scope of the transition target)
2. Compound's data fields (enclosing scope)
3. Ambiguity (same name in both scopes) → compile error; use explicit path
4. Unknown name → compile error

Symmetric with the scoping rule for READS inside nested state atoms — state atoms already read compound-scope fields via lexical lookup ("nested state atoms read compound-scope lexically", S3 in toy-example header). Extending the same rule to writes in `then:` unifies the syntax.

**Cross-scope field updates land in one `then:` block:**
```
alive -> firing_fireball
    when: cast_fireball() and can_cast and mana >= cost
    then:
        mana         => mana - cost           # firing_fireball.mana (target scope)
        last_cast_at => clock.now             # Player.last_cast_at (compound scope)
    do:   :> fireball_launched
```

No `Player.` prefix needed when the name is unambiguous. Reader just names the field; compiler routes it.

**Constructor args on transition targets — retired for the common case.** Header names only the phase change; all field values live in `then:`. Constructor args remain legal only when the target has required constructor params with no defaults that cannot carry forward from source (rare edge case).

**Explicit path escape available for disambiguation:**
```
then:
    Player.last_cast_at    => clock.now      # explicit compound-scope
    firing_fireball.mana   => new_mana       # explicit target-scope
```

Use when a name is shadowed between scopes or when scope-visibility improves reader clarity.

**Shadowing warning.** Adding a field to a state atom that shadows a compound-scope field with the same name silently changes the resolution of unqualified references in `then:`. Compiler warns on the declaration site — same lint any language runs for lexical-variable shadowing. Users add explicit path or rename.

**Kind 1a state atoms — "dead" phase reframed structurally.** During this iteration also clarified that alive/dead in Player and Enemy is *not* an emergent property (a prior Kind 1c push was reverted). It's the FSM proper: `dead` is a state atom with **no declared transitions**, which structurally means "ignore all messages." Absence of transitions is the semantic; state atoms express it declaratively.

Prior Kind 1c reframing had said alive/dead could be derived from `hp > 0` via observable, with every transition guarded on `and self is alive`. Rejected: guards are verbose, error-prone (forget one and dead entities accept input), and don't express "dead ignores everything" structurally. Kind 1a with an empty `dead` state atom does.

Enemy under-decomposition (single leaf atom where dead was implicit in a guard) moved to Kind 1a with proper alive/dead state atoms matching Player's shape.

**Kind 1c (derived phase observable) narrows to "derived label."** The Kind 1c category as originally proposed (derived enum observable used for phase-gated dispatch) collapses back into Kind 1a/1b. What remains as Kind 1c is only *derived labels* for display/grouping that don't gate transitions (e.g., `rank :< switch xp: case < 1000: novice; case < 5000: apprentice; else expert`). Not really a phase — just a computed observable.

**What retires.**

- Constructor args on transition targets for the common case (`<to>(field: value, ...)` in the header). Legal only for target constructors with required no-default no-carry params.
- Kind 1c as a phase-decomposition category (only Kind 1a and Kind 1b use arrow-syntax phase transitions; Kind 1c narrows to derived labels that don't gate transitions).
- Compound-level exhaustiveness invariants like `self is alive or self is firing_fireball or self is dead` — redundant because state-atom or enum-type declarations enumerate the full set at compile time.

**What lands.**

- **Single field-update mechanism**: `then:` with lexical scope resolution (target first, compound second).
- **Explicit-path escape** for disambiguation and reader clarity.
- **Shadowing warning** enforcing clarity when a later declaration would silently change unqualified resolution.
- **"Absence of transitions is the semantic"** for phases like `dead` that ignore all messages.
- **Kind 1a stays as the natural fit** for lifecycles where phases have distinct transition sets, even when the discriminator is data-derivable.

**Open items (deferred).**

- Sugar for time-limited phases with autonomous exit: `alive -> firing_fireball ... auto_exit: fireball_cast_time -> alive` or similar. Currently `firing_fireball -> alive when: complete()` plus a `clock.after(...) |> (_ => self.complete())` in the entry transition's `do:` block. Sugar deferred until pattern proves common.
- Compiler diagnostics for the shadowing warning — needs precise wording and fix-it suggestions (auto-rename vs explicit-path).
- Whether nested state atoms should be able to explicit-path-write to *sibling* state atom fields (currently: no; only target + compound). Deferred until real case appears.

**Larger frame this session confirms.** The atom model is genuinely unified — everything is a value; every transition is a pure function producing a new value; the only mechanism for field updates is `then:` with a single scope-resolution rule that mirrors reads. Composition-only stays honest: state atoms handle structural phase decomposition; enum-typed fields handle same-shape phases; both use uniform arrow syntax and uniform `then:` updates. Reader thinks in FSM terms; writer picks storage based on Kind 1a/1b criteria; compiler enforces exhaustiveness and scope resolution.

---

## Session note — Immutable-FSM sharpening: assignment retired everywhere; transition block form (when: / let: / then: / do:) lands; Kind 1 splits into 1a (state atoms) / 1b (enum + derived); enum-valued phase transitions share arrow syntax with state atoms (2026-08-12, no version bump; follows same-day FFI dissolutions below)

> **⚠️ PARTIALLY SUPERSEDED 2026-08-12** — The Kind 1 → 1a/1b/1c split introduced in this note has been RETIRED, along with `def phase:` per-phase field declarations. See the top-of-file session note (sub-atom-as-state-machine pattern) for the replacement. The transition block form (when: / let: / then: / do:) lands here IS current; the receive-glyph header (`<:` / `<|`) added later supersedes `when: msg(args)` for message-triggered transitions but composes with the same body sub-blocks.

**Trigger.** Toy-example iteration produced a Paddle with three state atoms (idle/up/down) each carrying one constant velocity field. User pushed: "this seems to be an anti-pattern? i thought we did mention thatwe want to do near-iimmutable oops." Two problems compounded:

1. **State atoms for value-only phase differences are overkill.** When phases have identical shape and identical behavior — differing only in a constant value they contribute — state-atom decomposition is machinery for no benefit. An enum-typed data field with a derived observable does the same job with less structure.

2. **Assignment inside transition bodies was smuggled mutation.** The prior rule ("assignment legal only inside transition blocks") looked like a discipline but was actually a partial retreat: `y = clamp(...)`, `active_enemies += [e]`, `Player.last_cast_at = clock.now` all *read* as mutation regardless of transition-block-scoping semantics. Under a "near-immutable OOP language" commitment, assignment on `data:` fields has no honest place.

Three collapses landed this addendum.

**Collapse 1 — Assignment retired everywhere; transitions are pure state → new-state functions.**

Every transition resolves to a new self via a **constructor expression** — either in the transition-line header (for the target phase) or in a `then:` sub-block (for compound-scope field updates). The transition body computes helpers (`let:`) and side effects (`do:`), never state updates.

Block form:
```
<from> -> <to>[(constructor-args)]
    when: <guard>
    let:  <intermediate>: <expr>+       # optional
    then: <field>: <expr>+              # optional; compound-scope updates
    do:   <side-effect-statements>+     # optional; emissions, one-shot wires
```

- `<to>` may carry constructor args — these set fields *intrinsic to the target phase* (state-atom constructor params, enum-case associated values).
- `then:` uses `field: expression` per line — same named-arg syntax as constructor calls, just spread across lines. Unlisted fields carry forward. Pure functional update; not assignment.
- `do:` contains only side effects — emissions (`:>`), one-shot pipe subscriptions (`|>`), external calls. Never contains field updates.

Compact single-line form for the common phase-only case:
```
idle -> up when pressed(key: Key.arrow_up)      # sugar for block with only when:
```

Assignment operators (`=`, `+=`, `-=`, `*=`, `/=`, `%=`) on `data:` fields are a syntax error at every scope — atom-body top level, handler bodies, transition bodies. There is no general mutation in the language. Cross-scope compound writes (`Compound.field = ...`) are also gone.

**Collapse 2 — Kind 1 splits into Kind 1a and Kind 1b.**

The prior "Kind 1 — discrete-phase field → state atoms" rule was too broad. It conflated two structurally different cases:

- **Kind 1a — Distinct-shape phases** (state atoms warranted). Phases differ in fields, valid transitions, or exposed behavior. State atoms earn their machinery because each phase carries structurally distinct information. Example: Player {alive(hp, mana) | firing_fireball(hp, mana) | dead}.

- **Kind 1b — Same-shape phases with different constants** (enum + derived is the right form). Phases accept the same messages and expose the same fields; only a constant value differs. State atoms are pure overhead. Declare an enum; store as a `data:` field; derive per-phase constants via a switch observable. Example: Paddle {idle | up | down} each contributing a velocity constant.

Compiler may hint when a Kind-1b-shaped enum is being over-decomposed into state atoms — suggesting the enum + derived form. State atoms should pay for their machinery; when they don't, the enum form is more immutable-first.

**Collapse 3 — The `<from> -> <to>` arrow syntax works uniformly for state atoms and enum cases.**

Under Kind 1b the user could have been forced to write `self -> self(direction: up)` for phase changes — verbose and syntactically inconsistent with Kind 1a's `alive -> firing_fireball(...)` form. Instead the arrow syntax generalizes:

| Phase mechanism | `idle -> up when …` compiles to |
|---|---|
| State atoms (nested `def idle:`, `def up:`) | swap active nested state atom |
| Enum-valued field (`direction: Direction`) | `self -> self(direction: up) when …` |

Compiler picks the mechanism from context. If `idle`/`up` are declared as nested state atoms of the compound → state-atom form. If they're cases of an enum whose type appears as a data field → enum-valued form.

Disambiguation:
- Exactly one enum-typed data field for the enum → arrow syntax targets that field implicitly.
- Multiple fields of the same enum type → prefix required: `direction: idle -> up`.
- Name is both a state atom and an enum case in scope → compile error; rename.
- Name is neither → compile error.

Consistent with Erlang/Elixir's `{next_state, NewData}` return shape being agnostic about whether the state name is atom-typed or something richer. User reads the FSM at the surface; compiler picks the mechanism from how phases were declared.

**User-proposed `=>` syntax initially substituted with `:`, then reverted.** User draft used `field => value` inside `then:`. Initial substitution: replaced with `:` on the grounds that `then:` was a multi-line named-arg spread for `self(field: value, ...)` and consistency with constructor calls argued against a new glyph. On re-review, reverted to `=>`:

- Single-line `then: y: clamp(...)` reads awkwardly — the eye parses `then:` as section label, then re-parses the next `:` as named-arg. Double-`:` in one line is visually noisy.
- `:` is already heavily overloaded (type annotation, named arg, section label, dictionary key); adding "field update with carry-forward" leans entirely on context.
- `=>` reads as "field becomes expression" — the functional-update semantics are visible in the glyph itself, reinforcing the immutable-FSM discipline. `:` doesn't carry that signal.
- Lambda ambiguity is context-resolved: `identifier '=>' expression` at line-start inside a `then:` block is unambiguously a field update; bare lambdas aren't statements. One extra parser rule scoped to `then:`.
- The distinct glyph honestly reflects a distinct semantic operation: constructor calls set *all* fields (using `:`); `then:` sets *some* fields with the rest carrying forward from source (using `=>`). Making these visibly different in the surface syntax is honest, not inconsistent. `let:` retains `:` because it's binding (naming a new value), not update.

Final syntax:
- `let: name: expression` — pure named binding (uses `:`, matches named-arg / declaration binding)
- `then: field => expression` — functional field update with carry-forward (uses `=>`, distinct from `:` and `=`)
- Inline constructor calls (`alive(hp: hp - amount)`, `self(y: value)`) still use `:` — those are constructor arg spreads; changing them would break the whole language's constructor syntax and isn't justified by the `then:` shift alone.

Slight asymmetry between inline constructor form and block `then:` form; the asymmetry is *informative* (they're related but distinct operations) rather than *arbitrary*.

**Cross-scope updates: two idiomatic forms, both immutable.**

Prior tension: nested-state transitions (`alive -> firing_fireball(...)`) that also needed to update a compound-scope field (`Player.last_cast_at`) forced either cross-scope assignment (mutation, retired) or a mandatory emit + listen decomposition. Under `then:`, both forms become idiomatic:

1. **`then:` on the phase transition itself** — compound-scope updates atomic with the phase change:
   ```
   alive -> firing_fireball(hp, mana - cost)
       when: cast_fireball() and can_cast and mana >= cost
       then: last_cast_at: clock.now
       do:   :> fireball_launched
   ```
2. **Emit + listen** — phase transition emits; separate compound self-transition listens:
   ```
   alive -> firing_fireball(hp, mana - cost) when: ... do: :> fireball_launched
   self -> self when: fireball_launched() then: last_cast_at: clock.now
   ```

Form 1 is compact for updates intrinsic to the phase change; form 2 is clearer when the compound update is a semantic consequence other listeners should also see. Both preserve the immutable FSM property; user picks based on decomposition intent.

**What retires.**

- Assignment inside transition bodies (`=`, `+=`, `-=`, `*=`, `/=`, `%=` on `data:` fields). Fully removed.
- Cross-scope field writes (`Compound.field = ...` from inside a nested-state transition). Fully removed.
- "Kind 2 as self-transition-with-mutation." Reframed as self-transition threading the field forward via `then:`.
- Kind 1 as a monolithic rule. Split into Kind 1a (state atoms — structural difference) and Kind 1b (enum + derived — value-only difference).

**What lands.**

- **Transition block form**: `when:` / `let:` / `then:` / `do:` sub-blocks under a transition line. Reading order: guard → helpers → new self (target + then) → side effects.
- **`then:` sub-block**: multi-line named-arg spread for compound-scope field updates. `field: expression` per line. Pure functional update.
- **`let:` sub-block**: pure intermediate bindings visible in `then:` and `do:`. Erases duplicated computation.
- **Enum-valued phase transitions** using arrow syntax: `idle -> up when ...` for enum cases, uniform with `alive -> firing_fireball` for state atoms. Compiler picks the mechanism.
- **PATTERNS.md**: Kind 1a / Kind 1b / Kind 2 decision table; cross-scope-updates entry documenting the two idiomatic forms.

**Open items (deferred).**

- Should `then:` allow `let:` bindings inline (mixed `field: expr` and `let name = expr`)? Currently `let:` is its own sub-block. Cleaner separation vs slight verbosity for cases where a value is bound and immediately used once.
- `with:` sugar for compound-scope updates on state-atom transitions was considered and rejected (conflicts with §6.10 RAII `with:`). `then:` covers the same ground; no sugar needed.
- Should Kind 1b arrow syntax require an explicit `on <field>` marker for readability even in the unambiguous case? Currently no (inferred from enum type); may revisit if disambiguation errors are common.

**Larger frame this session confirms.** The atom substrate isn't just close-to-immutable — it's fully immutable at the transition semantics level. Every state-changing operation is a pure function producing a new self. Assignment was a compromise that no longer earns its complexity now that `then:` handles the multi-field update case cleanly. The remaining "mutation-shaped" operations (list append via `+`, dictionary update via `+`, etc.) are functional constructor calls on immutable data structures — no in-place update, no aliasing risk. Corvid is now honestly the "close-to-immutable OOP language" originally scoped as the design goal — arguably just "immutable OOP language" without the hedge.

---

## Session note — FFI dissolutions: `extern:` section retires; FFI is `Extern` capability atom; symbol registry moves into prelude; direction inferred from type shape (2026-08-12, no version bump; follows same-day toy-example simplifications below)

**Trigger.** Toy-example addendum below left `state:` retired but `extern:` still a section. Working through clock-tick reconciliation for a game loop surfaced that `Clock` is itself a boundary atom — OS drives it, not Corvid. Extending the same reasoning to every capability shim showed `extern:` was the last special mechanism at the OS boundary. Three collapses landed.

**Collapse 1 — `extern:` section becomes `Extern` capability atom.** FFI stops being a syntactic privilege gated at parse time and becomes a capability provisioned through env. Shim atoms request access via `use: extern: Extern`; runtime linker fills the slot only for stdlib-designated shims. Trust boundary moves from parse-time policy check to env-composition-time provisioning check. Same policy, uniformly enforced with every other capability (Clock, Filesystem, ...) — one mechanism instead of two.

Access shape: `extern.get("clock_gettime")` returns a typed native handle. Handle type comes from a symbol registry (not caller-asserted); handle is invoked (outbound) or subscribed (inbound) using ordinary Corvid mechanics. No new syntax at the call site — this is regular capability-atom method dispatch over regular typed values.

**Collapse 2 — symbol registry moves into prelude.** Considered making the registry a separate `.cor` file per target. Realized the prelude is already target-scoped (platform-varying types, platform-varying shims) and already Corvid-native. Piggybacking the symbol registry into the prelude removes an entire concept — no separate registry file format, no separate build-config plumbing. Directory layout: `prelude/shared/` + `prelude/<target>/`; target-scoped subdirectories carry `foreign library` blocks declaring typed native symbols alongside the shim atoms that consume them.

Declaration form: `foreign library "libc": symbol "open": (path: CString, ...) -> Int32`. Prelude-only syntax; parser rejects `foreign library` / `symbol` outside `prelude/` files. Second layer of defense on top of the env-provisioning trust boundary — even if a shim atom somehow received Extern, non-prelude atoms cannot declare new symbols to resolve.

**Collapse 3 — direction inferred from symbol type shape.** Earlier drafts had `extern:` sub-labels `outbound:` / `inbound:` to distinguish Corvid→native calls from native→Corvid callbacks. User observation: outbound/inbound recapitulates atom IN/OUT ports — the FFI boundary crosses one port; which port is native is determined by the atom's I/O shape. Which means the sub-labels are redundant.

Rule: a symbol declared with type `(Args) -> Ret` is outbound (Corvid invokes). A symbol declared with type `Stream of T` is inbound (native invokes via runtime callback; shim atom subscribes to the stream via ordinary `|>` composition). No `direction:` field, no `inbound_symbol` / `outbound_symbol` keywords. One rule, one axis.

Callbacks-with-return values (qsort comparator, Win32 WndProc, pthread start_routine) are unsupported in v1.0. The general FFI type system covers only the two shapes above; rare APIs needing returning callbacks require hand-crafted per-API shims with compiler-baked native trampolines. Rationale: no general encoding fits both hot synchronous callbacks (comparators at 10^6/sec, sub-microsecond latency) and cold async callbacks (would need reply-channel mediation with cross-boundary blocking). Better to draw a hard line than design speculatively.

**Historical: earlier rejection of "FFI-as-capability" reversed.** The v0.11 spec rejected FFI-as-capability on type-safety grounds — the assumed shape was `env.ffi.call("name", [args])` with runtime-typed args. Prelude-registry model preserves type safety at compile time: `extern.get("name")` requires a compile-time string literal; compiler resolves against the prelude symbol table; retrieved handle's type comes from the prelude declaration; call site is fully typed. The rejection doesn't apply to this shape. Reversed.

**What retires.**

- `extern:` section keyword (was in §2.5 keywords). `Extern` (PascalCase) is a regular identifier for the capability atom.
- `extern_section` / `extern_binding` / `extern_field` grammar productions.
- `symbol:` / `library:` / `abi:` as sub-fields of `extern:` bindings (they now appear inside prelude-only `foreign library` blocks).
- Sub-label distinction `outbound:` / `inbound:` (never landed in spec; considered but dissolved before commitment).

**What lands.**

- §1.12 rewritten: extern-as-capability model; PosixFs example.
- §1.14 new: prelude target-scoping, `foreign library` / `symbol` declaration forms, WallClock as full inbound+outbound worked example.
- §2.5 keywords: `extern` retired; `foreign`, `library`, `symbol` added as prelude-only keywords.
- §10 grammar: `foreign_library_block` and `foreign_symbol_decl` productions added (marked prelude-only); `extern_section` production retired with a note.
- PATTERNS.md "I need FFI" entry rewritten: user answer is "you don't; use a typed capability, or contribute a shim to stdlib prelude"; underlying mechanism (Extern + prelude) described for completeness.

**Open items (not blockers; deferred).**

- Precise policy for how the runtime linker refuses `Extern` provisioning to non-stdlib atoms (stdlib whitelist vs build-config vs fully user-composed env). v1.0 lands on strict stdlib-only; opens up if pressure appears.
- Whether user projects can extend the prelude with additional foreign symbols (project-specific libraries). v1.0: no. Might land as a build-config feature if real use cases surface.
- ABI hints (`abi: "c"` vs `abi: "system"` on Windows) — currently deferred; default C ABI everywhere.

**Larger frame this session confirms.** Every "outside world" thing enters Corvid through env. Clock, Filesystem, Network, Extern — all capability atoms; none get syntactic privileges. Trust boundaries live at env-composition, not at parse time. This is the fully Clojure-ish version of the capability model: everything is data (typed handles retrieved from a registry); no special-case machinery; testability and inspectability fall out.

---

## Session note — Toy-example-driven simplifications S1–S6: atom-scope `let` constants; standing wires as top-level pipe; lexical-read into compound scope; per-state observables; pipe-endpoint restriction; position-based standing-vs-one-shot subscription (2026-08-12, no version bump; follows same-day special-case audit below)

**Toy-example stress test.** After the six special-case dissolutions landed, drafted three game-shape toy examples (Paddle, Player with cooldown ability, EnemySpawner) to see what the design felt like in practice. Six pain points surfaced; six simplifications named. All six committed this addendum.

**S1 — Bare pipe expressions at atom-body top-level = standing wires.**

Retiring `wires:` left standing subscriptions with no home. Simplest rule: a pipe expression written at atom-body top-level (not inside any handler) is a **standing subscription** — set up on atom construction, torn down on `-> before_destroy:`.

```
def EnemySpawner:
    use: clock: Clock
    
    clock.every(2s) |> spawn_enemy      # standing wire — lives as long as atom does
    
    -> spawn_enemy(tick: Instant): ...
```

Grammar: `def_member` gets a `wire_expr` alternative for top-level pipe expressions. Position-based (see S6): top-level = standing; inside handler = one-shot.

**S2 — `let` at atom-body top-level = atom-scope constant.**

Class-level constants had no defined home. Overloading `data:` fields to serve as constants (via defaults) muddles per-instance vs shared semantics. Adopted rule: **`let name: Type = expr` at atom-body top-level is an atom-scope compile-time constant** — no per-instance storage, same value for every instance, evaluable at compile time.

```
def Paddle:
    let max_speed: Float = 500.0
    let screen_bounds: Float = 360.0
    
    state:
        y: Float = 0.0
```

Same shape as module-level `let` (§7.1), just scoped to the atom. Distinguishes cleanly from `data: max_speed: Float = 500.0` (which would be per-instance immutable field with a mutable-via-constructor default).

Grammar: `def_member` gets a `let_binding` alternative.

**S3 — Nested state atoms have lexical read-access to compound scope; writes require explicit path.**

Pain: inside `def alive`, `requires can_cast` — but `can_cast` is a compound-Player observable, not on the `alive` state atom. Lookup was undefined.

Rule: **reads are lexical** — an unresolved name inside a nested state atom looks up (in order): the state atom's own `data:`/`state:`/`observables:`/`let`s; then the compound's `data:`/`use:`/`observables:`/`let`s; then module scope. **Writes to compound state require an explicit path** — `Compound.state.field = expr` or a named-reference (`parent.state.field = ...`); bare `state.x =` always writes to the *innermost* atom's state.

This makes standard state-machine idioms (guarding a state transition on compound-level conditions) work without ceremony while keeping mutation locality explicit.

**S4 — Observables can be per-state; compound aggregates.**

Pain: `health :< self.hp` on the compound auto-delegates to active state's `hp` — but `dead` state has no `hp`, so this fails at read-time.

Rule: **each nested state atom may declare its own `observables:`**. When accessing an observable on the compound:
1. If the compound declares it explicitly, use that.
2. Else, if the active state declares it, use that.
3. If the observable exists in *some* states but not all, its compound-level type is lifted to `Optional of T` — missing states return nil.
4. If it exists in *all* states with compatible shapes, the compound-level type is `T` — no Optional lift.

```
def Player:
    def alive(hp: Int):
        observables:
            hp :< self.hp                    # alive-state observable
    
    def dead:
        pass                                  # no hp observable here
    
    observables:
        health :< self.hp                     # compound-level: Optional of Int (dead returns nil)
```

If a designer wants `health` to always exist, they explicitly declare it on every state (perhaps returning a constant for `dead`). Compiler warns on Optional-lifted observables to surface this to authors.

**S5 — Pipe endpoint restricted to identifier / lambda / method-reference; call-expressions must be lambda-wrapped.**

Pain: `e.died |> despawn(e)` reads as "wire died to a call that takes `e`" but semantically the pipe feeds `died`'s payload as the argument. Payload/argument mismatch is silent.

Rule: **pipe endpoints must be one of**:
- **Identifier**: `pipe |> handler_name` — pipe payload passed as sole positional arg to `handler_name`.
- **Method-reference**: `pipe |> obj.method` — pipe payload passed as sole positional arg to `obj.method`.
- **Lambda**: `pipe |> (payload => expr)` — pipe payload bound as `payload` (or `_` if unused), expr evaluated.

Bare call-expressions (like `despawn(e)`) at pipe endpoints are rejected with a fix-it: "wrap with lambda if you meant `(_ => despawn(e))`, or use `despawn` alone to forward the payload." Catches the payload/argument mismatch class at compile time.

**S6 — Standing subscription vs one-shot handler determined by lexical position.**

Rule: pipe expressions live in exactly two places, with distinct semantics:

- **At atom-body top-level** (outside any handler): **standing subscription**. Set up on atom construction; torn down on `-> before_destroy:`. Fires every time the source emits.
- **Inside a `-> handler:` body**: **one-shot expression**. Set up when the handler runs; scoped to the handler's execution. If the source emits within the handler's scope, the pipe forwards it; if the handler exits (or the atom is destroyed), the subscription tears down.

For dynamically-created standing subscriptions inside a handler, users call `.subscribe()` explicitly rather than using `|>`. This makes the standing/one-shot distinction visible at read-time — bare `|>` at top level always means "standing"; bare `|>` inside a handler always means "one-shot / scoped."

**Cumulative effect after S1–S6.**

The atom-body vocabulary gains three lexical positions:
1. `let name: T = expr` — atom-scope constant (S2)
2. `expr |> target` — standing subscription (S1, S6)
3. `-> handler(...):` — externally-invocable message handler (unchanged; already committed)

Nested state atoms gain lexical read-access into compound scope (S3), each may declare its own observables (S4), and the compound aggregates them (S4). Pipe endpoints are shape-restricted for compile-time payload-checking (S5). Standing vs one-shot subscriptions are position-based, no keyword needed (S6).

**Mechanism-invention trap check.** Passes on all six. S1 reuses the existing pipe expression at a new position. S2 reuses `let` from module scope at a new position. S3 formalizes lexical scoping (standard rule, previously undefined). S4 gives observables the same aggregation semantics that method dispatch already has via auto-delegation. S5 tightens pipe grammar to catch a bug class — no new machinery, restriction of existing. S6 is position-based semantics of the pipe expression, no new syntax.

**Spec changes committed this addendum.** §1.11 atom-body vocabulary table extended with `let` constants + top-level standing wires + lexical read rules; §10 grammar adds `let_binding` and `wire_expr` alternatives in `def_member`, plus pipe-endpoint restriction rules; PATTERNS.md extended.

**Twenty-first dissolution session.** Cumulative surface at this point: five wire glyphs; seven core sections + two optional; three atom-body lexical positions (constants, standing wires, handlers); one channel primitive (`Stream of T`) with cardinality via contract; one pronoun (`self`); one declaration keyword (`def`); one FFI mechanism (`extern:`); one capability declaration (`use:`); structural typing throughout via `of` qualifier; case-convention as type-variable marker; state-machine atoms with auto-delegation and lexical-read into compound scope.

---

## Session note — Special-case audit dissolutions: Stream cardinality; entrypoints retired; pronoun cleanup; wires retired; conforms optional; constructor-params-as-data-fields (2026-08-12, no version bump; follows same-day atom-substrate consolidation below)

**Post-consolidation audit.** After committing the atom-substrate surface (def / -> / :> / |> / of / use: / extern: / stdlib catalog), walked the design end-to-end for special cases papered over. Six dissolutions identified; user confirmed all six.

**Dissolution 1 — Future / Signal → Stream with cardinality constraints.**

`Future of T` (fires exactly once) and `Signal of T` (fires exactly once, must fire eventually) are indistinguishable from `Stream of T` in shape. Distinction is nominal, not structural. Dissolves to:
- One primitive: `Stream of T` (zero or more values over time).
- Cardinality as `where` constraint: `Stream of T where emits <= 1` (future-shape), `where emits == 1` (signal-shape), `where emits >= 1` (never-empty).
- Prelude aliases for ergonomics: `alias Future of T = Stream of T where emits <= 1`, `alias Signal of T = Stream of T where emits == 1`.

Operators propagate cardinality via contracts: `map: emits(out) == emits(in)`, `filter: emits(out) <= emits(in)`, `debounce: emits(out) <= emits(in) + 1`. Reads structurally; no per-cardinality type constructor.

**Dissolution 2 — All `->` handlers exposed by default; `entrypoints:` retired.**

User's insight: "entrypoints should always be exposed no." Under the atom model, every handler is a channel that receives messages; whether the sender is an external caller or an internal wire is *composition context*, not a property of the handler itself. The `entrypoints:` section was pretending these were two different mechanisms when they're one.

Retired: `entrypoints:` section. Every `-> handler:` declaration is automatically part of the atom's public interface. External callers can invoke; internal wires can invoke; same dispatch. If a truly private handler is ever needed, add a `private` prefix as an escape hatch — no such need has surfaced.

Consequence: compound atoms declare their public API as `-> take_damage(amount):` etc. — same form as internal handlers. One mechanism.

**Dissolution 3 — `self` as sole pronoun; `active` retired; compound atoms auto-delegate to active state's method table.**

Five pronoun sources (`state.x`, `data.x`, `active.x`, bare `x`, `self.x`) collapse to two: `self` (explicit) and bare names (sugar for `self`-scoped lookup in invariants/where-clauses/handler bodies).

`active` retired entirely. In compound atoms with nested state atoms, message dispatch on the compound (`file.read(1024)`) auto-delegates to the currently-active nested state's method table. If the active state has a matching handler, it runs; if not, runtime error ("state `<name>` has no handler `<method>`"). This is the type-state pattern from Erlang OTP's `gen_statem`, spelled by convention rather than pronoun ceremony.

Consequence: fewer pronouns to teach, cleaner state-machine call sites (`file.read(1024)` instead of `file.active.read(1024)`), and the "which state am I in" reasoning happens at read-time via the state atom's name, not via a `self.active` chain.

**Dissolution 4 — `wires:` section retired; pipe expressions live inside handler bodies (or as bare handler one-liners).**

Retired: `wires:` section. Wire chains are just pipe expressions; they live inside `->` handler bodies. Since (2) also retired `entrypoints:`, the one-liner form is:

```
-> take_damage(amount: Int) |> damage_processor(amount)
```

That's a handler with a pipe expression as its body. No `wires:`, no `entrypoints:` — just handlers.

For a compound atom's internal-wiring graph (child-to-child pipes not triggered by an external message), those are handlers on `-> enter:` or `-> setup:` lifecycle hooks, or standing declarations that wire two child atoms' channels together at composition time. If a "standing wire" pattern needs its own syntax, revisit — for now, all wiring is expression-form inside handlers.

**Dissolution 5 — `conforms:` optional under structural typing; compiler warning for likely-matches.**

Under §3.4 structural conformance, an atom conforms to a shape by *having the right methods*, not by *declaring conformance*. The `conforms:` section was documentation + a shape-match assertion. Retired as required section; kept as optional declaration for documentation and explicit-conformance-check (compiler verifies).

Compiler emits a warning ("this atom's shape structurally matches `Filesystem` — consider adding `conforms: Filesystem` for documentation") when structural conformance is likely-intended. Encourages the declaration without requiring it.

**Dissolution 6 — Constructor params ARE data fields; unified.**

`def File(path: Path):` desugars to `def File: data: path: Path` plus an auto-synthesized constructor. No difference between constructor params and immediately-initialized data fields.

Consequences:
- Constructor param default: `def File(path: Path = "/tmp/default"):` = data field with a default value.
- Named-argument call sites: `File(path: "/tmp/foo")` invokes the auto-synthesized constructor with the named data field.
- If an atom needs distinct constructor logic (compute derived fields, validate at construction), it declares `constructor:` explicitly; otherwise the auto-synthesized one covers the default case.

Retires the "which do I use, constructor param or data field?" ceremony. One decision instead of two.

**Cumulative effect of the six dissolutions.**

The atom body's section vocabulary shrinks from:
> `use:`, `extern:`, `data:`, `state:`, `constructor:`, `entrypoints:`, `wires:`, `handlers:`, `observables:`, `invariants:`, `conforms:`, `metadata:`

to:
> `use:`, `extern:`, `data:`, `state:`, `observables:`, `invariants:`, `metadata:` (+ `constructor:` optional; `conforms:` optional)

Plus `-> handler:` declarations (in place of `entrypoints:` + `wires:` + `handlers:` — all three collapse). Plus nested `def` declarations (compound atom composition).

Seven core sections down from twelve. Handler declarations are the sole invocation mechanism (external or internal, same shape). Pronouns are one (`self`). Cardinality is contract-expressed on one channel primitive (`Stream of T`).

**Mechanism-invention trap check.** Passes on all six. Each retires machinery. Nothing added. `conforms:` and `constructor:` remain as optional documentation/customization; not required.

**Spec changes committed this addendum.** §1.13 stdlib shapes rewritten with Stream+cardinality (aliases for Future/Signal); §1.11 and §1.12 updated to drop `entrypoints:` / `wires:` / `active`; grammar §10 updated to reflect the retirements; PATTERNS.md addendum extended.

**Twentieth dissolution session.** Cumulative: `def` as sole declaration; five wire glyphs zero overlap; `use:` sole capability declaration; `extern:` sole FFI; `of` sole type parameter; case convention as type-variable marker; state-machine atoms as resource lifecycle; **`-> handler:` as sole invocation mechanism (external and internal both)**; **`self` as sole pronoun**; **`Stream of T` as sole channel primitive with cardinality via contract**; **seven core sections** (was twelve).

---

## Session note — Atom-substrate consolidation: `def`/`->`/`:>`/`|>`/`of`/`use:`/`extern:` + OS boundary as capability atoms (2026-08-12, no version bump)

**Nineteenth-session consolidation.** Ten committed decisions this session, all under the atom-substrate reduction from §1.11. Each removes a mechanism, reuses an existing one, or names a naming-question at its natural resolution. No new machinery added beyond what falls out of the atom model.

**1. `atom` keyword renamed to `def`.** Three letters, familiar Python/Clojure feel, reads as declaration. `atom` remains in prose for the *substrate concept* (an atom is a reactive node with typed I/O); `def` is the *syntax* that introduces one. `class`, `record`, `enum`, `protocol`, `program` remain as sugar keywords desugaring to `def` shapes. `function` retired (already committed).

**2. Handler prefix `on` retired; replaced by `->`.** Every handler declaration prefixed with `->`:
```
-> input(event):
    state.pending = event
```
Reads as "→ input(event): [do]" — arrow points from incoming event to handler body. Parser distinguishes handler `->` (line-start inside atom body) from return-type `->` (between argument-list-close and shape reference) by position. Same semantic story across positions: arrows point toward flow.

**3. Emit rewritten as `:>` push channel; symmetric with `:<` pull channel.** `emit(value)` → `:> value`. Rationale: `:<` was already committed for observable pull channels; `:>` mirrors it on the emit side. Same visual convention — colon marks the atom-boundary side, arrow direction marks pull vs push. Both operate at the atom's outward-facing surface:
- `:<` — standing declaration; subscribers read on demand (pull)
- `:>` — imperative statement; subscribers receive as it happens (push)

Named channel emit: `:> channel_name(value)`. Default channel: bare `:> value`.

**4. Wire composition unified under `|>`; `->` retires from wire duty.** Mixed `source -> op |> op` in the same chain was inconsistent. Uniform: every wire composition uses `|>`. `->` retains only structural/declarative roles (return-type, handler prefix). Time-deferred pipes use `~>` (visual marker for "pipe with a time gap"). Final glyph family:

| Glyph | Role |
|---|---|
| `->` | return-type in signatures + handler prefix (declarations only) |
| `\|>` | wire composition (all "output of left → input of right") |
| `~>` | time-deferred wire composition (pipe with delay) |
| `:<` | observable pull channel |
| `:>` | emit push channel |

Five glyphs, five distinct roles, zero overlap. `->` in `after N -> handler` sugar retires — see (5).

**5. Time as env-provided capability atom; `after` primitive retired.** `after duration -> handler` was a smuggled primitive that referenced an ambient scheduler nobody declared. Reduction: `Clock` is a capability atom shape (stdlib); the runtime provides a default `WallClock`; user code binds it via `use:` and calls `clock.after(duration) |> handler`. This unlocks:
- Test clocks (`TestClock` with manual `advance_to(t)`)
- Game clocks (`GameClock` driven by frame updates; scales/pauses with game state)
- Simulation clocks (deterministic replay)
- Remote clocks (RPC to time-of-authority)

All are user-writable atoms conforming to `Clock`; no language changes required per variant.

**6. OS boundary generalized — every OS-adjacent capability is an atom.** Time was the first hit; the same pattern applies to Filesystem, Network, Console, Random, Args, EnvVars, Exit, Signals, Log, and (later) Threads, Process, Devices, GUI events. Runtime constructs shim atoms wrapping OS syscalls; user atoms depend on capability shapes, never on OS directly. Layering:
```
[User atoms]     ← pure logic, zero OS knowledge, portable
     ↑ depend on
[env: Clock, Fs, Net, Console, ...]  ← capability atoms
     ↑ wrap
[Shim atoms: WallClock, PosixFs, ...]  ← translate atom calls to syscalls
     ↓ syscall
[OS]
```
Shim/proxy/sandbox/remote are all *the same mechanism* (atom composition). Wrapping `env.fs` with `SandboxedFs(inner: env.fs, allowed: [...])` is normal atom construction. Policy lives in how the runtime constructs env; user code sees uniform interfaces. This IS the effect system, spelled out via `use:` declarations (see 7) — the set of transitive `use:` closures across an atom subtree IS its effect set.

**7. `use:` block for capability declaration + implicit access; `provide:` unified into it.** Every atom declares its env dependencies in a `use:` block:
```
def debounce(duration: Duration):
    use:
        clock: Clock                          # inherit from parent env, expect Clock shape
    ...
    clock.after(duration) |> fire_pending    # bare `clock` — implicit lookup in atom scope
```

The source expression tells the composition checker where the value comes from:
- Shape-only (`clock: Clock`) → look up `clock` in parent env; check conformance
- Construction (`clock: GameClock(tick_ms: 16)`) → construct locally; this compound provides it to children
- Expression (`fs: SandboxedFs(inner: fs, ...)`) → evaluate; bare-name references resolve in *enclosing* scope (parent env), letting the entry wrap the parent's version

Children of the compound see the local bindings uniformly; whether an entry was inherited or constructed is invisible to them. `provide:` was never committed but was drafted; the unification with `use:` retires it before it could become mechanism-invention.

Order-dependent within the block: later entries can reference earlier ones by name. Bare names inside a construction expression resolve in enclosing scope, not the same block (avoids self-reference confusion).

**8. Resource lifecycle = state-machine atom; `-> before_destroy:` for RAII.** Resources (File, Socket, Process, Thread) are state-machine atoms with nested state atoms (`unopened → opened → closed`; `created → connecting → connected → closing → closed`). Handlers only exist on states that own the resource:

```
def File:
    data: path: Path
    def unopened: pass
    def opened:
        data: handle: NativeHandle
        -> read(n: Int) -> Stream of Bytes: ...
        -> write(bytes: Bytes) -> Stream of Int: ...
        -> close: native_close(handle); :> closed()
        -> before_destroy: native_close(handle)    # RAII cleanup
    def closed: pass
    entrypoints:
        open(): requires active == unopened; ...
    invariants:
        active in {unopened, opened, closed}
```

**Use-after-close** falls out: `.read()` is a handler on `opened` only; calling it in `closed` finds no handler → error. **Double-close** falls out: `close` transitions to `closed`; second call finds no `close` handler. **RAII** falls out: `-> before_destroy:` runs on atom destruction; if we forget to `close()`, the destructor path handles it.

For v1.0: runtime dispatch with "state X has no handler Y" errors. Compile-time type-state proofs (Rust typestate crates) deferred to future work.

**9. FFI via `extern:` block within stdlib shim atoms.** Shim atoms declare native bindings in a first-class section:
```
def PosixFs:
    conforms: Filesystem
    extern:
        native_open(path: CString, flags: Int32, mode: Int32) -> Int32:
            symbol: "open"
            library: "libc"
        # ...
    entrypoints:
        open(path: Path) -> File:
            let fd = native_open(path.to_cstring(), O_RDWR, 0)
            ...
```
Rules: `extern:` allowed only in stdlib-designated shim atoms (user code never gets FFI); signatures use C types (`CString`, `Int32`, `SizeT`, `Ptr of Byte`); marshalling from Corvid types (`Path`, `Bytes`) happens in method bodies (explicit at boundary); `symbol:` and `library:` fields resolve per-target — `libc` for native, `wasi_snapshot_preview1` for WASI, `js` for JavaScript host, `env` for WASM component-model. Same capability shape (`Filesystem`) has different shim implementations per target (`PosixFs`, `WasiFs`, `BrowserFs`); runtime picks at build/launch time.

Rejected alternatives: FFI-as-capability (`env.ffi.call("open", [...])`) loses type safety at the boundary and lets user code make arbitrary syscalls; `native def` keyword splits the type system into two atom categories. `extern:` as a section keeps shim atoms as normal atoms with one extra section — no fundamental category split.

**10. Type parameters as `of` qualifier; `<T>` fully retired; multi-channel output via named tuple.** Structural type parameters use the `of` qualifier (extending the retirement of `<T>` committed on 2026-08-11):
- Concrete: `Stream of Bytes`, `List of Int`, `Map of (String, User)`, `Future of File`, `Ptr of Byte`
- Variable (lowercase): `Stream of s`, `List of a`, `Map of (k, v)`

Case convention IS the type-variable marker (TitleCase = type reference; lowercase = shape variable). Binding: first appearance of a lowercase name in an atom's signature scope (constructor params + channel signature + `where` clause + `state:` types + `use:` types) is the binding site; subsequent appearances refer to the same variable. Compiler checks: unresolvable subsequent mention = error; bound-but-never-referenced = warning ("did you mean X?"). No `[a, b]` binding block (that's `<T>` in different clothing); no `'a` sigil (adds noise for no safety gain over consistency check).

Constraints go in `where` alongside preconditions/postconditions:
```
def dedupe:
    Stream of s -> Stream of s where s conforms Hashable
```

Multi-channel operators use **named tuple return type** as the single mechanism:
```
def partition(predicate: (a) -> Bool):
    Stream of a -> (matched: Stream of a, unmatched: Stream of a)
    
    -> input(item):
        if predicate(item):
            :> matched(item)
        else:
            :> unmatched(item)
```

Emission rule: bare `:> value` for default (single unnamed) channel; `:> name(value)` for named channels. Callers reference channels by name on the atom instance (`router.east`, `router.dropped`). No separate `channels:` section — return type is authoritative.

**Stdlib catalog for v1.0 — dogfooded in Corvid.** Every capability listed below is a stdlib atom, built with `extern:` bindings for the syscall boundary. If we can't express `HTTP server`, `Filesystem watch`, or `Signals` cleanly in Corvid, the language has a gap that needs fixing — not a stdlib workaround.

*Core (must ship v1.0):* Clock, Random, Filesystem, Network, Console, Args, EnvVars, Exit, Signals, Log.
*Extended (v1.0 if language supports; else v1.1):* Threads/Workers, Process, IPC, TimeZone/Locale, Crypto.
*v1.1+ (device-specific):* GUI events, Audio, GPU, Sensors/Devices.

**Mechanism-invention trap check.** Passes on all ten. Each commitment removes machinery (`after`, `emit`, `on`, `->` in wire position, `<T>`, `provide:`, `channels:`) or explicitly names a naming-question at its resolution (`def` vs `atom`, `use:` unification, `of` for element types, named tuples for multi-channel). The FFI mechanism (`extern:`) is genuinely new — it names the syscall boundary that shim atoms need — but stays as a section within normal atoms rather than a distinct atom category.

**Spec changes committed this addendum.** See the spec-side changelog for what moved. Sections most affected: §1.11 (direction — new commitments row), §2.5 (keywords — `def`, `use`, `extern`, `of` added; `atom`, `emit`, `on`, `after`, `function` retired), §2.6 (reserved syntactic space — glyph list), §3.2 (composite types — `of` qualifier), plus a discrepancy sweep across §3, §4, §5, §8, §10, §11 for `atom` → `def`, `on` → `->`, `emit(x)` → `:> x`, `Type<T>` → `Type of T`, wire `->` → `|>`.

**Nineteenth dissolution session.** Cumulative reduction after this session: `def` as sole declaration keyword; five wire glyphs (`->`, `|>`, `~>`, `:<`, `:>`) with zero overlap; `use:` as sole capability-declaration mechanism; `extern:` as the OS/FFI boundary; `of` as sole type-parameter mechanism; case convention as the type-variable marker; state-machine atoms as the resource-lifecycle mechanism. OS is a set of capability atoms. Time is one of them. Everything else composes.

**Open follow-ons after this session.**

- Compile-time state-narrowing proofs for state-machine atoms (typestate optimization; deferred past v1.0).
- Dynamic-channel operators (Map-of-Stream shape; user code, not language feature — check assumption when a real use case surfaces).
- Ambient-authority discipline: `use:` catches direct dependencies; transitive audit via tooling. Design the audit tool.
- Grammar sketch (§10) needs a full pass for the new syntactic forms.
- Worked example (§11) needs rewriting in the new syntax.

---

## Session note — Types-as-guards; `<T>` generic-parameter syntax retired at v1.0; wiring glyphs pinned (2026-08-11, no version bump)

**Follow-on from the refined-atom ban.** After committing the ban and drafting a toy example (DebouncedCounter, then a debounce definition sketch), several small syntactic pins landed:

**Wiring glyphs pinned to four:**
- `->` for routing (dual-purpose with return-type-marker; position disambiguates)
- `:<` for observable declarations (colon-prefix carries definition-flavor; left-arrow carries data-flow direction)
- `|>` for pipe (RxJS-style operator chain)
- `~>` for async/deferred wires (debounce, delay, throttle — wires with time)

Retired earlier glyphs: `-->` (collapsed to `->`), `<--` (replaced with `:<`).

**Types-as-guards framing added.** Reminded by user that types were previously framed as guards. Under this framing, the atom's I/O signature IS the guard for pipe-composability — no explicit `is pipe<T, T>` marker needed. Wire boundaries are guarded checkpoints; type mismatch = compile error or runtime check. This lands as the honest reading of Corvid's structural typing at wire endpoints, alongside types-as-atoms at the substrate level.

**`<T>` generic-parameter syntax retired at v1.0.** User pushed: "do we need `<T>` at all?" Under types-as-guards + structural type inference, no. The compiler traces types through atom bodies (handler parameters → state assignments → emit calls) and infers the atom's I/O shape structurally. Every atom is polymorphic-if-body-uses-types-generically; monomorphization happens at use site. Optional annotations remain available for legibility (`(T) -> U` shape notes, `where T is Comparable` constraint notes) but never required. Type-parameter constraint syntax (`<T: Protocol>`) similarly retires — constraints become guards on values at use, not on type parameters at declaration.

**Debounce as ordinary atom.** Drafted debounce with no `<T>` — reads cleanly:
```
atom debounce(duration: Duration):
    state:
        pending = nil
        current_token: Int = 0
    on input(event):
        state.pending = event
        state.current_token += 1
        let this_token = state.current_token
        after duration -> fire_pending(this_token)
    on fire_pending(token: Int):
        if token == state.current_token and state.pending != nil:
            emit(state.pending)
            state.pending = nil
```
Prelude-shipped operator. Same mechanisms as user atoms. No language-level generics; no compiler intrinsic — just a stateful atom with a timer transition. Users can write their own `debounce_leading_edge`, `throttle`, `sample`, etc. the same way.

**Spec changes committed this addendum.**

- `spec/spec-v0.11.md` §1.11 wiring glyphs table — `-->` → `->` with disambiguation rationale; `<--` → `:<` with declaration-flavor rationale.
- `spec/spec-v0.11.md` §1.11 v0.12+ list — added structural type inference through atom bodies as precursor to `<T>` retirement.
- `spec/spec-v0.11.md` §1.11 v1.0 substrate list — added types-as-guards commitment; `<T>` retirement commitment (with monomorphization at use site); type-parameter constraint syntax retirement; updated wiring glyphs to `->`, `:<`, `|>`, `~>`.

**Mechanism-invention trap check.** Passes. Every retirement removes a mechanism (`-->` glyph, `<--` glyph, `<T>` generic-parameter syntax, `<T: Protocol>` constraint syntax) — nothing added. The types-as-guards framing is a re-reading of existing structural conformance, not a new mechanism.

**Eighteenth dissolution session.** Cumulative reduction is now: `atom` as sole primitive; sugar keywords for common shapes; types are atoms (pure shapes); predicates in invariants/contracts/predicate-atoms; wire boundaries are guarded checkpoints; types flow structurally through atom bodies without generic-parameter ceremony. Four glyphs, one keyword, structural everything.

**Debounce example in the session.** Concrete artifact showing the atom model in action: stateful stream operator, timer-triggered emission, invariants, all with no generics, no explicit types. If prelude-shipped RxJS operators fit in ~15 lines of atom declaration each, the "operators are just atoms" claim is real.

---

## Session note — Refined atoms banned; predicates stay in invariants/contracts/predicate-atoms (2026-08-11, no version bump; supersedes earlier session's refined-atom choice)

**Reversal of the refined-atom commitment from earlier this same session.** Previous addendum committed choice (b) for refined atom identity — refinements share representation with their base, structural subatoming via refinement, Liquid-Haskell-style propagation. User pushed back: "perhaps we should ban `atom Nat: Int where atom.value >= 0` form." After considering the trade-offs, banning wins. Reversal explicitly supersedes the earlier commitment.

**What the user showed as the shape they wanted:**

```
atom Counter:
    state:
        count: Int = 0
    invariants:
        count between 0 and 100
```

Reading: predicates live in `invariants:`; bare field names are in scope (no `atom.` pronoun); no floating type-name (Nat, BoundedNat, etc.) that abstracts the constraint away from the atom that owns it.

**Design decisions this addendum commits.**

- **No refinement types at the type-signature level.** No `atom Nat: Int where value >= 0`; no floating type-name that carries a predicate. Types are pure atom shapes.
- **Predicates live in one of three places, all first-class:**
  - Invariants on atoms (single-atom or cross-atom constraints), using bare field names in scope
  - `requires` / `ensures` on transitions (use-site preconditions/postconditions)
  - Reusable predicate atoms (Bool-returning leaf atoms) invoked from invariants and contracts
- **Bare field names in scope for invariants** — no `atom.` or `self.` pronoun required (though allowed for disambiguation).
- **Type-checking is pure atom-shape-conformance** — structural, no refinement propagation, no dependent-typing verification cost.
- **Types-as-atoms unification stays intact** — types ARE atoms (as shapes). What changes is that atom SHAPES don't carry predicates; predicates are a separate first-class mechanism.

**Why the reversal is right.**

Refined types create a proliferation of type-names (Nat, BoundedNat, Email, Percent, ...) that abstract constraints away from the atoms that maintain them. The constraint on Counter's count is Counter's constraint — putting it in Counter's `invariants:` keeps the predicate co-located with the state. Reusability via predicate atoms costs one line at the invariant (`is_percentage(rate)`) instead of one type declaration + signature-level enforcement + refinement-inference machinery. This is simpler, more localized, more familiar to Swift/Rust/Kotlin users, and avoids the verification complexity of dependent typing while keeping all the actual expressiveness (predicates on values are still first-class — they just live in invariants and contracts, not in type signatures).

**What we give up:**
- Named refined types at signature positions (`atom foo(n: Nat)` doesn't work; write `atom foo(n: Int) requires n >= 0` instead).
- Compile-time refinement inference across operations.
- The Liquid-Haskell / Whiley / dependent-type direction at v1.0.

**What we keep:**
- Types-as-atoms unification (§1.3, §1.11) — atoms are the primitive; types are named shapes.
- All predicates first-class — via invariants, contracts, predicate atoms.
- Structural conformance on atom shapes (§3.4).
- Constraints local to where they matter (co-located with state or transition).

**Spec changes committed this addendum.**

- `spec/spec-v0.11.md` §1.3 — rewrote types-as-atoms paragraph. Removed the second half about refined types + refinement propagation + structural subatoming via refinement. Replaced with "predicates on values are invariants and contracts, not refined types" — documents the ban, shows the three predicate locations (invariants, transition contracts, predicate atoms), explains why the ban is right and what's traded away.
- `spec/spec-v0.11.md` §1.11 reduction table — removed "refined type" row. Kept "type" and "generic type" rows.
- `spec/spec-v0.11.md` §1.11 closing paragraph of reduction — rewrote from "type-checking is atom-shape-conformance + refinement satisfaction" to "type-checking is atom-shape-conformance only — pure structural. Predicates live in invariants/contracts/predicate-atoms."
- `spec/spec-v0.11.md` §1.11 v0.12+ list — updated "Types-as-atoms unification" entry to reflect the ban. Removed "Refined atom syntax" entry entirely.
- `spec/spec-v0.11.md` §1.11 v1.0 substrate list — removed "Refinement propagation" and "Structural subatoming via refinement" commitments. Added positive commitments: "type-checking is atom-shape-conformance (structural); no refinement types at signature level"; "predicates live in invariants / requires / ensures / predicate atoms."

**Mechanism-invention trap check.** Passes and then some. This session went further than the check normally reaches — it caught mechanism-invention I had committed and reversed it. Refined types were extra mechanism (a whole system for propagating predicates through operations, structural subatoming, refinement inference) when invariants+contracts+predicate-atoms already cover the same expressiveness. Corvid's discipline held.

**Seventeenth dissolution session** — this one is a reversal + simplification. Undoes the sixteenth session's commitment to refined atoms; keeps only the parts that were genuinely necessary (types-as-atoms unification, no separate type system, atom is the primitive). Net effect: strictly simpler v1.0 substrate than the sixteenth session's landing.

**Positioning correction.**

Previous framing: "types are atoms; refined types are atoms + predicates; Liquid-Haskell-style refinement propagation at v1.0."

Corrected framing: "types are atoms (pure shapes); predicates are contracts and invariants (first-class, but separate from types); no refinement types at the signature level; type-checking is structural conformance."

Cleaner. Aligns with Corvid's Clojure-inspired ethos (one mechanism where possible; avoid adding features that duplicate what's already there). Aligns with Swift/Rust/Kotlin position (predicates are assertions, not types) — familiar to Corvid's target audience.

**Open item.** `between 0 and 100` — is this a special ternary operator, sugar for `0 <= count <= 100` (Python-style chained comparison), or a method-call `count.between(0, 100)`? Design question deferred; whichever surface shape reads best. The mechanism underneath is a Bool-returning atom either way.

---

## Session note — Types-as-atoms unification (extends atom model landing, 2026-08-11, no version bump)

**Follow-on to the atom model landing.** User pushed on the types-as-contracts framing I'd sketched: "consider if types are contracts." I laid out three views (A: full unification, B: distinct, C: partial — types as representation-bearing contracts). User then jumped a level: "perhaps then we can unify types and atoms? consider that integers are records."

**The chain that lands the unification.**

1. Integers are records (§3.1 — already committed this session).
2. Records are compound atoms containing only data atoms (§1.11 direction).
3. Therefore integers ARE atoms.
4. Generalizing: every "type" is an atom (or a shape of atom).

Under this, the types-vs-contracts question dissolves. Types are atoms; refinements are predicates over atom instances; the syntactic split (`: Type` vs `where predicate`) becomes "does this contract determine representation or just narrow validity."

**Design decisions this session.**

- **Types are atoms.** Every type-name is an atom-name; every value is an atom-instance; type-checking is atom-shape-conformance + refinement satisfaction.
- **Refinements as (b), not (a).** `atom Nat: is Int where value >= 0` declares a refinement of Int: same shape, narrower validity, shared representation. Structural subatoming via refinement: Nat is-a Int (substitutable freely), Int is-not-a Nat (requires runtime refinement check). Whiley / Liquid Haskell model, not Rust newtype model.
- **Refinement propagation via inference (Liquid-Haskell-style).** When operations combine refined values (`nat1 + nat2`), compiler infers when the result satisfies the refinement (positive + positive = positive). When inference fails, result widens to base atom. Runtime checks fill undecidable gaps.
- **Structural conformance composes naturally.** Corvid's §3.4 structural typing already handles the substitutability question — Nat has all Int's operations + refinement; passing a Nat where Int expected is free; the reverse requires runtime check.
- **`type` and `contract` keywords retired at v1.0.** Types ARE atoms; there's one declaration form (`atom`) and one narrowing form (`where`). No separate mechanism.

**Spec changes committed this session.**

- `spec/spec-v0.11.md` §1.3 — rewrote the "types-as-contracts unification" paragraph as "types-as-atoms unification." Documents the reduction (types are atoms; refined types are atom + refinement predicate; the syntactic split between `: Type` and `where predicate` is transitional). Names (b) explicitly as the identity choice. Names refinement propagation as v1.0 target.
- `spec/spec-v0.11.md` §1.11 reduction table — added three rows: "type" → an atom; "refined type" → atom + refinement predicate; "generic type" → parameterized atom. Added closing paragraph "Types are atoms" naming the dissolution.
- `spec/spec-v0.11.md` §1.11 v0.12+ list — replaced "Types-as-contracts unification" with "Types-as-atoms unification"; added refined-atom syntax entry.
- `spec/spec-v0.11.md` §1.11 v1.0 substrate list — added `type` and `contract` keywords to retirement list; added refinement propagation commitment; added structural subatoming commitment.

**Mechanism-invention trap check.** Passes. No new mechanism — the unification REMOVES a mechanism (the artificial separation of types from atoms). All the pieces (atoms, refinements, structural conformance, `where` clauses) were already in the spec; they now compose consistently under one primitive.

**Dissolution-pattern update.** Sixteenth session. This dissolution is a follow-on to the fifteenth (atom model as v1.0 target); together they represent the full reduction the composition thread has been building toward. Types dissolve into atoms; contracts dissolve into refinement predicates; the "type system" as a distinct sub-system disappears.

**What's left to consider (natural follow-on threads).**

- Refined atom syntax details — `atom Nat: is Int where value >= 0` vs `atom Nat is Int where value >= 0` vs `type Nat is Int where value >= 0` (as sugar). Which reads best?
- Refinement propagation semantics — how does the compiler infer? Where does it give up and widen? Runtime check strategy at gaps?
- Generic atoms — `atom List<T>: ...` parameterized over another atom-contract. Does T mean "an atom with this shape" or "an atom conforming structurally to this signature"? Probably the latter (structural).
- Class-scope invariants remaining — cross-atom predicates in `invariants:`; are these first-class atoms too (compile-time predicate atoms), or a distinct mechanism? Probably distinct — they're checks, not entities.

**No version bump.** Cleanup of framing; existing constructs stay accurate.

---

## Session note — Atom model committed as v1.0 target; controlled mutability landed as §1 premise (2026-08-11, no version bump)

**Design landing after extended architectural thread.** Starting from "how does assignment work in pure OOP" and cycling through Smalltalk-style reduction (Kay's message-passing ideal — reduced-but-not-completed), Erlang OTP (actors with state), CSP + core.async (channels + transducers), Kahn process networks (deterministic dataflow), and Milner's π-calculus (everything is a process or channel), user landed on a specific reduction:

- **The atom is the primitive.** Private state + transition rule + typed I/O channels + optional contained atoms + optional wires + optional entrypoints + optional observables + optional contracts.
- **Classes ARE atoms** in Kay's original sense (Smalltalk's messaging vision applied structurally, not just by convention). A class is a compound atom — bounded container of contained atoms wired into an internal graph, with external entrypoints and observables.
- **Every atom is Env → Env** at its transition. Pure functions at the theoretical level; `(Env, Msg) → (Env, [Emission])` at the semantic level; emit-as-statement at the ergonomic surface (compiler lifts to pure form). Same reduction as Haskell's IO monad through do-notation. Emissions are dispatched by the containing atom's runtime (Elm architecture, Erlang OTP shape).
- **No functions as a distinct primitive.** Functions are the trivial atom shape (in | out, no state, pure transition). At v1.0, `function` keyword retires; write `atom name(x) -> y: ...` for the trivial shape.
- **No separate type system.** Types are named contracts. Refinements are contracts with `where` clauses. Cross-field predicates are `invariants:` sections. The split at the current spec surface (§1.3 promises unification; syntax still has `count: Int` distinct from `invariants: count >= 0`) is a transitional shape to be resolved by v1.0.
- **Controlled mutability as the governing discipline.** Mutation exists but is bounded (to one atom's state or a wire's payload), typed (transition contract declares changes), explicit (no ambient `self.x = y`), and observable (emissions are values the runtime can log/replay/mock).
- **Wiring glyphs earned their place through the WebSocket state-machine example.** `-->` (route), `<--` (observable read), `|>` (pipe operator chain), `~>` (async/deferred wire). Four glyphs, each corresponding to the four most common atom operations.
- **RxJS operators are first-class atoms** insertable inline in wires. `debounce(500ms)`, `batch(100)`, `throttle(10/sec)` etc. are prelude-shipped atoms (one input event, one output event, state = operator's memory). Users get RxJS as a language feature, not a library — the compiler sees the operators in the dataflow graph and can fuse/optimize/verify contracts across compositions.

**Spec changes committed this session.**

- `spec/spec-v0.11.md` §1.3 — tightened. Added paragraph naming the types-as-contracts unification as the v1.0 target and calling out the split at the current surface as transitional.
- `spec/spec-v0.11.md` §1.10 (new) — Controlled mutability as design premise. Documents the four discipline principles (bounded, typed, explicit, observable), contrasts with adjacent positions (pure FP, free imperative OOP, actor systems, CSP), enumerates what the discipline makes possible (reasoning locality, concurrency simplification, testability, time-travel debugging, RxJS operators, capability-based security).
- `spec/spec-v0.11.md` §1.11 (new) — Direction: atom model as v1.0 target. Concrete reduction table (function → leaf atom, record → compound-with-data-only, class → compound-with-state, enum → sum-state-atom, protocol → atom signature, section → compile-time atom, program → top-level compound). Documents Env → Env at every level, wiring glyphs, controlled-mutability enforcement. Names what's already committed at v0.11 as incremental moves aligned with the direction, and what's planned for v0.12+ (kind: cell, types-as-contracts unification, method-call desugaring, contract-first discipline, wiring-glyph grammar). Names v1.0 substrate commitments explicitly.

**No mechanism changes in v0.11.** Existing constructs (records, regular classes, enums, methods, etc.) remain accurate under both the current framing and the v1.0 atom framing — they're approximations of atom shapes that tighten each version. Nothing to migrate; nothing breaks. Users writing v0.11 code today are writing "atoms in disguise" — the underlying model won't change out from under them, the surface just gets more honest about what they're writing.

**External reference.** User authored `atom-unification.md` (external — not in the repo) documenting the theoretical derivation from Kay's Smalltalk through the class barrier (contracts, typestate, session types, linearity, capabilities, effect systems, separation logic), CSP with first-class channels + transducers, and dataflow compilation (Kahn networks, Lustre, MLIR). The document ends at the atom as the unifying primitive; the reduction is forced by the problem sequence, each step responding to a specific structural failure in the previous step. Corvid's role is to build one consistent language that lands the whole reduction at v1.0.

**Dissolution-pattern update.** Fifteenth dissolution session, and the largest structural dissolution to date. Not eliminating one mechanism — re-founding the entire language on a single primitive from which all others reduce. The mechanism inventory shrinks dramatically at v1.0: one keyword (`atom`), sugar for common shapes, dataflow compilation, contract substrate. Nothing added; many things unified.

**Composition thread officially concluded.** Value types over Bytes; primitives as records; `data:` usage-site rule; atoms as substrate; everything is `Env → Env`. The thread that started with "does Integer literally have bytes as a field" landed at "every construct in Corvid is a shape of atom." Full end-to-end honesty on the composition claim, and now the substrate for the composition (atoms) is documented as the language's v1.0 target.

**Toy syntax explored (not committed).** WebSocket state machine sketched in two forms — marker (`-->`, `<--`, `|>`, `~>`) and marker-less (keyword-heavy). Marker version wins for state-machine-heavy code (transitions visible as graph edges); marker-less version wins for consistency. Both can coexist — markers as sugar for one-line handlers, marker-less for multi-line. Full commitment on syntax deferred to v0.12+ / v1.0 when the atom substrate lands.

**What this means for user positioning of Corvid.**

Before this session: "Swift competitor for the Apple ecosystem with Clojure-inspired value/reference discipline and ObjC interop."

After: "Erlang-substrate reactive language with Swift-shaped surface, CSP + core.async composition, contract-based verification, and dataflow compilation. Class = bounded atom graph. Functions = the trivial atom shape. Everything is an atom." The Swift-shaped surface stays; the atom substrate is the v1.0 commitment. Distinctive position — no widely-used language currently ships this integration (Erlang has actors but no static types + dataflow compilation; Elm is UI-only; Pony has actors + capabilities but not the reactive-operator layer; Rust has ownership + async but not the reactive-atom substrate).

---

## Session note — Primitives as records over Bytes; refinement-driven wire-format specialization (2026-08-11, no version bump)

**Continuation of tension #2 reframe and the composition thread.** After settling that value types have canonical bytes as a base-class property (§3.4.6) and that Integer literally composes over `bytes: Bytes` (user's `class Integer: bytes: Bytes` framing), a residual layering fudge remained: §3.4.6 said "primitives have no field layout for the compiler to synthesize from," and §3.11.4 shipped per-primitive `extend class Bool: methods: function as() -> Bytes: ...` blocks in prelude — framed as "concrete implementation of the base-class property," but syntactically indistinguishable from user extensions. That was still primitive-specialness under a different name.

**User's push.** "It sounds like common sense" — dismissing my framing of refinement-driven wire-format specialization as a design choice. Right; it's not a choice, it's what refinements are for. The residual specialness was the real issue, and (A) — primitives-as-records-with-`bytes:`-field — is the honest resolution.

**Design chosen: (A).** Primitives are `kind: record` shipped in prelude with `data: bytes: Bytes where length == N`. Byte methods synthesize from the field layout the same way as user records. §3.10.10 wire format gets a refinement-driven length-prefix specialization: `where length == N` (fixed) → raw N bytes no prefix; `where length <= N` (bounded) → smallest UInt type fitting N; unrefined → UInt32. This makes synthesis produce the correct primitive encoding without per-primitive overrides. Arithmetic/comparison/IEEE 754 stay as compiler intrinsics (unchanged); storage stays inline machine words (compiler specialization).

**Spec changes.**

- `spec/spec-v0.11.md` §3.1 (Primitive Types) — rewritten. Was: "primitives are value types following record semantics" (opaque list). Now: "primitives are records over `Bytes`, shipped in prelude" with the record declarations shown for Bool, UInt8, UInt32, Int32, Float, Double, Char. Consequences paragraph documents that value semantics falls out of `kind: record` at the record boundary; storage-as-machine-word is a compiler specialization; nil-representation logic unchanged.
- §3.4.6 — the "primitives have no field layout" claim reversed. New wording: primitives are `kind: record` shipped in prelude with `data: bytes: Bytes where length == N`; byte methods synthesize via the same pathway as user records.
- §3.10.10 — Bytes wire-format table entry split into three cases: unrefined (UInt32 prefix), `where length == N` (no prefix), `where length <= N` (smallest UInt fitting N). New paragraph on refinement-driven length-prefix specialization; explicitly notes this is symmetrical (user records with refined-length Bytes fields — `data: mac: Bytes where length == 6` — get the same specialization).
- §3.11.4 — retired the per-primitive `extend class` blocks. Kept the `.as_bytes(order:)` explicit-endian method (still shipped explicitly on each multi-byte primitive since it's not part of `As<Bytes>` dispatch). Added a paragraph explaining what the reframe eliminated ("No per-primitive prelude implementation").
- v0.11 changelog block — added "Second reframe (2026-08-11) — primitives as records" paragraph documenting the reframe.

**Mechanism-invention trap check.** Passes. Refinement types — existing (§3.6). `kind: record` — existing (§3.3). Field-layout synthesis for byte methods — existing (§3.4.6). Compiler intrinsics for primitive arithmetic — existing implementation-level (not a new language mechanism). Wire-format refinement specialization — extension of existing wire format rules, uses refinement info that's already there. Zero new mechanisms. The reframe REMOVES a mechanism (per-primitive prelude extend blocks doubled as base-class-property provision) rather than adds one.

**Dissolution-pattern update.** Fourteenth session. This one is the biggest dissolution yet in structural terms — an entire "primitive" special category collapses into `kind: record` + refinement types + compiler intrinsics. Primitives stop being a taxonomically-distinct family with independent value semantics; they're records like any other. The taxonomy becomes: every user type is a class; classes vary by `kind:`; value semantics comes from `kind: record` at the class boundary; primitives are the leaf-level records over `Bytes`; composition all the way down.

**No version bump.** The reframe changes the SPEC framing substantially (§3.1 rewritten, §3.11.4 shrunk) but the LANGUAGE behavior is identical: same wire format, same arithmetic, same storage, same conformances. What changes is the layering story — "primitives are records over Bytes, unified with user records" replaces "primitives are opaque, byte methods hand-shipped in prelude." v0.11 remains the version that introduced canonical bytes as a value-type base-class property; this addendum makes primitives fit the same story cleanly.

**Composition thread — done.** Value types compose over Bytes. Primitives are records with `bytes: Bytes where length == N`. User records with fields transitively compose into bytes. Regular classes don't (references, cycles, identity). Base-class property (canonical bytes) IS the projection extracting the composed form; conformance to `As<Bytes>` / `From<Bytes>` is the consequence. §3.10.10 wire format synthesizes deterministically from the composition. Whole story now honest end-to-end.

---

## Session note — `data:` field usage-site rule; value-typed containers over regular fields (2026-08-11, no version bump)

**Continuation of tension #2 reframe.** After settling that value types compose over Bytes (Integer literally has `bytes: Bytes` as a field, per composition-is-honest reading), user surfaced a real subtlety: if Integer has a `Bytes` field where Bytes is `kind: regular` (mutable, referenced), how does Integer preserve value semantics?

**Wrong first framing (mine).** I claimed "Integer contains a reference — value semantics breaks; would need FixedBytes<N> as a new type." User pushed back: "but why isn't the value semantics defined from the Integer class itself?"

**Correct framing.** Value semantics is defined at the record class boundary (§3.3 `kind: record`), not by the field types. Records can hold `kind: regular` fields; the record boundary handles the semantics via COW/deep-copy. This is the standard VTCRT (Value Type Containing Reference Type) pattern — Swift's `struct String` internally holds a reference to a buffer, is nonetheless a value type. Same mechanism works for `record Integer: bytes: Bytes`. No new type needed.

**Then a second subtlety surfaced.** Under COW, mutation on a Bytes obtained from an Integer field triggers a copy — the Integer is unaffected. But this is silent: `int.bytes.set(0, 99)` looks like mutation, actually happens on a copy. Developer could be surprised.

**User's dissolution.** "Anything placed in `data:` should be assumed to reader only, which is what was originally intended anyway — remember, structs is class with data only."

That's the fix. `data:` isn't just a declaration marker; it's an advisory at the usage-site too. Writer calls on values obtained from a `data:` field emit a warning. No new annotation invented (`@readers_only` was mechanism-invention — sibling naming, rule 5 of the trap memory). The existing `data:` marker + existing reader/writer classification (§8.4.1) compose to give the diagnostic.

**Design chosen: (b) `data:` marker + writer diagnostic.** Not (a) immutable view type (adds `Bytes.View` as a new type, duplicates reader method surface); not (c) COW alone (silent, too implicit).

**Spec changes.**

- `spec/spec-v0.11.md` §3.3 — new paragraph after value-type base-class-property discussion: "`data:` fields carry a usage-site rule, not just a declaration-site meaning." Documents the two commitments (declaration-site data-vs-state; usage-site writer-call diagnostic). Includes example warning text (W0410 `data-field-mutation`). Flow-tracking scope: direct calls + one-hop through variable assignment; deeper chains rely on writer-effect diagnostic at the actual mutation site.
- `spec/spec-v0.11.md` §8.4.1 — added cross-reference paragraph noting that reader/writer classification composes with `data:`/`state:` at field level to enable the diagnostic.

**Mechanism-invention trap check.** Passes. Zero new mechanisms. `data:` — existing (§3.3). Reader/writer classification — existing (§8.4.1). COW under value-semantics-at-boundary — existing behavior. Diagnostic — new content, uses existing composition of mechanisms.

**Dissolution pattern.** Thirteenth session. The `data:` marker was carrying less weight than it always meant to. Making it load-bearing at the field-usage layer requires no new mechanism, only spec text acknowledging the composition of existing labels.

**No version bump.** Small clarifying additions to §3.3 and §8.4.1. Semantics were arguably always intended (pre-v0.6.0 `struct` = class with data only); v0.11 makes it explicit.

**Composition thread continues.** The `data:` usage-site rule closes the "value-type containing regular field" layering question. The composition reading (Integer literally has `bytes: Bytes`) now holds without needing FixedBytes<N> or a distinct immutable view type. Next issue in the design-coherence audit queue: to be surfaced.

---

## Session note — v0.11 auto-conformance reframed as base-class property (2026-08-11, no version bump)

**Follow-up to design-coherence audit tension #2.** The audit surfaced that v0.11's initial framing — "compiler auto-conforms value types to `As<Bytes>` and `From<Bytes>`" — was a specific-instantiation carve-out inside a general protocol (§3.4.6 vs §3.10.3). The compiler was knowing about a specific target-type name (`Bytes`) and a specific protocol instantiation (`As<Bytes>` / `From<Bytes>`); the general shape of `As<T>` says protocols are user-target-agnostic, so a compiler special-case per target name was a layering leak.

**User pushback stopped the janky "auto_derive:" metadata block proposal.** The first-cut structural fix reached for a metadata block letting protocols declare `derivable: from_field_layout` — but this would let user protocols demand compiler synthesis they can't guarantee is correct, and reintroduced compiler awareness of arbitrary protocol names. User: "this seems really janky tbrh."

**Second cut — base-class property.** Records already carry `==` / `hash` / copy-on-assign as base-class properties, synthesized from field layout at composition time. Canonical bytes is the same shape: one more base-class property on value types. The compiler synthesizes `.as() -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` because value types have canonical bytes as a fundamental property (§3.10.10 wire format); structural conformance to `As<Bytes>` and `From<Bytes>` (§3.4) falls out as a *consequence* — the required methods exist, structural check succeeds, no separate auto-conformance mechanism. The compiler stops knowing target-type names entirely; it just knows how to synthesize field-layout methods on value types.

**Documentation changes.**

- `spec/spec-v0.11.md` §3.3 (User-Defined Types) — extended `kind: record` and `kind: enum` entries to explicitly list canonical bytes alongside equality/hashing/copy-on-assign as value-type base-class properties. Added a paragraph clarifying that base-class properties are language commitments (not protocol conformances); structural conformance is the consequence.
- `spec/spec-v0.11.md` §3.4.6 — title changed from "Auto-Conformance for Value Types" to "Canonical Bytes as a Value-Type Base-Class Property." Rewrote opening: no more "compiler synthesizes conformance to As<Bytes>"; new framing is "compiler synthesizes byte methods on the class; conformance follows per §3.4." Rewrote all "auto-derived" language to "synthesized." Removed "Only As<Bytes> / From<Bytes> get auto-conformance" heading (was reifying the carve-out); replaced with "Why only bytes get the base-class property" — reframed as extending the base-property list, not a protocol-specific rule.
- `spec/spec-v0.11.md` §3.10.10 — opening reframed to "wire-format specification for the byte methods that §3.4.6 documents as value-type base-class properties."
- `spec/spec-v0.11.md` §3.10.8 — serialization substrate paragraph unchanged; "auto-conformance for value types" follow-up paragraph reframed as "Value types have canonical bytes as a base-class property" — points to §3.3 and §3.4.6.
- `spec/spec-v0.11.md` §3.11.4 — title changed from "Byte Conformances on Primitives" to "Byte Methods on Primitives"; opening explains primitives inherit the base-class property but need explicit prelude methods since they have no field layout for the compiler to synthesize from.
- `spec/spec-v0.11.md` v0.11 changelog block — rewrote status paragraph and change-log entries to reflect new framing; added a "Reframe from v0.11 initial draft" paragraph documenting the tension-2 audit and the resolution. Consistency-with-prior-decisions block updated to reference §3.3 pattern rather than §3.2. Mechanism-invention trap check block updated: "compiler synthesis of base-class methods → §3.3 pattern for records extended (same shape as existing == / hash synthesis)."
- `PATTERNS.md` "I need Codable / JSONEncoder / auto-serialization" — reframed from "auto-conformance to As<Bytes>" to "canonical bytes as a base-class property"; "auto-derived" → "synthesized"; "auto-conformance is only for As<Bytes>" heading → "only bytes get the base-class property."
- `~/.claude/memory/corvid-mechanism-invention-trap.md` — appended rule 7 (method-family shape check): distinguish strategies-competing-for-same-result-slot (→ method names, e.g., wrapping/saturating/checked) from single-canonical-operation-with-parameter-axis (→ single method with parameter, e.g., as_bytes(order:)). Meta-observation updated: trap has surfaced four times now, adding "choosing the wrong axis of variation" as the closely-related failure mode.

**Design-coherence tension #3 addressed by memory rule 7.** The tension: `.wrapping_to::<T>()` / `.saturating_to::<T>()` / `.checked_to::<T>()` got three method names for what could-in-principle be one method with a parameter; `.as_bytes(order:)` got one method with a parameter for what could-in-principle be three method names. Rule 7 gives the decision procedure: strategies (mutually-exclusive, algebraic-identity-differs-per-strategy) → method names; parameters (varies inside a single operation) → parameter. Narrowing strategies produce different values (`Int32(300).wrapping_to<UInt8>() = 44` vs `.saturating_to<UInt8>() = 255`) — different algebraic identity, strategies compete. Endianness varies which bytes come out for a single "encode this value" operation — same identity, one method, parameter axis. Both resolutions are correct under the rule.

**Composition question deferred.** User's next thread: "lets discuss if really strings, integers etc have a byte component (composition)." Open question — does the "value types HAVE canonical bytes" framing rest on a genuine composition/decomposition relation, or is it a mapping asserted at the language boundary without composition backing? To be worked next.

**No version bump.** Framing corrections in live docs + memory refinement + working-notes addendum only. The spec content lands identically at the compiled-code level; only the layering/framing/audit-narrative changes. v0.11 remains the version that introduced canonical bytes as a value-type base-class property.

---

## Session note — v0.11 Value-type auto-conformance + double-colon header form removed (2026-08-10)

Sixth same-day session in the post-v0.10 chain. User picked "1+2" from the remaining open list — v0.11 spec sections for auto-conformance direction that was framing-corrected in the previous addendum, bundled with the scheduled double-colon header form removal.

**Two things landed together in v0.11.**

**Thread 1 — §3.4.6 Auto-Conformance for Value Types (new).** Every `kind: record` and `kind: enum` automatically conforms to `As<Bytes>` and `From<Bytes>`. Compiler synthesizes `function as(self) -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` from field layout at composition time. Primitives get the same conformances shipped explicitly in prelude via `extend class Float: methods: function as(self) -> Bytes: ...` etc. (§3.11.4 new). `kind: regular` classes do NOT auto-conform — mutable state, potential cycles, identity require explicit user-written serialization. User-written `.as() -> Bytes` on a value type shadows the auto-derived one per §3.3.2 (own > inherited default).

**Thread 2 — §3.10.10 Canonical Value-Type Encoding (new).** Wire-format spec for the auto-conformance output. Deterministic, roundtrippable, byte-position-recoverable.

- **Endianness: network-order (big-endian).** Silent breakage across architectures is a footgun; native-endian users opt out by writing their own `.as() -> Bytes` on the record using each primitive's `.as_bytes(order: ByteOrder.host)` method (§3.11.4). Explicit-endian access is a specific-name method, not part of `As<Bytes>` dispatch.
- **Length prefix width: UInt32 (4 bytes).** Fixed width for decoder simplicity; up to 4 GiB per collection/string is sufficient for on-disk cache, IPC, snapshot use cases.
- **Discriminator width scales with case count.** ≤256 cases → UInt8; 257–65536 → UInt16; beyond → UInt32. Avoids the 4-byte-always-per-enum tax that a naive UInt32 discriminator would impose.
- **Optional as presence byte + payload**, not sentinel value — sentinel encoding fails for types where the sentinel value is meaningful (`Int32(0)`, `Float(0.0)`).
- **Field / case declaration order determines encoding.** Reordering is wire-incompatible; adding a mid-list case is wire-incompatible; adding a trailing case is compatible.

**Thread 3 — double-colon header form removed.** `class Foo: A, B:` and `class MyViewController: UIViewController:` now hard compile errors. v0.10.2's parse-time warning + fix-it grace period ended. Migration was mechanical (`Foo(A, B)` for conformance; `metadata: extends:` for ObjC inheritance).

**Mechanism-invention trap check.** Passes. `As<Bytes>` / `From<Bytes>` → §3.10.3, not new. Auto-derivation as base-class property → §3.2 pattern for records, not new. Wire format spec → new content but not a new mechanism. Method names uniformly `.as()` / `.from()` — never `as_bytes()` / `as_json()`; never `derive: as_bytes`. Zero sibling protocols invented; zero per-target method names invented; zero "third-party format story" deferred. Third-party formats use existing extension mechanism as previously agreed.

**Only `As<Bytes>` gets auto-conformance** — because bytes are the universal substrate with one canonical encoding. JSON/MsgPack/etc. have per-record author choices (field naming — camelCase vs snake_case? null handling? number precision? enum spelling?) with no defensible language-level default; silent choice would be a footgun. Users write `function as(self) -> Json:` explicitly.

**Design questions resolved in-session (called out in changelog):**

- **Endianness default: network-order over native-order.** Correctness across architectures wins over per-primitive byte-swap cost on little-endian hosts (single CPU instruction, negligible for the common case). Same-host performance users write their own encoding.
- **Length prefix width: fixed UInt32 over VarInt.** Decoder simplicity + seekability wins over saving 1 byte on small collections.
- **Discriminator scales with case count.** Avoids per-enum overhead tax.
- **Sentinel-Optional rejected in favor of presence byte.** Sentinel would fail for types where the sentinel value is meaningful.

**Files touched this session:**

- `spec/spec-v0.10.md` → renamed to `spec-v0.11.md`.
- Preamble bump v0.10.2 → v0.11; changelog block for both threads.
- `§3.4.6` new (~150 lines) — Auto-Conformance for Value Types.
- `§3.10.10` new (~120 lines) — Canonical Value-Type Encoding with per-type table + failure modes + guarantees + non-goals + rationale.
- `§3.11.4` new (~90 lines) — Byte Conformances on Primitives, with `Bool` / `UInt8` / `UInt32` illustrated in detail; `.as_bytes(order: ByteOrder)` explicit-endian method spec.
- `§3.11.5` renumbered from prior `§3.11.4` (Convertible retirement note).
- `§3.11.6` renumbered from prior `§3.11.5` (Bounded protocol).
- `§3.4.5` retirement subsection updated — deprecation → removal; diagnostic upgraded from warning to hard error.
- `§10` grammar deprecation note → historical-record note.
- `PATTERNS.md` — Codable / serialization entry rewritten to reflect v0.11 landing (before was "deferred to v0.11+ stdlib layer"; now shows actual auto-conformance + code example + wire format summary).
- `README.md` — status line bumped to v0.11 with three-paragraph history (v0.11, v0.10.2, v0.10.1).
- `working-notes.md` — this note.

**Remaining post-v0.11 open items:**

- Interop/stdlib deferred (F1 conditional compilation, F3/F4 CF↔Foundation bridging, F5 direct C-function invocation).
- Implementation-planning items (runtime cost model, package registry, stdlib scope).
- **Validation exercise against v0.11 surface** — translate a new file (Ink or WebSocket) to exercise auto-conformance in real code; would surface anything the spec missed.

**Dissolution-pattern update.** Twelfth session. This one shipped substantial new content (§3.4.6 + §3.10.10 + §3.11.4 = ~350 lines of new spec) but the underlying mechanism inventory is unchanged — the mechanisms `As<T>`, records, field-layout metadata, prelude extensions, method shadowing rules were all already in place. What v0.11 added was the *rule* saying "value types get this automatic" plus the *wire format* specifying what "this" produces. Pattern holds even as the language matures — new capability, no new mechanism.

---

## Session note — Value-type auto-conformance direction + framing corrections (2026-08-10, addendum, no version bump)

Continuation of the post-v0.10.2 open-items sweep. User picked "Value-type auto-derivation of `.as() -> Bytes` — deferred v0.11+" from the remaining open list. Session surfaced two mechanism-invention slips that had been carrying forward in the spec and PATTERNS.md unnoticed.

**First slip — naming variant of the trap.**

Proposed a `derive:` section with entries named `as_bytes` / `as_json` — per-target-name pattern. User caught: "what do you mean by derive here? again, this is the wrong thing, i said that we wanted `as -> bytes` or `As<Bytes>` not `as_x`." The v0.10.0 commitment: one method name `.as()` differentiated by return type via trait-qualified dispatch. Derivation entries must be `As<Bytes>` (protocol-parameterized), never `as_bytes`. Same shape as the WrappingAs slip during Convertible retirement — inventing per-target names when the committed convention has one name.

**Second slip — "third-party formats need future stdlib support" was reaching for structure.**

Flagged that third-party format packages (JSON, msgpack) would be a v0.12+ story requiring new stdlib mechanism. User pushed: "whats difficult about defining or rewriting their own? or just returning whatever As<String> means?" Answer: nothing. Third-party format packages ship their own target type + user-writable `.as() -> ThatType:` conformances via ordinary `extend class Foo:` (§3.3.2). No new language support required. My "deferred story" flag was imagined-future-mechanism.

**Both slips have the same root.** Reaching for structure the committed mechanism already provides. Sibling protocols (Convertible retirement) → sibling method names (this session, first) → imagined-future-mechanism (this session, second). Three costumes, one trap.

**Framing corrections applied.**

- **Value-type auto-derivation is not "a stdlib layer, deferred to v0.11+."** It's **auto-conformance at the base-class level for value types**, planned for v0.11 as a language rule (same shape as automatic structural equality on records per §3.2). Compiler synthesizes `.as() -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` from field layout at composition time. `kind: regular` classes still require explicit user-written serialization.
- **Third-party format story does not exist as a deferred item.** Format packages use existing extension mechanism + `As<T>` protocol, exactly like user code. Not a v0.12+ blocker; not blocked at all.
- **Only `As<Bytes>` gets auto-conformance.** Because bytes are the universal substrate with one canonical encoding. `As<Json>` / `As<Csv>` / etc. have per-record author choices (field naming, null handling, precision) — no defensible default, silent choice would be a footgun. Same principle as compile-error-on-narrowing (v0.10.1 Convertible retirement).

**Files touched this addendum.**

- `~/.claude/memory/corvid-mechanism-invention-trap.md` — added the naming-variant slip (§second-slip) and the third-party-support non-story (§third-slip); refined "how to apply" with two new checks (rule 5: naming-variant; rule 6: third-party non-story); meta-observation that the trap surfaced three times in one day at user's push.
- `spec/spec-v0.10.md` line 151 (v0.10.0 changelog serialization substrate) — reframed from "future stdlib layer deferred to v0.11+" to "auto-conformance at base-class level for v0.11"; naming-variant note (`as_bytes` / `as_json` are slips) added.
- `spec/spec-v0.10.md` §3.10.8 line 2596 — same reframing; added explicit paragraph "Auto-conformance for value types (v0.11 planned)" explaining what the direction is + why only Bytes gets it.
- `PATTERNS.md` line 218 (Codable / JSONEncoder / auto-serialization entry) — same reframing.
- `working-notes.md` — this addendum.

**Not touched (intentional).** Historical open-items lists in prior session notes (lines 42, 91, 144, 211) that carried "deferred to v0.11+ as stdlib layer" — those are session-time-of-writing snapshots and rewriting them retroactively distorts the design conversation. Corrected framing lives in this addendum and in the current live spec/PATTERNS.md.

**No version bump.** Framing corrections in live docs + memory refinement + working-notes addendum only. The v0.11 auto-conformance direction is *planned* but not shipped; when it lands (§3.4.6 new + §3.10.10 canonical wire format spec), that gets its own version bump and full changelog.

**Remaining post-v0.10.2 open items (unchanged):**

- **v0.11 auto-conformance implementation** — direction agreed, spec sections not yet authored (§3.4.6 Auto-Conformance for Value Types; §3.10.10 Canonical Value-Type Encoding).
- **Double-colon header form removal** — carried to v0.11 (v0.10.2 grace period).
- Interop/stdlib deferred items (F1, F3–F5).
- Implementation-planning items (runtime cost model, package registry, stdlib scope).

**Meta-observation for future sessions.** Every one of the trap's three surfacings this day was caught by the user, not by me running the check proactively. The memory file now carries rules 5 + 6 to catch the naming-variant and non-story flavors before proposing. Standing check for every design proposal: does the committed convention already cover this? If yes, don't restate it as a new mechanism / a new naming pattern / a new deferred story.

---

## Session note — v0.10.2 Explicit conformance syntax; parens sugar; ObjC inheritance to body-form; double-colon retired (2026-08-10)

Fifth same-day session in the post-v0.10 audit chain (v0.10.0 landing → Convertible retirement → open-items verification → Pattern subsystem → explicit conformance). User picked "Explicit `conforms: Protocol` syntax?" from the remaining post-v0.10.1 open-items list.

**Framing.** §3.4 committed "explicit conformance is legal but not required" without specifying syntax. Two shapes had appeared in the spec — header-colon `class Foo: A, B:` (5 code uses, worked example §11) and body-form `conforms: A` (used in prelude classes Bytes/String). Grammar §10 reserved `conformance_clause?` but never defined the production. Also `class MyViewController: UIViewController:` used the same header shape for ObjC subclassing (§9.3.1) — one syntax, two semantics resolved by compiler kind-check.

**Complication surfaced during migration audit.** The header-colon form was overloaded — inheritance AND conformance with the same shape. Retiring it required a decision on where inheritance goes.

**Design choice (user's, "parens sugar for class Foo(A,B)" over my initial recommendation of keeping the double-colon form).** The user pushed to retire the double-colon shape entirely in favor of parens. My follow-on analysis surfaced two viable placements for ObjC inheritance under parens:

- **Option (b):** parens holds both — first entry is base class if `kind: regular`, rest are protocols. Compiler infers roles.
- **Option (a):** parens for protocols only; ObjC inheritance moves to body-form `metadata: extends:`. Treats the exception as the exception.

**Recommended and accepted: option (a).** Rationale: pure Corvid commits to composition-over-inheritance (§1.5, §3.4); class inheritance exists only for ObjC interop. Putting the ergonomic sugar on the common case (protocol conformance) and moving inheritance to body-form makes the design commitment visible at every subclass declaration — writing `metadata: extends: UIViewController` in the body feels like the exception it is.

**Decisions in this session:**

- **§3.4.5 new (Explicit Conformance Declarations).** Both header parens sugar `class Foo(A, B):` and body-form `conforms: A` are legal and equivalent (desugar to the same conformance-set entry). Compile-time validation at declaration site is the primary payoff over structural conformance — fail at the declaration, not at the first use. Additive across extensions.
- **§10 grammar — `conformance_clause` production defined.** `conformance_clause := '(' protocol_type (',' protocol_type)* ')'`. Header ordering: `class NAME <generics> (conformances) where <constraints> :` — reads left-to-right.
- **ObjC class inheritance moves to body-form `metadata: extends:`.** Rules: one class, must be `kind: regular` + ObjC-imported, not modifiable by extensions. Pure Corvid classes cannot be subclassed.
- **Double-colon header form deprecated in v0.10.2, removed in v0.11.** Parse-time warning with fix-it suggestion. One-minor-version grace period; migration is mechanical.
- **§4.18 metadata alignment.** No schema change — existing `superclass:` and `conforms_to:` keys just get formal population semantics.

**Mechanism-invention trap check.** Passes. `conformance_clause` grammar production is a formal definition of a previously-reserved-but-undefined slot. `metadata: extends:` uses the existing metadata mechanism (§4.18) with a new key. No new mechanism invented. The retirement of the double-colon form eliminates a dual-purpose shape (mistake worth undoing) rather than adding one.

**Files touched this session:**

- `spec/spec-v0.10.md` — preamble bump to v0.10.2 + full changelog block; §3.4.5 new (~150 lines); §10 grammar `conformance_clause` production defined + header ordering reordered (`conformance_clause?` moved before `where_clause?`); §9.3.1 example rewritten for body-form; §3.4.4 line 1487 prose reference updated; §9 line 3943 prose reference updated; 4 code examples migrated to parens form; 1 code example migrated to body-form.
- `validation/websocket/01-Framer.parity.cor` — line 164 migrated to parens.
- `PATTERNS.md` — new "I need `class Foo extends Bar` / inheritance" entry; interfaces entry updated to note §3.4.5; Pattern extensibility entry updated to reflect explicit-conformance path.
- `README.md` — status line bumped to v0.10.2.
- `working-notes.md` — this note.

**Open items still standing** (v0.10.1 open list minus explicit conformance):

- **Value-type auto-derivation of `.as() -> Bytes`** — deferred to v0.11+ as stdlib layer.
- **Deprecation removal for double-colon header form** — carried to v0.11.
- Interop/stdlib deferred items (F1, F3–F5).
- Implementation-planning items (runtime cost model, package registry, stdlib scope).

**Dissolution-pattern update.** Eleventh session, but this one is different in shape — it formalized something previously left as "resolved in principle, undefined in detail" (Ink #13). The audit found an overloaded shape (double-colon = conformance OR inheritance), and the resolution split the shapes to match Corvid's design commitment. Not a dissolution — a formalization + a design-commitment surfacing. Adjacent pattern.

---

## Session note — v0.10.1 `Pattern` subsystem lands; raw-content prefix aliases (2026-08-10)

Follow-on to v0.10.0 and the Convertible retirement. Same session — user picked "Pattern class?" as the next item from the post-v0.10.0 open-items list.

**Framing.** §3.10.5 (String) forward-referenced `substring(matching: Pattern)`, `.split(pattern:)`, `.replace(pattern:, with:)` but never defined `Pattern`. Open item: what is Pattern, structurally?

**Design commitments (data-first).**

- **`Pattern` is a protocol, three concrete conformers ship in prelude.** `Literal` / `Glob` / `Regex`. Users add more (`Peg`, `Fuzzy`, `Levenshtein`) via `extend` + structural conformance.
- **Match record carries source backreference + range + named-captures map.** `Match.source` is the String; `Match.range: MatchRange` is a start/end StringPosition pair; `Match.captures: Map<String, MatchGroup>` is keyed by capture name.
- **Named captures only.** Unnamed group `(...)` in Regex source is a compile error. Users write `(?<name>...)` always. Consistent with §3.2 named-only records, §3.7.1 named-only enum associated values, §3.10.2 named-only multi-value carriers. Positional capture access would require positional Match accessors — Corvid doesn't offer positional access anywhere.
- **Prelude literal syntax via `regex"..."` / `glob"..."` / `literal"..."`** — new §4.16.17 raw-content prefix-literal alias shape. Backslashes and other characters pass through unmodified so `regex"^\d+"` reaches the constructor as `^\d+`, not as an escape-sequence failure. Cooked semantics remain the default for prefix-literal aliases; `raw` is opt-in.
- **Compilation caching via §4.16.10 value-alias discipline.** Compile-time-constant pattern source → compile once, share across use sites. No `Regex.compile(...)` boilerplate.

**Mechanism-invention trap check applied.** Every element of the Pattern subsystem uses an existing mechanism:

- Pattern protocol → §3.4 structural conformance (no `conforms: Pattern` declaration needed).
- Match/MatchRange/MatchGroup records → §3.2 named-only.
- Concrete Regex/Glob/Literal classes → §1.5 class-as-universal.
- Pattern literals → §4.16 alias mechanism (with §4.16.17 additive shape variant for raw-content).
- User-defined pattern literals → §4.16.16 `@`-marker discipline.

The ONE new thing is §4.16.17's `raw` flag on prefix-literal aliases. That's genuinely additive — a new shape variant within the existing §4.16 alias mechanism, not a new mechanism. Justified by regex source needing raw content; generalizes to any pattern language with its own escape rules (PEG, path globs, format strings).

**Not the same trap as WrappingAs/SaturatingAs.** Pattern's three concrete conformers (Literal/Glob/Regex) differ in structural behavior — different match algorithms, different compilation, different metacharacters. This is ordinary polymorphism where the differentiator is a real structural distinction. The trap would be if they were three sibling protocols differing only in method name; they're one protocol with three concrete implementations.

**Two design commitments called out to user for signoff (both accepted):**

1. Named captures only (option A over "generated `_1` `_2` names for unnamed").
2. Raw content for pattern literals via new §4.16.17 shape variant (option A over "double-backslash every regex").

**Files touched this session:**

- `spec/spec-v0.10.md` — preamble bump to v0.10.1 + Pattern changelog block; §3.10.5 (String) surface widened (`split(pattern: Pattern)`, `first_position(matching:)`, `all_matches(matching:)`, `replace_all(pattern:, with:)`); §3.10.9 new (Pattern protocol + Match records + Literal/Glob/Regex + RegexOptions + prelude literals + named-captures rule); §4.16.17 new (raw-content prefix-literal aliases).
- `PATTERNS.md` — new entry "I need NSRegularExpression / Regex / re.compile" with regex/glob/literal usage, named-captures rationale, extensibility example.
- `README.md` — status line bumped to v0.10.1.
- `working-notes.md` — this note.

**Open items still standing** (v0.10.0 open list minus Convertible and Pattern):

- **Value-type auto-derivation of `.as() -> Bytes`** — deferred to v0.11+ as stdlib layer.
- **Explicit `conforms: Protocol` syntax** — low priority.
- Interop/stdlib deferred items (F1, F3–F5).
- Implementation-planning items (runtime cost model, package registry, stdlib scope).

**Dissolution-pattern update.** Tenth dissolution-adjacent session. This one was mostly additive (Pattern is a real subsystem needing real spec), but the design still landed by using existing mechanisms rather than inventing new ones — §3.4 for structural conformance, §3.2 for named-only records, §4.16 for the literal syntax. The one addition (§4.16.17 raw flag) is a shape variant within an existing mechanism, not a new mechanism. Consistent with the pattern's spirit even when new stdlib genuinely has to ship.

---

## Session note — v0.10.0.1 `Convertible<To>` retired; narrowing methods; mechanism-invention trap surfaced (2026-08-10)

Small addendum session immediately after v0.10.0 landing. Started as verification pass on the post-v0.10.0 open-items list; discovered the "`Convertible` vs `As<T>` consolidation" item was actually two problems bundled:

1. **A pre-existing v0.8.5 spec inconsistency.** `Convertible<To>.checked_into` returned `Result<T, OverflowError>`, but v0.8.5 retired `Result` from the prelude in favor of domain enums. The bug had lived in §3.11.1 unnoticed since v0.8.5 shipped.
2. **Structural duplication with `As<T>`.** Both were parameterized-generic conversion protocols with trait-qualified dispatch on target type. Keeping both meant two shapes for structurally one thing.

**The mistake that surfaced the meta-lesson.**

My first proposal: retire `Convertible<To>`, replace with **three sibling protocols** — `WrappingAs<T>` / `SaturatingAs<T>` / `CheckedAs<T>` — each with a distinct method name (`.wrapping_as`, `.saturating_as`, `.checked_as`). Framed as "consolidation into the As-family."

User caught it: *"i thought we are going with polymorphic `as -> Bytes` etc? or just `as<Bytes>`?"* — pointing out that inventing three sibling protocol names betrayed the mechanism `As<T>` was defined by.

The check that would have caught it independently: **for narrowing `Int32 → UInt8`, all three semantics produce the same T (UInt8). Trait-qualified dispatch differentiates by T. Therefore trait-qualified dispatch cannot distinguish the three semantics.** The moment I noticed this, the correct move was: the differentiator is a naming question, not a protocol question. Ship three methods on `Int32`; don't invent three protocols.

**Why this slip is a distinct pattern from "add feature X" traps.**

Standard dissolution pattern: "add feature X" → "existing Y covers it." This slip: "dissolve X into Y" → but the dissolution invented Y-alike sibling structure alongside Y. Worse than an addition — an addition at least admits it's an addition. Sibling-protocol invention looks like consolidation but is mechanism-duplication.

**The corrected shape:**

- **`As<T>` remains the sole conversion protocol.** Prelude ships conformance for widening numeric pairs only (lossless).
- **Narrowing is a compile error on `As<T>`.** `Int32(300).as() -> UInt8` diagnostic points at three narrowing methods.
- **Three ordinary methods on numeric primitives**, not a protocol family: `wrapping_to::<T>()`, `saturating_to::<T>()`, `checked_to::<T>() -> ConvertOutcome<T>`.
- **`ConvertOutcome<T>` enum** replaces `Result<T, OverflowError>` per v0.8.5.
- **§3.9 arithmetic `checked_add`/etc.** also fixed — same debt, same reuse of `ConvertOutcome<T>`.

**Design principle written down for future sessions.**

*"When multiple candidate methods share a return type but differ in semantics, the semantics is not a type-parameter question and does not need a protocol family. Per §1.2 (author intent, not authority), the choice belongs at the call site as a method name."*

Saved as `~/.claude/memory/corvid-mechanism-invention-trap.md` (auto-memory, cross-session persistent) so future work catches this before repeating it.

**Files touched this addendum:**

- `spec/spec-v0.10.md` — §3.11.1 rewritten (lossless As<T>-only); §3.11.2 new (narrowing methods); §3.11.3 new (ConvertOutcome<T>); §3.11.4 new (retirement note + migration table); §3.11.5 renumbered from prior §3.11.2 (Bounded); §3.10.3 "Relationship to Convertible" updated (was "coexist for v0.10.0," now points at §3.11.4 retirement); §3.9 integer-overflow paragraph updated (`Result<T, OverflowError>` → `ConvertOutcome<T>`); §2.7 keyword-table Result row marked retired; v0.10.0 changelog gains Convertible-retirement subsection.
- `PATTERNS.md` — As/From entry gains narrowing-story addendum with the design invariant.
- `~/.claude/memory/corvid-mechanism-invention-trap.md` — new memory file capturing the meta-lesson.
- `~/.claude/memory/MEMORY.md` — index entry.
- `working-notes.md` — this addendum.

**Open items still standing** (v0.10.0 open list minus this):

- **`Pattern` class** — deferred future spec work.
- **Value-type auto-derivation of `.as() -> Bytes`** — deferred to v0.11+ as stdlib layer.
- **Explicit `conforms: Protocol` syntax** — low priority.
- Interop/stdlib deferred items (F1, F3–F5).
- Implementation-planning items.

The `Convertible` vs `As<T>` item is now fully closed.

---

## Session note — v0.10.0 Byte substrate; As<T>/From<T> conversion; grapheme-native String; extension conflict warn-not-error (2026-08-10)

Long session. Three interlocking landings. The path getting here was more instructive than the endpoints — the user re-framed the problem three times and each re-frame consolidated the design one level up.

**Thread 1 — Extension composition: rule 4 revised warn-instead-of-error.**

Started as a review question ("v0.9.1 §3.3.2 <- what was this about initially? seems backwards"). v0.9.1 rule 4 said two extensions declaring same-name methods → compile error, `replace` required to shadow. But every other author-intent situation in the spec — §7.7 visibility, §3.5 effect annotations, retracted `sealed`/`private`/`final` — says warn, don't block. Rule 4 was the only exception.

User's follow-on: "wait, extend doesnt override then? why shouldnt it override? maybe we just need a dev warning?" This is the general shape of the fix — same-name across extensions becomes a warning + deterministic resolution (current-module > imported > original), `replace` becomes optional signaling intent. Escalate to error via `-Werror=shadowing-extension` for CI-strict projects. Same-name self-conflict *within one class body* stays hard-error (author self-conflict is a different category).

The consistency-through-time principle: when rule N is out of line with the other rules the language already commits to, that's the signal to fix rule N, not to defend rule N.

**Thread 2 — Stdlib/interop gaps → substrate-level rethink.**

Started as "what can't we inherit or bridge from objc". Focused on String indexing semantics; user reframed with OOP + GoF: "what gof design pattern does this problem meet?" — Iterator/Bridge/Strategy/Facade candidates surfaced. Then user pushed one level higher: "everything is made of bytes, right? so every atom is technically a byte sequence, except that we dont generally allow folks to edit them?"

That reframe changed the design. Instead of "add a StringPosition API" and "add byte-slice methods" and "add Base64" and "add random" separately, ask: what universal substrate makes all of these fall out?

Answer: a mutable `Bytes` regular class + `String` as grapheme-native regular class + `.as() -> Bytes` as the universal convention. Then:
- StringPosition dissolves into an opaque token bound to a source String, with all navigation via `s.position(...)` methods.
- Byte access dissolves into universal `.as() -> Bytes` on any type conforming to `As<Bytes>`. No peer views on String (`.utf16`, `.utf8`, `.bytes` are not properties).
- Base64/random/etc. dissolve into `extend class Bytes:` and `extend class UInt8:` — the extension mechanism from v0.9.0 handles them without language changes.

**Thread 3 — Naming convention → protocol design.**

User noticed "there's a common pattern with Bytes and AsBytes as X, asX ?" and pushed toward `As<T>` and `From<T>` as *parameterized* protocols rather than method-name conventions. My initial resistance ("Rust normally routes this through conformance declaration `impl As for Foo`, not through method-name matching") was wrong — user pushed again: "no i want to do this: class String: methods: function as(self) -> Bytes:  without needing to conform to a protocol". Corvid's design has structural conformance built in since v0.6; the class with `function as(self) -> Bytes` structurally conforms to `As<Bytes>` automatically. No boilerplate.

Then: trait-qualified dispatch. User's key move: "i mean, if we specify the output type, cant this be matched to the right type? like as->bytes vs as->json". Yes — the return type is a *type parameter of the conformed protocol*, not an overload dimension. A class conforming to both `As<Bytes>` and `As<Json>` may declare both `function as(self) -> Bytes` and `function as(self) -> Json`; dispatch at the call site is by return-type context, turbofish, or trait qualification. This dodges the "no overloading" rule cleanly because same-name-different-return isn't overloading in the parameter-types sense — it's protocol-instantiation dispatch.

**Decisions in this session:**

- `As<T>` / `From<T>` protocols (§3.10.3). Trait-qualified dispatch (§3.4.4). No auto-derivation between the two directions.
- `Bytes` class (§3.10.4) — mutable, regular, NSData-bridged. No separate builder type. Array-literal conformance.
- `String` class (§3.10.5) — grapheme-native, no int indexing, no peer views, `.as() -> Bytes` for byte access.
- `Char` class (§3.10.6) — record, grapheme cluster.
- `StringPosition` class (§3.10.7) — opaque token, source-bound, no arithmetic.
- `Encoding` / `ByteOrder` / `DecodeOutcome<T>` enums (§3.10.8).
- §3.3.2 rule 1 revised to warn + deterministic resolution; rule 5 adds trait-qualified exemption for generic-protocol-derived same-name.

**Serialization by convention, not by reflection.** Any type conforming to `As<Bytes>` participates in the universal serialization substrate. Format-specific overlays (`As<Json>`, `As<MsgPack>`, `As<Yaml>`) become trait-qualified conformances added by users or third-party packages via `extend class Foo:`. No `Codable`-style reflection auto-magic — the Java `Serializable` trap doesn't compound. Value types (records, enums, primitives) may auto-derive via §4.18 metadata as a future stdlib layer (deferred).

**Why v0.10.0 and not v0.9.2.** Major-version-adjacent bump because the string/bytes/serialization decisions land the language's substrate for text and binary handling — foundational choices with ecosystem-wide effect on future stdlib design and third-party packages. Rule 4 revision, though smaller, ships alongside because it's part of the same "consistency pass" and touches semantics rather than fill-in.

**Files touched this session:**

- `spec/spec-v0.9.md` (renamed to `spec-v0.10.md`) — preamble bump; §3.3.2 rule 1 revised to warn + deterministic resolution, rule 4 adds trait-qualified exemption; §3.4.4 adds trait-qualified dispatch note; new §3.10.3 (As/From), §3.10.4 (Bytes), §3.10.5 (String), §3.10.6 (Char), §3.10.7 (StringPosition), §3.10.8 (Encoding/ByteOrder/DecodeOutcome).
- `PATTERNS.md` — extension entry revised for warn-not-error; new entries "I need String.Index", "I need .utf8/.utf16/data.count", "I need Data/NSMutableData/[UInt8]", "I need Codable/JSONEncoder"; Rust `From<T>`/`Into<T>` entry added.
- `validation/00-friction-log.md` — #25 String.Index / position API marked dissolved v0.10.0.
- `validation/websocket/00-friction-log.md` — #W-30 position part, #W-14, #W-53, #W-52, #W-61, `F8`, #W-37c, #W-37e all marked dissolved; cross-exercise table adds five new rows.
- `README.md` — status line bumped to v0.10.0; spec filename updated; String position API removed from Undecided list.
- `working-notes.md` — this note.

**Open items post-v0.10.0** (verified against friction logs and spec; entries marked "still open" here have been confirmed not already dissolved):

*Language design — deferred future spec work:*

- **`Pattern` class** — referenced from §3.10.5 (`substring(matching: Pattern)`, `.split(pattern:)`, `.replace(pattern:, with:)`) but the class itself is deferred. Scope: glob, regex, structural patterns. Needed before any serious text-processing stdlib.
- **`Convertible` vs `As<T>` consolidation** — §3.11.1 predates §3.10.3; overlap in numeric conversions (both offer projection from source type to target type). Future refactor may unify or explicitly document why both stay (e.g., `Convertible` retains the three-semantics narrowing split, `As<T>` is the general form).
- **Value-type auto-derivation of `.as() -> Bytes`** — records/enums/primitives could auto-synthesize via §4.18 metadata since their layout is closed and predictable. Deferred to v0.11+ as a stdlib layer, not a spec change.
- **Explicit `conforms: Protocol` syntax** — structural conformance covers 95% of cases (Ink #13 body-resolved), but the explicit declaration form is under-specified. Rare in practice; low priority.

*Interop / stdlib — deferred implementation choices, no language design change needed:*

- **Conditional compilation** — `F1`. Swift's `#if os(watchOS)`. Multi-target build-system concern.
- **CF↔Foundation bridging** — `F3`, `F4`. `as CFString`, `.takeRetainedValue()` and CF memory management. CoreFoundation-adjacent; not idiomatic Corvid.
- **Direct C-function invocation** — `F5`. `import c "..."` full workflow beyond the reference-card sketch. Needs §9 expansion once C interop matters.

*Implementation-planning items — meaningful only once implementation starts:*

- Runtime cost model for state-machine synchronization under load.
- Package registry protocol.
- Stdlib scope decisions (what ships with the compiler vs. separate packages).

Everything from prior "open items" lists that was actually already resolved has been re-verified and marked in the friction logs this session: `try` semantics (dissolved v0.8.5), String position API (dissolved v0.10.0), enum raw values (resolved v0.6.0), byte access / slice (dissolved v0.10.0), character-literal-to-byte (dissolved v0.10.0), string interpolation (resolved v0.8.0), `guard let` / `if let` (resolved v0.7/v0.8.1), optional chaining (retired v0.7), tuples / destructuring (dissolved v0.8.3), nested types (dissolved v0.8.4), definite assignment (dissolved v0.8.7), `old()` (dissolved v0.8.7), throws-in-ensures (dissolved v0.8.5), argument labels (resolved v0.5), extension mechanism (resolved v0.9.0), predicate patterns (dissolved v0.8.6), type aliases (resolved v0.6+/v0.8.2), bit-level ops (resolved v0.7.1), inout arrays (dissolved v0.8.7), namespace (resolved by existing mechanisms), generic constraints on categories (dissolved v0.9.0), MemoryLayout (resolved v0.7.1), range literals (resolved v0.8.0/v0.8.3).

**Dissolution-pattern update.** Ninth dissolution session. The re-framing chain here (stdlib gaps → GoF patterns → universal substrate → naming convention → trait-qualified dispatch) is the fullest example so far of the "one level up" move. Each step made the previous alternative look smaller. What could have been ten stdlib additions and a language feature became four types + two protocols, all falling out of existing structural commitments (§1.5 class-as-universal, §3.4 structural conformance, §3.3.2 extensions).

---

## Session note — v0.9.1 Extension conflict-resolution behavior spec; namespace friction closed (2026-08-08)

Housekeeping-plus session. Two threads:

**Thread 1 — Namespace friction (websocket #4) closed.** User pushed back on why "struct-as-namespace vs. module-level namespace construct" was still marked open, noting that record + `class:` section should cover it. Correct. The friction entry bundled two questions: (a) struct-as-namespace pattern (Swift's `enum Foo { static let PI = ... }`), resolved via `class:` section on records (§4.10.7); (b) module-level namespace construct (C++/C# `namespace Foo { ... }` block), not needed — one module = one namespace, and within-module sub-grouping uses (a). Marked resolved by existing mechanisms.

**Thread 2 — v0.9.1 fill-in for extension conflict resolution.** v0.9.0 shipped the top-line rule (same-name collision is compile error unless `replace`) but left seven behavioral questions implicit. This session fills them in without changing the design.

**Decisions in this session (rules 1–7 in §3.3.2):**

1. **Same-name collision without `replace` is a compile error at the composing site** — the compilation unit that has both extensions visible. This is where the conflict surfaces, even if that unit didn't write either extension.
2. **`replace` requires a shadowing target** — if there's no method with that name in the original definition or another visible extension, `replace-without-target` diagnostic. Prevents `replace` from being a copy-paste no-op.
3. **At most one `replace` per method per composing site** — if two extensions each `replace` the same method, both claim precedence, neither has grounds. Compile error naming both.
4. **Name matching ignores signatures** — Corvid has no overloading; two same-name methods always conflict regardless of parameter types.
5. **Extensions shadow inherited protocol defaults without `replace`** — extension is providing the class's own definition; per §3.4.4 priority (own > extension > inherited default), it wins over the default without ceremony. `replace` is reserved for shadowing explicit prior declarations. Edge case: second extension over first extension still needs `replace`.
6. **Runtime method table is unified after composition** — after compile-time conflict resolution, one entry per selector; dispatch is unchanged from any other class per §5.1.
7. **Load-time transitive visibility** — extensions apply globally at load time (ObjC category semantics); compile-time source visibility (§7.7) constrains what a module can *write against*, but any loaded module's extensions are runtime-visible on class instances. The consequence: if A imports both B and D, both of which independently extend Foo with `foo` without knowing about each other, the conflict surfaces at A. A resolves by dropping an import, writing its own `replace`, or upcasting per protocol.

Also added an illustrative diagnostic format (`error[E0447]: duplicate method \`foo\` on class \`Bar\`` with source location + suggested fix) so implementers have a target and users have a preview, and a "what is not a conflict" list clarifying that different methods, methods on different classes, and additive invariants all compose freely.

§3.4.4 (Default Method Inheritance) got a cross-reference update pointing at §3.3.2 rule 5 for the shadowing-inherited-default case.

**Why v0.9.1 and not v0.9.0 amendment.**

Behavior spec that fills in specifics for a landed rule. No design change; no retraction; no new construct. Sits at the same level as previous ".x" bumps (v0.8.1 filling in Session-3 gaps, v0.8.7 clarifying let-expression form). The top-line rule from v0.9.0 stands; v0.9.1 makes it implementable.

**Files touched this session:**

- `spec/spec-v0.9.md` — preamble bump to v0.9.1 + changelog block; §3.3.2 Conflict Resolution subsection (rules 1–7 + diagnostic example + not-a-conflict list); §3.4.4 cross-reference update to §3.3.2 rule 5.
- `validation/websocket/00-friction-log.md` — #4 (namespace) marked resolved by existing mechanisms.
- `README.md` — status line bumped to v0.9.1.
- `working-notes.md` — this note.

**Open items post-v0.9.1:**

- **String position API in stdlib** — Ink #25 / websocket #W-30. Stdlib scope, not language spec. Blocks parser translation work.
- **Interop/stdlib gaps** — byte-array raw access (`#W-53`), byte slice methods (`#W-52`, `#W-61`, `F8`), Foundation-primitive interop details (`#W-14`, `F6`), random-number API (`#W-37c`), base64 (`#W-37e`), CF↔Foundation bridging (`F3`, `F4`), conditional compilation (`F1`). All real, none touching language design.
- **Implementation-planning items** — runtime cost model for state-machine synchronization, package registry protocol, stdlib scope. Only meaningful once implementation starts.
- **Validation-driven exploration** — translate more Ink files, extend idiomatic pass to more Fragment types. Would surface new friction if the design isn't ready for it.

---

## Session note — v0.9.0 Unified class extension; Category retracted; default-method inheritance (2026-08-08)

Major-version bump. Not a "dissolves into existing mechanism" session in the strict sense — this is a **retraction and unification**. Started as v0.8.8 (small fix for Ink #W-69/#W-70: add generic constraints to `category(...)`). User pushed twice, each push moving the frame up a level.

**First push — "why can't guard/invariants cover this?"** — reframed the type-constraint question. Type-parameter conformance is a compile-time-checkable claim about the receiver, same as any `requires` clause; extending the requires machinery to accept `conforms_to` predicates dissolves the "add where clauses to categories" problem into the existing contract system. That was going to be the v0.8.8 draft: parameterized `target_class`, `conforms_to` predicate, section-level `requires:`.

**Second push — "perhaps the larger problem here is how do we monkey patch classes?"** — reframed everything. #W-69/#W-70 is one dimension of a bigger under-specified area. Categories were Corvid importing ObjC's vocabulary for what is structurally just "declaring more members on this class." That framing paid section-class ceremony for a job that fits at the class declaration level.

**Third push — "we can just extend some class by saying x modifier to `class NSString:`"** — nailed the shape. `extend class Foo:` at declaration position. One vocabulary, one class-body syntax for both definition and extension, generic-parameter machinery reused where it already lives.

The v0.8.8 draft was retracted mid-planning; v0.9.0 took its place.

**Decisions in this session:**

- **§3.3.2 new (Extension Declarations).** `extend class Foo:` is a declaration-position modifier. Body follows normal class-body rules. Generic parameters and where-clauses at declaration position. Extensions across modules stack. State fields still cannot be added (memory layout is fixed).
- **§3.4.4 new (Default Method Inheritance for Structural Conformers).** Reading A committed: structural conformers inherit protocol default methods for messages they don't define themselves. Diamond ambiguity resolved via compile-time detection + explicit disambiguation (define the method or upcast).
- **§4.10.4.3 (Category) retracted.** Section class removed; cross-reference points at §3.3.2. Migration is mechanical.
- **Conflict rules committed.** Same-named method across extensions is a compile error; opt-in `replace` modifier for explicit shadowing. Consistent with §1.2 (author intent, not authority) — no ObjC-style link-order-dependent silent resolution.
- **§5.1 note.** Method-table population is uniform across definition, extension, and inherited defaults.
- **§7.7 note.** Extension visibility follows module imports.
- **§10 grammar.** `class_decl` extended with `extend?` prefix; `where_clause` production added; `method_decl` extended with `replace?` prefix.
- **Ink #W-69, #W-70 dissolved by unification.**

**What this session confirms about the design philosophy.**

The "dissolves into existing mechanisms" pattern was extended one level up: not just "dissolve a would-be construct," but "**retract an existing construct** when the structural commitments (class-as-universal, structural conformance, section discipline) make it redundant." Category was inherited from ObjC's vocabulary; Corvid's class-body syntax covers its job; the retraction is the same shape as retiring `throws` in v0.8.5 or retiring `coordination_scope` before it shipped in v0.8.6. The difference: Category had actually landed in a prior spec version, so retracting it is a real breaking change for anyone who had internalized the v0.8.x shape. Migration is mechanical; the tradeoff is worth it.

The default-method inheritance decision (§3.4.4) is worth naming separately. Corvid has committed to structural conformance since v0.6+, but the spec left the question of "do conformers inherit defaults" ambiguous. Reading A (yes) makes structural conformance load-bearing at runtime — you don't just get "type-check passes"; you get the protocol's affordances. Reading B (no, only via upcast) would have made structural conformance a purely static-checking device, effectively toothless. Reading A carries the diamond problem; Corvid solves it with compile-time detection + explicit disambiguation rather than any implicit-resolution rule.

**Eight-session pattern table:**

| Version | Dissolved / Retracted | Replacement |
|---|---|---|
| v0.8.3 | `for` keyword, tuples, destructuring | prelude alias over `.for_each`; named-field records |
| v0.8.4 | nested type declarations | flat naming + leading-dot |
| v0.8.5 | `throws`, `try/catch/finally`, prelude `Result<T, E>` | nil-punning; domain enums; switch |
| v0.8.6 | `DispatchQueue`, `@main_actor`, would-be `coordination_scope` | contracts (§1.9); `execution:` metadata; class boundary IS coordination boundary |
| v0.8.7 | would-be `old(expr)` in ensures | `self`/params = entry-state, `result` = post-state (§3.4.3) |
| v0.9.0 | `category(target_class: X):` section class; ambiguous conformer-inheritance semantics | `extend class Foo:` at declaration position (§3.3.2); structural conformers inherit defaults (§3.4.4) |

**Files touched this session:**

- `spec/spec-v0.9.md` (renamed from `spec-v0.8.md` during this session's housekeeping pass) — preamble bump to v0.9.0 + big changelog block; §3.3.2 new; §3.4.4 new; §4.10.4.3 retracted (redirect to §3.3.2); §5.1 method-table note; §7.7 extension visibility note; §10 grammar (`class_decl` extend prefix, `where_clause`, `method_decl` replace prefix); two stale-reference fixups (§9 line 6104, §14 line 6721).
- `validation/websocket/00-friction-log.md` — §2.6 rewritten; #W-69/#W-70/marker-protocols marked dissolved/resolved; summary list item 5 updated.
- `PATTERNS.md` — Rust `impl<T: Trait> for Foo<T>` entry added; `traits with where clauses` entry rewritten; new Swift/ObjC extension entry (`I need extension Foo { ... }`).
- `README.md` — status line bumped to v0.9.0.
- `working-notes.md` — this note.

**Open items post-v0.9.0:**

- Predicate-based pattern matching sugar beyond `case x if pred(x):` — deferred (guard patterns cover the semantic need; Swift's `case \.foo:` syntax is a keypath projection that has no direct equivalent by design; friction #19 marked dissolved).
- ~~**File rename** `spec-v0.8.md` → `spec-v0.9.md`.~~ **Completed** — housekeeping pass folded into this session; all cross-references (README, PATTERNS, working-notes itself) updated to the new filename.
- **Example sweep across spec** — some downstream examples in §4/§5 that used `category(...)` were retired at the source-of-truth (§4.10.4.3); a full grep for any remaining stale `category(...)` in prose examples should confirm zero remain. Deferred to next session.

---

## Session note — v0.8.7 `old(expr)` dissolved; pre/post bindings in ensures (2026-08-08)

Seventh "dissolves into existing mechanisms" session. Started as a straightforward "add `old(expr)` to the contract system" proposal for Ink friction #28 — state-transition postconditions like `ensures self.current_index > old(self.current_index)` need pre-state reference; Dafny/JML/Eiffel all have some flavor of this.

**The pivot.** User asked "explain why isnt this just recursion." That reframed the problem. In a pure OOP/message-passing model where methods are transformations `Self → Self`, the pre-state IS the input (the receiver-as-argument); the post-state IS the output (the returned Self). Postconditions naturally reference both because both are already in scope. `old()` is a wart specific to mutation-heavy imperative programming where the pre-state isn't captured anywhere.

The dissolution: expose the entry-state snapshot the contract runtime already had to capture (to make any ensures checkable) as the natural identifier — `self` and parameter names inside ensures scope. Post-state gets a new binding: `result` (return value for value-returning methods; receiver-at-exit for void methods).

**Why `result` over alternatives.**

- `result` — familiar from Eiffel/Dafny for return values; extends cleanly to "resulting self" for void methods; single word.
- `self'` — prime notation from Alloy/Z; reads as "self-after"; punctuation-heavy.
- `after` / `post` — English-y; reads well but adds a keyword.

Picked `result` because it matches the method-as-transformation reading exactly — the post-state IS the result of the transformation. For value-returning methods it IS the return value already. For void methods it's the "resulting self" — the receiver considered as the output of the transformation.

**Decisions in this session:**

- **§3.4.3 new (Pre-state and Post-state Bindings in Ensures Clauses).** Formalizes the binding rule: inside ensures scope, `self`/params → entry-state; `result` → post-state. No `old(...)` form.
- **Ledger example (§8.4.1) rewritten.** `ensures accounts[from] == old(accounts[from]) - amount` → `ensures result.accounts[from] == self.accounts[from] - amount`.
- **§8.4.1 note updated.** Removed the `old(...)` reference; classification is inferred from body assignments as always.
- **§10 grammar note added.** No new production; a note near `method_decl` documents the binding rule.
- **Reader.inferred.cor `advance_index` rewritten.** `ensures result.current_index > self.current_index`. #28 marked resolved by dissolution.
- **`PATTERNS.md` extended.** New entry mapping "coming from Dafny/JML/Eiffel/Ada/Spec#/Alloy — I need `old(expr)`" to the pre/post binding rule.

**Reference-type semantics settled.** `self` in ensures scope captures the receiver's field values at entry using the same value/handle rules as any ordinary read — no separate "deep snapshot" semantics needed. To detect mutations on a referenced object, compare specific fields (`result.doc.title != self.doc.title`), not identity. This falls out of the ordinary read model; no new specification needed.

**What this session confirms about the design philosophy.**

The user's "why isn't this just recursion" was the trigger for the dissolution — same shape as previous sessions where a proposed mechanism collapsed once the user pointed at an existing primitive that already covered it. The recognition pattern is stable: **if the runtime already has to do something to make the feature checkable, expose the existing machinery via natural naming rather than a special-form wrapper.**

Contract system machinery (snapshot on entry, evaluate on exit) was already load-bearing for any postcondition on a mutable receiver. `old()` was routing that snapshot through a specially-named wrapper; the pre/post binding rule exposes it under `self`/params directly. Zero new capability. One less construct.

**Seven-session pattern table** (extending v0.8.6):

| Version | Dissolved | Replacement |
|---|---|---|
| v0.8.3 | `for` keyword, tuples, destructuring | prelude alias over `.for_each`; named-field records |
| v0.8.4 | nested type declarations | flat naming + leading-dot |
| v0.8.5 | `throws`, `try/catch/finally`, prelude `Result<T, E>` | nil-punning; domain enums; switch |
| v0.8.6 | `DispatchQueue`, `@main_actor`, would-be `coordination_scope` | contracts (§1.9); `execution:` metadata; class boundary IS coordination boundary |
| v0.8.7 | would-be `old(expr)` in ensures | `self`/params = entry-state, `result` = post-state (§3.4.3) |

**Files touched this session:**

- `spec/spec-v0.9.md` (was `spec-v0.8.md` at edit time; renamed in v0.9.0 housekeeping) — preamble version bump + changelog block; §3.4.3 new; §1.9 line 514 note update; §8.4.1 Ledger example rewrite; §10 grammar note near `method_decl`.
- `validation/03-Reader.inferred.cor` — VERSION NOTE (v0.8.7) added; `advance_index` postcondition rewritten; `read` postcondition rewritten; friction comment marked resolved.
- `validation/00-friction-log.md` — #28 marked dissolved.
- `PATTERNS.md` — new entry for Dafny/JML/Eiffel/etc.
- `README.md` — status line bumped; `old()` removed from undecided list.
- `working-notes.md` — this note.

**Open items post-v0.8.7:**

- Generic constraints on categories (`#W-69`, `#W-70`) — medium; matters for library authors.
- `inout` or reference-shaped arrays (`#W-72`) — likely dissolves (arrays are already reference-shaped).
- Predicate-based pattern matching (Ink) — extension to §3.7.

---

## Session note — v0.8.6 DispatchQueue equivalent + execution metadata + PATTERNS.md (2026-08-08)

Sixth "dissolves into existing mechanisms" session. Path had two phases.

**Phase 1 — DispatchQueue framing.** Started as "how does Corvid express DispatchQueue." I initially sketched two constructs: `execution:` metadata on classes (for thread/QoS/pool) plus a new `coordination_scope` construct (for multi-class shared invariants). User pushed back sharply on `coordination_scope`: "why cant Pattern B just be a class? many-reads-single-write?"

That collapsed the design. Every case where separate objects need shared invariants can be re-expressed as one class owning the shared state. Bank/Ledger, Producer/Consumer/Queue, Cache/Store — all become one class per shared state cluster. The class boundary IS the coordination boundary. `coordination_scope` retracted.

Many-reads-single-write also fell out naturally: methods that don't modify state (readers) can run concurrently; methods that do (writers) serialize. Compiler infers classification from method body. Runtime uses RWLock-style dispatch. No user-visible annotation.

**Phase 2 — PATTERNS.md.** After the retraction, user made a meta-point: "corvid already offers the right primitive here. however, we might need to start collecting these patterns so future agents and humans dont get confused."

That's exactly right. Six sessions of dissolutions have built up an implicit knowledge tree — every dissolution has a "here's how Corvid does it" answer that's obvious once known and invisible until it is. Anyone coming from Swift/ObjC/Rust/Java/Python will predictably reach for constructs Corvid dissolved and hit walls.

Created `PATTERNS.md` at project root. Organized by-source-language ("coming from Swift you'd reach for X; Corvid does Y") plus a Corvid-native-patterns section that names the recurring principles. Not spec-normative; onboarding guidance.

**Decisions in this session:**

- **`execution:` section on classes (§8.4.2).** Metadata: `thread:` (main/any), `qos:` (four levels), `pool:` (implicit/dedicated). Class-level default with per-method override via nested section. Replaces `@main_actor` annotation.
- **Reader/writer classification (§8.4.1).** Compiler infers from method body. Multiple readers concurrent; writers exclusive. `data:` fields skip coordination entirely. No user-facing annotation.
- **DispatchQueue mapping (§8.4.3).** Three-row table: serial-mutex → contracts + execution; scheduling → spawn/async_scope; main-thread → execution: thread: main. Cross-object coordination → one class owns the state. Cross-object pipelines → channels (§8.6, unchanged).
- **`coordination_scope` retracted before shipping.** Every case collapses to one class.
- **`@main_actor` retired.** Replaced by `execution: thread: main`. Documentation updated across §1 preamble and §8.9.
- **ObjC bridge exposes `dispatch_pool`.** Per-instance property. ObjC callers use standard `dispatch_async(instance.dispatch_pool, ^{ ... })`. Routes through contract-guarded serialization transparently. `execution: thread: main` binds to the actual ObjC main queue (same identity).
- **`PATTERNS.md` new file.** Onboarding catalog for the accumulated dissolutions.

**Two philosophical points landed in this session:**

1. **The class boundary is the coordination boundary.** If two "objects" share an invariant, they were one class artificially split. Deep insight: Corvid's class-as-universal commitment (§1.5) has coordination consequences beyond just "everything is a class." The class IS the unit of shared-state consistency.
2. **Design pattern documentation is load-bearing.** Six sessions of dissolutions leave onboarding hazards. The primitives are right; the mental-model translation is missing. PATTERNS.md is the answer — not spec-normative, but explicitly the "here's how this maps from what you know" document. Future agents and humans get to walk into the design with a map instead of re-deriving each dissolution.

**Six-session pattern table** (in v0.8.6 changelog):

| Version | Dissolved | Replacement |
|---|---|---|
| v0.8.3 | `for` keyword, tuples, destructuring | prelude alias over `.for_each`; named-field records |
| v0.8.4 | nested type declarations | flat naming + leading-dot |
| v0.8.5 | `throws`, `try/catch/finally`, prelude `Result<T, E>` | nil-punning; domain enums; switch |
| v0.8.6 | `DispatchQueue`, `@main_actor`, would-be `coordination_scope` | contracts (§1.9); `execution:` metadata; class boundary = coordination boundary |

The pattern is stable enough to be a design principle in its own right (noted in PATTERNS.md's "Corvid-native patterns" section): **before adding a mechanism, check whether Corvid's structural commitments already cover the case via convention or composition.**

## Session note — v0.8.5 throws retired, no try/catch, no prelude Result (2026-08-08)

Fifth consecutive session in the "language mechanism dissolves into existing tools" pattern. Started as engagement with Ink #17 (try semantics); user pushed the reduction all the way down: "im not even sure why we need Result at all given that we have used nil-punning?"

That question forced the honest answer. Working through it:

- Nil-punning (v0.7.0) covers the "did we get a value or not" case completely. No Result needed there.
- For discriminated failure ("how did we fail"), a domain-specific enum with a case per failure mode is more expressive than a generic `Result<T, E>` — cases are domain-named, contain per-case associated data, are metadata-inspectable, and interact with v0.8.4 leading-dot cleanly.
- File.open example demonstrated the case where discrimination matters: `.not_found` / `.permission_denied` / `.volume_unavailable` / `.corrupted` need different handling; nil throws them all into one bucket.
- No `Result<T, E>` in prelude. User-authored generic Result acceptable for middleware if genuinely needed, but stdlib doesn't push toward it.
- No `try` / `catch` / `finally` construct. Considered `Try` as a prelude section class (would fit the sections mechanism naturally per user framing "try is a class with a state machine"). Concluded: switch + domain enums cover the ground; adding Try invites reaching for it when decomposition is the right answer.
- `throws` effect retired entirely from §3.5. Failure is not an effect; it's a return-type discipline.

**Decisions in this session:**

- **`throws` effect retired.** §3.5 rewritten — no throws in advisory or semantic effects. `effect_clause` grammar updated to exclude `throws`. `throw` removed from `guard let` exit-verb list (exits: return / break / continue / panic).
- **No `try` / `catch` / `finally`.** Not a keyword. Not a section class. Not a prelude alias. `switch` handles dispatch; existing `with:` section (§4.10.4.5) handles the RAII/cleanup case that `finally` would cover.
- **No `Result<T, E>` in prelude.** Domain-specific enums per failure family. Generic middleware conforms to `Failable<T>` protocol over any domain enum (rare use case; users can author).
- **ObjC interop translates per-method.** `NSError**` out-param maps to a domain enum with `.completed(T)` / `.failed(NSError)` cases. Not a blanket `throws NSError` binding.
- **Ink #17 (try semantics) resolved by dissolution.** Ink #29 (throws in ensures) dissolves entirely — `throws` doesn't exist; ensures apply on switch-case discrimination via `returns .completed(_) implies ...` shape.
- **Example sweep across the spec.** ~12 example rewrites from `throws E` shape to domain-enum-return shape. §3.4 protocol example, §4.10 RAII, §5.6 callable types, §7 visibility examples, §8.2 async example, §8.6 channel backpressure, §9.3 ObjC interop mapping, §11 worked example.

**What Corvid gives up:**

1. **Cross-cutting effect propagation.** Rust `?` and Swift `try` propagate errors across several layers without ceremony. Corvid requires each layer to explicitly `switch` on the outcome enum. More code per layer; more visible dispatch; no hidden control-flow jumps.
2. **Generic middleware over "any failable computation."** Without `Result<T, E>`, retry combinators need to be either domain-specific or work over a `Failable<T>` protocol. Slight setup cost; same expressive power.

Both trade-offs consistent with §1.6 legibility, visible dispatch, class-based over control-flow-based — and the last five sessions' pattern.

**Five-session pattern consolidated:**

| Version | Dissolved | Reason |
|---|---|---|
| v0.8.3 | `for` keyword | prelude alias over `.iter().for_each` on `Iterable<T>` |
| v0.8.3 | tuples + destructuring | classes with named fields, no positional forms |
| v0.8.3 | positional enum values (retracted) | named-only, consistent with rest of Corvid |
| v0.8.4 | nested type declarations | flat naming + leading-dot at use sites |
| v0.8.5 | `throws` effect | failure in return type via nil-punning or domain enum |
| v0.8.5 | `try / catch / finally` | switch on domain enum + with: for cleanup |
| v0.8.5 | `Result<T, E>` in prelude | domain-specific enums beat generic wrapper |

Each closes a common friction item by re-examining whether Corvid's structural commitments already handle the case with existing tools. Each time, they do. Pattern is stable enough to be worth naming as a design principle: **before adding a mechanism, check whether the existing structural commitments already cover the case via convention or composition.**

## Session note — v0.8.4 nested types dissolved + leading-dot shorthand (2026-08-08)

Short paired-decision session. Started as engagement with `#W-2` / `#W-48` (nested type declarations); user pushed back on Reading 1 (nested types as feature) with a C# comparison: "won't it mean if someone imports this in the future they have to alias it (had this issue in c#)". That reframed the trade-off.

**Decisions in this session:**

- **Nested type declarations dissolved by convention (§3.3.1 new).** No in-body type nesting added. Types associated with a class use `<ClassName><Role>` naming at module scope. Convention over language mechanism, matching v0.8.3's tuple/destructuring dissolution and Corvid's broader "intent-not-authority" (§1.2) discipline.
- **Leading-dot enum shorthand adopted (§3.7.2 new).** `.name(...)` and `.name` resolve to `<InferredType>.name(...)` when target type is inferable from context. Contexts documented: switch scrutinee, function parameter, return, field/variable declared type, comparison target. Ambiguous contexts produce compile error with suggestion.
- **The two decisions are ergonomically paired.** Flat naming would read verbose at switch arms and function args if written as `FrameCollectorEvent.text(...)` everywhere; leading-dot makes it read `.text(...)` at the sites that matter most. Nesting was proposed to give the same use-site brevity; leading-dot gives it without the consumer-side aliasing cost of nesting.

**Cost analysis that drove the call:**

- **Nested types:** every consumer pays either per-use path noise (`FrameCollector.Event.text(...)`) or per-file aliasing (`using Event = FrameCollector.Event;` in C#). Small ownership-visibility win at declaration.
- **Flat + convention:** one longer symbol per import site; no consumer-side overhead. Ownership signal from naming pattern is convention-enforced, not language-enforced.
- **Verdict:** consumers outnumber declarations. Push cost to declaration. Confirmed by user's C# experience.

**Precedent for the pattern:**

This is the third session in a row where design pressure resolved to "convention + existing tools" rather than "add language feature":

1. **v0.8.3 no tuples, no destructuring.** Multi-value carriers = classes with named fields. No `(a, b)` type, no `let (a, b) = pair`.
2. **v0.8.3 `for` is prelude sugar.** Iteration = method dispatch on `Iterable<T>`. `for` retires from bootstrap.
3. **v0.8.4 no nested types.** Class-associated types = flat naming + optional module aliases. Leading-dot handles use-site ergonomics.

Each closes a common friction item by re-examining whether Corvid's structural commitments (§1.5 class-as-universal, §5.1 universal dispatch, §1.2 intent-not-authority) already handle the case with existing tools. Each time, they do.

**Related resolutions:**

- Ink #14a (leading-dot shorthand) resolved by feature — same v0.8.4 §3.7.2.
- `#W-2` / `#W-48` (nested types) resolved by convention — v0.8.4 §3.3.1.

## Session note — v0.8.3 class-based iteration + no destructuring (2026-08-08)

Session followed from friction-log audit. Ten items marked resolved across the two logs, surfacing the true pending set. Tuples were named as the next natural session target — but user framing pushed deeper: "consider that the `for` loop is also sugar? how does one actually iterate in oop? won't it be recursion? a object sending message to itself?"

That question re-scoped the work entirely. Path taken:

**Iteration in pure OOP is message dispatch on the collection.** Smalltalk / ObjC-lineage: `collection do: [block]` — collection controls how it walks itself, caller supplies per-element behavior. Fundamentally reducible to recursion via self-send. C-style `for` is imported convention, doesn't belong in bootstrap under Corvid's class-as-universal + universal-method-dispatch commitments.

**Decisions in this session:**

- **`for` retires from bootstrap; becomes prelude alias.** Expansion: `for x in y: body` → `y.iter().for_each { x -> body }`. Parses as an ordinary alias (§4.20.1). `Iterable<T>` conformance is what makes any class iterable.
- **`Iterable<T>` and `Iterator<T>` stdlib protocols.** `iter() -> Iterator<T>` on the collection; `next() -> T` (nil-on-exhaustion, integrates with v0.7 nil-messaging) plus `for_each(f)` default impl on the iterator.
- **`Pair<A, B>` (general) and `Entry<K, V>` (map-shaped) stdlib carriers.** Both `kind: record` with named fields. `Map.iter()` yields `Iterator<Entry<K, V>>`; user reads `entry.key` / `entry.value`. Domain-specific records (`Slice { position, length }`) preferred over generic `Pair` when a domain word applies.
- **No destructuring bindings.** `let (a, b) = pair` explicitly not spec'd. `for (k, v) in dict` not spec'd. Multi-value extraction is always named-accessor method dispatch on the returning class. Rule extends v0.8.1's named-only enum associated values discipline uniformly across the language.
- **Case-pattern extraction inside `switch` is the sole exception.** `case .some(value: let x):` and `case Order(id: let some_id, items: []):` are legal — pattern matching in `case` is discrimination-with-name-binding, which requires looking inside the value. Positional single-value form `case .some(let x)` also legal (no ambiguity with one associated value). Everything else uses accessors.
- **`while` and `loop` stay bootstrap.** They're the primitives stdlib uses to implement iteration methods without infinite regress or a TCO commitment. Discipline note in §4.20.3: user-facing code should prefer `.for_each` / `.map` / `.filter`; `while` / `loop` are for stdlib and for algorithms that genuinely don't fit iterator abstractions.
- **Tuple-family friction items dissolved.** `#W-35` (tuple destructuring in `for`), the tuple portion of `#W-6`, tuple types generally — all marked "dissolved by design" rather than "resolved by feature." No language-level tuples added.

**What this closed off:**

1. The tuples question. Corvid does not have tuples. This is intentional and consistent with the class-based model.
2. Positional destructuring anywhere. Names beat positions, always.
3. Any future proposal to add `for (k, v) in dict`-style shorthand. It contradicts the class-based iteration model.

**Two philosophical points worth remembering:**

1. **Structural commitments have surface-syntax consequences.** Committing to "class as universal" (§1.5) + "universal method dispatch" (§5.1) means `for` doesn't belong in bootstrap. Making that consequence explicit strengthens the design's internal coherence.
2. **The right answer to "how do I do X?" is sometimes "you don't — you do Y instead."** Tuples felt like an obvious language feature. The class-based frame reveals: they're an obvious *feature*, not an obvious *need*. Multi-value returns with named accessors are cleaner, more consistent, and more legible. The friction was in the framing, not in Corvid's expressiveness.

**User's own words on the accessor style:** "yes `for entry in dict: process(key: entry.key, value: entry.value)` is much cleaner and expected." Confirmed that the named-accessor form is the *expected* idiom, not a compromise.

## Session note — v0.8.2 alias namespace discipline + `|>` rejection (2026-08-08)

Short refinement session sharpening the alias mechanism from v0.8.0. Two decisions.

**Decision 1 — user aliases require `@` marker at declaration and use, in every position.**

Path to it: user asked whether alias triggers should use `@` at use sites to make it clear a construct is coming from an alias (with the parser simplification as bonus). I drafted a position-selective proposal — `@` for delimited/prefix/suffix/class-kind but not for substitution aliases (types, values, modules) or infix. User pushed back: "why not `@Vector`? it's a smaller price to pay" and then "just stick with one uniform consistent way to do things."

I was defending "brevity" as if it were the load-bearing win for substitution aliases; it isn't. The load-bearing wins are provenance visibility (reader sees `@X` and knows to grep for `alias @X` in this project) and namespace separation (user's `@Vec` never collides with an imported `Vec`; disjoint tables, no shadowing puzzles). Both apply equally to substitution and surface aliases. Uniformity costs one character per use and buys a globally consistent legibility signal.

Prelude aliases stay unmarked. They are Corvid's committed surface syntax and read as language. The `@` split reinforces the "prelude = language, `@` = project-local" binary from the other direction.

Infix carve-out considered and rejected once `|>` was dropped from prelude (see below); with `|>` gone, the strongest awkward example (`x @|> f`) becomes rare (only projects that actively want pipe), and uniformity is worth more than protecting one aesthetic case.

Grammar: `@` reserved as bootstrap sigil. `alias_trigger` production added at every use position. `user_signature` variant in `alias_signature` requires `@` on the trigger. Prelude sources use `prelude_signature` (unmarked); user code MUST use `user_signature` (compile error otherwise). Two-pass compilation preserved but Pass 2 alias-recognition collapses to "if `@` present, consult alias table; else bootstrap+prelude only."

**Decision 2 — `|>` (pipe) explicitly kept out of the prelude.**

User asked "do we really need `|>` ? what does it actually do for class-based programming."

Working through it: `|>` earns its keep in FP languages where data and behavior are separate (Elixir, F#, OCaml) — it threads values through modules of free functions. Corvid has universal method dispatch, cheap extensions/sections, and open runtime; method chaining (`x.f().g().h()`) is the native pipeline shape and covers every case pipe would. Shipping `|>` would create two idioms for the same operation and pull users toward free functions instead of extension methods (fighting Corvid's core extension model).

Documented in §4.20.3 as a "paradigm-mismatch exclusion." Projects that genuinely want pipe can declare `alias <lhs> @|> <rhs> ...` locally; the `@` marker makes the project-local nature visible at every use site. Swift has resisted `|>` for the same reason.

**Prelude-discipline principle worth remembering.** Constructs common in adjacent languages are excluded from the prelude when they don't fit Corvid's specific paradigm. The prelude should ship what Corvid's model *specifically* wants, not what's popular elsewhere. Worth applying to any future prelude candidate imported from a different paradigm.

**Two philosophical points that landed in this session:**

1. **Uniformity beats special cases when the underlying rule is coherent.** I initially proposed a position-selective `@` rule because I was optimizing local cost (identifier substitutions look ugly with `@`). User's push toward uniformity was right — the rule "all user aliases are `@`-marked" is easier to teach, easier to parse, easier to read, and the per-use cost is smaller than the ecosystem-level cost of a special-case rule.
2. **The prelude is the language's paradigm expressed as syntax.** Excluding `|>` isn't a syntactic decision, it's a paradigm decision. The prelude is a committed statement about how Corvid wants users to compose behavior. Method chaining is the answer; pipe would be a competing answer.

## Session note — v0.8.1 Session 3 fills (2026-08-08)

Small additive session. Filled concrete parity-translation gaps that didn't warrant Level-2-scale mechanism work.

**Decisions in this session:**

- **Narrowing conversion wraps by default.** User pushed on the "trap in dev, wrap in release" recommendation I made initially, and it was inconsistent with §3.9 arithmetic overflow discipline (which wraps in both dev and release). Corrected to: `.into(T)` wraps, `.checked_into(T)` returns Result, `.saturating_into(T)` clamps. One consistent rule across arithmetic and conversion.
- **Parameter defaults are compile-time constants, statically inserted.** User asked "why can't parameter defaults be statically inserted" — the answer is they can, if we restrict defaults to compile-time constants. Same discipline as value aliases (§4.16.10). Removes Python's mutable-default footgun by construction (constants can't be mutated shared state). Runtime-computed defaults use `= nil` + coalesce in body.
- **Enum associated values stay named-only.** User pushed back ("sure?") on my initial proposal to add positional form. Retracted — named-only is more consistent with the rest of Corvid (named args, named record fields, named `accepts`). Style guide note: when translating positional Swift enums, add names.
- **Collection literals rejected as parameter defaults.** They allocate; compile-time-constant discipline requires no allocation. Users needing `= []` default write `= nil` + coalesce in body.

Resolved friction points: `#W-47` (constructor defaults), `#W-56` / `#W-59` / `#W-65` (numeric conversion), `#W-66` (type extrema), `#W-29` multi-binding extension.

**Design pattern worth remembering:** the compile-time-constant discipline is now used in two places — value aliases (§4.16.10) and parameter defaults (§3.8.5). Both restrict to primitive literals + nil + value-alias references. This unifies the "what counts as static substitution" question across the language. If we add more static-substitution positions later, use the same rule.

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
