# Design patterns

## Data-first design

Before any behaviour, define the shape.

Ask field, producer, consumer, and read questions for every entity
that behaves or is exchanged. What fields does it hold. Who produces
those fields. Who consumes them. What does each consumer read. If
you can't answer, the shape isn't ready to write code against.

**How this plays out in Corvid.** Records first, then observations,
then transitions. If a transition body reaches for a value that
isn't on a record yet, stop and put it there. Don't compute it
locally each time.

## Effect-as-data over enum-of-behaviours

**Problem shape.** You have N entities each of which has a "special
behaviour," you're tagging each with an enum, and the transition
that handles the entity is dispatching on the tag:

```elixir
def Ability:
    case none
    case damage_enemy_2
    case heal_own_2
    case buff_own_1
    case attack_bypass
    case ...

running <: play(card: Card) -> running
    |: card.ability == Ability.damage_enemy_2
    |> enemy.damage(amount: 2)

running <: play(card: Card) -> running
    |: card.ability == Ability.heal_own_2
    |> own.heal(amount: 2)
# ...one arm per case
```

Every new entity behaviour adds both a case and an arm. The enum
inflates, and every consumer of `Card` grows a parallel switch.

**Pattern.** Collapse the enum into a data record on the entity. The
enum shrinks to kinds of effect, not kinds of card, and you get one
arm per kind rather than one arm per card:

```elixir
def EffectKind:
    case none
    case damage_side
    case heal_side
    case buff_side
    case attack_bypass

def Effect:
    kind:   EffectKind
    scope:  EffectScope
    amount: Int!

def Card:
    name:   String
    on_deploy: Effect!

running <: play(card: Card) -> running
    |: card.on_deploy.kind == EffectKind.damage_side
    |> enemy(card.on_deploy.scope).damage(amount: card.on_deploy.amount)

running <: play(card: Card) -> running
    |: card.on_deploy.kind == EffectKind.heal_side
    |> own_side(card.on_deploy.scope).heal(amount: card.on_deploy.amount)
```

Each new card is a data row, and each new shape of effect is one
EffectKind and one arm. The scaling asymmetry is the point.

**Why.** Enum-of-behaviours conflates two axes (entity identity and
behaviour kind) and forces one arm per (identity, kind) combination,
so every new card that behaves adds an arm to every consumer of the
enum. Effect-as-data separates the axes: the entity holds effect
values, and arms dispatch on kind. New entities cost data while new
behaviours cost code, and data grows faster than code does.

## Sentinel/default records as atom fields

**Problem shape.** You want a "no effect" or "empty" default record
value, and you're constructing it inline everywhere:

```text
Card(name: "?", ability: Effect(kind: .none, scope: .own_side, amount: 0), ...)
Card(name: "!", ability: Effect(kind: .none, scope: .own_side, amount: 0), ...)
```

**Pattern.** Hoist to an atom field with a declared type, then
reference by name:

```elixir
def Player(...):
    _no_effect: Effect! = Effect(kind: EffectKind.none, scope: EffectScope.own_side, amount: 0)
    _sentinel_card: Card! = Card(name: "?", on_deploy: _no_effect, ...)
```

Every downstream reference is `_no_effect` or `_sentinel_card`. The
default lives in one place, and changing it changes every use.

**Why.** Inline default records duplicate a piece of design intent
across the file, so when the default changes (a new field, a new
default value, a rename) the churn touches every use. Hoisting
consolidates the intent into one place. cxc's type checker keeps
every downstream reference honest against it.

## Fall-through arm as extension point

**Problem shape.** You have several arms guarded by `|: card.kind ==
EffectKind.X` for specific kinds. When a new kind lands, you have to
find every place that dispatches on `.kind` and add a new arm.

**Pattern.** Terminate the dispatch with an unguarded arm: the
vanilla case.

```elixir
idle <: place_hero(card: Card, team: Int!) -> idle
    |: card.on_deploy.kind == EffectKind.damage_all
    ...

idle <: place_hero(card: Card, team: Int!) -> idle
    |: card.on_deploy.kind == EffectKind.heal_all
    ...

# ...more guarded arms...

# Vanilla fall-through: any kind not matched above lands here.
idle <: place_hero(card: Card, team: Int!) -> idle
    |> self.side(team).append(card: card)
```

New EffectKinds land on the fall-through until they justify their
own arm. Adding a new kind becomes an additive change to the enum,
not a code change at every dispatch site.

**Why.** Kinds change fast. Dispatch sites change slowly. Sinking the
extension point into the consumer means kind additions don't ripple.

## Shared cross-phase handler via wildcard source

**Problem shape.** Several phases need to react to the same message
in the same way — a quit key, a help toggle, a global catch-all —
and copying the arm into every phase produces N identical blocks
that drift apart as the atom grows.

