# Runtime shape · dynamic main() based on plugin declaration

## Problem

Corvid's generated `main()` always calls `dispatch_main()`, which pumps
libdispatch queues but never installs a CFRunLoop on the main thread.
Atom code runs on libdispatch worker threads via the scheduler. This
is optimal for headless services but blocks any framework that
requires ownership of the main thread (AppKit, GTK, Qt, iOS).

Consequence: Corvid cannot ship a native GUI story without an
out-of-process shim written in the host framework's native language.
Public-release-wise, that reads as "Corvid replaces C/ObjC except
where it matters," which is a self-refutation of the language's value
proposition.

## Solution

Make `main()` emit compile-time-dynamic based on plugin declarations.
A plugin manifests the runtime shape it needs. cxc emits a
`main()` matching that shape.

### Data shapes, plugin manifest field

`def plugin:` grows one optional field:

```corvid
def plugin:
    name: String!         = "appkit"
    version: SemVer!      = SemVer(0, 1, 0)
    runtime_shape: RuntimeShape! = .main_runloop(worker_pool: 4)

    foreign "appkit":
        symbol "ak_window_open"  -> (Int32, Int32, CString) -> Int32
        ...
```

`RuntimeShape` is compiler-known:

```
.dispatch_main
    Current default. Atom scheduler owns the main queue (or the
    global concurrent queue under CXC_SINGLE_MAIN_QUEUE=0). main()
    ends with dispatch_main(). No CFRunLoop on main.

.main_runloop(worker_pool: Int! = 4)
    Framework plugin owns the main thread's run loop. main() spawns
    the atom scheduler on `worker_pool` pthreads, then calls the
    plugin's runloop entry symbol which owns main until exit. Atoms
    touch main-thread APIs via dispatch_async(dispatch_get_main_queue()).

.custom(entry_symbol: String!)
    Escape hatch. Plugin provides an entry function of shape
    void <entry>(void (*scheduler_start)(void)). Plugin calls
    scheduler_start when ready, then runs its own loop.
```

### Compile-time validation

- Zero or one plugin per binary may declare a non-default
  `runtime_shape`. Two → `E_MULTIPLE_RUNTIME_SHAPES`.
- `.main_runloop` requires `worker_pool >= 1`.
- `.custom` requires the entry symbol appear in the plugin's foreign
  block.

### Data flow, layer by layer

**Layer 0 (plugin manifest, `.cxc` source).** Author declares
`runtime_shape` on the plugin block, if non-default.

**Layer 1 (compiler analysis).** After plugin discovery, the compiler
scans all imported plugins for `runtime_shape`. Emits an
`_Cxc_RuntimeShape` C enum + `_Cxc_runtime_shape_g` global constant.
Enforces the "zero or one non-default" invariant.

**Layer 2 (compiler emit, main function).** `emit-main` dispatches on
runtime shape:

- `.dispatch_main` → today's emit unchanged (backwards-compat).
- `.main_runloop(worker_pool: N)` → emit:
  ```c
  int main(int argc, const char* argv[]) {
      @autoreleasepool {
          _Cxc_prelude_init(argc, argv);
          _Cxc_scheduler_start_on_workers(N);
          <plugin_name>_main_runloop_start();  // plugin-provided
      }
      return 0;
  }
  ```
- `.custom(entry_symbol: S)` → emit:
  ```c
  static void _Cxc_scheduler_kick(void) {
      _Cxc_scheduler_start_on_workers(<default_pool>);
  }
  int main(int argc, const char* argv[]) {
      @autoreleasepool {
          _Cxc_prelude_init(argc, argv);
          S(_Cxc_scheduler_kick);
      }
      return 0;
  }
  ```

**Layer 3 (runtime, scheduler on workers).** Under `.main_runloop` and
`.custom`, atom queues cannot target the main queue by default, the
main queue is drained by the AppKit run loop, not the atom
scheduler. Two runtime knobs:

- Atom target queue: for the non-default shapes, atoms target either
  a private serial queue owned by the scheduler pool, or the global
  concurrent queue with per-atom serialization (F32 L4.e.α pattern,
  reused).
- Main-hop primitive: atoms that need main-thread execution (drawing,
  keyboard input registration, window ops) use
  `dispatch_async(dispatch_get_main_queue(), ^{ ... })`. AppKit's run
  loop drains the main queue as part of its normal event pump.

**Layer 4 (plugin, AppKit).** The AppKit plugin exposes:

- Window/view lifecycle: `ak_window_open(w, h, title)`, `ak_close`.
- Drawing primitives: `ak_draw_rect`, `ak_draw_line`, `ak_draw_text`,
  `ak_draw_image`, `ak_clear`.
- Event subscription: `ak_subscribe_key(fn(code, chars, mods))`,
  `ak_subscribe_mouse(fn(x, y, button, kind))`,
  `ak_subscribe_window(fn(kind))`.
- Main-loop entry: `appkit_main_runloop_start()`, sets up
  NSApplication, activates, runs `[NSApp run]`.

Event callbacks fire on the AppKit main thread. The plugin marshals
them off-main via `dispatch_async` to a caller-supplied atom queue.

## Ordering guarantees

The existing F32 L4.e.α guarantee (atom send-order preserved under
single-threaded scheduler) must survive under `.main_runloop`. The
scheduler-on-workers path uses one of:

- Single serial queue for all atom targets (matches CXC_SINGLE_MAIN_QUEUE=1
  semantics but off-main).
- Per-atom serial queues (matches CXC_SINGLE_MAIN_QUEUE=0 semantics).

The default under `.main_runloop` is the single-serial shape, mirroring
today's default. Configurable per plugin via a future
`atom_ordering: .single | .per_atom` field if needed.

## Retirement

Status at 2026-10-13:

- `corvid-appkit-out-of-process` memory rule retired on 2026-09-27, superseded by `corvid-runtime-shape` which describes the new mechanism.
- `cocoa-viewer` example renamed to `appkit-viewer` on 2026-09-27, with the viewerd shim folded into Corvid source.
- An appkit section inside `docs/writing-corvid/` is not planned for v1.0, the guide stays scoped to the atom-substrate surface. The AppKit story lives in `compiler/examples/appkit-hello/README.md` and `compiler/examples/appkit-viewer/README.md`.

## Non-goals for this landing

- Multi-window support in the AppKit plugin (single window at v0.1).
- Menu bar customization beyond the default NSApp menus.
- Custom NSViews written in Corvid (drawing is still command-based, no
  virtual draw dispatch from AppKit into Corvid, the plugin owns the
  view and replays the atom-issued command buffer).
- Cross-platform (Linux GTK plugin can follow the same shape but is a
  separate landing).
