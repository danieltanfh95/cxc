(ns cxc.lower.seq-container
  "hickey-cycle-307a — `_Cxc_SeqOf<T>` universal-factory container
   synthesis.

   `Seq of T` is the pivot container for inter-container conversion
   and composable transformers. Backed by `NSMutableArray *` (strict
   / eager — no laziness, no NSEnumerator machinery). Shares element
   boxing with Set + Map (NSNumber for Int, NSString pass-through for
   String, NSNumber via @(e) for Bool).

   Methods synthesized per instantiation:
     - `-count` — element count (int64_t)
     - `-cxc_for_each:(void(^)(id))block` — iterate (parallel to List)
     - `-mapWithfn:(id(^)(id))fn` — transform each (same-type at
       307a; cross-type co-synth deferred to 307b)
     - `-filterWithpred:(BOOL(^)(id))pred` — keep matching
     - `-flat_mapWithfn:(id(^)(id))fn` — transform + flatten
       (same-type only at 307a)
     - `-to_list` — materialize to `_Cxc_ListOf<T>`
     - `-to_set` — materialize to `_Cxc_SetOf<T>` (only when T is
       Set-supported)
     - `-sum` — Int-typed fold, `Seq of Int` only (307a MVP
       substitute for the general `.reduce(init, combine)` which
       needs multi-param-lambda parser support — 307b)

   Cross-type `.map` / `.flat_map` where output element type U ≠ T
   require the U-typed `_Cxc_SeqOf<U>` class to be instantiated at
   the same time. That co-synth plumbing is deferred to 307b. For
   307a, these methods are same-type only — a mismatched lambda
   return type fails at clang. See AGENTS.md for the design log.

   Shared prefix with map/set: `map-c-type` / `map-box-expr` from
   `cxc.lower.map-set-containers` cover Seq's supported element set
   (Int / String)."
  (:require [cxc.diagnostics :refer [fail-user!]]
            [cxc.lower.map-set-containers :as map-set
             :refer [set-supported-primitives]]))

(set! *warn-on-reflection* true)

(def seq-supported-element-types
  "Element types whose synthesized `_Cxc_SeqOf<T>` class ships at
   307a MVP. Conservative subset chosen to match Set's supported
   primitive element types — guarantees `.to_set()` is always valid
   on every Seq. Extending to Bool / records is 307b work (needs
   lift / unbox discipline for `.to_list()` round-trip). POD-record
   Set elements landed cycle 297 but Seq's co-synth doesn't yet
   walk the record-env to confirm each is Set-eligible, so we hold
   at the primitive subset for 307a."
  set-supported-primitives)

(defn- supported?
  "True when the Seq element-type instantiation is MVP-eligible."
  [element-name]
  (contains? seq-supported-element-types element-name))

(defn- validate-element-type! [element-name]
  (when-not (supported? element-name)
    (fail-user! (str "Seq element type `" element-name "` — "
                     "hickey-cycle-307a MVP supports "
                     (sort seq-supported-element-types) ". "
                     "Extending the supported set is 307b work.")
                {:seq-element element-name})))

(def ^:private count-method-node
  "Shared `-count` method node; mirrors the Set/Map synth."
  {:name        "count"
   :selector    "count"
   :params      []
   :return-type "int64_t"
   :body        [{:node :return
                  :value {:node :objc-cast
                          :c-type "int64_t"
                          :expr  {:node :objc-msg
                                  :recv {:node :name :sym "_elements"}
                                  :selector "count"
                                  :args []}}}]})

(defn- for-each-method-node
  "`-cxc_for_each:` — same shape as List's synthesized method so
   the lambda-dispatch path (`lower-for-each-call`) works
   unmodified on Seq receivers."
  []
  {:name        "cxc_for_each"
   :selector    "cxc_for_each:"
   :params      [{:name "block" :c-type "void (^)(id)"}]
   :return-type "void"
   :body        [{:node :raw-c
                  :text "for (id _cxc_e in _elements) { block(_cxc_e); }"}]})

(defn- filter-method-node
  "`-filterWithpred:` — iterate, collect matches into a fresh buf,
   wrap in a new Seq of the same T. Shares selector name with List
   so the lambda-dispatch path (`lower-filter-call`) reuses the
   emit shape without a Seq-specific branch."
  [class-name]
  {:name        "filterWithpred"
   :selector    "filterWithpred:"
   :params      [{:name "pred" :c-type "BOOL (^)(id)"}]
   :return-type (str class-name " *")
   :body        [{:node :raw-c
                  :text (str "NSMutableArray *__buf = [NSMutableArray array]; "
                             "for (id __e in _elements) { "
                             "if (pred(__e)) [__buf addObject:__e]; } "
                             class-name " *__r = [[" class-name " alloc] "
                             "initWithelements:__buf]; return __r;")}]})

(defn- map-method-node
  "`-mapWithfn:` — apply the id-returning block to every element,
   build a new Seq. Same-type at 307a: the lambda must return the
   element's boxed form (NSNumber for Int, NSString for String,
   NSNumber for Bool). Cross-type U lands at 307b when the U-typed
   Seq class can be co-synthesized at instantiation time."
  [class-name]
  {:name        "mapWithfn"
   :selector    "mapWithfn:"
   :params      [{:name "fn" :c-type "id (^)(id)"}]
   :return-type (str class-name " *")
   :body        [{:node :raw-c
                  :text (str "NSMutableArray *__buf = "
                             "[NSMutableArray arrayWithCapacity:[_elements count]]; "
                             "for (id __e in _elements) { "
                             "id __v = fn(__e); "
                             "if (__v) [__buf addObject:__v]; } "
                             class-name " *__r = [[" class-name " alloc] "
                             "initWithelements:__buf]; return __r;")}]})

