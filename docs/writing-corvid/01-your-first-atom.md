# Your first atom

A counter that counts. It'll take about fifteen lines of Corvid and
will exercise the four building blocks: **records**, **atoms**,
**transitions**, **observations**.

## The whole program

```
def Counter:
    n: Int = 0

    bump    :- n + 1
    doubled :- n * 2

    idle <: increment -> idle
        then: n => n + 1

def main:
    -> running
        then:
            let c: Counter = Counter()
            |> c.increment()
            |> c.increment()
            |> c.increment()
            print(c.doubled)
```

Compile and run it, and you'll see `6` — three increments, doubled.

Now walk through it line by line.

## Records vs. atoms

`def X:` defines *something*. The compiler infers what kind of thing
from the shape of the body:

- A body of `field: Type = default` lines → a **record** (a plain data value).
- A body with a `-> phase` initial transition → an **atom** (a live actor with mutable state and a mailbox).
- A body of `case name` lines → an **enum** (a tagged variant).
- A body of message signatures with `:-` bodies → a **protocol** (a shape any atom can satisfy).

`Counter` above has a `<:` transition arm, so it's an atom. If you
removed the transition and left `n: Int = 0`, it would be a record.

## Fields and defaults

```
n: Int = 0
```

A field is a name, a type, and an optional default. If a field has
a default, callers can omit it; if it doesn't, callers must pass it.
That's how `Counter()` above is allowed to have no arguments — every
field has a default.

If you *want* the caller to pass `n`, either drop the default or use
a constructor parameter:

```
def Counter(start: Int!):
    n: Int = start
```

The `Int!` says non-nil — see [02-language-tour.md](02-language-tour.md#nullability) for the nullability discipline.

## Observations

```
bump    :- n + 1
doubled :- n * 2
```

The `:-` glyph declares an **observation**: a computed read-only view
of the atom's state. Callers can read `c.bump` and `c.doubled` from
outside without knowing they're computed rather than stored. When `n`
changes, the observations recompute on the next read.

Observations are how you compose derived values without duplicating
state. If you find yourself storing `doubled: Int` and updating it
alongside `n`, back up — that's what observations are for.

## Transitions

```
idle <: increment -> idle
    then: n => n + 1
```

Read left to right:

- **`idle`** — the phase this arm applies to. An atom is always in exactly one phase; arms only fire when the atom is in the arm's source phase.
- **`<: increment`** — the message this arm accepts. Callers send `|> c.increment()` to fire it.
- **`-> idle`** — the target phase. `increment` returns the atom to `idle`.
- **`then:`** — the body. Runs after the phase transition and before the next message is dispatched.
- **`n => n + 1`** — a **staging** update. `=>` is not assignment; it declares "the value of `n` in the next state." All `=>` updates in a body commit together at the end, against the pre-transition snapshot.

The example above lets the compiler infer the initial phase from
context (a single source phase named `idle` across all arms). You
can also declare it explicitly:

```
def Counter:
    n: Int = 0
    -> idle
    idle <: increment -> idle
        then: n => n + 1
```

Explicit initial phases become necessary when an atom has arms on
multiple source phases and it's not obvious which is initial, or
when the initial phase has an on-enter body:

```
def Counter:
    n: Int = 0
    -> idle
        then: n => 100
    idle <: increment -> idle
        then: n => n + 1
```

`main` conventionally uses `-> running`, sometimes with a `then:`
body of one-time setup, and transitions to `-> end` when done.

## Messages: sync vs. async

Two send glyphs:

- **`|>`** — synchronous. Blocks the sender until the target atom finishes the arm.
- **`:>`** — asynchronous. Enqueues the message on the target's mailbox and returns immediately.

In `main` above we used `|>` three times — each `increment` completes
before the next fires, so by the time `print` runs the counter has
seen all three. Using `:>` would enqueue but not wait; the print
might see any intermediate state.

The one gotcha: you can't `|>` yourself if the caller and callee are
the same atom in the same thread. Use `self :> msg` for
self-messages. See [04-common-mistakes.md](04-common-mistakes.md).

## Main

```
def main:
    -> running
        then: ...
```

`main` is an atom whose initial phase runs `then:` at program
start. When main hits a terminal phase (`end` by convention, or any
phase that self-schedules `stop`), the runtime exits.

## Where next

You now have the vocabulary to read half the [compiler/examples/](../../compiler/examples/) tree. Start with:

- [`atom-simple.cxc`](../../compiler/examples/atom-simple.cxc) — records + observations.
- [`atom-methods.cxc`](../../compiler/examples/atom-methods.cxc) — observations with arguments.
- [`cross-atom-send.cxc`](../../compiler/examples/cross-atom-send.cxc) — one atom sending to another.
- [`stream-typed-payload.cxc`](../../compiler/examples/stream-typed-payload.cxc) — the emit / subscribe pattern.

Then read [02-language-tour.md](02-language-tour.md) for the syntax
you haven't hit yet.
