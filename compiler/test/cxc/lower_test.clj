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
      (is (= [{:name "n" :c-type "int" :attrs ["assign"]}] (:properties cls)))
      (is (= [{:node :assign
               :lhs  {:node :name :sym "_n"}
               :rhs  {:node :int :value 42}}]
             (:init-body cls))))))

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
      (is (= ["<Foundation/Foundation.h>"] (:includes prog)))
      (is (true? (get-in prog [:main :autorelease?]))))))

(deftest hello-world-stays-pure-c
  (testing "no ObjC classes → stdio.h include, no autoreleasepool"
    (let [prog (lower-src "def main:\n    -> running\n        do: print(\"hi\")\n")]
      (is (= ["<stdio.h>"] (:includes prog)))
      (is (not (true? (get-in prog [:main :autorelease?])))))))

(deftest def-atom-with-transitions-fails-nyi
  (testing "def-atom body beyond data: fails-nyi (transitions/use:/etc are next)"
    (try
      (lower-src "def Ticker:\n    data:\n        n: Int = 0\n\n    -> incremented\n        then: n => n + 1\n\ndef main:\n    -> running\n        do: print(Ticker().n)\n")
      (is false "expected NYI throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-yet-implemented (:kind (ex-data e))))))))

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
      (is (= [{:node :assign
               :lhs  {:node :name :sym "_n"}
               :rhs  {:node :name :sym "initial"}}]
             (:init-body cls)))
      (is (= :objc-init-with (:node ctor)))
      (is (= "Counter" (:class ctor)))
      (is (= [{:name "initial" :value {:node :int :value 42}}] (:args ctor))))))

(deftest nullable-atom-type-lowers-as-pointer
  (testing "T? on an atom lowers to the same `T *` pointer; nil default fills _other = nil; property is strong (ARC)"
    (let [prog (lower-src "def Counter:\n    data:\n        n: Int = 5\n        other: Counter? = nil\n\ndef main:\n    -> running\n        do: print(1)\n")
          cls  (first (:classes prog))]
      (is (= [{:name "n"     :c-type "int"       :attrs ["assign"]}
              {:name "other" :c-type "Counter *" :attrs ["strong"]}]
             (:properties cls)))
      (is (= {:node :assign
              :lhs  {:node :name :sym "_other"}
              :rhs  {:node :nil}}
             (second (:init-body cls)))))))

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
