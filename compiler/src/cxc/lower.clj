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
      :locals     <name -> ty>       ; local scope for method bodies etc.")

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

(defn- def-atom-body-non-data [atom-node]
  (remove #(= :data-section (:node %)) (:body atom-node)))

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

(defn- build-atom-env
  [top-nodes enum-env]
  (let [atoms    (filter #(and (= :def-atom (:node %))
                               (not= "main" (:name %)))
                         top-nodes)
        skeleton (into {} (map (fn [a] [(:name a) {:fields [] :field-index {}
                                                   :ctor-params [] :extras []}]) atoms))]
    (reduce
     (fn [env a]
       (let [name        (:name a)
             dsec        (def-atom-data-section a)
             extras      (def-atom-body-non-data a)
             fields      (if dsec
                           (fields-from-data-section dsec {} env enum-env)
                           [])
             ctor-params (ctor-params-normalized (:ctor-params a) {} env enum-env)]
         (assoc env name {:fields fields
                          :field-index (into {} (map (juxt :name identity) fields))
                          :ctor-params ctor-params
                          :extras extras})))
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
      (#{"==" "!=" "<" "<=" ">" ">="} op) :bool
      (or (= lt :float) (= rt :float))    :float
      (and (= lt :int) (= rt :int))       :int
      :else (fail-nyi! (str "binop " op " between " lt " and " rt)
                       {:left lt :right rt}))))

(defn- infer-method-call-type
  "For `target.method(args)`: infer target-ty, look up method on the
   record, return its cxc-return-ty translated to a type-kw."
  [call-node ctx]
  (let [dot         (:callee call-node)
        target-ty   (infer-type (:target dot) ctx)
        record-name (and (map? target-ty) (:record target-ty))
        _           (when-not record-name
                      (fail-nyi! "method call on non-record target" call-node))
        method-name (:field dot)
        m           (get-in (:record-env ctx) [record-name :method-index method-name])
        _           (when-not m
                      (fail-nyi! (str "method `" method-name "` on record `"
                                      record-name "`") call-node))]
    (cxc-type->type-kw (:cxc-return-ty m)
                       (:record-env ctx) (:atom-env ctx) (:enum-env ctx) call-node)))

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
  (let [env-key (cond
                  (and (map? owner-ty) (:record owner-ty))
                  [:record (:record-env ctx) (:record owner-ty)]
                  (and (map? owner-ty) (:atom owner-ty))
                  [:atom   (:atom-env ctx)   (:atom owner-ty)])
        _       (when-not env-key
                  (fail-nyi! "field access on non-aggregate type" node))
        [kind env owner] env-key
        f       (get-in env [owner :field-index field-name])
        _       (when-not f
                  (fail-nyi! (str "field `" field-name "` on " kind " `" owner "`")
                             node))]
    (cxc-type->type-kw (:cxc-ty f)
                       (:record-env ctx) (:atom-env ctx) (:enum-env ctx) node)))

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
    :self       (or (get (:locals ctx) "self")
                    (fail-nyi! "type-of `self` outside a method body" node))
    :ident      (or (get (:locals ctx) (:name node))
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

(defn- lower-method-call
  "`target.method(args)` → C function call `Record_method(target, args...)`.
   Self becomes the first positional arg; user args are appended in order.
   Named args on method calls fail-nyi — parameters have declaration
   order in the record's method decl."
  [call-node ctx]
  (let [dot         (:callee call-node)
        target-ty   (infer-type (:target dot) ctx)
        record-name (:record target-ty)
        method-name (:field dot)
        args        (:args call-node)
        _           (when (some #(= :named (:kind %)) args)
                      (fail-nyi! "named args on method call (positional only for now)"
                                 call-node))
        arg-exprs   (mapv #(lower-expr (:value %) ctx) args)]
    {:node :call
     :fn   (c-method-name record-name method-name)
     :args (into [(lower-expr (:target dot) ctx)] arg-exprs)}))

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
    :ident      {:node :name  :sym (:name node)}
    :self       {:node :name  :sym "self"}
    :binop      {:node  :binop
                 :op    (:op node)
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

(defn- lower-stmt [node ctx]
  (case (:node node)
    :expr-stmt (lower-expr (:expr node) ctx)
    :emit      (fail-nyi! ":emit statement (needs mailbox runtime)" node)
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

(defn- lower-atom [atom-name ctx]
  (let [rec         (get-in ctx [:atom-env atom-name])
        extras      (:extras rec)
        ctor-params (:ctor-params rec)
        locals      (into {} (map (fn [p]
                                    [(:name p)
                                     (cxc-type->type-kw (:cxc-ty p)
                                                        (:record-env ctx)
                                                        (:atom-env ctx)
                                                        (:enum-env ctx)
                                                        p)])
                                  ctor-params))
        ctx-in-init (assoc ctx :locals locals)]
    (when (seq extras)
      (fail-nyi! (str "def-atom `" atom-name "` has body items beyond `data:` "
                      "(" (mapv :node extras) ") — atom classes with "
                      "transitions/use:/invariants/methods are next up")
                 {:atom atom-name :extras extras}))
    {:node        :objc-class
     :name        atom-name
     :superclass  "NSObject"
     :properties  (mapv (fn [f]
                          ;; ARC discipline: object pointers (atom types) → strong;
                          ;; scalars/records → assign. `strong` gives standard
                          ;; owning-reference semantics; `assign` is right for
                          ;; primitives and copyable values that ARC ignores.
                          (let [is-atom-ptr? (contains? (:atom-env ctx) (:cxc-ty f))]
                            {:name (:name f)
                             :c-type (:c-type f)
                             :attrs (if is-atom-ptr? ["strong"] ["assign"])}))
                        (:fields rec))
     :init-params (mapv (fn [p] {:name (:name p) :c-type (:c-type p)}) ctor-params)
     :init-body   (mapv #(default-init-stmt-for % ctx-in-init) (:fields rec))}))

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
        ctx        {:record-env record-env :atom-env atom-env :enum-env enum-env :locals {}}
        records    (filter #(= :record (:node %)) top-nodes)
        enums      (filter #(= :enum   (:node %)) top-nodes)
        c-enums    (mapv lower-enum enums)
        structs    (mapv #(lower-record-struct % ctx) records)
        functions  (record-method-functions ctx)
        classes    (mapv #(lower-atom % ctx) (keys atom-env))
        needs-foundation? (boolean (seq classes))
        includes   (if needs-foundation?
                     ["<Foundation/Foundation.h>"]
                     ["<stdio.h>"])]
    {:node      :objc-program
     :includes  includes
     :enums     c-enums
     :structs   structs
     :functions functions
     :classes   classes
     :main      {:body         (conj (main-atom-body main-atom ctx)
                                     {:node :return :value {:node :int :value 0}})
                 :autorelease? needs-foundation?}}))
