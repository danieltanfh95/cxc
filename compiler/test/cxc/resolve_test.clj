(ns cxc.resolve-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as parse]
            [cxc.prelude :as prelude]
            [cxc.resolve :as resolve]))

(defn- table-from
  "Parse `src` (user source), prepend prelude, run the same rename
   postwalk `lower` sees, and build the symbol table over it. Mirrors
   what `cxc.lower/lower` does internally so table shape matches
   what downstream landings will consume."
  [user-src]
  (let [combined  (parse/parse (str prelude/source user-src))
        renamed   (prelude/rewrite-prelude-names combined)]
    (resolve/build-symbol-table renamed)))

(deftest table-carries-prelude-provenance
  (testing "F51 — each prelude declaration is attributed to its owning std.* module"
    (let [table (table-from "def main:\n    -> running\n        do: print(\"hi\")\n")]
      ;; Post-rename, prelude Duration is _Cxc_Duration in the AST;
      ;; provenance lookup handles both forms.
      (is (= :std.time (get-in table ["_Cxc_Duration" :module])))
      (is (= :std.time (get-in table ["_Cxc_Instant"  :module])))
      (is (= :std.geom (get-in table ["_Cxc_Position" :module])))
      (is (= :std.input (get-in table ["_Cxc_Key"     :module])))
      (is (= :std.input (get-in table ["_Cxc_TestKeyboardInput" :module])))
      (is (= :std.io   (get-in table ["_Cxc_Console"  :module])))
      (is (= :std.clock (get-in table ["_Cxc_Clock"   :module])))
      (is (= :std.random (get-in table ["_Cxc_Random" :module])))
      (is (= :std.ticker (get-in table ["_Cxc_Ticker" :module]))))))

(deftest table-carries-user-provenance
  (testing "F51 — user declarations get :user provenance"
    (let [table (table-from "record V:\n    data:\n        x: Int\n\ndef main:\n    -> running\n")]
      (is (= :user (get-in table ["V" :module])))
      (is (= :user (get-in table ["main" :module]))))))

(deftest table-tags-kind
  (testing "F51 — each entry's :kind matches its declaration kind"
    (let [table (table-from "record V:\n    data:\n        x: Int\n\nenum E:\n    a\n    b\n\ndef main:\n    -> running\n")]
      (is (= :record   (get-in table ["V"    :kind])))
      (is (= :enum     (get-in table ["E"    :kind])))
      (is (= :def-atom (get-in table ["main" :kind]))))))

(deftest table-carries-record-members
  (testing "F51 — records expose their fields + methods in :members"
    (let [table (table-from "record V:\n    data:\n        x: Int\n        y: Int\n    methods:\n        function sum(self) -> Int:\n            return x + y\n\ndef main:\n    -> running\n")
          v     (get table "V")
          fs    (get-in v [:members :fields])
          ms    (get-in v [:members :methods])]
      (is (= ["x" "y"] (mapv :name fs)))
      (is (= ["sum"]   (mapv :name ms))))))

(deftest table-carries-atom-members
  (testing "F51 — atoms expose data-fields + transitions + observables"
    (let [table (table-from "def Counter:\n    data:\n        n: Int = 0\n\n    tally :< n\n\n    -> running\n\n    running <: bump -> self\n        then: n => n + 1\n\ndef main:\n    def c: Counter\n\n    -> running\n")
          counter (get table "Counter")]
      (is (= ["n"]           (mapv :name (get-in counter [:members :data-fields]))))
      (is (>= (count (get-in counter [:members :transitions])) 2)
          "Counter has at least two transitions: -> running and running <: bump"))))

(deftest table-carries-enum-cases
  (testing "F51 — enums expose their case list"
    (let [table (table-from "enum Color:\n    red\n    green\n    blue\n\ndef main:\n    -> running\n")]
      (is (= ["red" "green" "blue"]
             (get-in table ["Color" :members :cases]))))))

(deftest lookup-returns-entry-or-nil
  (testing "F51 — `lookup` fetches by name; nil for unknown"
    (let [table (table-from "record V:\n    data:\n        x: Int\n\ndef main:\n    -> running\n")
          ctx   {:symbol-table table}]
      (is (some? (resolve/lookup ctx "V")))
      (is (nil?  (resolve/lookup ctx "Nonexistent"))))))

(deftest lookup-member-drills-into-declaration
  (testing "F51 — `lookup-member` finds a member by kind + name inside a decl"
    (let [table (table-from "record V:\n    data:\n        x: Int\n    methods:\n        function bump(self) -> Int:\n            return x\n\ndef main:\n    -> running\n")
          ctx   {:symbol-table table}]
      (is (= "bump" (:name (resolve/lookup-member ctx "V" :methods "bump"))))
      (is (= "x"    (:name (resolve/lookup-member ctx "V" :fields  "x"))))
      (is (nil?     (resolve/lookup-member ctx "V" :methods "nope"))))))
