# Changelog

Format: date · tag · summary.

## 2026-09-28 · examples · appkit-viewer + appkit-hello · cross-file plugin import shape

Restructures the appkit example to import the plugin from a sibling module instead of declaring it inline. `main.cxc` now says `from appkit import Window` and never mentions the plugin manifest or the `ak_*` FFI symbols. Same shape as the B8 `hello-slice` fixture (2026-09-02) — the runtime-shape landing (2026-09-27, previous entry) is what let it apply to a full-fat GUI plugin, not a printing capability.

Two examples:

- `compiler/examples/appkit-hello/` — the minimal demo. One 720x480 window, two rects, two lines of text. Uses `compile-directory-to-file` to bring in the sibling `appkit` module.
- `compiler/examples/appkit-viewer/` — the pocket-MOBA sim as before, restructured to the same import shape. Renderer and main both do `use window: Window`. The `Window` capability atom lives in `appkit/window.cxc` alongside the plugin manifest and a `libappkit.m` that both examples share as source.

The `runtime_shape: RuntimeShape.main_runloop` field declared in the imported plugin still routes through `compile-directory-to-file`'s multi-file scan and reaches `emit-main` correctly; nothing new in the compiler for this restructuring. The change is entirely example shape + build script move to `compile-directory-to-file`. Full test suite (1072 tests / 2975 assertions) still passes.

Retires `hello_window.cxc` from `appkit-viewer/` (its role — proving the mechanism — is folded into `appkit-hello/main.cxc` as the smallest useful demo).

## 2026-09-27 · language · runtime-shape manifest field + appkit-viewer example (in-process live window)

Adds `runtime_shape:` to the `def plugin:` manifest and wires it end-to-end from parse to emit. A plugin manifest declaring `runtime_shape: RuntimeShape.main_runloop` causes `emit-main` to end `main()` with `[NSApplication sharedApplication]; [NSApp run];` instead of `dispatch_main()`, and gates `cxc_shutdown()`'s `exit(0)` behind `#if CXC_MAIN_RUNLOOP` so the atom hierarchy tearing down mid-run no longer race-terminates AppKit. Full test suite (1070 tests / 2971 assertions) plus 6 new manifest tests + 6 new emit tests, all pass.

Adds `compiler/examples/appkit-viewer/`: `libappkit.m` (Cocoa FFI plugin exposing `ak_clear`/`ak_gradient`/`ak_rect`/`ak_round_rect`/`ak_line`/`ak_text`/`ak_text_bold`/`ak_image`/`ak_present`/`ak_prep`/`ak_window_open`/`ak_run`/`ak_quit`/`ak_subscribe_key`/`ak_subscribe_close`), `hello_window.cxc` (minimal — a window with two rectangles and a title, drawn from Corvid source with zero ObjC), and `dota2.cxc` (the pocket-MOBA sim rendered to a 1280x800 window with card art, HUD, three lane panels, and a `GAME OVER` banner). Both link against the same dylib and use the same `runtime_shape:` field to opt in.

Retires the "no live window" workaround from the cocoa-viewer example. That path (offscreen PNG snapshots + out-of-process `viewerd`) was the only viable shape while Corvid's `main()` ran `dispatch_main()`; it stays in the corvid repo as a reference for what the constraint required, but the appkit-viewer example is the recommended shape going forward.

Design in `docs/design/runtime-shape.md` and `docs/design/runtime-shape-prototypes.md` (three prototypes considered — manifest / dylib constructor / weak-linked symbol — with the manifest shape winning on source-level discoverability, compile-time conflict detection, and cross-platform generalisation).

## 2026-09-26 · examples · cocoa-viewer · live dota2 view (out-of-process viewer)

Turns `compiler/examples/cocoa-viewer/` from a static-frame demo into a live view of the pocket-MOBA sim. Adds `dota2_live.cxc` (sim body copied from `cards/dota2/cxc/cards.cxc` with byte-parity preserved on seed-42) plus a `Renderer` sub-atom that walks lane/player state each phase boundary and issues plugin draw calls. The sim runs headless, writing one PNG per phase under `/tmp/dota2-frames/f{0..N}.png` via `cv_open_offscreen` + `cv_snapshot`. Playback is out-of-process: a shell wrapper `watch` chooses between `viewerd` (a standalone Cocoa app with auto-advance / step / play-pause), Preview.app (sidebar + arrow keys), and ffmpeg → QuickTime (looping auto-play). `libcocoa_viewer.m` is snapshot-only — `cv_clear` / `cv_rect` / `cv_line` / `cv_text` / `cv_snapshot`; the sim never opens a window itself.

