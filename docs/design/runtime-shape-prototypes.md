# Runtime shape — three prototypes tried in earnest

## The question

Corvid's `main()` today ends with `dispatch_main()`. That blocks any
framework requiring main-thread ownership (AppKit, GTK, Qt, …). For
Corvid to be a real GUI language on macOS without an ObjC shim, `main()`
must be *dynamic* — its shape has to change in response to what the
program actually needs.

The `main()` change itself is small: swap `dispatch_main()` for
`[NSApp run]`. AppKit's CFRunLoop drains `dispatch_get_main_queue()`
as part of its event pump, so existing atom code that hops to main via
`dispatch_async` keeps working. Substrate work: ~5 lines of emit + a
Cocoa include.

The real question is **where the shape signal comes from**: who
decides, when the decision is made, and how it's coordinated across
plugins and consumers.

Three prototypes span the design space, one at each level of the
stack.

## Prototype A · manifest-declared, compile-time

**Where the signal lives**: as a field in `def plugin:`.

```corvid
def plugin:
    name:          String!       = "appkit"
    version:       SemVer!       = SemVer(0, 1, 0)
    compat:        VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                                max: SemVer(2, 0, 0))
    runtime_shape: RuntimeShape! = RuntimeShape.main_runloop

    foreign "appkit":
        symbol "ak_window_open" -> (Int32, Int32, CString) -> Int32
        …
```

**Who reads it**: the compiler. `plugin-manifest.clj` parses the
field; `lower.clj` threads `:runtime-shape :main-runloop` onto the
program AST; `emit.clj` picks between `dispatch_main()` and
`[NSApp run]` in `emit-main`.

**Verified end-to-end**:
- `compiler/examples/appkit-hello/hello_window.cxc`
- Compiler now accepts `runtime_shape:` in the plugin manifest.
- The emitted `.m` file ends with `[NSApplication sharedApplication];
  [NSApp run]` when the plugin declared `.main_runloop`, else the
  today-shape `dispatch_main()`.
- Existing tests: plugin-manifest 59/150, emit 31/104, lower 394/1019
  all pass unchanged.
- Screenshot: native macOS window opens from the Corvid binary, blue
  and red rects drawn, "hello from corvid" title. Zero ObjC in the
  user's `.cxc` source.

**What breaks it**: multi-plugin coordination. If two imported
plugins declare different `runtime_shape` values, the compiler must
error out. Enforcement is straightforward (validate at manifest
extraction) but requires cross-module manifest visibility. In the
current prototype the plugin and consumer live in the same file, so
the check is local; the cross-file version needs `.cxc-caps`
propagation, which is designed but not yet built.

## Prototype B · runtime-hook negotiation, load-time

**Where the signal lives**: an `__attribute__((constructor))` inside
the plugin dylib.

```c
// In libappkit_hook.dylib:
static void plugin_run(void) { [NSApp run]; }

__attribute__((constructor))
static void plugin_register(void) {
    g_runtime_hook = plugin_run;
}
```

**Who reads it**: the runtime. The compiler emits an unconditional
call to `_cxc_runtime_prep()` before the tail of `main()`. If a plugin
registered a hook via its constructor (which runs at dylib load, i.e.
before `main()`), `_cxc_runtime_prep()` invokes it. Otherwise the
call is a no-op and `main()` falls through to `dispatch_main()`.

**Verified end-to-end**: `compiler/examples/appkit-hello/mechanism_B.m`
demonstrates the mechanism in a single-file harness. The constructor
runs at process startup ("[B] plugin constructor ran; hook registered"),
`_cxc_runtime_prep()` invokes `plugin_run` which calls `[NSApp run]`,
and the window opens.

**What it costs the compiler**: two lines. `emit-main` always emits
`_cxc_runtime_prep();` after atom bootstrap, and declares
`extern void _cxc_runtime_prep(void);`. No `:runtime-shape` field
needed. No manifest changes.

