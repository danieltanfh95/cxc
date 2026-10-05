# Self-host port handoff for the cloud session

Last updated during a local Claude Code session, 2026-10-04, by the opus 5.5 orchestrator pattern described below.

If you are a Claude Code cloud session picking this up, read this file first, then `README.md` for chapter-by-chapter idiom learnings, then your current chapter directive.

## Mission

Byte-for-byte parity on `cards.cxc` → `cards.m`. See `README.md` for the full statement. Not "equivalent", not "close", `diff -q` returns clean.

- Parity target: `/Users/danieltan/Projects/g-daniel/cards/dota2/cxc/cards.cxc` locally. On the cloud, it needs to be checked out alongside this repo or passed through a separate step. Note the path dependency to the user before building oracle paths.
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

- `/Users/danieltan/` paths will not exist on the cloud sandbox. Any oracle path assuming the local layout needs rewriting. Expect `cards.cxc` to be at a different path.
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
#   exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
#        -Dsun.stdout.encoding=UTF-8 -cp "$CP" clojure.main "$@"
# The UTF-8 flags are load-bearing. Linux JVM defaults stdout to a non-UTF-8
# charset, so oracle output of non-ASCII chars prints `?` and c_safe_param
# plus lex_scan_string_hex DIFF. macOS JVM defaults to UTF-8 already.

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
#           objc/NSObject.h (redirects to GNUstep <Foundation/NSObject.h>),
#           cxc_linux_shim.h (see heredoc below).

# Shim header, force-included into every Linux build by build.clj
sudo tee /usr/local/include/cxc-linux-shim/cxc_linux_shim.h > /dev/null << 'SHIMH'
/* cxc Linux shim (GNUstep + libobjc2).
 *
 * 1. NSValue getValue:size: forwarder. Apple added the two-argument
 *    selector in macOS 10.13. GNUstep stops at the one-argument form,
 *    so declare it as a category that ignores size and forwards. The
 *    cxc emit layer uses the two-argument form for a defensive size
 *    check, on Linux the check is dropped (sizeof(T) is already right
 *    on the caller side).
 *
 * 2. cxc_linux_box_struct. List-of-record boxing stores the record
 *    struct bytes in an NSValue. NSValue copies raw bytes and does not
 *    retain object pointers embedded in the struct, so once the boxing
 *    temporary goes out of scope the record's NSString fields can be
 *    freed while the list still references them. On macOS the freed
 *    memory happens to stay readable, on GNUstep the slot is reused at
 *    once (GSAutoreleasedMemory ... does not recognize isEqualToString:).
 *    The Linux emit calls this instead of +valueWithBytes:objCType:.
 *    It walks the @encode string, collects every object pointer in the
 *    bytes, and ties their lifetime to the NSValue via an associated
 *    object, so the box keeps its fields alive exactly as long as it
 *    lives. */
#ifndef CXC_LINUX_SHIM_H
#define CXC_LINUX_SHIM_H
#import <Foundation/NSValue.h>
@interface NSValue (CxcLinuxShim)
- (void)getValue:(void *)value size:(NSUInteger)size;
@end
NSValue *cxc_linux_box_struct(const void *bytes, const char *type);
#endif
SHIMH

# Shim .m compiled once and linked into every Linux build
sudo tee /usr/local/share/cxc_linux_shim.m > /dev/null << 'SHIMM'
/* cxc Linux shim implementation. Links into every Linux cxc build.
 * See cxc_linux_shim.h for what each piece is for. */
#import <Foundation/Foundation.h>
#include <objc/runtime.h>
#include <objc/encoding.h>
#include "cxc-linux-shim/cxc_linux_shim.h"

@implementation NSValue (CxcLinuxShim)
- (void)getValue:(void *)value size:(NSUInteger)size {
    (void)size;
    [self getValue:value];
}
@end

static char cxc_linux_box_key;

static size_t cxc_align_up(size_t off, size_t align) {
    return align ? (off + align - 1) / align * align : off;
}

/* Collect the object pointers of one value of `type` stored at `base`.
 * Returns the type string just past this value's typespec. */
