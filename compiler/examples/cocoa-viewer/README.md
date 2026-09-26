# cocoa-viewer

A Corvid plugin that FFIs to a Cocoa drawing wrapper and emits PNGs.
Two consumers share the same dylib:

- [dota2_frame.cxc](dota2_frame.cxc): one static frame, rendered
  offscreen. Minimal FFI demo.
- [dota2_live.cxc](dota2_live.cxc): the pocket-MOBA sim from
  `cards/dota2/cxc/cards.cxc`, wired to a `Renderer` sub-atom that
  writes a PNG at every sim phase boundary. Play through with
  Preview.app via [watch](watch).

Demonstrates:

- `def plugin:` + `foreign "cocoa_viewer":` block declaring FFI symbols.
- `use plugin: Plugin` in a consumer, calling `plugin.get("cv_rect")(...)`.
- Corvid → Objective-C emit linked against a user-supplied dylib.
- A `Renderer` atom that walks live sim state via chained
  observations, keeping the sim body byte-parity clean.

## Files

- [libcocoa_viewer.m](libcocoa_viewer.m): the C-callable Cocoa wrapper.
  Accumulates `DrawOp` objects in memory and renders them into a
  `CanvasView`-backed `NSBitmapImageRep` on demand. Exposes
  `cv_open_offscreen` / `cv_clear` / `cv_rect` / `cv_line` /
  `cv_text` / `cv_snapshot`. **Snapshot only** — live-window FFI was
  attempted and removed, see "Why no live window" below.
- [viewerd.m](viewerd.m): a standalone Cocoa app (NOT loaded from
  the sim). Reads `/tmp/dota2-frames/f*.png` and displays them in
  its own NSWindow, auto-advancing on an NSTimer (auto mode) or
  responding to arrow keys (step mode). Runs in its own process
  with its own NSApplication + CFRunLoop, which is why it can do
  what the in-process FFI cannot.
- [dota2_frame.cxc](dota2_frame.cxc): the static-frame consumer.
  Writes `/tmp/dota2_frame.png`.
- [dota2_live.cxc](dota2_live.cxc): the sim consumer. Body copied
  verbatim from `cards/dota2/cxc/cards.cxc` (byte-parity preserved
  against `snapshots/seed-42.out`); `def Renderer:` and the `def
  plugin:` manifest are the deltas.
- [watch](watch): shell wrapper — builds if needed, runs the sim,
  hands the resulting frames to one of four viewers (viewerd auto,
  viewerd step, Preview.app, or ffmpeg → QuickTime).
- [build.sh](build.sh): end-to-end build script. Compiles the dylib
  and viewerd, runs the Corvid compiler, and manually re-links the
  emitted `.m` against `libcocoa_viewer.dylib`.

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

## Running the static frame

```
/tmp/cocoa-viewer-out/dota2_frame
```

Writes `/tmp/dota2_frame.png` and exits. Open the PNG with anything
that reads PNG (Preview, `qlmanage -p`, `open`).

## What the frame shows

Three horizontal lane strips against a dark background, each with:

- The lane label + tower HP.
- Blue rectangles: your units, labeled with name + hp/max.
- Red rectangles: enemy units, labeled with name + hp/max.

Sizes and positions are hardcoded in `dota2_frame.cxc`. Editing the
`plugin.get("cv_rect")(...)` calls changes the frame.

## Running the live sim

```
./watch                    # auto mode (default): viewerd auto-advances
./watch --step             # viewerd, arrow-key stepping
./watch --preview          # frames in Preview.app, sidebar + arrow keys
./watch --mp4              # ffmpeg encode, QuickTime auto-plays (Cmd-L loops)
./watch --fps 6            # auto-mode framerate (default 4)
./watch --rebuild          # force rebuild before running
```

The [watch](watch) script builds `dota2_live` and `viewerd` if
either is missing, runs the sim once (which writes PNGs to
`/tmp/dota2-frames/`), then hands the frames to the chosen viewer.

