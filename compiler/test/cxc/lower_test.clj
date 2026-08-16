(ns cxc.lower-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cxc.parse :as p]
            [cxc.lower :as lower]
            [cxc.emit :as emit]))

(defn- lower-src [src] (lower/lower (p/parse src)))

(defn- main-work-body
  "Return the body of CxcMain's cxc_stateInit — the effective site of
   `def main: -> running do: <stmts>` under main-as-atom (Steps 16/17).
   Falls back to the raw main body for programs without ObjC classes."
  [prog]
  (if-let [cxc-main (first (filter #(= "CxcMain" (:name %)) (:classes prog)))]
    (:body (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cxc-main))))
    (get-in prog [:main :body])))

(deftest hello-world-program-lowers
  (testing "def main: -> running do: print(\"hello, world\") lowers cleanly. F65 — Foundation is always included because String is NSString *; the string literal lowers as :objc-str (`@\"...\"`) rather than :str (`\"...\"`)."
    (let [prog (lower-src "def main:
    -> running
        do: print(\"hello, world\")
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
  (is (thrown? clojure.lang.ExceptionInfo (lower-src "record R:\n    data:\n        x: Int\n"))))

(deftest unsupported-call-fails-nyi
  (testing "any call other than print raises :not-yet-implemented"
    (try
      (lower-src "def main:\n    -> running\n        do: unknown_fn(1)\n")
      (is false "expected NYI throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-yet-implemented (:kind (ex-data e))))))))

(deftest arithmetic-in-print-int
  (testing "int + int uses %lld format (Phase D F36a — Int aliased to Int64; printf format widens accordingly); binop lowers with left/right"
    (let [prog (lower-src "def main:\n    -> running\n        do: print(3 + 4 * 2)\n")
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
    (let [prog (lower-src "def main:\n    -> running\n        do: print(1.5 + 2)\n")
          call (first (get-in prog [:main :body]))]
      (is (= "%f\n" (get-in call [:args 0 :value]))))))

(deftest record-lowered-to-c-struct
  (testing "top-level record → :c-struct with resolved C types per field"
    (let [prog (lower-src "record V:\n    data:\n        x: Int\n        y: Float\n\ndef main:\n    -> running\n        do: print(1)\n")
          s    (first (:structs prog))]
      (is (= :c-struct (:node s)))
      (is (= "V" (:name s)))
      (is (= [{:name "x" :c-type "int64_t"}
              {:name "y" :c-type "float"}]
             (:fields s))))))

(deftest record-construction-with-named-args
  (testing "V(x: 3, y: 4) lowers to :struct-lit with named fields"
    (let [prog (lower-src "record V:\n    data:\n        x: Int\n        y: Int\n\ndef main:\n    -> running\n        do: print(V(x: 3, y: 4).x)\n")
          call (first (get-in prog [:main :body]))
          field-access (get-in call [:args 1])]
      (is (= :field (:node field-access)))
      (is (= "x" (:field field-access)))
      (let [ctor (:target field-access)]
        (is (= :struct-lit (:node ctor)))
        (is (= "V" (:type ctor)))
        (is (= [{:name "x" :value {:node :int :value 3}}
                {:name "y" :value {:node :int :value 4}}]
               (:fields ctor)))))))

(deftest nested-record-field-typing
  (testing "field access chained through a record field propagates the inner record's field type"
    (let [prog (lower-src "record Point:\n    data:\n        x: Float\n        y: Float\n\nrecord Line:\n    data:\n        start: Point\n        stop:  Point\n\ndef main:\n    -> running\n        do: print(Line(start: Point(x: 1.0, y: 2.0), stop: Point(x: 3.0, y: 4.0)).start.x)\n")
          call (first (get-in prog [:main :body]))]
      (is (= "%f\n" (get-in call [:args 0 :value]))))))

(deftest missing-field-in-ctor-fails-nyi
  (testing "V(x: 1) when V requires x + y (no default on y) — fail-nyi"
    (try
      (lower-src "record V:\n    data:\n        x: Int\n        y: Int\n\ndef main:\n    -> running\n        do: print(V(x: 1).x)\n")
      (is false "expected NYI throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-yet-implemented (:kind (ex-data e))))))))

(deftest record-defaults-fill-omitted-fields
  (testing "omitted field with a declared default is filled from the default"
    (let [prog (lower-src "record V:\n    data:\n        x: Int = 1\n        y: Int = 2\n\ndef main:\n    -> running\n        do: print(V(x: 10).y)\n")
          call (first (get-in prog [:main :body]))
          ctor (get-in call [:args 1 :target])]
      (is (= :struct-lit (:node ctor)))
      (is (= [{:name "x" :value {:node :int :value 10}}    ; overridden
              {:name "y" :value {:node :int :value 2}}]    ; default kicked in
             (:fields ctor))))))

;; F40 Landing 1 — call-site + decl-site validation (spec §3.8.5)

(deftest f40-record-ctor-duplicate-named-arg-rejected
  (testing "V(x: 1, x: 2) — duplicate named arg fails, not silently collapsed"
    (let [ex (try
               (lower-src "record V:\n    data:\n        x: Int\n        y: Int\n\ndef main:\n    -> running\n        do: print(V(x: 1, x: 2, y: 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"duplicate named arg" (.getMessage ex))))))

(deftest f40-record-ctor-unknown-named-arg-rejected
  (testing "V(bogus: 1) — unknown named arg fails, not silently ignored"
    (let [ex (try
               (lower-src "record V:\n    data:\n        x: Int\n        y: Int\n\ndef main:\n    -> running\n        do: print(V(x: 1, y: 2, bogus: 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"unknown named arg" (.getMessage ex)))
      (is (re-find #"bogus" (.getMessage ex))))))

(deftest f40-record-ctor-surplus-positional-rejected
  (testing "V(1, 2, 3) when V has 2 fields — surplus positional fails"
    (let [ex (try
               (lower-src "record V:\n    data:\n        x: Int\n        y: Int\n\ndef main:\n    -> running\n        do: print(V(1, 2, 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"too many positional" (.getMessage ex))))))

(deftest f40-record-ctor-positional-and-named-collision-rejected
  (testing "V(1, x: 2) — same param supplied positionally AND by name fails"
    (let [ex (try
               (lower-src "record V:\n    data:\n        x: Int\n        y: Int\n\ndef main:\n    -> running\n        do: print(V(1, x: 2, y: 3).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"both positionally and by name" (.getMessage ex))))))

(deftest f40-record-fields-defaults-must-follow-required
  (testing "record V { x: Int = 1, y: Int } — defaulted before required is a compile error"
    (let [ex (try
               (lower-src "record V:\n    data:\n        x: Int = 1\n        y: Int\n\ndef main:\n    -> running\n        do: print(V(x: 10, y: 20).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"defaulted param" (.getMessage ex))))))

(deftest f40-atom-ctor-duplicate-named-arg-rejected
  (testing "duplicate named arg on atom ctor fails"
    (let [ex (try
               (lower-src "def Widget(a: Int, b: Int):\n    data:\n        n: Int = a\n\ndef main:\n    def w: Widget(a: 1, a: 2, b: 3)\n\n    -> running\n        do: print(0)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"duplicate named arg" (.getMessage ex))))))

(deftest f40-atom-ctor-unknown-named-arg-rejected
  (testing "unknown named arg on atom ctor fails"
    (let [ex (try
               (lower-src "def Widget(a: Int):\n    data:\n        n: Int = a\n\ndef main:\n    def w: Widget(a: 1, ghost: 2)\n\n    -> running\n        do: print(0)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"unknown named arg" (.getMessage ex))))))

(deftest def-atom-lowers-to-objc-class
  (testing "def-atom with data:-only → :objc-class with @property ivars + -init defaults"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 42\n\ndef main:\n    def c: Counter\n\n    -> running\n        do: print(0)\n")
          cls  (first (:classes prog))]
      (is (= :objc-class (:node cls)))
      (is (= "Counter" (:name cls)))
      (is (= "NSObject" (:superclass cls)))
      (is (= [{:name "phase"            :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "own_queue"        :c-type "dispatch_queue_t" :attrs ["nonatomic" "strong"]}
              {:name "cxc_owned_resources" :c-type "NSMutableArray *" :attrs ["nonatomic" "strong"]}
              {:name "parent"           :c-type "id"               :attrs ["nonatomic" "weak"]}
              {:name "parent_slot_name" :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "n"                :c-type "int64_t"          :attrs ["assign"]}]
             (:properties cls)))
      (is (= [{:name "kCounter_phase_init" :value "init"}
              {:name "kCounter_phase_end"  :value "end"}]
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
                 :rhs  {:node :name :sym "kCounter_phase_init"}}]
               (:body setup)))
        (is (= [{:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :int :value 42}}]
               (:body state)))))))

(deftest def-atom-ctor-lowers-to-alloc-init
  (testing "Counter in def-slot position → :objc-alloc-init"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 42\n\ndef main:\n    def c: Counter\n\n    -> running\n        do: print(0)\n")
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
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 42\n\ndef main:\n    def c: Counter\n\n    -> running\n        do: print(0)\n")]
      ;; Foundation for ObjC; stdlib for cxc_shutdown() (needed by every
      ;; atom class's transitionToEnd cascade infrastructure).
      (is (some #{"<Foundation/Foundation.h>"} (:includes prog)))
      (is (some #{"<stdlib.h>"} (:includes prog)))
      (is (true? (get-in prog [:main :autorelease?]))))))

(deftest hello-world-includes-foundation
  (testing "F65 — every program includes Foundation because string literals are NSString; autoreleasepool wraps main"
    (let [prog (lower-src "def main:\n    -> running\n        do: print(\"hi\")\n")]
      (is (some #{"<Foundation/Foundation.h>"} (:includes prog))
          "F65 — Foundation always included; NSString literal requires it")
      (is (true? (get-in prog [:main :autorelease?]))
          "F65 — autoreleasepool wraps main since NSString literals allocate"))))

(deftest wildcard-on-enter-inlined-into-init
  (testing "initial-phase wildcard on-enter → its then:/do: append to :init-body after data defaults"
    (let [prog (lower-src "def Ticker:\n    data:\n        n: Int = 0\n\n    -> incremented\n        then: n => n + 1\n\ndef main:\n    def t: Ticker\n\n    -> running\n        do: print(0)\n")
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
                        :right {:node :int  :value 1}}}]
               (:body state)))))))

(deftest non-initial-wildcard-inlines-into-transitions-that-enter-that-phase
  (testing "`-> dead do: print(0)` inlines into `alive <: die -> dead` branch body (phase change, not self-loop)"
    (let [prog (lower-src "def Enemy:
    data:
        hp: Int = 3

    -> alive

    -> dead
        do: print(0)

    alive <: die -> dead

def main:
    -> running
        do: print(1)
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
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 0\n\ndef main:\n    -> running\n        do: print(1)\n")
          cls  (first (:classes prog))
          sels (set (map :selector (:methods cls)))]
      (is (contains? sels "cxc_installParent:inSlot:"))
      (is (contains? sels "transitionToEnd"))
      (is (contains? sels "nilSlot:")))))

(deftest transition-with-target-end-calls-transitionToEnd
  (testing "`alive <: die -> end` branch body is `[self transitionToEnd]`"
    (let [prog (lower-src "def Counter:
    data:
        n: Int = 0

    -> alive

    alive <: die -> end

def main:
    -> running
        do: print(1)
")
          cls  (first (:classes prog))
          die  (first (filter #(= "die" (:selector %)) (:methods cls)))
          branch (first (:body die))]
      (is (some? branch))
      (is (= 1 (count (:body branch))))
      (is (= "transitionToEnd" (get-in (first (:body branch)) [:selector]))))))

(deftest parent-installs-back-ref-on-nested-slot-child
  (testing "parent's cxc_stateInit calls -cxc_installParent:inSlot: on each nested slot after construction"
    (let [prog (lower-src "def Cell:\n    data:\n        n: Int = 0\n\ndef Container:\n    def cell: Cell\n\ndef main:\n    -> running\n        do: print(1)\n")
          container (first (filter #(= "Container" (:name %)) (:classes prog)))
          state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods container)))
          install-msg (second (:body state))]     ; [_cell = Cell(), install-parent-msg]
      (is (= :objc-msg (:node install-msg)))
      (is (= "cxc_installParent:inSlot:" (:selector install-msg)))
      (is (= "cell" (get-in install-msg [:args 1 :value]))))))

(deftest f31-f35-l4-atom-typed-data-field-becomes-slot
  (testing "F31/F35 Landing 4 — an atom-typed `data:` field with a default (eager Foo() or nil) participates in the slot machinery: parent installs back-ref at ctor time and cascades on -transitionToEnd. Retires the pre-L4 gap where only invented `def slot: Type` nested-atoms received back-refs. Spec-canonical `data:` form now matches (§1.11)."
    (let [prog (lower-src "def Helper:\n    -> alive\n\ndef X:\n    data:\n        h: Helper = Helper()\n\ndef main:\n    def x: X\n    -> running\n        do: print(0)\n")
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
    (let [prog (lower-src "def Helper:\n    -> alive\n\ndef X:\n    data:\n        h: Helper = nil\n\ndef main:\n    def x: X\n    -> running\n        do: print(0)\n")
          x-cls (first (filter #(= "X" (:name %)) (:classes prog)))
          state (first (filter #(= "cxc_stateInit" (:selector %)) (:methods x-cls)))
          install-msg (second (:body state))]
      (is (= :objc-msg (:node install-msg))
          "install-parent still emitted for nil-default slot")
      (is (= "cxc_installParent:inSlot:" (:selector install-msg))
          "runtime nil-messaging swallows the call at zero cost"))))

(deftest f31-f35-l4-f41-synthesized-field-not-a-slot
  (testing "F31/F35 Landing 4 — a no-default atom-typed data field is F41-sugared to a required ctor param (caller-provided reference). Since ownership is ambiguous, it is NOT treated as an owned slot — no back-ref, no cascade. Preserves the caller-supplies-shared-reference semantics."
    (let [prog (lower-src "def Helper:\n    -> alive\n\ndef X:\n    data:\n        h: Helper\n\ndef main:\n    def hh: Helper\n    def x: X(h: hh)\n    -> running\n        do: print(0)\n")
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
    data:
        n: Int = 0

    -> alive
        do: print(999)

    alive <: bump -> alive
        then: n => n + 1

def main:
    -> running
        do: print(1)
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

(deftest record-method-lowers-to-c-function
  (testing "record with a method → :c-function under :functions with mangled name and self as first param"
    (let [prog (lower-src "record V:\n    data:\n        x: Float\n        y: Float\n\n    methods:\n        function plus(self, other: V) -> V:\n            return V(x: self.x + other.x, y: self.y + other.y)\n\ndef main:\n    -> running\n        do: print(V(x: 1.0, y: 2.0).plus(V(x: 3.0, y: 4.0)).x)\n")
          f    (first (:functions prog))]
      (is (= :c-function (:node f)))
      (is (= "V_plus" (:name f)))
      (is (= "V" (:return-type f)))
      (is (= [{:name "self" :c-type "V"} {:name "other" :c-type "V"}] (:params f)))
      (is (= 1 (count (:body f))))
      (is (= :return (get-in f [:body 0 :node]))))))

(deftest atom-ctor-params-lower-to-init-with
  (testing "def-atom with ctor params → :init-params on class + :objc-init-with at call site"
    (let [prog (lower-src "def Counter(initial: Int = 0):\n    data:\n        n: Int = initial\n\ndef main:\n    def c: Counter(initial: 42)\n\n    -> running\n        do: print(0)\n")
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
        (is (= [{:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :name :sym "initial"}}]
               (:body state))))
      (is (= :objc-init-with (:node ctor)))
      (is (= "Counter" (:class ctor)))
      (is (= [{:name "initial" :value {:node :int :value 42}}] (:args ctor))))))

(deftest atom-typed-field-defaults-to-nil-and-lowers-as-pointer
  (testing "F39 L1 — reference-typed atom fields are already nil-allowed (spec §3.6.1) — no `?` needed. `other: Counter = nil` lowers to `Counter *` (ARC strong)."
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 5\n        other: Counter = nil\n\ndef main:\n    -> running\n        do: print(1)\n")
          cls  (first (:classes prog))]
      (is (= [{:name "phase"            :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "own_queue"        :c-type "dispatch_queue_t" :attrs ["nonatomic" "strong"]}
              {:name "cxc_owned_resources" :c-type "NSMutableArray *" :attrs ["nonatomic" "strong"]}
              {:name "parent"           :c-type "id"               :attrs ["nonatomic" "weak"]}
              {:name "parent_slot_name" :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "n"                :c-type "int64_t"          :attrs ["assign"]}
              {:name "other"            :c-type "Counter *"        :attrs ["strong"]}]
             (:properties cls)))
      (let [state-init (first (filter #(= "cxc_stateInit" (:selector %)) (:methods cls)))]
        (is (= {:node :assign
                :lhs  {:node :name :sym "_other"}
                :rhs  {:node :nil}}
               ;; cxc_stateInit body: [_n=5, _other=nil]
               (second (:body state-init))))))))

(deftest atom-method-lowers-to-objc-method
  (testing "def-atom with methods: → :objc-method under class :methods"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function bump(self) -> Int:\n            return self.n + 1\n\ndef main:\n    -> running\n        do: print(Counter().bump())\n")
          cls  (first (:classes prog))
          m    (first (filter #(= "bump" (:selector %)) (:methods cls)))]
      (is (= :objc-method (:node m)))
      (is (= "bump" (:selector m)))
      (is (= "int64_t" (:return-type m)))
      (is (empty? (:params m))))))

(deftest atom-method-call-lowers-to-objc-msg
  (testing "counter.bump() → :objc-msg with the receiver as :recv and bare selector"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function bump(self) -> Int:\n            return self.n + 1\n\ndef main:\n    -> running\n        do: print(Counter().bump())\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])]
      (is (= :objc-msg (:node msg)))
      (is (= "bump" (:selector msg)))
      (is (= :objc-alloc-init (get-in msg [:recv :node])))
      (is (empty? (:args msg))))))

(deftest atom-method-multiarg-selector
  (testing "plus(self, other: Int) → selector `plusWithother:` and matching call"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function plus(self, other: Int) -> Int:\n            return self.n + other\n\ndef main:\n    -> running\n        do: print(Counter().plus(5))\n")
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
               (lower-src "record V:\n    data:\n        x: Int = 1 + 1\n\ndef main:\n    -> running\n        do: print(V(x: 10).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-record-field-default-call-rejected
  (testing "record V { x: Int = foo() } — call as default fails"
    (let [ex (try
               (lower-src "record V:\n    data:\n        x: Int = foo()\n\ndef main:\n    -> running\n        do: print(V(x: 10).x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-atom-ctor-default-computed-rejected
  (testing "def Widget(x: Int = 1 + 1) — arithmetic expr as ctor default fails"
    (let [ex (try
               (lower-src "def Widget(x: Int = 1 + 1):\n    data:\n        n: Int = x\n\ndef main:\n    -> running\n        do: print(Widget().n)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-method-default-computed-rejected
  (testing "record method with `factor: Int = 2 * 2` default fails"
    (let [ex (try
               (lower-src "record V:\n    data:\n        x: Int\n    methods:\n        function scaled(self, factor: Int = 2 * 2) -> Int:\n            return self.x * factor\n\ndef main:\n    -> running\n        do: print(V(x: 5).scaled())\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"not a compile-time constant" (.getMessage ex))))))

(deftest f40-neg-literal-default-accepted
  (testing "record V { x: Int = -5 } — unary neg on literal is const"
    (let [prog (lower-src "record V:\n    data:\n        x: Int = -5\n\ndef main:\n    -> running\n        do: print(V().x)\n")
          call (first (main-work-body prog))
          ctor (get-in call [:args 1 :target])]
      (is (= :struct-lit (:node ctor)))
      (is (= 1 (count (:fields ctor))))
      (is (= "x" (get-in ctor [:fields 0 :name])))
      ;; Lower rewrites the parser's :neg wrapper to :unary op "-"; the
      ;; const-check happens on the parser AST and accepts :neg.
      (let [v (get-in ctor [:fields 0 :value])]
        (is (contains? #{:neg :unary} (:node v)))
        (is (= 5 (get-in v [:expr :value])))))))

;; F40 Landing 2 — named args + default insertion on method calls.

(deftest f40-atom-method-accepts-named-args
  (testing "counter.plus(other: 7) — named arg on atom method resolves"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function plus(self, other: Int) -> Int:\n            return self.n + other\n\ndef main:\n    -> running\n        do: print(Counter().plus(other: 7))\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])]
      (is (= "plusWithother:" (:selector msg)))
      (is (= 1 (count (:args msg))))
      (is (= 7 (get-in msg [:args 0 :value]))))))

(deftest f40-atom-method-defaults-fill-when-omitted
  (testing "counter.plus() with other defaulted uses default"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function plus(self, other: Int = 3) -> Int:\n            return self.n + other\n\ndef main:\n    -> running\n        do: print(Counter().plus())\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])]
      (is (= "plusWithother:" (:selector msg)))
      (is (= 1 (count (:args msg))))
      (is (= 3 (get-in msg [:args 0 :value]))))))

(deftest f40-atom-method-named-out-of-order-reorders
  (testing "counter.plus3(c: 3, a: 1, b: 2) — reordered to decl order"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 0\n\n    methods:\n        function plus3(self, a: Int, b: Int, c: Int) -> Int:\n            return self.n + a + b + c\n\ndef main:\n    -> running\n        do: print(Counter().plus3(c: 3, a: 1, b: 2))\n")
          call (first (main-work-body prog))
          msg  (get-in call [:args 1])
          arg-values (mapv :value (:args msg))]
      (is (= [1 2 3] arg-values)))))

(deftest f40-record-method-defaults-fill-when-omitted
  (testing "record method with param default inserts default at call site"
    (let [prog (lower-src "record V:\n    data:\n        x: Int\n    methods:\n        function scaled(self, factor: Int = 4) -> Int:\n            return self.x * factor\n\ndef main:\n    -> running\n        do: print(V(x: 5).scaled())\n")
          call (first (main-work-body prog))
          inner (get-in call [:args 1])]
      (is (= :call (:node inner)))
      (is (= "V_scaled" (:fn inner)))
      ;; args = [self, factor]
      (is (= 2 (count (:args inner))))
      (is (= 4 (get-in inner [:args 1 :value]))))))

(deftest f40-method-unknown-named-arg-rejected
  (testing "atom method with unknown named arg fails"
    (let [ex (try
               (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function plus(self, other: Int) -> Int:\n            return self.n + other\n\ndef main:\n    -> running\n        do: print(Counter().plus(bogus: 5))\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected exception")
      (is (= :not-yet-implemented (:kind (ex-data ex))))
      (is (re-find #"unknown named arg" (.getMessage ex))))))

;; F27 semantic once-only — chained comparison lowers to :chained-cmp;
;; emit renders as GNU stmt-expr binding each interior operand once.

(deftest f27-emit-renders-gnu-stmt-expr
  (testing "when: -10 <= val <= 10 emits a GNU stmt-expr binding _val once"
    (let [prog (lower-src "def main:\n    data:\n        val: Int = 5\n\n    -> running\n\n    running <: bump -> self\n        when: -10 <= val <= 10\n        then: val => val + 1\n")
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
    (let [prog (lower-src "def main:\n    -> running\n        do: print(clamp(15, 0, 100))\n")
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
    (let [prog (lower-src "def main:\n    -> running\n        do: print(1 == 2 and 3 == 3 or 4 == 4)\n")
          call (first (get-in prog [:main :body]))
          expr (get-in call [:args 1])]
      ;; Left-assoc: (((1==2) && (3==3)) || (4==4))
      (is (= "||" (:op expr)))
      (is (= "&&" (get-in expr [:left :op]))))))

(deftest is-nil-emits-pointer-eq
  (testing "`x is nil` → `x == nil` at emit layer (pointer identity in ObjC)"
    (let [prog (lower-src "def Counter:\n    data:\n        raw_other: Counter = nil\n\n    other :< raw_other\n\ndef main:\n    -> running\n        do: print(Counter().other is nil)\n")
          call (first (main-work-body prog))
          expr (get-in call [:args 1])]
      (is (= :binop (:node expr)))
      (is (= "==" (:op expr)))
      (is (= :nil (get-in expr [:right :node]))))))

(deftest enum-lowers-to-c-enum-with-mangled-cases
  (testing "enum decl → :c-enum under :enums; Key.arrow_down → C constant `Key_arrow_down`"
    (let [prog (lower-src "enum Key:\n    arrow_up\n    arrow_down\n\ndef main:\n    -> running\n        do: print(Key.arrow_down)\n")
          e    (first (:enums prog))
          call (first (get-in prog [:main :body]))]
      (is (= :c-enum (:node e)))
      (is (= "Key" (:name e)))
      (is (= ["arrow_up" "arrow_down"] (:cases e)))
      (is (= "%d\n" (get-in call [:args 0 :value])))
      (is (= {:node :name :sym "Key_arrow_down"} (get-in call [:args 1]))))))

(deftest nullable-non-atom-fails-nyi
  (testing "T? on a primitive or record has no lowering path yet — fail-nyi"
    (try
      (lower-src "def X:\n    data:\n        n: Int? = nil\n\ndef main:\n    -> running\n        do: print(1)\n")
      (is false "expected NYI throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-yet-implemented (:kind (ex-data e))))))))

(deftest atom-ctor-params-default-fills-when-omitted
  (testing "Counter() with no args uses ctor-param defaults"
    (let [prog (lower-src "def Counter(initial: Int = 5):\n    data:\n        n: Int = initial\n\ndef main:\n    def c: Counter\n\n    -> running\n        do: print(0)\n")
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
  (testing "v.plus(other) → :call {:fn V_plus :args [v other]}"
    (let [prog (lower-src "record V:\n    data:\n        x: Float\n        y: Float\n\n    methods:\n        function plus(self, other: V) -> V:\n            return V(x: self.x + other.x, y: self.y + other.y)\n\ndef main:\n    -> running\n        do: print(V(x: 1.0, y: 2.0).plus(V(x: 3.0, y: 4.0)).x)\n")
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
    data:
        n: Int = 0

    -> alive

    alive <: bump -> alive
        then: n => n + 1

def main:
    -> running
        do: print(1)
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
                {:node :objc-msg
                 :recv {:node :name :sym "self"}
                 :selector "checkInvariants"
                 :args []}]
               (:body wrap)))))))

(deftest message-triggered-transition-with-phase-change-emits-phase-assign
  (testing "`alive <: die -> dead` sets _phase to kA_phase_dead inside the branch body"
    (let [prog (lower-src "def Enemy:
    data:
        hp: Int = 3

    -> alive

    alive <: die -> dead
        do: print(0)

def main:
    -> running
        do: print(1)
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
    data:
        hp: Int = 3

    -> alive

    alive <: die -> dying
        then: hp => 0
    dying <: die -> dead

def main:
    -> running
        do: print(1)
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
    data:
        hp: Int = 10

    -> alive

    alive <: take_damage when: hp > 0 -> alive
        when: hp > 0
        then: hp => hp - 1

def main:
    -> running
        do: print(1)
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
    def s: Sidekick
    data:
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
  (testing "Audit F26: spec §1.11 line 1281 — `then:` on the wildcard `-> end` form is a compile error (the atom's state is about to be discarded, so field updates are meaningless). Uses an accompanying dummy atom so main is atom-promoted and the end-transition path runs. Positive semantic-fix cases (specific transition to end with `do:` inlining before -transitionToEnd, and `then:` rejection on specific) exercise through the existing end-to-end suite; explicit regression scaffolding for those would need multi-atom message wiring beyond what parse+lower can construct without the prelude."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`then:` on `-> end` wildcard .* compile error"
         (lower-src "def Sidekick:
    -> running

def main:
    def s: Sidekick
    data:
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
        do: print(1)
")))
    ;; F50 fix: any phase name is accepted.
    (is (some? (lower-src "def main:
    -> playing
        do: print(1)
")))
    (is (some? (lower-src "def main:
    -> tick
        do: print(1)
")))))

(deftest width-variants-preserve-c-type-across-observable-return
  (testing "Phase D F36a Landing 1b — width variants (`[:int 32]`/`[:uint 8]`/`[:float 32]`) carry their C type in metadata so `type-kw->c-type` picks the correct width for observable return types. Prior to 1b, all integer widths collapsed to `int64_t` at observable boundary regardless of the source width."
    (let [prog (lower-src "def Counter:
    data:
        n: UInt8 = 0
    small_next :< n

def main:
    -> running
        do: print(1)
")
          cls  (first (filter #(= "Counter" (:name %)) (:classes prog)))
          obs  (first (filter #(= "small_next" (:selector %)) (:methods cls)))]
      (is (some? obs) "observable emitted as an :objc-method under :methods")
      (is (= "uint8_t" (:return-type obs))
          "observable that reads a UInt8 field returns uint8_t, not int64_t"))))

(deftest sized-numeric-atoms-lower-to-correct-c-types
  (testing "Phase D F36a Landing 1a — spec §3.1 primitive substrate. Sized atoms UInt8/16/32/64, Int8/16/32/64, Double flow through primitive-c-type to their exact C widths. Int aliased to Int64. Float is 32-bit (fixes v0.11 lowering bug — was double)."
    (let [prog (lower-src "record Widths:
    data:
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
        do: print(1)
")
          widths (first (filter #(= "Widths" (:name %)) (:structs prog)))
          field-c-type (fn [n] (-> (filter #(= n (:name %)) (:fields widths))
                                   first :c-type))]
      (is (some? widths) "Widths struct emitted")
      (is (= "uint8_t"  (field-c-type "u8")))
      (is (= "uint16_t" (field-c-type "u16")))
      (is (= "uint32_t" (field-c-type "u32")))
      (is (= "uint64_t" (field-c-type "u64")))
      (is (= "int8_t"   (field-c-type "i8")))
      (is (= "int16_t"  (field-c-type "i16")))
      (is (= "int32_t"  (field-c-type "i32")))
      (is (= "int64_t"  (field-c-type "i64")))
      (is (= "int64_t"  (field-c-type "n"))  "Int alias for Int64")
      (is (= "float"    (field-c-type "f"))  "Float is 32-bit IEEE 754 (fix)")
      (is (= "double"   (field-c-type "d"))  "Double is 64-bit IEEE 754")
      (is (= "int"      (field-c-type "b"))  "Bool unchanged — future work"))))

(deftest non-numeric-substrate-lowers-to-correct-c-types
  (testing "Phase D F36a Landing 3a — spec §3.1 non-numeric substrate (Unit/Never/Any/Bytes). Bytes and Any resolve as reference-typed field storage; Unit and Never resolve as `void` return types. Char is deferred pending grapheme cluster design; `()` literal syntax + Any runtime dispatch are follow-on landings."
    (let [prog (lower-src "record NonNumeric:
    data:
        payload: Bytes
        opaque:  Any

    methods:
        function noop(self)  -> Unit:
            return self.opaque
        function fatal(self) -> Never:
            return self.opaque

def main:
    -> running
        do: print(1)
")
          rec  (first (filter #(= "NonNumeric" (:name %)) (:structs prog)))
          field-c-type (fn [n] (-> (filter #(= n (:name %)) (:fields rec))
                                   first :c-type))
          fn-return-type (fn [n] (-> (filter #(= n (:name %)) (:functions prog))
                                     first :return-type))]
      (is (some? rec) "NonNumeric struct emitted")
      (is (= "NSMutableData *" (field-c-type "payload"))
          "Bytes → NSMutableData * (reference-shaped, §3.10.4)")
      (is (= "id" (field-c-type "opaque"))
          "Any → id (dynamic dispatch boundary, §3.1.3)")
      (is (= "void" (fn-return-type "NonNumeric_noop"))
          "Unit return → void (§3.1.1 — one-value type)")
      (is (= "void" (fn-return-type "NonNumeric_fatal"))
          "Never return → void (§3.1.1 — divergent; _Noreturn attribute is a follow-on)"))))

(deftest pre-store-invariant-check-fires-before-field-assignment
  (testing "Phase D F49 Landing A — invariant enforcement runs on the SOURCE VALUE (ctor param or default expression) before any narrowing store can truncate. Pre-store check statements are prepended to cxc_stateInit body so the abort fires before `_field = <source>;`. Distinguishing label (`at construction (source value violates)`) points diagnostic at pre-store site.

  Unblocks F36 Landing 2a (picker) — narrowed storage (e.g. `Age.value` as `uint8_t` when invariant is `0 <= value <= 150`) cannot silently accept `Age(value: 300)` (which would store 44 = 300 & 0xff and then pass a post-store check on 44)."
    (let [prog (lower-src "def Bounded:
    data:
        v: Int = 42
    invariants:
        0 <= v
        v <= 150

def main:
    -> running
        do: print(1)
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
    data:
        v: Int = 42
    invariants:
        0 <= v
        v <= 150

def main:
    -> running
        do: print(1)
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

(deftest user-method-body-wrapped-with-entry-exit-invariant-checks
  (testing "Phase D F49 Landing C — spec §3.4.2 requires invariants to hold before/after each method. User method bodies now wrap with `[self checkInvariants]` calls at entry and exit. Trailing `return <expr>` gets the exit check inserted BEFORE the return so state is verified before the return statement runs. Early returns inside if/switch/loop branches don't get exit checks in this MVP (single-exit transform is a follow-on landing).

  Prior to Landing C, user method bodies emitted with no invariant enforcement — a method could mutate state into an invariant-violating shape and the violation would only surface later at the next transition boundary."
    (let [prog (lower-src "def Bounded:
    data:
        v: Int = 42
    invariants:
        0 <= v
        v <= 150

    methods:
        function noop(self) -> Int:
            return self.v

def main:
    -> running
        do: print(1)
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
    data:
        value: Int = 42
    invariants:
        0 <= value
        value <= 150

def Port:
    data:
        value: Int = 8080
    invariants:
        1 <= value
        value <= 65535

def Temp:
    data:
        value: Int = 0
    invariants:
        -128 <= value
        value <= 127

def Signed16:
    data:
        value: Int = 0
    invariants:
        -1000 <= value
        value <= 1000

def OneBound:
    data:
        value: Int = 5
    invariants:
        value >= 0

def Unbounded:
    data:
        value: Int = 0

def main:
    -> running
        do: print(1)
")
          value-c-type
          (fn [nm]
            (:c-type (first (filter #(= "value" (:name %))
                                    (:properties (first (filter #(= nm (:name %))
                                                                (:classes prog))))))))]
      (is (= "uint8_t"  (value-c-type "Age"))       "[0..150] → UInt8")
      (is (= "uint16_t" (value-c-type "Port"))      "[1..65535] → UInt16")
      (is (= "int8_t"   (value-c-type "Temp"))      "[-128..127] → Int8")
      (is (= "int16_t"  (value-c-type "Signed16")) "[-1000..1000] → Int16")
      (is (= "int64_t"  (value-c-type "OneBound"))
          "only lower bound → no narrowing (MVP requires both bounds)")
      (is (= "int64_t"  (value-c-type "Unbounded"))
          "no invariants → no narrowing"))))

(deftest picker-narrowed-field-still-fires-pre-store-invariant-check
  (testing "Phase D F36 Landing 2a × F49 Landing A — a picker-narrowed field's pre-store invariant check still fires on the WIDE source value before the narrowing store. This is the correctness contract that made Landing 2a safe to ship."
    (let [prog (lower-src "def Age:
    data:
        value: Int = 42
    invariants:
        0 <= value
        value <= 150

def main:
    -> running
        do: print(1)
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
      (is (= "uint8_t" (:c-type value-prop))
          "picker narrowed value to uint8_t")
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
    data:
        value: Int
    invariants:
        0 <= value
        value <= 150

def Container:
    data:
        xs: List of Int

def main:
    def a: Age(value: 42)

    -> running
        do: print(0)
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
    data:
        value: Int
    invariants:
        0 <= value
        value <= 150

def main:
    def a: Age(value: 42)

    -> running
        do: print(0)
")
          cls (first (filter #(= "Age" (:name %)) (:classes prog)))
          value-prop (first (filter #(= "value" (:name %)) (:properties cls)))
          init-params (:init-params cls)
          state (first (filter #(clojure.string/starts-with? (:selector %) "cxc_stateInit")
                               (:methods cls)))
          body (:body state)
          pre-checks (filter #(= :invariant-check (:node %)) body)]
      (is (= "uint8_t" (:c-type value-prop))
          "picker narrows Age.value storage to uint8_t")
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
    data:
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
         (lower-src "record R:
    data:
        n: Int
    methods:
        function first(self) -> Int:
            return self.n[0]

def main:
    -> running
        do: print(0)
")))))

(deftest f19-l2-string-subscript-lowers-to-character-at-index
  (testing "F19 L2 — `s[i]` on a String lowers to `(int64_t)[s characterAtIndex:i]` returning Int. Bounds behavior follows NSString: NSRangeException on OOB, caught by F48's `@try/@catch` transition wrapper."
    (let [prog (lower-src "record R:
    data:
        s: String
    methods:
        function first_char(self) -> Int:
            return self.s[0]

def main:
    -> running
        do: print(0)
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
    (let [prog (lower-src "record V:\n    data:\n        x: Int\n        y: Int\n\ndef main:\n    -> running\n")
          fn-names (set (map :name (:functions prog)))]
      (is (contains? fn-names "V_cxc_equals"))
      (is (contains? fn-names "V_cxc_hash")))))

(deftest f37-rewrites-record-eq-to-synthesized-equals-call
  (testing "F37 — `==` between two same-record-type operands lowers to a call to the synthesized equals fn instead of an illegal raw-C struct comparison. `!=` wraps the call in a `!`."
    ;; Feed lower a record + == inside an atom method body so we can
    ;; inspect the lowered call node.
    (let [src "record V:\n    data:\n        x: Int\n\ndef main:\n    -> running\n        do: print(V(x: 1) == V(x: 2))\n"
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

(deftest handler-decl-raises-targeted-diagnostic
  (testing "Audit F14: `-> handler(args):` parses as a distinct AST node (spec §1.11), but lowering waits on F9 + F38. Diagnostic names the handler + alternative (methods: section) so users get an actionable message. Needs a companion def-atom + def main so lower reaches the atom body before failing (main-atom promotion path fires first if the handler-decl sits on main itself)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"handler declaration `-> tick"
         (lower-src "def A:
    data:
        n: Int = 0

    -> running

    -> tick(msg: Int):
        return n

def main:
    def a: A

    -> running
")))))

(deftest f31-f35-l2-panic-lowers-to-inline-trap
  (testing "F31/F35 Landing 2 — `panic(\"msg\")` lowers to an inline expr that prints `panic: <msg>` + backtrace and calls `__builtin_trap()`. Uncatchable (bypasses `@try/@catch`); BEAM-style transition isolation is preserved separately."
    (let [prog (lower-src "def main:\n    -> running\n        do: panic(\"boom\")\n")
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
         (lower-src "def main:\n    -> running\n        do: panic(42)\n")))))

(deftest f31-f35-l2-panic-wrong-arity-rejected
  (testing "F31/F35 Landing 2 — panic expects exactly one arg."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"panic\(\) expects exactly one"
         (lower-src "def main:\n    -> running\n        do: panic(\"a\", \"b\")\n")))))

(deftest f31-f35-l2-panic-pulls-execinfo-include
  (testing "F31/F35 Landing 2 — programs using panic get `<execinfo.h>` in includes (for the inlined backtrace() call). Programs without panic don't."
    (let [with-panic    (lower-src "def main:\n    -> running\n        do: panic(\"x\")\n")
          without-panic (lower-src "def main:\n    -> running\n        do: print(1)\n")]
      (is (some #{"<execinfo.h>"} (:includes with-panic))
          "panic program includes <execinfo.h>")
      (is (not (some #{"<execinfo.h>"} (:includes without-panic)))
          "non-panic program doesn't pull in <execinfo.h>"))))

(deftest f9-l2-non-protocol-class-still-fails-nyi
  (testing "F9 Landing 2 — regular/record/enum classes via `class`/`metadata: kind:` still fail-nyi (extension composition lands with L4). L2 opens up ONLY `kind: protocol`."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F9 L2 lands.*protocol.*L4"
         (lower-src (str "class Foo:\n"
                         "    metadata:\n"
                         "        kind: regular\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l2-protocol-lowers-and-registers
  (testing "F9 Landing 2 — a `protocol` declaration lowers cleanly and its `accepts:` entries land in the program's :protocols map. Registration-only for now — dispatch wires in L4, conformance validation in L5."
    (let [prog (lower-src (str "protocol P:\n"
                               "    accepts:\n"
                               "        foo(x: Int) -> Int\n"
                               "        bar(s: String) -> Bool\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
          proto (get-in prog [:protocols "P"])]
      (is (some? proto) "protocol P registered on the lowered program")
      (is (= 2 (count (:accepts proto))))
      (is (= #{"foo" "bar"}
             (into #{} (map :name (:accepts proto)))))
      (is (contains? (:accepts-index proto) "foo"))
      (is (contains? (:accepts-index proto) "bar"))
      (is (empty? (:conforms proto)) "no explicit protocol-extends here"))))

(deftest f9-l2-protocol-with-default-methods-registered
  (testing "F9 Landing 2 — protocols may carry default `methods:` bodies; L2 stores them under :defaults for L6 (inheritance) to consume. L2 does not yet emit or dispatch them."
    (let [prog (lower-src (str "protocol P:\n"
                               "    methods:\n"
                               "        function greet(self) -> Int:\n"
                               "            return 42\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
          proto (get-in prog [:protocols "P"])]
      (is (some? proto))
      (is (= 1 (count (:defaults proto))))
      (is (= "greet" (:name (first (:defaults proto))))))))

(deftest f9-l2-duplicate-accepts-rejected
  (testing "F9 Landing 2 — duplicate `accepts` entry names in the same protocol fail at lower: each selector appears at most once in a protocol's surface (§3.4.5)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"declares `accepts` entry `foo` more than once"
         (lower-src (str "protocol P:\n"
                         "    accepts:\n"
                         "        foo(x: Int) -> Int\n"
                         "        foo(y: String) -> Bool\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l3-extension-env-keyed-by-target
  (testing "F9 Landing 3 — `extend class Foo:` blocks land in the program's `:extensions` map keyed by target name. Multiple extensions per target stack in a vector. No composition here — L4 merges these into the target's method table."
    (let [prog (lower-src (str "record Widget:\n"
                               "    data:\n"
                               "        n: Int\n"
                               "\n"
                               "extend class Widget:\n"
                               "    methods:\n"
                               "        function bar(self) -> Int:\n"
                               "            return 1\n"
                               "\n"
                               "extend class Widget:\n"
                               "    methods:\n"
                               "        function baz(self) -> Int:\n"
                               "            return 2\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
          exts (get-in prog [:extensions "Widget"])]
      (is (= 2 (count exts))
          "both `extend class Widget:` blocks register under target `Widget`")
      (is (every? #(= :extend-class (:node %)) exts)))))

(deftest f9-l3-extension-target-must-resolve
  (testing "F9 Landing 3 — `extend class BogusFoo:` with no matching record/atom/protocol in scope fails at lower with a §3.3.2 pointer. Parse-time can't catch this because the symbol table isn't ready yet."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"targets unknown class `BogusFoo`.*§3.3.2"
         (lower-src (str "extend class BogusFoo:\n"
                         "    methods:\n"
                         "        function bar(self) -> Int:\n"
                         "            return 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l3-extension-may-target-protocol
  (testing "F9 Landing 3 — `extend class P:` where P is a protocol is legal at L3: extension-env just records; L6 will handle default-method inheritance from extension methods (extension provides defaults, structurally). Registration succeeds; no L4-style composition on protocols yet."
    (let [prog (lower-src (str "protocol P:\n"
                               "    accepts:\n"
                               "        foo(x: Int) -> Int\n"
                               "\n"
                               "extend class P:\n"
                               "    methods:\n"
                               "        function bar(self) -> Int:\n"
                               "            return 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))]
      (is (= 1 (count (get-in prog [:extensions "P"])))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 4 — extension composition (single-module) + `replace`
;; + conflict-resolution warnings.
;; ---------------------------------------------------------------------------

(defn- capture-warnings [thunk]
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw]
      (let [result (thunk)]
        [result (str sw)]))))

(deftest f9-l4-single-extension-adds-method
  (testing "F9 Landing 4 — one extension of a record adds a method that composes into the record's method table with no conflict. Post-composition, the target's :methods contains both own and extension methods."
    (let [[prog stderr] (capture-warnings
                        #(lower-src (str "record Widget:\n"
                                         "    data:\n"
                                         "        n: Int\n"
                                         "    methods:\n"
                                         "        function own_m(self) -> Int:\n"
                                         "            return 1\n"
                                         "\n"
                                         "extend class Widget:\n"
                                         "    methods:\n"
                                         "        function ext_m(self) -> Int:\n"
                                         "            return 2\n"
                                         "\n"
                                         "def main:\n"
                                         "    -> running\n"
                                         "        do: print(0)\n")))
          widget-fns (filter #(str/starts-with? (or (:name %) "") "Widget_")
                             (:functions prog))]
      (is (= "" stderr) "no warnings for a clean add-a-method extension")
      (is (some #(= "Widget_own_m" (:name %)) widget-fns))
      (is (some #(= "Widget_ext_m" (:name %)) widget-fns)))))

(deftest f9-l4-shadowing-warns-and-picks-own
  (testing "F9 Landing 4 — extension declares `own_m` with no `replace`; own wins (§3.3.2 rule 1) and a shadowing warning fires naming the target. Composed emit uses the own body."
    (let [[prog stderr] (capture-warnings
                        #(lower-src (str "record Widget:\n"
                                         "    data:\n"
                                         "        n: Int\n"
                                         "    methods:\n"
                                         "        function own_m(self) -> Int:\n"
                                         "            return 100\n"
                                         "\n"
                                         "extend class Widget:\n"
                                         "    methods:\n"
                                         "        function own_m(self) -> Int:\n"
                                         "            return 200\n"
                                         "\n"
                                         "def main:\n"
                                         "    -> running\n"
                                         "        do: print(0)\n")))
          fn         (first (filter #(= "Widget_own_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (re-find #"shadows the class's own definition.*not marked `replace`" stderr))
      (is (= 100 (get-in return-stmt [:value :value]))
          "own body wins (returns 100, not extension's 200)"))))

(deftest f9-l4-replace-suppresses-warning-and-shadows
  (testing "F9 Landing 4 — `replace function own_m(self)` in extension overrides the own definition with no warning. Composed emit uses the extension body."
    (let [[prog stderr] (capture-warnings
                        #(lower-src (str "record Widget:\n"
                                         "    data:\n"
                                         "        n: Int\n"
                                         "    methods:\n"
                                         "        function own_m(self) -> Int:\n"
                                         "            return 100\n"
                                         "\n"
                                         "extend class Widget:\n"
                                         "    methods:\n"
                                         "        replace function own_m(self) -> Int:\n"
                                         "            return 200\n"
                                         "\n"
                                         "def main:\n"
                                         "    -> running\n"
                                         "        do: print(0)\n")))
          fn         (first (filter #(= "Widget_own_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (= "" stderr) "replace suppresses shadowing warning (rule 3)")
      (is (= 200 (get-in return-stmt [:value :value]))
          "replace extension body wins (returns 200)"))))

(deftest f9-l4-replace-without-target-warns
  (testing "F9 Landing 4 — `replace function unique_m(self)` in extension with no other declaration for `unique_m` fires `replace-without-target`. The method still lands (replace has no effect but doesn't drop the code)."
    (let [[_ stderr] (capture-warnings
                     #(lower-src (str "record Widget:\n"
                                      "    data:\n"
                                      "        n: Int\n"
                                      "\n"
                                      "extend class Widget:\n"
                                      "    methods:\n"
                                      "        replace function unique_m(self) -> Int:\n"
                                      "            return 1\n"
                                      "\n"
                                      "def main:\n"
                                      "    -> running\n"
                                      "        do: print(0)\n")))]
      (is (re-find #"replace-without-target" stderr)))))

(deftest f9-l4-two-extensions-conflict-later-wins
  (testing "F9 Landing 4 — two extensions declare the same method without `replace` and no own definition exists; §3.3.2 rule 5 says later wins with a shadowing warning for the earlier."
    (let [[prog stderr] (capture-warnings
                        #(lower-src (str "record Widget:\n"
                                         "    data:\n"
                                         "        n: Int\n"
                                         "\n"
                                         "extend class Widget:\n"
                                         "    methods:\n"
                                         "        function m(self) -> Int:\n"
                                         "            return 1\n"
                                         "\n"
                                         "extend class Widget:\n"
                                         "    methods:\n"
                                         "        function m(self) -> Int:\n"
                                         "            return 2\n"
                                         "\n"
                                         "def main:\n"
                                         "    -> running\n"
                                         "        do: print(0)\n")))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (re-find #"shadowed by a later extension" stderr))
      (is (= 2 (get-in return-stmt [:value :value]))
          "later extension wins"))))

(deftest f9-l4-extension-conforms-union
  (testing "F9 Landing 4 — extension-added conformances union into the target's `:conforms` at composition time."
    (let [prog (lower-src (str "protocol P:\n"
                               "    accepts:\n"
                               "        m(self) -> Int\n"
                               "\n"
                               "record Widget:\n"
                               "    data:\n"
                               "        n: Int\n"
                               "\n"
                               "extend class Widget(P):\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))]
      ;; L4 stores conforms on record-env; not directly surfaced on the
      ;; program shape, but the compose path must not throw and the
      ;; extension method must land.
      (is (some #(= "Widget_m" (:name %)) (:functions prog))))))

(deftest f9-l4-extension-invariant-on-record-fail-nyi
  (testing "F9 Landing 4 — record targets have no runtime invariant path yet; extension-added `invariants:` on a record fails-nyi with a pointer at the follow-on sub-landing. Atoms already handle invariants natively (no fail-nyi)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"record targets have no runtime invariant path"
         (lower-src (str "record Widget:\n"
                         "    data:\n"
                         "        n: Int\n"
                         "\n"
                         "extend class Widget:\n"
                         "    invariants:\n"
                         "        n > 0\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 5 — explicit conformance validation.
;;
;; Validates every `:conforms` declaration on the composed method table.
;; Missing methods, signature mismatches, and unknown protocols all
;; fail-compile with §3.4.5-pointer diagnostics.
;; ---------------------------------------------------------------------------

(deftest f9-l5-conformance-satisfied-by-extension-method
  (testing "F9 Landing 5 — `extend class Widget(P):` supplying `m` matching P's accepts entry validates cleanly."
    (let [prog (lower-src (str "protocol P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "\n"
                               "record Widget:\n"
                               "    data:\n"
                               "        n: Int\n"
                               "\n"
                               "extend class Widget(P):\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))]
      (is (some #(= "Widget_m" (:name %)) (:functions prog))))))

(deftest f9-l5-unknown-protocol-rejected
  (testing "F9 Landing 5 — conformance to a protocol name that doesn't resolve fires a §3.4.5-pointer diagnostic."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown protocol `BogusProto`"
         (lower-src (str "record Widget:\n"
                         "    data:\n"
                         "        n: Int\n"
                         "\n"
                         "extend class Widget(BogusProto):\n"
                         "    methods:\n"
                         "        function m(self) -> Int:\n"
                         "            return 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l5-missing-method-rejected
  (testing "F9 Landing 5 — class conforms to protocol but doesn't implement one of its accepts entries and no default is provided → fail-compile."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"claims conformance to `P` but neither implements nor inherits a default for `m`"
         (lower-src (str "protocol P:\n"
                         "    accepts:\n"
                         "        m() -> Int\n"
                         "\n"
                         "record Widget:\n"
                         "    data:\n"
                         "        n: Int\n"
                         "\n"
                         "extend class Widget(P):\n"
                         "    methods:\n"
                         "        function unrelated(self) -> Int:\n"
                         "            return 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l5-signature-mismatch-return-type-rejected
  (testing "F9 Landing 5 — method with mismatched return type fails with a specific detail line."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"return type mismatch — expected `Int`, got `String`"
         (lower-src (str "protocol P:\n"
                         "    accepts:\n"
                         "        m() -> Int\n"
                         "\n"
                         "record Widget:\n"
                         "    data:\n"
                         "        n: Int\n"
                         "\n"
                         "extend class Widget(P):\n"
                         "    methods:\n"
                         "        function m(self) -> String:\n"
                         "            return \"x\"\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l5-signature-mismatch-param-name-rejected
  (testing "F9 Landing 5 — Cxc supports named-arg calls (§3.4.5 rule 2), so protocol conformance requires param names to match too, not just types."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"param name mismatch — expected `x`, got `y`"
         (lower-src (str "protocol P:\n"
                         "    accepts:\n"
                         "        m(x: Int) -> Int\n"
                         "\n"
                         "record Widget:\n"
                         "    data:\n"
                         "        n: Int\n"
                         "\n"
                         "extend class Widget(P):\n"
                         "    methods:\n"
                         "        function m(self, y: Int) -> Int:\n"
                         "            return y\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l5-signature-mismatch-param-type-rejected
  (testing "F9 Landing 5 — param type must match too."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"param `x` type mismatch — expected `Int`, got `String`"
         (lower-src (str "protocol P:\n"
                         "    accepts:\n"
                         "        m(x: Int) -> Int\n"
                         "\n"
                         "record Widget:\n"
                         "    data:\n"
                         "        n: Int\n"
                         "\n"
                         "extend class Widget(P):\n"
                         "    methods:\n"
                         "        function m(self, x: String) -> Int:\n"
                         "            return 1\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l5-protocol-default-satisfies-conformance
  (testing "F9 Landing 5 — protocol carries a default `methods:` body for `m`; class declares `conforms: P` and doesn't implement `m` itself. L5 accepts the default's presence as structural satisfaction (L6 wires actual inheritance at call sites)."
    (let [prog (lower-src (str "protocol P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 42\n"
                               "\n"
                               "record Widget:\n"
                               "    data:\n"
                               "        n: Int\n"
                               "\n"
                               "extend class Widget(P):\n"
                               "    methods:\n"
                               "        function unrelated(self) -> Int:\n"
                               "            return 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))]
      (is (some? prog) "compiles cleanly — default satisfies the accepts"))))

;; ---------------------------------------------------------------------------
;; F9 Landing 6 — default-method inheritance (explicit-conformance) +
;; diamond-ambiguity detection.
;;
;; Structural-conformance-based inheritance is L6b (deferred).
;; ---------------------------------------------------------------------------

(deftest f9-l6-inherits-default-into-composed-table
  (testing "F9 Landing 6 — class explicitly conforms to P, P provides default `m`, class doesn't define `m` — default folded into class's composed :methods and emitted as a C function keyed by the class name."
    (let [prog (lower-src (str "protocol P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 42\n"
                               "\n"
                               "record Widget:\n"
                               "    data:\n"
                               "        n: Int\n"
                               "\n"
                               "extend class Widget(P):\n"
                               "    methods:\n"
                               "        function unrelated(self) -> Int:\n"
                               "            return 1\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (some? fn)
          "inherited default emits as Widget_m alongside class's own methods")
      (is (= 42 (get-in return-stmt [:value :value]))
          "inherited body returns 42 (from the protocol default)"))))

(deftest f9-l6-own-method-shadows-default
  (testing "F9 Landing 6 — when the class defines `m` itself (own or via extension), the own method wins over the protocol default with no diamond, no warning."
    (let [prog (lower-src (str "protocol P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 42\n"
                               "\n"
                               "record Widget:\n"
                               "    data:\n"
                               "        n: Int\n"
                               "\n"
                               "extend class Widget(P):\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 100\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (= 100 (get-in return-stmt [:value :value]))
          "own extension method wins (returns 100, not default's 42)"))))

(deftest f9-l6-diamond-ambiguity-across-conformed-protocols
  (testing "F9 Landing 6 — class explicitly conforms to two protocols P and Q that both provide a default `m`, and doesn't override → diamond ambiguity fires with a §3.4.4 diagnostic naming both candidates."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"diamond ambiguity.*method `m`.*Widget.*`P`.*`Q`"
         (lower-src (str "protocol P:\n"
                         "    accepts:\n"
                         "        m() -> Int\n"
                         "    methods:\n"
                         "        function m(self) -> Int:\n"
                         "            return 1\n"
                         "\n"
                         "protocol Q:\n"
                         "    accepts:\n"
                         "        m() -> Int\n"
                         "    methods:\n"
                         "        function m(self) -> Int:\n"
                         "            return 2\n"
                         "\n"
                         "record Widget:\n"
                         "    data:\n"
                         "        n: Int\n"
                         "\n"
                         "extend class Widget(P, Q):\n"
                         "    methods:\n"
                         "        function unrelated(self) -> Int:\n"
                         "            return 0\n"
                         "\n"
                         "def main:\n"
                         "    -> running\n"
                         "        do: print(0)\n"))))))

(deftest f9-l6-diamond-resolved-by-own-method
  (testing "F9 Landing 6 — same P/Q diamond setup + class defines its own `m` → own shadows all defaults per §3.4.4; no diamond error."
    (let [prog (lower-src (str "protocol P:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 1\n"
                               "\n"
                               "protocol Q:\n"
                               "    accepts:\n"
                               "        m() -> Int\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 2\n"
                               "\n"
                               "record Widget:\n"
                               "    data:\n"
                               "        n: Int\n"
                               "\n"
                               "extend class Widget(P, Q):\n"
                               "    methods:\n"
                               "        function m(self) -> Int:\n"
                               "            return 999\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
          fn         (first (filter #(= "Widget_m" (:name %))
                                    (:functions prog)))
          return-stmt (first (filter #(= :return (:node %)) (:body fn)))]
      (is (= 999 (get-in return-stmt [:value :value]))
          "own resolution — no diamond, own wins"))))

(deftest f37b-l1-bytes-length-returns-int
  (testing "F37b Landing 1 — `.length` on a `Bytes`-typed expression type-checks as `Int` (spec §3.10.10 user-facing convention: `.length` is an element count matching List/String; wire-format `UInt32` prefix is a different concern). Emitted C uses ObjC property syntax, dispatching to NSData's `-length`."
    (let [prog (lower-src (str "record Msg:\n"
                               "    data:\n"
                               "        payload: Bytes\n"
                               "    methods:\n"
                               "        function size(self) -> Int:\n"
                               "            return self.payload.length\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
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
    (let [prog (lower-src (str "record Cmp:\n"
                               "    data:\n"
                               "        a: Bytes\n"
                               "        b: Bytes\n"
                               "    methods:\n"
                               "        function eq(self) -> Bool:\n"
                               "            return self.a == self.b\n"
                               "        function neq(self) -> Bool:\n"
                               "            return self.a != self.b\n"
                               "\n"
                               "def main:\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
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
                   "        do: b |> pong\n"
                   "\n"
                   "def B:\n"
                   "    use:\n"
                   "        a: A\n"
                   "\n"
                   "    -> alive\n"
                   "\n"
                   "    alive <: pong -> alive\n"
                   "        do: a |> ping\n"
                   "\n"
                   "def main:\n"
                   "    def a: A(b: B(a: a))\n"
                   "    -> running\n"
                   "        do: print(0)\n")
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
                   "        do: b |> pong\n"
                   "\n"
                   "def main:\n"
                   "    def b: B\n"
                   "    def a: A(b: b)\n"
                   "    -> running\n"
                   "        do: print(0)\n")
          warnings (with-out-str
                     (binding [*err* *out*]
                       (try (lower-src src) (catch Exception _))))]
      (is (not (str/includes? warnings "`|>` cycle"))
          "no warning for a linear |> chain"))))

(deftest f31-f35-l1-sync-send-lowers-to-wait-for-processing
  (testing "F31/F35 Landing 1 — `|>` sync send lowers to `cxc_wait_for_processing` (spec framing: `|>` is a processing barrier). Retires the old `cxc_send_sync` name; under Layer 4 CXC_SINGLE_MAIN_QUEUE the helper is a nil-guarded direct call, under F32 it upgrades to dispatch_sync — the emit site is the sole substitution point."
    (let [prog (lower-src "def Sink:\n    data:\n        n: Int = 0\n\n    -> alive\n\n    alive <: absorb -> alive\n        then: n => n + 1\n\ndef main:\n    def sink: Sink\n\n    -> running\n        do:\n            sink |> absorb\n")
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
    (let [prog (lower-src "def Widget:\n    data:\n        bus: Widget! = Widget()\n\ndef main:\n    def w: Widget\n\n    -> running\n        do: print(0)\n")]
      (is (= :objc-program (:node prog))
          "T! on atom lowers cleanly"))))

(deftest f39-l2-nonnil-on-string-accepted
  (testing "F39 Landing 2 — `String!` is legal (String is reference-shaped)."
    (let [prog (lower-src "def X:\n    data:\n        name: String! = \"anon\"\n\ndef main:\n    def x: X\n\n    -> running\n        do: print(0)\n")]
      (is (= :objc-program (:node prog))))))

(deftest f39-l2-nonnil-on-value-primitive-rejected
  (testing "F39 Landing 2 — `T!` on a value primitive is trivially true (spec §3.6.1)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`Int!` is trivially true.*§3\.6\.1.*drop the `!`"
         (lower-src "def X:\n    data:\n        n: Int! = 5\n\ndef main:\n    -> running\n        do: print(0)\n")))))

(deftest f39-l2-nonnil-on-record-rejected
  (testing "F39 Landing 2 — `T!` on a record (value type) is trivially true."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`P!` is trivially true.*value type.*§3\.6\.1"
         (lower-src "record P:\n    data:\n        x: Int\n\ndef X:\n    data:\n        p: P! = P(x: 0)\n\ndef main:\n    -> running\n        do: print(0)\n")))))

(deftest f39-l2-nonnil-and-nullable-cannot-stack
  (testing "F39 Landing 2 — `T!?` / `T?!` are grammar errors — the two suffixes are mutually exclusive per spec §3.6.1."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cannot stack"
         (lower-src "def X:\n    data:\n        bus: Widget!? = nil\n\ndef main:\n    -> running\n        do: print(0)\n")))))

(deftest f39-l3-nonnil-ctor-param-check-inserted
  (testing "F39 Landing 3 — a `T!` ctor param gets an inline `if (p == nil) cxc_nonnil_violation(...)` guard at the top of cxc_stateInit. Warning-grade per spec §3.6.1 line 3686."
    (let [prog (lower-src (str "def EventBus:\n"
                               "    data:\n"
                               "        n: Int = 0\n"
                               "\n"
                               "def Widget(bus: EventBus!):\n"
                               "    data:\n"
                               "        n: Int = 0\n"
                               "\n"
                               "def main:\n"
                               "    def b: EventBus\n"
                               "    def w: Widget(bus: b)\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
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
                               "    data:\n"
                               "        peer: Widget! = Widget()\n"
                               "\n"
                               "    -> boot\n"
                               "        do: self |> bump\n"
                               "\n"
                               "    boot <: bump -> on\n"
                               "        then: peer => Widget()\n"
                               "\n"
                               "def main:\n"
                               "    def w: Widget\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
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
                               "    data:\n"
                               "        peer: Widget = Widget()\n"
                               "\n"
                               "    -> boot\n"
                               "        do: self |> bump\n"
                               "\n"
                               "    boot <: bump -> on\n"
                               "        then: peer => Widget()\n"
                               "\n"
                               "def main:\n"
                               "    def w: Widget\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
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

(deftest f39-l3-nonnil-method-return-check-wrapped
  (testing "F39 Landing 3 — a method declared `-> T!` has its trailing `return <expr>` rewritten to `T *_Cxc_ret = <expr>; if (_Cxc_ret == nil) cxc_nonnil_violation(...); return _Cxc_ret;` — bind once, check, return."
    (let [prog (lower-src (str "def Widget:\n"
                               "    data:\n"
                               "        peer: Widget = Widget()\n"
                               "    methods:\n"
                               "        function get_peer(self) -> Widget!:\n"
                               "            return peer\n"
                               "\n"
                               "def main:\n"
                               "    def w: Widget\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
          widget (first (filter #(= "Widget" (:name %)) (:classes prog)))
          get-peer (first (filter #(str/starts-with? (:selector %) "get_peer")
                                  (:methods widget)))
          body     (:body get-peer)
          last-3   (subvec body (max 0 (- (count body) 3)))]
      (is (= :var-decl (:node (first last-3)))
          "return-expr bound to a temp before the check")
      (is (= "_Cxc_ret" (:name (first last-3)))
          "temp name is _Cxc_ret")
      (is (= :raw-c (:node (second last-3)))
          "second-to-last stmt is the nonnil-check")
      (is (str/includes? (:text (second last-3)) "(_Cxc_ret) == nil")
          "check reads the return temp")
      (is (str/includes? (:text (second last-3)) "method `get_peer` return")
          "site label names the method")
      (is (= :return (:node (last last-3)))
          "final stmt returns the temp")
      (is (= "_Cxc_ret" (get-in (last last-3) [:value :sym]))
          "final stmt returns _Cxc_ret"))))

(deftest f39-l3-nonnil-method-return-non-annotated-no-wrap
  (testing "F39 Landing 3 — a bare `-> Foo` method keeps its plain `return <expr>` — no temp, no check."
    (let [prog (lower-src (str "def Widget:\n"
                               "    data:\n"
                               "        peer: Widget = Widget()\n"
                               "    methods:\n"
                               "        function get_peer(self) -> Widget:\n"
                               "            return peer\n"
                               "\n"
                               "def main:\n"
                               "    def w: Widget\n"
                               "    -> running\n"
                               "        do: print(0)\n"))
          widget (first (filter #(= "Widget" (:name %)) (:classes prog)))
          get-peer (first (filter #(str/starts-with? (:selector %) "get_peer")
                                  (:methods widget)))
          body     (:body get-peer)]
      (is (= :return (:node (last body)))
          "trailing return stays a plain :return")
      (is (nil? (some (fn [s]
                        (and (= :var-decl (:node s))
                             (= "_Cxc_ret" (:name s))))
                      body))
          "no _Cxc_ret temp for bare-return methods"))))

(deftest f39-l1-nullable-on-atom-rejected
  (testing "F39 Landing 1 — `T?` where T is a reference type (atom) is spec-refused as redundant (§3.6.1: reference types are already nil-allowed). Diagnostic names the type and points at the drop-the-`?` fix."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"redundant nullable on reference type `Counter\?`.*§3\.6\.1.*drop the `\?`"
         (lower-src "def Counter:\n    data:\n        other: Counter? = nil\n\ndef main:\n    -> running\n        do: print(0)\n")))))

(deftest f39-l1-nullable-on-string-rejected
  (testing "F39 Landing 1 — `String?` is also redundant: String is reference-shaped (NSString *)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"redundant nullable on reference type `String\?`.*§3\.6\.1"
         (lower-src "def X:\n    data:\n        name: String? = nil\n\ndef main:\n    -> running\n        do: print(0)\n")))))

(deftest f39-l1-nullable-on-value-primitive-still-nyi
  (testing "F39 Landing 1 keeps value-typed nullables in fail-nyi land — the Optional<T> value rep waits on F39 Landing 5. Distinct diagnostic from the reference-type reject so users see the migration path."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"nullable value type `Int\?`.*F39 Landing 5"
         (lower-src "def X:\n    data:\n        n: Int? = nil\n\ndef main:\n    -> running\n        do: print(0)\n")))))

(deftest f33-l1-cross-atom-data-field-read-rejected
  (testing "F33 Landing 1 — reading a `data:` field from outside the enclosing atom is a compile error. Spec §1.11: data-fields are internal; external reads must go through an observable or reader method. Diagnostic names the field, the owner atom, cites §1.11, and offers both migration paths."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cross-atom `data:` field access.*`n`.*`Counter`.*§1\.11.*observable.*reader method"
         (lower-src "def Counter:\n    data:\n        n: Int = 42\n\ndef main:\n    -> running\n        do: print(Counter().n)\n")))))

(deftest f33-l1-observable-read-still-allowed
  (testing "F33 Landing 1 exempts observables. `n :< raw_n` on the atom exposes the field to external reads without violating encapsulation."
    (let [prog (lower-src "def Counter:\n    data:\n        raw_n: Int = 42\n\n    n :< raw_n\n\ndef main:\n    -> running\n        do: print(Counter().n)\n")]
      (is (= :objc-program (:node prog))
          "compiles cleanly — observable read is spec-legal"))))

(deftest f33-l1-self-field-read-still-allowed
  (testing "F33 Landing 1 exempts self-field reads. Inside the atom's own method / invariant / transition body, `self.n` (or bare `n`) reads the data field directly — the guard only fires for cross-atom reads."
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function bump(self) -> Int:\n            return self.n + 1\n\ndef main:\n    def c: Counter\n\n    -> running\n        do: print(0)\n")]
      (is (= :objc-program (:node prog))
          "self.n inside Counter's own method compiles cleanly"))))
