# Examples

Reference implementations written in cxc.

1. **Design pressure.** Writing real code in the language surfaces spec gaps that smaller exercises miss. Each example lands with a short note on what it stressed.
2. **Demonstration.** Once a compiler exists, examples are what a newcomer runs first.

## Landed examples

- [`hello-agent/`](hello-agent/), minimum viable LLM agent harness. L7-aspirational, blocks on a third-party `Claude` plugin shape (spec §1.12). Prelude `Http`, `Json`, `Subprocess`, `EnvVars` capabilities have already landed. Forcing function for L7 F74 through F80.

## Convention

One directory per example. Each directory self-contained with its own `README.md` naming what it demonstrates and which spec sections it exercises.

Unbuildable examples that document intent still land here, name the blocking landings inline in the example's own `README.md`.
