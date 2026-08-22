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

   Landed sub-scope:
   - L1-a (2026-08-23) — type-position aliases, bare identifier body.
   - L1-c (2026-08-23) — parametric aliases via `of` qualifier
     (`alias StringDict of V: Map of (String, V)`; spec §26 retired
     `<T>` at v1.0).

   Follow-on within L1:
   - L1-b — section-header position hook (blocked on F8 primitive vocab).
   - L1-d — delimited-pattern position (needs F42).

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
   position-keyed registry
   `{position → {name → {:params [...] :body body-AST}}}`. Rejects
   duplicate (position, name) pairs.

   Empty registry (no aliases in source) returns `{}` — expansion
   becomes a no-op."
  [top-nodes]
  (let [aliases (alias-nodes top-nodes)]
    (reject-duplicate-aliases! aliases)
    (reduce (fn [acc {:keys [name position params body]}]
              (assoc-in acc [position name]
                        {:params (or params []) :body body}))
            {}
            aliases)))

;; ---------------------------------------------------------------------------
;; Expansion pass
;; ---------------------------------------------------------------------------

(declare walk-and-expand)

(defn- substitute-params
  "Walk `body`, replacing every `:type-ref` whose `:name` matches a
   parameter with the corresponding argument AST. Non-parameter
   `:type-ref`s pass through untouched — later alias-lookup passes handle
   them via the registry."
  [body param->arg]
  (if (empty? param->arg)
    body
    (walk/postwalk
     (fn [x]
       (if (and (map? x) (= :type-ref (:node x)))
         (get param->arg (:name x) x)
         x))
     body)))

(defn- args-vector
  "F7 L1-c — normalize a `:type-of :inner` slot to a flat vector of
   argument type-expr ASTs. `Foo of Bar` puts a single node in :inner;
   `Foo of (A, B)` puts a `:type-tuple`. Returns arg vector."
  [inner]
  (if (and (map? inner) (= :type-tuple (:node inner)))
    (:types inner)
    [inner]))

(defn- expand-parametric
  "L1-c — parametric application `<name> of <arg(s)>`. Looks up the
   alias, checks arity, binds params→args, substitutes inside body,
   then continues with normal recursive expansion so chained aliases
   still resolve. Returns the expanded AST fragment."
  [type-of registry path]
  (let [nm     (:outer type-of)
        entry  (get-in registry [:type nm])]
    (cond
      (nil? entry)
      ;; Not an alias — leave `:type-of` untouched but still recurse into
      ;; :inner so any nested aliases expand.
      (update type-of :inner walk-and-expand registry path)

      (empty? (:params entry))
      (throw (ex-info
              (str "F7 L1: alias `" nm "` is not parametric — "
                   "referenced as `" nm " of ...` but declared with no "
                   "parameter list. Drop the `of` clause or add "
                   "parameters to the alias declaration.")
              {:kind :cxc-f7-non-parametric-alias-with-args :name nm}))

      :else
      (let [args (args-vector (:inner type-of))]
        (when-not (= (count (:params entry)) (count args))
          (throw (ex-info
                  (str "F7 L1: alias `" nm "` arity mismatch — "
                       "declared with " (count (:params entry))
                       " parameter(s) " (pr-str (:params entry))
                       " but applied to " (count args) " argument(s).")
                  {:kind :cxc-f7-alias-arity-mismatch
                   :name nm
                   :expected (count (:params entry))
                   :actual (count args)})))
        (when (contains? path nm)
          (throw (ex-info
                  (str "F7 L1: alias expansion cycle detected on `" nm
                       "` — chain: "
                       (clojure.string/join " → " (conj (vec path) nm)))
                  {:kind :cxc-f7-alias-cycle
                   :name nm :chain (conj (vec path) nm)})))
        (when (>= (count path) max-expansion-depth)
          (throw (ex-info
                  (str "F7 L1: alias expansion depth exceeded ("
                       max-expansion-depth ") on `" nm "` — chain: "
                       (clojure.string/join " → " (conj (vec path) nm)))
                  {:kind :cxc-f7-alias-depth-exceeded
                   :name nm :chain (conj (vec path) nm)})))
        (let [;; Expand any nested aliases inside the argument types
              ;; BEFORE substituting them into the body. Otherwise a
              ;; chain like `alias Wrap of T: Box of T` used as
              ;; `Wrap of Alias` would leave Alias unexpanded inside Box.
              args         (mapv #(walk-and-expand % registry path) args)
              param->arg   (zipmap (:params entry) args)
              substituted  (substitute-params (:body entry) param->arg)]
          (walk-and-expand substituted registry (conj path nm)))))))

(defn- expand-type-ref
  "Type-position substitution for a non-parametric alias reference. If
   `type-ref` names a registered zero-arity type-alias, return the alias
   body; otherwise return the node unchanged. Recursion is guarded by
   `path` (the alias-chain) and capped at `max-expansion-depth`."
  [type-ref registry path]
  (let [nm    (:name type-ref)
        entry (get-in registry [:type nm])]
    (cond
      (nil? entry)
      type-ref

      (seq (:params entry))
      (throw (ex-info
              (str "F7 L1: alias `" nm "` is parametric — "
                   "declared with " (count (:params entry))
                   " parameter(s) " (pr-str (:params entry))
                   " but referenced as bare `" nm "` with no arguments. "
                   "Apply with `of` (e.g. `" nm " of ...`).")
              {:kind :cxc-f7-parametric-alias-without-args :name nm}))

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
      (walk-and-expand (:body entry) registry (conj path nm)))))

(defn- walk-and-expand
  "Recursively walk `node`, substituting alias references with alias
   bodies. Handles two forms:
   - `:type-ref` — bare-name references (zero-arity aliases).
   - `:type-of` — parametric applications `Name of args` (L1-c).

   Uses a manual pre-order walk so parametric applications can control
   how their `:inner` sub-tree is processed (arg expansion has to
   precede substitution to avoid missing nested aliases inside args).
   `path` tracks the alias chain for cycle-detection + depth-cap."
  [node registry path]
  (cond
    (map? node)
    (let [kind (:node node)]
      (cond
        (= kind :type-of)   (expand-parametric node registry path)
        (= kind :type-ref)  (expand-type-ref node registry path)
        :else
        (reduce-kv (fn [m k v] (assoc m k (walk-and-expand v registry path)))
                   node
                   node)))

    (vector? node)
    (mapv #(walk-and-expand % registry path) node)

    (seq? node)
    (map #(walk-and-expand % registry path) node)

    :else node))

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
