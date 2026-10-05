# Plan, trace + annotation + agent loop

Written for: future me (or any session resuming this thread).
Status: committed plan. Supersedes the exploration in [trace-annotation-note.md](trace-annotation-note.md) for sequencing and architecture decisions.
Date: 2026-10-05.

Upstream: [compiler/research/trace-annotation-note.md](../../compiler/research/trace-annotation-note.md) carries the thesis, the five annotation kinds, the three ASCII mockups, and the worked example. Read it first for the "why" (research-local, gitignored). This file is the "what to build, in what order, with what shape", lives in the tracked design surface.

## Priority steer

- Annotation smoothness with agents is primary.
- Git integration is secondary. Defer the shape-diff viewer, trace-diff, and cross-commit tween until the annotation loop is in use.
- Single-language stack. Clojure for the server. Python one-liner retires. Node remains dev-only via Playwright for screenshots during viewer iteration.

## What has already landed

The playthrough transport bar is the time-cursor-over-graph from the note's mockup 1. The two capabilities the note described as unbuilt are built.

- Shape graph with ELK compound layout. Clickable atoms open the phase state machine.
- Playthrough transport, scrubber, play, pause, step, speed selector, edge-flash on current message.
- Dark theme, sidebar collapse, bottom-left legend above the transport.
- Single-file HTML, served by `python3 -m http.server :8767` today.

Milestones 1 and 2 from the research note are therefore done. Phases 3 and 4 (annotation layer and LLM export) are the shippable gap.

## Architecture

Three layers, borrowed from the hunk.dev pattern and lifted onto trace plus graph.

```
  +----------------------------+     +-------------------------+
  |  Simple Browser (human)    |     |  Claude Code / Codex    |
  |  trace-viewer.html         |     |  cxc skill              |
  |  scrub, click-to-annotate  |     |  routes to trace-review |
  +------------+---------------+     +-----------+-------------+
               |  POST /focus,                   |  cxc trace annotation
               |  POST /annotations              |  list|show|claim|comment|
               v                                 v  addressed|wontfix
            +----------------------------------------+
            |  cxc trace serve (Clojure, zero-dep)   |
            |  com.sun.net.httpserver                |
            |  static-file + two POST handlers       |
            +----------------------------------------+
                           ^
                           | writes
                           v
                 <binary>.annotations.json  (canonical state)
                 <binary>.focus.json        (ephemeral)
```

Daemon split. `cxc.daemon` keeps its compile-source-cache responsibility. `cxc.trace_server` is a new short-lived process for review sessions. Two orthogonal processes rather than one braided daemon.

VS Code integration. The viewer runs in VS Code's Simple Browser. The Claude Code chat sits next to it. Human annotates on the left, agent processes on the right, no context switch.

## Schema v1

Sidecar at `<binary>.annotations.json`. One file per trace.

```json
{
  "cxc_annotation_version": 1,
  "source": "cards",
  "commit": "ddcfe13abc...",
  "trace_sha256": "a1b2c3...",
  "annotations": [
    {
      "id": "ann_01",
      "kind": "event",
      "target": {"trace_seq": 142, "event_kind": "send", "atom": "Encounter[0x7f...]", "msg": "cast"},
      "note": "...",
      "severity": "bug",
      "proposed_fix": "...",
      "author": "dan",
      "created_at": "2026-10-05T10:30:00Z",
      "status": "open",
      "claimed_by": null,
      "claimed_at": null,
      "comments": [
        {"by": "claude-code", "at": "2026-10-05T10:35:00Z", "text": "...", "linked_commit": "abc123"}
      ],
      "resolution": null
    }
  ]
}
```

Enum values, locked.

- `kind`, one of `event`, `range`, `node`, `edge`, `expected_vs_actual`.
- `severity`, one of `bug`, `finding`, `advisory`, `praise`.
- `status`, one of `open`, `in_progress`, `addressed`, `verified`, `wontfix`, `stale`.

Lifecycle transitions.

```
   open ───────► in_progress ─────► addressed ─────► verified   (terminal)
     │                                 │
     │                                 ▼
     └──────────► wontfix (terminal)   wontfix (terminal)
     │
     └──────────► stale (terminal, trace shifted under it)
```

