(ns cxc.lower.container-entries
  "atom-env registration entries for synthesized List / Map / Set
   container classes.

   Each `synthesize-*-container-entries` fn produces one atom-env
   entry per instantiation surfaced by the parallel scanner
   (`*-instantiations-from-atom-env`). The entries carry the
   compiler-known method-index (add/remove/contains + count
   observable) so `infer-method-call-type` resolves calls without
   any per-container special-casing.

   Fan-in: List forwards its element-atom's streams (Layer 4b), so
   `synthesize-list-container-entries` reads the element atom's
   `:stream-order` / `:stream-index` / `:stream-payload-index` and
   prepends `e: <ElementAtom>` to every payload. Map / Set land no
   fan-in at MVP."
  (:require [cxc.lower.list-container :refer [list-at-or-supported-element-types]]))

(defn container-entry-defaults
  "The always-empty keys every synthesized container atom-env entry
   carries. Container-specific synth code merges its distinguishing
   keys (`:kind`, `:element-type` / `:key-type`+`:val-type`, methods,
   observables, fan-in) on top."
  []
  {:fields               []
   :field-index          {}
   :ctor-params          []
   :methods              []
   :method-index         {}
   :observables          []
   :observables-typed    []
   :observable-index     {}
   :invariants           []
   :transitions          []
   :message-index        {}
   :companion-index      {}
   :use-caps             []
   :stream-index         {}
   :stream-order         []
   :stream-payload-index {}
   :subscription-map     {}
   :slot-names           []
   :extras               []})

(defn- scan-atom-env-fields
  "Walk every atom-env entry's :fields + :ctor-params, threading each
   field/param through `f`. `f` receives (acc, field-or-param) and
   returns the updated acc. Common scaffolding for the container-
   instantiation-collection pattern (List / Map / Set)."
  [atom-env acc f]
  (reduce (fn [acc [_ info]]
            (as-> acc a
              (reduce f a (:fields info))
              (reduce f a (:ctor-params info))))
          acc
          atom-env))

(defn list-instantiations-from-atom-env
  "Scan every atom-env entry's fields + ctor-params for `List of T`
   usages; return a map {element-type-name → list-class-name}. Feeds
   Step 3 class synthesis: one compiler-generated ObjC class per
   distinct element type."
  [atom-env]
  (scan-atom-env-fields
   atom-env {}
   (fn [acc fp]
     (let [ty (:cxc-ty fp)]
       ;; Field/ctor-param carries the already-mangled class name in
       ;; :cxc-ty (see base-type-name), so detect via the `_Cxc_ListOf`
       ;; prefix and recover the element.
       (if (and (string? ty) (.startsWith ^String ty "_Cxc_ListOf"))
         (assoc acc (subs ty (count "_Cxc_ListOf")) ty)
         acc)))))

(defn map-instantiations-from-atom-env
  "Scan every atom-env entry's fields + ctor-params for `Map of (K, V)`
   usages; return a map {[K-name V-name] → map-class-name}. Feeds
   Layer 6 Step 8 class synthesis: one compiler-generated ObjC class
   per distinct (K, V) pair."
  [atom-env]
  (scan-atom-env-fields
   atom-env {}
   (fn [acc fp]
     (let [ty (:cxc-ty fp)]
       (if (and (string? ty) (.startsWith ^String ty "_Cxc_MapOf"))
         (assoc acc [(:map-key fp) (:map-val fp)] ty)
         acc)))))

(def ^:private count-observable-defaults
  "Every container gains a `count` observable — factored so all three
   container-entry synthesizers share the same shape."
  {:observables-typed [{:name "count" :type-kw :int}]
   :observable-index  {"count" {:name "count" :type-kw :int}}})

