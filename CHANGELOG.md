# Changelog

Format: date · tag · summary.

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
