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
3. [docs/writing-corvid/04-common-mistakes.md](../../../docs/writing-corvid/04-common-mistakes.md): what to avoid and why.

[docs/writing-corvid/03-design-patterns.md](../../../docs/writing-corvid/03-design-patterns.md) is an **opt-in reference**, not a reading-order step. Consult a specific pattern only when a specific problem in front of you needs it. Do NOT scan it upfront and apply patterns preemptively — the patterns exist to solve real problems, not to structure a first draft.

For an experienced author looking for a specific answer, jump
straight to (2) or (3).

## The Zen of Corvid

These are the values the language is built around. Read them before you start; the patterns below all fall out of these principles. If a decision feels ambiguous, come back here.

1. **Atoms are modules.** State lives inside; access from outside is only through named observations (`:-`) and transitions (`<:`). No field is publicly reachable. This is the encapsulation that Clojure lacks.
2. **Every state change has a name.** All mutation flows through `<: msg -> phase` arms. The full audit surface for what can change an atom's state is `grep '^\s*<:'` on its body. Keep it small enough to enumerate.
3. **Derive, don't cache.** Observations are pure functions of current state, re-computed on read. No cache-then-invalidate machinery. If you're writing a dirty-flag or a memo, the design is wrong — hoist into an observation or split the atom.
4. **One atom until pressure.** Default to a single `def main:` atom holding all state and transitions. Split only under specific pressure: (a) RNG or long-lived state that must persist across observations, (b) an external lifecycle whose phases signal completion (`fs.open`, subprocess, socket), (c) a capability boundary shared by three or more transitions.
5. **Verbs are transitions, not atoms.** `Bidder`, `Deployer`, `Combat`, `Placer`, `Auction` are transitions on the atom that owns their state — never their own atoms. A strict-layered DAG of verb-atoms (`L0 Card / L1 Bidder / L2 Auction`) invented to dodge the `|>`-to-same-atom rejection is the cathedral anti-pattern. Fold callees back into transitions on the caller.
6. **Records hold data. Atoms hold behavior.** No observations inside `def Record:` bodies. If you want a derived value on a record, either move the record onto an atom as a field and put the observation there, or inline the derivation at call sites.
7. **Explicit capabilities are the whole point.** `def plugin:` for IO, RNG, subprocess, HTTP is not overhead — it's the value the language delivers. A translation that calls `print` and `SeededPrng` directly is legal but wastes the capability model. When there's an IO surface, prefer wrapping it as a plugin so the caps are enumerable.
8. **Fail loudly at the boundary.** Fallible capabilities raise typed records (`IOError`, `HttpError`, `ShellError`, `EnvError`, `JsonError`, `RandomError`). Recover at the call site with `or T():`. No `Result[T, E]`, no `try / catch` — the recovery is where the caller's context knows what fallback means.
9. **Named-Result records are a smell.** If your emit contains `FooResult`, `BarResult`, `QuuxResult` per function, the atom is doing too much and needs a real split (see 4), or the caller should be using `or T(): fallback` instead of unpacking a Result tuple. Corvid has no `Result[T, E]` on purpose.

The value story Corvid pitches (locality, auditable transitions, observation decoupling, explicit capabilities) emerges from writing transitions, observations, and plugins according to these nine values — not from architecture drawings.

## Verification workflow

**Compile before you commit.** The parser rejects more than the type checker. The productive loop is `cxc compile` → read the diagnostic → fix the exact line. Compiler messages teach the fix inline; read them literally, don't reinterpret.

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

Observations live only inside atom bodies (`def <AtomName>:` or `def main:`). They read pre-image state and return a value on demand. Legal shapes as of v1.0 (all compile-verified):

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

Applies only when you have already decided a sub-atom is needed (see "Starting shape" above — the default is one main atom). If you have decided, place the sub-atom as a field of the parent, not a `let`-local:

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

### RNG semantics

- `SeededPrng(seed: N)` is a **Park-Miller LCG** (a=16807, m=2^31-1). NOT Python's MT19937.
- Byte parity with Python's `random.*` is impossible at the RNG level. Match a reference Corvid impl or a semantic property, not Python's stream.
- Methods: `.below(n)` returns Int in `[0, n)`. `.next_u31()` returns Int in `[1, 2^31-2]`.
- `Random` is CSPRNG-only. `SeededPrng` does not conform to `Random`.

### Data before code

- Define the record before writing the transition.
- If a transition body reaches for a value not on any record, stop and put it on one.
- Multi-layer data-contract changes lead with a "before/after data shapes" table, then implementation.

### Readable names

