# Rosetta Code submission plan — stealth-launch cxc

Soft-launch surface for cxc (Corvid). Submit implementations of canonical
Rosetta Code tasks to rosettacode.org. The language category page appears
under `Category:Corvid` (or `Category:Cxc` — pending verification of the
site's current language-name convention), with per-task entries on each
task's page alongside C, Clojure, Python, etc. No announcement; anybody
browsing a task page discovers it.

## Task candidates — ranked by cxc idiom fit

Each entry: what it demonstrates, why it fits cxc, implementation note.

### Tier 1 — baseline, every language has these

1. **Hello world/Text** — the first thing a reader looks for. Minimal
   `def main:` with one `-> end` transition and a `print(...)` in the
   entry `then:`. Signals "cxc has entry points like you'd expect."

2. **FizzBuzz** — canonical loop + conditional demo. Showcases
   multi-arm dispatch (`||: <guard> : <body>`) as the natural if/elif
   substitute. Uses `self :> tick` self-send for iteration — pointing
   at the "atom-loop idiom" rather than a `while`. This is the
   first place cxc looks different.

3. **99 Bottles of Beer** — iteration + text formatting. Same loop
   shape as FizzBuzz but with more text. Probably redundant with
   FizzBuzz for the first wave — defer.

### Tier 2 — showcases cxc's actual shape

4. **Fibonacci sequence** — recursion vs iteration contrast. A tight
   arm-dispatch form that computes F(n) via a counter-and-pair phase.
   Readers coming from functional languages expect `fib n` to be a
   recursive function; cxc's version is an atom with state fields.
   This is the second "cxc looks different" moment.

5. **Conway's Game of Life** — THE cxc showcase. Each cell is an atom
   (or the whole grid is one atom with a 2D field); phases carry tick
   progression; transitions resolve neighbour counts. Natural fit for
   the lifecycle model. This is the piece to put time into. Expect
   ~150 lines, longest of the batch.

6. **N-queens** — backtracking via the atom-loop idiom. Illustrates
   how cxc handles problems that look loop-shaped in C/Python but
   decompose cleanly into phases + self-sends.

### Tier 3 — defer unless tier 1+2 lands well

7. **Tic-tac-toe** — state transitions showcase, but needs input
   handling (not trivial for a cxc binary; stdin-read via what cap?).
   Skip for first wave.

8. **Dining philosophers** — the atoms-as-processes showcase. Natural
   fit, but Rosetta's existing implementations assume shared mutex
   primitives; cxc's answer is different enough that it needs a
   paragraph of framing to not look wrong. Hold for a second wave.

9. **Towers of Hanoi** — recursive, forces a non-idiomatic shape in
   cxc (no lambda, no recursive functions). Skip.

## First wave — concrete

Pick 4: **Hello world / Text**, **FizzBuzz**, **Fibonacci sequence**,
**Conway's Game of Life**. In that order of risk: smallest, most
uncontroversial first; the showcase last.

Each lands as one file under `docs/rosetta/<task-slug>.cxc`, with a
header comment linking to the Rosetta Code task page and giving the
expected output. Compile-verified via `./bin/cxc compile
docs/rosetta/<file> /tmp/rosetta-<task>-out` and runtime-verified by
matching the expected output.

## Submission mechanics — user-answered 2026-11-20

1. **Language name on the wiki: `cxc`.** Category page is
   `Category:Cxc`; `<syntaxhighlight lang="cxc">` blocks for code.
   Short, distinctive, matches the file extension readers see
   immediately.
2. **Account: existing.** User edits under their existing rosettacode
   account. I draft the per-task wiki source; user pastes + saves.

Still needs site-verification before submission:

- **Syntax highlighting fallback.** `lang="cxc"` is almost certainly
  unrecognised by Rosetta's SyntaxHighlight extension. Options:
  `lang="clojure"` (close structural match — parens, kebab-case,
  `|:` would not highlight but blocks would), `lang="text"` (no
  colour, exact source), or `lang="javascript"` (surprisingly close
  for `|: guard : body` ternary-like shapes). Decide per-task.
- **Prior art.** Verify no `cxc` or `Corvid` entry already exists
  on the wiki before creating `Category:Cxc`.
- **Task-page names.** Verify current slugs on rosettacode.org —
  task names shift over time. Confirm "Hello world/Text",
  "FizzBuzz", "Fibonacci sequence", "Conway's Game of Life" are
  current.

## First wave — confirmed

Four tasks approved: **Hello world/Text**, **FizzBuzz**,
**Fibonacci sequence**, **Conway's Game of Life**.

## Implementation status

All four files landed on main 2026-11-21 (moved under `docs/rosetta/`
2026-11-23 for consistency with `docs/writing-corvid/`):

- `hello-world.cxc` — prints `Hello world!` on one line.
- `fizzbuzz.cxc` — 100-line FizzBuzz via self-send atom-loop idiom
  with grouped-arm `|:` guards (hickey-cycle-271 syntax).
- `fibonacci.cxc` — F(0) through F(19) via two running fields and
  `=>` updates.
- `game-of-life.cxc` — 5x5 blinker, 3 generations. Illustrates the
  Grid-as-atom pattern and the `cells => [25-element list literal]`
  workaround for the no-`.map` stdlib gap. Gotcha hit during
  implementation: each indented line in an observation body is a
  separate statement; the eight-neighbor sum stays on one line.

Pending before submission:

- Site verification (syntax-highlight lang code, task slug names,
  `Category:Cxc` availability).

Wiki-markup drafts landed 2026-11-21 under [wiki/](wiki/) — four
`.wiki` snippets plus a [wiki/README.md](wiki/README.md) submission
guide. All four use `lang="text"` (MediaWiki SyntaxHighlight has no
`cxc` lexer) and the standard `{{header|Cxc}}` template.

## Non-goals

- No performance tables. Rosetta is about expressiveness, not speed.
- No extensive explanatory prose on the wiki pages beyond the standard
  one-paragraph preamble. Code speaks.
- No tasks that force cxc into an awkward shape (string regex, bignum,
  complex-number arithmetic) in the first wave. Those live a few
  months out, after idioms for them exist in the compiler.
