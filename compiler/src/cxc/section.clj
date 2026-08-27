(ns cxc.section
  "F8 L1-a — Section-class primitive vocabulary + composition semantics.

   This module ships the atomic vocabulary and the compiler-internal
   composition function that merges multiple primitives into a single
   section spec, catching contradictions at compose time.

   Core scope:
   - Primitive definitions as pure data (`primitives`).
   - `compose` — merge N primitives, reject contradictions.
   - Registry data for compiler-baked behavioral sections and internal
     primitive compositions.

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
     combines three primitives on three different axes and merges cleanly.
   - **Semantically-meaningless compositions warn.** Per §4.10.1
     `compile_time weak` and `identity weak` on a class field are
     legal but warned. L1-a ships the classifier; warning emission
     wires in when parser hook lands."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Primitive vocabulary
;; ---------------------------------------------------------------------------

(def ^:private primitives
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
;; Registry — compiler-baked sections and internal primitive compositions
;; ---------------------------------------------------------------------------

(def ^:private protected-section-names
  "Compiler-baked behavioral section-header names. These are the
   sections shipped by the compiler (L3a `computed`, L3b `invariants`,
   L3c `metadata`, L3d `deprecated`) plus `use` (K6 capability
   requests). Kept as an internal reservation set so future compiler-
   internal registrations don't collide with a baked name."
  #{:computed
    :invariants
    :metadata
    :deprecated
    :use})

(defn protected?
  "True when `nm` (string or keyword) is a compiler-baked section-header
   name."
  [nm]
  (contains? protected-section-names (normalize-name nm)))

(def ^:private behavioral-seed-specs
  "F8 L3b.i (2026-08-24) — protected names whose backend implementation
   has landed but which aren't yet dispatched through the registry.
   Each entry carries `:impl` / `:backend` plus parser/walker metadata so
   downstream landings (L3b.ii+) can teach the parser to consult the
   registry and route through the recorded parser symbol; today the
   seed is descriptive metadata, forward-compatible with that
   dispatch wire-through.

   `:invariants` — F49 invariant-check machinery
   (`checkInvariants` method synthesis, pre-store checks,
   post-transition checks) has shipped since v0.11. The L2a parser
   produces normalized `:proposition` nodes directly at each legal
   `|:` position, so this registry entry is intentionally parser-less.
   Lower routes def-body/extension propositions through
   `def-atom-invariants` / extend-class merging. This seed makes that
   ownership explicit without reviving section-header dispatch.

   `:deprecated` — K3 deprecation walker (build.clj Layer 6 Step 11
   `warn-deprecated-uses`) walks each parsed program for `:type-ref` /
   `:call` references to definitions carrying a `@deprecated(\"msg\")`
   annotation and emits stderr warnings. The L3a top-level annotation
   parser owns the sole parse surface; the walker remains registry-routed.

   `:computed` intentionally stays `:reserved` even though L3a
   shipped: L3a chose parse-time sugar (rewrite to zero-arg
   observables before lower sees the AST) rather than a
   generator-carrying spec, so there's no `:spec` shape to record
   without inventing new metadata schema. Promoting `:computed`
   waits on a followup that decides the metadata schema for
   parse-sugar sections.

   `:metadata` — F53 sidecar manifest emission. The L3a top-level
   annotation parser owns the sole parse surface; parsed metadata
   annotations are retained by resolve and emitted on declaration
   exports in `<module>.cxc-manifest.json`. There is deliberately no
   section-header parser.

   `:use` — K6 partial capability-request extraction. Parser handles
   direct `use <name>: <Type>` via `parse-use-line`; lower reads the caps set at
   multiple sites (atom-env building, transition-injection eligibility,
   effect-checking dispatch). Same L3b.i shape — backend landed,
   registry ownership not yet wired.

   Seed flags:
   - `:allowed-in-extend?` — F8 L3b.iii — is the section permitted
     inside `extend Foo:` bodies? Default false (spec §3.3.2 restricts
     extensions from adding state, capabilities, or deprecation).
     `:invariants` is true since invariants compose additively across
     extensions per F49's extension-invariant merging. `:use` and
     `:deprecated` stay false — extend rejects them by design."
  {:invariants {:impl               :compiler-baked
                :backend            :f49-invariant-machinery
                :parser-less?       true
                :allowed-in-extend? true
                :notes              "L2b: `|:` propositions parse directly; F49 consumes their normalized records."}
   :metadata   {:impl               :compiler-baked
                :backend            :f53-manifest-emission
                :parser-sym         'cxc.parse/parse-annotation
                :allowed-in-extend? false
                :notes              "L3c: `@metadata(...)` annotations feed F53 declaration exports; no section-header backend exists."}
   :deprecated {:impl               :compiler-baked
                :backend            :k3-deprecation-walker
                :parser-sym         'cxc.parse/parse-annotation
                :walker-sym         'cxc.build/warn-deprecated-uses
                :allowed-in-extend? false
                :notes              "L3b: `@deprecated(\"msg\")` annotations feed the unchanged K3 walker."}
   :use        {:impl               :compiler-baked
                :backend            :k6-capability-extraction
                :parser-sym         'cxc.parse/parse-use-line
                :allowed-in-extend? false
                :notes              "L4 direct-line parser preserves the normalized :use-section/:caps shape consumed by K6."}})

(def ^:private registry
  "Section-class registry — `{section-name-keyword → spec}`. Compiler-
   internal. Seeded with the compiler-baked reservations. Names with
   a landed backend carry `:kind :behavioral` + a spec pointer
   (L3b.i: `invariants` → F49 machinery; L3c: `metadata` → F53
   manifest emission; L3d.i: `deprecated` → K3 walker; L3-use.i:
   `use` → K6 capability extraction); names still awaiting behavioral
   wire-in carry `:kind :reserved` + `:spec nil`. Kept as a var so
   the parser hook and post-parse walker enumeration consult a single
   canonical location.

   Registry entries have shape:
     {:name    :foo
      :kind    :behavioral | :reserved
      :spec    <compose-result or generator-carrying spec, or nil for
                :reserved entries pending a later landing>}"
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
  "F8 L3b.ii.α — resolve the parser fn for a behavioral construct
   name via the registry. Returns a var (invocable as a fn) or nil.

   Returns nil when: (a) `nm` has no registry entry, (b) the entry is
   not `:kind :behavioral`, (c) the entry's spec carries no
   `:parser-sym`. Otherwise returns `(requiring-resolve parser-sym)` —
   the target namespace is loaded on demand, and subsequent calls
   hit the resolved var directly.

   Case-insensitive via `lookup`. Callers invoke the returned var as
   `[tokens pos] → [node next-pos]`. A parser may own a direct line
   (`parse-use-line`) or a consolidated surface (`parse-annotation`
   for `@deprecated`). Parser-less behavioral entries such as
   `:invariants` return nil because their direct syntax has its own
   positional parser entry point.

   No parser wire-through yet — this fn is the substrate. L3b.ii.β
   (def-atom-body dispatch) and L3b.iii (class-body dispatch) will
   call this from parse.clj's cond arms."
  [nm]
  (let [e (lookup nm)]
    (when (= :behavioral (:kind e))
      (when-let [sym (get-in e [:spec :parser-sym])]
        (requiring-resolve sym)))))

(defn allowed-in-extend?
  "F8 L3b.iii — is the behavioral section-header allowed inside
   `extend Foo:` bodies? Reads `:allowed-in-extend?` on the seed's
   spec; default false (spec §3.3.2 restricts extensions from adding
   state, capabilities, or deprecation). True today only for
   `:invariants` — invariants compose additively across extensions
   per F49's extension-invariant merging.

   Callers combine this with `behavioral-parser` to gate the
   extend-class body registry-arm: skip the arm when
   `allowed-in-extend?` returns false, so direct `use` and `deprecated:`
   still fall through to their hardcoded rejection arms. The true
   `:invariants` flag now records that extension propositions compose;
   `|:` itself parses through the direct positional entry point."
  [nm]
  (let [e (lookup nm)]
    (boolean (get-in e [:spec :allowed-in-extend?]))))

(defn behavioral-walker
  "F8 L3d.ii.α — resolve the post-parse walker fn for a behavioral
   registry name. Returns a var or nil. Same
   nil-return conditions as `behavioral-parser`, plus nil when the
   entry's spec carries no `:walker-sym` (e.g. `:invariants` and
   `:use` — their lower-time processing happens inline, not as a
   post-parse walker).

   Callers invoke the returned var per the walker's own signature
   (currently only `warn-deprecated-uses [user-parsed full-parsed]`)."
  [nm]
  (let [e (lookup nm)]
    (when (= :behavioral (:kind e))
      (when-let [sym (get-in e [:spec :walker-sym])]
        (requiring-resolve sym)))))

