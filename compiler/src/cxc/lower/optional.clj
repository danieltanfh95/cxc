(ns cxc.lower.optional
  "F39 L5b-ii-a — Optional-boxed value-type infrastructure.

   Under the unified nullability rule (spec §3.6.1, design premise 1
   of plans/g3-structural-canonical/f39-nullability.md) bare `T` on a
   value type is nullable at every position. The C representation
   carrying nil is a `_Cxc_Optional_<T> { uint8_t present; T value; }`
   struct.

   The three fns here collect the set of inner Ts needing typedefs
   from record + atom envs, then produce one `:c-struct` per T.
   Called once from the top-level `lower` orchestrator after envs are
   built. No lowering-pass helpers touch this file."
  (:require [cxc.type-kw :refer [cxc-type->c optional-type-name]]))

;; 2026-09-04 Path 1 slice 2b — primitive names whose envelope
;; typedef must be available whenever Path 1 flips a nullable-
;; primitive local to `_Cxc_Optional_<T>` storage. Unconditional
;; inclusion is cheap (each typedef is a two-field struct) and
;; keeps slice 2d's storage-flip a one-line change to lower-let-stmt
;; without requiring a local-scan pass here.
(def ^:private primitive-envelope-names
  ;; Canonical width names only — `Int` aliases `Int64` per §3.1 and
  ;; both mangle to `_Cxc_Optional_Int64`; including both produces
  ;; duplicate typedefs. `Double` = 64-bit IEEE 754; `Float` = 32-bit.
  ;; 2026-09-16 — `String` joins the set so `List of String .at(i)`
  ;; can return `_Cxc_Optional_String` uniformly with the rest. NSString*
  ;; is natively nullable, but the envelope shape stays consistent
  ;; across all element types so downstream `??` / `.field` route
  ;; through one machinery.
  #{"Int8" "Int16" "Int32" "Int64"
    "UInt8" "UInt16" "UInt32" "UInt64"
    "Float" "Double"
    "Bool" "String"})

(defn collect-optional-types
  "L5b-ii-b (2026-08-22): scan record + atom envs for fields whose
   storage was flipped to `_Cxc_Optional_<T>` (i.e. `:optional? true`)
   and return the set of inner T names that need a typedef.
   `fields-from-data-section` sets the flag when a bare-T value-typed
   field lands storage. Returned names feed `synth-optional-typedefs`.

   Atom envs share the same fields shape when the atom has a
   data-section — those get their fields via the same helper.

   2026-09-04 Path 1 slice 2b — unconditionally seeds the primitive
   envelope typedefs so slice 2d's storage flip finds them already
   declared. Additive: existing field-set semantics unchanged."
  [record-env atom-env _enum-env]
  (let [;; `Int` is a prelude alias for `Int64`; both mangle to
        ;; `_Cxc_Optional_Int64`, so a field declared `Int` and
        ;; primitive-envelope-names' `Int64` would produce duplicate
        ;; typedefs. Canonicalize on the way in.
        canonical   #(if (= "Int" %) "Int64" %)
        rec-fields  (mapcat :fields (vals record-env))
        atom-fields (mapcat (fn [info]
                              (or (:fields info) []))
                            (vals atom-env))
        field-set   (into #{}
                          (comp (filter :optional?)
                                (map :cxc-ty)
                                (map canonical))
                          (concat rec-fields atom-fields))
        ;; 2026-09-16 — `.at(i) :- T` on List containers returns
        ;; `_Cxc_Optional_<T>`. Guarantee the typedef for every
        ;; record element type that appears in a List (primitives
        ;; are already covered by `primitive-envelope-names`).
        list-elem-records (into #{}
                                (comp (filter #(= :list-container
                                                  (:kind %)))
                                      (map :element-type)
                                      (filter #(contains? record-env %))
                                      (map canonical))
                                (vals atom-env))
        ;; F39 L5b-ii-c — ord binops (`x.n > 0`) on Optional operands
        ;; produce Optional<Bool>, which needs the `_Cxc_Optional_Bool`
        ;; typedef even when no field is declared Bool. Cheap to
        ;; include unconditionally whenever any Optional field exists.
        with-bool   (if (seq field-set)
                      (conj field-set "Bool")
                      field-set)]
    (-> with-bool
        (into primitive-envelope-names)
        (into list-elem-records))))

(defn synth-optional-typedef
  "Produce a `:c-struct` for `_Cxc_Optional_<T>`. Layout is presence-
   byte-first per §3.10.10 wire encoding — the `.present` field IS
   the first canonical byte, avoiding a conversion pass for
   `.as() to Bytes` (L9). C99 designated initializers construct
   instances at wrap sites via the existing `:struct-lit` emit path
   (no new emitter code needed).

   `t` is the source cxc type name (e.g. `\"Int\"`, `\"Position\"`);
   ctx supplies the envs needed to resolve `t`'s raw C type."
  [t ctx]
  (let [c-value-type (cxc-type->c {:node :type-ref :name t
                                   :resolves-to (get (:decl->module ctx) t)}
                                  (:record-env ctx)
                                  (:atom-env   ctx)
                                  (:enum-env   ctx)
                                  (:module-map ctx))]
    {:node   :c-struct
     :name   (optional-type-name t)
     :fields [{:name "present" :c-type "uint8_t"}
              {:name "value"   :c-type c-value-type}]}))

(defn synth-optional-typedefs
  "Emit one `_Cxc_Optional_<T>` typedef per T in `optional-types`.
   Called from `lower` after record/enum/atom envs are built so
   `cxc-type->c` can resolve the inner T. Under L5b-ii-a `optional-
   types` is always empty; under L5b-ii-b it becomes non-empty."
  [optional-types ctx]
  (mapv #(synth-optional-typedef % ctx) (sort optional-types)))