**What breaks it**: constructor ordering. If two plugins register,
last one wins (or first, depending on link order). No compile-time
validation of "at most one runtime shape" — the collision surfaces
only at runtime, and the user's diagnostic is "the wrong framework
is driving main." Also, plugin constructors run before Corvid's
runtime is initialized, so they can't safely allocate atoms or use
any Corvid primitive that assumes the scheduler is up. The
registered hook has to be a pure C function.

## Prototype C · weak-linked symbol, link-time

**Where the signal lives**: a strong definition of
`_Cxc_main_provider` inside the plugin dylib.

```c
// In libappkit_provider.dylib:
void _Cxc_main_provider(void) { [NSApp run]; }
```

**Who reads it**: the linker. The compiler emits a weak
forward-declaration and a null check in `main()`:

```c
extern void _Cxc_main_provider(void) __attribute__((weak));
…
if (_Cxc_main_provider) { _Cxc_main_provider(); return 0; }
dispatch_main();
```

When the plugin dylib is linked, the strong definition wins and the
symbol is non-NULL. When it isn't, the weak symbol resolves to NULL
and `main()` falls through.

**Verified end-to-end**: `compiler/examples/appkit-hello/mechanism_C.m`
demonstrates the mechanism. The strong definition wins ("[C]
_Cxc_main_provider: strong symbol won; [NSApp run]"), the window opens.

**What it costs the compiler**: three lines. `emit-main` emits the
weak declaration and the null-check branch.

**What breaks it**: linker semantics. If two dylibs both define
`_Cxc_main_provider` as strong, dyld's error surfaces at load time
with a duplicate-symbol diagnostic that doesn't mention runtime
shapes. Also, the plugin's linkage becomes load-bearing: a consumer
that decides to statically link (e.g. for distribution) loses the
weak-link mechanic and gets a link error unless the compiler emits
extra placeholder logic.

## Comparison

| Axis                          | A (manifest)                | B (constructor)               | C (weak link)                |
|-------------------------------|-----------------------------|-------------------------------|------------------------------|
| Where the author declares     | `def plugin:` source        | Nothing — plugin dylib exists | Nothing — plugin dylib exists|
| When the decision resolves    | Compile time                | Dylib load (before `main()`)  | Static link time             |
| Compiler cost                 | ~30 lines (schema + emit)   | ~2 lines (emit only)          | ~3 lines (emit only)         |
| Discoverable from `.cxc` source | Yes — grep for `runtime_shape:` | No — need to open plugin dylib source | No — need to inspect linker output |
| Multi-plugin conflict detection | Compile-time diagnostic  | Silent — last ctor wins       | Link-time (duplicate symbol) |
| Cross-platform generalization | Trivial — one field, N shapes | Weak — everyone races the ctor | Weak — one weak symbol name |
| Static-link friendly          | Yes                         | Yes                           | No — weak binding is dylib-shaped |
| Removes need for `.cxc-caps` propagation | No                | Yes                           | Yes                          |

## Second-order observations

**B and C are the same mechanism at different phases.** Both key on
"is the AppKit plugin present in the process." B checks via a runtime
pointer (set by ctor); C checks via a linker weak symbol. Same
information, different timing. This means B and C share failure modes
(hard-to-diagnose runtime binding), and switching between them is
mostly cosmetic.

**A is the odd one out.** It puts the signal in source. That makes
it grep-able, diff-able, reviewable — the same tools that already
audit Corvid code cover it. B and C hide the signal in the dylib's
binary structure, where standard source-level review misses it.

**Composition scales with A, not B/C.** When a program eventually
imports two plugins — say AppKit for GUI and a hypothetical
`priority_scheduler` that wants its own thread topology — A can
represent "at most one non-default runtime_shape" as a manifest
constraint. B and C can only detect the conflict at runtime, and B
can't detect it at all (last-write-wins is silent).

**The compiler cost of A is the schema, not the emit.** The 30 lines
are almost entirely field-parser boilerplate. The actual `emit-main`
switch is 5 lines. If Corvid ever grows a plugin-field DSL that
auto-generates parsers, A's cost collapses to the same 5 lines as B/C.

## Recommendation

**Ship Prototype A.**

Reasoning:

1. **Source-level discoverability outweighs the extra schema code.**
   A Corvid program's runtime shape is a decision that changes the
   entire process behavior. It belongs in source, where reviewers see
   it and grep finds it. B and C hide the decision in the dylib.

2. **Compile-time conflict detection is a real win over runtime
   collisions.** Composition is the eventual pressure — when the
   second plugin with a runtime shape shows up, A gives a clean error
   and B silently picks last-write-wins. The cost of A's cross-file
   `.cxc-caps` propagation is bounded and additive; the cost of
   debugging B's silent conflict is unbounded and one-off per incident.

3. **Cross-platform generalization is cleaner.** When a `linux-gtk`
   plugin ships, it declares `runtime_shape: RuntimeShape.gtk_main` in
   its manifest. A's `RuntimeShape` variant grows a case; B/C need
   parallel constructor / weak-symbol mechanisms per framework, and
   the compiler still has to know all of them to detect conflicts.

4. **B and C don't actually save compiler work.** The emit-main
   change for A is 5 lines. The savings B/C claim over A are in the
   *schema* code, not the emit. That savings evaporates the moment
   you want conflict detection or cross-platform coverage.

5. **A composes with everything Corvid already knows.** Plugin
   manifests, `.cxc-caps` emission, compile-time constants, error
   diagnostics — all of it already exists. Adding one field slots in
   naturally. B and C introduce a new axis of runtime binding that
   the rest of the compiler has no vocabulary for.

**What to keep from B and C.** The `_cxc_runtime_prep()` symbol from
B is worth keeping as an extension point for plugins that need to run
code before `main()` proper (thread pool init, logger setup) but that
don't want to own the main loop. That's an orthogonal concern to
runtime-shape selection; A doesn't preclude it.

## Follow-on work if A ships

- Cross-file `.cxc-caps` propagation: when the plugin lives in a
  different file from `def main:`, the consumer's compiler must read
  the plugin's `.cxc-caps` (or an equivalent import artifact) to see
  the `runtime_shape` value. Prototype today only covers same-file.
- Multi-plugin conflict validator: enforce "at most one non-default
  runtime_shape" at build-caps time.
- Grow `RuntimeShape` beyond `.dispatch_main` / `.main_runloop` when
  the next platform lands (`.gtk_main`, `.qt_exec`, `.custom(entry:)`).
- Retire the `corvid-appkit-out-of-process` memory rule; replace with
  a `corvid-runtime-shape` rule pointing at the manifest field.
- Move the cocoa-viewer example to appkit-viewer, fold viewerd's
  window/event loop into a Corvid source using this mechanism.

## Files touched

Under `compiler/examples/appkit-hello/`:

- `libappkit.m`, `libappkit.dylib` — AppKit FFI plugin, shared substrate.
- `harness_smoke.m` — verifies the plugin dylib works standalone.
- `hello_window.cxc` — Prototype A driver: compiles + runs.
- `mechanism_B.m` — Prototype B single-file harness.
- `mechanism_C.m` — Prototype C single-file harness.
- `build.sh` — builds hello_window through the modified compiler.

Compiler changes (Prototype A):

- `compiler/src/cxc/plugin_manifest.clj` — `eval-runtime-shape`,
  `runtime_shape` in the allowed field set, threaded onto the manifest map.
- `compiler/src/cxc/lower.clj` — extract at lower-time, stash on prog,
  add Cocoa include when `:main-runloop`.
- `compiler/src/cxc/emit.clj` — `emit-main` dispatches on
  `:runtime-shape`; `:main-runloop` variant emits `[NSApp run]`.
