---
name: cxc-authoring
description: Guidelines for writing Corvid (`.cxc`) source. Invoke before authoring or refactoring `.cxc` code, or when reviewing a cxc PR.
---

# cxc-authoring

Load this skill before writing or refactoring Corvid source. The
authoring guide lives in `docs/writing-corvid/` in the corvid repo.
This skill points at it, then surfaces the highest-leverage rules
and patterns inline so you don't have to context-switch for common
cases.

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
- **Numeric fields used in arithmetic or comparison are safest as `T!`.** Comparison of nullable `Int` in a guard position (`|: i < limit`) is supported as of 2026-09-27: the compiler unwraps the resulting Optional<Bool> as `.present && .value`, so a nil operand degrades the guard to `false`. Arithmetic on nullable Ints still produces `_Cxc_Optional_Int`, which callers must terminate with `?? default` before comparing. Prefer `T!` when you never intend nil; use `T` when a field is legitimately absent-until-first-write.

### Reserved words (do not use as identifiers or enum cases)

```
module from import as def use invariants accepts extend replace
deprecated then do when let alias self nil true false and or not
is in of if else return case function for weak unowned while loop
break continue raise
```

Common trap: `weak` and `unowned` are reserved (data-field reference modifiers). Rename to `low_hp` / `fragile` / `frail` etc. `case weak` in an enum triggers a parse error at the case line.

### `let` vs `alias`

- Both bind a name to an expression inside a `then:` or observation body.
- **`alias name :- expr`** is a pure-expression compile-time name. Preferred inside `then:` blocks and observation bodies. No lowering hazards.
- **`let x: T = expr`** is a runtime binding. Supported at statement position inside a `then:` block and at module scope, including chained lets referenced from `field => <expr-using-let>` updates (fixed 2026-09-27; typed lets are pre-registered into ctx before `:updates` lower, and do-stmts emit before then-stmts so C-level scope covers staged assignments). Chained `let` inside expression position (e.g. inside a `sort_by` comparator) still hits lowering gaps.
- Rule of thumb: reach for `alias` first inside `then:` — it stays purely compile-time and composes cleanly. Reach for typed `let x: T = ...` only when a runtime binding is required.

### Observations (`:-`) are atom-only, and never carry a `:Type` before `:-`

Observations live inside `def <AtomName>:` or `def <RecordName>:` bodies. They read pre-image state and return a value on demand. Legal shapes as of v1.0 (all compile-verified):

```corvid
# nullary observation, inferred return type
total_processed :- processed

# nullary observation, explicit return type on same line
total_processed :- Int = processed

# observation with args, inferred return type
plus(other: Int) :- n + other

# observation with args, explicit return type — return type goes AFTER `:-`,
# with the body on the next indented line
min_of(x: Int, y: Int) :- Int
    ||: x < y : x
    ||: else : y
```

**Common wrong shape**: `min_of(x: Int, y: Int): Int :- ...` (colon-return-type BEFORE `:-`). The parser rejects this because it looks like a field-decl with a `(...)` tuple type. The return type belongs AFTER the `:-` glyph, not before it.

**Also illegal**: `name: Type :- body`. Adding a `:Type` annotation to a nullary observation without an arg list conflates it with a field-decl `name: Type = default`. Either use the inline explicit form (`name :- Type = expr`) or drop the type.

**Compiler limitation**: observation args declared as `Int!` currently lower with `int64_t` params but call sites pass `_Cxc_Optional_Int64`, producing a C-level type-mismatch. If the args are numeric, declare them `Int` (nullable) and unwrap with `?? 0` at the use site.

Also illegal: observations inside `def Record` bodies. Records hold data, atoms hold behavior. If you find yourself wanting a derived value on a record, either (a) move the record to be an atom's field and define the observation on the atom, or (b) inline the derivation at each call site.

**Top-level free functions do not exist at v1.0.** Only `def <AtomName>:`, `def <RecordName>:`, `def <EnumName>:`, `def main:`, and `def plugin:` are valid at module scope. Writing `def helper(x: Int!, y: Int!): Int! :- ...` at the top level is a parse error at the second type annotation (`expected :newline (got :ident "Int")`). Move the helper into the atom that needs it as an observation, or inline the `||: guard : body` chain at the call site.

### Sub-atoms live as fields, not `let`-locals

```corvid
# WRONG — local scope drops the atom before its lifecycle runs
def main:
    -> running
        then:
            let c: Counter = Counter(limit: 5)   # dies with the block
            |> c.run()

# RIGHT — atom as a field of the outer atom
def main:
    c: Counter! = Counter(limit: 5)
    -> running
        then: |> c.run()
    running <: c.finished -> end
```

For simple tasks where a sub-atom isn't earning its keep, keep all state and transitions in a single `def main:` atom.

### RNG semantics

- `SeededPrng(seed: N)` is a **Park-Miller LCG** (a=16807, m=2^31-1). NOT Python's MT19937.
- Byte parity with Python's `random.*` is impossible at the RNG level. Match a reference Corvid impl or a semantic property, not Python's stream.
- Methods: `.below(n)` returns Int in `[0, n)`. `.next_u31()` returns Int in `[1, 2^31-2]`.
- `Random` is CSPRNG-only. `SeededPrng` does not conform to `Random`.

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
beyond a one-liner. The pipeline:

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
generated snippet, either compile it yourself or say up-front that
you haven't.

## Companion skills

- [cxc-cleanup-audit](../cxc-cleanup-audit/SKILL.md) walks an
  existing `.cxc` file against the 25 anti-patterns catalogued in
  the writing-corvid guide. Invoke it before a landing PR, or when
  a file has grown large enough that stale patterns may have
  accumulated. Where this skill tells you what to write, cleanup-audit
  tells you what to look for and remove.
