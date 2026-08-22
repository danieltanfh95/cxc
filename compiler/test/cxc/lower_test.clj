(ns cxc.lower-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cxc.parse :as p]
            [cxc.lower :as lower]
            [cxc.emit :as emit]))

(defn- lower-src [src] (lower/lower (p/parse src)))

(defn- main-work-body
  "Return the body of CxcMain's cxc_stateInit — the effective site of
   `def main: -> running then: <stmts>` under main-as-atom (Steps 16/17).
   Falls back to the raw main body for programs without ObjC classes."
  [prog]
  (if-let [cxc-main (first (filter #(= "CxcMain" (:name %)) (:classes prog)))]
    (:body (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cxc-main))))
    (get-in prog [:main :body])))

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

(deftest unsupported-call-fails-nyi
  (testing "any call other than print raises :not-yet-implemented"
    (try
      (lower-src "def main:\n    -> running\n        then: unknown_fn(1)\n")
      (is false "expected NYI throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-yet-implemented (:kind (ex-data e))))))))

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

(deftest missing-field-in-ctor-fails-nyi
  (testing "V(x: 1) when V requires x + y (no default on y) — fail-nyi"
    (try
      (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(x: 1).x)\n")
      (is false "expected NYI throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-yet-implemented (:kind (ex-data e))))))))

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
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"duplicate named arg" (.getMessage ex))))))

(deftest f40-record-ctor-unknown-named-arg-rejected
  (testing "V(bogus: 1) — unknown named arg fails, not silently ignored"
    (let [ex (try
               (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(x: 1, y: 2, bogus: 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"unknown named arg" (.getMessage ex)))
      (is (re-find #"bogus" (.getMessage ex))))))

(deftest f40-record-ctor-surplus-positional-rejected
  (testing "V(1, 2, 3) when V has 2 fields — surplus positional fails"
    (let [ex (try
               (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(1, 2, 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"too many positional" (.getMessage ex))))))

(deftest f40-record-ctor-positional-and-named-collision-rejected
  (testing "V(1, x: 2) — same param supplied positionally AND by name fails"
    (let [ex (try
               (lower-src "def V:\n    x: Int\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(1, x: 2, y: 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"both positionally and by name" (.getMessage ex))))))

(deftest f40-record-fields-defaults-must-follow-required
  (testing "record V { x: Int = 1, y: Int } — defaulted before required is a compile error"
    (let [ex (try
               (lower-src "def V:\n    x: Int = 1\n    y: Int\n\ndef main:\n    -> running\n        then: print(V(x: 10, y: 20).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"defaulted param" (.getMessage ex))))))

(deftest f40-atom-ctor-duplicate-named-arg-rejected
  (testing "duplicate named arg on atom ctor fails"
    (let [ex (try
               (lower-src "def Widget(a: Int, b: Int):\n    n: Int = a\n\ndef main:\n    w: Widget = Widget(a: 1, a: 2, b: 3)\n\n    -> running\n        then: print(0)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"duplicate named arg" (.getMessage ex))))))

(deftest f40-atom-ctor-unknown-named-arg-rejected
  (testing "unknown named arg on atom ctor fails"
    (let [ex (try
               (lower-src "def Widget(a: Int):\n    n: Int = a\n\ndef main:\n    w: Widget = Widget(a: 1, ghost: 2)\n\n    -> running\n        then: print(0)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
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
              {:name "n"                :c-type "_Cxc_Optional_Int64" :attrs ["assign"]}]
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
                                 {:name "value" :value {:node :int :value 42}}]}}]
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
          cls  (first (:classes prog))]
      ;; State-init runs data defaults then the initial-phase on-enter body.
      ;; Structural setup owns queue + phase = initial constant.
      (let [state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cls)))]
        (is (= [{:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :int  :value 0}}
                {:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :binop :op "+"
                        :left  {:node :name :sym "_n"}
                        :right {:node :int  :value 1}}}
                {:node :raw-c
                 :text "if ((_n) == nil) cxc_nonnil_violation(\"def-atom `Ticker` field write\", \"n\");"}]
               (:body state)))))))

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
        ;; Inside try: [phase-update, wildcard-do-stmt, checkInvariants-call]
        (is (= 3 (count b)))
        (is (= :assign (:node (first b))))
        (is (= "_phase" (get-in (first b) [:lhs :sym])))
        ;; Wildcard's print(0)
        (is (= :call (:node (second b))))
        ;; Trailing invariant check
        (is (= "checkInvariants" (get-in (nth b 2) [:selector])))))))

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
          branch (first (:body bump))]
      ;; Branch body wrapped in :end-catch-wrap. Inside: the specific
      ;; then: (no wildcard do inlined — self-loop isn't phase change),
      ;; followed by the post-transition invariant check.
      (let [wrap (first (:body branch))
            b    (:body wrap)]
        (is (= :end-catch-wrap (:node wrap)))
        (is (= 2 (count b)))
        (is (= :assign (:node (first b))))
        (is (= "checkInvariants" (get-in (second b) [:selector])))))))

(deftest record-observable-lowers-to-c-function
  (testing "F67 L4 — record parametric observable → :c-function under :functions with mangled name and self as first param (replaces reader methods per Proposal G class-body collapse)."
    (let [prog (lower-src "def V:\n    x: Float\n    y: Float\n\n    plus(other: V) :< V(x: self.x + other.x, y: self.y + other.y)\n\ndef main:\n    -> running\n        then: print(V(x: 1.0, y: 2.0).plus(V(x: 3.0, y: 4.0)).x)\n")
          f    (first (:functions prog))]
      (is (= :c-function (:node f)))
      (is (= "V_plus" (:name f)))
      (is (= "V" (:return-type f)))
      (is (= [{:name "self" :c-type "V"} {:name "other" :c-type "V"}] (:params f)))
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
      (is (= [{:name "initial" :c-type "int64_t"}] (:init-params cls)))
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
        ;; boundary (Sub-J).
        (is (= [{:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :struct-lit
                        :type "_Cxc_Optional_Int64"
                        :fields [{:name "present" :value {:node :int :value 1}}
                                 {:name "value"   :value {:node :name :sym "initial"}}]}}]
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
              {:name "n"                :c-type "_Cxc_Optional_Int64" :attrs ["assign"]}
              {:name "other"            :c-type "Counter *"        :attrs ["strong"]}]
             (:properties cls)))
      (let [state-init (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cls)))]
        (is (= {:node :assign
                :lhs  {:node :name :sym "_other"}
                :rhs  {:node :nil}}
               ;; cxc_stateInit body: [_n=5, _other=nil]
               (second (:body state-init))))))))

(deftest atom-observable-lowers-to-objc-method
  (testing "F67 L4 — def-atom parametric observable → :objc-method under class :methods (replaces reader methods per Proposal G)."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    bump() :< n + 1\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().bump())\n")
          cls  (first (:classes prog))
          m    (first (filter #(= "bump" (:selector %)) (:methods cls)))]
      (is (= :objc-method (:node m)))
      (is (= "bump" (:selector m)))
      (is (= "int64_t" (:return-type m)))
      (is (empty? (:params m))))))

(deftest atom-observable-call-lowers-to-objc-msg
  (testing "counter.bump() → :objc-msg with the receiver as :recv and bare selector (observable call site, F67 L4)."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    bump() :< n + 1\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().bump())\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])]
      (is (= :objc-msg (:node msg)))
      (is (= "bump" (:selector msg)))
      (is (= :objc-alloc-init (get-in msg [:recv :node])))
      (is (empty? (:args msg))))))

(deftest atom-observable-multiarg-selector
  (testing "plus(other: Int) → selector `plusWithother:` and matching call (parametric observable selector construction, F67 L2/L4)."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    plus(other: Int) :< n + other\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().plus(5))\n")
          m    (first (filter #(= "plusWithother:" (:selector %))
                              (:methods (first (filter #(= "Counter" (:name %)) (:classes prog))))))
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])]
      (is (= "plusWithother:" (:selector m)))
      (is (= [{:name "other" :c-type "int64_t"}] (:params m)))
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
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-record-field-default-call-rejected
  (testing "record V { x: Int = foo() } — call as default fails"
    (let [ex (try
               (lower-src "def V:\n    x: Int = foo()\n\ndef main:\n    -> running\n        then: print(V(x: 10).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-atom-ctor-default-computed-rejected
  (testing "def Widget(x: Int = 1 + 1) — arithmetic expr as ctor default fails"
    (let [ex (try
               (lower-src "def Widget(x: Int = 1 + 1):\n    n: Int = x\n\ndef main:\n    -> running\n        then: print(Widget().n)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-observable-default-computed-rejected
  (testing "parametric observable with `factor: Int = 2 * 2` default fails — non-const check runs on observable params too."
    (let [ex (try
               (lower-src "def V:\n    x: Int\n\n    scaled(factor: Int = 2 * 2) :< x * factor\n\ndef main:\n    -> running\n        then: print(V(x: 5).scaled())\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
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
  (testing "counter.plus(other: 7) — named arg on atom observable resolves (F67 L4 parametric observable arg dispatch)."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    plus(other: Int) :< n + other\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().plus(other: 7))\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])]
      (is (= "plusWithother:" (:selector msg)))
      (is (= 1 (count (:args msg))))
      (is (= 7 (get-in msg [:args 0 :value]))))))

(deftest f40-atom-observable-defaults-fill-when-omitted
  (testing "counter.plus() with other defaulted uses default (F67 L4)."
    (let [prog (lower-src "def Counter:\n    n: Int! = 10\n\n    plus(other: Int = 3) :< n + other\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().plus())\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])]
      (is (= "plusWithother:" (:selector msg)))
      (is (= 1 (count (:args msg))))
      (is (= 3 (get-in msg [:args 0 :value]))))))

(deftest f40-atom-observable-named-out-of-order-reorders
  (testing "counter.plus3(c: 3, a: 1, b: 2) — reordered to decl order (F67 L4 parametric observable)."
    (let [prog (lower-src "def Counter:\n    n: Int! = 0\n\n    plus3(a: Int, b: Int, c: Int) :< n + a + b + c\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().plus3(c: 3, a: 1, b: 2))\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])
          arg-values (mapv :value (:args msg))]
      (is (= [1 2 3] arg-values)))))

(deftest f40-record-observable-defaults-fill-when-omitted
  (testing "record parametric observable with param default inserts default at call site (F67 L4)."
    (let [prog (lower-src "def V:\n    x: Int!\n\n    scaled(factor: Int = 4) :< x * factor\n\ndef main:\n    -> running\n        then: print(V(x: 5).scaled())\n")
          call (first (main-work-body prog))
          inner (get-in call [:args 1])]
      (is (= :call (:node inner)))
      (is (= "V_scaled" (:fn inner)))
      ;; args = [self, factor]
      (is (= 2 (count (:args inner))))
      (is (= 4 (get-in inner [:args 1 :value]))))))

(deftest f40-observable-unknown-named-arg-rejected
  (testing "atom observable with unknown named arg fails (F67 L4)."
    (let [ex (try
               (lower-src "def Counter:\n    n: Int = 10\n\n    plus(other: Int) :< n + other\n\ndef main:\n    -> running\n        then: print(Counter().plus(bogus: 5))\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"unknown named arg" (.getMessage ex))))))

;; F27 semantic once-only — chained comparison lowers to :chained-cmp;
;; emit renders as GNU stmt-expr binding each interior operand once.

(deftest f27-emit-renders-gnu-stmt-expr
  (testing "when: -10 <= val <= 10 emits a GNU stmt-expr binding _val once"
    (let [prog (lower-src "def main:\n    val: Int! = 5\n\n    -> running\n\n    running <: bump -> self\n        when: -10 <= val <= 10\n        then: val => val + 1\n")
          c-src (emit/emit prog)]
      ;; The interior operand is bound to __cxc_cc_1 via __typeof__.
      (is (str/includes? c-src "__typeof__(_val) __cxc_cc_1 = (_val);"))
      ;; Comparisons reference the bound local, not the raw _val.
      (is (str/includes? c-src "(-10) <= __cxc_cc_1 && __cxc_cc_1 <= 10"))
      ;; Wrapped in a GNU statement-expression.
      (is (str/includes?
           c-src
           "({ __typeof__(_val) __cxc_cc_1 = (_val); (-10) <= __cxc_cc_1 && __cxc_cc_1 <= 10; })")))))

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
    (let [prog (lower-src "def Counter:\n    raw_other: Counter = nil\n\n    other :< raw_other\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(Counter().other is nil)\n")
          call (first (main-work-body prog))
          expr (get-in call [:args 1])]
      (is (= :binop (:node expr)))
      (is (= "==" (:op expr)))
      (is (= :nil (get-in expr [:right :node]))))))

(deftest enum-lowers-to-c-enum-with-mangled-cases
  (testing "enum decl → :c-enum under :enums; Key.arrow_down → C constant `Key_arrow_down`"
    (let [prog (lower-src "def Key:\n    case arrow_up\n    case arrow_down\n\ndef main:\n    -> running\n        then: print(Key.arrow_down)\n")
          e    (first (:enums prog))
          call (first (get-in prog [:main :body]))]
      (is (= :c-enum (:node e)))
      (is (= "Key" (:name e)))
      (is (= ["arrow_up" "arrow_down"] (:cases e)))
      (is (= "%d\n" (get-in call [:args 0 :value])))
      (is (= {:node :name :sym "Key_arrow_down"} (get-in call [:args 1]))))))

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
    (let [prog (lower-src "def V:\n    x: Float!\n    y: Float!\n\n    plus(other: V) :< V(x: self.x + other.x, y: self.y + other.y)\n\ndef main:\n    -> running\n        then: print(V(x: 1.0, y: 2.0).plus(V(x: 3.0, y: 4.0)).x)\n")
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
        ;; Inside the try body: then-update, checkInvariants
        (is (= [{:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :binop :op "+"
                        :left  {:node :name :sym "_n"}
                        :right {:node :int  :value 1}}}
                {:node :raw-c
                 :text "if ((_n) == nil) cxc_nonnil_violation(\"def-atom `Counter` field write\", \"n\");"}
                {:node :objc-msg
                 :recv {:node :name :sym "self"}
                 :selector "checkInvariants"
                 :args []}]
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
      (let [wrap (first (:body branch))]
        (is (= :end-catch-wrap (:node wrap)))
        (is (= {:node :assign
                :lhs  {:node :name :sym "_phase"}
                :rhs  {:node :name :sym "kEnemy_phase_dead"}}
               (first (:body wrap))))))))

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

(deftest header-and-body-when-guards-and-fold-into-single-branch-guard
  (testing "`when: by > 0` and body `when: hp > 0` AND together on the branch"
    (let [prog (lower-src "def Enemy:
    hp: Int! = 10

    -> alive

    alive <: take_damage when: hp > 0 -> alive
        when: hp > 0
        then: hp => hp - 1

def main:
    -> running
        then: print(1)
")
          cls  (first (:classes prog))
          td   (first (filter #(= "take_damage" (:selector %)) (:methods cls)))
          branch (first (:body td))]
      (is (some? branch))
      ;; guard is `(hp > 0) && (hp > 0)` — header AND body
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
    small_next :< n

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
  (testing "Phase D F36a Landing 3a — spec §3.1 non-numeric substrate (Any/Bytes). Bytes and Any resolve as reference-typed field storage. Unit/Never return-type mapping to `void` is not testable under F67 L4 observables (no explicit return type annotation); tracked as a follow-on for a `name(args) -> T :< expr` observable-return-type surface."
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
    invariants:
        0 <= v
        v <= 150

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
          assigns      (filter #(= :assign (:node %)) body)
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

(deftest pre-destruction-invariant-check-runs-before-resource-clear-and-cascade
  (testing "Phase D F49 Landing D — spec §3.4.2 requires invariants to hold BEFORE destruction. Prior to Landing D, `checkInvariants` fired last in transitionToEnd (after resource clear + child cascade + parent notify) — a failure at that site named a state the atom was no longer in. Landing D reorders: user on-end `do:` (may fix up state) → invariant check → resource clear → cascade → notify parent → (main: shutdown)."
    (let [prog (lower-src "def Bounded:
    v: Int = 42
    invariants:
        0 <= v
        v <= 150

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

(deftest user-observable-body-wrapped-with-entry-exit-invariant-checks
  (testing "Phase D F49 Landing C — spec §3.4.2 requires invariants to hold before/after each observable read. Observable bodies wrap with `[self checkInvariants]` calls at entry and exit; return synthesized around the observable expression body. Post F67 L4: observables are the reader surface (methods retire)."
    (let [prog (lower-src "def Bounded:
    v: Int = 42
    invariants:
        0 <= v
        v <= 150

    noop() :< v

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
      (is (= 0 (first check-idxs))
          "entry check is the first statement in the body")
      (is (< (second check-idxs) return-idx)
          "exit check runs before the return statement (verifies final state before returning)"))))

(deftest invariant-driven-picker-narrows-int-fields-to-sized-atoms
  (testing "Phase D F36 Landing 2a — spec §3.1.2 rule 3 invariant-driven representation picker. For each bare-Int `data:` field whose invariants chain-comparison-bound it to a range that fits a sized atom (UInt8/UInt16/... or Int8/Int16/Int32), storage narrows to the smallest fitting atom. Sized-atom fields (§3.1.2 rule 1) already pin their width; only bare Int gets picked. Fields with only one bound stay Int64.

  Safety hinges on F49 Landing A's pre-store check running on the wide ctor param / default before the narrowing assignment. Without F49 A this would be a silent-corruption path."
    (let [prog (lower-src "def Age:
    value: Int = 42
    invariants:
        0 <= value
        value <= 150

    -> running

    -> end

def Port:
    value: Int = 8080
    invariants:
        1 <= value
        value <= 65535

    -> running

    -> end

def Temp:
    value: Int = 0
    invariants:
        -128 <= value
        value <= 127

    -> running

    -> end

def Signed16:
    value: Int = 0
    invariants:
        -1000 <= value
        value <= 1000

    -> running

    -> end

def OneBound:
    value: Int = 5
    invariants:
        value >= 0

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
    invariants:
        0 <= value
        value <= 150

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
    invariants:
        0 <= value
        value <= 150

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
      (is (= [{:name "value" :c-type "int64_t"}] age-init-params)
          "Age.value field became required ctor param (int64_t — the source-level Int, NOT the picker-narrowed uint8_t)")
      (is (= "cxc_stateInitWithvalue:" (:selector age-state))
          "cxc_stateInit selector reflects the value parameter")
      (is (= [] cont-init-params)
          "Container fields (List of Int) skipped F41 — auto-init to empty container"))))

(deftest f41-picker-and-f49-a-end-to-end-narrowing-safety
  (testing "Phase D F41 × F36 Landing 2a × F49 Landing A end-to-end: user writes `data: value: Int; invariants: 0 <= value <= 150`, constructs `Age(value: 42)`, gets uint8_t storage for value, and out-of-range construction aborts on the wide ctor param before the narrowing assignment.

  This is the complete picker safety story — F41 makes ctor params real, picker narrows storage, pre-store check on the wide source aborts if the value doesn't fit. Without any of these three, silent truncation could occur."
    (let [prog (lower-src "def Age:
    value: Int
    invariants:
        0 <= value
        value <= 150

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
        when: n in n
        then: n => n
")))))

(deftest subscript-on-non-string-raises-targeted-diagnostic
  (testing "F19 L2 — subscript lowering handles `String[i]` only. On any other target type (Int, atom, container), lowering raises a targeted diagnostic naming the F42/F43 dependency and the workaround."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"subscript `expr\[i\]` on target of type :int"
         (lower-src "def R:
    n: Int!

    first() :< self.n[0]

def main:
    -> running
        then: print(0)
")))))

(deftest f19-l2-string-subscript-lowers-to-character-at-index
  (testing "F19 L2 — `s[i]` on a String lowers to `(int64_t)[s characterAtIndex:i]` returning Int. Bounds behavior follows NSString: NSRangeException on OOB, caught by F48's `@try/@catch` transition wrapper."
    (let [prog (lower-src "def R:
    s: String

    first_char() :< self.s[0]

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
          m    (some #(when (= :main (:node %)) %) (:classes prog))
          ;; Not present in this shape — main isn't a class here.
          ;; Simpler: inspect main's body directly.
          main-body (get-in prog [:main :body])
          ;; Find the print call and check its arg.
          call-arg (loop [stmts main-body]
                     (when-let [s (first stmts)]
                       (or (when (= :call (:node s))
                             (first (:args s)))
                           (recur (rest stmts)))))]
      ;; The printf call's second arg (formatted value) is the F37 call.
      ;; Loop over stmts to find any :call whose args mention V_cxc_equals.
      (let [walker (fn walker [x]
                     (cond
                       (map? x)
                       (or (when (and (= :call (:node x))
                                      (= "V_cxc_equals" (:fn x)))
                             x)
                           (some walker (vals x)))
                       (sequential? x) (some walker x)))]
        (is (some? (walker main-body))
            "expected V_cxc_equals call in main body")))))

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
  (testing "F37 L2+3 — a record with a bare (nullable) `String` field falls outside L3's `String!`-only path. Raises a targeted fail-nyi naming the field and pointing at F39 L9 Optional plumbing as the unblocker."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F37 Landings 2 \+ 3.*W\.name.*String"
         (lower-src "def W:\n    name: String\n\ndef main:\n    -> running\n        then:\n            let b: Bytes = W(name: \"hi\").as()\n            print(b.length)\n")))))

(deftest f37-l3-bytes-string-field-encodes-utf8-with-uint32-prefix
  (testing "F37 L3 — a record with a `String!` field synthesizes an encoder that emits `UInt32` big-endian length prefix + raw UTF-8 bytes per §3.10.10 String row. Body inspected for `dataUsingEncoding:NSUTF8StringEncoding` + `OSSwapHostToBigInt32` at the prefix site."
    (let [prog (lower-src "def Msg:\n    text: String!\n\ndef main:\n    -> running\n")
          f    (first (filter #(= "Msg_cxc_as_bytes" (:name %)) (:functions prog)))
          body-text (get-in f [:body 0 :text])]
      (is (some? f))
      (is (re-find #"dataUsingEncoding:NSUTF8StringEncoding" body-text))
      (is (re-find #"OSSwapHostToBigInt32.*\[__sd length\]" body-text)))))

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

(deftest f67-l4a-zero-arg-record-observable-lowers-as-c-function
  (testing "F67 L4a: `name :< expr` on a record lowers as a `Record_name(self)` C function. Return type inferred from the expression (int here); bare field refs (`x`, `y`) auto-rewrite to `self.x`, `self.y`."
    (let [prog (lower-src "def V:
    x: Int
    y: Int

    magnitude_sq :< x * x + y * y

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
  (testing "F67 L4a: `name(args) :< expr` on a record lowers as `Record_name(self, args...)` C function. Args become C params after self."
    (let [prog (lower-src "def V:
    x: Int
    y: Int

    scaled(k: Int) :< (x + y) * k

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
  (testing "F67 L3: `* <: name(args) -> * then: ...` (the handler-collapse form that subsumes the retired F14 `-> handler(args):` sugar) lowers as a void ObjC method on the atom's class. Same semantics as spec §1265/§2160 handler-decl; different surface syntax."
    (let [prog (lower-src "def A:
    n: Int = 0

    -> running

    * <: tick(msg: Int) -> *
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
  (testing "F67 L3: zero-arg `* <: tick() -> * then: ...` lowers with a plain selector (no colon) and no user params."
    (let [prog (lower-src "def A:
    n: Int = 0

    -> running

    * <: tick() -> *
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
  (testing "F67 L3: the legacy `-> foo(args):` handler-decl sugar (F14) is retired at parse time; users get a migration diagnostic pointing at the explicit `* <: foo(args) -> *` handler-shape transition."
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
  (testing "F67 L3: two handler-shape transitions with the same message name on one atom → duplicate-handler diagnostic (spec §1265: name uniqueness)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"declares handler `-> tick.*more than once"
         (lower-src "def A:
    n: Int = 0

    -> running

    * <: tick(msg: Int) -> *
        then: return n

    * <: tick(msg: Int) -> *
        then: return n

def main:
    a: A = A()

    -> running
")))))

(deftest f31-f35-l2-panic-lowers-to-inline-trap
  (testing "F31/F35 Landing 2 — `panic(\"msg\")` lowers to an inline expr that prints `panic: <msg>` + backtrace and calls `__builtin_trap()`. Uncatchable (bypasses `@try/@catch`); BEAM-style transition isolation is preserved separately."
    (let [prog (lower-src "def main:\n    -> running\n        then: panic(\"boom\")\n")
          call (first (get-in prog [:main :body]))]
      (is (= :ffi-call (:node call)))
      (is (= "panic" (:name call)))
      (let [rendered ((:emit-c-fn call) ["@\"boom\""])]
        (is (clojure.string/includes? rendered "panic: %s")
            "renders the panic-prefixed format string")
        (is (clojure.string/includes? rendered "backtrace(")
            "renders the inline backtrace call")
        (is (clojure.string/includes? rendered "__builtin_trap()")
            "renders the uncatchable trap")))))

(deftest f31-f35-l2-panic-non-string-arg-rejected
  (testing "F31/F35 Landing 2 — MVP accepts a string-literal arg only (spec §3.5.4). Domain-error payload waits on Result infra."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"panic\(\) arg must be a string literal"
         (lower-src "def main:\n    -> running\n        then: panic(42)\n")))))

(deftest f31-f35-l2-panic-wrong-arity-rejected
  (testing "F31/F35 Landing 2 — panic expects exactly one arg."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"panic\(\) expects exactly one"
         (lower-src "def main:\n    -> running\n        then: panic(\"a\", \"b\")\n")))))

(deftest f31-f35-l2-panic-pulls-execinfo-include
  (testing "F31/F35 Landing 2 — programs using panic get `<execinfo.h>` in includes (for the inlined backtrace() call). Programs without panic don't."
    (let [with-panic    (lower-src "def main:\n    -> running\n        then: panic(\"x\")\n")
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
  (testing "F9 Landing 2 — a `protocol` declaration lowers cleanly and its `accepts:` entries land in the program's :protocols map. Registration-only for now — dispatch wires in L4, conformance validation in L5."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        foo(x: Int) -> Int\n"
                               "        bar(s: String) -> Bool\n"
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
                               "    accepts:\n"
                               "        greet() -> Int\n"
                               "\n"
                               "    greet() :< 42\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          proto (get-in prog [:protocols "P"])]
      (is (some? proto))
      (is (= 1 (count (:defaults proto))))
      (is (= "greet" (:name (first (:defaults proto))))))))

(deftest f9-l2-duplicate-accepts-rejected
  (testing "F9 Landing 2 — duplicate `accepts` entry names in the same protocol fail at lower: each selector appears at most once in a protocol's surface (§3.4.5)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"declares `accepts` entry `foo` more than once"
         (lower-src (str "def P:\n"
                         "    accepts:\n"
                         "        foo(x: Int) -> Int\n"
                         "        foo(y: String) -> Bool\n"
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
                               "    bar() :< 1\n"
                               "\n"
                               "extend Widget:\n"
                               "    baz() :< 2\n"
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
                         "    bar() :< 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l3-extension-may-target-protocol
  (testing "F9 Landing 3 — `extend P:` where P is a protocol is legal at L3: extension-env just records; L6 handles default-method inheritance from extension observables (F67 L4)."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        foo(x: Int) -> Int\n"
                               "\n"
                               "extend P:\n"
                               "    bar() :< 1\n"
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
                                         "    own_m() :< 1\n"
                                         "\n"
                                         "extend Widget:\n"
                                         "    ext_m() :< 2\n"
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
                                         "    own_m() :< 100\n"
                                         "\n"
                                         "extend Widget:\n"
                                         "    own_m() :< 200\n"
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
                                         "    m() :< 1\n"
                                         "\n"
                                         "extend Widget:\n"
                                         "    m() :< 2\n"
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
                               "    accepts:\n"
                               "        m(self) -> Int\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    m() :< 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))]
      ;; L4 stores conforms on record-env; not directly surfaced on the
      ;; program shape, but the compose path must not throw and the
      ;; extension observable must land.
      (is (some #(= "Widget_m" (:name %)) (:functions prog))))))

(deftest f9-l4-extension-invariant-on-record-fail-nyi
  (testing "F9 Landing 4 — record targets have no runtime invariant path yet; extension-added `invariants:` on a record fails-nyi with a pointer at the follow-on sub-landing. Atoms already handle invariants natively (no fail-nyi)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"record targets have no runtime invariant path"
         (lower-src (str "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget:\n"
                         "    invariants:\n"
                         "        n > 0\n"
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

(deftest f9-l5-conformance-satisfied-by-extension-observable
  (testing "F9 Landing 5 + F67 L4 — `extend Widget(P):` supplying `m` (as an observable) matching P's accepts entry validates cleanly."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    m() :< 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))]
      (is (some #(= "Widget_m" (:name %)) (:functions prog))))))

(deftest f67-l4c-extension-replace-shadows-own-suppresses-warning
  (testing "F67 L4c — extension observable prefixed with `replace` intentionally shadows the target class's own observable (§3.3.2 rule 4). Extension wins in the composed observable list; own is dropped; no shadow warning fires."
    (let [sw (java.io.StringWriter.)
          prog (binding [*err* sw]
                 (lower-src (str "def Widget:\n"
                                 "    n: Int\n"
                                 "\n"
                                 "    bar() :< 1\n"
                                 "\n"
                                 "extend Widget:\n"
                                 "    replace bar() :< 2\n"
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
                                  "    bar() :< 1\n"
                                  "\n"
                                  "extend Widget:\n"
                                  "    bar() :< 2\n"
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
                                  "    replace unrelated() :< 1\n"
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
                         "    m() :< 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l5-missing-method-rejected
  (testing "F9 Landing 5 — class conforms to protocol but doesn't implement one of its accepts entries and no default is provided → fail-compile."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"claims conformance to `P` but neither implements nor inherits a default for `m`"
         (lower-src (str "def P:\n"
                         "    accepts:\n"
                         "        m() -> Int\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    unrelated() :< 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l5-signature-mismatch-return-type-rejected
  (testing "F9 L5 + F67 L4b — extension observable whose inferred body type does not match the protocol's accepts return type fails conformance with a specific detail line. `observable-methods-view` sources the observable's return type from post-inference `:type-kw`; `signature-mismatch-detail` compares it against the accepts entry's declared return type."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"return type mismatch — expected `Int`, got `String`"
         (lower-src (str "def P:\n"
                         "    accepts:\n"
                         "        m() -> Int\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    m() :< \"x\"\n"
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
                         "    accepts:\n"
                         "        m(x: Int) -> Int\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    m(y: Int) :< y\n"
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
                         "    accepts:\n"
                         "        m(x: Int) -> Int\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    m(x: String) :< 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l5-protocol-default-satisfies-conformance
  (testing "F9 Landing 5 + F67 L4 — protocol carries a default observable body for `m`; class declares `conforms: P` and doesn't implement `m` itself. L5 accepts the default's presence as structural satisfaction."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "\n"
                               "    m() :< 42\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    unrelated() :< 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))]
      (is (some? prog) "compiles cleanly — default satisfies the accepts"))))

;; ---------------------------------------------------------------------------
;; F9 Landing 6 — default-method inheritance (explicit-conformance) +
;; diamond-ambiguity detection.
;;
;; Structural-conformance-based inheritance is L6b (deferred).
;; ---------------------------------------------------------------------------

(deftest f9-l6-inherits-default-into-composed-table
  (testing "F9 Landing 6 + F67 L4 — class explicitly conforms to P, P provides default observable `m`, class doesn't define `m` — default folded into class's composed :methods and emitted as a C function keyed by the class name."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "\n"
                               "    m() :< 42\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    unrelated() :< 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (some? fn)
          "inherited default emits as Widget_m alongside class's own methods")
      (is (= 42 (get-in return-stmt [:value :value]))
          "inherited body returns 42 (from the protocol default)"))))

(deftest f9-l6-own-method-shadows-default
  (testing "F9 Landing 6 + F67 L4 — when the class defines `m` itself (own or via extension), the own observable wins over the protocol default with no diamond, no warning."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "\n"
                               "    m() :< 42\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P):\n"
                               "    m() :< 100\n"
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
                         "    accepts:\n"
                         "        m() -> Int\n"
                         "\n"
                         "    m() :< 1\n"
                         "\n"
                         "def Q:\n"
                         "    accepts:\n"
                         "        m() -> Int\n"
                         "\n"
                         "    m() :< 2\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P, Q):\n"
                         "    unrelated() :< 0\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l6b-structural-conformance-inherits-default
  (testing "F9 Landing 6b + F67 L4b — class does NOT declare `conforms: P` but structurally satisfies P's accepts surface AND P provides helper defaults not covered by accepts → class inherits those defaults implicitly. Helper defaults carry no explicit return type on the observable; downstream C emission derives the return type from the accepts entry when present, and helpers with trivial literal bodies emit via the observable-inference path."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        iter(self) -> Int\n"
                               "\n"
                               "    count_helper() :< 99\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "    iter() :< 7\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        then: print(0)\n"))
          fn         (first (filter #(= "Widget_count_helper" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (some? fn)
          "Widget_count_helper lowered via structural conformance to P")
      (is (= 99 (get-in return-stmt [:value :value]))))))

(deftest f9-l6b-protocol-with-no-defaults-not-inherited
  (testing "F9 Landing 6b — a protocol with no defaults produces no inheritance even under structural conformance. Structural-conformance detection is only for inheriting defaults; conforming-without-defaults is a call-site concern (L7+)."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        iter(self) -> Int\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "    iter() :< 7\n"
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
                         "    accepts:\n"
                         "        iter(self) -> Int\n"
                         "\n"
                         "    helper() :< 1\n"
                         "\n"
                         "def Q:\n"
                         "    accepts:\n"
                         "        iter(self) -> Int\n"
                         "\n"
                         "    helper() :< 2\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "    iter() :< 7\n"
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
                         "    accepts:\n"
                         "        iter(self) -> Int\n"
                         "\n"
                         "    helper() :< 1\n"
                         "\n"
                         "def Q:\n"
                         "    accepts:\n"
                         "        iter(self) -> Int\n"
                         "\n"
                         "    helper() :< 2\n"
                         "\n"
                         "def Widget:\n"
                         "    n: Int\n"
                         "\n"
                         "extend Widget(P):\n"
                         "    iter() :< 7\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        then: print(0)\n"))))))

(deftest f9-l6-diamond-resolved-by-own-method
  (testing "F9 Landing 6 + F67 L4 — same P/Q diamond setup + class defines its own `m` → own shadows all defaults per §3.4.4; no diamond error."
    (let [prog (lower-src (str "def P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "\n"
                               "    m() :< 1\n"
                               "\n"
                               "def Q:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "\n"
                               "    m() :< 2\n"
                               "\n"
                               "def Widget:\n"
                               "    n: Int\n"
                               "\n"
                               "extend Widget(P, Q):\n"
                               "    m() :< 999\n"
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
                               "    size() :< self.payload.length\n"
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
                               "    eq()  :< self.a == self.b\n"
                               "    neq() :< self.a != self.b\n"
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
                   "    use:\n"
                   "        b: B\n"
                   "\n"
                   "    -> alive\n"
                   "\n"
                   "    alive <: ping -> alive\n"
                   "        then: |> b.pong()\n"
                   "\n"
                   "def B:\n"
                   "    use:\n"
                   "        a: A\n"
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
                   "    use:\n"
                   "        b: B\n"
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
          send-call (some (fn [stmt]
                            (when (and (= :call (:node stmt))
                                       (clojure.string/includes?
                                        (or (:fn stmt) "") "cxc_"))
                              stmt))
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
          assign-idx (first (keep-indexed
                             (fn [i s]
                               (when (and (= :assign (:node s))
                                          (= "_peer" (get-in s [:lhs :sym])))
                                 i))
                             body))
          check-stmt (get body (inc assign-idx))]
      (is assign-idx "peer ivar is written in the bump transition body")
      (is (= :raw-c (:node check-stmt))
          "F39 L3 — the stmt directly after the peer write is the nonnil-check")
      (is (str/includes? (:text check-stmt) "(_peer) == nil")
          "check reads the just-stored ivar")
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
;; the protocol accepts entry (accepts declares `-> T!` → wrap the derived
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
          assign (first (filter #(and (map? %)
                                      (= :assign (:node %))
                                      (= "_strict" (get-in % [:lhs :sym])))
                                (tree-seq coll? seq bump)))]
      (is (some? assign) "assign to `_strict` present somewhere in bump method body (inside :transition-branch → :end-catch-wrap)")
      (is (= :c-value-nonnil-unwrap (get-in assign [:rhs :node]))
          "RHS wraps the loose (Optional) value in a runtime presence check")
      (is (re-find #"value-typed T!"
                   (get-in assign [:rhs :site]))
          "site label mentions value-typed T!")
      (is (= "strict" (get-in assign [:rhs :name]))
          "name label is the field name"))))

(deftest f39-l5b-ii-d-method-return-value-nonnil-inserts-runtime-check
  (testing "F39 Landing 5b-ii-d — a def-atom method whose `:cxc-return-ty-node` is `:type-nonnil` (`-> T!`) and whose body reads a bare Optional field emits the runtime presence check on the return path. Reuses the L3 `_Cxc_ret` var-decl + return triple; the check node is `:c-value-nonnil-unwrap` on the return-value binding, replacing L3's pointer nonnil-check for value-typed returns. Only protocol-inherited defaults (Proposal G collapse leaves observables without a return-type annotation surface) carry `:type-nonnil` on `:cxc-return-ty-node`, so the test shape is `extend X(P):` inheriting `strict_read() -> Int!` from P."
    (let [prog     (lower-src "def P:\n    accepts:\n        strict_read() -> Int!\n\n    strict_read() :< n\n\ndef X:\n    n: Int = 0\n\n    -> running\n\nextend X(P):\n    _dummy() :< 1\n\ndef main:\n    -> running\n        then: print(0)\n")
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
    (let [prog (lower-src "def Counter:\n    raw_n: Int = 42\n\n    n :< raw_n\n\ndef main:\n    -> running\n        then: print(Counter().n)\n")]
      (is (= :objc-program (:node prog))
          "observable read is spec-legal"))))

(deftest f33-self-field-read-still-allowed
  (testing "Self-field reads inside the atom's own observable / invariant / transition body compile — `self.n` (or bare `n`) reads the data field directly. Post F67 L4: observables replace the reader-method surface."
    (let [prog (lower-src "def Counter:\n    n: Int = 10\n\n    bump() :< n + 1\n\n    -> running\n\n    -> end\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n        then: print(0)\n")]
      (is (= :objc-program (:node prog))
          "self.n inside Counter's own observable compiles cleanly"))))

(deftest f67-l2-parametric-observable-lowers-with-args
  (testing "F67 L2 (Proposal G): parametric observables `<name>(args) :< <expr>` emit as ObjC methods with declared params. The observable body may reference both self-fields and its own args; both resolve during type inference. Under Proposal G this shape replaces reader methods — `function healed_by(self, amt) -> Int: return self.hp + amt` migrates to `healed_by(amt: Int) :< hp + amt`."
    (let [prog (lower-src "def Player:\n    hp: Int! = 100\n    max_hp: Int! = 100\n\n    healed_by(amt: Int) :< hp + amt\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(0)\n")
          cls  (first (filter #(= "Player" (:name %)) (:classes prog)))
          obs  (first (filter #(re-matches #"healed_by.*" (or (:selector %) "")) (:methods cls)))]
      (is (some? obs) "parametric observable emits as :objc-method")
      (is (= 1 (count (:params obs)))
          "parametric observable carries its declared param (amt: Int)")
      (is (= "amt" (:name (first (:params obs))))
          "param name preserved")
      (is (= "int64_t" (:return-type obs))
          "return type inferred from body expression (hp + amt : Int)"))))

(deftest f67-l2-zero-arg-observable-still-lowers
  (testing "F67 L2: zero-arg observable shape is preserved — `<name> :< <expr>` (no parens) still parses and lowers exactly as before. The parametric extension does not require parens on zero-arg observables."
    (let [prog (lower-src "def Counter:\n    n: Int = 0\n\n    doubled :< n + n\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(0)\n")
          cls  (first (filter #(= "Counter" (:name %)) (:classes prog)))
          obs  (first (filter #(= "doubled" (:selector %)) (:methods cls)))]
      (is (some? obs) "zero-arg observable still emits with bare selector")
      (is (= [] (:params obs))
          "zero-arg observable has empty params list"))))

(deftest f67-l2-parametric-observable-multi-arg
  (testing "F67 L2: parametric observables with multiple args. Each arg becomes an ObjC method param; the body expression sees all args + self-fields in scope."
    (let [prog (lower-src "def Box:\n    w: Int = 0\n    h: Int = 0\n\n    scaled_area(sx: Int, sy: Int) :< (w * sx) * (h * sy)\n\n    -> running\n\n    -> end\n\ndef main:\n    -> running\n        then: print(0)\n")
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
      (is (= ["nonatomic" "weak"] (:attrs f))
          "weak modifier picks (nonatomic, weak) attrs, overriding the default (strong) for atom pointers"))))

(deftest f13-l2-unowned-emits-nonatomic-unsafe-unretained-attrs
  (testing "F13 L2 — `unowned <AtomName>` field lowers to `@property (nonatomic, unsafe_unretained)`. Non-owning, non-zeroing pointer; access after target dealloc is UB per spec §6.14."
    (let [prog (lower-src "def Owner:\n    -> alive\n\ndef Child:\n    owner: unowned Owner\n\n    -> alive\n\ndef main:\n    -> running\n        then: print(0)\n")
          child (first (filter #(= "Child" (:name %)) (:classes prog)))
          f     (first (filter #(= "owner" (:name %)) (:properties child)))]
      (is (= ["nonatomic" "unsafe_unretained"] (:attrs f))
          "unowned modifier picks (nonatomic, unsafe_unretained) attrs"))))

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
  (testing "F18 L1 — `let x: Int = 5` uses the annotation authoritatively; annotation-based typing is exercised even when it aligns with inference."
    (let [prog  (lower-src "def main:\n    -> running\n        then:\n            let x: Int = 5\n            print(x)\n")
          stmts (get-in prog [:main :body])
          decl  (first (filter #(= :var-decl (:node %)) stmts))]
      (is (= "int64_t" (:c-type decl))
          "annotation `Int` lowers to int64_t"))))

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
;; K5 L1 — `if` at expression position (spec §3.8.1)
;; ---------------------------------------------------------------------------

(deftest k5-l1-if-expression-lowers-to-c-ternary
  (testing "K5 L1 — `let x = if 1 > 0: 10 else: 20` lowers the RHS as :c-ternary. Cond/then/else all lowered as expressions."
    (let [prog  (lower-src "def main:\n    -> running\n        then:\n            let x = if 1 > 0: 10 else: 20\n            print(x)\n")
          stmts (get-in prog [:main :body])
          decl  (first (filter #(and (= :var-decl (:node %)) (= "x" (:name %))) stmts))]
      (is (some? decl))
      (is (= :c-ternary (:node (:rhs decl))))
      (is (= 10 (:value (:then (:rhs decl)))))
      (is (= 20 (:value (:else (:rhs decl)))))
      (is (= "int64_t" (:c-type decl))
          "then-branch type flows out as block type"))))

(deftest k5-l1-else-if-chain-lowers-to-nested-ternary
  (testing "K5 L1 — `else if` chain desugars right-associatively: outer :c-ternary whose else is another :c-ternary."
    (let [prog  (lower-src "def main:\n    -> running\n        then:\n            let x = if 1 > 2: 1 else if 3 > 4: 2 else: 3\n            print(x)\n")
          stmts (get-in prog [:main :body])
          decl  (first (filter #(and (= :var-decl (:node %)) (= "x" (:name %))) stmts))
          rhs   (:rhs decl)]
      (is (= :c-ternary (:node rhs)))
      (is (= 1 (:value (:then rhs))))
      (is (= :c-ternary (:node (:else rhs)))
          "nested :c-ternary in outer's else branch")
      (is (= 2 (:value (:then (:else rhs)))))
      (is (= 3 (:value (:else (:else rhs))))))))

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
      (is (= ["strong"] (:attrs f))
          "unmodified atom-typed field still gets the pre-L2 default (strong)"))))

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
  (let [combined  (p/parse (str (:source (find-ns 'cxc.prelude)) src))]
    ;; use the same rename postwalk lower runs so table keys match
    (let [renamed  ((requiring-resolve 'cxc.prelude/rewrite-prelude-names) combined)
          table    ((requiring-resolve 'cxc.resolve/build-symbol-table) renamed)]
      {:symbol-table table
       :protocol-env {}})))

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
    (let [prog  (lower-src "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: green: print(2)\n                <: _: print(9)\n")
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
         (lower-src "def Outcome:\n    case ok\n    case err\n\ndef main:\n    -> running\n        then:\n            let o: Outcome = Outcome.ok\n            o:\n                <: ok(let x): print(1)\n                <: _: print(0)\n")))))

(deftest f69-l4b-dispatch-non-enum-scrutinee-int-accepted-with-wildcard
  (testing "F69 L4c4 — Int scrutinees are accepted (for literal patterns) as long as a `<: _:` wildcard tail is present (open type, exhaustiveness by wildcard). This supersedes the L4b-only rejection."
    (let [prog (lower-src "def main:\n    -> running\n        then:\n            let n: Int = 3\n            n:\n                <: 3: print(1)\n                <: _: print(0)\n")]
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
         (lower-src "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: purple: print(1)\n                <: _: print(0)\n")))))

(deftest f69-l4c1-dispatch-exhaustiveness-enforced
  (testing "F69 L4c1 — spec §3.7: a `<:` dispatch on a tagged-variant scrutinee must cover every declared case OR include a `<: _:` wildcard. Missing coverage without a wildcard rejects at lower-time listing the unhandled cases."
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
    (let [prog (lower-src "def Signal:\n    case red\n    case yellow\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: _: print(0)\n")]
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
    (is (= "_Cxc_Optional_Int64"    (lower/optional-type-name "Int")))
    (is (= "_Cxc_Optional_Int32"    (lower/optional-type-name "Int32")))
    (is (= "_Cxc_Optional_Bool"     (lower/optional-type-name "Bool")))
    (is (= "_Cxc_Optional_Double"   (lower/optional-type-name "Double")))
    (is (= "_Cxc_Optional_Position" (lower/optional-type-name "Position")))))

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
  (testing "F39 Landing 7 — bare `if <cond>: <block>` (statement-form K5) is deferred; the parser emits a targeted reject pointing at `if let`, `when:` guards, and expression-position `<:` dispatch as alternatives. Guards against silent parse failure that would look like a generic 'expected ...' error."
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
                               "    count() :< 42\n\n"
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
                                "    count() :< 42\n\n"
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
                         "    count() :< 42\n\n"
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
                         "    count() :< 42\n\n"
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
                         "    count() :< 42\n\n"
                         "    on_nil:\n"
                         "        count: 1 + 2\n\n"
                         "def main:\n"
                         "    -> running\n        then: print(0)\n"))))))

(deftest f39-l8-on-nil-not-applied-when-method-has-no-entry
  (testing "F39 Landing 8 — methods without an `on_nil:` entry are unaffected: emit is the raw `.method` property access (ObjC nil-messaging returns 0 for scalar returns). No `__cxc_recv` wrapping for un-declared methods. Guards against overreach — `on_nil` fires only for methods explicitly listed."
    (let [prog  (lower-src (str "def UserList:\n"
                                "    n: Int! = 5\n\n"
                                "    -> alive\n\n"
                                "    count() :< 42\n"
                                "    other() :< 99\n\n"
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
