(ns cxc.lower.self-field
  "Small ctx-scoped accessors that resolve a bare field name against
   the enclosing atom (via `(:locals ctx) 'self'` → `:atom`). These
   read the atom-env's `:field-index` / `:subscription-map` slots and
   answer one question each:
     - `field-needs-slot-setter?` — must a write route through the
       synthesized `-set<Field>:` slot setter (subscription-root)?
     - `field-nonnil?`           — declared `T!` on the enclosing atom
     - `self-field-info`         — the full field record

   All three degrade to falsy when self is not an atom in scope
   (record method context, free position) — safe to call from any
   lowering frame."
  (:require [cxc.env :as env]))

(defn field-needs-slot-setter?
  "True when writing this field in a `then:` update must route through
   the synthesized slot setter instead of a direct ivar assignment.
   Applies when the field is a subscription root on the enclosing
   atom's :subscription-map. Other fields keep the simple write."
  [field-name ctx]
  (let [self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))
        info      (env/env-lookup ctx :atom-env atom-name)
        subs      (get-in info [:subscription-map field-name])]
    (boolean (seq subs))))

(defn field-nonnil?
  "F39 L3 — is the field with this name declared `T!` on the enclosing
   atom? Reads self-fields from ctx; returns false when not found (so
   containers without the enclosing-atom context degrade gracefully)."
  [field-name ctx]
  (let [self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))
        info      (env/env-lookup ctx :atom-env atom-name)
        f         (get-in info [:field-index field-name])]
    (boolean (:nonnil? f))))

(defn self-field-info
  "F39 L5b-ii-c helper — look up a field record on the enclosing atom
   in ctx. Returns nil when self isn't an atom in scope (e.g. record
   method context or free position). Callers use this to check
   `:optional?` / `:c-type` for Optional wrap/unwrap decisions."
  [field-name ctx]
  (let [self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))
        info      (env/env-lookup ctx :atom-env atom-name)]
    (get-in info [:field-index field-name])))
