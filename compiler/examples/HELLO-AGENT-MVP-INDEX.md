# Hello-Agent MVP — CXC Files + Landings

Session index of everything shipped in the 2026-09-02/03 hello-agent MVP
push. Grouped by artifact and by feature area.

## Prelude capabilities (compiler-shipped, autoloaded)

Under [resources/prelude/target/macos/](../resources/prelude/target/macos/):

| File | Capability | Methods | Landed |
|------|------------|---------|--------|
| [fs.cxc](../resources/prelude/target/macos/fs.cxc) | `FileSystem` | `read_text` / `write_text` / `append_text` / `exists` / `is_dir` / `is_file` / `mkdirs` / `remove` / `copy` / `rename` / `size` / `tmp_path` / `list_dir` / `read_bytes` / `write_bytes` | 47a4c2f + 2c9d094 + 25cb3bd + 5e10972 + 954b5b7 + 40d5786 + 07a5ff9 |
| [subprocess.cxc](../resources/prelude/target/macos/subprocess.cxc) | `Subprocess` | `run(cmd)` + `run_full(cmd)` (envelope: `{exit_code, stdout, stderr}` via NSTask) | 07d4994 + 358f883 |
| [env.cxc](../resources/prelude/target/macos/env.cxc) | `Env` | `get_or` + `get_or_int` + `get_or_bool` + `set` + `remove` + `has` | c5e2f68 + db01b54 + 25cb3bd + 700b377 |
| [args.cxc](../resources/prelude/target/macos/args.cxc) | `Args` | `length()` + `get_or` + `get_or_int` + `get_or_bool` + `all_after` | 4d6a897 + 962108b |
| [json.cxc](../resources/prelude/target/macos/json.cxc) | `Json` | `encode_str` / `field_string` / `field_string_or` / `field_int_or` / `field_string_path` / `field_string_or_path` / `field_int_or_path` / `field_bool_or` / `array_length_at` | 6b12a7d + c922c06 + a798ab9 + f3c49bd |
| [http.cxc](../resources/prelude/target/macos/http.cxc) | `Http` | `get` / `post` / `get_envelope` / `post_envelope` / `head_status` / `download_to_file` | 838533a + 0e17b7b + 210a1fa + 40d5786 |
| [wall_time.cxc](../resources/prelude/target/macos/wall_time.cxc) | `Time` | `now()` + `now_iso()` + `since(t)` + `sleep_ms(ms)` | c3e098e + db01b54 + 97cad99 |
| [hash.cxc](../resources/prelude/target/macos/hash.cxc) | `Hash` | `sha256_hex(bytes)` — CommonCrypto CC_SHA256 hex | 6cdc733 |
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
- [time-iso.cxc](./time-iso.cxc) — ISO 8601 timestamp shape assertions.
- [fs-copy-rename-size.cxc](./fs-copy-rename-size.cxc) — copy → rename → size → missing/dir → -1.
- [fs-tmp-path.cxc](./fs-tmp-path.cxc) — unique-per-call tmp_path (empty prefix omits dash; write-then-read roundtrip).
- [env-has.cxc](./env-has.cxc) — set-empty vs unset differentiation via has() (PATH positive control).
- [random-uuid.cxc](./random-uuid.cxc) — v4 UUID shape assertions (len, version nibble, hyphens, non-repeat).
- [args-all-after.cxc](./args-all-after.cxc) — join trailing argv tokens (empty on out-of-range / negative).
- [http-head-status.cxc](./http-head-status.cxc) — HEAD status: found / not-found / URL-error. Manual.
- [fs-list-dir.cxc](./fs-list-dir.cxc) — populate temp dir + list contents + missing-dir empty.
- [http-download.cxc](./http-download.cxc) — GET example.com → temp file → check size + doctype prefix. Manual.
- [subprocess-run-full.cxc](./subprocess-run-full.cxc) — envelope decomposition: normal / exit-42 / not-found (127).
- [fs-bytes-io.cxc](./fs-bytes-io.cxc) — F37 `.as() -> Bytes` on a record → write_bytes → read_bytes → size + nil-on-miss.
- [bytes-string-roundtrip.cxc](./bytes-string-roundtrip.cxc) — `String.to_bytes` / `Bytes.to_string` UTF-8 roundtrip, both in-memory and via disk.
- [hash-sha256.cxc](./hash-sha256.cxc) — SHA-256 of `"hello"` and `""` against known-answer vectors.
- [map-get-or.cxc](./map-get-or.cxc) — `Map of (String, Int).get_or(k, fallback) -> Int` — present-key value / missing-key fallback.
- [map-get-or-string.cxc](./map-get-or-string.cxc) — `Map of (String, String).get_or(k, fallback) -> String` (String branch of the synthesized method).
- [map-of-bool.cxc](./map-of-bool.cxc) — `Map of (String, Bool)` toggle map: add/get_or with present-key overriding fallback + missing-key falling through both directions.
- [list-at-or.cxc](./list-at-or.cxc) — `List of T .at_or(i, fallback) -> T` random access for T ∈ {Int, String, Bool}: in-range value / past-end fallback / negative-index fallback.
- [map-int-key.cxc](./map-int-key.cxc) — `Map of (Int, String)`: Int-as-key coverage (HTTP status→reason lookup).
- [list-contains.cxc](./list-contains.cxc) — `List of T .contains(e) -> Bool` for T ∈ {Int, String, Bool}: NSArray -containsObject: via -isEqual: on each element.
- [int-abs-min-max.cxc](./int-abs-min-max.cxc) — Int method intrinsics `abs()`, `min(other)`, `max(other)`; compile-time ternaries, no helper call.
- [trim-prefix-suffix.cxc](./trim-prefix-suffix.cxc) — String `.trim_prefix(pre)` / `.trim_suffix(suf)` — URL scheme strip, trailing-newline strip, pass-through on non-match / empty pattern.
- [string-repeat-pad.cxc](./string-repeat-pad.cxc) — String `.repeat(n)`, `.pad_left(n, ch)`, `.pad_right(n, ch)` — build-by-repetition and column-align formatting.
- [set-basic.cxc](./set-basic.cxc) — `Set of T` end-to-end for T ∈ {String, Int}: add/remove/contains/count with dedup semantics. Regression test for prior `Set of Int` compile break (params were id-typed but body tried to `@(id)` box).
- [string-to-bool-or.cxc](./string-to-bool-or.cxc) — String `.to_bool_or(fallback)` — parses true/false/yes/no/1/0/on/off case-insensitively; unrecognized/empty falls through to fallback.
- [json-string-or.cxc](./json-string-or.cxc) — Json `.field_string_or` / `.field_string_or_path` — nil-tolerant fallback variants; save the caller a `||: v == nil : fallback ||: else : v` ladder on every JSON lookup.
- [fs-is-dir-file.cxc](./fs-is-dir-file.cxc) — FileSystem `.is_dir` / `.is_file` — distinguish directory from regular file (via NSFileManager -fileExistsAtPath:isDirectory:); nine-way {dir, file, missing} × {exists, is_dir, is_file} matrix.
- [env-typed-get.cxc](./env-typed-get.cxc) — Env `.get_or_int(name, fallback)` / `.get_or_bool(name, fallback)` — typed env var lookup composed prelude-side from `get_or(name, "").to_{int,bool}_or(fallback)`.
- [args-typed-get.cxc](./args-typed-get.cxc) — Args `.get_or_int(i, fallback)` / `.get_or_bool(i, fallback)` — typed argv lookup, same composition shape as env; tested with runtime argv.
- [random-between.cxc](./random-between.cxc) — Random `.between(low, high)` — uniform sampling on [low, high); positive-only and negative-crossing ranges; degenerate width-1 always yields low.