- `in_progress` carries `claimed_by` (agent id) and `claimed_at`. Check-and-set on claim, fail if already held.
- `addressed` carries a `resolution` record with `kind: "fix_landed"` and a `linked_commit`.
- `wontfix` carries a `resolution` record with `kind: "wontfix"` and a reason summary.
- `stale` detected automatically when `annotation.trace_sha256` does not match the current trace's sha256.
- `verified` is human-driven in practice. An auto-verify path can re-run the trace and check the proposed_fix, future enhancement.

Comments are the reasoning thread. Status is the lifecycle position. They are independent axes. An agent that disagrees with an annotation leaves a comment arguing the case rather than silently moving to `wontfix`.

Focus sidecar at `<binary>.focus.json`. Ephemeral, overwritten on each viewer action.

```json
{
  "trace": "cards.trace.jsonl",
  "atom": "Encounter[0x7f...]",
  "seq": 142,
  "selected_edge": null,
  "updated_at": "2026-10-05T10:30:12Z"
}
```

## Agent surface, `cxc trace *`

All subcommands live in a new `compiler/src/cxc/trace_annotations.clj`, dispatched from the existing `cxc` CLI entry.

Read.

- `cxc trace annotation list [--status X] [--severity Y] [--kind Z] [--format json|table]`
- `cxc trace annotation show <id>` -- full context, target plus surrounding trace lines plus shape of referenced atom
- `cxc trace focus` -- emits the focus sidecar

Write.

- `cxc trace annotate --from-focus --note "..." --severity bug --kind event [--proposed-fix "..."]`
- `cxc trace annotation claim <id>` -- open → in_progress, writes agent id as holder
- `cxc trace annotation comment <id> --text "..." [--commit <sha>]`
- `cxc trace annotation addressed <id> --commit <sha> --summary "..."`
- `cxc trace annotation wontfix <id> --reason "..."`
- `cxc trace annotation verified <id>` -- human-only in practice

Server.

- `cxc trace serve [--port 8767] [--trace-dir <path>]`

Export.

- `cxc trace export --format markdown [--status open]` -- renders the LLM markdown block from the sidecar

## Skill bundle

One `cxc` skill under `.claude/skills/cxc/`. SKILL.md is a router plus the Zen plus always-apply rules. The depth lives in `references/`.

```
.claude/skills/cxc/
  SKILL.md                       # router + Zen + shared discipline
  references/
    authoring.md                 # merged from current cxc-authoring
    cleanup-audit.md             # merged from current cxc-cleanup-audit
    trace-review.md              # the annotation loop, status machine, patterns
    annotation-schema.md         # the sidecar schema
    cli-reference.md             # cxc trace * subcommand table
```

Router shape, SKILL.md picks a sub-reference based on task.

- Writing or refactoring .cxc source → `references/authoring.md`
- Auditing a .cxc file → `references/cleanup-audit.md`
- Responding to open trace annotations → `references/trace-review.md`

A task that spans concerns loads both references in order.

Migration. The existing `cxc-authoring/SKILL.md` and `cxc-cleanup-audit/SKILL.md` move under `cxc/references/` with frontmatter stripped. The old paths become thin forwarders for one release cycle, then retire.

## Server, zero-dep Clojure

New file `compiler/src/cxc/trace_server.clj`. Uses `com.sun.net.httpserver` from the JDK, no new deps.

Handlers.

- `GET /` and children -- static file serve for the viewer HTML and siblings, rooted at `--trace-dir`
- `POST /annotations` -- write the annotation payload to the sidecar, overwriting; returns 200 on success
- `POST /focus` -- write the focus payload, overwriting
- `GET /annotations` and `GET /focus` -- for the viewer to re-sync after agent writes

Replaces `python3 -m http.server :8767` in the dev loop. Boots as `cxc trace serve`. Localhost single-user, no auth.

## Phases

```
Phase 0 — schema freeze                       (no code, enables everything)
Phase 1 — thin cut (CLI read-only)            (one session, no deps)
Phase 2 — lifecycle writes                    (depends on 1)
Phase 3 — skill bundle                        (independent, can land anytime after 1)
Phase 4 — Clojure server replaces python      (depends on 1)
Phase 5 — viewer click-to-annotate UI         (depends on 4)
Phase 6 — dogfood                             (depends on 1-5)
Phase 7+ — git picker + tween                 (deferred, revisit after loop in use)
```

