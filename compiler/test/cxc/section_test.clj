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

;; ---------------------------------------------------------------------------
;; F8 D5 — protected section-header names. Per decisions-frozen §12 the
;; compiler-baked behavioral sections (`computed`, `invariants`,
;; `metadata`, `deprecated`, `use`) share the registry with user-declared
;; `section class Name:` entries but cannot be shadowed by `alias` or by
;; user `section class` declarations.
;; ---------------------------------------------------------------------------

(deftest protected-set-covers-compiler-baked-behavioral-sections
  (testing "F8 D5 — every compiler-baked behavioral section header (L3a-d + use) is a protected name. Extend when new behavioral sections ship."
    (is (sec/protected? :computed))
    (is (sec/protected? :invariants))
    (is (sec/protected? :metadata))
    (is (sec/protected? :deprecated))
    (is (sec/protected? :use))))

(deftest protected-check-is-case-insensitive
  (testing "F8 D5 — protected? normalizes the input so `Computed`, `COMPUTED`, `\"computed\"` all resolve to the same protected slot. Prevents case-sensitivity accidents from bypassing the reservation."
    (is (sec/protected? "Computed"))
    (is (sec/protected? "COMPUTED"))
    (is (sec/protected? "computed"))
    (is (sec/protected? :Computed))))

(deftest protected-check-rejects-unrelated-names
  (testing "F8 D5 — a name that isn't in the protected set (e.g. `data`, `state`, `static`, or any user-picked name) returns false so it stays available for `alias` and `section class`."
    (is (not (sec/protected? :data)))
    (is (not (sec/protected? :state)))
    (is (not (sec/protected? :static)))
    (is (not (sec/protected? :my_custom_section)))))

(deftest registry-seeded-with-protected-reservations
  (testing "F8 D5 + L3b.i — the registry is seeded with a protected entry for every protected name. Names still awaiting backend wire-in carry `:kind :reserved` + `:spec nil`; names whose backend has landed (L3b.i: `invariants` → F49 machinery) carry `:kind :behavioral` + a spec pointer. The seed guarantees `alias X:` sees a collision regardless of wire-in state."
    ;; Reserved-shape entries: backend not yet claimed via the registry.
    (let [e (sec/lookup :computed)]
      (is (some? e))
      (is (= :computed (:name e)))
      (is (= :reserved (:kind e))))
    (is (= :reserved (:kind (sec/lookup :metadata))))
    (is (= :reserved (:kind (sec/lookup :deprecated))))
    (is (= :reserved (:kind (sec/lookup :use))))
    ;; L3b.i behavioral-shape entry.
    (is (= :behavioral (:kind (sec/lookup :invariants))))))

(deftest invariants-seed-records-f49-backend
  (testing "F8 L3b.i — the `:invariants` seed carries a spec pointer describing where the compiler already handles the section: F49 invariant-check machinery on the lower side, `parse-invariants-section` on the parse side. Downstream (L3b.ii) can consult this to route parser dispatch through the registry without re-hardcoding the section-name keyword. Case-insensitive lookup preserved via `normalize-name` in `lookup`."
    (let [e (sec/lookup :invariants)]
      (is (= :behavioral (:kind e)))
      (is (= :compiler-baked (get-in e [:spec :impl])))
      (is (= :f49-invariant-machinery (get-in e [:spec :backend])))
      (is (= 'cxc.parse/parse-invariants-section
             (get-in e [:spec :parser-sym]))))
    ;; Same shape survives the case-insensitive lookup path.
    (is (= :behavioral (:kind (sec/lookup "Invariants"))))
    (is (= :behavioral (:kind (sec/lookup :Invariants))))))

(deftest register-rejects-protected-name-shadow
  (testing "F8 D5 — `register!` hard-errors when the caller passes a protected section-header name. This is the guard behind `alias computed: runtime immutable identity` at parse time (via register-section-header-alias!) and behind the future L4 `section class Computed:` user syntax. Diagnostic names the reserved header and lists the full protected set."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"F8 D5: `computed` is a protected section-header name"
          (sec/register! :computed
                        {:kind :primitive-composition
                         :spec (sec/compose [:runtime :immutable])})))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"F8 D5: `invariants` is a protected section-header name"
          (sec/register! "Invariants"
                        {:kind :primitive-composition
                         :spec (sec/compose [:runtime])})))))

(deftest register-section-header-alias-rejects-protected-name
  (testing "F8 D5 — `alias computed: ...` at parse time flows through `register-section-header-alias!` → `register!` → the protected-name guard. User sees a targeted diagnostic instead of silently overwriting the behavioral-section entry."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"F8 D5: `computed` is a protected section-header name"
          (sec/register-section-header-alias!
            {:name :computed :primitives [:runtime :immutable]})))))

(deftest register-allows-unprotected-name
  (testing "F8 D5 — a name outside the protected set registers normally. Ensures the guard only fires on the reserved slots; user-picked names stay available."
    (is (some? (sec/register! :my_custom
                              {:kind :primitive-composition
                               :spec (sec/compose [:runtime :immutable])})))))

(deftest clear-registry-restores-protected-reservations
  (testing "F8 D5 + L3b.i — the test-helper `clear-registry!` wipes user entries but re-seeds the protected reservations, so tests always start from a state that already reflects the compiler-baked guarantees. The behavioral seed for `:invariants` (L3b.i) is part of that guarantee — clearing must not regress it back to `:reserved`."
    ;; Register a user entry.
    (sec/register! :userthing {:kind :primitive-composition
                              :spec (sec/compose [:runtime])})
    (is (some? (sec/lookup :userthing)))
    ;; Wipe and re-seed.
    (sec/clear-registry!)
    (is (nil? (sec/lookup :userthing))
        "user entries gone after clear")
    (is (= :reserved (:kind (sec/lookup :computed)))
        ":reserved protected entries still present after clear")
    (is (= :behavioral (:kind (sec/lookup :invariants)))
        "L3b.i behavioral seed still present after clear")))
