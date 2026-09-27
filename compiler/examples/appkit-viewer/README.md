# appkit-viewer

A native AppKit window driven from Corvid source. No Objective-C in
the sim code, no out-of-process viewer, no PNG snapshotting hop. The
`.cxc` file describes both the simulation and the rendering, the
compiler emits a `main()` that hands the main thread to AppKit's
CFRunLoop, and the atom lifecycle drives draw calls onto the window
through a small FFI plugin.

Two demos share the same [libappkit.m](libappkit.m) plugin substrate:

- [hello_window.cxc](hello_window.cxc): opens a native window, draws
  two coloured rectangles and two text lines. Minimal shape.
- [dota2.cxc](dota2.cxc): the pocket-MOBA sim, rendered live to a
  1280x800 window with card art, HUD, three lane panels, and a
  centered `GAME OVER` banner on the final frame. Screenshot below.

## Screenshots

[hello_window](screenshots/hello-window.png): the smallest useful
program. Two rectangles, a header, and a hint that closing the window
quits the process.

[dota2](screenshots/dota2-game-over.png): the sim after `p1 wins`
plays out. Card art loaded from `cards/dota2/card-art-feathered/`,
lane panels drawn from live atom state.

## What this demonstrates

- `def plugin:` with `runtime_shape: RuntimeShape.main_runloop` — a
  manifest field the compiler reads at compile time to decide the
  shape of `main()`.
- A user-mode Corvid file that opens a native window and draws to it,
  with zero Objective-C or Cocoa in the `.cxc` source.
- The `Renderer` sub-atom idiom: rendering is a plugin call chain
  fanned out from a `draw_frame` transition; the sim body is
  unaware of AppKit.
- Compile-time image loading: `plugin.get("ak_image")(...)` accepts
  absolute paths; missing files print once and become no-ops.

## What made this possible

Corvid's `main()` historically ended with `dispatch_main()`, which
pumps the main queue but never installs a CFRunLoop. AppKit needs
a CFRunLoop on the actual main thread to be useful (see the older
[cocoa-viewer README](../../../../corvid/compiler/examples/cocoa-viewer/README.md)'s
"Why no live window" section for the failure modes). The 2026-09-26
runtime-shape landing added a single field to `def plugin:`:

```corvid
runtime_shape: RuntimeShape! = RuntimeShape.main_runloop
```

The compiler sees this in the plugin manifest, threads it onto the
program AST, and swaps `emit-main`'s tail from `dispatch_main()` to
`[NSApp run]`. Full design in
[docs/design/runtime-shape.md](../../../docs/design/runtime-shape.md);
the three prototypes considered are in
[docs/design/runtime-shape-prototypes.md](../../../docs/design/runtime-shape-prototypes.md).

Two things get gated on the same `:main-runloop` value:

1. The tail of `main()`: `[NSApplication sharedApplication]; [NSApp run];`
   replaces `dispatch_main();`.
2. `cxc_shutdown()`'s `exit(0)` branch: under `:main-runloop`, atom
   lifecycle end is not the process exit path. AppKit owns the
   window's death; closing the window fires the delegate which calls
   `[NSApp terminate:nil]`. The atom hierarchy tearing down mid-run
   is fine — the last drawn frame stays on screen until the user
   closes the window.

## Files

- [libappkit.m](libappkit.m): the AppKit FFI plugin. Exposes drawing
  primitives (`ak_clear`, `ak_gradient`, `ak_rect`, `ak_round_rect`,
  `ak_line`, `ak_text`, `ak_text_bold`, `ak_image`), a window
  (`ak_prep`, `ak_window_open`, `ak_present`, `ak_run`), and
  subscription hooks (`ak_subscribe_key`, `ak_subscribe_close`). All
  drawing calls marshal to main via `dispatch_async` internally, so
  the sim can call them from any queue.
- [hello_window.cxc](hello_window.cxc): the smallest demo.
- [dota2.cxc](dota2.cxc): the pocket-MOBA demo. Body copied from
  `cards/dota2/cxc/cards.cxc`; `def Renderer:` and the `def plugin:`
  block are the additions.
- [build.sh](build.sh): builds the dylib, compiles the Corvid source,
  re-links against the dylib.

## Building

```
./build.sh                     # builds hello_window
./build.sh $(pwd)/dota2.cxc    # builds dota2
```

Produces `/tmp/appkit-out/<demo>` alongside `libappkit.dylib` in this
directory.

## Running

```
/tmp/appkit-out/hello_window
/tmp/appkit-out/dota2
```

A native macOS window opens. Close it with the red button (or ⌘Q)
to quit.

## Threading contract

- `ak_prep` and `ak_run` MUST be called on the main thread.
- Every other `ak_*` call is safe from any thread; the plugin
  marshals to main via `dispatch_async` internally.
- Drawing accumulates in a per-window command buffer.
  `ak_present()` triggers `setNeedsDisplay:` on the view; `drawRect:`
  replays the buffer.
- Callbacks (`ak_subscribe_key` / `ak_subscribe_close`) fire on main.

## What's next

- Multi-window: today's plugin holds a single global `NSWindow`. A
  window-atom shape that pairs one Corvid atom with one NSWindow is
  the natural next step.
- Cross-file plugin visibility: the `runtime_shape` field is currently
  only checked at same-file scope. When a plugin ships in one `.cxc`
  and a consumer imports it from another, `.cxc-caps` propagation
  needs to carry the field.
- `linux-gtk` and `windows-win32` runtime shapes: the `RuntimeShape`
  enum grows a case, `emit-main` grows a branch, and the same
  `def plugin:` shape covers each platform's main-loop mechanic.
