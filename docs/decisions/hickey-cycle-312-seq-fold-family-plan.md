# hickey-cycle-312 — `.fold` family on Seq, window-cursor game-of-life

Scope memo. Adds a fold primitive on Seq and the aggregates built on
it (`frequencies`, `group_by`, `min_by`/`max_by`/`first` currently
List-only). Payoff lands as two more stages of the game-of-life
refactoring study: stage 5 (window-cursor via alive-set bounding
box) and stage 6 (scatter via `frequencies`).

## Context

Current Seq surface (verified 2026-11-25):

```
on Seq:    .map  .filter  .flat_map  .for_each  .sum  .to_list  .to_set
on List:   + .at  .append  .count  .min_by  .max_by  .first
on Set:    + .insert  .without  .contains  .count
on Map:    + .assoc  .dissoc  .get  .count
```

Gaps that block the game-of-life compactification path:

1. No `.fold(init, (acc, elem) => new_acc)`. Every aggregation is
   either hardcoded (`.sum`) or expressed via chained `.map` +
   `.to_list`. Users write custom reductions as ad-hoc atom-loops.
2. `.min_by` / `.max_by` / `.first` are List-only sugar. A `Set of T`
   has to `.seq().to_list().min_by(...)` which is two unnecessary
   materialisations. The sugar should live on Seq.
3. No `.frequencies()` returning `Map of (T, Int)`. Core cellular-
   automata and histogram-style aggregates require it. Clojure's
   `frequencies` and Python's `Counter` are the reference shapes.
4. No `.group_by(key_fn)` returning `Map of (K, List of T)`. Standard
   data-wrangling move.

The game-of-life refactoring study stops at stage 4 (observer pattern
with `self :>` dropped by hickey-cycle-311). Stage 5 (window-cursor)
and stage 6 (scatter) both need `.min_by` / `.max_by` on Seq. Stage 6
additionally needs `.frequencies`.

## Proposed additions

### `.fold(init: U, (acc: U, elem: T) => U) -> U`

The base primitive. Walks the Seq once, threading an accumulator.
Enables every other aggregate as a prelude function.

```
alive_count: Int = alive_cells.seq().fold(0, (acc, _) => acc + 1)
sum_x:       Int = alive_cells.seq().fold(0, (acc, c) => acc + c.x)
by_x:        Map of (Int, List of Cell) =
    cells.seq().fold([], (acc, c) => acc.update(c.x, [], l => l.append(c)))
```

Compiler primitive. Lowers to an NSMutableArray walk with a mutable
local accumulator (internal — surface is immutable `acc` closed over
by the lambda, standard cxc lambda shape).

### `.min_by(f)` / `.max_by(f)` / `.first` lifted to Seq

Currently in `compiler/src/cxc/lower.clj` as List-only sugar
(lines 1846, 1902, 3307, 3315, 4538 per 2026-11-25 grep). Lift the
dispatch to accept any Seq-shaped receiver. Prelude implementation on
top of `.fold` is also acceptable — pick the one that keeps the
emitted Objective-C the same shape as today's List variant.

### `.frequencies()` returning `Map of (T, Int)`

Prelude function built on `.fold`:

```
.frequencies() :- self.fold(Map.empty(), (acc, elem) =>
    acc.assoc(elem, acc.get(elem) ?? 0 + 1))
```

Lives in `compiler/resources/prelude/target/shared/seq.cxc` (new
file) once the `.fold` primitive lands.

### `.group_by(key_fn)` returning `Map of (K, List of T)`

Prelude function built on `.fold`:

```
.group_by(k) :- self.fold(Map.empty(), (acc, elem) =>
    acc.assoc(k(elem), acc.get(k(elem)) ?? [] . append(elem)))
```

### `.bounds()` returning a `Rect` (optional, if List/Set of Cell is common)

Single-pass combined `min_x` / `min_y` / `max_x` / `max_y` over
Cells. Narrower than the general fold but saves four traversals in
the game-of-life window computation. Defer unless stage 5 benchmarks
show the four-pass bounding-box is measurable.

## Phase breakdown

Four phases, loosely ordered. Phase 2 depends on phase 1; phase 3
depends on phase 1; phases 4 and 5 depend on phases 1-3.

**Phase 1: `.fold` primitive on Seq.** Compiler surgery in
`cxc.lower`. Container-method dispatch extended with a new
`:c-seq-fold` lowering node. Tests pin behaviour on bare Seq, Seq
of primitive, Seq of record, empty-Seq, single-element-Seq.

**Phase 2: lift `.min_by` / `.max_by` / `.first` to Seq.** The
existing List sugar generalises. Keep the List-specific selector
if a selector collision would break existing emit, but extend
dispatch to accept Seq receivers. Tests pin Seq-of-record
`.min_by(c => c.x)` and the nil-propagation case (empty Seq →
nil, per hickey-cycle-309 lifting).

**Phase 3: `.frequencies` and `.group_by` in prelude.** Create
`compiler/resources/prelude/target/shared/seq.cxc`. Define both
functions over `.fold`. Tests via a scratch `.cxc` that constructs
a known input and asserts the aggregate shape.

