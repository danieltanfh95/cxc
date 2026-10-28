# uikit-canvas

A cxc program that owns its own iOS window content, responds to
taps, and advances every CADisplayLink vsync. The main atom's
`did_launch(window: Any!)` arm paints the window, installs a tap
gesture recognizer, and starts the game loop; `on_tap(x, y)`
counts taps and `on_tick(dt_millis)` counts frames. The label
re-paints every tick with both counters. All view-hierarchy work
goes through the `UIKit` capability atom, so `uikit-canvas.cxc`
has zero Objective-C.

## What this demonstrates

- `did_launch(window: Any!)` as a lifecycle arm. The cxc-generated
  `CxcAppDelegate` passes the live UIWindow in via performSelector.
- A UIKit cxc binding via `foreign "cxc_uikit":` and a co-located
  `libuikit.m` shim. `cxc compile`'s directory mode auto-discovers
  the `.m` and co-compiles it with the generated code.
- Window state cached in the shim after `init(window)`, so later
  arms (`on_tap`, `on_tick`) paint without rethreading the window.
- Tap bridging through a `CxcTapHandler` ObjC class that reads the
  gesture location and dispatches `on_tapWithx:y:` on the exported
  `cxc_main_atom` symbol via typed `objc_msgSend`.
- A CADisplayLink game loop installed by `start_game_loop()`. Each
  vsync the `CxcGameLoopHandler` computes a frame delta in ms and
  dispatches `on_tickWithdt_millis:` on the main atom.
- Two counters (`taps`, `frames`) accumulated across arms with the
  normal `taps => taps + 1` / `frames => frames + 1` transitions,
  surfaced through the same `uikit.paint_label` call.

## Files

- [uikit-canvas.cxc](uikit-canvas.cxc): main atom, tap + frame
  counters, five lifecycle arms (`did_launch`, `on_tap`, `on_tick`,
  `will_resign`, `did_activate`).
- [uikit/uikit.cxc](uikit/uikit.cxc): plugin module declaring the
  foreign block + the `UIKit` capability atom.
- [uikit/libuikit.m](uikit/libuikit.m): ObjC shim backing the
  symbols declared above, plus `CxcTapHandler` for gesture
  dispatch and `CxcGameLoopHandler` for the display-link loop.
- [build.sh](build.sh): compile, install, and (with `--boot`)
  launch in the first available iOS Simulator.

## Build

```bash
./build.sh                 # compile only
./build.sh --boot          # compile, install, launch
./build.sh --boot --device <UDID>
```

## Deploy to a real device

`build.sh` targets the simulator. To build the same sample for a
physical iPhone/iPad, invoke `cxc compile` directly with
`--target=ios-device` and set three env vars so the dev codesign
path can run:

```bash
export CXC_IOS_CODESIGN_IDENTITY="Apple Development: Jane Doe (ABCDE12345)"
export CXC_IOS_PROVISIONING_PROFILE=/path/to/app.mobileprovision
export CXC_IOS_ENTITLEMENTS=/path/to/entitlements.plist

cxc compile . build-device --target=ios-device
```

The compiler produces an arm64 iOS mach-o inside
`build-device/uikit-canvas.app`, embeds the provisioning profile
as `embedded.mobileprovision`, and signs the bundle with your
developer identity. Installation on the device goes through
`ios-deploy`, Xcode's Devices window, or Apple Configurator.

## Expected output

After `./build.sh --boot`:

```
uikit-canvas: painted
uikit-canvas: did_activate
```

The simulator shows a teal screen (RGB 30 / 120 / 140) with the
word `tap me` centered in light grey. The game loop then takes
over and the label becomes `taps: 0  frames: N` with `N` climbing
~60 per second. Tap anywhere on the window to bump the tap
counter. Send to background to see `will_resign`, foreground to
see `did_activate`.
