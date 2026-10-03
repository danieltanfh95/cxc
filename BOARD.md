# Corvid board

Async coordination for compiler + bundle + benchmark work. Any Claude session reads this on open to spin up. Human drops new items under `## Inbox`; items in flight live under `## Active`; waiting items under `## Blocked`; completed items under `## Done` (rotate out when the file grows).

Entry shape:
```
- [YYYY-MM-DD @who] <one-line title>
  context: <2-5 lines>
  acceptance: <what "done" looks like>
  thread: <optional back-and-forth, newest at top>
```

Related artifacts:
- Rules I follow: [memory/MEMORY.md](../../.claude/projects/-Users-danieltan-Projects-g-daniel-language-corvid/memory/MEMORY.md) (loaded into every session).
- Benchmark reports: [bench/cxc-agent-baseline/report/](../../bench/cxc-agent-baseline/report/).
- Pattern library: [docs/writing-corvid/patterns/](docs/writing-corvid/patterns/).
- Tight bundles: [bench/cxc-agent-baseline/prompts/cxc-tight-bundle.md](../../bench/cxc-agent-baseline/prompts/cxc-tight-bundle.md), [cxc-tight-bundle-2ex.md](../../bench/cxc-agent-baseline/prompts/cxc-tight-bundle-2ex.md), [cxc-context-bundle-v26.md](../../bench/cxc-agent-baseline/prompts/cxc-context-bundle-v26.md).

---

## Inbox
_(new items from the human, not yet picked up)_

- [2026-10-02 @dan] `self :> message(arg: Record)` silently dropped from inside a self-recursing transition on a complex atom
  context: filed from the match3-roguelike-deckbuilder sim v2 rewrite. Pattern, inside `Encounter.auto_play(policy: Int!, sentinel: Card)` transition's `then:` block, `self :> cast(c: self.pick(policy: policy, sentinel: sentinel))` followed by `self :> auto_play(policy: policy, sentinel: sentinel)` NEVER fires either message. No print inside `cast`'s then body ever runs. No recursive `auto_play` print ever runs. 0 casts across 13 turns of main-driven invocation. Same message pattern (`self :> cast(c: ...)` + `self :> auto_play(...)`) in a stripped-down isolation file ([/tmp/match3-sim-tests/t_selfcast.cxc](/tmp/match3-sim-tests/t_selfcast.cxc)) WORKS, both the cast and the recursive auto fire correctly. Workaround applied in sim.cxc, inline the full cast state-update logic in auto_play's then body (removing the `self :> cast(c: ...)` call), and `self :> auto_play(...)` recursion then fires correctly.
  repro, hard to minimize in-session. Reproduces in [cxc/sim.cxc](../../game/match3-roguelike-deckbuilder/cxc/sim.cxc) at commit before 2026-10-02 inline-cast rewrite. Does NOT reproduce in [/tmp/match3-sim-tests/t_selfcast.cxc](/tmp/match3-sim-tests/t_selfcast.cxc). Difference between them, sim's Encounter has ~20 transitions on `running` state (multiple start_hero_turn, cast with guard + fallthrough, auto_play with guard + fallthrough, five end_hero_turn variants with layered guards) vs the minimal test's three. Suspect, interaction between many guarded transitions on the same state causing the queued `self :>` to resolve against a transition that no-ops or fail the match-pass. Guard evaluation order and state-match semantics look like the first place to probe.
  acceptance, write a minimal repro by progressively adding transitions to t_selfcast.cxc until the drop occurs. Then either fix the drop (every well-formed `self :>` must dispatch to the first-matching transition) OR produce a diagnostic that explains WHY the message was dropped (compiler-level or runtime-level trace). Current silent drop makes agents waste iterations.
  thread:
  - 2026-10-03 — did not reproduce with the mechanical hypothesis "many arms on same state drops self :>". Built [/tmp/match3-sim-tests/t_sim_shape.cxc](/tmp/match3-sim-tests/t_sim_shape.cxc), 13 arms on `running` (hero_turn × 2, cast × 2, auto × 2, enemy_turn × 5), self :> cast + self :> auto fires correctly: casts=2, dmg=40, mana=0, matches minimal baseline. So the drop is NOT just "many sibling arms". Pre-workaround sim.cxc is gone (no git in match3 repo), so I cannot run the exact failing shape. Hypotheses the current repro does NOT cover: (a) message argument containing a nested observation call (`c: self.pick(policy: policy, sentinel: sentinel)` evaluates `pick` which may return the sentinel, which may stage differently from a plain-ref arg); (b) a cast fallthrough arm with EMPTY body (sim.cxc line 159 is `running <: cast(c: Card) -> running` with no `then:` — my repro has a `then: print(...)` fallthrough); (c) the auto_play message name collision with pick-returning-sentinel so self :> cast(c: sentinel) hits the first-arm guard `mana >= c.cost` with sentinel.cost=0 and silently consumes without printing. Next probe should test (b) and (c) — a cast fallthrough arm with no body is the most structurally different thing between my repro and the lost sim.
  - 2026-10-03 — separate observation while probing, `self :> msg` from main's `-> boot` then-block compiles to `dispatch_main()` and never exits if no arm leads main to the `end` phase. stdout stays pipe-buffered, so Claude's shell shows zero output and the drop looks total. Run under `/usr/bin/script -q /dev/null` to force line-buffering and the prints appear. Candidate diagnostic, if main declares `-> end` but has no reachable transition to it, emit a type-check-time warning naming the unreachable terminal phase.

