# hickey-cycle-313 — `fs.open` returning a File atom

Scope memo. Replaces the blocking `fs.read_text` / `fs.write_text` surface with an async File atom whose phases signal I/O progress. Brings file I/O in line with the substrate's "async via lifecycles, not futures" rule (memory `corvid-async-via-lifecycles`, spec §1.11).

## Context

Current FileSystem surface at `compiler/resources/prelude/target/shared/fs.cxc` is fully synchronous:

```
read_text(path)      :- String!  # blocks, loads whole file
read_lines(path)     :- String!  # blocks, loads then splits
read_bytes(path)     :- Bytes
write_text(path, c)  :- Int      # blocks, returns byte count
write_bytes(path, b) :- Int
append_text(path, c) :- Int
exists / is_dir / is_file         :- Bool!
mkdirs / remove / copy / rename / size / tmp_path / list_dir
```

Every fallible method raises `IOError`. Callers recover via `or IOError(reason: let m): fallback`.

Problems with the current shape:

1. **Blocks the atom's arm.** An atom's transition body can't be interrupted. Reading a large file freezes every handler on that atom until the read completes. The substrate otherwise never blocks arms — this is the only exception.
2. **No streaming.** Must load the full file as a String before the caller sees any bytes. 1 GB file = 1 GB allocation.
3. **Error channel is special-case syntax.** `or IOError(...)` is unique to cap methods that raise. Every other error propagation in the substrate is a message (e.g. `atom.failed(err)` broadcasts). Two ways to handle failure, split by call shape.
4. **Return ceremony.** `write_text` returns `Int` (bytes written). Callers bind `let _n: Int = fs.write_text(...)` purely to attach `or IOError`. Statement-position `or` doesn't parse, so the binding is forced.

## Proposed additions

### FileSystem surface

```
open_read(path: String!)  :- File   # streaming read, emits chunks
open_write(path: String!) :- File   # streaming write, accepts writes
slurp(path: String!)      :- File   # convenience: buffer, emit once
spit(path: String!, content: String!) :- File   # convenience: write-and-close

# Unchanged (pure probes stay synchronous):
exists / is_dir / is_file / size / list_dir / tmp_path

# Still synchronous (effectful but fast, idempotent):
mkdirs / remove / copy / rename
```

Rationale for keeping the second group synchronous: pure probes are value-returning and `IOError` is catchable at the call site without substrate cost. The async shift only earns its complexity for streaming data paths.

### File atom

```
def File:
    path:          String!
    bytes_read:    Int = 0   # observation on read side
    bytes_written: Int = 0   # observation on write side

    -> opening                 # initial, driven by fs.open_* internally
    opening  <: ok  -> opened
    opening  <: err(e: IOError) -> end
        :> failed(err: e)

    opened   <: read_all -> reading
        self :> pump
    opened   <: write(data: Bytes!) -> opened
        # buffered write, emits progress internally
        bytes_written => self.bytes_written + data.length()
    opened   <: close -> closing
        self :> flush

    reading  <: pump -> reading
        # emit :> chunk(data: Bytes!) for each buffer
        # when EOF, :> read_done [content: String!] and -> closing
    reading  <: err(e: IOError) -> end
        :> failed(err: e)

    closing  <: flush -> end
        :> closed
    closing  <: err(e: IOError) -> end
        :> failed(err: e)
```

Internal state machine uses standard `self :> ...` self-sends. No new syntax (no `~>` auto-transitions).

Broadcasts:

- `:> opened` — once, when the fd is ready.
- `:> chunk(data: Bytes!)` — streaming read path, per buffer.
- `:> read_done(content: String!)` — slurp path only, one-shot with buffered content.
- `:> closed` — fd released cleanly.
- `:> failed(err: IOError)` — terminal error, replaces the `or IOError` raise.

The `failed` broadcast IS the error channel. Callers subscribe via `* <: f.failed(err: IOError)` — same shape as any other atom message. The special-case `or TypedError` recovery form stays available for the synchronous probes but no longer applies to the streaming surface.

### Rosetta `file-io.cxc` reimagined

Streaming copy, canonical form:

