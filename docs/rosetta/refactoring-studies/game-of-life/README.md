# Game of Life — Refactoring Study

Three stages of the same program, each addressing a specific set of
OOP code smells the previous stage left on the table. All three
produce identical output (horizontal → vertical → horizontal blinker
over three generations on a 5×5 grid). The `docs/rosetta/game-of-life.cxc`
canonical submission now matches stage 3; stage 1 and stage 2 live
here for the migration narrative.

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

Still present: baked-in alive_cells seed. Set-of-POD-record ctor-param
lowering is 307b territory; the Set literal stays on the field default.

## Reading the diff

```sh
diff -u 01-baseline.cxc 02-walk-in-grid.cxc
diff -u 02-walk-in-grid.cxc 03-decomposed.cxc
```

Or compile and run each:

```sh
bin/cxc compile docs/rosetta/refactoring-studies/game-of-life/02-walk-in-grid.cxc /tmp/out
/tmp/out/02-walk-in-grid
```
