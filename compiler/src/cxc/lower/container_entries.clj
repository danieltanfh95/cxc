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
  (:require [cxc.lower.list-container :refer [list-at-or-supported-element-types
                                                list-at-eligible?]]
            [cxc.type-kw             :as type-kw]))

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

(defn- scan-record-env-fields
  "Walk every record-env entry's :fields, threading each through `f`.
   Mirror of `scan-atom-env-fields` — records don't carry ctor-params
   (their ctor is auto-derived from field order), so only :fields
   participates. Feeds the container-instantiation collector so a
   `List of <T>` embedded inside a record field triggers the same
   `_Cxc_ListOfT` class synthesis that atom-embedded lists do."
  [record-env acc f]
  (reduce (fn [acc [_ info]]
            (reduce f acc (:fields info)))
          acc
          record-env))

(defn- scan-enum-env-payload-fields
  "Walk every enum-env entry's `:case-entries` payload fields, threading
   each through `f`. Mirror of `scan-atom-env-fields` /
   `scan-record-env-fields` — enum payload fields parsed as
   `{:node :field :name X :type <type-node>}` are not pre-decorated
   with `:cxc-ty` / `:map-key` / `:map-val` (that decoration lives in
   `cxc.lower.field-normalize/fields-from-data-section` which runs on
   record/atom fields only). This helper synthesizes those keys on the
   fly via `cxc.type-kw/base-type-name` + `cxc.type-kw/map-key-val-of`,
   so a `case foo(xs: List of Card)` payload triggers the same
   `_Cxc_ListOfCard` class synthesis that `fields` / `ctor-params`
   embedded in records and atoms do.

   Chapter 9 landing (2026-10-04) — closes sub-case 2 of the
   recursive-user-types emit gap documented in
   `design/ast-shape-records.md`. Mutual recursion through
   `enum E { case nt(fields: List of NamedField) }` now registers
   `_Cxc_ListOfNamedField` the same way `Record { xs: List of Item }`
   does, so the self-host port's AST shape records can carry `List of
   NamedField` payloads without the clang `unknown type name` error.
   Sub-case 1 (self-reference via `case nonnil(inner: Self!)` value →
   pointer semantic change) stays deferred."
  [enum-env acc f]
  (reduce (fn [acc [_ info]]
            (reduce (fn [a ce]
                      (reduce (fn [a p]
                                (let [ty-node (:type p)
                                      stamped (if ty-node
                                                (merge p
                                                       {:cxc-ty (type-kw/base-type-name ty-node)}
                                                       (type-kw/map-key-val-of ty-node))
                                                p)]
                                  (f a stamped)))
                              a
                              (:payload ce)))
                    acc
                    (:case-entries info)))
          acc
          enum-env))

(defn list-instantiations-from-atom-env
  "Scan every atom-env AND record-env entry's fields + ctor-params for
   `List of T` usages; return a map {element-type-name → list-class-
   name}. Feeds Step 3 class synthesis: one compiler-generated ObjC
   class per distinct element type.

   Records participate 2026-09-12 (Phase 5 follow-on) — previously only
   atom fields drove synthesis, so `Record { xs: List of Item }` left
   `_Cxc_ListOfItem` undeclared at C level; now record fields trigger
   the same synth path. Arity-2 is the current callable; arity-1 kept
   for callers that haven't threaded record-env through yet (returns
   the atom-only scan).

   Enum payloads participate 2026-10-04 (chapter 9 sub-case 2) —
   `enum E { case nt(fields: List of NamedField) }` now registers
   `_Cxc_ListOfNamedField` the same way record/atom fields do. Arity-3
   accepts enum-env; arities 1 and 2 keep legacy callers working."
  ([atom-env] (list-instantiations-from-atom-env atom-env {} {}))
  ([atom-env record-env] (list-instantiations-from-atom-env atom-env record-env {}))
  ([atom-env record-env enum-env]
   (let [collect (fn [acc fp]
                   (let [ty (:cxc-ty fp)]
                     ;; Field/ctor-param carries the already-mangled
                     ;; class name in :cxc-ty (see base-type-name), so
                     ;; detect via the `_Cxc_ListOf` prefix and recover
                     ;; the element.
                     (if (and (string? ty) (.startsWith ^String ty "_Cxc_ListOf"))
                       (assoc acc (subs ty (count "_Cxc_ListOf")) ty)
                       acc)))]
     (-> {}
         (as-> a (scan-atom-env-fields atom-env a collect))
         (as-> a (scan-record-env-fields record-env a collect))
         (as-> a (scan-enum-env-payload-fields enum-env a collect))))))

(defn map-instantiations-from-atom-env
  "Scan every atom-env AND record-env entry's fields + ctor-params for
   `Map of (K, V)` usages; return a map {[K-name V-name] → map-class-
   name}. Feeds Layer 6 Step 8 class synthesis: one compiler-generated
   ObjC class per distinct (K, V) pair.

   Records participate 2026-09-12 — same rationale as
   `list-instantiations-from-atom-env`. Enum payloads participate
   2026-10-04 (chapter 9 sub-case 2)."
  ([atom-env] (map-instantiations-from-atom-env atom-env {} {}))
  ([atom-env record-env] (map-instantiations-from-atom-env atom-env record-env {}))
  ([atom-env record-env enum-env]
   (let [collect (fn [acc fp]
                   (let [ty (:cxc-ty fp)]
                     (if (and (string? ty) (.startsWith ^String ty "_Cxc_MapOf"))
                       (assoc acc [(:map-key fp) (:map-val fp)] ty)
                       acc)))]
     (-> {}
         (as-> a (scan-atom-env-fields atom-env a collect))
         (as-> a (scan-record-env-fields record-env a collect))
         (as-> a (scan-enum-env-payload-fields enum-env a collect))))))

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
   C sig the container class emits.

   2026-09-16 — `:return-optional?` flag (opt-in) tells
   infer-method-call-type to lift the return type-kw into
   `[:optional inner]` so downstream `??` / `.field` see the
   envelope. Used by `.at(i)`; leave nil for methods returning
   raw types."
  [method-name params return-ty & {:keys [return-optional?]}]
  (let [stamped-params (mapv #(assoc % :nonnil? true) params)]
    {:name               method-name
     :params             (into [{:name "self" :is-self? true :nonnil? true}]
                               stamped-params)
     :cxc-return-ty      return-ty
     :cxc-return-ty-node (when return-ty {:node :type-ref :name return-ty})
     :return-optional?   return-optional?}))

(defn synthesize-map-container-entries
  "Given {[K V] → map-class-name} produce atom-env entries for each
   map-class. Layer 6 Step 8 MVP: no fan-in over map values (deferred);
   entries carry :kind :map-container marker, key/value type names, and
   a `count` observable. Method-index registers add/remove/contains so
   infer-method-call-type resolves them."
  [instantiations]
  (into {}
        (map (fn [[[k v] class-name]]
               ;; 2026-09-16 — Full FP surface. `.add`/`.remove`/`.get_or`
               ;; retired; users compose via `m => m.assoc(k, v)` /
               ;; `m => m.dissoc(k)` at a field site, and read via
               ;; `m.get(k) ?? fallback`. See
               ;; [[corvid-no-imperative-container-mutation]].
               (let [methods [(container-method "assoc"
                                                [{:name "k" :cxc-ty k}
                                                 {:name "v" :cxc-ty v}]
                                                class-name)
                              (container-method "dissoc"
                                                [{:name "k" :cxc-ty k}]
                                                class-name)
                              (container-method "contains"
                                                [{:name "k" :cxc-ty k}]
                                                "Bool")
                              (container-method "get"
                                                [{:name "k" :cxc-ty k}]
                                                v
                                                :return-optional? true)]]
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
  "Scan atom-env AND record-env fields + ctor-params for `Set of T`
   usages; return {element-name → set-class-name}. Records participate
   2026-09-12 — same rationale as `list-instantiations-from-atom-env`.
   Enum payloads participate 2026-10-04 (chapter 9 sub-case 2)."
  ([atom-env] (set-instantiations-from-atom-env atom-env {} {}))
  ([atom-env record-env] (set-instantiations-from-atom-env atom-env record-env {}))
  ([atom-env record-env enum-env]
   (let [collect (fn [acc fp]
                   (let [ty (:cxc-ty fp)]
                     (if (and (string? ty) (.startsWith ^String ty "_Cxc_SetOf"))
                       (assoc acc (subs ty (count "_Cxc_SetOf")) ty)
                       acc)))]
     (-> {}
         (as-> a (scan-atom-env-fields atom-env a collect))
         (as-> a (scan-record-env-fields record-env a collect))
         (as-> a (scan-enum-env-payload-fields enum-env a collect))))))

(defn synthesize-set-container-entries
  "Given {element-name → set-class-name}, produce atom-env entries.
   MVP shape: no fan-in (parallel to Map). Methods add/remove/contains
   registered in :method-index; count observable in :observable-index."
  [instantiations]
  (into {}
        (map (fn [[elem class-name]]
               ;; 2026-09-16 — Full FP surface. `.add`/`.remove`
               ;; retired; users compose via `s => s.insert(e)` /
               ;; `s => s.without(e)` at a field site.
               (let [methods [(container-method "insert"
                                                [{:name "e" :cxc-ty elem}] class-name)
                              (container-method "without"
                                                [{:name "e" :cxc-ty elem}] class-name)
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
  [instantiations atom-env record-env]
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
                     ;; 2026-09-16 — imperative `.add(e)` / `.remove(e)`
                     ;; retired from user surface (audit note: they
                     ;; bypass `=>`-based field mutation and skip the
                     ;; atom's serial queue, breaking cxc's "state lives
                     ;; on an atom, mutation via `=>` in a transition"
                     ;; rule). Users compose via `xs => xs.append(e)`
                     ;; and `xs => xs.filter(x => x != e)` at a field
                     ;; site instead. `.at_or` also retired — the
                     ;; nil-punned surface is `xs.at(i) ?? fallback`
                     ;; which composes with `??` uniformly.
                     methods       (cond-> [(container-method "append"
                                                              [{:name "e" :cxc-ty elem}]
                                                              class-name)
                                            (container-method "filter"
                                                              [{:name "pred" :cxc-ty elem}]
                                                              class-name)
                                            ;; 2026-09-20 — `.empty()` factory.
                                            ;; Returns a fresh empty `List of T`
                                            ;; using the receiver's element type;
                                            ;; caller compose via `xs => xs.empty()`
                                            ;; instead of `xs.filter(x => false)`.
                                            ;; Instance-shape (not class-shape)
                                            ;; because Corvid has no first-class
                                            ;; type names in expression position —
                                            ;; the receiver carries the T.
                                            (container-method "empty"
                                                              []
                                                              class-name)
                                            ;; 2026-09-17 — `.sort_by(f: T -> Int)`.
                                            ;; Python-shape key-fn sort matching
                                            ;; `sorted(xs, key=fn)`. Returns a
                                            ;; new sorted list; caller-compose
                                            ;; via `xs => xs.sort_by(c => c.coin)`.
                                            (container-method "sort_by"
                                                              [{:name "keyfn" :cxc-ty elem}]
                                                              class-name)
                                            ;; 2026-10-01 — `.slice(start, end)`
                                            ;; returns elements in [start, end).
                                            ;; End exclusive, out-of-bound ends
                                            ;; are clamped. Matches Python/Rust.
                                            (container-method "slice"
                                                              [{:name "start" :cxc-ty "Int"}
                                                               {:name "end"   :cxc-ty "Int"}]
                                                              class-name)
                                            ;; 2026-10-01 — `.concat(other)`
                                            ;; appends another List of the
                                            ;; same element type. The param
                                            ;; type is the container class
                                            ;; itself, not `elem` — carrier
                                            ;; type matches the receiver.
                                            (container-method "concat"
                                                              [{:name "other"
                                                                :cxc-ty class-name
                                                                :c-type (str class-name " *")}]
                                                              class-name)]
                                     (contains? list-at-or-supported-element-types elem)
                                     (conj (container-method "contains"
                                                             [{:name "e" :cxc-ty elem}]
                                                             "Bool"))
                                     (list-at-eligible? elem
                                                        (contains? record-env elem)
                                                        (contains? atom-env elem))
                                     (conj (container-method "at"
                                                             [{:name "i" :cxc-ty "Int"}]
                                                             elem
                                                             ;; Atoms are already
                                                             ;; nullable pointers;
                                                             ;; skip envelope wrap.
                                                             :return-optional?
                                                             (not (contains? atom-env elem)))))]
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