- [2026-10-02 @dan] observation parameter named `default` emits broken selector
  context: filed from the match3-roguelike-deckbuilder sim rewrite. An observation declared as `best(default: Card) :- deck.sort_by(c => -c.dmg).at(0) ?? default` compiles the atom OK, but the caller site `b.best(sentinel)` lowers to `[_b bestWithdefault:_sentinel]` and clang fails with `no visible @interface for 'Bag' declares the selector 'bestWithdefault:'`. Renaming the parameter from `default` to `sentinel` fixes it. Root cause almost certainly, `default` is a C/ObjC reserved word (switch keyword) and the method-emission path renames the formal parameter in the method signature but not in the selector string.
  repro, `~/Projects/g-daniel/game/match3-roguelike-deckbuilder/cxc` scratch, swap `sentinel: Card` for `default: Card` in `/tmp/match3-sim-tests/t_at3.cxc` to reproduce. Same shape as the existing `cross-atom-obs-stale-repro.cxc`'s `first_card(default: Card)` which does NOT currently fail only because that example calls the obs from `self.first_card(x)` inside the same atom, not from a cross-atom callsite.
  acceptance, EITHER emit the selector with the same renamed identifier (`bestWithsentinel:` after escape) OR add a type-check-time ban on reserved-word parameter names with suggested rename in the diagnostic. First path preserves user-chosen names at the cxc source level.

- [2026-10-03 @me] cxc-authoring SKILL.md Hickey-lens review surfaced 11 structural defects
  context: codex 6.1 sol reviewed `.claude/skills/cxc-authoring/SKILL.md` through Hickey framings. Three high-impact findings. (a) Internal contradiction, Zen 6 forbids record observations (line 46), the Observations section forbids them again (line 111), the `Records hold data` paragraph (line 86) permits them, and the language tour permits them. Spec + compiler tests side with permit. (b) Zen 4+5 bundle persistent-state, lifecycle, and capability-scope into one atom-split prescription with a three-transition threshold that is a mechanism, not a policy. (c) Zen 7 phrases "write a plugin" as the mechanism when the policy is enumerable capabilities; standard caps already satisfy it. Also, Zen 10 (compile before you commit) is a verification procedure, not a value, and should move out of the Zen block. Full codex output at [/tmp/hickey-review/final-answer.md](/tmp/hickey-review/final-answer.md). Three candidates flagged for prose-to-table conversion, nullability behavior matrix, atom-split decision tree, failure handling outcome table.
  acceptance, pick 3 of the 11 findings per housekeeping pass. First pass priority, resolve the record-observation legality contradiction (one authoritative place, derive the others), collapse Zen 10 out of the Zen block into a verification note, decouple Zen 4+5+9-architecture into independent ownership/lifecycle/capability axes.

