# Common mistakes

Failure modes worth naming, each with the shape that catches them.

## Nullability confusion

`T`, `T!`, and (banned) `T?`.

- `T`: nullable. May be `nil`. Reads may need `?? fallback`.
- `T!`: non-nil. The compiler enforces. Passing `nil` is a compile error.
- `T?`: **not a legal form.** Never write it.

The trap: reading `T` (nullable) as if it were `T!`. If a field is
nullable and you dereference `.field` on it, that read is guarded by
Objective-C nil-messaging (returns 0/nil), which is often not what
you meant. Either declare `T!`, or use `?? fallback` at every read.

## Forward-reference in record definitions

Records lower to C struct typedefs, in file order. A record that
references another record must come *after* the record it references:

```
# Wrong: Card sees Effect before Effect is defined.
def Card:
    on_deploy: Effect!

def Effect:
    kind: EffectKind
    ...
```

```
# Right: Effect above Card.
def Effect:
    kind: EffectKind
    ...

def Card:
    on_deploy: Effect!
```

The error message when this bites is a C-level "member reference base
type is not a structure or union." Re-order.

## `|>` cycle to the same atom

Sync send to yourself, or a sync chain that comes back to yourself
on the same thread, is rejected at runtime:

> sync-send to atom `X` while a prior sync-send to the same atom on this thread is still awaiting reply

The fix is `self :> msg` (async self-send) or a redesign where the
target arm doesn't need to call back.

## `=>` in `then:` is snapshot-staged, not incremental

The body of `then:` reads the pre-transition snapshot of every
field, throughout. `=>` stages the new value; the commit happens at
the *end* of the body.

```
idle <: increment -> idle
    then:
        count => count + 1
        print(count)      # prints the OLD count
```

To print the new value: `print(count + 1)`. To reuse the new value
in the body: bind a `let` local first.

Also: **one `=>` per field per transition path.** Multiple `=>` on
the same field on one path is smuggled assignment; the compiler
rejects it. Fold into one expression or split via `let`.

## Same-atom sync-send between guarded arms

If arm A of atom X sends `|> X.other()` synchronously, and X is the
receiver, the second dispatch is rejected. Even if the arms are
different messages. The substrate's single-thread guarantee wants
atom X's dispatch table entered exactly once at a time.

Workarounds:
- Refactor the callee's work into an observation (pure read, no dispatch).
- Send async: `self :> other()`.
- Move the callee's work into the caller's own body.

## List literal in an observation body

```
others(idx: Int!) :- ||: idx == 0 : [1, 2]
                     ||: idx == 1 : [0, 2]
                     ||: else     : [0, 1]
```

The compiler will fail with `infer-type :list` because there's no
type context to determine the list's element type. Fix: hoist to
typed fields:

```
_others_of_0: List of Int = [1, 2]
_others_of_1: List of Int = [0, 2]
_others_of_2: List of Int = [0, 1]

others(idx: Int!) :- ||: idx == 0 : _others_of_0
                     ||: idx == 1 : _others_of_1
                     ||: else     : _others_of_2
```

## Imperative container mutation

Retired everywhere. All container mutation is FP:

| Wrong | Right |
|---|---|
| `xs.add(v)` | `xs => xs.append(v)` |
| `xs.remove(v)` | `xs => xs.filter(x => x != v)` |
| `m.put(k, v)` | `m => m.assoc(key: k, value: v)` |
| `m.delete(k)` | `m => m.dissoc(key: k)` |
| `s.add(x)` | `s => s.insert(x)` |
| `s.remove(x)` | `s => s.without(x)` |

Reads on List/Map that might miss: `.at(i) ?? fallback`,
`.get(key) ?? fallback`.

## Ability-flag enum trap

If you find yourself with an enum whose cases describe *what a
specific entity does* (`Ability.axe_king_bonus`,
`Ability.marksman_pierce`), you're in the enum-of-behaviours trap.
See [03-design-patterns.md](03-design-patterns.md#effect-as-data-over-enum-of-behaviours).

## Parametric state via parallel fields

If you catch yourself declaring `count_0`, `count_1`, `count_2` for
"one per lane," stop. `=>` is compile-time; you can't pick which
field to stage at runtime. Model as a sub-atom instead. See
[03-design-patterns.md](03-design-patterns.md#parametric-state-via-sub-atoms-not-n-parallel-fields).

## Observation return type ambiguity

An observation that composes another observation on the same atom
sometimes needs a declared return type for the compiler to resolve
the composition. If you get a resolution error and the observation
is single-line, add an explicit return type:

```
# Might fail without the return type.
grand_total: Int! :- self.subtotal() + self.tax()
```

## Payload type must be uniform across arms emitting to the same subscriber

If two arms of atom X emit `:> event(payload: ...)` and the payload
types differ (say, one emits `payload: Card`, another emits
`payload: Card?`), subscribers can't be typed consistently. The
compiler will reject the emit-site with a type mismatch. Fix by
making the payload uniformly nullable or uniformly non-nil across
all arms, typically by using a sentinel record instead of `nil` (see
the sentinel-field pattern in [03-design-patterns.md](03-design-patterns.md#sentineldefault-records-as-atom-fields)).

## Wildcard on-enter doesn't fire on self-loops

A wildcard arm `-> phase then: body` fires on *phase change*, not on
self-loops within the same phase. If an arm does `phase <: msg ->
phase`, the wildcard doesn't re-fire. This is deliberate (prevents
recursion) but occasionally surprises when you want the wildcard's
side effect after every message.

Fix: move the side effect into the specific arm's `then:` body.

## `??` on a bare non-nullable value

`native_fn() ?? default` is safe: the compiler recognises the LHS is
non-nullable and compiles the fallback as dead code. `??` is a
non-error when the LHS can't be nil, but you should still audit for
places where you *thought* the LHS was nullable and wasn't.

## Subprocess env, Random, and other capabilities

- `subprocess.run(cmd, env: .empty)` (or `.inherit` / `.merge({...})`). There is no default env; you must specify.
- `Random` is CSPRNG-only. For fast non-crypto RNG use `SeededPrng(seed:)` which does *not* conform to `Random`.
- `EnvVars` (read-only, total) and `EnvMut` (write, raises `EnvError`) are separate capabilities. Don't pass `EnvMut` to consumers that only need to read.

## Comment discipline for AI/LLM handoff

If you're passing Corvid source to an external LLM for review, strip
the comments first. Comments carry framing that biases the reviewer
toward your interpretation; you want unbiased second-pass analysis.
