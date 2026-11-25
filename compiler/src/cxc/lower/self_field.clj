(ns cxc.lower.self-field
  "Small ctx-scoped accessors that resolve a bare field name against
   the enclosing atom (via `(:locals ctx) 'self'` → `:atom`). These
   read the atom-env's `:field-index` / `:subscription-map` slots and
   answer one question each:
     - `field-needs-slot-setter?` — must a write route through the
       synthesized `-set<Field>:` slot setter (subscription-root)?
     - `field-nonnil?`           — declared `T!` on the enclosing atom
     - `self-field-info`         — the full field record
     - `self-observation-info`   — hickey-cycle-310 Phase 1: observable
                                   entry on the enclosing atom or record
     - `self-member-info`        — hickey-cycle-310 Phase 1: field OR
                                   observable entry on the enclosing
                                   self-scope (atom or record). Used by
                                   the shadowing warning.
     - `self-handler-info`       — hickey-cycle-311 Phase 1: message-
                                   handler entry on the enclosing atom.
                                   Used by `lower-send` to resolve bare
                                   `:> X(...)` to `self :> X(...)`.
     - `self-stream-info`        — hickey-cycle-311 Phase 1: stream
                                   emit-site entry on the enclosing atom.
                                   Paired with `self-handler-info` to
                                   decide stream-vs-handler dispatch at
                                   a bare `:>` site.

   All helpers degrade to falsy when self is not in scope
   (free position) — safe to call from any lowering frame."
  (:require [cxc.env :as env]))

(set! *warn-on-reflection* true)

(defn- self-atom-info
  "Resolve the atom-env entry for the enclosing `self` scope. Returns
   nil when self isn't an atom in scope (record method context or
   free position)."
  [ctx]
  (let [self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))]
    (env/env-lookup ctx :atom-env atom-name)))

(defn- self-record-info
  "Resolve the record-env entry for the enclosing `self` scope. Returns
   nil when self isn't a record in scope (atom body or free position)."
  [ctx]
  (let [self-info (get-in ctx [:locals "self"])
        rec-name  (and (map? self-info) (:record self-info))]
    (env/env-lookup ctx :record-env rec-name)))

(defn field-needs-slot-setter?
  "True when writing this field in a `then:` update must route through
   the synthesized slot setter instead of a direct ivar assignment.
   Applies when the field is a subscription root on the enclosing
   atom's :subscription-map. Other fields keep the simple write."
  [field-name ctx]
  (boolean (seq (get-in (self-atom-info ctx) [:subscription-map field-name]))))

(defn field-nonnil?
  "F39 L3 — is the field with this name declared `T!` on the enclosing
   atom? Reads self-fields from ctx; returns false when not found (so
   containers without the enclosing-atom context degrade gracefully)."
  [field-name ctx]
  (boolean (:nonnil? (get-in (self-atom-info ctx) [:field-index field-name]))))

(defn self-field-info
  "F39 L5b-ii-c helper — look up a field record on the enclosing atom
   in ctx. Returns nil when self isn't an atom in scope (e.g. record
   method context or free position). Callers use this to check
   `:optional?` / `:c-type` for Optional wrap/unwrap decisions."
  [field-name ctx]
  (get-in (self-atom-info ctx) [:field-index field-name]))

(defn- observation-lookup
  "Look up `name` in an env entry's observable-index, falling back to
   the raw :observables list. The index is the fast path (populated
   post-inference); the list fallback keeps resolution working during
   same-record / same-atom first-pass observation inference, where the
   index for a sibling observation isn't registered yet."
  [info name]
  (or (get-in info [:observable-index name])
      (some #(when (= name (:name %)) %) (:observables info))))

(defn self-observation-info
  "hickey-cycle-310 Phase 1 — resolve `name` against the enclosing
   self-scope's observable surface. Works for both atom and record self
   scopes: whichever one `self` is bound to. Returns the observable
   entry (:observable-index value, or the raw :observables node during
   first-pass inference) or nil.

   Callers in `lower-expr :call :ident` / `infer-call-type :ident`
   consult this to resolve bare `obs(args)` to `self.obs(args)` when
   nothing in the local scope shadows the name."
  [name ctx]
  (or (observation-lookup (self-atom-info ctx)   name)
      (observation-lookup (self-record-info ctx) name)))

(defn self-member-info
  "hickey-cycle-310 Phase 1 — resolve `name` against the enclosing
   self-scope's field OR observable index. Returns `{:kind :field
   :entry …}`, `{:kind :observation :entry …}`, or nil. Used by the
   shadowing warning in `lower-alias-stmt` / `lower-let-stmt` to
   describe what a local is shadowing."
  [name ctx]
  (let [atom-info (self-atom-info ctx)
        rec-info  (self-record-info ctx)]
    (cond
      (get-in atom-info [:field-index name])
      {:kind :field :entry (get-in atom-info [:field-index name])}

      (get-in rec-info [:field-index name])
      {:kind :field :entry (get-in rec-info [:field-index name])}

      (observation-lookup atom-info name)
      {:kind :observation :entry (observation-lookup atom-info name)}

      (observation-lookup rec-info name)
      {:kind :observation :entry (observation-lookup rec-info name)}

      :else nil)))

(defn self-handler-info
  "hickey-cycle-311 Phase 1 — resolve `name` against the enclosing atom's
   message-handler index. Returns the handler shape entry (from the
   atom-env's `:message-index`) when `name` is a declared message-
   triggered handler on self, or nil otherwise.

   Record self-scopes never carry handlers (records are passive data),
   so only the atom-env is consulted. Degrades to nil when self is not
   an atom in scope — safe to call from any lowering frame.

   Used by `lower-send` to resolve a bare `:> X(...)` to
   `self :> X(...)` when X names a self-handler and no self-stream
   shares the name."
  [name ctx]
  (get-in (self-atom-info ctx) [:message-index name]))

(defn self-stream-info
  "hickey-cycle-311 Phase 1 — resolve `name` against the enclosing atom's
   stream emit-site index. Returns the stream shape entry (from the
   atom-env's `:stream-index`) when `name` is a declared stream emit on
   self, or nil otherwise.

   Record self-scopes never carry streams; only the atom-env is
   consulted. Degrades to nil when self is not an atom in scope.

   Paired with `self-handler-info` to disambiguate stream-vs-handler
   dispatch at a bare `:>` site. The declaration-time collision check
   in `build-atom-env` rejects atoms that declare both a stream emit
   site and a message handler with the same name, so the ambiguous
   case never reaches lower-send in practice."
  [name ctx]
  (get-in (self-atom-info ctx) [:stream-index name]))
