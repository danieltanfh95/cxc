# cxc compiler

Clojure implementation of the cxc compiler. Emits ObjC source; clang produces the binary.

**Status (2026-08-28).** Bootstrap compiler under active development on `main`. Pipeline `lex → parse → resolve → lower → emit → build` is wired end-to-end; **929 tests / 2486 assertions passing**. Coverage spans the v1.0 atom-substrate surface: `def <Name>:` with body-shape inference (F69), transitions + `then:` (F70), observables (`:<`), invariants, extensions, container atoms, streams, ObjC-inheriting runtime, multi-file build. F51 module-scope terminal shape (strict-hinted `resolve-decl-lookup` + two-primitive lookup split + `:cxc-f51-unresolved-name` diagnostic) landed 2026-08-28. Feature-level tracking in [`design/spec-drift-audit.md`](design/spec-drift-audit.md) (per-item landing status) and [`design/plans/`](design/plans/) (per-feature landing plans).

## Getting started

**Prerequisites.** macOS with Xcode command-line tools (`clang`, `xcrun`). A JDK 11+. Clojure 1.12 via `clj` on `PATH` (`brew install clojure/tools/clojure`).

**Compile + run a program.**

```
cd compiler
clj -M -m cxc.core examples/hello.cxc /tmp
/tmp/hello        # → hello, world
```

The CLI compiles `examples/hello.cxc` → a native binary at `/tmp/hello`. Point at any of the 82 files in [`examples/`](examples/) — every one runs end-to-end under the test suite ([`examples/README.md`](examples/README.md) has a per-file capsule + expected stdout).

**Recommended reading order for first-time users.**

1. [`examples/hello.cxc`](examples/hello.cxc) — one-line `print`. The minimum surface.
2. [`examples/atom-simple.cxc`](examples/atom-simple.cxc) — `def Counter:` with a data field. Introduces the atom (spec §1.11).
3. [`examples/atom-methods.cxc`](examples/atom-methods.cxc) — methods on atoms, ObjC selector emission.
4. [`examples/observable-simple.cxc`](examples/observable-simple.cxc) — `:<` observable syntax.
5. [`examples/invariant-pass.cxc`](examples/invariant-pass.cxc) — `|:` invariant contract.
6. [`examples/stream-simple.cxc`](examples/stream-simple.cxc) — `:>` fanout + subscribe.
7. [`examples/multi-file-demo/`](examples/multi-file-demo/) — multi-module program layout.

Language spec: [`../design/spec/spec-cxc-v1.0.md`](../design/spec/spec-cxc-v1.0.md). The compiler emits ObjC — read [`compiler/.cxc-cache/last/hello.m`](compiler/.cxc-cache/) after your first run to see the lowering.

**Language mental model, in one paragraph.** cxc programs are graphs of *atoms* (`def <Name>:`). An atom is a class-with-a-state-machine: fields + methods + phases + transitions. Transitions (`<phase> <: <msg>(...) -> <phase>`) carry `then:` clauses that update fields, `:>` clauses that emit events, and `<:` clauses that subscribe to other atoms' events. Records (`def R: x: Int` — inferred kind: value type) and enums (`case a; case b` — inferred kind: tagged variant) fall out of the same `def` opener via structural body inference. `main` is a distinguished atom that boots the program. Everything else — streams, invariants, observables, capabilities — hangs off the atom substrate. See [`../design/positioning/overview.md`](../design/positioning/overview.md) for the design bet.

**What works today.** Every example in [`examples/`](examples/) compiles + runs + matches expected stdout under [`test/cxc/build_test.clj`](test/cxc/build_test.clj). Coverage: single-file + multi-file builds, records/enums/atoms, transitions, streams, invariants, ctor-params, use-caps, list/map/set containers, prelude (Duration, Instant, Clock, Random, TestKeyboardInput, Console, SystemPanic), F16 tagged-variant enums, F37 canonical bytes, F39 nullability, F49 pre-store invariant checks, F51 module scoping (strict-hinted resolution, 2026-08-28).

**What doesn't yet.** F9 protocol dispatch beyond L1-L6; F15 L4+ structural conformance beyond monomorphic; F32 concurrent-queue optionality; F42 collection literals; F53 L4 LSP. Full status: [`design/loop-status-2026-08-26.md`](design/loop-status-2026-08-26.md).

## Layout

    compiler/
    ├── deps.edn                   Clojure deps + aliases
    ├── src/cxc/
    │   ├── core.clj               CLI entry point + top-level pipeline
    │   ├── lex.clj                tokenizer
    │   ├── parse.clj              hand-rolled recursive-descent parser
    │   ├── resolve.clj            symbol table + module provenance
    │   ├── lower.clj              cxc AST → ObjC AST + all semantic checks
    │   ├── emit.clj               ObjC AST → .m source
    │   ├── build.clj              multi-file build + invoke clang
    │   └── prelude.clj            std.* prelude sources + name mangling
    └── test/cxc/                  test suites per module (build/emit/lex/lower/parse/resolve)

## Design references

- Language spec: `../design/spec/spec-cxc-v1.0.md`
- Design log: `../design/working-notes.md` (Phase 6 session note has compiler decisions)
- Onboarding: `../design/PATTERNS.md`

## Dev workflow

Use replsh for REPL-driven development. Per-project replsh config is git-ignored
(personal-machine artifact). Create `../.replsh/config.edn` locally with:

    {:sessions
     {"cxc" {:toolchain "clojure.deps"
             :cwd       "compiler"
             :init      "(require '[cxc.core :as core])"}}}

Then:

    replsh launch --name cxc
    replsh eval --name cxc '(require (quote [cxc.core :as core]) :reload) (core/-main)'
    replsh eval --name cxc '(require (quote [cxc.core-test]) :reload) (clojure.test/run-tests (quote cxc.core-test))'

Run tests directly:

    clj -M:test

Start an nREPL server manually:

    clj -M:nrepl -m nrepl.cmdline

## Runtime dependencies

At link time, cxc binaries depend on:
- **libobjc2** — Objective-C runtime (dispatch, ARC, method tables)
- **libdispatch** — concurrency (work-stealing thread pool)
- **libuv** — async I/O event loop
- **cxc runtime lib** — ships inline as generated ObjC/C in the emitted source (`emit.clj` `cxc-runtime-helpers`); no separate library artifact today. Extraction to a linkable library is a future landing.

## Emission target

ObjC source, not LLVM IR. Rationale: cxc's design is isomorphic to ObjC runtime
semantics (nil-punning = ObjC nil-messaging, message dispatch = `objc_msgSend`,
ARC = ARC, closures = blocks). Emitting ObjC lets clang do all lowering /
optimization / platform work; saves ~40–80k LOC of LLVM backend and ~12–18
months of engineering. See design/working-notes.md Phase 6 session note for
the full argument.
