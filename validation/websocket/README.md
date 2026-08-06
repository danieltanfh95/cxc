# Corvid v0.5 Empirical Validation: Starscream WebSocket

**Exercise:** Translate the frame parser + handshake layer of [Starscream](https://github.com/daltoniam/Starscream) — a Swift WebSocket library — to Corvid v0.5.

**Why this exercise.** Ink covered structural types, protocols, sealed enums, and the two-type pattern. It didn't touch real state machines, contract-worthy invariants across a protocol implementation, async I/O concerns, or delegate-callback patterns. WebSocket framing is the smallest realistic surface that exercises all four.

**Scope discipline.** Frame parser + handshake only. No network I/O (Transport/), no compression (Compression/), no engine (Engine/), no server plumbing (Server/). This is the state-machine-heavy core, not the plumbing around it.

## Files

| File | Purpose | LOC |
|---|---|---|
| `01-Framer.source.swift` | Original Swift — frame construction and parsing (masking, opcodes, byte-level framing) | 365 |
| `02-FrameCollector.source.swift` | Original Swift — fragment accumulation state machine (opcode dispatch, close handling, UTF-8 validation) | 107 |
| `03-HTTPHandler.source.swift` | Original Swift — WebSocket upgrade handshake (protocol definitions, header struct, URL helpers) | 150 |
| `03a-FoundationHTTPHandler.source.swift` | Original Swift — Foundation-based concrete `HTTPHandler` implementation | 124 |

**Total source:** 746 lines of Swift across 4 files.

## Planned passes

Mirrors the Ink exercise structure.

1. **Parity pass.** Line-by-line translation into `.parity.cor` counterparts with inline `FRICTION:` notes. Produces `00-friction-log.md`.
2. **Idiomatic pass.** Rewrite the same files using Corvid idioms — sections, contracts, state-machine-as-synchronization, universal enumerations. Compare to parity output. Produces `04-idiomatic-pass.md`.
3. **Extended coverage** (if useful). Additional context the first two passes leave open — extending to `Compression/` for section-composition, or to `Engine/` for concurrency wiring.

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
