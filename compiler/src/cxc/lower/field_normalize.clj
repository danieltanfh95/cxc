(ns cxc.lower.field-normalize
  "Field-level normalization at env-build time. Takes parsed
   `:data-section` fields and produces the env-record shape downstream
   lowering consumes.

   Two entry points:
     - `validate-reference-modifier-type!` — F13 L1 gate on
       `weak`/`unowned` modifiers (only apply to reference types).
     - `fields-from-data-section` — normalize the parsed fields into
       `{:name :cxc-ty :nullable? :nonnil? :optional? :scalar-c
         :c-type :default :type ...}` env entries, applying F39 L5b-ii
       Optional-boxed storage for value-typed bare-`T` fields."
  (:require [cxc.diagnostics :refer [fail-user!]]
            [cxc.lower.field-preds :refer [storage-value-typed?]]
            [cxc.type-kw :refer [base-type-name
                                 cxc-type->c
                                 optional-type-name
                                 map-key-val-of]]))

(set! *warn-on-reflection* true)

(defn validate-reference-modifier-type!
  "F13 L1 — a `weak`/`unowned` field must reference an atom (or
   `String`, the sole reference-typed value). Records and enums are
   value types; primitives were already rejected at parse time.
   env context lets us distinguish record/atom/enum by looking up
   the type name against each env."
  [field {:keys [record-env atom-env enum-env]}]
  (when-let [modifier (:modifier field)]
    (let [ty-nm    (base-type-name (:type field))
          modifier-nm (clojure.core/name modifier)
          in-record? (contains? record-env ty-nm)
          in-enum?   (contains? enum-env ty-nm)
          in-atom?   (contains? atom-env ty-nm)
          string?    (contains? #{"String" "_Cxc_String"} ty-nm)]
      (cond
        (or in-record? in-enum?)
        (fail-user!
         (str "F13 L1: `" modifier-nm " " ty-nm "` on field `"
              (:name field) "` — reference modifiers apply to reference "
              "types (atoms, `String`); `" ty-nm "` is "
              (if in-record? "a record" "an enum")
              " (value type, §3.2). Drop the modifier (spec §6.14).")
         field)

        (not (or in-atom? string?))
        (fail-user!
         (str "F13 L1: `" modifier-nm " " ty-nm "` on field `"
              (:name field) "` — `" ty-nm "` is not a known reference type "
              "(atom or `String`). Reference modifiers only apply to "
              "reference types (spec §6.14).")
         field)))))

(defn fields-from-data-section
  [data-section {:keys [record-env atom-env enum-env] :as ctx}]
  (mapv (fn [f]
          (validate-reference-modifier-type! f ctx)
          (let [ty-node (:type f)
                nonnil? (= :type-nonnil (:node ty-node))
                nullable? (= :type-nullable (:node ty-node))
                base    (base-type-name ty-node)
                scalar-c (cxc-type->c ty-node ctx)
                ;; F39 L5b-ii — value-typed bare-T fields (no `!`) box
                ;; to `_Cxc_Optional_<T>` storage. Shared classifier
                ;; with `field-storage-value-typed?` on completed
                ;; fields; feed `scalar-c` + `base` here before the
                ;; `:c-type` / `:cxc-ty` slots exist.
                value-typed? (storage-value-typed? scalar-c base
                                                   record-env enum-env)
                ;; F39 L5b-ii-c FLIPPED 2026-08-22 (Sub-P). Value-typed
                ;; bare-T fields box to `_Cxc_Optional_<T>` storage; `T!`
                ;; stays scalar. Fan-out (Sub-A through Sub-N) landed
                ;; guarded on this flag; Sub-Q iterates failures from
                ;; here (observable/method return unwrap, F49 invariant
                ;; substitution, atom-ctor param plumbing).
                optional? (and value-typed? (not nonnil?))
                c-type    (if optional? (optional-type-name base) scalar-c)]
            (merge {:name       (:name f)
                    :cxc-ty     base
                    :nullable?  nullable?
                    :nonnil?    nonnil?
                    :optional?  optional?
                    :scalar-c   scalar-c
                    :c-type     c-type
                    :default    (:default f)
                    ;; F15 L2.d.ii.α (2026-08-24) — preserve the raw
                    ;; parsed `:type` node on lowered record fields so
                    ;; L2.d.ii.β's structural-conformance check has a
                    ;; type node it can hand to `structural-conforms?`
                    ;; (which uses Clojure structural equality on the
                    ;; raw type nodes to decide field-type match). The
                    ;; existing `:cxc-ty` is a lossy string projection —
                    ;; loses nullability distinctions and can't
                    ;; distinguish `Int` from `Int!` at the same base
                    ;; name. Parallel to ε.β.β's `:type` preservation
                    ;; on ctor-params; additive field with no reader
                    ;; today, so existing tests pass without update.
                    :type       ty-node}
                   (map-key-val-of ty-node)
                   (when-let [m (:modifier f)] {:modifier m}))))
        (:fields data-section)))