static const char *cxc_collect_objects(const char *type, const char *base,
                                       NSMutableArray *out) {
    type = objc_skip_type_qualifiers(type);
    switch (*type) {
    case '@': {
        id obj = *(__unsafe_unretained id *)(const void *)base;
        if (obj != nil) [out addObject:obj];
        return objc_skip_typespec(type);
    }
    case '{': {
        const char *p = type + 1;
        while (*p != '=' && *p != '}' && *p != '\0') p++;
        if (*p != '=') return objc_skip_typespec(type);
        p++;
        size_t off = 0;
        while (*p != '}' && *p != '\0') {
            const char *field = objc_skip_type_qualifiers(p);
            off = cxc_align_up(off, objc_alignof_type(field));
            const char *next = cxc_collect_objects(field, base + off, out);
            off += objc_sizeof_type(field);
            p = next;
        }
        return (*p == '}') ? p + 1 : p;
    }
    case '[': {
        const char *p = type + 1;
        size_t n = 0;
        while (*p >= '0' && *p <= '9') { n = n * 10 + (size_t)(*p - '0'); p++; }
        size_t esz = objc_sizeof_type(p);
        const char *after = p;
        for (size_t i = 0; i < n; i++) {
            after = cxc_collect_objects(p, base + i * esz, out);
        }
        if (n == 0) after = objc_skip_typespec(p);
        return (*after == ']') ? after + 1 : after;
    }
    default:
        /* Scalars, C pointers, and unions (ambiguous active member) hold
         * no object reference the box can safely retain. */
        return objc_skip_typespec(type);
    }
}

