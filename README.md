# Corvid Language Design — Handoff Bundle

**Status:** Pre-implementation. Design work through v0.7.1 (Tier 1 sweep: numeric literal bases, character literals, bitwise + modulo operators, module-level `let`, `sizeof`/`alignof`/`offsetof` — all direct C inheritance, no design decisions). Prior v0.7.0 nil-messaging inversion (reference types nil-allowed by default, `T!` non-nil contract sugar, `?.` and force-unwrap retired, new `on_nil` section for userspace nil-punning) and v0.6.0 structural unification (class-as-universal, kind-via-metadata, closures-as-anonymous-classes, everything-is-expression, channel modes) preserved. No compiler exists yet.

**Working name:** Corvid.
**Original name:** objc5 (used throughout the design conversation; renamed when positioning solidified).

---

## What Corvid is, in one paragraph

A native-first programming language for the Apple ecosystem, targeting Mac, iOS, and Foundation-Swift servers. Shares the libobjc2 / objc4 runtime with Objective-C and Swift; interoperates directly with Foundation, AppKit, and UIKit. Positions as a peer of Swift with contracts and invariants as first-class features, universal queryability via extents, a unified metaprogramming mechanism (sections), and preservation of Objective-C's runtime openness.

## What this bundle contains

```
corvid/
├── README.md                        (this file)
├── working-notes.md                 (decision log from the design conversation)
├── corvid-handoff.zip               (original handoff archive, kept for provenance)
│
├── spec/
│   ├── charter.md                   (§1.1-§1.9 as standalone — read first)
│   └── spec-v0.7.md                 (full specification, ~4600 lines)
│
├── positioning/
│   ├── overview.md                  (positioning statement — read second)
│   ├── game-of-life.md              (reference implementation scope)
│   └── ecosystem.md                 (relationship to Swift, ObjC, frameworks)
│
├── validation/
│   ├── README.md                    (Ink translation exercise overview)
│   ├── 00-friction-log.md           (38 friction points from translation)
│   ├── 01-Markdown.parity.cor       (Corvid translation, parity pass)
│   ├── 01-Markdown.source.swift     (original Swift)
│   ├── 02-Blockquote.parity.cor
│   ├── 02-Blockquote.source.swift
│   ├── 03-Reader.inferred.cor       (interface reconstructed from usage)
│   ├── 04-idiomatic-pass.md         (idiomatic rewrite of 3 files + findings)
│   └── 05-fragment-coverage.md      (6 more Fragment types + patterns confirmed)
│
└── historical/
    ├── sections-design-analysis.md  (analytical work supporting §4 sections)
    └── session-transcript.md        (full design-conversation transcript)
```

## Recommended reading order

Depending on how much time you have.

**15 minutes:**
1. This README (5 min).
2. `positioning/overview.md` (10 min).

You now know what Corvid is, who it's for, and what the design bet is.

**1 hour:**
3. `spec/charter.md` (30 min).
4. `validation/README.md` + skim `validation/00-friction-log.md` (15 min).

You now know the design premises and know what's been empirically tested.

