(ns cxc.shape-vars-test
  "F15 L3.α — pure shape-variable discovery walker tests."
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.shape-vars :as sv]))

(deftest empty-ast-yields-empty-map
  (testing "F15 L3.α — walking an empty vector / nil / map without :type-refs returns an empty sorted-map. Downstream unification treats empty as `no shape variables to unify`; no error."
    (is (= {} (sv/collect-shape-vars nil)))
    (is (= {} (sv/collect-shape-vars [])))
    (is (= {} (sv/collect-shape-vars {})))
    (is (= {} (sv/collect-shape-vars {:node :something :other :thing})))))

(deftest titlecase-type-refs-are-not-shape-vars
  (testing "F15 L3.α — TitleCase `:type-ref` names are concrete types per spec §1.11 convention, never shape variables. `Int`, `String`, `Point` all skip."
    (is (= {} (sv/collect-shape-vars {:node :type-ref :name "Int"})))
    (is (= {} (sv/collect-shape-vars {:node :type-ref :name "String"})))
    (is (= {} (sv/collect-shape-vars
               [{:node :type-ref :name "Int"}
                {:node :type-ref :name "Point"}
                {:node :type-ref :name "MyRecord"}])))))

(deftest lowercase-type-ref-binds-shape-var
  (testing "F15 L3.α — a lowercase `:type-ref` name binds a shape variable. The map value is the first-encountered binding node (with `:node` and `:name` preserved) so downstream can point at it for diagnostics."
    (let [node {:node :type-ref :name "a"}
          m    (sv/collect-shape-vars node)]
      (is (= #{"a"} (set (keys m))))
      (is (= node (get m "a"))))))

(deftest multiple-distinct-shape-vars-collect-independently
  (testing "F15 L3.α — every distinct lowercase name gets its own entry. `Map of (k, v)` produces both `k` and `v` in one walk."
    (let [ast [{:node :type-ref :name "k"}
               {:node :type-ref :name "v"}
               {:node :type-ref :name "s"}]
          m   (sv/collect-shape-vars ast)]
      (is (= #{"k" "s" "v"} (set (keys m))))
      (is (= "k" (:name (get m "k"))))
      (is (= "v" (:name (get m "v"))))
      (is (= "s" (:name (get m "s")))))))

(deftest duplicate-mentions-share-binding
  (testing "F15 L3.α — same-name subsequent mentions collapse to the first-visited node. L3.β's unification does the reference-tracking; this walker just enumerates the variables."
    (let [first-a {:node :type-ref :name "a"}
          second-a {:node :type-ref :name "a"}
          m       (sv/collect-shape-vars [first-a second-a])]
      (is (= #{"a"} (set (keys m))))
      ;; postwalk visits leaves before parents; both `a` nodes are
      ;; leaves so the FIRST one visited wins. Both are equal `=` so
      ;; observable identity aliases either.
      (is (= first-a (get m "a"))))))

(deftest nested-in-type-of-recurses
  (testing "F15 L3.α — `List of a` (a `:type-of` node) surfaces the inner shape variable `a`. postwalk reaches into `:inner`."
    (let [ast {:node :type-of :outer "List"
               :inner {:node :type-ref :name "a"}}
          m   (sv/collect-shape-vars ast)]
      (is (= #{"a"} (set (keys m)))))))

(deftest nested-in-type-tuple-recurses
  (testing "F15 L3.α — `Map of (k, v)` — the parametric's inner is a `:type-tuple` — surfaces both `k` and `v`."
    (let [ast {:node :type-of :outer "Map"
               :inner {:node :type-tuple
                       :types [{:node :type-ref :name "k"}
                               {:node :type-ref :name "v"}]}}
          m   (sv/collect-shape-vars ast)]
      (is (= #{"k" "v"} (set (keys m)))))))

(deftest nested-in-nonnil-and-nullable-recurses
  (testing "F15 L3.α — nullability wrappers don't hide shape variables. `a!` (`:type-nonnil`) and `a?` (`:type-nullable` — grammar-banned per Nullability rule but the walker handles both defensively) both surface `a`."
    (is (= #{"a"} (set (keys (sv/collect-shape-vars
                              {:node :type-nonnil
                               :inner {:node :type-ref :name "a"}})))))
    (is (= #{"a"} (set (keys (sv/collect-shape-vars
                              {:node :type-nullable
                               :inner {:node :type-ref :name "a"}})))))))

(deftest mixed-titlecase-and-lowercase-in-signature
  (testing "F15 L3.α — realistic signature-scope AST: `def foo(x: a, y: Int) -> Pair of (a, b)` — surfaces `a` and `b` while `Int` and `Pair` are skipped as concrete."
    (let [ast [{:node :field :name "x"
                :type {:node :type-ref :name "a"}}
               {:node :field :name "y"
                :type {:node :type-ref :name "Int"}}
               {:node :return-type
                :type {:node :type-of :outer "Pair"
                       :inner {:node :type-tuple
                               :types [{:node :type-ref :name "a"}
                                       {:node :type-ref :name "b"}]}}}]
          m   (sv/collect-shape-vars ast)]
      (is (= #{"a" "b"} (set (keys m)))))))

(deftest returns-sorted-map
  (testing "F15 L3.α — return value is a sorted-map so iteration is deterministic. Downstream warnings + diagnostics that list shape variables produce stable output."
    (let [m (sv/collect-shape-vars
             [{:node :type-ref :name "z"}
              {:node :type-ref :name "a"}
              {:node :type-ref :name "m"}])]
      (is (= ["a" "m" "z"] (vec (keys m)))))))
