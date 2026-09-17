(ns cxc.lower.map-set-containers
  "Layer 6 Steps 8-9 — Map / Set container class synthesis.

   MVP restricts K / V / element types to Int and String; those two are
   the only types with a compile-time boxing recipe (NSNumber for Int,
   NSString pass-through for String). Anything else fails-user with a
   targeted diagnostic naming the accepted set.

   No fan-in machinery (parallels List's Step 3 → Step 6 arc — deferred
   until value atoms need observable aggregation).

   Called from `synthesize-container-entries` in the top-level `lower`
   orchestrator, one per instantiation the atom-env scan surfaces."
  (:require [cxc.diagnostics :refer [fail-user!]]
            [cxc.mangle :refer [->c-safe]]))

(def map-supported-key-types #{"String" "Int"})
(def map-supported-val-types #{"String" "Int" "Bool"})
(def set-supported-element-types #{"String" "Int"})

(defn map-box-expr
  "Cxc-typed expr → NSDictionary-safe ObjC expr. Int wraps as NSNumber
   `@(v)`; String is ALREADY NSString * per F65 §3.10.5, no wrapping
   needed; Bool wraps as NSNumber via `@(v)` (BOOL is a primitive ObjC
   truth type, boxed as NSNumber with numberWithBool)."
  [ty raw-name]
  (case ty
    "Int"    (str "@(" raw-name ")")
    "String" raw-name
    "Bool"   (str "@(" raw-name ")")))

(defn map-c-type
  "C-level parameter type for a supported K or V map type."
  [ty]
  (case ty
    "Int"    "int"
    "String" "NSString *"                   ; F65 §3.10.5
    "Bool"   "BOOL"))

(defn synthesize-map-container-class
  "Emit-ready :objc-class node for one `_Cxc_MapOf<K><V>` instantiation.
   Contents:
     - `NSMutableDictionary *_elements` ivar (allocated empty at init)
     - `-count` observable
     - `-addWithk:v:` — box K/V, `[_elements setObject:box(v) forKey:box(k)]`
     - `-removeWithk:` — box K, `[_elements removeObjectForKey:box(k)]`
     - `-containsWithk:` — box K, returns 1/0
   No fan-in machinery at Step 8 MVP (deferred until value atoms need
   observable aggregation; parallels List's Step 3 → Step 6 arc)."
  [class-name k-name v-name]
  (when-not (contains? map-supported-key-types k-name)
    (fail-user! (str "Map key type `" k-name "` — Layer 6 Step 8 MVP "
                     "supports " (sort map-supported-key-types))
                {:map-key k-name}))
  (when-not (contains? map-supported-val-types v-name)
    (fail-user! (str "Map value type `" v-name "` — Layer 6 Step 8 MVP "
                     "supports " (sort map-supported-val-types))
                {:map-val v-name}))
  (let [k-c   (map-c-type k-name)
        v-c   (map-c-type v-name)
        box-k (map-box-expr k-name "k")
        box-v (map-box-expr v-name "v")]
    {:node        :objc-class
     :name        class-name
     :superclass  "NSObject"
     :phase-constants []
     :properties  [{:name  "elements"
                    :c-type "NSMutableDictionary *"
                    :attrs ["nonatomic" "strong"]}]
     :init-params []
     :init-body   [{:node :assign
                    :lhs  {:node :name :sym "_elements"}
                    :rhs  {:node :objc-msg
                           :recv {:node :name :sym "NSMutableDictionary"}
                           :selector "dictionary"
                           :args []}}]
     :methods     [{:name        "count"
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
                   ;; 2026-09-16 — imperative `.add(k, v)` / `.remove(k)`
                   ;; retired. Users compose via `m => m.assoc(k, v)` and
                   ;; `m => m.dissoc(k)` at a field site inside a
                   ;; transition. See [[corvid-no-imperative-container-mutation]].
                   ;;
                   ;; A5-mirror — immutable assoc: allocates a fresh
                   ;; _Cxc_MapOfKV instance, copies _elements into its
                   ;; ivar via addEntriesFromDictionary:, sets the new
                   ;; entry on the copy, returns the new instance.
                   ;; Original map untouched.
                   {:name        "assocWithk_v"
                    :selector    "assocWithk:v:"
                    :params      [{:name "k" :c-type k-c}
                                  {:name "v" :c-type v-c}]
                    :return-type (str class-name " *")
                    :body        [{:node :raw-c
                                   :text (str class-name " *__r = "
                                              "[[" class-name " alloc] init]; "
                                              "[__r->_elements addEntriesFromDictionary:_elements]; "
                                              "[__r->_elements setObject:" box-v
                                              " forKey:" box-k "]; "
                                              "return __r;")}]}
                   ;; A5-mirror — immutable dissoc: same copy-and-modify
                   ;; shape as assoc, removes the key on the copy.
                   {:name        "dissocWithk"
                    :selector    "dissocWithk:"
                    :params      [{:name "k" :c-type k-c}]
                    :return-type (str class-name " *")
                    :body        [{:node :raw-c
                                   :text (str class-name " *__r = "
                                              "[[" class-name " alloc] init]; "
                                              "[__r->_elements addEntriesFromDictionary:_elements]; "
                                              "[__r->_elements removeObjectForKey:"
                                              box-k "]; "
                                              "return __r;")}]}
                   {:name        "containsWithk"
                    :selector    "containsWithk:"
                    :params      [{:name "k" :c-type k-c}]
                    :return-type "int"
                    :body        [{:node :raw-c
                                   :text (str "return ([_elements objectForKey:"
                                              box-k "] != nil) ? 1 : 0;")}]}
                   ;; 2026-09-16 — `.get(k) :- V` (nullable-V) replaces
                   ;; the retired `.get_or(k, fallback)`. Callers coalesce
                   ;; via `m.get(k) ?? fallback` — one nil-punned shape
                   ;; across all container reads. Envelope construction
                   ;; mirrors List `.at(i)`: NSNumber / NSString unbox
                   ;; into the `_Cxc_Optional_<V>` `.value` slot.
                   {:name        "getWithk"
                    :selector    "getWithk:"
                    :params      [{:name "k" :c-type k-c}]
                    :return-type (str "_Cxc_Optional_"
                                      (->c-safe (if (= "Int" v-name) "Int64" v-name)))
                    :body        [{:node :raw-c
                                   :text (str "id __v = [_elements objectForKey:" box-k "]; "
                                              "if (__v == nil) return (_Cxc_Optional_"
                                              (->c-safe (if (= "Int" v-name) "Int64" v-name))
                                              "){.present = 0}; "
                                              "return (_Cxc_Optional_"
                                              (->c-safe (if (= "Int" v-name) "Int64" v-name))
                                              "){.present = 1, .value = "
                                              (case v-name
                                                "Int"    "[(NSNumber *)__v longLongValue]"
                                                "String" "(NSString *)__v"
                                                "Bool"   "[(NSNumber *)__v boolValue]")
                                              "};")}]}]}))

(defn synthesize-set-container-class
  "Emit-ready :objc-class node for one `_Cxc_SetOf<T>` instantiation.
   Contents:
     - `NSMutableSet *_elements` ivar (allocated empty at init)
     - `-count` observable
     - `-addWithe:` — box element, `[_elements addObject:box(e)]`
     - `-removeWithe:` — box element, `[_elements removeObject:box(e)]`
     - `-containsWithe:` — box element, `[_elements containsObject:box(e)] ? 1 : 0`
   Element params carry the per-element C type (Int → int, String →
   NSString *) so the caller passes an unboxed value — mirrors Map's
   k-c/v-c specialization. No fan-in machinery at Step 9 MVP (parallels
   Map's landing — deferred until element atoms with observable
   aggregation need it)."
  [class-name element-name]
  (when-not (contains? set-supported-element-types element-name)
    (fail-user! (str "Set element type `" element-name "` — Layer 6 Step 9 "
                     "MVP supports " (sort set-supported-element-types))
                {:set-element element-name}))
  (let [box-e (map-box-expr element-name "e")
        e-c   (map-c-type   element-name)]
    {:node        :objc-class
     :name        class-name
     :superclass  "NSObject"
     :phase-constants []
     :properties  [{:name  "elements"
                    :c-type "NSMutableSet *"
                    :attrs ["nonatomic" "strong"]}]
     :init-params []
     :init-body   [{:node :assign
                    :lhs  {:node :name :sym "_elements"}
                    :rhs  {:node :objc-msg
                           :recv {:node :name :sym "NSMutableSet"}
                           :selector "set"
                           :args []}}]
     :methods     [{:name        "count"
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
                   ;; 2026-09-16 — imperative `.add(e)` / `.remove(e)`
                   ;; retired for Set (parallels Map + List retirement).
                   ;; Users compose via `s => s.insert(e)` / `s => s.without(e)`.
                   ;;
                   ;; A5-mirror — immutable insert: allocates a fresh
                   ;; _Cxc_SetOfT instance, copies _elements via
                   ;; unionSet:, adds the element to the copy, returns
                   ;; the new instance. Original set untouched.
                   {:name        "insertWithe"
                    :selector    "insertWithe:"
                    :params      [{:name "e" :c-type e-c}]
                    :return-type (str class-name " *")
                    :body        [{:node :raw-c
                                   :text (str class-name " *__r = "
                                              "[[" class-name " alloc] init]; "
                                              "[__r->_elements unionSet:_elements]; "
                                              "[__r->_elements addObject:" box-e "]; "
                                              "return __r;")}]}
                   ;; A5-mirror — immutable without: same copy-and-modify
                   ;; shape as insert, removes the element on the copy.
                   {:name        "withoutWithe"
                    :selector    "withoutWithe:"
                    :params      [{:name "e" :c-type e-c}]
                    :return-type (str class-name " *")
                    :body        [{:node :raw-c
                                   :text (str class-name " *__r = "
                                              "[[" class-name " alloc] init]; "
                                              "[__r->_elements unionSet:_elements]; "
                                              "[__r->_elements removeObject:" box-e "]; "
                                              "return __r;")}]}
                   {:name        "containsWithe"
                    :selector    "containsWithe:"
                    :params      [{:name "e" :c-type e-c}]
                    :return-type "int"
                    :body        [{:node :raw-c
                                   :text (str "return [_elements containsObject:"
                                              box-e "] ? 1 : 0;")}]}]}))
