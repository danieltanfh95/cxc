# Trace-review reference

Routed from the `cxc` skill when the task is responding to open trace annotations left by a human reviewer.

## The loop

```
human in viewer                      cxc trace serve             agent in any
                                     (sidecar watcher)           corvid-cwd claude
                                                                 session
  clicks a thing in the graph
  types a note
  Cmd+Enter sends OR Cmd+Shift+Enter saves draft
             │
             POST /annotations ─► sidecar on disk
                                       │
                                       │ fs-watch
                                       ▼
                              diff vs last snapshot: any draft→open?
                                       │ yes
                                       ▼
                              generate 6-char token per annotation
                              append pending comment to sidecar
                              fan-out to /tmp/cc-socks/<pid>.sock
                              where pid's cwd is under the project root
                                                                    │
                                                                    ▼
                                                            agent writes reply in chat
                                                            INCLUDING the reply-token
                                                            calls `cxc trace annotation
                                                            addressed <id> --summary ...`
                                                                    │
viewer polls sidecar every 2s                                       │
sees pending comment with token ◄───────────────────────────────────┘
calls GET /chat-excerpt?token=X
server greps ~/.claude/projects/*/*.jsonl
renders matched assistant turns under
the annotation in the detail pane
```

See `docs/design/trace-annotation.md` for the schema and `.claude/skills/cxc/references/annotation-schema.md` for the full field reference. See the "Push-driven workflow" section below for the push mechanism specifically.

## Status machine

```
   draft ─(human submits review)─► open ──► in_progress ──► addressed ──► verified  (terminal)
                                     │                           │
                                     │                           ▼
                                     └──────────► wontfix (terminal)
                                     │
                                     └──────────► stale  (terminal, trace drift)
```

- `draft` is the human's in-progress state. Written only by the viewer, hidden from `cxc trace annotation list` by default (opt in with `--include-drafts` or `--status draft`). The agent must not act on drafts.
- `open` is what reaches the agent. Either the human submitted a review (which bulk-flips drafts to open) or wrote the annotation structured from the start.
- `in_progress` carries `claimed_by` and `claimed_at`. Check-and-set, fails if already held by a different agent.
- `addressed` carries a `resolution` record with `kind: "fix_landed"` and a `linked_commit`.
- `wontfix` carries a `resolution` record with `kind: "wontfix"` and a reason summary.
- `verified` is human-driven in practice. The agent does not self-verify.
- `stale` is auto-set when `annotation.trace_sha256` does not match the current trace sha. Across-commit staleness is handled by git, not by auto-marking.

## Agent workflow

Human annotations read as *"there is something here, verify"* or *"what do you think about this"* — the human may be reporting a bug, asking a question, requesting a change, or thinking out loud. The agent decides intent from the note text and attachments. The agent treats every open annotation with equal weight, independent of severity. Severity is a human-authored field for the human's own tracking (which items to prioritise, which to defer), not a scheduling signal for the agent.

### Push-driven workflow (primary)

When `cxc trace serve` is running on the human's machine, submitted annotations arrive as peer-source inbox messages in your context automatically. You do not poll. The push shape:

```
Another Claude session sent a message while you were working:
[cxc-trace] 2 annotation(s) submitted on <trace-base>:

- ann_06 · atom=Hand
  <freeform human note, possibly with [image path] or [link url] attachments>

- ann_07 · seq=142 atom=Encounter
  <freeform human note>

Use `cxc trace annotation` subcommands to work the review; the trace-review skill documents the full loop.
```

(Framed by Claude Code with its own peer-safety guardrails.) The sender is `cxc trace serve`, not another claude binary, but the harness treats any peer message that way.

On receiving a push:

