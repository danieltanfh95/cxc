# appkit-viewer

A pocket-MOBA sim rendered live to a native AppKit window. Sim,
rendering, card-art layout, all in Corvid source. The sim body is
drawn from a private sim tree not vendored into this repo. `main.cxc`
does `from appkit import Window`, the plugin manifest and the FFI
symbol table live in the sibling `appkit` module
(`appkit/window.cxc`).

For the smallest self-contained AppKit demo (one window, two rects,
some text) see [../appkit-hello/](../appkit-hello/).

## What this demonstrates

- The multi-file compile shape: `main.cxc` at src-dir root imports
  `Window` from the `appkit` module. The user's demo never sees the
  plugin manifest or the `ak_*` FFI symbols, the module hides them.
- `runtime_shape: RuntimeShape.main_runloop` declared once in the
  imported plugin, threaded through the multi-file compile to change
  `main()`'s tail from `dispatch_main()` to `[NSApp run]` without the
  consumer knowing anything about it.
- `def Window:` as a capability atom: a facade whose methods
  (`window.rect(...)` / `window.text(...)` / `window.image(...)`)
  wrap `plugin.get("ak_...")(...)` calls one level down. Consumers
  work with named methods, not stringly-typed FFI dispatch.
- A `Renderer` sub-atom that walks live sim state via chained
  observations and drives the AppKit draw calls. The sim body is a
  copy of a private sim tree, excerpted into `main.cxc` for this
  demo.
- Compile-time image loading via `window.image(x, y, w, h, path)`.
  Missing files print once and become no-ops.

## What made this possible

Corvid's `main()` historically ended with `dispatch_main()`, which
pumps the main queue but never installs a CFRunLoop. AppKit needs a
CFRunLoop on the actual main thread to be useful, every hop back to
main from an atom under the old shape failed one of four ways:
inline AppKit tripped `NSInternalInconsistencyException` (AppKit's
thread check is `pthread_main_np()`, not queue ownership),
`dispatch_sync(main)` trapped (caller chain transitively owned the
main queue via the target chain), `dispatch_async + semaphore`
deadlocked (main queue couldn't drain while the semaphore-waiting
block still executed on it), and `performSelectorOnMainThread:`
raised `NSDestinationInvalidException` (no NSRunLoop on main). The
2026-09-27 runtime-shape landing added a single field to
`def plugin:`:

```elixir
runtime_shape: RuntimeShape! = RuntimeShape.main_runloop
```

cxc sees this in the plugin manifest (in any module the build sees,
including imported ones), threads it onto the program AST, and swaps
`emit-main`'s tail from `dispatch_main()` to `[NSApp run]`. Full design in
[docs/design/runtime-shape.md](../../../docs/design/runtime-shape.md).
the three prototypes considered are in
[docs/design/runtime-shape-prototypes.md](../../../docs/design/runtime-shape-prototypes.md).

Two things get gated on the same `:main-runloop` value:

1. The tail of `main()`: `[NSApplication sharedApplication]; [NSApp run];`
   replaces `dispatch_main();`.
2. `cxc_shutdown()`'s `exit(0)` branch: under `:main-runloop`, atom
   lifecycle end is not the process exit path. AppKit owns the
   window's death. Closing the window fires the delegate which calls
   `[NSApp terminate:nil]`. The atom hierarchy tearing down mid-run
   is fine, the last drawn frame stays on screen until the user
   closes the window.

## Files

- [main.cxc](main.cxc): the demo, sim + `from appkit import Window`
  + Renderer sub-atom. `def main:` at the root. Compiles to the
  `pocket-moba` binary.
- [appkit/window.cxc](appkit/window.cxc): the plugin module. Declares
  `def plugin:` (with `runtime_shape:` and every `ak_*` FFI symbol)
  and `def Window:` (the capability atom wrapping them).
- [appkit/libappkit.m](appkit/libappkit.m): the AppKit-facing C side.
  Exposes drawing primitives, window management, and subscription
  hooks. All drawing calls marshal to main via `dispatch_async`
  internally.
- [build.sh](build.sh): builds `libappkit.dylib`, runs the multi-file
  Corvid compile (`compile-directory-to-file`), links against the
  dylib.

## Building

```bash
./build.sh
```

Produces `/tmp/appkit-out/pocket-moba` (an executable) alongside
`appkit/libappkit.dylib` in this directory.

cxc currently emits FFI calls without extern declarations for
user-supplied libraries, so `build.sh` re-links the emitted `.m`
with `-Wno-implicit-function-declaration` and the correct `-L` /
`-rpath` / `-l` flags. This is a known gap. When cxc learns to emit
externs from `foreign` blocks, the recovery flag drops out.

## Running

```text
/tmp/appkit-out/pocket-moba
```

The sim plays through to completion, then the window holds the
final frame. The atom hierarchy has torn down but AppKit keeps its
run loop alive. Close the window with the red button (or ⌘Q) to
quit.

## Threading contract

- `ak_prep` and `ak_run` MUST be called on the main thread.
- Every other `ak_*` call is safe from any thread. The plugin
  marshals to main via `dispatch_async` internally.
- Drawing accumulates in a per-window command buffer.
  `ak_present()` triggers `setNeedsDisplay:` on the view. `drawRect:`
  replays the buffer.
- Callbacks (`ak_subscribe_key` / `ak_subscribe_close`) fire on main.

## What's next

- Multi-window: today's plugin holds a single global `NSWindow`. A
  window-atom shape that pairs one Corvid atom with one NSWindow is
  the natural next step.
- `.cxc-caps` propagation across project boundaries. Within one
  program (this directory), imports carry the `runtime_shape` field
  along with the rest of the manifest. Across published packages
  (`project.cxc` deps) the mechanism needs a serialised caps
  artifact.
- `linux-gtk` and `windows-win32` runtime shapes are unplanned at
  v1.0. The `RuntimeShape` enum would grow a case per platform, with
  `emit-main` growing a branch and the same `def plugin:` shape
  covering each platform's main-loop mechanic. See spec §1.12 for
  the capability model.
