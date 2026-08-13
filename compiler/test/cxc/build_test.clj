(ns cxc.build-test
  "End-to-end regression: cxc source → binary → run → verify stdout.

   Each source program lives as a standalone `.cxc` file under
   `compiler/examples/` so it can be read, hand-run, and iterated on
   outside the test harness. See `examples/README.md` for the current
   coverage table and how to add a new one."
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.build :as build]))

(defn- tmp-dir []
  (let [d (java.nio.file.Files/createTempDirectory
           "cxc-test-" (into-array java.nio.file.attribute.FileAttribute []))]
    (.toFile d)))

(defn- example [name] (slurp (str "examples/" name ".cxc")))

(defn- compile-and-run
  "Compile the named example, run the binary, return {:status :stdout}.
   Fails the surrounding test with clang stderr if compilation fails."
  [example-name]
  (let [src     (example example-name)
        out-dir (tmp-dir)
        {:keys [status stderr bin-path]}
        (build/compile-source-to-file src example-name (.getPath out-dir))]
    (is (zero? status) (str "clang failed for " example-name ":\n" stderr))
    (when (zero? status)
      (build/run-binary bin-path))))

(deftest end-to-end-hello-world
  (testing "hello.cxc — print of a string literal"
    (let [{:keys [exit stdout]} (compile-and-run "hello")]
      (is (zero? exit))
      (is (= "hello, world\n" stdout)))))

(deftest end-to-end-different-message
  (testing "greet.cxc — different string round-trips through the pipeline"
    (let [{:keys [exit stdout]} (compile-and-run "greet")]
      (is (zero? exit))
      (is (= "cxc is running\n" stdout)))))

(deftest end-to-end-arithmetic-in-print
  (testing "arithmetic.cxc — int binops through print; precedence preserved"
    (let [{:keys [exit stdout]} (compile-and-run "arithmetic")]
      (is (zero? exit))
      (is (= "11\n" stdout)))))

(deftest end-to-end-record-construction-and-field-access
  (testing "record-value.cxc — record → C struct; V(x:,y:).x + 100 = 103"
    (let [{:keys [exit stdout]} (compile-and-run "record-value")]
      (is (zero? exit))
      (is (= "103\n" stdout)))))

(deftest end-to-end-nested-record-field-chain
  (testing "nested-record.cxc — record-of-record; .a.b field chain works"
    (let [{:keys [exit stdout]} (compile-and-run "nested-record")]
      (is (zero? exit))
      (is (= "1.500000\n" stdout)))))

(deftest end-to-end-record-defaults
  (testing "record-defaults.cxc — omitted fields fall back to declared defaults"
    (let [{:keys [exit stdout]} (compile-and-run "record-defaults")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-def-atom-alloc-init
  (testing "atom-simple.cxc — def-atom → ObjC class; Counter() → alloc/init; .n → property"
    (let [{:keys [exit stdout]} (compile-and-run "atom-simple")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-record-methods
  (testing "record-methods.cxc — v.plus(other) → V_plus(v, other); result field access chains"
    (let [{:keys [exit stdout]} (compile-and-run "record-methods")]
      (is (zero? exit))
      (is (= "4.000000\n" stdout)))))

(deftest end-to-end-atom-ctor-params
  (testing "atom-ctor-params.cxc — Counter(initial: 42) → initWith selector; default fills for Counter()"
    (let [{:keys [exit stdout]} (compile-and-run "atom-ctor-params")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-nullable-nil-punning
  (testing "nullable-nil-punning.cxc — Counter?/nil in data; .n on nil pun-returns 0"
    (let [{:keys [exit stdout]} (compile-and-run "nullable-nil-punning")]
      (is (zero? exit))
      (is (= "0\n" stdout)))))
