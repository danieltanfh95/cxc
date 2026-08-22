(ns cxc.section-test
  "F8 L1-a — Section-class primitive vocabulary + composition.

   Covers the compose function: cross-axis merges work, same-axis
   collisions error, unknown primitives error, meaningless-but-legal
   pairs warn, canonical `data:` / `state:` / `static:` compositions
   from spec §4.10.2 produce the expected contribution maps."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cxc.section :as sec]))

(use-fixtures :each (fn [f] (sec/clear-registry!) (f)))

;; ---------------------------------------------------------------------------
;; Vocabulary
;; ---------------------------------------------------------------------------

(deftest primitive-vocabulary-covers-spec-4-10-1
  (testing "spec §4.10.1 Stage-1 primitives all defined"
    (doseq [p [:runtime :compile_time :immutable :mutable
               :expose :weak :unowned :identity :coordination]]
      (is (some? (sec/primitive p)) (str "missing: " p)))))

(deftest primitive-lookup-case-insensitive
  (testing "prose form `Runtime` and header form `runtime` both resolve"
    (is (= (sec/primitive :runtime) (sec/primitive "Runtime")))
    (is (= (sec/primitive :runtime) (sec/primitive 'RUNTIME)))))

;; ---------------------------------------------------------------------------
;; Composition — spec §4.10.2 canonical aliases
;; ---------------------------------------------------------------------------

(deftest compose-data-alias-matches-spec
  (testing "`data: = runtime immutable identity` per §4.10.2"
    (let [{:keys [primitives contributes]}
          (sec/compose [:runtime :immutable :identity])]
      (is (= [:runtime :immutable :identity] primitives))
      (is (= :runtime   (:default-existence contributes)))
      (is (= :immutable (:default-mutability contributes)))
      (is (= true       (:contributes-to-equality contributes))))))

(deftest compose-state-alias-matches-spec
  (testing "`state: = runtime mutable coordination` per §4.10.2"
    (let [{:keys [contributes]}
          (sec/compose [:runtime :mutable :coordination])]
      (is (= :runtime  (:default-existence contributes)))
      (is (= :mutable  (:default-mutability contributes)))
      (is (= false     (:contributes-to-equality contributes))))))

(deftest compose-static-alias-matches-spec
  (testing "`static: = compile_time immutable` per §4.10.2"
    (let [{:keys [contributes]}
          (sec/compose [:compile_time :immutable])]
      (is (= :compile-time (:default-existence contributes)))
      (is (= :immutable    (:default-mutability contributes))))))

(deftest compose-order-insensitive
  (testing "primitive order in header does not affect the merged spec"
    (is (= (:contributes (sec/compose [:runtime :immutable :identity]))
           (:contributes (sec/compose [:identity :runtime :immutable]))))))

;; ---------------------------------------------------------------------------
;; Contradictions — spec §4.10.1 rejects same-axis conflicts
;; ---------------------------------------------------------------------------

(deftest compose-rejects-mutability-conflict
  (testing "§4.10.1 — `immutable mutable` errors on the mutability axis"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"conflict on axis `mutability`"
         (sec/compose [:immutable :mutable])))))

(deftest compose-rejects-existence-conflict
  (testing "§4.10.1 — `runtime compile_time` errors on the existence axis"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"conflict on axis `existence`"
         (sec/compose [:runtime :compile_time])))))

(deftest compose-rejects-equality-contribution-conflict
  (testing "§4.10.1 — `identity coordination` errors"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"conflict on axis `equality-contribution`"
         (sec/compose [:identity :coordination])))))

(deftest compose-rejects-unknown-primitive
  (testing "unknown name → :cxc-f8-unknown-primitive diagnostic"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown section primitive `sealed`"
         (sec/compose [:sealed])))))

;; ---------------------------------------------------------------------------
;; Warnings — spec §4.10.1 meaningless-but-legal compositions
;; ---------------------------------------------------------------------------

(deftest compose-warns-compile-time-weak
  (testing "§4.10.1 — `compile_time weak` is legal but meaningless → warn"
    (let [{:keys [warnings]} (sec/compose [:compile_time :weak])]
      (is (= 1 (count warnings)))
      (is (= :cxc-f8-primitive-warn-composition (:kind (first warnings)))))))

(deftest compose-warns-identity-weak
  (testing "§4.10.1 — `identity weak` on a class field warns"
    (let [{:keys [warnings]} (sec/compose [:identity :weak])]
      (is (= 1 (count warnings))))))

(deftest compose-clean-has-no-warnings
  (testing "canonical `data:` composition warning-free"
    (is (empty? (:warnings (sec/compose [:runtime :immutable :identity]))))))

;; ---------------------------------------------------------------------------
;; Registry stub
;; ---------------------------------------------------------------------------

(deftest registry-round-trip
  (testing "register! + lookup — canonical name storage"
    (sec/register! :Foo {:kind :primitive-composition
                        :spec (sec/compose [:runtime :immutable])})
    (is (some? (sec/lookup :foo)))
    (is (some? (sec/lookup "FOO")))
    (is (= :foo (:name (sec/lookup :foo)))))

  (testing "unknown section names return nil"
    (is (nil? (sec/lookup :nonexistent)))))
