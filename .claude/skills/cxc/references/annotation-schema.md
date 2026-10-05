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
- `kind`: one of `event`, `range`, `node`, `edge`, `expected_vs_actual`.
- `target`: shape depends on `kind`, see below.
- `note`: free-text, required.
- `severity`: one of `bug`, `finding`, `advisory`, `praise`.
- `proposed_fix`: optional free-text with a suggested change.
- `author`: who created the annotation. Defaults to git `user.name`.
- `created_at`: ISO 8601 timestamp.
- `status`: one of `open`, `in_progress`, `addressed`, `verified`, `wontfix`, `stale`. Missing status is treated as `open`.
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
- `text`: free-text, required.
- `linked_commit`: optional git sha pointing at the commit the comment references.

## Resolution record

```json
{
  "by": "claude-code",
  "at": "2026-10-05T10:40:00Z",
  "kind": "fix_landed",
  "linked_commit": "abc1234",
  "summary": "guarded cast arm on c.id != sentinel.id"
}
```

- `by`: who resolved.
- `at`: ISO 8601 timestamp.
- `kind`: one of `fix_landed`, `wontfix`.
- `linked_commit`: required for `fix_landed`, absent for `wontfix`.
- `summary`: one-line description of what was done or why not.

When a human verifies an addressed annotation, the resolution record gains `verified_by` and `verified_at` fields alongside the existing `by`/`at`.

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
