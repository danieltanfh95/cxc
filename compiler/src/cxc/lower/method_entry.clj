(ns cxc.lower.method-entry
  "Method-shape env-entry constructors used by env-build and extension
   composition. Every entry produced here is compatible with the
   `:methods` list on record/atom env slots and the `:defaults` list
   on protocol env slots, so downstream call-site dispatch, shadow
   detection, and conformance checks operate on one uniform shape.

   Three converters live here:
     - `normalize-method-param` — parser `:param`/`:field` → env
       `{:name :cxc-ty :is-self? :default}`
     - `observable->method-entry` — F67 L4 observable (reader) →
       method-shape entry with a synthetic `:return` body
     - `handler-decl->method-entry` — F14 `-> name(args):` handler →
       method-shape entry preserving the handler body

   Default-value validation (spec §3.8.5 rules 1/3) fires here so
   observable and handler surfaces get the same discipline as
   `:methods` entries do at env-build time."
  (:require [cxc.lower.default-validate
             :refer [validate-defaults-after-required!
                     validate-default-is-const!]]))

(defn normalize-method-param
  "Parser hands us `self` as {:node :param :name \"self\"} and typed params
   as {:node :field :name ... :type ...}. Normalize to {:name :cxc-ty
   :is-self? :default}. `:default` preserves the parser's default-
   expr node (nil when absent) so `resolve-args-against-params` can
   insert it at call sites where the arg is omitted (spec §3.8.5)."
  [enclosing-record-name p]
  (if (= "self" (:name p))
    {:name "self" :cxc-ty enclosing-record-name :is-self? true :default nil}
    {:name (:name p) :cxc-ty (:name (:type p)) :is-self? false
     :default (:default p)}))

(defn observable->method-entry
  "F67 L4 — convert an observable node into a method-shape env entry
   compatible with `:methods` on record/atom env slots and `:defaults`
   on protocol env slots. Observables are pure readers; the body
   becomes `[{:node :return :value <expr>}]`. `self` is prepended to
   params to match downstream expectations.

   F67 L4b — observable return type is inferred from the body expression
   during `infer-observables-in-{record,atom}-env`; callers that need it
   at conformance-check time (see `observable-methods-view`) source
   `:cxc-return-ty` from the post-inference `:type-kw` slot on the
   observable-index entry. The entry produced here carries nil
   `:cxc-return-ty` — the pre-inference view.

   L5 — this converter is intentionally for bodyful `:observable` nodes
   only. Bodyless `:observation-signature` declarations feed protocol
   accepts/witness entries through `protocol-signature->accepts-entry`."
  [owner-name obs]
  (let [self-param {:name "self" :type nil :default nil}
        with-self  (into [self-param] (:params obs))
        site       (str "observable `" owner-name "." (:name obs) "`")]
    (validate-defaults-after-required! site (:params obs))
    (validate-default-is-const!        site (:params obs))
    {:name               (:name obs)
     :params             (mapv #(normalize-method-param owner-name %) with-self)
     :cxc-return-ty      nil
     :cxc-return-ty-node nil
     :body               {:node :block
                          :stmts [{:node :return :expr (:expr obs)}]}
     :replace?           (boolean (:replace? obs))
     :observable-derived? true}))

(defn handler-decl->method-entry
  "F14 — convert an atom-body `-> name(args):` handler declaration
   into a method-shaped entry compatible with `:methods` in atom-env.
   Handlers are externally-invocable side-effect-only methods (spec
   §1265); `:cxc-return-ty nil` marks void return, `:handler? true`
   is provenance for future wire-attachment work. `self` is implicit
   on handlers (spec §1265; user writes `-> log_it(msg: String):`) —
   we synthesize the self-param at position 0 to match the methods-
   section entry shape downstream `lower-atom-method` expects."
  [atom-name hd-node]
  (let [site         (str "handler `" atom-name ".-> " (:name hd-node) "`")
        user-params  (:params hd-node)
        self-param   {:name "self" :type nil :default nil}
        with-self    (into [self-param] user-params)]
    (validate-defaults-after-required! site user-params)
    (validate-default-is-const!        site user-params)
    {:name               (:name hd-node)
     :params             (mapv #(normalize-method-param atom-name %) with-self)
     :cxc-return-ty      nil
     :cxc-return-ty-node nil
     :body               (:body hd-node)
     :replace?           false
     :handler?           true}))
