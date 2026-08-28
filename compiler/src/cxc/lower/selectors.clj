(ns cxc.lower.selectors
  "Selector / free-function name construction for the ObjC emit layer.

   Two groups of fns:

     - Naming: `c-method-name` (record method → free C fn name),
       `objc-method-selector` (bare-name → Cocoa selector with the
       `WithFirst:Second:...` initWith convention).

     - Stream plumbing (Layer 5b Step 6 / Layer 4b): source-side
       selector shapes (`cxc_emit_<name>[With...]`), subscriber-side
       selector shapes
       (`cxc_stream_<name>_from_<Source>Withsender:<k1>:...`),
       per-stream subscribers-hashtable ivar / property, and the
       full `stream-source-methods` factory that produces the emit +
       add/remove-subscriber method set for one atom's stream-order.

   No lowering-pass helpers touch this file — pure name builders +
   node factories."
  (:require [clojure.string :as str]
            [cxc.mangle :refer [->c-safe]]))

(defn c-method-name
  "Free-function name for a record's method: `<Record>_<method>`.
   F56 L2+L3: `<Record>` is passed through `->c-safe` so prelude
   records get `_Cxc_Duration_plus` rather than `Duration_plus`."
  [record-name method-name]
  (str (->c-safe record-name) "_" method-name))