- Atom, record, enum, transition, observation, field, and parameter names flow verbatim into compile diagnostics, `grep '^\s*<:'` audit queries, `cxc trace` CLI reports, and the browser trace viewer. The debuggability dividend Corvid pitches depends on these names being readable at the trace surface, not just inside the source file.
- Prefer full words. `Rng` / `Board` / `Draw` / `Effects` / `Combat` over `Mt` / `Bd` / `Dr` / `Fx` / `Cb`. `cell_code` over `cc`. `is_matched_idx` over `imi`.
- Message names (the identifier after `<:`) land as the `msg` field of every `send` / `recv` event. `apply_deploy_ability` reads cleanly in the trace. `ap_d_a` reads as noise.
- Shortening to save tokens or lines costs more than it saves. A 780-line emit with `Mt` / `Bd` / `Dr` reads as `Mt[4377759824] → Bd[4377762448]` in the trace viewer, which is unreadable noise. Rename once and the whole audit surface comes back.

### Enums vs data

- If enum cases describe *what a specific entity does* (`Ability.axe_king_bonus`), collapse to effect-as-data on the entity.
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
- Prefer a fall-through arm to exhaustive-guard listing (additive extension).

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

### Parser gotchas — read the compiler message

The compiler teaches the four most common parser rejections inline at failure time. Read the diagnostic literally. Common ones and what the compiler now says:

- **Empty list `[]` in assignment position** (`field => []`, `let xs: List of T = []`): compiler says "Empty list literal `[]` has no element type … use `<field>.empty()`". Do that.
- **Multi-line expression without paren wrap** (trailing `+`, `-`, `and`, `or` at EOL): compiler says "binary operator `+` at end of line without a right-hand operand … wrap the whole expression in parens to continue on the next line". Do that.
- **`let x: T =` with newline before RHS**: compiler says "expected an expression, got a newline … keep the RHS on the same line as `=`, or hoist the value into an atom observation". Do that.
- **Wrong observation shape `v: T :- body`**: compiler says "`:Type` annotation belongs AFTER `:-`, not before it … Rewrite: `v :- T = body`". Do that.

If the compiler message is unclear on a specific case, the shape is probably novel — file it, don't invent a workaround.

## Compile-verify discipline

Verify against the compiler, not the spec, when writing anything
beyond a one-liner. The pipeline:

```bash
cxc compile path/to/your.cxc /tmp/out && /tmp/out/<binname>
```

That's the whole loop for a one-file emit. Read the `stderr` diagnostic on failure and fix the exact line.

**Byte-parity targets.** When the task has a reference implementation whose stdout you must match, the productive loop is `sed → compile → diff`, not just `compile`. Codex-2-v18 hit 19/19 byte parity on a 1400-line Python-to-Corvid translation using exactly this shape:

```bash
# swap seed literal, compile, run, diff against oracle
sed 's/SeededPrng(seed: [0-9]*)/SeededPrng(seed: 8)/' cards.cxc > /tmp/s8.cxc
cxc compile /tmp/s8.cxc /tmp/out && /tmp/out/cards > /tmp/mine.out
diff /path/to/reference/seed-8.out /tmp/mine.out | head
```

Iterate until the diff is empty on 3+ non-verifier seeds before emitting. Reading the compiler internals or the reference implementation to derive the trace format is a failure mode. The oracle diff *is* the spec.

Every example in the writing-corvid guide is verified this way
before it ships. When you propose Corvid code in a review or a
generated snippet, either compile it yourself or say up-front that
you haven't.

**Structural self-audit via `cxc trace`.** After the emit compiles and passes its byte-parity or equivalent gate, run a trace pass to verify the atom-topology matches your intent:

```bash
cxc compile cards.cxc /tmp/out --trace
/tmp/out/cards > /dev/null 2> /tmp/cards.trace.jsonl
cxc trace /tmp/cards.trace.jsonl
```

The `cxc trace` output shows the atoms present at runtime, the top-N message-passing edges between them, and topology counts. Read it against Zen items 4 (one atom until pressure), 5 (verbs are transitions), and 7 (explicit capabilities): a 1-atom monolith is Clojure-in-Corvid-clothing (item 4 satisfied, items 5/7 possibly under-used); a 9-layer DAG with atoms per verb is the cathedral anti-pattern (item 5 violated). If your trace shows either extreme, revisit the design before shipping.

For deep compiler-internals debugging where you need the Clojure build API directly, drop into `cd compiler && clojure -M -e '...'` with `cxc.build/compile-source-to-file`. The shell `cxc compile` path covers 95% of authoring work.

## Companion skills

- [cxc-cleanup-audit](../cxc-cleanup-audit/SKILL.md) — post-hoc anti-pattern scan for an existing `.cxc` file. **Invoke only after** the file compiles cleanly and passes its byte-parity or equivalent gate, and has grown past ~500 lines. Do NOT invoke upfront during authoring: the checklist primes for elaborate refactoring loops that don't help small first drafts, and often triggers premature structural decomposition.
