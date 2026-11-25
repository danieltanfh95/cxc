# hickey-cycle-311 — `:>` target inference for self-message-send

Scope memo. No implementation lands in this cycle. The goal is to
settle whether bare `:> X` should default to self as target for
message-send dispatch, mirroring how `:> stream_name` already
defaults to self as emitter for stream-broadcast dispatch.

## Context

cxc has two syntactically similar send forms with different dispatch
mechanisms:

```
:> died                      # stream emit from self (broadcast)
self :> emit_frame           # message send on self's queue (handler dispatch)
```

Both use `:>`. The first is a stream broadcast with implicit
self-as-emitter. The second is a message send with explicit target.
A reader scanning a transition body sees the inconsistency: why does
one need `self` and the other not?

Observed in the game-of-life refactoring study. Stage 4 of the study
emits `:> died` from Grid's gen-overflow arm and enqueues
`self :> emit_frame` from the row-overflow arm, three lines apart.
The emit-bare-ness feels inconsistent with the self-self explicit
form.

## Current behaviour

Snapshot of cxc as of hickey-cycle-310 (2026-11-25).

### Stream emits

`:> stream_name(payload)` inside an atom body emits on that atom's
declared stream. Subscribers listen via `<: target_atom.stream_name`.
Zero subscribers means the emit is a no-op. The emitting atom does
NOT dispatch a handler on itself as part of the emit.

```
# in Grid
:> frame_ready(cells: alive_cells, w: width, h: height)

# in View
running <: g.frame_ready(cells: Set of Cell, w: Int!, h: Int!) -> running
    ...
```

The emitter has no opinion about who listens or whether anyone does.

### Message sends

`target :> message_name(payload)` enqueues a message on the target
atom's own queue. The target's matching `phase <: message_name(...)`
handler dispatches when the queue picks the message up, and may
transition phase.

`self :> message_name(payload)` is the self-target form. Enqueues on
self's own queue. Common pattern for tick loops, self-driven state
machines, deferred render-after-commit.

```
# in Grid
self :> tick(Cursor(0, 0, 0))
self :> emit_frame
```

The receiver (self or other atom) matters because messages are
handler-dispatched, unlike streams which are subscriber-broadcast.

### The asymmetry

A stream has no explicit target in the emit position because
"anywhere" is implicit; self is just the emitter. A message has an
explicit target because dispatch MUST pick one. For self-sends, the
target IS self, and the user still writes it.

## Proposed behaviour

Allow `:> X` to resolve to `self :> X` when X is a declared message
handler on self AND no declared stream on self shares the name. The
existing stream-emit semantics for `:> X` continues when X is a
declared stream.

### Resolution rule

When the parser sees `:> X(payload)` with no explicit target:

