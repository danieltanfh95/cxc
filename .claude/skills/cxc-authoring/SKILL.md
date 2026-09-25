---
name: cxc-authoring
description: Guidelines for writing Corvid (`.cxc`) source. Invoke before authoring or refactoring `.cxc` code, or when reviewing a cxc PR.
---

# cxc-authoring

Load this skill before writing or refactoring Corvid source. The
canonical authoring guide lives in `docs/writing-corvid/` in
the corvid repo. This skill points at it, then surfaces the highest-leverage
rules and patterns inline so you don't have to context-switch for
common cases.

## When to invoke

- Writing new `.cxc` code from scratch.
- Refactoring an existing `.cxc` file (renaming, restructuring, cleaning up).
- Reviewing a Corvid PR for shape, not just syntax.
- Answering "how do I express X in Corvid" for a specific X.

Do **not** invoke for pure compiler-internals work (Clojure code
under `compiler/src/`). That's a separate concern with its own
conventions.

## Reading order for a fresh session

For a first-time reader (agent or human):

1. [docs/writing-corvid/01-your-first-atom.md](../../../docs/writing-corvid/01-your-first-atom.md): the vocabulary.
2. [docs/writing-corvid/02-language-tour.md](../../../docs/writing-corvid/02-language-tour.md): the reference.
3. [docs/writing-corvid/03-design-patterns.md](../../../docs/writing-corvid/03-design-patterns.md): architectural patterns to reach for.
4. [docs/writing-corvid/04-common-mistakes.md](../../../docs/writing-corvid/04-common-mistakes.md): what to avoid and why.

For an experienced author looking for a specific answer, jump
straight to (2) or (4).

## The rules that catch the most bugs

Inline pointers for common decisions. For rationale and examples,
open the linked doc section.

### Nullability

- `T` = nullable, `T!` = non-nil, `T?` is **banned everywhere**.
- Reach for `T!` unless `nil` is a legitimate state of the field.

### Data before code

- Define the record before writing the transition.
- If a transition body reaches for a value not on any record, stop and put it on one.
- Multi-layer data-contract changes lead with a "before/after data shapes" table, then implementation.

### Enums vs data

- If enum cases describe *what a specific entity does* (`Ability.axe_king_bonus`), collapse to effect-as-data on the entity. See [03-design-patterns.md#effect-as-data-over-enum-of-behaviours](../../../docs/writing-corvid/03-design-patterns.md).
- Enum cases should name kinds of behavior, not identities of behavers.

### Transitions and staging

- `=>` inside `then:` is **snapshot-staged**, not incremental. The body reads pre-image throughout; updates commit at end.
- One `=>` per field per transition path. Multiple `=>` on the same field is smuggled assignment; fold into one expression.
- To print the new value: `print(field + delta)`, not `field => field + delta; print(field)`.

### Sends

- `|>` = sync (blocks). `:>` = async (enqueues).
- `|>` to the same atom on the same thread is rejected at runtime. Use `self :> msg` for self-sends.
- Async payload uniformity: if two arms emit `:> event(payload:)`, the payload types must match across all arms.

### Guards

- `|:` for transition guards, protocol invariants, `requires`/`ensures`. Pattern-match dispatch still uses `when:`.
- Multiple guarded arms on the same `phase <: msg` are legal. A trailing unguarded arm is the vanilla case.
- Prefer a fall-through arm to exhaustive-guard listing (additive extension). See [03-design-patterns.md#fall-through-arm-as-extension-point](../../../docs/writing-corvid/03-design-patterns.md).

### Containers

- FP-only. `.append`, `.filter`, `.assoc`, `.dissoc`, `.insert`, `.without`. No `.add` / `.remove`.
- Reads that may miss: `.at(i) ?? fallback`, `.get(key) ?? fallback`.
- Iteration is `xs.for_each(x => ...)`. There is no `for x in xs:`.

### Async

- Async in Corvid is atom lifecycles. No `Future[T]` / `Promise[T]` types.
- `fs.open` returns a `File` atom whose phases signal completion.
- Subscribe to a sub-atom's messages via `<:` arms on the parent.

### Capabilities

- Every fallible capability raises typed records (`IOError` / `HttpError` / `ShellError` / `JsonError` / `EnvError` / `RandomError`).
- Recovery is at the call site: `alias x :- fs.read(...) or IOError(: fallback)`.
- No `Result[T, E]`. No `try / catch`.

### What Corvid does not have

If you find yourself reaching for one of these, look for the shape it's meant to replace:

- **User-defined operators**: use named methods.
- **Annotations `@name(args)`**: inferred from body shape; deprecation goes in comments.
- **Shape variables / generics on user types**: containers monomorphize.
- **Global mutable state**: mutation lives in atoms.
- **`if X: Y else: Z` as expression**: use `||: guard : body` chains.
- **`switch` / `case`**: use transitions + `|:` + variant patterns.
- **`for x in xs:`**: use `xs.for_each(...)`.
- **Imperative container mutation**: FP methods only.

## Compile-verify discipline

Verify against the compiler, not the spec, when writing anything
non-trivial. The pipeline:

```
cd compiler
clojure -M -e "
(require '[cxc.build :as b])
(let [src (slurp \"path/to/your.cxc\")
      out \"/tmp/verify-out\"]
  (clojure.java.io/make-parents (str out \"/x\"))
  (let [r (b/compile-source-to-file src \"verify\" out)]
    (println (:status r))
    (when (seq (:stderr r)) (println (:stderr r)))
    (when (zero? (:status r)) (println (:stdout (b/run-binary (:bin-path r)))))))
"
```

Every example in the writing-corvid guide is verified this way
before it ships. When you propose Corvid code in a review or a
generated snippet, either compile it yourself or say clearly that
you haven't.

## Companion skills

None currently. If you're doing a bulk audit or refactor of an
existing `.cxc` file, an "cxc-cleanup-audit" skill would sit here
next to this one; the authoring guide's patterns become the audit's
checklist. Not yet built.
