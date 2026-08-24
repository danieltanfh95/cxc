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

;; ---------------------------------------------------------------------------
;; F15 L2.c.i — collect-shapes walker. Foundation for L2.c.ii typedef
;; emit; discovers every distinct named-tuple shape used in an AST
;; regardless of position (record field, method param, receive payload,
;; nested inside another tuple, nested inside a parametric).
;; ---------------------------------------------------------------------------

(deftest collect-shapes-empty-ast-returns-empty-map
  (testing "F15 L2.c.i — an AST with no named-tuple types produces an empty result. The walker doesn't fabricate entries; downstream typedef emit has nothing to do for such programs."
    (is (= {} (nt/collect-shapes {})))
    (is (= {} (nt/collect-shapes [])))
    (is (= {} (nt/collect-shapes {:node :record :name "R" :fields [{:name "n" :type (ref "Int")}]})))))

(deftest collect-shapes-finds-top-level-named-tuple
  (testing "F15 L2.c.i — a named-tuple at any position in the AST is discovered by the postwalk. Position doesn't matter; discovery is structural."
    (let [shape (nt-node "x" "Int" "y" "Int")
          ast   {:node :method :name "midpoint"
                 :params [{:name "p" :type shape}]
                 :return-type (ref "Float")}
          result (nt/collect-shapes ast)]
      (is (= 1 (count result)))
      (is (contains? result "_Cxc_NT_x_Int_y_Int"))
      (is (= shape (get result "_Cxc_NT_x_Int_y_Int"))))))

(deftest collect-shapes-dedupes-by-mangled-name
  (testing "F15 L2.c.i — two occurrences of the same shape (even at different positions) collapse to a single entry. The mangled name is the identity key; downstream typedef emit produces one struct per distinct name."
    (let [shape (nt-node "x" "Int" "y" "Int")
          ast   [{:node :method :name "a" :params [{:name "p" :type shape}]}
                 {:node :method :name "b" :params [{:name "q" :type shape}]}]
          result (nt/collect-shapes ast)]
      (is (= 1 (count result))
          "same shape at two positions → one entry"))))

(deftest collect-shapes-preserves-order-insensitive-identity
  (testing "F15 L2.c.i — two occurrences of the same shape with different declared field order share a mangled name (per L2.a alphabetical sort) and thus one entry. Structural equivalence at the type level maps to identity in the collection."
    (let [shape-xy (nt-node "x" "Int" "y" "Int")
          shape-yx (nt-node "y" "Int" "x" "Int")
          ast   [{:node :method :name "a" :params [{:name "p" :type shape-xy}]}
                 {:node :method :name "b" :params [{:name "q" :type shape-yx}]}]
          result (nt/collect-shapes ast)]
      (is (= 1 (count result)))
      (is (contains? result "_Cxc_NT_x_Int_y_Int")))))

(deftest collect-shapes-finds-nested-tuples-as-separate-entries
  (testing "F15 L2.c.i — a nested named-tuple field (`{outer: {inner: Int}}`) surfaces BOTH the outer and the inner shape as distinct entries. Postwalk visits inner nodes independently; downstream typedef emit can decide whether to inline the inner or reference its typedef, but the collector doesn't take a stance — it collects everything the emit pass might need."
    (let [inner (nt-node "inner" "Int")
          outer (nt-node-with-fields
                  [{:name "outer" :type inner}])
          result (nt/collect-shapes {:node :method :name "f"
                                     :params [{:name "p" :type outer}]})]
      (is (= 2 (count result)))
      (is (contains? result "_Cxc_NT_inner_Int"))
      (is (contains? result "_Cxc_NT_outer_Nt1_inner_Int")))))

(deftest collect-shapes-returns-sorted-map
  (testing "F15 L2.c.i — the returned map is sorted by mangled name for deterministic iteration order. Matters for typedef emit ordering (stable diffs) and test assertions."
    (let [shapes [(nt-node "z" "Int")
                  (nt-node "a" "Int")
                  (nt-node "m" "Int")]
          ast    (mapv (fn [s] {:node :method :params [{:name "p" :type s}]}) shapes)
          result (nt/collect-shapes ast)]
      (is (sorted? result))
      (is (= ["_Cxc_NT_a_Int" "_Cxc_NT_m_Int" "_Cxc_NT_z_Int"]
             (vec (keys result)))))))

(deftest collect-shapes-propagates-mangling-errors
  (testing "F15 L2.c.i — a shape that fails to mangle (e.g. reserved-name collision) surfaces the underlying `fail-nyi!` exception. Better to fail loudly during collection than to silently skip a shape the typedef pass would need to emit."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"collides with a reserved F15 mangling prefix"
          (nt/collect-shapes {:node :method
                              :params [{:name "p" :type (nt-node "x" "Nn_Int")}]})))))

