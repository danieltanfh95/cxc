(ns cxc.section
  "Section-class primitive vocabulary + composition (spec §4.10.1)
   and the compiler-baked section-header registry.

   Core scope:
   - Primitive definitions as pure data (`primitives`).
   - `compose` — merge N primitives into a section spec, rejecting
     same-axis contradictions and surfacing legal-but-meaningless
     compositions as warnings.
   - Registry data for compiler-baked behavioral section headers
     (`invariants`, `use`, `computed`, `metadata`, `deprecated`).

   Design decisions:
   - **Primitive names are case-insensitive on lookup, canonical
     lowercase in storage.** Spec §4.10.1 writes `Runtime`,
     `Immutable` in prose but section-header usage is lowercase
     (`runtime:`, `immutable:`). Keys are keywords in lowercase
     (`:runtime`); `normalize-name` is the one canonicalization seam.
   - **Contradictions are error-on-compose.** Compatibility axes
     (existence, mutability, equality-contribution) admit exactly
     one value at a time; two primitives disagreeing on an axis
     produce a `:cxc-f8-primitive-contradiction` diagnostic.
   - **Cross-axis compositions are legal.** `runtime immutable
     identity` combines three primitives on three different axes
     and merges cleanly.
   - **Semantically-meaningless compositions warn.** Per §4.10.1
     `compile_time weak` and `identity weak` on a class field are
     legal but warned."
  (:require [clojure.string :as str]
            [cxc.diagnostics :as diag]))

;; ---------------------------------------------------------------------------
;; Primitive vocabulary
;; ---------------------------------------------------------------------------

(def ^:private primitives
  "Spec §4.10.1 Stage-1 primitives. Each entry describes one primitive
   section-class and the axis it contributes to. The axis label is the
   compose-time contradiction key: two primitives sharing an axis
   cannot appear in the same header. The entry's `:name` is derived
   from its map key at lookup time, so adding a primitive is one
   entry."
  {:runtime      {:axis :existence
                  :contributes {:default-existence :runtime}}
   :compile_time {:axis :existence
                  :contributes {:default-existence :compile-time}}

   :immutable    {:axis :mutability
                  :contributes {:default-mutability :immutable}}
   :mutable      {:axis :mutability
                  :contributes {:default-mutability :mutable}}

   :expose       {:axis :visibility
                  :contributes {:default-visibility :exposed}}

   :weak         {:axis :reference-strength
                  :contributes {:reference-strength :weak}}
   :unowned      {:axis :reference-strength
                  :contributes {:reference-strength :unowned}}

   :identity     {:axis :equality-contribution
                  :contributes {:contributes-to-equality true
                                :contributes-to-hash     true
                                :contributes-to-copy     true}}
   :coordination {:axis :equality-contribution
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
  (if (or (keyword? x) (symbol? x) (string? x))
    (keyword (str/lower-case (name x)))
    (diag/fail-compile!
      :cxc-f8-bad-primitive-name
      (str "F8: primitive name must be string/symbol/keyword; got "
           (pr-str x))
      nil
      {:value x})))

(defn primitive
  "Look up a primitive by name (case-insensitive). Returns the spec
   map with `:name` attached, or nil if the name isn't a known
   primitive."
  [nm]
  (let [k (normalize-name nm)]
    (when-let [spec (get primitives k)]
      (assoc spec :name k))))

;; ---------------------------------------------------------------------------
;; Composition — merge N primitives into a section spec
;; ---------------------------------------------------------------------------

(defn- reject-unknown!
  [names]
  (doseq [n names]
    (when-not (primitive n)
      (diag/fail-compile!
        :cxc-f8-unknown-primitive
        (str "F8: unknown section primitive `" (name (normalize-name n))
             "`. Known primitives: "
             (sort (map name (keys primitives))))
        nil
        {:name (normalize-name n)}))))

(defn- reject-axis-conflicts!
  "Group by axis; more than one primitive on the same axis is a
   contradiction. Diagnostic names both primitives + the axis."
  [prims]
  (doseq [[axis entries] (group-by :axis prims)
          :when (> (count entries) 1)]
    (diag/fail-compile!
      :cxc-f8-primitive-contradiction
      (str "F8: section primitives conflict on axis `" (name axis)
           "`, got " (pr-str (mapv (comp name :name) entries))
           ". Each axis admits at most one primitive per header.")
      nil
      {:axis axis
       :conflicting (mapv :name entries)})))

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
   `contributes` is the merged metadata contribution — cross-axis
   merges never collide by design because `reject-axis-conflicts!`
   runs first. `warnings` is a vector of §4.10.1 meaningless-
   composition maps (empty when clean).

   Throws `:cxc-f8-unknown-primitive` on unknown names and
   `:cxc-f8-primitive-contradiction` on same-axis collisions."
  [prim-names]
  (reject-unknown! prim-names)
  (let [canonical (mapv normalize-name prim-names)
        prims     (mapv primitive canonical)]
    (reject-axis-conflicts! prims)
    {:primitives  canonical
     :contributes (reduce merge {} (map :contributes prims))
     :warnings    (vec (collect-warnings canonical))}))

;; ---------------------------------------------------------------------------
;; Registry — compiler-baked sections and internal primitive compositions
;; ---------------------------------------------------------------------------

(def ^:private protected-section-names
  "Compiler-baked behavioral section-header names: the sections
   shipped by the compiler (`computed`, `invariants`, `metadata`,
   `deprecated`) plus `use` (K6 capability requests). Kept as an
   internal reservation set so future compiler-internal
   registrations don't collide with a baked name."
  #{:computed
    :invariants
    :metadata
    :deprecated
    :use})