- [2026-10-02 @dan] parking lot — lower-priority improvement candidates
  context: brainstorm captured 2026-10-02 for later triage. Not committed to.
    - trace viewer: fill the sequence / inbox / timeline tabs (shape works, those are stubs)
    - trace diff viewer (two runs side by side)
    - `cxc shape <file>` command so the agent can read shape.json without running the viewer
    - replayable trace export (share across sessions)
    - `[i]` sugar for `.at(i)`
    - parse-time check for 1-2-char identifiers (see [[feedback-example-teaches-shape]] / Mt → Rng SKILL guidance)
    - incremental-work benchmarks (advisor+implementer natural fit, see [[corvid-pitch-scoping]]) — "here's a working atom, add a phase", "here's a trace, find the drop"
    - add a small-task row (T1 counter + T2 sensor) to the three-column `report/visual-comparison/compare.html` so cxc shows its wall advantage at small scale
    - pre-v1.0 spec-drift audit against [design/spec/spec-cxc-v1.0.md](design/spec/spec-cxc-v1.0.md); memory flags this as the work-order driver
  acceptance: no action here. If one gets picked up, it moves to its own Inbox entry with a dated owner.

## Active
_(in flight right now; may have in-session threads below each)_

- [2026-10-02 @me] opus-corvid translate v27d1 — measure diagnostic uplift
  context: baseline for post-D1-D4 diagnostic landing (commit `853fb63`). D1-D4 add readable error messages for `.at` without `??`, duplicate unguarded arms, `=>` with non-field LHS, cross-category `=>` RHS. Hypothesis: fewer agent iterations because each error now carries the fix. Previous floor on same task: v26p15 i2 20.42m, mean clean ~26.3m.
  acceptance: run lands metrics.json. If wall < 20.42m (improves floor) OR mean of 3+ runs < 25m, diagnostic uplift validated. Otherwise diagnostics are not on the critical path for this task's wall time.
  thread:
  - 2026-10-02 15:58 — launched. Daemon pid 56427 serving. Prompt 91845 bytes, sandbox `/tmp/lang-bench-sandbox-RTTCpO`.

- [2026-10-01 @me] v26p1 pocket_moba runs
  context: v26 (both sonnet + opus) failed with no_extract. opus self-scheduled a seed sweep via `ScheduleWakeup` then returned; sonnet wrote binary to `./builds/out_restore/` which the allowlist did not cover, then asked for permission that never resolved. v26p1 patches the bundle with session-shape constraints (no scheduling, no permission requests, output to `/tmp/out` only) and widens the runner allowlist to tolerate `./builds/*` and `timeout *`.
  acceptance: both runs land `pass=true, byte_parity=19/19`. Target wall: opus < 70m, sonnet < 180m.
  thread:
  - 2026-10-01 11:28 — opus at 42m, sandbox `xMwPKh`, 140 turns, 14 cxc compiles, cards.cxc at 1221 LOC (close to final).
  - 2026-10-01 11:28 — sonnet at 42m, sandbox `3f9KCw`, 198 turns, 49 cxc compiles, still in probe phase.
  - 2026-10-01 12:35 — both still active at 1h20m. Opus running sequential verify.sh sweep (long bash call blocks ~15 min internally). Sonnet still rewriting + full-emit compiling (22 compiles at 60-120s each).


## Blocked
_(waiting on a human decision or external event)_

- (empty)

## Done
_(summary rows only; link to commit SHA or report when relevant; rotate out when file grows)_

