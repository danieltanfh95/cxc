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
                              :use-caps [...] :invariants [...]}}
      \"Key\"      {:name    \"Key\"
                    :module  :std.input
                    :kind    :enum
                    :node    <raw>
                    :members {:cases [\"arrow_up\" \"arrow_down\" ...]}}}

   `:name` is the *user-visible* (pre-mangling) name. F56 L2+L3
   (2026-08-27) retired the parse-time `rewrite-prelude-names`
   postwalk — this table is now authoritative for resolving user
   references; mangling happens at C-id output boundary in
   `cxc.lower/->c-safe`.
   "
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cxc.prelude :as prelude]))

;; ---------------------------------------------------------------------------
;; Prelude module → declared-names index
;; ---------------------------------------------------------------------------

(defn prelude-name->module
  "Map every prelude-declared name to its owning module keyword.
   F56 L2+L3 (2026-08-27): postwalk retired — AST carries only
   user-visible prelude names, so this table lists only that form.

   Built once from `prelude/prelude-modules` — the registry itself
   is the source of truth. Public so `cxc.lower/attribute-modules-
   to-decls` can consume it directly rather than reaching through
   `@#'`."
  []
  (into {}
        (for [{:keys [name names]} prelude/prelude-modules
              nm names]
          [nm (keyword (str "std." (clojure.core/name name)))])))

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

