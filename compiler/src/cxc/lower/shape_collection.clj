(ns cxc.lower.shape-collection
  "Per-atom AST walks that collect stream / message / companion shapes
   before atom-env slot construction.

   Each fn walks a def-atom's transition list (or the full atom-env for
   companion demand) and returns a name-keyed index of shape metadata.
   The passes are intentionally shallow: they read parsed AST slots
   without running type inference, so they run at env-build time
   alongside `fields-from-data-section` / `ctor-params-normalized`.

   L11 (`collect-companion-shapes`) synthesizes `<msg>_failed`
   companion declarations from a message-triggered transition's
   `|:` propositions. L12b (`collect-companion-demand` +
   `augment-atom-env-with-companions`) walks the built atom-env to
   discover subscribers and augments target atoms' stream metadata
   on demand — bounding synthesis growth to programs that actually
   subscribe."
  (:require [clojure.walk :as walk]
            [cxc.diagnostics :refer [fail-nyi!]]
            [cxc.type-kw :refer [base-type-name cxc-type->type-kw
                                 type-kw->c-type]]))

(defn- payload-signature
  "Compact comparable representation of a payload shape — vector of
   `[name cxc-type]` pairs in declaration order. Used to catch
   inconsistent payloads across transitions with the same message name."
  [payload]
  (mapv (fn [p] [(:name p) (base-type-name (:type p))]) payload))

(defn emit-nodes-in
  "Walk any parsed AST subtree and return every `:emit` node whose
   `:target` is nil (untargeted `:>` / `|>` — stream emissions). Skips
   targeted sends. Used both to discover the streams a source atom
   owns (Step 13) and to gate `dispatch_main()` on presence of any
   async work."
  [subtree]
  (let [found (java.util.ArrayList.)]
    (walk/postwalk
     (fn [n]
       (when (and (map? n) (= :emit (:node n)) (nil? (:target n)))
         (.add found n))
       n)
     subtree)
    (vec found)))

(defn collect-stream-shapes
  "Walk every transition body on this atom, gather all `:>` untargeted
   emit sites, and index them by stream name with payload signature.
   Two emits of the same stream name with different arg-name shapes are
   a design smell — same source-line reads would produce different
   subscriber-side selectors. Fail-nyi on mismatch, parallel to
   collect-message-shapes. Returns `{stream-name {:payload [{:name
   :type}]}}` where each payload entry captures `:name` and an inferred
   `:type` when the arg carried a `k: T` typed shape. Layer 4 payloads
   are typed via the same `{k: T}` grammar receivers use on the receive
   side; if the sender's args-block doesn't carry type info, the payload
   entry has :type nil and downstream lookup falls back on the
   subscriber-side declaration for the C type."
  [atom-name transitions]
  (let [emits (emit-nodes-in transitions)
        _     (doseq [e emits]
                (when-not (:async? e)
                  (fail-nyi! (str "sync stream emit `|> " (:msg e)
                                  "` — Layer 4 streams are async-only "
                                  "(design §18 chose Option A: async fan-out)")
                             e)))]
    (reduce
     (fn [acc e]
       (let [name    (:msg e)
             _       (doseq [a (:args e)]
                       (when (= :positional (:kind a))
                         (fail-nyi!
                          (str "positional arg on stream emit `:> " name
                               "` — Layer 4 requires `:> " name "(key: value)` "
                               "named form so payload shape matches subscriber "
                               "receive pattern")
                          e)))
             payload (mapv (fn [a] {:name (:name a)}) (:args e))
             sig     (mapv :name payload)]
         (if-let [existing (get acc name)]
           (do (when (not= (mapv :name (:payload existing)) sig)
                 (fail-nyi!
                  (str "def-atom `" atom-name "` emits stream `" name
                       "` twice with different payload shapes: "
                       (mapv :name (:payload existing)) " vs " sig
                       "; every emit of a stream must carry the same "
                       "keys so subscribers can bind a single receive shape")
                  {:atom atom-name :stream name}))
               acc)
           (assoc acc name {:payload payload}))))
     {}
     emits)))