(defn- flat-map-method-node
  "`-flat_mapWithfn:` — apply the Seq-returning block, concatenate
   the result sequences. Same-type only at 307a.

   Receives `id(^)(id)` so the block type is uniform with
   `.mapWithfn:`; the dispatcher wraps the return in a dynamic
   downcast to the Seq's own class at clang time."
  [class-name]
  {:name        "flat_mapWithfn"
   :selector    "flat_mapWithfn:"
   :params      [{:name "fn" :c-type "id (^)(id)"}]
   :return-type (str class-name " *")
   :body        [{:node :raw-c
                  :text (str "NSMutableArray *__buf = [NSMutableArray array]; "
                             "for (id __e in _elements) { "
                             "id __r = fn(__e); "
                             "if (__r) { "
                             class-name " *__s = (" class-name " *)__r; "
                             "[__buf addObjectsFromArray:__s->_elements]; "
                             "} } "
                             class-name " *__out = [[" class-name " alloc] "
                             "initWithelements:__buf]; return __out;")}]})

(defn- to-list-method-node
  "`-to_list` — materialize to `_Cxc_ListOf<T>`. Both backing stores
   are `NSMutableArray *` so `initWithelements:` copies the array
   content as-is; the list ctor's `arrayWithArray:` makes a fresh
   mutable buffer so the caller cannot mutate our backing via the
   returned list."
  [list-class-name]
  {:name        "to_list"
   :selector    "to_list"
   :params      []
   :return-type (str list-class-name " *")
   :body        [{:node :raw-c
                  :text (str "return [[" list-class-name " alloc] "
                             "initWithelements:_elements];")}]})

(defn- to-set-method-node
  "`-to_set` — materialize to `_Cxc_SetOf<T>`. Set's public
   `initWithelements:(NSArray *)` ctor (hickey-cycle-297b) seeds
   the mutable set from the Seq's backing array. NSMutableSet
   dedups via `-isEqual:` (correct for NSNumber / NSString /
   NSValue element kinds)."
  [set-class-name]
  {:name        "to_set"
   :selector    "to_set"
   :params      []
   :return-type (str set-class-name " *")
   :body        [{:node :raw-c
                  :text (str "return [[" set-class-name " alloc] "
                             "initWithelements:_elements];")}]})

(defn- sum-method-node
  "`-sum` — Int-typed sum for `Seq of Int`. 307a substitute for
   the general `.reduce(init, combine)` which needs multi-param
   lambda support (Corvid's parser recognises only single-param
   `x => body` and zero-param `() => body` at 2026-11-25).

   Shipped on Seq of Int only; emitted on other element types
   as a no-op (-0) by the compile-time guard on the method entry.

   The general `.reduce(init: U, combine: (U, T) -> U) :- U` is
   307b work — needs either multi-param lambda parsing or a
   curried `init => e => body` shape."
  []
  {:name        "sum"
   :selector    "sum"
   :params      []
   :return-type "int64_t"
   :body        [{:node :raw-c
                  :text (str "int64_t __acc = 0; "
                             "for (id __e in _elements) { "
                             "__acc += [(NSNumber *)__e longLongValue]; } "
                             "return __acc;")}]})

(defn synthesize-seq-container-class
  "Emit-ready `:objc-class` node for one `_Cxc_SeqOf<T>`
   instantiation. Mirrors `synthesize-set-container-class` in
   shape — initWithelements: copies the input NSArray into a
   fresh mutable buffer; `_elements` ivar carries the backing.

   Opts:
     :list-class-name — the mangled List class for `.to_list()`.
                        Threaded by the caller so we don't have
                        to rely on the mangle recipe here.
     :set-class-name  — the mangled Set class for `.to_set()`."
  [class-name element-name & {:keys [list-class-name set-class-name]}]
  (validate-element-type! element-name)
  {:node        :objc-class
   :name        class-name
   :superclass  "NSObject"
   :phase-constants []
   :properties  [{:name  "elements"
                  :c-type "NSMutableArray *"
                  :attrs ["nonatomic" "strong"]}]
   :init-params [{:name "elements" :c-type "NSArray *"}]
   :init-body   [{:node :assign
                  :lhs  {:node :name :sym "_elements"}
                  :rhs  {:node :objc-msg
                         :recv {:node :name :sym "NSMutableArray"}
                         :selector "arrayWithArray:"
                         :args [{:node :name :sym "elements"}]}}]
   :methods     (cond-> [count-method-node
                         (for-each-method-node)
                         (filter-method-node   class-name)
                         (map-method-node      class-name)
                         (flat-map-method-node class-name)
                         (to-list-method-node  list-class-name)
                         (to-set-method-node   set-class-name)]
                  ;; `.sum` on Seq of Int only (Int-typed fold MVP;
                  ;; general reduce waits on multi-param lambdas in
                  ;; 307b). String + records fall through.
                  (= element-name "Int")
                  (conj (sum-method-node)))})
