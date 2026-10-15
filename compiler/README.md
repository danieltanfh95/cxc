# cxc compiler

Clojure implementation of the cxc compiler. Emits ObjC source, and clang produces the binary.

**Status.** Bootstrap compiler on `main`, tagged v0.1.0-alpha. The cxc tag is independent of the Corvid language version, and cxc targets Corvid v1.0 as defined in [docs/spec/spec-cxc-v1.0.md](../docs/spec/spec-cxc-v1.0.md). Pipeline `lex → parse → resolve → lower → emit → build` is wired end-to-end, and coverage spans the v1.0 atom-substrate surface. `def <Name>:` with body-shape inference (F69), transitions + `then:` (F70), observables (`:-`), invariants, extensions, container atoms, streams, ObjC-inheriting runtime, multi-file build. Twelve prelude capabilities (FileSystem, Http, Json, Subprocess, EnvVars, EnvMut, Args, Random, Time, Hash, Console, Clock) with typed raise on fallible methods.

**Not distributable.** This is a bootstrap. There is no `cxc` binary, you invoke via `clj`, JVM starts each time. macOS only. No LSP, no IDE plugin, no package manager. Everything under [`examples/`](examples/) works, anything past it may hit `fail-nyi`. See [What you don't get](#what-you-dont-get) below.

Feature-level landing status lives in [CHANGELOG.md](../CHANGELOG.md) and the git log.

## Shape

Clojure sources under `src/cxc/` plus a `lower/` subdirectory. Deps: Clojure 1.12 + instaparse 1.5.0. No native components, so it runs anywhere the JVM does.

Biggest files (run `wc -l src/cxc/*.clj` for the full list):

| File | LOC | Role |
|---|---:|---|
| [`src/cxc/lower.clj`](src/cxc/lower.clj) | 8,862 | The big one. cxc AST → ObjC AST + every semantic check. |
| [`src/cxc/parse.clj`](src/cxc/parse.clj) | 3,933 | Hand-rolled recursive-descent. `def` opener → body-shape inference (F69). |
| [`src/cxc/emit.clj`](src/cxc/emit.clj) | 2,386 | ObjC AST → `.m` source string. Ships inline runtime helpers per-program. |
| [`src/cxc/build.clj`](src/cxc/build.clj) | 1,017 | Multi-file build, prelude enrichment, invoke clang, manifest emission. |
| [`src/cxc/plugin_manifest.clj`](src/cxc/plugin_manifest.clj) | 944 | Third-party plugin manifest parse + validation (F76). |
| [`src/cxc/resolve.clj`](src/cxc/resolve.clj) | 803 | Symbol table + module attribution + reference stamping (F51). |
| [`src/cxc/lex.clj`](src/cxc/lex.clj) | 755 | Layout-aware tokenizer. Bracket-suppressed newlines. |
| [`src/cxc/type_kw.clj`](src/cxc/type_kw.clj) | 564 | Type-kw vocabulary + Optional-boxing helpers. |
| [`src/cxc/named_tuple.clj`](src/cxc/named_tuple.clj) | 488 | Structural named-tuple conformance (F15). |
| [`src/cxc/prelude.clj`](src/cxc/prelude.clj) | 330 | Prelude module registry + assembly. Loads `.cxc` from [`resources/prelude/`](resources/prelude/). |

Balance: `project.clj`, `manifest.clj`, `section.clj`, `env.clj`, `incremental.clj`, `core.clj`, `mangle.clj`, `diagnostics.clj`, and the small `lower/` split-outs.

Tests live under [`test/cxc/`](test/cxc/). [`build_test.clj`](test/cxc/build_test.clj) is the end-to-end harness that compiles every file under [`examples/`](examples/) and asserts on the produced binary's stdout.

## Getting started

**Prereqs.** macOS with Xcode command-line tools (`clang`, `xcrun`). JDK 11+. Clojure 1.12 on `PATH` (`brew install clojure/tools/clojure`).

**Compile + run a single file.**

```bash
cd compiler
clj -M -m cxc.core examples/hello.cxc /tmp
/tmp/hello        # → hello, world
```

That's [`core/-main`](src/cxc/core.clj#L110) → `build/compile-source-to-file` → parse → resolve → lower → emit → clang → binary at `<out-dir>/<name>`. Point at any file in [`examples/`](examples/). Every one runs end-to-end under the test suite ([`examples/README.md`](examples/README.md) has a per-file capsule + expected stdout).

**Compile a multi-file project.** No CLI wrapper yet, go through Clojure:

```text
clj -M -e "(require '[cxc.build :as b]) (b/compile-directory-to-file \"examples/multi-file-demo\" \"demo\" \"/tmp\" {})"
```

Layout expected: one `.cxc` per module, no header required (module identity is the file path after F73 retired the `module <name>` header), imports via `from <name> import <Type>`. The working demo is [`examples/multi-file-demo/`](examples/multi-file-demo/), two files, `counter.cxc` + `main.cxc`.

**Target flag.** `--target=macos` is the default and the only working value today. Additional targets would drop into [`resources/prelude/target/<name>/`](resources/prelude/target/) but no other targets exist.

**Read the emitted ObjC.** Every compile also drops the `.m` at `compiler/.cxc-cache/last/<name>.m`. That's how you see what cxc actually produced. Gitignored, safe to delete.

**Dev loop.**

```text
clj -M:test
clj -M:nrepl -m nrepl.cmdline
```

Or via replsh with a personal `../.replsh/config.edn` (git-ignored):

```json
{:sessions
 {"cxc" {:toolchain "clojure.deps"
         :cwd       "compiler"
         :init      "(require '[cxc.core :as core])"}}}
```

Then:

```text
replsh launch --name cxc
replsh eval --name cxc '(require (quote [cxc.core :as core]) :reload) (core/-main)'
```

## Language mental model

cxc programs are graphs of *atoms* (`def <Name>:`). An atom is a class-with-a-state-machine: fields + methods + phases + transitions. Transitions (`<phase> <: <msg>(...) -> <phase>`) carry `then:` clauses that update fields, `:>` clauses that emit events, and `<:` clauses that subscribe to other atoms' events. Records (`def R: x: Int`, inferred kind: value type) and enums (`case a; case b`, inferred kind: tagged variant) fall out of the same `def` opener via structural body inference. `main` is a distinguished atom that boots the program. Everything else, streams, invariants, observables, capabilities, hangs off the atom substrate. See [`../docs/positioning/overview.md`](../docs/positioning/overview.md) for the design bet and [`../docs/spec/spec-cxc-v1.0.md`](../docs/spec/spec-cxc-v1.0.md) §1.11 for the atom-substrate shape the compiler implements. (Spec §3-§9 still describe an earlier `class`/`record`/`enum`/`protocol` shape, editorial rewrite pending.)

## Reading order for first-time users

1. [`examples/hello.cxc`](examples/hello.cxc), one-line `print`. The minimum surface.
2. [`examples/atom-simple.cxc`](examples/atom-simple.cxc), `def Counter:` with a data field. Introduces the atom (spec §1.11).
3. [`examples/atom-methods.cxc`](examples/atom-methods.cxc), methods on atoms, ObjC selector emission.
4. [`examples/observable-simple.cxc`](examples/observable-simple.cxc), `:-` observable syntax.
5. [`examples/invariant-pass.cxc`](examples/invariant-pass.cxc), `|:` invariant contract.
6. [`examples/stream-simple.cxc`](examples/stream-simple.cxc), `:>` fanout + subscribe.
7. [`examples/multi-file-demo/`](examples/multi-file-demo/), multi-module program layout.

## What the pipeline covers today

Every example in [`examples/`](examples/) compiles + runs + matches expected stdout under [`test/cxc/build_test.clj`](test/cxc/build_test.clj). Landed coverage includes: single-file + multi-file builds, records/enums/atoms, transitions, streams, invariants, ctor-params, use-caps, list/map/set containers, twelve prelude capabilities with typed raise (see [`resources/prelude/`](resources/prelude/)), F16 tagged-variant enums, F37 canonical bytes, F39 nullability, F49 pre-store invariant checks, F51 module scoping, F52 module let-bindings, F56 emit-boundary C-id mangling, F76 third-party plugin manifests.

Beyond those examples, you're gambling on `fail-nyi`. cxc is honest, anything it can't lower raises `:not-yet-implemented` with a specific message.

Notable gaps:

- F9 protocol dispatch beyond L6.
- F15 L4+ structural conformance beyond monomorphic.
- F32 L4.e.γ concurrent-queue optionality (single-main-queue is the shipping mode).
- F42 collection literals.
- F53 L4 LSP.

## What you don't get

- **No `cxc` binary.** Invoke via `clj`, and the JVM starts each time.
- **No LSP, no IDE plugin.** Errors show up at clang or `fail-nyi`. Autocomplete, jump-to-def, rename: all absent.
- **macOS only.** `:target :macos` is the only working prelude target.
- **macOS-only capabilities.** The twelve prelude capabilities ship for macOS/Foundation only. Linux (seccomp-bpf) and OpenBSD (pledge) enforcement paths are spec'd but not implemented. Windows is manifest-only.
- **No debugger, no source maps.** Failures come out of clang or the emitted ObjC. Cache the `.m` at `compiler/.cxc-cache/last/` and read it to see what happened.
- **No package system.** No registry, no dep resolver, no versioning story.
- **Spec drift.** Language spec §3-§9 still describes an earlier `class`/`record`/`enum`/`protocol` shape, not the atom-substrate `def` opener cxc implements, so the spec you'd read to learn the language currently doesn't match what cxc accepts. Editorial rewrite pending.

## Runtime dependencies

At link time, cxc binaries depend on:
- **libobjc2**, Objective-C runtime (dispatch, ARC, method tables).
- **libdispatch**, concurrency (work-stealing thread pool).
- **libuv**, async I/O event loop.
- **cxc runtime lib**, ships inline as generated ObjC/C in the emitted source ([`emit.clj`](src/cxc/emit.clj) `cxc-runtime-helpers`), with no separate library artifact today. Extraction to a linkable library is a future landing.

## Emission target

ObjC source, not LLVM IR. Rationale: cxc's design is isomorphic to ObjC runtime semantics (nil-punning = ObjC nil-messaging, message dispatch = `objc_msgSend`, ARC = ARC, closures = blocks). Emitting ObjC lets clang do all lowering / optimization / platform work, which saves ~40-80k LOC of LLVM backend and ~12-18 months of engineering.

## Design references

- Language spec: [`../docs/spec/spec-cxc-v1.0.md`](../docs/spec/spec-cxc-v1.0.md)
- Onboarding: [`../docs/PATTERNS.md`](../docs/PATTERNS.md)
- Positioning: [`../docs/positioning/overview.md`](../docs/positioning/overview.md)
