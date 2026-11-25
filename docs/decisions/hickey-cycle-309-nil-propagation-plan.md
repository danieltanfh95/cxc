# hickey-cycle-309 — nil-propagation through operators

Scope memo. No implementation lands in this cycle. The goal is to
settle the design question around nil behaviour at operator sites so
future work on the `!` surface, the ergonomics of nullable types, and
the semantics of guard contexts all proceeds from a single agreed
frame rather than ad-hoc per-site decisions.

## Context

cxc went with nullable-by-default for types. Bare `T` is nullable
(envelope-wrapped, may hold nil). `T!` is non-nil. `T?` is banned
universally at the spec level (memory `corvid-nullability-rule`).

This is the opposite of Rust / TypeScript / Kotlin, which default
non-nil and opt into nullability with `?` / `Option<T>`.

The practical consequence today: every primitive-Int field or
parameter that must carry a real value needs the `!` suffix, because
the operators that act on Int (`+`, `-`, `*`, `>=`, `==`, `range()`,
container indexing) refuse the `[:optional :int]` envelope. Writing
`iters: Int = 0` yields a nullable Int that can't be passed to
`range()` or added to anything.

Observed in the game-of-life refactoring study. Stage 5 audit stripped
`!` from six Cursor / Grid reference positions where the compiler
didn't need the non-nil proof, but every `!` on primitive Int stayed
because the operators demand it. The `!` surface on primitives IS the
tell that cxc's operators don't handle nil.

## Current behaviour

Snapshot of cxc as of hickey-cycle-308f (2026-11-25).

### Type-level

`T!` lowers to the raw C representation (e.g. `int64_t` for `Int!`).
Bare `T` lowers to an envelope struct `{present: bool, value: T}` for
value types, or a nullable pointer for reference types.

### Operator surface

| Site | Accepts envelope? | Behaviour on nil input |
|---|---|---|
| `+ - * / %` on Int | No | Compile error (type mismatch) |
| `== != < <= > >=` on Int | No | Compile error |
| `and or not` on Bool | No | Compile error |
| `range(n)` | No | Compile error |
| `List.at(i)` i-param | No | Compile error |
| `Set.contains(e)` e-param | No | Compile error |
| `Map.get(k)` k-param | No | Compile error |
| Record field access `c.x` | Yes, when c is `Cursor` | Returns value from envelope |
| Atom message send | Yes, when target is nullable atom | Pointer dereference (unchecked) |

### Unwrapping path

`envelope ?? fallback` unwraps the envelope, substituting the fallback
when present is false. Users thread `??` through every call chain that
touches nullable values, materialising a non-nil at each spot the
operators demand.

### Observed consequence

Code that logically works on nullable types collapses into one of two
shapes: either (a) every type gets `!` so the compiler is satisfied, or
(b) every expression ends in `?? fallback` to materialise a value.
Both patterns bloat the surface. The nullable-by-default type-level
choice is undermined by non-nil-by-default operator-level behaviour.

## Proposed behaviour

Adopt nil-propagation at the operator level. nil in, nil out, through
every arithmetic, comparison, logical, range, and container-access
operator on primitive types. Guard contexts (`|:`, `||:`) treat
nil-valued predicates as not-firing, equivalent to false.

### Three-valued logic framework

Boolean operators extend to three values: true, false, nil (SQL's
UNKNOWN). The truth table:

```
and    | true  | false | nil
-------+-------+-------+-------
true   | true  | false | nil
false  | false | false | false   (short-circuits)
nil    | nil   | false | nil

or     | true  | false | nil
-------+-------+-------+-------
true   | true  | true  | true    (short-circuits)
false  | true  | false | nil
nil    | true  | nil   | nil

not nil = nil
```

Short-circuit semantics match standard two-valued logic. `false and X`
shortcuts to false, `true or X` shortcuts to true, in both cases
regardless of whether X evaluates to true, false, or nil. A nil LEFT
operand cannot short-circuit, because the evaluator doesn't know
enough to collapse the expression without evaluating the right side.
The nil row of the table shows what happens after the right side is
evaluated.

### Per-operator contracts

