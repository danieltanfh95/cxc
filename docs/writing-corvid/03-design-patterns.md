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
    then: |> enemy.damage(amount: 2)

running <: play(card: Card) -> running
    |: card.ability == Ability.heal_own_2
    then: |> own.heal(amount: 2)
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
    then: |> enemy(card.on_deploy.scope).damage(amount: card.on_deploy.amount)

running <: play(card: Card) -> running
    |: card.on_deploy.kind == EffectKind.heal_side
    then: |> own_side(card.on_deploy.scope).heal(amount: card.on_deploy.amount)
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
    then: ...

idle <: place_hero(card: Card, team: Int!) -> idle
    |: card.on_deploy.kind == EffectKind.heal_all
    then: ...

# ...more guarded arms...

# Vanilla fall-through: any kind not matched above lands here.
idle <: place_hero(card: Card, team: Int!) -> idle
    then: |> self.side(team).append(card: card)
```

New EffectKinds land on the fall-through until they justify their
own arm. Adding a new kind becomes an additive change to the enum,
not a code change at every dispatch site.

**Why.** Kinds change fast. Dispatch sites change slowly. Sinking the
extension point into the consumer means kind additions don't ripple.

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
        then: count => count + 1

def Board:
    lanes: List of Lane = [Lane(), Lane(), Lane()]
    idle <: bump(lane: Int!) -> idle
        then: |> lanes.at(lane).bump()
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
    clock: Clock = Clock(ticks: 3)
    count: Int = 0

    -> running

    running <: clock.tick -> running
        then: count => count + 1

    running <: clock.done -> end
        then: print(count)              # prints 3
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
