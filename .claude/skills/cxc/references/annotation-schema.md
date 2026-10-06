# Annotation schema reference

Schema v1, locked 2026-10-05 via `docs/design/trace-annotation.md`. The authoritative design doc carries the rationale; this reference carries the field list and the lifecycle.

## Sidecar file

Path convention: trace at `path/<name>.trace.jsonl` → annotations at `path/<name>.annotations.json`. Both tracked in git when the trace is tracked.

```json
{
  "cxc_annotation_version": 1,
  "source": "cards",
  "commit": "ddcfe13abc...",
  "trace_sha256": "a1b2c3...",
  "annotations": [ /* array of annotation records */ ]
}
```

- `cxc_annotation_version`: integer, 1 for the current schema.
- `source`: the source binary or module name. Free-text.
- `commit`: git sha of the code the trace was generated from. Optional, auto-populated by the server on first write when `git` is available.
- `trace_sha256`: sha256 of the trace file. Optional. Used for within-commit drift detection.
- `annotations`: array of annotation records, newest appended at the end.

## Annotation record

```json
{
  "id": "ann_01",
  "kind": "event",
  "target": { ... },
  "note": "...",
  "attachments": [],
  "severity": "bug",
  "proposed_fix": "...",
  "author": "dan",
  "created_at": "2026-10-05T10:30:00Z",
  "status": "open",
  "claimed_by": null,
  "claimed_at": null,
  "comments": [],
  "resolution": null
}
```

- `id`: `ann_NN` where NN is a 2-digit zero-padded sequence number.
- `kind`: one of `event`, `range`, `node`, `edge`, `expected_vs_actual`. Describes the TARGET shape (what the annotation points at), not intent. The agent interprets intent (question, bug report, task, thinking-out-loud) from the note text.
- `target`: shape depends on `kind`, see below.
- `note`: free-text, required.
- `attachments`: optional array. Each entry is `{"kind": "image", "path": "..."}` where path is relative to the trace-dir and lives under `<name>.annotations.assets/`, OR `{"kind": "link", "url": "..."}` for inlined references. Images are uploaded via `POST /annotations-asset?trace=<name>` with raw image bytes and the appropriate `Content-Type: image/*`.
- `severity`: optional, human-only. One of `bug`, `finding`, `advisory`, `praise`. The agent does not use severity for scheduling; it treats every open annotation with equal weight.
- `proposed_fix`: optional free-text with a suggested change.
- `author`: who created the annotation. Defaults to git `user.name`.
- `created_at`: ISO 8601 timestamp.
- `updated_at`: optional ISO 8601 timestamp, set when a draft or open annotation is edited.
- `submitted_at`: optional ISO 8601 timestamp, set when the human submits a draft (bulk-flip or send-now).
- `status`: one of `draft`, `open`, `in_progress`, `addressed`, `verified`, `wontfix`, `stale`. Missing status is treated as `open`. `draft` is the human's in-progress state — written by the viewer, never visible to the agent via `cxc trace annotation list` unless `--include-drafts` is passed. The "submit review" action in the viewer bulk-flips drafts to `open` and stamps `submitted_at`.
- `claimed_by`: agent id when status is `in_progress`.
- `claimed_at`: ISO 8601 timestamp when claim happened.
- `comments`: array of comment records. See below.
- `resolution`: resolution record when status is terminal. See below.

## Target shapes

- `event`: `{"trace_seq": 142, "event_kind": "send", "atom": "Encounter[0x7f...]", "atom_cls": "Encounter", "msg": "cast"}`. At least `trace_seq` is required.
- `range`: `{"trace_seq_start": 142, "trace_seq_end": 158}`. Both required.
- `node`: `{"atom": "Encounter", "phase": "running"}`. `phase` optional.
- `edge`: `{"from_atom": "main", "to_atom": "Encounter", "msg": "start"}`. All three required.
- `expected_vs_actual`: same shape as `event`, plus annotation-level `expected` and `actual` fields alongside `note`.

## Comment record

