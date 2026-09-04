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
| [`nullable-nil-punning.cxc`](nullable-nil-punning.cxc) | bare `Counter` reference (nil-allowed by default per §3.6.1 unified rule) + `nil` literal; property access on nil → 0 (ObjC nil-messaging) | `0` |
| [`enum-cases.cxc`](enum-cases.cxc) | `def Direction:` with `case`-marker body (F69 tagged-variant inferred kind) → C typedef enum; `Direction.arrow_down` → `Direction_arrow_down` (index 1). Renamed from `Key` because Layer 5b Step 12 reserves `Key` as a prelude enum. (Pre-F68 L6b, opener was `enum Direction:`; retired 2026-08-XX in favor of `def` + structural kind inference.) | `1` |
| [`bool-and-is.cxc`](bool-and-is.cxc) | `and`/`or`/`is` lower to `&&`/`\|\|`/`==`; identity check on nil pointer | `1` |
| [`atom-methods.cxc`](atom-methods.cxc) | Methods on def-atoms → ObjC instance methods; `[c bump]` and `[c plusWithother:5]` selectors | `26` |
| [`atom-handler.cxc`](atom-handler.cxc) | F67 L3 handler-shape transition: `* <: shout(msg: Int) -> self then: print(msg)` (wildcard source + target + typed-payload = handler collapse) lowers as a void ObjC method sharing the atom's dispatch table (spec §1265, §2160). Externally invoked via `logger.shout(42)` from main. (Pre-F67-L3, the legacy `-> shout(msg: Int):` sugar produced the same lowering; retired 2026-08-19.) | `42` |
| [`atom-on-enter.cxc`](atom-on-enter.cxc) | Wildcard on-enter for initial phase — `then: n => 100` fires during init, overrides `data:` default | `100` |
| [`atom-on-enter-self-field.cxc`](atom-on-enter-self-field.cxc) | Unqualified data-field reference on RHS of `then:` resolves to `self.<field>` (direct ivar) | `30` |
| [`observable-simple.cxc`](observable-simple.cxc) | `doubled :< raw + raw` synthesizes zero-arg getter method; `.doubled` reads it via ObjC dot syntax | `42` |
| [`observable-chain.cxc`](observable-chain.cxc) | Observables typed in declaration order; later observable may reference earlier one | `7` |
| [`invariant-pass.cxc`](invariant-pass.cxc) | `invariants:` predicates checked at end of init; passing case runs through | `50` |
| [`invariant-fail.cxc`](invariant-fail.cxc) | Failing invariant aborts init with a stderr diagnostic; nonzero exit | *(abort)* |
| [`use-cap.cxc`](use-cap.cxc) | `use: src: OffsetSource` sugars to a required ctor-param + data-field; cap accessible in observables via `src.offset`. Uses a non-prelude atom name so the F56 collision diagnostic isn't triggered. | `1042` |
| [`chained-comparison.cxc`](chained-comparison.cxc) | `lo <= val <= hi` desugars to `(lo<=val) and (val<=hi)` at parse-time; passing case runs through | `5` |
| [`nested-slot.cxc`](nested-slot.cxc) | `paddle: Paddle = Paddle()` field with default self-constructs a sub-atom in parent init; observable reads through the slot. (Pre-F34-L3, the sugar was `def paddle: Paddle`; retired 2026-08-XX in favor of the ordinary field-with-default syntax.) | `7` |
| [`nested-slot-ctor-args.cxc`](nested-slot-ctor-args.cxc) | `cell: Cell = Cell(v: 99)` passes named args to the sub-atom's initWith selector via the default expression. (Pre-F34-L3 sugar `def cell: Cell(v: 99)` retired.) | `99` |
| [`self-sync-send-in-on-enter.cxc`](self-sync-send-in-on-enter.cxc) | `\|> self.bump()` in initial-phase on-enter fires the transition twice under Layer 4 single-thread (direct call via `cxc_wait_for_processing` runtime helper, renamed from `cxc_send_sync` at F31/F35 L1); `alive <: bump -> alive then: n => n + 1` runs | `2` |
| [`when-guard-saturation.cxc`](when-guard-saturation.cxc) | Header `when: n < max` on a message-triggered transition blocks the send when the guard fails; 4 bumps saturate at `max=3` | `3` |
| [`multi-phase-toggle.cxc`](multi-phase-toggle.cxc) | Same selector (`flip`) on two source phases groups into ONE ObjC method with two first-match branches; 3 flips cycle boot→on→off→on, state=1 | `1` |
| [`non-initial-wildcard.cxc`](non-initial-wildcard.cxc) | Wildcard on a non-initial phase (`-> armed then: n => 100`) inlines into the transition that enters that phase; wildcards only fire on phase *change*, never on self-loops (avoids recursion by design) | `101` |
| [`message-payload.cxc`](message-payload.cxc) | `add(by: Int)` payload binds `by` as a local in header `when: by > 0`, `then: n => n + by`; three sends `add(by: 5/10/3)` sum to 18; selector encoded as `addWithby:` | `18` |
| [`cross-atom-send.cxc`](cross-atom-send.cxc) | Source atom uses a Sink capability and sends `absorb(amount:)` twice; compile-time shape check against Sink's `<: absorb(amount: Int)` transition; sink.total = 12 | `12` |
| [`invariant-post-transition-fail.cxc`](invariant-post-transition-fail.cxc) | Third bump makes `value(=6) > max(=5)`; post-transition `-checkInvariants` runs at end of every message-triggered branch (Step 14) and aborts | *(abort)* |
| [`main-shutdown-on-end.cxc`](main-shutdown-on-end.cxc) | `def main` with `self :> stop` async self-send + `running <: stop -> end`; CxcMain runs `-> end then: print(7)` wildcard, then `transitionToEnd` invokes `cxc_shutdown()` to exit the `dispatch_main()` loop cleanly (Steps 16/17) | `7` |
| [`stream-simple.cxc`](stream-simple.cxc) | Emitter atom emits `:> beep` in its `-> end then:` wildcard; main holds a `def source: Emitter` slot and subscribes via `running <: source.beep -> end`; source-side fanout iterates its `_stream_subscribers_beep` NSHashTable and `cxc_send_async`s onto each subscriber's own queue; CxcMain's receive method fires, prints 9, `-> end` triggers `cxc_shutdown()` (Step 13) | `9` |
| [`stream-typed-payload.cxc`](stream-typed-payload.cxc) | `:> value(n: 42)` carries a typed payload; cross-emit-site type inference on Emitter derives `n: Int`; emit method signature becomes `-cxc_emit_value_Withn:(int)n`; fan-out block captures typed args; main subscribes with `source.value(n: Int)`, cross-atom type check at subscribe site, `then: last_n => n` stashes; self-send `done` advances to `end`; `-> end then: print(last_n)` prints 42 (Step 13 typed payload follow-up) | `42` |
| [`stream-nullable-slot.cxc`](stream-nullable-slot.cxc) | `source: Emitter = nil` (bare reference, nullable by default per §3.6.1 unified rule); at `cxc_runtimeBinding` the register call nil-messages harmlessly; a later `then: source => Emitter()` routes through the synthesized `-setSource:` slot setter which unsubs from the old value (nil no-op), assigns, subs to the new; Emitter's `beep` fanout finds CxcMain registered and delivers; prints 9 then exits (Layer 4b nullable-slot dynamic subscription) | `9` |
| [`prelude-duration.cxc`](prelude-duration.cxc) | The compiler-shipped prelude ([src/cxc/prelude.clj](../src/cxc/prelude.clj)) makes `Duration` and `Instant` records visible without an import; construct + method-call chain sums their millis fields (Layer 5 Step 1 dropped the user-visible `Cxc` prefix — internal names remain `_Cxc_Duration` / `_Cxc_Instant` via [cxc.prelude/rename-table](../src/cxc/prelude.clj) to sidestep CoreServices' SInt32 typedef) | `1500` |
| [`sync-receive.cxc`](sync-receive.cxc) | `running <\| bump(by: Int)` sync-receive glyph compiles the same way as `<:` under Layer 4 single-thread (serial queue already ensures the "no other transition runs meanwhile" guarantee spec §11 attributes to `<\|`); two sync-sends `\|> self.bump(by: 10)`/`\|> self.bump(by: 5)` + `\|> self.stop()` walk main to end printing 15 (Layer 4b) | `15` |
| [`prelude-clock.cxc`](prelude-clock.cxc) | Prelude-shipped `Clock(ticks: 3)` capability atom fires 3 `tick` streams via an async self-send loop, then emits `done` on end; main holds a `def clock: Clock(ticks: 3)` slot, subscribes to both streams, counts ticks, transitions to end on done, prints 3. Exercises prelude tree-shaking (Clock only emitted because referenced), main-as-atom with a prelude-cap slot, multi-stream subscription per source atom (Layer 4b Clock stub with post-rename internal name `_Cxc_Clock`; real periodic `every(rate)` is Layer 5) | `3` |
| [`list-count.cxc`](list-count.cxc) | Layer 5 Step 3 — `List of T` compiles to a synthesized `_Cxc_ListOfT` NSObject class per element-type instantiation, backed by NSMutableArray; the `.count` observable getter returns `(int)[_elements count]`. Main declares `xs: List of Int = [10, 20, 30, 40]`, prints 4. `add`/`remove`/`for_each` land at Steps 4–5. | `4` |
| [`list-add.cxc`](list-add.cxc) | Layer 5 Step 4 — `then: xs => xs + e` on a List-typed field detects the mutation-in-place pattern and routes to `-cxc_add:` (backed by `[_elements addObject:@e]`). Initial `[1, 2]` + 3 → count 3. | `3` |
| [`list-remove.cxc`](list-remove.cxc) | Mirror of list-add: `xs => xs - e` routes to `-cxc_remove:` → `[_elements removeObject:@e]`. NSMutableArray isEqual: matches boxed NSNumber so removing `@20` from `[10,20,30]` leaves count 2. | `2` |
| [`list-for-each.cxc`](list-for-each.cxc) | Layer 5 Step 5 — `xs.for_each(e => print(e))` special-cases at lower into `[xs cxc_for_each:^(id _cxc_e) { int e = [_cxc_e intValue]; ... }]`. Prelude unboxes id-typed block param per element type (Int→intValue). Iterates `[7, 11, 13]`. (F20 REJECTED 2026-08-18 — the `for e in xs:` sugar retired; iteration is method dispatch.) | `7`\|`11`\|`13` |
| [`import-only.cxc`](import-only.cxc) | Layer 5 Step 13 — `from ... import ...` parses advisory-only under Layer 5; names still resolve globally. Full namespace-scoped resolution + per-target prelude directories are Layer 6. Verifies parse compat + emit no-op through Duration/Instant arithmetic (500+250=750). (Renamed from `module-header.cxc` 2026-08-20: F73 retired the user `module <name>` header — module identity is file path.) | `750` |
| [`clock-now.cxc`](clock-now.cxc) | Layer 5b Step A + Step 9 — Clock gains a `now :< native_millis_now()` observable via the compiler-internal FFI hatch (`native_*` builtins lower to raw C). Program transitions on `clock.done` after snapshotting `clock.now > 0` (a liveness check on the observable — always 1 on any live system). Also exercises the `@interface NSObject (CxcFanoutSelectors)` category that emit now ships at the top of the file: Clock's tick/done streams have zero subscribers here, and Apple clang treats an unknown-selector send to an `id`-typed receiver as a hard error; the category declares every fanout selector so unsubscribed sources still compile. | `1` |
| [`f16-l3-enum-first-class.cxc`](f16-l3-enum-first-class.cxc) | F16 L3 — bare enum-case access flows as a first-class value in ctor kwargs (`Box(d: Direction.down)`) and field defaults (`d: Direction = Direction.up`). Two coupled fixes: parser's `looks-like-msg-payload-parens?` now excludes `.` after the type ident (else the heuristic mis-commits `d: Direction` as a `:msg-shape` payload declaration); lower's `default-expr-const-shape?` now accepts `:dot` where target names an enum and field is a bare (payload-less) case. Payload cases still reject (compound-literal defaults deferred). Direction cases have tag values 0/1/2 — prints them on three lines. | `0`\|`1`\|`2` |
| [`auto-nil-visible-to-next-parent-guard.cxc`](auto-nil-visible-to-next-parent-guard.cxc) | F35 L7 — pins spec §1.11 line 1284: sub-atom slot auto-nils atomically at the end of the sub-atom's `end`-transition, BEFORE any other transition on the parent evaluates its `when:` guard. Child emits `:> finished` in its die-transition; under L5 (sync post-order cascade) + L6 (sync `[home nilSlot:@"child"]`), `home._child = nil` before the queued finished-fanout dispatches to home. Home's `alive <: child.finished when: child is nil` transition sees the guard true, bumps hits, emits `:> ok`. Main waits on `home.ok` before ending — if the guard were false, no ok emit fires and dispatch_main idles forever (hangs, doesn't print 0). Prints 1. | `1` |
| [`random-below.cxc`](random-below.cxc) | Layer 5b Step 11 — prelude `Random` capability with `next_u32 :< native_random_u32()` observable and `below(n)` method wrapping `native_random_below(n)`; both lower through the FFI hatch to `arc4random` / `arc4random_uniform`. Deterministic assertion: `rng.below(1)` always returns 0. | `0` |
| [`test-keyboard.cxc`](test-keyboard.cxc) | Layer 5b Step 12 — prelude `TestKeyboardInput(events: Int)` async self-send loop emits `pressed(key: Int)` `events` times then `done`, matching the Clock stub shape. Main counts presses via `<: input.pressed(key: Int)` (dot-sourced receive routed through subscription-map instead of internal message-index), transitions on `input.done`. Prints 3. | `3` |
| [`game-loop.cxc`](game-loop.cxc) | Multi-source integration slice — one main atom composes two prelude capabilities (Clock + TestKeyboardInput), subscribes to four transitions (clock.every → running with 4-tick guard, input.pressed, input.done as no-op, clock.every → end at bound). Deterministic count = 2 presses + 4 ticks = 6. Exercises multi-source fan-in through main's own_queue and demonstrates that game-toys-shape programs are within reach once the remaining stdlib gaps close. | `6` |
| [`nil-lifting-int.cxc`](nil-lifting-int.cxc) | 2026-09-04 Path 1 (envelope) — `let n: Int = nil` stores `_Cxc_Optional_Int64 {.present = 0}`; `n == nil` inspects `.present`; arithmetic (`n + 1`, `1 + n`, `n + n`) propagates envelope-nil via `:c-optional-binop`; non-null arithmetic (`3 + 4`) emits raw binop (no envelope-lift when both sides prove non-nil). Same stdout as the pre-Path-1 sentinel implementation; storage flipped, semantics preserved. | seven-line summary |
| [`nil-lifting-widths.cxc`](nil-lifting-widths.cxc) | 2026-09-04 Path 1 (envelope) — coverage across `Int8` / `Int16` / `Int32` / `Float` / `Double`: each width gets its own `_Cxc_Optional_<T>` typedef (seeded unconditionally). Nil distinguishable from zero at every width; `Double` arithmetic propagates envelope via `:c-optional-binop`. Same stdout as the pre-Path-1 sentinel implementation; storage flipped, semantics preserved. | nine-line summary |
| [`nil-lifting-atom-method.cxc`](nil-lifting-atom-method.cxc) | 2026-09-04 Path 1 (envelope) — method call on a nullable atom receiver returning a primitive nil-carries via the envelope. `n: Int = 42` gives Counter a nullable-`Int` field with `_Cxc_Optional_Int64` storage; `get_n() :- n` inherits envelope return type. `dead.get_n()` on a nil receiver: ObjC nil-messaging returns zero-initialized struct, so `_Cxc_Optional_Int64` becomes `{present=0, value=0}` naturally — no explicit `:c-on-nil-dispatch` sentinel fallback needed. | three-line summary |
| [`nil-lifting-bool-truthy.cxc`](nil-lifting-bool-truthy.cxc) | 2026-09-04 Path 1 (envelope) — `let n: Bool = nil` gives `_Cxc_Optional_Bool {.present = 0}`; control-flow (`while :cond-envelope?`) unwraps as `.present && .value`, so envelope-nil is falsy (Kleene). `while t:` on `Bool` local requires envelope-truthy; explicit `while (n ?? false):` shows the pattern where user coerces envelope to raw. | four-line summary |
| [`nil-lifting-uint.cxc`](nil-lifting-uint.cxc) | 2026-09-04 Path 1 slice 12 — `UInt32` nullable local lifts to `_Cxc_Optional_UInt32` envelope storage. UInt has no natural sentinel value (the full unsigned range is meaningful data), so pre-Path-1 `let n: UInt32 = nil` was silently unsupported. Under Path 1 envelope: `.present = 0` distinguishes nil from every value in the type's range, so `nil == nil` is true and `42 == 0` is false without magic-value collision. | three-line summary |
| [`clock-every.cxc`](clock-every.cxc) | Method-returned Stream subscribe with receive-side count bound. Clock's `every(rate_ms: Int) -> Stream` method (spec §1913 signature) creates a `CxcStream` carrying a per-instance receiver-side selector plus a `dispatch_source_t` timer. Main subscribes via `<: clock.every(rate_ms: 5)(t: Instant)` and uses a two-transition `when:` guard pattern to bound the run at 1 tick (spec-conformant; no external Ticker). Layer 5c lifecycle: subscriber owns the returned stream, stream owns the `CxcTimerHandle` via its `teardown` block, timer handler weak-refs the stream. When the subscriber `-> end`, its resource array drops the stream, stream deallocs, `-cxc_end` fires teardown, handle deallocs, `dispatch_source_cancel` — no cross-atom retain on Clock, so a short-lived Clock doesn't strand its subscribers. Prints 1. | `1` |
| [`list-atom-fan-in.cxc`](list-atom-fan-in.cxc) | `List of AtomType` fan-in with element identity. Container holds `List of Emitter = [Emitter(seed: 10), Emitter(seed: 20)]`; each Emitter's `-> running then: :> beep(v: val)` fans through the container, prepending `e:` to the payload; main subscribes via `<: xs.beep(e: Emitter, v: Int)` and accumulates `v` into total. `clock.every` with a 1-tick receive-side guard bounds the run so `-> end` fires after both beeps arrive. Verifies three fixes: atom-typed literal elements (previously fail-nyi at boxing), container auto-subscribe to elements at `-initWithElements:` (init-load parity with runtime `cxc_add:`), and truly-async `:>` in then: blocks (defers via `cxc_send_async(self, ...)` so subscribers registered in later init phases hook up before fanout runs). Prints 30. | `30` |
| [`clock-every-typed.cxc`](clock-every-typed.cxc) | Method-stream typed payload. Extends `clock-every.cxc` by reading `t.millis` in the receiver body — verifies the whole typed-payload chain end-to-end: receive-side name/type validation, selector `cxc_stream_every_from__Cxc_ClockWithsender:value:` synthesized on both fanout and receive sides, and `native_clock_every`'s specialized event handler building a fresh `_Cxc_Instant` per fire and dispatching via `((void(*)(id,SEL,id,_Cxc_Instant))objc_msgSend)` cast. Payload declaration is required — the receiver can't opt out. Prints 1 (millis > 0 always). | `1` |
| [`duration-basics.cxc`](duration-basics.cxc) | F5 retirement (2026-08-15) — numeric unit suffixes (`500ms`, `2s`, etc.) removed per spec §2.7.1 ("suffix syntax is not part of v1"). Users construct dimensioned values via ordinary constructors: `Duration(millis: 500).millis + Duration(millis: 2000).millis` → 2500. Lex + parse now reject unit suffixes with a targeted diagnostic pointing at the constructor form. | `2500` |
| [`invariant-chained.cxc`](invariant-chained.cxc) | Layer 1.X / stale-check retirement — `-lim <= val <= lim` inside an `invariants:` block. Chained comparisons already desugar to `(A <= B) and (B <= C)` at the parser's `desugar-chained-comparisons` walk (which covers ALL predicate positions, invariants included); the fail-nyi in lower was dead. Also lands unary minus (`:neg` → `:unary` op `-` in emit) with numeric-preserving type inference. | `3` |
| [`stdlib-basics.cxc`](stdlib-basics.cxc) | Three stdlib additions in one program: prelude `Position` record (x/y floats + `plus`), `clamp(x, lo, hi)` compiler builtin (polymorphic on first arg's numeric type, nested-ternary emit), and `Instant.epoch` static-member desugar (`rewrite-prelude-names` extends beyond unit literals to a closed set of prelude class-member statics via `class-member-desugars`). `Position.origin` also registered. `clamp(15,0,10) + Instant.epoch.millis + Position(x:1.0,y:2.0).x` → 11.0. | `11.000000` |

## Running one by hand

```
cd compiler
clj -M -m cxc.core examples/hello.cxc /tmp
/tmp/hello
```

The CLI compiles `examples/<name>.cxc` to `/tmp/<name>` (a native
binary) via clang. Deletion is up to you.

## Reading the generated ObjC

Every `build_test.clj` run (and every CLI invocation) copies the last
emitted `.m` for each named example to
`compiler/.cxc-cache/last/<name>.m`, so you can browse the current
lowering without re-invoking the compiler:

```
less compiler/.cxc-cache/last/clock-now.m
```

The cache directory is gitignored. Its `bin/` sibling stores clang
outputs keyed by the SHA-256 of the emitted `.m`; a re-run with no
compiler-source change skips clang entirely on cache hit. Nuke
`.cxc-cache/` any time — it's pure derived state.

## Adding a new example

1. Write the `.cxc` file. Keep it minimal — one thing per file.
2. Add a build_test.clj entry using `(slurp "examples/<name>.cxc")` and
   assert on the produced stdout.
3. Add a row to the table above.

If the program should NOT compile yet (i.e. it stresses a feature the
lowering doesn't cover), put it under `../../design/examples/` instead
with a note about what blocks it.
