(ns cxc.build-test
  "End-to-end regression: cxc source → binary → run → verify stdout."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
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
