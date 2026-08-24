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

(def protected-section-names
  "F8 D5 (decisions-frozen §12) — section-header names that neither
   `alias` declarations nor user `section class Name:` declarations
   may shadow. These are the behavioral sections shipped by the
   compiler (L3a `computed`, L3b `invariants`, L3c `metadata`, L3d
   `deprecated`) plus `use` (K6 capability requests).

   Shipping the registry with protected names avoids the L3a-era
   concern (\"users can shadow `computed` via `alias computed:`\")
   without retiring the registry entirely. Users can still declare
   their own `section class Foo:` for any UNPROTECTED name and it
   participates in the same dispatch table.

   Extend when new compiler-baked behavioral sections ship. Removing
   a name from this set is a compat-break (users may have been
   relying on the reservation)."
  #{:computed
    :invariants
    :metadata
    :deprecated
    :use})

(defn protected?
  "True when `nm` (string or keyword) is a protected section-header
   name that user code cannot shadow via `alias` or `section class`."
  [nm]
  (contains? protected-section-names (normalize-name nm)))

(def ^:private behavioral-seed-specs
  "F8 L3b.i (2026-08-24) — protected names whose backend implementation
   has landed but which aren't yet dispatched through the registry.
   Each entry carries `:impl` / `:backend` / `:parser-sym` + notes so
   downstream landings (L3b.ii+) can teach the parser to consult the
   registry and route through the recorded parser symbol; today the
   seed is descriptive metadata, forward-compatible with that
   dispatch wire-through.

   `:invariants` — F49 invariant-check machinery
   (`checkInvariants` method synthesis, pre-store checks,
   post-transition checks) has shipped since v0.11. The parser
   handles `invariants:` via `parse-invariants-section`; lower routes
   invariants through `def-atom-invariants` / extend-class merging.
   This seed makes that ownership explicit.

   `:deprecated` — K3 deprecation walker (build.clj Layer 6 Step 11
   `warn-deprecated-uses`) walks each parsed program for `:type-ref` /
   `:call` references to definitions carrying a `:deprecated` node
   and emits stderr warnings. The parser handles `deprecated:` via
   `parse-deprecated-section`. Same shape as `:invariants` — backend
   landed, registry ownership not yet wired.

   `:computed` intentionally stays `:reserved` even though L3a
   shipped: L3a chose parse-time sugar (rewrite to zero-arg
   observables before lower sees the AST) rather than a
   generator-carrying spec, so there's no `:spec` shape to record
   without inventing new metadata schema. Promoting `:computed`
   waits on a followup that decides the metadata schema for
   parse-sugar sections.

   `:use` — K6 partial capability-request extraction. Parser handles
   `use:` via `parse-use-section`; lower reads the caps set at
   multiple sites (atom-env building, transition-injection eligibility,
   effect-checking dispatch). Same L3b.i shape — backend landed,
   registry ownership not yet wired.

   `:metadata` remains `:reserved` — metadata artifact emission is
   F53-adjacent but goes through `.cxc-manifest.json`, not a
   `metadata:` section."
  {:invariants {:impl        :compiler-baked
                :backend     :f49-invariant-machinery
                :parser-sym  'cxc.parse/parse-invariants-section
                :notes       "L3b.i metadata-only; L3b.ii will wire dispatch."}
   :deprecated {:impl        :compiler-baked
                :backend     :k3-deprecation-walker
                :parser-sym  'cxc.parse/parse-deprecated-section
                :walker-sym  'cxc.build/warn-deprecated-uses
                :notes       "L3d.i metadata-only; L3d.ii will wire dispatch."}
   :use        {:impl        :compiler-baked
                :backend     :k6-capability-extraction
                :parser-sym  'cxc.parse/parse-use-section
                :notes       "L3-use.i metadata-only; wire-through waits on the full K6 landing."}})

(def registry
  "Section-class registry — `{section-name-keyword → spec}`. Seeded
   with F8 D5 protected reservations. Names with a landed backend
   carry `:kind :behavioral` + a spec pointer (L3b.i: `invariants`
   → F49 machinery; L3d.i: `deprecated` → K3 walker; L3-use.i:
   `use` → K6 capability extraction); names still awaiting
   behavioral wire-in carry `:kind :reserved` + `:spec nil`. L4
   (`section class Name:` user syntax) adds entries for unprotected
   names. Kept as a var so the parser hook and future L4
   declaration handler consult a single canonical location.

   Registry entries have shape:
     {:name    :foo
      :kind    :primitive-composition | :behavioral | :alias-expanded
               | :reserved
      :spec    <compose-result or generator-carrying spec, or nil for
                :reserved entries pending L3b/c/d>}"
  (atom
   (into {}
         (map (fn [nm]
                (if-let [seed (get behavioral-seed-specs nm)]
                  [nm {:name nm :kind :behavioral :spec seed}]
                  [nm {:name nm :kind :reserved   :spec nil}])))
         protected-section-names)))