;; ---------------------------------------------------------------------------
;; F15 L2.c.ii — emit typedefs for collected shapes. Pure fn; caller
;; supplies a primitive-name → C-type mapping so this ns stays free of
;; lower.clj's mapping logic. MVP: primitives + non-nil primitives as
;; field types.
;; ---------------------------------------------------------------------------

(def ^:private mvp-c-type
  ;; Minimal primitive→C-type table for tests. Real callers pass
  ;; lower.clj's `cxc-type->c` (or equivalent) at wire-in time.
  {"Int"     "int64_t"
   "Int32"   "int32_t"
   "Int64"   "int64_t"
   "String"  "NSString *"
   "Bool"    "bool"
   "Float"   "float"
   "Double"  "double"})

(deftest emit-typedefs-empty-map-produces-empty-string
  (testing "F15 L2.c.ii — no collected shapes → no typedefs emitted. Programs that don't use named tuples pay no cost in the emitted output."
    (is (= "" (nt/emit-typedefs {} mvp-c-type)))))

(deftest emit-typedefs-single-flat-shape
  (testing "F15 L2.c.ii — a single flat named-tuple shape emits one typedef with fields sorted alphabetically. Uses the caller-supplied primitive→c-type mapping to translate field types."
    (let [shapes (nt/collect-shapes {:node :method
                                     :params [{:name "p"
                                               :type (nt-node "x" "Int" "y" "Int")}]})
          out    (nt/emit-typedefs shapes mvp-c-type)]
      (is (= (str "typedef struct {\n"
                  "    int64_t x;\n"
                  "    int64_t y;\n"
                  "} _Cxc_NT_x_Int_y_Int;")
             out)))))

(deftest emit-typedefs-multiple-shapes-separated-by-blank-line
  (testing "F15 L2.c.ii — multiple shapes emit as separate typedefs joined by blank lines. Iteration follows sorted-map order (mangled name ascending) so diffs are stable across runs."
    (let [shapes (nt/collect-shapes
                   [{:node :method :params [{:name "p" :type (nt-node "b" "Int")}]}
                    {:node :method :params [{:name "q" :type (nt-node "a" "String")}]}])
          out    (nt/emit-typedefs shapes mvp-c-type)]
      (is (= (str "typedef struct {\n"
                  "    NSString * a;\n"
                  "} _Cxc_NT_a_String;\n\n"
                  "typedef struct {\n"
                  "    int64_t b;\n"
                  "} _Cxc_NT_b_Int;")
             out)))))

(deftest emit-typedefs-non-nil-primitive-field
  (testing "F15 L2.c.ii — non-nil primitive fields (`x: Int!`) treat as their inner primitive at the C level. F39 nullability semantics live at the observation points (ctor, field write, method return), not in the underlying storage type."
    (let [shapes (nt/collect-shapes
                   {:node :method
                    :params [{:name "p"
                              :type (nt-node-with-fields
                                      [{:name "x" :type (nonnil (ref "Int"))}])}]})
          out    (nt/emit-typedefs shapes mvp-c-type)]
      (is (= (str "typedef struct {\n"
                  "    int64_t x;\n"
                  "} _Cxc_NT_x_Nn_Int;")
             out)))))

(deftest emit-typedefs-nested-field-throws-nyi
  (testing "F15 L2.c.ii MVP — nested named-tuple field types throw NYI. The nested typedef itself is still emitted (collect-shapes surfaces it as a separate entry), but the outer's field-type translation fails because we haven't settled the inline-vs-reference decision. Extended in a follow-on sub-landing."
    (let [inner (nt-node "inner" "Int")
          outer (nt-node-with-fields [{:name "outer" :type inner}])
          shapes (nt/collect-shapes
                   {:node :method :params [{:name "p" :type outer}]})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"nested named-tuple field types not supported"
            (nt/emit-typedefs shapes mvp-c-type))))))

(deftest emit-typedefs-parametric-field-throws-nyi
  (testing "F15 L2.c.ii MVP — parametric field types (`items: Array of Int`) throw NYI at typedef emit. Deferred to a follow-on sub-landing."
    (let [shapes (nt/collect-shapes
                   {:node :method
                    :params [{:name "p"
                              :type (nt-node-with-fields
                                      [{:name "items"
                                        :type {:node :type-of
                                               :outer "Array"
                                               :inner (ref "Int")}}])}]})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"parametric field types.*not supported"
            (nt/emit-typedefs shapes mvp-c-type))))))

(deftest emit-typedefs-unknown-primitive-throws
  (testing "F15 L2.c.ii — a field's primitive type that the caller's `primitive->c-type` doesn't map throws with a targeted diagnostic. The caller is responsible for supplying every primitive that could appear in a collected shape."
    (let [shapes (nt/collect-shapes
                   {:node :method
                    :params [{:name "p"
                              :type (nt-node "x" "Char")}]})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"primitive type `Char` has no C-type mapping"
            (nt/emit-typedefs shapes mvp-c-type))))))

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
