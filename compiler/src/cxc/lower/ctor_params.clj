(ns cxc.lower.ctor-params
  "Normalize def-atom ctor params from the parser's `:field` shape
   into the atom-env's ctor-param entry shape."
  (:require [cxc.type-kw :refer [base-type-name cxc-type->c map-key-val-of]]))

(defn ctor-params-normalized
  "Def-atom :ctor-params come from the parser as `:field` nodes
   (`{:node :field :name :type :default}`). Normalize to
   `{:name :cxc-ty :nullable? :c-type :default :type}` for the
   atom-env.

   F15 L2.c.iii.c.β.2 (2026-08-24) — the raw parsed `:type` node is
   preserved on the lowered ctor-param. Downstream `collect-shapes`
   postwalk on the lowered program AST (`:classes` → per-class
   ctor-params → `:type`) discovers any `:type-named-tuple` shape at
   a ctor-param position and feeds it into `emit-typedefs` via the
   L2.c.iii.b wire-in. Fields not reading `:type` on lowered
   ctor-params are unaffected; today no test program has a
   named-tuple ctor-param so the extra field is dead metadata."
  [ctor-params record-env atom-env enum-env module-map]
  (mapv (fn [p]
          (merge {:name      (:name p)
                  :cxc-ty    (base-type-name (:type p))
                  :nullable? (= :type-nullable (get-in p [:type :node]))
                  ;; F39 L2 — carry `T!` intent on ctor params too.
                  ;; Runtime binding check wires in L3.
                  :nonnil?   (= :type-nonnil (get-in p [:type :node]))
                  :c-type    (cxc-type->c (:type p) record-env atom-env enum-env module-map)
                  :default   (:default p)
                  ;; F15 L2.c.iii.c.β.2 — preserve raw parsed :type node.
                  :type      (:type p)}
                 (map-key-val-of (:type p))))
        ctor-params))
