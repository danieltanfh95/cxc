(ns cxc.section
  "F8 L1-a — Section-class primitive vocabulary + composition semantics.

   Spec ref: §4.10.1 (primitive sections) + §4.10.2 (aliases). This
   module ships the atomic vocabulary and the composition function
   that merges multiple primitives into a single section spec, catching
   contradictions at compose time.

   Landing scope (L1-a):
   - Primitive definitions as pure data (`primitives`).
   - `compose` — merge N primitives, reject contradictions.
   - Registry stub (`registry`) for user-declared / prelude-declared
     section classes to populate later (L4 / L2c).

   Not shipped in L1-a:
   - Parser hook consulting the registry (L1-b bundled with L4).
   - Prelude alias declarations (data, state, static, methods — F7 L2c).
   - Behavioral sections + generators (L3a-d).
   - `section class Name:` user syntax (L4).

   Design decisions:
   - **Primitive names are case-insensitive on lookup, canonical
     lowercase in storage.** Spec §4.10.1 writes `Runtime`, `Immutable`
     in prose but section-header usage is lowercase (`runtime:`,
     `immutable:`). We store keys as keywords in lowercase (`:runtime`)
     and normalize on lookup.
   - **Contradictions are error-on-compose.** Compatibility axes
     (existence, mutability, equality-contribution) admit exactly one
     value at a time; two primitives disagreeing on an axis produce a
     `:cxc-f8-primitive-contradiction` diagnostic.
   - **Cross-axis compositions are legal.** `runtime immutable identity`
     is the canonical `data:` alias per §4.10.2 — three primitives on
     three different axes merge cleanly.
   - **Semantically-meaningless compositions warn.** Per §4.10.1
     `compile_time weak` and `identity weak` on a class field are
     legal but warned. L1-a ships the classifier; warning emission
     wires in when parser hook lands."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Primitive vocabulary
;; ---------------------------------------------------------------------------

(def primitives
  "Spec §4.10.1 Stage-1 primitives. Each entry describes one primitive
   section-class and the axis it contributes to. The axis label is the
   compose-time contradiction key: two primitives sharing an axis
   cannot appear in the same header."
  {:runtime      {:name :runtime      :axis :existence
                  :contributes {:default-existence :runtime}}
   :compile_time {:name :compile_time :axis :existence
                  :contributes {:default-existence :compile-time}}

   :immutable    {:name :immutable    :axis :mutability
                  :contributes {:default-mutability :immutable}}
   :mutable      {:name :mutable      :axis :mutability
                  :contributes {:default-mutability :mutable}}

   :expose       {:name :expose       :axis :visibility
                  :contributes {:default-visibility :exposed}}

   :weak         {:name :weak         :axis :reference-strength
                  :contributes {:reference-strength :weak}}
   :unowned      {:name :unowned      :axis :reference-strength
                  :contributes {:reference-strength :unowned}}

   :identity     {:name :identity     :axis :equality-contribution
                  :contributes {:contributes-to-equality true
                                :contributes-to-hash     true
                                :contributes-to-copy     true}}
   :coordination {:name :coordination :axis :equality-contribution
                  :contributes {:contributes-to-equality false
                                :contributes-to-hash     false
                                :contributes-to-copy     false}}})

(def ^:private warn-pairs
  "Spec §4.10.1 — legal but semantically meaningless. Compose-time
   emits a warning; runtime behavior is the straight merge."
  #{#{:compile_time :weak}
    #{:identity :weak}})

;; ---------------------------------------------------------------------------
;; Normalization + lookup
;; ---------------------------------------------------------------------------

(defn- normalize-name
  "Accept a primitive reference as a string (`\"runtime\"`), symbol
   (`'runtime`), or keyword (`:runtime`). Return the canonical keyword."
  [x]
  (cond
    (keyword? x) (keyword (str/lower-case (name x)))
    (symbol? x)  (keyword (str/lower-case (name x)))
    (string? x)  (keyword (str/lower-case x))
    :else (throw (ex-info
                  (str "F8: primitive name must be string/symbol/keyword; got "
                       (pr-str x))
                  {:kind :cxc-f8-bad-primitive-name :value x}))))

(defn primitive
  "Look up a primitive by name (case-insensitive). Returns the spec map
   or nil if the name isn't a known primitive."
  [nm]
  (get primitives (normalize-name nm)))

;; ---------------------------------------------------------------------------
;; Composition — merge N primitives into a section spec
;; ---------------------------------------------------------------------------

(defn- reject-unknown!
  [names]
  (doseq [n names]
    (when-not (primitive n)
      (throw (ex-info
              (str "F8: unknown section primitive `" (name (normalize-name n))
                   "`. Known primitives: "
                   (sort (map name (keys primitives))))
              {:kind :cxc-f8-unknown-primitive :name (normalize-name n)})))))

(defn- reject-axis-conflicts!
  "Group by axis; more than one primitive on the same axis is a
   contradiction. Diagnostic names both primitives + the axis."
  [prims]
  (let [by-axis (group-by :axis prims)]
    (doseq [[axis entries] by-axis
            :when (> (count entries) 1)]
      (throw (ex-info
              (str "F8: section primitives conflict on axis `" (name axis)
                   "` — got " (pr-str (mapv (comp name :name) entries))
                   ". Each axis admits at most one primitive per header.")
              {:kind :cxc-f8-primitive-contradiction
               :axis axis
               :conflicting (mapv :name entries)})))))

(defn- collect-warnings
  "Return sequence of warning maps for §4.10.1 legal-but-meaningless
   compositions. Caller decides how to surface them."
  [prim-names]
  (let [set-of (set prim-names)]
    (keep (fn [pair]
            (when (every? set-of pair)
              {:kind :cxc-f8-primitive-warn-composition
               :primitives (vec pair)}))
          warn-pairs)))

(defn compose
  "Compose N primitive names into a single section spec.

   Returns `{:primitives [...] :contributes {...} :warnings [...]}`.
   `primitives` is the canonicalized keyword vector in input order.
   `contributes` is the merged metadata contribution — later primitives
   overwrite earlier on cross-axis merges (though cross-axis merges
   never collide by design). `warnings` is a vector of §4.10.1
   meaningless-composition maps (empty when clean).

   Throws `:cxc-f8-unknown-primitive` on unknown names and
   `:cxc-f8-primitive-contradiction` on same-axis collisions."
  [prim-names]
  (reject-unknown! prim-names)
  (let [canonical (mapv normalize-name prim-names)
        prims     (mapv primitives canonical)]
    (reject-axis-conflicts! prims)
    {:primitives  canonical
     :contributes (reduce merge {} (map :contributes prims))
     :warnings    (vec (collect-warnings canonical))}))

;; ---------------------------------------------------------------------------
;; Registry — populated by L4 user decls + L2c prelude aliases
;; ---------------------------------------------------------------------------

(def registry
  "Section-class registry — `{section-name-keyword → spec}`. Empty in
   L1-a; L2c (prelude aliases) + L4 (user `section class Name:` decls)
   populate it. Kept as a var so the parser hook can consult a
   single canonical location once wired in.

   Registry entries have shape:
     {:name    :foo
      :kind    :primitive-composition | :behavioral | :alias
      :spec    <compose-result or generator-carrying spec>}"
  (atom {}))

(defn lookup
  "Look up a section-header name in the registry. Returns spec or nil."
  [nm]
  (get @registry (normalize-name nm)))

(defn register!
  "Add / overwrite a registry entry. Returns the added entry.
   Idempotent under identical spec; conflicting redeclarations are the
   caller's responsibility to detect (L4 will layer duplicate-check on
   top per F7 L1 Q2 error-on-tie discipline)."
  [nm entry]
  (let [k (normalize-name nm)]
    (swap! registry assoc k (assoc entry :name k))
    (lookup k)))

(defn clear-registry!
  "Test helper — wipes the registry. Not for production use."
  []
  (reset! registry {}))
