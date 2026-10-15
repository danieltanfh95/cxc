# appkit-hello

The smallest useful AppKit demo written in Corvid. Opens a native
`720x480` window, draws a blue rectangle, a red rectangle, and two
lines of text. Zero Objective-C in the `.cxc` source. The `appkit`
module hides both the plugin manifest and the FFI symbol table
behind a `Window` capability atom.

For the fuller demo (a sim rendered live to a 1280x800 window with
card art) see [../appkit-viewer/](../appkit-viewer/).

## What this demonstrates

- `from appkit import Window` in a fresh `main.cxc`, no manifest
  boilerplate, no `plugin.get(...)` calls, no ObjC.
- The `runtime_shape: RuntimeShape.main_runloop` field declared once
  in `appkit/window.cxc` still routes through the multi-file compile
  so `main()` ends in `[NSApp run]` and the window stays open.
- Every drawing primitive as a named method on `Window`:
  `window.clear(r, g, b)`, `window.rect(x, y, w, h, r, g, b)`,
  `window.text(x, y, s, size, r, g, b)`, `window.present()`.

## Files

- [main.cxc](main.cxc): the demo, 6 draw calls in one transition.
- [appkit/window.cxc](appkit/window.cxc): the plugin module. Same
  shape as the appkit-viewer version but slimmer, only the FFI
  symbols this demo needs.
- [build.sh](build.sh): builds the shared `libappkit.dylib` (source
  lives in `../appkit-viewer/appkit/`), runs the multi-file Corvid
  compile, links.

## Building

```bash
./build.sh
```

Produces `/tmp/appkit-hello-out/hello` (an executable) alongside a
locally-built `appkit/libappkit.dylib` in this directory. The native
side of the plugin is shared with appkit-viewer to avoid duplicating
Cocoa glue. See the note in `build.sh`.

## Running

```text
/tmp/appkit-hello-out/hello
```

A native macOS window opens. Close it with the red button (or ⌘Q)
to quit.
