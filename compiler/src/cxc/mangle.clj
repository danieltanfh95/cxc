(ns cxc.mangle
  "M1b prep — F56 emit-boundary C-id mangling. Owns the
   `*decl->module*` dynamic and the `->c-safe` mangler.

   F56 L2+L3 (2026-08-27) — the parse-time `rewrite-prelude-names`
   postwalk retired. Post-parse AST carries only user-visible names;
   envs are keyed by user-visible names, downstream lower comparisons
   work in user-visible space. Mangling happens at the emit boundary
   via `->c-safe`, which consults `*decl->module*` bound dynamically
   at the top of `lower`.

   `->c-safe` is idempotent on already-mangled input (name not in the
   table → passthrough) so callers that happen to receive a compiler-
   synthesized C name (e.g. `_Cxc_Optional_Int64`) also work.

   F51 L3b(b.ii)-5d Batch 6 retired `*module-map*`. Every consumer
   that read it (cxc-type->c's internal resolve calls, decl-lookup)
   now takes the module-map explicitly. `*decl->module*` stays: its
   scope is single-purpose (drive F56 mangling in `->c-safe`), which
   is what the M5 Hickey-review landing separated it into."
  (:require [cxc.resolve :as resolve]))

(def ^:dynamic *decl->module*
  "F56 ambient — bound at the top of `cxc.lower/lower` from
   `attribute-modules-to-decls`. Consumed only by `->c-safe` below;
   never read directly by lowering code (that reads `(:decl->module
   ctx)` instead, per M5 discipline)."
  nil)

(defn ->c-safe
  "F56 L2+L3 — mangle a user-visible declared name to its C-safe
   identifier via the ambient `*decl->module*`. Returns `nm` verbatim
   when: `nm` is nil / not in the table (compiler-synthesized names,
   primitives) / already in mangled form."
  [nm]
  (if (and nm *decl->module* (contains? *decl->module* nm))
    (resolve/mangled-name-for (get *decl->module* nm) nm)
    nm))
