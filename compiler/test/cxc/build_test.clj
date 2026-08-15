(ns cxc.build-test
  "End-to-end regression: cxc source → binary → run → verify stdout.

   Each source program lives as a standalone `.cxc` file under
   `compiler/examples/` so it can be read, hand-run, and iterated on
   outside the test harness. See `examples/README.md` for the current
   coverage table and how to add a new one."
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.build :as build]))

(defn- tmp-dir []
  (let [d (java.nio.file.Files/createTempDirectory
           "cxc-test-" (into-array java.nio.file.attribute.FileAttribute []))]
    (.toFile d)))

(defn- example [name] (slurp (str "examples/" name ".cxc")))

(defn- compile-and-run
  "Compile the named example, run the binary, return {:status :stdout}.
   Fails the surrounding test with clang stderr if compilation fails."
  [example-name]
  (let [src     (example example-name)
        out-dir (tmp-dir)
        {:keys [status stderr bin-path]}
        (build/compile-source-to-file src example-name (.getPath out-dir))]
    (is (zero? status) (str "clang failed for " example-name ":\n" stderr))
    (when (zero? status)
      (build/run-binary bin-path))))

(defn- compile-dir-and-run
  "Compile every `*.cxc` file under `examples/<dir-name>` as one
   program (Layer 6 Step 2/3 multi-file compile), run the binary,
   return {:status :stdout}."
  [dir-name]
  (let [src-dir (str "examples/" dir-name)
        out-dir (tmp-dir)
        {:keys [status stderr bin-path]}
        (build/compile-directory-to-file src-dir dir-name (.getPath out-dir))]
    (is (zero? status) (str "clang failed for " dir-name ":\n" stderr))
    (when (zero? status)
      (build/run-binary bin-path))))

(deftest user-declaration-collides-with-prelude-name-rejected
  (testing "Audit F56 — bounded starter: a user declaration reusing a protected prelude name (e.g. `record Duration:`) parses cleanly, but `compile-source-to-file` raises a targeted diagnostic before the prelude is even prepended. Previously the postwalk in `rewrite-prelude-names` silently renamed the user's `Duration` to `_Cxc_Duration` and merged it with the prelude atom, producing baffling downstream errors."
    (let [out-dir (tmp-dir)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"collides with a protected prelude name"
           (build/compile-source-to-file
            "record Duration:\n    data:\n        n: Int\n\ndef main:\n    -> running\n        do: print(1)\n"
            "collision-check"
            (.getPath out-dir)))))))

(deftest end-to-end-hello-world
  (testing "hello.cxc — print of a string literal"
    (let [{:keys [exit stdout]} (compile-and-run "hello")]
      (is (zero? exit))
      (is (= "hello, world\n" stdout)))))

(deftest end-to-end-different-message
  (testing "greet.cxc — different string round-trips through the pipeline"
    (let [{:keys [exit stdout]} (compile-and-run "greet")]
      (is (zero? exit))
      (is (= "cxc is running\n" stdout)))))

(deftest end-to-end-arithmetic-in-print
  (testing "arithmetic.cxc — int binops through print; precedence preserved"
    (let [{:keys [exit stdout]} (compile-and-run "arithmetic")]
      (is (zero? exit))
      (is (= "11\n" stdout)))))

(deftest end-to-end-record-construction-and-field-access
  (testing "record-value.cxc — record → C struct; V(x:,y:).x + 100 = 103"
    (let [{:keys [exit stdout]} (compile-and-run "record-value")]
      (is (zero? exit))
      (is (= "103\n" stdout)))))

(deftest end-to-end-nested-record-field-chain
  (testing "nested-record.cxc — record-of-record; .a.b field chain works"
    (let [{:keys [exit stdout]} (compile-and-run "nested-record")]
      (is (zero? exit))
      (is (= "1.500000\n" stdout)))))

