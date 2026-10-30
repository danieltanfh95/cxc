# uikit-dots

A tap-anywhere paint toy that exercises three Corvid plugins from a
single atom. Every subdirectory under this sample is a self-contained
cxc plugin; the top-level `uikit-dots.cxc` imports them and wires
them to the lifecycle arms the cxc compiler emits.

## What it demonstrates

```
plugin           what it owns                   proven in sim
──────────────────────────────────────────────────────────────────
canvas/          CxcCanvasView                  touches mirror the
                 (UIView + shape list) +        proven uikit-canvas
                 touchesBegan/Moved/Ended       tap pattern on a
                                                blank canvas
audio/           AVAudioPlayer cache keyed      audio.load("beep",
                 by name                        "beep.wav") returns
                                                0, every subsequent
                                                audio.play("beep")
                                                fires the SFX
prefs/           NSUserDefaults integer         total_dots survives
                 wrapper                        app relaunch
```

The sample proves the compiler-side support landed in P1:

- `target.clj` adds AVFoundation to the iOS framework list, so
  `audio/libaudio.m` links out of the box.
- `bundle.clj` mirrors `Resources/*` into the `.app` root, so
  anything you drop into `Resources/` is reachable via
  `[[NSBundle mainBundle] pathForResource:ofType:]` at runtime.

## Files

```
uikit-dots/
├── uikit-dots.cxc              main atom, three `use` lines
├── Resources/beep.wav          synthesized 880 Hz tone (7100 bytes)
├── canvas/{canvas.cxc,libcanvas.m}
├── audio/{audio.cxc,libaudio.m}
├── prefs/{prefs.cxc,libprefs.m}
├── build.sh
└── README.md
```

Every `.m` carries a user shim; every `.cxc` declares a
`def plugin:` foreign block + a cap atom that wraps each symbol in
a one-liner method. Follow the pattern for your own plugins.

## Build

```bash
./build.sh                       # compile only
./build.sh --boot                # compile, install, launch
./build.sh --boot --device UDID  # pick a specific simulator
```

## Expected output

The simulator shows a blank near-black canvas on launch.

Tap anywhere on the canvas:
- a new colored dot drops at the tap location
- `beep.wav` plays
- `total_dots` increments and persists to NSUserDefaults

Drag to drop smaller dots along the move path. Relaunch and the
counter continues from the previous session.

## Headless click injection on this host

cliclick / osascript need macOS accessibility permissions. If yours
lacks them, launch the sample, tap manually in the Simulator window,
and watch the dots accumulate.
