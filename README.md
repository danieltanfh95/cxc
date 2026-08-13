# cxc Language Design — Handoff Bundle

**Name.** cxc — **channels-C**, positioned in the naming lineage of **objc** (objective-C). Just as objc added object-orientation on top of C, cxc adds channels / CSP / an atom substrate on top of the C-family systems lineage. Originally codenamed **Corvid** (2026-08); the codename persists in directory paths, historical design notes, and repo infrastructure. Both names refer to the same language.

**Status.** v1.0 draft. Pre-implementation. The design pivot to the atom substrate (see `design/spec/spec-cxc-v1.0.md` §1.11) is the committed direction; §3–§9 currently describe the v0.11 intermediate shape (`class`, `record`, `enum`, `protocol` as primary declaration forms) and will be restructured as sugar over `def` atom shapes in a subsequent spec revision.

**v0.11 landings (historical).** Value-type auto-conformance to `As<Bytes>` / `From<Bytes>` (§3.4.6, §3.10.10 canonical wire format spec) + double-colon header form removed (v0.10.2's grace period ended). Records, enums, and primitives auto-serialize via compiler-synthesized methods from field layout — same shape as automatic structural equality on records (§3.2), a base-class property rather than a stdlib opt-in. Canonical wire format: network-order (big-endian) multi-byte primitives, fields concatenated in declaration order, enums as discriminator + associated data, strings/collections as UInt32-length-prefixed payload. Only `As<Bytes>` gets auto-conformance because bytes are the universal substrate (§3.10.4) with one canonical encoding; format-specific overlays (JSON, MsgPack, etc.) have per-record author choices and remain user-written via ordinary `extend class Foo:` (§3.3.2).

**v0.10.2 (prior — explicit conformance syntax).** Header parens sugar `class Foo(A, B):` canonical for protocol conformance; body-form `metadata: extends:` for ObjC inheritance; double-colon deprecated (removed in v0.11 as scheduled).

**v0.10.1 (prior — Pattern subsystem + Convertible retirement).** Pattern subsystem: regex, glob, literal landed as follow-on to v0.10.0's String/Bytes work. Pattern is one protocol with three concrete conformers (§3.10.9), pattern literals via raw-content prefix aliases (`regex"..."` / `glob"..."` / `literal"..."`) per new §4.16.17, named captures only. Additive stdlib session — no design-mechanism changes. Also in v0.10.1: `Convertible<To>` retired (§3.11.1–§3.11.4) in favor of `As<T>` for lossless conversion + three ordinary methods (`wrapping_to`, `saturating_to`, `checked_to`) for numeric narrowing, plus `ConvertOutcome<T>` domain enum replacing the v0.8.5-inconsistent `Result<T, OverflowError>`. Prior v0.10.0 landing preserved.

**Prior v0.10.0 (byte-substrate + text/serialization).** Three interlocking landings: (a) `Bytes`, `String`, `Char`, `StringPosition` types (§3.10.4–§3.10.7) — grapheme-native String with no integer indexing, mutable Bytes as regular class (no separate builder), StringPosition as opaque token; (b) `As<T>` / `From<T>` parameterized conversion protocols with trait-qualified dispatch (§3.10.3, §3.4.4) — one class may declare `function as(self) -> Bytes` and `function as(self) -> Json` differentiated by target type; (c) §3.3.2 rule 4 revised from compile-error to warning + deterministic resolution (current module > imported > original) aligning extension composition with cxc's broader "warn, don't block" pattern; supporting enums `Encoding`, `ByteOrder`, `DecodeOutcome<T>` (§3.10.8). Dissolves Ink #25, websocket #W-30 (position part), #W-14, #W-53, #W-52, #W-61, `F8`, #W-37c, #W-37e. v0.9.1 (extension conflict-resolution seven-rule behavior spec) preserved as internal detail now revised through rule 1 (warn) and rule 5 (trait-qualified exemption). Prior work preserved: v0.9.0 unified class extension (`extend class Foo:`), v0.8.7 pre/post bindings in ensures, v0.8.6 concurrency model, v0.8.5 no-throws/no-try/no-prelude-Result, v0.8.4 nested types dissolved + leading-dot shorthand, v0.8.3 class-based iteration, v0.8.2 alias namespace discipline, v0.8.1 Session 3 fills, v0.8.0 library-first sugar, v0.7.x nil-messaging inversion + tier-1 sweep, v0.6.0 structural unification. Companion `PATTERNS.md` catalogs the accumulated dissolutions for onboarding. No compiler exists yet.

**Naming history.** `objc5` (initial design conversation, positioning as "next-generation Objective-C") → `Corvid` (v0.10.0, positioning clarified as "peer of Swift for Apple-ecosystem native development") → `cxc` (v1.0-draft, positioning as "channels-C" — the atom-substrate answer to objective-C, mirroring the objc naming convention).

---

## What cxc is, in one paragraph

An **atom-substrate reactive language with Swift-shaped surface, CSP + core.async composition, contract-based verification, and dataflow compilation.** Class = bounded atom graph. Functions = the trivial atom shape. Every construct is a shape of atom. Native-first for the Apple ecosystem (Mac, iOS, Foundation-Swift servers) via shared libobjc2 / objc4 runtime with Objective-C and Swift; interoperates directly with Foundation, AppKit, UIKit. Positions as the atom-substrate successor to objc — same C-family lineage, same ecosystem, new coordination primitive.

## What this bundle contains

```
corvid/                                    (directory name preserved from the Corvid codename era)
├── README.md                              (this file)
│
├── design/                                (all design documentation)
│   ├── PATTERNS.md                        (onboarding — "coming from language X, cxc does Y")
│   ├── working-notes.md                   (decision log from the design conversation)
│   │
│   ├── spec/
│   │   ├── charter.md                     (§1.1-§1.9 as standalone — read first)
│   │   └── spec-cxc-v1.0.md               (full specification; v1.0 draft, atom substrate)
│   │
│   ├── examples/
│   │   ├── README.md                      (examples overview)
│   │   └── game-toys.cxc                  (toy game atoms — Paddle, Player, Enemy, sub-atom-as-state-machine)
│   │
│   ├── explorations/
│   │   └── integers-from-bits.cxc         (worked derivation: Bit → Byte → UInt8 → Nat → Age)
│   │
│   ├── positioning/
│   │   ├── overview.md                    (positioning statement — read second)
│   │   ├── game-of-life.md                (reference implementation scope)
│   │   └── ecosystem.md                   (relationship to Swift, ObjC, frameworks)
│   │
│   ├── validation/                        (pre-atom-substrate translation exercises; .cor extension retained)
│   │   ├── README.md
│   │   ├── 00-friction-log.md             (38 friction points from translation)
│   │   ├── 01-Markdown.*, 02-Blockquote.*, 03-Reader.*
│   │   ├── 04-idiomatic-pass.md, 05-fragment-coverage.md
│   │   └── websocket/                     (WebSocket framer translation)
│   │
│   ├── historical/
│   │   ├── sections-design-analysis.md    (analytical work supporting §4 sections)
│   │   └── session-transcript.md          (full design-conversation transcript)
│   │
│   └── archive/
│       └── corvid-handoff.zip             (original handoff archive, kept for provenance)
│
├── compiler/                              (Clojure compiler — Phase 7)
├── runtime/                               (ObjC + C runtime library — Phase 7)
├── prelude/                               (FFI shims per §1.14 — Phase 7)
│
├── .replsh/                               (REPL session config for compiler dev)
└── .gitignore
```

## Recommended reading order

Depending on how much time you have.

**15 minutes:**
1. This README (5 min).
2. `design/positioning/overview.md` (10 min).

You now know what cxc is, who it's for, and what the design bet is.

**1 hour:**
3. `design/spec/charter.md` (30 min).
4. `design/validation/README.md` + skim `design/validation/00-friction-log.md` (15 min).

You now know the design premises and know what's been empirically tested.

**Half day:**
5. `design/positioning/game-of-life.md` (30 min).
6. `design/positioning/ecosystem.md` (30 min).
7. `design/spec/spec-cxc-v1.0.md`, sections you care about most. Different sections for different concerns:
   - §1: design premises (already read via charter).
   - §2: lexical structure.
   - §3: type system, contracts, effects.
   - §4: sections (the biggest chapter, the core mechanism).
   - §5: dispatch model.
   - §6: memory model (structs, classes, extents, tags, snapshots).
   - §7: modules, packages, sources.
   - §8: concurrency (channels, signals, state-machine-as-sync).
   - §9: ObjC interop.
   - §14: implementation stages.

**Full day:**
8. Full spec end-to-end (3-4 hours).
9. `design/validation/04-idiomatic-pass.md` and `design/validation/05-fragment-coverage.md` for the two-type pattern findings.
10. `design/working-notes.md` for the reasoning behind each major decision.

## What's decided vs. what's undecided

### Decided

- **The design charter** (§1.1-§1.9). Every subsequent decision descends from these premises. Reversing a premise requires deliberate rationale.
- **The section mechanism** (§4). One user-facing metaprogramming construct. Aliases, primitives, and behavioral extensions are all section classes.
- **The contract system** (§3.4, §1.9). Requires/ensures/invariants as first-class features. State-machine synchronization falls out.
- **The memory model** (§6). Struct, enumeration, class as three type constructs. Universal extents for classes. Handle-based references with FilC-style validation. Tags with contiguous storage. Snapshots for query results.
- **The dispatch model** (§5). All methods semantically dynamic. Compiler devirtualizes automatically. No source-level dispatch annotations.
- **The concurrency model** (§8). Channels for async event streams. Signals for one-shot completion. State-machine-as-synchronization. Structured async.
- **The module system** (§7). Sources, modules, packages as three levels. Package section for manifest. Aliases for brevity. Implicit resolution with explicit overrides.
- **The visibility model** (§7.7). Two states: `expose` and absent. Compiler warns on external access to non-exposed. Warnings per import.
- **ObjC interop** (§9). File-level coexistence. Auto-derived headers. Shared runtime substrate.
- **Runtime target**: libobjc2 / objc4. Design commits to Apple-ecosystem interop.
- **Language positioning**: peer of Swift for Apple-ecosystem native development. Not "next Objective-C."

### Undecided

- **Specific stdlib scope**. What ships with the compiler; what's separately packaged. Post-v1 decision.
- **Package registry protocol**. No registry exists; deferred.
- **Naming**: "cxc" is current working name. Could stick or change based on trademark research.
- **Detailed runtime cost model** for state-machine synchronization at scale.
- **String interpolation syntax** (spec gap #22 in validation).
- **Protocol conformance syntax** (spec gap #13).

The friction log has the complete list of syntactic gaps discovered during the Ink translation exercise. All are addressable; none require architectural changes.

## What the empirical validation tested

The Ink Markdown parser (JohnSundell/Ink) was translated to cxc in two passes:

**Parity pass** — line-by-line translation of Markdown.swift, Blockquote.swift, and (interface-inferred) Reader.swift. Surfaced 30 friction points across three files.

**Idiomatic pass** — rewrite of the same three files using cxc design idioms. Confirmed the design shortens code (~170 cxc lines vs. ~243 Swift), holds up structurally, and enables concrete correctness wins (invariants, contracts, state-machine sync).

**Extended coverage** — six additional types (MarkdownParser, Fragment protocol, Paragraph, HorizontalLine, Heading, List) added. Confirmed the two-type pattern (struct-value + class-builder) across every structural shape.

**Total surface tested:** 9 files, ~245 Swift lines translated, ~450 cxc lines produced.

**What's confirmed:**
- The section mechanism handles real code.
- The two-type pattern applies universally across Fragment types.
- Contracts and invariants document real bugs.
- Universal enumerations replace sealed protocols cleanly.
- The design language is shorter than Swift when it fits.

**What's not yet tested empirically:**
- Concurrency at scale (channels, signals, state-machine sync).
- Extent performance under high instance counts.
- ObjC framework interop at depth (only Foundation types touched).
- Multi-target deployment.

The Game of Life reference implementation (see `design/positioning/game-of-life.md`) is designed to fill these gaps.

## Immediate next steps for whoever picks this up

**If you're evaluating cxc** (should we build this at all):

1. Read the positioning statement.
2. Read the design charter.
3. Look at validation results.
4. Decide: does the bet resonate? Is the empirical evidence enough?

**If you've decided to build it**:

1. **Nail down the syntactic spec gaps** listed in the friction log. Roughly 200-300 lines of spec revision across §2, §3, §3.7, §7, §10.
2. **Design Stage 0**: the cxc→ObjC translator, written in Objective-C. Scope this precisely — which subset of cxc does it accept? What does it emit? Concrete example translations.
3. **Prototype the section mechanism**: even before a full compiler, prototype how sections desugar to primitives, generators run, and metadata artifacts get produced. This is the load-bearing mechanism; if it doesn't work, nothing else matters.
4. **Build Stage 1 milestone plan**: LLVM IR direct + LSP + incremental compilation = 18-24 months of engineering. Break into 3-month milestones.
5. **Start writing the Game of Life protocol module** as design pressure — you'll surface real requirements the current spec doesn't address.

**If you're going to change the design**:

1. Re-read the design charter first. Verify your change doesn't contradict a premise. If it does, decide whether to preserve or revise the premise.
2. Check the friction log to see if your change would resolve outstanding items.
3. Update the version history and add a change-log entry naming what changed and why.
4. Run at least one validation exercise (translate real code) before committing structural changes.

## Naming, briefly

- **"cxc"** — current working name. Bird family (crows, ravens). Signals intelligence and adaptability. No known programming-language name conflict but not verified against trademark databases.
- **"objc5"** — original working name used throughout the design conversation. Misleading because cxc is not the next version of Objective-C.
- **File extension**: `.cxc`.

Renaming to something else is easy at this stage; renaming after adoption is hard. Consider verifying the name before public announcement.

## The design conversation transcript

The full transcript of the design conversation that produced this bundle is preserved at `design/historical/session-transcript.md`. That transcript contains:

- The reasoning behind every design decision.
- Alternatives considered and rejected.
- Places where the design changed based on empirical findings from the validation exercise.
- User pushback that shaped specific commitments (e.g., the removal of `sealed` and `private` per §1.2).

The transcript is not necessary for using the bundle; the bundle is self-contained. But if you want to understand *why* a specific decision was made, the transcript is the source.

## Legal / attribution

cxc is a language design created by the user of this bundle. Anthropic's Claude was the AI collaborator throughout the design process. The Ink Markdown parser (used for validation) is © John Sundell, MIT license. The runtime target (libobjc2) is © the GNUstep project, MIT license.

No license is currently attached to cxc itself; the user should choose one (typically MIT or Apache 2.0 for language projects).

## Contact

The user of this bundle owns the design. This handoff has been prepared for continuation by another agent or a human implementer. Direct questions and follow-ups to the user.

---

*This bundle prepared August 2026 as a handoff artifact. Bundle version: 1.0.*
