(ns cxc.lower.list-container
  "Layer 5/6 — `_Cxc_ListOf<T>` container class synthesis.

   Emits one `:objc-class` node per element-type instantiation.
   Contents: elements NSMutableArray + init + count / add / remove /
   for-each methods, plus (Layer 4b) fan-in stream-source machinery so
   the container itself emits streams aggregated from element-side
   streams.

   Called from the top-level orchestrator's list-container-classes
   pass. Callee shape (opts: fanin-streams / fanin-payloads) carries
   the per-fan-in-stream data from the element's own `:stream-order`
   / `:stream-payload-index` in atom-env."
  (:require [clojure.string :as str]
            [cxc.lower.selectors :refer [stream-source-methods
                                         stream-selector
                                         stream-emit-selector]]))

(def list-at-or-supported-element-types
  "Element types whose synthesized _Cxc_ListOfT class ships an
   `-at_orWithi:fallback:` random-access method. Constrained to
   the primitives with a well-defined unbox recipe (matches map's
   get_or supported-val-types)."
  #{"Int" "String" "Bool"})

(defn- list-at-or-c-type
  "C-level parameter type for a supported at_or element type."
  [ty]
  (case ty
    "Int"    "int"
    "String" "NSString *"
    "Bool"   "BOOL"))

(defn- list-at-or-unbox-expr
  "Body of the -at_orWithi:fallback: method: bounds-check + typed
   unbox of the id result, returning fallback on out-of-range."
  [ty]
  (str "if (i < 0 || i >= (int)[_elements count]) return fallback; "
       "id __v = [_elements objectAtIndex:i]; "
       (case ty
         "Int"    "return __v ? [(NSNumber *)__v intValue] : fallback;"
         "String" "return __v ? (NSString *)__v : fallback;"
         "Bool"   "return __v ? [(NSNumber *)__v boolValue] : fallback;")))

(defn- list-contains-box-expr
  "Body of the -containsWithe: method: box e to id and delegate to
   NSArray's -containsObject: (uses -isEqual: for value equality on
   NSNumber/NSString)."
  [ty]
  (case ty
    "Int"    "return [_elements containsObject:@(e)] ? 1 : 0;"
    "String" "return [_elements containsObject:e] ? 1 : 0;"
    "Bool"   "return [_elements containsObject:@(e)] ? 1 : 0;"))

