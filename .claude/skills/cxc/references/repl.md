# REPL — live introspection and perturbation

Every cxc binary built after cycle 239 can open an in-process REPL socket on demand. The socket serves a line-based protocol for atom introspection (`:atoms`, `:read`), message injection (`:send`), and runtime perturbation (`:chaos`). The intended use is interactive authoring, live debugging, and residuality probing — the trace is the post-hoc record, the REPL is the live perturbation hand.

## Starting the REPL

Opt-in via `CXC_REPL=1` in the environment at process start. No compile flag needed — the stub is emitted into every binary and gated on the env var at `main()` entry.

```bash
CXC_REPL=1 ./my-binary &
# → stderr: cxc-repl: listening on /tmp/cxc-repl-<pid>.sock
#          cxc-repl: ready on /tmp/cxc-repl-<pid>.sock
```

Side effect: when `CXC_REPL=1` is set and the program would otherwise exit when `main` returns (no `dispatch_main`, no `main_runloop`, no `uikit`), the binary falls into `dispatch_main()` instead so the main queue keeps draining. `:send` ops land on actual arms, and the process stays alive until killed.

## Client

A babashka client ships at `compiler/tools/cxc-repl.bb`:

```bash
# One-shot op by explicit PID
cxc-repl 48219 ping
cxc-repl 48219 atoms

# Auto-resolve the newest cxc-repl socket under /tmp
cxc-repl auto atoms

# Interactive session — reads stdin lines, pipes op → response
cxc-repl auto
cxc> atoms
cxc> read 0x104936060 n
cxc> send 0x104936060 bump
cxc> chaos 0.1
```

Any client that speaks `AF_UNIX` + line framing works. The protocol is deliberately dumb: write one line, read one line, close.

## Ops

| Op | Args | Returns |
|---|---|---|
| `ping` | — | `pong` |
| `atoms` | — | JSON array of `{id, class, phase}` for every live atom |
| `read` | `<atom-id> <field-or-obs>` | stringified value |
| `send` | `<atom-id> <selector>` | `ok` after enqueue (zero-arg only in this slice) |
| `chaos` | `<rate>` (0.0 – 1.0) | `chaos:drop_rate=<rate>` |

`<atom-id>` is the hex pointer string returned by `:atoms` (e.g. `0x104936060`). Selectors are the C-level ObjC selector names, which for a cxc arm `running <: bump -> running` are just `bump`. Multi-arg messages encode their selector as `nameWitharg1:arg2:` under the cxc emission convention; those aren't callable from the REPL in the current slice (:send accepts zero-arg only).

`:read` uses KVC (`valueForKey:`). This covers:

- Plain ivar reads (`n`, `name`, `label`).
- Zero-arg observations (`doubled`, `label`, `count_in_grid`).
- Any ObjC property with a getter that takes no args.

KVC returns an `id`; the REPL surfaces `NSString` → raw string, `NSNumber` → decimal, anything else → `description`. A missing key returns a structured `error:read-failed:<name>:<reason>` instead of crashing.

`:send` resolves the selector via `NSSelectorFromString`, checks `respondsToSelector:`, and dispatches on `dispatch_get_main_queue()`. A bad selector returns `error:no-such-selector:<sel>`. Exceptions from the invoked method are logged to stderr.

`:chaos` sets an `_Atomic(double)` that `cxc_send_async` consults on each enqueue. Below the threshold, the send is silently dropped and logged in the trace as `chaos_drop`. Zero cost when the rate is 0 (atomic_load + double compare + untaken branch on the hot path).

## Residuality workflow

Chaos + trace pairs for residuality analysis:

```bash
# Baseline trace
./my-binary > /tmp/baseline.jsonl

# Chaotic trace — 10% async-send drop rate
rm -f /tmp/cxc-repl-*.sock
CXC_REPL=1 CXC_TRACE_PATH=/tmp/chaos.jsonl ./my-binary &
cxc-repl auto chaos 0.1
# … let it run …
pkill -f my-binary

# Diff which atoms stop producing observations
cxc trace diff /tmp/baseline.jsonl /tmp/chaos.jsonl
```

The atoms that keep producing observations across baseline and chaos are the residues — structurally robust to partial async-send loss. Atoms that go quiet under chaos are the ones that rest on send-reliability assumptions.

## Live authoring loop

```bash
# Terminal A — long-running binary with REPL
CXC_REPL=1 ./app &

# Terminal B — probe state during authoring
cxc-repl auto
cxc> atoms
cxc> read 0x104936060 current_phase
cxc> send 0x104936060 advance
cxc> read 0x104936060 current_phase
```

## Deferred ops

- `:swap <dylib> <cls>.<sel>` — hot-reload method-body swap. Design in `docs/design/hot-reload.md`. Needs daemon-side delta compile pipeline.
- `:branch <viewer-counterfactual>` — timeline branching. Lives in the trace viewer, not the runtime.
- `:send` with typed args — needs a JSON → ObjC encoder keyed on selector-type-signature reflection.

The IPC substrate doesn't change for any of these; each is a new case in `_cxc_repl_handle`.

## Wiring into replsh

The bb client works as a replsh `exec`-mode session. One-shot:

```bash
# Launch a bash session that pipes each eval through cxc-repl
replsh launch bash --name cxc \
  --cmd "python3 {bridge} --port {port} --backend bash"

# Then every eval runs through cxc-repl auto:
replsh eval --name cxc 'cxc-repl auto atoms'
replsh eval --name cxc 'cxc-repl auto read 0x104936060 n'
replsh eval --name cxc 'cxc-repl auto send 0x104936060 bump'
```

For a dedicated cxc session that stays bound to one PID, write a
wrapper script `cxc-repl-<pid>` that hardcodes the socket and pass
that as the backend. The dumb protocol means any shell wrapper
works — nothing cxc-specific lives in the client beyond the socket
path resolution.

## When to reach for this

- You just authored a new atom and want to confirm its observations compose correctly without writing a test fixture first.
- A trace shows an atom going quiet mid-session and you want to probe its state after the fact (binary still running).
- You're hunting a dispatch-ordering bug and want to see which atoms are in which phase at a given moment.
- You need to answer "does this atom survive if peer X stops responding" — chaos it.
- You're writing the first draft of a game loop and want to poke atoms from a shell without re-launching.
