# Corvid pattern examples

Each `NN-<name>.cxc` compiles and runs standalone. `./verify.sh` recompiles every example and diffs against the `.expected` capture. Pick the ones that cover the patterns the target task uses.

| # | pattern | file | what it teaches |
|---|---|---|---|
| 01 | single atom | [01-single-atom.cxc](01-single-atom.cxc) | atom, `=>` staging, snapshot-transaction (print new value = `n+1` not `n`), named-message dispatch |
| 02 | cross-atom send | [02-cross-atom.cxc](02-cross-atom.cxc) | atom-holds-atom via ctor param `def Doubler(target: Sink!)`, `|>` cross-atom sync send |
| 03 | multi-arm expression | [03-multi-arm-expr.cxc](03-multi-arm-expr.cxc) | `let x = ` \| : guard : body \| : else : fallback for guarded value expressions, div-by-zero avoidance |
| 04 | enum + record + guard dispatch | [04-enum-record-dispatch.cxc](04-enum-record-dispatch.cxc) | `def Kind: case a case b`, record with typed fields, one `<:` arm per case, wildcard arm at end, `.for_each` lambda |
| 05 | container methods | [05-container-list.cxc](05-container-list.cxc) | `List of T` functional methods `.append`, `.filter`, `.count`, `.for_each`. No imperative mutation |

## When to include which

**Every bundle: at least 01.** Every Corvid program needs the single-atom shape.

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

- Build a targeted authoring bundle by concatenating a subset of
  these examples with a short rule cheat sheet. Pattern 01 covers
  single-atom shape, 02 covers cross-atom, 03 multi-arm expressions,
  04 enum-record dispatch, 05 container iteration. Pick the two or
  three that match the task shape.
