# Trace review walkthrough

A dogfood run of the trace annotation loop against `sample-01-single-atom`. Shows one annotation moving from `open` through `addressed` to `verified`, and another closing as `wontfix` through reasoned disagreement. See `docs/design/trace-annotation.md` for the architecture and `.claude/skills/cxc/references/trace-review.md` for the full agent-side workflow.

## Setup

The sample lives at `compiler/research/sample-01-single-atom.*` (gitignored research corpus). It carries three files:

- `sample-01-single-atom.trace.jsonl`, 26 events from a Counter atom running `bump`, `reset`, `report`.
- `sample-01-single-atom.shape.json`, the compiled shape.
- `sample-01-single-atom.annotations.json`, five hand-written annotations exercising all five `kind` values.

Copy the three to a scratch directory so the research corpus stays clean:

```
cp compiler/research/sample-01-single-atom.annotations.json /tmp/sample.annotations.json
cp compiler/research/sample-01-single-atom.trace.jsonl      /tmp/sample.trace.jsonl
cp compiler/research/sample-01-single-atom.shape.json       /tmp/sample.shape.json
```

Boot the viewer:

```
cxc trace serve --port 8769 --trace-dir /tmp
```

Open `http://localhost:8769/trace-viewer.html?trace=sample.trace.jsonl&shape=sample.shape.json` in VS Code's Simple Browser.

## The initial state

The viewer sidebar shows ANNOTATIONS (5), with OPEN 5 as the only status chip. All five sample annotations default to `open` because the sample sidecar was authored before the status field existed; missing status reads as `open`.

Each annotation lands on the sidebar with a severity dot, kind label, and the first 90 characters of the note. The Counter atom in the shape graph carries a small red badge in the top-right corner reading "3", one dot per annotation that targets Counter by name or by atom_cls.

## Agent processes ann_02 (open → verified)

ann_02 is a `finding` on `event` kind, targeting `trace_seq=6` where `n: 0 → 1` after the first bump. The human noted the three-event signature (`guard_eval` → `arm` → `field_write`) is the canonical bump footprint and worth tracking.

Agent step 1, claim:

```
cxc trace annotation claim ann_02 --trace /tmp/sample.trace.jsonl --as "claude-code"
→ ann_02 claimed by claude-code
```

Status moves `open → in_progress`. The sidecar now carries `claimed_by: "claude-code"` and `claimed_at` timestamp. A different agent attempting to claim ann_02 now gets `annotation ann_02 already claimed by claude-code`.

Agent step 2, show the full context:

```
cxc trace annotation show ann_02 --trace /tmp/sample.trace.jsonl
```

Prints the annotation plus the three trace lines around `seq=6`. The agent sees the canonical signature directly from the trace.

Agent step 3, comment:

```
cxc trace annotation comment ann_02 \
  --text "Confirmed the guard_eval(seq=4) + arm(seq=5) + field_write(seq=6) footprint is the canonical bump shape, verified against lower.clj emit path. No change needed to lower, worth hoisting as a diff-view regression anchor." \
  --commit 86025c9 \
  --trace /tmp/sample.trace.jsonl \
  --as "claude-code"
→ commented on ann_02 as claude-code
```

The comment appends to the annotation's `comments` array with `by`, `at`, `text`, and `linked_commit`. The linked_commit is optional on comments but recommended when the comment references a specific fix.

Agent step 4, addressed:

```
cxc trace annotation addressed ann_02 \
  --commit 86025c9 \
  --summary "footprint confirmed, flagged for diff-view anchor use" \
  --trace /tmp/sample.trace.jsonl \
  --as "claude-code"
→ ann_02 marked addressed, commit=86025c9
```

Status moves `in_progress → addressed`. A `resolution` record lands with `kind: "fix_landed"`, `by: "claude-code"`, `linked_commit`, and the one-line summary.

Human step, verify:

```
cxc trace annotation verified ann_02 --trace /tmp/sample.trace.jsonl --as "dan"
→ ann_02 marked verified
```

