(ns cxc.lower.field-preds
  "Field-record predicates used at atom/record lowering time. Each
   consults `:cxc-ty` / `:c-type` slots that env-build populated (see
   `cxc.lower.field-normalize`) to answer 'is this a List-of / Map-of
   / Set-of field?' or 'does this field's storage box a primitive-
   sized value type?' — the latter drives F39 L5b-ii Optional-boxed
   storage detection."
  (:require [clojure.string :as str]
            [cxc.type-kw :refer [primitive-c-type]]))

(defn field-is-list-of?
  "Field's :cxc-ty is a `List of T` mangled class name."
  [field]
  (and (string? (:cxc-ty field))
       (.startsWith ^String (:cxc-ty field) "_Cxc_ListOf")))

(defn field-list-element-name [field]
  (subs (:cxc-ty field) (count "_Cxc_ListOf")))

(defn field-is-map-of? [field]
  (and (string? (:cxc-ty field))
       (.startsWith ^String (:cxc-ty field) "_Cxc_MapOf")))

(defn field-is-set-of? [field]
  (and (string? (:cxc-ty field))
       (.startsWith ^String (:cxc-ty field) "_Cxc_SetOf")))

(defn field-storage-value-typed?
  [field ctx]
  (let [c-ty (:c-type field)
        nm   (:cxc-ty field)]
    (and (string? c-ty)
         (not (str/ends-with? c-ty "*"))
         (not= "id" c-ty)
         (or (contains? primitive-c-type nm)
             (contains? (:record-env ctx) nm)
             (contains? (:enum-env   ctx) nm)))))
