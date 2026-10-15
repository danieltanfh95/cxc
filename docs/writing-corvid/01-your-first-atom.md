# Your first atom

A counter that counts, exercising records, atoms, transitions, and
observations.

## The whole program

```elixir
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

Prints `6`.

## Records vs. atoms

`def X:` defines something. cxc infers what kind of thing
from the shape of the body:

- A body of `field: Type = default` lines → a **record** (a plain data value).
- A body with a `-> phase` initial transition → an **atom** (a live actor with mutable state and a mailbox).
- A body of `case name` lines → an **enum** (a tagged variant).
- A body of message signatures with `:-` bodies → a **protocol** (a shape any atom can satisfy).

`Counter` above has a `<:` transition arm, so it's an atom. If you
removed the transition and left `n: Int = 0`, it would be a record.

## Fields and defaults

```text
n: Int = 0
```

A field is a name, a type, and a default. Every atom field has a default,
and record fields are required to have one too, so `Counter()` above is
allowed to have no arguments. For callers that need to pass a value
without a default, use a constructor parameter.

If you want the caller to pass `n`, either drop the default or use
a constructor parameter:

```elixir
def Counter(start: Int!):
    n: Int = start
```

The `Int!` says non-nil. See [02-language-tour.md](02-language-tour.md#nullability) for the nullability discipline.

## Observations

```elixir
bump    :- n + 1
doubled :- n * 2
```

The `:-` glyph declares an **observation**, a computed read-only view
of the atom's state. Callers can read `c.bump` and `c.doubled` from
outside without knowing they're computed rather than stored. When `n`
changes, the observations recompute on the next read.

Observations are how you compose derived values without duplicating
state. If you find yourself storing `doubled: Int` and updating it
alongside `n`, back up. Observations handle that derivation.

## Transitions

```elixir
idle <: increment -> idle
    then: n => n + 1
```

Read left to right:

- **`idle`**: the phase this arm applies to. An atom is always in exactly one phase, and arms only fire when the atom is in the arm's source phase.
- **`<: increment`**: the message this arm accepts. Callers send `|> c.increment()` to fire it.
- **`-> idle`**: the target phase. `increment` returns the atom to `idle`.
- **`then:`**: the body. Runs after the phase transition and before the next message is dispatched.
- **`n => n + 1`**: a **staging** update. `=>` is not assignment, it declares "the value of `n` in the next state." All `=>` updates in a body commit together at the end, against the pre-transition snapshot.

The example above lets cxc infer the initial phase from context (a
single source phase named `idle` across all arms). cxc also accepts
an explicit declaration:

```elixir
def Counter:
    n: Int = 0
    -> idle
    idle <: increment -> idle
        then: n => n + 1
```

Explicit initial phases become necessary when an atom has arms on
multiple source phases and inference is ambiguous, or
when the initial phase has an on-enter body:

```elixir
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

- **`|>`**: synchronous. Blocks the sender until the target atom finishes the arm.
- **`:>`**: asynchronous. Enqueues the message on the target's mailbox and returns immediately.

`main` sends three sync messages. Each `increment` finishes before
the next send, so `print` sees all three applied. `:>` would enqueue
without waiting, and the print might see any intermediate state.

Do not send `|>` to the same atom on the same thread. Use `self :> msg`
for self-messages. See [04-common-mistakes.md](04-common-mistakes.md).

## Main

```elixir
def main:
    -> running
        then: ...
```

`main` is an atom whose initial phase runs `then:` at program
start. When main hits a terminal phase (`end` by convention, or any
phase that self-schedules `stop`), the scheduler exits.

## Where next

Start with these [compiler/examples/](../../compiler/examples/) programs:

- [`atom-simple.cxc`](../../compiler/examples/atom-simple.cxc): records + observations.
- [`atom-methods.cxc`](../../compiler/examples/atom-methods.cxc): observations with arguments.
- [`cross-atom-send.cxc`](../../compiler/examples/cross-atom-send.cxc): one atom sending to another.
- [`stream-typed-payload.cxc`](../../compiler/examples/stream-typed-payload.cxc): the emit / subscribe pattern.

Then read [02-language-tour.md](02-language-tour.md) for the syntax
you haven't hit yet.
