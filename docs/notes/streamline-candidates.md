# Streamline candidates (pre-v1.0)

Pulled from the 2026-11-19 audit after the ars port exposed real
boilerplate. Each candidate is a simplification of already-committed
mechanics, not a new capability. The mechanism-invention trap applies
across the whole list: verify the committed dispatch does not already
handle the case before proposing anything parallel.

Items **A** and **B** landed together in hickey-cycle-269 (2026-11-20).
See `compiler/examples/plugin-auto-wrapper.cxc` for the shape and
`docs/spec/spec-cxc-v1.0.md` §7.2.1 for the normative rules.

The rest wait on explicit sign-off.


## C. Sub-phases (scoped state within an atom)

Phases nest under a parent phase and inherit arms:

```
def Wifi:
    -> running:
        -> disconnected
        -> connecting
        -> connected
    running   <: scan    -> connecting then: ...
    connecting <: dhcp_ok -> connected  then: ...
```

Arms on `running` fire regardless of sub-state. Arms on `connecting`
fire only while in `connecting`.

**Design question.** The sub-atom path already models this: make a
`conn: Conn` field whose phase is `disconnected|connecting|connected`.
The memory `corvid-parametric-state-via-subatoms` names this as the
canonical answer. Sub-phases are the inline alternative when the
dimension is state-of-the-parent rather than state-of-a-sibling.

**Cost.** Dispatch becomes two-level. New diagnostics: ambiguous arms
across levels, same-name sub-phases under different parents, illegal
cross-parent transitions. The `end` sentinel rule extends.

**Verdict.** Only land if statecharts-style nesting is a design
commitment at the language level. If it is only an ergonomic
preference, sub-atoms are the terminal shape.


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
