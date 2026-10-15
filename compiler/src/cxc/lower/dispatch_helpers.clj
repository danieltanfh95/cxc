(ns cxc.lower.dispatch-helpers
  "Helpers for lowering statement-position `<:` dispatch (F69 L4b/c1/
   c4). None of these fns type-infer or lower arm bodies — they only
   classify the scrutinee-type gate, enumerate patterns for coverage,
   and pick the C type the scrutinee's temporary should carry.

   `lower-dispatch-stmt` (in lower.clj) orchestrates; these fns are
   the reusable classifiers it composes."
  (:require [clojure.set]
            [cxc.diagnostics :refer [fail-nyi!]]
            [cxc.type-kw :refer [type-kw->c-type]]))

(defn arm-patterns
  "Return the vector of patterns in `arm` — L4c2 arms carry `:patterns`;
   older-shape arms fall back to `[(:pattern arm)]`."
  [arm]
  (or (:patterns arm) [(:pattern arm)]))

(defn check-dispatch-exhaustiveness!
  "F69 L4c1 — spec §3.7 exhaustiveness rule for tagged-variant scrutinees:
   every declared case must be covered, or a `<: *:` wildcard included.
   Rejects at lower-time with a message listing the missing cases when
   coverage is incomplete. Multi-pattern arms (L4c2) contribute every
   pattern in their `:patterns` vector to coverage.

   F69 L4c3 — guarded arms (with `if <cond>`) do NOT contribute to
   coverage: the guard may be false at runtime, so an unguarded arm
   for that case or a wildcard is still required."
  [enum-name enum-cases arms node]
  (let [unguarded-arms (remove :guard arms)
        all-patterns   (mapcat arm-patterns unguarded-arms)
        has-wildcard?  (some #(= :wildcard (:kind %)) all-patterns)]
    (when-not has-wildcard?
      (let [covered  (set (keep #(when (= :case (:kind %)) (:name %))
                                all-patterns))
            declared (set enum-cases)
            missing  (clojure.set/difference declared covered)]
        (when (seq missing)
          (fail-nyi!
           (str "F69 L4c1: non-exhaustive `<:` dispatch on `" enum-name
                "` — case(s) " (pr-str (sort (vec missing)))
                " unhandled. Add an arm per missing case, or a `<: _:` "
                "wildcard tail (spec §3.7 exhaustiveness rule).")
           node))))))

(defn scrut-ty->c-type
  "Return the C type string for a scrutinee's inferred type. Enum
   scrutinees carry the enum name (C typedef). Primitive scrutinees
   (Int, Bool) resolve via type-kw->c-type.

   Track A/2.b (2026-08-26) — id-typed (`:any`) scrutinees hit the
   `type-kw->c-type` fallback which returns `id`; kept explicit so
   the intent is documented at this call site."
  [scrut-ty scrut-node]
  (cond
    (and (map? scrut-ty) (:enum scrut-ty)) (:enum scrut-ty)
    :else                                  (type-kw->c-type scrut-ty scrut-node)))

(defn dispatch-scrutinee-supported?
  "L4b/c4 — the scrutinee-type gate. Enum (tagged-variant) is the
   L4b substrate; Int and Bool are the L4c4 primitive-literal
   substrates. Structural / agent scrutinees remain L4d.

   Track A/2.b (2026-08-26) — `:any` (id-typed) scrutinees are the
   companion-reason substrate: patterns are record names, condition is
   `[scrut isKindOfClass:[_Cxc_R_<T> class]]`. Enables the receiver-
   side surface for `<msg>_failed(reason:) ... <: reason: <: MyError(...): ...`
   (companion reason dispatch)."
  [scrut-ty]
  (or (and (map? scrut-ty) (:enum scrut-ty))
      (contains? #{:int :bool :any} scrut-ty)))

(defn reference-tk?
  "F39 L7 — true iff a type-kw denotes a reference-shaped type
   (nil-carrying at the pointer level). Atoms, `String`, `Bytes`,
   `Any`, `Block`. Optional-boxed value types (`[:optional ...]`)
   are handled separately by `optional-tk?`."
  [tk]
  (or (contains? #{:string :bytes :any :block} tk)
      (and (map? tk) (:atom tk))))
