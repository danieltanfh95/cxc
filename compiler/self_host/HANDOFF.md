# Self-host port handoff for the cloud session

Last updated during a local Claude Code session, 2026-10-04, by the opus 5.5 orchestrator pattern described below.

If you are a Claude Code cloud session picking this up, read this file first, then `README.md` for chapter-by-chapter idiom learnings, then your current chapter directive.

## Mission

Byte-for-byte parity on `cards.cxc` → `cards.m`. See `README.md` for the full statement. Not "equivalent", not "close", `diff -q` returns clean.

- Parity target: `compiler/self_host/parity_target/cards.cxc` in this repo (960 LOC, previously at `/Users/danieltan/Projects/g-daniel/cards/dota2/cxc/cards.cxc` locally, copied in at the local-to-cloud handoff).
- Baseline sha of `cards.m` as of 2026-10-04 is `738b928dbcefbba251f5887133e6eef65b369c463ebecbe516a7dbec2b0f1707`. The baseline shifted once from `d28f973d44...` at chapter 16's record-field retain fix; see `design/ten-char-ident-segfault.md`.
- Baseline-identity check command: `pkill -f cxc.daemon; CXC_NO_DAEMON=1 compiler/bin/cxc compile <path>/cards.cxc <tmp-out>; shasum -a 256 <tmp-out>/cards.m`.

## Orchestrator pattern

The local session ran the port as recurring chapters driven by a single opus 5.5 orchestrator subagent that spawned further subagents freely. Each chapter was one wake of the orchestrator with a tight directive, roughly 60 to 90 minutes of elapsed time per chapter.

To continue the pattern on the cloud:

1. Spawn an opus 5.5 (claude-opus-5-5) agent with the task description "Clojure to cxc self-host port orchestrator".
2. Give it the chapter directive naming the chapter number, the previous chapter's commit sha, and the if-resumed line from `README.md`'s last chapter section.
3. Grant it authority to spawn further subagents.
4. Instruct it to report back as text (not file writes), matching the chapter-report shape in `README.md`.
5. On completion, verify the commit, confirm `cards.m` sha matches the expected baseline, resume with the next chapter.

## Rules the orchestrator must follow

These are standing rules captured in the user's global memory at `~/.claude/projects/-Users-danieltan-Projects-g-daniel-language-corvid/memory/`.

- Specific-path staging on every commit. Never `git add -A`.
- English register. Commas and full stops only, no semicolons, colons, em-dashes, en-dashes.
- No destructive git operations without explicit confirmation.
- Never skip hooks or bypass signing.
- No shape variables, no annotations, no user-defined operators, no imperative container mutation, no sentinel returns. Caps raise typed errors.
- Kill the cxc daemon or set `CXC_NO_DAEMON=1` before any parity check after a compiler-source or prelude edit. The daemon caches loaded source and will false-positive otherwise.
- Clamp substring off and len with `.max(0).min(input.length())` before every call. Eager let eval fires substring on miss arms and negative length wraps NSUInteger.
- Terminal phase must be named `end`. Non-end terminal phase names leave the atom idling in the dispatch runloop.
- Field, observation, and let-local names must not collide with C keywords. The emit layer does not escape them (unlike param names fixed in `b006d47`).
- Enum case names must not collide with cxc keywords.
- Direct self-reference enum payloads are rejected at parse per `design/self-reference-enum-payload.md`. Three workarounds documented.
- Container-returned record-field read for records with reference fields uses the chapter-16 shadow-struct fix. See `design/ten-char-ident-segfault.md`.
- Observation reference ordering is lexical. If `a :- b` and both are observations, declare b first.

## Hard blockers at chapter 21 handoff

- Local Int bindings widen to `_Cxc_Optional_Int` in let scope. Workaround `let x: Int!` on the right-hand side.
- Lambda capture of local Int widens the same way. Workaround `||:` chain or hoist to atom field.
- `||:` chains do not continue across newlines. One physical line.
- `module` is reserved and cannot be a let name.
- Depth-N nested named-tuple in parse-layer bootstrap: handled, chapter 20.
- Bridge depth-N nested named-tuple: in flight chapter 21.
- Container inner types in TNT bodies: deferred, chapter 22+ queue.
- Float literal lex in shared lex table: deferred, chapter 22+ queue.
- Escape sequences in string / char lex in shared lex table: deferred, chapter 22+ queue.
- Sub-case 1 heap-boxing (self-reference enum payload): deferred per `design/self-reference-enum-payload.md`. Retirement condition is a future heap-boxing primitive, not currently in scope.

## Chapter 22 directive (if chapter 21 lands as expected)

