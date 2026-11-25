# hickey-cycle-310 — implicit self on identifier resolution

Scope memo. No implementation lands in this cycle. The goal is to
settle how bare identifiers in atom-body expression position should
resolve, so future work on identifier-resolution rules, grep-ability
tooling, and the ergonomics of transition bodies all proceeds from a
single agreed frame rather than ad-hoc per-site discussion.

## Context

cxc currently requires `self.` prefix on every read of a field,
observation, or ctor param from inside the owning atom's body. The
staging LHS of `=>` is bare because the grammar there admits only
field names. This produces an asymmetry inside a single statement:

```
staging_next => self.staging_next.insert(Cell(c.x, c.y))
```

LHS bare, RHS self-prefixed. Reading the two sides, a reader shuttles
between two spellings of the same field.

Observed in the game-of-life refactoring study. Every observation
body and transition arm carries `self.` on every field read. Stage 5
of the study counted about thirty `self.` sites inside Grid and View.
The runtime semantics are identical to the implicit-self spelling;
the self prefix is pure textual tax.

## Current behaviour

Snapshot of cxc as of hickey-cycle-309 (2026-11-25).

### Identifier resolution inside atom body

The resolver walks the scope chain:

1. Locals (lambda args, aliases, destructured bindings)
2. Message / observation / ctor params in the current scope
3. `use`d capability bindings (console, plugin, etc.)
4. Unresolved: compile error

Self fields / observations / ctor params are NOT in the resolver's
visible scope. They must be reached explicitly through `self.`.

### Where bare identifiers already work

- `use console: Console` then `console.write(...)` resolves `console`
  as a capability binding, not through self.
- `:> stream_name` as untargeted emit uses bare stream name.
- `-> phase_name` as transition target uses bare phase name.
- `|: msg_name -> ...` as message-triggered transition uses bare
  message name.
- `alias x :- expr` then `x` in subsequent lines reads the alias.
- Ctor params within the body of their own def line are bare, since
  they're treated as locals until field-promoted.

### Where `self.` is required today

- Field reads: `self.alive_cells`, `self.width`, `self.gens`.
- Observation calls: `self.alive(x, y)`, `self.done(c)`.
- Method calls: `self.view.show(self)`.
- Staging RHS: `staging_next => self.staging_next.empty()`.

## Proposed behaviour

Extend the resolver to search self-fields, self-observations, and
self-ctor-params after locals and params but before declaring
unresolved. Explicit `self.X` remains legal and continues to work
identically. The change is purely additive.

### Resolution order

```
bare identifier X in atom-body expression position:
  1. Local (lambda arg, alias, destructured binding)       [wins]
  2. Enclosing message / observation / ctor param           [wins]
  3. `use`d capability binding                              [wins]
  4. Self field                                             [implicit self]
  5. Self observation                                       [implicit self]
  6. Self ctor param (if not already a field)               [implicit self]
  7. Unresolved: compile error
```

Local scope wins, standard lexical rule.

### Where it applies

- Expression positions in observation bodies
- Expression positions in transition arm bodies (guard predicates,
  stagings RHS, sends, calls)
- Expression positions in lambdas nested inside the above

### Where it does not apply

- LHS of `=>` staging — stays a selector-only position. Writing
  `self.X => ...` is NOT becoming a legal alternative; the LHS
  remains bare because no competing interpretation exists.
- `self :> msg` for self-async sends — `self` here names the atom as
  a send target, not an identifier being resolved. Keep the keyword.
- `|> self.method()` for sync self-calls — same reason, the method
  dispatch needs an explicit receiver. Could potentially drop to
  `|> method()` but opens a different design question about whether
  bare method calls default to self; defer for now.
- `return self` or similar fluent-chain returns where `self` IS the
  value being returned.

### Example before and after

Current game-of-life stage 5 (aspirational on nil propagation):

```
done(c: Cursor)     :- c.gen >= self.gens
row_done(c: Cursor) :- c.y   >= self.height
col_done(c: Cursor) :- c.x   >= self.width

row_at(y: Int) :-
    range(self.width).map(x => ||: self.alive(x, y) : "#" |?: ".").join("")

running <: tick(c: Cursor):
    |:  self.done(c) -> end
        :> died
    |:  self.row_done(c) -> running
        alive_cells  => self.staging_next
        staging_next => self.staging_next.empty()
        self :> emit_frame
        self :> tick(Cursor(c.gen + 1, 0, 0))
```

