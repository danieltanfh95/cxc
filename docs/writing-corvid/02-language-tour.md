# Language tour

## Definitions

`def Name:` introduces every top-level thing. What comes out depends
on the body:

| Body shape | Kind | Example |
|---|---|---|
| `field: Type = default` lines only | record | `def V: x: Int = 0` |
| `case name` lines | enum (tagged variant) | `def Level: case low; case high` |
| `-> phase` + `<:` arms | atom | `def C: -> idle; idle <: tick -> idle` |
| message signatures with `:-` bodies | protocol | `def Sink: <: absorb(amount: Int)` |

Constructor parameters go on the head: `def Counter(start: Int!):`.
They act as required arguments and are also readable as fields.

## Nullability

Three surface forms. Only two are legal:

| Form | Meaning | Legal? |
|---|---|---|
| `T`  | nullable, may be `nil` | yes |
| `T!` | non-nil, the compiler enforces | yes |
| `T?` | (banned everywhere) | **no** |

Rule: reach for `T!` when the field must hold a value, and plain `T`
when `nil` is a legitimate state. Never write `T?`.

## Records

Plain value types. Field defaults are required.

```elixir
def V:
    x: Int = 0
    y: Int = 0
```

Constructor: `V()` or `V(x: 3)`. Fields not passed take their
declared default. Methods can be defined on records via the same
`name(args) :- body` syntax as atom observations.

## Atoms

Live actors. Have phases, mailboxes, transitions.

```elixir
def Counter:
    n: Int = 0
    -> idle
    idle <: increment -> idle
        then: n => n + 1
```

Every atom needs an initial phase.

## Phases

A phase is a named state. An atom is always in exactly one of them,
and transitions move between phases as messages arrive. Common naming
(not enforced but conventional):

- `idle`: passive, waiting for messages
- `<verb>ing`: actively doing something (`deploying`, `combatting`, `drawing`)
- `alive` / `dead`: reserved for atoms that model literal entity lifecycle (units that can die). Don't use for passive/active states.

An atom can have as many phases as it needs, but a small phase count
is usually a good sign.

## Transitions

Shape: `<source_phase> <: <message>(<args>) -> <target_phase>`, then
an optional guard, then an optional `then:` body.

```elixir
running <: fire(shot: Int!) -> firing
    |: shot > 0
    then:
        rounds => rounds + 1
        |> weapon.discharge(power: shot)
```

### `<:` versus `<|`

- **`<:`**: normal transition. Runs on the atom's mailbox thread.
- **`<|`**: sync-receive. Same lowering under the single-thread substrate, with reserved semantics for the concurrent runtime.

Use `<:` by default. Reserve `<|` for deliberate sync-receive tests.

## Guards: `|:`

The pipe-colon glyph. Sits between the transition header and the
`then:` body:

```elixir
idle <: place_hero(card: Card, team: Int!) -> idle
    |: card.type == CardType.item and card.on_deploy.kind == EffectKind.attach_thorn_all
    then: ...
```