(defn synthesize-list-container-class
  "Emit-ready :objc-class node for one `_Cxc_ListOf<T>` instantiation.
   Contents:
     - `NSMutableArray *_elements` ivar (via a property so the setter
       isn't emitted — we access `_elements` directly)
     - `-initWithElements:(NSArray *)elements` constructor that copies
       the input NSArray into a new NSMutableArray
     - `-count` returning `(int)[_elements count]`
     - `-addWithe:` / `-removeWithe:` — mutation ops (audit F44:
       former `cxc_add:`/`cxc_remove:` selectors renamed to match
       the standard `objc-method-selector` shape so ordinary
       method-call dispatch resolves them). Under Step 6 these
       also (un)subscribe the container to each element-side
       stream so fan-in works.
     - `-cxc_for_each:` — Step 5 iteration.
     - Layer 4b stream-source machinery (per fan-in stream: subscribers
       hashtable ivar + emit method + add/remove-subscriber methods
       + generic dispatch) synthesized from the container's fan-in
       :stream-order (populated by synthesize-list-container-entries
       from the element atom's own stream-order).
     - Per-element-stream forwarding methods
       `-cxc_stream_<name>_from_<Element>...` that receive an event
       and re-emit on the container with `e` prepended to payload."
  [class-name element-name & {:keys [fanin-streams fanin-payloads]}]
  ;; T1 — the caller (list-container-classes site in `lower`) reaches
  ;; `(:stream-order entry)` / `(:stream-payload-index entry)` which
  ;; can be nil for atoms without any streams. Sanitize once at the
  ;; top so every downstream expression sees non-nil defaults —
  ;; `:or` in destructure only fires on ABSENT keys, not on explicit
  ;; nil values, so we cannot hoist it into the argvec.
  (let [fanin-streams  fanin-streams
        fanin-payloads (or fanin-payloads {})
        stream-parts (stream-source-methods class-name
                                            fanin-streams
                                            fanin-payloads)
        forwarding-methods
        (mapv (fn [nm]
                (let [elem-keys   (mapv :name (rest (get fanin-payloads nm [])))
                      elem-params (into [{:name "sender" :c-type "id"}]
                                        (mapv (fn [p] {:name (:name p)
                                                       :c-type (:c-type p)})
                                              (rest (get fanin-payloads nm []))))
                      cont-keys   (into ["e"] elem-keys)]
                  {:node        :objc-method
                   :selector    (stream-selector element-name nm elem-keys)
                   :return-type "void"
                   :params      elem-params
                   :body        [{:node     :objc-msg
                                  :recv     {:node :name :sym "self"}
                                  :selector (stream-emit-selector nm cont-keys)
                                  :args     (into [{:node :name :sym "sender"}]
                                                  (map #(hash-map :node :name :sym %)
                                                       elem-keys))}]}))
              fanin-streams)
        add-body (into
                  [{:node :objc-msg
                    :recv {:node :name :sym "_elements"}
                    :selector "addObject:"
                    :args [{:node :name :sym "e"}]}]
                  (map (fn [nm]
                         {:node :objc-msg
                          :recv {:node :name :sym "e"}
                          :selector (str "cxc_addSubscriberFor_" nm ":")
                          :args [{:node :name :sym "self"}]})
                       fanin-streams))
        remove-body (into
                     (mapv (fn [nm]
                             {:node :objc-msg
                              :recv {:node :name :sym "e"}
                              :selector (str "cxc_removeSubscriberFor_" nm ":")
                              :args [{:node :name :sym "self"}]})
                           fanin-streams)
                     [{:node :objc-msg
                       :recv {:node :name :sym "_elements"}
                       :selector "removeObject:"
                       :args [{:node :name :sym "e"}]}])]
    {:node        :objc-class
     :name        class-name
     :superclass  "NSObject"
     :phase-constants []
     :properties  (into [{:name  "elements"
                          :c-type "NSMutableArray *"
                          :attrs ["nonatomic" "strong"]}
                         {:name  "own_queue"
                          :c-type "dispatch_queue_t"
                          :attrs ["nonatomic" "strong"]}]
                        (:properties stream-parts))
     :init-params [{:name "elements" :c-type "NSArray *"}]
     :init-body   (-> [{:node :assign
                        :lhs  {:node :name :sym "_elements"}
                        :rhs  {:node :objc-msg
                               :recv {:node :name :sym "NSMutableArray"}
                               :selector "arrayWithArray:"
                               :args [{:node :name :sym "elements"}]}}
                       {:node :queue-init :ivar "_own_queue"}]
                      ;; Auto-subscribe container to each element's
                      ;; fan-in stream for the initial literal load.
                      (cond-> (seq fanin-streams)
                        (conj {:node :raw-c
                               :text (str "for (id _cxc_ie in _elements) { "
                                          (str/join " "
                                                    (map (fn [nm]
                                                           (str "[_cxc_ie cxc_addSubscriberFor_"
                                                                nm ":self];"))
                                                         fanin-streams))
                                          " }")}))
                      (into (:init-stmts stream-parts)))
     :methods     (into [{:name        "count"
                          :selector    "count"
                          :params      []
                          :return-type "int"
                          :body        [{:node :return
                                         :value {:node :objc-cast
                                                 :c-type "int"
                                                 :expr  {:node :objc-msg
                                                         :recv {:node :name :sym "_elements"}
                                                         :selector "count"
                                                         :args []}}}]}
                         {:name        "addWithe"
                          :selector    "addWithe:"
                          :params      [{:name "e" :c-type "id"}]
                          :return-type "void"
                          :body        add-body}
                         {:name        "removeWithe"
                          :selector    "removeWithe:"
                          :params      [{:name "e" :c-type "id"}]
                          :return-type "void"
                          :body        remove-body}
                         {:name        "cxc_for_each"
                          :selector    "cxc_for_each:"
                          :params      [{:name "block" :c-type "void (^)(id)"}]
                          :return-type "void"
                          :body        [{:node :raw-c
                                         :text "for (id _cxc_e in _elements) { block(_cxc_e); }"}]}
                         ;; A5 (2026-09-03) — immutable append.
                         ;; Constructs a new _Cxc_ListOfT via
                         ;; initWithelements: (which -arrayWithArray:
                         ;; copies the source), then adds e on the copy
                         ;; and returns the new instance. Caller-side
                         ;; boxing wraps primitives before the id param
                         ;; sees them (mirrors existing addWithe: shape).
                         {:name        "appendWithe"
                          :selector    "appendWithe:"
                          :params      [{:name "e" :c-type "id"}]
                          :return-type (str class-name " *")
                          :body        [{:node :raw-c
                                         :text (str class-name " *__r = "
                                                    "[[" class-name " alloc] "
                                                    "initWithelements:_elements]; "
                                                    "[__r->_elements addObject:e]; "
                                                    "return __r;")}]}
                         ;; A5 — immutable filter. Iterates _elements,
                         ;; applies the pred block, collects matches
                         ;; into a fresh NSMutableArray, wraps in a new
                         ;; _Cxc_ListOfT and returns it. Original list
                         ;; is untouched.
                         {:name        "filterWithpred"
                          :selector    "filterWithpred:"
                          :params      [{:name "pred" :c-type "BOOL (^)(id)"}]
                          :return-type (str class-name " *")
                          :body        [{:node :raw-c
                                         :text (str "NSMutableArray *__buf = [NSMutableArray array]; "
                                                    "for (id __e in _elements) { "
                                                    "if (pred(__e)) [__buf addObject:__e]; } "
                                                    class-name " *__r = "
                                                    "[[" class-name " alloc] "
                                                    "initWithelements:__buf]; "
                                                    "return __r;")}]}]
                        (cond-> (into (vec forwarding-methods)
                                      (:methods stream-parts))
                          (contains? list-at-or-supported-element-types element-name)
                          (conj {:name        "at_orWithi_fallback"
                                 :selector    "at_orWithi:fallback:"
                                 :params      [{:name "i"        :c-type "int"}
                                               {:name "fallback" :c-type (list-at-or-c-type element-name)}]
                                 :return-type (list-at-or-c-type element-name)
                                 :body        [{:node :raw-c
                                                :text (list-at-or-unbox-expr element-name)}]}
                                {:name        "containsWithe"
                                 :selector    "containsWithe:"
                                 :params      [{:name "e" :c-type (list-at-or-c-type element-name)}]
                                 :return-type "int"
                                 :body        [{:node :raw-c
                                                :text (list-contains-box-expr element-name)}]})))
     :element-name element-name}))
