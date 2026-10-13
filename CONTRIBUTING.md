# Contributing

Thanks for looking. This is a one-person project at the public-alpha
stage. The surface will shift as feedback lands, so direction and scope
are not yet open to drive-by contribution. The guide below exists so
that if you decide to send a patch, it has the best chance of landing.

## Before you start

Open an issue first for anything beyond a typo or a clear bug fix.
There is a strong Clojure-philosophy bias on this project —
backward compatibility is a core virtue, "stable surface" wins over
"nicer syntax", and library-level abstraction is preferred over new
language or runtime features. Alignment on direction before you write
code saves both of us time.

If you are an AI agent contributing on behalf of a human, read
[AGENTS.md](AGENTS.md) first. It encodes the authoring rules the rest
of this doc assumes.

## Local setup

Requires macOS with `clang` (Xcode command-line tools) and a Clojure
CLI (`brew install clojure/tools/clojure`). The test suite drives
clang end-to-end on every example in `compiler/examples/`, so a
working toolchain is non-negotiable.

```sh
git clone https://github.com/danieltanfh95/cxc
cd cxc/compiler
clojure -M:test
```

Expect ~1100 tests, around two minutes on an M-series Mac. See the
README's "Try it" block for the one-off compile-and-run invocation.

## Tests

Every `.cxc` under `compiler/examples/` is compiled and run by the
end-to-end test suite. A change to the lower or emit layer that
touches a shape covered by an example must keep that example's
expected stdout matching. New compiler behaviour should land with a
new example that exercises it, and a one-line description in
`compiler/examples/README.md`.

For non-compiler work (viewer, docs, skills), run the tests anyway so
that a shared cache miss or a prelude-reload bug surfaces before the
PR goes out.

## Commits and PRs

- One concern per commit. Mixed-concern commits are the main reason a
  revert is painful.
- Commit subject uses the `<area>: <imperative one-liner>` form, see
  `git log` for the house style. Common areas: `compiler`, `viewer v2`,
  `self-host`, `docs`.
- No AI-tooling attribution lines in commit messages. The commit
  message is for the change itself.
- Branch off `main`, open the PR against `main`.
- A PR that fails `clojure -M:test` is not ready. The CI story is
  bare for now, so running tests locally is on you.

## English register in docs and prose

- Commas and full stops. No semicolons, no colons as mid-sentence
  connectives, no em-dashes.
- Head-first sentences. Main verb inside the first nine words by
  default.
- Specific names over generic role labels (`cxc` not `the compiler`).

## Scope boundaries

- `AGENTS.md` describes what to leave alone in detail. The short
  version: do not edit `raw/`, `compiler/research/`, `design/plans/`,
  `design/working-notes.md`, or any `.gitignore` entry; stage specific
  paths on commits, never `git add -A`.
- The language spec at `design/spec/spec-cxc-v1.0.md` is normative. A
  PR that changes language behaviour needs a matching spec edit.
- `docs/writing-corvid/` is opinionated and verified against the
  compiler before commit. Changes there require a compile check on
  every modified code block.

## Reporting a bug

- Include a minimal `.cxc` file that reproduces, if the bug is on the
  compile path.
- Include the `cards.m` diff or the clang error if the bug is on the
  emit path.
- The viewer tooling (`cxc trace view`, `cxc trace serve`) is
  undergoing active churn. If the bug is viewer-side, name the
  commit sha you built against.
