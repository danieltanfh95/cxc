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
            [cxc.mangle :refer [->c-safe c-safe-id]]
            [cxc.type-kw :refer [mangled-optional-type-name]]
            [cxc.lower.selectors :refer [stream-source-methods
                                         stream-selector
                                         stream-emit-selector]]))

(set! *warn-on-reflection* true)

(def list-at-or-supported-element-types
  "Element types whose synthesized _Cxc_ListOfT class ships an
   `-at_orWithi:fallback:` random-access method. Constrained to
   the primitives with a well-defined unbox recipe (matches map's
   get_or supported-val-types)."
  #{"Int" "String" "Bool"})

;; 2026-10-04 — Linux cloud port. Mirror of cxc.emit/linux? so synthesized
;; container classes also fall back to `(nonatomic, assign)` on their
;; dispatch_queue_t own_queue property. See the long comment in cxc.emit
;; for why strong dispatch properties segfault on Linux's libobjc2 stack.
(defn- linux? []
  (let [os-name (System/getProperty "os.name")]
    (and os-name (.startsWith (.toLowerCase os-name) "linux"))))

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

(defn- wrap-buf-into-new-list-c
  "C snippet: construct a new `class-name` wrapping the `__buf`
   NSMutableArray local and return it. Shared tail of filter / sort /
   concat / slice."
  [class-name]
  (str class-name " *__r = [[" class-name " alloc] "
       "initWithelements:__buf]; return __r;"))

(defn- list-at-envelope-c-type
  "The `_Cxc_Optional_<T>` envelope C name for the element type."
  [ty]
  (mangled-optional-type-name ty))

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
   `list-at-eligible?`.

   Chapter-16 fix for record elements with reference-typed fields
   (NSString, container pointers, atom pointers): `[nsvalue
   getValue:&__v]` memcpy's bytes into a stack local, bypassing
   ARC's retain hooks on strong-typed fields. ARC's scope-end
   destructor then over-releases the fields, which crashes once a
   field value escapes NSTaggedPointerString's inline encoding
   (10+ char idents of mixed content). Fix: declare the stack
   local as a plain byte buffer (unretained), then re-materialize
   each field via ARC-visible stores into the typed Token, so
   __strong fields are retained normally. See `design/ten-char-
   ident-segfault.md` for the hypothesis trail.

   Tagged enums (2026-10-22) are NSValue-boxed like records, but
   payload fields live in a `data` union — the ARC-safe shadow-
   struct re-materialisation doesn't apply uniformly, so the
   whole enum copies as a value (NSValue → stack-local T) and
   returns the envelope with the full struct inside. Enum payload
   fields with reference types will need the same ARC strategy as
   records if the ten-char-ident segfault class resurfaces for
   enum lists; defer until it bites."
  [element-name is-record? is-atom? is-enum? record-fields]
  (let [env-ty (list-at-envelope-c-type element-name)
        oob    (str "if (i < 0 || i >= (int64_t)[_elements count]) return ("
                    env-ty "){.present = 0}; ")]
    (cond
      is-atom?
      (str "if (i < 0 || i >= (int64_t)[_elements count]) return nil; "
           "return (" (->c-safe element-name) " *)"
           "[_elements objectAtIndex:i];")

      is-record?
      (let [ref-fields (filter (fn [f] (str/includes? (:c-type f "") "*"))
                               (or record-fields []))
            ;; Pointer-shaped shadow struct so ARC does NOT emit
            ;; destructors for the raw bytes.
            raw-struct-fields (str/join " "
                                        (map (fn [f]
                                               (if (str/includes? (:c-type f "") "*")
                                                 (str (:c-type f) " __unsafe_unretained "
                                                      (:name f) "; ")
                                                 (str (:c-type f) " " (:name f) "; ")))
                                             (or record-fields [])))
            field-copy (str/join " "
                                 (map (fn [f] (str "__v." (:name f) " = __raw." (:name f) ";"))
                                      (or record-fields [])))]
        (if (seq ref-fields)
          (str oob
               "struct __cxc_raw { " raw-struct-fields "} __raw; "
               "[(NSValue *)[_elements objectAtIndex:i] getValue:&__raw "
               "size:sizeof(__raw)]; "
               element-name " __v = {0}; "
               field-copy " "
               "return (" env-ty "){.present = 1, .value = __v};")
          (str oob
               element-name " __v; "
               "[(NSValue *)[_elements objectAtIndex:i] getValue:&__v "
               "size:sizeof(" element-name ")]; "
               "return (" env-ty "){.present = 1, .value = __v};")))

      is-enum?
      (str oob
           (->c-safe element-name) " __v; "
           "[(NSValue *)[_elements objectAtIndex:i] getValue:&__v "
           "size:sizeof(" (->c-safe element-name) ")]; "
           "return (" env-ty "){.present = 1, .value = __v};")

      (contains? list-at-supported-primitives element-name)
      (str oob
           "id __raw = [_elements objectAtIndex:i]; "
           "return (" env-ty "){.present = 1, .value = "
           (list-at-primitive-unbox element-name "__raw") "};"))))