1. **Acknowledge in-chat immediately** with a short "I'm looking into these N things, give me a moment." This is the handoff signal the human expects when they hit submit. Do not stay silent while investigation runs.
2. **Fan out investigation via parallel subagents.** For each annotation in the push that is NON-OBVIOUS (you can't answer from reading the note alone), spawn an `Agent` with `subagent_type: general-purpose` or `Explore` in a single message with multiple tool_use blocks so they run concurrently. The subagent prompt should include the ann_id, target (atom/event/edge), the human's note, and ask the subagent to pull trace context, read surrounding source, and return a short summary of what's actually happening versus what the human may have expected. Keep subagent prompts tight; they return summaries, not transcripts, which keeps your main-thread context intact.
3. **Compose a single response** that walks each annotation in turn. For questions, answer. For bug reports, say whether you confirmed the issue and what the fix shape would be. For requests, say what you'd do and ask for confirmation before landing. For thinking-out-loud notes, engage with the thought. **Include the push's `reply-token` naturally in your response** — the viewer greps your chat transcript for that token and renders your reply inline under the annotation. The token looks like `k2jq8f`; drop it in a heading, in prose, or at the top of each per-annotation section. Any assistant turn containing the token will be surfaced.
4. **Chat continues in the main thread** if the human wants to discuss. Each discussion turn is normal in-chat conversation; the annotation is the anchor, not the boundary. Follow-up turns can repeat the token to append to the viewer's rendered thread.
5. **Close the annotation** when a conclusion is reached: `cxc trace annotation addressed <id> --summary "<one-line>"`. Do NOT also call `cxc trace annotation comment --text "<the full answer>"` — the full answer lives in chat and the viewer resolves it via the token. Writing a `comment` is only needed for CLI-driven flows without a token (fallback workflow below).

### CLI-driven workflow (fallback)

When `cxc trace serve` isn't running (viewer-only mode, remote session, replaying an older trace), the human will say "pick up the open annotations", "look at the trace, I left notes", or similar. In that case:

1. `cxc trace annotation list --status open` to see the queue.
2. Process in id order (FIFO) unless the human asks for a different sequencing.
3. For each annotation, the same per-annotation lifecycle below applies.

### Per-annotation lifecycle

a. `cxc trace annotation claim <id>` to lock it. If the claim fails because someone else holds it, skip and move on.
b. `cxc trace annotation show <id>` to read the full target context, including surrounding trace lines.
c. Verify the human's suspicion against the trace and the source. The annotation is a *flag* or a *question*, not a diagnosis — the agent decides whether a real issue exists and what response fits the human's intent.
d. If a real issue exists AND the human wanted a fix, fix the code, re-run the trace if the fix is in a hot path, commit with a clear message, then `cxc trace annotation addressed <id> --commit <sha> --summary "<one-line description>"`. In the push-driven workflow the reply-token in chat carries the body; add `--text` only on CLI-only paths without a token.
e. If the annotation was a question, answer in-chat (with the reply-token so the viewer picks it up), then `cxc trace annotation addressed <id> --summary "answered: <one-line>"`. No `--text` needed — the chat body IS the answer.
f. If verification finds no real issue, say so in chat (with the token). Do NOT silently `wontfix`. Only mark `wontfix` after the human has seen your chat reply and does not push back.

### Reporting back

Report with counts: N addressed, M commented-and-paused, K wontfix. Do not include annotations silently closed, because you do not silently close them. On channel-driven workflows the human will see each resolution flow into the viewer sidebar as the status changes, so the terminal report is a short recap, not a full enumeration.

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

## Push setup (one-time)

The human runs:

```
cxc trace serve --trace-dir <dir>
```

This hosts the viewer, serves sidecar reads/writes, AND watches the sidecar for draft→open transitions. Each transition fans out to every live `/tmp/cc-socks/<pid>.sock` on the machine as a plain user-role message. No MCP, no `.mcp.json`, no dev flag.

The agent does NOT run `cxc trace serve` itself. The human owns setup; the agent only reads what arrives in its inbox.

Two independent processes end up running (plus the browser):

- `cxc trace serve` (viewer host + sidecar POST handler + inbox-socket poster)
- `claude` (any live Claude Code session, plain `claude`)

Both write to or read the SAME sidecar file. The server's filesystem watcher catches sidecar changes regardless of who wrote them (viewer POST, CLI write, git pull).

**Receiver-side detail**: `cxc trace serve` auto-merges `{"crossSessionInbound":"accept"}` into `~/.claude/settings.json` on first run so bypassPermissions-mode sessions deliver pushes immediately instead of silently holding them. The merge is idempotent and leaves an explicit non-`accept` value alone. The human can pass `--no-accept-peer-inbound` to opt out.

## Export for a cross-session handoff

If the human wants to hand a frozen annotation set to a different agent (local Claude Code → cloud session, or Claude Code → codex), the viewer's "export" button copies an LLM markdown block containing all open annotations with their targets, notes, and proposed fixes. Paste that block into the receiving agent's prompt.

The CLI equivalent is `cxc trace annotation list --status open --format json` for machine-readable, or the markdown block from the viewer for human-pastable.