Expand on the chapter 21 report's if-resumed line. Priorities in order:

1. **Container inner types in TNT bodies.** Parse layer recurses into container parsing from within named-tuple pair parsing. Bridge handles `TO:List` / `TO:Map` / `TO:Set` in TNT bodies and emits F15 `Of<arity>_<outer>_<inner>` mangling. If chapter 21 deferred this, chapter 22 picks it up.
2. **Float literal lex port** into the shared lex table (both standalone slice 33 and parse-layer bootstrap). Chapter 11's slice 29 scan-decimal-number is already parity-green standalone, lifting it into the shared lex closes the integer-vs-float gap in parse-layer field defaults.
3. **Escape sequences in string / char lex port** into the shared lex table. Chapter 10 slice 30 scan-string-with-escapes is already parity-green standalone, same lift pattern.

## Harness invocation

From repo root:

```sh
pkill -f cxc.daemon >/dev/null 2>&1 || true
bash compiler/self_host/harness/<harness-name>.sh
```

Each harness prints its progress and ends with `[harness] PARITY OK` or an error. All harnesses pre-pkill the daemon as of chapter 4.

To run every harness:

```sh
for h in compiler/self_host/harness/*.sh; do
  bash "$h" || echo "FAIL $h"
done
```

Count of parity-green harnesses at handoff: 32. The 33rd src file is `ast_shape_demo.cxc` (non-parity, design artifact).

## Oracle paths

Oracle Clojure files live under `compiler/self_host/oracle/`. They each `clj -M -e` the live `cxc.*` namespaces to drive the reference implementation and emit parity output.

## Commit cadence from this session

20 chapters, 37 commits, 7 live-compiler landings. All on `main` ahead of origin.

- `b6f46c6` chapter 1 scaffold + first two parity slices
- `3489ea3` `fs.read_lines`
- `9cdec7c` daemon stale-reload detection
- `d8224b7` enum-payload container-scan + conditional forward-class hoist
- `d41d047` `String.to_double_or` + `Int.to_char_string`
- `2311375` NUL-safety follow-up on `Int.to_char_string`
- `84f61da` reject direct self-reference enum payload at parse
- `dbba76c` `String.compare_to` strcmp-shape byte order
- `d074226` `String.code_at`
- `266df8c` list-at NSString retain fix for records with ref fields
- Remaining commits are self-host-only, see `README.md` chapter sections.

## Known failures to ignore in the compiler test suite

Baseline at commit `b006d47` and later: 38 failures / 7 errors / 1094 tests / 3229 assertions. These are pre-existing fixture expectation mismatches plus plugin-cache writes blocked by sandbox. No regression during the port.

## Points of care on cloud

- `/Users/danieltan/` paths will not exist on the cloud sandbox. Any oracle path assuming the local layout needs rewriting. `cards.cxc` is now in-repo at `compiler/self_host/parity_target/cards.cxc`.
- The cxc daemon socket at `/tmp/cxc-daemon.sock` is per-process. Each cloud session starts fresh so stale daemon is not a concern on cold boot but IS a concern after any mid-session compiler edit.
- The user runs with 1-hour prompt cache TTL. The orchestrator stays warm across chapter wakes within a cloud session.
- Push access is on `https://github.com/danieltanfh95/cxc.git`. Cloud session should push each landed chapter so progress survives any session interruption.

## Linux cloud toolchain (chapter 22 addendum, 2026-10-04)

The compiler was macOS-only until chapter 22's cloud session. The toolchain now compiles on Linux via a GNUstep + libobjc2 + libdispatch stack. The `build.clj` patch at `cond-> (into ["clang" "-fobjc-arc"] foundation-args)` auto-detects Linux and swaps `-framework Foundation` for the GNUstep flag set. No-op on macOS.

### Setup recipe (fresh Linux session)

