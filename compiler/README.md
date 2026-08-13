# cxc compiler

Clojure implementation of the cxc compiler. Emits ObjC source; clang produces the binary.

**Status.** Walking skeleton. No pipeline stages implemented yet.

## Layout

    compiler/
    ├── deps.edn                   Clojure deps + aliases
    ├── src/cxc/
    │   ├── core.clj               entry point (see docstring for pipeline plan)
    │   ├── lex.clj                (todo — tokenizer)
    │   ├── parse.clj              (todo — parser, Instaparse-based)
    │   ├── infer.clj              (todo — local inference)
    │   ├── check.clj              (todo — contract discharge)
    │   ├── lower.clj              (todo — cxc AST → ObjC AST)
    │   ├── emit.clj               (todo — ObjC AST → .m/.h source)
    │   └── build.clj              (todo — invoke clang)
    └── test/cxc/
        └── core_test.clj

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
- **cxc runtime lib** — scheduler, mailboxes, contract-check helpers (in `../runtime/`, not yet created)

## Emission target

ObjC source, not LLVM IR. Rationale: cxc's design is isomorphic to ObjC runtime
semantics (nil-punning = ObjC nil-messaging, message dispatch = `objc_msgSend`,
ARC = ARC, closures = blocks). Emitting ObjC lets clang do all lowering /
optimization / platform work; saves ~40–80k LOC of LLVM backend and ~12–18
months of engineering. See design/working-notes.md Phase 6 session note for
the full argument.
