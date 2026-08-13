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