Why the split: the in-process auto/step attempt (earlier iteration of this entry) is not viable with Corvid's current runtime. `dispatch_main()` + `CXC_SINGLE_MAIN_QUEUE=1` route atom sync sends inline on libdispatch worker threads and never install a CFRunLoop on the main thread, so every hop back to main from an atom fails: inline AppKit trips `NSInternalInconsistencyException`, `dispatch_sync(main)` traps because the caller chain transitively owns the main queue, `dispatch_async + semaphore` deadlocks, and `performSelectorOnMainThread:` raises `NSDestinationInvalidException`. A separate process (viewerd) has its own NSApplication and CFRunLoop, sidestepping the whole class of failures. The retired symbols were `cv_open` / `cv_present` / `cv_next_key` / `cv_run` / `cv_should_close`.

Known gap: the plugin FFI still requires build.sh's `-Wno-implicit-function-declaration` recovery (compiler doesn't emit externs for user plugin symbols). Chained `self._helper` observation refs also currently trip the state-snapshot lowering, so `LaneSide.unit_line` is kept as one flat expression rather than composed from `_u0` / `_u1` / ... helpers. Second-opinion review on the auto-mode design: Opus 5.5 subagent (2026-09-26); it argued for skipping `viewerd` entirely and using ffmpeg → QuickTime — both paths are shipped (`./watch` for auto/step in one process, `./watch --mp4` for the ffmpeg branch).

## 2026-09-25 · examples · cocoa-viewer + cxc-cleanup-audit skill

Adds `compiler/examples/cocoa-viewer/`: a Corvid plugin that FFIs to a Cocoa drawing wrapper (`libcocoa_viewer.m`) and renders a static dota2-simulator frame in a native macOS NSWindow. Uses the v1.0 `def plugin: ... foreign "cocoa_viewer":` shape. Working proof of concept; visual verification of the window is a manual step (`./build.sh && /tmp/cocoa-viewer-out/dota2_frame`). Documents a compiler gap: user-plugin FFI symbols don't currently get extern declarations in the emit, so `build.sh` re-links the .m with `-Wno-implicit-function-declaration`. Wiring to a live sim is left as a next step.

Adds `.claude/skills/cxc-cleanup-audit/`: a portable Claude Code skill packaging the 25 anti-patterns from `docs/writing-corvid/` as a mechanical checklist for auditing existing `.cxc` files. Sits next to `cxc-authoring`.

## 2026-09-25 · docs · public authoring guide

Adds `docs/writing-corvid/`: an opinionated authoring guide for humans and AI agents writing Corvid source. Six files: primer, language tour, design patterns, common mistakes, worked example, and index. Compile-verified examples (`counter` and `task-queue.cxc`). Also adds `.claude/skills/cxc-authoring/` as a Claude Code skill wrapper for agent-driven authoring, and `AGENTS.md` at repo root for codex/opencode discovery. README.md gains a "Writing Corvid" section linking the guide.

## 2026-09-14 · v0.1.0-alpha · initial public release

First public tag. Bootstrap compiler passes 1053 tests / 2927 assertions on macOS.

**Language surface (v1.0 spec §1.11).** `def <Name>:` with body-shape inference (structural / agent / tagged-variant / protocol per F69). Transitions (`<phase> <: <msg>(...) -> <phase>` + `then:`) per F70. Observables (`:-`). Invariants and extensions. Container atoms (`List of T`, `Map[K, V]`, `Set of T`). Multi-arm dispatch via `||: <guard> : <expr>`. Typed raise + `or T(field: let x): <body>` recovery.

**Prelude capabilities (macOS target).** FileSystem, Http, Json, Subprocess, EnvVars, EnvMut, Random, Time, Hash, Console, Args, Clock. All fallible methods raise typed errors (`IOError`, `HttpError`, `ShellError`, `JsonError`, `EnvError`, `RandomError`). `_or` variants stay total.

**Typed capability returns.** `Http.get` / `Http.post` return `HttpResponse` (`.status`, `.body`). `Subprocess.run_full` returns `ProcResult` (`.stdout`, `.stderr`, `.exit_code`). No more raw JSON envelopes at the seam.

**Nil-punning.** `??` on a value that can't be nil at the C level is identity — the fallback is dead code. Composes cleanly on FFI returns.

**Runtime.** Objective-C via clang, ARC. Foundation, NSURLSession, NSFileManager, CommonCrypto.

**Known limitations.** `EnvSpec.merge` deferred (map-in-variant not landed). `File` atom deferred (async I/O). `Json.array_at` deferred (dynamic-length iteration). Cross-platform target is macOS-only at alpha; Linux/OpenBSD/Windows enforcement paths are spec'd but not shipped.