Multiple arms with `|:` guards on the same `phase <: msg` are legal.
cxc tries each in source order and fires the first whose guard is
true. A trailing unguarded arm is the vanilla case
(see [03-design-patterns.md](03-design-patterns.md#fall-through-arm-as-extension-point)).

`|:` also serves in three other positions: as record/protocol
invariant declarations, as precondition/postcondition obligations on
record methods (both are `|:` lines, the keywords `requires` and
`ensures` were retired at v1.0), and as multi-arm dispatch.
Pattern-match guards inside a `case` dispatch retain the older
`when:` keyword.

## Staging: `=>`

Inside `then:`, `=>` declares "the value of this field in the next
state":

```elixir
then:
    n     => n + 1
    total => total + 1
```

The `=>` glyph is not assignment but a staged declaration, and every
`=>` update in the body commits together at the end against the
pre-transition snapshot. This has two consequences:

- Only one `=>` per field per transition path (spec §1.11). Multiple `=>` on the same field on one path is smuggled assignment.
- The body reads pre-image values throughout, so `print(n)` after `n => n + 1` shows the **old** `n`.

To print the new value: `print(n + 1)`. To compose two updates: use
`let` or fold them into one expression.

## Observations: `:-`

Computed read-only views.

```elixir
doubled :- n * 2
```

Callers read as `c.doubled`. Observations can call other
observations, take arguments (`plus(other: Int) :- n + other`), and
dispatch container methods on List/Map/Set fields.

The glyph is **`:-`** at v1.0.

## Sends: `|>` and `:>`

- **`|>`**: sync send. Blocks until the receiver finishes the arm, and cannot loop back to the same atom on the same thread (rejected at runtime).
- **`:>`**: async send. Enqueues and returns immediately.
- **`self :> msg`**: the standard self-send. Use this for iteration and for staged multi-message flows.

Emit (broadcast) is bare `:> msg(args)` on a body, and every
subscriber receives it. Subscribers register by writing an arm on
the source's message, as in `running <: source.tick -> running`.

## Multi-arm dispatch: `||:`

Expression-position guard chain, replaces retired `if X: Y else: Z`:

```elixir
reflect_bonus :- ||: has_thorn : 1
                  ||: card.combat.kind == .reflect : card.combat.amount
                  ||: else : 0
```

Reads top-to-bottom, first true guard wins, `||: else : ...` is the
catch-all.

## Containers

`List of T`, `Map of (K, V)`, `Set of T`. Mutation is FP-only, and every
mutation returns a new container:

```text
cards => cards.append(card: c)         # List
scores => scores.assoc(key: "a", 3)     # Map
tags => tags.insert("hero")             # Set
```

Reads use `.at(i) ?? fallback` and `.get(key) ?? fallback` because
lookups can miss:

```elixir
top :- cards.at(0) ?? _sentinel_card
```

Iteration uses `xs.for_each(x => print(x))` or the aggregate methods
(`.count`, `.filter`, `.sort_by`, etc.) because cxc has no `for x in
xs:` loop and iteration is method dispatch on the container.

## Enums

```elixir
def Direction:
    case up
    case down
```

Referenced as `Direction.up`. Enum values lower to C typedef enums,
and dispatch is via `|:` guards on `x == Direction.up` or via the
`x: <: case_name: body` pattern-match dispatch (which still uses
`when:` for guards, not `|:`).

## Capabilities and plugins

The prelude provides FileSystem, Http, Json, Subprocess, EnvVars,
EnvMut, Random, Time, Hash, Console, Args, Clock. Import via `use`:

```elixir
def main(fs: FileSystem):
    -> running
        then: |> fs.write_text(path: "/tmp/x", content: "hi")
```

Every fallible capability method raises a typed error (`IOError`,
`HttpError`, `ShellError`, `JsonError`, `EnvError`, `RandomError`).
Total variants (`_or`, predicates) never raise.

Custom capabilities go through the plugin shape:

```elixir
def plugin:
    name: "MyLib"
    version: SemVer(major: 1, minor: 0)
    foreign "libmylib":
        do_thing(x: Int) -> Int
```

See [docs/spec/spec-cxc-v1.0.md](../../docs/spec/spec-cxc-v1.0.md) §1.12
"Plugins" for the full form, with §1.14 (prelude) as context.

## Failure and error handling

- **No `Result[T, E]`**. Use raise + `or T(field: let x): <body>` recovery.
- **No `switch`/`case`**. Use transitions + `|:` guards + variant patterns.
- **No `try/catch`**. Fallible methods raise typed records, and recovery lands at the call site via `or`.

Example:

```elixir
alias content :- fs.read_text(path: p)
                     or IOError(: "")   # recover to empty string on any IOError
```

## What Corvid does not have

These are absent by design:

- **User-defined operators.** `+`, `[]`, `==` on user types are compiler-blessed only. Use named methods: `.concat(other)`, `.at(idx)`, `.equals(other)`.
- **Annotations / decorators.** No `@name(args)` grammar. Kind information is inferred from body shape, and deprecation goes in comments.
- **Shape variables / generics on user types.** Containers monomorphize at instantiation (`List of Int` and `List of String` are distinct types).
- **Async promises / futures.** Async flows through atom lifecycles: a `Clock` returns a stream whose phases signal completion (`tick`, `done`). The same shape is intended for `fs.open` and friends. `Future[T]` is not a type.
- **Global mutable state.** All mutation lives inside an atom's fields, staged through `=>`.
- **`if ... else ...` as an expression.** Use `||: guard : body` chains.
- **`for x in xs:`.** Use `xs.for_each(x => body)` or the aggregate methods.
- **Imperative container mutation.** Every mutation is FP: `.append` / `.assoc` / `.insert`. No `.add` / `.remove`.

When you're reaching for one of these, [04-common-mistakes.md](04-common-mistakes.md) probably names the shape you want instead.
