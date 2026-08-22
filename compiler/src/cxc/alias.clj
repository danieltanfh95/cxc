(ns cxc.alias
  "F7 L1 — Alias system, foundational substrate.

   Ships an AST-level expansion pass that runs post-parse, pre-lower.
   Reads `:alias` declaration nodes from the parsed top-level, builds
   a position-keyed registry, and walks the rest of the AST substituting
   matching references with the aliased body.

   Design decisions committed in this landing:
   - **Expansion timing**: AST-level (not token-stream). Better
     diagnostics, cleaner error locations, aligns with Clojure-inspired
     macro model (§4.16 open Q1).
   - **Precedence conflict**: error-on-tie. Two `:alias` declarations
     for the same (position, name) pair fail-compile (§4.16 open Q2).
   - **Recursive expansion cap**: hard N=32. Diagnostic names the
     expansion chain (§4.16 open Q4).

   MVP scope (L1-a):
   - Type-position aliases only (`alias UserID: UInt64`).
   - Non-parametric — bare identifier substitution.

   Follow-on within L1 (L1-b, L1-c ...):
   - Section-header position hook.
   - Parametric aliases (`alias StringDict<V>: Map<String, V>`).
   - Delimited-pattern position (needs F42).

   Downstream landings (L2a-e) populate the registry from prelude
   sources. L3 retires the compiler-baked sugar paths that alias
   expansion supersedes."
  (:require [clojure.walk :as walk]))

;; ---------------------------------------------------------------------------
;; Registry construction
;; ---------------------------------------------------------------------------

(def ^:private max-expansion-depth
  "Hard cap on recursive alias expansion to catch cycles + runaway
   substitutions. Matches the macro-expansion limits in Clojure/Rust."
  32)

(defn- alias-nodes
  "Extract `:alias` decls from the top-level node list. Returns them
   in encounter order so precedence conflicts can be detected in a
   single pass."
  [top-nodes]
  (filterv #(= :alias (:node %)) top-nodes))

(defn- reject-duplicate-aliases!
  "F7 L1 open Q2 — error-on-tie precedence. Two `:alias` declarations
   sharing the same `(position, name)` fail-compile with both source
   positions named."
  [aliases]
  (let [by-key (group-by (juxt :position :name) aliases)]
    (doseq [[[position nm] entries] by-key
            :when (> (count entries) 1)]
      (throw (ex-info
              (str "F7 L1: duplicate alias declaration for `" nm
                   "` in position `" (name position) "` — "
                   (count entries) " declarations detected. Aliases "
                   "with the same (position, name) are ambiguous; "
                   "keep at most one or rename the redundant ones.")
              {:kind :cxc-f7-duplicate-alias
               :name nm :position position :count (count entries)})))))

(defn build-registry
  "Walk `top-nodes`, extract every `:alias` declaration, and produce a
   position-keyed registry `{position → {name → body-AST}}`. Rejects
   duplicate (position, name) pairs.

   Empty registry (no aliases in source) returns `{}` — expansion
   becomes a no-op."
  [top-nodes]
  (let [aliases (alias-nodes top-nodes)]
    (reject-duplicate-aliases! aliases)
    (reduce (fn [acc {:keys [name position body]}]
              (assoc-in acc [position name] body))
            {}
            aliases)))

;; ---------------------------------------------------------------------------
;; Expansion pass
;; ---------------------------------------------------------------------------

(declare walk-and-expand)

(defn- expand-type-ref
  "Type-position substitution. If `type-ref` names a registered
   type-alias, return the alias body; otherwise return the node
   unchanged. Recursion is guarded by `path` (the alias-chain we've
   walked through to get here) and capped at `max-expansion-depth`."
  [type-ref registry path]
  (let [nm (:name type-ref)
        body (get-in registry [:type nm])]
    (cond
      (nil? body)
      type-ref

      (contains? path nm)
      (throw (ex-info
              (str "F7 L1: alias expansion cycle detected on `" nm
                   "` — chain: " (clojure.string/join " → "
                                                     (conj (vec path) nm)))
              {:kind :cxc-f7-alias-cycle
               :name nm :chain (conj (vec path) nm)}))

      (>= (count path) max-expansion-depth)
      (throw (ex-info
              (str "F7 L1: alias expansion depth exceeded (" max-expansion-depth
                   ") on `" nm "` — chain: "
                   (clojure.string/join " → " (conj (vec path) nm)))
              {:kind :cxc-f7-alias-depth-exceeded
               :name nm :chain (conj (vec path) nm)}))

      :else
      ;; The body may itself contain :type-ref nodes that need expansion.
      ;; Walk it with the current node's name added to the path so a
      ;; cycle through this alias fires the guard.
      (walk-and-expand body registry (conj path nm)))))

(defn- walk-and-expand
  "Recursively walk `node`, substituting every `:type-ref` that names
   a registered type-alias. `path` tracks the alias chain for
   cycle-detection + depth-cap purposes."
  [node registry path]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (= :type-ref (:node x)))
       (expand-type-ref x registry path)
       x))
   node))

(defn expand
  "Public entry — take parsed `top-nodes` (may include `:alias` decls),
   build the registry, and rewrite the rest of the AST with alias
   references substituted. `:alias` nodes themselves stay in the
   output so downstream diagnostics can point at the source declaration.

   Returns the rewritten node vector."
  [top-nodes]
  (let [registry (build-registry top-nodes)]
    (if (empty? registry)
      top-nodes
      (mapv (fn [node]
              (if (= :alias (:node node))
                node
                (walk-and-expand node registry #{})))
            top-nodes))))
