# Hello-Agent MVP — CXC Files + Landings

Session index of everything shipped in the 2026-09-02/03 hello-agent MVP
push. Grouped by artifact and by feature area.

## Prelude capabilities (compiler-shipped, autoloaded)

Under [resources/prelude/target/macos/](../resources/prelude/target/macos/):

| File | Capability | Methods | Landed |
|------|------------|---------|--------|
| [fs.cxc](../resources/prelude/target/macos/fs.cxc) | `FileSystem` | `read_text` / `write_text` / `append_text` / `exists` / `mkdirs` / `remove` | 47a4c2f + 2c9d094 + 25cb3bd |
| [subprocess.cxc](../resources/prelude/target/macos/subprocess.cxc) | `Subprocess` | `run(cmd)` (combined stdout+stderr via popen) | 07d4994 |
| [env.cxc](../resources/prelude/target/macos/env.cxc) | `Env` | `get_or` + `set` + `remove` | c5e2f68 + db01b54 + 25cb3bd |
| [args.cxc](../resources/prelude/target/macos/args.cxc) | `Args` | `length()` + `get_or(index, fallback)` | 4d6a897 |
| [json.cxc](../resources/prelude/target/macos/json.cxc) | `Json` | `encode_str` / `field_string` / `field_int_or` / `field_string_path` / `field_int_or_path` / `field_bool_or` / `array_length_at` | 6b12a7d + c922c06 + a798ab9 + f3c49bd |
| [http.cxc](../resources/prelude/target/macos/http.cxc) | `Http` | `get` / `post` / `get_envelope` / `post_envelope` | 838533a + 0e17b7b |
| [wall_time.cxc](../resources/prelude/target/macos/wall_time.cxc) | `Time` | `now()` + `since(t)` + `sleep_ms(ms)` | c3e098e + db01b54 |
| [console.cxc](../resources/prelude/target/macos/console.cxc) | `Console` | `write` + `write_raw` + `readln` + `write_err` | da59e95 + ac862c0 + 11155d1 + 6390fa5 |

## Examples (regression tests + manual demos)

Under [examples/](./):

- [raise-caught-by-or-arm.cxc](./raise-caught-by-or-arm.cxc) — 2d-iii(A) end-to-end: `raise MyError(...)` in a `then:` body caught by sender-side `or MyError(let m):` recovery. (1a59298)
- [fs-write-then-read.cxc](./fs-write-then-read.cxc) — FileSystem roundtrip.
- [subprocess-echo.cxc](./subprocess-echo.cxc) — Subprocess verification.
- [mini-agent.cxc](./mini-agent.cxc) — six-capability composition (Args + Env + FileSystem + Subprocess + Console + Int.to_string + String concat).
- [json-roundtrip.cxc](./json-roundtrip.cxc) — Json encode+extract with escape roundtrip.
- [string-helpers.cxc](./string-helpers.cxc) — String helper surface + Bool.to_string.
- [http-example-com.cxc](./http-example-com.cxc) — manual HTTP verification (fetches example.com).
- [hello-agent-mini.cxc](./hello-agent-mini.cxc) — full MVP hello-agent shape: Claude Messages API POST, status discrimination, response text extraction via string surgery, round-trip latency via `Time.since`. (Manual real-API run needs `ANTHROPIC_API_KEY`.)
- [time-wall-clock.cxc](./time-wall-clock.cxc) — Time.now() liveness + Instant.millis > 2023 boundary (int64 range check).
- [console-readln.cxc](./console-readln.cxc) — stdin readln round-trip.
- [env-set-time-since-sleep.cxc](./env-set-time-since-sleep.cxc) — env.set/get_or round-trip + Time.sleep_ms bracketed by Time.since.
- [fs-mkdirs-remove.cxc](./fs-mkdirs-remove.cxc) — nested mkdirs / write file / recursive remove.
- [env-remove-fs-append.cxc](./env-remove-fs-append.cxc) — env.remove round-trip + fs.append_text two-write log.
- [random-hex.cxc](./random-hex.cxc) — CSPRNG hex length invariants (0 / 1 / 8 / 32 / negative).
- [json-bool-array.cxc](./json-bool-array.cxc) — strict-Bool + array_length_at walk (nested + numeric-index paths).
- [logged-fetch.cxc](./logged-fetch.cxc) — six-capability composition demo (Http + Time + Random + Json + Fs + Console); real HTTP fetch instrumented with a `random.hex(16)` correlation ID, epoch timestamp, latency measurement, appended to a log file. Manual runtime verification; CI asserts compile only. (5bb9b5f)

## Non-plugin prelude capabilities enriched this session

| File | Capability | Additions this push | Landed |
|------|------------|---------------------|--------|
| [random.cxc](../resources/prelude/target/macos/random.cxc) | `Random` | `hex(nybbles)` — CSPRNG hex String for IDs | 38b0774 |

## Language surface added this session

### Statement / expression forms

- 2d-iii(A) — `raise T(args)` boxes a record ctor into `_Cxc_R_<T>` and `@throw`s NSException with `userInfo[@"reason"]` set. Wire shape identical to Track A/4 `|: as T(...)` so existing L12c end-catch-wrap + Track B/3 recovery chain catch it. (1a59298)
- `String + String` concatenation lowering — `-[NSString stringByAppendingString:]` with nil-coalesce on both sides so `X + nil` doesn't crash. (57ab9de + 603859a)
- `String == String` content-equality — reroutes to `-isEqualToString:` with a leading pointer-eq short-circuit for nil-nil. (5d10ab4)
- `:multi-arm-dispatch` infer-type — `||: <guard>: <body>` can now sit inside larger expressions (concat, method-call args). (0e17b7b)