Under implicit self:

```
done(c: Cursor)     :- c.gen >= gens
row_done(c: Cursor) :- c.y   >= height
col_done(c: Cursor) :- c.x   >= width

row_at(y: Int) :-
    range(width).map(x => ||: alive(x, y) : "#" |?: ".").join("")

running <: tick(c: Cursor):
    |:  done(c) -> end
        :> died
    |:  row_done(c) -> running
        alive_cells  => staging_next
        staging_next => staging_next.empty()
        self :> emit_frame
        self :> tick(Cursor(c.gen + 1, 0, 0))
```

Bare `done(c)` is self.done(c). Bare `gens` is self.gens. Bare
`staging_next` on the RHS is self.staging_next. The staging statement
`staging_next => staging_next.empty()` now reads symmetrically.

Only `self :>` self-send retains the `self` keyword, because that
names the target atom, not a field.

## Concerns

### Shadowing hazard

Standard lexical-scope hazard. If a user introduces a local that
collides with a self-field name, every subsequent bare reference
means the local, not the field.

```
def Thing:
    count: Int! = 10

    report() :-
        alias count :- items.length()
        count * 2                    # ← alias, NOT self.count
```

Mitigation: compiler warning on shadowing. "alias `count` shadows
self-field `count`; use `self.count` to refer to the field." Opt-in
pragma to silence for intentional shadowing.

Severity: real but bounded. Every language with implicit this / self
has this failure mode, and standard tooling (IDE hover, grep + type
info) surfaces it.

### Grep-ability loss

Today, `grep 'self\.foo'` finds every field-read of foo. Under
implicit self, `grep 'foo'` finds every reference, mixing field
reads, local reads, param reads, and unrelated tokens. Narrowing to
field reads requires type-aware tooling.

Mitigation: cxc's own trace-viewer and the planned LSP already run
type-aware. For plain-grep users, the `self.` form stays legal, and
projects can lint for its use if they prefer the explicit style.

Severity: real cost. Explicit over implicit is a known Python-ism
for a reason. Worth weighing against the verbosity saved.

### Ctor-param scope boundary

Ctor params declared in `def Grid(width: Int!, ...)` become fields
on the atom. Inside the body, bare `width` resolves to self.width.
No ambiguity.

Inside the `def` head itself (before the body starts), bare `width`
is the param being declared. No change.

### Lambda arg vs self-field

```
range(width).map(x => alive(x, y))
```

`x` is a lambda-local arg, shadows any self.x. `alive` is self-method
(no local of that name). `y` must come from the enclosing scope.
Standard lexical rules apply.

If both a lambda arg and a self-field share a name:

```
range(width).map(x => x * 2)
```

Inside the lambda, `x` is the arg. Outside, `x` would be self.x if
self has one. Readers familiar with any OO language find this natural.

### Observations as methods

`done(c)` — without self, is this a free function or a method on
self? cxc has no free functions; observations always live on a type.
Resolution: self-method. No ambiguity.

### Capability binding collision

`use console: Console` binds `console` as a capability. If a field
also named `console` existed, lookup order resolves capability first
(step 3 above). Field would need `self.console` explicit. Rare; call
out in the shadowing warning.

### Message / phase name collision

`-> running` and `|: tick -> ...` use bare phase / message names in
dedicated grammar positions. These are not expression contexts, so
the resolution rule doesn't touch them. Separate namespace.

## Interaction with other cxc features

### `??` nil coalesce

Unchanged. Bare `x ?? fallback` where x is a nullable self-field
resolves to self.x ?? fallback. Same as today's self.x ?? fallback.

### `=>` staging

LHS stays bare field selector. RHS gains the implicit-self benefit.
The asymmetry the memo opened with resolves one direction: the LHS
was already compact; the RHS now matches.

### Implicit self on method calls

`|> self.method()` for sync self-calls currently requires explicit
self. Could extend the proposal to `|> method()`, but the receiver
in `|>` dispatch carries semantic weight (it names the atom whose
queue the call enters). Defer as a separate decision. This memo's
scope is identifier resolution in expression position, not send-site
receiver inference.

### Stream emit vs self-async-send target

Related but distinct question. cxc today uses two similar-looking
syntaxes with different dispatch mechanisms:

