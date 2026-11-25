# appkit-hello

The smallest useful AppKit demo written in Corvid. Opens a native
`720x480` window, draws a blue rectangle, a red rectangle, and two
lines of text. Zero Objective-C in the `.cxc` source. The `appkit`
module hides both the plugin manifest and the FFI symbol table
behind a `Window` capability atom.

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
- [appkit/window.cxc](appkit/window.cxc): the plugin module
  declaring the FFI symbols this demo needs.
- [appkit/libappkit.m](appkit/libappkit.m): Cocoa glue the dylib
  links against.
- [build.sh](build.sh): builds `libappkit.dylib` from the local
  native source, runs the multi-file Corvid compile, links.

## Building

```bash
./build.sh
```

Produces `/tmp/appkit-hello-out/hello` (an executable) alongside a
locally-built `appkit/libappkit.dylib` in this directory.

## Running

```text
/tmp/appkit-hello-out/hello
```

A native macOS window opens. Close it with the red button (or ⌘Q)
to quit.
