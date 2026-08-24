(ns cxc.named-tuple-test
  "F15 L2.a — canonical named-tuple name mangling. Standalone pure-fn
   tests; no lower, no emit. L2.b onward wires the mangling into the
   type-check + C-emit sites."
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.named-tuple :as nt]))

(defn- nt-node [& field-pairs]
  {:node   :type-named-tuple
   :fields (mapv (fn [[name type-name]]
                   {:name name :type {:node :type-ref :name type-name}})
                 (partition 2 field-pairs))})

(defn- nt-node-with-fields [fields]
  {:node :type-named-tuple :fields fields})

(defn- ref [nm] {:node :type-ref :name nm})
(defn- nonnil [inner] {:node :type-nonnil :inner inner})

(deftest canonical-name-for-flat-primitive-shape
  (testing "F15 L2.a — canonical mangling for the simplest shape: two primitive fields in declared order."
    (is (= "_Cxc_NT_x_Int_y_Int"
           (nt/canonical-named-tuple-name (nt-node "x" "Int" "y" "Int"))))))

(deftest fields-sort-alphabetically-regardless-of-declared-order
  (testing "F15 L2.a — the ABI is order-insensitive per D4: `(y: Int, x: Int)` and `(x: Int, y: Int)` share the mangled name. Structural equivalence at the type level maps to identity at the C level, so both use-sites link against the same struct."
    (let [n-xy (nt/canonical-named-tuple-name (nt-node "x" "Int" "y" "Int"))
          n-yx (nt/canonical-named-tuple-name (nt-node "y" "Int" "x" "Int"))]
      (is (= n-xy n-yx))
      (is (= "_Cxc_NT_x_Int_y_Int" n-xy)))))

(deftest widths-are-distinct
  (testing "F15 L2.a — `Int32` and `Int64` mangle distinctly. Numeric width participates in the identity of the shape."
    (let [n-32 (nt/canonical-named-tuple-name (nt-node "n" "Int32"))
          n-64 (nt/canonical-named-tuple-name (nt-node "n" "Int64"))]
      (is (not= n-32 n-64))
      (is (= "_Cxc_NT_n_Int32" n-32))
      (is (= "_Cxc_NT_n_Int64" n-64)))))

(deftest non-nil-participates-via-Nn-prefix
  (testing "F15 L2.a — `T!` mangles as `Nn_<T>` prefix on the inner type. Non-nil and nullable shapes are distinct at the ABI (per §3.6.1 they're semantically different contracts)."
    (let [n-nullable (nt/canonical-named-tuple-name (nt-node "x" "Int"))
          n-nonnil   (nt/canonical-named-tuple-name
                       (nt-node-with-fields
                         [{:name "x" :type (nonnil (ref "Int"))}]))]
      (is (not= n-nullable n-nonnil))
      (is (= "_Cxc_NT_x_Int"    n-nullable))
      (is (= "_Cxc_NT_x_Nn_Int" n-nonnil)))))

(deftest mixed-nullability-and-fields
  (testing "F15 L2.a — multiple fields with mixed nullability sort by name and each carries its own `Nn_` prefix or absence. Order of declaration doesn't affect the result."
    (let [shape (nt-node-with-fields
                  [{:name "y" :type (ref "Int")}
                   {:name "x" :type (nonnil (ref "Int"))}])]
      (is (= "_Cxc_NT_x_Nn_Int_y_Int"
             (nt/canonical-named-tuple-name shape))))))

(deftest string-and-int-mixed
  (testing "F15 L2.a — heterogeneous primitive types (String + Int) mangle side-by-side. Confirms the scheme isn't accidentally numeric-only."
    (is (= "_Cxc_NT_count_Int_name_String"
           (nt/canonical-named-tuple-name
             (nt-node "name" "String" "count" "Int"))))))

(deftest empty-shape-throws-nyi
  (testing "F15 L2.a — an empty named-tuple shape (`()`) has no canonical form; throws NYI so the caller emits a diagnostic rather than producing `_Cxc_NT_` (which would collide with every other empty use)."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"empty named-tuple shape"
          (nt/canonical-named-tuple-name
            {:node :type-named-tuple :fields []})))))

(deftest nested-named-tuple-throws-nyi
  (testing "F15 L2.a — nested named-tuple fields (`(outer: (inner: Int))`) throw NYI. MVP covers flat named tuples of primitives; nesting requires a length-prefixed or bracketed scheme extended in a follow-on sub-landing. Throwing here beats silently producing an ambiguous mangling."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"nested named-tuple fields not supported in F15 L2.a"
          (nt/canonical-named-tuple-name
            (nt-node-with-fields
              [{:name "outer"
                :type (nt-node "inner" "Int")}]))))))

(deftest parametric-type-field-throws-nyi
  (testing "F15 L2.a — parametric-type fields (`items: Array of Int`) throw NYI. Same rationale as nested tuples: needs a scheme extension. Throwing surfaces the gap loudly."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"parametric-type fields.*not supported in F15 L2.a"
          (nt/canonical-named-tuple-name
            (nt-node-with-fields
              [{:name "items"
                :type {:node :type-of
                       :outer "Array"
                       :inner (ref "Int")}}]))))))

(deftest reserved-type-name-collision-throws
  (testing "F15 L2.a — a user type named `Nn_Int` or `_Cxc_NT_...` would collide with the compiler's reserved mangling markers. Throw at the mangling site so we discover the collision before the C emit produces an invalid struct definition. Longer-term the spec should prohibit those name shapes explicitly."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"collides with a reserved F15 L2.a mangling prefix"
          (nt/canonical-named-tuple-name
            (nt-node "x" "Nn_Int"))))))

(deftest reserved-field-name-collision-throws
  (testing "F15 L2.a — field names containing `_` or starting with `Nn` would confuse the mangling grammar (which uses `_` as segment separator and `Nn_` as a nonnil marker). Reject at the mangling site until L2.b extends the scheme."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"field name `raw_n` cannot contain `_`"
          (nt/canonical-named-tuple-name
            (nt-node "raw_n" "Int"))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"field name `Nn` cannot contain `_` or start with `Nn`"
          (nt/canonical-named-tuple-name
            (nt-node "Nn" "Int"))))))

(deftest non-named-tuple-input-throws
  (testing "F15 L2.a — passing a `:type-ref` (or anything other than `:type-named-tuple`) to the mangling fn is a caller bug; throw NYI with the observed node kind so the caller sees where the wrong node was passed."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"expected :type-named-tuple node"
          (nt/canonical-named-tuple-name (ref "Int"))))))

(deftest output-is-c-identifier-safe
  (testing "F15 L2.a — output uses only `[A-Za-z0-9_]` — legal in a C identifier. Matters because the mangled name is emitted as a `typedef struct` name and referenced across compilation units via `nm` / linkage."
    (let [n (nt/canonical-named-tuple-name
              (nt-node-with-fields
                [{:name "count" :type (ref "Int32")}
                 {:name "id"    :type (nonnil (ref "String"))}]))]
      (is (re-matches #"[A-Za-z_][A-Za-z0-9_]*" n))
      (is (= "_Cxc_NT_count_Int32_id_Nn_String" n)))))