Under the hood, `dota2_live` calls `cv_open_offscreen` at startup
and `cv_snapshot("/tmp/dota2-frames/fN.png")` at every sim phase
boundary via a `Renderer` sub-atom. It never opens a window itself.
Every "viewer" is out-of-process:

- **viewerd** is a small standalone Cocoa app (see [viewerd.m](viewerd.m))
  with its own NSApplication + CFRunLoop. Space toggles play/pause,
  Left/Right step one frame, `r` re-scans the dir, Escape quits.
- **Preview.app** displays the PNGs directly, with its sidebar
  showing all frames as thumbnails and arrow keys stepping.
- **QuickTime Player** plays a `.mp4` produced by ffmpeg. Cmd-L
  enables looping. Requires `ffmpeg` on `PATH`.

### What each frame shows

- **Header**: round number, current phase label (start / drew hands /
  market refilled / auctions done / deploys done / towers placed /
  before combat / combat done / final winner banner).
- **Scoreboard row**: p0 and p1 hand size, towers lost, auction wins.
- **Deck row**: deck / discard sizes, running frame counter.
- **Three lane strips**: each carries the two sides' tower state
  (`tower N (name)` or `no tower`) plus a `[Name hp/max]` line
  enumerating up to four units per side, then `combat_idx` /
  `overkill` state during combat resolution.

### How the live-view code is organised

`def plugin:` sits at the top of `dota2_live.cxc` next to the FFI
symbol list. The pocket-MOBA sim atoms come next, unchanged from the
reference (byte-parity with `snapshots/seed-42.out` is preserved)
except for a couple of view-only observations on `LaneSide`
(`tower_str`, `unit_line`, and a `_sentinel_unit`).

`def Renderer:` right before `def main:` owns the plugin call chain
for one frame. Every plugin call becomes a discarded alias at the top
of `draw_frame`'s `then:` body, because Corvid requires all `alias`
bindings before non-alias statements and the FFI is a value
expression, not a statement.

`def main:` grows a `renderer:` sub-atom and an offscreen open at the
transition into `idle`. Each phase boundary in main's transition
machine fans out `|> renderer.draw_frame(...)` before the next sim
step.

## Why no live window

An earlier iteration exposed `cv_open` / `cv_present` / `cv_next_key`
/ `cv_run` so a Corvid sim could drive a live NSWindow directly.
That path is not workable in-process with the current Corvid
runtime. Corvid's generated `main()` calls `dispatch_main()`, which
pumps the main queue but never installs a CFRunLoop on the main
thread. The `CXC_SINGLE_MAIN_QUEUE=1` runtime routes atom sync sends
inline on the caller's thread, typically a libdispatch worker rather
than the actual main thread. Every hop back to main fails:

- Inline AppKit calls raise `NSInternalInconsistencyException`
  (AppKit checks the running thread, not queue ownership).
- `dispatch_sync(main_queue)` traps because the caller chain
  transitively owns the main queue via the target chain.
- `dispatch_async(main_queue) + semaphore` deadlocks: the main queue
  can't drain while the semaphore-waiting block is still executing on
  it via the target chain.
- `performSelectorOnMainThread:` raises
  `NSDestinationInvalidException`: the main thread has no NSRunLoop
  under `dispatch_main()`.

The workable shape today is offscreen rendering plus a separate
viewer process (Preview.app here). If a future Corvid revision
carves out a "reserved main thread" runtime mode, or emits a
CFRunLoop-driven entrypoint, an in-process window becomes viable.

## Why this shape

The Cocoa integration lives inside a `def plugin:` block, per the
Corvid v1.0 spec's unified plugin form. Every capability that touches
the OS goes through the same shape: a plugin manifest naming a
foreign library, a set of `symbol` declarations, and consumer code
that does `use plugin: Plugin` + `plugin.get("name")(args)`. The
plugin definition + FFI + rpath dylib gives us native drawing
without teaching the compiler about AppKit specifically.