```
def main:
    use fs: FileSystem
    use console: Console
    src: File = fs.open_read("input.txt")
    dst: File = fs.open_write("output.txt")

    -> piping

    piping <: src.chunk(data: Bytes!) -> piping
        dst :> write(data)

    piping <: src.read_done -> finishing
        dst :> close

    finishing <: dst.closed -> end
        console.write("copied " + dst.bytes_written.to_string() + " bytes")

    * <: src.failed(err: IOError) -> end
        console.write("read failed: " + err.reason)
    * <: dst.failed(err: IOError) -> end
        console.write("write failed: " + err.reason)
```

Slurp + spit convenience shape:

```
def main:
    use fs: FileSystem
    use console: Console
    src: File = fs.slurp("input.txt")

    -> reading
    reading <: src.read_done(content: String!) -> writing
        dst: File = fs.spit("output.txt", content)

    writing <: dst.closed -> end
        console.write("copied " + content.length().to_string() + " chars")

    * <: src.failed(err: IOError) -> end
        console.write("read failed: " + err.reason)
    * <: dst.failed(err: IOError) -> end
        console.write("write failed: " + err.reason)
```

## Why these over alternatives

**`open_read` / `open_write` over `open(path, mode:)`.** A single `open` with a mode param requires the mode to be pattern-matched inside File's own transitions, or the File atom becomes two-headed (handlers that only apply in read-mode, others only in write-mode). Splitting the entry point keeps the File atom's state machine coherent and the cap surface self-documenting.

**`chunk(data)` as broadcast, not atom message.** Chunks are producer-driven — the reader doesn't ask for them. Modelling as broadcast matches the semantics: File fires `chunk` when bytes are available, subscribers consume. A request/response pattern (`read_next` → `chunk`) adds round-trips without buying anything.

**`failed` as broadcast, not `or` raise.** The streaming surface can fail mid-stream, long after the open call returned. A raise at open time can't express that. A broadcast can fire at any phase. Consistency win: every failure in the substrate is now a message.

**Keep `slurp` and `spit` as convenience.** The buffered one-shot form is the common 80% case. Making callers wire `open_read + read_all + buffer + close` for small files is cosmetic purity. Two convenience methods, with the canonical streaming form available when needed.

## What this doesn't change

- Synchronous probes (`exists`, `size`, `is_dir`, etc.) stay synchronous. Pure value returns, no substrate cost to catch.
- `mkdirs` / `remove` / `copy` / `rename` stay synchronous. These are fast, effectful, and callers typically don't need streaming.
- `IOError` record stays as-is. Just travels through broadcasts instead of raises.
- Existing `read_text` / `write_text` / `read_lines` / `read_bytes` stay for one cycle as `DEPRECATED` and are removed the cycle after. Avoids a cliff for consumers.

## Migration

The cycle lands in three steps:

1. **Add `File` atom + `open_read` / `open_write` / `slurp` / `spit` to prelude.** Native backing via GCD read/write-in-background against existing `native_fs_*` primitives; initial impl can buffer the whole read and emit as a single `chunk` + `read_done` (incremental streaming is a follow-up).
2. **Update `docs/rosetta/file-io.cxc` to the new form.** Removes the `let _n: Int =` ceremony.
3. **Deprecate the old blocking methods.** Callers get a one-cycle grace period, then they're removed.

Known consumers to migrate: `ars/cxc/main.cxc` (uses `fs.read_text` in `Cli.config_load`), any Rosetta samples (just `file-io.cxc` today).

## Follow-up (not in this cycle)

- **Incremental streaming native layer.** Step 1 above emits chunks after full buffering. Real streaming via `NSInputStream` / `dispatch_io` is the follow-up cycle. The cxc surface doesn't change; only the native backing.
- **HTTP cap gets the same shape.** `http.request` already planned to return an atom per memory `corvid-async-via-lifecycles`. Replaces the one landed blocking form once File lands.
- **Statement-position `or TypedError` recovery.** Still absent at parser level; adds ambiguity (where does the recovery body end?) with little payoff once File takes over the streaming surface. Punt indefinitely.

## Tracking

BOARD-37. See this file for the plan, BOARD for progress notes.
