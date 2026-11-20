# Streamline candidates (pre-v1.0)

Pulled from the 2026-11-19 audit after the ars port exposed real
boilerplate. Each candidate is a simplification of already-committed
mechanics, not a new capability. The mechanism-invention trap applies
across the whole list: verify the committed dispatch does not already
handle the case before proposing anything parallel.

Landing record:

- **A + B** — hickey-cycle-269 (2026-11-20). `def plugin <AtomName>:` + `symbol "c_sym" as corvid_name(params) -> ReturnType` auto-wrapper. See `compiler/examples/plugin-auto-wrapper.cxc` and spec §7.2.1.
- **G** — hickey-cycle-270 (2026-11-20). Inline `or <Pattern>: <expr>` arm body. See `compiler/examples/or-recovery-inline-body.cxc` and spec §3.5.4.
- **D** — hickey-cycle-271 (2026-11-20). Grouped transition arms `<source> <: <msg>(args):` + `|: <guard> -> <target>` arms. See `compiler/examples/grouped-transition-arms.cxc` and spec §1.11 (transitions).
- **E + F** — docs-only (hickey-cycle-272, 2026-11-20). Compiler already accepts omitted `-> end` and omitted non-entry `-> phase` declarations (`collect-phase-names` synthesizes the phase set from arrow references). Change is an authoring-guide update so readers don't write redundant declarations.
- **C** — SKIPPED 2026-11-20. Wildcard arms (`* <: msg`) already cover the sub-phase shared-handler use case via concrete-over-wildcard precedence. Docs update in hickey-cycle-272.
- **H** — SKIPPED. No fixture pressure.


## C. Sub-phases (scoped state within an atom) — SKIPPED 2026-11-20

Proposal was: phases nest under a parent and inherit arms (e.g. a
`running:` parent with `disconnected|connecting|connected` sub-phases
underneath it, where arms on `running` fire regardless of sub-state).

**Fixture that motivated the audit.** `game/micro-rts-roguelite/
design/phase-7-game-cxc/main.cxc` carries five phases (`lobby_
encounter`, `lobby_deck`, `idle`, `await_restart`, `draft`) each
duplicating the same three `kb.key` arms: H toggles help, Q quits,
bare catch-all. ~60 lines of pure redundancy. Sub-phases of a `lobby`
parent would factor the shared H/Q/fall-through up a level.

**Why skipped.** `* <:` wildcard arms already solve this with
concrete-over-wildcard precedence (lower.clj:8127-8136). Verified
2026-11-20 against two fixtures at `compiler/scratch/wildcard-
compose*.cxc`:

- Different-message composition: `* <: ping -> self` fires in every
  phase whose concrete arms miss the message.
- Same-message composition: `stage0 <: kb |: code == 15 -> stage1`
  fires in stage0 for code=15; `* <: kb |: code == 4` wildcard fires
  in stage0 for code=4. Guard-partitioned branches dispatch correctly.

The micro-rts duplication would collapse with no new grammar:

    * <: kb.key(code: Int32!, text: CString!) -> self
        |: code == 4
        then: |> self.renderer.toggle_help()
    * <: kb.key(code: Int32!, text: CString!) -> self
        |: code == 113 or code == 81
        then: alias _q :- window.quit()
    * <: kb.key(code: Int32!, text: CString!) -> self   # catch-all

Mechanism-invention trap applies — adding sub-phases when wildcard
arms already distinguish the cases is the exact shape the rule
flags.

**Follow-on (not a candidate, a docs task).** The language tour and
design-patterns guides should name the wildcard-arm pattern for
shared global handlers. The micro-rts author either didn't know
about `* <:` or didn't reach for it; the absence of the pattern in
`docs/writing-corvid/` is the real gap.


## D. Group transition arms under a source+message prefix

Reshape:

```
idle <: fire -> firing |: shot > 0  then: ...
idle <: fire -> dry    |: shot == 0 then: ...
```

into:

```
idle <: fire:
    |: shot > 0  -> firing then: ...
    |: shot == 0 -> dry    then: ...
```

Pure syntactic reshape, no new semantics. Mirrors expression-position
`||:` multi-arm dispatch. Lower rewrites the group into the N-arm
form that exists today.

**Cost.** One new transition-header form in parse. Terminal shape at
lower-time is identical.

**Verdict.** Moderate payoff, low risk. Good "while touching the
grammar" candidate.


## E. Implicit `-> end`

Every atom ends with `-> end`. Make it implicit when absent.

**Cost.** Parse: check for `end` in the synthesized phase set.
Lower: synthesize when missing. One edge case: an atom with no phases
(do any exist?).

**Verdict.** Small but free. Bundle with other wrapper-reducer
changes.


## F. Drop redundant `-> phase` declarations

Phases spring from arrow references inside transition arms. The
top-level `-> phase` lines duplicate that information except for the
entry phase. Keep the first `-> phase` line (entry); drop the others.

**Cost.** Lower already synthesizes the phase set from arrow sites.
No semantic change.

**Verdict.** Small payoff when atoms are tiny. Composes with E.


## G. Inline-body `or T(...): <expr>` arms

Today requires INDENT block:

```
let x: Int = might_fail()
    or Boom(msg: let _m):
        -1
```

Proposed inline:

```
let x: Int = might_fail() or Boom(msg: let _m): -1
```

**Why.** `|:` guard arms, `<:` pattern-match arms, and `||:` multi-arm
dispatch all accept inline single-expression bodies. Only the `or`
recovery arm forces block form. The restriction landed in parse-or-
arms without a cited rationale.

**Cost.** Narrow parse change.

**Verdict.** Pure consistency. Land alongside anything touching the
arm grammar.


## H. Orthogonal / parallel phases

One phase per atom today. Concurrent dimensions (focus × visibility,
connection × battery) split into sub-atoms.

**Verdict.** Skip unless a user-level fixture appears where the
sub-atom form breaks down. Adding a new dimension-set syntax parallel
to sub-atoms violates the mechanism-invention trap directly. Flag and
defer.
