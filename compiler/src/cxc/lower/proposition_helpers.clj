(ns cxc.lower.proposition-helpers
  "Small helpers around normalized proposition nodes (`|:` invariants).

   `proposition-error-call` reconstructs the typed-failure ctor call
   from L2a-normalized proposition slots. F49 pre-store + Layer 6 post-
   store check paths both reuse this to feed the failure branch to
   `lower-expr`.

   `collect-self-field-refs` walks a proposition AST and returns the
   set of unqualified `:ident` names that would resolve to self-fields
   (not to any local). F49 Landing A uses this to decide whether every
   field ref has a source-value substitution — only then is the pre-
   store check safe to emit."
  (:require [clojure.walk :as walk]
            [cxc.diagnostics :refer [fail-compile!]]))

(defn proposition-error-call
  "Convert a normalized proposition's optional typed failure payload back
   into the ordinary constructor-call AST consumed by `lower-expr`. L2a
   keeps the type and named args separate so all proposition placements
   share one record shape; F49 only needs to reconstruct the expression
   when it builds a runtime failure branch."
  [{:keys [as-error-type as-error-args] :as proposition}]
  (when as-error-type
    (when-not (= :type-ref (:node as-error-type))
      (fail-compile!
       :cxc-l2b-invalid-proposition-error-type
       (str "typed proposition failure must construct a named error type; got `"
            (:node as-error-type) "`")
       proposition))
    {:node   :call
     :callee {:node        :ident
              :name        (:name as-error-type)
              :resolves-to (:resolves-to as-error-type)}
     :args   (mapv (fn [[name value]]
                     {:kind :named :name name :value value})
                   as-error-args)}))

(defn collect-self-field-refs
  "Walk an AST value bottom-up, returning the set of unqualified :ident names
   that would resolve to a self-field (not to a local, not to a fully-
   qualified path). Used by F49 Landing A to decide whether a proposition
   (predicate plus optional typed failure args) is safe to pre-store-check:
   if every field ref has a source value in the substitution map, the
   pre-store check emits before any field assignment truncates."
  [ast ctx]
  (let [acc (atom #{})]
    (walk/postwalk
     (fn [n]
       (when (and (map? n)
                  (= :ident (:node n))
                  (not (contains? (:locals ctx) (:name n)))
                  (get (:self-fields ctx) (:name n)))
         (swap! acc conj (:name n)))
       n)
     ast)
    @acc))
