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
            [cxc.type-kw :refer [mangled-optional-type-name]]))

(set! *warn-on-reflection* true)

(def map-supported-key-types #{"String" "Int"})
(def map-supported-val-types #{"String" "Int" "Bool"})
(def set-supported-primitives #{"String" "Int"})

(defn set-supported?
  "True when `element-name` can serve as a Set element at this cycle.
   Primitives (Int, String) have hand-crafted box recipes; records are
   accepted when all their fields are primitives (POD) so NSValue byte-
   equality + content-hash yield correct Set semantics. hickey-cycle-297
   (2026-11-23) opens the record path; mixed-pointer-and-value records
   land in a follow-on cycle alongside a proper <Record>_isEqual:/hash
   wrapper class."
  [element-name record-env]
  (or (contains? set-supported-primitives element-name)
      (when-let [rec (get record-env element-name)]
        (every? (fn [f]
                  (contains? #{"Int" "Int8" "Int16" "Int32" "Int64"
                               "UInt8" "UInt16" "UInt32" "UInt64"
                               "Float" "Double" "Bool"}
                             (:cxc-ty f)))
                (:fields rec)))))

(defn map-box-expr
  "Cxc-typed expr → NSDictionary-safe ObjC expr. Int wraps as NSNumber
   `@(v)`; String is ALREADY NSString * per F65 §3.10.5, no wrapping
   needed; Bool wraps as NSNumber via `@(v)` (BOOL is a primitive ObjC
   truth type, boxed as NSNumber with numberWithBool).

   hickey-cycle-297 — POD records box via `[NSValue valueWithBytes:&v
   objCType:@encode(<Record>)]`. NSValue's `-isEqual:` byte-compares
   wrapped POD per Apple's documented contract; `-hash` content-hashes
   the same bytes. Sufficient for Set membership + NSMutableSet dedup."
  [ty raw-name record-env]
  (cond
    (= "Int"    ty) (str "@(" raw-name ")")
    (= "String" ty) raw-name
    (= "Bool"   ty) (str "@(" raw-name ")")
    (contains? record-env ty)
    (str "[NSValue valueWithBytes:&" raw-name " objCType:@encode(" ty ")]")
    :else
    (throw (ex-info (str "map-box-expr: unsupported type " ty) {:ty ty}))))

(defn map-c-type
  "C-level parameter type for a supported K or V map type.
   2026-10-01 — Int is int64_t, not int. Corvid Int! is 64-bit; the read
   side already uses longLongValue (line ~150). The write side was passing
   values through `int` selector params, truncating anything > 2^31 silently
   (e.g. 5_000_000_000 → 705_032_704). Found during MT19937 debug on
   opus-v26p3 (opus self-interrogation 2026-10-01).

   hickey-cycle-297 — records pass by value as the plain struct name
   (`Cell`); the method signature receives a copy and takes `&e` for
   the NSValue box, so the caller's original isn't aliased."
  [ty record-env]
  (cond
    (= "Int"    ty) "int64_t"
    (= "String" ty) "NSString *"              ; F65 §3.10.5
    (= "Bool"   ty) "BOOL"
    (contains? record-env ty) ty
    :else
    (throw (ex-info (str "map-c-type: unsupported type " ty) {:ty ty}))))

(defn- copy-and-modify-c
  "C snippet: allocate a fresh container, copy _elements via
   `copy-selector`, apply the `mutation` statement, return the new
   instance. Shared by Map assoc/dissoc and Set insert/without.
   `init-call` is the alloc+init expression — Map uses the bare
   `init` form, Set uses `initWithelements:@[]` since hickey-
   cycle-297b flipped Set's init to take elements."
  [class-name init-call copy-selector mutation]
  (str class-name " *__r = " init-call "; "
       "[__r->_elements " copy-selector ":_elements]; "
       mutation " "
       "return __r;"))

(def ^:private count-method-node
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

(defn- empty-method-node
  "The `.empty()` factory on a container returns a fresh empty
   instance. `init-call` is the alloc+init expression — Map uses the
   bare `init` form; Set uses `initWithelements:@[]` after
   hickey-cycle-297b flipped Set's init to take elements."
  [class-name init-call]
  {:name        "empty"
   :selector    "empty"
   :params      []
   :return-type (str class-name " *")
   :body        [{:node :raw-c
                  :text (str "return " init-call ";")}]})

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
  [class-name k-name v-name record-env]
  (when-not (contains? map-supported-key-types k-name)
    (fail-user! (str "Map key type `" k-name "` — Layer 6 Step 8 MVP "
                     "supports " (sort map-supported-key-types))
                {:map-key k-name}))
  (when-not (contains? map-supported-val-types v-name)
    (fail-user! (str "Map value type `" v-name "` — Layer 6 Step 8 MVP "
                     "supports " (sort map-supported-val-types))
                {:map-val v-name}))
  (let [k-c   (map-c-type k-name record-env)
        v-c   (map-c-type v-name record-env)
        box-k (map-box-expr k-name "k" record-env)
        box-v (map-box-expr v-name "v" record-env)]
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
     :methods     [count-method-node
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
                                   :text (copy-and-modify-c
                                          class-name
                                          (str "[[" class-name " alloc] init]")
                                          "addEntriesFromDictionary"
                                          (str "[__r->_elements setObject:" box-v
                                               " forKey:" box-k "];"))}]}
                   ;; A5-mirror — immutable dissoc: same copy-and-modify
                   ;; shape as assoc, removes the key on the copy.
                   {:name        "dissocWithk"
                    :selector    "dissocWithk:"
                    :params      [{:name "k" :c-type k-c}]
                    :return-type (str class-name " *")
                    :body        [{:node :raw-c
                                   :text (copy-and-modify-c
                                          class-name
                                          (str "[[" class-name " alloc] init]")
                                          "addEntriesFromDictionary"
                                          (str "[__r->_elements removeObjectForKey:"
                                               box-k "];"))}]}
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
                   (let [opt-ty (mangled-optional-type-name v-name)]
                     {:name        "getWithk"
                      :selector    "getWithk:"
                      :params      [{:name "k" :c-type k-c}]
                      :return-type opt-ty
                      :body        [{:node :raw-c
                                     :text (str "id __v = [_elements objectForKey:" box-k "]; "
                                                "if (__v == nil) return (" opt-ty
                                                "){.present = 0}; "
                                                "return (" opt-ty
                                                "){.present = 1, .value = "
                                                (case v-name
                                                  "Int"    "[(NSNumber *)__v longLongValue]"
                                                  "String" "(NSString *)__v"
                                                  "Bool"   "[(NSNumber *)__v boolValue]")
                                                "};")}]})
                   ;; 2026-10-22 — `.empty()` factory, parallel to List's.
                   ;; Fresh empty `Map of (K, V)`; receiver carries K/V
                   ;; so caller composes `m => m.empty()` to reset at
                   ;; lifecycle boundaries without re-stating types.
                   (empty-method-node class-name
                                      (str "[[" class-name " alloc] init]"))]}))

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
  [class-name element-name record-env]
  (when-not (set-supported? element-name record-env)
    (fail-user! (str "Set element type `" element-name "` — Layer 6 Step 9 "
                     "MVP supports Int, String, and POD records "
                     "(records with only primitive numeric / Bool fields). "
                     "hickey-cycle-297 (2026-11-23) added the record path; "
                     "records containing String or nested records land in "
                     "a follow-on cycle alongside a synthesized "
                     "`<Record>_isEqual:`/`-hash` wrapper class.")
                {:set-element element-name}))
  (let [box-e (map-box-expr element-name "e" record-env)
        e-c   (map-c-type   element-name record-env)
        ;; hickey-cycle-307a — `.seq()` lift only when Seq supports
        ;; the element type (primitive subset: Int + String). Record
        ;; elements become Set-eligible in cycle 297 but aren't yet
        ;; Seq-eligible; emitting `-seq` unconditionally would
        ;; reference an undeclared `_Cxc_SeqOf<Record>` class.
        seq-eligible? (contains? #{"Int" "String"} element-name)]
    {:node        :objc-class
     :name        class-name
     :superclass  "NSObject"
     :phase-constants []
     :properties  [{:name  "elements"
                    :c-type "NSMutableSet *"
                    :attrs ["nonatomic" "strong"]}]
     ;; hickey-cycle-297b — Set's init mirrors List's: takes an NSArray
     ;; of pre-boxed elements and seeds the mutable set from it. Field
     ;; defaults (`alive_cells: Set of Cell = [Cell(1,1), ...]`) route
     ;; through `initWithelements:@[boxed...]`; the no-default path
     ;; passes `@[]`. Keeps the construction shape symmetric with List
     ;; and Map (both own their init args).
     :init-params [{:name "elements" :c-type "NSArray *"}]
     :init-body   [{:node :assign
                    :lhs  {:node :name :sym "_elements"}
                    :rhs  {:node :objc-msg
                           :recv {:node :name :sym "NSMutableSet"}
                           :selector "setWithArray:"
                           :args [{:node :name :sym "elements"}]}}]
     :methods     (cond-> [count-method-node
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
                                   :text (copy-and-modify-c
                                          class-name
                                          (str "[[" class-name " alloc] initWithelements:@[]]")
                                          "unionSet"
                                          (str "[__r->_elements addObject:" box-e "];"))}]}
                   ;; A5-mirror — immutable without: same copy-and-modify
                   ;; shape as insert, removes the element on the copy.
                   {:name        "withoutWithe"
                    :selector    "withoutWithe:"
                    :params      [{:name "e" :c-type e-c}]
                    :return-type (str class-name " *")
                    :body        [{:node :raw-c
                                   :text (copy-and-modify-c
                                          class-name
                                          (str "[[" class-name " alloc] initWithelements:@[]]")
                                          "unionSet"
                                          (str "[__r->_elements removeObject:" box-e "];"))}]}
                   {:name        "containsWithe"
                    :selector    "containsWithe:"
                    :params      [{:name "e" :c-type e-c}]
                    :return-type "int"
                    :body        [{:node :raw-c
                                   :text (str "return [_elements containsObject:"
                                              box-e "] ? 1 : 0;")}]}
                   ;; 2026-10-22 — `.empty()` factory, parallel to List
                   ;; and Map. Fresh empty `Set of T` on the same type
                   ;; parameter; caller composes `s => s.empty()`.
                   (empty-method-node class-name
                                      (str "[[" class-name " alloc] initWithelements:@[]]"))
                   ;; hickey-cycle-297b — `-filterWithpred:` mirrors
                   ;; List's filter: build a fresh buffer, enumerate
                   ;; the underlying NSMutableSet, call pred on each
                   ;; (boxed) element, keep those where pred returns
                   ;; YES. Rehydrates into a new _Cxc_SetOf<T> via the
                   ;; init-with-array path so the output is a bona fide
                   ;; Set (with dedup, per NSMutableSet semantics —
                   ;; the input has no duplicates so this is a pass-
                   ;; through).
                   {:name        "filterWithpred"
                    :selector    "filterWithpred:"
                    :params      [{:name "pred" :c-type "BOOL (^)(id)"}]
                    :return-type (str class-name " *")
                    :body        [{:node :raw-c
                                   :text (str "NSMutableArray *__buf = "
                                              "[NSMutableArray array]; "
                                              "for (id __e in _elements) { "
                                              "if (pred(__e)) [__buf addObject:__e]; } "
                                              "return [[" class-name " alloc] "
                                              "initWithelements:__buf];")}]}
                   ]
                  ;; hickey-cycle-307a (2026-11-25) — `.seq()` lifts
                  ;; the Set into a `_Cxc_SeqOf<T>` for composable
                  ;; transformers and container conversion. Gated by
                  ;; `seq-eligible?` so record-elements (now
                  ;; Set-eligible per cycle 297) don't emit a method
                  ;; referencing an undeclared `_Cxc_SeqOf<Record>`.
                  seq-eligible?
                  (conj {:name        "seq"
                         :selector    "seq"
                         :params      []
                         :return-type (str "_Cxc_SeqOf" element-name " *")
                         :body        [{:node :raw-c
                                        :text (str "return [[_Cxc_SeqOf" element-name
                                                   " alloc] initWithelements:[_elements allObjects]];")}]}))}))
