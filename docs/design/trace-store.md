# Trace storage — Datalevin migration plan

## Status

JSONL today, Datalevin as the target backing store. Raised when the
game session's `game.trace.jsonl` reached 141 MB over 816k events and
`cxc view --live` lagged noticeably.

## Current pipeline

```
compiled binary (CXC_TRACE_* macros)
   │
   └─▶ writes one JSON line per event to <path>.jsonl
          │
          ├─▶ cxc.trace-io/load-events        (full-file read + sort by :seq)
          ├─▶ cxc.trace-view                  (count-lines + slice-trace!)
          └─▶ cxc.trace-server /trace-events  (RandomAccessFile tail-f, 200ms poll)
```

## Pain points

1. **Range reads cost a full scan.** `load-events` sorts the whole
   file by `:seq` before returning anything. `cxc trace summarize`
   and `cxc trace diff` both go through this path.
2. **Startup time scales with trace size.** `count-lines` reads
   every byte to find `\n`; for the 141 MB game trace this is ~1-2 s
   on SSD before the first viewer byte is served.
3. **Live-trace polling is per-byte, not per-event.** The tail-f
   loop reads newly-appended bytes and re-decodes JSON for every
   subscriber. OK at low volume, noticeable at high volume because
   there is no server-side cursor or de-dup.
4. **No query interface beyond linear scan.** The viewer filters
   client-side; the summarize / diff CLIs compile their own filters
   in Clojure. There is no shared query layer.

## Why Datalevin

- **Datalog queries** replace ad-hoc scan + filter Clojure in three
  trace CLIs with declarative queries whose indexes the store
  maintains.
- **AEVT / AVET indexes** give O(log n) range queries by any
  component of an event (seq, atom-name, phase, ts).
- **Transaction log** is first-class. The server-side cursor the
  SSE handler fakes with byte offsets becomes a `tx-id` watermark;
  "events since tx" is a native query.
- **Embedded.** No new process, no network. One LMDB directory per
  trace plus a Clojure API.

## Migration shape

### Phase 0 — dependency + smoke test

Add `com.github.huahaiy/datalevin` to `deps.edn`. Verify the native
LMDB bundle loads on Darwin arm64 and amd64. Smoke test: open an
empty store, write 100 events, read them back. If the native-dep
step breaks the build, back out and fall through to Phase 0a.

### Phase 0a — fallback if Datalevin blocks

Build an index-sidecar fallback (`<trace>.jsonl.seqidx`) that lists
`<seq> <byte-offset>` every 10k events. Pure Clojure, no new dep.
Gets us the O(log n) range query without adopting Datalevin. Loses
the datalog query layer; trace CLIs stay as hand-written scans.

### Phase 1 — dual-write

`cxc.trace-store` namespace with one protocol:

```clojure
(defprotocol TraceStore
  (append-events! [this events])
  (count-events [this])
  (read-range [this {:keys [from-seq to-seq limit]}])
  (subscribe [this {:keys [from-tx callback]}]))
```

Two implementations: `JsonlTraceStore` (current behavior) and
`DatalevinTraceStore` (new). Writer is dual-write for one cycle —
JSONL for compatibility, Datalevin for query. Reader prefers
Datalevin when the store directory exists next to the JSONL.

### Phase 2 — Datalevin-first

Flip the default to Datalevin. JSONL becomes an export artifact
(`cxc trace export --format=jsonl`) for workflows that need
grep-friendly text. Trace CLIs rewrite their filters as datalog
queries.

### Phase 3 — remove JSONL writer

After one release cycle on Datalevin-first, retire the dual-write
and delete the `JsonlTraceStore` writer path. Reader stays
present to open legacy JSONL files until a planned retirement.

## Event schema (Datalevin)

```clojure
{:event/seq        ...  ; int, :unique :db.unique/identity
 :event/tx-id      ...  ; int, assigned by Datalevin tx
 :event/ts         ...  ; wall-clock ms since epoch
 :event/kind       ...  ; :atom-init / :msg-send / :transition / ...
 :event/atom-name  ...  ; string
 :event/atom-id    ...  ; string (ptr-string at emit time)
 :event/phase      ...  ; string (nilable)
 :event/selector   ...  ; string (nilable, for msg-send)
 :event/arm-index  ...  ; int (nilable, for transition-branch)
 :event/payload    ...  ; EDN-encoded map of kind-specific fields}
```

Indexes:
- `:event/seq` — primary, dense; range queries by seq.
- `:event/ts` — secondary; range queries by wall-clock.
- `:event/atom-name` — AVET; "all events for Foo".
- `:event/phase`, `:event/kind` — AVET; cross-atom filters.

## Open questions

- **Native library bundling.** Datalevin ships LMDB JNI bindings.
  The compiler currently has no native deps. Adding one forces a
  decision on the build tier (uberjar vs. native-image vs. source
  distribution).
- **Store placement.** JSONL writes to `<cwd>/<name>.trace.jsonl`.
  Datalevin needs a directory. Options: `<name>.trace.ldb/` as a
  sibling, or `.cxc-trace/<name>/` under a per-project directory.
- **Writer side ownership.** The compiled binary emits C that
  writes JSONL today via `CXC_TRACE_*` macros + `fprintf`. A direct
  Datalevin write from C needs a bridge (JVM call? sidecar process?
  socket to a daemon?). Simplest shipping shape: binary keeps
  writing JSONL to a chunked file, a background Clojure process
  tails-and-indexes into Datalevin. Harder but cleanest: binary
  writes to a named FIFO the daemon drains.
- **Crash safety.** Datalevin is LMDB-backed, so partially-written
  transactions roll back cleanly. JSONL on crash leaves trailing
  partial lines. The index builder needs to handle both.

## Success criterion

A 141 MB / 816k-event trace opens in `cxc view` under 500 ms, and
`cxc trace diff a b` runs under 2 s for traces of that size on a
2024 Apple Silicon machine. Both numbers are ~10x faster than the
current JSONL path on the same hardware.