(defn objc-method-selector
  "Build the ObjC selector for a method call. Zero-arg → bare name;
   with args → `<name>With<first>:<rest>:...` matching the initWith
   convention on ctors. Not to be confused with cxc's `.name` — cxc
   source uses dot syntax; only the emit layer sees selectors."
  [method-name arg-names]
  (if (empty? arg-names)
    method-name
    (str method-name "With" (first arg-names) ":"
         (str/join "" (map #(str % ":") (rest arg-names))))))

(defn stream-selector
  "Cocoa selector on the SUBSCRIBER side for a stream receive. Encoding:
   `cxc_stream_<streamname>_from_<SourceClass>Withsender:<k1>:<k2>:...`.
   The `_from_<Source>` suffix scopes the selector to the source class,
   so two receive-glyph transitions on the same subscriber pulling
   streams named identically from different sources compile to distinct
   selectors (Choice 1A, see design analysis).

   Layer 5b Step 6b: every subscriber-side selector carries a leading
   `sender:` argument (typed `id`) filled by the source's fanout with
   `self`. Container fan-in forwarding methods use it as the element
   pointer on re-emit (`e:`); ordinary main-side subscribers receive it
   but can ignore it. Source-side selectors (`cxc_emit_<name>[With...]`)
   are unchanged — sender is derived at fanout, not passed by the caller
   of `:> name`."
  [source-class stream-name payload-keys]
  (objc-method-selector (str "cxc_stream_" stream-name "_from_" (->c-safe source-class))
                        (into ["sender"] payload-keys)))

(defn stream-emit-selector
  "Cocoa selector on the SOURCE side for a stream emit method:
   `cxc_emit_<streamname>[With<k1>:<k2>:...]`. Called by `:>` emit-site
   lowering; body iterates the subscriber hashtable + cxc_send_async's
   each."
  [stream-name payload-keys]
  (objc-method-selector (str "cxc_emit_" stream-name) payload-keys))

(defn stream-subscribers-ivar-name
  [stream-name]
  (str "_stream_subscribers_" stream-name))

(defn stream-subscribers-prop
  "Per-stream `NSHashTable *_stream_subscribers_<name>` property.
   `strong` under ARC because the hashtable itself is owned by this
   atom; weak semantics are on the HASHTABLE's stored subscribers
   (configured via +weakObjectsHashTable), not on the container."
  [stream-name]
  {:name   (subs (stream-subscribers-ivar-name stream-name) 1)  ; drop leading _
   :c-type "NSHashTable *"
   :attrs  ["nonatomic" "strong"]})

(defn stream-source-methods
  "Synthesize the per-stream ivars, init statements, and methods for
   an atom that emits `streams`. `typed-payloads` maps stream-name →
   inferred payload [{:name :type-kw :c-type} ...] (from
   infer-stream-payload-types). Callers wire the returned pieces into
   the class's :properties, cxc_structuralSetup body, and :methods
   list."
  [source-class stream-order typed-payloads]
  (if (empty? stream-order)
    {:properties [] :init-stmts [] :methods []}
    (let [props     (mapv stream-subscribers-prop stream-order)
          init-stmts (mapv (fn [nm]
                             {:node :hashtable-init
                              :ivar (stream-subscribers-ivar-name nm)})
                           stream-order)
          emit-methods
          (mapv (fn [nm]
                  (let [payload (get typed-payloads nm [])
                        keys    (mapv :name payload)
                        c-types (mapv :c-type payload)
                        method-params (mapv (fn [p] {:name (:name p)
                                                     :c-type (:c-type p)})
                                            payload)]
                    {:node        :objc-method
                     :selector    (stream-emit-selector nm keys)
                     :return-type "void"
                     :params      method-params
                     ;; Layer 5b Step 6b: fanout dispatches to subscribers
                     ;; with `sender: self` prepended to the user payload.
                     ;; :arg-names / :arg-c-types match `stream-selector`
                     ;; (which now also prepends "sender").
                     :body        [{:node        :stream-fanout
                                    :ivar        (stream-subscribers-ivar-name nm)
                                    :selector    (stream-selector source-class nm keys)
                                    :arg-names   (into ["sender"] keys)
                                    :arg-c-types (into ["id"] c-types)
                                    :prepend-self? true}]}))
                stream-order)
          per-stream-add
          (mapv (fn [nm]
                  {:node        :objc-method
                   :selector    (str "cxc_addSubscriberFor_" nm ":")
                   :return-type "void"
                   :params      [{:name "sub" :c-type "id"}]
                   :body        [{:node :stream-add-sub
                                  :ivar (stream-subscribers-ivar-name nm)}]})
                stream-order)
          per-stream-remove
          ;; Layer 4b: nullable-slot dynamic subscription. When a slot
          ;; is reassigned, the setter unsubs from the old source. Sends
          ;; are nil-message-safe (Cocoa), so callers pass whatever's in
          ;; the slot without pre-checking.
          (mapv (fn [nm]
                  {:node        :objc-method
                   :selector    (str "cxc_removeSubscriberFor_" nm ":")
                   :return-type "void"
                   :params      [{:name "sub" :c-type "id"}]
                   :body        [{:node :stream-remove-sub
                                  :ivar (stream-subscribers-ivar-name nm)}]})
                stream-order)
          generic-add
          {:node        :objc-method
           :selector    "cxc_addSubscriberToStream:sub:"
           :return-type "void"
           :params      [{:name "streamName" :c-type "NSString *"}
                         {:name "sub"        :c-type "id"}]
           :body        [{:node     :stream-add-sub-dispatch
                          :branches (mapv (fn [nm]
                                            {:stream-name nm
                                             :selector    (str "cxc_addSubscriberFor_"
                                                               nm ":")})
                                          stream-order)}]}
          generic-remove
          {:node        :objc-method
           :selector    "cxc_removeSubscriberFromStream:sub:"
           :return-type "void"
           :params      [{:name "streamName" :c-type "NSString *"}
                         {:name "sub"        :c-type "id"}]
           :body        [{:node     :stream-add-sub-dispatch
                          :branches (mapv (fn [nm]
                                            {:stream-name nm
                                             :selector    (str "cxc_removeSubscriberFor_"
                                                               nm ":")})
                                          stream-order)}]}]
      {:properties props
       :init-stmts init-stmts
       :methods    (-> emit-methods
                       (into per-stream-add)
                       (into per-stream-remove)
                       (conj generic-add generic-remove))})))