(defn behavioral-walkers
  "F8 L3d.ii.α+ — enumerate every registry entry whose spec carries a
   `:walker-sym` and return `{section-name → walker-var}` (a sorted-map
   for deterministic iteration order across runs).

   Substrate for `build/compile-source-to-file` to iterate walkers via
   the registry instead of hardcoding a `warn-deprecated-uses` call.
   Today only `:deprecated` carries a walker-sym, so the returned map
   has one entry for the compiler-baked K3 walker.

   Skips reserved entries + behavioral entries without a `:walker-sym`.
   Walker resolution is on-demand via `requiring-resolve` — the target
   namespace loads lazily on first call."
  []
  (into (sorted-map)
        (keep (fn [[nm entry]]
                (when (= :behavioral (:kind entry))
                  (when-let [sym (get-in entry [:spec :walker-sym])]
                    [nm (requiring-resolve sym)]))))
        @registry))

(defn- seed-protected-reservations! []
  (into {}
        (map (fn [nm]
               (if-let [seed (get behavioral-seed-specs nm)]
                 [nm {:name nm :kind :behavioral :spec seed}]
                 [nm {:name nm :kind :reserved   :spec nil}])))
        protected-section-names))

(defn clear-registry!
  "Test helper — wipes the registry and re-seeds the F8 D5 protected
   reservations so subsequent tests see a clean slate that still
   reflects the compiler-baked reservations. Not for production use."
  []
  (reset! registry (seed-protected-reservations!)))