**Phase 4: game-of-life stage 5 (window-cursor).** Compute bounding
box of `alive_cells` per generation via `.min_by` / `.max_by`.
Cursor walks only within the bounded rectangle expanded by a
one-cell halo. Viewport visits drop from `width × height` to
`|window|`. Lives at `docs/rosetta/refactoring-studies/game-of-life/
05-window-cursor.cxc` AND becomes the canonical submission at
`docs/rosetta/game-of-life.cxc`. Named observations
(`in_window(c)`, `cell_at(c)`, `bounds_of(cells)` as a Rect
constructor) carry the readability. Wiki prose rewritten to
foreground the cursor-over-derived-domain pattern as the cxc
hook. This is the public-facing landing; polish accordingly.

**Phase 5: game-of-life stage 6 (scatter).** Collapse per-cell
dispatch to a single bulk transform:

```
counts: Map of (Cell, Int) =
    alive_cells.seq()
        .flat_map(c => self.offsets.map(o => Cell(c.x + o.x, c.y + o.y)))
        .frequencies()

alive_cells => counts.seq()
    .filter((c, n) => n == 3 or (n == 2 and self.alive_cells.contains(c)))
    .map((c, _) => c)
    .to_set()
```

No Cursor, no `tick`/`step_cell` arms, no `staging_next` field. The
pure functional shape the Clojure and Python Rosetta entries use.
Lives at `docs/rosetta/refactoring-studies/game-of-life/
06-scatter.cxc`.

## Scope decisions

### In scope

- `.fold` primitive on Seq
- Lift `.min_by` / `.max_by` / `.first` to Seq
- Prelude `.frequencies` and `.group_by`
- Game-of-life stages 5 and 6 in the refactoring study
- **Canonical `docs/rosetta/game-of-life.cxc` tracks stage 5
  (window-cursor).** The Rosetta Code submission is cxc's first
  real public-facing code. Window-cursor is cxc-distinctive: no
  other mainstream language expresses "Cursor walks a dynamically-
  bounded region derived from state" as naturally as a cxc atom
  observation + Cursor record + guarded dispatch. Scatter (stage 6)
  stays in the study for completeness but reads as a Clojure or
  Python port rather than something cxc-native, so it stays
  study-only. The public face should show the thing only cxc
  does well.

### Out of scope

- HashLife-style tile indexing with active-tile set
- Parallelism / concurrent fold variants
- Infinite / lazy Seq (every current Seq is eager, that stays)
- Streaming aggregates
- `.bounds()` as a specialised method (defer; see phase 4 note)

## Open questions

### Return type of `.first` on empty Seq

Current List `.first` behaviour: unclear from grep. Under 309
lifting, empty → nil propagates naturally. Confirm during Phase 2
that the List emit already does this and extend consistently.

### Accumulator type inference

Does cxc's type inference carry `U` through `fold(init, (acc, elem)
=> ...)` from `init`'s type? If not, `.fold` may need an explicit
`U` type hint. Spike during Phase 1 to confirm inference reaches
the lambda body.

### `.group_by` output ordering

Map has no declared iteration order. Users who want ordered groups
(min x first, say) chain `.group_by` with a `.seq().sort_by(...)`
on the Map (which requires `sort_by` on Map Seqs). Fine for v1.0.

### Compiler primitive vs. prelude for `.frequencies`

Could be a C-level helper (`cxc_seq_frequencies`) with direct NSDictionary
accumulation for speed. Prelude-via-fold is clearer for pedagogy
and matches the "add primitives sparingly" posture. Default: prelude.
Benchmark drives a flip if ever.

## Non-goals

- Changing existing method semantics on List / Set / Map
- Introducing a `Traversable` or `Monoid` protocol
- Operator overloading for aggregation
- Parallel fold

## Recommendation

Approve the four additions and the two game-of-life stages. Land in
phase order. Phase 1 is the structural compiler piece; phases 2-3
are small; phases 4-5 are `.cxc` refactoring that exercises the new
API end-to-end.

Pair with:
- `hickey-cycle-309` for the nil-propagation payoff on empty-Seq
  edge cases (`.min_by` on empty returns nil, chained consumers
  short-circuit)
- `hickey-cycle-310` for implicit-self reducing noise in prelude
  method definitions
- `hickey-cycle-311` for bare-`:>` dropping self:> on the stage 5
  self-sends

Stage 5 (window-cursor) and stage 6 (scatter) together close the
refactoring-study narrative: OOP smells → full decomposition →
dependency inversion → iteration-domain shrink → paradigm shift.
Stage 5 ships as the Rosetta Code submission; stage 6 remains a
study-only artefact so the reader sees both end-states.

## References

- `docs/decisions/hickey-cycle-309-nil-propagation-plan.md`
- `docs/decisions/hickey-cycle-310-implicit-self-plan.md`
- `docs/decisions/hickey-cycle-311-send-target-inference-plan.md`
- `docs/rosetta/refactoring-studies/game-of-life/` — the study
- Rosetta Code Game of Life, Clojure + Python entries:
  https://rosettacode.org/wiki/Conway%27s_Game_of_Life — the
  scatter shape this memo imports
- Christophe Grand, "Conway's Game of Life in Clojure":
  http://clj-me.cgrand.net/2011/08/19/conways-game-of-life/ — the
  Clojure entry's source