(defn list-at-eligible?
  "True when `.at(i)` can be synthesized on `List of <element-name>`.
   Records + primitives + atoms + tagged enums (2026-10-22) are
   eligible; other kinds (Bytes) are skipped at MVP — extend when
   a slice needs them."
  [element-name is-record? is-atom? is-enum?]
  (or is-record?
      is-atom?
      is-enum?
      (contains? list-at-supported-primitives element-name)))

(defn- list-at-or-c-type
  "C-level parameter type for a supported at_or element type.
   Int is int64_t to match Corvid's 64-bit Int. A 32-bit `int` here
   silently truncated the `.contains(needle)` selector arg at values
   >= 2^31. Same bug class as the 2026-10-01 map-c-type fix."
  [ty]
  (case ty
    "Int"    "int64_t"
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
     - `-count` returning `(int64_t)[_elements count]`
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
  [class-name element-name & {:keys [fanin-streams fanin-payloads is-record? is-atom? is-enum? record-fields]}]
  ;; T1 — the caller (list-container-classes site in `lower`) reaches
  ;; `(:stream-order entry)` / `(:stream-payload-index entry)` which
  ;; can be nil for atoms without any streams. Sanitize once at the
  ;; top so every downstream expression sees non-nil defaults —
  ;; `:or` in destructure only fires on ABSENT keys, not on explicit
  ;; nil values, so we cannot hoist it into the argvec.
  (let [fanin-payloads (or fanin-payloads {})
        stream-parts (stream-source-methods class-name
                                            fanin-streams
                                            fanin-payloads)
        forwarding-methods
        (mapv (fn [nm]
                (let [elem-keys   (mapv :name (rest (get fanin-payloads nm [])))
                      elem-params (into [{:name "sender" :c-type "id"}]
                                        (mapv (fn [p] {:name (c-safe-id (:name p))
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
                                                  (map #(hash-map :node :name :sym (c-safe-id %))
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
                          :attrs (if (linux?)
                                   ["nonatomic" "assign"]
                                   ["nonatomic" "strong"])}]
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
                          :return-type "int64_t"
                          :body        [{:node :return
                                         :value {:node :objc-cast
                                                 :c-type "int64_t"
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
                                                    (wrap-buf-into-new-list-c class-name))}]}
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
                                                    (wrap-buf-into-new-list-c class-name))}]}
                         ;; 2026-10-01 — `.concat(other)` returns a new
                         ;; List containing self's elements followed by
                         ;; other's elements. Both must share element
                         ;; type (compiler enforces via method dispatch).
                         {:name        "concatWithother"
                          :selector    "concatWithother:"
                          :params      [{:name "other" :c-type (str class-name " *")}]
                          :return-type (str class-name " *")
                          :body        [{:node :raw-c
                                         :text (str "NSMutableArray *__buf = "
                                                    "[NSMutableArray arrayWithArray:_elements]; "
                                                    "if (other) [__buf addObjectsFromArray:"
                                                    "[other elements]]; "
                                                    (wrap-buf-into-new-list-c class-name))}]}
                         ;; 2026-10-01 — `.slice(start, end)` returns a new
                         ;; List with elements in [start, end). End is
                         ;; exclusive. Out-of-bound ranges are clamped to
                         ;; [0, count]; a reversed/empty range yields an
                         ;; empty List. Matches Python / Rust slice.
                         {:name        "sliceWithstart_end"
                          :selector    "sliceWithstart:end:"
                          :params      [{:name "start" :c-type "int64_t"}
                                        {:name "end"   :c-type "int64_t"}]
                          :return-type (str class-name " *")
                          :body        [{:node :raw-c
                                         :text (str "int64_t __n = (int64_t)[_elements count]; "
                                                    "int64_t __s = start < 0 ? 0 : (start > __n ? __n : start); "
                                                    "int64_t __e = end < 0 ? 0 : (end > __n ? __n : end); "
                                                    "if (__e < __s) __e = __s; "
                                                    "NSArray *__sub = [_elements "
                                                    "subarrayWithRange:NSMakeRange((NSUInteger)__s, "
                                                    "(NSUInteger)(__e - __s))]; "
                                                    "NSMutableArray *__buf = "
                                                    "[NSMutableArray arrayWithArray:__sub]; "
                                                    (wrap-buf-into-new-list-c class-name))}]}]
                        (cond-> (into (vec forwarding-methods)
                                      (:methods stream-parts))
                          ;; 2026-09-16 — `.at(i) :- T` (nullable T).
                          ;; Universally synthesized when the element
                          ;; type has an unbox recipe (primitives, String,
                          ;; records). Complements the raw-scalar `at_or`
                          ;; convenience for primitives.
                          (list-at-eligible? element-name is-record? is-atom? is-enum?)
                          (conj {:name        "atWithi"
                                 :selector    "atWithi:"
                                 :params      [{:name "i" :c-type "int64_t"}]
                                 :return-type (list-at-return-c-type element-name is-atom?)
                                 :body        [{:node :raw-c
                                                :text (list-at-body element-name is-record? is-atom? is-enum? record-fields)}]})
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
                                                :text (list-contains-box-expr element-name)}]})
                          ;; 2026-10-21 — `.join(sep: String!) :- String!`
                          ;; on `List of String`. Delegates to NSArray's
                          ;; componentsJoinedByString:. One linear pass
                          ;; through the elements, avoids the quadratic
                          ;; cost of accumulating `xs => xs + part` across
                          ;; transitions. See container_entries.clj for
                          ;; the method-index registration.
                          (= element-name "String")
                          (conj {:name        "joinWithsep"
                                 :selector    "joinWithsep:"
                                 :params      [{:name "sep" :c-type "NSString *"}]
                                 :return-type "NSString *"
                                 :body        [{:node :raw-c
                                                :text "return [_elements componentsJoinedByString:sep];"}]})
                          ;; hickey-cycle-294 — `.map(x => body)` and
                          ;; `.map_indexed((v, i) => body)` on `List of Int`.
                          ;; Both closes spec drift at §5.6.1 line 7578
                          ;; where `array.map(x => x + 1)` is shown as a
                          ;; working example. Scope at this cycle: Int
                          ;; element type only, so the boxing path stays
                          ;; a single `@(fn(__e))` form instead of
                          ;; element-type-aware forking. Other element
                          ;; types land in follow-on cycles.
                          (= element-name "Int")
                          (conj {:name        "mapWithfn"
                                 :selector    "mapWithfn:"
                                 :params      [{:name "fn" :c-type "int64_t (^)(id)"}]
                                 :return-type (str class-name " *")
                                 :body        [{:node :raw-c
                                                :text (str "NSMutableArray *__buf = "
                                                           "[NSMutableArray array]; "
                                                           "for (id __e in _elements) { "
                                                           "[__buf addObject:@(fn(__e))]; } "
                                                           (wrap-buf-into-new-list-c class-name))}]})
                          (= element-name "Int")
                          (conj {:name        "map_indexedWithfn"
                                 :selector    "map_indexedWithfn:"
                                 :params      [{:name "fn" :c-type "int64_t (^)(id, NSUInteger)"}]
                                 :return-type (str class-name " *")
                                 :body        [{:node :raw-c
                                                :text (str "NSMutableArray *__buf = "
                                                           "[NSMutableArray array]; "
                                                           "[_elements enumerateObjectsUsingBlock:"
                                                           "^(id __e, NSUInteger __i, BOOL *__stop) { "
                                                           "[__buf addObject:@(fn(__e, __i))]; }]; "
                                                           (wrap-buf-into-new-list-c class-name))}]})
                          ;; hickey-cycle-295 — cross-type `.map` for
                          ;; `List of Int → List of String`. Block returns
                          ;; `NSString *` directly (reference type, no
                          ;; NSNumber boxing). The output list class is
                          ;; `_Cxc_ListOfString`, not `class-name`, since
                          ;; the receiver is `_Cxc_ListOfInt` and the
                          ;; element type flips. `_Cxc_ListOfString` is
                          ;; eagerly co-synthesized whenever `List of Int`
                          ;; is reachable — see
                          ;; container_entries.clj's
                          ;; `list-instantiations-from-atom-env` for the
                          ;; co-synth trigger.
                          (= element-name "Int")
                          (conj {:name        "mapStringWithfn"
                                 :selector    "mapStringWithfn:"
                                 :params      [{:name "fn" :c-type "NSString * (^)(id)"}]
                                 :return-type "_Cxc_ListOfString *"
                                 :body        [{:node :raw-c
                                                :text (str "NSMutableArray *__buf = "
                                                           "[NSMutableArray array]; "
                                                           "for (id __e in _elements) { "
                                                           "NSString *__s = fn(__e); "
                                                           "[__buf addObject:(__s ? __s : @\"\")]; } "
                                                           (wrap-buf-into-new-list-c "_Cxc_ListOfString"))}]})
                          ;; hickey-cycle-296 — `.map(fn)` and
                          ;; `.map_indexed(fn)` on `List of Bool`.
                          ;; Blocks return BOOL (ObjC's native 0/1
                          ;; truthy); boxing goes through `@(BOOL)`
                          ;; which produces NSNumber numberWithBool:.
                          ;; Mirrors the Int-container surface.
                          (= element-name "Bool")
                          (conj {:name        "mapWithfn"
                                 :selector    "mapWithfn:"
                                 :params      [{:name "fn" :c-type "BOOL (^)(id)"}]
                                 :return-type (str class-name " *")
                                 :body        [{:node :raw-c
                                                :text (str "NSMutableArray *__buf = "
                                                           "[NSMutableArray array]; "
                                                           "for (id __e in _elements) { "
                                                           "[__buf addObject:@(fn(__e))]; } "
                                                           (wrap-buf-into-new-list-c class-name))}]})
                          (= element-name "Bool")
                          (conj {:name        "map_indexedWithfn"
                                 :selector    "map_indexedWithfn:"
                                 :params      [{:name "fn" :c-type "BOOL (^)(id, NSUInteger)"}]
                                 :return-type (str class-name " *")
                                 :body        [{:node :raw-c
                                                :text (str "NSMutableArray *__buf = "
                                                           "[NSMutableArray array]; "
                                                           "[_elements enumerateObjectsUsingBlock:"
                                                           "^(id __e, NSUInteger __i, BOOL *__stop) { "
                                                           "[__buf addObject:@(fn(__e, __i))]; }]; "
                                                           (wrap-buf-into-new-list-c class-name))}]})
                          ;; hickey-cycle-296 — cross-type `.map` for
                          ;; `List of Bool → List of String`. Row-render
                          ;; shape used by game-of-life's `row_at` once
                          ;; cells are stored as Bool. Same body as the
                          ;; Int version; only the element unbox differs
                          ;; (the block's `id` param is `@YES`/`@NO`
                          ;; boxed by the receiver — unbox via
                          ;; `boolValue` at the call-site prelude).
                          (= element-name "Bool")
                          (conj {:name        "mapStringWithfn"
                                 :selector    "mapStringWithfn:"
                                 :params      [{:name "fn" :c-type "NSString * (^)(id)"}]
                                 :return-type "_Cxc_ListOfString *"
                                 :body        [{:node :raw-c
                                                :text (str "NSMutableArray *__buf = "
                                                           "[NSMutableArray array]; "
                                                           "for (id __e in _elements) { "
                                                           "NSString *__s = fn(__e); "
                                                           "[__buf addObject:(__s ? __s : @\"\")]; } "
                                                           (wrap-buf-into-new-list-c "_Cxc_ListOfString"))}]})))}))
