# Compiler examples — programs the current compiler can build and run

Every `.cxc` file in this directory is compiled and executed by the
end-to-end test suite ([compiler/test/cxc/build_test.clj](../test/cxc/build_test.clj)).
The tests verify the produced binary's stdout matches the expected string.

These are **pipeline-verification programs**, not showcase examples.
They exist to prove the current lower→emit→clang→run chain works for a
specific subset of cxc. Design demonstrations (game-of-life,
game-toys.cxc, etc.) live under `../../design/examples/` and may exceed
what the current compiler can lower.

## Files (current coverage)

| File | What it verifies | Expected stdout |
|---|---|---|
| [`hello.cxc`](hello.cxc) | `print` of a string literal | `hello, world` |
| [`greet.cxc`](greet.cxc) | Different string round-trips through the pipeline | `cxc is running` |
| [`arithmetic.cxc`](arithmetic.cxc) | Int binop precedence + `%d` format selection | `11` |
| [`record-value.cxc`](record-value.cxc) | Record → C typedef struct; ctor + field access + arithmetic | `103` |
| [`nested-record.cxc`](nested-record.cxc) | Record-of-record; chained `.a.b` field access; float promotion | `1.500000` |
| [`record-defaults.cxc`](record-defaults.cxc) | Omitted fields fall back to declared defaults (`V().x` uses default) | `3` |
| [`atom-simple.cxc`](atom-simple.cxc) | Def-atom → ObjC class; `Counter()` → `[[Counter alloc] init]`; `.n` → property | `42` |
| [`record-methods.cxc`](record-methods.cxc) | Record method → C free function; `v.plus(o)` → `V_plus(v, o)` | `4.000000` |
| [`atom-ctor-params.cxc`](atom-ctor-params.cxc) | Def-atom ctor params → `initWithFoo:` selector; `data:` defaults resolve against ctor scope | `42` |
| [`nullable-nil-punning.cxc`](nullable-nil-punning.cxc) | `Counter?` type + `nil` literal; property access on nil → 0 (ObjC nil-messaging) | `0` |
| [`enum-cases.cxc`](enum-cases.cxc) | `enum Key: ...` → C typedef enum; `Key.arrow_down` → `Key_arrow_down` (index 1) | `1` |
| [`bool-and-is.cxc`](bool-and-is.cxc) | `and`/`or`/`is` lower to `&&`/`\|\|`/`==`; identity check on nil pointer | `1` |
| [`atom-methods.cxc`](atom-methods.cxc) | Methods on def-atoms → ObjC instance methods; `[c bump]` and `[c plusWithother:5]` selectors | `26` |
| [`atom-on-enter.cxc`](atom-on-enter.cxc) | Wildcard on-enter for initial phase — `then: n => 100` fires during init, overrides `data:` default | `100` |
| [`atom-on-enter-self-field.cxc`](atom-on-enter-self-field.cxc) | Unqualified data-field reference on RHS of `then:` resolves to `self.<field>` (direct ivar) | `30` |
| [`observable-simple.cxc`](observable-simple.cxc) | `doubled :< raw + raw` synthesizes zero-arg getter method; `.doubled` reads it via ObjC dot syntax | `42` |
| [`observable-chain.cxc`](observable-chain.cxc) | Observables typed in declaration order; later observable may reference earlier one | `7` |
| [`invariant-pass.cxc`](invariant-pass.cxc) | `invariants:` predicates checked at end of init; passing case runs through | `50` |
| [`invariant-fail.cxc`](invariant-fail.cxc) | Failing invariant aborts init with a stderr diagnostic; nonzero exit | *(abort)* |
| [`use-cap.cxc`](use-cap.cxc) | `use: clock: Clock` sugars to a required ctor-param + data-field; cap accessible in observables via `clock.offset` | `1042` |
| [`chained-comparison.cxc`](chained-comparison.cxc) | `lo <= val <= hi` desugars to `(lo<=val) and (val<=hi)` at parse-time; passing case runs through | `5` |
| [`nested-slot.cxc`](nested-slot.cxc) | `def paddle: Paddle` self-constructs a sub-atom in parent init; observable reads through the slot | `7` |
| [`nested-slot-ctor-args.cxc`](nested-slot-ctor-args.cxc) | `def cell: Cell(v: 99)` passes named args to the sub-atom's initWith selector | `99` |
| [`self-sync-send-in-on-enter.cxc`](self-sync-send-in-on-enter.cxc) | `self \|> bump` in initial-phase on-enter fires the transition twice under Layer 4 single-thread (direct call via `cxc_send_sync`); `alive <: bump -> alive then: n => n + 1` runs | `2` |
| [`when-guard-saturation.cxc`](when-guard-saturation.cxc) | Header `when: n < max` on a message-triggered transition blocks the send when the guard fails; 4 bumps saturate at `max=3` | `3` |
| [`multi-phase-toggle.cxc`](multi-phase-toggle.cxc) | Same selector (`flip`) on two source phases groups into ONE ObjC method with two first-match branches; 3 flips cycle boot→on→off→on, state=1 | `1` |
| [`non-initial-wildcard.cxc`](non-initial-wildcard.cxc) | Wildcard on a non-initial phase (`-> armed then: n => 100`) inlines into the transition that enters that phase; wildcards only fire on phase *change*, never on self-loops (avoids recursion by design) | `101` |
| [`message-payload.cxc`](message-payload.cxc) | `add{by: Int}` payload binds `by` as a local in header `when: by > 0`, `then: n => n + by`; three sends `add(by: 5/10/3)` sum to 18; selector encoded as `addWithby:` | `18` |
| [`cross-atom-send.cxc`](cross-atom-send.cxc) | Source atom uses a Sink capability and sends `absorb(amount:)` twice; compile-time shape check against Sink's `<: absorb{amount: Int}` transition; sink.total = 12 | `12` |
| [`invariant-post-transition-fail.cxc`](invariant-post-transition-fail.cxc) | Third bump makes `value(=6) > max(=5)`; post-transition `-checkInvariants` runs at end of every message-triggered branch (Step 14) and aborts | *(abort)* |
| [`main-shutdown-on-end.cxc`](main-shutdown-on-end.cxc) | `def main` with `self :> stop` async self-send + `running <: stop -> end`; CxcMain runs `-> end do: print(7)` wildcard, then `transitionToEnd` invokes `cxc_shutdown()` to exit the `dispatch_main()` loop cleanly (Steps 16/17) | `7` |
| [`stream-simple.cxc`](stream-simple.cxc) | Emitter atom emits `:> beep` in its `-> end do:` wildcard; main holds a `def source: Emitter` slot and subscribes via `running <: source.beep -> end`; source-side fanout iterates its `_stream_subscribers_beep` NSHashTable and `cxc_send_async`s onto each subscriber's own queue; CxcMain's receive method fires, prints 9, `-> end` triggers `cxc_shutdown()` (Step 13) | `9` |
| [`stream-typed-payload.cxc`](stream-typed-payload.cxc) | `:> value(n: 42)` carries a typed payload; cross-emit-site type inference on Emitter derives `n: Int`; emit method signature becomes `-cxc_emit_value_Withn:(int)n`; fan-out block captures typed args; main subscribes with `source.value{n: Int}`, cross-atom type check at subscribe site, `then: last_n => n` stashes; self-send `done` advances to `end`; `-> end do: print(last_n)` prints 42 (Step 13 typed payload follow-up) | `42` |
| [`stream-nullable-slot.cxc`](stream-nullable-slot.cxc) | `source: Emitter? = nil` (nullable slot); at `cxc_runtimeBinding` the register call nil-messages harmlessly; a later `then: source => Emitter()` routes through the synthesized `-setSource:` slot setter which unsubs from the old value (nil no-op), assigns, subs to the new; Emitter's `beep` fanout finds CxcMain registered and delivers; prints 9 then exits (Layer 4b nullable-slot dynamic subscription) | `9` |
| [`prelude-duration.cxc`](prelude-duration.cxc) | The compiler-shipped prelude ([src/cxc/prelude.clj](../src/cxc/prelude.clj)) makes `CxcDuration` and `CxcInstant` records visible without an import; construct + method-call chain sums their millis fields (Layer 4b provisional names — `Cxc` prefix avoids CoreFoundation collision; Layer 5 gets proper namespacing) | `1500` |
| [`sync-receive.cxc`](sync-receive.cxc) | `running <\| bump{by: Int}` sync-receive glyph compiles the same way as `<:` under Layer 4 single-thread (serial queue already ensures the "no other transition runs meanwhile" guarantee spec §11 attributes to `<\|`); two sync-sends `\|> bump(10)`/`\|> bump(5)` + `\|> stop` walk main to end printing 15 (Layer 4b) | `15` |
| [`prelude-clock.cxc`](prelude-clock.cxc) | Prelude-shipped `CxcClock(ticks: 3)` capability atom fires 3 `tick` streams via an async self-send loop, then emits `done` on end; main holds a `def clock: CxcClock(ticks: 3)` slot, subscribes to both streams, counts ticks, transitions to end on done, prints 3. Exercises prelude tree-shaking (CxcClock only emitted because referenced), main-as-atom with a prelude-cap slot, multi-stream subscription per source atom (Layer 4b Clock stub — real periodic `every(rate)` is Layer 5) | `3` |

## Running one by hand

```
cd compiler
clj -M -m cxc.core examples/hello.cxc /tmp
/tmp/hello
```

The CLI compiles `examples/<name>.cxc` to `/tmp/<name>` (a native
binary) via clang. Deletion is up to you.

## Adding a new example

1. Write the `.cxc` file. Keep it minimal — one thing per file.
2. Add a build_test.clj entry using `(slurp "examples/<name>.cxc")` and
   assert on the produced stdout.
3. Add a row to the table above.

If the program should NOT compile yet (i.e. it stresses a feature the
lowering doesn't cover), put it under `../../design/examples/` instead
with a note about what blocks it.