## Non-plugin prelude capabilities enriched this session

| File | Capability | Additions this push | Landed |
|------|------------|---------------------|--------|
| [random.cxc](../resources/prelude/target/macos/random.cxc) | `Random` | `next_u32` + `below(n)` + `between(low, high)` + `hex(nybbles)` + `uuid()` — CSPRNG hex + RFC 4122 v4 UUID | 38b0774 + 7021840 |

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
- `String.to_bytes()` — UTF-8 encode to Bytes (NSMutableData). (a501598)
- `String.path_join(sub)` / `.basename()` / `.dirname()` / `.extension()` — NSString path methods. (9baf551)
- `Bytes.to_string()` — UTF-8 decode from NSMutableData. (a501598)

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
- `native_time_now_iso` — NSISO8601DateFormatter with fractional seconds, cached via dispatch_once. (97cad99)
- `native_fs_copy` / `native_fs_rename` / `native_fs_size` — NSFileManager copyItem / moveItem / attributesOfItemAtPath (regular files only for size). (5e10972)
- `native_fs_tmp_path` — NSTemporaryDirectory + 16-hex CSPRNG suffix; caller writes the file. (954b5b7)
- `native_env_has` — libc getenv NULL-check; distinguishes set-empty from unset. (700b377)
- `native_random_uuid` — Foundation NSUUID; lowercased canonical v4. (7021840)
- `native_args_all_after` — join argv[index..argc] with spaces. (962108b)
- `native_http_head_status` — sync NSURLSession HEAD; returns status Int or -1. (210a1fa)
- `native_fs_list_dir` — NSFileManager contentsOfDirectoryAtPath joined by `\n`; empty on error. (40d5786)
- `native_http_download_to_file` — NSURLSession dataTask + NSData writeToFile:atomically; binary-safe. (40d5786)
- `native_subprocess_run_full` — NSTask + separate stdout/stderr pipes; returns `{exit_code, stdout, stderr}` JSON envelope. (358f883)
- `native_fs_read_bytes` / `native_fs_write_bytes` — NSData dataWithContentsOfFile / writeToFile; Bytes ↔ NSMutableData. (07a5ff9)
- `native_sha256_hex` — CommonCrypto CC_SHA256 + hex-encode; base-includes adds `<CommonCrypto/CommonDigest.h>`. (6cdc733)
- `native_http_get` / `native_http_post` — sync NSURLSession dataTask + dispatch_semaphore. (838533a)
- `native_http_get_envelope` / `native_http_post_envelope` — same with `{status,body}` JSON envelope. (0e17b7b)

