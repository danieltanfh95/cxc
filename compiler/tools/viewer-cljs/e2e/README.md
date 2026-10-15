# viewer-v2 e2e suite

Playwright click-through tests for the compiled viewer v2 (served from
`compiler/tools/trace-viewer.html`). One `.mjs` file per feature area. Each
runs standalone under `node`, exits 0 on PASS and non-zero on FAIL, and prints
a one-line summary.

## Running

Needs the viewer server up and reachable at `VIEWER_BASE` (default
`http://127.0.0.1:8769`). The suite loads its traces from whatever
directory the server was launched against.

**Local-only note.** The default harness points at `compiler/research/`,
which is gitignored and only exists on a maintainer checkout. Public
contributors should point `--trace-dir` at `compiler/test/cxc/fixtures/`
instead and run only the tests that use the committed `sample-01-single-atom`
fixtures, or vendor their own trace into a local scratch dir.

```bash
# from the repo root:
cxc trace serve --trace-dir compiler/test/cxc/fixtures --port 8769 &
# (equivalent to `cxc trace serve` with --trace-dir defaulting to pwd)

# then:
cd compiler/tools/viewer-cljs/e2e
./run-all.sh
```

`run-all.sh` wraps each test in `timeout 120 node <file>` and prints a
pass/fail summary at the end. Individual tests:

```bash
node annotations-list-e2e.mjs
```

## Files

- `_harness.mjs`, shared playwright import, fixture URLs, sidecar
  snapshot/restore, PASS/FAIL exit helpers.
- `<area>-e2e.mjs`, one test per area. See `run-all.sh` for the full set.
- `golden/`, committed PNG baselines produced by `visual-parity-e2e.mjs`.
  First run writes them. Later runs assert existence. A pixel-diff step is
  intentionally out of scope here (see outstanding items in the fix report).
- `run-all.sh`, runs every `*-e2e.mjs` with a 120s timeout each.

## Playwright resolution

The harness tries two sources, in order:

1. `PLAYWRIGHT_PATH` from the environment (set this if your Playwright install
   lives outside the usual node resolver, e.g. a shared runtime cache).
2. `playwright` from the standard node resolver (works if a local
   `node_modules/playwright` or `NODE_PATH` is set).

No `deps.edn` or `package.json` edit, the suite is pure node ESM.

## State restoration

Tests that mutate the sidecar snapshot it before the run and restore it from a
`process.on('exit')` hook even when PASS/FAIL exits early. If a run was killed
hard (SIGKILL / power loss), look for `<trace>.annotations.json.e2ebak` under
the server's trace-dir (`compiler/research/` on a maintainer checkout,
`compiler/test/cxc/fixtures/` on a public checkout) and copy it back by hand.

## Writing new tests

- Import from `_harness.mjs`: `launch`, `openViewer`, `PASS`, `FAIL`, `assert`.
- Use `snapshotSidecar(stem)` BEFORE any state-mutating step. It auto-registers
  cleanup. No explicit `restoreSidecar` needed in the finally.
- End with exactly one `PASS(NAME, …)` or `FAIL(NAME, …)` so the runner reads
  the status from stdout/stderr and the exit code.
- Keep each file self-contained and short (one feature area, no shared state).