- `:> stream_name(payload)` — stream emit from self. Broadcast to
  subscribers. No handler dispatch on self.
- `self :> message_name(payload)` — message send enqueuing on self's
  own queue. Dispatches to the matching `running <: message_name`
  handler.

The first already has implicit self-as-emitter (there IS no target
to name in a stream emit; self is the only possible emitter). The
second requires explicit `self` because `other_atom :> msg` is also
legal and the receiver matters.

A reader seeing `:> died` next to `self :> emit_frame` reasonably
asks why the inconsistency. The answer is "different mechanisms with
colliding syntax." Whether to UNIFY them — single `:> X` resolving
to stream-emit or self-message-send based on what X is declared as —
is a real design question with semantic tension (stream broadcast vs
guaranteed-handler-dispatch, name collision rules, interaction with
cross-atom sends).

Deferred to hickey-cycle-311 (`:>` target inference). Keeping 310
scoped to expression-position identifier resolution so the resolver
change stays mechanical.

### Observation + field name collision

If an observation and a field share a name (should be a compile
error already, but worth noting), implicit self makes the ambiguity
visible. Keep the pre-existing "shared-name compile error" check.

## Migration path

Backward compatible. Every existing `self.X` call site continues to
work. The change is purely additive: bare identifiers that previously
failed to resolve now resolve to self-fields where available.

Two phases.

**Phase 1: resolver change.** Extend the identifier resolver in
`cxc.env` / `cxc.resolve` to search self-env after locals and params.
Emit shadowing warning when a local shadows a self-field. Single
central change.

**Phase 2: prelude + examples cleanup.** Audit `.cxc` sources in the
prelude and examples. Strip redundant `self.` where the explicit
form carries no reader value. Keep `self.` where it signals "this is
state, not a local" in dense bodies where the intent matters.

User code never breaks. Optional stylistic migration follows.

## Scope decisions

### In scope

- Resolver rule extension for expression-position bare identifiers
- Shadowing warning when locals / params collide with self-fields
- Documentation + spec update on identifier resolution order
- Optional cleanup of prelude + examples

### Out of scope

- Implicit receiver on method calls (`|> method()`); separate memo
- Dropping `self :>` for self-async sends; the `self` keyword here
  carries different meaning than identifier resolution
- Changing the LHS of `=>` to accept `self.X`; no benefit
- Flow-sensitive type narrowing on self-fields; separate feature

## Open questions

### Pragma to require explicit self

Teams that prefer the explicit style might want a module-level
pragma like `require_explicit_self: true` that re-raises the pre-310
error on bare self-references. Reasonable escape hatch. Decide at
implementation time whether the pragma is worth the surface area.

### IDE hover disambiguation

For editor integrations, bare `foo` on a read line should distinguish
"self.foo field read" from "local foo" visually (color, tooltip,
jump-to-definition). Not a compiler concern but a tooling
implication worth flagging.

### Shadowing warning default

Warn vs error on shadowing. Lean warn (shadowing is sometimes
intentional, e.g. a message param deliberately named to override a
field for the scope of one transition). Error would be too
restrictive for existing idioms.

## Non-goals

- Changing the capability binding resolution (console, plugin, etc.
  stay as they are)
- Introducing a `with-fields` scope or similar block
- Adding a global `this` alternative to `self`

## Recommendation

Approve the resolution-order extension. Land phase 1 as a single
compiler change (resolver + shadowing warning). Phase 2 cleanup is
optional and incremental; projects adopt the terser style at their
own pace.

Pair with hickey-cycle-309 for maximum ergonomic win. Stage 6 of the
game-of-life refactoring study (pending both cycles) carries the
combined shape: nil propagates through operators, bare identifiers
resolve to self, and the `!` surface shrinks to its genuine-contract
positions.

## References

- memory `corvid-arm-body-field-snapshot` — field reads snapshot at
  arm entry; implicit self doesn't change the snapshot semantics
- `docs/decisions/hickey-cycle-309-nil-propagation-plan.md` — the
  nil-propagation companion memo; same cycle genre
- `docs/rosetta/refactoring-studies/game-of-life/` — the study that
  surfaced the `self.` tax in dense transition bodies
- `hickey-cycle-306-ffi-plan.md` — prior design memo in the same
  decisions genre