**Half day:**
5. `positioning/game-of-life.md` (30 min).
6. `positioning/ecosystem.md` (30 min).
7. `spec/spec-v0.7.md`, sections you care about most. Different sections for different concerns:
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
9. `validation/04-idiomatic-pass.md` and `validation/05-fragment-coverage.md` for the two-type pattern findings.
10. `working-notes.md` for the reasoning behind each major decision.

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
- **Naming**: "Corvid" is current working name. Could stick or change based on trademark research.
- **Detailed runtime cost model** for state-machine synchronization at scale.
- **String interpolation syntax** (spec gap #22 in validation).
- **`try` keyword semantics** (spec gap #17).
- **Protocol conformance syntax** (spec gap #13).
- **`old()` in ensures clauses** (spec gap #28).
- **String position API in stdlib** (spec gap #25).

The friction log has the complete list of syntactic gaps discovered during the Ink translation exercise. All are addressable; none require architectural changes.

## What the empirical validation tested

The Ink Markdown parser (JohnSundell/Ink) was translated to Corvid in two passes:

**Parity pass** — line-by-line translation of Markdown.swift, Blockquote.swift, and (interface-inferred) Reader.swift. Surfaced 30 friction points across three files.

**Idiomatic pass** — rewrite of the same three files using Corvid design idioms. Confirmed the design shortens code (~170 Corvid lines vs. ~243 Swift), holds up structurally, and enables concrete correctness wins (invariants, contracts, state-machine sync).

**Extended coverage** — six additional types (MarkdownParser, Fragment protocol, Paragraph, HorizontalLine, Heading, List) added. Confirmed the two-type pattern (struct-value + class-builder) across every structural shape.

**Total surface tested:** 9 files, ~245 Swift lines translated, ~450 Corvid lines produced.

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

The Game of Life reference implementation (see `positioning/game-of-life.md`) is designed to fill these gaps.

## Immediate next steps for whoever picks this up

**If you're evaluating Corvid** (should we build this at all):

1. Read the positioning statement.
2. Read the design charter.
3. Look at validation results.
4. Decide: does the bet resonate? Is the empirical evidence enough?

**If you've decided to build it**:

1. **Nail down the syntactic spec gaps** listed in the friction log. Roughly 200-300 lines of spec revision across §2, §3, §3.7, §7, §10.
2. **Design Stage 0**: the Corvid→ObjC translator, written in Objective-C. Scope this precisely — which subset of Corvid does it accept? What does it emit? Concrete example translations.
3. **Prototype the section mechanism**: even before a full compiler, prototype how sections desugar to primitives, generators run, and metadata artifacts get produced. This is the load-bearing mechanism; if it doesn't work, nothing else matters.
4. **Build Stage 1 milestone plan**: LLVM IR direct + LSP + incremental compilation = 18-24 months of engineering. Break into 3-month milestones.
5. **Start writing the Game of Life protocol module** as design pressure — you'll surface real requirements the current spec doesn't address.

**If you're going to change the design**:

1. Re-read the design charter first. Verify your change doesn't contradict a premise. If it does, decide whether to preserve or revise the premise.
2. Check the friction log to see if your change would resolve outstanding items.
3. Update the version history and add a change-log entry naming what changed and why.
4. Run at least one validation exercise (translate real code) before committing structural changes.

## Naming, briefly

- **"Corvid"** — current working name. Bird family (crows, ravens). Signals intelligence and adaptability. No known programming-language name conflict but not verified against trademark databases.
- **"objc5"** — original working name used throughout the design conversation. Misleading because Corvid is not the next version of Objective-C.
- **File extension**: `.cor`.

Renaming to something else is easy at this stage; renaming after adoption is hard. Consider verifying the name before public announcement.

## The design conversation transcript

The full transcript of the design conversation that produced this bundle is preserved at `historical/session-transcript.md`. That transcript contains:

- The reasoning behind every design decision.
- Alternatives considered and rejected.
- Places where the design changed based on empirical findings from the validation exercise.
- User pushback that shaped specific commitments (e.g., the removal of `sealed` and `private` per §1.2).

The transcript is not necessary for using the bundle; the bundle is self-contained. But if you want to understand *why* a specific decision was made, the transcript is the source.

## Legal / attribution

Corvid is a language design created by the user of this bundle. Anthropic's Claude was the AI collaborator throughout the design process. The Ink Markdown parser (used for validation) is © John Sundell, MIT license. The runtime target (libobjc2) is © the GNUstep project, MIT license.

No license is currently attached to Corvid itself; the user should choose one (typically MIT or Apache 2.0 for language projects).

## Contact

The user of this bundle owns the design. This handoff has been prepared for continuation by another agent or a human implementer. Direct questions and follow-ups to the user.

---

*This bundle prepared August 2026 as a handoff artifact. Bundle version: 1.0.*