- 2026-10-03 — re-assigning a child-atom slot via `=>` now re-installs parent linkage AND re-subscribes streams. Fix (option (a) from the acceptance), [lower.clj:8936-8956](compiler/src/cxc/lower.clj#L8936-L8956) extends `slot-setter-methods` with `:slot-name` on atom-slot fields, and [emit.clj:1658-1700](compiler/src/cxc/emit.clj#L1658-L1700) emits `cxc_installParent:nil inSlot:nil` on the OLD value plus `cxc_installParent:self inSlot:@"<name>"` on the NEW value, bracketing the existing unsub/sub pair. Verified on [/tmp/match3-sim-tests/t_rebind_routing2.cxc](/tmp/match3-sim-tests/t_rebind_routing2.cxc), parent receives `:> did_pong(tag: 1, n: 7)` from the rebound child. Pattern suite 5/5 unchanged. Not yet committed, dirty tree.

- 2026-10-03 — error-first diagnostic coverage expansion closed as a tracking item. Four verified gaps (D1, D2, D3, D4) landed in commit `853fb63` and the enumeration note at [compiler/research/diagnostic-coverage.md](compiler/research/diagnostic-coverage.md) is up to date with the four unverified candidates for later. Future diagnostic additions file as their own board items instead of re-opening this one.

- 2026-10-03 — 2x corvid-vs-rust/ts wall-time gap moved out of BOARD into [bench/cxc-agent-baseline/report/corvid-wall-gap-measurement.md](bench/cxc-agent-baseline/report/corvid-wall-gap-measurement.md). The experimental matrix, readings, lever list and run progression now live in the report file. Acceptance was "documents the measurement, no action needed", that condition is met and the row is retired from the active board surface.

- 2026-10-02 — container ctor params promote to fields, two follow-ons from `d7685d2` landed. First follow-on, container-with-no-default on an atom whose body fields trigger F41 synth no longer promotes as a required ctor param that violates spec §3.8.5 ordering (fix at [lower.clj:958-966](compiler/src/cxc/lower.clj#L958-L966), narrows `f41-eligible?` with `(not (container-field? f))`). Second follow-on, `literal-default?` empty-collection extension retired because the ctor param carried the raw `:list` / `:map` / `:set` AST as its default, and `resolve-args-against-params` passed that to `lower-expr`, which has no arm for those nodes, surfacing as `lowering not yet implemented: expression :list` on every empty container field default. Pattern suite 5/5 (real pass this time, see next item). v27d1 cards.cxc compiles clean. Match3 `Enc(deck: d)` override now requires the header form `def Enc(deck: List of Card):` which the `raw-ctor-eligible?` path covers correctly.

- 2026-10-02 — [docs/writing-corvid/patterns/verify.sh](docs/writing-corvid/patterns/verify.sh) false-pass bug fixed. The script compiled into `out/` and only checked whether the binary was executable, so a failed compile left a stale binary from a prior run and reported `OK`. Added one line (`rm -f "$HERE/out/$base"`) before each compile. This masked the empty-container-literal regression above for a full day. Lesson for similar test harnesses, always clear the target artefact before building.

- 2026-10-02 — cxc-daemon committed + wired into bench. Three files (`compiler/bin/cxc-daemon`, `compiler/src/cxc/daemon.clj`, modified `compiler/bin/cxc`) landed as commit `42d5b4c`. Measurement re-verified on this host: hello.cxc 4.12s cold → 0.53s warm (7.8x), confirming the Oct 1 measurement under the memory-citation-decay rule. Wire-in at [bench/cxc-agent-baseline/bin/run-one-lang-versioned.sh](../../bench/cxc-agent-baseline/bin/run-one-lang-versioned.sh) auto-starts the daemon before any `corvid*` language run and logs `cxc-daemon already up` or `starting cxc-daemon` into the run log. Falls back to cold-start silently via `bin/cxc`'s `CXC_NO_DAEMON=1` path if the daemon is unreachable. Opt out of the auto-start with `CXC_NO_DAEMON=1`.

- 2026-10-02 — incremental per-atom recompile investigation. Finding note at [compiler/research/incremental-recompile-finding.md](compiler/research/incremental-recompile-finding.md). TL;DR: low-leverage right now. Daemon captured the big win (3-4s → 0.5s warm). Agent think-time is >90% of bench wall, compile ~1.2%. Prelude dominates the .m (2000+ fixed lines out of 2333 for hello.cxc), so per-atom splits save at most 0.2s per compile today. Three directions scoped: (A) PCH for prelude as cheap experiment, (B) prelude/user block cache split (low value, doesn't save clang), (C) true per-atom .c + link (compelling at 10k+ LOC, net-negative on small files). Recommendation: park C until a 10k+ codebase exists to measure against, revisit A if someone wants to shave 0.15-0.3s off every compile.

- 2026-10-02 — match3 project zero-workaround sweep. All six workarounds documented by the match3 session retired, five via compiler changes landed today, one via source rewrite only: (a) nullable `Int` obs params with `(?? 0)` dance — rewritten to `Int!`; (b) `|: indices.filter(...).count > 0` inlined guard — collapsed to `|: self.any_matches`; (c) `def Entity(hp_init: Int!): hp: Int! = hp_init` aliased-head boilerplate — collapsed to body-field defaults as implicit ctor params; (d) `enum.to_string()` unsupported — `<Enum>_cxc_to_string` now synthesized; (e) stale "List-of-Spell inlined because" comment — removed, variable-ref list literal already compiles; (f) `cxc has no bitshift` stale comment — corrected. Compiler changes verified via patterns suite plus full headless+visual build of [game/match3-roguelike-deckbuilder/cxc/](../../game/match3-roguelike-deckbuilder/cxc/). Workaround #5 (compiler heap OOM at 150-call nested obs) could not be reproduced on synthetic benchmarks; most likely interaction with nullable-Int Optional-expansion in prior drafts (now diagnosed upstream at [lower.clj:8225-8257](compiler/src/cxc/lower.clj#L8225-L8257)).

- 2026-10-02 — body-field defaults with LITERAL defaults promote to ctor params. [lower.clj:884-920](compiler/src/cxc/lower.clj#L884-L920) — `hp: Int! = 50` on an atom now gives `Entity(hp: 100)` as a legal construction with the field default as the fallback. Scoped to `:lit-int/float/string/bool/nil` defaults; non-literal defaults (`size: Int! = width * height`) stay as init-body initializers because they reference other ctor params and must evaluate in-body. Retires the aliased-head boilerplate (`def Entity(hp_init: Int!): hp: Int! = hp_init`) for every tunable field.

- 2026-10-02 — `.to_string() -> String!` on any enum. [lower.clj:1457-1465, 2888-2906](compiler/src/cxc/lower.clj#L1457-L1465) + synth fn at [f37_bytes.clj:388-421](compiler/src/cxc/lower/f37_bytes.clj#L388-L421). Covers value-only, raw-valued, and tagged enums; emits `<Enum>_cxc_to_string(a) -> NSString *` switching over the discriminator and returning `@"<case-name>"`. Payload values are not formatted — just the case name — matching Python / Clojure keyword semantics. Removes the manual int-code-plus-lookup workaround game/sim code was writing.

- 2026-10-02 — observation body Optional-leak diagnostic added at [lower.clj:8225-8257](compiler/src/cxc/lower.clj#L8225-L8257). Previously, writing an obs with nullable params `foo(x: Int, y: Int) :- Int!` that does arithmetic in the body (`x * 10 + y`) propagated Optional through the body, which clang reported as a 2000-char `__typeof__` chain error pointing at the lowered `_Cxc_Optional_Int64 → int64_t` initializer. Now fails at type-check time with a readable message naming the nullable params and the fix (`T` → `T!`). Retires BOARD items "cross-atom obs-with-args fails to resolve" and "intra-atom obs-to-obs wraps return in Optional" — both were the same nullability-usage pattern surfaced through unreadable errors.

- 2026-10-02 — no-arg obs call in transition guard position fixed. [lower.clj:4335-4370](compiler/src/cxc/lower.clj#L4335-L4370) added an atom-observable branch to the `:dot` cond, parallel to F67 L4a's record-observable branch. Bare `self.obs_name` (no parens) where `obs_name` is a zero-param observable on the atom now lowers to `:objc-msg` with no args, instead of falling through to the `:snapshot-name` field-read reroute that produced `__cxc_snap._obs_name`. Verified on guard-position, `if cond:` statement, and `then:` body uses. Pattern regression suite (5 cases) still passes. Fixes match3 Board.run_cascade workaround.

- 2026-10-01 — persistent compile daemon shipped. [src/cxc/daemon.clj](compiler/src/cxc/daemon.clj) + [bin/cxc-daemon](compiler/bin/cxc-daemon) + [bin/cxc](compiler/bin/cxc) wrapper now routes `cxc compile` via UNIX socket when daemon is up. Measured: 1103-LOC file 3.5s cold → 0.5-0.7s warm (**5-6x speedup**); hello.cxc 3s cold → 200-280ms warm (~14x). Fallback to cold-start on any socket error (opt out: `CXC_NO_DAEMON=1`). Start with `cxc-daemon start`, stop with `cxc-daemon stop`.
- 2026-10-01 — bundle v26 updated with Edit-over-Write rule + probe-in-/tmp rule. [bench/cxc-agent-baseline/prompts/cxc-context-bundle-v26.md](bench/cxc-agent-baseline/prompts/cxc-context-bundle-v26.md) now instructs cheap models to use `Edit` for incremental fixes and reserve `Write` for the first draft and final emit. Rationale cited inline from v26p1 measurement: sonnet averaged 59 Writes (full-file overwrites) vs opus 23 Writes + 4 Edits. Target: substantial Write reduction in next sonnet run.
- 2026-09-30 — patterns library created at [docs/writing-corvid/patterns/](docs/writing-corvid/patterns/) with 5 verified `.cxc` examples + `verify.sh` regression check. Covers single-atom, cross-atom, multi-arm expression, enum-record-guard dispatch, container-list.
- 2026-09-30 — tight bundles shipped (`cxc-tight-bundle.md` 2KB for T1-shape, `cxc-tight-bundle-2ex.md` 2.6KB for T2-shape). Verified via T1 counter + T2 sensor+aggregator benchmarks.
- 2026-09-30 — corpus-tax experiments landed, see [bench/cxc-agent-baseline/report/corpus-tax-experiments.md](../../bench/cxc-agent-baseline/report/corpus-tax-experiments.md). Full T1+T2 × sonnet+opus matrix with 3 bundles.
- 2026-09-30 — v26 bundle shipped at [bench/cxc-agent-baseline/prompts/cxc-context-bundle-v26.md](../../bench/cxc-agent-baseline/prompts/cxc-context-bundle-v26.md), targeting T4 (pocket_moba). First run (both models) failed with harness issues → v26p1 patch now in flight.
- 2026-09-30 — compiler error messages improved: empty-list literal in expression position (lower.clj:2311), parse-expr fallthrough with concrete causes (parse.clj:885).

## Standing threads
_(items without a date, carrying context across many sessions)_

- **advisor+implementer split experiment.** Not scoped. Hypothesis: opus writes a 5-10 line design brief for pocket_moba, sonnet writes the emit against it. If the two-tier total wall × cost beats either tier alone, the split is validated. Needs a prompt scaffold and a runner that does opus-first-then-sonnet in one job. Report in [bench/cxc-agent-baseline/report/positioning-tiered-agents.md](../../bench/cxc-agent-baseline/report/positioning-tiered-agents.md) is the vision doc.
- **compile RTT reduction.** JVM cold-start is 2-3s per `cxc compile`. On tasks sonnet runs 50+ compiles (v25 big task spent ~25% of wall on cxc). A GraalVM native-image build or a REPL-persistent compile server would collapse this. Hasn't been attempted.
- **T3 benchmark (lifecycles + plugin).** Current benchmarks are T1 (one atom), T2 (two atoms), T4 (whole-program pocket_moba). T3 (one atom with lifecycle phases + one plugin) is the missing rung. Would test whether tight bundles scale through lifecycle patterns before falling back to full bundle at T4.
- **bundle shape per task class.** Current data says: sonnet prefers broader bundles (more examples), opus prefers narrower (only patterns the target uses). Prompt caching might flatten this; untested. Could be a layered bundle (core 2KB + opt-in sections) that each model draws from differently.

---

How to update this file:
- Add new ideas under `## Inbox` with a date + your handle (`@dan` or just `@you`).
- When I take an item, I move it from Inbox → Active and append a thread entry.
- When I need a decision from you, I move Active → Blocked with the question.
- When I finish, I move Active → Done with a one-line summary + commit SHA / report path.
- No one else edits other people's entries silently. Append-only inside a thread.