```sh
# apt packages
sudo apt-get install -y \
  openjdk-21-jdk clojure cmake robin-map-dev \
  libxml2-dev libffi-dev libicu-dev libgnutls28-dev \
  libblocksruntime-dev libobjc-13-dev gobjc

# clj shim (upstream download.clojure.org blocked; Maven Central works)
mkdir -p /opt/clj-shim/lib && cd /opt/clj-shim/lib
for p in \
  org/clojure/clojure/1.12.0/clojure-1.12.0.jar \
  org/clojure/spec.alpha/0.5.238/spec.alpha-0.5.238.jar \
  org/clojure/core.specs.alpha/0.4.74/core.specs.alpha-0.4.74.jar \
  org/clojure/data.json/2.5.1/data.json-2.5.1.jar
do
  curl -sS -o "$(basename "$p")" "https://repo1.maven.org/maven2/$p"
done
# then /usr/local/bin/clj is a bash shim with:
#   CP="$LIB_DIR/clojure-1.12.0.jar:...spec.alpha-0.5.238.jar:...core.specs.alpha-0.4.74.jar:...data.json-2.5.1.jar"
#   for p in src resources; do [ -d "$p" ] && CP="${CP}:$(pwd)/$p"; done
#   exec java -cp "$CP" clojure.main "$@"

# libobjc2 (modern Objective-C runtime for Linux, provides ARC)
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/gnustep/libobjc2 /home/user/gnustep/libobjc2
cd /home/user/gnustep/libobjc2 && mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ ..
make -j$(nproc) && sudo make install

# gnustep-make configured for ng (libobjc2) runtime
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/gnustep/tools-make /home/user/gnustep/tools-make
cd /home/user/gnustep/tools-make
LDFLAGS="-L/usr/local/lib" CFLAGS="-I/usr/local/include/GNUstep" \
  ./configure --with-library-combo=ng-gnu-gnu --with-runtime-abi=gnustep-2.1 \
    --with-objc-lib-flag=-lobjc CC=clang CXX=clang++ --prefix=/usr/GNUstep
make -j$(nproc) && sudo make install

# gnustep base against libobjc2
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/gnustep/libs-base /home/user/gnustep/libs-base
cd /home/user/gnustep/libs-base && . /usr/GNUstep/share/GNUstep/Makefiles/GNUstep.sh && \
  CPPFLAGS="-I/usr/local/include/GNUstep" LDFLAGS="-L/usr/local/lib -Wl,-rpath,/usr/local/lib" \
  ./configure --disable-importing-config-file
make -j$(nproc) && sudo -E env PATH=$PATH make install

# libdispatch
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/apple/swift-corelibs-libdispatch /home/user/apple/swift-corelibs-libdispatch
cd /home/user/apple/swift-corelibs-libdispatch && mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DBUILD_TESTING=OFF ..
make -j$(nproc) && sudo make install

# Shim headers for Darwin-only includes the emit layer references
sudo mkdir -p /usr/local/include/cxc-linux-shim/{CommonCrypto,libkern,objc}
# contents: crt_externs.h (_NSGetArgv/_NSGetArgc/_NSGetEnviron via /proc/self/cmdline + environ),
#           CommonCrypto/CommonDigest.h (stub prototypes, no digest impl),
#           libkern/OSByteOrder.h (OSSwapHostToBigInt16/32/64 via glibc endian.h htobe16/32/64),
#           objc/NSObject.h (redirects to GNUstep <Foundation/NSObject.h>).
```

### Slice harness tally on Linux cloud (post-glue, 2026-10-04)

11 PASS, 1 DIFF, 17 CRASH, 3 UNK across 32 harnesses.

- PASS: ascii_predicates, diagnostics_shape, env_predicates, lex_classify_word, lex_match_multi_glyph, lex_scan_char, lex_scan_digits_decimal, mangle_c_safe, resolve_import_alias, resolve_mangled_name_for, resolve_name_to_module.
- CRASH: every slice that touches NSString in atom state or record fields. Non-deterministic SIGSEGV in `objc_msgSend_fpret` reached via `objc_retainAutoreleaseReturnValue` from `cxc_send_async`.

Root cause. `OS_OBJECT_USE_OBJC=1` makes `dispatch_queue_t` an Objective-C object, so cxc's `@property (nonatomic, strong) dispatch_queue_t own_queue` compiles clean. But Linux's swift-corelibs-libdispatch does not register dispatch queue classes with `objc_class_flag_fast_arc`, so libobjc2's `retain()` falls through to `ManualRetainReleaseMessage(obj, retain, ...)` which calls `objc_msgSend` on a dispatch queue whose isa.dtable is not laid out for the retain selector. The msgSend walks garbage memory and segfaults. Core dump at `/tmp/cores/core.lex_scan_float.*` confirms.

Fix paths (none landed in chapter 22):
1. Patch libdispatch to tag dispatch classes for fast ARC.
2. Patch libobjc2 retain fallback to detect dispatch objects and route via `dispatch_retain` / `dispatch_release`.
3. Patch cxc emit (`emit.clj` + `lower.clj`) to use `(nonatomic, assign)` for dispatch properties and emit explicit `dispatch_retain` / `dispatch_release`. Shifts cards.m baseline sha unless gated by Linux-only emit.

Any of the three unblocks the remaining 17 slices on cloud. Primitive-only slices run parity-green today.

If anything in this handoff disagrees with what you observe in the tree, trust the tree and update the handoff.