**Arithmetic (`+`, `-`, `*`, `/`, `%`).** Each operator lifts to
accept `Int | nil` and return `Int | nil`. nil in either operand
yields nil. `nil + 1 → nil`, `1 + nil → nil`, `nil + nil → nil`.
Division by zero stays a runtime error, not nil. The zero and
missing cases are distinct.

**Ordered comparison (`<`, `<=`, `>`, `>=`).** Returns Bool-or-nil.
nil in either operand yields nil. `nil >= 5 → nil`, `5 >= nil → nil`.

**Equality (`==`, `!=`).** Phase 1 (2026-11-25, 5d24acd) landed the
MATERIALISING reading, not the strict-Kleene reading originally
scoped here. `nil == nil → true`, `nil == 5 → false`,
`5 == nil → false`, `5 == 5 → true`. Kept because:

1. The existing emit (`:c-optional-binop :eq`, F39 L5b-ii-c) was
   already materialising when Phase 1 opened. Strict Kleene would
   require a flip across the Layer-5b lowering surface.
2. Prelude sites rely on `||: r == nil : raise ...` as a nil-check
   (e.g. `compiler/resources/prelude/target/shared/{fs,json}.cxc`).
   Strict Kleene (`r == nil → nil`) would skip these guards.
3. `.is_nil` (the memo's recommendation (a)) is available as the
   strict-Kleene reading for callers who want it, closing the hole
   `x == nil` would otherwise leave if propagation were strict.

The strict-Kleene reading survives as the v2.0 target: a parallel
rewrite of the `||: r == nil` prelude sites to `.is_nil` would
unblock flipping `:c-optional-binop :eq` without breakage.

**Logical (`and`, `or`, `not`).** Per the three-valued truth table.

**Guard contexts (`|:`, `||:`).** A guard whose predicate evaluates
to nil does NOT fire. Equivalent to false for the purpose of arm
selection. This is where the user-facing payoff lives: `|: c.gen >= self.gens -> end`
where `c` is nil yields `nil.gen → nil`, `nil >= self.gens → nil`,
guard reads nil, arm skipped.

**range(n).** If n is nil, returns nil. A nil-seq short-circuits every
chain method (map, filter, for_each) to nil / no-op. Alternative: nil
n yields the empty seq. Picking: nil-propagate for consistency.
for_each on nil-seq is a no-op, matching the empty-seq case.

**List.at(i).** Already returns envelope. If i is nil, return nil
envelope. Chain to `?? fallback` works unchanged.

**Set.contains(e).** If e is nil, return nil (not false). Arguably
false is reasonable — a nullable-elem set might or might not count
nil as "present" — but nil-propagation is the uniform rule.

**Map.get(k).** If k is nil, return nil envelope.

**Record field access `c.x`.** Already works on nullable c. nil
propagates: `nil.x → nil`.

**Method calls on atoms.** `nil.method(args) → nil` for sync `|>`.
For async `:>`, enqueue is a no-op on nil target.

### Interaction with the `!` type suffix

Under nil-propagation, `!` still carries meaning but shrinks in
necessity:

- **Compile-time proof.** `T!` is a type-level contract that the
  value is non-nil. The compiler emits raw (non-envelope) C for
  guaranteed-non-nil paths, skipping the per-operation presence
  check. Performance hint.
- **Reader signal.** `T!` tells the reader "this is definitely a
  real value, no nil path to consider." Documentation value.
- **Ctor discipline.** Record fields marked `!` must be set at
  construction. Fields marked `T` (nullable) default to nil.

Three positions where `!` would still matter:

1. Record field defaults. `iters: Int! = 0` guarantees iters is
   never nil. `iters: Int = 0` says iters starts at 0 but may become
   nil later (via explicit staging).
2. Performance-critical hot loops. `!` lets the compiler specialize
   to raw arithmetic without envelope dispatch.
3. API boundaries. Public observations returning `Bool!` vs `Bool`
   signal to callers whether a nil result is possible.

Elsewhere, `!` becomes optional. Removing it stops being a type
error. The type widens to nullable.

**`!` boundaries stay hard.** Passing a nullable value to a `!` param
still fails at compile time. There is no automatic lifting across the
boundary. The user must either (a) prove non-nil via `!` on the
source, (b) materialise with `?? fallback`, or (c) use the `.is_nil`
predicate to narrow in a guard. This preserves `!` as a real contract.
The surface shrinks because fewer sites NEED to be `!`, not because
`!` loses its teeth.

**`.is_nil` is non-propagating.** The predicate always returns `Bool!`.
This is the escape hatch from the Kleene equality propagation that
otherwise makes "is this nil" undetectable. See the open question
below for syntax options.

### Interaction with `??`

`??` stays. It materialises a concrete value from a nullable at a
specific point. Even under nil-propagation, `??` is useful at
boundaries where the user wants to commit to a definite value
(terminal output, explicit fallback, interop with a non-cxc API that
needs non-nil).

The difference from today: `??` becomes a stylistic choice at every
site rather than a requirement at every arithmetic operation.

### Interaction with flow typing

Flow typing (the compiler narrowing a type within a branch where a
null-check holds) becomes less critical under nil-propagation,
because operators handle nil gracefully. Still worthwhile as a
future enhancement:

```
|: c != nil
    # c is Cursor! within this arm
    use c.gen + 1 as raw Int, compiler emits unspecialized int64_t
```

Out of scope for this cycle.

## Migration path

Three phases.

**Phase 1: operator lifting.** Compiler changes at the emit stage.
Every Int-arithmetic site generates envelope-aware C code when either
operand's type is nullable. `cxc.lower` already has the type-kw to
dispatch; emit becomes conditional.

Expected churn: a broad scatter of call sites in `cxc.lower` around
arithmetic, comparison, logical, range, and container-access lowering.
Each operator class gets an envelope-aware emit path. Existing `!`
call sites keep the raw path. Real count TBD at implementation time.

**Phase 2: guard-context nil-as-false.** Transition arm matcher
treats nil-valued predicates as not-firing. One central change in the
guard-evaluation code path in lower / runtime.

Landed 2026-11-25 as regression pins; no new emit shape needed. The
2026-09-27 arm-head `:guard-cond-envelope?` flag (and its 2026-09-04
sibling `:cond-envelope?` on `:c-ternary` / `:c-if-chain-branch` /
`:c-while`) already expanded the guard to `.present && .value` for
Optional-Bool predicates, so envelope-nil (`.present=0`) degrades to
false at the C-truth-position boundary. Phase 1 operator lifting
(5d24acd) turned nullable-`>=` and similar chained comparisons into
Optional-Bool values routinely, so the pre-existing unwrap became the
guard-selection surface the memo scopes. Tests pin the flag at
`hc309-phase2-*` (lower, AST-level) and `end-to-end-hc309-phase2-*`
(build, executable) in the compiler test suite.

**Phase 3: remove unnecessary `!` from prelude + examples.** Audit
prelude `.cxc` files and existing examples. Drop `!` where the
compiler no longer requires it. Keep `!` where it signals intent or
performance-matters.

Phases are LOOSELY ordered. Phase 2 (guard nil-as-false) benefits
most after phase 1, because phase 1 is what generates nil-valued Bools
from comparisons like `c.gen >= self.gens`. Without phase 1, phase 2
handles only nil Bools that arrive from direct nullable Bool field
reads, which is a narrower slice. Phase 3 depends on both earlier
phases. Phase 1 is the biggest and unlocks the user-visible win.

## Scope decisions

### In scope

- Operator lifting on all primitive types (Int, Int8-64, UInt8-64,
  Float, Double, Bool, String).
- Guard-context nil-as-false for `|:` and `||:`.
- `range()`, container indexing, container membership nil-propagation.
- Record field access nil-propagation (already works).
- Documentation + spec update.

### Out of scope

- Collection element types (`List of Int` keeps current semantics; a
  nullable-Int list is distinct from a non-nil-Int list, and both are
  legal).
- Flow typing (future cycle).
- Changing the default from nullable to non-nil (that's the Rust /
  TS direction; cxc committed to nullable-by-default).
- Changing `??` semantics.
- Short-circuit variants (e.g. a new `and-strict` that errors on nil).
- Collection `.map` / `.filter` lambda invariants — the lambda body
  still receives element-typed values; nil-propagation applies at the
  operator level inside the lambda, not at the dispatch.

## Open questions

### Equality against nil

If `==` propagates, the user needs another way to ask "is this nil?".
The chosen form MUST return `Bool!` (non-nil Bool), otherwise we have
infinite regress on the propagation question.

Options:

**(a) A predicate form.** `x.is_nil` as a synthesised observation on
every nullable type. Reads clearly, no new syntax, parallels
Clojure's `nil?`.

**(b) A non-propagating equality.** `x === nil` or `is_nil(x)` as a
primitive operator or built-in that always returns `Bool!`.

**(c) Pattern dispatch.** `|: x as nil` (uses `|: as T` typed arm
syntax from spec §12).

Recommend (a). The method form reads naturally alongside existing
dotted observations and avoids new operator syntax. The `.present`
accessor on envelope lowered forms already exposes the same bit; `.is_nil`
is `not .present` with a user-facing name.

### Nil at comparison sorts

`List.sort_by(fn)` where fn returns nil for some elements. nil
positions become unordered relative to each other. Pick: nil-valued
keys sort to the end (SQL `ORDER BY ... NULLS LAST` default) or
throw. Lean toward "nil keys sort to end" for ergonomics.

### Concatenation / string formatting

`"gen " + nil` or `"gen " + c.gen.to_string()` where c is nil. The
string concatenation sees nil. Propagate to nil? Produce "gen "? Or
"gen nil"? Lean toward propagation: nil out from `.to_string()` on
nil, nil from the concat. Downstream `console.write(nil)` becomes a
no-op (or writes nothing).

### Division by zero

Stays a runtime error, distinct from nil. `5 / nil → nil`, but
`5 / 0 → runtime error`. The zero and missing cases carry different
semantics; conflating them would hide bugs.

### Container element nullability

`List of Int` has nullable-Int elements. Does `.sum()` propagate?

Consistency with the general operator rule says yes. `.sum()` reduces
with `+`, and `nil + 1 → nil`, so any nil element forces the sum to
nil. Users who want skip-nil semantics compose explicitly:
`list.filter(e => not e.is_nil).sum()`.

The alternative (SQL-style skip-nil as the aggregate default) would
carve an exception to the general rule and create a teaching surface
("most ops propagate, aggregates skip, remember which is which").
Rejecting the exception for consistency, even though SQL's default is
ergonomic for data-analysis workloads.

`.count()` returns the full length including nil-valued elements,
because `.count()` is a cardinality query on the container, not a
reduction over the element values.

## Non-goals

- Changing the ban on `T?`. `T?` as syntax stays banned; bare `T` IS
  the nullable form.
- Changing exception / error-record semantics. Errors are typed
  records per memory `corvid-cap-typed-errors`. nil is not an error
  signal.
- Adding `Maybe` / `Option` as a first-class type. cxc's envelope IS
  the Maybe. No new monadic plumbing.

## Recommendation

Approve the design as above. Land in three phases over subsequent
cycles. Phase 1 (operator lifting) is the biggest and most valuable;
phase 2 (guard-context nil-as-false) is small and unlocks the
`c.gen >= self.gens` reads-as-false scenario the game-of-life
refactor surfaced. Phase 3 (prelude `!` audit) is documentation and
ergonomics.

Pre-approval check for v2.0 planning: does the project want to also
reconsider the nullable-by-default call? If v2.0 flips to non-nil-by-
default, this memo becomes less load-bearing because `!` disappears
from the type surface entirely. Current recommendation: keep
nullable-by-default (it's simpler to reason about absence as a
first-class value), and land nil-propagation to recover ergonomics.
That's the path with the lightest spec churn.

## References

- memory `corvid-nullability-rule` — the `T` nullable / `T!` non-nil /
  `T?` banned convention
- memory `corvid-envelope-over-sentinel` — the design preference for
  envelope + tag over sentinel-value-lifting
- memory `corvid-cap-typed-errors` — errors are typed records, nil is
  not an error signal
- `docs/rosetta/refactoring-studies/game-of-life/` — stage 5 audit
  that surfaced the `!` floor
- `hickey-cycle-306-ffi-plan.md` — prior design memo in the same
  decisions genre