### Runtime / build

- `<crt_externs.h>` auto-included in Foundation programs (backs `_NSGetArg*`). (4d6a897)

## Commit sequence

```
e9b5771 parse: reject NSObject-colliding use-binding names
6cdc733 Hash capability: sha256_hex via CommonCrypto
9baf551 String path helpers (.path_join / .basename / .dirname / .extension)
a501598 String.to_bytes + Bytes.to_string — UTF-8 roundtrip
07a5ff9 FileSystem.read_bytes + .write_bytes — binary I/O
358f883 Subprocess.run_full — NSTask envelope with real exit code + separated streams
40d5786 FileSystem.list_dir + Http.download_to_file
210a1fa Http.head_status(url)
962108b Args.all_after(index)
7021840 Random.uuid() — RFC 4122 v4 UUID
700b377 Env.has(name) — presence check distinct from get_or fallback
954b5b7 FileSystem.tmp_path(prefix)
5e10972 FileSystem.copy + .rename + .size
97cad99 Time.now_iso() — ISO 8601 timestamp
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

Group A (language / codegen gaps) all landed 2026-09-03. The full
designed hello-agent still needs one item — third-party plugin
discovery — before it compiles end-to-end. See
[design/examples/hello-agent/STATUS.md](../../design/examples/hello-agent/STATUS.md)
for the authoritative list.

**Landed 2026-09-03:**

- **A1.** Multi-line method body (`sig :- RetType` + indented body block) — f221fe1
- **A2.** Tagged-variant codegen for reference-type payloads (`__unsafe_unretained` in the tag union) — 44e1f75
- **A3.** `|: v is .case(let bind)` — payload destructure at arm-head `is` position — 39a7577
- **A4.** Or-recovery on non-emit expressions (was 2d-iii(B)) — e45dcfd
- **A5.** Immutable `List of T` ops (`.append(v)` / `.filter(pred)`) — 2cc40f9
- **A6.** `|>` inside lambda body — d2ca9df
- **A7.** Third-party plugin discovery — DNS-reverse module paths in `src-dir/` (hand-vendored) or via `project.cxc` manifest declaring git-URL + tag deps (fetched into `~/.cxc/cache/<sha>/`, lockfile at `project.lock`). Publishing = `git push origin <tag>`. Zero registry infrastructure. See `compiler/examples/dns-reverse-plugin/`.

The full hello-agent example compiles as of A7 landing.

**Wrongly listed as gaps in prior versions of this file — actually landed** (REPL-verified 2026-09-03):

- Variant destructuring (`<: kind(field: let x):`) — works via both `<:` dispatch and `or T(field: let m):` recovery arms
- `=>` field update — works as field-update-in-transition-body with atomic commit at end of `then:`
- `List of T` container with `.for_each` / `.count` — landed; `.at_or` / `.contains` added this push (though `.append` / `.filter` (A5 above) still missing)

**Design-deferred (no forcing consumer):**

- `Path` distinct-type — String path helpers via `String.path_join/basename/dirname/extension` (9baf551) are the interim; Path newtype awaits a consumer that needs the type-level distinction
- `Stream of T` inbound bindings — mechanism-invention-trap guard: waits for first real consumer

## Compiler-hardening this push

- **NSObject-name-collision reject** (e9b5771) — `use <name>: T` for `<name>` ∈ {`hash`, `description`, `debugDescription`, `class`, `superclass`} now fails at parse-time with a targeted diagnostic + rename suggestion, instead of producing a deep clang error in the emitted `.m` when the synthesized `_<name>` ivar collides with NSObject's own. Grows conservatively — add more entries if new collisions surface.
- `ProcResult` as a distinct record type (envelope-shaped `{exit_code, stdout, stderr}` JSON is available via `subprocess.run_full` since 358f883 — richer record-typed return waits on native intrinsics returning records)
- Nested JSON navigation for anything beyond dot-paths — `field_string_path` / `field_int_or_path` cover `a.b.0.c`-style walks, but not general JSONPath, filters, or wildcards
- Real subprocess status + stderr separation

Each is a substantial landing. The MVP validated that the runtime + FFI + prelude autoloading + capability composition all hold up end-to-end and that the seven MVP capabilities compose cleanly. Full agent orchestration composes on top when those features land.
