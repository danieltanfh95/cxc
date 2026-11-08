# Hot reload — design plan

## Status

Spec'd deferred at v1.0. Flagged because the atom-lifecycle model
makes hot reload more natural than in most ObjC codebases: method
bodies recompile without changing the atom's handle layout, so a
swap can land without invalidating existing instances.

## Scope

**In scope (slice 1):**
- Replace an observation body (`name(args) :- expr`).
- Replace a transition arm body (`then:` statements).
- Replace an invariant body (`|:` proposition).

**Out of scope (deferred to later slices):**
- Add / remove a field (would change `_State` struct layout).
- Add / remove a phase (would change the dispatch table).
- Add / remove a plugin's foreign entry (would change manifest).
- Redefine a record (value types, no handle indirection).

The in-scope set covers "edit a method body during an interactive
session and see the change without restarting." The out-of-scope
set requires process restart — layout-changing edits are
semantically a different program.

## Why atoms make this cheaper than typical ObjC hot reload

- **No stored method ptrs.** cxc never stashes an `IMP` across a
  dispatch; every message send goes through `objc_msgSend`. Method
  replacement via `method_setImplementation` takes effect on the
  next send.
- **State lives in `_state` ivars.** A method-body swap leaves the
  ivar layout untouched, so existing instances keep their state
  and transition bookkeeping valid.
- **Transitions are per-arm dispatches.** The dispatcher
  method (`-selector:(id)arg0 ...`) is table-driven; arm bodies
  compile into guarded branches inside it. Replacing a single arm
  means recompiling the whole dispatcher method, not the class.

## Pipeline sketch

```
editor save → cxc daemon (watch mode)
                 │
                 ├─▶ incremental parse           (reuses parse cache)
                 ├─▶ incremental lower           (reuses lower cache)
                 ├─▶ emit changed-method chunk   (one-method .m)
                 ├─▶ clang -dynamiclib → delta.dylib
                 └─▶ send IPC message to running binary:
                        "load delta.dylib, swap selectors S1…Sn"
                              │
                              └─▶ dlopen(delta.dylib)
                                   extract function ptrs for S1…Sn
                                   method_setImplementation(cls, sel, imp)
```

### IPC channel

Running binary listens on a Unix domain socket at
`~/.cxc/hotreload/<pid>.sock`. The daemon connects on each delta,
writes a short framed message (`uint32 len` + msgpack / JSON
payload), reads a status response, disconnects. Zero shared state
with the program's normal socket traffic.

### Delta dylib layout

One dylib per hot reload, named `delta-<hash>.dylib`. Exports one C
symbol per replaced method with a stable naming scheme:

```
cxc_hotreload_<ClassName>_<selector-with-underscores>
```

The binary resolves each symbol via `dlsym`, wraps in `IMP` (the
ObjC function-ptr type), and swaps via
`method_setImplementation(class_getInstanceMethod(cls, sel), imp)`.

Old implementations are retained for the process lifetime (the
symbol is in a dylib that the process still holds open). On
subsequent reloads of the same method, a new dylib is loaded; the
old one's symbol is replaced but its dylib stays mapped.

### Binary-side opt-in

Hot reload is opt-in via `def plugin: runtime_shape:` carrying a
new `RuntimeShape.hot_reload_dev` case, OR via an env var
`CXC_HOT_RELOAD=1` for ad-hoc sessions. Production builds never
install the IPC socket.

## Slice 1 implementation plan

### 1. Compiler-side daemon mode

`cxc.daemon` already exists for compile-cache reuse. Extend with:
- File watcher on the cxc source tree (one-level dir watch, no
  recursive walk, cheap).
- Incremental compile-trigger on `.cxc` change.
- Diff AST against the last successful compile; identify the
  changed method set.

### 2. Delta emission

New emit target: `emit-delta-m` that produces a one-file .m with:
- `#include` of the main program's headers.
- One free function per changed method with the
  `cxc_hotreload_<cls>_<sel>` naming scheme.
- Each function's body mirrors the normal method-impl emit but is
  parameterized by `self` instead of being a `- (void)...` method.

### 3. Runtime stub

Emit into every program (gated on `runtime_shape`):
- Unix socket listener on first `-> running` arm fire.
- Message handler that dlopens dylibs and swaps methods.
- `dlerror()` checks with structured failure reporting over the
  socket so the daemon can show the user why a swap failed.

### 4. Daemon-side orchestrator

New ns `cxc.hot-reload`:
- Spawns clang with the delta .m.
- Opens the binary's socket.
- Sends `{:op :swap :dylib "delta-abc.dylib" :selectors [...]}`.
- Logs the status back to the editor.

### 5. Test harness

End-to-end:
- Spawn a long-lived cxc binary with `CXC_HOT_RELOAD=1`.
- Modify an observation body on disk.
- Trigger reload.
- Send a probe message, verify the new body ran.

## Slice 2+ (deferred)

- **Phase addition / removal** via `objc_registerClassPair` on a
  class-paired trampoline that proxies the extended dispatch.
- **Field addition** via a parallel "shadow state" ivar that the
  compiler consults when a field is absent in the base layout.
  Complicated; likely not worth v1.0.
- **Record redefinition** via version-tagged value types + at-use
  migration. Scope creep for v1.0.

## Open questions

- **libobjc2 vs. system libobjc.** Apple's libobjc on
  macOS/iOS supports `method_setImplementation` and
  `class_addMethod` natively. libobjc2 is only needed if we target
  non-Apple platforms (Linux via GNUstep). v1.0 targets Apple
  only, so system libobjc is enough. The design keeps "libobjc2"
  in the name because the spec's wording did; the mechanism is
  identical on both.
- **Debugger integration.** LLDB can lose track of symbols in a
  swapped method. `dsymutil` on the delta dylib solves breakpoints
  in the new code; the old method's breakpoints become stale.
  Open: whether the daemon should emit per-delta dSYMs.
- **Thread safety.** `method_setImplementation` is atomic from the
  ObjC runtime's view, but an in-flight message dispatch may read
  the IMP via `objc_msgSend` at an unspecified instant. The window
  between "daemon sends swap request" and "running binary finishes
  swap" is unprotected. Mitigation: pause the dispatch queue
  during a swap (easy for main-queue atoms, harder for
  multi-queue setups).

## Non-goals (hard)

- Runtime creation of new classes with novel field layouts
  (§4.18.3 compile-time-fixed layout invariant stays).
- Runtime creation of method implementations from source strings
  (no eval).
- Live-coding "paused REPL that lets you redefine anything" — out
  of scope for the lifecycle model; the slice-1 contract is
  method-body swaps only.

## Success criterion

Edit a transition arm's `then:` body in an atom participating in
a running cxc binary. Within 500 ms of save, subsequent arm fires
execute the new body. Existing instances keep their state, phase,
and in-flight message queue. No restart, no state loss.
