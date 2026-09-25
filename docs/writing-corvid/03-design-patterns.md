# Design patterns

Patterns that pay off over time. Each one has a **problem shape**
(what the code looks like when you should reach for the pattern), a
**pattern** (the shape to reach for), and a **why** (the failure the
pattern prevents).

## Data-first design

Before any behaviour, define the shape.

For every entity that behaves or is exchanged: what fields does it
hold, who produces those fields, who consumes them, what does each
consumer read? If you can't answer, the shape isn't ready to write
code against.

**How this plays out in Corvid.** Records first, then observations,
then transitions. If a transition body reaches for a value that
isn't on a record yet, stop and put it there. Don't compute it
locally each time.

## Effect-as-data over enum-of-behaviours

**Problem shape.** You have N entities each of which has a "special
behaviour," you're tagging each with an enum, and the transition
that handles the entity is dispatching on the tag:

```
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

Every new entity behaviour adds a case *and* an arm. The enum
inflates, and every consumer of `Card` grows a parallel switch.

**Pattern.** Collapse the enum into a data record on the entity. The
enum shrinks to *kinds of effect*, not *kinds of card*. One arm per
kind, not per card:

```
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

Each new card is a data row. Each new *shape of effect* is one new
EffectKind + one arm. The scaling asymmetry is the point.

**Why.** Enum-of-behaviours conflates two axes (*entity identity*
and *behaviour kind*) and forces one arm per (identity, kind)
combination. Effect-as-data separates them: entity holds effect
values; arms dispatch on kind. New entities cost data; new behaviours
cost code. This matches the shape of how the two typically evolve.

## Sentinel/default records as atom fields

**Problem shape.** You want a "no effect" or "empty" default record
value, and you're constructing it inline everywhere:

```
Card(name: "?", ability: Effect(kind: .none, scope: .own_side, amount: 0), ...)
Card(name: "!", ability: Effect(kind: .none, scope: .own_side, amount: 0), ...)
```

**Pattern.** Hoist to an atom field with a declared type, then
reference by name:

```
def Player(...):
    _no_effect: Effect! = Effect(kind: EffectKind.none, scope: EffectScope.own_side, amount: 0)
    _sentinel_card: Card! = Card(name: "?", on_deploy: _no_effect, ...)
```

Every downstream reference is `_no_effect` or `_sentinel_card`. The
default lives in one place; changing it changes every use.

**Why.** Inline default records duplicate a piece of design intent
across the file. When the default changes (a new field, a new
default value, a rename) the churn touches every use. A hoisted
field consolidates the intent, and the compiler's type checker keeps
the references honest.

## Fall-through arm as extension point

**Problem shape.** You have several arms guarded by `|: card.kind ==
EffectKind.X` for specific kinds. When a new kind lands, you have to
find every place that dispatches on `.kind` and add a new arm.

**Pattern.** Terminate the dispatch with an unguarded arm: the
vanilla case.

```
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

**Why.** Guards on the target consumer are a slow-changing surface.
Kinds are a fast-changing surface. Sinking the extension point into
the consumer means kind additions don't ripple.

## Format at the boundary

**Problem shape.** You're storing `tower_hp_display: String` or
`unit_name_short: String` (precomputed presentation strings) on a
record or atom.

**Pattern.** Keep the data in canonical form. Format at the print or
serialize boundary via observations:

```
def Lane:
    tower_hp: Int = 20

    # Presentational: reads canonical field, formats on demand.
    tower_hp_str :- tower_hp.to_string()  # or a richer formatter
```

Callers reach for `.tower_hp_str` when they want the string form;
`.tower_hp` when they want the number.

**Why.** Precomputed presentation is redundant with the canonical
field and rots on schedule mismatch (you update `tower_hp` but forget
`tower_hp_display`). Observations compute on read, so they can't
drift. The tradeoff (recomputing on every read) is negligible
compared to the correctness win.

## Split a record when a field is dead in a slot

**Problem shape.** A record has field `X`, but in *some* consumer
slots, `X` is always the default / never read. E.g., `Effect.scope`
is meaningful for a deploy effect that targets a side of the lane,
but meaningless for a combat-time effect that modifies the card
itself.

**Pattern.** Split the record type by slot semantics:

```
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
signature: it suggests optionality that doesn't exist. Splitting
narrows the shape to what's really there. If two slots then need
similar fields, extract a shared record and compose.

## Verify slot semantics from consumer code, not from the slot name

**Problem shape.** You're about to add a behaviour to `on_deploy`
because the name suggests "runs after deploy." But you haven't
checked what "deploy" means for cards that don't land as units.

**Pattern.** Before assigning behaviour to a slot, grep the
consumers of that slot. Read what actually fires.

Concrete example: `on_deploy` in the dota2 simulator sounded like
"fires after the unit lands." Items and scrolls never land as
units, but they *do* have `on_deploy` effects that fire when
played. So the actual semantics is "fires when the card is played,"
not "fires after landing." Any new effect that fires-when-played
belongs on `on_deploy` regardless of whether the card lands.

**Why.** Slot names are convenient labels; consumer code is the
ground truth. Naming-first design ends up creating a new slot for
each near-synonym, and behavior spreads across slots for accidental
reasons.

## Phase-name standardisation

**Problem shape.** Phase names vary by atom: one atom's `ready` is
another's `waiting` is another's `alive` for the same intent
(passive, waiting for messages).

**Pattern.** Adopt a small vocabulary:

- **`idle`** for the passive-waiting state that most atoms have.
- **`<verb>ing`** for actively doing something (`deploying`, `drawing`, `combatting`).
- **`alive` / `dead`** *only* for atoms that model a real entity lifecycle where death is a state to observe (e.g., a unit on a lane). Don't use `alive`/`dead` for passive/active states of atoms that aren't modeling entity death.

**Why.** Consistent phase names let a reader pattern-match across
atoms without cross-referencing definitions. `alive`/`dead` earn
their weight when death is externally observable; using them as
passive/active flags dilutes the signal.

## Parametric state via sub-atoms, not N parallel fields

**Problem shape.** You want state parameterised by a runtime index,
e.g. "one counter per lane" for three lanes. You're tempted to
declare `count_0: Int`, `count_1: Int`, `count_2: Int`.

**Pattern.** Model it as a sub-atom, not parallel fields:

```
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
progress and completion:

```
alias file :- fs.open(path: "/tmp/x")   # returns a File atom

running <: file.opened -> reading       # subscribe to File.opened phase
    then: |> file.read()

reading <: file.read_done(content: String) -> done
    then: print(content)
```

`File` moves through phases: `opening` → `opened` → `reading` →
`read_done` → `closed`. Callers subscribe to the phases they care
about via `<:` arms on the sub-atom's messages/streams.

**Why.** Future/Promise is a value-typed handle that outlives its
producer, which fights the substrate's actor model. Atoms *are* the
substrate's async unit. Modelling completion as a phase transition
keeps the flow inside the substrate's guarantees.

## Envelope over sentinel for missing / optional

**Problem shape.** You need to encode "value present or absent" and
you're reaching for a sentinel: a magic value (`-1`, empty string,
`NaN`) that means "absent."

**Pattern.** Use a wrapper (record with a `present: Bool` field) or
a nullable slot with `??` fallback. Reserve sentinels for cases
where the sentinel is genuinely out-of-band (a value the domain
can't produce).

**Why.** Sentinels rot: today `-1` means "absent" but tomorrow the
domain admits negative values. Envelopes carry presence explicitly
and can't lie about it.
