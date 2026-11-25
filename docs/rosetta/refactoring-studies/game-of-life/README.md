# Game of Life — Refactoring Study

Three stages of the same program, each addressing a specific set of
OOP code smells the previous stage left on the table. Stages 1-4 all compile under the
current cxc and produce identical output (horizontal → vertical →
horizontal blinker over three generations on a 5×5 grid). Stage 5
is aspirational pending a compiler change (see below). The
`docs/rosetta/game-of-life.cxc` canonical submission tracks stage 4.

## Stage 1 — baseline, with feature envy

File: [`01-baseline.cxc`](01-baseline.cxc)

The `main` atom drives the game. It walks `(gen, x, y)` across
Grid's cells through a four-arm dispatch, and sends three messages
to Grid per generation (`step_cell`, `commit`, `render`). Grid is
reactive.

Smells present:
- **Feature envy** on main.tick. The method cares about Grid's data
  (`g.width`, `g.height`) and Grid's behaviour (`g.step_cell`,
  `g.commit`, `g.render`) more than about main's own state.
- **Inappropriate intimacy** between main and Grid. main carries
  the three-message driving protocol.
- **Primitive obsession** on `(gen, x, y)` as three Ints threaded
  through tick's signature.
- **Temporary field** `next_alive` on Grid, visible as peer to the
  real domain state `alive_cells`.
- **God class (mild)** on Grid — rule, buffer, view parameters, and
  I/O in one atom.
- **Magic numbers and baked-in state** — `width = 5`, `height = 5`,
  blinker literal all hardcoded on Grid.
- **Shotgun surgery (latent)** — extensions (wrap-around, variant
  rulesets, metrics) touch both main and Grid.

## Stage 2 — walk into Grid, feature envy fixed

File: [`02-walk-in-grid.cxc`](02-walk-in-grid.cxc)

Grid owns the walk. main becomes a 4-line kickoff that waits on
Grid's `died` emit. Grid gains `tick(gen, x, y)` on itself, emits
`:> died` when the generation cap trips, and renders through a
`self :> render_frame` self-send that keeps the double-buffer
snapshot consistent across the transition.

Fixed:
- Feature envy on main — the walk's data lives on Grid, so the
  walk now lives on Grid.
- Shotgun surgery on extensions — the walk changes only touch Grid.
- Inappropriate intimacy — main's surface collapses to kickoff +
  end-observer.

Still present: primitive obsession on `(gen, x, y)`, temporary field,
god class, magic numbers.

## Stage 3 — full decomposition

File: [`03-decomposed.cxc`](03-decomposed.cxc)

Named Cursor record carries the tick coordinates. Named predicates
(`done`, `row_done`, `col_done`) replace the raw guard arithmetic.
View splits off as its own atom with `show(board: Grid!)`. The
temp field renames to `staging_next` with an intent comment. Width,
height, gens, and the View reference lift to Grid's ctor params.

Fixed:
- Primitive obsession on `(gen, x, y)` — Cursor record.
- God class — View extracted, Grid retains rule + buffer + walk.
- Magic numbers — width / height / gens are ctor params.
- Temp field intent — rename signals operational role.
- Divergent change (latent) — View changes no longer touch Grid.

Still present: Grid holds a direct `view: View!` reference and calls
`|> self.view.show(self)`. Grid knows View exists. Any View change
(colour, Unicode glyphs, a different sink) reaches into Grid's
imports. And baked-in alive_cells seed carries forward from stage 1
until Set-of-POD-record ctor params lower.

## Stage 4 — dependency inversion, terser observations

File: [`04-observer.cxc`](04-observer.cxc)

Grid stops holding a View reference. Instead, Grid emits
`:> frame_ready(cells: alive_cells, w: width, h: height)` on
initialisation and after every commit. View subscribes with
`running <: g.frame_ready(cells: Set of Cell, w: Int!, h: Int!)`
and renders what the payload carries. Grid has no idea who, if
anyone, is listening. Multiple Views could subscribe to the same
Grid without Grid noticing. The payload snapshots the `alive_cells`
Set at emit time, so even if Grid commits a new frame before View
gets around to rendering, View renders the frame it was handed
(cxc containers are immutable, so the reference held by the payload
stays the old Set after Grid rebinds).

Return-type annotations drop where inference reads them off the body:
`alive`, `neighbors`, `next_cell`, `done`, `row_done`, `col_done`,
and View's `row_at` all lose `:- Bool!` / `:- Int!` / `:- String!`
on the signature.

Fixed:
- Inappropriate coupling — Grid no longer references View. Dependency
  arrow runs one way: View → Grid.
- Signature clutter — about 8 obvious return-type annotations removed.

Still present: baked-in alive_cells seed (same as stage 3). Primitive
coordinates still carry `!` because cxc defaults to nullable types
(see note below).

### Why the `!` suffix sticks around

cxc's nullability default is opposite of Rust/TS. Bare `T` is
nullable (envelope-wrapped, can be nil). `T!` is non-nil. `T?` is
banned universally per the spec, so `!` is the only way to mark
required-value types. Container types (`List of T`, `Set of T`,
`Map of (K, V)`, `String`) are always non-nil and skip the suffix.
Primitive coordinates like `Int` that must carry a real value need
`!`. The verbosity is a known design cost of nullable-by-default.

## Stage 5 — nil-propagation (aspirational)

File: [`05-nil-propagation.cxc`](05-nil-propagation.cxc)

Every `!` suffix drops. The file does NOT compile under current cxc
because `range()`, arithmetic (`c.gen + 1`), and comparisons
(`c.gen >= self.gens`) refuse the `[:optional :int]` envelope and
demand non-nil `:int`. The design memo at
[`../../../decisions/hickey-cycle-309-nil-propagation-plan.md`](../../../decisions/hickey-cycle-309-nil-propagation-plan.md)
scopes the operator-lifting work that would let this shape land.
Under the proposed semantics, nil propagates through arithmetic and
comparison, and `|:` guards treat nil-valued predicates as
not-firing.

Point of the stage 5 file: pin the end-state so future design work
on cxc operators has a concrete target to validate against.

## Reading the diff

```sh
diff -u 01-baseline.cxc 02-walk-in-grid.cxc
diff -u 02-walk-in-grid.cxc 03-decomposed.cxc
diff -u 03-decomposed.cxc 04-observer.cxc
diff -u 04-observer.cxc 05-nil-propagation.cxc
```

Or compile and run each:

```sh
bin/cxc compile docs/rosetta/refactoring-studies/game-of-life/02-walk-in-grid.cxc /tmp/out
/tmp/out/02-walk-in-grid
```
