# hickey-cycle-306 — FFI unification audit

Audit of the 54 `native_*` entries in `compiler/src/cxc/lower/ffi_builtins.clj`
and the question: which can migrate to `plugin.get("name")(args)` under the
current v1.0 FFI surface?

## Mechanism recap

`plugin.get("name")` resolves a single dyld symbol at compile time against
a `foreign "<library>":` block nested inside `def plugin:`. The retrieved
handle is a C function pointer with the signature the `symbol` entry
declares. Call sites emit as a direct C-level function call with C-type
marshalling at the boundary.

`native_*` is a different mechanism: each entry carries an `:emit-c` fn
that produces inline C/ObjC — statement-expression blocks that compose
multiple C calls, Foundation/CommonCrypto API messages, ARC-aware local
state (`__weak`, `dispatch_once`), NSError handling, bounds-check logic,
and so on. The emission is inline, not a function call.

## Classification

| Category | Sites | What it would take |
| --- | --- | --- |
| (a) libc symbol, one call, no post-processing | 0 | — |
| (b) libc symbol + trivial surface shim | ~3 | `foreign "libc":` entry + arithmetic in `.cxc` body |
| (c) compiler-emitted, needs runtime library linkage | ~4 | export `_cxc_reflection_*` via stable symbols + `foreign "cxc_runtime":` |
| (d) inline ObjC composition, multi-call, stateful | ~47 | extract body into a C/ObjC runtime library; link into every binary |

Nothing falls into (a). The 51 ffi_builtins entries plus the 3 seeded-prng
entries all require infrastructure beyond adding a `symbol "..."` entry.

## Per-site findings

### (b) libc shim — would migrate with `foreign "libc":` plus `.cxc` surface edit

- `native_random_u32` — `arc4random() & 0x7FFFFFFF`. libc call plus a bit mask.
  Would need `symbol "arc4random" -> () -> UInt32` and the mask moves
  into `random.cxc`.
- `native_random_below(n)` — `(int)(arc4random_uniform((uint32_t) n))`.
  libc call plus one cast. Would need `symbol "arc4random_uniform" -> (UInt32) -> UInt32`.
- `native_time_sleep_ms(ms)` — `usleep(ms * 1000)` with clamping to
  useconds_t max. libc call plus clamping; clamping moves into `wall_time.cxc`.

Migration blocked today: the `.cxc` surface would carry the type-juggling
that the current `:emit-c` keeps inside the compiler. Moving it is a
user-visible change (observable types change at the foreign-decl boundary,
not a breakage but a surface shift). Picking this up needs user sign-off
on where the clamp/mask discipline lives.

### (c) compiler-emitted single-fn — needs `foreign "cxc_runtime":`

- `native_class_names` → `_cxc_reflection_class_names()`
- `native_class_field_names(cls)` → `_cxc_reflection_class_field_names([cls UTF8String])`
- `native_class_method_names(cls)` → `_cxc_reflection_class_method_names([cls UTF8String])`
- `native_class_phase_names(cls)` → `_cxc_reflection_class_phase_names([cls UTF8String])`

These are the closest to migrable today — each resolves to exactly one
emitted C function. Blockers:

1. The functions are emitted `static` inside the compiled `.m` file.
   `plugin.get` resolves via dyld, so the symbols must be externally
   linked. Removing `static` risks name collisions with user code; they
   would need a namespacing convention and a runtime-side declaration.

2. The spec (§7.2.1, example at `foreign "cxc_runtime":`) hypothesizes
   this library tag but the compiler does not currently ship a
   `libcxc_runtime` or equivalent. Adding one is a build-infrastructure
   change (clang invocation, link order, installer path).

3. The current argument marshalling for these is `[cls UTF8String]` at
   the emit site. Under `plugin.get("...")(cls)` the `foreign` signature
   would be `(CString) -> CString`, and `.cxc` callers would need to
   marshal to CString explicitly. `reflection.cxc` currently writes
   `native_class_field_names(cls_name)` with `cls_name: String!` — the
   coercion happens invisibly. A migrated form would be
   `plugin.get("_cxc_reflection_class_field_names")(cls_name.c_str())`
   (or similar). The `.c_str()` surface itself may not exist.

### (d) inline ObjC composition — blocked, require a runtime library

47 sites fall into this bucket. Each emits a statement-expression
block spanning 5–40 C/ObjC lines, composing multiple API calls, NSError
handling, bounds checking, state caching, and argument marshalling. A
sampling:

- `native_console_readln` — 4 KiB stack buffer + fgets + strlen trim
  + NSString constructor. Four C calls plus state; no single-symbol
  reduction.
- `native_sha256_hex` — CC_SHA256 + 64-char hex formatter loop.
  CommonCrypto call plus a format loop.
- `native_time_now_iso` — static NSISO8601DateFormatter cached via
  dispatch_once + `stringFromDate:[NSDate date]`. Three cached-state
  calls per invocation.
