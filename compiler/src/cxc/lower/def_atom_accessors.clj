(ns cxc.lower.def-atom-accessors
  "Pure AST accessors over parsed `:def-atom` nodes. Each fn reads
   `(:body atom-node)` (or nested slots on it) and filters/projects to
   the shape downstream lowering wants. No env access, no type
   inference — these run before env-build.

   Includes the two F67 L3 handler-shape helpers
   (`transition-is-handler-shape?`, `transition->handler-decl`) because
   they're the transition-side twin of `def-atom-handler-decls`: the
   former recognizes handler-collapse transitions, the latter converts
   them to synthetic `:handler-decl` nodes that get folded in with the
   real ones.")

(defn def-atom-data-section [atom-node]
  (first (filter #(= :data-section (:node %)) (:body atom-node))))

(defn def-atom-top-level-fields
  "F67 L5 — atom-body top-level `:field` nodes (replaces `data:` section
   per Proposal D). Returns them wrapped in a synthetic data-section so
   downstream `fields-from-data-section` consumes both surfaces
   uniformly during the grace window."
  [atom-node]
  (let [top-fields (filterv #(= :field (:node %)) (:body atom-node))]
    (when (seq top-fields)
      {:node :data-section :fields top-fields})))

(defn def-atom-effective-data-section
  "Merge legacy `data:`-section fields with F67 L5 top-level fields.
   Both surfaces coexist during the migration window; either produces
   the same downstream shape."
  [atom-node]
  (let [legacy (def-atom-data-section atom-node)
        top    (def-atom-top-level-fields atom-node)]
    (cond
      (and legacy top) {:node :data-section
                        :fields (into (or (:fields legacy) [])
                                      (:fields top))}
      legacy           legacy
      top              top
      :else            nil)))

(defn def-atom-transitions [atom-node]
  (filterv #(= :transition (:node %)) (:body atom-node)))

(defn def-atom-observables [atom-node]
  (filterv #(= :observable (:node %)) (:body atom-node)))

(defn def-atom-invariants
  "Return normalized def-body proposition records (empty if absent)."
  [atom-node]
  (filterv #(= :proposition (:node %)) (:body atom-node)))

(defn def-atom-use-caps
  "Return the capability list from the atom's use: section (empty if
   absent). Each cap is {:name :type}."
  [atom-node]
  (or (:caps (first (filter #(= :use-section (:node %)) (:body atom-node))))
      []))

(defn def-atom-on-nil-entries
  "F39 L8 (spec §4.10.9) — return the on_nil entry list from the atom's
   `on_nil:` section (empty if absent). Each entry is `{:method :value}`
   where `:value` is a parser-produced expression subject to const-check
   at env-build time. Section is unique per atom; extras beyond the first
   are ignored (matches invariants/use-section discipline)."
  [atom-node]
  (or (:entries (first (filter #(= :on-nil-section (:node %))
                               (:body atom-node))))
      []))

(defn def-atom-handler-decls
  "F14 — `-> name(args):` handler declarations at atom-body top level.

   F67 L3 note: the legacy `-> name(args):` sugar is retired at parse
   time (see parse.clj/parse-handler-decl); this helper still exists
   because F67 L3 also folds handler-shape transitions
   (`* <: name(args) -> *`) into the same downstream method-entry
   pipeline via `transition->handler-decl`."
  [atom-node]
  (filterv #(= :handler-decl (:node %)) (:body atom-node)))

(defn transition-is-handler-shape?
  "F67 L3 — recognize the handler-collapse form:
     `* <: name(a: T, b: U) -> * then: <body>`
   i.e. wildcard source, wildcard target, typed-params receive.

   These lower to handler-shaped method entries (spec §1265 semantics),
   subsuming the retired `-> name(args):` sugar."
  [txn]
  (and (= :wildcard (get-in txn [:source :kind]))
       (= "self"    (:target txn))
       (= "<:"      (get-in txn [:receive :glyph]))
       (some?       (get-in txn [:receive :msg :params]))))

(defn transition->handler-decl
  "F67 L3 — convert a handler-shape transition into a synthetic
   :handler-decl node, so downstream lowering (name uniqueness checks
   in lower-def-atom, `handler-decl->method-entry`) can consume it
   with no branch.

   Body shape: handler-decls historically stored `:body` as a
   `parse-block` result `{:node :block :stmts [...]}` (downstream
   lowering pulls `(:stmts (:body method))`). Wrap the stmts the
   same way so the same accessor works uniformly.

   F70 I3 — collect stmts from BOTH `:do-clause` bodies (legacy)
   and `:then-clause` `:stmts` (I3 unified). Field updates
   (`:updates`) on a handler-shape transition remain a design
   oddity but are ignored here — handlers have no phase state to
   commit."
  [txn]
  (let [body-clauses (:body txn)
        stmts        (vec (mapcat (fn [c]
                                    (case (:node c)
                                      :do-clause   (:stmts c)
                                      :then-clause (:stmts c)
                                      nil))
                                  body-clauses))]
    {:node   :handler-decl
     :name   (get-in txn [:receive :msg :source :name])
     :params (get-in txn [:receive :msg :params])
     :body   {:node :block :stmts stmts}
     :cxc-line (:cxc-line txn)
     :cxc-col  (:cxc-col txn)}))

(defn def-atom-body-extras
  "Everything in the def-atom body that isn't a section lower knows
   how to consume — e.g. contracts: (Layer 6+). Handler-decls are
   consumed separately by F14. F67 L5 top-level `:field` nodes are
   consumed by `def-atom-effective-data-section`. F37 L6b
   `:class-function` is filtered separately by `def-atom-class-
   functions` (fail-nyi'd with an F37 L6 pointer until synthesis
   lands). (Prior K3 deprecation walker + `@deprecated` annotation
   retired 2026-08-30 with the annotation-grammar-retirement wave.)"
  [atom-node]
  (remove #(#{:data-section :methods-section :transition :observable
              :proposition :use-section
              :on-nil-section
              :handler-decl :deprecated :field
              :class-function} (:node %))
          (:body atom-node)))

(defn def-atom-class-functions
  "F37 L6b (2026-08-24) — collect `:class-function` nodes from the
   def-atom body. Filtered out of `def-atom-body-extras` so the
   generic extras fail-nyi doesn't mis-diagnose them; a dedicated
   fail-nyi fires at lower-time pointing at F37 L6 (from(Bytes)
   synthesis) as the landing that will consume them."
  [atom-node]
  (filterv #(= :class-function (:node %)) (:body atom-node)))
