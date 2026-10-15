(ns cxc.section-test
  "F8 L1-a — Section-class primitive vocabulary + composition.

   Covers the compose function: cross-axis merges work, same-axis
   collisions error, unknown primitives error, meaningless-but-legal
   pairs warn, and representative internal compositions produce the
   expected contribution maps."
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
;; Composition
;; ---------------------------------------------------------------------------

(deftest compose-runtime-immutable-identity
  (testing "runtime + immutable + identity compose across three axes"
    (let [{:keys [primitives contributes]}
          (sec/compose [:runtime :immutable :identity])]
      (is (= [:runtime :immutable :identity] primitives))
      (is (= :runtime   (:default-existence contributes)))
      (is (= :immutable (:default-mutability contributes)))
      (is (= true       (:contributes-to-equality contributes))))))

(deftest compose-runtime-mutable-coordination
  (testing "runtime + mutable + coordination compose across three axes"
    (let [{:keys [contributes]}
          (sec/compose [:runtime :mutable :coordination])]
      (is (= :runtime  (:default-existence contributes)))
      (is (= :mutable  (:default-mutability contributes)))
      (is (= false     (:contributes-to-equality contributes))))))

(deftest compose-compile-time-immutable
  (testing "compile_time + immutable compose across two axes"
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
;; Registry lookup
;; ---------------------------------------------------------------------------

(deftest registry-lookup
  (testing "unknown section names return nil"
    (is (nil? (sec/lookup :nonexistent)))))

;; ---------------------------------------------------------------------------
;; F8 — compiler-baked section-header names. The compiler-baked behavioral
;; sections (`computed`, `invariants`, `metadata`, `deprecated`, `use`)
;; live in the internal registry as reserved / behavioral entries; the
;; user-declaration surface (`section class Name:`) was deleted at v1.0.
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
  (testing "F8 D5 — a name that isn't in the protected set (e.g. `data`, `state`, `static`, or any user-picked name) returns false so it stays available for internal composition or `section class`."
    (is (not (sec/protected? :data)))
    (is (not (sec/protected? :state)))
    (is (not (sec/protected? :static)))
    (is (not (sec/protected? :my_custom_section)))))

(deftest registry-seeded-with-protected-reservations
  (testing "F8 D5 + L3b.i + L3-use.i — the registry is seeded with a protected entry for every protected name. Names still awaiting backend wire-in carry `:kind :reserved` + `:spec nil`; landed backends carry `:kind :behavioral` + a spec pointer. Post annotation-grammar-retirement (2026-08-30) :metadata + :deprecated revert to :reserved."
    ;; Reserved-shape entries: backend not yet claimed via the registry.
    (let [e (sec/lookup :computed)]
      (is (some? e))
      (is (= :computed (:name e)))
      (is (= :reserved (:kind e))))
    (is (= :reserved (:kind (sec/lookup :metadata))))
    (is (= :reserved (:kind (sec/lookup :deprecated))))
    ;; Behavioral-shape entries.
    (is (= :behavioral (:kind (sec/lookup :invariants))))
    (is (= :behavioral (:kind (sec/lookup :use))))))

(deftest invariants-seed-records-f49-backend
  (testing "L2b — the `:invariants` seed retains its F49 backend pointer but is explicitly parser-less. The L2a positional parser produces normalized proposition nodes directly, so registry metadata cannot revive the retired header. Case-insensitive lookup remains preserved."
    (let [e (sec/lookup :invariants)]
      (is (= :behavioral (:kind e)))
      (is (= :compiler-baked (get-in e [:spec :impl])))
      (is (= :f49-invariant-machinery (get-in e [:spec :backend])))
      (is (true? (get-in e [:spec :parser-less?])))
      (is (nil? (get-in e [:spec :parser-sym]))))
    ;; Same shape survives the case-insensitive lookup path.
    (is (= :behavioral (:kind (sec/lookup "Invariants"))))
    (is (= :behavioral (:kind (sec/lookup :Invariants))))))

;; :deprecated + :metadata registry seeds retired 2026-08-30 alongside the
;; annotation-grammar retirement wave. Both entries flowed through
;; `parse-annotation` (also retired). :deprecated's K3 walker retired.
;; :metadata's F53 pipe was drift — F53 never consumed :annotations end-
;; to-end (verified during retirement audit). Kind info now via body-
;; shape inference (§3.3).

(deftest deprecated-metadata-seeds-retired
  (testing "annotation-grammar-retirement (2026-08-30) — :deprecated and :metadata are no longer behavioral seeds"
    (is (not= :behavioral (:kind (sec/lookup :deprecated))))
    (is (not= :behavioral (:kind (sec/lookup :metadata))))))

(deftest use-seed-records-k6-backend
  (testing "L4 — the `:use` seed points at the direct-line parser (no `:walker-sym` since capability extraction happens inline in lower, not as a post-parse walker). The parser preserves `:use-section`/`:caps`, so lower continues reading the same normalized shape."
    (let [e (sec/lookup :use)]
      (is (= :behavioral (:kind e)))
      (is (= :compiler-baked (get-in e [:spec :impl])))
      (is (= :k6-capability-extraction (get-in e [:spec :backend])))
      (is (= 'cxc.parse/parse-use-line
             (get-in e [:spec :parser-sym])))
      (is (nil? (get-in e [:spec :walker-sym]))))
    (is (= :behavioral (:kind (sec/lookup "Use"))))
    (is (= :behavioral (:kind (sec/lookup :Use))))))

(deftest behavioral-parser-skips-parser-less-invariants
  (testing "L2b — `behavioral-parser :invariants` returns nil at every case-normalized lookup, proving registry dispatch cannot accept the hard-retired section header."
    (is (nil? (sec/behavioral-parser :invariants)))
    (is (nil? (sec/behavioral-parser "Invariants")))
    (is (nil? (sec/behavioral-parser :Invariants)))))

(deftest behavioral-parser-resolves-use-only
  (testing "annotation-grammar-retirement (2026-08-30) — only :use survives as a behavioral-parser entry; :metadata + :deprecated retired with the annotation grammar"
    (is (= (requiring-resolve 'cxc.parse/parse-use-line)
           (sec/behavioral-parser :use)))
    (is (nil? (sec/behavioral-parser :metadata)))
    (is (nil? (sec/behavioral-parser :deprecated)))))

(deftest behavioral-parser-nil-for-reserved-entries
  (testing "F8 L3b.ii.α — a `:reserved` entry (no backend wired yet) resolves to nil. Caller uses nil to fall through to the direct hardcoded parser arm (until L3b.ii.β retires the hardcoded arms)."
    (is (nil? (sec/behavioral-parser :computed)))))

(deftest behavioral-parser-nil-for-unregistered-name
  (testing "F8 L3b.ii.α — names not in the registry resolve to nil. Caller doesn't have to pre-check `lookup` — passes any tokenized keyword through and gets nil for user-picked names too. Enables the parse-time dispatch to be a single arm regardless of whether a name is protected or user-declared."
    (is (nil? (sec/behavioral-parser :madeupthing)))
    (is (nil? (sec/behavioral-parser :nothingregistered)))))

(deftest behavioral-walker-nil-at-v1
  (testing "annotation-grammar-retirement (2026-08-30) — no registry seed carries a :walker-sym at v1.0; behavioral-walker returns nil for every name. Prior K3 :deprecated walker retired with the annotation grammar."
    (is (nil? (sec/behavioral-walker :deprecated)))
    (is (nil? (sec/behavioral-walker :metadata)))
    (is (nil? (sec/behavioral-walker :invariants)))
    (is (nil? (sec/behavioral-walker :use)))
    (is (nil? (sec/behavioral-walker :computed)))
    (is (nil? (sec/behavioral-walker :madeupthing)))))

(deftest behavioral-walkers-empty-at-v1
  (testing "annotation-grammar-retirement (2026-08-30) — `behavioral-walkers` returns an empty sorted-map at v1.0; the substrate stays for future walker landings. Prior compiler-baked K3 :deprecated walker retired with the annotation grammar."
    (let [m (sec/behavioral-walkers)]
      (is (map? m))
      (is (empty? m)))))

(deftest allowed-in-extend-only-invariants
  (testing "L2b — the `:invariants` registry marker records that direct `|:` propositions compose additively inside `extend Foo:` bodies. It is not a header-parser permission. `:use` and `:deprecated` remain false; reserved and unregistered names default to false."
    (is (true? (sec/allowed-in-extend? :invariants)))
    (is (true? (sec/allowed-in-extend? "Invariants")))
    (is (false? (sec/allowed-in-extend? :deprecated)))
    (is (false? (sec/allowed-in-extend? :use)))
    (is (false? (sec/allowed-in-extend? :computed)))
    (is (false? (sec/allowed-in-extend? :metadata)))
    (is (false? (sec/allowed-in-extend? :madeupthing)))))

(deftest clear-registry-restores-protected-reservations
  (testing "L3b.i — the test-helper `clear-registry!` re-seeds the compiler-baked reservations, so tests always start from a state that reflects the compiler-baked guarantees. The behavioral seed for `:invariants` (L3b.i) is part of that guarantee — clearing must not regress it back to `:reserved`. Post annotation-grammar-retirement (2026-08-30) :metadata + :deprecated are :reserved after clear."
    (sec/clear-registry!)
    (is (= :reserved (:kind (sec/lookup :computed)))
        ":reserved protected entries present after clear")
    (is (= :behavioral (:kind (sec/lookup :invariants)))
        "L3b.i behavioral seed present after clear")
    (is (= :reserved (:kind (sec/lookup :metadata)))
        "annotation-grammar-retirement — :metadata reverts to :reserved after clear")
    (is (= :reserved (:kind (sec/lookup :deprecated)))
        "annotation-grammar-retirement — :deprecated reverts to :reserved after clear")
    (is (= :behavioral (:kind (sec/lookup :use)))
        "L3-use.i behavioral seed present after clear")))
