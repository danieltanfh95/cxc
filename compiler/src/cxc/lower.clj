(ns cxc.lower
  "Lower cxc AST to ObjC/C AST.

   ObjC AST vocabulary (Clojure data — a subset of what real ObjC allows,
   grown feature by feature alongside the parser):

     {:node :objc-program
      :includes    [\"<stdio.h>\"] or [\"<Foundation/Foundation.h>\"]
      :structs     [ ... :c-struct ... ]        ; C typedef structs (records)
      :classes     [ ... :objc-class ... ]      ; ObjC classes (def-atoms)
      :main        { :body [ ... :stmt ... ]
                     :autorelease?  <bool> }}   ; wrap body in @autoreleasepool

     {:node :c-struct
      :name        \"V\"
      :fields      [ {:name \"x\" :c-type \"int\"} ... ]}

     {:node :objc-class
      :name        \"Counter\"
      :superclass  \"NSObject\"
      :properties  [ {:name \"n\" :c-type \"int\" :attrs [\"assign\"]} ... ]
      :init-body   [ ... :stmt ... ]}           ; body of -init (excludes self=super/return self)

     {:node :objc-method            ; unused in this subset; kept for shape
      :selector    \"init\"
      :return-type \"instancetype\"
      :params      [ {:name \"foo\" :c-type \"NSString *\"} ]
      :body        [ ... :stmt ... ]}

   Statement nodes (in :body vectors):
     {:node :call    :fn \"printf\" :args [ ... :expr ... ]}
     {:node :return  :value <expr>}
     {:node :assign  :lhs <expr> :rhs <expr>}

   Expression nodes (as :args, :value, :lhs, :rhs):
     {:node :str             :value \"hello\"}
     {:node :int             :value 42}
     {:node :float           :value 3.14}
     {:node :name            :sym \"self\"}
     {:node :binop           :op \"+\" :left <expr> :right <expr>}
     {:node :field           :target <expr> :field \"x\"}
     {:node :struct-lit      :type \"V\" :fields [{:name \"x\" :value <expr>} ...]}
     {:node :objc-alloc-init :class \"Counter\"}        ; [[Counter alloc] init]
     {:node :objc-msg        :recv <expr> :selector \"length\" :args []}
     {:node :call            :fn \"printf\" :args [ ... ]}

   Coverage this file (2026-08-13):
   - Hello-world: `def main: -> running do: print(\"...\")`
   - Arithmetic in print: `print(3 + 4 * 2)` → `11`
   - Records → C typedef structs; construction; field access; defaults.
   - Def-atoms with `data:` only → ObjC classes (NSObject subclass with
     @property ivars + -init that fills defaults). Reference semantics;
     `X()` in expression position → `[[X alloc] init]`.

   Not yet: transitions with then:, use: injection, contracts, method
   dispatch, nested-atom slots, the mailbox/scheduler runtime. Those
   raise :not-yet-implemented so subsequent work surfaces the gaps.

   Entry point convention: the top-level `def main` atom (lowercase `main`)
   is the runtime entry point. Its `-> running do: ...` transition body
   becomes the body of `int main(int, char **)`, wrapped in
   `@autoreleasepool` when any ObjC class is emitted.")

(defn- fail-nyi! [what node]
  (throw (ex-info (str "lowering not yet implemented: " what)
                  {:kind :not-yet-implemented :what what :node node})))

;; ---------------------------------------------------------------------------
;; Type env
;;
;; Two parallel envs keyed by name:
;;   record-env: {name -> {:fields [{:name :cxc-ty :c-type :default}]
;;                         :field-index {name -> field}}}
;;   atom-env:   same shape, but for def-atoms (reference semantics on emit)
;;
;; Record vs atom is a syntactic distinction: `record V:` picks value
;; semantics; `def X:` picks reference. Same field shape, different
;; construction call at the emit layer (:struct-lit vs :objc-alloc-init).
;; ---------------------------------------------------------------------------

(def ^:private primitive-c-type
  {"Int"     "int"
   "Float"   "double"
   "String"  "const char *"
   "Bool"    "int"})     ;; No stdbool in scope yet; treat bool as int for now.

(declare cxc-type->c)

(defn- cxc-type->c
  "Map a cxc type-ref to a C type string. Primitives use the table above.
   Record types map to their own struct name (value type — used as-is).
   Atom types map to `<name> *` (reference type — pointer to ObjC object).
   Unknown types fail-nyi so we surface the gap early."
  [ty-node record-env atom-env]
  (let [nm (:name ty-node)]
    (cond
      (contains? primitive-c-type nm) (primitive-c-type nm)
      (contains? record-env nm)       nm
      (contains? atom-env nm)         (str nm " *")
      :else                            (fail-nyi! (str "type " nm) ty-node))))

(defn- fields-from-data-section
  "Extract the [{:name :cxc-ty :c-type :default}] shape from a def-node's
   data: section (records use :sections, def-atoms use :body)."
  [data-section record-env atom-env]
  (mapv (fn [f]
          {:name    (:name f)
           :cxc-ty  (:name (:type f))
           :c-type  (cxc-type->c (:type f) record-env atom-env)
           :default (:default f)})
        (:fields data-section)))

(defn- build-record-env
  "Records are pure value types. Two-pass: names first (so record→record
   refs resolve), then field resolution."
  [top-nodes atom-env]
  (let [records  (filter #(= :record (:node %)) top-nodes)
        skeleton (into {} (map (fn [r] [(:name r) {:fields [] :field-index {}}]) records))]
    (reduce
     (fn [env r]
       (let [name   (:name r)
             dsec   (first (filter #(= :data-section (:node %)) (:sections r)))
             fields (fields-from-data-section dsec env atom-env)]
         (assoc env name {:fields fields
                          :field-index (into {} (map (juxt :name identity) fields))})))
     skeleton
     records)))

(defn- def-atom-data-section [atom-node]
  (first (filter #(= :data-section (:node %)) (:body atom-node))))

(defn- def-atom-body-non-data [atom-node]
  (remove #(= :data-section (:node %)) (:body atom-node)))

(defn- build-atom-env
  "Def-atoms other than `main`. For this milestone we only accept atoms
   whose body is a single `data:` section (optionally empty). Anything
   else (use:, invariants:, transitions, methods:, nested slots) is not
   yet lowered — we return the shape but the presence of extra body
   items will fail-nyi later during class emission."
  [top-nodes]
  (let [atoms    (filter #(and (= :def-atom (:node %))
                               (not= "main" (:name %)))
                         top-nodes)
        skeleton (into {} (map (fn [a] [(:name a) {:fields [] :field-index {}
                                                   :extras []}]) atoms))]
    (reduce
     (fn [env a]
       (let [name   (:name a)
             dsec   (def-atom-data-section a)
             extras (def-atom-body-non-data a)
             fields (if dsec
                      (fields-from-data-section dsec {} env)
                      [])]
         (assoc env name {:fields fields
                          :field-index (into {} (map (juxt :name identity) fields))
                          :extras extras})))
     skeleton
     atoms)))

;; ---------------------------------------------------------------------------
;; Expression type inference
;; ---------------------------------------------------------------------------

(declare infer-type)

(defn- infer-binop-type
  [{:keys [op left right]} record-env atom-env]
  (let [lt (infer-type left  record-env atom-env)
        rt (infer-type right record-env atom-env)]
    (cond
      (#{"==" "!=" "<" "<=" ">" ">="} op) :bool
      (or (= lt :float) (= rt :float))    :float
      (and (= lt :int) (= rt :int))       :int
      :else (fail-nyi! (str "binop " op " between " lt " and " rt)
                       {:left lt :right rt}))))

(defn- infer-call-type
  [{:keys [callee] :as node} record-env atom-env]
  (let [nm (:name callee)]
    (cond
      (contains? record-env nm) {:record nm}
      (contains? atom-env   nm) {:atom nm}
      (= "print" nm)            :void
      :else (fail-nyi! (str "call to `" nm "`") node))))

(defn- field-type-for-owner [owner-ty record-env atom-env field-name node]
  (let [env-key (cond
                  (and (map? owner-ty) (:record owner-ty)) [:record record-env (:record owner-ty)]
                  (and (map? owner-ty) (:atom   owner-ty)) [:atom   atom-env   (:atom   owner-ty)])
        _       (when-not env-key (fail-nyi! "field access on non-aggregate type" node))
        [kind env owner] env-key
        f       (get-in env [owner :field-index field-name])
        _       (when-not f (fail-nyi! (str "field `" field-name "` on " kind " `" owner "`") node))]
    (case (:cxc-ty f)
      "Int"    :int
      "Float"  :float
      "String" :string
      "Bool"   :bool
      (cond
        (contains? record-env (:cxc-ty f)) {:record (:cxc-ty f)}
        (contains? atom-env   (:cxc-ty f)) {:atom   (:cxc-ty f)}
        :else (fail-nyi! (str "unresolved field type `" (:cxc-ty f) "`") node)))))

(defn- infer-type [node record-env atom-env]
  (case (:node node)
    :lit-string :string
    :lit-int    :int
    :lit-float  :float
    :ident      (fail-nyi! (str "type-of identifier `" (:name node)
                                "` (no local-binding env yet)")
                           node)
    :binop      (infer-binop-type node record-env atom-env)
    :call       (infer-call-type node record-env atom-env)
    :dot        (field-type-for-owner (infer-type (:target node) record-env atom-env)
                                      record-env atom-env (:field node) node)
    (fail-nyi! (str "infer-type " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Expression lowering
;; ---------------------------------------------------------------------------

(declare lower-expr lower-stmt)

(defn- lower-record-ctor
  "`V(x: 3, y: 4)` → C designated initializer. Named args match by name;
   positional args match by declaration order. Fields with no arg fall
   back to their declared default. Fields with no arg AND no default
   fail-nyi."
  [record-name args record-env atom-env]
  (let [rec        (get record-env record-name)
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
                              :value (lower-expr v record-env atom-env)}))
                         (range)
                         (:fields rec))]
    {:node :struct-lit :type record-name :fields fields}))

(defn- lower-atom-ctor
  "`Counter()` → `[[Counter alloc] init]`. Currently only zero-arg calls
   are supported — the atom's data: defaults fill the instance in -init.
   User-provided ctor args (positional or named) would map onto an
   `-initWithX:...` selector; not yet wired."
  [atom-name args _record-env _atom-env]
  (when (seq args)
    (fail-nyi! (str "atom constructor args on `" atom-name
                    "` (only zero-arg `X()` supported so far)")
               {:atom atom-name :args args}))
  {:node :objc-alloc-init :class atom-name})

(defn- print-format-string [arg-type]
  (case arg-type
    :string "%s\n"
    :int    "%d\n"
    :bool   "%d\n"
    :float  "%f\n"
    (fail-nyi! (str "print of type " (pr-str arg-type)) {:type arg-type})))

(defn- lower-print [call record-env atom-env]
  (let [arg-expr (:value (first (:args call)))
        arg-ty   (infer-type arg-expr record-env atom-env)]
    {:node :call
     :fn   "printf"
     :args [{:node :str :value (print-format-string arg-ty)}
            (lower-expr arg-expr record-env atom-env)]}))

(defn- lower-expr [node record-env atom-env]
  (case (:node node)
    :lit-string {:node :str   :value (:value node)}
    :lit-int    {:node :int   :value (:value node)}
    :lit-float  {:node :float :value (:value node)}
    :ident      {:node :name  :sym (:name node)}
    :self       {:node :name  :sym "self"}
    :binop      {:node  :binop
                 :op    (:op node)
                 :left  (lower-expr (:left node)  record-env atom-env)
                 :right (lower-expr (:right node) record-env atom-env)}
    :dot        {:node   :field
                 :target (lower-expr (:target node) record-env atom-env)
                 :field  (:field node)}
    :call
    (let [callee-name (get-in node [:callee :name])]
      (cond
        (contains? record-env callee-name)
        (lower-record-ctor callee-name (:args node) record-env atom-env)

        (contains? atom-env callee-name)
        (lower-atom-ctor callee-name (:args node) record-env atom-env)

        (= "print" callee-name)
        (lower-print node record-env atom-env)

        :else
        (fail-nyi! (str "call to `" callee-name "`") node)))
    (fail-nyi! (str "expression " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Statement lowering
;; ---------------------------------------------------------------------------

(defn- lower-stmt [node record-env atom-env]
  (case (:node node)
    :expr-stmt (lower-expr (:expr node) record-env atom-env)
    :emit      (fail-nyi! ":emit statement (needs mailbox runtime)" node)
    :return    {:node :return :value (lower-expr (:expr node) record-env atom-env)}
    (fail-nyi! (str "statement " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Transition body — collect statements from `do:` clauses
;; ---------------------------------------------------------------------------

(defn- lower-do-clause [clause record-env atom-env]
  (mapv #(lower-stmt % record-env atom-env) (:stmts clause)))

(defn- lower-transition-body [clauses record-env atom-env]
  (vec (mapcat (fn [c]
                 (case (:node c)
                   :do-clause (lower-do-clause c record-env atom-env)
                   (fail-nyi! (str "transition body clause " (:node c)) c)))
               clauses)))

;; ---------------------------------------------------------------------------
;; Top-level lowering — records → C structs; def-atoms → ObjC classes
;; ---------------------------------------------------------------------------

(defn- lower-record [record-node record-env]
  (let [rec (get record-env (:name record-node))]
    {:node   :c-struct
     :name   (:name record-node)
     :fields (mapv (fn [f] {:name (:name f) :c-type (:c-type f)}) (:fields rec))}))

(defn- default-init-stmt-for
  "Emit `_<name> = <default>;` for a field with a default. Fields with
   no default get an explicit `_<name> = 0;` — C zero-init is fine for
   ints/doubles, and gives predictable behavior. String/atom defaults
   without an explicit initializer would need nil; not yet exercised."
  [field record-env atom-env]
  (let [rhs (if-let [d (:default field)]
              (lower-expr d record-env atom-env)
              {:node :int :value 0})]
    {:node :assign
     :lhs  {:node :name :sym (str "_" (:name field))}
     :rhs  rhs}))

(defn- lower-atom [atom-name record-env atom-env]
  (let [rec    (get atom-env atom-name)
        extras (:extras rec)]
    (when (seq extras)
      (fail-nyi! (str "def-atom `" atom-name "` has body items beyond `data:` "
                      "(" (mapv :node extras) ") — atom classes with "
                      "transitions/use:/invariants/methods are next up")
                 {:atom atom-name :extras extras}))
    {:node       :objc-class
     :name       atom-name
     :superclass "NSObject"
     :properties (mapv (fn [f] {:name (:name f) :c-type (:c-type f) :attrs ["assign"]})
                       (:fields rec))
     :init-body  (mapv #(default-init-stmt-for % record-env atom-env) (:fields rec))}))

(defn- main-atom-body
  "Extract the `-> running do: ...` transition's statements from the
   entry-point atom. Assumes exactly one transition targeting `running`
   with a `do:` body — the current subset. Other shapes fail-nyi."
  [main-node record-env atom-env]
  (let [transitions (filter #(= :transition (:node %)) (:body main-node))
        running-txn (first (filter #(= "running" (:target %)) transitions))]
    (when-not running-txn
      (fail-nyi! "`def main` must have a `-> running` transition" main-node))
    (lower-transition-body (:body running-txn) record-env atom-env)))

(defn- main-def-atom? [node]
  (and (= :def-atom (:node node)) (= "main" (:name node))))

(defn lower
  "Take parsed top-level nodes and produce an ObjC/C program AST.

   Requires exactly one top-level `def main` atom. Top-level `record`
   decls lower to C typedef structs; other top-level `def` atoms lower
   to ObjC classes. Module decl, imports, protocols, enums, classes are
   ignored for now."
  [top-nodes]
  (let [main-atom  (first (filter main-def-atom? top-nodes))
        _          (when-not main-atom
                     (throw (ex-info "no `def main` entry point found at top level"
                                     {:top top-nodes})))
        atom-env   (build-atom-env top-nodes)
        record-env (build-record-env top-nodes atom-env)
        records    (filter #(= :record (:node %)) top-nodes)
        structs    (mapv #(lower-record % record-env) records)
        atom-names (keys atom-env)
        classes    (mapv #(lower-atom % record-env atom-env) atom-names)
        needs-foundation? (boolean (seq classes))
        includes   (if needs-foundation?
                     ["<Foundation/Foundation.h>"]
                     ["<stdio.h>"])]
    {:node     :objc-program
     :includes includes
     :structs  structs
     :classes  classes
     :main     {:body          (conj (main-atom-body main-atom record-env atom-env)
                                     {:node :return :value {:node :int :value 0}})
                :autorelease?  needs-foundation?}}))
