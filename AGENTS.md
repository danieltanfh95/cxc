# AGENTS.md

Guidance for AI agents (codex, opencode, etc.) working in this repository.

## What this repo is

An atom-substrate language ("cxc" on disk, "Corvid" in prose) with a
bootstrap compiler in Clojure. Compiler reads `.cxc`, emits
Objective-C, drives clang, produces macOS binaries. See
[README.md](README.md) for the public overview.

## Writing Corvid code (`.cxc` files)

**Read the authoring guide first.** [docs/writing-corvid/](docs/writing-corvid/)
contains an opinionated guide for humans and AI agents. Six files;
read the ones matching your task:

- **Writing a new `.cxc` file from scratch**: start at [docs/writing-corvid/01-your-first-atom.md](docs/writing-corvid/01-your-first-atom.md).
- **Refactoring existing `.cxc` code**: [docs/writing-corvid/03-design-patterns.md](docs/writing-corvid/03-design-patterns.md) names the shapes to reach for.
- **Debugging a compile error**: [docs/writing-corvid/04-common-mistakes.md](docs/writing-corvid/04-common-mistakes.md) covers the frequent failure modes.
- **Syntax lookup**: [docs/writing-corvid/02-language-tour.md](docs/writing-corvid/02-language-tour.md).

For any Corvid task, apply the rules baked into the guide. The
short version:

- Data before code. Records first, then observations, then transitions.
- Nullability: `T` nullable, `T!` non-nil, `T?` banned everywhere.
- Effect-as-data over enum-of-behaviours (see design-patterns.md).
- `=>` is snapshot-staged, not incremental. One `=>` per field per path.
- Containers are FP-only: `.append`, `.filter`, `.assoc`, `.dissoc`, `.insert`, `.without`.
- Sync send `|>` cannot loop back to the same atom. Use `self :> msg`.
- No `Result[T, E]`; no `switch/case`; no `try/catch`. Raise + `or T(...)` recovery.

## Working on the compiler (Clojure under `compiler/`)

The compiler is a different discipline from the language. Run tests
before shipping:

```
cd compiler
clojure -M:test
```

Tests should stay green. 1053 tests / 2927 assertions is the current
count.

## Compile-verify discipline

If you propose Corvid code (in a PR, an issue reply, or generated
output), compile it first. The pipeline is in
[README.md](README.md#try-it). Do not ship untested Corvid code.

Every example in [docs/writing-corvid/](docs/writing-corvid/) is
verified against the compiler before commit; the pattern is worth
following.

## What to leave alone

- Don't run `git add -A` or `git add .`. Stage explicit paths only.
- Don't commit files under `raw/`, `compiler/research/`, `design/plans/`, `design/working-notes.md`. These are local scratch and are gitignored for a reason.
- Don't modify `.gitignore` to un-ignore private scratch directories.
- Don't skip commit hooks (`--no-verify`).

## Language spec vs. authoring guide

- **Spec** at [design/spec/spec-cxc-v1.0.md](design/spec/spec-cxc-v1.0.md). Normative. When guide and spec disagree, spec wins.
- **Guide** at [docs/writing-corvid/](docs/writing-corvid/). Opinionated, for readable authoring.
- **Translation patterns** at [design/PATTERNS.md](design/PATTERNS.md). "Coming from Swift/Rust/Python, how do I write X here?" Complementary to the guide.
