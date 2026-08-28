(ns cxc.lower.module-lets
  "F52 L4 — top-level `let NAME = <const-literal>` module bindings.

   Pre-lower pipeline consumed by `cxc.lower/lower` right after
   `resolve/ensure-references-enriched`:

     1. `extract-module-lets`         — collect `{name → value-node}`
     2. `reject-module-let-shadowing!` — reject local re-binds
     3. `substitute-module-let-refs`  — postwalk-fold every ref to the
                                        literal value (bare + qualified
                                        via `imported-module-idents`)
     4. `module-let-emit-entries`     — hand emit the `static const`
                                        declarations via
                                        `module-let-c-type-and-literal`

   All public so tests (and lower.clj) can reach them by name. No
   dependencies on other lower helpers — only diagnostics and
   clojure.walk."
  (:require [clojure.string]
            [clojure.walk :as walk]
            [cxc.diagnostics :refer [fail-user!]]))

(defn module-let-const-shape?
  "F52 L4 — pre-lower const check for module-let RHS. Restricted subset
   of `default-expr-const-shape?`: primitive literals + unary. Rejects
   ident refs, atom/enum refs, and everything else so the check can
   run before atom-env / enum-env are built."
  [expr]
  (case (:node expr)
    (:lit-int :lit-float :lit-string :lit-char
     :lit-nil :lit-bool)     true
    (:neg :not)              (module-let-const-shape? (:expr expr))
    false))

(defn extract-module-lets
  "F52 L4 — walk `top-nodes` and build `{name → value-node}` over every
   `:module-let` decl. Rejects non-const RHS (via
   `module-let-const-shape?`) and duplicate names (flat-namespace at
   v1.0 — cross-module qualified access is a follow-on)."
  [top-nodes]
  (reduce (fn [acc n]
            (if (not= :module-let (:node n))
              acc
              (let [nm (:name n)
                    v  (:value n)]
                (when-not (module-let-const-shape? v)
                  (fail-user!
                   (str "module let-binding `" nm "` — RHS is not a "
                        "compile-time constant (spec §7.1 + §3.8.5 rule 1). "
                        "Only primitive literals + unary neg/not are accepted "
                        "at this landing; got `" (:node v) "` node.")
                   {:module-let nm :value-node (:node v)}))
                (when (contains? acc nm)
                  (fail-user!
                   (str "duplicate module let-binding `" nm "` — v1.0 uses "
                        "a flat namespace across modules for top-level "
                        "bindings; rename one of the duplicates.")
                   {:module-let nm}))
                (assoc acc nm v))))
          {}
          top-nodes))