1. If X is a declared stream on self → stream emit (today's behaviour)
2. If X is a declared message handler in self's current phase →
   `self :> X(payload)` (new behaviour)
3. If BOTH → handler wins; shadow warning emitted; the name is
   dropped from the stream index so no dead emit method is
   synthesized. A hard compile error would reject the memo's own
   After example (`:> emit_frame` co-existing with
   `running <: emit_frame`), so Phase 1 landed the softer rule.
4. If NEITHER → defensively a compile error, but unreachable under
   current semantics: `:> X` with no handler is still a stream emit,
   because the emit site itself declares the stream.

Cross-atom sends `other :> X(payload)` still require the explicit
target, since "self" default only fires when the target is omitted.

### Example before and after

Stage 4 Grid, before:

```
-> running
    self :> emit_frame
    self :> tick(Cursor(0, 0, 0))

running <: emit_frame -> running
    :> frame_ready(cells: alive_cells, w: width, h: height)

running <: tick(c: Cursor):
    |:  self.done(c) -> end
        :> died
    |:  self.row_done(c) -> running
        ...
        self :> emit_frame
        self :> tick(Cursor(c.gen + 1, 0, 0))
```

After:

```
-> running
    :> emit_frame
    :> tick(Cursor(0, 0, 0))

running <: emit_frame -> running
    :> frame_ready(cells: alive_cells, w: width, h: height)

running <: tick(c: Cursor):
    |:  self.done(c) -> end
        :> died
    |:  self.row_done(c) -> running
        ...
        :> emit_frame
        :> tick(Cursor(c.gen + 1, 0, 0))
```

Every self-targeted `:>` drops the `self`. `:> emit_frame` resolves
to the handler. `:> tick(...)` resolves to the handler. `:> died`
and `:> frame_ready(...)` resolve to stream emits. Same syntax, same
mental model (fire X on self), compiler picks the right mechanism
based on X's declaration.

## Concerns

### Name collision between stream and handler

A user declares both `:> x` (stream emit site) AND `running <: x` (a
handler). What does bare `:> x` mean?

Under the implicit-stream-declaration model (streams are declared by
their emit sites, not by a separate `def stream`), the Phase 1 After
example already hits this: `:> emit_frame` from the init body AND
`running <: emit_frame` as a handler. A hard compile error here
would reject the memo's own target shape.

**Landed resolution (Phase 1, 2026-11-25):** non-fatal shadow warning,
and the name is reclassified to handler-only. The stream-emit path
drops the name from the stream index so no dead `cxc_emit_<name>`
method gets synthesized. A separate atom subscribing via
`<: other.<name>` would then receive nothing, which is the footgun
the warning surfaces.

**Known noise (Phase 1b follow-up):** the warning fires on every
bare `:> X` that resolves to a self-handler, including cases where
no external `<: other.X` subscription exists. This makes the
canonical game-of-life migration emit three warnings for its
self-send sites (`emit_frame`, `step_cell`, `tick`), none of which
are actual footguns. Phase 1b refinement: scan the module for any
`<: <this-atom>.<shadowed-name>` subscription and only warn when
one exists. Until then, the warning reads as "you are using the
Phase 1 feature as designed" and is advisory.

**Secondary bug fixed in the Phase 1 land:** `collect-stream-shapes`
validates emit shapes eagerly (positional args rejected, async-only,
same-payload-shape across sites). Without a skip-names hook, it
failed on handler-named emits before `lower-send`'s target-inference
branch could reclassify them. Fix: the function now takes
`handler-names` and skips those emits during collection, so the
stream-emit shape rules apply only to actual streams. Caught when
migrating game-of-life stage 4 to bare `:>` — `:> tick(Cursor(0,0,0))`
is a positional-arg self-send to the `tick` handler, which the
eager validation flagged as a stream-emit shape violation.

### Reader clarity

Under the proposal, `:> x` could mean two different things depending
on whether x is a stream or a handler. Readers have to know which.

Counter: today's reader already has to know what X is. `self.foo`
versus `foo` versus `other.foo` all require resolving the name to
know the semantics. Stream-vs-handler is one more axis.

Mitigation: IDE tooling disambiguates on hover. The `self.X` /
`self :> X` explicit forms stay legal for readers who want it.

### Dispatch-mechanism-at-a-glance

A dense transition body under the proposal has every send looking
similar:

```
:> died
:> emit_frame
:> tick(...)
:> frame_ready(cells: alive_cells, w: width, h: height)
```

Which is a stream broadcast and which is a handler dispatch? Reader
can't tell without cross-referencing declarations.

Counter: the cross-reference is cheap. Grep the atom for `:> X` emit
sites AND `running <: X` handler sites; the pair tells you. Also,
the semantic distinction (stream vs message) matters at BOTH ends
(declaration site and consumption site); the send site is where the
distinction matters least, because the emitter doesn't care about
dispatch mechanism.

Mitigation: the `self :>` explicit form remains, so projects that
want the visual distinction can lint for it.

### Interaction with the stream-vs-message distinction

Deeper question lurking: are streams and messages actually distinct
concepts, or is "stream" just "message with multi-subscriber broadcast
semantics"? Under the proposal, the SYNTAX unifies; the SEMANTICS stay
distinct (stream is broadcast, no state transition on emitter;
message is handler-dispatch, may transition emitter's phase when
self-sent).

Out of scope for this memo. Unifying the conceptual model would be a
much larger spec touch. The proposal here is syntactic only.

### Phase gating

Message handlers are phase-scoped: `running <: tick` fires only when
self is in `running` phase. Stream emits don't care about emitter
phase. Under the proposal, `:> tick(...)` from a different phase
would... silently not fire? Compile error? Depend on runtime check?

Resolution: same as today's `self :> tick(...)` from a wrong phase.
Runtime message queue holds the message; if the atom never re-enters
`running`, the message sits unprocessed until atom end. Standard
cxc semantics. Not changed by this proposal.

## Interaction with other cxc features

### Cross-atom sends

`other_atom :> X` stays unchanged. The target inference only kicks
in when the target is omitted.

### `|>` sync self-calls

`|> self.method()` stays unchanged. Sync method calls have different
semantics (synchronous processing, no queue). The `self` in `self.method`
is a receiver for method dispatch, not a target for queue enqueue.

If someone drops `self` to get `|> method()`, that's covered by the
hickey-cycle-310 implicit-self proposal (bare identifier resolves to
self-method). The two memos together give the full picture:
`|> method()` is covered by 310, `:> message()` is covered by 311.

### Stream subscriptions

`running <: target.stream(payload) -> ...` unchanged. Subscriptions
always name the target explicitly.

### Self-stream subscription

`running <: self.stream_name(payload) -> ...` is legal (an atom
subscribing to its own stream). Rare. Not affected.

## Migration path

Backward compatible. Every existing `self :> X` and `:> X` works
unchanged. The change is purely additive: bare `:> X` that previously
required the `self.` prefix now resolves when X is a declared
handler.

Two phases.

**Phase 1: parser + resolver change.** Parser already accepts bare
`:> X`. Resolver today only looks up X in the stream namespace;
extend to also check the message-handler namespace with the
disambiguation rules above.

**Phase 2: prelude + examples cleanup.** Audit `.cxc` sources, drop
redundant `self` where `:>` targets self. Keep `self` where the
author wants the visual distinction.

## Scope decisions

### In scope

- Target inference for bare `:> X` (self-default when X is a handler)
- Compile-time disambiguation on stream/handler name collision
- Documentation on the resolution rule

### Out of scope

- Unifying stream and message as a single primitive
- `|>` sync-call receiver inference (covered by 310)
- Cross-atom send target inference (no sensible default; receiver
  matters)
- Changing phase gating semantics

## Open questions

### Stream-emit of a stream shared with a handler

A single name used for BOTH stream emit and message handler on the
same atom is already a design smell. Should the compiler reject this
at declaration time (not just at use time)?

Lean: reject at declaration time. Same-name collision is easier to
diagnose when the declarations themselves are flagged, not when a
downstream `:>` call fails to resolve.

### Pragma to require explicit target

Parallel to 310's explicit-self pragma. Teams preferring the explicit
style can opt back into `self :> X` requirement with a module-level
flag.

### IDE affordance

`:> X` resolution should surface in hover (which mechanism did X
pick). Not a compiler concern, but tooling implication to flag.

## Non-goals

- Changing stream subscription grammar
- Changing cross-atom send grammar
- Introducing a `fire` keyword or similar bare-send primitive
- Collapsing `|>` sync and `:>` async into one syntax

## Recommendation

Approve the target inference rule. Land phase 1 as a compiler change
in the parser / resolver chain. Phase 2 cleanup is optional and
incremental.

Pair with hickey-cycle-310 (implicit self on identifier resolution)
and hickey-cycle-309 (nil-propagation through operators) for the
full ergonomic sweep. Stage 7 of the game-of-life refactoring study
(pending all three cycles) would show the combined shape: nil
propagates, bare identifiers resolve to self, bare `:>` sends resolve
to self. The remaining explicit `self` in the file would mark only
the positions where the author genuinely wants the distinction.

## References

- `docs/decisions/hickey-cycle-310-implicit-self-plan.md` — the
  identifier-resolution companion memo; flagged this question as
  scope-adjacent and deferred here
- `docs/decisions/hickey-cycle-309-nil-propagation-plan.md` — the
  operator-nil-propagation companion memo
- `docs/rosetta/refactoring-studies/game-of-life/` — the study that
  surfaced the `self :>` tax in dense transition bodies
- `hickey-cycle-306-ffi-plan.md` — prior design memo in the same
  decisions genre
