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

(defn storage-value-typed?
  "Return TRUE when a field whose storage carries the given C type and
   cxc-type name would box to `_Cxc_Optional_<T>` storage. Shared
   decision between env-build normalization and completed-field
   consumers. Rejects pointer / `id` / block storage, plus the four
   non-value base types (`Block`, `String`, `Bytes`, `Any`)."
  [c-type cxc-ty record-env enum-env]
  (and (string? c-type)
       (not (str/ends-with? c-type "*"))
       (not= "id" c-type)
       (not (.contains ^String c-type "^"))
       (not= "Block"  cxc-ty)
       (not= "String" cxc-ty)
       (not= "Bytes"  cxc-ty)
       (not= "Any"    cxc-ty)
       (or (contains? primitive-c-type cxc-ty)
           (contains? record-env       cxc-ty)
           (contains? enum-env         cxc-ty))))

(defn field-storage-value-typed?
  "Would this fully-built field's storage be value-typed? Reads
   `:c-type` + `:cxc-ty` from the field and `:record-env` + `:enum-env`
   from ctx. See `storage-value-typed?` for the shared classifier."
  [field ctx]
  (storage-value-typed? (:c-type field) (:cxc-ty field)
                        (:record-env ctx) (:enum-env ctx)))
