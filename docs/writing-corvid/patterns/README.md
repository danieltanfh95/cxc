# Corvid pattern examples

Each `NN-<name>.cxc` is a short, self-contained atom program that compiles and runs. The matching `NN-<name>.expected` file is its stdout, captured by `./verify.sh`. Use these as building blocks for LLM authoring bundles — pick the ones that cover the patterns your target task uses.

Verified 2026-09-30 with cxc from this repo.

| # | pattern | file | what it teaches |
|---|---|---|---|
| 01 | single atom | [01-single-atom.cxc](01-single-atom.cxc) | atom, `=>` staging, snapshot-transaction (print new value = `n+1` not `n`), named-message dispatch |
| 02 | cross-atom send | [02-cross-atom.cxc](02-cross-atom.cxc) | atom-holds-atom via ctor param `def Doubler(target: Sink!)`, `|>` cross-atom sync send |
| 03 | multi-arm expression | [03-multi-arm-expr.cxc](03-multi-arm-expr.cxc) | `let x = ` \| : guard : body \| : else : fallback for guarded value expressions, div-by-zero avoidance |
| 04 | enum + record + guard dispatch | [04-enum-record-dispatch.cxc](04-enum-record-dispatch.cxc) | `def Kind: case a case b`, record with typed fields, one `<:` arm per case, wildcard arm at end, `.for_each` lambda |
| 05 | container methods | [05-container-list.cxc](05-container-list.cxc) | `List of T` functional methods `.append`, `.filter`, `.count`, `.for_each`; no imperative mutation |

## When to include which

**Every bundle: at least 01.** Single-atom shape is load-bearing for any Corvid code.

**Multi-atom task: include 02.** Any task with more than one class of state.

**Task that computes a value that could be undefined (division, lookup, else): include 03.** Prevents Python-shape `if/else` sprawl inside transitions.

**Task with enum-tagged data: include 04.** Otherwise the model will reach for string-tag dispatch, which is Python-shape.

**Task that maintains a list of values: include 05.** Otherwise the model will reach for imperative `.add()` / `.remove()` calls that Corvid does not have.

## Verification

```bash
cd docs/writing-corvid/patterns
bash verify.sh
```

Recompiles every `.cxc`, runs it, and diffs against the `.expected` capture. Non-zero exit indicates a regression, either in the example or in the compiler.

## Consumers

- [bench/cxc-agent-baseline/prompts/cxc-tight-bundle.md](../../../../bench/cxc-agent-baseline/prompts/cxc-tight-bundle.md) uses 01 for T1-shape tasks.
- [bench/cxc-agent-baseline/prompts/cxc-tight-bundle-2ex.md](../../../../bench/cxc-agent-baseline/prompts/cxc-tight-bundle-2ex.md) uses 01 + 02 for T2-shape tasks.
- Add more targeted bundles by concatenating a subset of these examples with the six-rule cheat sheet from the tight bundles.
