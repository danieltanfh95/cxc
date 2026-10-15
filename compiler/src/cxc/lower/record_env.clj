(ns cxc.lower.record-env
  "Build record-env from top-level `record :=` defs.

   For each record: normalize fields (F13 L1 modifier gate + F39
   L5b-ii Optional storage) via `fields-from-data-section`, collect
   observables (F67 L4 — the `methods:` section retires under
   Proposal G), validate auto-ctor default discipline (spec §3.8.5
   rules 1+3), and reject data-field / observable name collisions
   (F8 L3a). Auto-detects the F38 `:variant` (`:class` when the
   record carries observables, else `:struct`).

   F37 L6b — a record's `class function` decls parse at v1.0 but
   emit is F37 L6-gated; fires a targeted fail-nyi so the shape isn't
   silently dropped.

   F38 — records can't hold user-declared-agent fields; the check runs
   after build to have both envs in hand."
  (:require [cxc.diagnostics :refer [fail-compile! fail-nyi!]]
            [cxc.env :as env]
            [cxc.lower.default-validate
             :refer [validate-defaults-after-required!
                     validate-default-is-const!]]
            [cxc.lower.field-normalize :refer [fields-from-data-section]]))

(defn- user-declared-atom?
  "True iff `nm` names a user-declared def-atom in `atom-env` (as
   opposed to a runtime-provided entry or a synthesized container
   class). Used by [[validate-no-agent-fields-in-records!]] to
   distinguish `foo: Tally` (reject) from `foo: List of Int` (allow)."
  [atom-env nm]
  (when-let [info (get atom-env nm)]
    (not (env/synthesized-atom-kind? (:kind info)))))

(defn validate-no-agent-fields-in-records!
  "F38 — a structural (record) def can't hold a field whose type
   resolves to a user-declared agent. Records are value-shape; a raw
   agent-pointer field would silently alias the same instance across
   supposed value copies (assignment, ctor bit-copy). Reject with a
   promotion fix-it. Nested containers (`List of Tally`) are a
   separate design question and aren't rejected here — this pass only
   catches the direct field-of-agent shape."
  [record-env atom-env]
  (doseq [[rec-nm rec-info] record-env
          f (:fields rec-info)
          :let [ty (:cxc-ty f)]
          :when (user-declared-atom? atom-env ty)]
    (fail-compile!
     :cxc-f38-record-holds-agent-field
     (str "record `" rec-nm "` field `" (:name f) ": " ty
          "` — a structural def can't hold an agent-typed field. "
          "Records are value-shape; bit-copying `" rec-nm "` would "
          "silently alias the same `" ty "` instance across copies, "
          "breaking value semantics. Fix: promote `" rec-nm "` to an "
          "agent (add a `-> phase` initial-phase marker to turn it "
          "into a def-atom), or drop the field and message the `" ty
          "` via `|>` from wherever `" rec-nm "` is used.")
     f)))

(defn build-record-env
  [top-nodes atom-env enum-env module-map]
  (let [records  (filter #(= :record (:node %)) top-nodes)
        skeleton (into {} (map (fn [r]
                                 [(:name r) {:fields [] :field-index {}
                                             :methods [] :method-index {}
                                             :variant :struct}])
                               records))]
    (reduce
     (fn [env r]
       (let [name    (:name r)
             legacy-dsec (first (filter #(= :data-section    (:node %)) (:sections r)))
             top-fields  (filterv #(= :field (:node %)) (:sections r))
             ;; F67 L5 — merge legacy `data:` section with top-level
             ;; field decls. Either surface produces the same shape.
             dsec    (cond
                       (and legacy-dsec (seq top-fields))
                       {:node :data-section
                        :fields (into (or (:fields legacy-dsec) []) top-fields)}
                       legacy-dsec        legacy-dsec
                       (seq top-fields)   {:node :data-section :fields top-fields}
                       :else              nil)
             ;; F67 L4 — observables on records (Proposal G collapse).
             ;; The `methods:` section is retired; observables carry
             ;; the callable-reader surface. Extension methods still
             ;; land in `:methods` via compose-record-env-with-extensions
             ;; (extension observables convert to method-shape entries
             ;; in extension-methods-for).
             observables (filterv #(= :observable (:node %)) (:sections r))
             ;; F37 L6b (2026-08-24) — records may carry `class function`
             ;; decls (spec §3.4.6 static-fn shape; canonical use is
             ;; `class function from(source: Bytes) -> DecodeOutcome<Self>`).
             ;; Parses at v1.0 but lower/emit consumption lands with
             ;; F37 L6 — fail-nyi with a targeted pointer so the shape
             ;; isn't silently ignored on a record body.
             _ (when-let [cfs (seq (filterv #(= :class-function (:node %))
                                             (:sections r)))]
                 (fail-nyi!
                  (str "record `" name "` has `class function` decls "
                       "(" (mapv :name cfs) ") — spec §3.4.6 static-fn "
                       "shape parses at v1.0 but lower/emit consumption "
                       "lands with F37 L6 (`class function from(source: "
                       "Bytes) -> DecodeOutcome<Self>` synthesis + "
                       "`Type.name(args)` call-site dispatch).")
                  {:record name :class-functions cfs}))
             fields  (fields-from-data-section dsec env atom-env enum-env module-map)
             site    (str "record `" name "` fields (auto-ctor)")
             _       (validate-defaults-after-required! site fields)
             _       (validate-default-is-const!       site fields atom-env enum-env)
             ;; F8 L3a follow-on — reject data-field / observable name
             ;; collisions inside one record. Silent collision would
             ;; make the observable dead code (`.field` on a value
             ;; dispatches to the field access path, never the observable
             ;; getter) — confusing for users writing `computed: n: Int
             ;; = ...` alongside `data: n: Int`. Fires at env build so
             ;; the diagnostic wins over any downstream emit.
             _       (let [field-names (set (map :name fields))
                           collisions (filter #(contains? field-names (:name %)) observables)]
                       (when-let [c (first collisions)]
                         (fail-nyi!
                          (str "F8 L3a: observable `" (:name c) "` on record `"
                               name "` collides with a data field of the same "
                               "name. The field would win at `.` dispatch and "
                               "the observable would be dead code. Rename one "
                               "or move the derivation to `computed:` under a "
                               "different name.")
                          c)))
             ;; F38 auto-detect: any observable → :class variant, else
             ;; :struct. Structural trigger, no user syntax. Base
             ;; :methods is empty; ext-composed methods bump variant
             ;; downstream if needed.
             variant (if (seq observables) :class :struct)]
         (assoc env name {:fields       fields
                          :field-index  (into {} (map (juxt :name identity) fields))
                          :methods      []
                          :method-index {}
                          :observables  observables
                          :variant      variant})))
     skeleton
     records)))