```json
{
  "by": "claude-code",
  "at": "2026-10-05T10:35:00Z",
  "text": "fixed in slice-at-depth refactor",
  "linked_commit": "abc1234"
}
```

- `by`: who wrote the comment.
- `at`: ISO 8601 timestamp.
- `text`: free-text, required for persisted comments.
- `linked_commit`: optional git sha pointing at the commit the comment references.
- `kind`: optional. Omitted for ordinary comments. `"pending"` marks a comment written by `cxc trace serve` on push, carrying a `token` for the viewer to resolve to a chat excerpt.
- `token`: present when `kind == "pending"`. A 6-char base36 string the agent includes in its chat reply. The viewer greps `~/.claude/projects/*/*.jsonl` for the token and renders matched assistant turns inline in place of `text`. A `pending` comment has no `text`.

A `pending` comment is a POINTER into the agent's chat transcript. It stays a pointer until `cxc trace annotation freeze` resolves it to an inlined `text` body (useful before committing the review to git, so a reader without access to the chat transcript still sees the full exchange).

## Resolution record

Resolution provenance is flat: `by`/`at` identify the original resolver;
`verified_by`/`verified_at` identify the later human verification. There is
no nested provenance object.

The viewer's **mark addressed** action sets annotation `status` to
`addressed` and writes:

```json
{
  "by": "human",
  "at": "2026-10-05T10:40:00Z",
  "kind": "fix_landed",
  "summary": "guarded cast arm on c.id != sentinel.id"
}
```

Agent-written `fix_landed` records can also carry `linked_commit`, as in
existing sidecars. The viewer does not require or invent a commit sha.
**Mark verified** (including bulk verification) sets annotation `status`
to `verified`, preserves the existing resolution fields, changes `kind`
to `verified`, and adds verification provenance. For an agent-addressed
record, the result is:

```json
{
  "by": "claude-code",
  "at": "2026-10-05T10:40:00Z",
  "kind": "verified",
  "linked_commit": "abc1234",
  "summary": "guarded cast arm on c.id != sentinel.id",
  "verified_by": "human",
  "verified_at": "2026-10-05T10:45:00Z"
}
```

If the addressed record has no resolution, verification defaults `by` to
`human` and `at` to the verification time before adding `verified_by` and
`verified_at`.

The viewer's **wontfix** action sets annotation `status` to `wontfix` and
replaces the resolution with:

```json
{
  "by": "human",
  "at": "2026-10-05T10:40:00Z",
  "kind": "wontfix",
  "reason": "the current behavior is intentional",
  "summary": "the current behavior is intentional"
}
```

- `by`: who originally resolved; `human` for viewer-written resolutions.
- `at`: ISO 8601 timestamp of the original resolution.
- `kind`: one of `fix_landed`, `verified`, `wontfix`. Viewer verification
  uses `verified`; older verified sidecars may retain `fix_landed`.
- `linked_commit`: optional git sha, retained when verifying an existing
  resolution. Agent-written `fix_landed` records conventionally include it;
  viewer-written addressed records omit it.
- `summary`: trimmed description of the fix, or the wontfix explanation.
- `reason`: trimmed wontfix explanation, duplicated in `summary` by the
  viewer. Existing records may have just `summary` or just `reason`;
  the viewer displays either.
- `verified_by`, `verified_at`: verifier and ISO 8601 verification timestamp;
  the viewer sets these to `human` and the current time.

Each viewer resolution action also stamps annotation-level `updated_at`.
Existing `fix_landed`/`wontfix` records remain readable without migration;
annotation `status` controls lifecycle eligibility, not resolution `kind`.

## Focus sidecar

Ephemeral, overwritten on each viewer action.

```json
{
  "trace": "cards.trace.jsonl",
  "atom": "Encounter[0x7f...]",
  "seq": 142,
  "selected_edge": null,
  "updated_at": "2026-10-05T10:30:12Z"
}
```

Sits at `<trace-dir>/cxc-trace-focus.json`. Read by `cxc trace focus`, written by the server on each POST `/focus`.