**Pattern.** Write the arm once with `*` as source. Concrete
`phase <: msg` arms take precedence; the wildcard is the fall-through
floor that fires wherever no concrete arm matched.

```elixir
def Game:
    -> lobby
    -> running
    -> await_restart

    # Concrete, phase-specific
    lobby <: kb(code: Int!) -> running
        |: code == 32         # space: start match
    running <: kb(code: Int!) -> running
        |: code == 32         # space: pause toggle (handled below)
        paused => not paused
    await_restart <: kb(code: Int!) -> draft
        |: code == 15         # R: restart

    # Wildcard — fires in any phase on kb(code) when no concrete arm
    # matched. H toggles help, Q quits, bare catch-all absorbs stray
    # keys so the runtime doesn't abort on no-arm-match.
    * <: kb(code: Int!) -> self
        |: code == 4
        |> renderer.toggle_help()
    * <: kb(code: Int!) -> self
        |: code == 113 or code == 81
        alias _q :- window.quit()
    * <: kb(code: Int!) -> self
```

**Why.** The wildcard-arm mechanism has concrete-over-wildcard
precedence built in. Repeating phase-specific copies of a global
handler is a signal that you want `*`. Writing the handler once
makes adding a new phase to the atom a free extension — the global
behavior comes along automatically.

**Rule of thumb.** If you write the same `<: msg` arm with three or
more distinct source phases, pull it to `* <: msg` and keep the
phase-specific arms only for phases that genuinely differ.

## Format at the boundary

**Problem shape.** You're storing `tower_hp_display: String` or
`unit_name_short: String` (precomputed presentation strings) on a
record or atom.

**Pattern.** Store the data raw. Format at the print or serialize
boundary via observations:

```elixir
def Lane:
    tower_hp: Int = 20

    # Presentational: reads the raw field, formats on demand.
    tower_hp_str :- tower_hp.to_string()  # or a richer formatter
```

Callers reach for `.tower_hp_str` when they want the string form,
and `.tower_hp` when they want the number.

**Why.** Precomputed presentation is redundant with the raw field
and rots on schedule mismatch (you update `tower_hp` but forget
`tower_hp_display`). Observations compute on read, so they can't
drift. The tradeoff (recomputing on every read) is negligible
compared to the correctness win.

## Split a record when a field is dead in a slot

**Problem shape.** A record has field `X`, but in some consumer
slots `X` is always the default and never read.
`Effect.scope` is meaningful for a deploy effect that targets a
side of the lane, but meaningless for a combat-time effect that
modifies the card itself.

**Pattern.** Split the record type by slot semantics:

```elixir
def DeployEffect:
    kind:   EffectKind
    scope:  EffectScope
    amount: Int!

def CombatEffect:
    kind:   EffectKind
    amount: Int!

def Card:
    on_deploy:     DeployEffect!
    combat_effect: CombatEffect!
```

Each slot's type carries exactly the fields that are meaningful at
that slot.

**Why.** A field that's always the default is a lie in the type
signature. It suggests an optionality that doesn't hold in fact.
Splitting narrows the shape to what's there. When two slots then
need similar fields, extract a shared record and compose.

## Verify slot semantics from consumer code, not from the slot name

**Problem shape.** You're about to add a behaviour to `on_deploy`
because the name suggests "runs after deploy." But you haven't
checked what "deploy" means for cards that don't land as units.

**Pattern.** Before assigning behaviour to a slot, grep the
consumers of that slot. Read what actually fires.

`on_deploy` in the pocket-MOBA simulator sounded like
"fires after the unit lands." Items and scrolls never land as units,
yet they carry `on_deploy` effects that fire when
played. So the actual semantics is "fires when the card is played,"
not "fires after landing." Any new effect that fires-when-played
belongs on `on_deploy` regardless of whether the card lands.

**Why.** Slot names are convenient labels, but consumer code is the
ground truth. Naming-first design ends up creating a new slot for
each near-synonym, and behaviour spreads across slots for accidental
reasons.

## Phase-name standardisation

**Problem shape.** Phase names vary by atom: one atom's `ready` is
another's `waiting` is another's `alive` for the same intent
(passive, waiting for messages).

**Pattern.** Adopt a small vocabulary:

- **`idle`** for the passive-waiting state that most atoms have.
- **`<verb>ing`** for actively doing something (`deploying`, `drawing`, `combatting`).
- **`alive` / `dead`** for atoms that model a real entity lifecycle where death is a state to observe (a unit on a lane, say). Don't use `alive`/`dead` for passive/active states of atoms that aren't modelling entity death.

**Why.** Consistent phase names let a reader pattern-match across
atoms without cross-referencing definitions. `alive`/`dead` earn
their weight when death is externally observable, and using them as
passive/active flags dilutes the signal.