(defn- container-method
  "Build a method-index entry for a synthesized container method.
   `return-ty` is a cxc type-name string (or nil for void); params
   receive self implicitly prepended.

   2026-09-05 Path 1 slice 8-A — container-synth methods (List
   at_or, Map get_or, Set contains, …) are hand-crafted to take raw
   C scalars in their ObjC signatures (`(int)i`, `(NSString *)k`).
   The call-site slice-8-B envelope-arg unwrap only fires when the
   callee param is marked non-nil (or otherwise not envelope-eligible),
   so every synthesized-container param is stamped `:nonnil? true`
   here. Bare-T primitive args to List.at_or get automatically
   unwrapped envelope→raw at the call site, matching the raw-scalar
   C sig the container class emits."
  [method-name params return-ty]
  (let [stamped-params (mapv #(assoc % :nonnil? true) params)]
    {:name               method-name
     :params             (into [{:name "self" :is-self? true :nonnil? true}]
                               stamped-params)
     :cxc-return-ty      return-ty
     :cxc-return-ty-node (when return-ty {:node :type-ref :name return-ty})}))

(defn synthesize-map-container-entries
  "Given {[K V] → map-class-name} produce atom-env entries for each
   map-class. Layer 6 Step 8 MVP: no fan-in over map values (deferred);
   entries carry :kind :map-container marker, key/value type names, and
   a `count` observable. Method-index registers add/remove/contains so
   infer-method-call-type resolves them."
  [instantiations]
  (into {}
        (map (fn [[[k v] class-name]]
               (let [methods [(container-method "add"
                                                [{:name "k" :cxc-ty k}
                                                 {:name "v" :cxc-ty v}]
                                                nil)
                              (container-method "remove"
                                                [{:name "k" :cxc-ty k}]
                                                nil)
                              (container-method "contains"
                                                [{:name "k" :cxc-ty k}]
                                                "Bool")
                              (container-method "get_or"
                                                [{:name "k"        :cxc-ty k}
                                                 {:name "fallback" :cxc-ty v}]
                                                v)]]
                 [class-name
                  (merge (container-entry-defaults)
                         count-observable-defaults
                         {:kind         :map-container
                          :key-type     k
                          :val-type     v
                          :methods      methods
                          :method-index (into {} (map (juxt :name identity) methods))})]))
             instantiations)))

(defn set-instantiations-from-atom-env
  "Scan atom-env fields + ctor-params for `Set of T` usages; return
   {element-name → set-class-name}."
  [atom-env]
  (scan-atom-env-fields
   atom-env {}
   (fn [acc fp]
     (let [ty (:cxc-ty fp)]
       (if (and (string? ty) (.startsWith ^String ty "_Cxc_SetOf"))
         (assoc acc (subs ty (count "_Cxc_SetOf")) ty)
         acc)))))

(defn synthesize-set-container-entries
  "Given {element-name → set-class-name}, produce atom-env entries.
   MVP shape: no fan-in (parallel to Map). Methods add/remove/contains
   registered in :method-index; count observable in :observable-index."
  [instantiations]
  (into {}
        (map (fn [[elem class-name]]
               (let [methods [(container-method "add"
                                                [{:name "e" :cxc-ty elem}] nil)
                              (container-method "remove"
                                                [{:name "e" :cxc-ty elem}] nil)
                              (container-method "contains"
                                                [{:name "e" :cxc-ty elem}] "Bool")]]
                 [class-name
                  (merge (container-entry-defaults)
                         count-observable-defaults
                         {:kind         :set-container
                          :element-type elem
                          :methods      methods
                          :method-index (into {} (map (juxt :name identity) methods))})]))
             instantiations)))

(defn synthesize-list-container-entries
  "Given {element-name → list-class-name} and the current atom-env
   (which knows element atoms' :stream-index / :stream-order /
   :stream-payload-index after inference), produce atom-env entries
   for each list-class. The synthetic entry carries:
     - `:kind :list-container` marker (lower-atom skips these)
     - `:element-type` name (used by class synth + for_each unbox)
     - `count` observable in observable-index
     - fan-in :stream-order / :stream-index / :stream-payload-index
       cloned from element atom, each payload prepended with
       `e: <ElementAtom>` so the container's emit signature carries
       the element identity"
  [instantiations atom-env]
  (into {}
        (map (fn [[elem class-name]]
               (let [elem-info      (get atom-env elem)
                     elem-streams   (:stream-order elem-info [])
                     elem-index     (:stream-index elem-info {})
                     elem-payloads  (:stream-payload-index elem-info {})
                     fanin-payloads
                     (into {}
                           (map (fn [nm]
                                  [nm (into [{:name   "e"
                                              :type-kw {:atom elem}
                                              :c-type (str elem " *")}]
                                            (get elem-payloads nm []))])
                                elem-streams))
                     fanin-index
                     (into {}
                           (map (fn [[nm entry]]
                                  [nm (assoc entry :payload
                                             (into [{:name "e"}]
                                                   (:payload entry [])))])
                                elem-index))
                     methods       (cond-> [(container-method "add"
                                                              [{:name "e" :cxc-ty elem}] nil)
                                            (container-method "remove"
                                                              [{:name "e" :cxc-ty elem}] nil)
                                            ;; A5 (2026-09-03) — immutable
                                            ;; append: returns a new
                                            ;; _Cxc_ListOfT instance with e
                                            ;; added. No per-element gating —
                                            ;; append doesn't need typed
                                            ;; unbox on the return side.
                                            (container-method "append"
                                                              [{:name "e" :cxc-ty elem}]
                                                              class-name)
                                            ;; A5 — immutable filter: takes
                                            ;; a `x => bool` lambda, returns
                                            ;; new _Cxc_ListOfT with only
                                            ;; matching elements. Call-site
                                            ;; special-cased in lower.clj
                                            ;; (mirrors for_each pattern);
                                            ;; the param cxc-ty is a placeholder
                                            ;; since the arg-shape check is
                                            ;; skipped by the special-case.
                                            (container-method "filter"
                                                              [{:name "pred" :cxc-ty elem}]
                                                              class-name)]
                                     (contains? list-at-or-supported-element-types elem)
                                     (conj (container-method "at_or"
                                                             [{:name "i"        :cxc-ty "Int"}
                                                              {:name "fallback" :cxc-ty elem}]
                                                             elem)
                                           (container-method "contains"
                                                             [{:name "e" :cxc-ty elem}]
                                                             "Bool")))]
                 [class-name
                  (merge (container-entry-defaults)
                         count-observable-defaults
                         {:kind                 :list-container
                          :element-type         elem
                          :methods              methods
                          :method-index         (into {} (map (juxt :name identity) methods))
                          :stream-index         fanin-index
                          :stream-order         elem-streams
                          :stream-payload-index fanin-payloads})]))
             instantiations)))
