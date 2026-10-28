# uikit-canvas

A cxc program that owns its own iOS window content. The main atom's
`did_launch(window: Any!)` arm paints the window a teal background
and places a centered label. All view-hierarchy work goes through
the `UIKit` capability atom; the user's `.cxc` file has zero
Objective-C.

## What this demonstrates

- `did_launch(window: Any!)` as a lifecycle arm. The cxc-generated
  `CxcAppDelegate` passes the live UIWindow in via performSelector,
  and the atom receives it as an opaque reference.
- A UIKit cxc binding via `foreign "cxc_uikit":` and a co-located
  `libuikit.m` shim. `cxc compile`'s directory mode auto-discovers
  the `.m` and co-compiles it with the generated code.
- The `set_window_background(window, r, g, b)` method is one line
  of cxc on top of one C function. Growing the surface is the same
  pattern.

## Files

- [uikit-canvas.cxc](uikit-canvas.cxc): main atom, one `did_launch`
  arm, five lifecycle arms total.
- [uikit/uikit.cxc](uikit/uikit.cxc): plugin module declaring the
  foreign block + the `UIKit` capability atom.
- [uikit/libuikit.m](uikit/libuikit.m): ObjC shim backing the
  symbols declared above.
- [build.sh](build.sh): compile, install, and (with `--boot`)
  launch in the first available iOS Simulator.

## Build

```bash
./build.sh                 # compile only
./build.sh --boot          # compile, install, launch
./build.sh --boot --device <UDID>
```

## Expected output

After `./build.sh --boot`:

```
uikit-canvas: painted
uikit-canvas: did_activate
```

The simulator shows a teal screen (RGB 30 / 120 / 140) with a
light-grey "hello from cxc" label centered. Send to background to
see `will_resign`, foreground to see `did_activate`.
