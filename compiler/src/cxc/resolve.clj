(ns cxc.resolve
  "F51 — unified symbol table for top-level declarations.

   Consolidates `record-env`, `atom-env`, `enum-env` (previously three
   ad-hoc maps built independently in `cxc.lower`) into one table
   keyed by declaration name. Each entry carries provenance
   (`:module` — which module the declaration came from) so future
   name-resolution passes can respect module boundaries, and a
   `:members` map with per-kind detail (fields, methods, observables,
   cases, ...) so F38 (uniform dispatch) can look up member
   signatures without walking the raw AST.

   Landing 1 (this file): table is built alongside the existing envs
   in `cxc.lower/lower`. Envs remain the primary read path for now;
   the symbol table is available on ctx as `:symbol-table` for new
   code to query. Regression assertions in `verify-parity!` catch
   drift between the table and the ad-hoc envs so migration lands
   incrementally without silent divergence.

   Landing 2 (later): envs become derived views over the table
   (records-in-table filter → record-env, etc.), retiring
   duplicate construction.

   Landing 3 (later): individual lookup sites in lower.clj migrate
   from `(:record-env ctx)` / `(:atom-env ctx)` calls to
   `(lookup ctx name)` calls.

   Table shape:

     {\"Duration\" {:name    \"Duration\"
                    :module  :std.time
                    :kind    :record
                    :node    <raw AST node>
                    :members {:fields  [{:name :type :default}]
                              :methods [{:name :params :return-type}]}}
      \"Age\"      {:name    \"Age\"
                    :module  :user
                    :kind    :record
                    :node    <raw>
                    :members {:fields [...] :methods [...]}}
      \"Clock\"    {:name    \"Clock\"        ; NB: not the mangled name
                    :module  :std.clock
                    :kind    :def-atom
                    :node    <raw>
                    :members {:data-fields [...] :methods [...]
                              :observables [...] :transitions [...]
                              :use-caps [...] :nested-slots [...]
                              :invariants [...]}}
      \"Key\"      {:name    \"Key\"
                    :module  :std.input
                    :kind    :enum
                    :node    <raw>
                    :members {:cases [\"arrow_up\" \"arrow_down\" ...]}}}

   `:name` is the *user-visible* (pre-mangling) name. When
   `rewrite-prelude-names` postwalk retires (F56), the table becomes
   authoritative for resolving user references without text-level
   rewriting.
   "
  (:require [clojure.set]
            [cxc.prelude :as prelude]))

;; ---------------------------------------------------------------------------
;; Prelude module → declared-names index
;; ---------------------------------------------------------------------------

(defn- prelude-name->module
  "Map every prelude-declared name to its owning module keyword.
   Includes BOTH the user-visible form (`\"Duration\"`) AND the
   post-rename mangled form (`\"_Cxc_Duration\"`) so provenance
   lookup works whether the caller feeds pre- or post-rename AST.
   Landing 1 sees post-rename AST via `cxc.lower/lower`; the
   pre-rename form will matter once F56's postwalk retires and the
   table becomes the authoritative name resolver.

   Built once from `prelude/prelude-modules` — the registry itself
   is the source of truth, so any module added by dropping a new
   resource file into `resources/prelude/` gets picked up
   automatically once its entry lands in the registry."
  []
  (into {}
        (for [{:keys [name names]} prelude/prelude-modules
              nm names
              nm-form [nm (get prelude/rename-table nm nm)]]
          [nm-form (keyword (str "std." (clojure.core/name name)))])))

;; ---------------------------------------------------------------------------
;; Member extraction — flatten each declaration's per-kind detail
;; ---------------------------------------------------------------------------

(defn- record-members
  "Extract fields + observables from a `record` AST node. Field
   entries preserve the raw parser shape so lower's downstream
   `fields-from-data-section` can consume them unchanged.

   F67 L4 — `methods:` is retired; observables carry the callable
   surface. `:methods` in the table shape stays for downstream code
   that queries method-shape entries (populated with derived shape
   from observables); `:observables` exposes the raw observable
   nodes."
  [record-node]
  (let [dsec (first (filter #(= :data-section (:node %)) (:sections record-node)))
        obs  (filterv #(= :observable (:node %)) (:sections record-node))]
    {:fields      (vec (:fields dsec))
     :methods     obs
     :observables obs}))

;; F38 auto-detect — a record's storage variant follows from its shape.
;; No user syntax picks it; the compiler observes whether the record
;; carries any behavior and lowers accordingly.
;;
;;   :struct  — pure data, no methods. Lowers to a C typedef struct
;;              with value copy semantics. F37 equality/hash ship as
;;              free C functions. Cheap; matches C interop naturally.
;;              Spec §5.1 dispatch requirement is vacuous — no methods
;;              to dispatch — so struct storage is fully spec-compliant.
;;
;;   :class   — has one or more methods. Lowers to an ObjC class with
;;              methods as selectors (spec §5.1 dispatch guarantee);
;;              F37 equality/hash ship as `-isEqual:` / `-hash`; ctor
;;              lowers to `[[T alloc] initWith...:]`. Reference storage.
;;              F9 extensions later augment the class's method table.
;;
;; A data-only record that later grows a method silently transitions
;; from :struct to :class. For immutable records the transition is
;; observably transparent — reads return the same value regardless of
;; whether the field storage is shared or copied. FFI/interop that
;; leaks a raw C pointer would notice; no current cxc code path does
;; so, and F54 (backend targeting + generated headers) is deferred.

(defn record-variant
  "Return `:struct` for a data-only record, `:class` for one with
   methods. Trigger is structural — no user syntax picks it. Used
   by F38 lowering to route ctor / dispatch / F37 synthesis."
  [record-node]
  (if (seq (:methods (record-members record-node)))
    :class
    :struct))

(defn- atom-members
  "Extract every kind of body item from a `def-atom` AST node. Keys
   match the ad-hoc filters spread across `def-atom-transitions` /
   `def-atom-observables` / `def-atom-invariants` etc. in `cxc.lower`
   — future landings can route those helpers through this map.

   F67 L4 — `methods:` is retired; observables carry the callable
   reader surface. `:methods` stays for downstream code that queries
   method-shape entries; it mirrors `:observables` here."
  [atom-node]
  (let [body  (:body atom-node)
        dsec  (first (filter #(= :data-section (:node %)) body))
        invs  (first (filter #(= :invariants-section (:node %)) body))
        uses  (first (filter #(= :use-section (:node %)) body))
        obs   (filterv #(= :observable (:node %)) body)]
    {:data-fields   (vec (:fields dsec))
     :methods       obs
     :observables   obs
     :transitions   (filterv #(= :transition (:node %)) body)
     :use-caps      (vec (:caps uses))
     :nested-slots  (filterv #(= :nested-atom (:node %)) body)
     :invariants    (vec (:predicates invs))
     :handler-decls (filterv #(= :handler-decl (:node %)) body)}))

(defn- enum-members [enum-node]
  {:cases (vec (:cases enum-node))})

(defn- extract-members [node]
  (case (:node node)
    :record   (record-members node)
    :def-atom (atom-members node)
    :enum     (enum-members node)))

;; ---------------------------------------------------------------------------
;; Table construction
;; ---------------------------------------------------------------------------

(def ^:private declaration-kinds
  "Top-level AST node kinds that produce a symbol-table entry."
  #{:record :def-atom :enum})

(defn- entry-for [node module]
  (cond-> {:name    (:name node)
           :module  module
           :kind    (:node node)
           :node    node
           :members (extract-members node)}
    ;; F38 auto-detect: only records carry :variant. Atoms are always
    ;; ObjC classes; enums are always ints. Recording it here lets
    ;; downstream lowering key off `(resolve/lookup ...)` alone.
    (= :record (:node node))
    (assoc :variant (record-variant node))))

(defn build-symbol-table
  "Walk `top-nodes` (the combined prelude + user AST as `cxc.lower`
   sees it) and produce the unified symbol table. Names collide?
   Later entries win — matches the historical behavior of
   `build-record-env`/`build-atom-env` (both use `into`, last-write-
   wins). F56's `validate-no-prelude-collisions!` already rejects
   user declarations that would shadow a protected prelude name, so
   collisions here are a compiler bug worth failing loudly on rather
   than an expected case.

   Provenance: a declaration whose name appears in the prelude
   name→module index gets that module tag; everything else is
   attributed to `:user`. When per-file module identification lands
   (F52), the `:user` fallback splits per input file."
  [top-nodes]
  (let [prelude-provenance (prelude-name->module)]
    (reduce
      (fn [table node]
        (if-not (contains? declaration-kinds (:node node))
          table
          (let [module (get prelude-provenance (:name node) :user)]
            (assoc table (:name node) (entry-for node module)))))
      {}
      top-nodes)))

;; ---------------------------------------------------------------------------
;; Parity check — Landing 1 safety net
;; ---------------------------------------------------------------------------

(defn verify-parity!
  "Cross-check the ad-hoc envs against the symbol table. The envs are
   deliberately filtered subsets of the table (prelude tree-shaking
   removes unreferenced capability atoms; `main` promotes to atom-env
   only when the program has other atoms; runtime-provided and
   synthesized container entries never appear in top-nodes at all).
   So the parity invariant is `env-names ⊆ table-names` per kind,
   not equality: every name lower actually reaches for must have
   originated in the top-nodes vector the table was built from.

   Callers strip synthesized/runtime-provided entries from the envs
   before passing them here — those legitimately exist in the envs
   but not in the table by design (they aren't user-authored
   declarations).

   Retires once Landing 2 makes the envs derived views of the table."
  [table {:keys [record-env atom-env enum-env]}]
  (let [table-records (set (map key (filter #(= :record   (:kind (val %))) table)))
        table-atoms   (set (map key (filter #(= :def-atom (:kind (val %))) table)))
        table-enums   (set (map key (filter #(= :enum     (:kind (val %))) table)))
        env-records   (set (keys record-env))
        env-atoms     (set (keys atom-env))
        env-enums     (set (keys enum-env))
        record-orphans (clojure.set/difference env-records table-records)
        atom-orphans   (clojure.set/difference env-atoms   table-atoms)
        enum-orphans   (clojure.set/difference env-enums   table-enums)]
    (when (seq record-orphans)
      (throw (ex-info "F51 parity: record-env contains names not in symbol table"
                      {:orphans record-orphans})))
    (when (seq atom-orphans)
      (throw (ex-info "F51 parity: atom-env contains names not in symbol table"
                      {:orphans atom-orphans})))
    (when (seq enum-orphans)
      (throw (ex-info "F51 parity: enum-env contains names not in symbol table"
                      {:orphans enum-orphans})))
    table))

;; ---------------------------------------------------------------------------
;; Lookup — canonical read path for downstream landings
;; ---------------------------------------------------------------------------

(defn lookup
  "Fetch a symbol-table entry by user-visible name. Nil if absent.
   `ctx` is the lower ctx map (the same one currently carrying
   `record-env`/`atom-env`/`enum-env`). Future F38 dispatch sites
   query through here instead of reaching into the individual envs."
  [ctx name]
  (get-in ctx [:symbol-table name]))

(defn lookup-member
  "Fetch a member entry inside a declaration's :members map.
   `member-kind` is `:methods`/`:fields`/`:observables`/etc. per
   `record-members` / `atom-members` / `enum-members` output.
   Returns nil if the declaration or the named member is missing."
  [ctx decl-name member-kind member-name]
  (some #(when (= member-name (:name %)) %)
        (get-in (lookup ctx decl-name) [:members member-kind])))
