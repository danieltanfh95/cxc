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
  (testing "use-cap.cxc — `use: clock: Clock` sugars to required ctor-param + data-field; capability accessible via `clock.offset` in observables"
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
  (testing "list-add.cxc — Layer 5 Step 4 `then: xs => xs + e` on a List-typed field detects the mutation-in-place pattern and lowers to `[_xs cxc_add:@e]` via the synthesized `-cxc_add:` method on the container class. Initial [1,2] + 3 → count=3."
    (let [{:keys [exit stdout]} (compile-and-run "list-add")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-list-remove
  (testing "list-remove.cxc — mirror of list-add: `xs => xs - e` routes to `-cxc_remove:` → `[_elements removeObject:@e]`. NSMutableArray isEqual: matches boxed NSNumber, so removing @20 from [10,20,30] leaves count=2."
    (let [{:keys [exit stdout]} (compile-and-run "list-remove")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))
