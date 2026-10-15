(ns cxc.lower.tree-shake
  "Prelude tree-shaking helpers.

   `reachable-prelude-atoms` decides which prelude atoms (Clock,
   String helpers, etc.) get kept in atom-env based on whether user
   top-nodes mention their name anywhere. Prelude atoms shipped
   with the compiler but unreferenced by the current program are
   dropped so their fanout selectors don't inflate output.

   `names-mentioned-in` is the shared name-scan primitive — reused
   by any pass that needs to ask 'which names in this set appear in
   this AST subtree?'."
  (:require [clojure.walk :as walk]))

(defn names-mentioned-in
  "Set of :ident-node names and :type-ref-node names anywhere in a
   subtree that fall in the given name-set. Single-pass walk; cheap
   enough to run over full top-nodes for prelude tree-shaking."
  [subtree name-set]
  (let [found (java.util.HashSet.)]
    (walk/postwalk
     (fn [n]
       (when (map? n)
         (let [nm (or (and (contains? #{:ident :type-ref :name} (:node n))
                           (or (:name n) (:sym n)))
                      (:name n))]
           (when (and nm (contains? name-set nm))
             (.add found nm))))
       n)
     subtree)
    (into #{} found)))

(defn reachable-prelude-atoms
  "Which prelude atoms are actually referenced by non-prelude top-nodes
   — computed as a transitive closure over prelude-atom bodies.
   Non-prelude atoms are always kept (users authored them directly).
   Prelude atoms are added only when the user program mentions them
   directly OR when a kept prelude atom's own body mentions them.

   Transitivity matters post-2c-i (2026-09-02): the prelude Console
   was retired in favor of a plugin-shaped Console (`use plugin:
   Plugin`), so a user program mentioning only `Console` transitively
   requires `Plugin`. Without the closure, Plugin gets tree-shaken
   away and Console's `use plugin: Plugin` slot fails to type-resolve.

   Names here are user-visible (F56 L2+L3 retired the rename postwalk;
   `prelude-atom-names` is `prelude/atom-names` in user-visible form)."
  [top-nodes prelude-atom-names prelude-atom-nodes]
  (let [by-name (into {} (map (juxt :name identity) prelude-atom-nodes))]
    (loop [reached (names-mentioned-in top-nodes prelude-atom-names)]
      (let [expanded (reduce (fn [acc nm]
                               (into acc (names-mentioned-in
                                          (get by-name nm)
                                          prelude-atom-names)))
                             reached
                             reached)]
        (if (= expanded reached) reached (recur expanded))))))
