---
name: cxc
description: Everything Corvid. Authoring, auditing, trace annotation review. Load this skill for any `.cxc` work or any task involving the cxc toolchain.
---

# cxc

Single skill bundle for the Corvid language and its tooling. Routes to one of three sub-concerns based on the task at hand.

## Routing

- Writing or refactoring .cxc source, or answering "how do I express X in Corvid" → load `references/authoring.md`.
- Auditing an existing .cxc file against the anti-pattern catalog → load `references/cleanup-audit.md`.
- Responding to open trace annotations left by a human reviewer → load `references/trace-review.md`.

A task that spans two concerns, e.g. refactor driven by a trace annotation, loads both references in order. Start with the one that defines the task's primary verb.

Supporting references:

- `references/annotation-schema.md`, the full annotation sidecar schema and lifecycle, used by both the trace-review flow and anyone reading the raw JSON.
- `references/cli-reference.md`, the full `cxc *` subcommand table.

## Always-apply rules

These apply to every Corvid task regardless of which reference the agent routes to.

### The Zen of Corvid (abbreviated)

Full write-up in `references/authoring.md`.

1. **Atoms are modules.** State lives inside, access from outside is only through named observations and transitions.
2. **Every state change has a name.** All mutation flows through `<: msg -> phase` arms.
3. **Derive, don't cache.** Observations are pure functions of current state, re-computed on read.
4. **One atom until pressure.** Default to a single `def main:` atom holding all state and transitions. Split on ownership, lifecycle, or capability-scope pressure.
5. **Verbs are transitions, not atoms.** Fold caller-callee pairs back into transitions on the owner atom.
6. **Records hold data. Atoms hold behavior.** No observations inside `def Record:` bodies.
7. **Explicit capabilities are the whole point.** Capability dependencies must appear in the atom's declaration surface.
8. **Fail loudly at the boundary.** Fallible capabilities raise typed records, recover at the call site with `or T():`.

### Operational discipline

- **Compile before you commit.** `cxc compile` is the productive loop; the parser rejects more than the type checker. Read compiler messages literally.
- **Never git add -A in this repo.** Stage specific paths. `compiler/research/` and `raw/` are permanently untracked.
- **Kill the daemon on compiler-source edits.** `cxc.daemon` caches loaded source. After editing anything under `compiler/src/`, run `pkill -f cxc.daemon` or set `CXC_NO_DAEMON=1`.

### Spec and version discipline

See `references/authoring.md` for the full list. Highlights:

- No shape variables. Monomorphization only.
- No annotations grammar (`@name(args)`). Deprecation via comments.
- Pipe-colon `|:` as the unified four-position guard.
- Observation glyph `:-`.
- Nullability, `T` nullable, `T!` non-nil, `T?` banned.
- Dispatch via `<: msg -> phase` and `||:` multi-arm.
- Containers are FP, `.append` / `.filter` / `.assoc` / `.insert`, not `.add` / `.remove`.

## Quick card, trace-review path

Common path short enough to inline here so the agent does not need a second file load.

1. `cxc trace annotation list --status open`
2. For each annotation, in severity order (bug first):
   a. `cxc trace annotation claim <id> --as "claude-code"`
   b. `cxc trace annotation show <id>` to read target + trace context.
   c. Fix the code. Compile. Re-run the trace if needed. Commit.
   d. `cxc trace annotation comment <id> --text "<what you did>" --commit <sha> --as "claude-code"`
   e. `cxc trace annotation addressed <id> --commit <sha> --summary "..." --as "claude-code"`
3. If an annotation seems wrong, leave a comment arguing the case. Do NOT silently `wontfix`.
4. Report back: N addressed, M commented-and-paused, 0 silently closed.

Full workflow and the lifecycle state machine live in `references/trace-review.md`.

## When NOT to load this skill

- Pure compiler-internals work (Clojure code under `compiler/src/`). That has its own conventions.
- Infra, build scripts, or non-Corvid tooling that happens to live in the repo.
- Reading the trace JSONL itself for debugging the trace emission path (not for review).

## Migration note

This skill supersedes the earlier `cxc-authoring` and `cxc-cleanup-audit` skills, which now exist as thin forwarders pointing at this bundle. Future references extending the Corvid surface add their own `references/<topic>.md` file and get wired into the routing section above.
