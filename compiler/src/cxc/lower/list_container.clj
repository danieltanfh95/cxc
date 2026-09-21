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
            [cxc.mangle :refer [->c-safe]]
            [cxc.type-kw :refer [optional-type-name]]
            [cxc.lower.selectors :refer [stream-source-methods
                                         stream-selector
                                         stream-emit-selector]]))

(def list-at-or-supported-element-types
  "Element types whose synthesized _Cxc_ListOfT class ships an
   `-at_orWithi:fallback:` random-access method. Constrained to
   the primitives with a well-defined unbox recipe (matches map's
   get_or supported-val-types)."
  #{"Int" "String" "Bool"})

;; 2026-09-16 — `.at(i) :- T` support: primitives via NSNumber
;; unbox, String via id-cast, records via NSValue-copy into a
;; stack local. Everything else (Bytes, atoms) fails to synth
;; the method with a clear diagnostic — extend when a slice needs
;; those element kinds at .at.
(def list-at-supported-primitives
  #{"Int" "Int8" "Int16" "Int32" "Int64"
    "UInt8" "UInt16" "UInt32" "UInt64"
    "Float" "Double"
    "Bool" "String"})

(defn- list-at-envelope-c-type
  "The `_Cxc_Optional_<T>` envelope C name for the element type."
  [ty]
  (str "_Cxc_Optional_" (->c-safe (if (= "Int" ty) "Int64" ty))))

(defn- list-at-return-c-type
  "C return type for `.at(i)`. Atoms return raw pointers (nullable by
   nature); records + primitives use the Optional envelope."
  [element-name is-atom?]
  (if is-atom?
    (str (->c-safe element-name) " *")
    (list-at-envelope-c-type element-name)))

(defn- list-at-primitive-unbox
  "NSNumber / NSString unpack expression for a primitive element type."
  [ty raw-name]
  (case ty
    "String"                  (str "(NSString *)" raw-name)
    "Bool"                    (str "[" raw-name " boolValue]")
    ("Float")                 (str "[" raw-name " floatValue]")
    ("Double")                (str "[" raw-name " doubleValue]")
    ("Int8")                  (str "[" raw-name " charValue]")
    ("Int16")                 (str "[" raw-name " shortValue]")
    ("Int32")                 (str "[" raw-name " intValue]")
    ("Int" "Int64")           (str "[" raw-name " longLongValue]")
    ("UInt8")                 (str "[" raw-name " unsignedCharValue]")
    ("UInt16")                (str "[" raw-name " unsignedShortValue]")
    ("UInt32")                (str "[" raw-name " unsignedIntValue]")
    ("UInt64")                (str "[" raw-name " unsignedLongLongValue]")))

(defn- list-at-body
  "Method body for `.at(i)` synthesized on `_Cxc_ListOf<T>`. Bounds
   check first (OOB → nil envelope for value-typed, nil for atoms).
   Primitives + String unbox from NSNumber/NSString; records copy
   bytes out of NSValue into a stack local; atoms cast the id
   directly (element identity preserved). Element types outside
   these categories are skipped at synthesis time via
   `list-at-eligible?`."
  [element-name is-record? is-atom?]
  (let [env-ty (list-at-envelope-c-type element-name)
        oob    (str "if (i < 0 || i >= (int)[_elements count]) return ("
                    env-ty "){.present = 0}; ")]
    (cond
      is-atom?
      (str "if (i < 0 || i >= (int)[_elements count]) return nil; "
           "return (" (->c-safe element-name) " *)"
           "[_elements objectAtIndex:i];")

      is-record?
      (str oob
           element-name " __v; "
           "[(NSValue *)[_elements objectAtIndex:i] getValue:&__v "
           "size:sizeof(" element-name ")]; "
           "return (" env-ty "){.present = 1, .value = __v};")

      (contains? list-at-supported-primitives element-name)
      (str oob
           "id __raw = [_elements objectAtIndex:i]; "
           "return (" env-ty "){.present = 1, .value = "
           (list-at-primitive-unbox element-name "__raw") "};"))))

(defn list-at-eligible?
  "True when `.at(i)` can be synthesized on `List of <element-name>`.
   Records + primitives + atoms are eligible; other kinds (Bytes,
   tagged enums) are skipped at MVP — extend when a slice needs them."
  [element-name is-record? is-atom?]
  (or is-record?
      is-atom?
      (contains? list-at-supported-primitives element-name)))

