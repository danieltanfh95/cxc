# Hello-Agent MVP — CXC Files Created

Session index of every `.cxc` file created in the 2026-09-02/03 hello-agent
MVP push. Grouped by role.

## Prelude capabilities (compiler-shipped, autoloaded)

Under [resources/prelude/target/macos/](../resources/prelude/target/macos/):

- [fs.cxc](../resources/prelude/target/macos/fs.cxc) — `FileSystem`: `read_text` / `write_text` / `exists` (Foundation NSString file I/O). Landed in [47a4c2f](../../.git).
- [subprocess.cxc](../resources/prelude/target/macos/subprocess.cxc) — `Subprocess`: `run(cmd)` returns combined stdout+stderr via libc `popen`. Landed in 07d4994.
- [env.cxc](../resources/prelude/target/macos/env.cxc) — `Env`: `get_or(name, fallback)` wraps libc `getenv`. Landed in c5e2f68.
- [args.cxc](../resources/prelude/target/macos/args.cxc) — `Args`: `length()` + `get_or(index, fallback)` via Darwin `_NSGetArgc`/`_NSGetArgv`. Landed in 4d6a897.
- [json.cxc](../resources/prelude/target/macos/json.cxc) — `Json`: `encode_str(s)` + `field_string(json, key)` via NSJSONSerialization. Landed in 6b12a7d.
- [http.cxc](../resources/prelude/target/macos/http.cxc) — `Http`: synchronous `get(url, headers_json)` + `post(url, headers_json, body)` via NSURLSession + dispatch_semaphore. Landed in 838533a.

Pre-existing prelude file modified this session:

- [console.cxc](../resources/prelude/target/macos/console.cxc) — retired the old prelude Console form (see da59e95).

## Examples (regression tests + manual demos)

Under [examples/](./):

- [raise-caught-by-or-arm.cxc](./raise-caught-by-or-arm.cxc) — 2d-iii(A) end-to-end. `raise MyError(...)` in a `then:` body caught by sender-side `or MyError(let m):` recovery. Landed in 1a59298.
- [fs-write-then-read.cxc](./fs-write-then-read.cxc) — 2d roundtrip. Writes then reads a fixed string via `FileSystem`.
- [subprocess-echo.cxc](./subprocess-echo.cxc) — 2e verification. Runs `echo` and pipes output through `Console`.
- [mini-agent.cxc](./mini-agent.cxc) — six-capability composition proof. `Args` + `Env` + `FileSystem` + `Subprocess` + `Console` + `Int.to_string` + `String + String` all in one `then:` block. Landed across 2f/2g/2i/2h.
- [json-roundtrip.cxc](./json-roundtrip.cxc) — 4 verification. Encode + embed + extract with escape handling.
- [http-example-com.cxc](./http-example-com.cxc) — 5 manual verification (fetches https://example.com and prints HTML). Not run in CI.
- [hello-agent-mini.cxc](./hello-agent-mini.cxc) — 6 MVP hello-agent shape. Direct Claude Messages API POST composing every stdlib capability. Landed in 603859a.

## What's NOT in the MVP

The full designed hello-agent (see [design/examples/hello-agent/hello-agent.cxc](../../design/examples/hello-agent/hello-agent.cxc)) needs several language landings that were deliberately deferred:

- `List of T` container with `.append` / `.filter` / `.for_each` / `.length`
- Closures for `.for_each(elem => body)`
- Variant destructuring: `tc.action is .shell(cmd)` binding
- `=>` field reassignment shorthand
- `<expr> or T(binding): body` recovery on non-emit expressions (2d-iii(B))
- Multi-arm transitions with rich `||:` guards on payload fields
- `Bytes` / `Path` types for binary I/O (2d-ii)
- `ProcResult { exit_code, stdout, stderr }` — richer subprocess surface

Each is a substantial landing; the MVP path validated that the runtime + FFI + prelude autoloading + capability composition all hold up end-to-end. Full agent orchestration composes on top when those features land.