### Phase 0, schema freeze

- Promote this file as the canonical schema reference.
- Lock the five annotation kinds, the four severity values, the six status values, the lifecycle transitions, the comment and resolution record shapes.
- Lock the two sidecar files, `<binary>.annotations.json` and `<binary>.focus.json`.

Exit criterion. Schema is written, no further edits expected until Phase 2 lifecycle reveals a gap.

### Phase 1, thin cut

- `compiler/src/cxc/trace_annotations.clj` with two subcommands.
  - `cxc trace annotation list [--status open]`
  - `cxc trace annotation show <id>`
- Both read-only, no server, no POST path, no viewer UI.
- Dispatcher wiring in `compiler/src/cxc/core.clj`.
- Skill stub at `.claude/skills/cxc/references/trace-review.md` with the quick card.
- Test input, a hand-written annotations.json alongside `compiler/research/sample-01-single-atom.trace.jsonl`.

Exit criterion. Agent run against a hand-written annotations file produces a sensible "here is what I would do" response.

### Phase 2, lifecycle writes

- Add status-write subcommands, `claim`, `comment`, `addressed`, `wontfix`.
- Check-and-set semantics on `claim`.
- Stale detection, compare `annotation.trace_sha256` to current trace sha, mark stale on mismatch.
- Resolution record populated on terminal transitions.
- Comment thread append semantics.

Exit criterion. Agent can process a full annotation set, each annotation ends in a terminal state with a comment trail.

### Phase 3, skill bundle

- Create `.claude/skills/cxc/SKILL.md` as router plus Zen plus always-apply rules.
- Move `cxc-authoring/SKILL.md` content to `cxc/references/authoring.md`.
- Move `cxc-cleanup-audit/SKILL.md` content to `cxc/references/cleanup-audit.md`.
- Expand `cxc/references/trace-review.md` with the full status machine, patterns, worked examples.
- Add `cxc/references/annotation-schema.md` (link to this plan, or inline the schema section).
- Add `cxc/references/cli-reference.md` (the subcommand table).
- Replace the two old skill files with one-line forwarders.

Exit criterion. `/cxc` or any Corvid task loads the new bundle. The two old skills still work via forwarder.

### Phase 4, Clojure server replaces python

- `compiler/src/cxc/trace_server.clj`, zero-dep via `com.sun.net.httpserver`.
- Static-file handler.
- POST `/annotations` and POST `/focus`.
- GET `/annotations` and GET `/focus` for viewer re-sync.
- `cxc trace serve` entry point in `core.clj`.
- Retire the python one-liner from any dev script that still mentions it.

Exit criterion. `cxc trace serve` boots, Simple Browser renders the viewer, POSTs land on disk, GETs re-sync the viewer after agent writes.

### Phase 5, viewer click-to-annotate UI

- Click on any node, edge, or scrubber event marker opens the annotation popup.
- Popup fields, `kind` (defaulted from click target), `note`, `severity`, `proposed_fix`, save/cancel.
- On save, POST to `/annotations`. On success, re-render marker on the graph.
- Status badges on annotated elements.
  - `open` red
  - `in_progress` amber
  - `addressed` green outline
  - `verified` green with checkmark
  - `wontfix` grey
  - `stale` grey with dashed stroke
- "Export annotations" button, copies the LLM markdown block to clipboard.
- Focus sync, viewer POSTs `/focus` on scrubber move and node click.

Exit criterion. Human annotates in Simple Browser, agent reads via `cxc trace annotation list`, acts, writes status, viewer updates on next poll or re-render.

### Phase 6, dogfood

- Pick a real trace, probably `compiler/research/sample-01-single-atom.trace.jsonl`.
- Human writes two or three real annotations against observed behavior.
- Agent processes, lands fixes against a scratch commit, closes the annotations.
- Capture the loop as a short walkthrough in `docs/trace-review.md`.

Exit criterion. One complete annotation to fix to verify cycle executed end-to-end without manual plumbing.

### Phase 7+, deferred

