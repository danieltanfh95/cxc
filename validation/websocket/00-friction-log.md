# Friction Log — Starscream WebSocket Translation

**Status:** Skeleton. To be populated during the parity pass.

**Format** (per Ink exercise convention):

```
### Friction #N — <short title>

**Source:** `<file>.source.swift:<line>` — brief excerpt or paraphrase.
**Corvid attempt:** what you tried to write, or "no clear syntax."
**Category:** grammar | primitive | idiom | interop | contract | concurrency.
**Spec section(s) implicated:** §X.Y (and why).
**Blocking?** yes / no — can the translation proceed with a placeholder?
**Notes:** ambiguities, alternatives considered, related friction points.
```

## Categories to watch for

Ink's 38 friction points clustered around syntactic gaps (pattern matching §3.7, optionals §3, string interpolation §2, grammar §10). This exercise is designed to add gaps from different territories. Expected new clusters:

- **Contracts on protocol-satisfying methods.** Does `requires`/`ensures` compose with protocol conformance? What does the caller see?
- **Delegate-callback patterns.** Swift's `weak var delegate: FooDelegate?` — what's the Corvid idiom, and does the two-type pattern extend to delegates?
- **Enum-with-associated-data across error boundaries.** `Event` cases carrying `Error`, `Data?`, `String, UInt16` — does the parity translation stay ergonomic?
- **Byte-level manipulation.** Bitmask ops, opcode extraction, length encoding. Does Corvid have a good idiom for this or does it feel like C-in-a-costume?
- **Foundation type bindings.** `URLRequest`, `HTTPCookieStorage`, `URL` extensions, `Data`. Depth of interop, not just presence.
- **State-machine invariants.** `FrameCollector` has three invariants stated in comments and enforced by branches. Can they become `invariant` clauses?

## Running list

(empty — populated during translation)
