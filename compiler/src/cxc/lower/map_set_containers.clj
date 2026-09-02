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
  (:require [cxc.diagnostics :refer [fail-user!]]))

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
                   {:name        "addWithk_v"
                    :selector    "addWithk:v:"
                    :params      [{:name "k" :c-type k-c}
                                  {:name "v" :c-type v-c}]
                    :return-type "void"
                    :body        [{:node :raw-c
                                   :text (str "[_elements setObject:" box-v
                                              " forKey:" box-k "];")}]}
                   {:name        "removeWithk"
                    :selector    "removeWithk:"
                    :params      [{:name "k" :c-type k-c}]
                    :return-type "void"
                    :body        [{:node :raw-c
                                   :text (str "[_elements removeObjectForKey:"
                                              box-k "];")}]}
                   {:name        "containsWithk"
                    :selector    "containsWithk:"
                    :params      [{:name "k" :c-type k-c}]
                    :return-type "int"
                    :body        [{:node :raw-c
                                   :text (str "return ([_elements objectForKey:"
                                              box-k "] != nil) ? 1 : 0;")}]}
                   {:name        "get_orWithk_fallback"
                    :selector    "get_orWithk:fallback:"
                    :params      [{:name "k"        :c-type k-c}
                                  {:name "fallback" :c-type v-c}]
                    :return-type v-c
                    :body        [{:node :raw-c
                                   :text (str "id __v = [_elements objectForKey:" box-k "]; "
                                              (case v-name
                                                "Int"    "return __v ? [(NSNumber *)__v intValue] : fallback;"
                                                "String" "return __v ? (NSString *)__v : fallback;"
                                                "Bool"   "return __v ? [(NSNumber *)__v boolValue] : fallback;"))}]}]}))

(defn synthesize-set-container-class
  "Emit-ready :objc-class node for one `_Cxc_SetOf<T>` instantiation.
   Contents:
     - `NSMutableSet *_elements` ivar (allocated empty at init)
     - `-count` observable
     - `-addWithe:` — box element, `[_elements addObject:box(e)]`
     - `-removeWithe:` — box element, `[_elements removeObject:box(e)]`
     - `-containsWithe:` — box element, `[_elements containsObject:box(e)] ? 1 : 0`
   No fan-in machinery at Step 9 MVP (parallels Map's landing —
   deferred until element atoms with observable aggregation need it)."
  [class-name element-name]
  (when-not (contains? set-supported-element-types element-name)
    (fail-user! (str "Set element type `" element-name "` — Layer 6 Step 9 "
                     "MVP supports " (sort set-supported-element-types))
                {:set-element element-name}))
  (let [box-e (map-box-expr element-name "e")]
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
                   {:name        "addWithe"
                    :selector    "addWithe:"
                    :params      [{:name "e" :c-type "id"}]
                    :return-type "void"
                    :body        [{:node :raw-c
                                   :text (str "[_elements addObject:" box-e "];")}]}
                   {:name        "removeWithe"
                    :selector    "removeWithe:"
                    :params      [{:name "e" :c-type "id"}]
                    :return-type "void"
                    :body        [{:node :raw-c
                                   :text (str "[_elements removeObject:" box-e "];")}]}
                   {:name        "containsWithe"
                    :selector    "containsWithe:"
                    :params      [{:name "e" :c-type "id"}]
                    :return-type "int"
                    :body        [{:node :raw-c
                                   :text (str "return [_elements containsObject:"
                                              box-e "] ? 1 : 0;")}]}]}))
