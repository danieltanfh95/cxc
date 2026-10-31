(ns cxc.lower.program-scan
  "Program-wide AST scans used by the top-level `lower` orchestrator to
   decide runtime infrastructure. Each fn walks `top-nodes` and returns
   a summary — presence of async sends, presence of panic, the set of
   cross-atom sync-send edges — without touching envs or type
   inference."
  (:require [clojure.string :as str]
            [cxc.type-kw :refer [base-type-name]]
            [cxc.util.walk :refer [tree-nodes]]))

(defn program-has-async-sends?
  "Walk the parsed AST for any `:emit` node with `:async? true` — a
   `:>` targeted send OR an untargeted stream emission. Programs
   without async sends don't need dispatch_main; their work completes
   synchronously during construction and main() returns normally."
  [top-nodes]
  (boolean
   (some #(and (map? %) (= :emit (:node %)) (:async? %))
         (tree-nodes top-nodes))))

(defn resolve-target-atom-name
  "F31/F35 L3 — best-effort resolution of a `|>` target expression to
   the target's atom class name. Uses only the enclosing atom's
   declared shape (data fields, ctor params, use: caps, nested slots)
   — no full type inference, keeps the cycle-detection pass
   independent of the type-check pipeline. Returns nil when the
   target isn't a simple ident or its type doesn't resolve to a
   known atom."
  [target-node atom-node atom-names]
  (case (:node target-node)
    :name
    (when (= "self" (:sym target-node)) (:name atom-node))
    :ident
    (let [nm (:name target-node)
          section     (fn [kind] (first (filter #(= kind (:node %))
                                                (:body atom-node))))
          data-fields (get (section :data-section) :fields [])
          use-caps    (get (section :use-section)  :caps   [])
          ctor-params (:ctor-params atom-node)
          find-in    (fn [items key-fn]
                       (some (fn [it]
                               (when (= nm (key-fn it))
                                 (base-type-name (:type it))))
                             items))
          resolved   (or (find-in ctor-params :name)
                         (find-in data-fields :name)
                         (find-in use-caps :name))]
      (when (contains? atom-names resolved) resolved))
    nil))

(defn sync-send-edges
  "F31/F35 L3 — extract `|>` cross-atom edges from every atom in the
   program. Returns a set of `#{[source-atom target-atom target-msg]}`
   triples. Self-sends (source = target) are excluded — they're a
   pattern users deliberately reach for (self-drive in on-enter) and
   can only deadlock via infinite recursion on Layer 4, which is a
   distinct failure mode."
  [top-nodes]
  (let [atom-nodes (filter #(and (= :def-atom (:node %))
                                 (not= "main" (:name %)))
                           top-nodes)
        atom-names (into #{} (map :name atom-nodes))]
    (into #{}
          (mapcat
           (fn [a]
             (keep (fn [n]
                     (when (and (map? n) (= :emit (:node n))
                                (not (:async? n))
                                (some? (:target n)))
                       (when-let [tgt (resolve-target-atom-name
                                       (:target n) a atom-names)]
                         (when-not (= tgt (:name a))
                           [(:name a) tgt (:msg n)]))))
                   (tree-nodes (:body a)))))
          atom-nodes)))

(defn warn-sync-send-cycles!
  "F31/F35 L3 — DFS over the |> edge set collected by `sync-send-edges`.
   For each strongly-connected group of size ≥ 2, log a warning
   naming the atoms in the cycle. Warnings go to *err* so they surface
   in build output without failing the compile — spec §1.11 line 1523
   makes cycle-avoidance the user's responsibility; a --strict flag can
   promote to error in a future landing."
  [edges]
  (let [adj (reduce (fn [m [s t _msg]] (update m s (fnil conj #{}) t))
                    {} edges)
        ;; Threaded seen set keeps the DFS value-return pure: `dfs`
        ;; returns `[cycles seen']` and the outer reduce folds seen
        ;; across start nodes.
        dfs (fn dfs [node stack on-stack seen]
              (cond
                (contains? on-stack node)
                [#{(conj (vec (drop-while #(not= % node) stack)) node)}
                 seen]
                (contains? seen node)
                [#{} seen]
                :else
                (reduce (fn [[acc seen] next-node]
                          (let [[cs seen'] (dfs next-node
                                                (conj stack node)
                                                (conj on-stack node)
                                                seen)]
                            [(into acc cs) seen']))
                        [#{} (conj seen node)]
                        (get adj node #{}))))
        cycles (first (reduce (fn [[acc seen] n]
                                (let [[cs seen'] (dfs n [] #{} seen)]
                                  [(into acc cs) seen']))
                              [#{} #{}]
                              (keys adj)))]
    (doseq [c cycles]
      (binding [*out* *err*]
        (println (str "warning: `|>` cycle across atoms "
                      (str/join " → " (map #(str "`" % "`") c))
                      " — spec §1.11 line 1523 makes cycle-avoidance "
                      "the caller's responsibility. Under Layer 4 (single "
                      "main queue) this recurses until stack overflow; "
                      "under future F32 concurrent queues it deadlocks. "
                      "Break the cycle by routing one edge through `:>` "
                      "(async) or by refactoring the callback path."))))
    cycles))

(defn program-uses-panic?
  "F31/F35 L2 — walk the parsed AST for any `:call` node whose callee is
   `panic`. Programs that use panic need <execinfo.h> and <stdlib.h> for
   the inlined backtrace + free()."
  [top-nodes]
  (boolean
   (some #(and (map? %)
               (= :call (:node %))
               (= :ident (get-in % [:callee :node]))
               (= "panic" (get-in % [:callee :name])))
         (tree-nodes top-nodes))))