(deftest end-to-end-record-defaults
  (testing "record-defaults.cxc — omitted fields fall back to declared defaults"
    (let [{:keys [exit stdout]} (compile-and-run "record-defaults")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-def-atom-alloc-init
  (testing "atom-simple.cxc — def-atom → ObjC class; Counter() → alloc/init; .n → property"
    (let [{:keys [exit stdout]} (compile-and-run "atom-simple")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-record-methods
  (testing "record-methods.cxc — v.plus(other) → V_plus(v, other); result field access chains"
    (let [{:keys [exit stdout]} (compile-and-run "record-methods")]
      (is (zero? exit))
      (is (= "4.000000\n" stdout)))))

(deftest end-to-end-atom-ctor-params
  (testing "atom-ctor-params.cxc — Counter(initial: 42) → initWith selector; default fills for Counter()"
    (let [{:keys [exit stdout]} (compile-and-run "atom-ctor-params")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-nullable-nil-punning
  (testing "nullable-nil-punning.cxc — Counter?/nil in data; .n on nil pun-returns 0"
    (let [{:keys [exit stdout]} (compile-and-run "nullable-nil-punning")]
      (is (zero? exit))
      (is (= "0\n" stdout)))))

(deftest end-to-end-enum-cases
  (testing "enum-cases.cxc — enum decl + Key.arrow_down accesses index-1 case"
    (let [{:keys [exit stdout]} (compile-and-run "enum-cases")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-bool-and-is
  (testing "bool-and-is.cxc — and/or/is lower to C boolean ops; identity check on nil"
    (let [{:keys [exit stdout]} (compile-and-run "bool-and-is")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-atom-methods
  (testing "atom-methods.cxc — def-atom methods → ObjC instance methods; both zero-arg and multi-arg"
    (let [{:keys [exit stdout]} (compile-and-run "atom-methods")]
      (is (zero? exit))
      (is (= "26\n" stdout)))))

(deftest end-to-end-atom-on-enter
  (testing "atom-on-enter.cxc — `-> ready then: n => 100` fires during init, overrides data default"
    (let [{:keys [exit stdout]} (compile-and-run "atom-on-enter")]
      (is (zero? exit))
      (is (= "100\n" stdout)))))

(deftest end-to-end-atom-on-enter-self-field
  (testing "atom-on-enter-self-field.cxc — then: hp => max_hp reads sibling data field (unqualified → self ivar)"
    (let [{:keys [exit stdout]} (compile-and-run "atom-on-enter-self-field")]
      (is (zero? exit))
      (is (= "30\n" stdout)))))

(deftest end-to-end-observable-simple
  (testing "observable-simple.cxc — `doubled :< raw + raw` synthesizes a zero-arg getter; .doubled reads it"
    (let [{:keys [exit stdout]} (compile-and-run "observable-simple")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-observable-chain
  (testing "observable-chain.cxc — later observable references earlier one (declaration order enables chaining)"
    (let [{:keys [exit stdout]} (compile-and-run "observable-chain")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-invariant-pass
  (testing "invariant-pass.cxc — init satisfies invariants; program runs to completion"
    (let [{:keys [exit stdout]} (compile-and-run "invariant-pass")]
      (is (zero? exit))
      (is (= "50\n" stdout)))))

(deftest end-to-end-invariant-fail
  (testing "invariant-fail.cxc — data init violates `value <= max`; init aborts with a diagnostic on stderr"
    (let [{:keys [exit stderr]} (compile-and-run "invariant-fail")]
      (is (not (zero? exit))
          "expected non-zero exit from abort()")
      (is (re-find #"Gauge invariant #1 failed" (or stderr ""))
          "expected diagnostic naming the failing invariant index"))))

(deftest end-to-end-use-cap
  (testing "use-cap.cxc — `use: src: OffsetSource` sugars to required ctor-param + data-field; capability accessible via `src.offset` in observables. (Renamed away from the prelude `Clock` collision that F56 diagnostic now rejects — the example's intent is `use:` sugar, not shadowing.)"
    (let [{:keys [exit stdout]} (compile-and-run "use-cap")]
      (is (zero? exit))
      (is (= "1042\n" stdout)))))

(deftest end-to-end-chained-comparison
  (testing "chained-comparison.cxc — `lo <= val <= hi` desugars to `(lo<=val) and (val<=hi)` at parse-time; passing case runs through"
    (let [{:keys [exit stdout]} (compile-and-run "chained-comparison")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-nested-slot
  (testing "nested-slot.cxc — `def paddle: Paddle` self-constructs a sub-atom in parent init; observable reads through the slot"
    (let [{:keys [exit stdout]} (compile-and-run "nested-slot")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-nested-slot-ctor-args
  (testing "nested-slot-ctor-args.cxc — `def cell: Cell(v: 99)` passes named args to the sub-atom's initWith selector"
    (let [{:keys [exit stdout]} (compile-and-run "nested-slot-ctor-args")]
      (is (zero? exit))
      (is (= "99\n" stdout)))))

(deftest end-to-end-self-sync-send-in-on-enter
  (testing "self-sync-send-in-on-enter.cxc — `self |> bump` in on-enter fires the transition twice; n = 2"
    (let [{:keys [exit stdout]} (compile-and-run "self-sync-send-in-on-enter")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-when-guard-saturation
  (testing "when-guard-saturation.cxc — 4 bumps, `when: n < max` blocks the 4th; n saturates at max=3"
    (let [{:keys [exit stdout]} (compile-and-run "when-guard-saturation")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-multi-phase-toggle
  (testing "multi-phase-toggle.cxc — same selector `flip` on two phases, first-match dispatch groups into one method; 3 flips = boot→on→off→on, state=1"
    (let [{:keys [exit stdout]} (compile-and-run "multi-phase-toggle")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-non-initial-wildcard
  (testing "non-initial-wildcard.cxc — `-> armed then: n => 100` fires when the `arm` transition enters `armed` (not at init); final n = 101 after `fire` adds 1"
    (let [{:keys [exit stdout]} (compile-and-run "non-initial-wildcard")]
      (is (zero? exit))
      (is (= "101\n" stdout)))))

(deftest end-to-end-message-payload
  (testing "message-payload.cxc — `add{by: Int}` payload binds `by` as local in when: and then:; three sends with 5/10/3 sum to 18"
    (let [{:keys [exit stdout]} (compile-and-run "message-payload")]
      (is (zero? exit))
      (is (= "18\n" stdout)))))

(deftest end-to-end-cross-atom-send
  (testing "cross-atom-send.cxc — Source constructs then sends `absorb(amount:)` to a Sink capability; sink.total = 12 after two sends"
    (let [{:keys [exit stdout]} (compile-and-run "cross-atom-send")]
      (is (zero? exit))
      (is (= "12\n" stdout)))))

(deftest end-to-end-invariant-post-transition-fail
  (testing "invariant-post-transition-fail.cxc — third bump makes value(=6) > max(=5); post-transition invariant check aborts mid-init"
    (let [{:keys [exit stderr]} (compile-and-run "invariant-post-transition-fail")]
      (is (not (zero? exit)) "expected non-zero exit from abort()")
      (is (re-find #"Gauge invariant #0 failed" (or stderr ""))
          "expected diagnostic naming the violated invariant"))))

(deftest end-to-end-main-shutdown-on-end
  (testing "main-shutdown-on-end.cxc — `self :> stop` triggers `running <: stop -> end`; `-> end do:` prints 7; CxcMain's transitionToEnd invokes cxc_shutdown() to exit the dispatch_main loop cleanly (Steps 16/17)"
    (let [{:keys [exit stdout]} (compile-and-run "main-shutdown-on-end")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-stream-simple
  (testing "stream-simple.cxc — Emitter emits `:> beep` in `-> end do:` after self-firing; main subscribes to `source.beep` via CxcMain's cxc_runtimeBinding; Emitter fanout dispatches to CxcMain via cxc_send_async; CxcMain's transition prints 9 then shuts down (Step 13)"
    (let [{:keys [exit stdout]} (compile-and-run "stream-simple")]
      (is (zero? exit))
      (is (= "9\n" stdout)))))

(deftest end-to-end-stream-typed-payload
  (testing "stream-typed-payload.cxc — `:> value(n: 42)` on Emitter carries a typed payload; main subscribes with `source.value{n: Int}`, binds `n` locally, stashes it in `last_n` via `then:`, self-sends `done` to advance to `end`, and `-> end do: print(last_n)` prints 42. Exercises cross-emit-site type inference on Emitter, typed method signature on `-cxc_emit_value_Withn:`, block-capture of typed args on fan-out, and cross-atom type check at the subscribe site."
    (let [{:keys [exit stdout]} (compile-and-run "stream-typed-payload")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-stream-nullable-slot
  (testing "stream-nullable-slot.cxc — nullable slot `source: Emitter? = nil` filled dynamically via `then: source => Emitter()`; slot setter unsubs from old (nil→no-op), assigns, subs to new; subscription actually registers with Emitter at that point; Emitter fires beep → main receives → prints 9 → exits (Layer 4b nullable-slot dynamic subscription)"
    (let [{:keys [exit stdout]} (compile-and-run "stream-nullable-slot")]
      (is (zero? exit))
      (is (= "9\n" stdout)))))

(deftest end-to-end-prelude-duration
  (testing "prelude-duration.cxc — the prelude-shipped Duration + Instant records are visible without an import; construct + method-call chain (Instant + Duration = Instant with summed millis) prints 1500. Layer 5 Step 1 dropped the Cxc user-visible prefix; internal names are `_Cxc_Duration`/`_Cxc_Instant` via the rename table."
    (let [{:keys [exit stdout]} (compile-and-run "prelude-duration")]
      (is (zero? exit))
      (is (= "1500\n" stdout)))))

(deftest end-to-end-sync-receive
  (testing "sync-receive.cxc — `running <| bump{by: Int}` sync-receive under Layer 4 single-thread compiles identically to `<:` (serial-queue semantics already provide `<|`'s no-interleave guarantee); two `|>` sync-sends followed by `|> stop` cleanly walks main to end printing 15"
    (let [{:keys [exit stdout]} (compile-and-run "sync-receive")]
      (is (zero? exit))
      (is (= "15\n" stdout)))))

(deftest end-to-end-prelude-clock
  (testing "prelude-clock.cxc — prelude-shipped Clock capability atom fires 3 ticks then a `done` stream; main subscribes to both, increments count on each tick, and transitions to end on done, printing 3. Exercises prelude tree-shaking (Clock only included because referenced), main-atom-lifecycle with a nested-slot cap, and multi-stream subscription per source atom."
    (let [{:keys [exit stdout]} (compile-and-run "prelude-clock")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-list-count
  (testing "list-count.cxc — Layer 5 Step 3 lands `List of T` as a compiler-synth class per instantiation. main declares `xs: List of Int = [10,20,30,40]`; init constructs a `_Cxc_ListOfInt` instance backed by NSMutableArray; `xs.count` observable getter returns the element count via (int)[_elements count]. Prints 4."
    (let [{:keys [exit stdout]} (compile-and-run "list-count")]
      (is (zero? exit))
      (is (= "4\n" stdout)))))

(deftest end-to-end-list-add
  (testing "list-add.cxc — audit F44 landing: `do: xs.add(e)` on a List-typed field dispatches through the container's method-index (name `add` → selector `addWithe:`), lowering to `[_xs addWithe:@(e)]` with call-site NSNumber boxing for primitive elements. Retires the former `then: xs => xs + e` pattern-match (which violated spec §3.9 arithmetic-only + / - and spec §1.11 pure-then: rule). Initial [1,2].add(3) → count=3."
    (let [{:keys [exit stdout]} (compile-and-run "list-add")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-list-remove
  (testing "list-remove.cxc — mirror of list-add under audit F44: `do: xs.remove(e)` routes to `-removeWithe:` → `[_elements removeObject:@(e)]`. NSMutableArray isEqual: matches boxed NSNumber, so removing 20 from [10,20,30] leaves count=2."
    (let [{:keys [exit stdout]} (compile-and-run "list-remove")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-list-for-each
  (testing "list-for-each.cxc — Layer 5 Step 5. Step 2's parse-time `for e in xs: print(e)` sugar produces `xs.for_each(e => print(e))`; lower special-cases for_each on a List container with a lambda arg, emitting `[xs cxc_for_each:^(id _cxc_e) { int e = [_cxc_e intValue]; ... }]`. Element unboxed per element-type (Int→intValue). Prints 7 11 13."
    (let [{:keys [exit stdout]} (compile-and-run "list-for-each")]
      (is (zero? exit))
      (is (= "7\n11\n13\n" stdout)))))

(deftest end-to-end-module-header
  (testing "module-header.cxc — Layer 5 Step 13 makes `module com.example.foo` and `from com.cxc.stdlib import Duration, Instant` advisory-parse. Lower/emit ignore the nodes (all names still resolve globally in Layer 5); full multi-module resolution is Layer 6. Program computes 500 + 250 → 750."
    (let [{:keys [exit stdout]} (compile-and-run "module-header")]
      (is (zero? exit))
      (is (= "750\n" stdout)))))

(deftest end-to-end-clock-now
  (testing "clock-now.cxc — Layer 5b Step A + Step 9. Clock gains a `now :< native_millis_now()` observable via the compiler-internal FFI hatch (native_* builtins lower to raw C expressions). Program takes `clock.now > 0` — always 1 on any system with a valid wall clock — as a liveness check on the observable and prints 1. Also exercises the fanout-selector NSObject category emitted at the top of the file: Clock's tick/done streams have no subscribers here, and Apple clang treats an unknown-selector send to an `id`-typed receiver as a hard error; the category makes every fanout selector declared program-wide."
    (let [{:keys [exit stdout]} (compile-and-run "clock-now")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-random-below
  (testing "random-below.cxc — Layer 5b Step 11. Prelude ships `Random` with `next_u32 :< native_random_u32()` observable and `below(n)` method wrapping `native_random_below(n)` — both hatched through the FFI-builtin registry to `arc4random`/`arc4random_uniform`. Deterministic assertion: `rng.below(1)` always returns 0."
    (let [{:keys [exit stdout]} (compile-and-run "random-below")]
      (is (zero? exit))
      (is (= "0\n" stdout)))))

(deftest end-to-end-test-keyboard
  (testing "test-keyboard.cxc — Layer 5b Step 12. Prelude ships `TestKeyboardInput(events: Int)` — an async self-send loop emitting `pressed(key: Int)` `events` times followed by `done`, mirroring the Clock stub shape. Main subscribes to both, counts presses in a `then:` update, transitions on `done`, prints 3. Also confirms the collect-message-shapes fix: dot-sourced receives (`<: input.pressed{key: Int}`) route through the subscription-map path, not the internal message-index (which would collide on nil msg-name for two dot-sourced streams). Prelude enum `Key` is emitted but unused by this program — game-toys is Step 14."
    (let [{:keys [exit stdout]} (compile-and-run "test-keyboard")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-ticker
  (testing "ticker.cxc — Layer 5c Step 10. Prelude ships `Ticker(rate_ms: Int, count: Int)` — a real dispatch_source_t periodic timer wired to send `beat` back to the atom's queue via an FFI-hatched call (`native_schedule_periodic_beat`). Timer runs on its own serial queue (macOS libdispatch quirk: timers targeting the main queue don't fire under dispatch_main). Timer objects get an unbalanced CFBridgingRetain to survive main()'s @autoreleasepool drain — matches the design invariant that Ticker lives until cxc_shutdown/exit(0). Program counts 3 ticks (10ms apart), prints 3, exits 0. Method-returned Stream subscribe (Step 8 original design) is deferred; multiple Ticker instances cover the polymorphism use case."
    (let [{:keys [exit stdout]} (compile-and-run "ticker")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-game-loop
  (testing "game-loop.cxc — Layer 5c integration slice. Composes two prelude capabilities (Ticker + TestKeyboardInput) in one main atom subscribing to four streams: ticker.tick, input.pressed, input.done (consumed as no-op), and ticker.done (exit trigger). Increments a counter on every tick + press. TestKeyboardInput fires its 2 pressed events immediately via async self-send; Ticker's first tick is 5ms out via dispatch_source_t. Both drain into main's own_queue, get serialized, and terminate on ticker.done after 4 ticks — total = 2 + 4 = 6. Deterministic across runs. Prints 6."
    (let [{:keys [exit stdout]} (compile-and-run "game-loop")]
      (is (zero? exit))
      (is (= "6\n" stdout)))))

(deftest end-to-end-clock-every
  (testing "clock-every.cxc — Layer 5c Step 8 (method-returned Stream subscribe). Clock ships a real `every(rate_ms: Int) -> Stream` method (via `native_clock_every` FFI hatch) that creates a CxcStream carrying a per-instance receiver-side selector plus a dispatch_source_t timer. Main subscribes via `<: clock.every(rate_ms: 5){t: Instant}` and Ticker(rate_ms: 50, count: 1) bounds the run. `beats > 0` at end verifies the stream fired at least once — deterministic across runs since the 5ms every-tick easily fires before the 50ms limit. The `{t: Instant}` clause is required by the method-stream payload registry entry for `[_Cxc_Clock every]`; here the body ignores `t`. Prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "clock-every")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-deprecation-warning
  (testing "deprecation-warning.cxc — Layer 6 Step 11. `deprecated: \"<message>\"` section inside a record body attaches a deprecation message; use-site walker in cxc.build emits one warning per external reference to *err*. Verifies: program compiles + runs to `7` (no behavior change), and the warning fires (visible in stderr — test asserts on stdout only since the warning is on stderr, but a real strict-mode build would upgrade to compile-time error)."
    (let [{:keys [exit stdout]} (compile-and-run "deprecation-warning")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-event-callback
  (testing "event-callback.cxc — Layer 6 Step 12 MVP. `data: cb: Block` on main (bare `Block` type = zero-arg void-return escaping block, `void (^)(void)` at C). `then: cb => () => print(42)` installs the block via a new zero-arg-lambda grammar path — parser recognizes `()` `=>` as `{:node :lambda :params [] :body <expr>}`, lowered to `{:node :objc-block-literal :params [] :stmts [<body-expr>]}` when the containing type context is Block. `do: cb()` invokes the stored block — bare-ident-callee resolution recognizes Block-typed self-fields and emits `_cb()`. Block field promotes `def main` to a real atom class so ivar storage + ctx-with-self are available. ARC-wise, block property is `nonatomic copy` so stack blocks migrate to heap on assignment (standard ObjC block-property pattern). Prints `42`."
    (let [{:keys [exit stdout]} (compile-and-run "event-callback")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-set-add-remove
  (testing "set-add-remove.cxc — Layer 6 Step 9. `Set of String` field on main; add alpha, add beta, add alpha (dup — no-op), remove beta. Then check `.count` (1), `.contains(\"alpha\")` (1), `.contains(\"beta\")` (0). Verifies: (1) grammar reuse of `Set of T` (existing `of` single-arg shape works — only base-type-name/cxc-type->c gained a `Set` branch); (2) mangled class name `_Cxc_SetOfString`; (3) NSMutableSet backing with value-equality dedup; (4) same K/V boxing helpers as Map cover Int/String; (5) parallel auto-init-to-empty for Set fields without an explicit default (no set-literal grammar at MVP)."
    (let [{:keys [exit stdout]} (compile-and-run "set-add-remove")]
      (is (zero? exit))
      (is (= "1\n1\n0\n" stdout)))))

(deftest end-to-end-map-basic
  (testing "map-basic.cxc — Layer 6 Step 8. `Map of (String, Int)` field on main, populated via `.add(k, v)`, mutated via `.remove(k)`, queried via `.count` observable and `.contains(k)`. Verifies: (1) grammar for `Map of (K, V)` (paren-tuple as `of` operand → :type-tuple :inner); (2) mangled class name `_Cxc_MapOfStringInt` from base-type-name; (3) auto-init to empty NSMutableDictionary when no field default (MVP has no map-literal grammar; explicit defaults fail-nyi); (4) K/V restricted to Int/String at MVP — String boxes via `[NSString stringWithUTF8String:]`, Int via `@(v)`; (5) synthesized container class ships with `count` observable and `addWithk:v:` / `removeWithk:` / `containsWithk:` methods on the atom method-dispatch path. Add apples=3, pears=7, remove apples → count=1, contains(apples)=0, contains(pears)=1. Prints `1`, `0`, `1`."
    (let [{:keys [exit stdout]} (compile-and-run "map-basic")]
      (is (zero? exit))
      (is (= "1\n0\n1\n" stdout)))))

(deftest end-to-end-console-hello
  (testing "console-hello.cxc — Layer 6 Step 5. Console prelude atom with `write(msg: String) -> Int` method wrapping the `native_console_write` FFI hatch (printf %s). Main nests a Console (`def console: Console`) and calls `console.write(\"hi\\n\")` in its `-> running do:`. Prints `hi` + newline."
    (let [{:keys [exit stdout]} (compile-and-run "console-hello")]
      (is (zero? exit))
      (is (= "hi\n" stdout)))))

(deftest end-to-end-multi-file-demo
  (testing "multi-file-demo/ — Layer 6 Steps 2+3 (module system minimum viable). Two-file program: counter.cxc declares a Counter record; main.cxc uses it via `Counter(seed: 41).bump()`. Each file has a `module <dotted>` header; main declares `from std.time import Duration` (advisory — unused). `compile-directory-to-file` reads both files, validates the import against `stdlib-manifest`, strips module/import lines, concatenates, and processes as one program. Prints 42."
    (let [{:keys [exit stdout]} (compile-dir-and-run "multi-file-demo")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-list-atom-fan-in
  (testing "list-atom-fan-in.cxc — `List of AtomType` fan-in with element identity. Container holds a `List of Emitter` populated by a literal `[Emitter(seed: 10), Emitter(seed: 20)]`. Each Emitter emits `beep(v: val)` on its `-> running do:`. Main subscribes via `<: xs.beep{e: Emitter, v: Int}` and accumulates `v` into total; a Ticker(rate_ms: 50, count: 1) bounds the run. Verifies three landing pieces: (1) atom-typed elements accepted in list literals (previously fail-nyi as `not boxable`); (2) container auto-subscribes to each element's fan-in stream at `-initWithElements:` for literal-loaded elements (runtime `cxc_add:` path already did this; init-load path was silently unhooked); (3) untargeted `:>` in transition do: blocks defers via `cxc_send_async(self, ...)` so subscribers registered in later init phases (container init, main runtimeBinding) are hooked up when the fanout runs on dispatch_main. Total = 10 + 20 = 30."
    (let [{:keys [exit stdout]} (compile-and-run "list-atom-fan-in")]
      (is (zero? exit))
      (is (= "30\n" stdout)))))

(deftest end-to-end-clock-every-typed
  (testing "clock-every-typed.cxc — method-stream typed payload. Extends clock-every.cxc by reading `t.millis` in the subscriber body (`then: got_millis => t.millis`). Verifies the whole typed-payload chain: `method-stream-payload-shapes` registry entry for `[_Cxc_Clock every]` declaring `t: Instant`, receive-side validation matching the declared shape, selector `cxc_stream_every_from__Cxc_ClockWithsender:t:` synthesized on both fanout and receive sides, and native_clock_every's specialized event handler building a fresh `_Cxc_Instant` per fire and dispatching via `((void(*)(id,SEL,id,_Cxc_Instant))objc_msgSend)` cast. `got_millis > 0` at end because timeIntervalSince1970 is always positive on any live system. Prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "clock-every-typed")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-unit-literals
  (testing "unit-literals.cxc — Layer 5.4 unit literal desugar. Numeric literals with a recognized time-unit suffix (`500ms`, `2s`) rewrite at post-parse to `_Cxc_Duration(millis: N)` calls, with the multiplier baked in (`s` → *1000). Rewrite happens in `rewrite-prelude-names` so no lower changes needed. Expression `500ms.millis + 2s.millis` computes 500 + 2000 = 2500. Retires the `unit-suffixed integer literal` fail-nyi for the recognized units — unrecognized units still surface the diagnostic."
    (let [{:keys [exit stdout]} (compile-and-run "unit-literals")]
      (is (zero? exit))
      (is (= "2500\n" stdout)))))

(deftest end-to-end-invariant-chained
  (testing "invariant-chained.cxc — Layer 1.X / stale-check retirement. `-lim <= val <= lim` in an invariants: block. Chained comparisons desugar to `(A <= B) and (B <= C)` at parse time (parser's `desugar-chained-comparisons` walks the entire AST); the stale fail-nyi in lower's invariant path was dead code and is retired. Unary minus (`-lim`) lands as `:neg` (parser) → `:unary` op `-` in emit — pairs cleanly with lower's new `:neg` inference (numeric-preserving). Program constructs Bounded(y: 3, limit: 7); passing invariant runs through; prints 3."
    (let [{:keys [exit stdout]} (compile-and-run "invariant-chained")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-picker-narrowing-with-f41-ctor-params
  (testing "picker-narrowing-f41.cxc — F41 × F36 Landing 2a × F49 Landing A end-to-end. Age has `data: value: Int` (no default → F41 makes it a required ctor param) + `invariants: 0 <= value <= 150` (picker narrows storage to uint8_t). Program constructs `Age(value: 42)`, reads back the value, prints 42. In-range case runs to completion — storage narrowing is transparent to the user."
    (let [{:keys [exit stdout]} (compile-and-run "picker-narrowing-f41")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-picker-narrowing-out-of-range-aborts-on-pre-store-check
  (testing "picker-narrowing-oor.cxc — same shape but `Age(value: 300)` violates the invariant. Pre-store check (F49 Landing A) fires on the WIDE int64 ctor param BEFORE the narrowing uint8_t assignment. Without pre-store check, storage would silently truncate 300 → 44 (300 & 0xff) and the post-store check would pass on 44. With pre-store check, aborts with the source-value-violates diagnostic — 300 never reaches the narrowing store."
    (let [{:keys [exit stderr]} (compile-and-run "picker-narrowing-oor")]
      (is (not (zero? exit)) "expected non-zero exit from pre-store check abort")
      (is (re-find #"Age invariant #1 failed at construction \(source value violates\)"
                   (or stderr ""))
          "expected pre-store diagnostic naming construction site + source value violation"))))

(deftest end-to-end-stdlib-basics
  (testing "stdlib-basics.cxc — three stdlib additions in one program. (1) Prelude `Position` record with `x: Float, y: Float`; ctor + field access. (2) `clamp(x, lo, hi)` compiler builtin — polymorphic on first arg's numeric type (Int here), lowers to nested-ternary via a fresh :ffi-call with an inline emit-c-fn. (3) `Instant.epoch` desugars at rewrite-prelude-names to `Instant(millis: 0)` — same walk that handles unit-literals; a small closed set of prelude class-member statics registered in `class-member-desugars`. Program: `clamp(15,0,10) + Instant.epoch.millis + Position(x:1.0, y:2.0).x` → 10 + 0 + 1.0 = 11.0."
    (let [{:keys [exit stdout]} (compile-and-run "stdlib-basics")]
      (is (zero? exit))
      (is (= "11.000000\n" stdout)))))

(deftest end-to-end-explicit-target-macos
  (testing "F55 Landing 3 — passing `:target :macos` through the opts arity produces byte-equivalent output to the default arity (backward compat + wiring proof). Uses hello.cxc which touches no target-specific caps so behavior is identical; the assertion here is 'no regression from opts arity'."
    (let [src     (example "hello")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "hello" (.getPath out-dir) {:target :macos})]
      (is (zero? status) (str "compile failed with explicit :macos target:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "hello, world\n" stdout)))))))

(deftest end-to-end-record-equality
  (testing "record-equality.cxc — F37 synthesized structural equality. Vec2(1,2)==Vec2(1,2) prints 1; Vec2(1,2)==Vec2(1,3) prints 0; nested Line equality with per-field record recursion prints 1. Prior lowering emitted raw C `==` on struct values (illegal C); F37 reroutes through `<Record>_cxc_equals`."
    (let [{:keys [exit stdout]} (compile-and-run "record-equality")]
      (is (zero? exit))
      (is (= "1\n0\n1\n" stdout)))))

(deftest explicit-target-unknown-rejected
  (testing "F55 Landing 3 — an unknown target name raises a targeted diagnostic naming the missing resource path"
    (let [src     (example "hello")
          out-dir (tmp-dir)
          ex      (try (build/compile-source-to-file
                        src "hello" (.getPath out-dir) {:target :nowhere})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected an exception for an unknown target")
      (is (re-find #"prelude module resource missing" (ex-message ex))))))
