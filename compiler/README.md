# cxc compiler

Clojure implementation of the cxc compiler. Emits ObjC source; clang produces the binary.

**Status (2026-08-20).** Bootstrap compiler under active development on `main`. Pipeline `lex → parse → resolve → lower → emit → build` is wired end-to-end; 408 tests / 1075 assertions passing. Coverage spans the v1.0 atom-substrate surface: `def <Name>:` with body-shape inference (F69), transitions + `then:` (F70), observables (`:<`), invariants, extensions, container atoms, streams, ObjC-inheriting runtime, multi-file build. Feature-level tracking in `design/spec-drift-audit.md` (per-item landing status) and `design/plans/` (per-feature landing plans).

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
