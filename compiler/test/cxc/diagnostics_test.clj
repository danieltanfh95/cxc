(ns cxc.diagnostics-test
  "Phase 1a coverage for cxc.diagnostics. Verifies the extended ex-info
   data shape, location extraction, backward-compat on existing call-site
   signatures, and the JSON serializer. Phase 1b will add fixture coverage
   per rule ID from design/plans/diagnostic-pattern-dictionary.md."
  (:require [clojure.test :refer [deftest testing is]]
            [cxc.diagnostics :as d]))

(defmacro ^:private caught-ex [& body]
  `(try ~@body (catch Exception e# e#)))

(deftest location-from-node-test
  (testing "extracts :line and :col from an AST node"
    (is (= {:line 42 :col 7}
           (d/location-from-node {:node :raise :line 42 :col 7}))))
  (testing "includes :file when *current-file* is bound"
    (binding [d/*current-file* "cards.cxc"]
      (is (= {:file "cards.cxc" :line 42 :col 7}
             (d/location-from-node {:node :raise :line 42 :col 7})))))
  (testing "returns nil when node has no location and no file bound"
    (is (nil? (d/location-from-node {:node :raise}))))
  (testing "returns file-only map when node has no location but file bound"
    (binding [d/*current-file* "x.cxc"]
      (is (= {:file "x.cxc"}
             (d/location-from-node {:node :raise})))))
  (testing "handles nil node"
    (is (nil? (d/location-from-node nil))))
  (testing "hickey-cycle-284 — parser-convention `:src-line` is honored"
    ;; Pre-cycle, `location-from-node` read only `:line`. The parser
    ;; mostly stamps `:src-line` via `t-line`, so lower-layer errors
    ;; carried no location through this function. :line fallback
    ;; stays available for hand-built nodes and test fixtures.
    (is (= {:line 99}
           (d/location-from-node {:node :raise :src-line 99})))
    (is (= {:line 7 :col 4}
           (d/location-from-node {:node :raise :src-line 7 :col 4})))))

(deftest fail-nyi-backward-compat-test
  (testing "two-arg form still works unchanged (existing call sites)"
    (let [ex (caught-ex (d/fail-nyi! "foo surface" {:node :raise :line 10 :col 2}))
          data (ex-data ex)]
      (is (= :not-yet-implemented (:kind data)))
      (is (= "foo surface" (:what data)))
      (is (= {:line 10 :col 2} (:location data)))
      (is (= "NYI-001" (:rule-ref data)))
      (is (= "lowering not yet implemented: foo surface" (ex-message ex))))))

(deftest fail-nyi-with-opts-test
  (testing "opts can override rule-ref and add suggested-fix"
    (let [ex (caught-ex (d/fail-nyi! "bar" {:line 5 :col 1}
                                     {:rule-ref "NYI-002"
                                      :suggested-fix {:kind :advice
                                                      :text "see FL-3 tracker"}}))
          data (ex-data ex)]
      (is (= "NYI-002" (:rule-ref data)))
      (is (= :advice (get-in data [:suggested-fix :kind])))
      (is (= "see FL-3 tracker" (get-in data [:suggested-fix :text]))))))

(deftest fail-user-backward-compat-test
  (testing "two-arg form unchanged"
    (let [ex (caught-ex (d/fail-user! "duplicate field `x`" {:line 20 :col 3}))
          data (ex-data ex)]
      (is (= :cxc-user-error (:kind data)))
      (is (= "duplicate field `x`" (:what data)))
      (is (= {:line 20 :col 3} (:location data)))
      (is (= "duplicate field `x`" (ex-message ex))))))

(deftest fail-user-with-opts-test
  (testing "opts carries rule-ref and :replace fix"
    (let [ex (caught-ex (d/fail-user! "nullable in non-nil slot"
                                     {:line 7 :col 4}
                                     {:rule-ref "NUL-001"
                                      :suggested-fix {:kind :replace
                                                      :range {:start {:line 7 :col 10}
                                                              :end {:line 7 :col 11}}
                                                      :new-text "T!"}}))
          data (ex-data ex)]
      (is (= "NUL-001" (:rule-ref data)))
      (is (= :replace (get-in data [:suggested-fix :kind])))
      (is (= "T!" (get-in data [:suggested-fix :new-text]))))))

(deftest fail-compile-backward-compat-test
  (testing "three-arg form unchanged, :kind carried through"
    (let [ex (caught-ex (d/fail-compile! :cxc-f9-signature-mismatch
                                        "sig mismatch"
                                        {:line 100 :col 5}))
          data (ex-data ex)]
      (is (= :cxc-f9-signature-mismatch (:kind data)))
      (is (= "sig mismatch" (:what data)))
      (is (= {:line 100 :col 5} (:location data))))))

(deftest current-file-binding-test
  (testing "*current-file* propagates to :file and :location"
    (binding [d/*current-file* "cards.cxc"]
      (let [ex (caught-ex (d/fail-user! "bad" {:line 1 :col 1}))
            data (ex-data ex)]
        (is (= "cards.cxc" (:file data)))
        (is (= "cards.cxc" (get-in data [:location :file])))))))

(deftest json-diagnostic-nyi-test
  (testing "serializer handles NYI with rule-ref default"
    (let [ex (caught-ex (d/fail-nyi! "foo" {:line 10 :col 2}))
          j (d/->json-diagnostic ex)]
      (is (= "not-yet-implemented" (:kind j)))
      (is (= "foo" (:message j)))
      (is (= {:line 10 :col 2} (:location j)))
      (is (= "NYI-001" (:rule_ref j)))
      (is (not (contains? j :suggested_fix))))))

(deftest json-diagnostic-user-with-fix-test
  (testing "serializer emits :replace fix as snake_case"
    (let [ex (caught-ex (d/fail-user! "nullable in non-nil slot"
                                     {:line 7 :col 4}
                                     {:rule-ref "NUL-001"
                                      :suggested-fix {:kind :replace
                                                      :range {:start {:line 7 :col 10}
                                                              :end {:line 7 :col 11}}
                                                      :new-text "T!"}}))
          j (d/->json-diagnostic ex)]
      (is (= "cxc-user-error" (:kind j)))
      (is (= "nullable in non-nil slot" (:message j)))
      (is (= "NUL-001" (:rule_ref j)))
      (is (= {:kind "replace"
              :range {:start {:line 7 :col 10} :end {:line 7 :col 11}}
              :new_text "T!"}
             (:suggested_fix j))))))

(deftest json-diagnostic-advice-test
  (testing "serializer emits :advice fix"
    (let [ex (caught-ex (d/fail-user! "end phase misnamed"
                                     {:line 50 :col 1}
                                     {:rule-ref "PHA-002"
                                      :suggested-fix {:kind :advice
                                                      :text "rename to `end`"}}))
          j (d/->json-diagnostic ex)]
      (is (= {:kind "advice" :text "rename to `end`"}
             (:suggested_fix j))))))

(deftest json-diagnostic-nil-safety-test
  (testing "nil node, nil fix, nil rule-ref all drop out cleanly"
    (let [ex (caught-ex (d/fail-user! "stray thing" nil))
          j (d/->json-diagnostic ex)]
      (is (= "cxc-user-error" (:kind j)))
      (is (= "stray thing" (:message j)))
      (is (not (contains? j :location)))
      (is (not (contains? j :rule_ref)))
      (is (not (contains? j :suggested_fix))))))