(defn lookup
  "Look up a section-header name in the registry. Returns spec or nil."
  [nm]
  (get @registry (normalize-name nm)))

(defn behavioral-parser
  "F8 L3b.ii.α — resolve the parser fn for a behavioral section-header
   name via the registry. Returns a var (invocable as a fn) or nil.

   Returns nil when: (a) `nm` has no registry entry, (b) the entry is
   not `:kind :behavioral`, (c) the entry's spec carries no
   `:parser-sym`. Otherwise returns `(requiring-resolve parser-sym)` —
   the target namespace is loaded on demand, and subsequent calls
   hit the resolved var directly.

   Case-insensitive via `lookup`. Callers invoke the returned var as
   `[tokens pos] → [node next-pos]` matching the shape of
   `parse-invariants-section` / `parse-deprecated-section` /
   `parse-use-section`.

   No parser wire-through yet — this fn is the substrate. L3b.ii.β
   (def-atom-body dispatch) and L3b.iii (class-body dispatch) will
   call this from parse.clj's cond arms."
  [nm]
  (let [e (lookup nm)]
    (when (= :behavioral (:kind e))
      (when-let [sym (get-in e [:spec :parser-sym])]
        (requiring-resolve sym)))))

(defn behavioral-walker
  "F8 L3d.ii.α — resolve the post-parse walker fn for a behavioral
   section-header name via the registry. Returns a var or nil. Same
   nil-return conditions as `behavioral-parser`, plus nil when the
   entry's spec carries no `:walker-sym` (e.g. `:invariants` and
   `:use` — their lower-time processing happens inline, not as a
   post-parse walker).

   Callers invoke the returned var per the walker's own signature
   (currently only `warn-deprecated-uses [parsed deprecations]`)."
  [nm]
  (let [e (lookup nm)]
    (when (= :behavioral (:kind e))
      (when-let [sym (get-in e [:spec :walker-sym])]
        (requiring-resolve sym)))))

(defn- seed-protected-reservations! []
  (into {}
        (map (fn [nm]
               (if-let [seed (get behavioral-seed-specs nm)]
                 [nm {:name nm :kind :behavioral :spec seed}]
                 [nm {:name nm :kind :reserved   :spec nil}])))
        protected-section-names))

(defn register!
  "Add / overwrite a registry entry. Returns the added entry.
   Idempotent under identical spec; conflicting redeclarations are the
   caller's responsibility to detect (L4 will layer duplicate-check on
   top per F7 L1 Q2 error-on-tie discipline).

   Rejects protected section-header names (per F8 D5, decisions-frozen
   §12): `alias computed: ...` / `section class Computed:` and their
   siblings hard-error at registration time with a targeted diagnostic
   naming the reserved header and the reason. The check runs at
   `register!` so the seed reservations (L3a `computed`, plus reserved
   `invariants`/`metadata`/`deprecated`/`use`) can't be silently
   overwritten by user code.

   Internal seeding uses `reset!` directly rather than `register!`, so
   this check only fires on user-initiated registrations."
  [nm entry]
  (let [k (normalize-name nm)]
    (when (protected? k)
      (throw (ex-info
              (str "F8 D5: `" (name k) "` is a protected section-header "
                   "name (compiler-baked behavioral section). Users "
                   "cannot shadow protected names via `alias " (name k)
                   ": ...` or `section class " (name k) ":`. Pick a "
                   "different name for your composition.")
              {:kind :cxc-f8-protected-section-name-shadow
               :name k
               :protected-names (vec (sort protected-section-names))})))
    (swap! registry assoc k (assoc entry :name k))
    (lookup k)))

(defn clear-registry!
  "Test helper — wipes the registry and re-seeds the F8 D5 protected
   reservations so subsequent tests see a clean slate that still
   reflects the compiler-baked reservations. Not for production use."
  []
  (reset! registry (seed-protected-reservations!)))

;; ---------------------------------------------------------------------------
;; Section-header alias registration (F7 L1-b + F8 L1-b bridge)
;; ---------------------------------------------------------------------------

(defn register-section-header-alias!
  "F7 L1-b + F8 L1-b — validate a section-header alias's primitive list
   via `compose` and register the composed spec in `registry` under the
   alias name. Called by the parser when a `:section-header` alias
   declaration is parsed so subsequent section headers in the same
   source can consult the registry via `lookup`.

   Compose-time errors (unknown primitives, axis contradictions) surface
   with the alias name attached for source-attribution."
  [{:keys [name primitives]}]
  (try
    (let [composed (compose primitives)]
      (register! name
                 {:kind :alias-expanded
                  :spec composed}))
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info
              (str "F7 L1-b: section-header alias `" name
                   "` failed compose validation: " (.getMessage e))
              (assoc (ex-data e) :alias name))))))
