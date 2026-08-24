(ns cxc.shape-vars
  "F15 L3.α — shape-variable discovery.

   Per spec §1.11 + F15 plan §Landing 3: lowercase-named type refs in
   a signature scope (constructor param types, channel signature,
   `where` clauses, data-field types, `use:` types) are **shape
   variables**. First mention in the scope binds; subsequent mentions
   refer to the same variable. `List of a`, `Map of (k, v)`,
   `Stream of s` are the canonical forms.

   This ns provides the pure walker that discovers shape variables in
   a signature AST. Unification against concrete call-site arguments
   is a downstream landing (L3.β) that consumes this fn's output.

   TitleCase names are concrete types (`Int`, `Point`, `User`); they
   never bind shape variables. The convention comes from §1.11 and is
   enforced by `starts-with-lowercase?`."
  (:require [clojure.walk :as walk]))

(defn- starts-with-lowercase?
  "True iff `nm` starts with an ASCII lowercase letter. Names starting
   with `_` (compiler-reserved), digits, or other chars are not shape
   variables — they either resolve as concrete types elsewhere or
   fail-parse. Empty strings are not shape variables (defensive)."
  [^String nm]
  (boolean (and nm
                (pos? (count nm))
                (let [c (.charAt nm 0)]
                  (and (>= (int c) (int \a))
                       (<= (int c) (int \z)))))))

(defn collect-shape-vars
  "Walk `signature-ast` and return `{shape-var-name → first-binding-node}`
   for every lowercase `:type-ref` name encountered. Returned map is a
   `sorted-map` so consumers (unification, warnings) see deterministic
   iteration order across runs.

   Same-name subsequent mentions collapse to the first-visited node —
   L3.β's unification does the reference-tracking (this ns just
   enumerates the variables). Callers that need to distinguish binding
   sites from references can re-walk with the collected set as a
   filter.

   Recurses into nested `:type-tuple`, `:type-nonnil`, `:type-nullable`,
   `:type-named-tuple`, and `:type-of` shapes via `clojure.walk/postwalk` —
   the walker doesn't need per-node awareness because `:type-ref` nodes
   with a `:name` string are the sole discovery signal.

   TitleCase names (concrete types) are skipped by name-case check.
   Empty maps and non-map inputs pass through cleanly."
  [signature-ast]
  (let [acc (volatile! (transient {}))]
    (walk/postwalk
     (fn [x]
       (when (and (map? x)
                  (= :type-ref (:node x))
                  (starts-with-lowercase? (:name x))
                  (not (contains? @acc (:name x))))
         (vswap! acc assoc! (:name x) x))
       x)
     signature-ast)
    (into (sorted-map) (persistent! @acc))))
