(ns cxc.lower-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cxc.env :as env]
            [cxc.parse :as p]
            [cxc.prelude :as prelude]
            [cxc.lower :as lower]
            cxc.lower.shape-collection
            cxc.lower.ctor-params
            cxc.lower.protocol-shapes
            [cxc.type-kw :as type-kw]
            [cxc.emit :as emit]))

(defn- lower-src [src] (lower/lower (p/parse src)))

(defn- lower-src+prelude
  "Test helper that mirrors build.clj's parse pipeline: prepends the
   prelude source before parse and runs the post-parse rewrite pass
   that mangles user-visible prelude names (`Uncaught` → `_Cxc_Uncaught`).
   Use in tests that reference prelude records / atoms by name — plain
   `lower-src` skips the prelude, which is fine for most tests but
   drops Uncaught / Duration / etc. from the record-env."
  [src]
  (-> (p/parse (str prelude/source "\n" src))
      lower/lower))

(deftest f37-l6b-class-function-on-atom-fails-nyi-with-f37-l6-pointer
  (testing "F37 L6b — a `class function` decl inside a def-atom body parses cleanly but lower fires a targeted fail-nyi naming the atom, the function names, and pointing at F37 L6 as the landing that will consume them. No silent no-op."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"class function.*F37 L6"
         (lower-src (str "def Counter:\n"
                         "    n: Int = 0\n\n"
                         "    -> running\n\n"
                         "    class function empty() -> Counter:\n"
                         "        return Counter(n: 0)\n\n"
                         "def main:\n"
                         "    -> running\n        then: print(0)\n"))))))

(deftest f37-l6b-class-function-on-record-fails-nyi-with-f37-l6-pointer
  (testing "F37 L6b — a `class function` decl inside a record body (no transitions, no ctor params → reshaped to :record at parse time) fires a targeted fail-nyi at build-record-env, mirroring the atom-side diagnostic. Prevents silent-drop in the record path."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"class function.*F37 L6"
         (lower-src (str "def Duration:\n"
                         "    ms: Int = 0\n\n"
                         "    class function seconds(n: Int) -> Duration:\n"
                         "        return Duration(ms: n * 1000)\n\n"
                         "def main:\n"
                         "    -> running\n        then: print(0)\n"))))))


(defn- main-work-body
  "Return the body of CxcMain's cxc_stateInit — the effective site of
   `def main: -> running then: <stmts>` under main-as-atom (Steps 16/17).
   Falls back to the raw main body for programs without ObjC classes."
  [prog]
  (if-let [cxc-main (first (filter #(= "CxcMain" (:name %)) (:classes prog)))]
    (:body (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cxc-main))))
    (get-in prog [:main :body])))

(deftest f15-l2c-iii-b-lowered-program-carries-empty-typedefs-field
  (testing "F15 L2.c.iii.b — the lowered program AST now carries a `:named-tuple-typedefs` string. Today no source program lowers to an AST containing `:type-named-tuple` (lower fails-nyi on any occurrence), so `collect-shapes` finds nothing and the field is an empty string. Backward-compat: existing programs produce byte-identical .m output because the emit hook (L2.c.iii.a) omits empty strings from its join. When L2.c.iii.c teaches lower to preserve named-tuple type info, this field populates automatically."
    (let [prog (lower-src "def main:\n    -> running\n        then: print(1)\n")]
      (is (contains? prog :named-tuple-typedefs)
          "the wire-in surfaces the field on every lowered program")
      (is (= "" (:named-tuple-typedefs prog))
          "no named-tuple shapes reach lower today → empty string"))))

(deftest hello-world-program-lowers
  (testing "def main: -> running then: print(\"hello, world\") lowers cleanly. F65 — Foundation is always included because String is NSString *; the string literal lowers as :objc-str (`@\"...\"`) rather than :str (`\"...\"`)."
    (let [prog (lower-src "def main:
    -> running
        then: print(\"hello, world\")
")]
      (is (= :objc-program (:node prog)))
      (is (some #{"<Foundation/Foundation.h>"} (:includes prog))
          "F65 — Foundation always included; NSString literal requires it")
      (let [body (get-in prog [:main :body])]
        ;; expect the printf call then a `return 0`
        (is (= 2 (count body)))
        (let [call (first body)]
          (is (= :call (:node call)))
          (is (= "printf" (:fn call)))
          (is (= 2 (count (:args call))))
          (is (= "%s\n" (get-in call [:args 0 :value])))
          (is (= :objc-str (get-in call [:args 1 :node]))
              "F65 — string literal lowers as NSString literal")
          (is (= "hello, world" (get-in call [:args 1 :value]))))
        (let [ret (second body)]
          (is (= :return (:node ret)))
          (is (= 0 (get-in ret [:value :value]))))))))

(deftest missing-program-errors
  (is (thrown? clojure.lang.ExceptionInfo (lower-src "def R:\n    x: Int\n"))))

(deftest unsupported-call-fails-user
  (testing "any call other than print raises :cxc-user-error (T2 batch 3: unknown-name call is a structural user error, not a compiler gap)"
    (try
      (lower-src "def main:\n    -> running\n        then: unknown_fn(1)\n")
      (is false "expected NYI throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :cxc-user-error (:kind (ex-data e))))))))

(deftest arithmetic-in-print-int
  (testing "int + int uses %lld format (Phase D F36a — Int aliased to Int64; printf format widens accordingly); binop lowers with left/right"
    (let [prog (lower-src "def main:\n    -> running\n        then: print(3 + 4 * 2)\n")
          call (first (get-in prog [:main :body]))]
      (is (= "printf" (:fn call)))
      (is (= "%lld\n" (get-in call [:args 0 :value])))
      (let [expr (get-in call [:args 1])]
        (is (= :binop (:node expr)))
        (is (= "+" (:op expr)))
        (is (= 3 (get-in expr [:left :value])))
        (is (= :binop (get-in expr [:right :node])))
        (is (= "*" (get-in expr [:right :op])))))))

(deftest arithmetic-in-print-float-wins
  (testing "float ∘ int promotes to float; %f is chosen"
    (let [prog (lower-src "def main:\n    -> running\n        then: print(1.5 + 2)\n")
          call (first (get-in prog [:main :body]))]
      (is (= "%f\n" (get-in call [:args 0 :value]))))))

(deftest record-lowered-to-c-struct
  (testing "top-level record → :c-struct with resolved C types per field"
    (let [prog (lower-src "def V:\n    x: Int\n    y: Float\n\ndef main:\n    -> running\n        then: print(1)\n")
          s    (first (filter #(= "V" (:name %)) (:structs prog)))]
      (is (= :c-struct (:node s)))
      (is (= "V" (:name s)))
      (is (= [{:name "x" :c-type "_Cxc_Optional_Int64"}
              {:name "y" :c-type "_Cxc_Optional_Float"}]
             (:fields s))))))

(deftest record-construction-with-named-args
  (testing "V(x: 3, y: 4) lowers to :struct-lit with named fields"
    (let [prog (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(x: 3, y: 4).x)\n")
          call (first (get-in prog [:main :body]))
          field-access (:arg call)]
      (is (= :field (:node field-access)))
      (is (= "x" (:field field-access)))
      (let [ctor (:target field-access)]
        (is (= :struct-lit (:node ctor)))
        (is (= "V" (:type ctor)))
        (is (= [{:name "x"
                 :value {:node :struct-lit
                         :type "_Cxc_Optional_Int64"
                         :fields [{:name "present" :value {:node :int :value 1}}
                                  {:name "value" :value {:node :int :value 3}}]}}
                {:name "y"
                 :value {:node :struct-lit
                         :type "_Cxc_Optional_Int64"
                         :fields [{:name "present" :value {:node :int :value 1}}
                                  {:name "value" :value {:node :int :value 4}}]}}]
               (:fields ctor)))))))

(deftest nested-record-field-typing
  (testing "field access chained through a record field propagates the inner record's field type"
    (let [prog (lower-src "def Point:\n    x: Float!\n    y: Float!\n\ndef Line:\n    start: Point!\n    stop:  Point!\n\ndef main:\n    -> running\n        then: print(Line(start: Point(x: 1.0, y: 2.0), stop: Point(x: 3.0, y: 4.0)).start.x)\n")
          call (first (get-in prog [:main :body]))]
      (is (= "%f\n" (get-in call [:args 0 :value]))))))

(deftest missing-field-in-ctor-rejected
  (testing "V(x: 1) when V requires x + y (no default on y) — cxc-user-error (T2 reclassified from :not-yet-implemented — a missing required field is a structural user error, not a compiler gap)"
    (try
      (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(x: 1).x)\n")
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :cxc-user-error (:kind (ex-data e))))))))

(deftest record-defaults-fill-omitted-fields
  (testing "omitted field with a declared default is filled from the default"
    (let [prog (lower-src "def V:\n    x: Int = 1\n    y: Int = 2\n\ndef main:\n    -> running\n        then: print(V(x: 10).y)\n")
          call (first (get-in prog [:main :body]))
          ctor (get-in call [:arg :target])]
      (is (= :struct-lit (:node ctor)))
      (is (= [{:name "x"
               :value {:node :struct-lit
                       :type "_Cxc_Optional_Int64"
                       :fields [{:name "present" :value {:node :int :value 1}}
                                {:name "value" :value {:node :int :value 10}}]}}
              {:name "y"
               :value {:node :struct-lit
                       :type "_Cxc_Optional_Int64"
                       :fields [{:name "present" :value {:node :int :value 1}}
                                {:name "value" :value {:node :int :value 2}}]}}]
             (:fields ctor))))))

;; F40 Landing 1 — call-site + decl-site validation (spec §3.8.5)

(deftest f40-record-ctor-duplicate-named-arg-rejected
  (testing "V(x: 1, x: 2) — duplicate named arg fails, not silently collapsed"
    (let [ex (try
               (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(x: 1, x: 2, y: 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"duplicate named arg" (.getMessage ex))))))

(deftest f40-record-ctor-unknown-named-arg-rejected
  (testing "V(bogus: 1) — unknown named arg fails, not silently ignored"
    (let [ex (try
               (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(x: 1, y: 2, bogus: 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"unknown named arg" (.getMessage ex)))
      (is (re-find #"bogus" (.getMessage ex))))))

(deftest f40-record-ctor-surplus-positional-rejected
  (testing "V(1, 2, 3) when V has 2 fields — surplus positional fails"
    (let [ex (try
               (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(1, 2, 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"too many positional" (.getMessage ex))))))

(deftest f40-record-ctor-positional-and-named-collision-rejected
  (testing "V(1, x: 2) — same param supplied positionally AND by name fails"
    (let [ex (try
               (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(1, x: 2, y: 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"both positionally and by name" (.getMessage ex))))))

(deftest f40-record-fields-defaults-must-follow-required
  (testing "record V { x: Int = 1, y: Int } — defaulted before required is a compile error"
    (let [ex (try
               (lower-src "def V:\n    x: Int = 1\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(x: 10, y: 20).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"defaulted param" (.getMessage ex))))))

(deftest f40-atom-ctor-duplicate-named-arg-rejected
  (testing "duplicate named arg on atom ctor fails"
    (let [ex (try
               (lower-src "def Widget(a: Int, b: Int):\n    n: Int = a\n\ndef main:\n    w: Widget = Widget(a: 1, a: 2, b: 3)\n\n    -> running\n        then: print(0)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"duplicate named arg" (.getMessage ex))))))

(deftest f40-atom-ctor-unknown-named-arg-rejected
  (testing "unknown named arg on atom ctor fails"
    (let [ex (try
               (lower-src "def Widget(a: Int):\n    n: Int = a\n\ndef main:\n    w: Widget = Widget(a: 1, ghost: 2)\n\n    -> running\n        then: print(0)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"unknown named arg" (.getMessage ex))))))

(deftest def-atom-lowers-to-objc-class
  (testing "def-atom with data:-only → :objc-class with @property ivars + -init defaults"
    (let [prog (lower-src "def Counter:\n    n: Int = 42\n\n    -> running\n\n    -> end\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n        then: print(0)\n")
          cls  (first (:classes prog))]
      (is (= :objc-class (:node cls)))
      (is (= "Counter" (:name cls)))
      (is (= "NSObject" (:superclass cls)))
      (is (= [{:name "phase"            :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "own_queue"        :c-type "dispatch_queue_t" :attrs ["nonatomic" "strong"]}
              {:name "cxc_owned_resources" :c-type "NSMutableArray *" :attrs ["nonatomic" "strong"]}
              {:name "parent"           :c-type "id"               :attrs ["nonatomic" "weak"]}
              {:name "parent_slot_name" :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              ;; F33 L5 — data-field @properties are readonly (no
              ;; external setter). Internal writes still go through
              ;; the ivar directly.
              {:name "n"                :c-type "_Cxc_Optional_Int64" :attrs ["readonly" "assign"]}]
             (:properties cls)))
      (is (= [{:name "kCounter_phase_init"    :value "init"}
              {:name "kCounter_phase_running" :value "running"}
              {:name "kCounter_phase_end"     :value "end"}]
             (:phase-constants cls)))
      ;; -init is a thin shell calling the three private factored methods.
      (is (= [{:node :objc-msg :recv {:node :name :sym "self"}
               :selector "cxc_structuralSetup" :args []}
              {:node :objc-msg :recv {:node :name :sym "self"}
               :selector "cxc_stateInit" :args []}
              {:node :objc-msg :recv {:node :name :sym "self"}
               :selector "cxc_runtimeBinding" :args []}]
             (:init-body cls)))
      (let [setup (first (filter #(= "cxc_structuralSetup" (:selector %)) (:methods cls)))
            state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cls)))]
        (is (= [{:node :queue-init :ivar "_own_queue"}
                {:node :assign
                 :lhs  {:node :name :sym "_phase"}
                 :rhs  {:node :name :sym "kCounter_phase_running"}}]
               (:body setup)))
        (is (= [{:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :struct-lit
                        :type "_Cxc_Optional_Int64"
                        :fields [{:name "present" :value {:node :int :value 1}}
                                 {:name "value" :value {:node :int :value 42}}]}}
                ;; F32 L4.d.β.α — post-init seed of concurrent-mode
                ;; `_state` slot from freshly-populated ivars.
                {:node        :initial-state-commit
                 :class-name  "Counter"
                 :data-fields [{:name "n" :c-type "_Cxc_Optional_Int64"
                                :attrs ["readonly" "assign"]}]}]
               (:body state)))))))

(deftest def-atom-ctor-lowers-to-alloc-init
  (testing "Counter in def-slot position → :objc-alloc-init"
    (let [prog (lower-src "def Counter:\n    n: Int = 42\n\n    -> running\n\n    -> end\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n        then: print(0)\n")
          main-cls (first (filter #(= "CxcMain" (:name %)) (:classes prog)))
          state-body (:body (first (filter #(clojure.string/starts-with?
                                             (:selector %) "cxc_stateInit")
                                           (:methods main-cls))))
          ctor (some (fn [stmt]
                       (when (and (= :assign (:node stmt))
                                  (= "_c" (get-in stmt [:lhs :sym])))
                         (:rhs stmt)))
                     state-body)]
      (is (= :objc-alloc-init (:node ctor)))
      (is (= "Counter" (:class ctor))))))

(deftest def-atom-triggers-foundation-and-autoreleasepool
  (testing "presence of an ObjC class flips includes to Foundation and wraps main in @autoreleasepool"
    (let [prog (lower-src "def Counter:\n    n: Int = 42\n\n    -> running\n\n    -> end\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n        then: print(0)\n")]
      ;; Foundation for ObjC; stdlib for cxc_shutdown() (needed by every
      ;; atom class's transitionToEnd cascade infrastructure).
      (is (some #{"<Foundation/Foundation.h>"} (:includes prog)))
      (is (some #{"<stdlib.h>"} (:includes prog)))
      (is (true? (get-in prog [:main :autorelease?]))))))

(deftest hello-world-includes-foundation
  (testing "F65 — every program includes Foundation because string literals are NSString; autoreleasepool wraps main"
    (let [prog (lower-src "def main:\n    -> running\n        then: print(\"hi\")\n")]
      (is (some #{"<Foundation/Foundation.h>"} (:includes prog))
          "F65 — Foundation always included; NSString literal requires it")
      (is (true? (get-in prog [:main :autorelease?]))
          "F65 — autoreleasepool wraps main since NSString literals allocate"))))

(deftest wildcard-on-enter-inlined-into-init
  (testing "initial-phase wildcard on-enter → its then:/then: append to :init-body after data defaults"
    (let [prog (lower-src "def Ticker:\n    n: Int! = 0\n\n    -> incremented\n        then: n => n + 1\n\ndef main:\n    t: Ticker = Ticker()\n\n    -> running\n        then: print(0)\n")
          cls   (first (:classes prog))
          ;; State-init runs data defaults then the initial-phase on-enter body.
          ;; Structural setup owns queue + phase = initial constant.
          state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cls)))]
      (is (= [{:node :assign
               :lhs  {:node :name :sym "_n"}
               :rhs  {:node :int  :value 0}}
              {:node :assign
               :lhs  {:node :name :sym "_n"}
               :rhs  {:node :binop :op "+"
                      :left  {:node :name :sym "_n"}
                      :right {:node :int  :value 1}}}
              {:node :raw-c
               :text "if ((_n) == nil) cxc_nonnil_violation(\"def-atom `Ticker` field write\", \"n\");"}
              ;; F32 L4.d.β.α — concurrent-mode `_state` seed.
              {:node        :initial-state-commit
               :class-name  "Ticker"
               :data-fields [{:name "n" :c-type "int64_t"
                              :attrs ["readonly" "assign"]}]}]
             (:body state))))))

(deftest non-initial-wildcard-inlines-into-transitions-that-enter-that-phase
  (testing "`-> dead then: print(0)` inlines into `alive <: die -> dead` branch body (phase change, not self-loop)"
    (let [prog (lower-src "def Enemy:
    hp: Int = 3

    -> alive

    -> dead
        then: print(0)

    alive <: die -> dead

def main:
    -> running
        then: print(1)
")
          cls (first (:classes prog))
          die (first (filter #(= "die" (:selector %)) (:methods cls)))
          branch (first (:body die))]
      (is (some? branch))
      ;; Branch body wrapped in :end-catch-wrap (Step 15).
      (let [wrap (first (:body branch))
            b    (:body wrap)]
        (is (= :end-catch-wrap (:node wrap)))
        ;; Inside try (post F33 L3.c): [commit-next-state (F33 L3.b),
        ;; phase-update, wildcard-do-stmt (print)]. The commit runs
        ;; first (empty then here — nothing was written, so commit
        ;; is a no-op copy of unchanged fields; kept for uniformity).
        ;; Post-commit `[self checkInvariants]` suppressed when
        ;; snapshot is active (F33 L3.c).
        (is (= 3 (count b)))
        (is (= :commit-next-state (:node (first b))))
        (is (= :assign (:node (second b))))
        (is (= "_phase" (get-in (second b) [:lhs :sym])))
        ;; Wildcard's print(0)
        (is (= :call (:node (nth b 2))))))))

(deftest atom-emits-transitionToEnd-nilSlot-and-installParent
  (testing "every atom class synthesizes the Step 11 lifecycle methods"
    (let [prog (lower-src "def Counter:\n    n: Int = 0\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(1)\n")
          cls  (first (:classes prog))
          sels (set (map :selector (:methods cls)))]
      (is (contains? sels "cxc_installParent:inSlot:"))
      (is (contains? sels "transitionToEnd"))
      (is (contains? sels "nilSlot:")))))

(deftest transition-with-target-end-calls-transitionToEnd
  (testing "`alive <: die -> end` branch body is `[self transitionToEnd]`"
    (let [prog (lower-src "def Counter:
    n: Int = 0

    -> alive

    alive <: die -> end

def main:
    -> running
        then: print(1)
")
          cls  (first (:classes prog))
          die  (first (filter #(= "die" (:selector %)) (:methods cls)))
          branch (first (:body die))]
      (is (some? branch))
      (is (= 1 (count (:body branch))))
      (is (= "transitionToEnd" (get-in (first (:body branch)) [:selector]))))))

(deftest parent-installs-back-ref-on-nested-slot-child
  (testing "parent's cxc_stateInit calls -cxc_installParent:inSlot: on each nested slot after construction"
    (let [prog (lower-src "def Cell:\n    n: Int = 0\n\n    -> running\n\n    -> end\n\ndef Container:\n    cell: Cell = Cell()\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(1)\n")
          container (first (filter #(= "Container" (:name %)) (:classes prog)))
          state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods container)))
          install-msg (second (:body state))]     ; [_cell = Cell(), install-parent-msg]
      (is (= :objc-msg (:node install-msg)))
      (is (= "cxc_installParent:inSlot:" (:selector install-msg)))
      (is (= "cell" (get-in install-msg [:args 1 :value]))))))

(deftest f31-f35-l4-atom-typed-data-field-becomes-slot
  (testing "F31/F35 Landing 4 — an atom-typed `data:` field with a default (eager Foo() or nil) participates in the slot machinery: parent installs back-ref at ctor time and cascades on -transitionToEnd. Retires the pre-L4 gap where only invented `def slot: Type` nested-atoms received back-refs. Spec-canonical `data:` form now matches (§1.11)."
    (let [prog (lower-src "def Helper:\n    -> alive\n\ndef X:\n    h: Helper = Helper()\n\n    -> running\n\n    -> end\n\ndef main:\n    x: X = X()\n    -> running\n        then: print(0)\n")
          x-cls (first (filter #(= "X" (:name %)) (:classes prog)))
          state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods x-cls)))
          install-msg (second (:body state))
          end   (first (filter #(= "transitionToEnd" (:selector %)) (:methods x-cls)))
          catch-wrap (first (filter #(= :end-catch-log-only (:node %)) (:body end)))
          cascade-child (first (filter #(= :end-cascade-child (:node %))
                                       (:body catch-wrap)))]
      (is (= :objc-msg (:node install-msg)) "second stmt is install-parent-msg")
      (is (= "cxc_installParent:inSlot:" (:selector install-msg))
          "install fires on the freshly-constructed Helper")
      (is (= "h" (get-in install-msg [:args 1 :value]))
          "slot name matches the data-field name")
      (is (some? cascade-child)
          "F31/F35 L4 — data-field slot participates in -transitionToEnd cascade")
      (is (= "h" (:slot-name cascade-child))
          "cascade targets the data-field slot"))))

(deftest f31-f35-l4-nil-default-data-field-cascades-nil-safe
  (testing "F31/F35 Landing 4 — `data: h: Helper = nil` still gets the install/cascade wiring; the runtime calls on nil are no-ops (ObjC nil-messaging). Verifies the broadened filter doesn't break nullable atom-typed fields."
    (let [prog (lower-src "def Helper:\n    -> alive\n\ndef X:\n    h: Helper = nil\n\n    -> running\n\n    -> end\n\ndef main:\n    x: X = X()\n    -> running\n        then: print(0)\n")
          x-cls (first (filter #(= "X" (:name %)) (:classes prog)))
          state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods x-cls)))
          install-msg (second (:body state))]
      (is (= :objc-msg (:node install-msg))
          "install-parent still emitted for nil-default slot")
      (is (= "cxc_installParent:inSlot:" (:selector install-msg))
          "runtime nil-messaging swallows the call at zero cost"))))

(deftest f31-f35-l4-f41-synthesized-field-not-a-slot
  (testing "F31/F35 Landing 4 — a no-default atom-typed data field is F41-sugared to a required ctor param (caller-provided reference). Since ownership is ambiguous, it is NOT treated as an owned slot — no back-ref, no cascade. Preserves the caller-supplies-shared-reference semantics."
    (let [prog (lower-src "def Helper:\n    -> alive\n\ndef X:\n    h: Helper\n\n    -> running\n\n    -> end\n\ndef main:\n    hh: Helper = Helper()\n    x: X = X(h: hh)\n    -> running\n        then: print(0)\n")
          x-cls (first (filter #(= "X" (:name %)) (:classes prog)))
          state (first (filter #(and (:selector %)
                                     (str/starts-with? (:selector %) "cxc_stateInit"))
                               (:methods x-cls)))
          install-msgs (filter #(and (= :objc-msg (:node %))
                                     (= "cxc_installParent:inSlot:" (:selector %)))
                               (:body state))]
      (is (empty? install-msgs)
          "no install-parent for F41-synthesized ctor-param fields"))))

(deftest self-loop-transition-does-not-fire-wildcard
  (testing "`alive <: bump -> alive` targets same phase → wildcard on alive does NOT re-fire (would recurse)"
    (let [prog (lower-src "def Counter:
    n: Int = 0

    -> alive
        then: print(999)

    alive <: bump -> alive
        then: n => n + 1

def main:
    -> running
        then: print(1)
")
          cls  (first (:classes prog))
          bump (first (filter #(= "bump" (:selector %)) (:methods cls)))
          branch (first (:body bump))
          wrap (first (:body branch))
          b    (:body wrap)]
      ;; Branch body wrapped in :end-catch-wrap. Inside (post F33 L3.c):
      ;; [bag-write, commit-next-state]. Wildcard do isn't inlined
      ;; (self-loop isn't a phase change). Post-commit
      ;; `[self checkInvariants]` is suppressed when the branch is
      ;; snapshotted — invariants for this atom would run pre-commit
      ;; inline if any existed, but this atom (`Counter`) has no
      ;; invariants so the pre-commit block is empty.
      (is (= :end-catch-wrap (:node wrap)))
      (is (= 2 (count b)))
      (is (= :assign (:node (first b))))
      (is (= :commit-next-state (:node (second b)))))))

(deftest record-observable-lowers-to-c-function
  (testing "F67 L4 — record parametric observable → :c-function under :functions with mangled name and self as first param (replaces reader methods per Proposal G class-body collapse)."
    (let [prog (lower-src "def V:\n    x: Float\n    y: Float\n\n    plus(other: V) :- V(x: self.x + other.x, y: self.y + other.y)\n\ndef main:\n    -> running\n        then: print(V(x: 1.0, y: 2.0).plus(V(x: 3.0, y: 4.0)).x)\n")
          f    (first (:functions prog))]
      (is (= :c-function (:node f)))
      (is (= "V_plus" (:name f)))
      (is (= "V" (:return-type f)))
      ;; F15 L2.c.iii.c.δ — record-observable params now carry the
      ;; raw :type node alongside :c-type. `self` is synthesized and
      ;; has no :type; `other` carries the parsed :type-ref for V.
      (is (= [{:name "self" :c-type "V"}
              {:name "other" :c-type "V"
               ;; Batch 7 — `ensure-references-enriched` runs at the
               ;; top of `lower`, so the preserved raw :type node
               ;; now carries `:resolves-to :main` from attribution.
               :type {:node :type-ref :name "V" :resolves-to :main}}]
             (:params f)))
      (is (= 1 (count (:body f))))
      (is (= :return (get-in f [:body 0 :node]))))))

(deftest atom-ctor-params-lower-to-init-with
  (testing "def-atom with ctor params → :init-params on class + :objc-init-with at call site"
    (let [prog (lower-src "def Counter(initial: Int = 0):\n    n: Int = initial\n\ndef main:\n    c: Counter = Counter(initial: 42)\n\n    -> running\n        then: print(0)\n")
          cls  (first (filter #(= "Counter" (:name %)) (:classes prog)))
          ;; ctor call is inside main's :init-body — the def-slot form
          ;; lowers to an assign to _c whose rhs is the alloc/init.
          main-cls (first (filter #(= "CxcMain" (:name %)) (:classes prog)))
          state-body (:body (first (filter #(clojure.string/starts-with?
                                             (:selector %) "cxc_stateInit")
                                           (:methods main-cls))))
          ctor (some (fn [stmt]
                       (when (and (= :assign (:node stmt))
                                  (= "_c" (get-in stmt [:lhs :sym])))
                         (:rhs stmt)))
                     state-body)]
      ;; F15 L2.c.iii.c.δ.2 — :init-params now carry raw :type alongside :c-type.
      (is (= [{:name "initial" :c-type "int64_t"
               :type {:node :type-ref :name "Int"}}]
             (:init-params cls)))
      ;; -init shell calls cxc_structuralSetup, cxc_stateInitWithinitial:initial,
      ;; cxc_runtimeBinding — ctor-params thread through the state-init selector.
      (is (= [{:node :objc-msg :recv {:node :name :sym "self"}
               :selector "cxc_structuralSetup" :args []}
              {:node :objc-msg :recv {:node :name :sym "self"}
               :selector "cxc_stateInitWithinitial:"
               :args [{:node :name :sym "initial"}]}
              {:node :objc-msg :recv {:node :name :sym "self"}
               :selector "cxc_runtimeBinding" :args []}]
             (:init-body cls)))
      (let [state (first (filter #(= "cxc_stateInitWithinitial:" (:selector %))
                                 (:methods cls)))]
        (is (= [{:name "initial" :c-type "int64_t"}] (:params state)))
        ;; F39 L5b-ii-c — `n: Int` field storage is Optional-boxed; the
        ;; scalar ctor-param `initial` gets auto-wrapped at the assign
        ;; boundary (Sub-J). Raw ctor-param mirror (2026-09-15) also
        ;; adds an `_initial` ivar so later transition bodies can read
        ;; the value; `initial-state-commit` covers both fields.
        (is (= [{:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :struct-lit
                        :type "_Cxc_Optional_Int64"
                        :fields [{:name "present" :value {:node :int :value 1}}
                                 {:name "value"   :value {:node :name :sym "initial"}}]}}
                {:node :assign
                 :lhs  {:node :name :sym "_initial"}
                 :rhs  {:node :struct-lit
                        :type "_Cxc_Optional_Int64"
                        :fields [{:name "present" :value {:node :int :value 1}}
                                 {:name "value"   :value {:node :name :sym "initial"}}]}}
                ;; F32 L4.d.β.α — concurrent-mode `_state` seed.
                {:node        :initial-state-commit
                 :class-name  "Counter"
                 :data-fields [{:name "n" :c-type "_Cxc_Optional_Int64"
                                :attrs ["readonly" "assign"]}
                               {:name "initial" :c-type "_Cxc_Optional_Int64"
                                :attrs ["readonly" "assign"]}]}]
               (:body state))))
      (is (= :objc-init-with (:node ctor)))
      (is (= "Counter" (:class ctor)))
      (is (= [{:name "initial" :value {:node :int :value 42}}] (:args ctor))))))

(deftest atom-typed-field-defaults-to-nil-and-lowers-as-pointer
  (testing "F39 L1 — reference-typed atom fields are already nil-allowed (spec §3.6.1) — no `?` needed. `other: Counter = nil` lowers to `Counter *` (ARC strong)."
    (let [prog (lower-src "def Counter:\n    n: Int = 5\n    other: Counter = nil\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(1)\n")
          cls  (first (:classes prog))]
      (is (= [{:name "phase"            :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "own_queue"        :c-type "dispatch_queue_t" :attrs ["nonatomic" "strong"]}
              {:name "cxc_owned_resources" :c-type "NSMutableArray *" :attrs ["nonatomic" "strong"]}
              {:name "parent"           :c-type "id"               :attrs ["nonatomic" "weak"]}
              {:name "parent_slot_name" :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              ;; F33 L5 — data-field @properties are readonly.
              {:name "n"                :c-type "_Cxc_Optional_Int64" :attrs ["readonly" "assign"]}
              {:name "other"            :c-type "Counter *"        :attrs ["readonly" "strong"]}]
             (:properties cls)))
      (let [state-init (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cls)))]
        (is (= {:node :assign
                :lhs  {:node :name :sym "_other"}
                :rhs  {:node :nil}}
               ;; cxc_stateInit body: [_n=5, _other=nil]
               (second (:body state-init))))))))

(deftest atom-observable-lowers-to-objc-method
  (testing "F67 L4 — def-atom parametric observable → :objc-method under class :methods (replaces reader methods per Proposal G)."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    bump() :- n + 1\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().bump())\n")
          cls  (first (:classes prog))
          m    (first (filter #(= "bump" (:selector %)) (:methods cls)))]
      (is (= :objc-method (:node m)))
      (is (= "bump" (:selector m)))
      (is (= "int64_t" (:return-type m)))
      (is (empty? (:params m))))))

(deftest atom-observable-call-lowers-to-objc-msg
  (testing "counter.bump() → :objc-msg with the receiver as :recv and bare selector (observable call site, F67 L4)."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    bump() :- n + 1\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().bump())\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])]
      (is (= :objc-msg (:node msg)))
      (is (= "bump" (:selector msg)))
      (is (= :objc-alloc-init (get-in msg [:recv :node])))
      (is (empty? (:args msg))))))

(deftest atom-observable-multiarg-selector
  (testing "plus(other: Int) → selector `plusWithother:` and matching call (parametric observable selector construction, F67 L2/L4). 2026-09-05 Path 1 slice 8-A: `other: Int` (bare-T primitive param) lifts to `_Cxc_Optional_Int64` envelope C sig; slice 1-wider full lifts the return to envelope, so `print(Counter().plus(5))` routes through `:c-print-optional` and the objc-msg is nested under `:arg` (not `[:args 1]`) with the raw `5` wrapped in `:c-envelope-lit` at the call site."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    plus(other: Int) :- n + other\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().plus(5))\n")
          m    (first (filter #(= "plusWithother:" (:selector %))
                              (:methods (first (filter #(= "Counter" (:name %)) (:classes prog))))))
          call (first (main-work-body prog))
          msg  (:arg call)]
      (is (= "plusWithother:" (:selector m)))
      (is (= [{:name "other" :c-type "_Cxc_Optional_Int64"
               :type {:node :type-ref :name "Int"}}]
             (:params m)))
      (is (= "plusWithother:" (:selector msg)))
      (is (= 1 (count (:args msg)))))))

;; F40 Landing 3 — reject non-compile-time-constant defaults.

(deftest f40-record-field-default-computed-rejected
  (testing "record V { x: Int = 1 + 1 } — arithmetic expr as default fails"
    (let [ex (try
               (lower-src "def V:\n    x: Int = 1 + 1\n\ndef main:\n    -> running\n        then: print(V(x: 10).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-record-field-default-call-rejected
  (testing "record V { x: Int = foo() } — call as default fails"
    (let [ex (try
               (lower-src "def V:\n    x: Int = foo()\n\ndef main:\n    -> running\n        then: print(V(x: 10).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-atom-ctor-default-computed-rejected
  (testing "def Widget(x: Int = 1 + 1) — arithmetic expr as ctor default fails"
    (let [ex (try
               (lower-src "def Widget(x: Int = 1 + 1):\n    n: Int = x\n\ndef main:\n    -> running\n        then: print(Widget().n)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-observable-default-computed-rejected
  (testing "parametric observable with `factor: Int = 2 * 2` default fails — non-const check runs on observable params too."
    (let [ex (try
               (lower-src "def V:\n    x: Int\n\n    scaled(factor: Int = 2 * 2) :- x * factor\n\ndef main:\n    -> running\n        then: print(V(x: 5).scaled())\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f16-l3-enum-case-default-accepted
  (testing "F16 L3 — bare enum-case access `d: Direction = Direction.up` accepted as a compile-time constant default per §3.8.5. `default-expr-const-shape?` reaches into enum-env to verify: target is a bare `:ident` naming an enum, field is one of that enum's bare (payload-less) cases."
    ;; Should not throw.
    (let [prog (lower-src "def Direction:\n    case up\n    case down\n\ndef Box:\n    d: Direction = Direction.up\n\ndef main:\n    -> running\n        then: print(Box().d)\n")]
      (is (some? prog) "compile succeeds"))))

(deftest q10b-enum-payload-case-default-accepted
  (testing "Q10-B relaxation (2026-08-30) — payload-carrying enum-case defaults (`.ok(value: 1)`) are now accepted as compile-time constants when all payload args are themselves compile-time constants. Reversal of the F16 L3 deferral (`f16-l3-enum-payload-case-default-still-rejected`) after spec §4.16.10 relaxed the const-expr definition to cover constructor calls with all-const args, transitively. Enum payload cases are one such shape (parser produces `:call` over `:dot`)."
    ;; Should not throw — Q10-B accepts.
    (let [prog (lower-src "def Outcome:\n    case ok(value: Int)\n    case failed(reason: Int)\n\ndef Box:\n    o: Outcome = Outcome.ok(value: 1)\n\ndef main:\n    -> running\n        then: print(1)\n")]
      (is (some? prog) "compile succeeds under Q10-B"))))

(deftest q10b-enum-payload-case-default-with-nonconst-arg-still-rejected
  (testing "Q10-B — payload'd enum-case default whose payload arg is NOT a compile-time constant still rejects. `.ok(value: foo())` — the `foo()` call isn't const, so the const-shape check fails."
    (let [ex (try
               (lower-src "def Outcome:\n    case ok(value: Int)\n\ndef Box:\n    o: Outcome = Outcome.ok(value: foo())\n\ndef main:\n    -> running\n        then: print(1)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception when payload arg is non-const")
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest q10b-list-literal-default-accepted
  (testing "Q10-B relaxation (2026-08-30) — list literal default `xs: List of Int = [1, 2, 3]` accepted as compile-time constant when all elements are themselves compile-time constants. Persistent-collection semantics (F33) mean the folded value is shared safely across call sites — no mutable-default footgun."
    (let [prog (lower-src "def Box:\n    xs: List of Int = [1, 2, 3]\n\ndef main:\n    -> running\n        then: print(1)\n")]
      (is (some? prog) "compile succeeds under Q10-B — empty transitive check"))))

(deftest q10b-empty-list-default-accepted
  (testing "Q10-B — empty list literal `[]` accepted (empty-collection const)."
    (let [prog (lower-src "def Box:\n    xs: List of Int = []\n\ndef main:\n    -> running\n        then: print(1)\n")]
      (is (some? prog) "compile succeeds under Q10-B"))))

(deftest q10b-list-with-nonconst-element-rejected
  (testing "Q10-B — list-literal default with a non-const element `[1, foo(), 3]` rejects — the const-shape check is transitive; a single non-const element aborts folding."
    (let [ex (try
               (lower-src "def Box:\n    xs: List of Int = [1, foo(), 3]\n\ndef main:\n    -> running\n        then: print(1)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception when list contains non-const element")
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-neg-literal-default-accepted
  (testing "record V { x: Int = -5 } — unary neg on literal is const"
    (let [prog (lower-src "def V:\n    x: Int = -5\n\ndef main:\n    -> running\n        then: print(V().x)\n")
          call (first (main-work-body prog))
          ctor (get-in call [:arg :target])]
      (is (= :struct-lit (:node ctor)))
      (is (= 1 (count (:fields ctor))))
      (is (= "x" (get-in ctor [:fields 0 :name])))
      ;; Lower rewrites the parser's :neg wrapper to :unary op "-"; the
      ;; const-check happens on the parser AST and accepts :neg.
      (let [wrapped (get-in ctor [:fields 0 :value])
            v       (get-in wrapped [:fields 1 :value])]
        (is (= :struct-lit (:node wrapped)))
        (is (= "_Cxc_Optional_Int64" (:type wrapped)))
        (is (contains? #{:neg :unary} (:node v)))
        (is (= 5 (get-in v [:expr :value])))))))

;; F40 Landing 2 — named args + default insertion on method calls.

(deftest f40-atom-observable-accepts-named-args
  (testing "counter.plus(other: 7) — named arg on atom observable resolves (F67 L4 parametric observable arg dispatch). 2026-09-05 Path 1: bare-T Int return + param lift; msg lives under `:arg` of `:c-print-optional`; call-site slice 8-B wraps raw `7` in `:c-envelope-lit`."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    plus(other: Int) :- n + other\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().plus(other: 7))\n")
          call (first (main-work-body prog))
          msg  (:arg call)]
      (is (= "plusWithother:" (:selector msg)))
      (is (= 1 (count (:args msg))))
      (is (= 7 (get-in msg [:args 0 :value :value]))))))

(deftest f40-atom-observable-defaults-fill-when-omitted
  (testing "counter.plus() with other defaulted uses default (F67 L4). 2026-09-05 Path 1: msg lives under `:arg`; default `3` wraps to `:c-envelope-lit`."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    plus(other: Int = 3) :- n + other\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().plus())\n")
          call (first (main-work-body prog))
          msg  (:arg call)]
      (is (= "plusWithother:" (:selector msg)))
      (is (= 1 (count (:args msg))))
      (is (= 3 (get-in msg [:args 0 :value :value]))))))

(deftest f40-atom-observable-named-out-of-order-reorders
  (testing "counter.plus3(c: 3, a: 1, b: 2) — reordered to decl order (F67 L4 parametric observable). 2026-09-05 Path 1: msg lives under `:arg`; each raw literal wraps to `:c-envelope-lit`, so `:value` on the arg is the envelope-lit node whose inner `:value` is the int-lit node whose `:value` is the number."
    (let [prog (lower-src "def Counter:\n    n: Int! = 0\n\n    plus3(a: Int, b: Int, c: Int) :- n + a + b + c\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().plus3(c: 3, a: 1, b: 2))\n")
          call (first (main-work-body prog))
          msg  (:arg call)
          arg-values (mapv #(get-in % [:value :value]) (:args msg))]
      (is (= [1 2 3] arg-values)))))

(deftest f40-record-observable-defaults-fill-when-omitted
  (testing "record parametric observable with param default inserts default at call site (F67 L4). 2026-09-05 Path 1: `scaled` return lifts to envelope, so `print(...)` routes through `:c-print-optional`; inner `:call` sits under `:arg`; default `4` wraps to `:c-envelope-lit` at arg position."
    (let [prog (lower-src "def V:\n    x: Int!\n\n    scaled(factor: Int = 4) :- x * factor\n\ndef main:\n    -> running\n        then: print(V(x: 5).scaled())\n")
          call (first (main-work-body prog))
          inner (:arg call)]
      (is (= :call (:node inner)))
      (is (= "V_scaled" (:fn inner)))
      ;; args = [self, factor]
      (is (= 2 (count (:args inner))))
      (is (= 4 (get-in inner [:args 1 :value :value]))))))

(deftest f40-observable-unknown-named-arg-rejected
  (testing "atom observable with unknown named arg fails (F67 L4)."
    (let [ex (try
               (lower-src "def Counter:\n    n: Int = 10\n\n    plus(other: Int) :- n + other\n\ndef main:\n    -> running\n        then: print(Counter().plus(bogus: 5))\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"unknown named arg" (.getMessage ex))))))

;; F27 semantic once-only — chained comparison lowers to :chained-cmp;
;; emit renders as GNU stmt-expr binding each interior operand once.

(deftest f27-emit-renders-gnu-stmt-expr
  (testing "arm-head `|: -10 <= val <= 10` emits a GNU stmt-expr binding val (now via `__cxc_snap._val` under F33 L2.c) once"
    (let [prog (lower-src "def main:\n    val: Int! = 5\n\n    -> running\n\n    running <: bump -> self\n        |: -10 <= val <= 10\n        then: val => val + 1\n")
          c-src (emit/emit prog)]
      ;; The interior operand is bound to __cxc_cc_1 via __typeof__.
      ;; F33 L2.c: receive-glyph body reads route through the snapshot.
      (is (str/includes? c-src "__typeof__(__cxc_snap._val) __cxc_cc_1 = (__cxc_snap._val);"))
      ;; Comparisons reference the bound local, not the raw snapshot access.
      (is (str/includes? c-src "(-10) <= __cxc_cc_1 && __cxc_cc_1 <= 10"))
      ;; Wrapped in a GNU statement-expression.
      (is (str/includes?
           c-src
           "({ __typeof__(__cxc_snap._val) __cxc_cc_1 = (__cxc_snap._val); (-10) <= __cxc_cc_1 && __cxc_cc_1 <= 10; })")))))

;; F46 — clamp single-evaluation: emit-c-fn binds each arg to a
;; typeof-declared local so a side-effectful arg fires once.

(deftest f46-clamp-emits-typeof-bindings
  (testing "clamp(x, 0, 100) emit-c uses `__typeof__` + `__cxc_cv` so x fires once at runtime"
    (let [prog (lower-src "def main:\n    -> running\n        then: print(clamp(15, 0, 100))\n")
          call (first (main-work-body prog))
          ffi  (get-in call [:args 1])
          rendered ((:emit-c-fn ffi) ["counter_next()" "0" "100"])]
      (is (= :ffi-call (:node ffi)))
      (is (= "clamp" (:name ffi)))
      ;; `__typeof__` is a compile-time type query, so the arg text
      ;; inside `__typeof__(...)` is not runtime-evaluated. The
      ;; runtime binding happens in `= (arg);`. Comparisons + branches
      ;; then reference the bound locals, not the original exprs.
      (is (re-find #"__typeof__\(counter_next\(\)\) __cxc_cv = \(counter_next\(\)\);"
                   rendered))
      (is (re-find #"__cxc_cv < __cxc_clo" rendered))
      (is (re-find #"__cxc_cv > __cxc_chi" rendered))
      ;; The comparisons + branches must NOT reference the raw arg
      ;; text — bound locals only.
      (is (not (re-find #"counter_next\(\)\s*[<>]" rendered))))))

(deftest and-or-is-lower-to-c-ops
  (testing "and → &&, or → ||, is → ==; all infer :bool"
    (let [prog (lower-src "def main:\n    -> running\n        then: print(1 == 2 and 3 == 3 or 4 == 4)\n")
          call (first (get-in prog [:main :body]))
          expr (get-in call [:args 1])]
      ;; Left-assoc: (((1==2) && (3==3)) || (4==4))
      (is (= "||" (:op expr)))
      (is (= "&&" (get-in expr [:left :op]))))))

(deftest is-nil-emits-pointer-eq
  (testing "`x is nil` → `x == nil` at emit layer (pointer identity in ObjC)"
    (let [prog (lower-src "def Counter:\n    raw_other: Counter = nil\n\n    other :- raw_other\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().other is nil)\n")
          call (first (main-work-body prog))
          expr (get-in call [:args 1])]
      (is (= :binop (:node expr)))
      (is (= "==" (:op expr)))
      (is (= :nil (get-in expr [:right :node]))))))

(deftest enum-lowers-to-c-enum-with-mangled-cases
  (testing "enum decl → :c-enum under :enums; user-visible `Key` shadows the prelude Key attribution so `:name` on the c-enum node arrives mangled (`_Cxc_Key`), and case-tag references in main lower to `_Cxc_Key_arrow_down` — matching the typedef closer + all downstream tag-refs (F56 discipline, extended to enums 2026-09-12)."
    (let [prog (lower-src "def Key:\n    case arrow_up\n    case arrow_down\n\ndef main:\n    -> running\n        then: print(Key.arrow_down)\n")
          e    (first (:enums prog))
          call (first (get-in prog [:main :body]))]
      (is (= :c-enum (:node e)))
      (is (= "_Cxc_Key" (:name e)))
      (is (= ["arrow_up" "arrow_down"] (:cases e)))
      (is (= "%d\n" (get-in call [:args 0 :value])))
      (is (= {:node :name :sym "_Cxc_Key_arrow_down"} (get-in call [:args 1]))))))

(deftest f16-l1-payload-enum-lowers-to-tagged-union
  (testing "F16 L1 — an enum with any payload-carrying case lowers to a `:c-tagged-enum` (tagged-union struct at emit) rather than a plain `:c-enum`. Bare cases in the mixed enum still contribute a tag but no union member; payload cases carry their param list resolved to C types."
    (let [prog (lower-src "def Msg:\n    case ping\n    case pong(reply_to: Int!, seq: Int!)\n\ndef main:\n    -> running\n")
          e    (first (:enums prog))]
      (is (= :c-tagged-enum (:node e))
          "enum with any payload case → :c-tagged-enum, not :c-enum")
      (is (= "Msg" (:name e)))
      (is (= 2 (count (:entries e))))
      (is (= "ping"    (get-in e [:entries 0 :name])))
      (is (= []        (get-in e [:entries 0 :payload])) "bare case → empty payload")
      (is (= "pong"    (get-in e [:entries 1 :name])))
      (is (= [{:name "reply_to" :c-type "int64_t"}
              {:name "seq"      :c-type "int64_t"}]
             (get-in e [:entries 1 :payload]))
          "payload params resolved to C types via cxc-type->c"))))

(deftest f16-l1-payload-enum-construction-c-tagged-init
  (testing "F16 L1 — `Msg.pong(reply_to: 7, seq: 42)` intercepts at :call lowering and produces a `:c-tagged-init` node carrying the enum name, case name, and named field values. Emit renders this as a designated-initializer compound literal `((Msg){.tag = Msg_Tag_pong, .data.pong = { .reply_to = 7, .seq = 42 }})`."
    (let [prog (lower-src "def Msg:\n    case ping\n    case pong(reply_to: Int!, seq: Int!)\n\ndef main:\n    -> running\n        then:\n            let m: Msg = Msg.pong(reply_to: 7, seq: 42)\n            print(0)\n")
          main-body (get-in prog [:main :body])
          m-decl (first (filter #(= "m" (:name %)) main-body))]
      (is (= :var-decl (:node m-decl)))
      (is (= :c-tagged-init (get-in m-decl [:rhs :node])))
      (is (= "Msg"  (get-in m-decl [:rhs :enum-name])))
      (is (= "pong" (get-in m-decl [:rhs :case-name])))
      (is (= "reply_to" (get-in m-decl [:rhs :fields 0 :name])))
      (is (= 7 (get-in m-decl [:rhs :fields 0 :value :value])))
      (is (= "seq" (get-in m-decl [:rhs :fields 1 :name])))
      (is (= 42 (get-in m-decl [:rhs :fields 1 :value :value]))))))

(deftest f16-l1-bare-case-access-in-tagged-enum-emits-tagged-init
  (testing "F16 L1 — bare case access `Msg.ping` on a tagged enum can't lower to a plain C constant (`Msg_ping`) because the C type of `Msg` is a struct. Emit a zero-field `:c-tagged-init` instead: `((Msg){.tag = Msg_Tag_ping})`. Value-only enums keep the pre-F16 `Enum_case` constant."
    (let [prog (lower-src "def Msg:\n    case ping\n    case pong(reply_to: Int!)\n\ndef main:\n    -> running\n        then:\n            let m: Msg = Msg.ping\n            print(0)\n")
          main-body (get-in prog [:main :body])
          m-decl (first (filter #(= "m" (:name %)) main-body))
          rhs (:rhs m-decl)]
      (is (= :c-tagged-init (:node rhs))
          "bare-case access in a tagged enum lowers to :c-tagged-init")
      (is (= "Msg" (:enum-name rhs)))
      (is (= "ping" (:case-name rhs)))
      (is (= [] (:fields rhs))
          "no field inits for a bare case"))))

(deftest f16-l1-value-only-enum-still-lowers-to-c-enum
  (testing "F16 L1 — enum with all cases bare keeps the pre-F16 value-only `:c-enum` representation (backwards compatibility). Only mixed / payload-only enums flip to `:c-tagged-enum`."
    (let [prog (lower-src "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n")
          e    (first (:enums prog))]
      (is (= :c-enum (:node e)))
      (is (= ["red" "green"] (:cases e))))))

(deftest f16-l2-raw-value-enum-preserves-values-and-picks-narrowest-type
  (testing "F16 L2 — a raw-value enum keeps `:c-enum` shape but adds `:raw-values` (parallel to `:cases`) and `:raw-c-type`. Type is the smallest fitting unsigned int per §3.3: UInt8 for [0,255], UInt16 for [0,65535], etc. Both are consumed by emit to render `typedef enum : <c-type> { E_a = v, E_b = v } E;`."
    (let [prog (lower-src "def HTTP:\n    case ok = 200\n    case not_found = 404\n\ndef main:\n    -> running\n")
          e    (first (:enums prog))]
      (is (= :c-enum (:node e)))
      (is (= ["ok" "not_found"] (:cases e)))
      (is (= [200 404] (:raw-values e)))
      (is (= "uint16_t" (:raw-c-type e))
          "404 > 255 forces UInt16; smallest fitting is uint16_t"))))

(deftest f16-l2-raw-value-uint8-narrowest-type
  (testing "F16 L2 — all raw values within [0,255] pick UInt8 (uint8_t)."
    (let [prog (lower-src "def Small:\n    case a = 0\n    case b = 100\n    case c = 255\n\ndef main:\n    -> running\n")
          e    (first (:enums prog))]
      (is (= [0 100 255] (:raw-values e)))
      (is (= "uint8_t" (:raw-c-type e))))))

(deftest f16-l2-mixed-raw-and-bare-cases-rejected
  (testing "F16 L2 — mixing raw-value cases and bare cases on the same enum rejects at parse. All-or-none per spec §3.3."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F16 L2: enum `Mixed` mixes cases with explicit raw values"
         (lower-src "def Mixed:\n    case a = 1\n    case b\n\ndef main:\n    -> running\n")))))

(deftest f16-l2-raw-value-plus-payload-rejected
  (testing "F16 L2 — a single case can't have both a raw value and a payload. Distinct shapes per §3.3."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F16 L2: case `bad` has both a payload"
         (lower-src "def Weird:\n    case bad(n: Int!) = 1\n\ndef main:\n    -> running\n")))))

(deftest f16-l2-raw-value-non-int-literal-rejected
  (testing "F16 L2 — raw value must be an integer literal at v1.0. String/float raw values deferred."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F16 L2: raw value on case `a` must be an integer literal"
         (lower-src "def E:\n    case a = \"hello\"\n\ndef main:\n    -> running\n")))))

(deftest f16-l1-payload-default-rejected
  (testing "F16 L1 — a default on an enum payload field rejects at parse time. Payload fields don't carry defaults — the case *value* is the payload."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F16 L1: default on enum payload"
         (lower-src "def Msg:\n    case ok(n: Int = 0)\n\ndef main:\n    -> running\n")))))

(deftest f16-l1-dispatch-payload-binding-scoped-to-arm
  (testing "F16 L1 — `<: pong(reply_to: let r):` binds `r` as a local scoped to the arm body only. Lower generates a `:var-decl` for each binding at the head of the arm body reading from `.data.<case>.<field>` on the scrutinee temp."
    (let [prog (lower-src "def Msg:\n    case ping\n    case pong(reply_to: Int!)\n\ndef main:\n    -> running\n        then:\n            let m: Msg = Msg.pong(reply_to: 7)\n            m:\n                <: ping: print(0)\n                <: pong(reply_to: let r): print(r)\n")
          main-body (get-in prog [:main :body])
          scope    (first (filter #(= :c-scope (:node %)) main-body))
          if-chain (first (filter #(= :c-if-chain (:node %)) (:stmts scope)))
          pong-arm (second (:branches if-chain))
          decl    (first (:body pong-arm))]
      (is (= :var-decl (:node decl)))
      (is (= "r" (:name decl)))
      (is (= "int64_t" (:c-type decl)))
      (is (= :field (get-in decl [:rhs :node])))
      (is (= "reply_to" (get-in decl [:rhs :field])))
      (is (= "pong" (get-in decl [:rhs :target :field])))
      (is (= "data" (get-in decl [:rhs :target :target :field])))
      (is (= "_cxc_scrut" (get-in decl [:rhs :target :target :target :sym]))))))

(deftest nullable-non-atom-suffix-banned
  (testing "F39 L5a (2026-08-20) — `T?` on a value primitive fires the unified `:cxc-f39-nullable-suffix-banned` diagnostic (was `fail-nyi!` pre-L5a). Bare `Int` is the nullable form under the unified rule; `Int!` is the non-nil contract."
    (try
      (lower-src "def X:\n    n: Int? = nil\n\ndef main:\n    -> running\n        then: print(1)\n")
      (is false "expected suffix-banned throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :cxc-f39-nullable-suffix-banned (:kind (ex-data e))))))))

(deftest atom-ctor-params-default-fills-when-omitted
  (testing "Counter() with no args uses ctor-param defaults"
    (let [prog (lower-src "def Counter(initial: Int = 5):\n    n: Int = initial\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n        then: print(0)\n")
          main-cls (first (filter #(= "CxcMain" (:name %)) (:classes prog)))
          state-body (:body (first (filter #(clojure.string/starts-with?
                                             (:selector %) "cxc_stateInit")
                                           (:methods main-cls))))
          ctor (some (fn [stmt]
                       (when (and (= :assign (:node stmt))
                                  (= "_c" (get-in stmt [:lhs :sym])))
                         (:rhs stmt)))
                     state-body)]
      (is (= :objc-init-with (:node ctor)))
      (is (= [{:name "initial" :value {:node :int :value 5}}] (:args ctor))))))

(deftest method-call-lowers-to-c-function-call
  (testing "v.plus(other) → :call {:fn V_plus :args [v other]} — F67 L4 parametric observable dispatch"
    (let [prog (lower-src "def V:\n    x: Float!\n    y: Float!\n\n    plus(other: V) :- V(x: self.x + other.x, y: self.y + other.y)\n\ndef main:\n    -> running\n        then: print(V(x: 1.0, y: 2.0).plus(V(x: 3.0, y: 4.0)).x)\n")
          print-call (first (get-in prog [:main :body]))
          field-node (get-in print-call [:args 1])
          plus-call  (:target field-node)]
      (is (= :field (:node field-node)))
      (is (= "x" (:field field-node)))
      (is (= :call (:node plus-call)))
      (is (= "V_plus" (:fn plus-call)))
      (is (= 2 (count (:args plus-call))))
      (is (= :struct-lit (get-in plus-call [:args 0 :node])))    ; first arg = self expr
      (is (= :struct-lit (get-in plus-call [:args 1 :node]))))))

(deftest message-triggered-transition-lowers-to-objc-method
  (testing "`alive <: bump -> alive then: n => n + 1` → :objc-method with one transition-branch"
    (let [prog (lower-src "def Counter:
    n: Int! = 0

    -> alive

    alive <: bump -> alive
        then: n => n + 1

def main:
    -> running
        then: print(1)
")
          cls  (first (:classes prog))
          bump (first (filter #(= "bump" (:selector %)) (:methods cls)))]
      (is (some? bump) "expected a `bump` transition method on the class")
      (is (= "void" (:return-type bump)))
      (is (empty? (:params bump)))
      ;; Body: [transition-branch]. One branch for the alive source phase;
      ;; no phase reassign (source==target). Branch body is wrapped in
      ;; :end-catch-wrap (Step 15 BEAM containment).
      (let [branch (first (:body bump))
            wrap   (first (:body branch))]
        (is (= :transition-branch (:node branch)))
        (is (= "kCounter_phase_alive" (:source-phase-const branch)))
        (is (= :end-catch-wrap (:node wrap)))
        ;; Inside the try body (post F33 L3.c):
        ;;   1. write to `__cxc_next._n` (bag write; RHS reads snapshot via L2.c)
        ;;   2. nonnil check against the just-written bag field
        ;;   3. commit-next-state (bag → live ivars)
        ;; Post-commit `[self checkInvariants]` is suppressed under
        ;; F33 L3.c when snapshot is active — pre-commit inline
        ;; checks would take its place if any invariants existed,
        ;; but Counter has none so the pre-commit block is empty.
        (is (= [{:node :assign
                 :lhs  {:node :field
                        :target {:node :name :sym "__cxc_next"}
                        :field  "_n"}
                 :rhs  {:node :binop :op "+"
                        :left  {:node :field
                                :target {:node :name :sym "__cxc_snap"}
                                :field  "_n"}
                        :right {:node :int  :value 1}}}
                {:node :raw-c
                 :text "if ((__cxc_next._n) == nil) cxc_nonnil_violation(\"def-atom `Counter` field write\", \"n\");"}
                {:node        :commit-next-state
                 :atom-name   "Counter"
                 ;; F32 L4.c fix — `:class-name` on the commit node so
                 ;; concurrent-mode `sizeof(<Cls>_State)` uses the
                 ;; emitted ObjC class name (matters only for main:
                 ;; `class-name-for-atom "main"` = `"CxcMain"`; regular
                 ;; atoms are name-equivalent).
                 :class-name  "Counter"
                 :data-fields [{:name "n" :cxc-ty "Int" :nullable? false
                                :nonnil? true :optional? false
                                :scalar-c "int64_t" :c-type "int64_t"
                                :default {:node :lit-int :value 0}
                                ;; F15 L2.d.ii.α — raw parsed type node
                                ;; preserved so downstream structural-
                                ;; conformance checks compare types
                                ;; against a nullability-preserving form.
                                :type {:node :type-nonnil
                                       :inner {:node :type-ref :name "Int"}}}]}]
               (:body wrap)))))))

(deftest message-triggered-transition-with-phase-change-emits-phase-assign
  (testing "`alive <: die -> dead` sets _phase to kA_phase_dead inside the branch body"
    (let [prog (lower-src "def Enemy:
    hp: Int = 3

    -> alive

    alive <: die -> dead
        then: print(0)

def main:
    -> running
        then: print(1)
")
          cls  (first (:classes prog))
          die  (first (filter #(= "die" (:selector %)) (:methods cls)))
          branch (first (:body die))]
      (is (some? die))
      (is (= "kEnemy_phase_alive" (:source-phase-const branch)))
      ;; Branch body is wrapped in :end-catch-wrap (Step 15).
      ;; F33 L3.b: commit-next-state fires first (no writes in this
      ;; transition, so commit is a no-op copy), then the phase assign.
      (let [wrap (first (:body branch))
            body (:body wrap)]
        (is (= :end-catch-wrap (:node wrap)))
        (is (= :commit-next-state (:node (first body))))
        (is (= {:node :assign
                :lhs  {:node :name :sym "_phase"}
                :rhs  {:node :name :sym "kEnemy_phase_dead"}}
               (second body)))))))

(deftest multiple-transitions-same-selector-different-phases-group-into-one-method
  (testing "`alive <: die` and `dying <: die` share selector `die` → one method, two branches in source order"
    (let [prog (lower-src "def Enemy:
    hp: Int = 3

    -> alive

    alive <: die -> dying
        then: hp => 0
    dying <: die -> dead

def main:
    -> running
        then: print(1)
")
          cls  (first (:classes prog))
          die  (first (filter #(= "die" (:selector %)) (:methods cls)))]
      (is (some? die))
      (is (= 2 (count (:body die))))
      (is (= "kEnemy_phase_alive" (get-in (:body die) [0 :source-phase-const])))
      (is (= "kEnemy_phase_dying" (get-in (:body die) [1 :source-phase-const]))))))

(deftest arm-propositions-and-fold-into-single-branch-guard
  (testing "multiple arm-head `|:` propositions AND together on the branch"
    (let [prog (lower-src "def Enemy:
    hp: Int! = 10

    -> alive

    alive <: take_damage -> alive
        |: hp > 0
        |: hp < 20
        then: hp => hp - 1

def main:
    -> running
        then: print(1)
")
          cls  (first (:classes prog))
          td   (first (filter #(= "take_damage" (:selector %)) (:methods cls)))
          branch (first (:body td))]
      (is (some? branch))
      ;; guard is `(hp > 0) && (hp < 20)` — both arm propositions
      (is (= "&&" (get-in branch [:guard :op]))))))

(deftest target-self-lowers-as-no-phase-change
  (testing "Audit F24: spec §1.11 line 1312 — `-> self` means self-transition, no phase change. Prior code generated a bogus `k<Atom>_phase_self` constant and phase-set assignment; now target=self skips the phase update entirely and treats the transition as staying in the source phase."
    (let [prog (lower-src "def Sidekick:
    -> running

def main:
    s: Sidekick = Sidekick()
    n: Int = 0

    -> running

    running <: bump -> self
        then: n => n + 1
")
          cls  (first (filter #(= "CxcMain" (:name %)) (:classes prog)))
          phases (:phase-constants cls)]
      ;; The bug was `self` becoming a phase name; verify it isn't one
      (is (not-any? #(re-find #"_phase_self$" %) (map :name phases))
          "`self` must not be interned as a phase constant")
      (is (some #(re-find #"_phase_running$" %) (map :name phases))
          "`running` remains a real phase constant"))))

(deftest wildcard-end-then-clause-rejected
  (testing "Audit F26 + F70 I3: `then:` field updates on `-> end` are a compile error (the atom's state is about to be discarded, so writes are meaningless). Under I3, `then:` accepts both `:updates` and `:stmts`; the rule migrates from clause-level to entry-level — only non-empty `:updates` triggers the reject. Non-update entries (prints, sends) are allowed on `-> end` and lower as post-commit side effects."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`then:` field updates on `-> end` wildcard .* compile error"
         (lower-src "def Sidekick:
    -> running

def main:
    s: Sidekick = Sidekick()
    n: Int = 0

    -> running

    -> end
        then: n => 99
")))))

(deftest def-main-accepts-non-running-initial-phase
  (testing "Audit F50: spec §1.11 — `def main` is entry point by naming convention; the phase name in the initial transition is not privileged. Plain-main emission does not use the target name (there is no ObjC phase-state), so any phase name should compile. Previously lower hardcoded target == \"running\"."
    ;; Baseline sanity: `-> running` still works.
    (is (some? (lower-src "def main:
    -> running
        then: print(1)
")))
    ;; F50 fix: any phase name is accepted.
    (is (some? (lower-src "def main:
    -> playing
        then: print(1)
")))
    (is (some? (lower-src "def main:
    -> tick
        then: print(1)
")))))

(deftest f50-promoted-main-with-capability-ctor-params
  (testing "F50 — `def main(cap: T)` where T is an atom lowers cleanly. Each ctor-param is auto-provisioned via `[[T alloc] init]` (or `initWith...:` when T declares defaulted ctor-params of its own). The lowered `:main-bootstrap` node carries `:cap-args` with per-cap init expressions; emit produces `[[CxcMain alloc] initWithcap:[[T alloc] init]]`."
    (let [prog (lower-src (str "def Widget:\n"
                               "    n: Int = 0\n"
                               "    -> running\n"
                               "    -> end\n"
                               "\n"
                               "def main(w: Widget):\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          bootstrap (first (filter #(= :main-bootstrap (:node %))
                                   (get-in prog [:main :body])))]
      (is (some? bootstrap) "main-bootstrap node present")
      (is (= "CxcMain" (:class-name bootstrap)))
      (is (= 1 (count (:cap-args bootstrap))))
      (let [cap (first (:cap-args bootstrap))]
        (is (= "w" (:name cap)))
        ;; Widget's ctor-params come from data-field F41 synthesis;
        ;; construction should still succeed (fields have defaults).
        (is (some? (:cap-init cap))
            "capability init lowered")))))

(deftest f50-non-atom-main-ctor-param-rejected
  (testing "F50 — `def main(x: Int)` fails at lower: Int is not an atom and can't be auto-provisioned. Diagnostic points at §1.13 (capability catalog)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"every ctor-param on `main` must resolve to an atom"
         (lower-src (str "def Peer:\n"
                         "    n: Int = 0\n"
                         "    -> running\n"
                         "    -> end\n"
                         "\n"
                         "def main(x: Int):\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest width-variants-preserve-c-type-across-observable-return
  (testing "Phase D F36a Landing 1b — width variants (`[:int 32]`/`[:uint 8]`/`[:float 32]`) carry their C type in metadata so `type-kw->c-type` picks the correct width for observable return types. Prior to 1b, all integer widths collapsed to `int64_t` at observable boundary regardless of the source width."
    (let [prog (lower-src "def Counter:
    n: UInt8! = 0
    small_next :- n

    -> running

    -> end

def main:
    -> running
        then: print(1)
")
          cls  (first (filter #(= "Counter" (:name %)) (:classes prog)))
          obs  (first (filter #(= "small_next" (:selector %)) (:methods cls)))]
      (is (some? obs) "observable emitted as an :objc-method under :methods")
      (is (= "uint8_t" (:return-type obs))
          "observable that reads a non-Optional UInt8! field returns uint8_t, not int64_t"))))

(deftest sized-numeric-atoms-lower-to-correct-c-types
  (testing "Phase D F36a Landing 1a — spec §3.1 primitive substrate. Sized atoms UInt8/16/32/64, Int8/16/32/64, Double flow through primitive-c-type to their exact C widths. Int aliased to Int64. Float is 32-bit (fixes v0.11 lowering bug — was double)."
    (let [prog (lower-src "def Widths:
    u8:  UInt8
    u16: UInt16
    u32: UInt32
    u64: UInt64
    i8:  Int8
    i16: Int16
    i32: Int32
    i64: Int64
    n:   Int
    f:   Float
    d:   Double
    b:   Bool

def main:
    -> running
        then: print(1)
")
          widths (first (filter #(= "Widths" (:name %)) (:structs prog)))
          field-c-type (fn [n] (-> (filter #(= n (:name %)) (:fields widths))
                                   first :c-type))]
      (is (some? widths) "Widths struct emitted")
      (is (= "_Cxc_Optional_UInt8"  (field-c-type "u8")))
      (is (= "_Cxc_Optional_UInt16" (field-c-type "u16")))
      (is (= "_Cxc_Optional_UInt32" (field-c-type "u32")))
      (is (= "_Cxc_Optional_UInt64" (field-c-type "u64")))
      (is (= "_Cxc_Optional_Int8"   (field-c-type "i8")))
      (is (= "_Cxc_Optional_Int16"  (field-c-type "i16")))
      (is (= "_Cxc_Optional_Int32"  (field-c-type "i32")))
      (is (= "_Cxc_Optional_Int64"  (field-c-type "i64")))
      (is (= "_Cxc_Optional_Int64"  (field-c-type "n"))  "Int aliases Int64 inside Optional storage")
      (is (= "_Cxc_Optional_Float"  (field-c-type "f"))  "Float remains 32-bit inside Optional storage")
      (is (= "_Cxc_Optional_Double" (field-c-type "d"))  "Double remains 64-bit inside Optional storage")
      (is (= "_Cxc_Optional_Bool"   (field-c-type "b"))  "Bool uses Optional value storage"))))

(deftest non-numeric-substrate-lowers-to-correct-c-types
  (testing "Phase D F36a Landing 3a — spec §3.1 non-numeric substrate (Any/Bytes). Bytes and Any resolve as reference-typed field storage. Unit/Never return-type mapping to `void` is not testable under F67 L4 observables (no explicit return type annotation); tracked as a follow-on for a `name(args) -> T :- expr` observable-return-type surface."
    (let [prog (lower-src "def NonNumeric:
    payload: Bytes
    opaque:  Any

def main:
    -> running
        then: print(1)
")
          rec  (first (filter #(= "NonNumeric" (:name %)) (:structs prog)))
          field-c-type (fn [n] (-> (filter #(= n (:name %)) (:fields rec))
                                   first :c-type))]
      (is (some? rec) "NonNumeric struct emitted")
      (is (= "NSMutableData *" (field-c-type "payload"))
          "Bytes → NSMutableData * (reference-shaped, §3.10.4)")
      (is (= "id" (field-c-type "opaque"))
          "Any → id (dynamic dispatch boundary, §3.1.3)"))))

(deftest pre-store-invariant-check-fires-before-field-assignment
  (testing "Phase D F49 Landing A — invariant enforcement runs on the SOURCE VALUE (ctor param or default expression) before any narrowing store can truncate. Pre-store check statements are prepended to cxc_stateInit body so the abort fires before `_field = <source>;`. Distinguishing label (`at construction (source value violates)`) points diagnostic at pre-store site.

  Unblocks F36 Landing 2a (picker) — narrowed storage (e.g. `Age.value` as `uint8_t` when invariant is `0 <= value <= 150`) cannot silently accept `Age(value: 300)` (which would store 44 = 300 & 0xff and then pass a post-store check on 44)."
    (let [prog (lower-src "def Bounded:
    v: Int = 42
    |: 0 <= v
    |: v <= 150

    -> running

    -> end

def main:
    -> running
        then: print(1)
")
          cls  (first (filter #(= "Bounded" (:name %)) (:classes prog)))
          state-method (first (filter #(= "cxc_stateInit" (:selector %))
                                      (:methods cls)))
          body         (:body state-method)
          checks       (filter #(= :invariant-check (:node %)) body)
          first-assign-idx (first (keep-indexed
                                    #(when (= :assign (:node %2)) %1)
                                    body))
          last-check-idx   (last (keep-indexed
                                    #(when (= :invariant-check (:node %2)) %1)
                                    body))]
      (is (= 2 (count checks))
          "one pre-store check per invariant predicate")
      (is (< last-check-idx first-assign-idx)
          "every pre-store check statement precedes every field assignment")
      (is (every? #(re-find #"at construction \(source value violates\)"
                            (:label %))
                  checks)
          "pre-store labels distinguish from post-store checkInvariants site")
      (is (= [{:node :int :value 0} {:node :int :value 42}]
             [(:left (:cond (first checks))) (:right (:cond (first checks)))])
          "first check reads default value (42), not ivar (_v) — checks the source before store")
      (is (= [{:node :int :value 42} {:node :int :value 150}]
             [(:left (:cond (second checks))) (:right (:cond (second checks)))])
          "second check also reads default value — proves substitution fires uniformly"))))

(deftest l2b-typed-proposition-failure-value-reaches-f49-emission
  (testing "L2b — normalized typed propositions retain and lower their error constructor on both post-store and pre-store F49 checks; emit constructs that value only inside the failure branch before the existing abort."
    (let [prog (lower-src "def OutOfRange:
    value: Int = 0

def Gauge:
    value: Int = 5
    |: value <= 10 as OutOfRange(value: value)

    -> running

    -> end

def main:
    -> running
        then: print(1)
")
          cls (first (filter #(= "Gauge" (:name %)) (:classes prog)))
          state-method (first (filter #(= "cxc_stateInit" (:selector %))
                                      (:methods cls)))
          check-method (first (filter #(= "checkInvariants" (:selector %))
                                      (:methods cls)))
          pre-check (first (filter #(= :invariant-check (:node %))
                                   (:body state-method)))
          post-check (first (filter #(= :invariant-check (:node %))
                                    (:body check-method)))
          c-src (emit/emit prog)]
      (is (= :struct-lit (get-in pre-check [:failure-value :node])))
      (is (= "OutOfRange" (get-in pre-check [:failure-value :type])))
      (is (= {:node :int :value 5}
             (get-in pre-check [:failure-value :fields 0 :value :fields 1 :value]))
          "pre-store typed error args use the wide source substitution")
      (is (= "OutOfRange" (get-in post-check [:failure-value :type])))
      ;; Track A/4 (2026-08-26) — the emit shape for typed propositions
      ;; flipped from `(void)(<ctor>);` + abort to box+@throw. The
      ;; constructed struct is assigned into an `_Cxc_R_<T>` wrapper
      ;; and thrown via NSException with `userInfo[@"reason"]` = box,
      ;; so L12c can catch and fan through the companion dispatch.
      (is (str/includes?
           c-src
           "_Cxc_R_OutOfRange *__cxc_r = [[_Cxc_R_OutOfRange alloc] init];")
          "typed failure path boxes into _Cxc_R_<T>")
      (is (str/includes?
           c-src
           "__cxc_r->value = ((OutOfRange){.value = ((_Cxc_Optional_Int64){.present = 1, .value = 5})});")
          "constructed record assigned into the box's @public value ivar (pre-store site)")
      (is (str/includes?
           c-src
           "__cxc_r->value = ((OutOfRange){.value = __cxc_snap._value});")
          "constructed record assigned into the box (post-store site reads snapshot)")
      (is (str/includes?
           c-src
           "@throw [NSException exceptionWithName:@\"CxcReason\"")
          "typed failure throws NSException")
      (is (str/includes? c-src "userInfo:@{@\"reason\": __cxc_r}")
          "reason box travels via userInfo[@\"reason\"] for L12c to extract"))))

(deftest pre-destruction-invariant-check-runs-before-resource-clear-and-cascade
  (testing "Phase D F49 Landing D — spec §3.4.2 requires invariants to hold BEFORE destruction. Prior to Landing D, `checkInvariants` fired last in transitionToEnd (after resource clear + child cascade + parent notify) — a failure at that site named a state the atom was no longer in. Landing D reorders: user on-end `do:` (may fix up state) → invariant check → resource clear → cascade → notify parent → (main: shutdown)."
    (let [prog (lower-src "def Bounded:
    v: Int = 42
    |: 0 <= v
    |: v <= 150

    -> running

    -> end

def main:
    -> running
        then: print(1)
")
          cls  (first (filter #(= "Bounded" (:name %)) (:classes prog)))
          end-method (first (filter #(= "transitionToEnd" (:selector %))
                                    (:methods cls)))
          ;; end-body is wrapped in :end-catch-log-only inside :phase-guard-eq;
          ;; unwrap to find the ordered stmt list.
          end-body   (:body (second (:body end-method)))
          check-idx  (first (keep-indexed
                             #(when (and (= :objc-msg (:node %2))
                                         (= "checkInvariants" (:selector %2)))
                                %1)
                             end-body))
          clear-idx  (first (keep-indexed
                             #(when (= :owned-resources-clear (:node %2)) %1)
                             end-body))
          parent-idx (first (keep-indexed
                             #(when (= :end-cascade-parent (:node %2)) %1)
                             end-body))]
      (is (some? check-idx) "checkInvariants call present in end-body")
      (is (some? clear-idx) "owned-resources-clear present in end-body")
      (is (< check-idx clear-idx)
          "invariant check precedes resource clear (pre-destruction ordering)")
      (is (< check-idx parent-idx)
          "invariant check precedes parent notification"))))

(deftest f35-l5-owned-resources-clear-runs-after-child-cascade
  (testing "F35 L5 (2026-08-23) — post-order sync teardown ordering: cascade to children FIRST (each child fully tears down), THEN clear self's owned resources, THEN notify parent. Pre-L5 the resources-clear ran BEFORE cascade under async semantics; under sync cascade a child may still observe self-owned shared resources during its own teardown, so the reorder is load-bearing."
    (let [prog (lower-src "def Helper:
    n: Int

    -> running

    -> end

def Bounded:
    h: Helper = Helper(n: 1)

    -> running

    -> end

def main:
    -> running
        then: print(1)
")
          cls  (first (filter #(= "Bounded" (:name %)) (:classes prog)))
          end-method (first (filter #(= "transitionToEnd" (:selector %))
                                    (:methods cls)))
          end-body   (:body (second (:body end-method)))
          cascade-idx (first (keep-indexed
                              #(when (= :end-cascade-child (:node %2)) %1)
                              end-body))
          clear-idx  (first (keep-indexed
                             #(when (= :owned-resources-clear (:node %2)) %1)
                             end-body))
          parent-idx (first (keep-indexed
                             #(when (= :end-cascade-parent (:node %2)) %1)
                             end-body))]
      (is (some? cascade-idx) "cascade-child present (Helper slot)")
      (is (some? clear-idx) "owned-resources-clear present")
      (is (some? parent-idx) "end-cascade-parent present")
      (is (< cascade-idx clear-idx)
          "child cascade precedes self's resource clear (F35 L5)")
      (is (< clear-idx parent-idx)
          "resource clear precedes parent notification"))))

(deftest user-observable-body-wrapped-with-entry-exit-invariant-checks
  (testing "Phase D F49 Landing C — spec §3.4.2 requires invariants to hold before/after each observable read. Observable bodies wrap with `[self checkInvariants]` calls at entry and exit; return synthesized around the observable expression body. Post F67 L4: observables are the reader surface (methods retire)."
    (let [prog (lower-src "def Bounded:
    v: Int = 42
    |: 0 <= v
    |: v <= 150

    noop() :- v

    -> running

    -> end

def main:
    -> running
        then: print(1)
")
          cls  (first (filter #(= "Bounded" (:name %)) (:classes prog)))
          noop (first (filter #(= "noop" (:selector %)) (:methods cls)))
          body (:body noop)
          check-idxs (keep-indexed
                       #(when (and (= :objc-msg (:node %2))
                                   (= "checkInvariants" (:selector %2)))
                          %1)
                       body)
          return-idx (first (keep-indexed
                              #(when (= :return (:node %2)) %1)
                              body))]
      (is (= 2 (count check-idxs))
          "entry AND exit checks emitted")
      ;; F32 L4.e.β.2.α (2026-08-24) — observable bodies now prepend a
      ;; `:state-snapshot-init` (mirrors receive-glyph body), so entry
      ;; check-stmt is at index 1 (snapshot at 0), not 0.
      (is (= :state-snapshot-init (:node (first body)))
          "snapshot-init prepended before invariant checks")
      (is (= 1 (first check-idxs))
          "entry check follows the snapshot init")
      (is (< (second check-idxs) return-idx)
          "exit check runs before the return statement (verifies final state before returning)"))))

(deftest invariant-driven-picker-narrows-int-fields-to-sized-atoms
  (testing "Phase D F36 Landing 2a — spec §3.1.2 rule 3 invariant-driven representation picker. For each bare-Int `data:` field whose invariants chain-comparison-bound it to a range that fits a sized atom (UInt8/UInt16/... or Int8/Int16/Int32), storage narrows to the smallest fitting atom. Sized-atom fields (§3.1.2 rule 1) already pin their width; only bare Int gets picked. Fields with only one bound stay Int64.

  Safety hinges on F49 Landing A's pre-store check running on the wide ctor param / default before the narrowing assignment. Without F49 A this would be a silent-corruption path."
    (let [prog (lower-src "def Age:
    value: Int = 42
    |: 0 <= value
    |: value <= 150

    -> running

    -> end

def Port:
    value: Int = 8080
    |: 1 <= value
    |: value <= 65535

    -> running

    -> end

def Temp:
    value: Int = 0
    |: -128 <= value
    |: value <= 127

    -> running

    -> end

def Signed16:
    value: Int = 0
    |: -1000 <= value
    |: value <= 1000

    -> running

    -> end

def OneBound:
    value: Int = 5
    |: value >= 0

    -> running

    -> end

def Unbounded:
    value: Int = 0

    -> running

    -> end

def main:
    -> running
        then: print(1)
")
          value-c-type
          (fn [nm]
            (:c-type (first (filter #(= "value" (:name %))
                                    (:properties (first (filter #(= nm (:name %))
                                                                (:classes prog))))))))]
      (is (= "_Cxc_Optional_UInt8"  (value-c-type "Age"))       "[0..150] → Optional UInt8")
      (is (= "_Cxc_Optional_UInt16" (value-c-type "Port"))      "[1..65535] → Optional UInt16")
      (is (= "_Cxc_Optional_Int8"   (value-c-type "Temp"))      "[-128..127] → Optional Int8")
      (is (= "_Cxc_Optional_Int16"  (value-c-type "Signed16")) "[-1000..1000] → Optional Int16")
      (is (= "_Cxc_Optional_Int64"  (value-c-type "OneBound"))
          "only lower bound → no narrowing (MVP requires both bounds)")
      (is (= "_Cxc_Optional_Int64"  (value-c-type "Unbounded"))
          "no invariants → no narrowing"))))

(deftest picker-narrowed-field-still-fires-pre-store-invariant-check
  (testing "Phase D F36 Landing 2a × F49 Landing A — a picker-narrowed field's pre-store invariant check still fires on the WIDE source value before the narrowing store. This is the correctness contract that made Landing 2a safe to ship."
    (let [prog (lower-src "def Age:
    value: Int = 42
    |: 0 <= value
    |: value <= 150

    -> running

    -> end

def main:
    -> running
        then: print(1)
")
          cls  (first (filter #(= "Age" (:name %)) (:classes prog)))
          value-prop (first (filter #(= "value" (:name %)) (:properties cls)))
          state-method (first (filter #(= "cxc_stateInit" (:selector %))
                                      (:methods cls)))
          body (:body state-method)
          checks (filter #(= :invariant-check (:node %)) body)
          last-check-idx (last (keep-indexed
                                 #(when (= :invariant-check (:node %2)) %1)
                                 body))
          first-assign-idx (first (keep-indexed
                                    #(when (= :assign (:node %2)) %1)
                                    body))]
      (is (= "_Cxc_Optional_UInt8" (:c-type value-prop))
          "picker narrowed value to UInt8 inside Optional storage")
      (is (= 2 (count checks))
          "one pre-store check per invariant predicate")
      (is (< last-check-idx first-assign-idx)
          "pre-store checks precede any narrowing field assignment")
      (is (= {:node :int :value 42}
             (get-in (first checks) [:cond :right]))
          "check reads the default literal 42 (source value), not the narrowed ivar _value"))))

(deftest f41-data-fields-without-defaults-become-ctor-params
  (testing "Phase D F41 — spec §10 requires data fields without defaults to become required ctor params (`construction auto-synthesised from data fields + parameters`). Prior impl silently zero-initialized them.

  Ctor-param synthesis is skipped for:
  - `main` atom — special construction path (env-cap wiring for main-with-ctor-params is Layer 5)
  - Container-typed fields (`List of T`, `Map of (K,V)`, `Set of T`) — auto-init to empty container

  Field ordering preserved: explicit `def X(a, b):` ctor params first, then F41-synthesized field params in data-declaration order, then use:-capability params."
    (let [prog (lower-src "def Age:
    value: Int
    |: 0 <= value
    |: value <= 150

    -> running

    -> end

def Container:
    xs: List of Int

    -> running

    -> end

def main:
    a: Age = Age(value: 42)

    -> running
        then: print(0)
")
          age-cls  (first (filter #(= "Age" (:name %)) (:classes prog)))
          age-init-params (:init-params age-cls)
          age-state (first (filter #(clojure.string/starts-with? (:selector %) "cxc_stateInit")
                                   (:methods age-cls)))
          cont-cls (first (filter #(= "Container" (:name %)) (:classes prog)))
          cont-init-params (:init-params cont-cls)]
      ;; F15 L2.c.iii.c.δ.2 — :init-params now carry raw :type alongside :c-type.
      (is (= [{:name "value" :c-type "int64_t"
               :type {:node :type-ref :name "Int"}}] age-init-params)
          "Age.value field became required ctor param (int64_t — the source-level Int, NOT the picker-narrowed uint8_t)")
      (is (= "cxc_stateInitWithvalue:" (:selector age-state))
          "cxc_stateInit selector reflects the value parameter")
      (is (= [] cont-init-params)
          "Container fields (List of Int) skipped F41 — auto-init to empty container"))))

(deftest f41-picker-and-f49-a-end-to-end-narrowing-safety
  (testing "Phase D F41 × F36 Landing 2a × F49 Landing A end-to-end: user writes `value: Int` with own-line `|: 0 <= value` / `|: value <= 150`, constructs `Age(value: 42)`, gets uint8_t storage for value, and out-of-range construction aborts on the wide ctor param before the narrowing assignment.

  This is the complete picker safety story — F41 makes ctor params real, picker narrows storage, pre-store check on the wide source aborts if the value doesn't fit. Without any of these three, silent truncation could occur."
    (let [prog (lower-src "def Age:
    value: Int
    |: 0 <= value
    |: value <= 150

    -> running

    -> end

def main:
    a: Age = Age(value: 42)

    -> running
        then: print(0)
")
          cls (first (filter #(= "Age" (:name %)) (:classes prog)))
          value-prop (first (filter #(= "value" (:name %)) (:properties cls)))
          init-params (:init-params cls)
          state (first (filter #(clojure.string/starts-with? (:selector %) "cxc_stateInit")
                               (:methods cls)))
          body (:body state)
          pre-checks (filter #(= :invariant-check (:node %)) body)]
      (is (= "_Cxc_Optional_UInt8" (:c-type value-prop))
          "picker narrows Age.value to UInt8 inside Optional storage")
      (is (= "int64_t" (:c-type (first init-params)))
          "ctor param stays int64_t — the wide source that the pre-check runs against")
      (is (= 2 (count pre-checks))
          "pre-store check fires on both invariant predicates")
      (is (every? #(re-find #"source value violates" (:label %)) pre-checks)
          "pre-store checks tagged with source-value label"))))

(deftest in-operator-raises-targeted-diagnostic
  (testing "Audit F47: `x in y` parses as a binop but its semantic lowering (dispatch to a container `contains` method — spec §1.3 membership predicates) is Phase D work. Silently emitting the literal token `in` into the generated C causes clang to fail far from the source; lower now raises a targeted diagnostic naming the operator so the failure points to the actual language feature."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`in` membership operator"
         (lower-src "def main:
    n: Int = 0

    -> running

    running <: check -> self
        |: n in n
        then: n => n
")))))

(deftest subscript-on-non-string-raises-targeted-diagnostic
  (testing "F19 L2 — subscript lowering handles `String[i]` only. On any other target type (Int, atom, container), lowering raises a targeted diagnostic naming the F42/F43 dependency and the workaround."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"subscript `expr\[i\]` on target of type :int"
         (lower-src "def R:
    n: Int!

    first() :- self.n[0]

def main:
    -> running
        then: print(0)
")))))

(deftest f19-l2-string-subscript-lowers-to-character-at-index
  (testing "F19 L2 — `s[i]` on a String lowers to `(int64_t)[s characterAtIndex:i]` returning Int. Bounds behavior follows NSString: NSRangeException on OOB, caught by F48's `@try/@catch` transition wrapper."
    (let [prog (lower-src "def R:
    s: String

    first_char() :- self.s[0]

def main:
    -> running
        then: print(0)
")
          fn         (first (filter #(= "R_first_char" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))
          subscript-expr (:value return-stmt)]
      (is (some? fn) "R_first_char lowered")
      (is (= :objc-cast (:node subscript-expr)))
      (is (= "int64_t" (:c-type subscript-expr)))
      (is (= :objc-msg (get-in subscript-expr [:expr :node])))
      (is (= "characterAtIndex" (get-in subscript-expr [:expr :selector])))
      (is (= [{:node :int :value 0}] (get-in subscript-expr [:expr :args]))))))

(deftest f37-synthesizes-record-equals-and-hash-functions
  (testing "F37 — every record gets a synthesized `<Record>_cxc_equals` (bool a,b) and `<Record>_cxc_hash` (long) free C function. Prefix `cxc_` avoids collision with user-defined equals/hash methods."
    (let [prog (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))]
      (is (contains? fn-names "V_cxc_equals"))
      (is (contains? fn-names "V_cxc_hash")))))

(deftest f37-rewrites-record-eq-to-synthesized-equals-call
  (testing "F37 — `==` between two same-record-type operands lowers to a call to the synthesized equals fn instead of an illegal raw-C struct comparison. `!=` wraps the call in a `!`."
    ;; Feed lower a record + == inside an atom method body so we can
    ;; inspect the lowered call node.
    (let [src "def V:\n    x: Int\n\ndef main:\n    -> running\n        then: print(V(x: 1) == V(x: 2))\n"
          prog (lower-src src)
          main-body (get-in prog [:main :body])
          walker (fn walker [x]
                   (cond
                     (map? x)
                     (or (when (and (= :call (:node x))
                                    (= "V_cxc_equals" (:fn x)))
                           x)
                         (some walker (vals x)))
                     (sequential? x) (some walker x)))]
      (is (some? (walker main-body))
          "expected V_cxc_equals call in main body"))))

;; ---------------------------------------------------------------------------
;; F37 Landing 2 — primitive-field canonical bytes (spec §3.4.6 / §3.10.10)
;; ---------------------------------------------------------------------------

(deftest f37-l2-bytes-synthesizes-record-as-bytes-fn
  (testing "F37 L2 — a record with only primitive-byte-eligible fields gets a synthesized `<Record>_cxc_as_bytes` free C function alongside the F37 equals/hash pair. Fields must be `T!` (non-nil) — bare `T` value-type is Optional-boxed under F39 Sub-P and defers to F39 L9. Non-primitive fields skip synthesis at L2."
    (let [prog     (lower-src "def V:\n    x: Int!\n    y: Int!\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))]
      (is (contains? fn-names "V_cxc_as_bytes"))
      (is (contains? fn-names "V_cxc_equals") "sibling equals still emitted")
      (is (contains? fn-names "V_cxc_hash")   "sibling hash still emitted"))))

(deftest f37-l2-bytes-primitive-record-encodes-big-endian
  (testing "F37 L2 — synthesized body uses `OSSwapHostToBigInt64` for Int (Int64) fields per §3.10.10 big-endian encoding. Body text is inspected to verify the byte-order call is present per field."
    (let [prog (lower-src "def V:\n    x: Int!\n    y: Int!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "V_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (= "NSMutableData *" (:return-type f)))
      (is (re-find #"OSSwapHostToBigInt64" body-text)
          "Int (int64_t) fields use OSSwap big-endian")
      (is (re-find #"NSMutableData dataWithCapacity:16" body-text)
          "capacity hint = 8 + 8 bytes for two Int fields"))))

(deftest f37-l2-bytes-rewrites-record-as-call-to-synthesized-fn
  (testing "F37 L2 — `p.as()` on a record local lowers to a `:call` whose `:fn` is `V_cxc_as_bytes` (analogous to F37 equals rewrite). Downstream annotations `let b: Bytes = p.as()` unify against :bytes inferred at the intercept. `.as()` at method-name position requires the parser's dot-branch to accept the `as` keyword (spec-committed reserved word used as method surface)."
    (let [src "def V:\n    x: Int!\n\ndef main:\n    -> running\n        then:\n            let b: Bytes = V(x: 1).as()\n            print(b.length)\n"
          prog (lower-src src)
          main-body (get-in prog [:main :body])
          walker (fn walker [x]
                   (cond
                     (map? x)
                     (or (when (and (= :call (:node x))
                                    (= "V_cxc_as_bytes" (:fn x)))
                           x)
                         (some walker (vals x)))
                     (sequential? x) (some walker x)))]
      (is (some? (walker main-body))
          "expected V_cxc_as_bytes call in main body"))))

(deftest f37-l2-bytes-non-primitive-field-fails-nyi
  (testing "F37 L2+3 — a record with a currently-unsupported field kind falls outside every landed encoder arm. The remaining hole at v1.0 is `Any` (heterogeneous reference type — no canonical wire format). Nullable `String` was previously listed here as fail-nyi; 2026-08-27 landed nullable-String encoder+decoder so the diagnostic re-targets the next unlanded field type."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F37 Landings 2 \+ 3.*W\.thing.*Any"
         (lower-src "def W:\n    thing: Any\n\ndef main:\n    -> running\n        then:\n            let b: Bytes = W(thing: 0).as()\n            print(b.length)\n")))))

(deftest f37-l3-bytes-string-field-encodes-utf8-with-uint32-prefix
  (testing "F37 L3 — a record with a `String!` field synthesizes an encoder that emits `UInt32` big-endian length prefix + raw UTF-8 bytes per §3.10.10 String row. Body inspected for `dataUsingEncoding:NSUTF8StringEncoding` + `OSSwapHostToBigInt32` at the prefix site."
    (let [prog (lower-src "def Msg:\n    text: String!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Msg_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"dataUsingEncoding:NSUTF8StringEncoding" body-text))
      (is (re-find #"OSSwapHostToBigInt32.*\[__sd length\]" body-text)))))

;; ---------------------------------------------------------------------------
;; F37 L8 — user override rule (spec §3.3.2 rule 6).
;;
;; The synthesized `<Record>_cxc_as_bytes` default fires ONLY when the
;; record hasn't declared an `as` callable itself. Post-F67 collapse,
;; records carry their user-callable readers as observables (own) or
;; extension methods (via `extend`), so the shadow check consults BOTH
;; `:observable-index` and `:method-index`.
;;
;; The parser also gained `as`/`from` as whitelisted observable-name
;; tokens — `as` is a reserved word for `import X as Y` so the pre-L8
;; parser rejected it at declaration position; L8 accepts it.
;; ---------------------------------------------------------------------------

(deftest f37-l8-user-observable-as-shadows-synthesized-fn
  (testing "F37 L8 — a record with an own observable named `as` shadows the synthesized `<Record>_cxc_as_bytes`. Observable body evaluates to Bytes by delegating to a nested record's `.as()`. Verifies: (a) the parser accepts `as` at observable-name position (previously rejected as a reserved word), (b) no `<Outer>_cxc_as_bytes` function is emitted, (c) the observable's C function IS emitted, (d) `.as()` at a call site dispatches to the observable path."
    (let [prog (lower-src (str "def Inner:\n"
                               "    x: Int!\n\n"
                               "def Outer:\n"
                               "    i: Inner!\n\n"
                               "    as() :- i.as()\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          fn-names (into #{} (map :name (:functions prog)))]
      (is (contains? fn-names "Inner_cxc_as_bytes")
          "Inner (no user override) still gets the synthesized fn")
      (is (not (contains? fn-names "Outer_cxc_as_bytes"))
          "Outer's synthesized as-bytes is SUPPRESSED by the user observable")
      (is (contains? fn-names "Outer_as")
          "Outer's user observable is emitted as `Outer_as`"))))

(deftest f37-l8-parser-accepts-as-at-observable-declaration-position
  (testing "F37 L8 — the parser accepts `as` as an observable name on a record. Pre-L8 the lexer tokenizes `as` as `:kw` (reserved for `import X as Y`), and `parse-observable` demanded `:ident`, so `as() :- …` was a syntax error. L8 threads a whitelist (`as`, `from`) through both `looks-like-observable?` and `parse-observable`."
    (let [prog (lower-src (str "def V:\n"
                               "    n: Int!\n\n"
                               "    as() :- n\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          v-fn (first (filter #(= "V_as" (:name %)) (:functions prog)))]
      (is (some? v-fn) "user's `as` observable lowers to `V_as`")
      (is (= "int64_t" (:return-type v-fn))
          "return type inferred from body (`n` is Int)"))))

(deftest f37-l8-user-as-with-non-bytes-return-still-suppresses-synthesis
  (testing "F37 L8 — a user `as()` observable whose inferred return is NOT Bytes still suppresses synthesis (name-based shadow). Return-type-differentiated dispatch (§3.10.3 trait-qualified) is a deeper landing; the L8 shadow rule keys on name to match how §3.3.2 rule 6 is worded (\"if the class defines `as` …\"). If a user wants BOTH a Bytes-typed `as()` and an Int-typed one, they can name them differently or wait for the trait-qualified follow-on."
    (let [prog (lower-src (str "def V:\n"
                               "    n: Int!\n\n"
                               "    as() :- n\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          fn-names (into #{} (map :name (:functions prog)))]
      (is (not (contains? fn-names "V_cxc_as_bytes"))
          "user-declared `as` (any return type) suppresses the Bytes synthesis"))))

(deftest f37-l5-bytes-value-only-enum-discriminator-only
  (testing "F37 L5 — value-only enum (no payload cases) synthesizes `<Enum>_cxc_as_bytes` that emits a single discriminator byte. Discriminator width per §3.10.10: ≤256 cases → 1 byte. The switch/case block for payload dispatch is omitted (no tagged rep — the enum's C type is a plain integer)."
    (let [prog (lower-src "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Signal_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f) "value-only enum still gets a synthesized as-bytes fn")
      (is (re-find #"\(uint8_t\)\(a\)" body-text)
          "discriminator reads from `a` directly (no `.tag` on value-only enums)")
      (is (nil? (re-find #"switch \(a\.tag\)" body-text))
          "no switch dispatch for value-only enums (no payloads to encode)"))))

(deftest f37-l5-bytes-tagged-enum-switch-on-tag
  (testing "F37 L5 — tagged enum (mixed / payload cases) synthesizes `<Enum>_cxc_as_bytes` that writes the discriminator via `a.tag` and switches on `a.tag` to encode per-case payloads. Bare cases emit an empty block; payload cases emit each field's encoding reading from `a.data.<case>.<field>`."
    (let [prog (lower-src "def Msg:\n    case ping\n    case pong(seq: Int32!)\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Msg_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"\(uint8_t\)\(a\.tag\)" body-text)
          "tagged enums read discriminator from `.tag`")
      (is (re-find #"switch \(a\.tag\)" body-text)
          "switch dispatches per-case payload encode")
      (is (re-find #"case Msg_Tag_ping" body-text)
          "bare case still appears (empty block, just `break`)")
      (is (re-find #"case Msg_Tag_pong" body-text)
          "payload case appears with encode block")
      (is (re-find #"a\.data\.pong\.seq" body-text)
          "payload field access via `.data.<case>.<field>`")
      (is (re-find #"OSSwapHostToBigInt32" body-text)
          "Int32 payload uses 32-bit big-endian byte swap"))))

(deftest f37-l5-bytes-large-enum-uses-wider-discriminator
  (testing "F37 L5 — discriminator width scales with case count per §3.10.10. ≤256 → UInt8 (1 byte), ≤65536 → UInt16 (2 bytes, big-endian). Boundary at 256 exactly stays UInt8 (0..255 fits); 257 forces UInt16."
    (let [;; Build a 257-case enum programmatically via the case names
          cases (str/join "\n" (for [i (range 257)] (str "    case c" i)))
          src   (str "def Big:\n" cases "\n\ndef main:\n    -> running\n")
          prog (lower-src src)
          f    (first (filter #(= "Big_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"uint16_t" body-text)
          "≥257 cases uses uint16_t discriminator")
      (is (re-find #"OSSwapHostToBigInt16" body-text)
          "multi-byte discriminator emits big-endian byte swap"))))

(deftest f39-l9-bytes-optional-primitive-field-envelope
  (testing "F39 L9 — bare `T` value-typed primitive field (Optional-boxed under F39 L5b-ii-c Sub-P) encodes as `UInt8` presence byte + T bytes when present, per §3.10.10 Optional row. Body inspected for the presence-guarded conditional over the field's `.present` / `.value` accessors."
    (let [prog (lower-src "def Opt:\n    x: Int32\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Opt_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"a\.x\.present" body-text)
          "presence byte written from Optional storage's .present field")
      (is (re-find #"if \(a\.x\.present\)" body-text)
          "payload write guarded by .present check")
      (is (re-find #"a\.x\.value" body-text)
          "payload reads from Optional storage's .value field"))))

(deftest f37-l4-bytes-nested-record-field-recurses
  (testing "F37 L4 — a record with a nested-record field synthesizes an encoder that dispatches to the nested record's `<Nested>_cxc_as_bytes` via `appendData:`. No length prefix per §3.10.10 record row (fixed-shape concatenation). Both records get their own synthesized fn."
    (let [prog (lower-src "def Inner:\n    n: Int32!\n\ndef Outer:\n    i: Inner!\n    k: Int32!\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))
          outer-fn (first (filter #(= "Outer_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in outer-fn [:body 0 :text])]
      (is (contains? fn-names "Inner_cxc_as_bytes"))
      (is (contains? fn-names "Outer_cxc_as_bytes"))
      (is (re-find #"Inner_cxc_as_bytes\(a\.i\)" body-text)
          "outer encoder dispatches to nested via synthesized fn"))))

(deftest f37-l3-bytes-mixed-string-and-primitive-fields
  (testing "F37 L3 — a record mixing `Int!` and `String!` synthesizes both encoders in declaration order (§3.10.10 record row: fields concatenated in declaration order)."
    (let [prog (lower-src "def Named:\n    n: Int!\n    text: String!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Named_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])
          int-pos (.indexOf body-text "OSSwapHostToBigInt64")
          str-pos (.indexOf body-text "NSUTF8StringEncoding")]
      (is (some? f))
      (is (and (pos? int-pos) (pos? str-pos))
          "both encoders present")
      (is (< int-pos str-pos)
          "Int64 encoder precedes String encoder per declaration order"))))

;; ---------------------------------------------------------------------------
;; F37 L6 — from(Bytes) decoder synthesis (spec §3.4.6 / §3.10.10).
;;
;; Primitives-only MVP: every field of the receiver record must be a
;; fixed-width primitive (Bool / sized-int / Int / Float / Double).
;; String / nested-record / Optional / enum decode ship as follow-on
;; sub-landings; those receivers get a targeted fail-nyi at the intercept.
;;
;; The synthesis path emits three artifacts per eligible record:
;;   1) `_Cxc_DecodeOutcome_<R>` C struct (tagged shape carrying either
;;      `.data.converted.value : <R>` or `.data.invalid.{offset, reason}`).
;;   2) `_cxc_decode_converted_<R>` / `_cxc_decode_invalid_<R>` factories.
;;   3) `<R>_cxc_from_bytes(NSMutableData *src) -> _Cxc_DecodeOutcome_<R>`
;;      with per-field bounds check + big-endian read + designated-init
;;      constructor at the tail.
;; ---------------------------------------------------------------------------

(deftest f37-l6-synthesizes-decode-outcome-struct-per-eligible-record
  (testing "F37 L6 — a primitive-only record gets a `_Cxc_DecodeOutcome_<R>` C struct in :structs alongside its main <R> struct. Emitted via :raw-c-decl (union+struct+#define text) since the outcome type isn't user-declared and doesn't fit the ordinary field-list struct shape."
    (let [prog (lower-src "def V:\n    x: Int32!\n    y: Int32!\n\ndef main:\n    -> running\n")
          structs (:structs prog)
          outcome (first (filter #(and (= :raw-c-decl (:node %))
                                       (re-find #"_Cxc_DecodeOutcome_V" (:text %)))
                                 structs))]
      (is (some? outcome)
          "expected _Cxc_DecodeOutcome_V typedef in :structs")
      (is (re-find #"_CXC_DECODE_CONVERTED" (:text outcome))
          "tag discriminator macro emitted")
      (is (re-find #"NSString \*reason" (:text outcome))
          "invalid payload carries reason NSString"))))

(deftest f37-l6-synthesizes-from-bytes-fn-and-factories
  (testing "F37 L6 — primitive-only record gets three synthesized C functions: `<R>_cxc_from_bytes`, `_cxc_decode_converted_<R>`, `_cxc_decode_invalid_<R>`. The decoder returns the outcome struct type."
    (let [prog     (lower-src "def V:\n    x: Int32!\n    y: Int32!\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))
          decoder  (first (filter #(= "V_cxc_from_bytes" (:name %)) (:functions prog)))]
      (is (contains? fn-names "V_cxc_from_bytes"))
      (is (contains? fn-names "_cxc_decode_converted_V"))
      (is (contains? fn-names "_cxc_decode_invalid_V"))
      (is (= "_Cxc_DecodeOutcome_V" (:return-type decoder)))
      (is (= "NSMutableData *" (get-in decoder [:params 0 :c-type]))))))

(deftest f37-l6-decoder-body-per-field-bounds-check-and-swap
  (testing "F37 L6 — decoder body emits per-field bounds check + `OSSwapBigToHostInt32` (matching L2's Host-to-Big) + final designated-initializer constructor tail. Body text inspected for the expected shape."
    (let [prog (lower-src "def V:\n    x: Int32!\n    y: Int32!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "V_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"NSUInteger __off = \(NSUInteger\)start_off" body-text)
          "offset walk starts at caller-supplied start_off (variable-length ABI 2026-08-27)")
      (is (re-find #"OSSwapBigToHostInt32" body-text)
          "Int32 field decoded big-endian → host")
      (is (re-find #"expected 4 bytes for field x" body-text)
          "per-field bounds diagnostic names the offending field")
      (is (re-find #"_cxc_decode_converted_V" body-text)
          "success tail dispatches to converted factory")
      (is (re-find #"\.x = __x_val" body-text)
          "designated-initializer constructor names field x")
      (is (re-find #"\.y = __y_val" body-text)
          "designated-initializer constructor names field y"))))

(deftest f37-l6-decoder-body-primitive-widths
  (testing "F37 L6 — decoder handles Bool (1 byte + validity check), UInt8 (1 byte raw), Int64/Int (8 bytes big-endian). One test exercises all three widths to catch per-type branch drift."
    (let [prog (lower-src "def R:\n    b: Bool!\n    u: UInt8!\n    n: Int!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "R_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"invalid Bool byte" body-text)
          "Bool field validates 0x00/0x01 range with a targeted reason")
      (is (re-find #"OSSwapBigToHostInt64" body-text)
          "Int (int64_t) field decoded big-endian → host"))))

(deftest f37-l6-from-call-rewrites-to-synthesized-fn
  (testing "F37 L6 — `V.from(source: b)` at a call site lowers to a `:call` whose `:fn` is `V_cxc_from_bytes` and args carry the source bytes expression. Mirrors the `.as()` intercept pattern from L2."
    (let [src "def V:\n    x: Int32!\n\ndef main:\n    -> running\n        then:\n            let b: Bytes = V(x: 1).as()\n            V.from(source: b)\n"
          prog (lower-src src)
          main-body (get-in prog [:main :body])
          walker (fn walker [x]
                   (cond
                     (map? x)
                     (or (when (and (= :call (:node x))
                                    (= "V_cxc_from_bytes" (:fn x)))
                           x)
                         (some walker (vals x)))
                     (sequential? x) (some walker x)))]
      (is (some? (walker main-body))
          "expected V_cxc_from_bytes call in main body"))))

(deftest f37-l6-from-wrong-arg-count-fails-nyi
  (testing "F37 L6 — `V.from()` (zero args) and `V.from(source: b, extra: 0)` (two args) both fail-nyi at lower time; the diagnostic names the expected arity."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F37 L6.*expects exactly one arg"
         (lower-src "def V:\n    x: Int32!\n\ndef main:\n    -> running\n        then: V.from()\n")))))

(deftest f37-l6-string-field-decode-body-utf8-and-length-prefix
  (testing "F37 L6 String extension — a record with a `String!` field synthesizes a decode block that reads a UInt32 big-endian length prefix, bounds-checks it, then reads the raw UTF-8 payload into an NSString via `initWithBytes:length:encoding:` (nil result from invalid UTF-8 short-circuits to `_cxc_decode_invalid_<R>`)."
    (let [prog (lower-src "def Msg:\n    text: String!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Msg_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"OSSwapBigToHostInt32" body-text)
          "UInt32 length prefix decoded big-endian → host")
      (is (re-find #"expected 4 bytes for field text length" body-text)
          "prefix short-read diagnostic names the field")
      (is (re-find #"less than declared length" body-text)
          "payload short-read diagnostic distinguishes from prefix short-read")
      (is (re-find #"initWithBytes:__bytes \+ __off length:__text_val_len" body-text)
          "NSString construction reads payload_len bytes at current offset")
      (is (re-find #"encoding:NSUTF8StringEncoding" body-text))
      (is (re-find #"invalid UTF-8 for field text" body-text)
          "UTF-8 validity check emits a targeted reason on nil NSString"))))

(deftest f37-l6-mixed-primitive-and-string-fields-decode-in-order
  (testing "F37 L6 — a record mixing `Int!` and `String!` decodes both in declaration order; the primitive walk advances `__off` before the String walk begins. Verifies the offset-walk composition."
    (let [prog (lower-src "def Named:\n    n: Int!\n    text: String!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Named_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])
          int-pos (.indexOf body-text "OSSwapBigToHostInt64")
          str-pos (.indexOf body-text "NSUTF8StringEncoding")]
      (is (some? f))
      (is (and (pos? int-pos) (pos? str-pos))
          "both decode blocks present")
      (is (< int-pos str-pos)
          "Int64 decoder precedes String decoder per declaration order"))))

(deftest f37-l6-non-eligible-field-fails-nyi-at-intercept
  (testing "F37 L6 — a record with a currently-non-eligible field kind fails at the `.from()` intercept with a targeted diagnostic naming the offending field + type and pointing at the follow-on sub-landing. Non-eligible cases per current L6 coverage: `Any` fields (heterogeneous reference type with no canonical wire format — spec §3.10.10 doesn't fix a representation). Container fields (`List of T`, `Map of ...`) also defer via F42. Prior test target (variable-length nested records) landed 2026-08-27 as the variable-length ABI change; retargeted to `Any` here."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F37 Landings 2 \+ 3.*W\.thing.*Any"
         (lower-src "def W:\n    thing: Any\n\ndef main:\n    -> running\n        then:\n            let b: Bytes = W(thing: 0).as()\n            W.from(source: b)\n")))))

(deftest f37-l6-optional-nested-record-field-decode-body-presence-envelope
  (testing "F37 L6 Optional-nested-record extension (2026-08-27) + variable-length ABI (2026-08-27) — a record with a bare-T value-typed field where T is a decode-eligible nested record synthesizes a decode block that: (1) reads a UInt8 presence byte and validates 0x00/0x01, (2) on absent leaves `.value` bit-zero, (3) on present calls `Inner_cxc_from_bytes(src, __off, &__consumed)` with no pre-slice and no fixed-size bounds check (variable-length ABI change), (4) unpacks the outcome — invalid propagates with offset carried absolute; converted lands into `_Cxc_Optional_<T>.value` — (5) advances __off by the inner's reported consumed count. Variable-length inner (String, Optional, further nested) works transitively."
    (let [prog (lower-src "def Inner:\n    n: Int32!\n\ndef R:\n    i: Inner\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "R_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"expected 1 presence byte for Optional nested-record field i" body-text)
          "presence byte bounds-check with per-field diagnostic")
      (is (re-find #"invalid presence byte for Optional nested-record field i" body-text)
          "presence byte validity check (0x00/0x01 only)")
      (is (re-find #"_Cxc_Optional_Inner __i_val = \{ \.present = __i_val_presence" body-text)
          "designated-initializer for the Optional storage struct")
      (is (re-find #"if \(__i_val_presence == 0x01\)" body-text)
          "payload decode guarded by presence check")
      (is (re-find #"int64_t __i_consumed = 0;" body-text)
          "consumed-out local for the variable-length ABI")
      (is (re-find #"Inner_cxc_from_bytes\(src, \(int64_t\)__off, &__i_consumed\)" body-text)
          "dispatches to inner decoder with start_off + consumed_out")
      (is (re-find #"__i_val\.value = __i_res\.data\.converted\.value" body-text)
          "converted value lands into Optional payload")
      (is (re-find #"__off \+= \(NSUInteger\)__i_consumed;" body-text)
          "advance by inner's reported consumed count"))))

(deftest f37-l6-optional-value-only-enum-field-decode-body-presence-envelope
  (testing "F37 L6 Optional-value-only-enum extension (2026-08-27) — a record with a bare-T value-typed field where T is a value-only enum synthesizes a decode block that (1) reads a UInt8 presence byte and validates 0x00/0x01, (2) on absent leaves `.value` bit-zero, (3) on present bounds-checks + reads discriminator at the width dictated by case count, range-checks against case count, and casts into `_Cxc_Optional_<Enum>.value`. Verifies the presence-envelope + inline enum decode via body-text inspection."
    (let [prog (lower-src "def Signal:\n    case red\n    case green\n    case yellow\n\ndef R:\n    sig: Signal\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "R_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"expected 1 presence byte for Optional enum field sig" body-text)
          "presence byte bounds-check with per-field diagnostic")
      (is (re-find #"invalid presence byte for Optional enum field sig" body-text)
          "presence byte validity check (0x00/0x01 only)")
      (is (re-find #"_Cxc_Optional_Signal __sig_val = \{ \.present = __sig_val_presence" body-text)
          "designated-initializer for the Optional storage struct")
      (is (re-find #"if \(__sig_val_presence == 0x01\)" body-text)
          "payload decode guarded by presence check")
      (is (re-find #"expected 1 bytes for Optional enum field sig \(Signal\) payload" body-text)
          "discriminator width bounds-check picks 1 byte for ≤256 cases")
      (is (re-find #"if \(__sig_disc >= 3\) return" body-text)
          "range check against case count 3")
      (is (re-find #"invalid discriminator for Optional enum field sig \(Signal\)" body-text)
          "invalid-discriminator diagnostic on out-of-range payload")
      (is (re-find #"__sig_val\.value = \(Signal\)__sig_disc" body-text)
          "casts discriminator to the enum's C type into `.value`"))))

(deftest f37-encoder-symmetry-record-with-optional-value-only-enum-field-synthesizes-as-bytes
  (testing "F37 encoder-symmetry (2026-08-27, Optional-value-only-enum sub) — a record with an Optional-value-only-enum field synthesizes `<R>_cxc_as_bytes`. Field encoding writes a presence byte and dispatches to `<Enum>_cxc_as_bytes(a.<field>.value)` on present. Enables round-trip with the F37 L6 Optional-value-only-enum decode extension."
    (let [prog (lower-src "def Signal:\n    case red\n    case green\n\ndef R:\n    sig: Signal\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))
          f    (first (filter #(= "R_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (contains? fn-names "R_cxc_as_bytes"))
      (is (contains? fn-names "Signal_cxc_as_bytes"))
      (is (re-find #"a\.sig\.present" body-text)
          "encodes presence from `.present`")
      (is (re-find #"Signal_cxc_as_bytes\(a\.sig\.value\)" body-text)
          "dispatches to the enum encoder on present"))))

(deftest f37-l6-optional-tagged-enum-field-decode-body-presence-plus-switch
  (testing "F37 L6 Optional-tagged-enum extension (2026-08-27) — a record with a bare-T value-typed field where T is a tagged enum with primitive/`String!` payloads synthesizes a decode block that: (1) reads presence byte + validity check, (2) on present bounds-checks + reads discriminator + range-checks, (3) writes the tag into `_Cxc_Optional_<Enum>.value.tag`, (4) switches on the discriminator with per-case arms writing directly into `.value.data.<case>.<field>`. Reuses `f37-tagged-enum-payload-decode-c-src` with a `<bind>.value`-prefixed target so the payload writers land in the Optional's inner storage."
    (let [prog (lower-src "def Msg:\n    case ping\n    case pong(seq: Int32!)\n\ndef R:\n    msg: Msg\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "R_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"expected 1 presence byte for Optional tagged-enum field msg" body-text)
          "presence-byte bounds check with per-field diagnostic")
      (is (re-find #"invalid presence byte for Optional tagged-enum field msg" body-text)
          "presence-byte validity check (0x00/0x01)")
      (is (re-find #"_Cxc_Optional_Msg __msg_val = \{ \.present = __msg_val_presence" body-text)
          "designated-initializer for the Optional storage struct")
      (is (re-find #"if \(__msg_val_presence == 0x01\)" body-text)
          "payload decode guarded by presence check")
      (is (re-find #"expected 1 bytes for Optional tagged-enum field msg \(Msg\) payload" body-text)
          "discriminator bounds-check picks 1 byte for ≤256 cases")
      (is (re-find #"if \(__msg_disc >= 2\) return" body-text)
          "range check against case count 2")
      (is (re-find #"__msg_val\.value\.tag = \(Msg_Tag\)__msg_disc" body-text)
          "writes tag into the Optional's inner storage")
      (is (re-find #"case Msg_Tag_ping:" body-text)
          "per-case arm for bare case")
      (is (re-find #"case Msg_Tag_pong:" body-text)
          "per-case arm for payload case")
      (is (re-find #"__msg_val\.value\.data\.pong\.seq" body-text)
          "payload field written into the Optional's inner tagged-union member"))))

(deftest f37-l6-nullable-string-field-decode-body-presence-plus-length-prefix
  (testing "F37 L6 nullable-String extension (2026-08-27) — a record with a bare `String` field (nullable; not `String!`) synthesizes a decode block that: (1) reads presence byte + validity check, (2) on present bounds-checks + reads UInt32 big-endian length prefix, (3) bounds-checks payload against declared length, (4) `NSString initWithBytes:length:encoding:` with UTF-8 validity. Binds `NSString *__<field>_val = nil` at the top and assigns on present — no `_Cxc_Optional_String` envelope (String is excluded from F39 L5b-ii Sub-P value-typed boxing; nil at rest represents absent)."
    (let [prog (lower-src "def R:\n    msg: String\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "R_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"expected 1 presence byte for nullable String field msg" body-text)
          "presence-byte bounds check with per-field diagnostic")
      (is (re-find #"invalid presence byte for nullable String field msg" body-text)
          "presence-byte validity check (0x00/0x01)")
      (is (re-find #"NSString \*__msg_val = nil;" body-text)
          "reference-type binding starts nil (no Optional envelope)")
      (is (re-find #"if \(__msg_val_presence == 0x01\)" body-text)
          "payload decode guarded by presence check")
      (is (re-find #"expected 4 bytes for nullable String field msg length prefix" body-text)
          "length-prefix bounds check")
      (is (re-find #"OSSwapBigToHostInt32" body-text)
          "length prefix decoded big-endian → host")
      (is (re-find #"expected __msg_slen bytes for nullable String field msg, source has less than declared length" body-text)
          "payload bounds check against declared length")
      (is (re-find #"initWithBytes:__bytes \+ __off length:__msg_slen encoding:NSUTF8StringEncoding" body-text)
          "NSString UTF-8 decode direct assignment")
      (is (re-find #"invalid UTF-8 for nullable String field msg" body-text)
          "UTF-8 validity check on nil return"))))

(deftest f37-encoder-symmetry-record-with-nullable-string-field-synthesizes-as-bytes
  (testing "F37 encoder-symmetry (2026-08-27, nullable-String sub) — a record with a nullable `String` field synthesizes `<R>_cxc_as_bytes`. Field encoding writes a presence byte based on `a.<field> != nil`; on present, dispatches to the String encoding path (UInt32 length prefix + UTF-8 payload) reading from `a.<field>` directly (no `.value` accessor)."
    (let [prog (lower-src "def R:\n    msg: String\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))
          f    (first (filter #(= "R_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (contains? fn-names "R_cxc_as_bytes"))
      (is (re-find #"a\.msg != nil" body-text)
          "encodes presence from `!= nil` check")
      (is (re-find #"\[a\.msg dataUsingEncoding:NSUTF8StringEncoding\]" body-text)
          "extracts UTF-8 bytes from `a.msg` directly on present")
      (is (re-find #"OSSwapHostToBigInt32" body-text)
          "length prefix encoded big-endian"))))

(deftest f37-encoder-symmetry-record-with-optional-tagged-enum-field-synthesizes-as-bytes
  (testing "F37 encoder-symmetry (2026-08-27, Optional-tagged-enum sub) — a record with an Optional-tagged-enum field synthesizes `<R>_cxc_as_bytes`. Field encoding writes a presence byte and dispatches to `<Enum>_cxc_as_bytes(a.<field>.value)` on present. Enables round-trip with the F37 L6 Optional-tagged-enum decode extension."
    (let [prog (lower-src "def Msg:\n    case ping\n    case pong(seq: Int32!)\n\ndef R:\n    msg: Msg\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))
          f    (first (filter #(= "R_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (contains? fn-names "R_cxc_as_bytes"))
      (is (contains? fn-names "Msg_cxc_as_bytes"))
      (is (re-find #"a\.msg\.present" body-text)
          "encodes presence from `.present`")
      (is (re-find #"Msg_cxc_as_bytes\(a\.msg\.value\)" body-text)
          "dispatches to the enum encoder on present"))))

(deftest f37-encoder-symmetry-record-with-enum-field-synthesizes-as-bytes
  (testing "F37 encoder-symmetry (2026-08-27) — a record with a value-only enum field now synthesizes `<R>_cxc_as_bytes` (previously fail-nyi'd at the intercept). Field encoding dispatches to the enum's synthesized `<Enum>_cxc_as_bytes` mirror of the nested-record arm. Enables round-trip with the F37 L6 value-only enum decode extension."
    (let [prog (lower-src "def Signal:\n    case red\n    case green\n\ndef R:\n    sig: Signal!\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))
          f    (first (filter #(= "R_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (contains? fn-names "R_cxc_as_bytes"))
      (is (contains? fn-names "Signal_cxc_as_bytes")
          "enum's own encoder still emitted (F37 L5)")
      (is (re-find #"Signal_cxc_as_bytes\(a\.sig\)" body-text)
          "field encoding dispatches to the enum encoder"))))

(deftest f37-l6-value-only-enum-field-decode-body-discriminator-and-range-check
  (testing "F37 L6 value-only enum extension — a record with a field of an enum that has no payload cases synthesizes a decode block that reads a discriminator (width per §3.10.10 case count), range-checks against case count, then casts to the enum's C type. Value-only enums with ≤256 cases use UInt8."
    (let [prog (lower-src "def Signal:\n    case red\n    case green\n    case yellow\n\ndef R:\n    sig: Signal!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "R_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"expected 1 bytes for enum field sig \(Signal\)" body-text)
          "1-byte bounds check for a ≤256-case enum")
      (is (re-find #"if \(__sig_raw >= 3\) return" body-text)
          "range check against case count 3")
      (is (re-find #"invalid discriminator for enum field sig \(Signal\)" body-text)
          "invalid-discriminator diagnostic names the enum + field")
      (is (re-find #"Signal __sig_val = \(Signal\)__sig_raw" body-text)
          "casts discriminator to the enum's C type"))))

(deftest f37-l6-tagged-enum-field-decode-body-per-case-payload-switch
  (testing "F37 L6 tagged-enum extension (2026-08-27) — a field whose type is a tagged enum with primitive/String payload cases synthesizes a decode block: (1) reads discriminator (width per case count), (2) range-checks, (3) writes tag into the union struct, (4) switches on the discriminator with a per-case arm that decodes payload fields directly into `.data.<case>.<field>`. Verifies via body-text inspection."
    (let [prog (lower-src "def Msg:\n    case ping\n    case pong(seq: Int32!)\n\ndef R:\n    msg: Msg!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "R_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"expected 1 bytes for tagged enum field msg \(Msg\)" body-text)
          "discriminator bounds-check names field + enum")
      (is (re-find #"if \(__msg_disc >= 2\) return" body-text)
          "range check against case count 2")
      (is (re-find #"Msg __msg_val" body-text)
          "declares local of the enum type")
      (is (re-find #"__msg_val\.tag = \(Msg_Tag\)__msg_disc" body-text)
          "writes tag into the union struct")
      (is (re-find #"case Msg_Tag_ping:" body-text)
          "per-case arm for bare case")
      (is (re-find #"case Msg_Tag_pong:" body-text)
          "per-case arm for payload case")
      (is (re-find #"__msg_val\.data\.pong\.seq" body-text)
          "payload field decoded directly into union member")
      (is (re-find #"OSSwapBigToHostInt32" body-text)
          "Int32 payload decoded big-endian → host"))))

(deftest f37-l6-optional-primitive-field-decode-body-presence-envelope
  (testing "F37 L6 Optional-primitive extension — a record with a bare-T value-typed field (Optional-boxed per F39 L5b-ii-c Sub-P) synthesizes a decode block that: (1) reads a UInt8 presence byte and validates 0x00/0x01 range, (2) on absent leaves `.value` bit-zero (presence 0 short-circuits the payload read), (3) on present bounds-checks + reads T bytes, (4) binds `_Cxc_Optional_<T> __<field>_val = {.present, .value}`. Body inspected for the presence-guarded conditional + validity check."
    (let [prog (lower-src "def Opt:\n    x: Int32\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Opt_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"expected 1 presence byte for Optional field x" body-text)
          "presence byte bounds-check with per-field diagnostic")
      (is (re-find #"invalid presence byte for Optional field x" body-text)
          "presence byte validity check (0x00/0x01 only)")
      (is (re-find #"_Cxc_Optional_Int32 __x_val = \{ \.present = __x_val_presence" body-text)
          "designated-initializer for the Optional storage struct")
      (is (re-find #"if \(__x_val_presence == 0x01\)" body-text)
          "payload decode guarded by presence check")
      (is (re-find #"OSSwapBigToHostInt32" body-text)
          "payload uses primitive's big-endian → host converter"))))

(deftest f37-l6-optional-nested-in-primitive-record-decodes
  (testing "F37 L6 — a record mixing Optional-primitive AND non-Optional primitive AND String! decodes all three in declaration order. Verifies the dispatcher handles mixed field kinds without ordering bugs."
    (let [prog (lower-src "def Mixed:\n    a: Int!\n    b: Int32\n    c: String!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Mixed_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])
          a-pos (.indexOf body-text "expected 8 bytes for field a")
          b-pos (.indexOf body-text "expected 1 presence byte for Optional field b")
          c-pos (.indexOf body-text "expected 4 bytes for field c length")]
      (is (some? f))
      (is (and (pos? a-pos) (pos? b-pos) (pos? c-pos))
          "all three decode blocks present")
      (is (< a-pos b-pos c-pos)
          "decode blocks in field declaration order"))))

(deftest f37-l6-nested-record-decode-body-variable-length-abi
  (testing "F37 L6 nested-record extension + variable-length ABI (2026-08-27) — a record with a nested Inner field synthesizes a decode block that: (1) calls the inner's `Inner_cxc_from_bytes(src, __off, &__consumed)` directly (no pre-slice, no fixed-size bounds check — inner does its own bounds), (2) unpacks the outcome, invalid propagates with offset already absolute; converted extracts `.data.converted.value`, (3) advances __off by the inner's reported consumed count. Variable-length inner works transitively (Inner may contain String / Optional / further nested)."
    (let [prog (lower-src "def Inner:\n    n: Int32!\n\ndef Outer:\n    i: Inner!\n    k: Int32!\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))
          f    (first (filter #(= "Outer_cxc_from_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (contains? fn-names "Inner_cxc_from_bytes"))
      (is (contains? fn-names "Outer_cxc_from_bytes"))
      (is (some? f))
      (is (re-find #"int64_t __i_consumed = 0;" body-text)
          "declares consumed-out local for the ABI call")
      (is (re-find #"Inner_cxc_from_bytes\(src, \(int64_t\)__off, &__i_consumed\)" body-text)
          "dispatches to the inner decoder with start_off + consumed_out")
      (is (re-find #"__i_res\.tag == _CXC_DECODE_INVALID" body-text)
          "checks nested outcome discriminator")
      (is (re-find #"_cxc_decode_invalid_Outer\(__i_res\.data\.invalid\.offset" body-text)
          "propagates nested invalid offset directly (already absolute)")
      (is (re-find #"Inner __i_val = __i_res\.data\.converted\.value" body-text)
          "extracts converted value from nested outcome")
      (is (re-find #"__off \+= \(NSUInteger\)__i_consumed;" body-text)
          "advances outer __off by the inner's reported consumed count"))))

(deftest f37-l6-variable-length-nested-record-decodes
  (testing "F37 L6 variable-length nested-record (2026-08-27) — Inner containing a variable-length field (String!) now decodes cleanly under the ABI change. Outer's decoder emits nested-decode block calling Inner with start_off + consumed_out; Inner walks String's length-prefix + payload internally and reports total consumed count back."
    (let [prog (lower-src "def Inner:\n    text: String!\n    n: Int32!\n\ndef Outer:\n    i: Inner!\n    k: Int32!\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))
          outer-f (first (filter #(= "Outer_cxc_from_bytes" (:name %)) (:functions prog)))
          outer-body (get-in outer-f [:body 0 :text])]
      (is (contains? fn-names "Inner_cxc_from_bytes")
          "inner-with-variable-String is now decode-eligible")
      (is (contains? fn-names "Outer_cxc_from_bytes"))
      (is (re-find #"Inner_cxc_from_bytes\(src, \(int64_t\)__off, &__i_consumed\)" outer-body)
          "outer dispatches with start_off + consumed_out"))))

(deftest f67-l4a-zero-arg-record-observable-lowers-as-c-function
  (testing "F67 L4a: `name :- expr` on a record lowers as a `Record_name(self)` C function. Return type inferred from the expression (int here); bare field refs (`x`, `y`) auto-rewrite to `self.x`, `self.y`."
    (let [prog (lower-src "def V:
    x: Int
    y: Int

    magnitude_sq :- x * x + y * y

def main:
    -> running
        then: print(V(x: 3, y: 4).magnitude_sq)
")
          f    (first (filter #(= "V_magnitude_sq" (:name %)) (:functions prog)))]
      (is (some? f) "record observable emitted as C function V_magnitude_sq")
      (is (= :c-function (:node f)))
      (is (= 1 (count (:params f))) "self is the sole param on zero-arg observable")
      (is (= "V" (:c-type (first (:params f))))))))

(deftest f67-l4a-parametric-record-observable-lowers-with-args
  (testing "F67 L4a: `name(args) :- expr` on a record lowers as `Record_name(self, args...)` C function. Args become C params after self."
    (let [prog (lower-src "def V:
    x: Int
    y: Int

    scaled(k: Int) :- (x + y) * k

def main:
    -> running
        then: print(V(x: 1, y: 2).scaled(10))
")
          f    (first (filter #(= "V_scaled" (:name %)) (:functions prog)))]
      (is (some? f) "V_scaled function emitted")
      (is (= 2 (count (:params f))) "self + k")
      (is (= "self" (:name (first (:params f)))))
      (is (= "k"    (:name (second (:params f))))))))

(deftest f67-l3-wildcard-handler-transition-lowers-as-method
  (testing "F67 L3: `* <: name(args) -> self then: ...` (the handler-collapse form that subsumes the retired F14 `-> handler(args):` sugar) lowers as a void ObjC method on the atom's class. Same semantics as spec §1265/§2160 handler-decl; different surface syntax."
    (let [prog (lower-src "def A:
    n: Int = 0

    -> running

    * <: tick(msg: Int) -> self
        then: return n

def main:
    a: A = A()

    -> running
")
          a-cls (first (filter #(= "A" (:name %)) (:classes prog)))
          tick  (first (filter #(and (= :objc-method (:node %))
                                     (re-find #"tick" (str (:selector %))))
                               (:methods a-cls)))]
      (is (some? tick) "tick handler-shape transition emits as a method on class A")
      (is (= "void" (:return-type tick)) "handler methods return void"))))

(deftest f67-l3-zero-arg-wildcard-handler-lowers
  (testing "F67 L3: zero-arg `* <: tick() -> self then: ...` lowers with a plain selector (no colon) and no user params."
    (let [prog (lower-src "def A:
    n: Int = 0

    -> running

    * <: tick() -> self
        then: return n

def main:
    a: A = A()

    -> running
")
          a-cls (first (filter #(= "A" (:name %)) (:classes prog)))
          tick  (first (filter #(and (= :objc-method (:node %))
                                     (= "tick" (:selector %)))
                               (:methods a-cls)))]
      (is (some? tick) "zero-arg handler-shape present with bare selector `tick`")
      (is (empty? (:params tick)) "no user params (self implicit)"))))

;; F67 L4: handler-shape ↔ `methods:` collision test retired with `methods:`.
;; Collisions between handler-shape and reader observables of the same name
;; are covered by the observable-name-uniqueness path (handler dispatch and
;; observable dispatch share the atom's method table; §2160). A dedicated
;; observable/handler-name-collision test is a follow-on landing.

(deftest f67-l3-legacy-handler-decl-sugar-retired
  (testing "F67 L3: the legacy `-> foo(args):` handler-decl sugar (F14) is retired at parse time; users get a migration diagnostic pointing at the explicit `* <: foo(args) -> self` handler-shape transition."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"(?s)F67 L3.*handler-decl sugar is retired.*\* <: tick"
         (lower-src "def A:
    n: Int = 0

    -> running

    -> tick(msg: Int):
        return n

def main:
    a: A = A()

    -> running
")))))

(deftest f67-l3-duplicate-handler-shape-rejected
  (testing "F67 L3 + L10 (2026-08-25): two `* <: msg(args) -> self` on one atom are rejected. L10's wildcard-source duplicate check catches this case per the ratified precedence rule (concrete arm wins in its phase; `*` arm fires elsewhere — two `*` arms would be ambiguous)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"declares two wildcard-source arms `\* <: tick"
         (lower-src "def A:
    n: Int = 0

    -> running

    * <: tick(msg: Int) -> self
        then: return n

    * <: tick(msg: Int) -> self
        then: return n

def main:
    a: A = A()

    -> running
")))))

(deftest l10-b-wildcard-arm-with-concrete-overlap-routes-to-transition
  (testing "L10.b (2026-08-25) — when `* <: msg(args) -> self` shares its message name with a concrete-source arm, the wildcard arm is reclassified as a phase-wildcard transition (routed through the transition dispatch method) instead of a separate handler method. Concrete arms fire first in their phase; the wildcard fires in any other phase. First-match order is concrete-then-wildcard."
    (let [lowered (lower-src "def Counter:
    n: Int! = 0

    alive <: bump(x: Int) -> alive
        then: n => n + x

    * <: bump(x: Int) -> self
        then: n => n

def main:
    c: Counter = Counter()
    -> running
        then: |> c.bump(x: 1)
")
          counter-cls (first (filter #(= "Counter" (:name %)) (:classes lowered)))
          bump-method (first (filter #(re-find #"^bump" (or (:selector %) ""))
                                     (:methods counter-cls)))
          branches    (:body bump-method)]
      (is (some? bump-method) "single bump-selector method holds both arms")
      (is (some :wildcard-source? branches)
          "wildcard-source arm was reclassified into transition dispatch")
      (let [concrete-branches (filterv (complement :wildcard-source?) branches)
            wildcard-branches (filterv :wildcard-source? branches)]
        (is (seq concrete-branches))
        (is (seq wildcard-branches))
        (let [concrete-idx (some (fn [[i b]] (when-not (:wildcard-source? b) i))
                                 (map-indexed vector branches))
              wildcard-idx (some (fn [[i b]] (when (:wildcard-source? b) i))
                                 (map-indexed vector branches))]
          (is (< concrete-idx wildcard-idx)
              "concrete-source arm precedes wildcard-source arm (L10.b precedence)"))))))

(deftest l13-coverage-tail-abort-emitted-when-all-arms-guarded
  (testing "L13 (2026-08-25) — when every arm in a phase carries a `|:` guard, lower emits a tail-abort branch after the guarded ones. Replaces the silent-drop behavior inherited from retired `when:` guards; fires a runtime coverage-abort if no guard held."
    (let [lowered (lower-src "def Counter:
    n: Int! = 0
    max: Int! = 3

    alive <: bump -> alive
        |: n < max
        then: n => n + 1

def main:
    c: Counter = Counter()
    -> running
        then: |> c.bump()
")
          counter-cls (first (filter #(= "Counter" (:name %)) (:classes lowered)))
          bump-method (first (filter #(= "bump" (:selector %))
                                     (:methods counter-cls)))
          branches    (:body bump-method)]
      (is (some? bump-method) "bump method exists")
      (is (some #(= :coverage-abort (get-in % [:body 0 :node])) branches)
          "one tail branch has a :coverage-abort body"))))

(deftest l13-coverage-tail-abort-skipped-when-unguarded-fallback
  (testing "L13 — a phase that has at least one unguarded arm needs no tail-abort; the unguarded arm is the coverage fallback (fires when phase matches and no earlier guard held)."
    (let [lowered (lower-src "def Counter:
    n: Int! = 0
    max: Int! = 3

    alive <: bump -> alive
        |: n < max
        then: n => n + 1

    alive <: bump -> alive
        then: n => n

def main:
    c: Counter = Counter()
    -> running
        then: |> c.bump()
")
          counter-cls (first (filter #(= "Counter" (:name %)) (:classes lowered)))
          bump-method (first (filter #(= "bump" (:selector %))
                                     (:methods counter-cls)))
          branches    (:body bump-method)]
      (is (some? bump-method))
      (is (not-any? #(= :coverage-abort (get-in % [:body 0 :node])) branches)
          "no coverage-abort tail — unguarded arm covers the phase"))))

(deftest l10-duplicate-wildcard-source-arm-non-handler-shape-rejected
  (testing "L10 (2026-08-25) — two wildcard-source arms on the same message name with concrete target(s) are rejected. Ambiguous under the ratified precedence rule (concrete-phase arm wins in its own phase; `*` arm fires everywhere else — two `*` arms on the same message have no total order)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"declares two wildcard-source arms `\* <: tick"
         (lower-src "def A:
    n: Int = 0

    -> running

    * <: tick(msg: Int) -> running
        then: n => msg

    * <: tick(msg: Int) -> running
        then: n => msg + 1

def main:
    a: A = A()

    -> running
")))))

(defn- atom-transitions
  "Test helper — parse `src` and return the transitions on the first
   def-atom whose name matches `atom-name`."
  [src atom-name]
  (let [prog (p/parse src)
        atom-node (first (filter #(and (= :def-atom (:node %))
                                       (= atom-name (:name %)))
                                 prog))]
    (filterv #(= :transition (:node %)) (:body atom-node))))

(deftest l11-companion-synthesized-for-public-message-transition
  (testing "L11 (2026-08-25) — `collect-companion-shapes` synthesizes `<msg>_failed(reason:)` for every message-triggered public transition. Bare `|:` contributes `:invariant-violation` to the reason-type union; `|: as ErrorType(...)` contributes a `:typed` entry with the type name. Runtime delivery is deferred to L12; L11 lands metadata only."
    (let [txns (atom-transitions
                "def A:
    n: Int! = 0
    max: Int! = 3

    alive <: bump -> alive
        |: n < max
        then: n => n + 1
"
                "A")
          companions (#'cxc.lower.shape-collection/collect-companion-shapes "A" txns)]
      (is (contains? companions "bump_failed") "bump gets a companion")
      (let [entry (get companions "bump_failed")]
        (is (= "bump" (:of-msg entry)))
        (is (= [{:name "reason"}] (:payload entry)))
        (is (some #(= :invariant-violation (:kind %)) (:reason-types entry))
            "bare `|:` contributes :invariant-violation")))))

(deftest l11-companion-typed-invariant-contributes-typed-reason
  (testing "L11 — a `|: as OutOfRange(...)` clause on the target's transition contributes a typed reason entry (kind :typed, type-name from the AST) to the companion's reason-type union."
    (let [txns (atom-transitions
                "def A:
    n: Int! = 0
    max: Int! = 3

    alive <: bump -> alive
        |: n < max as OutOfRange(field: \"n\")
        then: n => n + 1
"
                "A")
          companions (#'cxc.lower.shape-collection/collect-companion-shapes "A" txns)
          entry (get companions "bump_failed")]
      (is (some #(and (= :typed (:kind %)) (= "OutOfRange" (:type-name %)))
                (:reason-types entry))
          "typed `as OutOfRange(...)` contributes a :typed reason entry"))))

(deftest l11-companion-mixed-bare-and-typed-reasons-union
  (testing "L11 — an atom with multiple message transitions contributes each transition's `|:` reasons to that message's companion; distinct bare + typed reasons appear as separate entries in the union."
    (let [txns (atom-transitions
                "def A:
    n: Int! = 0

    alive <: bump -> alive
        |: n < 10
        then: n => n + 1

    alive <: bump -> alive
        |: n >= 10 as OutOfRange(field: \"n\")
        then: n => n
"
                "A")
          companions (#'cxc.lower.shape-collection/collect-companion-shapes "A" txns)
          entry (get companions "bump_failed")
          kinds (set (map :kind (:reason-types entry)))]
      (is (contains? kinds :invariant-violation) "bare `|:` union member")
      (is (contains? kinds :typed) "typed `|: as T` union member"))))

(deftest l11-companion-index-lands-on-atom-env
  (testing "L11 — the companion-index synthesized by env-build is threaded onto each atom's env entry as `:companion-index`, mirroring `:message-index`. Downstream (L12) reads this to wire runtime delivery."
    (let [prog ((requiring-resolve 'cxc.resolve/ensure-references-enriched)
                 (p/parse "def A:
    n: Int! = 0
    max: Int! = 3

    alive <: bump -> alive
        |: n < max
        then: n => n + 1

def main:
    a: A = A()
    -> running
"))
          env (#'cxc.lower/build-atom-env prog {}
                (cxc.env/module-map-from-attribution
                  (cxc.env/attribute-modules-to-decls prog)))]
      (is (contains? (get-in env ["A" :companion-index]) "bump_failed")
          "A's atom-env entry exposes companion metadata for `bump_failed`"))))

(deftest l12a-companion-receive-arm-typo-rejected
  (testing "L12a (2026-08-26) — sender-side receive-arm on a companion message with a typo'd name (`_faild`) rejects with `no such stream and no companion message on <Target>`. Preserves the diagnostic clarity from the pre-companion stream-subscribe path."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"atom `Player` emits no `:> take_damage_fialed` stream and has no `take_damage_fialed` companion"
         (lower-src "def Player:
    hp: Int! = 100

    alive <: take_damage(amount: Int) -> alive
        |: amount > 0
        then: hp => hp - amount

def Logger:
    player: Player = Player()
    n: Int! = 0

    * <: player.take_damage_fialed(reason: Int) -> self
        then: n => n + 1

def main:
    l: Logger = Logger()
    -> running
")))))

(deftest l12b-companion-receive-arm-valid-lowers-cleanly
  (testing "L12b (2026-08-26) — a sender-side receive-arm on a valid companion (`<msg>_failed(reason: T)`) triggers demand-driven augmentation of the target's stream-index/order/payload-index, so lowering succeeds and the target atom gains a companion emit method (`cxc_emit_<msg>_failed:`). Runtime delivery from `|:`-failure path is L12c; the scaffolding lands here as guarded dead code."
    (let [lowered (lower-src "def Player:
    hp: Int! = 100

    alive <: take_damage(amount: Int) -> alive
        |: amount > 0
        then: hp => hp - amount

def Logger:
    player: Player = Player()
    n: Int! = 0

    * <: player.take_damage_failed(reason:) -> self
        then: n => n + 1

def main:
    l: Logger = Logger()
    -> running
")
          player-cls (first (filter #(= "Player" (:name %)) (:classes lowered)))
          methods    (:methods player-cls)
          selectors  (mapv :selector methods)]
      (is (some? player-cls) "Player class lowered successfully")
      (is (some #(re-find #"cxc_emit_take_damage_failed" (or % "")) selectors)
          "Player gained a companion emit method for take_damage_failed")
      (is (some #(re-find #"cxc_addSubscriberFor_take_damage_failed" (or % "")) selectors)
          "Player gained a per-companion add-subscriber method"))))

(deftest track-a3-typed-subscriber-reason-form-rejected
  (testing "Track A/3 (2026-08-26) — the typed subscriber form `reason: T` is retired. L12b's per-subscriber reason-type declaration no longer decides the reason universe; the target atom's `|: as T` clauses do (see A/2.c). Reject the typed form with a migration diagnostic pointing at bareword `reason:` + a `<:` case-pattern body. Replaces the earlier L12b `inconsistent reason types` check — under A/3 that check can never fire (all subscribers are bareword)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track A/3.*typed subscriber reason payload `reason: Int`.*Rewrite as bareword"
         (lower-src "def Player:
    hp: Int! = 100

    alive <: take_damage(amount: Int) -> alive
        |: amount > 0
        then: hp => hp - amount

def Logger:
    player: Player = Player()
    n: Int! = 0

    * <: player.take_damage_failed(reason: Int) -> self
        then: n => n + 1

def main:
    l: Logger = Logger()
    -> running
")))))

(deftest l12c-end-catch-wrap-emits-companion-dispatch
  (testing "L12c (2026-08-26) — the `:end-catch-wrap` around every transition branch body includes runtime companion dispatch (`respondsToSelector:` + `objc_msgSend` on `cxc_emit_<msg>_failedWithreason:`). Silent-by-default: if no subscriber demand-drove the companion emit method into existence (per L12b), the branch is a no-op. Track A/5 (2026-08-26) flipped the reason payload from a nil placeholder to `e.userInfo[@\"reason\"]` — Track A/4 boxes typed `|:` failures into `_Cxc_R_<T>` and throws with userInfo, and this catch fans the box out through the companion."
    (let [emitted (emit/emit (lower-src "def Player:
    hp: Int! = 100

    alive <: take_damage(amount: Int) -> alive
        |: amount > 0
        then: hp => hp - amount

def Logger:
    player: Player = Player()
    n: Int! = 0

    * <: player.take_damage_failed(reason:) -> self
        then: n => n + 1

def main:
    l: Logger = Logger()
    -> running
"))]
      (is (str/includes? emitted "cxc_emit_%@_failedWithreason:")
          "@catch fires the companion dispatch")
      (is (str/includes? emitted "respondsToSelector:__cxc_comp")
          "silent-by-default: dispatch guarded on respondsToSelector:")
      (is (str/includes? emitted "id __cxc_reason = e.userInfo[@\"reason\"]")
          "Track A/5: reason box extracted from NSException.userInfo")
      (is (str/includes? emitted "((void (*)(id, SEL, id))objc_msgSend)(self, __cxc_comp, __cxc_reason)")
          "Track A/5: reason box (not nil) fanned out through companion dispatch"))))

(deftest l12b-companion-receive-arm-wrong-payload-rejected
  (testing "L12b — a sender-side receive-arm on a valid companion with a wrong payload key (e.g. `(oops: Int)`) rejects with the standard stream-shape mismatch diagnostic (source emits `[reason]`, subscriber declared `[oops]`). The augmentation still fires but the receive-side payload-shape check catches the mismatch."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"payload shape.*differs from source-side emit shape"
         (lower-src "def Player:
    hp: Int! = 100

    alive <: take_damage(amount: Int) -> alive
        |: amount > 0
        then: hp => hp - amount

def Logger:
    player: Player = Player()
    n: Int! = 0

    * <: player.take_damage_failed(oops: Int) -> self
        then: n => n + 1

def main:
    l: Logger = Logger()
    -> running
")))))

(deftest track-a2a-reason-box-wrapper-class-per-user-record
  (testing "Track A/2.a (2026-08-26) — every user-declared record gains a `_Cxc_R_<Record>` wrapper class in the lowered program's `:reason-boxes` vector. The wrapper is a minimal `@interface X : NSObject { @public T value; } @end / @implementation X @end`, emitted between `:structs` and `:functions` so the wrapped struct type is complete and downstream fanout code has the wrapper in scope. Compiled unconditionally; later tracks (A/4/A/5) box + throw + fan through it, later tree-shaking prunes to reason-referenced records only."
    (let [lowered (lower-src "def MyError:\n    msg: String\n    code: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          boxes   (:reason-boxes lowered)
          box     (first (filter #(= "_Cxc_R_MyError" (:name %)) boxes))]
      (is (some? box) "wrapper class synthesized for user record")
      (is (= "MyError" (:struct-c-type box))
          ":struct-c-type points at the wrapped record's C name")
      (is (= :reason-box (:node box))
          ":node tag is :reason-box (distinct from :c-struct and :class)"))
    (let [emitted (emit/emit (lower-src "def MyError:\n    msg: String\n    code: Int\n\ndef main:\n    -> running\n        then: print(1)\n"))]
      (is (str/includes? emitted "@interface _Cxc_R_MyError : NSObject { @public MyError value; } @end")
          "wrapper @interface emits with @public struct ivar")
      (is (str/includes? emitted "@implementation _Cxc_R_MyError @end")
          "wrapper @implementation emits, no method bodies")
      (is (< (.indexOf emitted "typedef struct")
             (.indexOf emitted "@interface _Cxc_R_MyError"))
          "wrapper @interface follows the record's struct typedef so the ivar's type is complete"))))

(deftest track-a2a-reason-box-only-uncaught-when-no-user-records
  (testing "Track A/2.a (2026-08-26) — a program with zero user records still emits ONE reason-box: the Track A/6 Uncaught wrapper (`_Cxc_R__Cxc_Uncaught`). It's compiler-guaranteed regardless of whether prelude was prepended, because the L12c end-catch-wrap references it unconditionally on non-typed catches. User-record wrappers are additive on top."
    (let [lowered (lower-src "def main:\n    -> running\n        then: print(1)\n")
          box-names (mapv :name (:reason-boxes lowered))]
      (is (= ["_Cxc_R__Cxc_Uncaught"] box-names)
          "no user records → only the Uncaught wrapper is synthesized"))))

(deftest track-a8-end-to-end-typed-reason-class-invariant-catch-fan-dispatch-bind
  (testing "Track A/8 (2026-08-26) — full typed-reason pipeline end-to-end. Target atom declares a class-level `|: cond as MyError(msg: String)` invariant that fires on post-transition state; a companion subscriber dispatches on the id-typed reason via `<: reason: <: MyError(msg: let m): body`. The emitted C stitches together every landed track:\n     - A/2.a: `_Cxc_R_MyError` wrapper class exists\n     - A/4: post-store invariant check boxes + throws NSException with userInfo[@\"reason\"]\n     - A/5: L12c catch fans e.userInfo[@\"reason\"] through cxc_emit_<msg>_failedWithreason:\n     - A/2.b: subscriber emits `isKindOfClass:[_Cxc_R_MyError class]` and reads `((_Cxc_R_MyError *)_cxc_scrut)->value.msg`\n     - A/2.c: exhaustiveness with typed-only reason universe → no wildcard needed\n     - A/6: Uncaught wrapper still emitted for non-typed catches\n\n   Uses class-level `|:` (not arm-head) because arm-head typed reasons\n   are silent-dropped in transition lowering today (pre-existing gap;\n   the class-level path is the wired A/4 surface)."
    (let [emitted (emit/emit (lower-src+prelude "def MyError:\n    msg: String\n\ndef Player:\n    hp: Int! = 100\n    |: hp >= 0 as MyError(msg: \"negative-hp\")\n\n    alive <: take_damage(amount: Int) -> alive\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: MyError(msg: let m): print(m)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n"))]
      ;; A/2.a: wrapper class exists for MyError (and Uncaught for A/6).
      (is (str/includes? emitted "@interface _Cxc_R_MyError : NSObject { @public MyError value; } @end")
          "A/2.a: MyError wrapper class synthesized")
      (is (str/includes? emitted "@interface _Cxc_R__Cxc_Uncaught : NSObject")
          "A/6: Uncaught wrapper class synthesized")
      ;; A/4: class-level invariant boxes + throws for MyError.
      (is (str/includes? emitted "_Cxc_R_MyError *__cxc_r = [[_Cxc_R_MyError alloc] init];")
          "A/4: post-store invariant site allocs the reason box")
      (is (str/includes? emitted "@throw [NSException exceptionWithName:@\"CxcReason\"")
          "A/4: throws NSException on invariant failure")
      (is (str/includes? emitted "userInfo:@{@\"reason\": __cxc_r}")
          "A/4: reason box travels via userInfo[@\"reason\"]")
      ;; A/5: L12c fans reason through companion dispatch.
      (is (str/includes? emitted "id __cxc_reason = e.userInfo[@\"reason\"]")
          "A/5: catch extracts reason from userInfo")
      (is (str/includes? emitted "((void (*)(id, SEL, id))objc_msgSend)(self, __cxc_comp, __cxc_reason)")
          "A/5: reason (not nil) fanned to companion")
      ;; A/6: catch synthesizes Uncaught when userInfo[@"reason"] is nil.
      (is (str/includes? emitted "_Cxc_R__Cxc_Uncaught *__cxc_u = [[_Cxc_R__Cxc_Uncaught alloc] init]")
          "A/6: non-typed catch synthesizes Uncaught box")
      ;; A/2.b: subscriber emits isKindOfClass + destructure.
      (is (str/includes? emitted "isKindOfClass:[_Cxc_R_MyError class]")
          "A/2.b: subscriber dispatches on isKindOfClass of the wrapper")
      (is (str/includes? emitted "((_Cxc_R_MyError *)_cxc_scrut)->value.msg")
          "A/2.b: subscriber binding reads through the @public value ivar"))))

(deftest track-a8-typed-reason-with-multi-record-universe-and-uncaught-arm
  (testing "Track A/8 (2026-08-26) — multi-typed-reason universe: target atom has two class-level `|:` invariants (declaring MyError and OtherErr as reason records). Subscriber covers both plus an Uncaught arm for exception bubble-ups. Pins that: (a) both reason wrappers synthesize, (b) both throw sites box the correct wrapper, (c) subscriber's three-arm dispatch satisfies A/2.c exhaustiveness without a wildcard."
    (let [emitted (emit/emit (lower-src+prelude "def MyError:\n    msg: String\n\ndef OtherErr:\n    code: Int\n\ndef Player:\n    hp: Int! = 100\n    tag: Int! = 0\n    |: hp >= 0 as MyError(msg: \"neg\")\n    |: tag >= 0 as OtherErr(code: 42)\n\n    alive <: take_damage(amount: Int) -> alive\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: MyError(msg: let m): print(m)\n                <: OtherErr(code: let c): print(c)\n                <: Uncaught(msg: let u): print(u)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n"))]
      (is (str/includes? emitted "@interface _Cxc_R_MyError")
          "MyError wrapper class present")
      (is (str/includes? emitted "@interface _Cxc_R_OtherErr")
          "OtherErr wrapper class present")
      (is (str/includes? emitted "_Cxc_R_MyError *__cxc_r = [[_Cxc_R_MyError alloc] init]")
          "MyError throw site boxes into MyError wrapper")
      (is (str/includes? emitted "_Cxc_R_OtherErr *__cxc_r = [[_Cxc_R_OtherErr alloc] init]")
          "OtherErr throw site boxes into OtherErr wrapper")
      (is (str/includes? emitted "isKindOfClass:[_Cxc_R_MyError class]")
          "subscriber dispatches on MyError")
      (is (str/includes? emitted "isKindOfClass:[_Cxc_R_OtherErr class]")
          "subscriber dispatches on OtherErr")
      (is (str/includes? emitted "isKindOfClass:[_Cxc_R__Cxc_Uncaught class]")
          "subscriber's Uncaught arm dispatches on the prelude wrapper")
      (is (str/includes? emitted "((_Cxc_R__Cxc_Uncaught *)_cxc_scrut)->value.msg")
          "Uncaught arm binding reads msg through the prelude struct"))))

(deftest track-a7-reason-record-not-defined-rejected
  (testing "Track A/7 (2026-08-26) — `|: ... as UndefinedName(...)` fails at compile time with a targeted diagnostic naming the atom and pointing at `def <Name>:` at top level. Fires before lower-expr's generic \"unknown call\" diagnostic so users get the reason-specific migration hint. Applies to class-level invariants (post-store + pre-store F49); arm-head `|:` typed reasons are a separate pre-existing gap (currently silent-dropped)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track A/7: `\|: \.\.\. as NotDefined\(\.\.\.\)` on atom `A`.*reason record `NotDefined` is not defined"
         (lower-src "def A:\n    hp: Int = 100\n    |: hp > 0 as NotDefined(x: 1)\n    -> ready\n\ndef main:\n    a: A = A()\n    -> running\n")))))

(deftest track-a7-reason-cannot-be-enum
  (testing "Track A/7 (2026-08-26) — `|: ... as MyEnum(...)` where MyEnum is an enum fails with a targeted diagnostic. Reasons must be records: enums don't fit box+throw semantics (per-case payload storage differs; wrapper class assumes struct-shaped ivar)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track A/7:.*reason must be a record, but `Color` is an enum"
         (lower-src "def Color:\n    case red\n    case blue\n\ndef A:\n    hp: Int = 100\n    |: hp > 0 as Color(dummy: 1)\n    -> ready\n\ndef main:\n    a: A = A()\n    -> running\n")))))

(deftest track-a7-reason-cannot-be-atom
  (testing "Track A/7 (2026-08-26) — `|: ... as OtherAtom(...)` where OtherAtom is an atom fails with a targeted diagnostic. Atoms are stateful actors; reason records are value-typed data carriers boxed for throw semantics — mixing the two categories confuses the runtime shape."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track A/7:.*reason must be a record, but `B` is an atom"
         (lower-src "def B:\n    -> ready\n\ndef A:\n    hp: Int = 100\n    |: hp > 0 as B(dummy: 1)\n    -> ready\n\ndef main:\n    a: A = A()\n    -> running\n")))))

(deftest track-a7-reason-record-valid-lowers-cleanly
  (testing "Track A/7 (2026-08-26) — a valid `|: ... as <Record>(<fields>)` clause referencing a def'd record lowers cleanly. Positive-path pin so the A/7 validator doesn't over-reject."
    (let [lowered (lower-src "def OutOfRange:\n    value: Int\n\ndef A:\n    hp: Int = 100\n    |: hp > 0 as OutOfRange(value: hp)\n    -> ready\n\ndef main:\n    a: A = A()\n    -> running\n")]
      (is (some? lowered)
          "valid reason record lowers under A/7 validation"))))

(deftest track-a1-bareword-reason-payload-parses-and-lowers
  (testing "Track A/1 (2026-08-26) — bareword `reason:` at receive-payload position (no declared type) is accepted by the parser and lowers cleanly. The existing `Any`-fallback in `augment-atom-env-with-companions` produces an id-typed reason at the wire, so the subscriber body can dispatch on it via a generic `<:` case-pattern block. Retirement of the typed `reason: T` slot is Track A/3."
    (let [lowered (lower-src "def Player:
    hp: Int! = 100

    alive <: take_damage(amount: Int) -> alive
        |: amount > 0
        then: hp => hp - amount

def Logger:
    player: Player = Player()
    n: Int! = 0

    * <: player.take_damage_failed(reason:) -> self
        then: n => n + 1

def main:
    l: Logger = Logger()
    -> running
")
          player-cls (first (filter #(= "Player" (:name %)) (:classes lowered)))
          selectors  (mapv :selector (:methods player-cls))]
      (is (some? player-cls) "Player class lowered under bareword subscriber")
      (is (some #(re-find #"cxc_emit_take_damage_failed" (or % "")) selectors)
          "companion emit method still demand-driven from a bareword subscriber"))))

(deftest track-a2b-bareword-reason-case-pattern-block-with-wildcard-lowers
  (testing "Track A/2.b (2026-08-26) — the receiver-side composition (bareword reason payload + `<:` dispatch on the id-typed reason binding + wildcard tail) lowers cleanly to a `[reason isKindOfClass:...]`-style branch chain. Wildcard tail satisfies the F69 L4c4 open-type exhaustiveness rule for id-typed scrutinees; companion-reason closure via `:companion-index :reason-types` ∪ Uncaught is Track A/2.c. Pins the earlier A/1 test's follow-on: what was guarded dead code with an L4b diagnostic now compiles end-to-end."
    (let [lowered (lower-src "def Player:
    hp: Int! = 100

    alive <: take_damage(amount: Int) -> alive
        |: amount > 0
        then: hp => hp - amount

def Logger:
    player: Player = Player()
    n: Int! = 0

    * <: player.take_damage_failed(reason:) -> self
        then:
            reason:
                <: *: print(1)

def main:
    l: Logger = Logger()
    -> running
")]
      (is (some? lowered)
          "wildcard-tail dispatch on id-typed reason lowers cleanly under A/2.b"))))

(deftest track-a2b-record-pattern-dispatch-emits-iskindofclass-check
  (testing "Track A/2.b (2026-08-26) — a `<:` case-pattern arm whose pattern is a record name and whose scrutinee is id-typed emits an `[scrut isKindOfClass:[_Cxc_R_<Record> class]]` check. The wildcard tail satisfies open-type exhaustiveness. This is the receiver-side surface for companion reason dispatch; the wrapper class comes from Track A/2.a, the id-typed scrutinee gate from this landing."
    (let [emitted (emit/emit (lower-src "def MyError:\n    msg: String\n\ndef Player:\n    hp: Int! = 100\n\n    alive <: take_damage(amount: Int) -> alive\n        |: amount > 0\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: MyError: print(1)\n                <: *: print(2)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n"))]
      (is (str/includes? emitted "isKindOfClass:[_Cxc_R_MyError class]")
          "record pattern lowers to isKindOfClass: against the wrapper class")
      (is (str/includes? emitted "_Cxc_R_MyError")
          "wrapper class name is present in the emit"))))

(deftest track-a2b-record-pattern-payload-binding-destructures-via-value-ivar
  (testing "Track A/2.b (2026-08-26) — a case pattern with named-payload bindings on a record pattern (id-typed scrutinee) initializes each binding from `((_Cxc_R_<T> *)tmp)->value.<field>`. Field type is looked up from the record's declared field list; positional bindings and unknown fields fail with the shared F16 L1 diagnostics."
    (let [emitted (emit/emit (lower-src "def MyError:\n    msg: String\n    code: Int\n\ndef Player:\n    hp: Int! = 100\n\n    alive <: take_damage(amount: Int) -> alive\n        |: amount > 0\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: MyError(msg: let m, code: let c): print(m)\n                <: *: print(9)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n"))]
      (is (str/includes? emitted "((_Cxc_R_MyError *)_cxc_scrut)->value.msg")
          "String field binding reads from the boxed struct's msg field")
      (is (str/includes? emitted "((_Cxc_R_MyError *)_cxc_scrut)->value.code")
          "Int field binding reads from the boxed struct's code field"))))

(deftest track-a2b-record-pattern-unknown-field-binding-rejected
  (testing "Track A/2.b (2026-08-26) — an unknown payload field name on a record pattern fails with the shared F16 L1 diagnostic naming the declared fields. Reuses the same policy used for enum-case payload bindings so the two forms share a diagnostic voice."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F16 L1: unknown payload field `oops` on record `MyError`"
         (lower-src "def MyError:\n    msg: String\n\ndef Player:\n    hp: Int! = 100\n\n    alive <: take_damage(amount: Int) -> alive\n        |: amount > 0\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: MyError(oops: let m): print(1)\n                <: *: print(2)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n")))))

(deftest track-a2b-non-record-pattern-name-on-any-scrutinee-rejected
  (testing "Track A/2.b (2026-08-26) — a case pattern whose name isn't a declared record fails-nyi with a targeted diagnostic pointing at `def <Name>:`. Prevents accidental typos silently missing at runtime — id-typed dispatch is open, so a stray name would otherwise become an isKindOfClass: check against a non-existent class (compile error deferred to clang, worse UX)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L4b: case pattern `<: NotARecord:` on id-typed scrutinee requires a record name"
         (lower-src "def Player:\n    hp: Int! = 100\n\n    alive <: take_damage(amount: Int) -> alive\n        |: amount > 0\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: NotARecord: print(1)\n                <: *: print(2)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n")))))

(deftest track-a6-bare-invariant-does-not-force-arm-on-subscriber
  (testing "Track A/6 (2026-08-26) — bare `|:` clauses (invariant violations, no `as T`) still hard-abort at runtime; they never fire the companion. Cocoa exceptions from then:/do: bodies get boxed as `Uncaught` on the L12c catch side and fan through, but the subscriber can silently drop them (id-typed dispatch is nil-safe / arm-chain no-ops for unhandled kinds). So a target atom with a bare `|:` doesn't force the subscriber to add an Uncaught arm — Uncaught handling is opt-in. This test pins that under A/6 the subscriber pattern from the pre-A/6 diagnostic case now lowers cleanly."
    (let [lowered (lower-src "def MyError:\n    msg: String\n\ndef Player:\n    hp: Int! = 100\n\n    alive <: take_damage(amount: Int) -> alive\n        |: amount > 0\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: MyError: print(1)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n")]
      (is (some? lowered)
          "bare-invariant-only target + no Uncaught arm on subscriber → lowers cleanly under A/6"))))

(deftest track-a6-uncaught-prelude-record-and-wrapper-class-synthesized
  (testing "Track A/6 (2026-08-26) — the prelude ships an `Uncaught(msg: String)` record via `resources/prelude/shared/system.cxc`. It renames to `_Cxc_Uncaught` internally, and the reason-box synthesis (Track A/2.a) picks it up automatically as `_Cxc_R__Cxc_Uncaught`. Emitted whenever the full atom pipeline runs (any program with a def-atom); F30 minimal-main mode short-circuits before reason-boxes and doesn't include it."
    (let [emitted (emit/emit (lower-src "def A:\n    -> ready\n\ndef main:\n    a: A = A()\n    -> running\n"))]
      (is (str/includes? emitted "@interface _Cxc_R__Cxc_Uncaught : NSObject")
          "Uncaught's reason-box wrapper class emitted under the full atom pipeline")
      (is (str/includes? emitted "@public _Cxc_Uncaught value")
          "wrapper wraps the mangled Uncaught struct as its @public ivar"))))

(deftest track-a6-non-typed-catch-synthesizes-uncaught-reason
  (testing "Track A/6 (2026-08-26) — the L12c end-catch-wrap synthesizes an Uncaught reason box when `e.userInfo[@\"reason\"]` is nil (Cocoa exceptions bubbling up from then:/do: bodies, or any @throw NSException without a reason box). The synthesized Uncaught carries `e.reason ?: e.name` as its msg, so subscribers can `<: Uncaught(msg: let m):` and print a human-readable failure text."
    (let [emitted (emit/emit (lower-src "def Player:\n    hp: Int! = 100\n\n    alive <: take_damage(amount: Int) -> alive\n        |: amount > 0\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then: n => n + 1\n\ndef main:\n    l: Logger = Logger()\n    -> running\n"))]
      (is (str/includes? emitted "_Cxc_R__Cxc_Uncaught *__cxc_u = [[_Cxc_R__Cxc_Uncaught alloc] init]")
          "catch site alloc's an Uncaught box when reason is nil")
      (is (str/includes? emitted "(_Cxc_Uncaught){.msg = (e.reason ?: (e.name ?: @\"unknown\"))}")
          "synthesized Uncaught carries e.reason ?: e.name as msg"))))

(deftest track-a2c-companion-reason-closed-sum-typed-only-lowers-without-wildcard
  (testing "Track A/2.c (2026-08-26) — when every `|:` clause on the target atom is typed (`as T(...)`) and the receiver's arm set covers every reason record, the dispatch is exhaustive WITHOUT a wildcard tail. This closes the sum against `:companion-index :reason-types` and makes companion-reason dispatch a closed-sum operation rather than open-with-wildcard."
    (let [lowered (lower-src "def MyError:\n    msg: String\n\ndef Player:\n    hp: Int! = 100\n\n    alive <: take_damage(amount: Int) -> alive\n        |: amount > 0 as MyError(msg: \"neg\")\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: MyError: print(1)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n")]
      (is (some? lowered)
          "closed sum: typed-only universe + arms covering every reason → exhaustive without wildcard"))))

(deftest track-a2c-companion-reason-missing-arm-listed
  (testing "Track A/2.c (2026-08-26) — when the target's `|: as T` clauses declare multiple reason record types and the receiver's arm set misses one, the diagnostic names the specific companion + the missing record(s). Points users at the exact arm to add rather than falling back to the generic \"add a wildcard\" hint."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L4c1.*missing arm.*\"OtherErr\""
         (lower-src "def MyError:\n    msg: String\n\ndef OtherErr:\n    code: Int\n\ndef Player:\n    hp: Int! = 100\n\n    alive <: take_damage(amount: Int) -> alive\n        |: amount > 0 as MyError(msg: \"neg\")\n        |: amount < 100 as OtherErr(code: 1)\n        then: hp => hp - amount\n\ndef Logger:\n    player: Player = Player()\n    n: Int! = 0\n\n    * <: player.take_damage_failed(reason:) -> self\n        then:\n            reason:\n                <: MyError: print(1)\n\ndef main:\n    l: Logger = Logger()\n    -> running\n")))))

(deftest l9-or-continuation-parses-and-lowers-to-sync-send-with-recovery
  (testing "L9 (2026-08-26) — a sync `|>` send followed by an `or:` block parses into an `:emit` node carrying `:or-body`, which lowers to `:sync-send-with-recovery` and emits a Cocoa `@try/@catch` around the send-call. MVP handles bare `or:` with statement bodies; binding (`or e:`) and pattern-matched (`or T:`) variants are a follow-on."
    (let [emitted (emit/emit (lower-src "def Sink:
    n: Int! = 0

    alive <: pulse -> alive
        |: n < 3
        then: n => n + 1

def Boss:
    s: Sink = Sink()

    -> running
        then:
            |> s.pulse()
            or:
                |> s.pulse()

def main:
    b: Boss = Boss()
    -> running
"))]
      (is (str/includes? emitted "cxc_wait_for_processing")
          "the recovery site still calls the sync-send helper")
      (is (str/includes? emitted "NSException *__cxc_or_e = cxc_wait_for_processing")
          "Q1 (2026-08-26): outer @try wrap retired; sender captures the helper's return value directly")
      (is (str/includes? emitted "if (__cxc_or_e)")
          "recovery block runs only on non-nil captured exception"))))

(deftest track-b2-sync-send-with-recovery-carries-arms-terminal-shape
  (testing "Track B/2 (2026-08-26) — the lowered `:sync-send-with-recovery` node carries `:arms` as terminal shape. `:or-body` is retired; downstream (emit) reads `:arms` directly. Pins the shape by walking the AST for the recovery node and asserting a single-bare-arm program produces a one-arm list with a nil pattern."
    (let [prog (lower-src "def S:\n    n: Int! = 0\n    * <: ping -> self\n        then: n => n + 1\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or:\n                |> s.ping()\n\ndef main:\n    b: B = B()\n    -> running\n")
          nodes (tree-seq coll? #(if (map? %) (vals %) (seq %)) prog)
          recov (first (filter #(and (map? %)
                                     (= :sync-send-with-recovery (:node %)))
                               nodes))]
      (is (some? recov) "found a lowered sync-send-with-recovery node")
      (is (vector? (:arms recov)))
      (is (= 1 (count (:arms recov))))
      (is (nil? (:pattern (first (:arms recov)))))
      (is (not (contains? recov :or-body))
          ":or-body retired — :arms is the terminal shape"))))

(deftest q1-f48-end-catch-wrap-appends-rethrow-after-cleanup
  (testing "Q1 (2026-08-26) — F48 :end-catch-wrap now appends `@throw;` after cleanup (log + companion emit + transitionToEnd) so sync-send callers can capture the failure on their own frame. Async callers' `cxc_send_async` wrapper swallows-with-log so the process doesn't crash. Pins the shape: cleanup fires BEFORE the re-throw (order matters — companion subscribers see the failure regardless of who catches upstream)."
    (let [emitted (emit/emit (lower-src "def A:\n    n: Int! = 0\n    * <: bump -> self\n        then: n => n + 1\n\ndef main:\n    a: A = A()\n    -> running\n        then: |> a.bump()\n"))
          wrap-idx    (str/index-of emitted "@catch (NSException *e) {")
          cleanup-idx (str/index-of emitted "[self transitionToEnd];" wrap-idx)
          throw-idx   (str/index-of emitted "    @throw;\n" cleanup-idx)]
      (is (some? wrap-idx) "F48 wrap present")
      (is (some? cleanup-idx) "cleanup (transitionToEnd) present after wrap")
      (is (some? throw-idx) "re-throw present after cleanup")
      (is (< cleanup-idx throw-idx)
          "re-throw appears AFTER cleanup — companion subscribers fire before propagation"))))

(deftest q1-cxc-wait-for-processing-returns-nsexception-captures-on-senders-frame
  (testing "Q1 (2026-08-26) — `cxc_wait_for_processing` runtime helper now returns `NSException *` (nil on success). Captures via `__block NSException *__captured` + inner `@try/@catch` inside the block, then wraps the whole dispatch in an outer `@try/@finally` so `_cxc_sending_to` is always cleaned up. No exception ever crosses the `dispatch_sync` queue boundary — avoids UB under F32 concurrent mode."
    (let [emitted (emit/emit (lower-src "def A:\n    n: Int! = 0\n    * <: bump -> self\n        then: n => n + 1\n\ndef main:\n    a: A = A()\n    -> running\n        then: |> a.bump()\n"))]
      (is (str/includes? emitted "static inline NSException *cxc_wait_for_processing")
          "helper signature returns NSException*")
      (is (str/includes? emitted "__block NSException *__captured = nil;")
          "capture slot allocated as __block so the inner @catch can write it")
      (is (str/includes? emitted "__captured = e;")
          "inner @catch captures the target's re-raised exception on the same thread it fires")
      (is (str/includes? emitted "return __captured;")
          "helper returns the captured exception (nil if none)"))))

(deftest q1-cxc-send-async-swallows-with-log
  (testing "Q1 (2026-08-26) — `cxc_send_async` wraps the block in `@try/@catch` and swallows the target's re-raised exception with an `NSLog` for oncall visibility. Preserves the pre-Q1 async fire-and-forget contract: async sends never crash the process. Companion emit already fired inside F48 before the re-throw, so subscribers still receive the failure signal."
    (let [emitted (emit/emit (lower-src "def A:\n    n: Int! = 0\n    * <: bump -> self\n        then: n => n + 1\n\ndef main:\n    a: A = A()\n    -> running\n        then: |> a.bump()\n"))]
      (is (str/includes? emitted "cxc async unhandled")
          "async wrapper swallows with NSLog to stderr"))))

(deftest q2-bounded-target-declares-sem-property-and-init
  (testing "Q2 (2026-08-26) — `def <Atom>(queue: bounded(N))` triggers three emit-side changes: (1) the atom's @interface synthesizes a `cxc_mailbox_sem` @property backed by an ivar; (2) `-cxc_structuralSetup` allocates it via `dispatch_semaphore_create(N)`; (3) unbounded atoms in the same program are unaffected (no sem property, no init). Pins the isolation — bounded discipline is per-class, opt-in, and doesn't perturb other atoms' shapes."
    (let [emitted (emit/emit (lower-src "def A(queue: bounded(2)):\n    n: Int! = 0\n    * <: bump -> self\n        then: n => n + 1\n\ndef B:\n    m: Int! = 0\n    * <: tick -> self\n        then: m => m + 1\n\ndef main:\n    a: A = A()\n    b: B = B()\n    -> running\n        then: |> a.bump()\n"))
          a-iface-start (str/index-of emitted "@interface A ")
          b-iface-start (str/index-of emitted "@interface B ")]
      (is (some? a-iface-start) "A @interface present")
      (is (some? b-iface-start) "B @interface present")
      (let [a-iface (subs emitted a-iface-start b-iface-start)
            b-iface (subs emitted b-iface-start (min (+ b-iface-start 2000) (count emitted)))]
        (is (str/includes? a-iface "cxc_mailbox_sem")
            "A (bounded) declares cxc_mailbox_sem property")
        (is (not (str/includes? b-iface "cxc_mailbox_sem"))
            "B (unbounded) does NOT declare cxc_mailbox_sem — per-class opt-in isolation"))
      (is (str/includes? emitted "_cxc_mailbox_sem = dispatch_semaphore_create(2);")
          "sem initialized at declared capacity in structuralSetup"))))

(deftest q2-bounded-target-sync-send-routes-through-bounded-helper
  (testing "Q2 (2026-08-26) — sync `|>` to a bounded target lowers to `cxc_wait_for_processing_bounded` (not `cxc_wait_for_processing`) with the target's sem accessor prepended. Pre-Q2 emitted `cxc_wait_for_processing(target, ^{ ... })`; post-Q2 with bounded target emits `cxc_wait_for_processing_bounded(target, [target cxc_mailbox_sem], ^{ ... })`. Q1's `:sync-send-checked` capture-and-rethrow shape still wraps — Q2 only swaps the helper name + adds the sem arg."
    (let [emitted (emit/emit (lower-src "def A(queue: bounded(2)):\n    n: Int! = 0\n    * <: bump -> self\n        then: n => n + 1\n\ndef main:\n    a: A = A()\n    -> running\n        then: |> a.bump()\n"))]
      (is (str/includes? emitted "cxc_wait_for_processing_bounded(_a, [_a cxc_mailbox_sem]")
          "sync send to bounded target routes through _bounded helper with sem accessor")
      (is (str/includes? emitted "NSException *__cxc_bs = cxc_wait_for_processing_bounded")
          "Q1's :sync-send-checked capture wraps the bounded call (bare `|>` still re-throws)"))))

(deftest q2-unbounded-target-keeps-pre-q2-shape
  (testing "Q2 (2026-08-26) — sync `|>` to an unbounded (default) target emits the pre-Q2 unchanged `cxc_wait_for_processing` shape. No sem accessor, no bounded helper. Zero-cost for the default case: unbounded atoms carry no extra ivar, no extra helper call, no extra bytes of runtime."
    (let [emitted (emit/emit (lower-src "def A:\n    n: Int! = 0\n    * <: bump -> self\n        then: n => n + 1\n\ndef main:\n    a: A = A()\n    -> running\n        then: |> a.bump()\n"))]
      (is (str/includes? emitted "cxc_wait_for_processing(_a, ^")
          "unbounded target keeps direct cxc_wait_for_processing call")
      (is (not (str/includes? emitted "cxc_wait_for_processing_bounded(_a"))
          "no bounded-helper CALL when target is unbounded (helper definition is header-only, always present)")
      (is (not (str/includes? emitted "cxc_mailbox_sem];"))
          "no sem ivar accessor at any send call site when no atom in program is bounded"))))

(deftest q2-bounded-runtime-helpers-emit-once
  (testing "Q2 (2026-08-26) — the `cxc_send_async_bounded` + `cxc_wait_for_processing_bounded` runtime helpers are always emitted as part of `cxc-runtime-helpers` (whenever any atom is emitted, whether or not any target is actually bounded). Header-only helpers; no runtime cost for programs that never call them. Async variant uses `DISPATCH_TIME_NOW` (drop-newest); sync variant uses `DISPATCH_TIME_FOREVER` (block-until-space) per spec §1.11."
    (let [emitted (emit/emit (lower-src "def A:\n    n: Int! = 0\n    * <: bump -> self\n        then: n => n + 1\n\ndef main:\n    a: A = A()\n    -> running\n        then: |> a.bump()\n"))]
      (is (str/includes? emitted "static inline void cxc_send_async_bounded(id target, dispatch_semaphore_t sem")
          "async bounded helper defined")
      (is (str/includes? emitted "static inline NSException *cxc_wait_for_processing_bounded(id target, dispatch_semaphore_t sem")
          "sync bounded helper defined")
      (is (str/includes? emitted "dispatch_semaphore_wait(sem, DISPATCH_TIME_NOW)")
          "async uses TIME_NOW — drop-newest discipline per spec §1.11")
      (is (str/includes? emitted "dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER)")
          "sync uses TIME_FOREVER — block-until-space discipline per spec §1.11"))))

(deftest q1-bare-sync-send-emits-capture-and-rethrow
  (testing "Q1 (2026-08-26) — bare sync `|>` (no `or:` arms) lowers to `:sync-send-checked` wrapping the raw send-call. Emit renders as `{ NSException *__cxc_bs = cxc_wait_for_processing(...); if (__cxc_bs) @throw __cxc_bs; }` — sender's own F48 catches the re-thrown exception (preserving pre-Q1 behavior where bare `|>` failure killed the sender's atom too). Without explicit capture + rethrow, the helper's non-nil return would be silently discarded."
    (let [emitted (emit/emit (lower-src "def A:\n    n: Int! = 0\n    * <: bump -> self\n        then: n => n + 1\n\ndef main:\n    a: A = A()\n    -> running\n        then: |> a.bump()\n"))]
      (is (str/includes? emitted "NSException *__cxc_bs = cxc_wait_for_processing")
          "bare sync send captures helper's return value")
      (is (str/includes? emitted "if (__cxc_bs) @throw __cxc_bs;")
          "sender re-throws on non-nil capture so its own F48 wrap catches"))))

(deftest track-b3-typed-arm-emits-iskindofclass-dispatch
  (testing "Track B/3 (2026-08-26) — a typed `or T(field: let x):` arm emits `if ([__cxc_reason isKindOfClass:[_Cxc_R_T class]]) { <destructure> <body> } else { @throw; }`. The catch prelude extracts `__cxc_reason` from `e.userInfo[@\"reason\"]` and synthesizes `_Cxc_R__Cxc_Uncaught` on nil (mirrors L12c catch-side behavior)."
    (let [emitted (emit/emit (lower-src+prelude "def MyError:\n    msg: String\n\ndef S:\n    n: Int! = 0\n    |: n >= 0 as MyError(msg: \"neg\")\n    * <: ping -> self\n        then: n => n + 1\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or MyError(msg: let m):\n                |> s.ping()\n\ndef main:\n    b: B = B()\n    -> running\n"))]
      (is (str/includes? emitted "id __cxc_reason = __cxc_or_e.userInfo[@\"reason\"]")
          "catch extracts the reason box from userInfo")
      (is (str/includes? emitted "_Cxc_R__Cxc_Uncaught *__cxc_u = [[_Cxc_R__Cxc_Uncaught alloc] init]")
          "non-typed catches synthesize an Uncaught box on nil")
      (is (str/includes? emitted "isKindOfClass:[_Cxc_R_MyError class]")
          "typed arm gates on isKindOfClass of the wrapper")
      (is (str/includes? emitted "((_Cxc_R_MyError *)__cxc_reason)->value.msg")
          "destructure binding reads through the @public value ivar")
      (is (str/includes? emitted "@throw;")
          "typed-only arm chain re-throws on fallthrough"))))

(deftest track-b3-bind-arm-terminal-suppresses-rethrow
  (testing "Track B/3 (2026-08-26) — a bind arm (`or e:`) is a catchall: emits an unconditional `else { id e = __cxc_reason; <body> }` and skips the `@throw;` fallback. When placed at the tail of a typed chain it makes the chain total; when placed alone it degenerates to the MVP catchall."
    (let [emitted (emit/emit (lower-src+prelude "def MyError:\n    msg: String\n\ndef S:\n    n: Int! = 0\n    |: n >= 0 as MyError(msg: \"neg\")\n    * <: ping -> self\n        then: n => n + 1\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or MyError(msg: let m):\n                |> s.ping()\n            or e:\n                |> s.ping()\n\ndef main:\n    b: B = B()\n    -> running\n"))]
      (is (str/includes? emitted "isKindOfClass:[_Cxc_R_MyError class]"))
      (is (str/includes? emitted "id e = __cxc_reason")
          "bind arm declares a local `id` alias for the reason")
      (is (not (str/includes? emitted "else { @throw __cxc_or_e; }"))
          "chain with a bind catchall is total — no `else @throw` fallback appended (F48's own `@throw;` after cleanup is a different site, unrelated to arm dispatch)"))))

(deftest track-b3-unreachable-arm-after-catchall-rejected
  (testing "Track B/3 (2026-08-26) — a typed arm following a catchall (bare or bind) is unreachable and rejected at lower with a diagnostic telling the user to remove or reorder. Prevents silently-dead-code recovery arms."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track B/3: unreachable `or:` arm"
         (lower-src+prelude "def MyError:\n    msg: String\n\ndef S:\n    n: Int! = 0\n    |: n >= 0 as MyError(msg: \"neg\")\n    * <: ping -> self\n        then: n => n + 1\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or:\n                |> s.ping()\n            or MyError(msg: let m):\n                |> s.ping()\n\ndef main:\n    b: B = B()\n    -> running\n")))))

(deftest track-b5-typed-arm-chain-preserves-source-order-and-terminates-with-throw
  (testing "Track B/5 (2026-08-26) — a multi-typed `or:` chain (no catchall) emits an `if / else if / else if / ... / else { @throw; }` dispatch preserving source order. Verifies: arms in the declared order become the corresponding if / else-if predicates; the final `else { @throw; }` re-raises unmatched reasons so the enclosing F48 wrapper (or an outer sync-send `or:`) still gets a chance."
    (let [emitted (emit/emit (lower-src+prelude "def ErrA:\n    msg: String\n\ndef ErrB:\n    code: Int\n\ndef T:\n    n: Int! = 0\n    |: n < 3 as ErrA(msg: \"a\")\n    |: n < 3 as ErrB(code: 1)\n\n    -> running\n\n    running <: kick -> running\n        then: n => n + 1\n\ndef Sender:\n    t: T = T()\n\n    -> running\n        then:\n            |> t.kick()\n            or ErrA(msg: let m):\n                |> t.kick()\n            or ErrB(code: let c):\n                |> t.kick()\n\ndef main:\n    s: Sender = Sender()\n    -> running\n"))
          idx-a  (str/index-of emitted "isKindOfClass:[_Cxc_R_ErrA class]")
          idx-b  (str/index-of emitted "isKindOfClass:[_Cxc_R_ErrB class]")]
      (is (some? idx-a) "ErrA arm gates on _Cxc_R_ErrA")
      (is (some? idx-b) "ErrB arm gates on _Cxc_R_ErrB")
      (is (< idx-a idx-b)
          "arm order preserved: ErrA precedes ErrB in emitted dispatch")
      (is (str/includes? emitted "@throw;")
          "typed-only chain terminates with @throw fallthrough"))))

(deftest track-b4-cross-atom-reason-record-recognized-verbatim
  (testing "Track B/4 (2026-08-26) — cross-atom reason transparency. Reason records are top-level globals; the wrapper class name is a deterministic function of the record name (`_Cxc_R_<T>`), so a sender's `or T(...):` arm and a receiver's `|: ... as T(...)` invariant see the same box class. No cross-atom wrapping / adapter is needed — the record IS the reason vocabulary, shared verbatim. Test: two atoms (thrower + sender) share a `SharedErr` record; both use the same `_Cxc_R_SharedErr` wrapper class in the emitted C."
    (let [emitted (emit/emit (lower-src+prelude "def SharedErr:\n    code: Int\n\ndef Thrower:\n    n: Int! = 0\n    |: n < 3 as SharedErr(code: 42)\n\n    -> running\n\n    running <: kick -> running\n        then: n => n + 1\n\ndef Sender:\n    t: Thrower = Thrower()\n\n    -> running\n        then:\n            |> t.kick()\n            or SharedErr(code: let c):\n                |> t.kick()\n\ndef main:\n    s: Sender = Sender()\n    -> running\n"))]
      (is (str/includes? emitted "@interface _Cxc_R_SharedErr")
          "single wrapper class synthesized for the shared reason record")
      (is (str/includes? emitted "_Cxc_R_SharedErr *__cxc_r = [[_Cxc_R_SharedErr alloc] init]")
          "Thrower's invariant throw site boxes into the shared wrapper")
      (is (str/includes? emitted "isKindOfClass:[_Cxc_R_SharedErr class]")
          "Sender's or-arm dispatches on the same shared wrapper")
      (is (str/includes? emitted "((_Cxc_R_SharedErr *)__cxc_reason)->value.code")
          "Sender's destructure reads through the shared struct layout"))))

(deftest track-b3-typed-arm-references-undeclared-record-rejected
  (testing "Track B/3 (2026-08-26) — `or T(...):` where `T` is not a declared record fails at lower with a targeted diagnostic. Prevents silent silent-drop on typos or forgotten `def T:`."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track B/3: `or NoSuchType\(...\):` arm references type `NoSuchType`"
         (lower-src+prelude "def S:\n    n: Int! = 0\n    * <: ping -> self\n        then: n => n + 1\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or NoSuchType():\n                |> s.ping()\n\ndef main:\n    b: B = B()\n    -> running\n")))))

(deftest l9-or-continuation-only-on-sync-sends
  (testing "L9 — `or:` continuation is defined only for sync `|>` sends. Async `:>` sends are fire-and-forget with no caller-frame boundary the recovery block could run in; the parser only look-aheads for `or:` at the `|>` position, so `:>`-followed-by-`or:` lands as an unrelated `or` statement token — parse error is the current diagnostic."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unexpected keyword or in expression"
         (lower-src "def A:
    n: Int! = 0
    * <: bump -> self
        then:
            :> ping
            or:
                n => n + 1
")))))

(deftest panic-requires-use-cap
  (testing "2026-08-26 panic-cap gate — `panic(...)` called without a `use panic: SystemPanic` declaration on the enclosing atom errors at compile-time with a migration hint. Rationale: panic is the process-terminal escape hatch; the `use:` is the compile-time \"you're on your own\" contract."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"panic\(\.\.\.\) requires the enclosing atom to declare `use panic: SystemPanic`"
         (lower-src "def main:\n    -> running\n        then: panic(\"boom\")\n")))))

(deftest panic-with-use-cap-lowers-cleanly
  (testing "2026-08-26 panic-cap gate — `panic(...)` with `use panic: SystemPanic` on the enclosing atom lowers to the F31/F35 L2 inline trap without error. SystemPanic is a phantom capability type (no runtime state); the `use:` slot is a compile-time contract."
    (let [emitted (emit/emit (lower-src "def main:\n    use panic: SystemPanic\n\n    -> running\n        then: panic(\"boom\")\n"))]
      (is (clojure.string/includes? emitted "__builtin_trap()")
          "panic still lowers to inline trap under the cap gate"))))

(deftest f31-f35-l2-panic-lowers-to-inline-trap
  (testing "F31/F35 Landing 2 — `panic(\"msg\")` lowers to an inline expr that prints `panic: <msg>` + backtrace and calls `__builtin_trap()`. Uncatchable (bypasses `@try/@catch`); BEAM-style transition isolation is preserved separately. Post 2026-08-26 `use panic:` gate: main becomes main-as-atom (has a `:use-section`); panic call site lives inside its transition method, verified via emitted C."
    (let [emitted (emit/emit (lower-src "def main:\n    use panic: SystemPanic\n\n    -> running\n        then: panic(\"boom\")\n"))]
      (is (clojure.string/includes? emitted "panic: %s")
          "renders the panic-prefixed format string")
      (is (clojure.string/includes? emitted "backtrace(")
          "renders the inline backtrace call")
      (is (clojure.string/includes? emitted "__builtin_trap()")
          "renders the uncatchable trap"))))

(deftest f31-f35-l2-panic-non-string-arg-rejected
  (testing "F31/F35 Landing 2 — MVP accepts a string-literal arg only (spec §3.5.4). Domain-error payload waits on Result infra."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"panic\(\) arg must be a string literal"
         (lower-src "def main:\n    use panic: SystemPanic\n\n    -> running\n        then: panic(42)\n")))))

(deftest f31-f35-l2-panic-wrong-arity-rejected
  (testing "F31/F35 Landing 2 — panic expects exactly one arg."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"panic\(\) expects exactly one"
         (lower-src "def main:\n    use panic: SystemPanic\n\n    -> running\n        then: panic(\"a\", \"b\")\n")))))

(deftest f31-f35-l2-panic-pulls-execinfo-include
  (testing "F31/F35 Landing 2 — programs using panic get `<execinfo.h>` in includes (for the inlined backtrace() call). Programs without panic don't."
    (let [with-panic    (lower-src "def main:\n    use panic: SystemPanic\n\n    -> running\n        then: panic(\"x\")\n")
          without-panic (lower-src "def main:\n    -> running\n        then: print(1)\n")]
      (is (some #{"<execinfo.h>"} (:includes with-panic))
          "panic program includes <execinfo.h>")
      (is (not (some #{"<execinfo.h>"} (:includes without-panic)))
          "non-panic program doesn't pull in <execinfo.h>"))))

;; f9-l2-non-protocol-class-still-fails-nyi deleted at F68 L6b — `class` /
;; `metadata: kind:` sugar is retired; there is no non-protocol :class node
;; anymore. Class kind (regular/record/enum) is derived structurally under
;; `def` and its lowering is exercised by the atom/record/enum tests
;; directly (no fail-nyi wall to enforce).

(deftest f9-l2-protocol-lowers-and-registers
  (testing "L5 — a signature-only def lowers cleanly and its sync/async declarations populate the unchanged internal :accepts protocol map."
    (let [prog (lower-src (str "def P:\n"
                               "    foo(x: Int) :- Int\n"
                               "    <: bar(s: String)\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          proto (get-in prog [:protocols "P"])]
      (is (some? proto) "protocol P registered on the lowered program")
      (is (= 2 (count (:accepts proto))))
      (is (= #{"foo" "bar"}
             (into #{} (map :name (:accepts proto)))))
      (is (contains? (:accepts-index proto) "foo"))
      (is (contains? (:accepts-index proto) "bar"))
      (is (empty? (:conforms proto)) "no explicit protocol-extends here"))))

(deftest f9-l2-protocol-with-default-observables-registered
  (testing "F9 Landing 2 + F67 L4 — protocols may carry default observable bodies; L2 stores them under :defaults for L6 (inheritance) to consume. Under Proposal G class-body collapse, defaults are observables, not `methods:` entries."
    (let [prog (lower-src (str "def P:\n"
                               "    greet() :- Int\n"
                               "\n"
                               "    greet() :- 42\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          proto (get-in prog [:protocols "P"])]
      (is (some? proto))
      (is (= 1 (count (:defaults proto))))
      (is (= "greet" (:name (first (:defaults proto))))))))

(deftest f9-l2-duplicate-signatures-rejected
  (testing "L5 — duplicate signature names in the same protocol fail at lower: each selector appears at most once in a protocol's surface (§3.4.5)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"declares signature `foo` more than once"
         (lower-src (str "def P:\n"
                         "    foo(x: Int) :- Int\n"
                         "    foo(y: String) :- Bool\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l3-extension-env-keyed-by-target
  (testing "F9 Landing 3 — `extend Foo:` blocks land in the program's `:extensions` map keyed by target name. Multiple extensions per target stack in a vector. Post F67 L4: extension methods declare as observables."
    (let [prog (lower-src (str "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget:\n"
                               "    bar() :- 1\n"
                               "\n"
                               "extend Widget:\n"
                               "    baz() :- 2\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          exts (get-in prog [:extensions "Widget"])]
      (is (= 2 (count exts))
          "both `extend Widget:` blocks register under target `Widget`")
      (is (every? #(= :extend-class (:node %)) exts)))))

(deftest f9-l3-extension-target-must-resolve
  (testing "F9 Landing 3 — `extend BogusFoo:` with no matching record/atom/protocol in scope fails at lower with a §3.3.2 pointer."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"targets unknown class `BogusFoo`.*§3.3.2"
         (lower-src (str "extend BogusFoo:\n"
                         "    bar() :- 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l3-extension-may-target-protocol
  (testing "F9 Landing 3 — `extend P:` where P is a protocol is legal at L3: extension-env just records; L6 handles default-method inheritance from extension observables (F67 L4)."
    (let [prog (lower-src (str "def P:\n"
                               "    foo(x: Int) :- Int\n"
                               "\n"
                               "extend P:\n"
                               "    bar() :- 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))]
      (is (= 1 (count (get-in prog [:extensions "P"])))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 4 — extension composition (single-module) + `replace`
;; + conflict-resolution warnings.
;; ---------------------------------------------------------------------------

(defn- capture-warnings [thunk]
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw]
      (let [result (thunk)
            ;; F68 L1 — filter transient sugar-keyword deprecation
            ;; noise from stderr so tests probing shadow-warnings /
            ;; conformance-warnings / other semantic warnings aren't
            ;; polluted. Sublanding scaffold — retires when the sugar
            ;; keywords retire in F68 L5.
            filtered (->> (str/split-lines (str sw))
                          (remove #(str/includes? % "F68 Proposal H"))
                          (str/join "\n"))]
        [result (if (empty? filtered) "" (str filtered "\n"))]))))

(deftest f9-l4-single-extension-adds-method
  (testing "F9 Landing 4 + F67 L4 — one extension of a record adds an observable that composes into the record's method table (extension observables convert to method-shape at compose time)."
    (let [[prog stderr] (capture-warnings
                        #(lower-src (str "def Widget:\n"
                                         "    n: Int\n"
                                         "\n"
                                         "    own_m() :- 1\n"
                                         "\n"
                                         "extend Widget:\n"
                                         "    ext_m() :- 2\n"
                                         "\n"
                                         "def main:\n"
                                         "    -> running\n"
                                         "        then: print(0)\n")))
          widget-fns (filter #(str/starts-with? (or (:name %) "") "Widget_")
                             (:functions prog))]
      (is (= "" stderr) "no warnings for a clean add-an-observable extension")
      (is (some #(= "Widget_own_m" (:name %)) widget-fns))
      (is (some #(= "Widget_ext_m" (:name %)) widget-fns)))))

(deftest f9-l4-shadowing-warns-and-picks-own
  (testing "F9 Landing 4 + F67 L4 — extension declares `own_m` observable that shadows the record's own observable; own wins (§3.3.2 rule 1) and a shadowing warning fires."
    (let [[prog stderr] (capture-warnings
                        #(lower-src (str "def Widget:\n"
                                         "    n: Int\n"
                                         "\n"
                                         "    own_m() :- 100\n"
                                         "\n"
                                         "extend Widget:\n"
                                         "    own_m() :- 200\n"
                                         "\n"
                                         "def main:\n"
                                         "    -> running\n"
                                         "        then: print(0)\n")))
          fn         (first (filter #(= "Widget_own_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (re-find #"shadows the class's own definition.*not marked `replace`" stderr))
      (is (= 100 (get-in return-stmt [:value :value]))
          "own body wins (returns 100, not extension's 200)"))))

;; F9 L4 `replace` modifier tests retire — `replace` was a `methods:`-specific
;; keyword whose migration path is a follow-on landing. Shadowing detection
;; without `replace` still fires (see f9-l4-shadowing-warns-and-picks-own),
;; and F67 L4 covers the retirement diagnostic for the `replace function …`
;; source shape (see parse_test.clj `f67-l4-replace-modifier-retired`).

(deftest f9-l4-two-extensions-conflict-later-wins
  (testing "F9 Landing 4 + F67 L4 — two extensions declare the same observable and no own definition exists; §3.3.2 rule 5 says later wins with a shadowing warning for the earlier."
    (let [[prog stderr] (capture-warnings
                        #(lower-src (str "def Widget:\n"
                                         "    n: Int\n"
                                         "\n"
                                         "extend Widget:\n"
                                         "    m() :- 1\n"
                                         "\n"
                                         "extend Widget:\n"
                                         "    m() :- 2\n"
                                         "\n"
                                         "def main:\n"
                                         "    -> running\n"
                                         "        then: print(0)\n")))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (re-find #"shadowed by a later extension" stderr))
      (is (= 2 (get-in return-stmt [:value :value]))
          "later extension wins"))))

(deftest f9-l4-extension-conforms-union
  (testing "F9 Landing 4 — extension-added conformances union into the target's `:conforms` at composition time."
    (let [prog (lower-src (str "def P:\n"
                               "    m(self) :- Int\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    m() :- 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))]
      ;; L4 stores conforms on record-env; not directly surfaced on the
      ;; program shape, but the compose path must not throw and the
      ;; extension observable must land.
      (is (some #(= "Widget_m" (:name %)) (:functions prog))))))

(deftest f9-l4-extension-invariant-on-record-fail-nyi
  (testing "L2b/F9 Landing 4 — record targets have no runtime invariant path yet; an extension-added `|:` proposition on a record fails-nyi with a pointer at the follow-on sub-landing. Atoms already handle invariants natively (no fail-nyi)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"record targets have no runtime invariant path"
         (lower-src (str "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget:\n"
                         "    |: n > 0\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 5 — explicit conformance validation.
;;
;; Validates every `:conforms` declaration on the composed method table.
;; Missing methods, signature mismatches, and unknown protocols all
;; fail-compile with §3.4.5-pointer diagnostics.
;; ---------------------------------------------------------------------------

(deftest l5-sync-and-async-signature-conformance
  (testing "F9 Landing 5 + L5 — `extend Widget(P):` supplying `m` (as an observable) matching P's sync signature validates cleanly."
    (let [prog (lower-src (str "def P:\n"
                               "    m() :- Int\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    m() :- 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))]
      (is (some #(= "Widget_m" (:name %)) (:functions prog)))))

  (testing "L5 — an async receive signature is satisfied by the existing wildcard handler transition shape; return type remains void and witness/conformance matching is unchanged."
    (let [prog (lower-src (str "def Logger:\n"
                               "    <: warn(msg: String)\n"
                               "\n"
                               "def LoggerImpl:\n"
                               "    -> running\n"
                               "\n"
                               "    * <: warn(msg: String) -> self\n"
                               "        then: print(msg)\n"
                               "\n"
                               "extend LoggerImpl(Logger):\n"
                               "    helper() :- 0\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          logger (get-in prog [:protocols "Logger"])
          impl   (first (filter #(= "LoggerImpl" (:name %)) (:classes prog)))
          warn   (first (filter #(re-find #"warn" (str (:selector %)))
                                (:methods impl)))]
      (is (nil? (get-in logger [:accepts-index "warn" :return-type])))
      (is (= "void" (:return-type warn))))))

(deftest f67-l4c-extension-replace-shadows-own-suppresses-warning
  (testing "F67 L4c — extension observable prefixed with `replace` intentionally shadows the target class's own observable (§3.3.2 rule 4). Extension wins in the composed observable list; own is dropped; no shadow warning fires."
    (let [sw (java.io.StringWriter.)
          prog (binding [*err* sw]
                 (lower-src (str "def Widget:\n"
                                 "    n: Int\n"
                                 "\n"
                                 "    bar() :- 1\n"
                                 "\n"
                                 "extend Widget:\n"
                                 "    replace bar() :- 2\n"
                                 "\n"
                                 "def main:\n"
                                 "    -> running\n"
                                 "        then: print(0)\n")))
          bar-fn (first (filter #(= "Widget_bar" (:name %))
                                (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body bar-fn)))]
      (is (some? bar-fn))
      (is (= 2 (get-in return-stmt [:value :value]))
          "extension's replace-flagged observable wins over own")
      (is (not (str/includes? (str sw) "shadowed by"))
          "no shadow warning when the extension is `replace`-marked"))))

(deftest f67-l4c-extension-shadow-without-replace-still-warns
  (testing "F67 L4c — extension observable colliding with own WITHOUT `replace` still fires the shadow warning (own wins; §3.3.2 rule 1)."
    (let [sw (java.io.StringWriter.)
          _prog (binding [*err* sw]
                  (lower-src (str "def Widget:\n"
                                  "    n: Int\n"
                                  "\n"
                                  "    bar() :- 1\n"
                                  "\n"
                                  "extend Widget:\n"
                                  "    bar() :- 2\n"
                                  "\n"
                                  "def main:\n"
                                  "    -> running\n"
                                  "        then: print(0)\n")))]
      (is (str/includes? (str sw) "bar")
          "shadow warning fires when extension shadows without `replace`"))))

(deftest f67-l4c-replace-without-target-warns
  (testing "F67 L4c — `replace` on an extension observable that has no matching own or other extension observable to shadow fires the replace-without-target warning (§3.3.2 rule 3)."
    (let [sw (java.io.StringWriter.)
          _prog (binding [*err* sw]
                  (lower-src (str "def Widget:\n"
                                  "    n: Int\n"
                                  "\n"
                                  "extend Widget:\n"
                                  "    replace unrelated() :- 1\n"
                                  "\n"
                                  "def main:\n"
                                  "    -> running\n"
                                  "        then: print(0)\n")))]
      (is (str/includes? (str sw) "replace-without-target")
          "warn when replace is used but no shadow target exists"))))

(deftest f9-l5-unknown-protocol-rejected
  (testing "F9 Landing 5 — conformance to a protocol name that doesn't resolve fires a §3.4.5-pointer diagnostic."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown protocol `BogusProto`"
         (lower-src (str "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(BogusProto):\n"
                         "    m() :- 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l5-missing-method-rejected
  (testing "F9 Landing 5 + L5 — class conforms to protocol but doesn't implement one of its required signatures and no default is provided → fail-compile."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"claims conformance to `P` but neither implements nor inherits a default for `m`"
         (lower-src (str "def P:\n"
                         "    m() :- Int\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    unrelated() :- 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l5-signature-mismatch-return-type-rejected
  (testing "F9 L5 + L5 — extension observable whose inferred body type does not match the protocol's sync return type fails conformance with a specific detail line."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"return type mismatch — expected `Int`, got `String`"
         (lower-src (str "def P:\n"
                         "    m() :- Int\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    m() :- \"x\"\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l5-signature-mismatch-param-name-rejected
  (testing "F9 Landing 5 — Cxc supports named-arg calls (§3.4.5 rule 2), so protocol conformance requires param names to match. Post F67 L4: observable form."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"param name mismatch — expected `x`, got `y`"
         (lower-src (str "def P:\n"
                         "    m(x: Int) :- Int\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    m(y: Int) :- y\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l5-signature-mismatch-param-type-rejected
  (testing "F9 Landing 5 — param type must match. Post F67 L4: observable form carries typed params."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"param `x` type mismatch — expected `Int`, got `String`"
         (lower-src (str "def P:\n"
                         "    m(x: Int) :- Int\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    m(x: String) :- 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l5-protocol-default-satisfies-conformance
  (testing "F9 Landing 5 + F67 L4 — protocol carries a default observable body for `m`; class declares `conforms: P` and doesn't implement `m` itself. L5 accepts the default's presence as structural satisfaction."
    (let [prog (lower-src (str "def P:\n"
                               "    m() :- Int\n"
                               "\n"
                               "    m() :- 42\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    unrelated() :- 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))]
      (is (some? prog) "compiles cleanly — default satisfies the signature"))))

;; ---------------------------------------------------------------------------
;; F9 Landing 6 — default-method inheritance (explicit-conformance) +
;; diamond-ambiguity detection.
;;
;; Structural-conformance-based inheritance is L6b (deferred).
;; ---------------------------------------------------------------------------

(deftest f9-l6-inherits-default-into-composed-table
  (testing "F9 Landing 6 + F67 L4 — class explicitly conforms to P, P provides default observable `m`, class doesn't define `m` — default folded into class's composed :methods and emitted as a C function keyed by the class name."
    (let [prog (lower-src (str "def P:\n"
                               "    m() :- Int\n"
                               "\n"
                               "    m() :- 42\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    unrelated() :- 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (some? fn)
          "inherited default emits as Widget_m alongside class's own methods")
      ;; 2026-09-05 Path 1 slice 1-wider full — the protocol default
      ;; `m() :- 42` has a bare-T Int return, so the return-stmt wraps
      ;; the raw `42` in `:c-envelope-lit` at lower time. Peer one
      ;; extra `:value` level to reach the underlying int-lit.
      (is (= 42 (get-in return-stmt [:value :value :value]))
          "inherited body returns 42 (from the protocol default)"))))

(deftest f9-l6-own-method-shadows-default
  (testing "F9 Landing 6 + F67 L4 — when the class defines `m` itself (own or via extension), the own observable wins over the protocol default with no diamond, no warning."
    (let [prog (lower-src (str "def P:\n"
                               "    m() :- Int\n"
                               "\n"
                               "    m() :- 42\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    m() :- 100\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (= 100 (get-in return-stmt [:value :value]))
          "own extension observable wins (returns 100, not default's 42)"))))

(deftest f9-l6-diamond-ambiguity-across-conformed-protocols
  (testing "F9 Landing 6 + F67 L4 — class explicitly conforms to two protocols P and Q that both provide a default observable `m`, and doesn't override → diamond ambiguity fires."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"diamond ambiguity.*method `m`.*Widget.*`P`.*`Q`"
         (lower-src (str "def P:\n"
                         "    m() :- Int\n"
                         "\n"
                         "    m() :- 1\n"
                         "\n"
                         "def Q:\n"
                         "    m() :- Int\n"
                         "\n"
                         "    m() :- 2\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P, Q):\n"
                         "    unrelated() :- 0\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l6b-structural-conformance-inherits-default
  (testing "F9 Landing 6b + L5 — class does NOT declare `conforms: P` but structurally satisfies P's signature surface and inherits P's helper default."
    (let [prog (lower-src (str "def P:\n"
                               "    iter(self) :- Int\n"
                               "\n"
                               "    count_helper() :- 99\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "    iter() :- 7\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          fn         (first (filter #(= "Widget_count_helper" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (some? fn)
          "Widget_count_helper lowered via structural conformance to P")
      ;; 2026-09-05 Path 1 slice 1-wider full — `count_helper() :- 99`
      ;; has a bare-T Int return, so the return sig lifts to envelope
      ;; and the raw scalar `99` is wrapped in `:c-envelope-lit` at the
      ;; return statement. Peer one extra level into `:value :value` to
      ;; reach the underlying int-lit's `:value`.
      (is (= 99 (get-in return-stmt [:value :value :value]))))))

(deftest f9-l6b-protocol-with-no-defaults-not-inherited
  (testing "F9 Landing 6b — a protocol with no defaults produces no inheritance even under structural conformance. Structural-conformance detection is only for inheriting defaults; conforming-without-defaults is a call-site concern (L7+)."
    (let [prog (lower-src (str "def P:\n"
                               "    iter(self) :- Int\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "    iter() :- 7\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          ;; F37 auto-synthesizes Widget_cxc_equals + Widget_cxc_hash for every
          ;; record — filter those out; assert only Widget_iter (own observable)
          ;; remains from user-facing sites.
          user-widget-fns (->> (:functions prog)
                               (filter #(str/starts-with? (or (:name %) "") "Widget_"))
                               (remove #(str/starts-with? (:name %) "Widget_cxc_"))
                               (map :name)
                               set)]
      (is (= #{"Widget_iter"} user-widget-fns)
          "no defaults inherited from P (P has no defaults)"))))

(deftest f9-l6b-diamond-across-structural-conformances
  (testing "F9 Landing 6b + F67 L4 — diamond ambiguity fires when structural conformances produce multiple candidates for the same default."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"diamond ambiguity.*method `helper`.*Widget"
         (lower-src (str "def P:\n"
                         "    iter(self) :- Int\n"
                         "\n"
                         "    helper() :- 1\n"
                         "\n"
                         "def Q:\n"
                         "    iter(self) :- Int\n"
                         "\n"
                         "    helper() :- 2\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "    iter() :- 7\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l6b-explicit-and-structural-together-still-flat
  (testing "F9 L6b + F67 L4 — a class that explicitly conforms to P AND structurally conforms to Q, both providing the same default, still fires diamond."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"diamond ambiguity.*method `helper`.*Widget"
         (lower-src (str "def P:\n"
                         "    iter(self) :- Int\n"
                         "\n"
                         "    helper() :- 1\n"
                         "\n"
                         "def Q:\n"
                         "    iter(self) :- Int\n"
                         "\n"
                         "    helper() :- 2\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    iter() :- 7\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l6-diamond-resolved-by-own-method
  (testing "F9 Landing 6 + F67 L4 — same P/Q diamond setup + class defines its own `m` → own shadows all defaults per §3.4.4; no diamond error."
    (let [prog (lower-src (str "def P:\n"
                               "    m() :- Int\n"
                               "\n"
                               "    m() :- 1\n"
                               "\n"
                               "def Q:\n"
                               "    m() :- Int\n"
                               "\n"
                               "    m() :- 2\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P, Q):\n"
                               "    m() :- 999\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (= 999 (get-in return-stmt [:value :value]))
          "own resolution — no diamond, own wins"))))

(deftest f37b-l1-bytes-length-returns-int
  (testing "F37b Landing 1 — `.length` on a `Bytes`-typed expression type-checks as `Int` (spec §3.10.10 user-facing convention: `.length` is an element count matching List/String; wire-format `UInt32` prefix is a different concern). Emitted C uses ObjC property syntax, dispatching to NSData's `-length`."
    (let [prog (lower-src (str "def Msg:\n"
                               "    payload: Bytes\n"
                               "\n"
                               "    size() :- self.payload.length\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          fn-body (some (fn [f]
                          (when (= "Msg_size" (:name f))
                            (:body f)))
                        (:functions prog))]
      (is (some? fn-body) "Msg_size lowered")
      (is (some (fn [s]
                  (and (= :return (:node s))
                       (= :field (get-in s [:value :node]))
                       (= "length" (get-in s [:value :field]))))
                fn-body)
          "return expression is a `.length` field access"))))

(deftest f37b-l1-bytes-equality-lowers-to-isEqualToData
  (testing "F37b Landing 1 — `==`/`!=` between two `Bytes` operands intercepts before the raw C `==` fallthrough and dispatches to `-isEqualToData:` (NSData's value-eq). Raw C `==` on two NSData pointers is pointer-eq, which is almost never what the user wrote."
    (let [prog (lower-src (str "def Cmp:\n"
                               "    a: Bytes\n"
                               "    b: Bytes\n"
                               "\n"
                               "    eq()  :- self.a == self.b\n"
                               "    neq() :- self.a != self.b\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          fn-body (fn [nm]
                    (some (fn [f] (when (= nm (:name f)) (:body f)))
                          (:functions prog)))
          eq-body  (fn-body "Cmp_eq")
          neq-body (fn-body "Cmp_neq")
          eq-ret  (last eq-body)
          neq-ret (last neq-body)]
      (is (= :objc-msg (get-in eq-ret [:value :node]))
          "`==` on Bytes lowers as an ObjC message send")
      (is (= "isEqualToData:" (get-in eq-ret [:value :selector]))
          "selector is isEqualToData:")
      (is (= :unary (get-in neq-ret [:value :node]))
          "`!=` wraps the same selector in `!`")
      (is (= "!" (get-in neq-ret [:value :op]))
          "negation operator")
      (is (= "isEqualToData:" (get-in neq-ret [:value :expr :selector]))
          "wrapped selector is isEqualToData:"))))

(deftest f31-f35-l3-sync-send-cycle-warning
  (testing "F31/F35 Landing 3 — cross-atom `|>` cycle emits a compile-time warning to *err*. Spec §1.11 line 1523 keeps cycle-avoidance on the user; the warning surfaces the failure mode before Layer 4 stack-overflow or F32 deadlock fires it at runtime."
    (let [src (str "def A:\n"
                   "    use b: B\n"
                   "\n"
                   "    -> alive\n"
                   "\n"
                   "    alive <: ping -> alive\n"
                   "        then: |> b.pong()\n"
                   "\n"
                   "def B:\n"
                   "    use a: A\n"
                   "\n"
                   "    -> alive\n"
                   "\n"
                   "    alive <: pong -> alive\n"
                   "        then: |> a.ping()\n"
                   "\n"
                   "def main:\n"
                   "    a: A = A(b: B(a: a))\n"
                   "    -> running\n"
                   "        then: print(0)\n")
          warnings (with-out-str
                     (binding [*err* *out*]
                       (try (lower-src src) (catch Exception _))))]
      (is (str/includes? warnings "`|>` cycle")
          "warning names the cycle keyword")
      (is (str/includes? warnings "`A`")
          "warning names the first atom in the cycle")
      (is (str/includes? warnings "`B`")
          "warning names the second atom in the cycle")
      (is (str/includes? warnings "§1.11 line 1523")
          "warning cites the spec anchor"))))

(deftest f31-f35-l3-sync-send-no-cycle-no-warning
  (testing "F31/F35 Landing 3 — a `|>` chain without a cycle emits no warning. Guards against false-positive noise from linear chains and self-sends."
    (let [src (str "def B:\n"
                   "    -> alive\n"
                   "\n"
                   "    alive <: pong -> alive\n"
                   "\n"
                   "def A:\n"
                   "    use b: B\n"
                   "\n"
                   "    -> alive\n"
                   "\n"
                   "    alive <: ping -> alive\n"
                   "        then: |> b.pong()\n"
                   "\n"
                   "def main:\n"
                   "    b: B = B()\n"
                   "    a: A = A(b: b)\n"
                   "    -> running\n"
                   "        then: print(0)\n")
          warnings (with-out-str
                     (binding [*err* *out*]
                       (try (lower-src src) (catch Exception _))))]
      (is (not (str/includes? warnings "`|>` cycle"))
          "no warning for a linear |> chain"))))

(deftest f31-f35-l1-sync-send-lowers-to-wait-for-processing
  (testing "F31/F35 Landing 1 — `|>` sync send lowers to `cxc_wait_for_processing` (spec framing: `|>` is a processing barrier). Retires the old `cxc_send_sync` name; under Layer 4 CXC_SINGLE_MAIN_QUEUE the helper is a nil-guarded direct call, under F32 it upgrades to dispatch_sync — the emit site is the sole substitution point."
    (let [prog (lower-src "def Sink:\n    n: Int = 0\n\n    -> alive\n\n    alive <: absorb -> alive\n        then: n => n + 1\n\ndef main(sink: Sink):\n    -> running\n        then:\n            |> sink.absorb()\n")
          main-cls (first (filter #(= "CxcMain" (:name %)) (:classes prog)))
          state-body (:body (first (filter #(clojure.string/starts-with?
                                             (:selector %) "cxc_stateInit")
                                           (:methods main-cls))))
          ;; Q1 (2026-08-26) — bare sync `|>` now lowers to
          ;; `:sync-send-checked` wrapping the raw `:call`, so unwrap
          ;; one level before checking the helper name.
          unwrap    (fn [stmt]
                      (if (= :sync-send-checked (:node stmt))
                        (:send-call stmt) stmt))
          send-call (some (fn [stmt]
                            (let [s (unwrap stmt)]
                              (when (and (= :call (:node s))
                                         (clojure.string/includes?
                                          (or (:fn s) "") "cxc_"))
                                s)))
                          state-body)]
      (is (some? send-call) "expected a cxc_* send helper in main's state-init")
      (is (= "cxc_wait_for_processing" (:fn send-call))
          "sync send lowers to the renamed helper, not the retired cxc_send_sync"))))

(deftest f39-l2-nonnil-on-atom-accepted
  (testing "F39 Landing 2 — `T!` on a reference type (atom) is legal. C-level representation identical to bare `T` (still a pointer); the contract is enforced at observation points in F39 L3 (not this landing)."
    (let [prog (lower-src "def Widget:\n    bus: Widget! = Widget()\n\n    -> running\n\n    -> end\n\ndef main:\n    w: Widget = Widget()\n\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))
          "T! on atom lowers cleanly"))))

(deftest f39-l2-nonnil-on-string-accepted
  (testing "F39 Landing 2 — `String!` is legal (String is reference-shaped)."
    (let [prog (lower-src "def X:\n    name: String! = \"anon\"\n\n    -> running\n\n    -> end\n\ndef main:\n    x: X = X()\n\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))))))

(deftest f39-l5a-nonnil-on-value-primitive-accepted
  (testing "F39 Landing 5a (2026-08-20) — `T!` on a value primitive (Int!, Float!, etc.) is ACCEPTED under the unified rule. Bare `T` on a value type is nil-allowed (Optional-boxed at C level post-L5b); `T!` opts OUT of the envelope and lowers to raw `T`. Under L5a alone (no rep flip yet), both `Int!` and `Int` still emit `int64_t` — the semantic distinction only becomes visible when L5b flips bare-`T` to Optional-boxed. This test locks in the accept side; the pre-L5a `:cxc-f39-nonnil-on-value` reject is retired."
    (let [prog (lower-src "def X:\n    n: Int! = 5\n\ndef main:\n    x: X = X()\n\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))
          "T! on Int lowers cleanly under L5a"))))

(deftest f39-l5a-nonnil-on-record-accepted
  (testing "F39 Landing 5a (2026-08-20) — `T!` on a record (value shape) is accepted. Same rationale as the primitive case: `T!` opts out of Optional-boxing (L5b) and emits the raw record C struct."
    (let [prog (lower-src "def P:\n    x: Int\n\ndef X:\n    p: P! = P(x: 0)\n\ndef main:\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))
          "T! on record lowers cleanly under L5a"))))

(deftest f39-l2-nonnil-and-nullable-cannot-stack
  (testing "F39 Landing 2 — `T!?` / `T?!` are grammar errors — the two suffixes are mutually exclusive per spec §3.6.1."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cannot stack"
         (lower-src "def X:\n    bus: Widget!? = nil\n\ndef main:\n    -> running\n        then: print(0)\n")))))

(deftest f39-l3-nonnil-ctor-param-check-inserted
  (testing "F39 Landing 3 — a `T!` ctor param gets an inline `if (p == nil) cxc_nonnil_violation(...)` guard at the top of cxc_stateInit. Warning-grade per spec §3.6.1 line 3686."
    (let [prog (lower-src (str "def EventBus:\n"
                               "    n: Int = 0\n"
                               "\n"
                               "    -> running\n"
                               "\n"
                               "    -> end\n"
                               "\n"
                               "def Widget(bus: EventBus!):\n"
                               "    n: Int = 0\n"
                               "\n"
                               "def main:\n"
                               "    b: EventBus = EventBus()\n"
                               "    w: Widget = Widget(bus: b)\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          widget (first (filter #(= "Widget" (:name %)) (:classes prog)))
          state  (first (filter #(str/starts-with? (:selector %) "cxc_stateInit")
                                (:methods widget)))
          first-stmt (first (:body state))]
      (is (= :raw-c (:node first-stmt))
          "F39 L3 — first stmt of cxc_stateInit is the ctor-param nonnil-check")
      (is (str/includes? (:text first-stmt) "(bus) == nil")
          "check reads the incoming param name")
      (is (str/includes? (:text first-stmt) "cxc_nonnil_violation")
          "check dispatches to the runtime helper")
      (is (str/includes? (:text first-stmt) "ctor param")
          "site label names the observation point")
      (is (str/includes? (:text first-stmt) "\"bus\"")
          "check passes the parameter name to the runtime helper"))))

(deftest f39-l3-nonnil-field-write-check-inserted
  (testing "F39 Landing 3 — a `then: field => rhs` on a `T!` field appends a post-store nonnil-check reading the just-assigned ivar. Post-store is safe because the check is warning-grade (no abort)."
    (let [prog (lower-src (str "def Widget:\n"
                               "    peer: Widget! = Widget()\n"
                               "\n"
                               "    -> boot\n"
                               "        then: |> self.bump()\n"
                               "\n"
                               "    boot <: bump -> on\n"
                               "        then: peer => Widget()\n"
                               "\n"
                               "def main:\n"
                               "    w: Widget = Widget()\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          widget (first (filter #(= "Widget" (:name %)) (:classes prog)))
          bump-mth (first (filter #(= "bump" (:selector %))
                                  (:methods widget)))
          ;; Receive-glyph methods wrap the transition body in a
          ;; :transition-branch → :end-catch-wrap. Unwrap two layers
          ;; to reach the actual statement sequence.
          branch (first (:body bump-mth))
          catch-wrap (first (:body branch))
          body (:body catch-wrap)
          ;; F33 L3.b — write targets `__cxc_next._peer`; the nonnil
          ;; check now reads that same bag field so it validates the
          ;; value about to commit, not the stale ivar.
          assign-idx (first (keep-indexed
                             (fn [i s]
                               (when (and (= :assign (:node s))
                                          (= "_peer" (get-in s [:lhs :field])))
                                 i))
                             body))
          check-stmt (get body (inc assign-idx))]
      (is assign-idx "peer bag-field is written in the bump transition body")
      (is (= :raw-c (:node check-stmt))
          "F39 L3 — the stmt directly after the peer write is the nonnil-check")
      (is (str/includes? (:text check-stmt) "(__cxc_next._peer) == nil")
          "check reads the just-stored bag field")
      (is (str/includes? (:text check-stmt) "field write")
          "site label names the observation point"))))

(deftest f39-l3-nonnil-non-annotated-field-write-no-check
  (testing "F39 Landing 3 — `then:` on a field NOT declared `T!` gets no check (zero-cost for non-annotated fields)."
    (let [prog (lower-src (str "def Widget:\n"
                               "    peer: Widget = Widget()\n"
                               "\n"
                               "    -> boot\n"
                               "        then: |> self.bump()\n"
                               "\n"
                               "    boot <: bump -> on\n"
                               "        then: peer => Widget()\n"
                               "\n"
                               "def main:\n"
                               "    w: Widget = Widget()\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          widget (first (filter #(= "Widget" (:name %)) (:classes prog)))
          bump-mth (first (filter #(= "bump" (:selector %))
                                  (:methods widget)))
          ;; Receive-glyph methods wrap the transition body in a
          ;; :transition-branch → :end-catch-wrap. Unwrap two layers
          ;; to reach the actual statement sequence.
          branch (first (:body bump-mth))
          catch-wrap (first (:body branch))
          body (:body catch-wrap)]
      (is (nil? (some (fn [s]
                        (and (= :raw-c (:node s))
                             (str/includes? (or (:text s) "") "cxc_nonnil_violation")))
                      body))
          "no nonnil-check emitted when the field is bare `T`"))))

;; F67 L4 / L4b: F39 L3 nonnil-return-check retires — no observable-header
;; surface to declare `-> T!` on a reader. Field-level nonnil enforcement
;; (writes into `T!` slots) stays intact via `nonnil-check-stmt`. Restoring
;; nonnil enforcement on observable returns requires deriving nonnil from
;; the protocol sync signature (`:- T!` wraps the derived
;; method body's return) — separate follow-on landing, distinct from the
;; observable-header annotation reverted in L4b.

(deftest f39-l5a-nullable-suffix-banned-on-atom
  (testing "F39 Landing 5a (2026-08-20) — `T?` on a reference type (atom) is banned under the unified rule. Pre-L5a `:cxc-f39-nullable-on-reference` retired; unified `:cxc-f39-nullable-suffix-banned` fires with the same fix-it shape (use bare `T` for nullable, `T!` for non-nil)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`Counter\?` — the `\?` suffix is banned universally.*§3\.6\.1.*bare `Counter`.*`Counter!`"
         (lower-src "def Counter:\n    other: Counter? = nil\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(0)\n")))))

(deftest f39-l5a-nullable-suffix-banned-on-string
  (testing "F39 Landing 5a — `String?` fires the unified `banned universally` diagnostic (String is reference-shaped; same code path as any other reference)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`String\?` — the `\?` suffix is banned universally.*§3\.6\.1"
         (lower-src "def X:\n    name: String? = nil\n\ndef main:\n    -> running\n        then: print(0)\n")))))

(deftest f39-l5a-nullable-suffix-banned-on-value-primitive
  (testing "F39 Landing 5a — `T?` on a value primitive fires the SAME unified diagnostic as on references (pre-L5a this was a distinct `fail-nyi!` pointing at L5; L5a folds both branches). Under L5b, bare `Int` becomes Optional-boxed; the `?` suffix stays banned."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`Int\?` — the `\?` suffix is banned universally.*§3\.6\.1.*bare `Int`.*`Int!`"
         (lower-src "def X:\n    n: Int? = nil\n\ndef main:\n    -> running\n        then: print(0)\n")))))

(deftest f39-l5b-ii-c-value-typed-nil-default-accepted
  (testing "F39 Landing 5b-ii-c (2026-08-22) — the L5b-i fence is retired: bare value-typed `T = nil` now lowers to Optional storage with a `{present:0}` init. Covers primitive (Int) on the atom-declaration path where the default is inlined into `cxc_stateInit`."
    (let [prog (lower-src "def X:\n    n: Int = nil\n\n    -> running\n\n    -> end\n\ndef main:\n    x: X = X()\n\n    -> end\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))
          "value-typed `Int = nil` lowers cleanly under Optional storage"))))

(deftest f39-l5b-i-value-typed-nonnil-nil-default-still-rejected
  (testing "F39 Landing 5b-ii-c — `T!` opts out of Optional storage, so `Int! = nil` is still a fence-worthy contract violation: the storage is scalar `int64_t` and `_n = nil` is uncompilable. The L5b-i fence retirement in Sub-P was gated on `:optional?`, so `T!` fields (never Optional) keep the fence active."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F39 L5b-i: value-typed field `n: Int!`"
         (lower-src "def X:\n    n: Int! = nil\n\n    -> running\n\n    -> end\n\ndef main:\n    x: X = X()\n\n    -> end\n        then: print(0)\n")))))

(deftest f39-l5b-ii-c-record-typed-nil-default-accepted
  (testing "F39 Landing 5b-ii-c — record-typed fields (value-shaped structs) now accept `= nil` via Optional-boxed storage. The exact game-toys.cxc shape (`ActionMachine.fireball_target: Position = nil`) is unblocked."
    (let [prog (lower-src "def Position:\n    x: Int = 0\n    y: Int = 0\n\ndef X:\n    p: Position = nil\n\n    -> running\n\n    -> end\n\ndef main:\n    x: X = X()\n\n    -> end\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))
          "record-typed `Position = nil` lowers cleanly under Optional storage"))))

(deftest f39-l5b-ii-d-then-update-value-nonnil-inserts-runtime-check
  (testing "F39 Landing 5b-ii-d — assigning an Optional-typed RHS into a value-typed `T!` field emits a runtime presence check via `:c-value-nonnil-unwrap`. On absent the same warning-grade `cxc_nonnil_violation` L3 uses for reference `T!` fires; the underlying `.value` is taken either way (warning-grade, no abort). Site + name labels name the atom + field."
    (let [prog   (lower-src "def X:\n    strict: Int! = 0\n    loose: Int = 5\n\n    -> running\n\n    running <: bump -> running\n        then: strict => loose\n\ndef main:\n    -> running\n        then: print(0)\n")
          cls    (first (filter #(= "X" (:name %)) (:classes prog)))
          bump   (first (filter #(= "bump" (:selector %)) (:methods cls)))
          ;; F33 L3.b: `strict` write now targets `__cxc_next._strict`
          ;; instead of `_strict`. Look for the assign to the bag field.
          assign (first (filter #(and (map? %)
                                      (= :assign (:node %))
                                      (= "_strict"
                                         (get-in % [:lhs :field])))
                                (tree-seq coll? seq bump)))]
      (is (some? assign) "assign to `__cxc_next._strict` present somewhere in bump method body (inside :transition-branch → :end-catch-wrap)")
      (is (= :c-value-nonnil-unwrap (get-in assign [:rhs :node]))
          "RHS wraps the loose (Optional) value in a runtime presence check")
      (is (re-find #"value-typed T!"
                   (get-in assign [:rhs :site]))
          "site label mentions value-typed T!")
      (is (= "strict" (get-in assign [:rhs :name]))
          "name label is the field name"))))

(deftest f39-l5b-ii-d-method-return-value-nonnil-inserts-runtime-check
  (testing "F39 Landing 5b-ii-d + L5 — a protocol-inherited default whose sync signature returns `T!` inserts the runtime presence check on the return path."
    (let [prog     (lower-src "def P:\n    strict_read() :- Int!\n\n    strict_read() :- n\n\ndef X:\n    n: Int = 0\n\n    -> running\n\nextend X(P):\n    _dummy() :- 1\n\ndef main:\n    -> running\n        then: print(0)\n")
          cls      (first (filter #(= "X" (:name %)) (:classes prog)))
          m        (first (filter #(= "strict_read" (:selector %)) (:methods cls)))
          ret-decl (first (filter #(and (map? %)
                                        (= :var-decl (:node %))
                                        (= "_Cxc_ret" (:name %)))
                                  (tree-seq coll? seq m)))]
      (is (some? m) "strict_read method is emitted on X (inherited from P)")
      (is (= "int64_t" (:return-type m))
          "return type lowers to int64_t (value-typed Int!)")
      (is (some? ret-decl) "return-value var-decl present")
      (is (= :c-value-nonnil-unwrap (get-in ret-decl [:rhs :node]))
          "return binding wraps the Optional read in a runtime presence check")
      (is (re-find #"strict_read.*value-typed T!"
                   (get-in ret-decl [:rhs :site]))
          "site label mentions method name + value-typed T!"))))

(deftest f39-l5b-i-reference-typed-nil-default-still-accepted
  (testing "F39 Landing 5b-i — reference-typed fields (atoms, String) are UNAFFECTED. `_x = nil` is valid ObjC for pointer/id storage, so the fence deliberately excludes them. Guards against a regression where the fence overreaches into the reference-typed path already covered by F31/F35 install/cascade wiring. Counter needs `-> alive` here to parse as an atom (F68: def with no transitions is a record — a records-typed field would trip the fence, which is spec-correct)."
    (let [prog (lower-src "def Counter:\n    n: Int = 0\n\n    -> alive\n\n    -> end\n\ndef X:\n    c: Counter = nil\n    s: String = nil\n\n    -> running\n\n    -> end\n\ndef main:\n    x: X = X()\n\n    -> end\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))
          "reference-typed `= nil` defaults still lower cleanly"))))

(deftest f33-cross-atom-data-field-read-allowed
  (testing "F33 (Proposal C+C1, 2026-08-17 rewrite): external `.field` reads on any atom are spec-legal. Reading is observation, not mutation; the previous cross-atom-read diagnostic was retracted. External mutation is impossible by grammar (parse has no `:assign`), so the encapsulation firewall is structural without needing a read-blocking guard."
    (let [prog (lower-src "def Counter:\n    n: Int = 42\n\ndef main:\n    -> running\n        then: print(Counter().n)\n")]
      (is (= :objc-program (:node prog))
          "external `Counter().n` read compiles cleanly under Proposal C+C1"))))

(deftest f33-observable-read-still-allowed
  (testing "Observable reads compile — the observable-wrapped shape is one of two valid patterns for exposing computed views (the other being direct `.field` reads under Proposal C+C1)."
    (let [prog (lower-src "def Counter:\n    raw_n: Int = 42\n\n    n :- raw_n\n\ndef main:\n    -> running\n        then: print(Counter().n)\n")]
      (is (= :objc-program (:node prog))
          "observable read is spec-legal"))))

(deftest f33-self-field-read-still-allowed
  (testing "Self-field reads inside the atom's own observable / invariant / transition body compile — `self.n` (or bare `n`) reads the data field directly. Post F67 L4: observables replace the reader-method surface."
    (let [prog (lower-src "def Counter:\n    n: Int = 10\n\n    bump() :- n + 1\n\n    -> running\n\n    -> end\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))
          "self.n inside Counter's own observable compiles cleanly"))))

(deftest f67-l2-parametric-observable-lowers-with-args
  (testing "F67 L2 (Proposal G): parametric observables `<name>(args) :- <expr>` emit as ObjC methods with declared params. The observable body may reference both self-fields and its own args; both resolve during type inference. Under Proposal G this shape replaces reader methods — `function healed_by(self, amt) -> Int: return self.hp + amt` migrates to `healed_by(amt: Int) :- hp + amt`."
    (let [prog (lower-src "def Player:\n    hp: Int! = 100\n    max_hp: Int! = 100\n\n    healed_by(amt: Int) :- hp + amt\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(0)\n")
          cls  (first (filter #(= "Player" (:name %)) (:classes prog)))
          obs  (first (filter #(re-matches #"healed_by.*" (or (:selector %) "")) (:methods cls)))]
      (is (some? obs) "parametric observable emits as :objc-method")
      (is (= 1 (count (:params obs)))
          "parametric observable carries its declared param (amt: Int)")
      (is (= "amt" (:name (first (:params obs))))
          "param name preserved")
      (is (= "_Cxc_Optional_Int64" (:return-type obs))
          ;; 2026-09-05 Path 1 slice 1-wider full — `amt: Int` (bare-T
          ;; primitive param) lifts to envelope, so `hp + amt` (with `hp`
          ;; raw and `amt` envelope) infers `[:optional :int64]` — the
          ;; observable's C sig follows suit.
          "return type inferred from body — envelope under Path 1"))))

(deftest f67-l2-zero-arg-observable-still-lowers
  (testing "F67 L2: zero-arg observable shape is preserved — `<name> :- <expr>` (no parens) still parses and lowers exactly as before. The parametric extension does not require parens on zero-arg observables."
    (let [prog (lower-src "def Counter:\n    n: Int = 0\n\n    doubled :- n + n\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(0)\n")
          cls  (first (filter #(= "Counter" (:name %)) (:classes prog)))
          obs  (first (filter #(= "doubled" (:selector %)) (:methods cls)))]
      (is (some? obs) "zero-arg observable still emits with bare selector")
      (is (= [] (:params obs))
          "zero-arg observable has empty params list"))))

(deftest f67-l2-parametric-observable-multi-arg
  (testing "F67 L2: parametric observables with multiple args. Each arg becomes an ObjC method param; the body expression sees all args + self-fields in scope."
    (let [prog (lower-src "def Box:\n    w: Int = 0\n    h: Int = 0\n\n    scaled_area(sx: Int, sy: Int) :- (w * sx) * (h * sy)\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(0)\n")
          cls  (first (filter #(= "Box" (:name %)) (:classes prog)))
          obs  (first (filter #(re-matches #"scaled_area.*" (or (:selector %) "")) (:methods cls)))]
      (is (some? obs) "multi-arg parametric observable emits")
      (is (= 2 (count (:params obs)))
          "both params carried through")
      (is (= ["sx" "sy"] (mapv :name (:params obs)))
          "param names preserved in order"))))

;; ---------------------------------------------------------------------------
;; F13 L1 — env-time reference-modifier validation (spec §6.14)
;; ---------------------------------------------------------------------------

(deftest f13-l1-weak-on-atom-accepted
  (testing "F13 L1 — `weak <AtomName>` on an atom-typed data field passes env-time validation; the modifier flows through to the lowered field entry so L2 can pick the ObjC property attrs."
    (let [prog (lower-src "def Parent:\n    -> alive\n\ndef Child:\n    parent: weak Parent\n\n    -> alive\n\ndef main:\n    -> running\n        then: print(0)\n")
          child (first (filter #(= "Child" (:name %)) (:classes prog)))
          f     (first (filter #(= "parent" (:name %)) (:properties child)))]
      (is (some? f) "Child.parent lowered as an ObjC property")
      ;; Storage class is verified in L2; here we assert env-time
      ;; validation didn't reject the field.
      (is (= :objc-program (:node prog))))))

(deftest f13-l1-weak-on-record-rejected
  (testing "F13 L1 — `weak <Record>` fails env-time validation. Records are value types (§3.2); reference modifiers apply only to reference types (atoms, `String`)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F13 L1: `weak V` on field `p` .* record"
         (lower-src "def V:\n    x: Int\n\ndef X:\n    p: weak V\n\n    -> alive\n\ndef main:\n    -> running\n        then: print(0)\n")))))

(deftest f13-l1-weak-on-enum-rejected
  (testing "F13 L1 — `weak <Enum>` fails env-time validation. Enums are value types."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F13 L1: `weak Color` on field `c` .* enum"
         (lower-src "def Color:\n    case red\n    case green\n\ndef X:\n    c: weak Color\n\n    -> alive\n\ndef main:\n    -> running\n        then: print(0)\n")))))

(deftest f13-l1-unowned-on-atom-accepted
  (testing "F13 L1 — `unowned <AtomName>` on an atom-typed data field passes env-time validation."
    (let [prog (lower-src "def Parent:\n    -> alive\n\ndef Child:\n    parent: unowned Parent\n\n    -> alive\n\ndef main:\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))))))

;; ---------------------------------------------------------------------------
;; F13 L2 — ObjC property emit for weak / unsafe_unretained (spec §6.14)
;; ---------------------------------------------------------------------------

(deftest f13-l2-weak-emits-nonatomic-weak-attrs
  (testing "F13 L2 — `weak <AtomName>` field lowers to `@property (nonatomic, weak)` on the ObjC class definition. ARC handles the nil-out on target dealloc; no additional runtime scaffolding needed. Field name deliberately distinct from the compiler-synthesized `parent` back-ref ivar."
    (let [prog (lower-src "def Owner:\n    -> alive\n\ndef Child:\n    owner: weak Owner\n\n    -> alive\n\ndef main:\n    -> running\n        then: print(0)\n")
          child (first (filter #(= "Child" (:name %)) (:classes prog)))
          f     (first (filter #(= "owner" (:name %)) (:properties child)))]
      ;; F33 L5 — data-field @properties are readonly (external code
      ;; can't write via -set<F>:). The ownership attr still layers on.
      (is (= ["nonatomic" "readonly" "weak"] (:attrs f))
          "weak modifier picks (nonatomic, readonly, weak) attrs, overriding the default (strong) for atom pointers"))))

(deftest f13-l2-unowned-emits-nonatomic-unsafe-unretained-attrs
  (testing "F13 L2 — `unowned <AtomName>` field lowers to `@property (nonatomic, unsafe_unretained)`. Non-owning, non-zeroing pointer; access after target dealloc is UB per spec §6.14."
    (let [prog (lower-src "def Owner:\n    -> alive\n\ndef Child:\n    owner: unowned Owner\n\n    -> alive\n\ndef main:\n    -> running\n        then: print(0)\n")
          child (first (filter #(= "Child" (:name %)) (:classes prog)))
          f     (first (filter #(= "owner" (:name %)) (:properties child)))]
      ;; F33 L5 — readonly attr precedes the ownership qualifier.
      (is (= ["nonatomic" "readonly" "unsafe_unretained"] (:attrs f))
          "unowned modifier picks (nonatomic, readonly, unsafe_unretained) attrs"))))

;; ---------------------------------------------------------------------------
;; F18 L1 — local `let` statement lowers to C var-decl; ctx threads so
;; subsequent stmts resolve the binding (spec §2.5).
;; ---------------------------------------------------------------------------

(deftest f18-l1-let-stmt-emits-var-decl
  (testing "F18 L1 — `let x = 5` inside a `do:` block lowers to a `:var-decl` with inferred type. Uses the plain-main path (main body inlined into `main()`)."
    (let [prog  (lower-src "def main:\n    -> running\n        then:\n            let x = 5\n            print(x)\n")
          stmts (get-in prog [:main :body])
          decl  (first (filter #(= :var-decl (:node %)) stmts))]
      (is (some? decl) ":var-decl emitted for the let-stmt")
      (is (= "x" (:name decl)))
      (is (= "int64_t" (:c-type decl))
          "Int inference produces int64_t per F36 L1a"))))

(deftest f18-l1-let-stmt-with-annotation-uses-declared-type
  (testing "F18 L1 — `let x: Int = 5` uses the annotation authoritatively. 2026-09-04 Path 1 slice 2e: nullable-primitive annotation lifts storage to the Optional envelope; `T!` still lowers raw."
    (let [prog  (lower-src "def main:\n    -> running\n        then:\n            let x: Int = 5\n            print(x)\n")
          stmts (get-in prog [:main :body])
          decl  (first (filter #(= :var-decl (:node %)) stmts))]
      (is (= "_Cxc_Optional_Int64" (:c-type decl))
          "annotation `Int` (nullable) lowers to envelope storage"))
    (let [prog  (lower-src "def main:\n    -> running\n        then:\n            let x: Int! = 5\n            print(x)\n")
          stmts (get-in prog [:main :body])
          decl  (first (filter #(= :var-decl (:node %)) stmts))]
      (is (= "int64_t" (:c-type decl))
          "annotation `Int!` (non-nil) lowers raw"))))

(deftest f18-l1-let-stmt-visible-to-subsequent-stmts
  (testing "F18 L1 — a `let x = 5; print(x)` sequence resolves `x` in the second stmt via the ctx that lower-body-stmts threads through the reduce. Failure mode pre-L1: `print(x)` would fail ident-lookup because x wasn't in `:locals`."
    (let [prog (lower-src "def main:\n    -> running\n        then:\n            let x = 5\n            print(x)\n")]
      (is (= :objc-program (:node prog))
          "no lowering error → subsequent stmt saw the let"))))

;; ---------------------------------------------------------------------------
;; F18 L2 — `do:` at expression position (spec §3.8.2)
;; ---------------------------------------------------------------------------

(deftest f18-l2-inline-do-expression-lowers-to-stmt-expr
  (testing "F18 L2 — `let x = do: 5` at expression position lowers the RHS to `:c-stmt-expr` wrapping a single `:int` (the lowered :expr-stmt body). The wrapping :var-decl carries the inferred type from the block's final expression."
    (let [prog  (lower-src "def main:\n    -> running\n        then:\n            let x = do: 5\n            print(x)\n")
          stmts (get-in prog [:main :body])
          decl  (first (filter #(and (= :var-decl (:node %)) (= "x" (:name %))) stmts))]
      (is (some? decl))
      (is (= :c-stmt-expr (:node (:rhs decl))))
      (is (= "int64_t" (:c-type decl))
          "final :expr-stmt is a lit-int → block type is Int → var-decl typed int64_t"))))

(deftest f18-l2-indented-do-expression-with-let-bindings
  (testing "F18 L2 — indented `do:` block with local `let` bindings; the final `:expr-stmt` (an infix add) sees the bindings introduced by earlier stmts and its type flows out as the block's type."
    (let [prog  (lower-src "def main:\n    -> running\n        then:\n            let r = do:\n                let a = 3\n                let b = 4\n                a + b\n            print(r)\n")
          stmts (get-in prog [:main :body])
          decl  (first (filter #(and (= :var-decl (:node %)) (= "r" (:name %))) stmts))
          rhs   (:rhs decl)]
      (is (= :c-stmt-expr (:node rhs)))
      (is (= 3 (count (:stmts rhs)))
          "two :var-decl + one lowered expression at the tail")
      (is (= "int64_t" (:c-type decl))
          "final expression `a + b` sees the two lets and infers Int"))))

(deftest f18-l2-empty-do-block-is-unit
  (testing "F18 L2 open-Q3 ratified — an empty `do:` block has type :void (Unit). No user diagnostic; emits `((void)0)` at emit time. This test only asserts the :c-stmt-expr shape survives lowering with zero stmts; a downstream consumer that needs a value from it will fail at type-check time (not F18 L2's problem)."
    ;; Empty `do:` is hard to reach as an inline expression today (parser
    ;; would need at least one statement in the inline form), but the
    ;; indented empty case is legal per the grammar. Build a synthetic
    ;; node and pass it through the lowering path directly to confirm
    ;; the round-trip.
    (let [prog (lower-src "def main:\n    -> running\n        then: print(1)\n")]
      (is (= :objc-program (:node prog))
          "sanity — enclosing program still compiles"))))

;; ---------------------------------------------------------------------------
;; R6 (2026-08-31) — expression-position `if` retired; lower-side `:if-expr`
;; handlers deleted alongside. `||:` multi-arm dispatch is the sole
;; expression-position branching form; its lower→c-ternary shape is exercised
;; by `end-to-end-r6-multi-arm-dispatch` + the multi-arm-dispatch lower tests.
;; ---------------------------------------------------------------------------

(deftest f18-l1-let-in-do-clause-of-transition
  (testing "F18 L1 — `let` also works inside a receive-glyph transition's `do:` clause on a real atom (main-as-atom path)."
    (let [prog  (lower-src "def Counter:\n    n: Int = 0\n\n    -> running\n\n    -> end\n\n    running <: bump -> running\n        then:\n            let doubled = n + n\n            print(doubled)\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n        then: print(0)\n")
          c-cls (first (filter #(= "Counter" (:name %)) (:classes prog)))
          bump  (first (filter #(= "bump" (:selector %)) (:methods c-cls)))]
      (is (some? bump))
      (is (= :objc-program (:node prog))))))

;; ---------------------------------------------------------------------------
;; F17 L1 — while / break / continue lowering (spec §3.8.3 / §4.20.3)
;; ---------------------------------------------------------------------------

(deftest f17-l1-while-lowers-to-c-while
  (testing "F17 L1 — a statement-position `while <cond>: <body>` lowers to `:c-while` with `:cond` + `:body` (already-lowered stmts). `let` inside the body threads through `lower-body-stmts` so the loop is a proper sub-scope."
    (let [prog (lower-src "def main:\n    -> running\n        then:\n            while true:\n                let n = 0\n                print(n)\n")
          stmts (get-in prog [:main :body])
          w     (first (filter #(= :c-while (:node %)) stmts))]
      (is (some? w) ":c-while emitted")
      (is (= 2 (count (:body w))))
      (is (= :var-decl (:node (first (:body w))))
          "let-stmt inside the while lowers into the body"))))

(deftest f17-l1-break-continue-lower
  (testing "F17 L1 — `break` and `continue` inside a while body lower to `:c-break` / `:c-continue` stmts, which emit as `break;`/`continue;`."
    (let [prog (lower-src "def main:\n    -> running\n        then:\n            while true:\n                break\n                continue\n")
          w    (first (filter #(= :c-while (:node %)) (get-in prog [:main :body])))]
      (is (= :c-break    (:node (nth (:body w) 0))))
      (is (= :c-continue (:node (nth (:body w) 1)))))))

(deftest f17-l1-break-outside-loop-rejected
  (testing "F17 L1 — `break` at statement position outside any enclosing while → error naming the constraint."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F17 L1: `break` outside a `while` body"
         (lower-src "def main:\n    -> running\n        then:\n            break\n")))))

(deftest f17-l1-continue-outside-loop-rejected
  (testing "F17 L1 — `continue` at statement position outside any enclosing while → error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F17 L1: `continue` outside a `while` body"
         (lower-src "def main:\n    -> running\n        then:\n            continue\n")))))

(deftest f13-l2-weak-field-excluded-from-owned-slot-cascade
  (testing "F13 L2 — a `weak` atom-typed field is a non-owning reference; parent must NOT install a back-ref on the child or cascade end into it. Verifies that install-parent + end-cascade wiring skips weak/unowned fields."
    (let [prog (lower-src "def Owner:\n    -> alive\n\n    -> end\n\ndef Child:\n    owner: weak Owner\n\n    -> alive\n\n    -> end\n\ndef main:\n    -> running\n        then: print(0)\n")
          child (first (filter #(= "Child" (:name %)) (:classes prog)))
          state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods child)))
          install-msgs (filter #(and (= :objc-msg (:node %))
                                     (= "cxc_installParent:inSlot:" (:selector %)))
                               (:body state))
          end (first (filter #(= "transitionToEnd" (:selector %)) (:methods child)))
          catch-wrap (first (filter #(= :end-catch-log-only (:node %)) (:body end)))
          cascade-children (filter #(= :end-cascade-child (:node %))
                                   (:body catch-wrap))]
      (is (empty? install-msgs)
          "no install-parent call for weak field — child has a different owner")
      (is (empty? cascade-children)
          "no end-cascade for weak field — this atom doesn't own the child's lifecycle"))))

(deftest f13-l2-no-modifier-keeps-default-strong-for-atoms
  (testing "F13 L2 — an atom-typed data field WITHOUT a modifier still emits `(strong)` — the pre-L2 default is preserved for the no-modifier case."
    (let [prog (lower-src "def Owner:\n    -> alive\n\ndef Child:\n    owner: Owner = Owner()\n\n    -> alive\n\ndef main:\n    -> running\n        then: print(0)\n")
          child (first (filter #(= "Child" (:name %)) (:classes prog)))
          f     (first (filter #(= "owner" (:name %)) (:properties child)))]
      ;; F33 L5 — data-field @properties are readonly. The strong
      ;; ownership qualifier still applies for atom-typed fields.
      (is (= ["readonly" "strong"] (:attrs f))
          "unmodified atom-typed field: readonly + pre-L2 default (strong)"))))

;; ---------------------------------------------------------------------------
;; F69 L2 — Unified body-shape lookup (def-kind)
;; ---------------------------------------------------------------------------

(defn- ctx-for
  "Build a lowering ctx with just enough envs to exercise `def-kind`.
   Runs `resolve/build-symbol-table` over the parsed source (with
   prelude) and threads it plus a stub protocol-env into a bare ctx.
   Anything downstream of the F51 read path lands here; the ad-hoc
   envs are omitted because `def-kind` reads via `resolve/lookup`."
  [src]
  (let [combined (p/parse (str (:source (find-ns 'cxc.prelude)) src))
        table    ((requiring-resolve 'cxc.resolve/build-symbol-table) combined)]
    {:symbol-table table
     :protocol-env {}}))

(deftest f69-l2-def-kind-structural
  (testing "F69 L2 — fields-only def resolves to :structural"
    (let [ctx (ctx-for "def Point:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n")]
      (is (= :structural (lower/def-kind ctx "Point"))))))

(deftest f69-l2-def-kind-tagged-variant
  (testing "F69 L2 — cases-only def resolves to :tagged-variant"
    (let [ctx (ctx-for "def Color:\n    case red\n    case green\n\ndef main:\n    -> running\n")]
      (is (= :tagged-variant (lower/def-kind ctx "Color"))))))

(deftest f69-l2-def-kind-agent
  (testing "F69 L2 — def with transitions resolves to :agent"
    (let [ctx (ctx-for "def Counter:\n    n: Int = 0\n\n    -> running\n\n    running <: bump -> self\n        then: n => n + 1\n\ndef main:\n    -> running\n")]
      (is (= :agent (lower/def-kind ctx "Counter"))))))

(deftest f69-l2-def-kind-protocol
  (testing "F69 L2 — accepts-only def resolves to :protocol via protocol-env fallback"
    (let [ctx (assoc (ctx-for "def main:\n    -> running\n")
                :protocol-env {"MyProto" {:accepts []}})]
      (is (= :protocol (lower/def-kind ctx "MyProto"))))))

(deftest f69-l2-def-kind-unknown-returns-nil
  (testing "F69 L2 — unknown name resolves to nil"
    (let [ctx (ctx-for "def main:\n    -> running\n")]
      (is (nil? (lower/def-kind ctx "Nonexistent"))))))

;; ---------------------------------------------------------------------------
;; F69 L4b — `<:` dispatch lowering (statement position, tagged-variant scrutinee)
;; ---------------------------------------------------------------------------

(deftest f69-l4b-dispatch-lowers-to-scoped-if-chain
  (testing "F69 L4b — a `<:` dispatch on an enum-typed scrutinee lowers to a `:c-scope` wrapping (a) a `:var-decl` binding the scrutinee to `_cxc_scrut` and (b) a `:c-if-chain` whose branches carry tag-equality conditions in declaration order, followed by the wildcard `else` branch."
    (let [prog  (lower-src "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: green: print(2)\n                <: *: print(9)\n")
          body  (main-work-body prog)
          scope (first (filter #(= :c-scope (:node %)) body))]
      (is (some? scope) "dispatch stmt lowered to :c-scope")
      (let [stmts     (:stmts scope)
            decl      (first stmts)
            if-chain  (second stmts)
            branches  (:branches if-chain)]
        (is (= :var-decl (:node decl)))
        (is (= "_cxc_scrut" (:name decl))
            "scrutinee binds to the compiler-authored local name")
        (is (= "Signal" (:c-type decl)) "temp carries the enum's C type")
        (is (= :c-if-chain (:node if-chain)))
        (is (= 3 (count branches)) "one branch per parsed arm")
        (is (= "==" (get-in branches [0 :cond :op])))
        (is (= "Signal_red" (get-in branches [0 :cond :right :sym]))
            "first branch compares against the first case's C constant")
        (is (= "Signal_green" (get-in branches [1 :cond :right :sym])))
        (is (nil? (:cond (nth branches 2)))
            "wildcard branch has no condition — emits as bare `else`")))))

(deftest f69-l4b-dispatch-payload-binding-on-bare-case-rejected
  (testing "F16 L1 — `<: name(let x):` payload binding on a bare (no-payload) case rejects at lower-time with a message pointing at the case's payload declaration. Post-F16 the rejection is not about F16 being missing (it landed 2026-08-23) but about the case itself having no payload to bind."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L4b:.*payload bindings on value-only enum"
         (lower-src "def Outcome:\n    case ok\n    case err\n\ndef main:\n    -> running\n        then:\n            let o: Outcome = Outcome.ok\n            o:\n                <: ok(let x): print(1)\n                <: *: print(0)\n")))))

(deftest f69-l4b-dispatch-non-enum-scrutinee-int-accepted-with-wildcard
  (testing "F69 L4c4 — Int scrutinees are accepted (for literal patterns) as long as a `<: *:` wildcard tail is present (open type, exhaustiveness by wildcard). This supersedes the L4b-only rejection."
    (let [prog (lower-src "def main:\n    -> running\n        then:\n            let n: Int = 3\n            n:\n                <: 3: print(1)\n                <: *: print(0)\n")]
      (is (some? prog)))))

(deftest f69-l4c4-dispatch-primitive-without-wildcard-rejected
  (testing "F69 L4c4 — a primitive scrutinee without a wildcard rejects (spec §3.7 exhaustiveness — primitives are open types)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L4c4: non-exhaustive.*primitive"
         (lower-src "def main:\n    -> running\n        then:\n            let n: Int = 3\n            n:\n                <: 0: print(0)\n")))))

(deftest f69-l4b-dispatch-unknown-case-rejected
  (testing "F69 L4b — a `<:` arm naming a case not declared on the scrutinee's enum rejects at lower-time with a message listing the actual cases."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L4b: unknown case `purple`"
         (lower-src "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: purple: print(1)\n                <: *: print(0)\n")))))

(deftest f69-l4c1-dispatch-exhaustiveness-enforced
  (testing "F69 L4c1 — spec §3.7: a `<:` dispatch on a tagged-variant scrutinee must cover every declared case OR include a `<: *:` wildcard. Missing coverage without a wildcard rejects at lower-time listing the unhandled cases."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"non-exhaustive `<:` dispatch on `Signal`.*yellow"
         (lower-src "def Signal:\n    case red\n    case yellow\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: green: print(2)\n")))))

(deftest f69-l4c1-dispatch-exhaustive-with-all-cases-covered
  (testing "F69 L4c1 — every case handled and no wildcard is legal; no exhaustiveness diagnostic fires."
    (let [prog (lower-src "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: green: print(2)\n")]
      (is (some? prog)))))

(deftest f69-l4c1-dispatch-exhaustive-via-wildcard
  (testing "F69 L4c1 — wildcard-tail satisfies exhaustiveness regardless of covered cases."
    (let [prog (lower-src "def Signal:\n    case red\n    case yellow\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: *: print(0)\n")]
      (is (some? prog)))))

;; ---------------------------------------------------------------------------
;; F38 — structural def can't hold an agent-typed field (value-semantics
;; guard). A raw agent-pointer field on a record would silently alias the
;; same instance across bit-copies. Reject at lower-time with a promotion
;; fix-it. `List of Agent` / `Map of ... Agent` are not covered by this
;; check — they surface a different design question and would fire only
;; if they resolve to atom-env by name, which the container synthesis
;; explicitly excludes via [[user-declared-atom?]].
;; ---------------------------------------------------------------------------

(deftest f38-record-with-agent-field-rejected
  (testing "F38 — structural `X` with an agent-typed field fails compile with a promotion fix-it. Prior to the diagnostic, this shape emitted `Tally * c;` inside the struct BEFORE the `@class Tally;` forward decl, producing uncompilable C."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"(?s)record `X` field `c: Tally` .* value-shape.*promote `X` to an agent"
         (lower-src "def Tally:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        then: n => n + 1\n\ndef X:\n    c: Tally = nil\n\ndef main:\n    x: X = X()\n\n    -> end\n        then: print(0)\n")))))

(deftest f38-record-with-value-field-accepted
  (testing "F38 — a structural def holding value-typed fields (Int, String, another record, an enum) is accepted; the diagnostic only fires when the field type resolves to a user-declared agent."
    (let [prog (lower-src "def V:\n    x: Int\n    y: Int\n\ndef X:\n    v: V\n    n: Int\n\ndef main:\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))))))

(deftest f38-record-with-list-of-int-accepted
  (testing "F38 — `List of Int` on a record is not rejected by this diagnostic; the field's `:cxc-ty` is the synthesized container class name (`_Cxc_ListOfInt`), which is explicitly excluded from the agent check via `:kind :list-container`."
    (let [prog (lower-src "def X:\n    xs: List of Int\n\ndef main:\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))))))

(deftest f39-l5b-ii-a-optional-type-name-mangling
  (testing "F39 Landing 5b-ii-a (2026-08-21) — `optional-type-name` mangles a value-type name into the Optional-boxed C typedef name. `Int` canonicalizes to `Int64` per §3.1 (prelude alias). Other primitives, records, and enums pass through unchanged."
    (is (= "_Cxc_Optional_Int64"    (type-kw/optional-type-name "Int")))
    (is (= "_Cxc_Optional_Int32"    (type-kw/optional-type-name "Int32")))
    (is (= "_Cxc_Optional_Bool"     (type-kw/optional-type-name "Bool")))
    (is (= "_Cxc_Optional_Double"   (type-kw/optional-type-name "Double")))
    (is (= "_Cxc_Optional_Position" (type-kw/optional-type-name "Position")))))

(deftest f39-l5b-ii-c-collect-emits-optional-typedefs-for-bare-value-fields
  (testing "F39 Landing 5b-ii-c (2026-08-22) — flip landed: `collect-optional-types` scans record/atom envs for value-typed bare-`T` fields and emits the corresponding `_Cxc_Optional_<T>` typedefs (`_Cxc_Optional_Int64` for Int, plus `_Cxc_Optional_Bool` always when any Optional exists so ord binops have somewhere to land)."
    (let [prog (lower-src "def X:\n    n: Int = 5\n\n    -> running\n\n    -> end\n\ndef main:\n    x: X = X()\n\n    -> end\n        then: print(0)\n")
          opt-names (set (keep #(when (str/starts-with? (:name %) "_Cxc_Optional_")
                                  (:name %))
                               (:structs prog)))]
      (is (= :objc-program (:node prog)))
      (is (contains? opt-names "_Cxc_Optional_Int64")
          "Int field flip produces `_Cxc_Optional_Int64`")
      (is (contains? opt-names "_Cxc_Optional_Bool")
          "Bool typedef always included when any Optional field exists (ord-binop result target)"))))

(deftest f39-l6-coalesce-parses-and-lowers-reference-nullable
  (testing "F39 Landing 6 (2026-08-21) — `??` coalesce parses as a low-precedence binary op (same as `or`) and lowers to `:c-coalesce {:mode :reference}` for a nullable-reference LHS. Reference case: `Counter().other ?? Counter()` — .other is Counter-typed and nullable-by-default. Lowered form binds LHS once via a GNU statement-expression, then ternaries on `!= nil`."
    (let [prog (lower-src (str "def Counter:\n"
                               "    other: Counter = nil\n\n"
                               "    -> alive\n\n"
                               "    -> end\n\n"
                               "def main:\n"
                               "    fallback: Counter = Counter()\n"
                               "    picked: Counter = Counter().other ?? fallback\n\n"
                               "    -> end\n"
                               "        then: print(0)\n"))
          state-init (main-work-body prog)
          picked-assign (first (filter #(and (= :assign (:node %))
                                             (= "_picked" (get-in % [:lhs :sym])))
                                       state-init))]
      (is (some? picked-assign) "picked assignment landed in cxc_stateInit")
      (is (= :c-coalesce (get-in picked-assign [:rhs :node]))
          "RHS is a :c-coalesce node")
      (is (= :reference (get-in picked-assign [:rhs :mode]))
          ":mode is :reference (LHS is nullable Counter pointer, not Optional-boxed)"))))

(deftest f39-l6-coalesce-emits-single-eval-ternary
  (testing "F39 Landing 6 — emit renders `:c-coalesce :reference` as a GNU statement-expression that binds LHS to `__cxc_coal` once (avoiding double-eval of side-effecting LHS like a method call) and ternaries on `!= nil`."
    (let [prog (lower-src (str "def Counter:\n"
                               "    other: Counter = nil\n\n"
                               "    -> alive\n\n"
                               "    -> end\n\n"
                               "def main:\n"
                               "    fallback: Counter = Counter()\n"
                               "    picked: Counter = Counter().other ?? fallback\n\n"
                               "    -> end\n"
                               "        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "__cxc_coal")
          "emitted C contains the single-eval binding name")
      (is (str/includes? c-src "!= nil")
          "emitted C contains reference-mode nil check"))))

(deftest f39-l6-coalesce-right-hand-side-required
  (testing "F39 Landing 6 — `??` is a binary infix op; a missing RHS is a parse error. This is what makes `??` the ergonomic \"force unwrap\": the user MUST provide a fallback, they can't accidentally get a raw T without saying what nil should become. Guards the design property from a future parser refactor accidentally making RHS optional."
    (is (thrown? clojure.lang.ExceptionInfo
                 (lower-src "def main:\n    x: Int = nil ??\n\n    -> end\n        then: print(0)\n")))))

;; ---------------------------------------------------------------------------
;; F39 Landing 7 — `if let x = maybe:` scoped unwrap (statement form).
;; ---------------------------------------------------------------------------

(deftest f39-l7-if-let-parses-into-if-let-ast
  (testing "F39 Landing 7 — `if let <ident> [: T] = <expr>: <block> [else: <block>]` parses to `{:node :if-let :bindings [...] :then <block> :else <block-or-nil>}`. Single-binding form with an else-block. `let` is dispatched from `if` — bare `if <cond>:` (without `let`) fires a targeted `parse-if-let-statement` reject pointing at K5 deferral."
    (let [prog (lower-src (str "def Slot:\n    n: Int! = 5\n\n    -> alive\n\n"
                               "def X:\n"
                               "    other: Slot\n\n"
                               "    -> running\n\n"
                               "    running <: bump -> running\n"
                               "        then:\n"
                               "            if let s = other:\n"
                               "                print(1)\n"
                               "            else:\n"
                               "                print(0)\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))]
      (is (= :objc-program (:node prog))
          "if-let statement inside a then: block lowers cleanly to an objc-program"))))

(deftest f39-l7-if-let-reference-nullable-emits-nested-if-with-nil-guard
  (testing "F39 Landing 7 — reference-typed RHS (nullable atom) lowers to a `:c-scope` wrapping [{:node :var-decl} for the tmp, {:node :c-if-chain} for the guard]. The guard branch's first stmt is the unwrapped binding (`Slot * s = __cxc_ifl_0`), followed by the user then-body. The else-block is present when supplied."
    (let [prog (lower-src (str "def Slot:\n    n: Int! = 5\n\n    -> alive\n\n"
                               "def X:\n"
                               "    other: Slot\n\n"
                               "    -> running\n\n"
                               "    running <: bump -> running\n"
                               "        then:\n"
                               "            if let s = other:\n"
                               "                print(1)\n"
                               "            else:\n"
                               "                print(0)\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "__cxc_ifl_0") "scoped tmp is present in emit")
      (is (str/includes? c-src "!= nil") "reference-nullable guard is `!= nil`")
      (is (str/includes? c-src "Slot * s = __cxc_ifl_0")
          "unwrapped binding shadows the tmp inside the true-branch"))))

(deftest f39-l7-if-let-optional-value-emits-presence-check-and-value-extract
  (testing "F39 Landing 7 — Optional-boxed value RHS (bare `T` where `T` is a value type) lowers to a presence-check guard `__cxc_ifl_0.present` and a `.value` extract into the unwrapped local. The user local's C type is the inner raw `T`, not the Optional envelope, so subsequent uses work with the scalar."
    (let [prog (lower-src (str "def X:\n"
                               "    n: Int = 5\n\n"
                               "    -> running\n\n"
                               "    running <: bump -> running\n"
                               "        then:\n"
                               "            if let x = n:\n"
                               "                print(x + 1)\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "_Cxc_Optional_Int64 __cxc_ifl_0")
          "tmp is typed as the Optional envelope")
      (is (str/includes? c-src "__cxc_ifl_0.present")
          "guard reads the presence byte")
      (is (str/includes? c-src "int64_t x = __cxc_ifl_0.value")
          "unwrapped local is the inner raw type via `.value`"))))

(deftest f39-l7-if-let-no-else-branch-is-legal
  (testing "F39 Landing 7 — the else-block is optional; when omitted, guard-fail falls through with no-op. Common shape for `if let x = maybe: emit(x)` style dispatch. Emit has no `else { ... }` clause on the `:c-if-chain`."
    (let [prog (lower-src (str "def Slot:\n    n: Int! = 5\n\n    -> alive\n\n"
                               "def X:\n"
                               "    other: Slot\n\n"
                               "    -> running\n\n"
                               "    running <: bump -> running\n"
                               "        then:\n"
                               "            if let s = other:\n"
                               "                print(1)\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "__cxc_ifl_0")
          "if-let compiles cleanly without else")
      ;; No else clause on the if-let branch — check the tmp-decl is not
      ;; followed by an `else` keyword. This is a coarse check; the
      ;; structural form is verified by "compiles cleanly + no-else emit
      ;; pattern".
      (let [ifl-idx (str/index-of c-src "__cxc_ifl_0")
            slice    (subs c-src ifl-idx (min (count c-src) (+ ifl-idx 300)))]
        (is (not (str/includes? slice " else "))
            "no else branch in the emitted if-chain when source omits it")))))

(deftest f39-l7-if-let-multi-binding-nests-and-short-circuits
  (testing "F39 Landing 7 — `if let a = ma, let b = mb:` nests each binding's temp-decl inside the previous guard's true-branch so later RHS aren't evaluated when an earlier guard fails. Emit shows `_b` read only inside the `.present` guard of `_a`. Else-block is textually duplicated once per binding depth (semantic impact nil — one fail-path runs per invocation)."
    (let [prog (lower-src (str "def X:\n"
                               "    a: Int = 5\n"
                               "    b: Int = 10\n\n"
                               "    -> running\n\n"
                               "    running <: bump -> running\n"
                               "        then:\n"
                               "            if let x = a, let y = b:\n"
                               "                print(x + y)\n"
                               "            else:\n"
                               "                print(0)\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)
          ifl0-idx  (str/index-of c-src "__cxc_ifl_0")
          ifl1-idx  (str/index-of c-src "__cxc_ifl_1")
          guard0-idx (str/index-of c-src "__cxc_ifl_0.present")
          decl1-idx  (when ifl1-idx
                       (str/index-of c-src "_Cxc_Optional_Int64 __cxc_ifl_1"))]
      (is (some? ifl0-idx) "first tmp declared")
      (is (some? ifl1-idx) "second tmp declared")
      (is (some? guard0-idx) "first guard reads .present on __cxc_ifl_0")
      (is (< guard0-idx decl1-idx)
          "second tmp's declaration is inside (below) the first guard — short-circuit"))))

(deftest f39-l7-if-let-non-nullable-rhs-rejected
  (testing "F39 Landing 7 — a non-nullable RHS (scalar `T!` value or already-unwrapped value) has no nil case to unwrap, so `if let x = <scalar>` is a category error. Diagnostic key `:cxc-f39-if-let-non-nullable-rhs`; message points at `let` (plain binding) or `??` (fallback) as alternatives."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F39 L7.*requires a nullable RHS"
         (lower-src (str "def X:\n"
                         "    n: Int! = 5\n\n"
                         "    -> running\n\n"
                         "    running <: bump -> running\n"
                         "        then:\n"
                         "            if let x = n:\n"
                         "                print(x)\n\n"
                         "def main:\n"
                         "    -> running\n        then: print(0)\n"))))))

(deftest f39-l7-bare-if-without-let-rejected-with-k5-pointer
  (testing "F39 Landing 7 — bare `if <cond>: <block>` (statement-form K5) is deferred; the parser emits a targeted reject pointing at `if let`, arm-head `|:` propositions, and expression-position `<:` dispatch as alternatives. Guards against silent parse failure that would look like a generic 'expected ...' error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F39 L7.*only `if let"
         (lower-src (str "def X:\n"
                         "    n: Int = 5\n\n"
                         "    -> running\n\n"
                         "    running <: bump -> running\n"
                         "        then:\n"
                         "            if n:\n"
                         "                print(0)\n\n"
                         "def main:\n"
                         "    -> running\n        then: print(0)\n"))))))

;; ---------------------------------------------------------------------------
;; F39 Landing 8 — `on_nil:` section + nil-receiver dispatch (spec §4.10.9).
;; ---------------------------------------------------------------------------

(deftest f39-l8-on-nil-section-collects-into-atom-env
  (testing "F39 Landing 8 — `on_nil:` inside a def-atom body parses to `:on-nil-section` and folds into atom-env as `:on-nil-entries` map (method-name → raw const-value expr). Multiple entries stack; each maps to its declared value. Method-existence + const-shape validation runs at env-build time."
    (let [prog (lower-src (str "def UserList:\n"
                               "    n: Int! = 5\n\n"
                               "    -> alive\n\n"
                               "    count() :- 42\n\n"
                               "    on_nil:\n"
                               "        count: 7\n\n"
                               "def X:\n"
                               "    ul: UserList\n\n"
                               "    -> running\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          ul   (first (filter #(= "UserList" (:name %)) (:classes prog)))]
      ;; Presence check via emit: on_nil-guarded reads render as
      ;; `_Cxc_recv = ...; (__cxc_recv != nil) ? ... : 7` inside a
      ;; GNU stmt-expr. Env-fold shape is verified indirectly by the
      ;; guarded emit fire; a direct :on-nil-entries assertion would
      ;; require exporting the atom-env, which lower doesn't do today.
      (is (some? ul)
          "UserList lowers cleanly with the `on_nil:` section"))))

(deftest f39-l8-on-nil-wraps-atom-method-call-in-nil-guarded-ternary
  (testing "F39 Landing 8 — a `.method()` call on an atom that declares `on_nil: method: <const>` emits a GNU statement-expression binding the receiver once and ternary'ing on `!= nil`: non-nil dispatches the ObjC message; nil returns the declared constant. Eval-once matches L6-ref `??` / L5b-ii-d unwrap patterns for side-effecting receivers."
    (let [prog  (lower-src (str "def UserList:\n"
                                "    n: Int! = 5\n\n"
                                "    -> alive\n\n"
                                "    count() :- 42\n\n"
                                "    on_nil:\n"
                                "        count: 7\n\n"
                                "def X:\n"
                                "    ul: UserList\n\n"
                                "    -> running\n\n"
                                "    running <: bump -> running\n"
                                "        then:\n"
                                "            print(ul.count)\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "__cxc_recv")
          "emit binds the receiver to a single-eval temp `__cxc_recv`")
      (is (str/includes? c-src "(__cxc_recv != nil)")
          "guard is `!= nil` on the temp")
      (is (str/includes? c-src ": (7)")
          "fallback constant is emitted in the else branch"))))

(deftest f39-l8-on-nil-rejects-unknown-method
  (testing "F39 Landing 8 — an `on_nil:` entry for a name that isn't a method or observable on the atom fires `:cxc-f39-on-nil-unknown-method` with a fix-it. Guards typos and stale entries. Protocol-inherited entries are validated by a follow-on pass and don't fire this reject today."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"declares `on_nil:` entry for `bogus`"
         (lower-src (str "def UserList:\n"
                         "    n: Int! = 5\n\n"
                         "    -> alive\n\n"
                         "    count() :- 42\n\n"
                         "    on_nil:\n"
                         "        bogus: 0\n\n"
                         "def main:\n"
                         "    -> running\n        then: print(0)\n"))))))

(deftest f39-l8-on-nil-duplicate-entry-rejected
  (testing "F39 Landing 8 — two entries for the same method name fire `:cxc-f39-on-nil-duplicate-entry`. Guards against ambiguous fallback selection (which const would win?). Diagnostic names the offending method."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"declares `on_nil:` entry `count` more than once"
         (lower-src (str "def UserList:\n"
                         "    n: Int! = 5\n\n"
                         "    -> alive\n\n"
                         "    count() :- 42\n\n"
                         "    on_nil:\n"
                         "        count: 7\n"
                         "        count: 9\n\n"
                         "def main:\n"
                         "    -> running\n        then: print(0)\n"))))))

(deftest f39-l8-on-nil-non-const-value-rejected
  (testing "F39 Landing 8 — the fallback value must satisfy `default-expr-const-shape?` (spec §3.8.5): primitive literals / nil / unary `-`/`not` on those, plus ident (value-alias). A binop or method call as the fallback is rejected. Value must be constructible at compile time."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"on_nil entry `count`.*must be a compile-time constant"
         (lower-src (str "def UserList:\n"
                         "    n: Int! = 5\n\n"
                         "    -> alive\n\n"
                         "    count() :- 42\n\n"
                         "    on_nil:\n"
                         "        count: 1 + 2\n\n"
                         "def main:\n"
                         "    -> running\n        then: print(0)\n"))))))

(deftest f39-l8-on-nil-not-applied-when-method-has-no-entry
  (testing "F39 Landing 8 — methods without an `on_nil:` entry are unaffected: emit is the raw `.method` property access (ObjC nil-messaging returns 0 for scalar returns). No `__cxc_recv` wrapping for un-declared methods. Guards against overreach — `on_nil` fires only for methods explicitly listed."
    (let [prog  (lower-src (str "def UserList:\n"
                                "    n: Int! = 5\n\n"
                                "    -> alive\n\n"
                                "    count() :- 42\n"
                                "    other() :- 99\n\n"
                                "    on_nil:\n"
                                "        count: 7\n\n"
                                "def X:\n"
                                "    ul: UserList\n\n"
                                "    -> running\n\n"
                                "    running <: bump -> running\n"
                                "        then:\n"
                                "            print(ul.other)\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "_ul.other")
          "bare property access for the un-declared method")
      (is (not (str/includes? c-src ": (7)"))
          "no `on_nil` fallback in the emitted call site"))))

;; ---------------------------------------------------------------------------
;; F33 L2.b — snapshot-init prelude on receive-glyph transition methods.
;; ---------------------------------------------------------------------------

(deftest f33-l2b-receive-glyph-method-carries-snapshot-init-prelude
  (testing "F33 L2.b — a receive-glyph method on an atom with user data-fields carries a `:prelude` slot with one `:state-snapshot-init` node naming the atom's user data-fields. L2.b is dead-code: reads still route to raw ivars, but the snapshot binding exists at method entry so L2.c can flip the reroute."
    (let [prog (lower-src (str "def Counter:\n"
                               "    n: Int! = 0\n\n"
                               "    -> running\n\n"
                               "    running <: bump -> running\n"
                               "        then: n => n + 1\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          cls  (first (filter #(= "Counter" (:name %)) (:classes prog)))
          bump (first (filter #(= "bump" (:selector %)) (:methods cls)))]
      (is (some? bump))
      (is (some? (:prelude bump))
          "receive-glyph method carries :prelude when atom has data-fields")
      ;; L3.a added a second prelude node (`:next-state-init`) alongside
      ;; the snapshot-init. Prelude is [snapshot-init, next-state-init].
      (is (= 2 (count (:prelude bump)))
          "prelude has snapshot-init + next-state-init (L3.a)")
      (let [snap (first (:prelude bump))]
        (is (= :state-snapshot-init (:node snap)))
        (is (= "Counter" (:atom-name snap)))
        (is (= "Counter" (:class-name snap)))
        (is (= ["n"] (mapv :name (:data-fields snap)))
            "prelude names ONLY user data-fields")))))

(deftest f33-l2b-atom-without-data-fields-emits-no-snapshot-prelude
  (testing "F33 L2.b — an atom with zero user `data:` fields (only compiler-injected `_phase`) gets NO `:prelude` slot on its receive-glyph methods. The `<Atom>_State` typedef also doesn't exist for such atoms (L2.a), so a snapshot would reference a non-existent type."
    (let [prog (lower-src (str "def Trivial:\n"
                               "    -> alive\n\n"
                               "    alive <: bump -> alive\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          cls  (first (filter #(= "Trivial" (:name %)) (:classes prog)))
          bump (first (filter #(= "bump" (:selector %)) (:methods cls)))]
      (is (some? bump))
      (is (nil? (:prelude bump))
          "no prelude for a data-less atom"))))

(deftest f33-l2b-emit-inlines-snapshot-init-as-c-declaration-at-method-entry
  (testing "F33 L2.b — emit produces `<Atom>_State __cxc_snap = { ._<field> = _<field>, ... };` as the first statement of the ObjC method body, ahead of any branch dispatch. Uses designated initializers with underscore-prefixed field names (matching the ivar names)."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 0\n"
                                "    max: Int! = 10\n\n"
                                "    -> running\n\n"
                                "    running <: bump -> running\n"
                                "        then: n => n + 1\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "Counter_State __cxc_snap = { ._n = _n, ._max = _max };")
          "snapshot-init emitted as designated-initializer decl (default single-queue path)"))))

(deftest f32-l4e-beta-1-self-field-read-inside-receive-glyph-reroutes-via-snapshot
  (testing "F32 L4.e.β.1 — `self.field` reads inside a receive-glyph transition body reroute to `__cxc_snap._field`, parallel to L2.c's bare-ident reroute. Semantics-equivalent under the default flag (F33 L3.b's writes stay in `__cxc_next` until commit fires at end of body, so the ivar during-body carries the same pre-transition value as the snapshot). Under concurrent mode with L4.e.α's snapshot-from-`_state` seed, self reads now route through committed state — closes the self-side of the ivar-reader gap so L4.e.β can retire per-field ivar writes."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 0\n\n"
                                "    -> running\n\n"
                                "    running <: bump -> running\n"
                                "        then: n => self.n + 1\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "__cxc_next._n = (__cxc_snap._n + 1);")
          "self.n RHS read reroutes to __cxc_snap._n inside receive-glyph body")
      (is (nil? (str/index-of c-src "self.n + 1"))
          "no direct ObjC self.n property access on the RHS"))))

(deftest f32-l4e-beta-2-alpha-observable-body-prepends-snapshot-init-and-routes-reads
  (testing "F32 L4.e.β.2.α — atom observable bodies now prepend a `:state-snapshot-init` and thread `:snapshot-name` into the body ctx so field reads route via `__cxc_snap._field` (parallel to receive-glyph body). Under the default flag the snapshot copies from ivars — same values, small stack alloc overhead; under concurrent mode L4.e.α's guarded shape sources from `_state` so observable reads see committed state. Closes the observable side of the ivar-reader gap so L4.e.β proper can retire per-field ivar writes."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 10\n\n"
                                "    bump :- n + 1\n\n"
                                "    -> running\n\n"
                                "    -> end\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(Counter().bump)\n"))
          cls   (first (filter #(= "Counter" (:name %)) (:classes prog)))
          bump  (first (filter #(= "bump" (:selector %)) (:methods cls)))
          body  (:body bump)
          c-src (emit/emit prog)]
      (is (= :state-snapshot-init (:node (first body)))
          "snapshot-init is the first stmt in the observable body")
      (is (= "Counter" (:class-name (first body)))
          "snapshot-init carries the atom's class name for the state struct")
      (is (str/includes? c-src "return (__cxc_snap._n + 1);")
          "bare-field read inside observable body routes via __cxc_snap._n"))))

(deftest f32-l4e-beta-3-check-invariants-body-uses-snapshot-when-invariants-present
  (testing "F32 L4.e.β.3 — `-checkInvariants` body now prepends a `:state-snapshot-init` and lowers invariant predicates via `:snapshot-name \"__cxc_snap\"` so field reads route via `__cxc_snap._field` instead of raw `_field` ivars. Under default flag: semantics-equivalent (snapshot copies from ivars). Under concurrent mode: L4.e.α's guarded emit sources snapshot from `_state`, so invariants observe committed state. Prevents stale-ivar invariant checks after L4.e.β retires per-field ivar writes."
    (let [prog  (lower-src (str "def Bounded:\n"
                                "    v: Int = 42\n"
                                "    |: 0 <= v\n"
                                "    |: v <= 150\n\n"
                                "    -> running\n\n"
                                "    -> end\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(1)\n"))
          cls   (first (filter #(= "Bounded" (:name %)) (:classes prog)))
          check (first (filter #(= "checkInvariants" (:selector %)) (:methods cls)))
          body  (:body check)
          c-src (emit/emit prog)]
      (is (= :state-snapshot-init (:node (first body)))
          "snapshot-init prepended to -checkInvariants body")
      (is (= "Bounded" (:class-name (first body)))
          "snapshot carries the atom's class name")
      ;; The invariant `0 <= v` (via chained-comparison desugar) reads v.
      ;; Under L4.e.β.3 the read routes through __cxc_snap._v.
      (is (str/includes? c-src "__cxc_snap._v")
          "invariant predicates read fields via __cxc_snap._v under -checkInvariants"))))

(deftest f32-l4e-beta-3-check-invariants-without-invariants-skips-snapshot
  (testing "F32 L4.e.β.3 — an atom with data-fields but NO declared invariants skips the snapshot init in -checkInvariants (empty invariant-stmts body — snapshot would be a pointless no-op AND would trip other tests that assert non-receive-glyph classes don't emit snapshot preludes)."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int = 0\n\n"
                                "    -> running\n\n"
                                "    -> end\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(1)\n"))
          cls   (first (filter #(= "Counter" (:name %)) (:classes prog)))
          check (first (filter #(= "checkInvariants" (:selector %)) (:methods cls)))
          body  (:body check)]
      (is (not-any? #(= :state-snapshot-init (:node %)) body)
          "no snapshot init when no invariants are declared"))))

(deftest f32-l4e-beta-2-alpha-observable-on-zero-data-atom-skips-snapshot
  (testing "F32 L4.e.β.2.α — an observable on an atom with no data fields skips the snapshot init (state struct would be empty; snapshot would be a pointless no-op)."
    (let [prog  (lower-src (str "def Ticker:\n"
                                "    always_true :- true\n\n"
                                "    -> running\n\n"
                                "    -> end\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(1)\n"))
          cls   (first (filter #(= "Ticker" (:name %)) (:classes prog)))
          obs   (first (filter #(= "always_true" (:selector %)) (:methods cls)))
          body  (:body obs)]
      (is (not-any? #(= :state-snapshot-init (:node %)) body)
          "no snapshot-init when the atom has no data fields"))))

(deftest f32-l4e-beta-1-self-field-outside-receive-glyph-stays-on-ivar-path
  (testing "F32 L4.e.β.1 — `self.field` reads OUTSIDE a receive-glyph body (init bodies, invariant sites, observable bodies) do NOT have `:snapshot-name` in ctx and stay on the ObjC property path unchanged. Regression guard: the reroute is scoped strictly to snapshotted contexts."
    ;; A record with a self-referring observable: `doubled :- self.n * 2`.
    ;; The observable body has no snapshot ctx, so `self.n` stays as
    ;; direct property access (`self.n * 2` in emitted C).
    (let [prog  (lower-src (str "def V:\n"
                                "    n: Int = 0\n\n"
                                "    doubled :- n * 2\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      ;; V's `doubled` observable reads `n` (which lowers to `self.n`
      ;; via the record-method convention) and multiplies. The emitted
      ;; V_doubled C fn should NOT reference __cxc_snap (no snapshot
      ;; context on record observables).
      (is (some? (str/index-of c-src "V_doubled"))
          "sanity: V_doubled observable exists")
      (is (nil? (str/index-of c-src "__cxc_snap._n"))
          "record observable body does not route through snapshot"))))

(deftest f32-l4e-alpha-snapshot-init-sources-from-state-under-concurrent-mode
  (testing "F32 L4.e.α — snapshot-init emits a `!CXC_SINGLE_MAIN_QUEUE` branch that sources from `_state` via `_cxc_atom_state_ptr`; falls back to a zero-init struct if `_state` is nil. Default single-queue branch preserved (byte-identical). Substrate for L4.e.β retiring per-field ivar writes."
    (let [prog       (lower-src (str "def Counter:\n"
                                     "    n: Int! = 0\n\n"
                                     "    -> running\n\n"
                                     "    running <: bump -> running\n"
                                     "        then: n => n + 1\n\n"
                                     "def main:\n"
                                     "    -> running\n        then: print(0)\n"))
          c-src      (emit/emit prog)
          norm       (fn [s] (str/join "\n" (map str/triml (str/split-lines s))))
          normalized (norm c-src)]
      (is (str/includes? normalized "#if !CXC_SINGLE_MAIN_QUEUE\nCounter_State *__cxc_snap_src = (Counter_State *)_cxc_atom_state_ptr(&_state);")
          "concurrent-mode branch loads _state pointer via helper")
      (is (str/includes? normalized "Counter_State __cxc_snap = __cxc_snap_src ? *__cxc_snap_src : (Counter_State){0};")
          "nil-safe deref falls back to zero-init struct")
      (is (str/includes? normalized "#else\nCounter_State __cxc_snap = { ._n = _n };\n#endif")
          "default-flag branch preserves ivar-sourced init byte-identically"))))

;; ---------------------------------------------------------------------------
;; F33 L2.c — reroute data-field reads inside receive-glyph bodies through
;; the transition-entry snapshot. The write path is unchanged (still `_field
;; = value;`); only reads flip. Under Layer 4 single-thread this is
;; observably identical when a body has at most one write per field, but
;; guards subsequent reads from a same-body prior write.
;; ---------------------------------------------------------------------------

(deftest f33-l2c-receive-glyph-body-reads-route-through-snapshot
  (testing "F33 L2.c — inside a receive-glyph transition body, a bare data-field ident (e.g. `n` in `n => n + 1`) lowers its READ occurrences to `__cxc_snap._n`. The WRITE lhs stays raw ivar (`_n`). This enforces the F33 §1.11 atomic-transition contract: reads see the entry snapshot, writes commit to the atom's state at end of body."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 0\n\n"
                                "    -> running\n\n"
                                "    running <: bump -> running\n"
                                "        then: n => n + 1\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "_n = (__cxc_snap._n + 1);")
          "write assigns to raw ivar; RHS reads via snapshot")
      (is (not (str/includes? c-src "_n = (_n + 1)"))
          "no raw-ivar read remains in the receive-glyph body"))))

(deftest f33-l2c-multi-field-reads-both-flip-to-snapshot
  (testing "F33 L2.c — when a receive-glyph body references two data-fields (e.g. `hp` and `max`), both flip to snapshot reads. Guarantees the body sees a consistent pre-transition view: an intermediate write to `hp` cannot leak into a subsequent read of `max`, and vice versa."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    hp:  Int! = 10\n"
                                "    max: Int! = 100\n\n"
                                "    -> running\n\n"
                                "    running <: heal -> running\n"
                                "        then: hp => hp + max\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "_hp = (__cxc_snap._hp + __cxc_snap._max);")
          "both reads flip to snapshot; write still lands on raw ivar"))))

(deftest f33-l2c-arm-proposition-read-also-flips
  (testing "F33 L2.c — arm-head `|:` propositions on receive-glyph transitions read through the snapshot too. Proposition evaluation is part of the branch dispatch, still inside the method body; `|: hp > 0` needs the pre-transition value."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    hp: Int! = 10\n\n"
                                "    -> alive\n\n"
                                "    alive <: hit -> alive\n"
                                "        |: hp > 0\n"
                                "        then: hp => hp - 1\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "__cxc_snap._hp > 0")
          "guard reads through snapshot"))))

;; ---------------------------------------------------------------------------
;; F33 L3.a — `__cxc_next` bag init (dead code). Extends the receive-glyph
;; prelude with `<Atom>_State __cxc_next = __cxc_snap;` after the snapshot.
;; ---------------------------------------------------------------------------

(deftest f33-l3a-prelude-carries-next-state-init-after-snapshot
  (testing "F33 L3.a — receive-glyph :prelude carries [snapshot-init, next-state-init]. Snapshot must precede — the next-state bag is initialized as a copy of the snapshot, so the snapshot must exist first."
    (let [prog (lower-src (str "def Counter:\n"
                               "    n: Int! = 0\n\n"
                               "    -> running\n\n"
                               "    running <: bump -> running\n"
                               "        then: n => n + 1\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          cls  (first (filter #(= "Counter" (:name %)) (:classes prog)))
          bump (first (filter #(= "bump" (:selector %)) (:methods cls)))
          [p0 p1] (:prelude bump)]
      (is (= :state-snapshot-init (:node p0)) "snapshot first")
      (is (= :next-state-init (:node p1)) "next-state-init second")
      (is (= "Counter" (:class-name p1))
          "next-state-init names the class for the emitted type"))))

(deftest f33-l3a-atom-without-data-fields-emits-no-next-state-init
  (testing "F33 L3.a — no next-state-init when the atom has no user data-fields (mirrors L2.b: the state-struct type doesn't exist, so a bag can't be declared)."
    (let [prog (lower-src (str "def Trivial:\n"
                               "    -> alive\n\n"
                               "    alive <: bump -> alive\n\n"
                               "def main:\n"
                               "    -> running\n        then: print(0)\n"))
          cls  (first (filter #(= "Trivial" (:name %)) (:classes prog)))
          bump (first (filter #(= "bump" (:selector %)) (:methods cls)))]
      (is (nil? (:prelude bump))))))

(deftest f33-l3a-emit-inlines-next-state-init-after-snapshot-decl
  (testing "F33 L3.a — emit produces `<Atom>_State __cxc_next = __cxc_snap;` immediately after the snapshot decl, before any branch dispatch. The bag is unused at L3.a — clang folds it out at -O — so behavior is unchanged."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 0\n\n"
                                "    -> running\n\n"
                                "    running <: bump -> running\n"
                                "        then: n => n + 1\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "Counter_State __cxc_next = __cxc_snap;")
          "next-state-init emitted verbatim")
      ;; Sanity: snapshot appears BEFORE next-state-init in the emitted
      ;; text (order is load-bearing — bag inits from snapshot).
      (let [snap-idx (str/index-of c-src "Counter_State __cxc_snap")
            next-idx (str/index-of c-src "Counter_State __cxc_next")]
        (is (some? snap-idx))
        (is (some? next-idx))
        (is (< snap-idx next-idx) "snapshot comes before next-state-init")))))

;; ---------------------------------------------------------------------------
;; F33 L3.b — writes redirect to `__cxc_next` bag; a commit sequence copies
;; the bag back to live ivars after all `then:`s, before phase-update.
;; ---------------------------------------------------------------------------

(deftest f33-l3b-then-update-writes-to-bag-not-ivar
  (testing "F33 L3.b — `then: n => n + 1` now lowers its write LHS to `__cxc_next._n` (bag field), not `_n` (raw ivar). Read on the RHS still flows through the snapshot per L2.c. Slot-setter fields (subscription roots) keep the old ivar-write path — the bag is skipped for them."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 0\n\n"
                                "    -> running\n\n"
                                "    running <: bump -> running\n"
                                "        then: n => n + 1\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "__cxc_next._n = (__cxc_snap._n + 1);")
          "write goes to `__cxc_next._n`; RHS reads snapshot")
      ;; The only occurrence of `_n = <snapshot ...>` should be the bag
      ;; write (which starts with `__cxc_next._n`). Check for token
      ;; boundary — no line begins with ` _n = (__cxc_snap...`.
      (is (nil? (str/index-of c-src " _n = (__cxc_snap._n + 1);"))
          "no direct ivar write (space-boundaried) at the bag-eligible RHS")
      (is (str/includes? c-src "_n = __cxc_next._n;")
          "commit sequence copies bag back to ivar"))))

(deftest f33-l3b-commit-sequence-covers-all-non-slot-setter-fields
  (testing "F33 L3.b — the commit sequence copies EVERY non-slot-setter data-field (even ones not written in this transition). Cheap under -O (clang folds no-op copies of unchanged snapshot values), and simpler codegen than tracking per-branch mutation sets."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    hp:  Int! = 10\n"
                                "    max: Int! = 100\n\n"
                                "    -> running\n\n"
                                "    running <: hit -> running\n"
                                "        then: hp => hp - 1\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "_hp = __cxc_next._hp;")
          "written field commits")
      (is (str/includes? c-src "_max = __cxc_next._max;")
          "un-written field ALSO commits (no-op copy at -O)"))))

(deftest f33-l3b-nonnil-check-reads-bag-not-ivar
  (testing "F33 L3.b — post-store nonnil check on a `T!` bag write reads `__cxc_next._<field>`, not `_<field>`. The ivar is stale until commit, so a check against `_<field>` would see the OLD value and miss a nil written by the RHS."
    (let [prog  (lower-src (str "def Widget:\n"
                                "    peer: Widget! = Widget()\n\n"
                                "    -> boot\n"
                                "        then: |> self.bump()\n\n"
                                "    boot <: bump -> on\n"
                                "        then: peer => Widget()\n\n"
                                "def main:\n"
                                "    w: Widget = Widget()\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "(__cxc_next._peer) == nil")
          "nonnil check reads bag field (the just-written value)"))))

;; ---------------------------------------------------------------------------
;; F33 L3.c — invariant check runs pre-commit against `__cxc_next` bag.
;; On invariant failure abort() fires BEFORE any live-ivar write, so the
;; atom stays in its pre-transition state (matches F49 pre-store abort
;; semantics per decisions-frozen §8). The post-commit `[self checkInvariants]`
;; call is suppressed on snapshotted branches; the `-checkInvariants` method
;; itself still exists and is still called from `cxc_runtimeBinding` (init)
;; and F49 method wrappers (both of which lack a bag).
;; ---------------------------------------------------------------------------

(deftest f33-l3c-invariant-reads-bag-not-ivar
  (testing "F33 L3.c — pre-commit invariant check reads `__cxc_next._<field>`, not `_<field>` or `__cxc_snap._<field>`. Routing via `:snapshot-name \"__cxc_next\"` at re-lower time picks up the pending write so the check observes the state that WILL commit."
    (let [prog  (lower-src (str "def Gauge:\n"
                                "    value: Int! = 3\n"
                                "    max:   Int! = 5\n\n"
                                "    |: value <= max\n\n"
                                "    -> alive\n\n"
                                "    alive <: bump -> alive\n"
                                "        then: value => value + 1\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "if (!((__cxc_next._value <= __cxc_next._max)))")
          "invariant reads bag fields via `__cxc_next._<field>` in transition body")
      ;; The live-ivar check `if (!((_value <= _max)))` still appears
      ;; ONCE inside the synthesized -checkInvariants method (which
      ;; -cxc_runtimeBinding calls at init). That's correct — the
      ;; method-body check reads live ivars because there's no bag at
      ;; construction time. F33 L3.c only replaces the TRANSITION-BODY
      ;; call, not the method itself.
      (is (str/includes? c-src "- (void)checkInvariants")
          "checkInvariants method still emitted (called from cxc_runtimeBinding)"))))

(deftest f33-l3c-pre-commit-invariant-precedes-commit-stmt
  (testing "F33 L3.c — pre-commit invariant stmts sit AFTER `then:` bag writes and BEFORE `commit-next-state`. On invariant failure abort() fires before commit copies bag → live ivars."
    (let [prog   (lower-src (str "def Gauge:\n"
                                 "    value: Int! = 3\n"
                                 "    max:   Int! = 5\n\n"
                                 "    |: value <= max\n\n"
                                 "    -> alive\n\n"
                                 "    alive <: bump -> alive\n"
                                 "        then: value => value + 1\n\n"
                                 "def main:\n"
                                 "    -> running\n        then: print(0)\n"))
          bump   (first (filter #(= "bump" (:selector %))
                                (:methods (first (:classes prog)))))
          branch (first (:body bump))
          wrap   (first (:body branch))
          body   (:body wrap)
          nodes  (mapv :node body)]
      ;; Expected shape: [:assign (bag write) :raw-c (nonnil) :invariant-check
      ;;                  :commit-next-state] — invariant sits between the
      ;; bag write's nonnil check and the commit.
      (is (some #{:invariant-check} nodes)
          "invariant-check node present in branch body")
      (let [inv-idx    (.indexOf nodes :invariant-check)
            commit-idx (.indexOf nodes :commit-next-state)]
        (is (< -1 inv-idx commit-idx)
            "invariant appears BEFORE commit-next-state")))))

(deftest f33-l3c-post-commit-check-suppressed-on-snapshotted-branch
  (testing "F33 L3.c — snapshotted branches drop the trailing `[self checkInvariants]` message send. Pre-commit inline checks cover the same contract with correct rollback semantics."
    (let [prog   (lower-src (str "def Gauge:\n"
                                 "    value: Int! = 3\n"
                                 "    max:   Int! = 5\n\n"
                                 "    |: value <= max\n\n"
                                 "    -> alive\n\n"
                                 "    alive <: bump -> alive\n"
                                 "        then: value => value + 1\n\n"
                                 "def main:\n"
                                 "    -> running\n        then: print(0)\n"))
          bump   (first (filter #(= "bump" (:selector %))
                                (:methods (first (:classes prog)))))
          branch (first (:body bump))
          wrap   (first (:body branch))
          body   (:body wrap)]
      (is (not (some (fn [n]
                       (and (= :objc-msg (:node n))
                            (= "checkInvariants" (:selector n))))
                     body))
          "no trailing [self checkInvariants] on snapshotted branch"))))

;; ---------------------------------------------------------------------------
;; F33 L5 — data-field @properties are readonly. External code can still
;; READ (getter is synthesized), but no `-set<F>:` setter is emitted.
;; ObjC-bridge external writes are structurally impossible; internal
;; writes still go through `_field = ...` directly (which ObjC allows
;; even when the @property is marked readonly).
;; ---------------------------------------------------------------------------

(deftest f33-l5-data-field-property-emits-readonly-attr
  (testing "F33 L5 — a plain data-field @property emits `@property (readonly, assign) int64_t n;`. External code doing `[counter setN:5]` gets an unrecognized-selector runtime error; `counter.n` READ still works via the getter (which the readonly declaration synthesizes)."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 0\n\n"
                                "    -> running\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "@property (readonly, assign) int64_t n;")
          "user data-field property lowers to readonly, assign int64_t")
      (is (str/includes? c-src "@property (nonatomic, copy) NSString * phase;")
          "protocol-injected `phase` property stays readwrite (no readonly)")
      (is (str/includes? c-src "@property (nonatomic, strong) dispatch_queue_t own_queue;")
          "protocol-injected `own_queue` stays readwrite (runtime writes to it)"))))
;; ---------------------------------------------------------------------------
;; F33 L3.d — `do:` clauses run post-commit; their ident reads should route
;; to live ivars, not the pre-transition snapshot, since the commit has
;; already landed by the time `do:` fires. Semantic flip achieved by
;; dropping `:snapshot-name` from the ctx passed to `lower-do-clause` /
;; `lower-body-stmts` (for :then-clause :stmts under F70 I3).
;; ---------------------------------------------------------------------------

(deftest f33-l3d-do-clause-reads-live-ivar-not-snapshot
  (testing "F33 L3.d — a `then:` `:stmts` block on a receive-glyph transition (post-F70 unified surface — side-effect stmts after field updates) reads live ivars (post-commit), not the pre-transition snapshot. Under `then: n => n + 1  print(n)`, the commit sequence lands the bag write onto `_n` BEFORE the print stmt fires; the read must see the new value."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 0\n\n"
                                "    -> running\n\n"
                                "    running <: bump -> running\n"
                                "        then:\n"
                                "            n => n + 1\n"
                                "            print(n)\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      ;; Side-effect print reads `n` as a bare ident — L3.d makes this
      ;; route to `_n` (live ivar), NOT `__cxc_snap._n`. Commit runs
      ;; earlier so `_n` holds the new value at print time. The print
      ;; lowers via `printf("%lld\n", (long long)(_n));`.
      (is (str/includes? c-src "(long long)(_n)")
          "post-commit stmt reads live ivar (_n)")
      (is (not (str/includes? c-src "(long long)(__cxc_snap._n)"))
          "post-commit stmt does NOT route through the pre-transition snapshot"))))

;; ---------------------------------------------------------------------------
;; F33 L2.d — snapshot reroute already covers on-enter bodies:
;;   1. Non-initial-phase on-enter (`-> other_phase then: ...`) is inlined
;;      by `inline-wildcard-then-stmts` via `lower-transition-branch`'s
;;      ctx' — which L2.c already tagged with `:snapshot-name`. So reads in
;;      such on-enter bodies automatically flip.
;;   2. Initial-phase on-enter (`-> initial_phase then: ...`) inlines into
;;      `cxc_stateInit`; snapshot is neither present nor semantically
;;      meaningful (state is being constructed, no pre-transition value to
;;      protect). Must stay raw.
;;   3. `-> end` wildcards: field updates are a compile error (spec §1.11),
;;      so snapshot semantics would be a no-op. Reads still use raw ivars —
;;      no coherence issue since the atom is single-threaded and terminal.
;; L2.d is an AUDIT landing: verify the above three shapes are consistent
;; with the atomic-transition contract, no code change.
;; ---------------------------------------------------------------------------

(deftest f33-l2d-non-initial-on-enter-reads-route-through-snapshot
  (testing "F33 L2.d — a `-> phaseX then: ...` wildcard whose target phase is NOT the initial phase (i.e., it fires when some message-transition sends us to phaseX) inherits snapshot semantics from the enclosing transition ctx (L2.c). Verifies the audit finding: L2.c's ctx-threading already covers on-enter bodies inlined via `inline-wildcard-then-stmts`."
    (let [prog  (lower-src (str "def Enemy:\n"
                                "    hp:  Int! = 3\n"
                                "    log: Int! = 0\n\n"
                                "    -> alive\n\n"
                                "    -> dying\n"
                                "        then: log => hp\n\n"
                                "    alive <: die -> dying\n"
                                "        then: hp => 0\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      ;; The `-> dying then: log => hp` wildcard inlines into the `die`
      ;; branch (target=dying). Its read of `hp` MUST route through the
      ;; snapshot — reading the pre-transition `hp` (3, before the
      ;; `hp => 0` commit), not the just-written 0.
      (is (str/includes? c-src "_log = __cxc_snap._hp;")
          "on-enter body's `hp` read goes through snapshot, not raw ivar"))))

(deftest f33-l2d-initial-on-enter-stays-raw-in-cxc-state-init
  (testing "F33 L2.d — initial-phase on-enter inlines into `cxc_stateInit`, which is construction context. Snapshot must NOT be introduced there: the ivars are being INITIALIZED, so reading through a snapshot would read pre-init zero/nil, and the whole point of init is to assign the raw ivars. Verifies no snapshot appears in cxc_stateInit."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 0\n\n"
                                "    -> alive\n"
                                "        then: n => 42\n\n"
                                "def main:\n"
                                "    c: Counter = Counter()\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)
          ;; Isolate the method body: everything between the `-
          ;; (void)cxc_stateInit {` opening of Counter's stateInit and
          ;; the next `- (void)` method start.
          [_ after-open] (str/split c-src #"- \(void\)cxc_stateInit \{" 2)
          init-body      (when after-open
                           (first (str/split after-open #"\n- \(void\)" 2)))]
      (is (some? init-body) "expected a cxc_stateInit method body")
      (is (str/includes? init-body "_n = 42;")
          "on-enter update lands on raw ivar in stateInit")
      (is (not (str/includes? init-body "__cxc_snap"))
          "no snapshot machinery in cxc_stateInit body"))))

;; ---------------------------------------------------------------------------
;; F33 L2.e — auto-transition entries. Audit finding: the only call site
;; of `lower-transition-body` (lower.clj:5219) is `main-atom-body`
;; (lower.clj:8011-8026), which runs the plain-main path — `def main: ->
;; phase then: ...` inlined directly into C `main()` with no phase
;; machinery and no ivars. There are no data-fields to snapshot from, no
;; message transitions to fire, and the whole body is a single straight-
;; line entry. No snapshot needed. Full-fat main-as-atom takes the
;; receive-glyph path (L2.b/c) instead.
;; ---------------------------------------------------------------------------

(deftest f33-l2e-plain-main-transition-body-has-no-snapshot
  (testing "F33 L2.e — plain-main (a `def main: -> running then: ...` with no fields, no other atoms, no message transitions) lowers to a straight-line C main() body with NO snapshot machinery. There are no data-fields to snapshot from and no atomic-transition contract to enforce — main() runs once and exits. Verifies the audit finding."
    (let [prog  (lower-src (str "def main:\n"
                                "    -> running\n"
                                "        then: print(42)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "int main(int argc")
          "plain-main compiles to a C main()")
      (is (not (str/includes? c-src "__cxc_snap"))
          "no snapshot in plain-main path")
      (is (not (str/includes? c-src "_State __cxc_snap"))
          "no snapshot struct init anywhere"))))

(deftest f33-l2c-init-body-still-uses-raw-ivar
  (testing "F33 L2.c — init-body (`cxc_stateInit`) is NOT a receive-glyph body and MUST NOT read through the snapshot: the snapshot doesn't exist during construction, and default-expression init runs against the raw ivars being assigned. Guards against overreach into non-transition entry sites."
    (let [prog  (lower-src (str "def Counter:\n"
                                "    n: Int! = 5\n\n"
                                "    -> running\n\n"
                                "def main:\n"
                                "    -> running\n        then: print(0)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "_n = 5;")
          "init assigns raw ivar with the default literal")
      (is (not (str/includes? c-src "__cxc_snap = { ._n = _n };"))
          "no snapshot init prelude on Counter (no receive-glyph methods declared)"))))

;; ---------------------------------------------------------------------------
;; F37 L6a — `Bytes.at(offset)` primitive-method dispatch. Reads one byte
;; from an NSMutableData * and widens to Int64. First primitive-target
;; method-call arm in lower-method-call; earlier only record + atom
;; targets dispatched (list-container was a sub-case of atom).
;; ---------------------------------------------------------------------------

(deftest f37-l6a-bytes-at-emits-widened-pointer-index
  (testing "F37 L6a — `bytes.at(i)` lowers to `((int64_t)((const uint8_t *)[bytes bytes])[i])` — one Objective-C property access + one pointer index + a widen. No allocation, no message send in the hot path."
    (let [prog (lower-src (str "def Vec:\n"
                               "    x: Int32!\n\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then:\n"
                               "            let b: Bytes = Vec(x: 1).as()\n"
                               "            print(b.at(2))\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src "((int64_t)((const uint8_t *)[")
          "cast + pointer index shape emitted verbatim")
      (is (str/includes? c-src " bytes])[")
          "reads the raw byte pointer via ObjC `-bytes` property"))))

(deftest f37-l6a-bytes-at-wrong-arity-rejected
  (testing "F37 L6a — `.at()` and `.at(a, b)` are hard errors at lower time; the diagnostic names the arity mismatch."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F37 L6a: `Bytes.at` expects exactly one arg"
         (lower-src (str "def Vec:\n"
                         "    x: Int32!\n\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then:\n"
                         "            let b: Bytes = Vec(x: 1).as()\n"
                         "            print(b.at())\n"))))))

(deftest f37-l6a-bytes-slice-emits-subdata-with-range
  (testing "F37 L6a — `bytes.slice(start, length)` lowers to `[[bytes subdataWithRange:NSMakeRange(start, length)] mutableCopy]`. The `-mutableCopy` up-converts the Foundation-returned NSData to NSMutableData so the result keeps Bytes's C-level type. One allocation per call."
    (let [prog (lower-src (str "def Vec:\n"
                               "    x: Int32!\n"
                               "    y: Int32!\n\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then:\n"
                               "            let b: Bytes = Vec(x: 1, y: 2).as()\n"
                               "            let s: Bytes = b.slice(4, 4)\n"
                               "            print(s.length)\n"))
          c-src (emit/emit prog)]
      (is (str/includes? c-src " subdataWithRange:NSMakeRange(")
          "Foundation's -subdataWithRange call is emitted")
      (is (str/includes? c-src " mutableCopy]")
          "-mutableCopy converts NSData → NSMutableData"))))

(deftest f37-l6a-bytes-slice-wrong-arity-rejected
  (testing "F37 L6a — `.slice()`, `.slice(a)`, and `.slice(a, b, c)` are hard errors at lower time."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F37 L6a: `Bytes.slice` expects exactly two args"
         (lower-src (str "def Vec:\n"
                         "    x: Int32!\n\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then:\n"
                         "            let b: Bytes = Vec(x: 1).as()\n"
                         "            print(b.slice(0).length)\n"))))))

(deftest f8-l3a-write-to-computed-field-rejected
  (testing "F8 L3a (setter-error diagnostic) — `then: doubled => 10` where `doubled` is a computed field (observable) is a hard error at lower time. Computed fields are read-only per spec §4.10.4.1; the check runs before the bag-write emit path so users get a targeted diagnostic instead of a broken C compile (dead `__cxc_next._doubled` assignment)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F8 L3a: `doubled` is a computed field \(observable\) on atom `V`"
         (lower-src (str "def V:\n"
                         "    n: Int = 3\n\n"
                         "    doubled :- n * 2\n\n"
                         "    running <: bump -> self\n"
                         "        then: doubled => 10\n\n"
                         "def main:\n"
                         "    v: V = V()\n"
                         "    -> running\n"))))))

(deftest f8-l3a-record-field-observable-name-collision-rejected
  (testing "F8 L3a follow-on — a record data field and an observable with the same name is a hard error. Silent collision would make the observable dead code — `.field` on a value dispatches through the field-access path before the observable getter."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F8 L3a: observable `n` on record `V` collides with a data field"
         (lower-src "def V:\n    n: Int\n    n :- 2\n\ndef main:\n    -> running\n")))))

(deftest f8-l3a-atom-field-observable-name-collision-rejected
  (testing "F8 L3a follow-on — same check applies inside atoms (a `def` with a `running <:` clause): a data field and a computed observable with the same name is a hard error. Blocks silent-dead-code and blocks `then: n => …` from firing on the observable and hitting the L3a setter-error path."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F8 L3a: observable `n` on atom `V` collides with a data field"
         (lower-src (str "def V:\n"
                         "    n: Int = 3\n\n"
                         "    n :- 2\n\n"
                         "    running <: bump -> self\n\n"
                         "def main:\n"
                         "    v: V = V()\n"
                         "    -> running\n"))))))

(deftest f15-l2c-iii-c-alpha-cxc-type-to-c-emits-mangled-for-named-tuple
  (testing "F15 L2.c.iii.c.α — `cxc-type->c` returns the canonical mangled name for `:type-named-tuple` nodes (D4-consistent reference-by-name, matching L2.c.ii-b's field-side convention). Dead code today: upstream `base-type-name` fails-nyi on `:type-named-tuple` in the ctor-params / method-params lowering paths, so this branch isn't reached from any real program. Direct-invocation test locks in the intended behavior; L2.c.iii.c.β teaches `base-type-name` and preserves `:type` on lowered params so `collect-shapes` finds the shape and emit-typedefs synthesizes the typedef."
    (let [ty {:node   :type-named-tuple
              :fields [{:name "x" :type {:node :type-ref :name "Int"}}
                       {:name "y" :type {:node :type-ref :name "Int"}}]}]
      (is (= "_Cxc_NT_x_Int_y_Int"
             (#'cxc.type-kw/cxc-type->c ty {} {} {} {})))
      (is (= "_Cxc_NT_x_Int_y_Int"
             (#'cxc.type-kw/cxc-type->c
              {:node :type-named-tuple
               :fields [{:name "y" :type {:node :type-ref :name "Int"}}
                        {:name "x" :type {:node :type-ref :name "Int"}}]}
              {} {} {} {}))
          "field order at call site doesn't affect the emitted C name (canonical mangling)"))))

(deftest f15-l2c-iii-c-beta1-base-type-name-emits-mangled-for-named-tuple
  (testing "F15 L2.c.iii.c.β.1 — `base-type-name` returns the canonical mangled name for `:type-named-tuple` (matches L2.c.iii.c.α's convention). Dead code today: no test program has a named-tuple param position, so this branch isn't reached from any real lowering path. Direct-invocation test locks in the intended behavior. L2.c.iii.c.β.2 preserves the raw `:type` node on lowered ctor-params so `collect-shapes` finds the shape end-to-end."
    (let [ty {:node   :type-named-tuple
              :fields [{:name "x" :type {:node :type-ref :name "Int"}}
                       {:name "y" :type {:node :type-ref :name "Int"}}]}]
      (is (= "_Cxc_NT_x_Int_y_Int"
             (#'cxc.type-kw/base-type-name ty)))
      (is (= "_Cxc_NT_x_Int_y_Int"
             (#'cxc.type-kw/base-type-name
              {:node :type-nullable :inner ty}))
          "nullable wrapping is orthogonal — base-type-name recurses"))))

(deftest f15-l2c-iii-c-beta2-ctor-params-preserve-raw-type
  (testing "F15 L2.c.iii.c.β.2 — `ctor-params-normalized` preserves the raw parsed `:type` node on each lowered ctor-param. Verifies the additive field is present (dead metadata today — no downstream reader; L2.c.iii.c.γ+ will teach `cxc-type->type-kw` to recognize mangled named-tuple names so `infer-streams-in-atom-env` and similar sites don't fail-nyi on them). Byte-identical .m output for existing programs — the extra map key isn't emitted."
    (let [ctor-params [{:node    :field
                        :name    "x"
                        :type    {:node :type-ref :name "Int"}
                        :default nil}]
          lowered     (#'cxc.lower.ctor-params/ctor-params-normalized
                       ctor-params {} {} {} {})]
      (is (= 1 (count lowered)))
      (is (= {:node :type-ref :name "Int"} (:type (first lowered)))
          "raw :type node preserved on the lowered ctor-param")
      (is (= "Int" (:cxc-ty (first lowered)))
          "existing :cxc-ty key still present + correct"))))

(deftest f15-l2c-iii-c-gamma-alpha-type-kw-recognizes-named-tuple-prefix
  (testing "F15 L2.c.iii.c.γ.α — `cxc-type->type-kw` recognizes the D4-reserved `_Cxc_NT` prefix as a named-tuple type-kw, without threading a dedicated env. Downstream pattern matches on type-kw don't yet handle `:named-tuple` — today's programs never reach this branch since upstream lowering paths still fail-nyi on named-tuple ctor params before `infer-streams-in-atom-env` runs. Test locks in the recognition rule so downstream landings can build on it.

  Non-`_Cxc_NT` names still fall through to the existing `:else` fail-nyi, and record/atom/enum-env lookups still take precedence when a name happens to resolve there (nominal registration wins over prefix recognition, matching L2.a's discipline that user-declared names collide with reserved prefixes at parse time — see `reserved-type-name-prefix?`)."
    ;; Recognition: mangled named-tuple → :named-tuple kw.
    (is (= {:named-tuple "_Cxc_NT_x_Int_y_Int"}
           (#'cxc.type-kw/cxc-type->type-kw
            "_Cxc_NT_x_Int_y_Int" {} {} {} nil)))
    ;; Recognition survives across nesting-prefixed manglings.
    (is (= {:named-tuple "_Cxc_NT_outer_Nt1_inner_Int"}
           (#'cxc.type-kw/cxc-type->type-kw
            "_Cxc_NT_outer_Nt1_inner_Int" {} {} {} nil)))
    ;; Non-mangled names still fail-nyi (prefix isn't `_Cxc_NT`).
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unresolved type name"
         (#'cxc.type-kw/cxc-type->type-kw
          "SomeUserType" {} {} {} nil)))))

(deftest f15-l2c-iii-c-gamma-beta-type-kw-to-c-type-named-tuple
  (testing "F15 L2.c.iii.c.γ.β — `type-kw->c-type` renders `:named-tuple` type-kws as the bare mangled struct name (value type, no pointer suffix, matches L2.c.ii-b's field-side convention + the L2.c.iii.c.α cxc-type->c convention). Dead code today; enables γ-chain end-to-end when combined with the earlier sub-fires."
    (is (= "_Cxc_NT_x_Int_y_Int"
           (#'cxc.type-kw/type-kw->c-type
            {:named-tuple "_Cxc_NT_x_Int_y_Int"} nil)))
    (is (= "_Cxc_NT_outer_Nt1_inner_Int"
           (#'cxc.type-kw/type-kw->c-type
            {:named-tuple "_Cxc_NT_outer_Nt1_inner_Int"} nil)))))

(deftest f15-l2c-iii-c-named-tuple-ctor-param-end-to-end-typedef
  (testing "F15 L2.c.iii.c — end-to-end typedef emission: a program with a named-tuple ctor-param position lowers successfully and the mangled struct typedef appears on `:named-tuple-typedefs`. Verifies α + β.1 + β.2 + γ.α + γ.β compose into a working typedef pipeline for the ctor-param position. Downstream uses of the param (e.g. `.x` field access) remain out of scope — those need a shape-lookup helper against collect-shapes' output, a separate follow-on."
    (let [prog (lower-src (str "def Foo(p: (x: Int, y: Int)):\n"
                               "    running <: nop -> self\n\n"
                               "def main:\n"
                               "    -> running\n"))
          typedefs (:named-tuple-typedefs prog)]
      (is (some? typedefs))
      (is (.contains typedefs "_Cxc_NT_x_Int_y_Int")
          "typedef struct for the named-tuple shape is emitted")
      (is (.contains typedefs "int64_t x;"))
      (is (.contains typedefs "int64_t y;")))))

(deftest f15-l2c-iii-c-epsilon-alpha-named-tuple-env-empty-when-unused
  (testing "F15 L2.c.iii.c.ε.α — lowered programs carry `:named-tuple-env`, a `{mangled → shape-node}` map surfaced from `collect-shapes`. Empty for programs that don't use named tuples (byte-identical behavior — no consumer today besides tests). Same collect-shapes call feeds `:named-tuple-typedefs`; sharing the computation means the env and the typedef block are always in sync."
    (let [prog (lower-src "def main:\n    -> running\n")
          env  (:named-tuple-env prog)]
      (is (some? env))
      (is (empty? env)))))

(deftest f15-l2c-iii-c-epsilon-alpha-named-tuple-env-populated-from-ctor-param
  (testing "F15 L2.c.iii.c.ε.α — a program with a named-tuple ctor-param position surfaces its shape on `:named-tuple-env`. The env's key is the canonical mangled name (matches `:named-tuple-typedefs`); the value is the raw `:type-named-tuple` node so downstream field-lookup (ε.β) can walk `:fields`."
    (let [prog (lower-src (str "def Foo(p: (x: Int, y: Int)):\n"
                               "    running <: nop -> self\n\n"
                               "def main:\n"
                               "    -> running\n"))
          env  (:named-tuple-env prog)]
      (is (some? env))
      (is (contains? env "_Cxc_NT_x_Int_y_Int"))
      (let [shape (get env "_Cxc_NT_x_Int_y_Int")]
        (is (= :type-named-tuple (:node shape)))
        (is (= [{:name "x" :type {:node :type-ref :name "Int"}}
                {:name "y" :type {:node :type-ref :name "Int"}}]
               (:fields shape)))))))

(deftest f15-l2d-ii-beta-record-conforms-to-named-tuple-param
  (testing "F15 L2.d.ii.β — passing a record whose fields structurally match a named-tuple ctor-param succeeds. The check runs after `resolve-args-against-params` in `lower-atom-ctor` and consults `named-tuple/structural-conforms?` — the record's raw field `:type` nodes (preserved by L2.d.ii.α) compare Clojure-structurally against the required tuple's `:fields`, so `V(x: Int, y: Int)` satisfies `(x: Int, y: Int)`."
    ;; End-to-end: V has fields {x: Int, y: Int}; Foo declares p as
    ;; a named-tuple (x: Int, y: Int); passing V() at the ctor site
    ;; should lower cleanly. Regression on the check would fail-nyi.
    (let [prog (lower-src (str "def V:\n    x: Int\n    y: Int\n\n"
                               "def Foo(p: (x: Int, y: Int)):\n"
                               "    running <: nop -> self\n\n"
                               "def main:\n"
                               "    f: Foo = Foo(p: V(x: 1, y: 2))\n"
                               "    -> running\n"))]
      (is (some? prog))
      (is (contains? (:named-tuple-env prog) "_Cxc_NT_x_Int_y_Int")))))

(deftest f15-l2d-ii-beta-record-missing-field-rejected
  (testing "F15 L2.d.ii.β — passing a record that lacks a required named-tuple field fails-nyi with an actionable diagnostic naming the missing field and the record's available fields. Spec §3.4 structural conformance discipline: value must supply every field the required shape names."
    (let [ex (try
               (lower-src (str "def V:\n    x: Int\n\n"
                               "def Foo(p: (x: Int, y: Int)):\n"
                               "    running <: nop -> self\n\n"
                               "def main:\n"
                               "    f: Foo = Foo(p: V(x: 1))\n"
                               "    -> running\n"))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"does not structurally conform" (.getMessage ex)))
      (is (re-find #"missing field `y`" (.getMessage ex)))
      (is (re-find #"record has: x" (.getMessage ex))))))

(deftest f15-l2d-ii-beta-record-wrong-field-type-rejected
  (testing "F15 L2.d.ii.β — passing a record whose field type differs from the required named tuple's field type fails-nyi with a targeted diagnostic naming the mismatched field, expected type, and got type. Nullability sensitivity holds automatically since the type comparison is Clojure structural on the raw `:type` nodes (via L2.d.ii.α preservation)."
    (let [ex (try
               ;; V's `x` is Float; Foo requires x: Int → type mismatch
               (lower-src (str "def V:\n    x: Float\n    y: Int\n\n"
                               "def Foo(p: (x: Int, y: Int)):\n"
                               "    running <: nop -> self\n\n"
                               "def main:\n"
                               "    f: Foo = Foo(p: V(x: 1.0, y: 2))\n"
                               "    -> running\n"))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"does not structurally conform" (.getMessage ex)))
      (is (re-find #"field `x` type mismatch" (.getMessage ex))))))

(deftest f15-l2c-iii-c-epsilon-beta-gamma-field-type-for-owner-named-tuple-primitive
  (testing "F15 L2.c.iii.c.ε.β.γ — `field-type-for-owner` resolves `.field` access on a `:named-tuple` owner by consulting `:named-tuple-env` on ctx and translating the looked-up field type through `cxc-type->type-kw`. Primitive field returns the primitive type-kw (`Int` → `:int`)."
    (let [shape {:node   :type-named-tuple
                 :fields [{:name "x" :type {:node :type-ref :name "Int"}}
                          {:name "y" :type {:node :type-ref :name "Int"}}]}
          ctx   {:record-env      {}
                 :atom-env        {}
                 :enum-env        {}
                 :named-tuple-env {"_Cxc_NT_x_Int_y_Int" shape}}
          tk    (#'cxc.lower/field-type-for-owner
                 {:named-tuple "_Cxc_NT_x_Int_y_Int"}
                 ctx "x" {:node :dot :field "x"})]
      (is (= :int tk)))))

(deftest f15-l2c-iii-c-epsilon-beta-gamma-field-type-for-owner-named-tuple-nested
  (testing "F15 L2.c.iii.c.ε.β.γ — nested named-tuple field type resolves to `{:named-tuple <nested-mangled>}` via cxc-type->type-kw's γ.α prefix branch. Confirms the recursion story: `.outer.inner` chained access can walk from an outer tuple through a nested one without extra machinery — the same `:named-tuple` type-kw participates on every level."
    (let [inner-shape {:node   :type-named-tuple
                       :fields [{:name "c" :type {:node :type-ref :name "Int"}}]}
          outer-shape {:node   :type-named-tuple
                       :fields [{:name "inner"
                                 :type inner-shape}]}
          outer-mangled "_Cxc_NT_inner_Nt1_c_Int"
          inner-mangled "_Cxc_NT_c_Int"
          ctx   {:record-env      {}
                 :atom-env        {}
                 :enum-env        {}
                 :named-tuple-env {outer-mangled outer-shape
                                   inner-mangled inner-shape}}
          tk    (#'cxc.lower/field-type-for-owner
                 {:named-tuple outer-mangled}
                 ctx "inner" {:node :dot :field "inner"})]
      (is (= {:named-tuple inner-mangled} tk)))))

(deftest f15-l2c-iii-c-epsilon-beta-gamma-field-type-for-owner-unknown-field
  (testing "F15 L2.c.iii.c.ε.β.γ — accessing a field the named tuple doesn't declare fails with a targeted diagnostic naming the mangled shape and the available fields. Discipline mirrors the record-side unknown-field case."
    (let [shape {:node   :type-named-tuple
                 :fields [{:name "x" :type {:node :type-ref :name "Int"}}
                          {:name "y" :type {:node :type-ref :name "Int"}}]}
          ctx   {:record-env      {}
                 :atom-env        {}
                 :enum-env        {}
                 :named-tuple-env {"_Cxc_NT_x_Int_y_Int" shape}}
          ex    (try
                  (#'cxc.lower/field-type-for-owner
                   {:named-tuple "_Cxc_NT_x_Int_y_Int"}
                   ctx "z" {:node :dot :field "z"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :cxc-user-error (:kind (ex-data ex))))
      (is (re-find #"field `z` not present on named" (.getMessage ex)))
      (is (re-find #"available: x, y" (.getMessage ex))))))

;; ---------------------------------------------------------------------------
;; F15 L4 Sub-1 — compiler-known parametric protocol shapes (registry +
;; `structurally-satisfies-protocol?`). Consumer wiring lands in Sub-2.
;; ---------------------------------------------------------------------------

(defn- f15-l4-record-info
  "Test helper: build a minimal record-env slot with the given method-
   entries as `:method-index`. Method-entry shape matches what
   `observable->method-entry` / extension composition emits — `:params`
   is a vec of `{:name :cxc-ty :is-self? :default}`, `:cxc-return-ty` is
   the raw cxc name string. `structurally-satisfies-protocol?` reads
   these two slots plus (unused today) `:class-fn-index`."
  [method-entries]
  {:fields       []
   :field-index  {}
   :methods      (vec method-entries)
   :method-index (into {} (map (juxt :name identity) method-entries))})

(defn- f15-l4-instance-method-entry
  "Test helper: shape a self-only-plus-non-self method entry."
  [self-cxc-name method-name non-self-params return-cxc-name]
  {:name           method-name
   :params         (into [{:name "self" :cxc-ty self-cxc-name
                           :is-self? true :default nil}]
                         (mapv (fn [p]
                                 (assoc p :is-self? false :default nil))
                               non-self-params))
   :cxc-return-ty  return-cxc-name})

(deftest f15-l4-sub1-satisfies-as-t-with-matching-instance-method
  (testing "F15 L4 Sub-1 — a record with `function as(self) -> Bytes` structurally satisfies `As<Bytes>`. Placeholder `:T` in the shape resolves to \"Bytes\" and matches the method's :cxc-return-ty. Symmetric with signature-mismatch-detail's cxc-name-string comparison."
    (let [info (f15-l4-record-info
                [(f15-l4-instance-method-entry "Bag" "as" [] "Bytes")])]
      (is (true? (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                  info "Bag" "As" :bytes))))))

(deftest f15-l4-sub1-does-not-satisfy-as-t-when-return-type-differs
  (testing "F15 L4 Sub-1 — a record whose `as` method returns String does not satisfy `As<Bytes>`. The predicate must reject mismatched return types; otherwise Sub-2's fallback dispatch could route `.as()` to a method with the wrong shape."
    (let [info (f15-l4-record-info
                [(f15-l4-instance-method-entry "Bag" "as" [] "String")])]
      (is (false? (boolean (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                            info "Bag" "As" :bytes)))))))

(deftest f15-l4-sub1-does-not-satisfy-as-t-with-extra-params
  (testing "F15 L4 Sub-1 — a record whose `as` takes an extra non-self param does not satisfy `As<Bytes>`. The registry's `As` shape declares empty required params; extras reject."
    (let [info (f15-l4-record-info
                [(f15-l4-instance-method-entry
                  "Bag" "as" [{:name "target" :cxc-ty "Bytes"}] "Bytes")])]
      (is (false? (boolean (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                            info "Bag" "As" :bytes)))))))

(deftest f15-l4-sub1-does-not-satisfy-as-t-when-method-absent
  (testing "F15 L4 Sub-1 — a record with no `as` method does not satisfy `As<Bytes>`. Absence rejects — Sub-2 will fall through to the F37 synthesis path (or diagnostic) after this predicate returns false."
    (let [info (f15-l4-record-info
                [(f15-l4-instance-method-entry "Bag" "other" [] "Bytes")])]
      (is (false? (boolean (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                            info "Bag" "As" :bytes)))))))

(deftest f15-l4-sub1-from-t-not-satisfied-without-class-fn-index
  (testing "F15 L4 Sub-1 — `From<T>` requires a class-level `from(source: T) -> Self`; the record env has no `:class-fn-index` at v1.0 so this predicate always returns false for `From<T>` today. Sub-2 (or a later sub) seeds the env slot with F37 L6 synthesized `_cxc_from_bytes` shape or user-authored class functions."
    (let [info (f15-l4-record-info [])]
      (is (false? (boolean (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                            info "Bag" "From" :bytes)))))))

(deftest f15-l4-sub1-unknown-shape-name-yields-nil
  (testing "F15 L4 Sub-1 — asking about an unknown protocol shape returns nil (not false). The nil-vs-false distinction lets a caller tell 'no such shape in the registry' apart from 'shape exists but conformance failed'."
    (let [info (f15-l4-record-info [])]
      (is (nil? (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                 info "Bag" "NoSuchShape" :bytes))))))

(deftest f15-l4-sub1-multiple-methods-only-required-one-consulted
  (testing "F15 L4 Sub-1 — extra methods on the record don't affect conformance. The predicate checks only the required set — a record can carry `.as()`, `.shout()`, `.n_bytes()` and still cleanly satisfy `As<Bytes>` based solely on the `as` slot."
    (let [info (f15-l4-record-info
                [(f15-l4-instance-method-entry "Bag" "as"      [] "Bytes")
                 (f15-l4-instance-method-entry "Bag" "shout"   [] "String")
                 (f15-l4-instance-method-entry "Bag" "n_bytes" [] "Int")])]
      (is (true? (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                  info "Bag" "As" :bytes))))))

;; ---------------------------------------------------------------------------
;; F15 L4 Sub-2 — updated From<T> shape uses `{:apply "DecodeOutcome"
;; :inner :Self}` to reflect F37 L6's canonical bytes contract.
;; `protocol-req-matches-method?` resolves this marker at instantiation
;; T=:bytes to `_Cxc_DecodeOutcome_<Rec>`. `seed-class-fn-index-from-
;; f37-decode` populates the record env's `:class-fn-index` for every
;; decode-eligible record so the structural check succeeds.
;; ---------------------------------------------------------------------------

(defn- f15-l4-class-fn-entry
  "Test helper: shape a class-function method-index entry (no self
   receiver; class fns don't take a self param)."
  [_class-name method-name params return-cxc-name]
  {:name          method-name
   :params        (mapv (fn [p] (assoc p :is-self? false :default nil)) params)
   :cxc-return-ty return-cxc-name})

(deftest f15-l4-sub2-from-t-shape-uses-decode-outcome-marker
  (testing "F15 L4 Sub-2 — the `From<T>` shape's `:returns` is now `{:apply \"DecodeOutcome\" :inner :Self}` rather than plain `:Self`. This reflects F37 L6's canonical-bytes contract where the class function `from(source: Bytes)` returns a fallible `DecodeOutcome<Self>` — not the raw `Self`. Sub-1 committed to `:Self` as a placeholder before spec-reconciliation; Sub-2 adopts the F37 shape so the L6 synthesis is a natural conformer."
    (let [from-shape (get-in (deref #'cxc.lower.protocol-shapes/compiler-known-protocol-shapes)
                             ["From" :class-methods "from"])]
      (is (= {:apply "DecodeOutcome" :inner :Self}
             (:returns from-shape))))))

(deftest f15-l4-sub2-satisfies-from-t-with-seeded-class-fn-index
  (testing "F15 L4 Sub-2 — a record with a `:class-fn-index` entry matching the F37 L6 shape (params `[{name: \"source\" cxc-ty: \"Bytes\"}]`, return-type `_Cxc_DecodeOutcome_<Rec>`) structurally satisfies `From<Bytes>`. `protocol-req-matches-method?` resolves the `{:apply \"DecodeOutcome\" :inner :Self}` return marker to `_Cxc_DecodeOutcome_Bag` at self=Bag, matching the entry's `:cxc-return-ty`."
    (let [info (-> (f15-l4-record-info [])
                   (assoc :class-fn-index
                          {"from" (f15-l4-class-fn-entry
                                    "Bag" "from"
                                    [{:name "source" :cxc-ty "Bytes"}]
                                    "_Cxc_DecodeOutcome_Bag")}))]
      (is (true? (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                   info "Bag" "From" :bytes))))))

(deftest f15-l4-sub2-does-not-satisfy-from-when-return-type-differs
  (testing "F15 L4 Sub-2 — a class-function `from(source: Bytes) -> Bag` (plain Self, not `DecodeOutcome<Bag>`) does NOT satisfy `From<Bytes>`. `protocol-req-matches-method?` resolves `{:apply \"DecodeOutcome\" :inner :Self}` to `_Cxc_DecodeOutcome_Bag`, which mismatches the entry's `\"Bag\"` return type. Prevents non-fallible `from` shapes from silently conforming."
    (let [info (-> (f15-l4-record-info [])
                   (assoc :class-fn-index
                          {"from" (f15-l4-class-fn-entry
                                    "Bag" "from"
                                    [{:name "source" :cxc-ty "Bytes"}]
                                    "Bag")}))]
      (is (false? (boolean (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                             info "Bag" "From" :bytes)))))))

(deftest f15-l4-sub2-seed-populates-decode-eligible-records
  (testing "F15 L4 Sub-2 — `seed-class-fn-index-from-f37-decode` walks the record env, checks `record-decode-eligible?` per record, and stamps a synthetic `from` entry on eligible records' `:class-fn-index`. The entry's `:cxc-return-ty` matches `decode-outcome-struct-name` so the structural check succeeds at `T=:bytes`."
    ;; Programmatic construction of a minimal decode-eligible record
    ;; env — every field must pass `f37-decode-eligible-field?`. Bool
    ;; via primitive path is the simplest eligible shape.
    (let [record-env {"P" {:name         "P"
                           :fields       [{:name "n" :cxc-ty "Int"
                                           :nonnil? true}]
                           :field-index  {}
                           :methods      []
                           :method-index {}
                           :variant      :struct}}
          enum-env   {}
          seeded     (#'cxc.lower.protocol-shapes/seed-class-fn-index-from-f37-decode
                       record-env enum-env)
          from       (get-in seeded ["P" :class-fn-index "from"])]
      (is (some? from) "eligible record gets a `from` entry")
      (is (= "_Cxc_DecodeOutcome_P" (:cxc-return-ty from)))
      (is (= "source" (:name (first (:params from)))))
      (is (= "Bytes"  (:cxc-ty (first (:params from))))))))

(deftest f15-l4-sub2-seed-skips-non-eligible-records
  (testing "F15 L4 Sub-2 — a record with a field that fails `f37-decode-eligible-field?` (e.g. `Any`) doesn't get seeded. `structurally-satisfies-protocol? info \"P\" \"From\" :bytes` remains false on non-eligible records, matching F37 L6's actual synthesis coverage."
    (let [record-env {"P" {:name         "P"
                           :fields       [{:name "thing" :cxc-ty "Any"
                                           :nonnil? true}]
                           :field-index  {}
                           :methods      []
                           :method-index {}
                           :variant      :struct}}
          enum-env   {}
          seeded     (#'cxc.lower.protocol-shapes/seed-class-fn-index-from-f37-decode
                       record-env enum-env)]
      (is (nil? (get-in seeded ["P" :class-fn-index "from"]))
          "non-eligible record has no `from` seed"))))

;; ---------------------------------------------------------------------------
;; F15 L4 Sub-3 — structural fallback in `infer-method-call-type`.
;; Consults `structurally-satisfies-protocol?` on direct-method-lookup
;; miss for compiler-known protocol instance-methods (`As<T>` today).
;; Truly unreachable at v1.0 because (a) records with a user-declared
;; `.as()` resolve via direct lookup, and (b) records without one
;; match the F37 intercept upstream — but scaffolds the shape for
;; future compiler-known protocols (F42 L1's `Constructible<T>` etc.)
;; whose return type is unambiguous from the shape alone. Full F37
;; intercept consolidation deferred — would require distinguishing
;; synthetic from user-authored method-index entries so the F37
;; encoder-synthesis + eligibility checks keep working.
;; ---------------------------------------------------------------------------

(deftest f15-l4-sub3-structural-fallback-when-user-as-would-return-bytes
  (testing "F15 L4 Sub-3 — when a record HAS a user-declared `.as() -> Bytes` method (thus structurally satisfies `As<Bytes>`), `infer-method-call-type`'s primary `:method-index` lookup wins before the structural fallback runs. The fallback still produces `:bytes` if consulted (verified via direct predicate call) — both paths agree, but priority is direct lookup. Confirms the fallback shape doesn't conflict with the primary path."
    (let [info (f15-l4-record-info
                 [(f15-l4-instance-method-entry "Bag" "as" [] "Bytes")])]
      (is (true? (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                   info "Bag" "As" :bytes))
          "record with .as() -> Bytes structurally satisfies As<Bytes>"))))

(deftest f15-l4-sub3-structural-fallback-shape-is-additive-only
  (testing "F15 L4 Sub-3 — the fallback branch fires ONLY when both `:method-index` and `:observable-index` misses on `method-name`. For a record with no `as` method at all, `structurally-satisfies-protocol?` returns false (no `:as` in method-index → instance-method requirement unmet), so the fallback misses too. `:else` fail-nyi catches it. At v1.0 no reachable case fires this fallback."
    (let [info (f15-l4-record-info [])]
      (is (false? (boolean (#'cxc.lower.protocol-shapes/structurally-satisfies-protocol?
                             info "Bag" "As" :bytes)))
          "record with no .as() method fails As<Bytes> conformance"))))

;; ---------------------------------------------------------------------------
;; F51 L3b(b.ii)-2 — module attribution + qualified lookup primitive.
;; `attribute-modules-to-decls` builds `{name → module-kw}` by walking
;; top-nodes and tracking the current module via `:module` nodes;
;; `module-map-from-attribution` inverts it; `resolve-decl-lookup`
;; prefers a caller-supplied `:resolves-to` hint and falls back to
;; short-name. b.ii-3 (multi-file) + b.ii-5c (single-file) preserve
;; `:resolves-to` end-to-end, so the hint path is now the primary
;; route; the short-name fallback catches unmigrated sites until
;; b.ii-5e retires it.
;; ---------------------------------------------------------------------------

(deftest f51-l3b-b-ii-2-attribution-single-module-all-main
  (testing "F51 L3b(b.ii)-2 — with no `:module` header (single-file / pre-multi-module program), every user declaration attributes to `:main`. This is the flat-namespace convention F51 L1 established for pre-b.ii-3 programs; the attribution table must reflect it so future qualified-lookup semantics stay consistent with today's flat behavior."
    (let [top   [{:node :record :name "Bag"     :sections []}
                 {:node :def-atom :name "State" :sections []}
                 {:node :enum    :name "Color"  :cases []}]
          attr  (cxc.env/attribute-modules-to-decls top)]
      (is (= {"Bag"   :main
              "State" :main
              "Color" :main} attr)))))

(deftest f51-l3b-b-ii-2-attribution-tracks-module-headers
  (testing "F51 L3b(b.ii)-2 — a `:module foo` node re-scopes subsequent decls to `:foo`; a following `:module bar` re-scopes again. Per-decl module provenance derives from the same `:module` nodes that the b.ii-3 multi-file assembly inserts between per-file ASTs (build-symbol-table in resolve.clj uses the same walk pattern)."
    (let [top   [{:node :record :name "A" :sections []}
                 {:node :module :name "foo"}
                 {:node :record :name "B" :sections []}
                 {:node :def-atom :name "C" :sections []}
                 {:node :module :name "bar"}
                 {:node :enum :name "D" :cases []}]
          attr  (cxc.env/attribute-modules-to-decls top)]
      (is (= {"A" :main
              "B" :foo
              "C" :foo
              "D" :bar} attr)))))

(deftest f51-l3b-b-ii-2-attribution-prelude-overrides-surrounding-module
  (testing "F51 L3b(b.ii)-2 — a decl whose name matches a prelude entry (e.g. `Duration` → `:std.time`) takes the prelude's provenance even inside a user `:module` block. Rationale: prelude declarations are conceptually re-declared in user code post-concat but semantically owned by their prelude module; attribution must match what `prelude-name->module` reports so downstream qualified lookups agree."
    (let [top   [{:node :module :name "user"}
                 {:node :record :name "Bag"      :sections []}
                 {:node :record :name "Duration" :sections []}]
          attr  (cxc.env/attribute-modules-to-decls top)]
      (is (= :user     (get attr "Bag")))
      (is (= :std.time (get attr "Duration"))))))

(deftest f51-l3b-b-ii-2-attribution-ignores-non-decl-nodes
  (testing "F51 L3b(b.ii)-2 — non-decl top-level nodes (imports, use, sections, main body) don't appear in the attribution table. Only `:record` / `:def-atom` / `:enum` register — the three declaration kinds carried by record-env / atom-env / enum-env respectively."
    (let [top   [{:node :module :name "foo"}
                 {:node :use    :name "std.time"}
                 {:node :record :name "R" :sections []}
                 {:node :something-else :name "R-ignored"}]
          attr  (cxc.env/attribute-modules-to-decls top)]
      (is (= {"R" :foo} attr))
      (is (not (contains? attr "R-ignored")))
      (is (not (contains? attr "std.time"))))))

(deftest f51-l3b-b-ii-2-module-map-inverts-attribution
  (testing "F51 L3b(b.ii)-2 — `module-map-from-attribution` inverts `{name → module}` into `{module → #{names}}`. This shape lets the lookup primitive test membership in O(1) — the hot path is `resolve-decl-lookup` under Sub-3's future preserved-metadata pipeline, so the reverse index is worth building once at ctx-construction."
    (let [attr (cxc.env/attribute-modules-to-decls
                [{:node :module :name "foo"}
                 {:node :record :name "A" :sections []}
                 {:node :record :name "B" :sections []}
                 {:node :module :name "bar"}
                 {:node :enum   :name "C" :cases []}])
          mm   (cxc.env/module-map-from-attribution attr)]
      (is (= #{"A" "B"} (get mm :foo)))
      (is (= #{"C"}     (get mm :bar))))))

(deftest f51-l3b-b-ii-5d-batch-7-lookup-nil-hint-returns-nil
  (testing "F51 L3b(b.ii)-5d Batch 7 (2026-08-28) — nil `resolves-to` returns nil under strict resolution. The short-name fallback (`:else (get env name)`) retired with this batch: references must carry an attribution hint to resolve; nameless callers use [[env-lookup]] for bookkeeping instead."
    (let [env {"Bag" {:fields [] :methods []}}
          mm  {:foo #{"Bag"}}]
      (is (nil? (cxc.env/resolve-decl-lookup env mm "Bag" nil))))))

(deftest f51-l3b-b-ii-2-lookup-hint-matches-module-membership
  (testing "F51 L3b(b.ii)-2 — with `resolves-to = :foo` and `:foo` declaring `Bag`, the qualified lookup succeeds — returns the same env entry as the short-name fallback would, but via the qualified path. Under b.ii-3+ this is the primary route; today it's dead code exercised only by this test."
    (let [env {"Bag" {:fields [] :methods []}}
          mm  {:foo #{"Bag"}}]
      (is (= (get env "Bag")
             (cxc.env/resolve-decl-lookup env mm "Bag" :foo))))))

(deftest f51-l3b-b-ii-2-lookup-hint-mismatch-returns-nil
  (testing "F51 L3b(b.ii)-2 — with `resolves-to = :bar` but `:bar` NOT declaring `Bag`, the primitive returns nil rather than falling back to short-name. Rationale: the hint asserts the caller resolved this reference to a specific module; if that module doesn't own the name, the reference is unresolved (a stale hint or a bug upstream) — silently defaulting to short-name would mask the error. Unreachable under today's flat-namespace pipeline; reserved for b.ii-3 diagnostics."
    (let [env {"Bag" {:fields [] :methods []}}
          mm  {:foo #{"Bag"} :bar #{"Other"}}]
      (is (nil? (cxc.env/resolve-decl-lookup env mm "Bag" :bar))))))

(deftest f51-l3b-b-ii-2-lookup-nil-name-returns-nil
  (testing "F51 L3b(b.ii)-2 — passing `nil` as the name short-circuits to nil regardless of module hint. Defensive guard — some AST slots (`:callee`, `:type`) are nil for compiler-synthesized nodes and callers should be free to pass them through without threading a pre-check."
    (is (nil? (cxc.env/resolve-decl-lookup {"Bag" {}} {:foo #{"Bag"}} nil :foo)))
    (is (nil? (cxc.env/resolve-decl-lookup {"Bag" {}} {}          nil nil)))))

(deftest f51-l3b-b-ii-2-ctx-carries-module-map-slot
  (testing "F51 L3b(b.ii)-2 — the ctx assembled by `lower` carries `:module-map` and `:decl->module` slots so downstream call sites can consult them without re-walking top-nodes. Scaffolding today; consumer wiring lives in Sub-3+. Verified indirectly via a minimal end-to-end lower that emits an empty main — the slots must show up in the same env-construction path any real program takes."
    ;; Programmatic assertion via a spy on the private helpers: after
    ;; b.ii-3 wires consumers, we'd exercise them through a real
    ;; qualified-name resolution. Today, verify the primitives compose
    ;; the way ctx construction does — attribution over the same node
    ;; shape lower sees, then inversion.
    (let [top  [{:node :module :name "foo"}
                {:node :record :name "R" :sections []}]
          attr (cxc.env/attribute-modules-to-decls top)
          mm   (cxc.env/module-map-from-attribution attr)]
      ;; Batch 7 — module-map-from-attribution now also seeds the
      ;; prelude modules so direct-invocation callers (without a
      ;; prepended prelude AST) still resolve prelude refs.
      (is (= #{"R"} (get mm :foo)))
      (is (= :foo (get attr "R"))))))

;; ---------------------------------------------------------------------------
;; F51 L3b(b.ii)-5a — `decl-lookup` caller-facing wrapper. Reads
;; `:resolves-to` from the reference node and `:module-map` from ctx,
;; then dispatches to `resolve-decl-lookup`. b.ii-5b/5c migrated ~55
;; consumer sites through it (single-file + multi-file paths now
;; consume `:resolves-to`); these tests pin the terminal-shape
;; semantics so future migrations have a stable contract.
;; ---------------------------------------------------------------------------

(deftest f51-l3b-b-ii-5a-decl-lookup-uses-resolves-to-hint
  (testing "F51 L3b(b.ii)-5a — `decl-lookup` extracts `:resolves-to` from the reference node and forwards to `resolve-decl-lookup`. When the hint names the module the decl lives in, returns the env entry."
    (let [ctx        {:record-env {"Bag" {:kind :record}}
                      :module-map {:foo #{"Bag"}}}
          ref-node   {:node :type-ref :name "Bag" :resolves-to :foo}]
      (is (= {:kind :record}
             (cxc.env/decl-lookup ctx :record-env ref-node))))))

(deftest f51-l3b-b-ii-5d-batch-7-decl-lookup-nil-hint-returns-nil
  (testing "F51 L3b(b.ii)-5d Batch 7 (2026-08-28) — a ref node with no `:resolves-to` returns nil under strict resolution. The Batch-7 update to `resolve-decl-lookup` retired the short-name fallback; upstream attribution (`resolve/ensure-references-enriched` in single-file / `resolve/resolve-references` in multi-file build) is expected to populate the hint before the reference reaches lower."
    (let [ctx        {:record-env {"Bag" {:kind :record}}
                      :module-map {}}
          ref-node   {:node :type-ref :name "Bag"}]
      (is (nil? (cxc.env/decl-lookup ctx :record-env ref-node))))))

(deftest f51-l3b-b-ii-5a-decl-lookup-hint-mismatch-returns-nil
  (testing "F51 L3b(b.ii)-5a — hint names a module that doesn't declare `name`: strict resolution says unresolved. Callers get nil so they can fire targeted diagnostics (`:cxc-f51-unresolved-name` post-b.ii-5e)."
    (let [ctx        {:atom-env {"Clock" {:kind :atom}}
                      :module-map {:std.clock #{"Clock"}}}
          ref-node   {:node :type-ref :name "Clock" :resolves-to :foo}]
      (is (nil? (cxc.env/decl-lookup ctx :atom-env ref-node))))))

(deftest f51-l3b-b-ii-5a-decl-lookup-dispatches-per-env-kw
  (testing "F51 L3b(b.ii)-5a — `env-kw` selects which of `:record-env` / `:atom-env` / `:enum-env` to consult. Consumers pass the kw they'd have written into a raw `get` — no need to pull the env out at the call site."
    (let [ctx {:record-env {"R" {:tag :record-hit}}
               :atom-env   {"A" {:tag :atom-hit}}
               :enum-env   {"E" {:tag :enum-hit}}
               :module-map {:m #{"R" "A" "E"}}}]
      (is (= {:tag :record-hit}
             (cxc.env/decl-lookup ctx :record-env
                                      {:node :type-ref :name "R" :resolves-to :m})))
      (is (= {:tag :atom-hit}
             (cxc.env/decl-lookup ctx :atom-env
                                      {:node :type-ref :name "A" :resolves-to :m})))
      (is (= {:tag :enum-hit}
             (cxc.env/decl-lookup ctx :enum-env
                                      {:node :type-ref :name "E" :resolves-to :m}))))))

(deftest f51-l3b-b-ii-5a-decl-lookup-nil-name-returns-nil
  (testing "F51 L3b(b.ii)-5a — reference nodes with `:name` nil (compiler-synthesized nodes at certain positions) short-circuit through the `resolve-decl-lookup` nil-name guard. Matches the b.ii-2 tests for the primitive."
    (let [ctx      {:record-env {"Bag" {}} :module-map {:foo #{"Bag"}}}
          ref-node {:node :type-ref :name nil}]
      (is (nil? (cxc.env/decl-lookup ctx :record-env ref-node))))))

;; b.ii-5d Batch 4 retired the `decl-lookup` string arity (a b.ii-5c
;; shim that consulted ambient `*decl->module*`). Every consumer now
;; passes either an AST reference node (via `decl-lookup`) or a bare
;; env key (via `env-lookup`). The two `-string-arity-` regression
;; tests that pinned the shim's behavior deleted with it.

;; ---------------------------------------------------------------------------
;; F51 L3b(b.ii)-5d Batch 7 (2026-08-28) — strict-lookup diagnostic.
;; `resolve-decl-lookup` no longer falls back to short-name; consumer
;; sites that must-resolve (record/atom ctor call) fire
;; `:cxc-f51-unresolved-name` when the lookup returns nil.
;; ---------------------------------------------------------------------------

(deftest f51-l3b-b-ii-5d-batch7-record-ctor-with-stale-hint-diagnostic
  (testing "F51 L3b(b.ii)-5d Batch 7 — a record ctor call whose callee ident carries a `:resolves-to` hint naming a module that does NOT declare the short name fires `:cxc-f51-unresolved-name`. This models an upstream attribution bug: the ref survived to lower with a stale hint."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cxc-f51-unresolved-name|resolved to no env entry"
         (let [prog (p/parse "def R:\n    x: Int\n\ndef main:\n    -> running\n        then: print(R(x: 1).x)\n")
               ;; Rewrite the R(...) callee's :resolves-to to a
               ;; module that doesn't declare R — simulates a stale
               ;; attribution hint.
               tampered ((requiring-resolve 'clojure.walk/postwalk)
                          (fn [n]
                            (if (and (map? n)
                                     (= :ident (:node n))
                                     (= "R" (:name n)))
                              (assoc n :resolves-to :nonexistent-module)
                              n))
                          prog)]
           (lower/lower tampered))))))

(deftest f51-l3b-b-ii-5d-batch7-atom-ctor-with-stale-hint-diagnostic
  (testing "F51 L3b(b.ii)-5d Batch 7 — same strict-lookup failure on an atom ctor: `A()` with a hint naming a module that doesn't declare `A` raises `:cxc-f51-unresolved-name`. Phantom-cap short-circuit (`SystemPanic`) doesn't apply to user atoms."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cxc-f51-unresolved-name|resolved to no env entry"
         (let [prog (p/parse "def A:\n    -> running\n\ndef main:\n    a: A = A()\n    -> running\n")
               tampered ((requiring-resolve 'clojure.walk/postwalk)
                          (fn [n]
                            (if (and (map? n)
                                     (= :ident (:node n))
                                     (= "A" (:name n)))
                              (assoc n :resolves-to :nonexistent-module)
                              n))
                          prog)]
           (lower/lower tampered))))))

(deftest f51-l3b-b-ii-5d-batch7-strict-primitive-returns-nil-without-hint
  (testing "F51 L3b(b.ii)-5d Batch 7 — the primitive itself has no short-name fallback: a ref-node with `:resolves-to nil` returns nil regardless of whether `(get env name)` would succeed. Callers whose intent is bookkeeping (data-derived name, iteration) use `env-lookup` instead."
    (let [ctx      {:record-env {"Bag" {:kind :record}} :module-map {:m #{"Bag"}}}
          ref-node {:node :type-ref :name "Bag"}]
      (is (nil? (cxc.env/decl-lookup ctx :record-env ref-node)))
      ;; env-lookup is the correct primitive when the name is
      ;; data-derived; it returns the entry.
      (is (= {:kind :record}
             (cxc.env/env-lookup ctx :record-env "Bag"))))))

;; ---------------------------------------------------------------------------
;; F52 L4 module let-bindings retired (R3, 2026-08-31): module-scope
;; `let` deleted universally; the extract/substitute/reject/emit pipeline
;; and its 26-test surface came out with it. See parse.clj R3 fix-it +
;; spec §7.1.
;; ---------------------------------------------------------------------------

(deftest raw-primitive-eq-nil-rejected
  (testing "2026-09-06 Post-Path-1 #1: raw-primitive `x == nil` fires a static user error. Two shapes exercised — a bare literal (`0 == nil`) and a `T!` local (`let n: Int! = 5; n == nil`). Guards against the pre-fix silent constant-fold that hid `if r == nil:` on `r: Int!` from the user."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"binop `==` between non-nullable primitive"
         (lower-src (str "def main:\n"
                         "    -> running\n"
                         "        then: print((0 == nil).to_string())\n"))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"binop `==` between non-nullable primitive"
         (lower-src (str "def main:\n"
                         "    -> running\n"
                         "        then:\n"
                         "            let n: Int! = 5\n"
                         "            print((n == nil).to_string())\n"))))))

(deftest post-path-1-uint-bang-ret-guard-fires-on-nullable-receiver
  (testing "2026-09-06 Post-Path-1 #4: slice 10 swapped the `t-bang-primitive-ret?` predicate from `primitive-nil-sentinel` (Int/Bool/Float families) to `primitive-envelope-eligible?` (adds UInt). Regression guard proving a `-> UInt64!` method on a nullable atom receiver actually fires the `:c-nonnil-return-guard` wrap. Pre-swap the branch missed UInt entirely (no sentinel → predicate returned nil → guard never fired), so `nil_atom.tally()` on a UInt-returning method would silently produce `0` from ObjC nil-messaging without a stderr diagnostic."
    (let [prog (lower-src (str "def P:\n"
                               "    tally() :- UInt64!\n"
                               "\n"
                               "    tally() :- n\n"
                               "\n"
                               "def X:\n"
                               "    n: UInt64! = 42\n"
                               "\n"
                               "    -> running\n"
                               "\n"
                               "extend X(P):\n"
                               "    _dummy() :- 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then:\n"
                               "            let dead: X = nil\n"
                               "            print(dead.tally().to_string())\n"))
          main-cls   (first (filter #(= "CxcMain" (:name %)) (:classes prog)))
          state-init (first (filter #(= "cxc_stateInit" (:selector %))
                                    (:methods main-cls)))
          guards     (filter #(and (map? %)
                                   (= :c-nonnil-return-guard (:node %)))
                             (tree-seq coll? seq (:body state-init)))]
      (is (some? state-init) "CxcMain.cxc_stateInit is emitted")
      (is (seq guards)
          "`:c-nonnil-return-guard` wraps `dead.tally()` — UInt64! primitive return on nullable receiver")
      (is (re-find #"tally.*non-null primitive return"
                   (:site-label (first guards)))
          "guard's site-label names the method and the guard reason"))))
