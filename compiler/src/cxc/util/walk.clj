(ns cxc.util.walk
  "Shared AST-walk helpers. `tree-nodes` yields a lazy pre-order DFS
   over an arbitrary nested map / vector tree, suitable for
   `filter` / `some` / `into` pipelines. Supersedes six
   postwalk-with-mutable-accumulator sites across cxc.lower and
   gives existence checks (`some`) short-circuiting that
   `clojure.walk/postwalk` cannot.")

(set! *warn-on-reflection* true)

(defn tree-nodes
  "Lazy pre-order DFS over any nested map / vector tree. Yields the
   root first, then descends into map values and sequential elements.
   Pair with `(filter map?)` plus a `:node`-predicate for the common
   AST-scan shape."
  [root]
  (tree-seq (some-fn map? sequential?)
            (fn [x] (if (map? x) (vals x) (seq x)))
            root))
