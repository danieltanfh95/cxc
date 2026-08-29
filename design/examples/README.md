# Examples

Reference implementations written in cxc. Two purposes:

1. **Design pressure.** Writing real code in the language surfaces spec gaps the friction log hasn't caught yet. See the Ink translation exercise in `../validation/` for the pattern.
2. **Demonstration.** Once a compiler exists, examples are what a newcomer runs first.

## Anchor example

**Game of Life** — multi-tier (Mac, iOS, Foundation-Swift server) sharing a protocol module. Scope and rationale in `../positioning/game-of-life.md`. Chosen to exercise:

- Section mechanism at realistic size
- Two-type pattern (struct-value + class-builder) across a domain
- Concurrency (channels, signals, state-machine sync)
- Multi-target build from shared source
- ObjC framework interop (AppKit, UIKit, Foundation)

## Status

`game-of-life/` will land here when either (a) the spec-gap sweep completes and the design is stable enough to write against, or (b) it is written as spec-pressure ahead of the sweep to force gap discovery.

**Landed spec-pressure examples:**

- [`hello-agent/`](hello-agent/) — minimum viable LLM agent harness. L7-aspirational (blocks on HttpClient plugin + JSON library + subprocess plugin); demonstrates atom-as-agent, composite `AgentTools` capability, and the L7 use-→ manifest-→ enforcement chain. Forcing function for L7 F74–F80.
- `game-i4-preview.cxc`, `game-toys.cxc` — pre-substrate; retained as historical spec-pressure sketches.

## Convention

One directory per example. Each directory self-contained with its own `README.md` explaining what it demonstrates and which spec sections it exercises.

Examples that don't compile yet should still land here — they document intent and drive spec work. Mark unbuildable examples with a `STATUS.md` naming what blocks them.