(defn- list-at-or-c-type
  "C-level parameter type for a supported at_or element type."
  [ty]
  (case ty
    "Int"    "int"
    "String" "NSString *"
    "Bool"   "BOOL"))

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
  [class-name element-name & {:keys [fanin-streams fanin-payloads is-record? is-atom?]}]
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
              fanin-streams)]
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
                         ;; 2026-09-16 — user-facing `addWithe:` /
                         ;; `removeWithe:` retired. See the container-
                         ;; entries note for the model rationale (bypass
                         ;; of `=>` transitions). Users now compose via
                         ;; `xs => xs.append(e)` and `xs => xs.filter(...)`.
                         ;; The internal `add-body` / `remove-body`
                         ;; scaffolding (fan-in subscribe/unsubscribe) is
                         ;; still built above for the init-time auto-
                         ;; subscribe loop, but no selector exposes it.
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
                                                    ;; Dynamic-append fan-in: `initWithelements:`
                                                    ;; only subscribes elements present at ctor
                                                    ;; time, so the newly-appended `e` needs its
                                                    ;; own subscribe calls. Mirrors the ctor
                                                    ;; loop's per-stream subscribe list.
                                                    (str/join " "
                                                              (map (fn [nm]
                                                                     (str "[e cxc_addSubscriberFor_"
                                                                          nm ":__r];"))
                                                                   fanin-streams))
                                                    " return __r;")}]}
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
                                                    "return __r;")}]}
                         ;; 2026-09-20 — fresh empty `List of T`. Instance
                         ;; method (receiver carries T); returns a new
                         ;; container backed by an empty NSMutableArray.
                         ;; Ignores _elements — replaces the retired
                         ;; `xs.filter(x => false)` idiom.
                         {:name        "empty"
                          :selector    "empty"
                          :params      []
                          :return-type (str class-name " *")
                          :body        [{:node :raw-c
                                         :text (str "return [[" class-name " alloc] "
                                                    "initWithelements:"
                                                    "[NSMutableArray array]];")}]}
                         ;; 2026-09-17 — immutable sort by Int key.
                         ;; Wraps the user's key-fn (int64_t (^)(id)) in
                         ;; an NSComparator that computes keys per pair
                         ;; and returns ascending / descending / same.
                         ;; NSMutableArray sortUsingComparator is stable.
                         ;; Two key-fn calls per compare (n log n × 2);
                         ;; game-sized lists (dozens) — non-issue.
                         {:name        "sort_byWithkeyfn"
                          :selector    "sort_byWithkeyfn:"
                          :params      [{:name "keyfn" :c-type "int64_t (^)(id)"}]
                          :return-type (str class-name " *")
                          :body        [{:node :raw-c
                                         :text (str "NSMutableArray *__buf = [NSMutableArray "
                                                    "arrayWithArray:_elements]; "
                                                    "[__buf sortUsingComparator:"
                                                    "^NSComparisonResult(id __a, id __b) { "
                                                    "int64_t __ka = keyfn(__a); "
                                                    "int64_t __kb = keyfn(__b); "
                                                    "if (__ka < __kb) return NSOrderedAscending; "
                                                    "if (__ka > __kb) return NSOrderedDescending; "
                                                    "return NSOrderedSame; }]; "
                                                    class-name " *__r = "
                                                    "[[" class-name " alloc] "
                                                    "initWithelements:__buf]; "
                                                    "return __r;")}]}]
                        (cond-> (into (vec forwarding-methods)
                                      (:methods stream-parts))
                          ;; 2026-09-16 — `.at(i) :- T` (nullable T).
                          ;; Universally synthesized when the element
                          ;; type has an unbox recipe (primitives, String,
                          ;; records). Complements the raw-scalar `at_or`
                          ;; convenience for primitives.
                          (list-at-eligible? element-name is-record? is-atom?)
                          (conj {:name        "atWithi"
                                 :selector    "atWithi:"
                                 :params      [{:name "i" :c-type "int"}]
                                 :return-type (list-at-return-c-type element-name is-atom?)
                                 :body        [{:node :raw-c
                                                :text (list-at-body element-name is-record? is-atom?)}]})
                          ;; 2026-09-16 — `.at_or(i, fallback)` retired
                          ;; in favor of `xs.at(i) ?? fallback`. `.contains`
                          ;; stays: it's a pure query, not imperative
                          ;; mutation.
                          (contains? list-at-or-supported-element-types element-name)
                          (conj {:name        "containsWithe"
                                 :selector    "containsWithe:"
                                 :params      [{:name "e" :c-type (list-at-or-c-type element-name)}]
                                 :return-type "int"
                                 :body        [{:node :raw-c
                                                :text (list-contains-box-expr element-name)}]})))
     :element-name element-name}))
