# Corvid v0.5 Empirical Validation: Starscream WebSocket

**Exercise:** Translate the frame parser + handshake layer of [Starscream](https://github.com/daltoniam/Starscream) — a Swift WebSocket library — to Corvid v0.5.

**Why this exercise.** Ink covered structural types, protocols, sealed enums, and the two-type pattern. It didn't touch real state machines, contract-worthy invariants across a protocol implementation, async I/O concerns, or delegate-callback patterns. WebSocket framing is the smallest realistic surface that exercises all four.

**Scope discipline.** Frame parser + handshake only. No network I/O (Transport/), no compression (Compression/), no engine (Engine/), no server plumbing (Server/). This is the state-machine-heavy core, not the plumbing around it.

## Files

| File | Purpose | LOC |
|---|---|---|
| `00-friction-log.md` | Consolidated friction log from the parity pass (73 items across 3 files, plus 8 categories deferred). |
| `01-Framer.source.swift` | Original Swift — frame construction and parsing (masking, opcodes, byte-level framing) | 365 |
| `01-Framer.parity.cor` | Parity translation with inline `FRICTION:` notes. |
| `02-FrameCollector.source.swift` | Original Swift — fragment accumulation state machine (opcode dispatch, close handling, UTF-8 validation) | 107 |
| `02-FrameCollector.parity.cor` | Parity translation with inline `FRICTION:` notes. |
| `03-HTTPHandler.source.swift` | Original Swift — WebSocket upgrade handshake (protocol definitions, header struct, URL helpers) | 150 |
| `03-HTTPHandler.parity.cor` | Parity translation with inline `FRICTION:` notes. |
| `03a-FoundationHTTPHandler.source.swift` | Original Swift — Foundation-based concrete `HTTPHandler` implementation | 124 |
| `03a-FoundationHTTPHandler.deferred.md` | Reasoning for not translating this file + catalog of the additional friction categories a full translation would surface. |

**Total source:** 746 lines of Swift across 4 files.
**Total translated:** ~950 lines of Corvid parity (3 files; the CF-bridging file is deferred).

## Passes

Mirrors the Ink exercise structure.

1. **Parity pass** — DONE. Line-by-line translation into `.parity.cor` counterparts with inline `FRICTION:` notes. Log at `00-friction-log.md`.
2. **Idiomatic pass** — NOT STARTED. Rewrite the same files using Corvid idioms — sections, contracts, state-machine-as-synchronization, universal enumerations. Compare to parity output. Will produce `04-idiomatic-pass.md`.
3. **Extended coverage** (optional). Additional context the first two passes leave open — extending to `Compression/` for section-composition, or to `Engine/` for concurrency wiring.

## Parity-pass results (summary)

**73 friction points** across three translated files, clustered into **15 families**. See `00-friction-log.md` for the full log and cross-exercise priority comparison with Ink.

Category mix (vs. Ink's cluster around pattern-matching / optionals / strings):
- Bit-level operations (bitwise ops, shifts, compound-bit-assigns)
- Enum raw values (integer-backed enums)
- Range literals + range-based iteration
- Generics on categories/extensions
- Foundation concurrency primitives (DispatchQueue-shaped serialization)
- CF↔Foundation bridging (catalogued in `03a-...deferred.md`, not translated)

**Six friction points recur from the Ink log** (string interpolation, `guard let`/`if let`, argument labels, class-level members on structs, optional chaining, `try` semantics). These are the highest-priority spec fixes because they've been validated on two independent codebases.

**Design decisions surfaced (require choices, not just spec additions):**
1. DispatchQueue equivalent at the language level, or leave as `unsafe:`.
2. Closure syntax + capture list annotations.
3. `inout` or reference-shaped arrays.

Recommended next step per the log: **Path B (idiomatic pass)** — the parity pass was verbose exactly where the idiomatic pass will collapse into `invariants:` and `requires`/`ensures` clauses, so measuring the payoff over the same code surface is the highest-signal follow-up.

## What this exercise is designed to surface

- **Real state machines.** `FrameCollector.add(frame:)` has three implicit invariants: "first frame can't be continue," "second and beyond must be continue," "if isFin then reset." Direct opportunity to test `requires`/`ensures` (§3.4) and state-machine-as-synchronization (§1.9, §8).
- **Byte-level framing with contracts.** `Framer` does bit-level manipulation with masking, opcode tables, and length encoding. Exercises whether Corvid can express low-level work cleanly.
- **Protocol-based delegation.** `HTTPHandler` and `FrameCollectorDelegate` are pure protocols with concrete implementations. Exercises the interop story between spec-level abstractions and Foundation-typed impls.
- **Foundation interop at depth.** `URLRequest`, `HTTPCookieStorage`, `URL` extensions, `Data`. Real bindings, not just types.

## What this exercise is NOT designed to cover

- **Concurrency at scale.** Only the frame parser and handshake are here. `WSEngine`, `NativeEngine`, and the transport layer would be a separate exercise.
- **TLS / security.** `Security/` is out of scope.
- **Compression.** Deferred — exercises `permessage-deflate` extension negotiation, a distinct concern.

## Reference implementations

Both Swift and Objective-C reference implementations exist for the same RFC 6455 problem:

- **Swift:** [daltoniam/Starscream](https://github.com/daltoniam/Starscream) — the source translated here.
- **Objective-C:** [facebookincubator/SocketRocket](https://github.com/facebookincubator/SocketRocket) — used as a reference for how the same problem is solved without Swift's affordances. Not translated; consulted for interop-pattern comparison.

## License

Starscream source is Apache 2.0 (© Vluxe / Dalton Cherry, 2019). The `.source.swift` files here are unmodified copies for reference.
