(ns cxc.env
  "M1a — module attribution + env-lookup primitives, extracted from
   cxc.lower. This is the leaf namespace of the lowering layer: no
   deps on cxc.type-kw / cxc.lower.expr / cxc.lower / anything
   downstream.

   Two lookup primitives, chosen by whether the caller has an AST
   reference node in hand:

     `decl-lookup`  — reference resolution. Takes an AST ref node,
                      reads `:name` + `:resolves-to`, strictly
                      resolves against the module-map. Returns nil
                      when no hint or no match — consumers that
                      must-resolve raise `:cxc-f51-unresolved-name`
                      at their call site.
     `env-lookup`   — bookkeeping. Takes a plain name string, does
                      `(get env nm)`. Use when the name isn't a
                      user reference (data-derived, iteration,
                      locals).

   Attribution helpers (`attribute-modules-to-decls`,
   `module-map-from-attribution`) build the module-map that
   `decl-lookup` / `resolve-decl-lookup` consult. See F51 L3b(b.ii)
   plan doc for the full derivation."
  (:require [cxc.resolve :as resolve]))

;; ---------------------------------------------------------------------------
;; atom-env :kind vocabulary + phantom-cap registry (moved here 2026-08-28
;; from cxc.lower so cxc.type-kw and other leaves can reach them without
;; back-referencing cxc.lower).
;;
;; atom-env entries fall into one of these kinds. The distinction matters
;; at env-consumer sites that want to skip synthesized / runtime entries
;; (e.g. F38's "no agent field in record" check excludes containers
;; because `List of Tally` is legal).
;;
;;   :def-atom          user-declared `def X:` — the default kind
;;   :runtime-provided  prelude-supplied (Clock, Console, CxcStream, …)
;;   :list-container    synthesized `List of T` class
;;   :map-container     synthesized `Map of (K, V)` class
;;   :set-container     synthesized `Set of T` class
;;
;; Phantom capability atoms (SystemPanic, ...) are compiler-known cap
;; types that never appear in atom-env — they license `use:` slots
;; without requiring the user to `def` them. Extension is one edit
;; here (add the name); consumers reach it via `phantom-atom-name?`.
;; ---------------------------------------------------------------------------

(def synthesized-atom-kinds
  #{:runtime-provided :list-container :map-container :set-container})

(defn synthesized-atom-kind?
  "True when a `:kind` value on an atom-env entry marks it as
   compiler-synthesized (container class, runtime-provided prelude
   entry) rather than a user-declared `def-atom`."
  [k]
  (contains? synthesized-atom-kinds k))

(def phantom-atom-names
  "Cap types the compiler recognizes without a matching atom-env entry.
   Adding a new phantom cap: extend this set + any `use: <name>`
   validation that reaches it (grep `phantom-atom-name?`)."
  #{"SystemPanic"})

(defn phantom-atom-name?
  "True when `nm` names a phantom capability atom — one the compiler
   licenses at type-check without requiring the user to declare it."
  [nm]
  (contains? phantom-atom-names nm))

(defn attribute-modules-to-decls
  "F51 L3b(b.ii)-2 — walk `top-nodes` stream-wise tracking the current
   module via `:module` nodes; return `{decl-name-string → module-kw}`
   over records/atoms/enums (Corvid's decl kinds). Prelude-declared
   names get their `std.*` attribution via `prelude-name->module`;
   declarations before any `:module` node are attributed to `:main`
   (single-file convention per F51 L1).

   Returns a plain map — env entries consume it to stamp their own
   `:module` slot at build time. Also used to derive `:module-map`
   (module-kw → #{short-name ...}) for reverse lookup."
  [top-nodes]
  (let [prelude-provenance resolve/prelude-name->module]
    (resolve/walk-modules
     top-nodes {}
     (fn [acc module n]
       (assoc acc (:name n)
              (or (get prelude-provenance (:name n)) module))))))

(defn module-map-from-attribution
  "Invert the name→module map into module→#{names} for reverse
   (module-scoped) enumeration by the lookup primitive.

   Prelude provenance is merged in before inversion so a reference
   to a prelude decl (`Duration`, `SystemPanic`, ...) resolves even
   when the caller (test harness, direct-invocation) hasn't
   prepended the parsed prelude AST to top-nodes. Prelude entries
   win on conflict: the build.clj pipeline attributes prelude names
   to their `std.*` module anyway, so this is a no-op for it, but
   it keeps a caller-supplied `{\"Duration\" :custom}` from putting
   `Duration` under two modules."
  [name->module]
  (let [complete (merge name->module resolve/prelude-name->module)]
    (reduce-kv (fn [acc nm m]
                 (update acc m (fnil conj #{}) nm))
               {}
               complete)))

(defn resolve-decl-lookup
  "Strict resolution. Returns the env entry when the reference has a
   `:resolves-to` hint AND that module declares the short name,
   returns nil otherwise.

   Silent-miss policy. Consumers that treat nil as \"not applicable\"
   (predicate uses: `enum-case-access?`, `enum-payload-call?`,
   `record-from-bytes-call?`) read nil as the answer. Consumers that
   must-resolve raise `:cxc-f51-unresolved-name` at their callsite
   when the lookup returns nil (see e.g. `lower-record-ctor`,
   `lower-atom-ctor`).

   `env` may be any of `:record-env` / `:atom-env` / `:enum-env` —
   the primitive is agnostic to which. `module-map` is
   `{module-kw → #{name-string ...}}` (see
   `module-map-from-attribution`)."
  [env module-map name resolves-to]
  (when (and name resolves-to
             (contains? (get module-map resolves-to #{}) name))
    (get env name)))

(defn env-lookup
  "Bookkeeping primitive — plain env access by name. Use at sites
   whose name isn't a user reference: type-kw slot extraction
   (`(:atom target-ty)`), env-key iteration, name pulled off a data
   structure, etc. No resolution, no diagnostic — just `(get env nm)`.

   The reference-resolution primitive is [[decl-lookup]] — use that
   whenever you have a user-visible AST reference node in hand."
  [ctx env-kw nm]
  (get-in ctx [env-kw nm]))

(defn decl-lookup
  "Caller-facing wrapper around `resolve-decl-lookup`. Reads the
   reference node's `:resolves-to` hint and the ctx's `:module-map`,
   then dispatches. `env-kw` is one of `:record-env`, `:atom-env`,
   `:enum-env`.

   `ref-node` must be an AST reference node (map with `:name` and
   `:resolves-to`) or nil for an absent reference. Any other shape
   is an upstream contract violation. Sites whose name isn't a user
   reference (data-derived, iteration, locals) must use
   [[env-lookup]] — plain env access, not a resolution."
  [ctx env-kw {:keys [name resolves-to]}]
  (resolve-decl-lookup (get ctx env-kw)
                       (:module-map ctx)
                       name
                       resolves-to))
