(ns cxc.lower
  "Lower cxc AST to ObjC/C AST.

   ObjC/C AST vocabulary (Clojure data — a subset of what real ObjC allows,
   grown feature by feature alongside the parser):

     {:node :objc-program
      :includes  [\"<stdio.h>\"] or [\"<Foundation/Foundation.h>\"]
      :structs   [ ... :c-struct ... ]        ; C typedef structs (records)
      :functions [ ... :c-function ... ]      ; C free functions (record methods)
      :classes   [ ... :objc-class ... ]      ; ObjC classes (def-atoms)
      :main      {:body [ ... :stmt ... ]
                  :autorelease? <bool>}}      ; wrap body in @autoreleasepool

     {:node :c-struct
      :name    \"V\"
      :fields  [ {:name \"x\" :c-type \"int\"} ... ]}

     {:node :c-function
      :name        \"V_plus\"
      :return-type \"V\"
      :params      [ {:name \"self\" :c-type \"V\"} ... ]
      :body        [ ... :stmt ... ]}

     {:node :objc-class
      :name        \"Counter\"
      :superclass  \"NSObject\"
      :properties  [ {:name \"n\" :c-type \"int\" :attrs [\"assign\"]} ... ]
      :init-body   [ ... :stmt ... ]}

   Statement nodes (in :body vectors):
     {:node :call    :fn \"printf\" :args [ ... :expr ... ]}
     {:node :return  :value <expr>}
     {:node :assign  :lhs <expr> :rhs <expr>}

   Expression nodes:
     {:node :str             :value \"hello\"}
     {:node :int             :value 42}
     {:node :float           :value 3.14}
     {:node :name            :sym \"self\"}
     {:node :binop           :op \"+\" :left <expr> :right <expr>}
     {:node :field           :target <expr> :field \"x\"}
     {:node :struct-lit      :type \"V\" :fields [{:name \"x\" :value <expr>} ...]}
     {:node :objc-alloc-init :class \"Counter\"}
     {:node :call            :fn \"printf\" :args [ ... ]}

   Coverage this file (2026-08-13):
   - Hello-world: `def main: -> running do: print(\"...\")`
   - Arithmetic in print; binops with %d/%f dispatch
   - Records → C typedef structs; construction; field access; defaults
   - Record methods → C functions (`V_plus(self, other)`); method-call
     syntax `v.plus(other)` lowers to `V_plus(v, other)`
   - Def-atoms with `data:` only → ObjC classes (NSObject subclass with
     @property ivars + -init that fills defaults). `X()` → alloc/init

   Not yet: transitions on def-atoms, use: injection, contracts, nested
   slots, actor runtime. Those raise :not-yet-implemented.

   Ctx map threaded through infer-type / lower-expr:
     {:record-env <name -> {:fields :field-index :methods :method-index}>
      :atom-env   <name -> {:fields :field-index :extras}>
      :locals     <name -> ty>       ; local scope for method bodies etc."
  (:require [clojure.string :as str]))

(defn- fail-nyi! [what node]
  (throw (ex-info (str "lowering not yet implemented: " what)
                  {:kind :not-yet-implemented :what what :node node})))

;; ---------------------------------------------------------------------------
;; Type env
;; ---------------------------------------------------------------------------

(def ^:private primitive-c-type
  {"Int"     "int"
   "Float"   "double"
   "String"  "const char *"
   "Bool"    "int"})

(defn- cxc-type->c
  "Primitives → C type name; records → own struct name (value); atoms →
   `<name> *` (pointer, already nullable in ObjC); enums → own typedef
   name (int-backed). `T?` wraps another type — legal today only for
   atoms (their pointer type IS nullable). Nullable primitives/records/
   enums would need Optional wrappers — fail-nyi."
  [ty-node record-env atom-env enum-env]
  (case (:node ty-node)
    :type-nullable
    (let [inner (:inner ty-node)
          nm    (:name inner)]
      (cond
        (contains? atom-env nm) (str nm " *")
        :else (fail-nyi! (str "nullable non-atom type `" nm "?` "
                              "(only atom types are nullable today; "
                              "primitives/records/enums would need an Optional wrapper)")
                         ty-node)))
    :type-ref
    (let [nm (:name ty-node)]
      (cond
        (contains? primitive-c-type nm) (primitive-c-type nm)
        (contains? record-env nm)       nm
        (contains? atom-env nm)         (str nm " *")
        (contains? enum-env nm)         nm
        :else                            (fail-nyi! (str "type " nm) ty-node)))
    (fail-nyi! (str "cxc-type->c: unknown type-node " (:node ty-node)) ty-node)))

(defn- base-type-name
  "Extract the underlying type name from either a :type-ref or a
   :type-nullable wrapper. Used to key the type env regardless of
   nullability, since nullability is orthogonal to which env the type
   lives in."
  [ty-node]
  (case (:node ty-node)
    :type-ref      (:name ty-node)
    :type-nullable (:name (:inner ty-node))
    (fail-nyi! (str "base-type-name: unknown type-node " (:node ty-node)) ty-node)))

(defn- cxc-type->type-kw
  "Map a cxc type-ref name to the type-keyword vocab used by infer-type.
   Primitive names → :int/:float/:string/:bool. Aggregate names →
   {:record N}, {:atom N}, or {:enum N} depending on which env they
   resolve in."
  [cxc-name record-env atom-env enum-env node]
  (case cxc-name
    "Int"    :int
    "Float"  :float
    "String" :string
    "Bool"   :bool
    (cond
      (contains? record-env cxc-name) {:record cxc-name}
      (contains? atom-env   cxc-name) {:atom   cxc-name}
      (contains? enum-env   cxc-name) {:enum   cxc-name}
      :else (fail-nyi! (str "unresolved type name `" cxc-name "`") node))))

(defn- type-kw->c-type
  "Reverse of cxc-type->type-kw for the C emit side. Observable return
   types are inferred as type-kws directly; this converts them back to
   emit-ready C type strings."
  [tk node]
  (cond
    (= :int    tk) "int"
    (= :float  tk) "double"
    (= :string tk) "const char *"
    (= :bool   tk) "int"
    (and (map? tk) (:record tk)) (:record tk)
    (and (map? tk) (:atom   tk)) (str (:atom tk) " *")
    (and (map? tk) (:enum   tk)) (:enum tk)
    :else (fail-nyi! (str "unrepresentable observable type " (pr-str tk))
                     node)))

(defn- fields-from-data-section
  [data-section record-env atom-env enum-env]
  (mapv (fn [f]
          {:name      (:name f)
           :cxc-ty    (base-type-name (:type f))
           :nullable? (= :type-nullable (get-in f [:type :node]))
           :c-type    (cxc-type->c (:type f) record-env atom-env enum-env)
           :default   (:default f)})
        (:fields data-section)))

(defn- normalize-method-param
  "Parser hands us `self` as {:node :param :name \"self\"} and typed params
   as {:node :field :name ... :type ...}. Normalize to {:name :cxc-ty}."
  [enclosing-record-name p]
  (if (= "self" (:name p))
    {:name "self" :cxc-ty enclosing-record-name :is-self? true}
    {:name (:name p) :cxc-ty (:name (:type p)) :is-self? false}))

(defn- extract-methods
  "Return [{:name :params [{:name :cxc-ty :is-self?}] :cxc-return-ty :body}]
   from a record's :methods-section (may be nil)."
  [record-name methods-section]
  (mapv (fn [m]
          {:name          (:name m)
           :params        (mapv #(normalize-method-param record-name %) (:params m))
           :cxc-return-ty (:name (:return-type m))
           :body          (:body m)})
        (:methods methods-section)))

(defn- build-record-env
  [top-nodes atom-env enum-env]
  (let [records  (filter #(= :record (:node %)) top-nodes)
        skeleton (into {} (map (fn [r]
                                 [(:name r) {:fields [] :field-index {}
                                             :methods [] :method-index {}}])
                               records))]
    (reduce
     (fn [env r]
       (let [name    (:name r)
             dsec    (first (filter #(= :data-section    (:node %)) (:sections r)))
             msec    (first (filter #(= :methods-section (:node %)) (:sections r)))
             fields  (fields-from-data-section dsec env atom-env enum-env)
             methods (extract-methods name msec)]
         (assoc env name {:fields       fields
                          :field-index  (into {} (map (juxt :name identity) fields))
                          :methods      methods
                          :method-index (into {} (map (juxt :name identity) methods))})))
     skeleton
     records)))

(defn- def-atom-data-section [atom-node]
  (first (filter #(= :data-section (:node %)) (:body atom-node))))

(defn- ctor-params-normalized
  "Def-atom :ctor-params come from the parser as `:field` nodes
   (`{:node :field :name :type :default}`). Normalize to
   `{:name :cxc-ty :nullable? :c-type :default}` for the atom-env."
  [ctor-params record-env atom-env enum-env]
  (mapv (fn [p]
          {:name      (:name p)
           :cxc-ty    (base-type-name (:type p))
           :nullable? (= :type-nullable (get-in p [:type :node]))
           :c-type    (cxc-type->c (:type p) record-env atom-env enum-env)
           :default   (:default p)})
        ctor-params))

(defn- def-atom-methods-section [atom-node]
  (first (filter #(= :methods-section (:node %)) (:body atom-node))))

(defn- def-atom-transitions [atom-node]
  (filterv #(= :transition (:node %)) (:body atom-node)))

(defn- def-atom-observables [atom-node]
  (filterv #(= :observable (:node %)) (:body atom-node)))

(defn- def-atom-invariants
  "Return the predicate list from the atom's invariants: section (empty
   if absent). Section is unique — parser emits at most one."
  [atom-node]
  (or (:predicates (first (filter #(= :invariants-section (:node %))
                                  (:body atom-node))))
      []))

(defn- def-atom-use-caps
  "Return the capability list from the atom's use: section (empty if
   absent). Each cap is {:name :type}."
  [atom-node]
  (or (:caps (first (filter #(= :use-section (:node %)) (:body atom-node))))
      []))

(defn- def-atom-nested-slots
  "Return the `def <lowercase>: <TypeRef>[(args)]` slots from the atom
   body. Parser tags these as `:nested-atom`."
  [atom-node]
  (filterv #(= :nested-atom (:node %)) (:body atom-node)))

(defn- def-atom-body-extras
  "Everything in the def-atom body that isn't data:/methods:/transition/
   observable/invariants/use:/nested-atom — e.g. contracts: (Layer 6+).
   These are what remains for later layers to consume."
  [atom-node]
  (remove #(#{:data-section :methods-section :transition :observable
              :invariants-section :use-section :nested-atom} (:node %))
          (:body atom-node)))

(defn- build-atom-env
  [top-nodes enum-env]
  (let [atoms    (filter #(and (= :def-atom (:node %))
                               (not= "main" (:name %)))
                         top-nodes)
        skeleton (into {} (map (fn [a] [(:name a) {:fields [] :field-index {}
                                                   :ctor-params [] :methods []
                                                   :method-index {} :observables []
                                                   :invariants [] :transitions []
                                                   :extras []}])
                               atoms))
        atom-names (set (keys skeleton))]
    (reduce
     (fn [env a]
       (let [name          (:name a)
             dsec          (def-atom-data-section a)
             msec          (def-atom-methods-section a)
             transitions   (def-atom-transitions a)
             observables   (def-atom-observables a)
             invariants    (def-atom-invariants a)
             use-caps      (def-atom-use-caps a)
             nested-slots  (def-atom-nested-slots a)
             extras        (def-atom-body-extras a)
             ;; use: caps are sugar for a required ctor-param plus a
             ;; same-named data-field defaulted to that ctor-param.
             ;; Type must be an atom (capability atoms are the intended
             ;; kind); user-declared collisions raise below.
             _             (doseq [c use-caps]
                             (when-not (contains? atom-names (base-type-name (:type c)))
                               (fail-nyi!
                                (str "use: `" (:name c) "` requires an atom type; got `"
                                     (base-type-name (:type c)) "` (Layer 3.1 only "
                                     "supports capability atom types in use: for now)")
                                c)))
             ;; def <lowercase>: <AtomType>[(args)] synthesizes a data
             ;; field whose default is the ctor call. Type must be an
             ;; atom (nested slots hold sub-atoms, not values).
             _             (doseq [s nested-slots]
                             (when-not (contains? atom-names (base-type-name (:type s)))
                               (fail-nyi!
                                (str "nested slot `def " (:name s) "` requires an atom "
                                     "type; got `" (base-type-name (:type s)) "`")
                                s)))
             cap-idents     (mapv (fn [c] {:node :ident :name (:name c)}) use-caps)
             cap-as-params  (mapv (fn [c] {:name (:name c) :type (:type c) :default nil})
                                  use-caps)
             cap-as-fields  (mapv (fn [c id] {:name (:name c) :type (:type c) :default id})
                                  use-caps cap-idents)
             nested-as-fields (mapv
                               (fn [s]
                                 {:name (:name s)
                                  :type (:type s)
                                  :default
                                  {:node :call
                                   :callee {:node :ident :name (base-type-name (:type s))}
                                   :args   (or (:ctor-args s) [])}})
                               nested-slots)
             merged-ctor    (into (vec (:ctor-params a)) cap-as-params)
             merged-fields  (-> (or (:fields dsec) [])
                                (into cap-as-fields)
                                (into nested-as-fields))
             _             (let [n->count (frequencies (map :name merged-ctor))
                                 dupes    (filter #(> (val %) 1) n->count)]
                             (when (seq dupes)
                               (fail-nyi!
                                (str "duplicate ctor-param names on def-atom `" name
                                     "` (likely collision between use: and explicit "
                                     "ctor-params): " (mapv key dupes))
                                {:atom name :dupes (mapv key dupes)})))
             _             (let [n->count (frequencies (map :name merged-fields))
                                 dupes    (filter #(> (val %) 1) n->count)]
                             (when (seq dupes)
                               (fail-nyi!
                                (str "duplicate field names on def-atom `" name
                                     "` (collision between data:/use:/nested-slot): "
                                     (mapv key dupes))
                                {:atom name :dupes (mapv key dupes)})))
             merged-dsec   (assoc (or dsec {:node :data-section}) :fields merged-fields)
             fields        (fields-from-data-section merged-dsec {} env enum-env)
             ctor-params   (ctor-params-normalized merged-ctor {} env enum-env)
             methods       (extract-methods name msec)]
         (assoc env name {:fields       fields
                          :field-index  (into {} (map (juxt :name identity) fields))
                          :ctor-params  ctor-params
                          :methods      methods
                          :method-index (into {} (map (juxt :name identity) methods))
                          :observables  observables
                          :invariants   invariants
                          :transitions  transitions
                          :extras       extras})))
     skeleton
     atoms)))

(defn- build-enum-env
  "Top-level `enum Foo: a, b, c` decls. Env is keyed by enum name;
   value has :cases (vector of case names in decl order) and
   :case-index (name → declaration-order index; used for the C
   integer value in the emitted `Foo_<case>` constants)."
  [top-nodes]
  (into {}
        (for [e (filter #(= :enum (:node %)) top-nodes)]
          [(:name e) {:cases      (:cases e)
                      :case-index (into {} (map-indexed (fn [i c] [c i])
                                                        (:cases e)))}])))

;; ---------------------------------------------------------------------------
;; Type inference (ctx-threaded)
;; ---------------------------------------------------------------------------

(declare infer-type)

(defn- infer-binop-type
  [{:keys [op left right]} ctx]
  (let [lt (infer-type left  ctx)
        rt (infer-type right ctx)]
    (cond
      ;; Comparison ops always return bool regardless of operand types
      ;; (parser accepts nonsense combos; we let clang catch those).
      (#{"==" "!=" "<" "<=" ">" ">="} op) :bool

      ;; Boolean connectives: both sides must be bool (or int since we
      ;; don't have a distinct bool type at the C level yet).
      (#{"and" "or"} op) :bool

      ;; `is` is identity/equality: pointer-equal for atoms, value-equal
      ;; for enums/ints/nil. Returns bool.
      (= "is" op) :bool

      (or (= lt :float) (= rt :float)) :float
      (and (= lt :int) (= rt :int))    :int

      :else (fail-nyi! (str "binop " op " between " lt " and " rt)
                       {:left lt :right rt}))))

(defn- infer-method-call-type
  "For `target.method(args)`: infer target-ty, look up method on the
   record OR atom (falling back to observable-index for atoms), return
   its cxc-return-ty translated to a type-kw. Observables carry
   :type-kw directly."
  [call-node ctx]
  (let [dot         (:callee call-node)
        target-ty   (infer-type (:target dot) ctx)
        method-name (:field dot)]
    (cond
      (and (map? target-ty) (:record target-ty))
      (let [rn (:record target-ty)
            m  (get-in (:record-env ctx) [rn :method-index method-name])]
        (when-not m
          (fail-nyi! (str "method `" method-name "` on record `" rn "`") call-node))
        (cxc-type->type-kw (:cxc-return-ty m)
                           (:record-env ctx) (:atom-env ctx) (:enum-env ctx) call-node))

      (and (map? target-ty) (:atom target-ty))
      (let [an (:atom target-ty)
            m  (get-in (:atom-env ctx) [an :method-index method-name])
            o  (get-in (:atom-env ctx) [an :observable-index method-name])]
        (cond
          m (cxc-type->type-kw (:cxc-return-ty m)
                               (:record-env ctx) (:atom-env ctx) (:enum-env ctx) call-node)
          o (:type-kw o)
          :else (fail-nyi! (str "method/observable `" method-name "` on atom `" an "`")
                           call-node)))

      :else
      (fail-nyi! "method call on non-record/non-atom target" call-node))))

(defn- infer-call-type
  [{:keys [callee] :as node} ctx]
  (case (:node callee)
    :ident
    (let [nm (:name callee)]
      (cond
        (contains? (:record-env ctx) nm) {:record nm}
        (contains? (:atom-env   ctx) nm) {:atom nm}
        (= "print" nm)                   :void
        :else (fail-nyi! (str "call to `" nm "`") node)))
    :dot   (infer-method-call-type node ctx)
    (fail-nyi! (str "call with non-ident/non-dot callee " (:node callee)) node)))

(defn- field-type-for-owner [owner-ty ctx field-name node]
  (cond
    (and (map? owner-ty) (:record owner-ty))
    (let [owner (:record owner-ty)
          f     (get-in (:record-env ctx) [owner :field-index field-name])]
      (when-not f
        (fail-nyi! (str "field `" field-name "` on record `" owner "`") node))
      (cxc-type->type-kw (:cxc-ty f)
                         (:record-env ctx) (:atom-env ctx) (:enum-env ctx) node))

    (and (map? owner-ty) (:atom owner-ty))
    (let [owner (:atom owner-ty)
          f     (get-in (:atom-env ctx) [owner :field-index field-name])
          o     (get-in (:atom-env ctx) [owner :observable-index field-name])]
      (cond
        f (cxc-type->type-kw (:cxc-ty f)
                             (:record-env ctx) (:atom-env ctx) (:enum-env ctx) node)
        ;; Observables read like fields — dot syntax on an ObjC object
        ;; dispatches to our synthesized zero-arg method.
        o (:type-kw o)
        :else
        (fail-nyi! (str "field/observable `" field-name "` on atom `" owner "`") node)))

    :else
    (fail-nyi! "field access on non-aggregate type" node)))

(defn- enum-case-access?
  "A `:dot` whose target is an `:ident` naming an enum, and whose field
   is one of that enum's cases. Not to be confused with instance field
   access on an aggregate."
  [dot-node ctx]
  (let [tgt (:target dot-node)]
    (and (= :ident (:node tgt))
         (contains? (:enum-env ctx) (:name tgt))
         (contains? (get-in ctx [:enum-env (:name tgt) :case-index])
                    (:field dot-node)))))

(defn- infer-type [node ctx]
  (case (:node node)
    :lit-string :string
    :lit-int    :int
    :lit-float  :float
    :lit-nil    :nil
    :lit-int-unit   (fail-nyi! (str "unit-suffixed integer literal `"
                                    (:value node) (:unit node)
                                    "` — needs stdlib type for unit "
                                    "`" (:unit node) "` (Layer 5.4)")
                               node)
    :lit-float-unit (fail-nyi! (str "unit-suffixed float literal `"
                                    (:value node) (:unit node)
                                    "` — needs stdlib type for unit "
                                    "`" (:unit node) "` (Layer 5.4)")
                               node)
    :lambda     (fail-nyi! (str "lambda expression — needs a consumer to "
                                "invoke through (Layer 5 collection methods "
                                "`.map`/`.for_each`; will lower to ObjC blocks)")
                           node)
    :self       (or (get (:locals ctx) "self")
                    (fail-nyi! "type-of `self` outside a method body" node))
    :ident      (or (get (:locals ctx) (:name node))
                    (when-let [f (get (:self-fields ctx) (:name node))]
                      (or (:type-kw f)
                          (cxc-type->type-kw (:cxc-ty f)
                                             (:record-env ctx) (:atom-env ctx)
                                             (:enum-env ctx) node)))
                    (fail-nyi! (str "type-of identifier `" (:name node)
                                    "` (no local-binding env yet)")
                               node))
    :binop      (infer-binop-type node ctx)
    :call       (infer-call-type node ctx)
    :dot        (if (enum-case-access? node ctx)
                  {:enum (get-in node [:target :name])}
                  (field-type-for-owner (infer-type (:target node) ctx)
                                        ctx (:field node) node))
    (fail-nyi! (str "infer-type " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Expression lowering (ctx-threaded)
;; ---------------------------------------------------------------------------

(declare lower-expr lower-stmt)

(defn- lower-record-ctor
  [record-name args ctx]
  (let [rec        (get-in ctx [:record-env record-name])
        by-name    (into {} (keep (fn [a] (when (= :named (:kind a))
                                            [(:name a) (:value a)]))
                                  args))
        positional (filterv #(= :positional (:kind %)) args)
        fields     (mapv (fn [i f]
                           (let [v (or (get by-name (:name f))
                                       (:value (get positional i))
                                       (:default f))]
                             (when-not v
                               (fail-nyi! (str "no value provided for field `"
                                               (:name f) "` on `" record-name
                                               "` (no arg and no default)")
                                          {:record record-name :field (:name f)}))
                             {:name  (:name f)
                              :value (lower-expr v ctx)}))
                         (range)
                         (:fields rec))]
    {:node :struct-lit :type record-name :fields fields}))

(defn- resolve-args-against-params
  "Match named/positional call args against declared params in decl
   order. Fall back to each param's declared default; fail-nyi if
   neither is available. Returns a vector of {:name :value} pairs in
   declaration order."
  [call-site-label params args]
  (let [by-name    (into {} (keep (fn [a] (when (= :named (:kind a))
                                            [(:name a) (:value a)]))
                                  args))
        positional (filterv #(= :positional (:kind %)) args)]
    (mapv (fn [i p]
            (let [v (or (get by-name (:name p))
                        (:value (get positional i))
                        (:default p))]
              (when-not v
                (fail-nyi! (str "no value provided for param `" (:name p)
                                "` on " call-site-label
                                " (no arg and no default)")
                           {:param (:name p) :site call-site-label}))
              {:name (:name p) :value v}))
          (range)
          params)))

(defn- lower-atom-ctor
  "`X(a: 1, b: 2)` → ObjC init call. With no ctor params, emits plain
   `[[X alloc] init]`. With ctor params, emits an :objc-init-with node
   carrying the resolved (name, value) pairs — one selector part per
   param in declaration order."
  [atom-name args ctx]
  (let [rec         (get-in ctx [:atom-env atom-name])
        ctor-params (:ctor-params rec)]
    (if (empty? ctor-params)
      (do
        (when (seq args)
          (fail-nyi! (str "atom constructor args on `" atom-name
                          "` — atom declares no ctor params")
                     {:atom atom-name :args args}))
        {:node :objc-alloc-init :class atom-name})
      (let [resolved (resolve-args-against-params
                      (str "`" atom-name "` constructor")
                      ctor-params args)]
        {:node :objc-init-with
         :class atom-name
         :args  (mapv (fn [{:keys [name value]}]
                        {:name name :value (lower-expr value ctx)})
                      resolved)}))))

(defn- c-method-name
  "Free-function name for a record's method: `<Record>_<method>`. Simple
   name-mangling; collisions with user-declared functions of that shape
   would be a legitimate compile error, not a silent shadowing."
  [record-name method-name]
  (str record-name "_" method-name))

(defn- objc-method-selector
  "Build the ObjC selector for a method call. Zero-arg → bare name;
   with args → `<name>With<first>:<rest>:...` matching the initWith
   convention on ctors. Not to be confused with cxc's `.name` — cxc
   source uses dot syntax; only the emit layer sees selectors."
  [method-name arg-names]
  (if (empty? arg-names)
    method-name
    (str method-name "With" (first arg-names) ":"
         (str/join "" (map #(str % ":") (rest arg-names))))))

(defn- lower-method-call
  "`target.method(args)` dispatches on target type:
   - record target → C free function call `Record_method(target, args...)`
     with self as first positional arg
   - atom target → ObjC message send `[target methodWithArg:...]` with
     arg-name-derived selector parts, falling back to observable-index
     for zero-arg synthesized getters
   Named args on method calls fail-nyi — parameters have declaration
   order in the source-level method decl."
  [call-node ctx]
  (let [dot         (:callee call-node)
        target-ty   (infer-type (:target dot) ctx)
        method-name (:field dot)
        args        (:args call-node)
        _           (when (some #(= :named (:kind %)) args)
                      (fail-nyi! "named args on method call (positional only for now)"
                                 call-node))
        arg-exprs   (mapv #(lower-expr (:value %) ctx) args)
        target-lo   (lower-expr (:target dot) ctx)]
    (cond
      (and (map? target-ty) (:record target-ty))
      {:node :call
       :fn   (c-method-name (:record target-ty) method-name)
       :args (into [target-lo] arg-exprs)}

      (and (map? target-ty) (:atom target-ty))
      (let [atom-name   (:atom target-ty)
            m           (get-in ctx [:atom-env atom-name :method-index method-name])
            o           (get-in ctx [:atom-env atom-name :observable-index method-name])
            _           (when (and (nil? m) (nil? o))
                          (fail-nyi! (str "method/observable `" method-name "` on atom `"
                                          atom-name "`") call-node))
            _           (when (and o (seq arg-exprs))
                          (fail-nyi! (str "observable `" method-name "` called with "
                                          "arguments — observables are zero-arg reads")
                                     call-node))
            param-names (if m (mapv :name (rest (:params m))) [])]
        {:node     :objc-msg
         :recv     target-lo
         :selector (objc-method-selector method-name param-names)
         :args     arg-exprs})

      :else
      (fail-nyi! "method call on non-record/non-atom target" call-node))))

(defn- print-format-string [arg-type]
  (cond
    (= :string arg-type)                    "%s\n"
    (= :int arg-type)                       "%d\n"
    (= :bool arg-type)                      "%d\n"
    (= :float arg-type)                     "%f\n"
    (and (map? arg-type) (:enum arg-type))  "%d\n"    ; enums are int-backed
    :else (fail-nyi! (str "print of type " (pr-str arg-type)) {:type arg-type})))

(defn- lower-print [call ctx]
  (let [arg-expr (:value (first (:args call)))
        arg-ty   (infer-type arg-expr ctx)]
    {:node :call
     :fn   "printf"
     :args [{:node :str :value (print-format-string arg-ty)}
            (lower-expr arg-expr ctx)]}))

(defn- lower-expr [node ctx]
  (case (:node node)
    :lit-string {:node :str   :value (:value node)}
    :lit-int    {:node :int   :value (:value node)}
    :lit-float  {:node :float :value (:value node)}
    :lit-nil    {:node :nil}
    :lit-int-unit   (fail-nyi! (str "unit-suffixed integer literal `"
                                    (:value node) (:unit node)
                                    "` — needs stdlib type for unit "
                                    "`" (:unit node) "` (Layer 5.4)")
                               node)
    :lit-float-unit (fail-nyi! (str "unit-suffixed float literal `"
                                    (:value node) (:unit node)
                                    "` — needs stdlib type for unit "
                                    "`" (:unit node) "` (Layer 5.4)")
                               node)
    :lambda     (fail-nyi! (str "lambda expression — needs a consumer to "
                                "invoke through (Layer 5 collection methods "
                                "`.map`/`.for_each`; will lower to ObjC blocks)")
                           node)
    :ident      (let [n  (:name node)
                      sf (get (:self-fields ctx) n)]
                  (cond
                    (contains? (:locals ctx) n)
                    {:node :name :sym n}

                    (:expr sf)
                    ;; Observable — dispatch through the synthesized getter.
                    ;; `self.name` in emitted C is an ObjC message send.
                    {:node :field
                     :target {:node :name :sym "self"}
                     :field  n}

                    sf
                    ;; Real data-field — direct ivar access; matches how we
                    ;; write self-fields (`_field = value;`).
                    {:node :name :sym (str "_" n)}

                    :else
                    {:node :name :sym n}))
    :self       {:node :name  :sym "self"}
    :binop      {:node  :binop
                 :op    (case (:op node)
                          "and" "&&"
                          "or"  "||"
                          "is"  "=="            ; pointer-eq for atoms, value-eq for enums/ints
                          (:op node))
                 :left  (lower-expr (:left node)  ctx)
                 :right (lower-expr (:right node) ctx)}
    :dot        (if (enum-case-access? node ctx)
                  {:node :name
                   :sym  (str (get-in node [:target :name]) "_" (:field node))}
                  {:node   :field
                   :target (lower-expr (:target node) ctx)
                   :field  (:field node)})
    :call
    (let [callee (:callee node)]
      (case (:node callee)
        :ident
        (let [callee-name (:name callee)]
          (cond
            (contains? (:record-env ctx) callee-name)
            (lower-record-ctor callee-name (:args node) ctx)

            (contains? (:atom-env ctx) callee-name)
            (lower-atom-ctor callee-name (:args node) ctx)

            (= "print" callee-name)
            (lower-print node ctx)

            :else
            (fail-nyi! (str "call to `" callee-name "`") node)))

        :dot   (lower-method-call node ctx)

        (fail-nyi! (str "call with " (:node callee) " callee") node)))
    (fail-nyi! (str "expression " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Statement lowering
;; ---------------------------------------------------------------------------

(defn- lower-send
  "Lower a targeted `:emit` node to a `cxc_send_async` / `cxc_send_sync`
   runtime helper call. Untargeted `:> msg` (stream emission) lands in
   Step 13. Payload args land in Step 9 (cross-atom shape check in Step
   10). Sync sends are direct method calls under Layer 4 single-thread
   (design §15); Layer 4c upgrades them to real `dispatch_sync` when
   queue targets differ."
  [node ctx]
  (when (nil? (:target node))
    (fail-nyi! (str "untargeted send `" (if (:async? node) ":>" "|>") " "
                    (:msg node) "` — stream emission is Layer 4 Step 13")
               node))
  (when (seq (:args node))
    (fail-nyi! (str "send arguments (`" (:msg node) "(...)` payload "
                    "— Layer 4 Step 9)")
               node))
  (let [target-lo (lower-expr (:target node) ctx)
        target-ty (infer-type (:target node) ctx)
        _         (when-not (and (map? target-ty) (:atom target-ty))
                    (fail-nyi! (str "send target must resolve to an atom-typed "
                                    "expression (got " (pr-str target-ty) ")")
                               node))
        atom-name (:atom target-ty)
        atom-info (get-in ctx [:atom-env atom-name])
        _         (when-not atom-info
                    (fail-nyi! (str "unknown atom type `" atom-name "` on send target") node))
        selector  (:msg node)
        helper    (if (:async? node) "cxc_send_async" "cxc_send_sync")]
    {:node :call
     :fn   helper
     :args [target-lo
            {:node :objc-block-literal
             :stmts [{:node :objc-msg
                      :recv target-lo
                      :selector selector
                      :args []}]}]}))

(defn- lower-stmt [node ctx]
  (case (:node node)
    :expr-stmt (lower-expr (:expr node) ctx)
    :emit      (lower-send node ctx)
    :return    {:node :return :value (lower-expr (:expr node) ctx)}
    (fail-nyi! (str "statement " (:node node)) node)))

(defn- lower-do-clause [clause ctx]
  (mapv #(lower-stmt % ctx) (:stmts clause)))

(defn- lower-transition-body [clauses ctx]
  (vec (mapcat (fn [c]
                 (case (:node c)
                   :do-clause (lower-do-clause c ctx)
                   (fail-nyi! (str "transition body clause " (:node c)) c)))
               clauses)))

(defn- lower-then-update
  "`then: field => expr` → `_field = <expr>;`. LHS is the atom's own
   ivar (direct ivar access, same as data-default init statements)."
  [update ctx]
  {:node :assign
   :lhs  {:node :name :sym (str "_" (:field update))}
   :rhs  (lower-expr (:expr update) ctx)})

(defn- lower-on-enter-body
  "Body of a wildcard on-enter transition when inlined into init. Same
   as a general transition body but with `then:` support — regular
   `do:` stmts pass through unchanged."
  [clauses ctx]
  (vec (mapcat (fn [c]
                 (case (:node c)
                   :then-clause (mapv #(lower-then-update % ctx) (:updates c))
                   :do-clause   (lower-do-clause c ctx)
                   (fail-nyi! (str "on-enter body clause " (:node c)) c)))
               clauses)))

(defn- initial-phase-for
  "First phase mentioned as a transition target — i.e. the phase the
   atom enters when init completes. cxc has no explicit initial-phase
   marker; declaration order fixes it. Atoms without any transitions
   default to `init` — every atom has a phase (spec §1.12), and `init`
   is a semantically-neutral choice for `data:`/`methods:`-only atoms."
  [transitions]
  (or (some :target transitions) "init"))

(defn- collect-phase-names
  "All phase names appearing in transition sources or targets, plus the
   implicit `init` and `end` sentinels. Order-preserving dedup — the
   first mention fixes ordering, which matters for readability of the
   emitted `static NSString * const` block. `init` is always first,
   `end` always last."
  [transitions]
  (let [mentioned (concat (keep #(when (= :phase (get-in % [:source :kind]))
                                   (get-in % [:source :name])) transitions)
                          (map :target transitions))
        middle    (remove #{"init" "end"} (distinct mentioned))]
    (vec (concat ["init"] middle ["end"]))))

(defn- phase-constant-name
  "Canonicalized ObjC constant name for a phase: `k<Atom>_phase_<name>`.
   Every read/write in emitted code references one of these — never a
   raw literal — so pointer-equality comparisons are safe (spec §5)."
  [atom-name phase-name]
  (str "k" atom-name "_phase_" phase-name))

(defn- wildcard-on-enter?
  "A transition with implicit source, no message receipt, no guard —
   the `-> phase then/do: ...` form that fires on every entry."
  [t]
  (and (= :implicit (get-in t [:source :kind]))
       (nil? (:receive t))
       (nil? (:when t))))

(defn- message-triggered?
  "A transition with a receive glyph (`<:` async, `<|` sync). Contrasts
   with implicit-source wildcards (`-> phase ...`) which fire on every
   entry to their target phase."
  [t]
  (contains? #{"<:" "<|"} (get-in t [:receive :glyph])))

(defn- transition-msg-name
  "Extract the message-name from a receive-glyph transition's pattern.
   Only bare-ident patterns (`bump`, `take_damage`) are handled at
   Layer 4 Step 4-7; dotted sources like `input.pressed` land in Step 13."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident (:name src)
      (fail-nyi! (str "message pattern source on transition must be a bare ident "
                      "(got " (:node src) "); dotted sources like `input.pressed` "
                      "are Layer 4 Step 13")
                 src))))

(defn- lower-transition-branch
  "Lower one transition into a `:transition-branch` — an `if` block on
   the source phase (+ combined header/body `when:` guards) that runs
   the transition's body and returns. Multiple transitions on the same
   selector are stacked in first-match order inside one method (design
   §12: first-match dispatch)."
  [atom-name t ctx]
  (when (not= :phase (get-in t [:source :kind]))
    (fail-nyi! (str "message-triggered transition on def-atom `" atom-name
                    "` must have an explicit source phase (got "
                    (get-in t [:source :kind]) ")")
               t))
  (let [source-name  (get-in t [:source :name])
        target       (:target t)
        msg          (get-in t [:receive :msg])
        payload      (:payload msg)
        _            (when (seq payload)
                       (fail-nyi! (str "message payload binding "
                                       "(named args in `" (transition-msg-name t)
                                       "{...}` — Layer 4 Step 9)")
                                  msg))
        ;; `self` in the branch body — needed by infer-type for
        ;; `self :> foo` sends and `self.field` reads.
        ctx'         (assoc-in ctx [:locals "self"] {:atom atom-name})
        header-when  (:when t)
        body-clauses (:body t)
        then-clauses (filter #(= :then-clause (:node %)) body-clauses)
        do-clauses   (filter #(= :do-clause   (:node %)) body-clauses)
        let-clauses  (filter #(= :let-clause  (:node %)) body-clauses)
        when-clauses (filter #(= :when-clause (:node %)) body-clauses)
        _            (when (seq let-clauses)
                       (fail-nyi! "`let:` bindings on message-triggered transition (Layer 4 Step 9+)" t))
        body-whens   (mapv :expr when-clauses)
        guard-exprs  (cond-> []
                       header-when      (conj header-when)
                       (seq body-whens) (into body-whens))
        guard-lo     (when (seq guard-exprs)
                       ;; AND-fold all guards into one expression.
                       (reduce (fn [acc e]
                                 {:node :binop :op "&&"
                                  :left acc :right (lower-expr e ctx')})
                               (lower-expr (first guard-exprs) ctx')
                               (rest guard-exprs)))
        then-stmts   (vec (mapcat (fn [c] (mapv #(lower-then-update % ctx')
                                                (:updates c)))
                                  then-clauses))
        do-stmts     (vec (mapcat (fn [c] (lower-do-clause c ctx')) do-clauses))
        phase-update (when (not= target source-name)
                       {:node :assign
                        :lhs {:node :name :sym "_phase"}
                        :rhs {:node :name
                              :sym (phase-constant-name atom-name target)}})
        body         (cond-> []
                       (seq then-stmts) (into then-stmts)
                       phase-update     (conj phase-update)
                       (seq do-stmts)   (into do-stmts))]
    {:node        :transition-branch
     :source-phase-const (phase-constant-name atom-name source-name)
     :guard       guard-lo
     :body        body}))

(defn- lower-message-triggered-transitions
  "Group transitions by message-selector. Each group becomes a single
   ObjC instance method whose body is a sequence of :transition-branch
   nodes in source-order. Same-selector transitions on different source
   phases are the common case (`alive <: bump ...` and `dying <: bump ...`);
   emitting them as branches in one method is what makes ObjC selector
   dispatch work — you can't have two methods with identical selectors."
  [atom-name transitions ctx]
  (let [msg-txns    (filter message-triggered? transitions)
        _           (doseq [t msg-txns]
                      (when (= "<|" (get-in t [:receive :glyph]))
                        (fail-nyi! (str "sync-receive glyph `<|` on transition "
                                        "(rendezvous semantics — deferred to Layer 4b)")
                                   t)))
        ;; group-by preserves group order per key; sort by first appearance
        ;; to keep emit order deterministic and match declaration order.
        by-selector (reduce (fn [acc t] (update acc (transition-msg-name t)
                                                (fnil conj []) t))
                            {}
                            msg-txns)
        ;; Preserve declaration order of selectors.
        selector-order (distinct (mapv transition-msg-name msg-txns))]
    (mapv (fn [sel]
            (let [ts (get by-selector sel)
                  branches (mapv #(lower-transition-branch atom-name % ctx) ts)]
              {:node        :objc-method
               :selector    sel
               :return-type "void"
               :params      []
               :body        branches}))
          selector-order)))

;; ---------------------------------------------------------------------------
;; Top-level lowering — records, methods, atoms, main
;; ---------------------------------------------------------------------------

(defn- lower-record-struct [record-node ctx]
  (let [rec (get-in ctx [:record-env (:name record-node)])]
    {:node   :c-struct
     :name   (:name record-node)
     :fields (mapv (fn [f] {:name (:name f) :c-type (:c-type f)}) (:fields rec))}))

(defn- lower-method-fn
  "A record method becomes a free C function. `self` and each typed
   param join the locals env before the body is lowered."
  [record-name method ctx]
  (let [locals   (into {} (map (fn [p]
                                 [(:name p)
                                  (cxc-type->type-kw (:cxc-ty p)
                                                     (:record-env ctx)
                                                     (:atom-env ctx)
                                                     (:enum-env ctx)
                                                     method)])
                               (:params method)))
        ctx'     (assoc ctx :locals locals)
        stmts    (mapv #(lower-stmt % ctx') (:stmts (:body method)))
        params   (mapv (fn [p]
                         {:name   (:name p)
                          :c-type (if (= "self" (:name p))
                                    record-name
                                    (cxc-type->c {:node :type-ref :name (:cxc-ty p)}
                                                 (:record-env ctx)
                                                 (:atom-env ctx)
                                                 (:enum-env ctx)))})
                       (:params method))
        ret-c    (cxc-type->c {:node :type-ref :name (:cxc-return-ty method)}
                              (:record-env ctx) (:atom-env ctx) (:enum-env ctx))]
    {:node        :c-function
     :name        (c-method-name record-name (:name method))
     :return-type ret-c
     :params      params
     :body        stmts}))

(defn- record-method-functions
  "For every record, lower every one of its methods to a :c-function."
  [ctx]
  (vec (for [[rec-name info] (:record-env ctx)
             m               (:methods info)]
         (lower-method-fn rec-name m ctx))))

(defn- default-init-stmt-for [field ctx]
  (let [rhs (if-let [d (:default field)]
              (lower-expr d ctx)
              {:node :int :value 0})]
    {:node :assign
     :lhs  {:node :name :sym (str "_" (:name field))}
     :rhs  rhs}))

(defn- lower-atom-method
  "A def-atom method becomes an ObjC instance method. `self` and each
   typed param join the locals env before the body is lowered. `self`
   isn't emitted as a formal param — ObjC provides it implicitly. Ivars
   are visible via property syntax (`self.n`) which lowers to a normal
   `.n` field access — the emitted C sees an object with a property."
  [atom-name method ctx]
  (let [self-ty     {:atom atom-name}
        locals      (into {}
                          (map (fn [p]
                                 [(:name p)
                                  (if (= "self" (:name p))
                                    self-ty
                                    (cxc-type->type-kw (:cxc-ty p)
                                                       (:record-env ctx)
                                                       (:atom-env ctx)
                                                       (:enum-env ctx)
                                                       method))]))
                          (:params method))
        ctx'        (assoc ctx :locals locals)
        stmts       (mapv #(lower-stmt % ctx') (:stmts (:body method)))
        args        (rest (:params method))            ; drop self
        param-names (mapv :name args)
        objc-params (mapv (fn [p]
                            {:name   (:name p)
                             :c-type (cxc-type->c {:node :type-ref :name (:cxc-ty p)}
                                                  (:record-env ctx)
                                                  (:atom-env ctx)
                                                  (:enum-env ctx))})
                          args)
        ret-c       (cxc-type->c {:node :type-ref :name (:cxc-return-ty method)}
                                 (:record-env ctx) (:atom-env ctx) (:enum-env ctx))]
    {:node        :objc-method
     :selector    (objc-method-selector (:name method) param-names)
     :return-type ret-c
     :params      objc-params
     :body        stmts}))

(defn- lower-observable-method
  "An observable `name :< expr` becomes a zero-arg synthesized method
   `- (T)name { return <expr>; }`. Reads self-fields via ivar direct
   access (matches init-body). Only readonly — no setter is emitted."
  [o ctx]
  {:node        :objc-method
   :selector    (:name o)
   :return-type (:c-type o)
   :params      []
   :body        [{:node :return :value (lower-expr (:expr o) ctx)}]})

(def ^:private comparison-ops #{"<" "<=" ">" ">=" "==" "!="})

(defn- chained-comparison?
  "Detects `a <= b <= c`-shaped predicates. Parser produces these as a
   left-assoc binop with a comparison inside a comparison, which is NOT
   what the user meant — needs desugaring at the parser layer (Layer 1.X).
   Fail-nyi in invariants so users don't get silent miscompilation."
  [expr]
  (and (= :binop (:node expr))
       (contains? comparison-ops (:op expr))
       (or (and (= :binop (get-in expr [:left :node]))
                (contains? comparison-ops (get-in expr [:left :op])))
           (and (= :binop (get-in expr [:right :node]))
                (contains? comparison-ops (get-in expr [:right :op]))))))

(defn- lower-invariant-check
  "One predicate → an :invariant-check init statement. Ctx must carry
   self-fields + init-locals so unqualified names resolve as in init."
  [atom-name idx pred ctx]
  (when (chained-comparison? pred)
    (fail-nyi! (str "chained comparison in invariant on `" atom-name
                    "` — needs parser-side desugaring (Layer 1.X); "
                    "for now split into `a <= b and b <= c`")
               pred))
  {:node  :invariant-check
   :cond  (lower-expr pred ctx)
   :label (str atom-name " invariant #" idx " failed at init")})

(defn- lower-atom [atom-name ctx]
  (let [rec           (get-in ctx [:atom-env atom-name])
        extras        (:extras rec)
        ctor-params   (:ctor-params rec)
        methods       (:methods rec)
        observables   (:observables-typed rec)
        transitions   (:transitions rec)
        initial-phase (initial-phase-for transitions)
        initial-on-enter (first (filter #(and (wildcard-on-enter? %)
                                              (= initial-phase (:target %)))
                                        transitions))
        locals        (into {} (map (fn [p]
                                      [(:name p)
                                       (cxc-type->type-kw (:cxc-ty p)
                                                          (:record-env ctx)
                                                          (:atom-env ctx)
                                                          (:enum-env ctx)
                                                          p)])
                                    ctor-params))
        self-fields   (into {} (map (juxt :name identity) (:fields rec)))
        ;; Observables see other observables declared earlier — same
        ;; lookup shape as self-fields but keyed by name → :type-kw entry.
        self-plus-obs (into self-fields
                            (map (juxt :name identity) observables))
        ctx-with-self (assoc ctx :self-fields self-plus-obs)
        ;; `self` visible in on-enter bodies for `self :> foo` / `self.x`.
        ctx-in-init   (assoc ctx-with-self
                             :locals (assoc locals "self" {:atom atom-name}))]
    (when (seq extras)
      (fail-nyi! (str "def-atom `" atom-name "` has body items beyond `data:`/`methods:`"
                      "/observable/wildcard on-enter transition "
                      "(" (mapv :node extras) ") — use:/invariants/receive-glyphs next up")
                 {:atom atom-name :extras extras}))
    ;; Layer 4 Step 4: message-triggered transitions become instance methods.
    ;; Layer 4 Step 8 covers non-initial-phase wildcard on-enter (inlined at
    ;; each transition site targeting that phase); for now, disallow them.
    (let [unhandled (remove #(or (message-triggered? %)
                                 (identical? % initial-on-enter))
                            transitions)]
      (when (seq unhandled)
        (fail-nyi! (str "def-atom `" atom-name "` has transitions that Layer 4 "
                        "Step 4 doesn't handle yet (non-initial-phase wildcard "
                        "on-enter → Step 8; sync-receive `<|` → Layer 4b): "
                        (mapv #(select-keys % [:source :receive :target]) unhandled))
                   {:atom atom-name :unhandled unhandled})))
    (let [phase-names     (collect-phase-names transitions)
          queue-init-stmt {:node :queue-init :ivar "_own_queue"}
          phase-init-stmt {:node :assign
                           :lhs  {:node :name :sym "_phase"}
                           :rhs  {:node :name
                                  :sym  (phase-constant-name atom-name initial-phase)}}
          default-stmts   (mapv #(default-init-stmt-for % ctx-in-init) (:fields rec))
          on-enter-stmts  (if initial-on-enter
                            (lower-on-enter-body (:body initial-on-enter) ctx-in-init)
                            [])
          invariant-stmts (vec (map-indexed
                                (fn [i p] (lower-invariant-check atom-name i p ctx-in-init))
                                (:invariants rec)))
          user-methods    (mapv #(lower-atom-method atom-name % ctx-with-self) methods)
          obs-methods     (mapv #(lower-observable-method % ctx-with-self) observables)
          data-props      (mapv (fn [f]
                                  ;; ARC discipline: object pointers (atom types) → strong;
                                  ;; scalars/records → assign. `strong` gives standard
                                  ;; owning-reference semantics; `assign` is right for
                                  ;; primitives and copyable values that ARC ignores.
                                  (let [is-atom-ptr? (contains? (:atom-env ctx) (:cxc-ty f))]
                                    {:name (:name f)
                                     :c-type (:c-type f)
                                     :attrs (if is-atom-ptr? ["strong"] ["assign"])}))
                                (:fields rec))
          phase-prop      {:name "phase" :c-type "NSString *"
                           :attrs ["nonatomic" "copy"]}
          own-queue-prop  ;; dispatch_queue_t is a toll-free-bridged ObjC object
                          ;; under modern ARC (OS_OBJECT_USE_OBJC=1); `strong`
                          ;; keeps it alive for the atom's lifetime.
                          {:name "own_queue" :c-type "dispatch_queue_t"
                           :attrs ["nonatomic" "strong"]}
          ;; Three-part init factoring (design §7.4). Splits responsibility
          ;; so alternate ctors (e.g. -initFromSnapshot: in Layer 6+) can
          ;; substitute :state-init with restore logic while keeping
          ;; structural setup and runtime binding intact. Data defaults
          ;; may reference ctor-param names (`data: stored: Int = v`),
          ;; so cxc_stateInit takes the ctor-params through and threads
          ;; them from -init.
          self-msg-noargs (fn [sel] {:node :objc-msg
                                     :recv {:node :name :sym "self"}
                                     :selector sel :args []})
          param-names     (mapv :name ctor-params)
          state-selector  (objc-method-selector "cxc_stateInit" param-names)
          state-init-msg  {:node :objc-msg
                           :recv {:node :name :sym "self"}
                           :selector state-selector
                           :args (mapv (fn [p] {:node :name :sym (:name p)})
                                       ctor-params)}
          setup-method    {:node :objc-method :selector "cxc_structuralSetup"
                           :return-type "void" :params []
                           :body [queue-init-stmt phase-init-stmt]}
          state-method    {:node :objc-method :selector state-selector
                           :return-type "void"
                           :params (mapv (fn [p] {:name (:name p) :c-type (:c-type p)})
                                         ctor-params)
                           :body (into default-stmts on-enter-stmts)}
          bind-method     {:node :objc-method :selector "cxc_runtimeBinding"
                           :return-type "void" :params []
                           :body invariant-stmts}
          init-shell      [(self-msg-noargs "cxc_structuralSetup")
                           state-init-msg
                           (self-msg-noargs "cxc_runtimeBinding")]
          txn-methods     (lower-message-triggered-transitions
                           atom-name transitions ctx-with-self)]
      {:node            :objc-class
       :name            atom-name
       :superclass      "NSObject"
       :phase-constants (mapv (fn [p] {:name (phase-constant-name atom-name p)
                                       :value p}) phase-names)
       :properties      (into [phase-prop own-queue-prop] data-props)
       :init-params     (mapv (fn [p] {:name (:name p) :c-type (:c-type p)}) ctor-params)
       :init-body       init-shell
       :methods         (-> [setup-method state-method bind-method]
                            (into user-methods)
                            (into obs-methods)
                            (into txn-methods))})))

(defn- main-atom-body [main-node ctx]
  (let [transitions (filter #(= :transition (:node %)) (:body main-node))
        running-txn (first (filter #(= "running" (:target %)) transitions))]
    (when-not running-txn
      (fail-nyi! "`def main` must have a `-> running` transition" main-node))
    (lower-transition-body (:body running-txn) ctx)))

(defn- main-def-atom? [node]
  (and (= :def-atom (:node node)) (= "main" (:name node))))

(defn- lower-enum
  "Top-level `enum Foo: a, b, c` → C typedef enum with `Foo_a`, `Foo_b`,
   ... as members. Case ordering fixes each case's integer value."
  [enum-node]
  {:node  :c-enum
   :name  (:name enum-node)
   :cases (:cases enum-node)})

(defn- infer-observables-in-atom-env
  "Second pass: with the atom-env already built (so cross-atom types
   resolve), infer each observable's return type in a ctx where the
   atom's own data-fields are self-fields. Observables are typed in
   declaration order so a later observable may reference earlier ones.
   Populates :observables-typed and :observable-index on each entry."
  [atom-env record-env enum-env]
  (reduce-kv
   (fn [env atom-name info]
     (let [self-fields (into {} (map (juxt :name identity) (:fields info)))
           {:keys [typed]}
           (reduce (fn [{:keys [typed known]} o]
                     (let [ctx    {:record-env record-env :atom-env atom-env
                                   :enum-env enum-env :locals {}
                                   :self-fields known}
                           tk     (infer-type (:expr o) ctx)
                           typed' (assoc o :type-kw tk
                                         :c-type   (type-kw->c-type tk o))]
                       {:typed (conj typed typed')
                        :known (assoc known (:name o) typed')}))
                   {:typed [] :known self-fields}
                   (:observables info))]
       (assoc env atom-name
              (assoc info
                     :observables-typed typed
                     :observable-index  (into {} (map (juxt :name identity) typed))))))
   atom-env atom-env))

(defn lower
  "Take parsed top-level nodes and produce an ObjC/C program AST."
  [top-nodes]
  (let [main-atom  (first (filter main-def-atom? top-nodes))
        _          (when-not main-atom
                     (throw (ex-info "no `def main` entry point found at top level"
                                     {:top top-nodes})))
        enum-env   (build-enum-env top-nodes)
        atom-env   (build-atom-env top-nodes enum-env)
        record-env (build-record-env top-nodes atom-env enum-env)
        atom-env   (infer-observables-in-atom-env atom-env record-env enum-env)
        ctx        {:record-env record-env :atom-env atom-env :enum-env enum-env :locals {}}
        records    (filter #(= :record (:node %)) top-nodes)
        enums      (filter #(= :enum   (:node %)) top-nodes)
        c-enums    (mapv lower-enum enums)
        structs    (mapv #(lower-record-struct % ctx) records)
        functions  (record-method-functions ctx)
        classes    (mapv #(lower-atom % ctx) (keys atom-env))
        needs-foundation? (boolean (seq classes))
        needs-abort?      (boolean (some #(seq (:invariants (second %))) atom-env))
        ;; Runtime helpers (cxc_send_async / cxc_send_sync / cxc_shutdown)
        ;; are needed whenever any atom has a message-triggered transition
        ;; (potential receiver) — sends land in do: blocks that reference
        ;; the helpers by name. Detection is one-sided: if any atom can
        ;; receive, we include the helpers even if no sends happen to
        ;; land in the current program (harmless static-inline definitions).
        needs-runtime?    (boolean (some #(seq (filter message-triggered?
                                                       (:transitions (second %))))
                                         atom-env))
        base-includes     (if needs-foundation?
                            ["<Foundation/Foundation.h>"]
                            ["<stdio.h>"])
        includes          (cond-> base-includes
                            needs-abort?   (conj "<stdlib.h>")
                            needs-runtime? (conj "<stdlib.h>"))
        ;; Dedup includes while preserving order.
        includes          (vec (distinct includes))]
    {:node           :objc-program
     :includes       includes
     :needs-runtime? needs-runtime?
     :enums          c-enums
     :structs        structs
     :functions      functions
     :classes        classes
     :main           {:body         (conj (main-atom-body main-atom ctx)
                                          {:node :return :value {:node :int :value 0}})
                      :autorelease? needs-foundation?}}))