(defn collect-message-shapes
  "Walk an atom's message-triggered transitions, grouping by message
   name. Every transition sharing a message name must declare the SAME
   payload shape — different shapes would produce different ObjC
   selectors from cxc source that reads identically, which is a design
   smell. Returns `{msg-name {:payload [{:name :type}] :selector <str>}}`
   for later selector encoding + arg marshalling."
  [atom-name transitions]
  (let [msg-txns (filter #(and (:receive %)
                               (contains? #{"<:" "<|"}
                                          (get-in % [:receive :glyph]))
                               (= :ident (get-in % [:receive :msg :source :node])))
                         transitions)]
    (reduce
     (fn [acc t]
       (let [src (get-in t [:receive :msg :source])
             msg-name (:name src)
             payload  (or (get-in t [:receive :msg :payload]) [])]
         (if-let [existing (get acc msg-name)]
           (do (when (not= (payload-signature (:payload existing))
                           (payload-signature payload))
                 (fail-nyi!
                  (str "def-atom `" atom-name "` has two transitions on message `"
                       msg-name "` with different payload shapes: "
                       (payload-signature (:payload existing)) " vs "
                       (payload-signature payload) "; cxc source that reads "
                       "the same must map to the same ObjC selector")
                  {:atom atom-name :msg msg-name}))
               acc)
           (assoc acc msg-name {:payload payload}))))
     {}
     msg-txns)))

