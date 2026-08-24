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

(deftest nested-named-tuple-mangles-with-Nt-count-prefix
  (testing "F15 L2.b — a nested named-tuple field (`(outer: (inner: Int))`) mangles as `Nt<count>_<segments>` where count is the nested field count in decimal digits. The count bounds segment consumption unambiguously so a downstream parser knows exactly when the nested type ends and the parent's next field resumes."
    (is (= "_Cxc_NT_outer_Nt1_inner_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "outer"
                 :type (nt-node "inner" "Int")}]))))))

(deftest nested-named-tuple-with-multiple-inner-fields
  (testing "F15 L2.b — nested named-tuple with multiple inner fields: count reflects the inner-field count, and inner fields sort alphabetically (same discipline as top-level)."
    (is (= "_Cxc_NT_pt_Nt2_x_Int_y_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "pt"
                 :type (nt-node "y" "Int" "x" "Int")}]))))))

(deftest nested-named-tuple-recurses
  (testing "F15 L2.b — nesting recurses unboundedly. A nested-in-nested shape mangles as `Nt<count>_...Nt<count>_...`; each level's count bounds its own segments so the whole form is unambiguous."
    (is (= "_Cxc_NT_a_Nt1_b_Nt1_c_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "a"
                 :type (nt-node-with-fields
                         [{:name "b"
                           :type (nt-node "c" "Int")}])}]))))))

(deftest nested-empty-tuple-throws
  (testing "F15 L2.b — an empty nested named-tuple has no canonical form (same rationale as the top-level empty rejection): would produce `Nt0_` which is ambiguous with a top-level empty inside a parent. Throw so the caller emits a diagnostic."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"nested empty named-tuple shape"
          (nt/canonical-named-tuple-name
            (nt-node-with-fields
              [{:name "outer"
                :type {:node :type-named-tuple :fields []}}]))))))

(defn- type-of [outer inner]
  {:node :type-of :outer outer :inner inner})

(deftest parametric-array-of-int-mangles-with-Of1-prefix
  (testing "F15 L2.b.ii — single-arg parametric field (`items: Array of Int`) mangles as `Of1_<outer>_<inner-mangling>`. Arity marker (`Of1`) tells parsers to consume exactly one bounded type argument after the outer name; outer name follows directly, then the recursively-mangled inner type."
    (is (= "_Cxc_NT_items_Of1_Array_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "items" :type (type-of "Array" (ref "Int"))}]))))))

(deftest parametric-optional-mangles-similarly
  (testing "F15 L2.b.ii — the scheme is uniform across parametric outer names. `Optional of Int` uses the same `Of1_<outer>_<inner>` form as `Array of Int`."
    (is (= "_Cxc_NT_maybe_Of1_Optional_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "maybe" :type (type-of "Optional" (ref "Int"))}]))))))

(deftest parametric-with-nonnil-inner
  (testing "F15 L2.b.ii — the inner type recursively mangles, so a non-nil inner (`Array of Int!`) picks up the `Nn_` prefix. Composes naturally with L2.a's non-nil handling."
    (is (= "_Cxc_NT_items_Of1_Array_Nn_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "items"
                 :type (type-of "Array" (nonnil (ref "Int")))}]))))))

(deftest parametric-inside-nested-named-tuple
  (testing "F15 L2.b.ii — parametric fields can appear inside nested named tuples. Each layer's bounded markers (Nt<count>, Of<arity>) compose so the full form remains unambiguous."
    (is (= "_Cxc_NT_wrapper_Nt1_items_Of1_Array_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "wrapper"
                 :type (nt-node-with-fields
                         [{:name "items"
                           :type (type-of "Array" (ref "Int"))}])}]))))))

(deftest parametric-nested-parametric
  (testing "F15 L2.b.ii — parametric-of-parametric (`Array of Array of Int`) recurses through the same Of1 shape; each Of1 consumes exactly one inner, which is itself a bounded mangling."
    (is (= "_Cxc_NT_grid_Of1_Array_Of1_Array_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "grid"
                 :type (type-of "Array"
                                (type-of "Array" (ref "Int")))}]))))))

(deftest parametric-outer-with-underscore-throws
  (testing "F15 L2.b.ii — parametric outer names containing `_` would confuse the segment separator. Reject at the mangling site until the scheme extension in a follow-on sub-landing."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"parametric outer name `My_Box` cannot contain `_`"
          (nt/canonical-named-tuple-name
            (nt-node-with-fields
              [{:name "b" :type (type-of "My_Box" (ref "Int"))}]))))))

