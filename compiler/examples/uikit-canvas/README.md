# uikit-canvas

A cxc program that owns its own iOS window content and responds to
taps. The main atom's `did_launch(window: Any!)` arm paints the
window and installs a tap gesture recognizer; subsequent taps route
through `on_tap(x: Int32!, y: Int32!)` and repaint the label with
the current tap count. All view-hierarchy work goes through the
`UIKit` capability atom, so `uikit-canvas.cxc` has zero
Objective-C.

## What this demonstrates

- `did_launch(window: Any!)` as a lifecycle arm. The cxc-generated
  `CxcAppDelegate` passes the live UIWindow in via performSelector.
- A UIKit cxc binding via `foreign "cxc_uikit":` and a co-located
  `libuikit.m` shim. `cxc compile`'s directory mode auto-discovers
  the `.m` and co-compiles it with the generated code.
- Window state cached in the shim after `init(window)`, so later
  arms (`on_tap`, timers) paint without rethreading the window.
- Tap bridging through a `CxcTapHandler` ObjC class that reads the
  gesture location and dispatches `on_tapWithx:y:` on the exported
  `cxc_main_atom` symbol via typed `objc_msgSend`.
- A `taps: Int32!` field accumulated across tap arms with the
  normal `taps => taps + 1` transition.

## Files

- [uikit-canvas.cxc](uikit-canvas.cxc): main atom, tap counter,
  four lifecycle arms (`did_launch`, `on_tap`, `will_resign`,
  `did_activate`).
- [uikit/uikit.cxc](uikit/uikit.cxc): plugin module declaring the
  foreign block + the `UIKit` capability atom.
- [uikit/libuikit.m](uikit/libuikit.m): ObjC shim backing the
  symbols declared above, plus `CxcTapHandler` for gesture
  dispatch.
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

The simulator shows a teal screen (RGB 30 / 120 / 140) with the
word `tap me` centered in light grey. Tap anywhere on the window
and the label flips to `taps: 1`, `taps: 2`, and so on. Send to
background to see `will_resign`, foreground to see `did_activate`.
