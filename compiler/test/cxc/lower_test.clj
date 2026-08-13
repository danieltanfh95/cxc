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
  (testing "V(x: 1) when V requires x + y — fail-nyi, not silent"
    (try
      (lower-src "record V:\n    data:\n        x: Int\n        y: Int\n\ndef main:\n    -> running\n        do: print(V(x: 1).x)\n")
      (is false "expected NYI throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-yet-implemented (:kind (ex-data e))))))))
