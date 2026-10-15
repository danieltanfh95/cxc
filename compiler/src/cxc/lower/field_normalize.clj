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
  (:require [clojure.string :as str]
            [cxc.diagnostics :refer [fail-nyi!]]
            [cxc.type-kw :refer [base-type-name
                                 cxc-type->c
                                 primitive-c-type
                                 optional-type-name
                                 map-key-val-of]]))

(defn validate-reference-modifier-type!
  "F13 L1 — a `weak`/`unowned` field must reference an atom (or
   `String`, the sole reference-typed value). Records and enums are
   value types; primitives were already rejected at parse time.
   env context lets us distinguish record/atom/enum by looking up
   the type name against each env."
  [field record-env atom-env enum-env]
  (when-let [modifier (:modifier field)]
    (let [ty-nm    (base-type-name (:type field))
          modifier-nm (clojure.core/name modifier)
          in-record? (contains? record-env ty-nm)
          in-enum?   (contains? enum-env ty-nm)
          in-atom?   (contains? atom-env ty-nm)
          string?    (contains? #{"String" "_Cxc_String"} ty-nm)]
      (cond
        in-record?
        (fail-nyi!
         (str "F13 L1: `" modifier-nm " " ty-nm "` on field `"
              (:name field) "` — reference modifiers apply to reference "
              "types (atoms, `String`); `" ty-nm "` is a record "
              "(value type, §3.2). Drop the modifier (spec §6.14).")
         field)

        in-enum?
        (fail-nyi!
         (str "F13 L1: `" modifier-nm " " ty-nm "` on field `"
              (:name field) "` — reference modifiers apply to reference "
              "types (atoms, `String`); `" ty-nm "` is an enum "
              "(value type, §3.2). Drop the modifier (spec §6.14).")
         field)

        (not (or in-atom? string?))
        (fail-nyi!
         (str "F13 L1: `" modifier-nm " " ty-nm "` on field `"
              (:name field) "` — `" ty-nm "` is not a known reference type "
              "(atom or `String`). Reference modifiers only apply to "
              "reference types (spec §6.14).")
         field)))))

(defn fields-from-data-section
  [data-section record-env atom-env enum-env module-map]
  (mapv (fn [f]
          (validate-reference-modifier-type! f record-env atom-env enum-env)
          (let [ty-node (:type f)
                nonnil? (= :type-nonnil (:node ty-node))
                nullable? (= :type-nullable (:node ty-node))
                base    (base-type-name ty-node)
                scalar-c (cxc-type->c ty-node record-env atom-env enum-env module-map)
                ;; F39 L5b-ii — value-typed bare-T fields (no `!`) box
                ;; to `_Cxc_Optional_<T>` storage. Predicate is inlined
                ;; here because `field-storage-value-typed?` operates on
                ;; a fully-built field record, and we're building it.
                value-typed? (and (string? scalar-c)
                                  (not (str/ends-with? scalar-c "*"))
                                  (not= "id" scalar-c)
                                  (not (.contains ^String scalar-c "^"))
                                  (not= "Block" base)
                                  (not= "String" base)
                                  (not= "Bytes" base)
                                  (not= "Any" base)
                                  (or (contains? primitive-c-type base)
                                      (contains? record-env base)
                                      (contains? enum-env base)))
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
