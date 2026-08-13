(ns cxc.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as p]))

(defn- first-node [source]
  (first (p/parse source)))

(defn- node-kind [n] (:node n))

(deftest module-decl
  (is (= {:node :module :name "com.example.foo"}
         (first-node "module com.example.foo\n"))))

(deftest from-import
  (let [n (first-node "from com.cxc.stdlib import Clock, Random\n")]
    (is (= :from-import (node-kind n)))
    (is (= "com.cxc.stdlib" (:from n)))
    (is (= ["Clock" "Random"] (:names n)))))

(deftest empty-record
  (testing "record with just data: section, one field"
    (let [n (first-node "record V:\n    data:\n        x: Float\n")]
      (is (= :record (node-kind n)))
      (is (= "V" (:name n)))
      (let [ds (first (:sections n))]
        (is (= :data-section (node-kind ds)))
        (is (= 1 (count (:fields ds))))
        (is (= "x" (-> ds :fields first :name)))))))

(deftest record-with-methods
  (testing "Vector2 — data + methods + a return expression"
    (let [src "record Vector2:
    data:
        x: Float
        y: Float

    methods:
        function plus(self, other: Vector2) -> Vector2:
            return Vector2(x: self.x + other.x, y: self.y + other.y)
"
          n (first-node src)]
      (is (= :record (node-kind n)))
      (is (= 2 (count (:sections n))))
      (let [ms (second (:sections n))]
        (is (= :methods-section (node-kind ms)))
        (let [f (first (:methods ms))]
          (is (= :function (node-kind f)))
          (is (= "plus" (:name f)))
          (is (= 2 (count (:params f))))
          (is (= "Vector2" (get-in f [:return-type :name])))
          (let [ret (first (:stmts (:body f)))]
            (is (= :return (node-kind ret)))
            (is (= :call (node-kind (:expr ret))))))))))

(deftest expr-binops-and-dots
  (testing "self.x + other.x — dot access + binary op"
    (let [n   (first-node "record R:\n    methods:\n        function f(self) -> Float:\n            return self.x + other.x\n")
          ret (-> n :sections first :methods first :body :stmts first)
          expr (:expr ret)]
      (is (= :binop (:node expr)))
      (is (= "+" (:op expr)))
      (is (= :dot (get-in expr [:left :node])))
      (is (= :self (get-in expr [:left :target :node])))
      (is (= "x" (get-in expr [:left :field]))))))

(deftest expr-nested-call-with-named-args
  (let [n   (first-node "record R:\n    methods:\n        function f(self) -> V:\n            return V(x: 1, y: 2)\n")
        ret (-> n :sections first :methods first :body :stmts first)
        call (:expr ret)]
    (is (= :call (:node call)))
    (is (= 2 (count (:args call))))
    (is (= :named (-> call :args first :kind)))
    (is (= "x" (-> call :args first :name)))))

(deftest field-with-default
  (let [n  (first-node "record R:\n    data:\n        n: Int = 42\n")
        f  (-> n :sections first :fields first)]
    (is (= "n" (:name f)))
    (is (= "Int" (get-in f [:type :name])))
    (is (= 42 (get-in f [:default :value])))))