(deftest parametric-reserved-outer-throws
  (testing "F15 L2.b.ii — a parametric outer name matching a reserved mangling prefix (`Nn_`, `_Cxc_NT`, `Nt<digits>`, `Of<digits>`) collides with the compiler's internal markers. Throw at the mangling site so the collision surfaces loudly."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"parametric outer name `Of1` collides"
          (nt/canonical-named-tuple-name
            (nt-node-with-fields
              [{:name "b" :type (type-of "Of1" (ref "Int"))}]))))))

(defn- type-tuple [& types]
  {:node :type-tuple :types (vec types)})

(deftest parametric-map-of-kv-arity-2
  (testing "F15 L2.b.iii — `Map of (K, V)` mangles as `Of2_Map_<K>_<V>`. Arity marker (2) tells parsers to consume exactly two bounded type arguments after the outer name; each argument recurses through mangle-type independently."
    (is (= "_Cxc_NT_m_Of2_Map_String_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "m" :type (type-of "Map"
                                          (type-tuple (ref "String")
                                                      (ref "Int")))}]))))))

(deftest parametric-arity-3
  (testing "F15 L2.b.iii — the arity marker scales beyond 2. `Triple of (A, B, C)` mangles with `Of3_` and three inner segments. Uniform scheme across all arities."
    (is (= "_Cxc_NT_t_Of3_Triple_Int_String_Bool"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "t" :type (type-of "Triple"
                                          (type-tuple (ref "Int")
                                                      (ref "String")
                                                      (ref "Bool")))}]))))))

(deftest parametric-arity-2-with-non-nil-inner
  (testing "F15 L2.b.iii — each element of a multi-arg parametric recurses through mangle-type, so a non-nil element picks up the `Nn_` prefix. `Map of (String, Int!)` composes cleanly."
    (is (= "_Cxc_NT_m_Of2_Map_String_Nn_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "m" :type (type-of "Map"
                                          (type-tuple (ref "String")
                                                      (nonnil (ref "Int"))))}]))))))

(deftest parametric-arity-2-with-nested-parametric-value
  (testing "F15 L2.b.iii — nested parametric elements work: `Map of (String, Array of Int)` mangles with the inner Array participating as its own `Of1_Array_Int` element. Recursion composes uniformly."
    (is (= "_Cxc_NT_m_Of2_Map_String_Of1_Array_Int"
           (nt/canonical-named-tuple-name
             (nt-node-with-fields
               [{:name "m" :type (type-of "Map"
                                          (type-tuple (ref "String")
                                                      (type-of "Array" (ref "Int"))))}]))))))

(deftest parametric-arity-1-explicit-tuple-shape
  (testing "F15 L2.b.iii — a parametric wrapped as a single-element tuple `Wrapper of (T,)` mangles identically to `Wrapper of T` (both `Of1_Wrapper_T`). Retains injectivity — the two AST shapes represent the same type-level meaning."
    (let [tuple-arity-1 (nt/canonical-named-tuple-name
                          (nt-node-with-fields
                            [{:name "w" :type (type-of "Wrapper"
                                                       (type-tuple (ref "Int")))}]))
          plain-arity-1 (nt/canonical-named-tuple-name
                          (nt-node-with-fields
                            [{:name "w" :type (type-of "Wrapper" (ref "Int"))}]))]
      (is (= "_Cxc_NT_w_Of1_Wrapper_Int" plain-arity-1))
      (is (= plain-arity-1 tuple-arity-1)
          "single-element tuple and plain single-inner shape produce the same mangling"))))

(deftest parametric-empty-tuple-inner-throws
  (testing "F15 L2.b.iii — a parametric with an empty type-tuple inner (`Foo of ()`) is degenerate; throw so the caller emits a diagnostic rather than producing an `Of0_Foo` mangling with zero type args."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"empty type-tuple inner is degenerate"
          (nt/canonical-named-tuple-name
            (nt-node-with-fields
              [{:name "f" :type (type-of "Foo" (type-tuple))}]))))))

(deftest reserved-type-name-collision-throws
  (testing "F15 L2.b — user type names matching a reserved F15 mangling prefix collide with the compiler's internal markers. Reserved prefixes: `Nn_` (non-nil marker), `_Cxc_NT` (top-level + nested-tuple prefix), `Nt<digits>` (nested-tuple length marker, L2.b), `Of<digits>` (parametric-arity marker, L2.b.ii). Throw at the mangling site so collisions surface before C emit produces an invalid struct definition."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"collides with a reserved F15 mangling prefix"
          (nt/canonical-named-tuple-name
            (nt-node "x" "Nn_Int"))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"collides with a reserved F15 mangling prefix"
          (nt/canonical-named-tuple-name
            (nt-node "x" "Nt3_field_Int"))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"collides with a reserved F15 mangling prefix"
          (nt/canonical-named-tuple-name
            (nt-node "x" "Of1_Array_Int"))))))

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