(defn protected?
  "True when `nm` (string or keyword) is a compiler-baked
   section-header name."
  [nm]
  (contains? protected-section-names (normalize-name nm)))

(def ^:private behavioral-seed-specs
  "Protected names whose backend implementation has landed and
   carry parser/walker metadata consulted by the registry
   dispatch wire-through.

   `:invariants` — F49 invariant-check machinery (checkInvariants
   method synthesis, pre-store checks, post-transition checks) has
   shipped. The parser produces normalized `:proposition` nodes
   directly at each legal `|:` position, so this registry entry
   is intentionally parser-less. Lower routes def-body/extension
   propositions through `def-atom-invariants` / extend-class
   merging.

   `:computed` intentionally stays `:reserved`: parse-time sugar
   rewrites to zero-arg observables before lower sees the AST,
   leaving no generator-carrying spec to record.

   `:use` — K6 capability-request extraction. Parser handles
   direct `use <name>: <Type>` via `parse-use-line`; lower reads
   the caps set at multiple sites.

   `:metadata` + `:deprecated` seeds retired alongside the
   annotation-grammar retirement wave. Kind info flows through
   body-shape inference (§3.3); deprecation is expressed as a
   comment/convention at v1.0.

   Seed flags:
   - `:allowed-in-extend?` — is the section permitted inside
     `extend Foo:` bodies? Default false (spec §3.3.2 restricts
     extensions from adding state, capabilities, or deprecation).
     `:invariants` is true since invariants compose additively
     across extensions. `:use` stays false — extend rejects it
     by design."
  {:invariants {:impl               :compiler-baked
                :backend            :f49-invariant-machinery
                :parser-less?       true
                :allowed-in-extend? true
                :notes              "`|:` propositions parse directly; F49 consumes their normalized records."}
   :use        {:impl               :compiler-baked
                :backend            :k6-capability-extraction
                :parser-sym         'cxc.parse/parse-use-line
                :allowed-in-extend? false
                :notes              "Direct-line parser preserves the normalized :use-section/:caps shape consumed by K6."}})

(defn- seed-registry
  "Pure function from the two compile-time constants
   (`protected-section-names` + `behavioral-seed-specs`) to the
   section-class registry map."
  []
  (into {}
        (map (fn [nm]
               (if-let [seed (get behavioral-seed-specs nm)]
                 [nm {:name nm :kind :behavioral :spec seed}]
                 [nm {:name nm :kind :reserved   :spec nil}])))
        protected-section-names))

