(ns cxc.build-test
  "End-to-end regression: cxc source → binary → run → verify stdout.

   Each source program lives as a standalone `.cxc` file under
   `compiler/examples/` so it can be read, hand-run, and iterated on
   outside the test harness. See `examples/README.md` for the current
   coverage table and how to add a new one."
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.build :as build]
            [cxc.parse]))

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
  (testing "Audit F56 — bounded starter: a user declaration reusing a protected prelude name (e.g. `def Duration:`) parses cleanly, but `compile-source-to-file` raises a targeted diagnostic before the prelude is even prepended. Previously the postwalk in `rewrite-prelude-names` silently renamed the user's `Duration` to `_Cxc_Duration` and merged it with the prelude atom, producing baffling downstream errors."
    (let [out-dir (tmp-dir)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"collides with a protected prelude name"
           (build/compile-source-to-file
            "def Duration:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n"
            "collision-check"
            (.getPath out-dir)))))))

(deftest end-to-end-hello-world
  (testing "hello.cxc — print of a string literal"
    (let [{:keys [exit stdout]} (compile-and-run "hello")]
      (is (zero? exit))
      (is (= "hello, world\n" stdout)))))

(deftest end-to-end-custom-section
  (testing "F8 L1-c — custom-section.cxc — user-declared section header (`alias my_data: runtime immutable identity`) used as a field-carrying section inside a class body, fields hoisted to :data-section at fold time, compiles + runs end-to-end"
    (let [{:keys [exit stdout]} (compile-and-run "custom-section")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-f8-computed
  (testing "F8 L3a — `computed:` behavioral section MVP. `V { n: Int = 21 }` declares a `computed: doubled: Int = n * 2`; parse-time rewrite converts the field-shape decl to an observable `doubled() :< n * 2` before lower sees the AST. `V().doubled` dispatches through the observable path and prints 42."
    (let [{:keys [exit stdout]} (compile-and-run "f8-computed")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-prelude-data-alias
  (testing "F7 L2c — `data:` is a prelude alias (spec §4.10.2). No user alias declaration needed; the prelude's `alias data: runtime immutable identity` populates the section-class registry before user source parses."
    (let [{:keys [exit stdout]} (compile-and-run "prelude-data-alias")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-f39-optional-bytes
  (testing "F39 L9 — mixed bare `Int32` (Optional-boxed) + `Int32!` (non-nil) fields. Bare field emits 1-byte presence + 4-byte payload (when present); `Int32!` emits 4 bytes flat. `Opt(x:1, y:2)` yields 1+4+4 = 9 bytes."
    (let [{:keys [exit stdout]} (compile-and-run "f39-optional-bytes")]
      (is (zero? exit))
      (is (= "9\n" stdout)))))

(deftest end-to-end-f37-nested-record-bytes
  (testing "F37 L4 — nested record `Outer{ i: Inner!, k: Int32! }` where Inner is `{ n: Int32! }`. Encoding recurses into Inner's synthesized fn via `appendData:`. Inner emits 4 bytes; Outer contributes those 4 + k's 4 = 8 total (no length prefix per §3.10.10 record row)."
    (let [{:keys [exit stdout]} (compile-and-run "f37-nested-record-bytes")]
      (is (zero? exit))
      (is (= "8\n" stdout)))))

(deftest end-to-end-f37-string-bytes
  (testing "F37 L3 — record with mixed Int32! + String! fields. `n` encodes as 4-byte big-endian Int32; `text` encodes as 4-byte UInt32 length prefix + UTF-8 bytes. `\"hi\"` is 2 UTF-8 bytes; total = 4 + 4 + 2 = 10."
    (let [{:keys [exit stdout]} (compile-and-run "f37-string-bytes")]
      (is (zero? exit))
      (is (= "10\n" stdout)))))

(deftest end-to-end-f37-primitive-bytes
  (testing "F37 L2 — end-to-end canonical bytes for a record with primitive Int32! fields. `Vec(x:1, y:2).as()` synthesizes `Vec_cxc_as_bytes` which returns an NSMutableData * of exactly 8 bytes (2 × 4-byte Int32, big-endian). `b.length` prints 8."
    (let [{:keys [exit stdout]} (compile-and-run "f37-primitive-bytes")]
      (is (zero? exit))
      (is (= "8\n" stdout)))))

(deftest end-to-end-k5-if-expression
  (testing "K5 L1 — `if` at expression position (spec §3.8.1). Uses both simple `if...else` and `if...else if...else` chain. Result flows through `let` bindings. Lowered as C ternary; prints the selected branch value."
    (let [{:keys [exit stdout]} (compile-and-run "k5-if-expression")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-f18-do-expression
  (testing "F18 L2 — `do:` at expression position (spec §3.8.2). Indented `do:` block with two `let` bindings; the final expression `a + b` is the block's value. Lowered as GNU statement-expression `({ ... })`; the enclosing `let r = ...` binds `r` to that value; `print(r)` yields 7."
    (let [{:keys [exit stdout]} (compile-and-run "f18-do-expression")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-f13-weak-nils-out
  (testing "F13 L3 — `weak <AtomType>` field on `Parent` nils out when the target Child's last strong ref goes away. Parent stores `ref: weak Child`; the Child() passed to Parent's ctor is a temporary with no other __strong owner, so ARC releases at end-of-full-expression and zeroes the weak ivar. Reading `p.ref.value` observes the nilled weak → auto-lift produces Optional-nil for `Int` (F39 L5b-ii-c) → print renders `nil\\n`. Failure mode this catches: ARC unexpectedly keeps Child alive (autorelease-pool or accidental __strong storage) → print would render `42`."
    (let [{:keys [exit stdout]} (compile-and-run "f13-weak-nils-out")]
      (is (zero? exit))
      (is (= "nil\n" stdout)))))

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
  (testing (str "nullable-nil-punning.cxc — F39 L5b-ii-c update: `.n` on the "
                "nil `other` slot now propagates nil through the Optional "
                "boxing (was scalar zero-pun pre-flip 2026-08-22). print "
                "renders Optional-nil as `nil\\n`.")
    (let [{:keys [exit stdout]} (compile-and-run "nullable-nil-punning")]
      (is (zero? exit))
      (is (= "nil\n" stdout)))))

(deftest end-to-end-enum-cases
  (testing "enum-cases.cxc — enum decl + Key.arrow_down accesses index-1 case"
    (let [{:keys [exit stdout]} (compile-and-run "enum-cases")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-f16-payload-enum
  (testing "F16 L1 — payload-carrying enum case. `Msg.pong(reply_to: 7, seq: 42)` lowers to a tagged-union compound literal `((Msg){.tag = Msg_Tag_pong, .data.pong = {.reply_to = 7, .seq = 42}})`. Dispatch on `m` binds `r` and `s` via `<field>: let name` in the matched arm; `r + s = 49`."
    (let [{:keys [exit stdout]} (compile-and-run "f16-payload-enum")]
      (is (zero? exit))
      (is (= "49\n" stdout)))))

(deftest end-to-end-f37-enum-bytes
  (testing "F37 L5 — enum canonical bytes. Discriminator is 1 byte (≤256 cases per §3.10.10). `Msg.pong(seq: 42).as()` yields 1 tag byte + 4 payload bytes = 5."
    (let [{:keys [exit stdout]} (compile-and-run "f37-enum-bytes")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-f16-raw-value-enum
  (testing "F16 L2 — raw-value enum. `case not_found = 404` lowers to `HTTP_not_found = 404` in a typed C enum `typedef enum : uint16_t { … } HTTP;`. Print prints the integer value directly."
    (let [{:keys [exit stdout]} (compile-and-run "f16-raw-value-enum")]
      (is (zero? exit))
      (is (= "404\n" stdout)))))

(deftest end-to-end-dispatch-basic
  (testing "dispatch-basic.cxc — F69 L4b `<:` receive-glyph dispatch on a tagged-variant scrutinee. Binds `s = Signal.yellow`, dispatches with case + wildcard arms; the `yellow` branch fires and prints 200."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-basic")]
      (is (zero? exit))
      (is (= "200\n" stdout)))))

(deftest end-to-end-dispatch-multi-pattern
  (testing "dispatch-multi-pattern.cxc — F69 L4c2 comma-separated multi-pattern arms per spec §3.7. `c = Warmth.orange` matches the `red, orange, yellow` arm; prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-multi-pattern")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-dispatch-guard
  (testing "dispatch-guard.cxc — F69 L4c3 guards (`<: pattern when: <cond>:`). Reuses the class-body-transition `when:` guard keyword. `l = Level.medium`, `strict = true`; the `medium when: strict` arm fires and prints 5."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-guard")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-dispatch-literal
  (testing "dispatch-literal.cxc — F69 L4c4 integer-literal patterns. Scrutinee `n = 42` matches the `<: 42:` arm; prints 2."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-literal")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-dispatch-leading-dot
  (testing "dispatch-leading-dot.cxc — F69 L4c5 leading-dot shorthand per spec §3.7.2. `<: .ok:` resolves to `<: Outcome.ok:` via the scrutinee's inferred type. Prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-leading-dot")]
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

(deftest end-to-end-atom-handler
  (testing "atom-handler.cxc — F14: `-> shout(msg: Int):` on an atom lowers as a void ObjC method sharing the atom's dispatch table (spec §1265, §2160). Externally invoked via `logger.shout(42)` from main."
    (let [{:keys [exit stdout]} (compile-and-run "atom-handler")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

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

(deftest end-to-end-observable-parametric
  (testing "F67 L2 — observable-parametric.cxc: `scaled_area(sx: Int, sy: Int) :< (w * sx) * (h * sy)` — parametric observable emits as ObjC method with declared params; caller invokes `r.scaled_area(2, 5)` and gets (3*2)*(4*5) = 120."
    (let [{:keys [exit stdout]} (compile-and-run "observable-parametric")]
      (is (zero? exit))
      (is (= "120\n" stdout)))))

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

(deftest end-to-end-panic-halts
  (testing "panic-halts.cxc — F31/F35 L2. `panic(\"boom\")` writes `panic: <msg>` + backtrace to stderr, then traps. Uncatchable — bypasses `@try/@catch` wrappers (which stay for BEAM-style transition isolation). Exit is SIGTRAP (128 + 5 = 133 on darwin)."
    (let [{:keys [exit stderr]} (compile-and-run "panic-halts")]
      (is (not (zero? exit))
          "expected non-zero exit from __builtin_trap()")
      (is (re-find #"panic: boom" (or stderr ""))
          "expected `panic:` prefix + user-supplied message on stderr"))))

(deftest end-to-end-nil-dot-method
  (testing "nil-dot-method.cxc — F39 Landing 4 + L5b-ii-c. `.method()` on a nil atom returns the zero-value for its Optional return type, whose `present` bit is false. Locks in the nil-flow-through-`.` promise (spec §3.6.1 line 3690, §5.7 line 7448)."
    (let [{:keys [exit stdout]} (compile-and-run "nil-dot-method")]
      (is (zero? exit))
      (is (= "nil\n" stdout)
          "nil.get_n() returns Optional-nil via ObjC zero-value messaging"))))

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
  (testing "nested-slot.cxc — `paddle: Paddle = Paddle()` self-constructs a sub-atom in parent init; observable reads through the slot"
    (let [{:keys [exit stdout]} (compile-and-run "nested-slot")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-nested-slot-ctor-args
  (testing "nested-slot-ctor-args.cxc — `cell: Cell = Cell(v: 99)` passes named args to the sub-atom's initWith selector"
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
  (testing "stream-typed-payload.cxc — `:> value(n: 42)` on Emitter carries a typed payload; main subscribes with `source.value{n: Int}`, binds `n` locally, stashes it in `last_n` via `then:`, self-sends `done` to advance to `end`, and `-> end then: print(last_n)` prints 42. Exercises cross-emit-site type inference on Emitter, typed method signature on `-cxc_emit_value_Withn:`, block-capture of typed args on fan-out, and cross-atom type check at the subscribe site."
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
  (testing "sync-receive.cxc — `running <| bump(by: Int)` sync-receive under Layer 4 single-thread compiles identically to `<:` (serial-queue semantics already provide `<|`'s no-interleave guarantee); two `|>` sync-sends followed by `|> stop` cleanly walks main to end printing 15"
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
  (testing "list-add.cxc — audit F44 landing: `then: xs.add(e)` on a List-typed field dispatches through the container's method-index (name `add` → selector `addWithe:`), lowering to `[_xs addWithe:@(e)]` with call-site NSNumber boxing for primitive elements. Retires the former `then: xs => xs + e` pattern-match (which violated spec §3.9 arithmetic-only + / - and spec §1.11 pure-then: rule). Initial [1,2].add(3) → count=3."
    (let [{:keys [exit stdout]} (compile-and-run "list-add")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-list-remove
  (testing "list-remove.cxc — mirror of list-add under audit F44: `then: xs.remove(e)` routes to `-removeWithe:` → `[_elements removeObject:@(e)]`. NSMutableArray isEqual: matches boxed NSNumber, so removing 20 from [10,20,30] leaves count=2."
    (let [{:keys [exit stdout]} (compile-and-run "list-remove")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-list-for-each
  (testing "list-for-each.cxc — Layer 5 Step 5. `xs.for_each(e => print(e))` lowers to `[xs cxc_for_each:^(id _cxc_e) { int e = [_cxc_e intValue]; ... }]` on a List container. Element unboxed per element-type (Int→intValue). Prints 7 11 13. (F20 REJECTED 2026-08-18 — the `for e in xs:` sugar retired; iteration is method dispatch.)"
    (let [{:keys [exit stdout]} (compile-and-run "list-for-each")]
      (is (zero? exit))
      (is (= "7\n11\n13\n" stdout)))))

(deftest end-to-end-import-only
  (testing "import-only.cxc — `from std.time import Duration, Instant` names resolve against the prelude at Layer 5 (all names still resolve globally; namespace-scoped resolution is Layer 6). Program computes 500 + 250 → 750. (Renamed from module-header 2026-08-20: F73 retired the user `module <name>` header — module identity is file path — so the file no longer carries or exercises that shape.)"
    (let [{:keys [exit stdout]} (compile-and-run "import-only")]
      (is (zero? exit))
      (is (= "750\n" stdout)))))

(deftest end-to-end-clock-now
  (testing "clock-now.cxc — Layer 5b Step A + Step 9. Clock gains a `now :< native_millis_now()` observable via the compiler-internal FFI hatch (native_* builtins lower to raw C expressions). Program takes `clock.now > 0` — always 1 on any system with a valid wall clock — as a liveness check on the observable and prints 1. Also exercises the fanout-selector NSObject category emitted at the top of the file: Clock's tick/done streams have no subscribers here, and Apple clang treats an unknown-selector send to an `id`-typed receiver as a hard error; the category makes every fanout selector declared program-wide."
    (let [{:keys [exit stdout]} (compile-and-run "clock-now")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-f16-l3-enum-first-class
  (testing "f16-l3-enum-first-class.cxc — F16 L3 verify: enum values flow as first-class expressions in ctor calls (`Box(d: Direction.down)`) and as field defaults (`d: Direction = Direction.up`). Two coupled fixes: (1) parser — `looks-like-msg-payload-parens?` now excludes `.` after the type ident (previously the heuristic misparsed `Box(d: Direction.down)` as a `:msg-shape` payload declaration because it only excluded `(`); (2) lower — `default-expr-const-shape?` now accepts `:dot` when target is a bare `:ident` naming an enum in enum-env AND field is a bare (no-payload) case, satisfying spec §3.8.5 rule 1 for enum-typed defaults. Payload-case defaults (`.ok(v: 1)`) remain rejected (compound-literal defaults deferred). Prints 0/1/2 on three lines."
    (let [{:keys [exit stdout]} (compile-and-run "f16-l3-enum-first-class")]
      (is (zero? exit))
      (is (= "0\n1\n2\n" stdout)))))

(deftest end-to-end-auto-nil-visible-to-next-parent-guard
  (testing "auto-nil-visible-to-next-parent-guard.cxc — F35 L7 pins spec §1.11 line 1284: sub-atom slot auto-nils atomically at the end of the sub-atom's `end`-transition, BEFORE any other transition on the parent evaluates its `when:` guard. Child emits `:> finished` in its die-transition, then transitionToEnd runs sync (L5 cascade + L6 [home nilSlot:@\"child\"] direct call) → `home._child = nil`. Home's `alive <: child.finished` receive dispatches later, guard `when: child is nil` MUST see nil (proof of L7 guarantee). Guard-passes path emits `:> ok` back to main. Main waits on `home.ok` before ending — so if the guard were false, no ok emit fires, dispatch_main idles forever (test hangs, not prints 0). Prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "auto-nil-visible-to-next-parent-guard")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-random-below
  (testing "random-below.cxc — Layer 5b Step 11. Prelude ships `Random` with `next_u32 :< native_random_u32()` observable and `below(n)` method wrapping `native_random_below(n)` — both hatched through the FFI-builtin registry to `arc4random`/`arc4random_uniform`. Deterministic assertion: `rng.below(1)` always returns 0."
    (let [{:keys [exit stdout]} (compile-and-run "random-below")]
      (is (zero? exit))
      (is (= "0\n" stdout)))))

(deftest end-to-end-test-keyboard
  (testing "test-keyboard.cxc — Layer 5b Step 12. Prelude ships `TestKeyboardInput(events: Int)` — an async self-send loop emitting `pressed(key: Int)` `events` times followed by `done`, mirroring the Clock stub shape. Main subscribes to both, counts presses in a `then:` update, transitions on `done`, prints 3. Also confirms the collect-message-shapes fix: dot-sourced receives (`<: input.pressed(key: Int)`) route through the subscription-map path, not the internal message-index (which would collide on nil msg-name for two dot-sourced streams). Prelude enum `Key` is emitted but unused by this program — game-toys is Step 14."
    (let [{:keys [exit stdout]} (compile-and-run "test-keyboard")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-game-loop
  (testing "game-loop.cxc — F62-migrated. Composes two prelude capabilities (Clock + TestKeyboardInput) in one main atom. Uses a two-transition `when:` guard pattern on `clock.every(rate_ms: 5){t: Instant}` — one running-guard that increments through 4 ticks, one end-guard that fires when the count reaches 4. Also subscribes to input.pressed (adds 1) and input.done (no-op). Total = 2 presses + 4 ticks = 6. Deterministic across runs since 5ms is well under the input-fire deadline; both sources drain into main's own_queue and serialize."
    (let [{:keys [exit stdout]} (compile-and-run "game-loop")]
      (is (zero? exit))
      (is (= "6\n" stdout)))))

(deftest end-to-end-clock-every
  (testing "clock-every.cxc — F62-migrated. Method-returned Stream subscribe with receive-side counting bound (no external Ticker). Clock ships `every(rate_ms: Int) -> Stream of Instant` (spec §1913 signature) via `native_clock_every` FFI hatch — creates a CxcStream carrying a per-instance receiver-side selector plus a dispatch_source_t timer. Main subscribes via `<: clock.every(rate_ms: 5){t: Instant}` with a two-transition `when:` guard pair (increment through 1 tick, then exit). `beats > 0` at end verifies the stream fired at least once."
    (let [{:keys [exit stdout]} (compile-and-run "clock-every")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-deprecation-warning
  (testing "deprecation-warning.cxc — Layer 6 Step 11. `deprecated: \"<message>\"` section inside a record body attaches a deprecation message; use-site walker in cxc.build emits one warning per external reference to *err*. Verifies: program compiles + runs to `7` (no behavior change), and the warning fires (visible in stderr — test asserts on stdout only since the warning is on stderr, but a real strict-mode build would upgrade to compile-time error)."
    (let [{:keys [exit stdout]} (compile-and-run "deprecation-warning")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-event-callback
  (testing "event-callback.cxc — Layer 6 Step 12 MVP. `data: cb: Block` on main (bare `Block` type = zero-arg void-return escaping block, `void (^)(void)` at C). `then: cb => () => print(42)` installs the block via a new zero-arg-lambda grammar path — parser recognizes `()` `=>` as `{:node :lambda :params [] :body <expr>}`, lowered to `{:node :objc-block-literal :params [] :stmts [<body-expr>]}` when the containing type context is Block. `then: cb()` invokes the stored block — bare-ident-callee resolution recognizes Block-typed self-fields and emits `_cb()`. Block field promotes `def main` to a real atom class so ivar storage + ctx-with-self are available. ARC-wise, block property is `nonatomic copy` so stack blocks migrate to heap on assignment (standard ObjC block-property pattern). Prints `42`."
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
  (testing "console-hello.cxc — Layer 6 Step 5. Console prelude atom with `write(msg: String) -> Int` method wrapping the `native_console_write` FFI hatch (printf %s). Main is capability-provisioned (`def main(console: Console)`) and calls `console.write(\"hi\\n\")` in its `-> running do:`. Prints `hi` + newline."
    (let [{:keys [exit stdout]} (compile-and-run "console-hello")]
      (is (zero? exit))
      (is (= "hi\n" stdout)))))

(deftest end-to-end-multi-file-demo
  (testing "multi-file-demo/ — Layer 6 Steps 2+3 (module system minimum viable). Two-file program: `counter.cxc` declares a `Counter` record; `main.cxc` uses it via `Counter(seed: 41).bump()` and declares `from std.time import Duration` (advisory — unused). `compile-directory-to-file` reads both files, synthesizes per-file `module <derived>` headers internally for provenance (F73: user-facing `module <name>` retired, module identity is file path), validates the import against `stdlib-manifest`, strips the synthesized headers before lower, and processes as one program. Prints 42."
    (let [{:keys [exit stdout]} (compile-dir-and-run "multi-file-demo")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-list-atom-fan-in
  (testing "list-atom-fan-in.cxc — F62-migrated. `List of AtomType` fan-in with element identity. Container holds a `List of Emitter` populated by a literal `[Emitter(seed: 10), Emitter(seed: 20)]`. Each Emitter emits `beep(v: val)` on its `-> running do:`. Main subscribes via `<: xs.beep(e: Emitter, v: Int)` and accumulates `v` into total; a Clock.every(50) receive-side count bound (single tick) fires `-> end` after both beeps arrive. Verifies three landing pieces: (1) atom-typed elements accepted in list literals (previously fail-nyi as `not boxable`); (2) container auto-subscribes to each element's fan-in stream at `-initWithElements:` for literal-loaded elements (runtime `cxc_add:` path already did this; init-load path was silently unhooked); (3) untargeted `:>` in transition then: blocks defers via `cxc_send_async(self, ...)` so subscribers registered in later init phases (container init, main runtimeBinding) are hooked up when the fanout runs on dispatch_main. Total = 10 + 20 = 30."
    (let [{:keys [exit stdout]} (compile-and-run "list-atom-fan-in")]
      (is (zero? exit))
      (is (= "30\n" stdout)))))

(deftest end-to-end-clock-every-typed
  (testing "clock-every-typed.cxc — method-stream typed payload. Extends clock-every.cxc by reading `t.millis` in the subscriber body (`then: got_millis => t.millis`). Verifies the whole typed-payload chain: `method-stream-payload-shapes` registry entry for `[_Cxc_Clock every]` declaring `t: Instant`, receive-side validation matching the declared shape, selector `cxc_stream_every_from__Cxc_ClockWithsender:t:` synthesized on both fanout and receive sides, and native_clock_every's specialized event handler building a fresh `_Cxc_Instant` per fire and dispatching via `((void(*)(id,SEL,id,_Cxc_Instant))objc_msgSend)` cast. `got_millis > 0` at end because timeIntervalSince1970 is always positive on any live system. Prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "clock-every-typed")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-duration-basics
  (testing "duration-basics.cxc — F5 retirement. Unit suffixes (`500ms`, `2s`, `16ms`) removed per spec §2.7.1 (\"suffix syntax is not part of v1\"). Users construct durations explicitly via `Duration(millis: N)`; suffix machinery in lex + prelude retired. Expression `Duration(millis: 500).millis + Duration(millis: 2000).millis` computes 500 + 2000 = 2500."
    (let [{:keys [exit stdout]} (compile-and-run "duration-basics")]
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
  (testing "stdlib-basics.cxc — three stdlib additions in one program. (1) Prelude `Position` record with `x: Float, y: Float`; ctor + field access. (2) `clamp(x, lo, hi)` compiler builtin — polymorphic on first arg's numeric type (Int here), lowers to nested-ternary via a fresh :ffi-call with an inline emit-c-fn. (3) `Instant.epoch` desugars at rewrite-prelude-names to `Instant(millis: 0)` — a small closed set of prelude class-member statics registered in `class-member-desugars`. Program: `clamp(15,0,10) + Instant.epoch.millis + Position(x:1.0, y:2.0).x` → 10 + 0 + 1.0 = 11.0."
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

(deftest end-to-end-let-binding
  (testing "let-binding.cxc — F18 L1 statement-form `let x = expr` inside a `then:` clause. Post F18 L1' Landing 2 (2026-08-24) the transition `let:` sub-block is retired; the example migrated to statement-form. Bindings emit as C var-decls at the point they appear; sequential bindings reference each other. Program: `let doubled = 10 * 2; let plus_one = doubled + 1; print(doubled); print(plus_one)` prints 20, then 21."
    (let [{:keys [exit stdout]} (compile-and-run "let-binding")]
      (is (zero? exit))
      (is (= "20\n21\n" stdout)))))

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

;; ---------------------------------------------------------------------------
;; F51 L1 — module identity + provenance at compile-directory entry.
;; ---------------------------------------------------------------------------

(defn- write-file! [^java.io.File dir rel-path content]
  (let [f (java.io.File. dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(deftest f73-user-module-header-rejected-with-fix-it
  (testing "F73 (2026-08-20, supersedes F51 L1) — a `module <name>` header at the top of any source file is a hard error at parse time. Module identity is the file path relative to the source root; the resolver walks the tree. Diagnostic names the retirement and points at the fix (delete the line)."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "hello.cxc"
                               "module com.example.hello\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "hello" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected fail! on the retired header")
      (is (re-find #"`module <name>` declaration is retired" (ex-message ex)))
      (is (re-find #"file path relative to the source root" (ex-message ex))
          "diagnostic names the file-path-authoritative rule"))))

(deftest f51-l1-reserved-main-subdirectory-rejected
  (testing "F51 L1 — a `src-dir/main/` subdirectory collides with the reserved `:main` module (root of src-dir + single-file compile). Rejected at compile start with a rename fix-it."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "root.cxc"
                               "def main:\n    -> running\n        then: print(1)\n")
          _       (write-file! src-dir "main/other.cxc"
                               "def X:\n    n: Int\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L1: subdirectory" (ex-message ex)))
      (is (re-find #"reserved `:main`" (ex-message ex))))))

(deftest f51-l1-duplicate-declaration-in-same-module-rejected
  (testing "F51 L1 — same declaration name in two files in the same directory (== same module) is a hard error naming the collision and both files."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "a.cxc"
                               "def V:\n    n: Int\n")
          _       (write-file! src-dir "b.cxc"
                               "def V:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L1: declaration `V` appears more than once"
                   (ex-message ex)))
      (is (re-find #"main" (ex-message ex))
          "names the module (root of src-dir → :main)"))))

(deftest f51-l1-multi-file-demo-migrates-cleanly
  (testing "F51 L1 — the migrated multi-file-demo (module headers removed) still compiles + runs. Uses the existing example on disk."
    (let [{:keys [exit stdout]} (compile-dir-and-run "multi-file-demo")]
      (is (zero? exit) "runs successfully after header removal")
      (is (= "42\n" stdout) "prints Counter(seed: 41).bump = 42"))))

;; ---------------------------------------------------------------------------
;; F51 L2 — module dep graph + cycle detection + topological order.
;; ---------------------------------------------------------------------------

(deftest f51-l2-topological-order-basic
  (testing "F51 L2 — a module that imports another user module compiles cleanly with dep-order concatenation (imported module's decls come first)."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          {:keys [status stderr]}
          (build/compile-directory-to-file (.getPath src-dir)
                                           "prog" (.getPath out-dir))]
      (is (zero? status) (str "clang failed: " stderr)))))

(deftest f51-l2-cycle-two-modules-rejected
  (testing "F51 L2 — module `a` imports `b` and `b` imports `a` — cycle rejected with a diagnostic naming both modules in the cycle path."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "a/x.cxc"
                               "from b import Y\n\ndef X:\n    n: Int\n")
          _       (write-file! src-dir "b/y.cxc"
                               "from a import X\n\ndef Y:\n    n: Int\n")
          _       (write-file! src-dir "main.cxc"
                               "from a import X\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected cycle rejection")
      (is (re-find #"F51 L2: module import cycle" (ex-message ex)))
      (is (re-find #"\ba\b" (ex-message ex)) "diagnostic names module `a`")
      (is (re-find #"\bb\b" (ex-message ex)) "diagnostic names module `b`")
      (is (= :cxc-f51-module-cycle (:kind (ex-data ex)))))))

(deftest f51-l2-cycle-three-modules-rejected
  (testing "F51 L2 — a 3-module cycle `a → b → c → a` is rejected and the full cycle path is in the diagnostic."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "a/x.cxc"
                               "from b import B\n\ndef A:\n    n: Int\n")
          _       (write-file! src-dir "b/x.cxc"
                               "from c import C\n\ndef B:\n    n: Int\n")
          _       (write-file! src-dir "c/x.cxc"
                               "from a import A\n\ndef C:\n    n: Int\n")
          _       (write-file! src-dir "main.cxc"
                               "from a import A\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L2: module import cycle" (ex-message ex)))
      (is (re-find #"\ba\b" (ex-message ex)))
      (is (re-find #"\bb\b" (ex-message ex)))
      (is (re-find #"\bc\b" (ex-message ex))))))

(deftest f51-l2-unknown-module-import-rejected
  (testing "F51 L2 — `from unknown_mod import X` in a user file is rejected: the module is not another user module in src-dir and not a prelude module."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "main.cxc"
                               "from unknown_mod import X\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected unknown-module rejection")
      (is (re-find #"unknown module `unknown_mod`" (ex-message ex))
          "diagnostic names the unknown module"))))

(deftest f51-l2-self-import-rejected
  (testing "F51 L2 — a module importing itself (e.g. `from foo import X` inside a file in module `foo`) is trivially wrong; rejected explicitly with a per-module diagnostic."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "foo/a.cxc"
                               "from foo import Y\n\ndef X:\n    n: Int\n")
          _       (write-file! src-dir "foo/b.cxc"
                               "def Y:\n    n: Int\n")
          _       (write-file! src-dir "main.cxc"
                               "from foo import X\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L2: module `foo` imports itself" (ex-message ex)))
      (is (= :cxc-f51-self-import (:kind (ex-data ex)))))))

(deftest f52-l1-import-x-parses-and-adds-dep-edge
  (testing "F52 L1 — `import X` parses to a `:import` top node and contributes an edge to module `X` in the dep graph (same as `from X import ...`). The prog compiles cleanly because `lib` is a known user module and its decls concatenate before the consumer under topological order."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "import lib\n\ndef main:\n    -> running\n        then: print(1)\n")
          {:keys [status stderr]}
          (build/compile-directory-to-file (.getPath src-dir)
                                           "prog" (.getPath out-dir))]
      (is (zero? status) (str "clang failed: " stderr)))))

(deftest f52-l1-import-with-alias-parses
  (testing "F52 L1 — `import X as Y` parses cleanly; the `as` alias name is preserved on the AST node for downstream binding at F51 L3."
    (let [imp (first (cxc.parse/parse "import foo.bar as baz\n"))]
      (is (= :import (:node imp)))
      (is (= "foo.bar" (:from imp)))
      (is (= "baz" (:as imp))))))

(deftest f52-l1-import-unknown-module-rejected
  (testing "F52 L1 — `import unknown_mod` in a user file is rejected at build with the same diagnostic that `from unknown_mod import ...` would produce."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "main.cxc"
                               "import nowhere\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected unknown-module rejection")
      (is (re-find #"unknown module `nowhere`" (ex-message ex))))))

(deftest f52-l2-from-import-with-alias-parses
  (testing "F52 L2 — `from X import a as b, c` produces `:names [{:name \"a\" :as \"b\"} {:name \"c\" :as nil}]`. Aliased and plain entries mix on one import line."
    (let [n (first (cxc.parse/parse "from std.time import Duration as D, Instant\n"))]
      (is (= :from-import (:node n)))
      (is (= "std.time" (:from n)))
      (is (= [{:name "Duration" :as "D"} {:name "Instant" :as nil}]
             (:names n))))))

(deftest f52-l2-from-import-alias-of-nonexistent-name-rejected
  (testing "F52 L2 — `from std.time import Nope as N` rejects because `Nope` is not exported by `std.time`. Alias doesn't change export-check semantics."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "main.cxc"
                               "from std.time import Nope as N\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected unknown-import rejection")
      (is (re-find #"does not export `Nope`" (ex-message ex))))))

(deftest f52-l1-import-self-rejected
  (testing "F52 L1 — a module `import`ing itself (via a file in the same module directory) hits the same self-import rejection as `from foo import ...`."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "foo/a.cxc"
                               "import foo\n\ndef X:\n    n: Int\n")
          _       (write-file! src-dir "foo/b.cxc"
                               "def Y:\n    n: Int\n")
          _       (write-file! src-dir "main.cxc"
                               "from foo import X\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L2: module `foo` imports itself" (ex-message ex)))
      (is (= :cxc-f51-self-import (:kind (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; F51 L3a — user declaration + imported name collision (per-module).
;; ---------------------------------------------------------------------------

(deftest f51-l3a-from-import-name-collides-with-decl-rejected
  (testing "F51 L3a — `from lib import Helper` alongside `def Helper:` in the same module is a hard collision: the imported name and the declared name bind the same local identifier."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef Helper:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected collision rejection")
      (is (re-find #"F51 L3a: `Helper` in module `main`" (ex-message ex)))
      (is (re-find #"both declared" (ex-message ex)))
      (is (re-find #"and imported" (ex-message ex)))
      (is (re-find #"add `as <alias>`" (ex-message ex)))
      (is (= :cxc-f51-import-collides-with-decl (:kind (ex-data ex)))))))

(deftest f51-l3a-aliased-from-import-avoids-collision
  (testing "F51 L3a — `from lib import Helper as H` alongside `def Helper:` does NOT fire the L3a collision diagnostic: the alias makes the import's local binding `H`, disjoint from the declared `Helper`. Downstream flat-namespace concatenation collision is a separate concern that F51 L3b (full resolver) resolves."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper as H\n\ndef Helper:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (not= :cxc-f51-import-collides-with-decl
                (some-> ex ex-data :kind))
          "aliased import must not trip L3a collision"))))

(deftest f51-l3a-aliased-import-target-collides-with-decl-rejected
  (testing "F51 L3a — `from lib import Foo as Helper` alongside `def Helper:` collides: the alias binds to `Helper` locally, and the declaration also uses `Helper`. The alias'd name is the surface for collision, not the source-side name."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Foo:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Foo as Helper\n\ndef Helper:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected collision on the aliased local binding")
      (is (re-find #"F51 L3a: `Helper` in module `main`" (ex-message ex)))
      (is (= :cxc-f51-import-collides-with-decl (:kind (ex-data ex)))))))

(deftest f51-l3a-import-x-collides-with-decl-rejected
  (testing "F51 L3a — `import lib` alongside `def lib:` collides: the whole-module import binds the module name `lib` into the current scope, and the user's declaration also names `lib`. Fix-it: `import lib as L`."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "import lib\n\ndef lib:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected collision on the module-name binding")
      (is (re-find #"F51 L3a: `lib` in module `main`" (ex-message ex)))
      (is (= :cxc-f51-import-collides-with-decl (:kind (ex-data ex)))))))

(deftest f51-l3a-collision-across-files-in-same-module
  (testing "F51 L3a — the collision surface is the module, not the file: `def Helper:` in file a.cxc + `from lib import Helper` in file b.cxc (both under module `foo`) collides on `Helper`. The diagnostic names both files."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "foo/a.cxc"
                               "def Helper:\n    n: Int\n")
          _       (write-file! src-dir "foo/b.cxc"
                               "from lib import Helper\n")
          _       (write-file! src-dir "main.cxc"
                               "from foo import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected cross-file collision within the module")
      (is (re-find #"F51 L3a: `Helper` in module `foo`" (ex-message ex)))
      (is (re-find #"a\.cxc" (ex-message ex))
          "diagnostic names the declaration file")
      (is (re-find #"b\.cxc" (ex-message ex))
          "diagnostic names the import file"))))

;; ---------------------------------------------------------------------------
;; F53 L1 — per-module cxc-manifest.json emission.
;; ---------------------------------------------------------------------------

(defn- manifest-path [^java.io.File out-dir module]
  (java.io.File. out-dir (str module ".cxc-manifest.json")))

(deftest f53-l1-manifest-emitted-for-single-file
  (testing "F53 L1 — a src-dir with a single file at the root produces module `:main` and writes `main.cxc-manifest.json` alongside the compiled binary."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               "def Foo:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          {:keys [status stderr]}
          (build/compile-directory-to-file (.getPath src-dir)
                                           "prog" (.getPath out-dir))
          m-file  (manifest-path out-dir "main")]
      (is (zero? status) (str "clang failed: " stderr))
      (is (.exists m-file) "main.cxc-manifest.json written to out-dir")
      (is (pos? (.length m-file)) "manifest is non-empty"))))

(deftest f53-l1-manifest-includes-all-declarations
  (testing "F53 L1 — every user declaration in the module (records, atoms, enums) appears in the manifest's exports array with its kind."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               (str "def Widget:\n    n: Int\n\n"
                                    "def Kind:\n    case red\n    case blue\n\n"
                                    "def main:\n    -> running\n        then: print(1)\n"))
          {:keys [status]}
          (build/compile-directory-to-file (.getPath src-dir)
                                           "prog" (.getPath out-dir))
          m-json  (slurp (manifest-path out-dir "main"))]
      (is (zero? status))
      (is (re-find #"\"name\": \"Widget\"" m-json) "Widget export present")
      (is (re-find #"\"kind\": \"record\"" m-json) "record kind serialised")
      (is (re-find #"\"name\": \"Kind\"" m-json) "Kind export present")
      (is (re-find #"\"kind\": \"enum\"" m-json) "enum kind serialised")
      (is (re-find #"\"name\": \"main\"" m-json) "main atom export present")
      (is (re-find #"\"kind\": \"def-atom\"" m-json) "def-atom kind serialised"))))

(deftest f53-l1-manifest-shape-matches-schema
  (testing "F53 L1 — the manifest carries the top-level shape keys the v1 schema requires: $schema, module, compiler-version, compiled-at, source-hash, source-files, exports, imports."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               "def main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          m-json  (slurp (manifest-path out-dir "main"))]
      (is (re-find #"\"\$schema\": \"cxc-manifest/v1\"" m-json))
      (is (re-find #"\"module\": \"main\"" m-json))
      (is (re-find #"\"compiler-version\":" m-json))
      (is (re-find #"\"compiled-at\":" m-json))
      (is (re-find #"\"source-hash\":" m-json))
      (is (re-find #"\"source-files\":" m-json))
      (is (re-find #"\"exports\":" m-json))
      (is (re-find #"\"imports\":" m-json)))))

(deftest f53-l1-manifest-emitted-per-module-in-multi-file
  (testing "F53 L1 — in multi-file mode with modules `lib` and `main`, one manifest is written per user module and each manifest lists only that module's declarations."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          lib-json  (slurp (manifest-path out-dir "lib"))
          main-json (slurp (manifest-path out-dir "main"))
          main-exports (first (clojure.string/split main-json #"\"imports\":"))]
      (is (re-find #"\"module\": \"lib\"" lib-json) "lib manifest names lib")
      (is (re-find #"\"name\": \"Helper\"" lib-json) "lib manifest lists Helper")
      (is (not (re-find #"\"name\": \"Helper\"" main-exports))
          "main manifest's exports do NOT list Helper (that's lib's export)")
      (is (re-find #"\"module\": \"main\"" main-json) "main manifest names main")
      (is (re-find #"\"name\": \"main\"" main-exports) "main manifest exports the main atom")
      (is (re-find #"\"kind\": \"from-import\"" main-json)
          "main manifest records its from-import of Helper"))))

(deftest f53-l1-manifest-source-hash-changes-on-source-change
  (testing "F53 L1 — the manifest's source-hash reflects the module's source contents; two different sources produce two different hashes (sha256)."
    (let [compile-once
          (fn [src-content]
            (let [src-dir (tmp-dir)
                  out-dir (tmp-dir)
                  _       (write-file! src-dir "app.cxc" src-content)
                  _       (build/compile-directory-to-file (.getPath src-dir)
                                                            "prog" (.getPath out-dir))
                  json    (slurp (manifest-path out-dir "main"))]
              (second (re-find #"\"source-hash\": \"([^\"]+)\"" json))))
          h1 (compile-once
              "def Foo:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          h2 (compile-once
              "def Foo:\n    n: Int\n    m: Int\n\ndef main:\n    -> running\n        then: print(1)\n")]
      (is (some? h1) "hash extractable from first manifest")
      (is (some? h2) "hash extractable from second manifest")
      (is (not= h1 h2) "different sources → different source-hash"))))

;; ---------------------------------------------------------------------------
;; F53 L2 — program-wide cxc-deps.json emission.
;; ---------------------------------------------------------------------------

(defn- deps-path [^java.io.File out-dir]
  (java.io.File. out-dir "cxc-deps.json"))

(deftest f53-l2-dep-graph-basic
  (testing "F53 L2 — a user module `app` importing user module `lib` produces `cxc-deps.json` at out-dir root, with `lib` declared and `app` listing lib in its depends-on."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          deps-file (deps-path out-dir)
          deps-json (slurp deps-file)]
      (is (.exists deps-file) "cxc-deps.json written at out-dir root")
      (is (re-find #"\"\$schema\": \"cxc-deps/v1\"" deps-json))
      (is (re-find #"\"topological-order\":" deps-json))
      (is (re-find #"\"modules\":" deps-json))
      (is (re-find #"\"lib\":" deps-json) "lib module present in graph")
      (is (re-find #"\"main\":" deps-json) "main module present in graph")
      (is (re-find #"\"depends-on\":\s*\[\s*\"lib\"\s*\]" deps-json)
          "main's depends-on lists lib"))))

(deftest f53-l2-dep-graph-includes-prelude-deps
  (testing "F53 L2 — when a user module imports a prelude module (e.g. std.time), the prelude module appears in the graph with reverse depended-by pointing at the user."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               "from std.time import Duration\n\ndef main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          deps-json (slurp (deps-path out-dir))]
      (is (re-find #"\"std\.time\":" deps-json) "prelude module appears")
      (is (re-find #"\"main\":" deps-json))
      (is (re-find #"\"depends-on\":\s*\[\s*\"std\.time\"\s*\]" deps-json)
          "main depends on std.time"))))

(deftest f53-l2-dep-graph-reverse-edges-correct
  (testing "F53 L2 — the depended-by list is the correct reverse of depends-on: when app imports lib, lib.depended-by contains app."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          deps-json (slurp (deps-path out-dir))
          ;; Extract lib's block so we can assert on its depended-by
          lib-block (re-find #"\"lib\":\s*\{[^}]*\}" deps-json)]
      (is (some? lib-block) "lib module block present")
      (is (re-find #"\"depended-by\":\s*\[\s*\"main\"\s*\]" lib-block)
          "lib is depended-by main"))))

;; ---------------------------------------------------------------------------
;; F52 L3 — source overrides (`:source-overrides {module → path}`).
;; ---------------------------------------------------------------------------

(deftest f52-l3-source-override-replaces-module-contents
  (testing "F52 L3 — `:source-overrides {\"lib\" path}` substitutes the on-disk source for module `lib` with the contents of `path`. The original file's path + derived module stay intact; only the source string swaps. Verifies the swap took effect by having the on-disk `lib` fail-nyi (references an unknown ident) while the override provides a real declaration — a successful compile proves the override won."
    (let [src-dir      (tmp-dir)
          out-dir      (tmp-dir)
          override-dir (tmp-dir)
          _            (write-file! src-dir "lib/util.cxc"
                                    "def Helper:\n    x: PRODUCTION_MISSING_TYPE\n")
          override-f   (write-file! override-dir "override.cxc"
                                    "def Helper:\n    x: Int\n")
          _            (write-file! src-dir "app.cxc"
                                    "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          result       (build/compile-directory-to-file
                        (.getPath src-dir) "prog" (.getPath out-dir)
                        {:source-overrides {"lib" (.getPath override-f)}})]
      (is (zero? (:status result))
          (str "override should let compile succeed even though on-disk lib is broken; stderr: "
               (:stderr result))))))

(deftest f52-l3-source-override-unknown-module-warns
  (testing "F52 L3 — an override targeting a module not discovered under `src-dir` is silently ignored with a stderr warning. Prevents footguns from typoed override keys silently succeeding while producing the on-disk source."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          err     (java.io.StringWriter.)
          _       (write-file! src-dir "app.cxc"
                               "def main:\n    -> running\n        then: print(1)\n")
          result  (binding [*err* err]
                    (build/compile-directory-to-file
                     (.getPath src-dir) "prog" (.getPath out-dir)
                     {:source-overrides {"nonexistent" "/tmp/does-not-matter.cxc"}}))]
      (is (zero? (:status result)) "compile still succeeds despite unused override")
      (is (re-find #"source override for `nonexistent`" (str err))
          "stderr warning fires for the unmatched override")
      (is (re-find #"F52 L3" (str err))
          "diagnostic cites the responsible landing"))))

(deftest f52-l3-source-override-defaults-to-none-when-opts-omit
  (testing "F52 L3 — opts without :source-overrides is a no-op (default `{}`); on-disk sources are used as before. Regression guard for the 3-arity + 4-arity dispatch: existing callers pass no opts (line 613 pattern) or opts without the key (line 615 pattern)."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               "def main:\n    -> running\n        then: print(9)\n")
          result  (build/compile-directory-to-file
                   (.getPath src-dir) "prog" (.getPath out-dir))
          {:keys [exit stdout]} (build/run-binary (:bin-path result))]
      (is (zero? (:status result)))
      (is (zero? exit))
      (is (= "9\n" stdout) "on-disk source used when no override provided"))))