- `native_fs_read_text` — NSStringEncoding + NSFileManager +
  NSError handling. Multi-step.
- `native_fs_list_dir` — NSFileManager contentsOfDirectoryAtPath +
  componentsJoinedByString. Two Foundation calls.
- `native_subprocess_run_full` — NSTask setup + NSPipe stdout/stderr
  wiring + waitUntilExit + JSON envelope assembly. Dozens of lines.
- `native_http_get_envelope`, `native_http_post_envelope`,
  `native_http_head_status`, `native_http_download_to_file` —
  NSURLSession dataTask + dispatch_semaphore blocking + JSON envelope
  shaping. Each ~30 lines.
- `native_json_*` (11 entries) — NSJSONSerialization +
  NSDictionary lookups + type-filtering + default-coercion.
- `native_fs_*` write/append/copy/rename/mkdirs/remove/size/tmp_path/
  read_bytes/write_bytes/is_dir/is_file (14 entries) — each wraps
  NSFileManager with NSError handling and return-code discipline.
- `native_args_*` (3 entries) — reaches into process `argv` via a
  compiler-emitted global. Not a dyld symbol.
- `native_seeded_prng_*` (3 entries) — Park-Miller LCG state struct
  mutated across calls. Multi-call state, not a single-symbol lookup.
- `native_clock_every` — dispatch_source_t timer + CxcStream + weak
  subscriber fanout. ~40 lines of ObjC.
- `native_millis_now`, `native_random_uuid`, `native_random_hex`,
  `native_console_write`, `native_console_write_err` — Foundation /
  CommonCrypto / stdio calls with per-arg marshalling.

Migration requires:

1. **Runtime library.** Extract each `:emit-c` body into a standalone
   C/ObjC function in a `libcxc_runtime` (or similar). Stable export
   names, stable signatures, versioned ABI.
2. **Build-pipeline change.** Every `cxc compile` link step picks up
   `libcxc_runtime`. Distribution: ship as a dylib alongside the
   compiler, or static-link into each binary. Discoverability: `clang
   -L<path> -lcxc_runtime` or equivalent.
3. **`foreign "cxc_runtime":` declaration.** std.prelude gains a block
   enumerating every exported symbol with its signature. Current
   `foreign "libc":` block grows alongside.
4. **Surface rewrite.** Every `native_<foo>(args)` call in the prelude
   `.cxc` files becomes `plugin.get("cxc_runtime_<foo>")(args)` (or
   similar). The `:emit-c` entries retire from `ffi_builtins.clj`.
5. **Spec touch.** §1.14 / §7.2.1 gain a normative statement that
   `foreign "cxc_runtime":` is a reserved library tag the compiler
   guarantees to link; today the spec treats it as a hypothetical
   example.

Each of these touches is user-level decision territory (where does the
runtime live, how is it distributed, what's the ABI contract) and sits
outside the mechanical scope of this cycle.

## Recommendation

- **This cycle (306):** publish this plan, do not migrate any site.
  The sole category of truly mechanical migration (a) is empty; (b) is
  3 sites that all touch user-visible surface discipline; (c) and (d)
  require the runtime-library infrastructure.

- **Next cycle, user-scoped:** decide whether the Corvid stdlib wants
  a shipping runtime library. This unblocks all of (c) + (d). Without
  it, `native_*` remains the mechanism the prelude uses for
  non-libc-reducible operations, and the "two FFI systems" the user
  flagged in cycle 303 stays — because they aren't two systems serving
  the same purpose. `plugin.get` resolves single dyld symbols;
  `native_*` emits compiler-synthesized inline bodies. Collapsing one
  into the other requires promoting every compiler-synthesized body to
  a shippable runtime function.

- **Micro-migration path, if user picks it up:** take the three (b)
  sites (`arc4random`, `arc4random_uniform`, `usleep`) and move them
  through `plugin.get("libc_sym")(…)` in `random.cxc` and
  `wall_time.cxc`. This proves the terminal shape for the prelude
  without introducing runtime-library infrastructure. Scope: 3 symbol
  entries added to `std.prelude`, 3 `.cxc` body edits,
  3 `ffi_builtins.clj` entries retire.

## Audit mechanics

- `grep -on 'native_[a-z0-9_]*' resources/prelude/target/shared/*.cxc` →
  50 distinct symbols at call sites in the prelude.
- `grep '"native_' src/cxc/lower/ffi_builtins.clj` → 51 registered
  entries, plus 3 `native_seeded_prng_*` in the same table. One
  entry (`native_seeded_prng_new`) has no prelude call-site today;
  it's reachable only through the `SeededPrng(seed:)` ctor lowering.
- Sites producing a `List of T` return (not any today; several comments
  mention it as a planned follow-up to replace the newline-joined
  String interim) would need `foreign` to accept cxc container types,
  which the v1.0 spec marshals explicitly at the shim body, not at
  the plugin boundary. Deferred.