## Parametric state via sub-atoms, not N parallel fields

**Problem shape.** You want state parameterised by a runtime index,
e.g. "one counter per lane" for three lanes. You're tempted to
declare `count_0: Int`, `count_1: Int`, `count_2: Int`.

**Pattern.** Model it as a sub-atom, not parallel fields:

```elixir
def Lane:
    count: Int = 0
    idle <: bump -> idle
        count => count + 1

def Board:
    lanes: List of Lane = [Lane(), Lane(), Lane()]
    idle <: bump(lane: Int!) -> idle
        |> lanes.at(lane).bump()
```

**Why.** `=>` staging targets a compile-time field name. You can't
`counts[i] => ...` at runtime. If you tried to parameterise by index,
you'd end up with a switch on `lane` and one arm per index. A
sub-atom sidesteps this by moving the mutation into the sub-atom's
own transition table.

## Async via lifecycles, not futures

**Problem shape.** A blocking operation (file read, HTTP request,
long computation) needs to happen without freezing the caller. You
reach for `Future[T]` or `Promise[T]`.

**Pattern.** Model the operation as an atom whose phases signal
progress and completion. The shipped `Clock` capability is the
simplest example:

```elixir
def main:
    use console: Console
    clock: Clock = Clock(ticks: 3)
    count: Int = 0

    -> running

    running <: clock.tick -> running
        count => count + 1

    running <: clock.done -> end
        console.write(count.to_string())              # prints 3
```

`Clock` moves through phases internally, emitting `tick` on each
period and `done` when the configured count elapses. The parent
subscribes to the phases via `<:` arms on the sub-atom's streams.
Later `fs.open` and `http.request` will follow the same shape, with
phases `opening` → `opened` → `reading` → `read_done` → `closed`.
Those capabilities expose a blocking surface. See spec §1.11
"Async lifecycle policies" for the ratified rule.

**Why.** Future/Promise is a value-typed handle that outlives its
producer, which fights the substrate's actor model. Atoms are the
substrate's async unit, and modelling completion as a phase
transition keeps the flow inside the substrate's guarantees.

## Atom-level iteration via phases, not `while`

**Problem shape.** The task iterates with per-step side effects or state transitions (counting, draining a queue, retrying until a condition clears). You reach for `while` or `.for_each`.

**Pattern.** Model the loop body as a phase that receives a self-send retry, with a guarded exit arm to the terminal phase. Each self-send is one iteration.

```elixir
def Counter(limit: Int!):
    use console: Console
    n: Int = 0
    -> counting
        self :> tick

    counting <: tick -> counting
        |: n < limit
        console.write(n.to_string())
        n => n + 1
        self :> tick

    counting <: tick -> end
        |: n == limit
        console.write("done")
        :> finished

def main:
    counter: Counter = Counter(limit: 3)
    -> running

    running <: counter.finished -> end
```

The three load-bearing arms: initial-phase `then: self :> tick` kicks the loop, `<: tick -> counting |: n < limit` is the retry body, `<: tick -> end |: n == limit` is the exit. The parent waits on an outbound `:> finished` emission rather than guessing when the loop stops.

Bare `:> tick` resolves to `self :> tick` under the send-target
inference rule when `tick` is a handler on the enclosing atom (see
[02-language-tour.md § Bare :> self-send inference](02-language-tour.md#bare--self-send-inference)).
The pattern above spells `self :> tick` explicitly because the
sibling `patterns/06-atom-loop-counter.cxc` fixture still spells it
that way; either form compiles.

**Why.** `while` is bootstrap-only, intended for stdlib and pure computation. It executes inside one transition on one thread. Atom-level iteration spans transitions, which is where phases, snapshot-staging, and the scheduler earn their keep. Driving a loop through phases keeps every step visible in the trace, each `tick` is a seq-numbered receive, and the exit is a transition the viewer can show. A `while` buried in a `then:` body is opaque to the trace. The spec keeps `while` and `loop` as bootstrap primitives for a reason, but user-facing iteration that touches atom state belongs in the lifecycle.

See [patterns/06-atom-loop-counter.cxc](patterns/06-atom-loop-counter.cxc) for the verified minimal shape.

## Envelope over sentinel for missing / optional

**Problem shape.** You need to encode "value present or absent" and
you're reaching for a sentinel value (`-1`, empty string, `NaN`)
that means "absent."

**Pattern.** Use a wrapper (record with a `present: Bool` field) or
a nullable slot with `??` fallback. Reserve sentinels for cases
where the sentinel is genuinely out-of-band (a value the domain
can't produce).

**Why.** Sentinels rot when the domain grows to admit the sentinel
value as legitimate. Today `-1` means "absent," but if tomorrow the
domain admits negative values, every read site has to be audited
for the collision. Envelopes carry presence explicitly and can't lie.