### Method intrinsics

- `Int.to_string()` — decimal render via `stringWithFormat:@"%lld"`. (c5e2f68)
- `Bool.to_string()` — literal `"true"` / `"false"`. (5d10ab4)
- `String.length()`, `.contains(sub)`, `.starts_with(pre)`, `.ends_with(suf)` — NSString direct methods. (5d10ab4)
- `String.substring(start, len)` — nil-safe slice. (1319b71)
- `String.index_of(sub)`, `.trim()`, `.replace(from, to)` — parsing primitives. (dddac82)
- `String.to_int_or(fallback)` — NSScanner + isAtEnd; fallback on non-integer or partial match. (b93f385)
- `String.uppercase()` / `.lowercase()` — Foundation NSString case-mapping. (6390fa5)

### Native intrinsics (backing prelude atoms)

- `native_fs_read_text` / `native_fs_write_text` / `native_fs_exists` — Foundation NSString file I/O. (47a4c2f)
- `native_subprocess_run` — libc popen with 2>&1. (07d4994)
- `native_env_get_or_default` — libc getenv with fallback. (c5e2f68)
- `native_args_length` / `native_args_get_or` — Darwin `_NSGetArgc` / `_NSGetArgv`. (4d6a897)
- `native_json_encode_str` / `native_json_field_string` — NSJSONSerialization roundtrip. (6b12a7d)
- `native_json_field_int_or` — numeric field extraction. (c922c06)
- `native_json_field_string_path` / `native_json_field_int_or_path` — nested walk via dot-path (e.g. `usage.input_tokens`, `content.0.text`). (dot-path variant / a798ab9)
- `native_millis_now` int64_t fix — was truncating to 32-bit; now returns full epoch millis. Clock.now + Time.now both consume this. (c3e098e)
- `native_console_readln` — fgets on stdin; strips trailing newline; nil on EOF. (11155d1)
- `native_env_set` — setenv(name, value, 1); returns 0/-1. (db01b54)
- `native_time_sleep_ms` — usleep(ms*1000); no-op on non-positive; truncates absurd waits. (db01b54)
- `native_fs_mkdirs` / `native_fs_remove` — NSFileManager createDirectoryAtPath (mkdir -p) + removeItemAtPath (recursive). (2c9d094)
- `native_console_write_err` — fputs+fputc newline to stderr. (6390fa5)
- `native_env_remove` — libc unsetenv. (25cb3bd)
- `native_fs_append_text` — fopen `a` mode + fputs; concurrent-safe for short lines on POSIX. (25cb3bd)
- `native_random_hex` — arc4random_buf → hex-encode; clamp at 4096 nybbles. (38b0774)
- `native_json_field_bool_or` — strict-typed Bool extraction (only true/false, numeric 1/0 falls through). (f3c49bd)
- `native_json_array_length_at` — dot-path walk + NSArray count; supports numeric-index segments. (f3c49bd)
- `native_http_get` / `native_http_post` — sync NSURLSession dataTask + dispatch_semaphore. (838533a)
- `native_http_get_envelope` / `native_http_post_envelope` — same with `{status,body}` JSON envelope. (0e17b7b)

### Runtime / build

- `<crt_externs.h>` auto-included in Foundation programs (backs `_NSGetArg*`). (4d6a897)

## Commit sequence

```
5bb9b5f logged-fetch: six-capability composition demo
f3c49bd Json.field_bool_or + Json.array_length_at
38b0774 Random.hex(nybbles) — CSPRNG hex String
25cb3bd Env.remove + FileSystem.append_text
6390fa5 String.uppercase / lowercase + Console.write_err
2c9d094 FileSystem.mkdirs + .remove
253f948 hello-agent-mini: report round-trip latency via Time.since
db01b54 Env.set + Time.since(Instant) + Time.sleep_ms
11155d1 Console.readln() — stdin line read
c3e098e Time wall-clock capability + int64_t truncation fix
b93f385 String.to_int_or(fallback) — NSScanner-based Int parse
a798ab9 Json.field_int_or_path — numeric dot-path extraction
5b761c5 hello-agent-mini: switch to http.post_envelope
0e17b7b Http.{get,post}_envelope + :multi-arm-dispatch infer-type
051228a hello-agent-mini: extract Claude response text + token counts
dddac82 String.trim / replace / index_of
1319b71 String.substring(start, len)
c922c06 Json.field_int_or
ac862c0 Console.write_raw
5d10ab4 String content-equality + helpers + Bool.to_string
1e61001 Hello-agent MVP index
603859a hello-agent-mini + nil-safe String concat
838533a std.http MVP
6b12a7d std.json MVP
4d6a897 std.args prelude
c5e2f68 std.env + Int.to_string
57ab9de String concat + mini-agent
07d4994 std.subprocess MVP
47a4c2f std.fs MVP
1a59298 raise T(args) statement form
```

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
- Nested JSON navigation for anything beyond dot-paths — `field_string_path` / `field_int_or_path` cover `a.b.0.c`-style walks, but not general JSONPath, filters, or wildcards
- Real subprocess status + stderr separation

Each is a substantial landing. The MVP validated that the runtime + FFI + prelude autoloading + capability composition all hold up end-to-end and that the seven MVP capabilities compose cleanly. Full agent orchestration composes on top when those features land.