(defn- proposition-nodes-in-transition
  "L11 — every `:proposition` node reachable from a transition body's
   clauses (guard head and then/do bodies). Kept shallow: propositions
   at the arm head or as their own statements. Deeper walks land with
   nested-scope work in L12."
  [t]
  (->> (:body t)
       (filter #(= :proposition (:node %)))))

(defn collect-companion-shapes
  "L11 — synthesize `<msg>_failed(reason:)` companion declarations for
   every message-triggered public transition on this atom. Payload is
   the single `reason` key; `:reason-types` records the union of typed
   `|: as T` clauses plus a `:invariant-violation` sentinel for any
   bare `|:`. Runtime delivery is L12; L11 lands metadata only so
   senders can declaratively subscribe against the companion.

   Returns `{\"<msg>_failed\" {:payload [{:name \"reason\"}]
                               :reason-types [...]
                               :of-msg \"<msg>\"}}` per unique msg."
  [_atom-name transitions]
  (let [msg-txns (filter #(and (:receive %)
                               (contains? #{"<:" "<|"}
                                          (get-in % [:receive :glyph]))
                               (= :ident (get-in % [:receive :msg :source :node])))
                         transitions)]
    (reduce
     (fn [acc t]
       (let [msg-name (get-in t [:receive :msg :source :name])
             failed   (str msg-name "_failed")
             props    (proposition-nodes-in-transition t)
             typed    (keep (fn [p]
                              (when-let [et (:as-error-type p)]
                                (when (= :type-ref (:node et))
                                  {:kind :typed :type-name (:name et)})))
                            props)
             has-bare? (some #(nil? (:as-error-type %)) props)
             reasons  (cond-> (vec (distinct typed))
                        has-bare?
                        (conj {:kind :invariant-violation}))
             entry    {:payload      [{:name "reason"}]
                       :reason-types reasons
                       :of-msg       msg-name}]
         (if-let [existing (get acc failed)]
           (assoc acc failed
                  (assoc existing :reason-types
                         (vec (distinct (concat (:reason-types existing) reasons)))))
           (assoc acc failed entry))))
     {}
     msg-txns)))

(defn- collect-companion-demand
  "L12b — walk every atom's transitions to find companion subscription
   sites (`<: field.<msg>_failed(reason: T) -> ...`). Returns
   `{target-atom {companion-name #{{:subscriber X :reason-type T}}}}`.
   Feeds `augment-atom-env-with-companions` so target atoms only
   synthesize per-companion emit/subscribe scaffolding on demand,
   bounding output growth to programs that actually subscribe."
  [atom-env]
  (reduce-kv
   (fn [demand subscriber-name info]
     (reduce
      (fn [demand2 t]
        (let [glyph (get-in t [:receive :glyph])
              src   (get-in t [:receive :msg :source])]
          (if (and (contains? #{"<:" "<|"} glyph)
                   (= :dot (:node src))
                   (= :ident (get-in src [:target :node])))
            (let [field-name  (get-in src [:target :name])
                  field-info  (get-in info [:field-index field-name])
                  target-atom (some-> field-info :type base-type-name)
                  msg-name    (:field src)
                  target-info (get atom-env target-atom)]
              (if (and target-atom target-info
                       (contains? (:companion-index target-info) msg-name))
                (let [payload   (get-in t [:receive :msg :payload])
                      reason-p  (first payload)
                      _ (when (and reason-p
                                   (= "reason" (:name reason-p))
                                   (not (:bareword? reason-p)))
                          (fail-nyi!
                           (str "Track A/3 (2026-08-26): typed subscriber "
                                "reason payload `reason: " (or (base-type-name
                                                                (:type reason-p))
                                                               "T")
                                "` on `" subscriber-name "` subscribing to `"
                                target-atom "." msg-name "` is retired. Rewrite "
                                "as bareword `reason:` and dispatch on the reason "
                                "via `<: reason: <: <Record>: ...` inside the "
                                "receive-arm body — the target atom's `|: as T` "
                                "clauses declare the reason universe (spec §12).")
                           t))
                      reason-ty (when (and reason-p (= "reason" (:name reason-p)))
                                  (base-type-name (:type reason-p)))]
                  (update-in demand2 [target-atom msg-name] (fnil conj #{})
                             {:subscriber  subscriber-name
                              :reason-type reason-ty}))
                demand2))
            demand2)))
      demand
      (:transitions info)))
   {}
   atom-env))

(defn augment-atom-env-with-companions
  "L12b — for each companion subscription demand, augment the target
   atom's `:stream-index` + `:stream-order` + `:stream-payload-index`
   so the existing per-stream synthesis (ivar/hashtable/emit method +
   subscribe/unsubscribe methods) picks the companion up automatically.

   Reason type comes from subscribers' declared type. All subscribers
   of a given companion must agree on the reason type; inconsistent
   declarations fail-nyi. Missing/mis-keyed subscriber payload defers
   to the receive-side validation for a cleaner diagnostic — augment
   here still fires so validation sees stream-index populated."
  [atom-env record-env enum-env]
  (let [demand (collect-companion-demand atom-env)]
    (reduce-kv
     (fn [env target msg-map]
       (reduce-kv
        (fn [env2 msg subs]
          (let [reason-tys (set (keep :reason-type subs))
                _ (when (> (count reason-tys) 1)
                    (fail-nyi!
                     (str "companion `" target "." msg
                          "` has subscribers declaring inconsistent "
                          "reason types: " reason-tys
                          "; all subscribers must agree on the reason type "
                          "(union reason types land with L12c)")
                     {:target target :companion msg}))
                reason-ty  (or (first reason-tys) "Any")
                type-kw    (cxc-type->type-kw reason-ty record-env env2
                                              enum-env {})
                c-type     (type-kw->c-type type-kw {})
                current    (get env2 target)
                stream-idx (assoc (or (:stream-index current) {}) msg
                                  {:payload [{:name "reason"}]})
                stream-ord (let [ord (or (:stream-order current) [])]
                             (if (some #(= msg %) ord)
                               ord
                               (conj ord msg)))
                stream-pay (assoc (or (:stream-payload-index current) {}) msg
                                  [{:name    "reason"
                                    :type-kw type-kw
                                    :c-type  c-type}])]
            (assoc env2 target
                   (assoc current
                          :stream-index         stream-idx
                          :stream-order         stream-ord
                          :stream-payload-index stream-pay))))
        env
        msg-map))
     atom-env
     demand)))
