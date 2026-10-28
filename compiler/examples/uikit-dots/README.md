# uikit-dots

A tap-anywhere paint toy that exercises the full phone-game plugin
stack from a single Corvid atom. Every subdirectory under this
sample is a self-contained cxc plugin; the top-level
`uikit-dots.cxc` imports all four and wires them to the lifecycle
arms the cxc compiler emits.

## What it demonstrates

```
plugin           what it owns                   proven in sim
──────────────────────────────────────────────────────────────────
canvas/          CxcCanvasView                  6 demo dots in a
                 (UIView + shape list) +        diagonal paint on
                 touchesBegan/Moved/Ended       launch; touch arms
                                                mirror the proven
                                                uikit-canvas tap
                                                pattern
audio/           AVAudioPlayer cache keyed      audio.load("beep",
                 by name                        "beep.wav") returns
                                                0 (shim finds the
                                                bundled asset)
prefs/           NSUserDefaults integer         total_dots survives
                 wrapper                        app relaunch via
                                                cfprefsd flush
assets/          NSBundle byte-length           read_bytes_count
                 probe                          ("beep.wav") = 7100
                                                (matches on-disk
                                                size)
```

The sample proves the compiler-side support landed in P1:

- `target.clj` adds AVFoundation / AudioToolbox to the iOS
  framework list, so `audio/libaudio.m` links out of the box.
- `bundle.clj` mirrors `Resources/*` into the `.app` root, so
  anything you drop into `Resources/` is reachable via
  `[[NSBundle mainBundle] pathForResource:ofType:]` at runtime.

## Files

```
uikit-dots/
├── uikit-dots.cxc              main atom, four `use` lines
├── Resources/beep.wav          synthesized 880 Hz tone (7100 bytes)
├── canvas/{canvas.cxc,libcanvas.m}
├── audio/{audio.cxc,libaudio.m}
├── prefs/{prefs.cxc,libprefs.m}
├── assets/{assets.cxc,libassets.m}
├── build.sh
└── README.md
```

Every `.m` carries a user shim; every `.cxc` declares a
`def plugin:` foreign block + a cap atom that wraps each symbol
in a one-liner method. Follow the pattern for your own plugins.

## Build

```bash
./build.sh                       # compile only
./build.sh --boot                # compile, install, launch
./build.sh --boot --device UDID  # pick a specific simulator
```

## Expected output

On the first launch after an uninstall:

```
uikit-dots: launched, beep asset = 7100 bytes
uikit-dots: resumed with total_dots = 0
```

The simulator shows six colored circles arranged diagonally on a
near-black canvas (two will fall off the right edge on narrower
devices; harmless).

Tap anywhere on the canvas:
- a new colored dot drops at the tap location
- `beep.wav` plays
- `total_dots` increments and persists to NSUserDefaults

Drag to drop smaller dots along the move path. Relaunch and the
`resumed with total_dots = N` line reflects the previous session's
count.

## Headless click injection on this host

cliclick / osascript need macOS accessibility permissions. If
yours lacks them, launch the sample, tap manually in the
Simulator window, and watch the dots accumulate.