NSValue *cxc_linux_box_struct(const void *bytes, const char *type) {
    NSValue *v = [NSValue valueWithBytes:bytes objCType:type];
    NSMutableArray *objs = [NSMutableArray array];
    cxc_collect_objects(type, (const char *)bytes, objs);
    if ([objs count] > 0) {
        objc_setAssociatedObject(v, &cxc_linux_box_key, objs,
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    return v;
}
SHIMM
clang -fobjc-arc -fobjc-runtime=gnustep-2.1 -fblocks \
  -I/usr/GNUstep/include -I/usr/local/include/GNUstep \
  -I/usr/local/include/cxc-linux-shim -I/usr/local/include \
  -Wno-import -DGNUSTEP -DGNUSTEP_BASE_LIBRARY=1 -D_NONFRAGILE_ABI=1 \
  -DOS_OBJECT_HAVE_OBJC_SUPPORT=0 -DOS_OBJECT_USE_OBJC=0 \
  -c /usr/local/share/cxc_linux_shim.m -o /usr/local/share/cxc_linux_shim.o
```

### Slice harness tally on Linux cloud

Baseline (chapter 22 Linux glue only, pre-emit-fix): 11 PASS, 1 DIFF, 17 CRASH, 3 UNK.
Current (chapter 22 priorities + emit shim + NSValue shim, 2026-10-04): 27 PASS, 2 DIFF, 0 CRASH, 3 UNK.
Current (after the chapter 32 record-read fix below, 2026-10-05): 37 PASS of 37 harnesses.

- Baseline PASS set: ascii_predicates, diagnostics_shape, env_predicates, lex_classify_word, lex_match_multi_glyph, lex_scan_char, lex_scan_digits_decimal, mangle_c_safe, resolve_import_alias, resolve_mangled_name_for, resolve_name_to_module.
- Added PASS (16 slices unblocked): lex_scan_digits_full, lex_scan_float, lex_scan_number, lex_scan_string, lex_scan_string_escapes, lex_scan_word, lex_tokenize, lex_tokenize_full, named_tuple_canonical, named_tuple_collect_shapes, named_tuple_conforms, named_tuple_emit_typedefs, resolve_build_symbol_table, resolve_enrich_refs, resolve_module_scoped_refs, resolve_unscoped_refs.
- DIFF (resolved pre-chapter-23): c_safe_param and lex_scan_string_hex were oracle-side Linux JVM stdout charset, fixed by the UTF-8 flags in the clj shim. Both PASS now, tally 29 PASS, 3 UNK.
- UNK (resolved in chapter 23): collect_shapes_from_parse_layer, lex_tokenize_with_layout, parse_layer_bootstrap hit a runtime `GSAutoreleasedMemory does not recognize isEqualToString:` then hung. Root cause, List-of-record boxing (`:objc-nsvalue-box` in emit.clj) stores the record bytes in an NSValue, and NSValue does not retain object pointers embedded in the struct. When the boxing temporary `__cxc_nv` releases its strong NSString fields at the end of the statement expression, a heap string field (a Token value built by concat, for example) is freed while the list still holds its pointer. macOS leaves the freed bytes readable so parity holds there, GNUstep reuses the slot at once. Fix, under `linux?` the emit calls the shim's `cxc_linux_box_struct(&__cxc_nv, @encode(T))`, which walks the @encode string, collects every embedded object pointer, and ties their lifetime to the NSValue with an associated object. macOS emit is byte-identical. parse_layer_bootstrap and lex_tokenize_with_layout now PASS. collect_shapes_from_parse_layer no longer crashes, its remaining DIFF is on the nine container-inner corpus rows added in 5cc0ffa, where named_tuple_collect_shapes.cxc has no `Of<N>` grammar yet (port-layer gap, not a runtime bug).

Chapter 32 record-read bugs, root causes and landed fix (2026-10-05). Both were Linux-only and are fixed in the emit, gated by `linux?`, with no shim change.

- `sort_by`, `filter`, and `for_each` lambdas over a `List of R` with string fields over-released those strings. All three bind the element through `unbox-prelude-for-element` in `lower.clj`, which copied the NSValue bytes with `getValue:` straight into a strong-typed struct local. The copy bypasses ARC's retain, the scope-end release still runs, so every lambda call dropped one reference per string field (the chapter-16 `.at` bug on the lambda path). Under `linux?` the prelude now copies the bytes into an aligned untyped buffer and copy-initializes the typed local from it, which retains every strong field (nested records included). These three are the only lambda-taking container methods, and Map and Set never box records, so this was the last raw `getValue:` site.
- An inline `(xs.at(k) ?? R(...)).f` read returned nil for a String field on both arms. Not a lifetime bug. Clang 18 on Linux miscompiles a member read taken straight off a GNU statement expression whose result is a conditional (or a compound literal) of a struct with ARC strong fields. Under `linux?` the `:optional` `??` emit binds the conditional to a named local `__cxc_coal_r` and yields that.
- Reproducers (a five-card `sort_by` then `filter` then `for_each` chain with concatenated names, and three inline `??` reads) segfaulted and printed `(null)` before the fix, 50 of 50 runs clean after. The workaround idioms recorded in chapter 32 (read records through an observation, do not `sort_by` records with strings, the hazard line near the top of this file and the README idioms) are no longer needed on Linux and can be retired by the orchestrator.

Dispatch-ARC crash root cause and landed fix. `OS_OBJECT_USE_OBJC=1` made `dispatch_queue_t` an Objective-C object, so cxc's `@property (nonatomic, strong) dispatch_queue_t own_queue` compiled clean, but Linux's swift-corelibs-libdispatch did not register dispatch queue classes with `objc_class_flag_fast_arc`, so libobjc2's `retain()` fell through to `ManualRetainReleaseMessage(obj, retain, ...)` which msgSent on a dtable not laid out for the retain selector. Chapter 22 landing `d230e12` fixes this at the emit layer: dispatch typed properties become `(nonatomic, assign)` with explicit `dispatch_retain` / `dispatch_release` under a `linux?` guard, and `-DOS_OBJECT_USE_OBJC=0` keeps dispatch handles as plain C pointers so `dispatch_release` expands to the C function instead of the retired `[obj release]`.

### Optional complementary libobjc2 patch

A sibling investigation also produced a libobjc2 arc.mm patch that routes dispatch objects through `_os_object_retain` / `_os_object_release` instead of `ManualRetainReleaseMessage`. The patch lives at `/home/user/gnustep/libobjc2/arc.mm` on the cloud as uncommitted upstream change. Not strictly needed once the emit-layer fix is in but useful if the emit layer is reverted or if other dispatch-typed emit paths slip through. Full patch is +56 lines on `retain()` and `release()` static inlines; see the chapter 22 cloud session transcript for the diff.

If anything in this handoff disagrees with what you observe in the tree, trust the tree and update the handoff.
