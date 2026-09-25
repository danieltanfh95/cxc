# cocoa-viewer

A Corvid plugin that FFIs to a Cocoa drawing wrapper, renders a static
dota2-simulator frame in a native macOS window.

Demonstrates:

- `def plugin:` + `foreign "cocoa_viewer":` block declaring FFI symbols.
- `use plugin: Plugin` in a consumer, calling `plugin.get("cv_rect")(...)`.
- Corvid → Objective-C emit linked against a user-supplied dylib.

## Files

- [libcocoa_viewer.m](libcocoa_viewer.m): the C-callable Cocoa wrapper.
  NSApplication + NSWindow + a custom `CanvasView` that plays back a
  list of `DrawOp` objects. Exposes `cv_open` / `cv_clear` / `cv_rect`
  / `cv_line` / `cv_text` / `cv_present` / `cv_run`.
- [dota2_frame.cxc](dota2_frame.cxc): the Corvid consumer. Declares
  the plugin manifest, uses it in `def main:`, and draws a hardcoded
  turn-3 frame (three lanes with unit rectangles + tower HP labels).
- [build.sh](build.sh): end-to-end build script. Compiles the dylib,
  runs the Corvid compiler, and manually re-links the emitted `.m`
  against `libcocoa_viewer.dylib`.

## Building

```
./build.sh
```

Or with a different Corvid demo file:

```
./build.sh my_other_demo.cxc
```

Produces `/tmp/cocoa-viewer-out/<demo>` (an executable) alongside a
`./libcocoa_viewer.dylib` in this directory.

The Corvid compiler currently emits FFI calls without extern
declarations for user-supplied libraries, so the internal clang step
inside `cxc.build` will fail with `implicit-function-declaration`
errors. `build.sh` recovers by re-invoking clang with
`-Wno-implicit-function-declaration` and the correct `-L` / `-rpath` /
`-l` flags. This is a known gap in the compiler's plugin lowering
pipeline. When the compiler learns to emit externs from `foreign`
blocks, this rebuild step goes away.

## Running

```
/tmp/cocoa-viewer-out/dota2_frame
```

A 720x480 window opens with a rendered frame. Close it via Cmd-Q or
the red close button.

If the window doesn't appear:

- Confirm you're on macOS with a graphical session (not SSH).
- Confirm `libcocoa_viewer.dylib` is next to the binary or on `@rpath`
  (`otool -L /tmp/cocoa-viewer-out/dota2_frame` shows the load path).
- Confirm the process has permission to draw (System Settings →
  Privacy → Screen Recording for terminal apps drawing via NSApp,
  usually not needed for basic windows).

## What the frame shows

Three horizontal lane strips against a dark background, each with:

- The lane label + tower HP.
- Blue rectangles: your units, labeled with name + hp/max.
- Red rectangles: enemy units, labeled with name + hp/max.

Sizes and positions are hardcoded in `dota2_frame.cxc`. Editing the
`plugin.get("cv_rect")(...)` calls changes the frame.

## Next steps

To turn this from a static frame into a live view of a running sim:

1. Have the sim atom emit frame data (per-turn snapshots as `Frame`
   records: `{ turn, lanes, hp, hand_count, deck_count }`).
2. Wire the viewer as a subscriber that receives each frame and
   re-renders on every emit.
3. Bind keyboard input via a new FFI symbol (`cv_next_key -> Int32`)
   so the user can step through turns with Space.

The wrapper's `cv_present` already yields to the runloop, so
incremental redraw between turns is a matter of calling it after each
frame's draw calls.

## Why this shape

The Cocoa integration lives inside a `def plugin:` block, per the
Corvid v1.0 spec's unified plugin form. Every capability that touches
the OS goes through the same shape: a plugin manifest naming a
foreign library, a set of `symbol` declarations, and consumer code
that does `use plugin: Plugin` + `plugin.get("name")(args)`. The
plugin definition + FFI + rpath dylib gives us a native GUI without
teaching the compiler about AppKit specifically.
