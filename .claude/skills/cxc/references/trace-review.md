# Trace-review reference

Routed from the `cxc` skill when the task is responding to open trace annotations left by a human reviewer.

## The loop

```
human in VS Code Simple Browser        agent in Claude Code / codex
  scrubs through the shape graph         sees the open annotations
  clicks node, edge, or event            via cxc trace annotation list
  writes a note, saves                   claims, acts, comments, closes
             │                                   │
             POST /annotations                   cxc trace annotation *
             │                                   │
             v                                   v
      <trace>.annotations.json  (JSON sidecar, committed in git)
```

See `docs/design/trace-annotation.md` for the schema and `.claude/skills/cxc/references/annotation-schema.md` for the full field reference.

## Status machine

```
   open ───────► in_progress ─────► addressed ─────► verified  (terminal)
     │                                 │
     │                                 ▼
     └──────────► wontfix (terminal)   wontfix (terminal)
     │
     └──────────► stale  (terminal, trace drift)
```

- `open` is what the human writes.
- `in_progress` carries `claimed_by` and `claimed_at`. Check-and-set, fails if already held by a different agent.
- `addressed` carries a `resolution` record with `kind: "fix_landed"` and a `linked_commit`.
- `wontfix` carries a `resolution` record with `kind: "wontfix"` and a reason summary.
- `verified` is human-driven in practice. The agent does not self-verify.
- `stale` is auto-set when `annotation.trace_sha256` does not match the current trace sha. Across-commit staleness is handled by git, not by auto-marking.

## Agent workflow

The default behavior when a human says "pick up the open annotations", "look at the trace, I left notes", or similar:

1. `cxc trace annotation list --status open` to see the queue.
2. Process in severity order, `bug` first, then `finding`, then `advisory`, then `praise`.
3. For each annotation:
   a. `cxc trace annotation claim <id>` to lock it. If the claim fails because someone else holds it, skip and move on.
   b. `cxc trace annotation show <id>` to read the full target context, including surrounding trace lines.
   c. Fix the code. Re-run the trace if the fix is in a hot path. Commit with a clear message.
   d. `cxc trace annotation comment <id> --text "<what you did and why>" --commit <sha>` to record the fix.
   e. `cxc trace annotation addressed <id> --commit <sha> --summary "<one-line description>"` to move the status.
4. If an annotation seems wrong (misread, non-issue, already fixed), leave a comment arguing the case and move on. Do NOT silently `wontfix`. Only mark `wontfix` after the human has seen the comment and does not push back.
5. Report back with counts: N addressed, M commented-and-paused, K wontfix. Do not include annotations silently closed, because you do not silently close them.

## Rules

- The viewer is the human's surface. Never open it, never navigate it with Playwright, never scroll it. Read focus with `cxc trace focus`, write annotations with the CLI.
- Author field defaults to the git user name when `--as` is not supplied. For agent runs, pass `--as "claude-code"` (or the equivalent agent id) so the comment thread shows who did what.
- The `linked_commit` on a comment is the specific commit that implemented the fix. The one on `resolution` is the same. If you make multiple commits for one annotation, use the one that would show up in `git log --grep <ann-id>` as the primary.
- Comments are the reasoning thread. Status is the lifecycle position. If you disagree with an annotation's severity or framing, leave a comment arguing the case. Do not conflate reasoning with lifecycle.
- Never git add -A in this repo. Stage specific paths. `compiler/research/` and `raw/` are permanently untracked, so the annotation sidecar there won't commit; move the trace and its sidecar to a tracked path if the loop needs persistence across sessions.

## Error paths and recovery

- `annotation already terminal`, meaning the annotation is `verified`, `wontfix`, or `stale`. The CLI refuses further writes. Leave the state alone, move on.
- `annotation already claimed by X`, meaning a different agent holds it. Skip. If X is you and it is stuck, pass `--as X` to re-assert the claim.
- `annotation not found`, meaning the id does not exist in the sidecar. Check that `--trace` points at the right trace file; the sidecar is resolved from the trace path.
- `no --trace specified and no *.trace.jsonl in cwd`, meaning the CLI cannot find a trace to key off. Pass `--trace <path>` explicitly.

## Export for a cross-session handoff

If the human wants to hand a frozen annotation set to a different agent (local Claude Code → cloud session, or Claude Code → codex), the viewer's "export" button copies an LLM markdown block containing all open annotations with their targets, notes, and proposed fixes. Paste that block into the receiving agent's prompt.

The CLI equivalent is `cxc trace annotation list --status open --format json` for machine-readable, or the markdown block from the viewer for human-pastable.
