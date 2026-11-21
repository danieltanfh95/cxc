# Rosetta Code submission drafts

Four wiki-markup snippets ready to paste into existing rosettacode.org
task pages. Each snippet is a single `==={{header|Cxc}}===` section
that goes alphabetically among the other languages on each task.

| Task | Draft | Target page |
|------|-------|-------------|
| Hello world/Text | [hello-world.wiki](hello-world.wiki) | https://rosettacode.org/wiki/Hello_world/Text |
| FizzBuzz | [fizzbuzz.wiki](fizzbuzz.wiki) | https://rosettacode.org/wiki/FizzBuzz |
| Fibonacci sequence | [fibonacci.wiki](fibonacci.wiki) | https://rosettacode.org/wiki/Fibonacci_sequence |
| Conway's Game of Life | [game-of-life.wiki](game-of-life.wiki) | https://rosettacode.org/wiki/Conway%27s_Game_of_Life |

## Submission order

Submit in this order to catch any wiki-config issue before the longer
drafts land:

1. Hello world/Text — shortest, lowest risk.
2. FizzBuzz — exercises the atom-loop and `|:` guards.
3. Fibonacci sequence — exercises `=>` transition arrows.
4. Conway's Game of Life — the showcase; largest; `||:` + nested atom.

## Design decisions

- **`lang="text"`.** MediaWiki's SyntaxHighlight extension does not
  recognise `lang="cxc"`. `lang="text"` renders as a plain
  monospace block without colouring, which beats a broken
  highlighter error. Switching to `lang="javascript"` or
  `lang="clojure"` would colour parens and strings plausibly but
  mis-highlight `|:` / `||:` / `:>` / `->` glyphs; plain text is
  less distracting.
- **`{{header|Cxc}}`.** Standard Rosetta Code language-header
  template. The `|Cxc` argument becomes the section heading and
  creates a link to `Category:Cxc` (or the equivalent) for the
  language-wide index. This is the first entry, so the category
  page will not exist yet; following the redlink after the first
  submission lands lets you create it with a one-paragraph language
  summary.
- **Preamble prose per task.** One paragraph before the code block
  explaining the idiom the task demonstrates. No performance
  notes, no "cxc looks weird" disclaimers, no hedging.
- **Reader coming from imperative languages.** The Game of Life
  draft carries four bullet notes at the bottom because the
  `||:` / `|>` / `:>` / immutable-List divergence from Python/C is
  large enough to warrant an inline guide. The other three tasks
  do not need them.

## Site-verification tasks (pending)

Before submitting, confirm from a logged-in session:

- [ ] `Category:Cxc` is available (not taken by a prior submission)
- [ ] Task slug names match the URLs in the table above
- [ ] No existing `Cxc` or `Corvid` entry on any of the four tasks

If any of these fails, update the drafts and this README before
pasting.

## Post-submission

- Create `Category:Cxc` page with a one-paragraph language summary
  (atom lifecycles, pre-v1.0, Apple-ecosystem target, Objective-C
  emit).
- Link back to the project repository under
  `{{language programming paradigm|Functional}}` and
  `{{language programming paradigm|Object-oriented}}` category
  tags.
