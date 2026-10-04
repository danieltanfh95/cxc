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

If anything in this handoff disagrees with what you observe in the tree, trust the tree and update the handoff.