(def ^:private registry
  "Section-class registry: `{section-name-keyword → spec}`. Pure
   value, computed once at ns load. Names with a landed backend
   carry `:kind :behavioral` + a spec pointer (invariants → F49
   machinery; use → K6 capability extraction); names still
   awaiting behavioral wire-in carry `:kind :reserved` + `:spec nil`.

   `metadata` + `deprecated` entries retired with the
   annotation-grammar-retirement wave; both carry reserved-name
   status (no behavioral seed).

   Registry entries have shape:
     {:name    :foo
      :kind    :behavioral | :reserved
      :spec    <compose-result or generator-carrying spec, or nil
                for :reserved entries pending a later landing>}"
  (seed-registry))

(defn lookup
  "Look up a section-header name in the registry. Returns the
   registry entry or nil."
  [nm]
  (get registry (normalize-name nm)))

(defn- behavioral-spec-sym-resolve
  "Resolve a sym on a behavioral entry's spec via `requiring-resolve`.
   Returns nil when the lookup misses, the entry isn't `:behavioral`,
   or the spec carries no `sym-key`. Backs both `behavioral-parser`
   (`:parser-sym`) and `behavioral-walker` (`:walker-sym`)."
  [nm sym-key]
  (let [e (lookup nm)]
    (when (= :behavioral (:kind e))
      (when-let [sym (get-in e [:spec sym-key])]
        (requiring-resolve sym)))))

(defn behavioral-parser
  "Resolve the parser fn for a behavioral section name via the
   registry. Returns a var (invocable as a fn) or nil.

   Returns nil when: (a) `nm` has no registry entry, (b) the entry
   is not `:kind :behavioral`, (c) the entry's spec carries no
   `:parser-sym`. Otherwise returns `(requiring-resolve parser-sym)`
   — the target namespace is loaded on demand.

   Case-insensitive via `lookup`. Callers invoke the returned var
   as `[tokens pos] → [node next-pos]`. Only `:use` carries a
   `:parser-sym` at v1.0 (parse-use-line). Parser-less behavioral
   entries like `:invariants` return nil because their direct
   syntax has its own positional parser entry point."
  [nm]
  (behavioral-spec-sym-resolve nm :parser-sym))

(defn allowed-in-extend?
  "Is the behavioral section-header allowed inside `extend Foo:`
   bodies? Reads `:allowed-in-extend?` on the seed's spec; default
   false (spec §3.3.2 restricts extensions from adding state,
   capabilities, or deprecation). True today only for `:invariants`.

   Callers combine this with `behavioral-parser` to gate the
   extend-class body registry-arm."
  [nm]
  (let [e (lookup nm)]
    (boolean (get-in e [:spec :allowed-in-extend?]))))

(defn behavioral-walker
  "Resolve the post-parse walker fn for a behavioral registry
   name. Returns a var or nil. Same nil-return conditions as
   `behavioral-parser`, plus nil when the entry's spec carries no
   `:walker-sym` (e.g. `:invariants` and `:use` — their lower-time
   processing happens inline, not as a post-parse walker).

   Post annotation-grammar-retirement no registry entry carries a
   `:walker-sym`; this fn always returns nil at v1.0."
  [nm]
  (behavioral-spec-sym-resolve nm :walker-sym))

(defn behavioral-walkers
  "Enumerate every registry entry whose spec carries a `:walker-sym`
   and return `{section-name → walker-var}` (a sorted-map for
   deterministic iteration order). Returns an empty map at v1.0
   since no seed carries a `:walker-sym`. The caller's doseq is a
   no-op; the substrate stays so future walker landings need no
   wiring change.

   Skips reserved entries + behavioral entries without a
   `:walker-sym`. Walker resolution is on-demand via
   `requiring-resolve`."
  []
  (into (sorted-map)
        (keep (fn [[nm entry]]
                (when (= :behavioral (:kind entry))
                  (when-let [sym (get-in entry [:spec :walker-sym])]
                    [nm (requiring-resolve sym)]))))
        registry))
