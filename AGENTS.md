# AGENTS.md

AI agents (codex, opencode, etc.) working in this repo follow these rules.

## What this repo is

cxc is a language for the Apple ecosystem based on lifecycles
("cxc" on disk, "Corvid" in prose). Corvid supports functional
(codata) object-oriented programming without imperative baggage. cxc
is written in Clojure, reads `.cxc`, emits Objective-C, drives clang,
and produces macOS binaries. [README.md](README.md) gives the public
overview.

## Writing Corvid code (`.cxc` files)

**Read the authoring guide first.** [docs/writing-corvid/](docs/writing-corvid/)
contains an opinionated guide for humans and AI agents, in six files.
Read the ones matching your task:

- **Writing a new `.cxc` file from scratch**: start at [docs/writing-corvid/01-your-first-atom.md](docs/writing-corvid/01-your-first-atom.md).
- **Refactoring existing `.cxc` code**: [docs/writing-corvid/03-design-patterns.md](docs/writing-corvid/03-design-patterns.md) names the shapes to reach for.
- **Debugging a compile error**: [docs/writing-corvid/04-common-mistakes.md](docs/writing-corvid/04-common-mistakes.md) covers the frequent failure modes.
- **Syntax lookup**: [docs/writing-corvid/02-language-tour.md](docs/writing-corvid/02-language-tour.md).

Apply the rules in `docs/writing-corvid/` for any Corvid task. Agent
sessions running Claude Code also have the `cxc` skill bundle at
[.claude/skills/cxc/](.claude/skills/cxc/). Load it for routing into
authoring, cleanup-audit, or trace-review references.

## Working on the compiler (Clojure under `compiler/`)

The compiler is a different discipline from the language. Run tests
before shipping:

```bash
cd compiler
clojure -M:test
```

Keep tests green.

Hooks under `.githooks/` gate the invariants locally (no CI). Enable
once per clone:

```bash
git config core.hooksPath .githooks
```

- `pre-commit` runs `clj-kondo` on staged compiler + viewer-cljs
  source. Fast (~3s).
- `pre-push` runs the full test suite, both kondo surfaces, and a
  reflection check across cxc namespaces. Thorough (~1 min).

The reflection check leans on `(set! *warn-on-reflection* true)` in
every `cxc.*` namespace; adding a reflection site surfaces at load
time, and the hook fails the push.

## Operational rules

Operational rules (compile-verify, scratch directories, `git add -A`,
`--no-verify`, daemon handling) live in
[.claude/skills/cxc/SKILL.md](.claude/skills/cxc/SKILL.md) under
*Operational discipline*. Read that file before touching anything
in this repo. Claude Code sessions load it as a skill; other agents
read it as a plain markdown doc.

## Language spec vs. authoring guide

- **Spec** at [docs/spec/spec-cxc-v1.0.md](docs/spec/spec-cxc-v1.0.md). Normative.
- **Guide** at [docs/writing-corvid/](docs/writing-corvid/). Opinionated, for readable authoring.
- **Translation patterns** at [docs/PATTERNS.md](docs/PATTERNS.md). "Coming from Swift/Rust/Python, how do I write X here?" Complementary to the guide.