(defn- record-variant
  "Return `:struct` for a data-only record, `:class` for one with
   methods. Trigger is structural — no user syntax picks it. Used
   by F38 lowering to route ctor / dispatch / F37 synthesis. Kept
   private — callers should read the `:variant` slot off the
   symbol-table entry rather than recomputing."
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
        uses  (first (filter #(= :use-section (:node %)) body))
        obs   (filterv #(= :observable (:node %)) body)]
    {:data-fields   (vec (:fields dsec))
     :methods       obs
     :observables   obs
     :transitions   (filterv #(= :transition (:node %)) body)
     :use-caps      (vec (:caps uses))
     :invariants    (filterv #(= :proposition (:node %)) body)
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

(defn- metadata-annotations
  "Return the parsed `@metadata(...)` annotations attached to a def.
   Keep the normalized annotation AST intact here; F53 owns conversion
   from expression nodes to the consumer-facing JSON value shape."
  [node]
  (into []
        (filter #(= "metadata" (:name %)))
        (:annotations node)))

(defn- entry-for [node module]
  (cond-> {:name    (:name node)
           :module  module
           :kind    (:node node)
           :node    node
           ;; L3c NEW-INPUT — make annotation data a first-class symbol
           ;; entry field instead of requiring downstream users to reach
           ;; back through the full declaration AST.
           :annotations          (vec (:annotations node))
           :metadata-annotations (metadata-annotations node)
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
   name→module index gets that module tag; otherwise the current
   `:module` marker (last seen in the walk) drives the attribution.
   Pre-any-marker declarations fall back to `:user` (single-file /
   pre-F51 L1 convention); the multi-file build's per-file `:module`
   markers switch this to the file's derived module."
  [top-nodes]
  (let [prelude-provenance (prelude-name->module)]
    (first
     (reduce
      (fn [[table current-module] node]
        (cond
          ;; F51 L1 — a `module <name>` node updates the current
          ;; provenance for all subsequent declarations until the
          ;; next `:module` node. Prelude declarations still get
          ;; their std.* attribution from `prelude-provenance` (the
          ;; prelude synthesizes no `:module` node when assembled).
          (= :module (:node node))
          [table (keyword (:name node))]

          (contains? declaration-kinds (:node node))
          (let [module (or (get prelude-provenance (:name node))
                           current-module
                           :user)]
            [(assoc table (:name node) (entry-for node module)) current-module])

          :else
          [table current-module]))
      [{} nil]
      top-nodes))))

;; ---------------------------------------------------------------------------
;; F56 L1 — resolver-produced mangled names.
;;
;; Pure fn: given a symbol-table entry (or plain user-visible name +
;; explicit module), return the C-safe mangled form the emit layer
;; should use for identifiers derived from this name.
;;
;; Scheme (per F56 plan §5):
;; - `:prelude` (module in `:std.*`) → `_Cxc_<Name>` (backward-
;;   compatible with existing FFI hatches like `_Cxc_Duration`).
;; - `:user` and everything else → `<module>_<name>` with dots →
;;   underscores (e.g. `user.myapp` module + `Foo` name → `user_myapp_Foo`).
;; - The `:main` module (single-file / root of src-dir per F51 L1)
;;   uses the unqualified name so existing programs don't churn on
;;   symbol names in the emitted .m.
;;
;; L1 introduced the fn; L2+L3 (2026-08-27) migrated emit consumers
;; to call it via `cxc.lower/->c-safe` and deleted the parse-time
;; rename postwalk + rename-table. `validate-no-prelude-collisions!`
;; remains as a source-level guard (spec §4.20).
;; ---------------------------------------------------------------------------

(defn mangled-name-for
  "F56 L1 — return the C-safe mangled identifier for a user-visible
   `name` declared in `module`. Module conventions:
   - `:std.*` → `_Cxc_<name>` (prelude backward-compat).
   - `:main` → `name` unchanged (single-file / root; user-authored
     root-level decls emit under their user-visible name).
   - anything else → `<module-with-dots-as-underscores>_<name>`.

   Pure fn; no reads from the symbol table. Callers who have an entry
   should use `mangled-name` (below) which extracts the module for
   them."
  [module name]
  (let [module-str (some-> module clojure.core/name)]
    (cond
      (nil? module-str)
      name

      (str/starts-with? module-str "std.")
      (str "_Cxc_" name)

      (= module-str "main")
      name

      :else
      (str (str/replace module-str "." "_") "_" name))))

(defn mangled-name
  "F56 L1 — convenience wrapper: look up `user-name` in `table` and
   apply `mangled-name-for`. Returns the input name verbatim when the
   table has no entry (compiler-synthesized names that aren't user
   declarations). Production emit sites go through
   `cxc.lower/->c-safe`, which consults an ambient `*decl->module*`
   map instead of a table arg; this fn stays for convenience and
   external tooling that already holds a symbol-table."
  [table user-name]
  (if-let [entry (get table user-name)]
    (mangled-name-for (:module entry) user-name)
    user-name))

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

;; ---------------------------------------------------------------------------
;; F51 L3b sub-landing (a) — per-module scope index + unscoped type-ref
;; discovery.
;;
;; Under L3b's strict-scoping rules a name resolves via:
;;   1. Declarations in the same module.
;;   2. Names imported into the module via `from X import a` / `import X`.
;;   3. Prelude names (always visible; no import required — spec §7.6
;;      resolution precedence).
;; A reference outside all three would fail under strict L3b.
;;
;; This sub-landing (a) ships the scope index + a walker that surfaces
;; unscoped references. Consumed as a WARN diagnostic in
;; `build/compile-directory-to-file` so users can migrate proactively
;; before b.ii-5e retires the short-name fallback path in
;; `lower/decl-lookup`. b.ii-5a-c wired lower's env lookups to consume
;; `:resolves-to` (see `resolve-references` below); b.ii-5e retires the
;; remaining short-name fallback.
;; ---------------------------------------------------------------------------

(def ^:private builtin-type-names
  "Type names built into the language grammar — always in scope, no
   import required. Includes numeric widths (spec §3.1), non-numeric
   substrate (§3.1.3 Any/Never/Unit), reference-shaped primitives
   (String/Bytes/Block), and the parametric containers (List/Map/Set/
   Array/Optional). Mirrors the primitive maps in `lower/cxc-type->c`
   and `parse/known-primitive-value-type-names`; if either grows a new
   type here should follow."
  #{"Int" "Int8" "Int16" "Int32" "Int64"
    "UInt" "UInt8" "UInt16" "UInt32" "UInt64"
    "Float" "Double" "Bool" "Char" "Byte"
    "String" "Bytes" "Block"
    "Any" "Unit" "Never"
    "List" "Map" "Set" "Array" "Optional"})

(def ^:private prelude-name-set
  "Every user-visible name declared in the prelude plus every builtin
   type name — the fixed \"always in scope\" set that every module
   sees regardless of imports. Assembled from `prelude/prelude-modules`
   (source of truth for prelude declarations) plus `builtin-type-names`
   (source of truth for language primitives)."
  (clojure.set/union
    builtin-type-names
    (into #{} (mapcat :names) prelude/prelude-modules)))

(defn- decls-in-file
  "Names of top-level user declarations (`:record` / `:def-atom` /
   `:enum`) in a parsed file's node vector."
  [nodes]
  (into #{}
        (comp (filter #(contains? #{:record :def-atom :enum} (:node %)))
              (map :name))
        nodes))

(defn- imports-in-file
  "Local-binding names introduced by a parsed file's import statements.
   `from X import a` binds `a`; `from X import a as b` binds `b`.
   `import X` binds `X`; `import X as Y` binds `Y`. Mirrors
   `build/import-local-bindings` shape; kept here as a private helper
   so `resolve` doesn't depend on `build`."
  [nodes]
  (into #{}
        (mapcat (fn [n]
                  (case (:node n)
                    :from-import (map #(or (:as %) (:name %)) (:names n))
                    :import      [(or (:as n) (:from n))]
                    nil)))
        nodes))

(defn build-module-scope-index
  "Given `files-with-nodes` (each entry `{:module :nodes ...}` — the
   shape `build/compile-directory-to-file` produces after parse + module
   header injection), return `{module-kw → #{name-string ...}}` — the
   set of names each user module resolves under L3b's strict scoping.

   In-scope = own module's decls (aggregated across every file in the
   module) + names imported into the module + prelude names.

   Sub-landing (a) callers use this to detect references that would
   break under L3b; sub-landing (b) will consume it during the resolver
   rewrite."
  [files-with-nodes]
  (reduce (fn [acc {:keys [module nodes]}]
            (update acc module
                    (fnil clojure.set/union prelude-name-set)
                    (clojure.set/union (decls-in-file nodes)
                                       (imports-in-file nodes))))
          {}
          files-with-nodes))

(defn- capitalized?
  "True for strings starting with an uppercase ASCII letter — the
   convention for user-defined types (records / atoms / enums) and
   type-shaped names in cxc. Used to filter bare :ident references
   at ctor-call positions so we surface cross-module ctor uses
   (`Foo(x: 1)`) without noise from lowercase locals / params /
   method names."
  [s]
  (and (string? s)
       (pos? (count s))
       (let [c (.charAt ^String s 0)]
         (and (>= (int c) (int \A)) (<= (int c) (int \Z))))))

(defn- module-scoped-ref-names
  "Walk `nodes` and return the distinct set of names that participate
   in module-scope resolution:
   - Every `{:node :type-ref :name X}` — field types, method params,
     return types, handler contracts.
   - Every capitalized bare `:ident` at a `:call :callee` position —
     the ctor-call surface (`Foo(x: 1)` where `Foo` names a record
     / atom / enum). Lowercase :idents in the same position are
     lowercase functions (spec convention) and stay out of scope
     for L3b (a) — F52 module-level `let` bindings handle those
     when it lands.
   - Every `:extend-class :target X` — F9 extension targets
     (`extend Foo:`). Extension of a cross-module type without an
     explicit import is a module-scope concern.
   Bare :ident occurrences at other positions (locals, method
   params, receiver of `.field`) are NOT included: L3b's resolution
   there is scope-local, not module-scope."
  [nodes]
  (let [acc (volatile! (transient #{}))]
    (walk/postwalk
      (fn [x]
        (cond
          (not (map? x)) nil
          (and (= :type-ref (:node x)) (string? (:name x)))
          (vswap! acc conj! (:name x))
          ;; `Foo(x: 1)` — callee is a bare :ident with a capitalized
          ;; name. Only fire at :call :callee position; other :ident
          ;; occurrences (e.g. bare-`Foo` as a value) are rare and
          ;; safe to leave for a follow-on landing.
          (and (= :call (:node x))
               (let [c (:callee x)]
                 (and (map? c)
                      (= :ident (:node c))
                      (capitalized? (:name c)))))
          (vswap! acc conj! (:name (:callee x)))
          ;; `SomeSource.msg` — receive-glyph source, `Foo.bar` static
          ;; access, or any dot expr whose target is a capitalized
          ;; bare :ident. Under spec convention, capitalized bare
          ;; :ident at a :dot :target position refers to a top-level
          ;; type (record / atom / enum). Lowercase :dot targets
          ;; (`x.field`, `foo.bar`) are locals or function results
          ;; and stay out of scope.
          (and (= :dot (:node x))
               (let [t (:target x)]
                 (and (map? t)
                      (= :ident (:node t))
                      (capitalized? (:name t)))))
          (vswap! acc conj! (:name (:target x)))
          ;; `extend Foo:` — target is a plain string, not a :type-ref.
          ;; Extension of a cross-module type without an import surfaces
          ;; here.
          (and (= :extend-class (:node x)) (string? (:target x)))
          (vswap! acc conj! (:target x))
          ;; `MyContainer of Int` — parametric type reference with an
          ;; :outer name held as a plain string (not a :type-ref).
          ;; Builtin containers (`List`, `Map`, `Set`, `Array`,
          ;; `Optional`) live in `builtin-type-names` and pass; a
          ;; capitalized user-declared parametric type at :outer
          ;; position (`MyContainer of Int`) surfaces here.
          (and (= :type-of (:node x))
               (string? (:outer x))
               (capitalized? (:outer x)))
          (vswap! acc conj! (:outer x)))
        x)
      nodes)
    (persistent! @acc)))

(defn find-unscoped-type-refs
  "Return the sequence of `{:module :file :name :declared-in}` entries
   for every module-scope reference in `files-with-nodes` whose name
   does NOT resolve in its module's scope (per `build-module-scope-index`).

   Coverage: every `:type-ref :name X`, plus every capitalized bare
   `:ident` at a `:call :callee` position (ctor calls). See
   `module-scoped-ref-names` for the walker's inclusion rules.

   Sub-landing (a)'s `build` wiring turns each entry into a WARN
   diagnostic so users can add the missing import before b.ii-5e
   retires the short-name fallback in `lower/decl-lookup` and makes
   the check a hard error. Empty result means every module-scope
   reference would still resolve after the fallback retires.

   `:declared-in` (added in sub-landing (b.i) per D2 ratification) is
   a vector of module keywords where the unscoped name IS declared
   somewhere in the program — the shadow-mode information users need
   to add a `from <module> import <name>` (single-source) or qualify
   the reference (multi-source). Empty vector means the name isn't
   declared anywhere in the program; the diagnostic surfaces as
   \"undefined\" rather than \"needs import\"."
  [files-with-nodes]
  (let [scope-index    (build-module-scope-index files-with-nodes)
        ;; Reverse index: {name → sorted-vec-of-modules-declaring-it}.
        ;; Only user-declared names participate (prelude names are
        ;; always in scope so they never surface as unscoped).
        declared-index (reduce (fn [acc {:keys [module nodes]}]
                                 (reduce (fn [a nm]
                                           (update a nm (fnil conj []) module))
                                         acc
                                         (decls-in-file nodes)))
                               {}
                               files-with-nodes)]
    (for [{:keys [module nodes file]} files-with-nodes
          nm                          (module-scoped-ref-names nodes)
          :when                       (not (contains? (get scope-index module #{}) nm))]
      {:module      module
       :file        file
       :name        nm
       :declared-in (vec (sort (get declared-index nm [])))})))

;; ---------------------------------------------------------------------------
;; F51 L3b sub-landing (b.ii) — reference resolution enrichment
;;
;; Enrichment pass that walks a file's parsed AST and, for every
;; reference node the L3b(a) walker recognizes, adds a `:resolves-to
;; <module-kw>` attribute pointing at the module that OWNS the name.
;; b.ii-5a-c consume the hint in `lower/decl-lookup` and
;; `lower/resolve-decl-lookup`, so path (1) fires whenever the
;; enriched metadata reaches lower (guaranteed after b.ii-3 multi-file
;; + b.ii-5c single-file).
;;
;; Resolution rules (mirrors `build-module-scope-index` order):
;;   1. Prelude name → module from `prelude-name->module`.
;;   2. Own-module decl → the file's own `:module`.
;;   3. Imported via `from X import name` / `import X as name` /
;;      `import X` where X == name → the imported module.
;;   4. Otherwise → nil (unresolved; L3b(a) WARN territory).
;;
;; Ambiguity (name imported via more than one path) → nil today; a
;; future landing surfaces this as a diagnostic when the consumer
;; needs to disambiguate.
;; ---------------------------------------------------------------------------

(defn- import-source-map
  "Build `{local-name → module-kw}` from a file's `:from-import` /
   `:import` nodes. Local name is `:as` when present, else the
   imported symbol / module name. Values are the source module the
   local binding resolves to."
  [nodes]
  (into {}
        (mapcat (fn [n]
                  (case (:node n)
                    :from-import
                    (let [src-mod (keyword (:from n))]
                      (map (fn [sym]
                             [(or (:as sym) (:name sym)) src-mod])
                           (:names n)))
                    :import
                    (let [src-mod (keyword (:from n))]
                      [[(or (:as n) (:from n)) src-mod]])
                    nil)))
        nodes))

(defn- resolve-name-to-module
  "Resolve a bare name reference to its owning module keyword under
   the L3b(b.ii) rules. Returns nil when unresolved or ambiguous.
   `own-module` is the resolving file's module keyword; `own-decls`
   is the set of names declared anywhere in that module (aggregated
   across every file in the module); `imports` is the local-binding
   map `import-source-map` produced for THIS file."
  [nm own-module own-decls imports prelude-provenance]
  (cond
    (contains? prelude-provenance nm)  (get prelude-provenance nm)
    (contains? own-decls nm)           own-module
    (contains? imports nm)             (get imports nm)
    :else                              nil))

(defn- enrich-node-with-resolution
  "Add `:resolves-to <module>` to a reference-shaped node when the
   name resolves under the current file's scope. Returns the node
   unchanged when the name is unresolved OR when the node kind
   doesn't participate in module-scope resolution. Idempotent — a
   node that already carries `:resolves-to` is preserved verbatim
   (does not re-resolve or overwrite). This lets `lower/lower`
   safely re-run enrichment on already-enriched input from build.clj
   without stomping over an intentionally-tampered hint."
  [node own-module own-decls imports prelude-provenance]
  (letfn [(resolve! [nm]
            (resolve-name-to-module nm own-module own-decls imports prelude-provenance))]
    (cond
      (not (map? node)) node

      (and (= :type-ref (:node node)) (string? (:name node))
           (not (contains? node :resolves-to)))
      (if-let [m (resolve! (:name node))]
        (assoc node :resolves-to m)
        node)

      (and (= :call (:node node))
           (let [c (:callee node)]
             (and (map? c)
                  (= :ident (:node c))
                  (capitalized? (:name c))
                  (not (contains? c :resolves-to)))))
      (if-let [m (resolve! (:name (:callee node)))]
        (update node :callee assoc :resolves-to m)
        node)

      (and (= :dot (:node node))
           (let [t (:target node)]
             (and (map? t)
                  (= :ident (:node t))
                  (capitalized? (:name t))
                  (not (contains? t :resolves-to)))))
      (if-let [m (resolve! (:name (:target node)))]
        (update node :target assoc :resolves-to m)
        node)

      (and (= :extend-class (:node node)) (string? (:target node))
           (not (contains? node :resolves-to)))
      (if-let [m (resolve! (:target node))]
        (assoc node :resolves-to m)
        node)

      (and (= :type-of (:node node))
           (string? (:outer node))
           (capitalized? (:outer node))
           (not (contains? node :resolves-to)))
      (if-let [m (resolve! (:outer node))]
        (assoc node :resolves-to m)
        node)

      :else node)))

(defn- resolve-references-in-file
  "Return the file's node vector with reference nodes enriched by
   `:resolves-to <module-kw>` per L3b(b.ii) resolution rules. Pure
   metadata pass. Internal — callers use [[resolve-references]] which
   handles the per-module aggregation up-front.

   `own-decls-per-module` is `{module-kw → #{name-string ...}}` of
   own-module decls (produced by aggregating `decls-in-file` across
   every file in each module). Prelude-name → prelude-module index
   is computed once by callers and threaded in."
  [{:keys [module nodes] :as file-entry} own-decls-per-module prelude-provenance]
  (let [own-decls  (get own-decls-per-module module #{})
        imports    (import-source-map nodes)
        enriched   (walk/postwalk
                     (fn [x]
                       (enrich-node-with-resolution
                         x module own-decls imports prelude-provenance))
                     nodes)]
    (assoc file-entry :nodes enriched)))

(defn resolve-references
  "Apply `resolve-references-in-file` to every entry in
   `files-with-nodes`. Returns the same shape with enriched nodes.
   Pure additive pass — attaches `:resolves-to <module-kw>` on
   reference nodes. Consumed by `lower/decl-lookup` as the strict
   hint (b.ii-5d Batch 7 retired the short-name fallback: unresolved
   refs now return nil, and consumer sites raise
   `:cxc-f51-unresolved-name` when nil surfaces at a must-resolve
   position)."
  [files-with-nodes]
  (let [own-decls-per-module
        (reduce (fn [acc {:keys [module nodes]}]
                  (update acc module
                          (fnil clojure.set/union #{})
                          (decls-in-file nodes)))
                {}
                files-with-nodes)
        prelude-provenance (prelude-name->module)]
    (mapv #(resolve-references-in-file % own-decls-per-module prelude-provenance)
          files-with-nodes)))

(defn enrich-prelude-nodes
  "F51 L3b(b.ii)-5d Batch 7 (2026-08-28) — attach `:resolves-to` to
   every reference inside the parsed prelude AST. Post-Batch 7 the
   short-name fallback in `lower/resolve-decl-lookup` is retired, so
   prelude→prelude type refs (e.g. `Instant` fields declared as
   `Duration`) need explicit provenance to resolve.

   Consumes the flat parsed prelude node vector (as produced by
   `parse/parse` on the assembled prelude source) and returns the
   same shape with references enriched. Resolution rules: prelude
   names hit `prelude-name->module`; unknown names get no
   `:resolves-to` (primitives like `Int`, and any true user error,
   are filtered by cxc-type->c's primitive-c-type check before the
   resolver runs).

   Callers: `build/compile-parsed-to-file` in both single- and
   multi-file paths — invoked on the raw parsed prelude before the
   full-parsed concat."
  [prelude-nodes]
  (let [prelude-provenance (prelude-name->module)]
    (walk/postwalk
      (fn [x]
        (enrich-node-with-resolution x nil #{} {} prelude-provenance))
      prelude-nodes)))

(defn ensure-references-enriched
  "F51 L3b(b.ii)-5d Batch 7 (2026-08-28) — idempotent enrichment for
   a flat top-node stream (prelude + user, or user-only).

   Post-Batch 7, `lower/resolve-decl-lookup` is strict: refs without
   `:resolves-to` never match. The single- + multi-file `build.clj`
   pipeline enriches references upstream via
   [[resolve-references-in-file]] before feeding lower, so those
   paths are already covered. This helper handles the remaining
   surface: direct `lower/lower` callers that bypass build (unit
   tests, REPL invocations) whose top-nodes carry no
   `:resolves-to`. Idempotent — nodes that already have the hint
   pass through unchanged.

   Walks the input twice:
     1. `decls-in-file` to build per-module own-decl sets (single-
        file collapses to `:main`; multi-file uses each `:module`
        marker's module).
     2. Postwalk each ref through `enrich-node-with-resolution`.

   Imports are not consulted — this is a best-effort fill-in for
   in-file / prelude refs; cross-module import resolution stays in
   the build pipeline where the import-graph is available."
  [top-nodes]
  (let [prelude-provenance (prelude-name->module)
        [_ own-decls-per-module]
        (reduce
          (fn [[m acc] n]
            (cond
              (= :module (:node n))
              [(keyword (:name n)) acc]

              (contains? #{:record :def-atom :enum} (:node n))
              [m (update acc m (fnil conj #{}) (:name n))]

              :else [m acc]))
          [:main {}]
          top-nodes)]
    (walk/postwalk
      (fn [x]
        (enrich-node-with-resolution
          x
          ;; Best-effort own-module: for a mixed stream we can't tell
          ;; per-node module in a postwalk (postwalk visits leaves
          ;; first). Callers whose top-nodes carry mixed modules
          ;; should pre-enrich via `resolve-references` (build.clj
          ;; does). For single-file `lower-src` callers everything
          ;; attributes to `:main`, so `own-module = :main` is
          ;; correct.
          :main
          (get own-decls-per-module :main #{})
          {}
          prelude-provenance))
      top-nodes)))
