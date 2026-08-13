(ns cxc.lower-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as p]
            [cxc.lower :as lower]))

(defn- lower-src [src] (lower/lower (p/parse src)))

(deftest hello-world-program-lowers
  (testing "def main: -> running do: print(\"hello, world\") lowers cleanly"
    (let [prog (lower-src "def main:
    -> running
        do: print(\"hello, world\")
")]
      (is (= :objc-program (:node prog)))
      (is (some #{"<stdio.h>"} (:includes prog)))
      (let [body (get-in prog [:main :body])]
        ;; expect the printf call then a `return 0`
        (is (= 2 (count body)))
        (let [call (first body)]
          (is (= :call (:node call)))
          (is (= "printf" (:fn call)))
          (is (= 2 (count (:args call))))
          (is (= "%s\n" (get-in call [:args 0 :value])))
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
  (testing "int + int keeps %d format; binop lowers with left/right"
    (let [prog (lower-src "def main:\n    -> running\n        do: print(3 + 4 * 2)\n")
          call (first (get-in prog [:main :body]))]
      (is (= "printf" (:fn call)))
      (is (= "%d\n" (get-in call [:args 0 :value])))
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
      (is (= [{:name "x" :c-type "int"}
              {:name "y" :c-type "double"}]
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

(deftest def-atom-lowers-to-objc-class
  (testing "def-atom with data:-only → :objc-class with @property ivars + -init defaults"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 42\n\ndef main:\n    -> running\n        do: print(Counter().n)\n")
          cls  (first (:classes prog))]
      (is (= :objc-class (:node cls)))
      (is (= "Counter" (:name cls)))
      (is (= "NSObject" (:superclass cls)))
      (is (= [{:name "phase"            :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "own_queue"        :c-type "dispatch_queue_t" :attrs ["nonatomic" "strong"]}
              {:name "parent"           :c-type "id"               :attrs ["nonatomic" "weak"]}
              {:name "parent_slot_name" :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "n"                :c-type "int"              :attrs ["assign"]}]
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
  (testing "Counter() in expression position → :objc-alloc-init"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 42\n\ndef main:\n    -> running\n        do: print(Counter().n)\n")
          call (first (get-in prog [:main :body]))
          ctor (get-in call [:args 1 :target])]
      (is (= :objc-alloc-init (:node ctor)))
      (is (= "Counter" (:class ctor))))))

(deftest def-atom-triggers-foundation-and-autoreleasepool
  (testing "presence of an ObjC class flips includes to Foundation and wraps main in @autoreleasepool"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 42\n\ndef main:\n    -> running\n        do: print(Counter().n)\n")]
      ;; Foundation for ObjC; stdlib for cxc_shutdown() (needed by every
      ;; atom class's transitionToEnd cascade infrastructure).
      (is (some #{"<Foundation/Foundation.h>"} (:includes prog)))
      (is (some #{"<stdlib.h>"} (:includes prog)))
      (is (true? (get-in prog [:main :autorelease?]))))))

(deftest hello-world-stays-pure-c
  (testing "no ObjC classes → stdio.h include, no autoreleasepool"
    (let [prog (lower-src "def main:\n    -> running\n        do: print(\"hi\")\n")]
      (is (= ["<stdio.h>"] (:includes prog)))
      (is (not (true? (get-in prog [:main :autorelease?])))))))

(deftest wildcard-on-enter-inlined-into-init
  (testing "initial-phase wildcard on-enter → its then:/do: append to :init-body after data defaults"
    (let [prog (lower-src "def Ticker:\n    data:\n        n: Int = 0\n\n    -> incremented\n        then: n => n + 1\n\ndef main:\n    -> running\n        do: print(Ticker().n)\n")
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
    (let [prog (lower-src "def Counter(initial: Int = 0):\n    data:\n        n: Int = initial\n\ndef main:\n    -> running\n        do: print(Counter(initial: 42).n)\n")
          cls  (first (:classes prog))
          call (first (get-in prog [:main :body]))
          ctor (get-in call [:args 1 :target])]
      (is (= [{:name "initial" :c-type "int"}] (:init-params cls)))
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
        (is (= [{:name "initial" :c-type "int"}] (:params state)))
        (is (= [{:node :assign
                 :lhs  {:node :name :sym "_n"}
                 :rhs  {:node :name :sym "initial"}}]
               (:body state))))
      (is (= :objc-init-with (:node ctor)))
      (is (= "Counter" (:class ctor)))
      (is (= [{:name "initial" :value {:node :int :value 42}}] (:args ctor))))))

(deftest nullable-atom-type-lowers-as-pointer
  (testing "T? on an atom lowers to the same `T *` pointer; nil default fills _other = nil; property is strong (ARC)"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 5\n        other: Counter? = nil\n\ndef main:\n    -> running\n        do: print(1)\n")
          cls  (first (:classes prog))]
      (is (= [{:name "phase"            :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "own_queue"        :c-type "dispatch_queue_t" :attrs ["nonatomic" "strong"]}
              {:name "parent"           :c-type "id"               :attrs ["nonatomic" "weak"]}
              {:name "parent_slot_name" :c-type "NSString *"       :attrs ["nonatomic" "copy"]}
              {:name "n"                :c-type "int"              :attrs ["assign"]}
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
      (is (= "int" (:return-type m)))
      (is (empty? (:params m))))))

(deftest atom-method-call-lowers-to-objc-msg
  (testing "counter.bump() → :objc-msg with the receiver as :recv and bare selector"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function bump(self) -> Int:\n            return self.n + 1\n\ndef main:\n    -> running\n        do: print(Counter().bump())\n")
          call (first (get-in prog [:main :body]))
          msg  (get-in call [:args 1])]
      (is (= :objc-msg (:node msg)))
      (is (= "bump" (:selector msg)))
      (is (= :objc-alloc-init (get-in msg [:recv :node])))
      (is (empty? (:args msg))))))

(deftest atom-method-multiarg-selector
  (testing "plus(self, other: Int) → selector `plusWithother:` and matching call"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 10\n\n    methods:\n        function plus(self, other: Int) -> Int:\n            return self.n + other\n\ndef main:\n    -> running\n        do: print(Counter().plus(5))\n")
          m    (first (filter #(= "plusWithother:" (:selector %))
                              (:methods (first (:classes prog)))))
          call (first (get-in prog [:main :body]))
          msg  (get-in call [:args 1])]
      (is (= "plusWithother:" (:selector m)))
      (is (= [{:name "other" :c-type "int"}] (:params m)))
      (is (= "plusWithother:" (:selector msg)))
      (is (= 1 (count (:args msg)))))))

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
    (let [prog (lower-src "def Counter:\n    data:\n        other: Counter? = nil\n\ndef main:\n    -> running\n        do: print(Counter().other is nil)\n")
          call (first (get-in prog [:main :body]))
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
    (let [prog (lower-src "def Counter(initial: Int = 5):\n    data:\n        n: Int = initial\n\ndef main:\n    -> running\n        do: print(Counter().n)\n")
          call (first (get-in prog [:main :body]))
          ctor (get-in call [:args 1 :target])]
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