(defn imported-module-idents
  "F52 L4-c — collect the set of ident-names the program's `:import`
   nodes bind. Two shapes:
   - `import foo` / `import foo.bar` → the leaf (`foo` or `bar`)
     becomes the local binding — matches how `parse-import` shapes
     `:from` (dotted path); use the last component so `import
     foo.bar` binds `bar` in current scope, matching spec §7.6.
   - `import foo as baz` → `:as` provides the local binding.

   `:from-import` nodes bring individual names directly and don't
   introduce a module ident, so they're skipped here — those names
   are already reachable as bare idents (`substitute-module-let-refs`
   catches them via the module-let map).

   Program-wide flat scan; per-file tracking waits until qualified-
   name resolution needs it."
  [top-nodes]
  (into #{}
        (comp (filter #(= :import (:node %)))
              (map (fn [n]
                     (or (:as n)
                         (last (clojure.string/split (:from n) #"\."))))))
        top-nodes))

(defn substitute-module-let-refs
  "F52 L4 — postwalk `nodes` replacing module-let references with the
   bound value node. Two forms:

   1. Bare `{:node :ident :name X}` where X is a module-let name →
      substitute with the value node (L4-a).
   2. `{:node :dot :target {:node :ident :name X} :field NAME}` where
      NAME is a module-let AND X is an imported module ident (per
      `imported-module-idents`) → substitute with the value node
      (L4-c). Under flat namespace + duplicate rejection, this makes
      `foo.PI` and bare `PI` interchangeable within a file that
      imports `foo` — matching spec §7.6 access via imported module.

   Non-matching nodes pass through. Empty map short-circuits."
  [nodes ml-map imported-modules]
  (if (empty? ml-map)
    nodes
    (walk/postwalk
      (fn [x]
        (cond
          (and (map? x)
               (= :ident (:node x))
               (contains? ml-map (:name x)))
          (get ml-map (:name x))

          (and (map? x)
               (= :dot (:node x))
               (map? (:target x))
               (= :ident (:node (:target x)))
               (contains? imported-modules (:name (:target x)))
               (contains? ml-map (:field x)))
          (get ml-map (:field x))

          :else x))
      nodes)))

(defn module-let-c-type-and-literal
  "F52 L4-d — derive `[c-type c-literal]` for the emit-side `static
   const` declaration of a module-let. Handles primitive literals + a
   single layer of unary neg/not. Returns nil for shapes we don't emit
   as C consts today (`:lit-nil`, `:lit-string` — strings would need
   `static NSString * const` and its own initialization pattern; skip
   for MVP so the emit runs cleanly on numeric + bool + char only).

   Sync any additions here with `module-let-const-shape?` — the accept
   set on the pre-lower validator gates what reaches this fn."
  [value-node]
  (case (:node value-node)
    :lit-int   ["int"    (str (:value value-node))]
    :lit-float ["double" (let [s (str (:value value-node))]
                           (if (re-find #"[.eE]" s) s (str s ".0")))]
    :lit-char  ["char"   (str \' (:value value-node) \')]
    :lit-bool  ["int"    (if (:value value-node) "1" "0")]
    :neg (when-let [[t l] (module-let-c-type-and-literal (:expr value-node))]
           [t (str "-(" l ")")])
    :not (when-let [[_ l] (module-let-c-type-and-literal (:expr value-node))]
           ["int" (str "!(" l ")")])
    nil))

(defn module-let-emit-entries
  "F52 L4-d — normalize the raw `{name → value-node}` map into a vec of
   `{:name :c-type :c-literal}` entries suitable for `emit/emit`'s
   `:module-lets` section. Ordering is unspecified (map keys); emit
   sorts by name for stable output."
  [ml-map]
  (into []
        (keep (fn [[nm v]]
                (when-let [[c-ty c-lit] (module-let-c-type-and-literal v)]
                  {:name nm :c-type c-ty :c-literal c-lit})))
        ml-map))

(defn reject-module-let-shadowing!
  "F52 L4-b — reject any local binding that would shadow a top-level
   module-let. Walks the tree looking for binding-introducing nodes
   whose bound name matches a module-let. Fires BEFORE
   `substitute-module-let-refs` runs so the diagnostic surfaces
   cleanly (unshadowed, naive substitution would rewrite the local
   binding's own name into the constant value, producing something
   like `let 5 = 5` — actionable but confusing).

   Covered binding kinds:
   - `:let-stmt` local `let x = expr` (F18 L1).
   - `:if-let` `:bindings` list (F39 L7 unwrap-statement).
   - `:function` / `:class-function` `:params` list (spec §3.7 fn
     params). Params are a distinct scoping mechanism from `let`, but
     they carry the same collision risk: a param named `PI` inside a
     method body would refer to the param, and any `PI` reference in
     that body would substitute to the module-let value under naive
     rewriting."
  [nodes ml-map]
  (when (seq ml-map)
    (walk/postwalk
      (fn [x]
        (cond
          (and (map? x) (= :let-stmt (:node x))
               (contains? ml-map (:name x)))
          (fail-user!
           (str "local `let " (:name x) "` shadows the module-let of the "
                "same name (F52 L4). v1.0 rejects local shadowing of top-"
                "level module-lets — rename one of the two.")
           {:module-let (:name x) :node :let-stmt})

          (and (map? x) (= :if-let (:node x)))
          (do (doseq [b (:bindings x)
                      :when (contains? ml-map (:name b))]
                (fail-user!
                 (str "`if let " (:name b) "` binding shadows the module-"
                      "let of the same name (F52 L4). Rename one of the "
                      "two.")
                 {:module-let (:name b) :node :if-let}))
              x)

          (and (map? x)
               (contains? #{:function :class-function} (:node x)))
          (do (doseq [p (:params x)
                      :when (contains? ml-map (:name p))]
                (fail-user!
                 (str "function `" (:name x) "` parameter `" (:name p)
                      "` shadows the module-let of the same name (F52 L4). "
                      "Rename one of the two.")
                 {:module-let (:name p)
                  :node       (:node x)
                  :fn-name    (:name x)}))
              x)

          :else x))
      nodes))
  nodes)
