(ns cxc.build-test
  "End-to-end regression: cxc source → binary → run → verify stdout."
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.build :as build]))

(defn- tmp-dir []
  (let [d (java.nio.file.Files/createTempDirectory
           "cxc-test-" (into-array java.nio.file.attribute.FileAttribute []))]
    (.toFile d)))

(deftest end-to-end-hello-world
  (testing "compile + run a hello-world cxc program"
    (let [src "def main:
    -> running
        do: print(\"hello, world\")
"
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "hello" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "hello, world\n" stdout)))))))

(deftest end-to-end-different-message
  (testing "different string literal round-trips through the whole pipeline"
    (let [src "def main:
    -> running
        do: print(\"cxc is running\")
"
          out-dir (tmp-dir)
          {:keys [status bin-path]}
          (build/compile-source-to-file src "greet" (.getPath out-dir))]
      (is (zero? status))
      (when (zero? status)
        (is (= "cxc is running\n" (:stdout (build/run-binary bin-path))))))))

(deftest end-to-end-arithmetic-in-print
  (testing "int binops through print — precedence and result"
    (let [src "def main:
    -> running
        do: print(3 + 4 * 2)
"
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "arith" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (is (= "11\n" (:stdout (build/run-binary bin-path))))))))

(deftest end-to-end-record-construction-and-field-access
  (testing "record → C typedef struct; V(x:,y:).x is usable in an int expr"
    (let [src "record V:
    data:
        x: Int
        y: Int

def main:
    -> running
        do: print(V(x: 3, y: 4).x + 100)
"
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "rec" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (is (= "103\n" (:stdout (build/run-binary bin-path))))))))

(deftest end-to-end-nested-record-field-chain
  (testing "record-of-record; .a.b field chain works"
    (let [src "record Point:
    data:
        x: Float
        y: Float

record Line:
    data:
        start: Point
        stop:  Point

def main:
    -> running
        do: print(Line(start: Point(x: 1.5, y: 2.0), stop: Point(x: 3.0, y: 4.0)).start.x)
"
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "nest" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (is (= "1.500000\n" (:stdout (build/run-binary bin-path))))))))