- Git picker and shape-diff view, mockup 2 from the research note.
- Trace-diff with divergence point.
- Tween across commits.
- MCP server as an alternative surface if codex integration grows.
- Websockets for push-based viewer updates if poll latency becomes a problem.

## Dependencies

```
Phase 0 (schema)
   │
Phase 1 (thin cut, CLI)  ──────┐
   │                           │
   ├──► Phase 2 (lifecycle)    │
   │                           │
   ├──► Phase 4 (server)       │
   │                           ▼
   └──► Phase 5 (viewer UI) ──► Phase 6 (dogfood)

Phase 3 (skill bundle)   independent, can land anytime after Phase 1
```

## Scope at a glance

- New Clojure code, approximately 400 lines across `trace_annotations.clj`, `trace_server.clj`, and `core.clj` subcommand dispatch.
- New viewer code, approximately 200 lines for the annotation popup, status badges, focus POSTs.
- One schema doc (this file), one CLI reference, one skill-router SKILL.md, three references.
- No new runtime deps.
- Retires the python one-liner and consolidates two existing skills.

## Decisions (resolving the open questions)

Resolved 2026-10-05.

1. **Access control is git.** Annotations are JSON files committed alongside code. Anyone who can commit code can commit annotations. PR review is the gating step. No separate access-control model.

2. **Versioning is git.** The annotation sidecar lives next to the trace file at `<path>/<name>.annotations.json` and is tracked in git. `git checkout <commit>` gives the state as of that commit. `git log <file>` is the history. `git blame` is the author trail. The schema's "who/when" fields become mostly ephemeral, kept as cheap atomic signals within a working-copy session, and the real audit trail is the commit history.

   Within-commit drift (trace regenerated non-deterministically at the same code) still gets flagged via optional `trace_sha256` comparison; this surfaces non-determinism in the trace, which is the actual bug when it happens. Across-commit staleness is not a schema concern; it is a merge-rebase concern that git already handles.

3. **Single-file HTML stays, through Phase 6.** `trace-viewer.html` carries HTML, CSS, and JS inline. Current line count is 2466. Phase 5 adds roughly 200 lines for the annotation popup and status badges. Revisit if the viewer passes 3000 lines, split the JS to a sibling `trace-viewer.js` first (CSS and HTML stay in the HTML). Do not pull in a framework (Svelte, Lit, etc.) until the single-file maintenance cost is proven.

4. **Multi-trace scope via picker.** `cxc trace serve --trace-dir <path>` scans `*.trace.jsonl` under the dir. The viewer sidebar carries a trace picker, dropdown style. If only one trace is present, auto-select. Each trace has its own annotation sidecar next to it. CLI scopes to one trace via `--trace <path>` flag, defaulting to the one named in the current focus sidecar.

## Schema updates from the git-versioning decision

- `trace_sha256` on the top-level sidecar remains, used for within-commit drift detection. Mismatch at read time triggers a warning, not an auto-status-change.
- `stale` as a status value stays in the enum but is used sparingly, only when within-commit trace drift is detected. Across-commit staleness is handled by git history, not by auto-marking.
- `claimed_by` and `claimed_at` are kept as cheap atomic signals for within-session concurrency (two agents hitting the same annotation during one working-copy session). The real "who did what" lives in git log.
- `author` on annotations and `by` on comments default to the git user name when not supplied, read from `git config user.name`.
- `linked_commit` on comments and resolution records stays, since it points at the specific fix.
- Sidecar path convention locks in. Trace at `path/<name>.trace.jsonl` → annotation at `path/<name>.annotations.json`. Both committed together.

## Dismissals

- MCP server, deferred until a second agent surface (codex) is used in anger against this loop.
- Websockets, deferred until poll latency becomes a real friction, not before.
- Authentication on the server, out of scope, localhost single-user.
- Multi-reviewer sync, deferred until the single-user flow is proven.
- Benchmarks on JDK HttpServer versus http-kit, deferred until throughput is a bottleneck.
- Playwright screenshot harness staying Node, kept as dev-only, not shipped, no stack-coherence violation at the ship surface.

## Immediate next move

Start Phase 0 (schema freeze is this document) and Phase 1 (CLI thin cut) in the same session. Together they cost one session and prove the agent surface against a hand-written annotation. Everything after that layers on with the loop visible end-to-end.