Status moves `addressed → verified`. The resolution record gains `verified_by: "dan"` and `verified_at`. ann_02 is now terminal, further writes refuse.

## Agent processes ann_04 (open → wontfix)

ann_04 is a `finding` on `expected_vs_actual`, flagging a UI concern about distinguishing observation calls from transition arms in the diff view's icon palette. The human noted it is not a real bug for Counter, just a surface flag.

Agent step 1, claim:

```
cxc trace annotation claim ann_04 --trace /tmp/sample.trace.jsonl --as "claude-code"
→ ann_04 claimed by claude-code
```

Agent step 2, read the target. The target points at `seq=25`, where `arm=0` fires on `report`. The agent considers the surrounding events via `cxc trace annotation show ann_04`.

Agent step 3, reasoned response via comment:

```
cxc trace annotation comment ann_04 \
  --text "Reviewed. The viewer already distinguishes observation calls from transition arms via the obs_return trace kind, so the arm-vs-observation discriminator exists in the data stream. Keeping it open as advisory to track if the shape graph icon palette surfaces it." \
  --trace /tmp/sample.trace.jsonl --as "claude-code"
→ commented on ann_04 as claude-code
```

The agent explains the structural case before changing status. This is the rule, do not silently `wontfix`.

Agent step 4, wontfix with the reason mirrored in the resolution summary:

```
cxc trace annotation wontfix ann_04 \
  --reason "existing obs_return kind already distinguishes observations in the trace; no action on the icon palette until the diff view lands" \
  --trace /tmp/sample.trace.jsonl --as "claude-code"
→ ann_04 marked wontfix
```

Status moves `in_progress → wontfix`. Resolution carries `kind: "wontfix"` and the reason. The human sees the comment before the wontfix lands in the viewer, so a dispute path exists.

## The final sidebar state

```
ID       STATUS       SEV      KIND
ann_01   open         praise   node
ann_02   verified     finding  event
ann_03   open         advisory range
ann_04   wontfix      finding  expected_vs_actual
ann_05   open         advisory edge
```

Sidebar chips: OPEN 3, VERIFIED 1, WONTFIX 1. The Counter atom's badge still shows 3 because the counting is by target-atom, not status.

## What the loop proves

- Status machine moves the five transitions correctly, `open → in_progress → addressed → verified` for the fix path, `open → in_progress → wontfix` for the dispute path.
- The claim is a check-and-set, a second agent is blocked while the first holds the annotation.
- Comments carry reasoning independent of status, so disagreement reads as structured dialogue, not as a silent close.
- The viewer re-renders status chips and badges after each save without a page reload (polling the sidecar).
- Author provenance comes from `--as` or defaults to `git config user.name`, both human and agent are legible in the trail.

## When the loop fails

Three common failure paths and recovery.

- `annotation already terminal`, meaning `verified`, `wontfix`, or `stale`. The CLI refuses further writes. Nothing to do. If the resolution was wrong, open a new annotation that references the terminal one.
- `annotation already claimed by X`, meaning the claim-and-set rejected because another agent holds the lock. Skip and move on. If X is the current agent and the claim is stuck, re-run with the same `--as X` to re-assert.
- `no --trace specified and no *.trace.jsonl in cwd`, meaning the CLI could not locate a trace to key off. Pass `--trace <absolute path>`.

## Where this fits in a real review session

- Human runs `cxc compile <target.cxc> <out> --trace`, binary emits `<name>.trace.jsonl`.
- Human runs `cxc trace serve --trace-dir <path>`, opens the viewer in VS Code's Simple Browser.
- Human scrubs, clicks atoms and edges, writes notes via "+ annotate".
- Human says "pick up open annotations" in the Claude Code chat.
- Agent loads the `cxc` skill, routes to `references/trace-review.md`, and runs the loop above against the live sidecar.
- Human verifies the terminal states or disputes via new comments.
- When the session ends, the sidecar is committed alongside the code change, so the review record travels with git history.
