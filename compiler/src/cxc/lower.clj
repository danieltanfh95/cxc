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
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cxc.prelude :as prelude]))

(defn- fail-nyi! [what node]
  (throw (ex-info (str "lowering not yet implemented: " what)
                  {:kind :not-yet-implemented :what what :node node})))

(defn- program-has-async-sends?
  "Walk the parsed AST for any `:emit` node with `:async? true` — a
   `:>` targeted send OR an untargeted stream emission. Programs
   without async sends don't need dispatch_main; their work completes
   synchronously during construction and main() returns normally."
  [top-nodes]
  (let [found (atom false)]
    (walk/postwalk
     (fn [n]
       (when (and (map? n) (= :emit (:node n)) (:async? n))
         (reset! found true))
       n)
     top-nodes)
    @found))

;; ---------------------------------------------------------------------------
;; Type env
;; ---------------------------------------------------------------------------

(def ^:private primitive-c-type
  {"Int"     "int"
   "Float"   "double"
   "String"  "const char *"
   "Bool"    "int"})

(declare base-type-name)

(defn- cxc-type->c
  "Primitives → C type name; records → own struct name (value); atoms →
   `<name> *` (pointer, already nullable in ObjC); enums → own typedef
   name (int-backed). `T?` wraps another type — legal today only for
   atoms (their pointer type IS nullable). Nullable primitives/records/
   enums would need Optional wrappers — fail-nyi."
  [ty-node record-env atom-env enum-env]
  (case (:node ty-node)
    :type-nullable
    (let [inner (:inner ty-node)]
      (case (:node inner)
        :type-of  (str (base-type-name inner) " *")
        :type-ref (let [nm (:name inner)]
                    (cond
                      (contains? atom-env nm) (str nm " *")
                      :else
                      (fail-nyi! (str "nullable non-atom type `" nm "?` "
                                      "(only atom types are nullable today; "
                                      "primitives/records/enums would need an Optional wrapper)")
                                 ty-node)))
        (fail-nyi! (str "cxc-type->c: nullable of unknown inner " (:node inner))
                   ty-node)))
    :type-ref
    (let [nm (:name ty-node)]
      (cond
        (contains? primitive-c-type nm) (primitive-c-type nm)
        (contains? record-env nm)       nm
        (contains? atom-env nm)         (str nm " *")
        (contains? enum-env nm)         nm
        :else                            (fail-nyi! (str "type " nm) ty-node)))
    :type-of
    (if (= "List" (:outer ty-node))
      ;; Compiler-synthesized List class — reference-typed, pointer at
      ;; the C level. Element-type validity is checked at the atom-env
      ;; registration site.
      (str (base-type-name ty-node) " *")
      (fail-nyi! (str "cxc-type->c: `" (:outer ty-node)
                      " of ...` unsupported at Layer 5 Step 3")
                 ty-node))
    (fail-nyi! (str "cxc-type->c: unknown type-node " (:node ty-node)) ty-node)))

(defn- base-type-name
  "Extract the underlying type name from either a :type-ref or a
   :type-nullable wrapper. Used to key the type env regardless of
   nullability, since nullability is orthogonal to which env the type
   lives in."
  [ty-node]
  (case (:node ty-node)
    :type-ref      (:name ty-node)
    :type-nullable (base-type-name (:inner ty-node))
    :type-of       (if (= "List" (:outer ty-node))
                     ;; List of T → mangled ObjC class name. Deterministic
                     ;; function of the element type, no env lookup needed.
                     ;; Registered in atom-env by build-atom-env so
                     ;; infer-type / method-call resolution find it.
                     (str "_Cxc_ListOf" (base-type-name (:inner ty-node)))
                     (fail-nyi! (str "base-type-name: `" (:outer ty-node)
                                     " of ...` — only `List of T` is "
                                     "supported at Layer 5 Step 3")
                                ty-node))
    (fail-nyi! (str "base-type-name: unknown type-node " (:node ty-node)) ty-node)))

(defn- list-container-class-name
  "Mangled ObjC class name for `List of <element-name>`. Kept in sync
   with base-type-name's :type-of branch."
  [element-name]
  (str "_Cxc_ListOf" element-name))

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

(defn- payload-signature
  "Compact comparable representation of a payload shape — vector of
   `[name cxc-type]` pairs in declaration order. Used to catch
   inconsistent payloads across transitions with the same message name."
  [payload]
  (mapv (fn [p] [(:name p) (base-type-name (:type p))]) payload))

(defn- emit-nodes-in
  "Walk any parsed AST subtree and return every `:emit` node whose
   `:target` is nil (untargeted `:>` / `|>` — stream emissions). Skips
   targeted sends. Used both to discover the streams a source atom
   owns (Step 13) and to gate `dispatch_main()` on presence of any
   async work."
  [subtree]
  (let [found (java.util.ArrayList.)]
    (walk/postwalk
     (fn [n]
       (when (and (map? n) (= :emit (:node n)) (nil? (:target n)))
         (.add found n))
       n)
     subtree)
    (vec found)))

(defn- collect-stream-shapes
  "Walk every transition body on this atom, gather all `:>` untargeted
   emit sites, and index them by stream name with payload signature.
   Two emits of the same stream name with different arg-name shapes are
   a design smell — same source-line reads would produce different
   subscriber-side selectors. Fail-nyi on mismatch, parallel to
   collect-message-shapes. Returns `{stream-name {:payload [{:name
   :type}]}}` where each payload entry captures `:name` and an inferred
   `:type` when the arg carried a `k: T` typed shape. Layer 4 payloads
   are typed via the same `{k: T}` grammar receivers use on the receive
   side; if the sender's args-block doesn't carry type info, the payload
   entry has :type nil and downstream lookup falls back on the
   subscriber-side declaration for the C type."
  [atom-name transitions]
  (let [emits (emit-nodes-in transitions)
        ;; Sync `|>` untargeted emits aren't defined for streams (spec
        ;; only ever pairs bare `|>` with a target). Reject early so
        ;; the meaning stays crisp: `:>` is fan-out, no other option.
        _     (doseq [e emits]
                (when-not (:async? e)
                  (fail-nyi! (str "sync stream emit `|> " (:msg e)
                                  "` — Layer 4 streams are async-only "
                                  "(design §18 chose Option A: async fan-out)")
                             e)))]
    (reduce
     (fn [acc e]
       (let [name    (:msg e)
             ;; Emit-site args have shape [{:kind :named/:positional
             ;; :name :value}]. Only named args carry a stream-payload
             ;; key. Positional args on stream emits are legal (spec
             ;; allows bare `:> value` for single-channel), but Layer
             ;; 4 requires named args so the payload shape is
             ;; unambiguous — same rule as message-triggered
             ;; transitions.
             _       (doseq [a (:args e)]
                       (when (= :positional (:kind a))
                         (fail-nyi!
                          (str "positional arg on stream emit `:> " name
                               "` — Layer 4 requires `:> " name "(key: value)` "
                               "named form so payload shape matches subscriber "
                               "receive pattern")
                          e)))
             payload (mapv (fn [a] {:name (:name a)}) (:args e))
             sig     (mapv :name payload)]
         (if-let [existing (get acc name)]
           (do (when (not= (mapv :name (:payload existing)) sig)
                 (fail-nyi!
                  (str "def-atom `" atom-name "` emits stream `" name
                       "` twice with different payload shapes: "
                       (mapv :name (:payload existing)) " vs " sig
                       "; every emit of a stream must carry the same "
                       "keys so subscribers can bind a single receive shape")
                  {:atom atom-name :stream name}))
               acc)
           (assoc acc name {:payload payload}))))
     {}
     emits)))

(defn- collect-message-shapes
  "Walk an atom's message-triggered transitions, grouping by message
   name. Every transition sharing a message name must declare the SAME
   payload shape — different shapes would produce different ObjC
   selectors from cxc source that reads identically, which is a design
   smell. Returns `{msg-name {:payload [{:name :type}] :selector <str>}}`
   for later selector encoding + arg marshalling."
  [atom-name transitions]
  (let [msg-txns (filter #(and (:receive %)
                               (contains? #{"<:" "<|"}
                                          (get-in % [:receive :glyph])))
                         transitions)]
    (reduce
     (fn [acc t]
       (let [src (get-in t [:receive :msg :source])
             msg-name (:name src)          ; already gated to :ident nodes by transition-msg-name
             payload  (or (get-in t [:receive :msg :payload]) [])]
         (if-let [existing (get acc msg-name)]
           (do (when (not= (payload-signature (:payload existing))
                           (payload-signature payload))
                 (fail-nyi!
                  (str "def-atom `" atom-name "` has two transitions on message `"
                       msg-name "` with different payload shapes: "
                       (payload-signature (:payload existing)) " vs "
                       (payload-signature payload) "; cxc source that reads "
                       "the same must map to the same ObjC selector")
                  {:atom atom-name :msg msg-name}))
               acc)
           (assoc acc msg-name {:payload payload}))))
     {}
     msg-txns)))

(defn- def-atom-body-extras
  "Everything in the def-atom body that isn't data:/methods:/transition/
   observable/invariants/use:/nested-atom — e.g. contracts: (Layer 6+).
   These are what remains for later layers to consume."
  [atom-node]
  (remove #(#{:data-section :methods-section :transition :observable
              :invariants-section :use-section :nested-atom} (:node %))
          (:body atom-node)))

(defn- build-atom-env
  "Build the atom-env from every top-level `def X:` — includes `main`
   whenever the program has any other user-declared atoms (so main can
   send to them via the same shape check as any other sender) OR
   whenever main itself declares message-triggered transitions
   (needs a class to receive messages). Prelude atoms are ignored for
   this decision so records-only user programs don't get promoted to
   ObjC classes just because Clock exists in the prelude — user
   programs that DO reference a prelude atom will separately reach
   into it via the shape check and pull main in through referenced
   atoms.

   For pure hello-world (no user atoms, no receive transitions on
   main), main stays out of the env and the old main-atom-body path
   handles it as a plain function body."
  [top-nodes enum-env]
  (let [other-atoms (filter #(and (= :def-atom (:node %))
                                  (not= "main" (:name %))
                                  (not (contains? prelude/atom-names
                                                  (:name %))))
                            top-nodes)
        main-node   (first (filter #(and (= :def-atom (:node %))
                                         (= "main" (:name %)))
                                   top-nodes))
        main-has-message-txn?
        (boolean (some #(and (= :transition (:node %))
                             (contains? #{"<:" "<|"} (get-in % [:receive :glyph])))
                       (:body main-node)))
        ;; A nested slot / use: cap on main brings that atom's lifecycle
        ;; into main's — cascade end + parent-back-ref plumbing require
        ;; main to be a real atom class. This catches the case where
        ;; the user program's ONLY atom references are prelude atoms
        ;; via `def clock: Clock` (post-rename `_Cxc_Clock`).
        main-has-atom-slot-or-cap?
        (boolean (some #(contains? #{:nested-atom :use-section} (:node %))
                       (:body main-node)))
        ;; A List of T field on main is atom-typed (compiler-synth
        ;; container class), so its ivar needs a real class to live on.
        ;; Plain-main-body path can't hold instance state — promote to
        ;; atom class whenever main carries container-typed data fields.
        main-has-list-field?
        (boolean (some (fn [n]
                         (and (= :data-section (:node n))
                              (some #(= :type-of (get-in % [:type :node]))
                                    (:fields n))))
                       (:body main-node)))
        include-main? (or (seq other-atoms)
                          main-has-message-txn?
                          main-has-atom-slot-or-cap?
                          main-has-list-field?)
        atoms    (if include-main?
                   (filter #(= :def-atom (:node %)) top-nodes)
                   other-atoms)
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
             methods       (extract-methods name msec)
             message-index (collect-message-shapes name transitions)
             ;; Streams this atom EMITS (untargeted `:>` in any transition
             ;; body). Indexed by stream name; the ordered list preserves
             ;; declaration order so the emitted ivars + methods have a
             ;; stable footprint. Layer 4 Step 13.
             stream-index  (collect-stream-shapes name transitions)
             stream-order  (vec (distinct (map :msg (emit-nodes-in transitions))))
             ;; Streams this atom SUBSCRIBES to, indexed by the local
             ;; field name whose value is the source atom. Layer 4b uses
             ;; this to synthesize slot setters that (un)register on
             ;; field mutation. Format: {"action" ["cast_completed"], ...}
             subscription-map
             (reduce
              (fn [acc t]
                (let [src (get-in t [:receive :msg :source])]
                  (if (and (= "<:" (get-in t [:receive :glyph]))
                           (= :dot (:node src))
                           (= :ident (get-in src [:target :node])))
                    (update acc (get-in src [:target :name])
                            (fnil conj []) (:field src))
                    acc)))
              {}
              transitions)
             subscription-map (into {} (map (fn [[k v]] [k (vec (distinct v))])
                                            subscription-map))
             ;; Names of fields that are nested-slot children — Step 11
             ;; installs a parent back-ref on each after construction, and
             ;; -transitionToEnd cascades end into each on this atom's end.
             slot-names    (mapv :name nested-slots)]
         (assoc env name {:fields        fields
                          :field-index   (into {} (map (juxt :name identity) fields))
                          :ctor-params   ctor-params
                          :methods       methods
                          :method-index  (into {} (map (juxt :name identity) methods))
                          :observables   observables
                          :invariants    invariants
                          :transitions   transitions
                          :message-index message-index
                          :stream-index  stream-index
                          :stream-order  stream-order
                          :subscription-map subscription-map
                          :slot-names    slot-names
                          :extras        extras})))
     skeleton
     atoms)))

(defn- list-instantiations-from-atom-env
  "Scan every atom-env entry's fields + ctor-params for `List of T`
   usages; return a map {element-type-name → list-class-name}. Feeds
   Step 3 class synthesis: one compiler-generated ObjC class per
   distinct element type."
  [atom-env]
  (let [seed (fn [acc field-or-param]
               (let [ty (:cxc-ty field-or-param)]
                 ;; Field/ctor-param carries the already-mangled class
                 ;; name in :cxc-ty (see base-type-name), so detect via
                 ;; the `_Cxc_ListOf` prefix and recover the element.
                 (if (and (string? ty) (.startsWith ^String ty "_Cxc_ListOf"))
                   (let [elem (subs ty (count "_Cxc_ListOf"))]
                     (assoc acc elem ty))
                   acc)))]
    (reduce (fn [acc [_ info]]
              (as-> acc a
                (reduce seed a (:fields info))
                (reduce seed a (:ctor-params info))))
            {}
            atom-env)))

(defn- synthesize-list-container-entries
  "Given {element-name → list-class-name}, produce atom-env entries
   for each list-class. The synthetic entry carries enough shape for
   method resolution / infer-type: a `count` observable with return
   type Int, a `:kind :list-container` marker, and a `:element-type`
   pointer to the element atom/record/primitive name. The class body
   itself is generated by `synthesize-list-container-class` in the
   emit-classes phase — these entries are skipped in `lower-atom`."
  [instantiations]
  (into {}
        (map (fn [[elem class-name]]
               [class-name
                {:kind             :list-container
                 :element-type     elem
                 :fields           []
                 :field-index      {}
                 :ctor-params      []
                 :methods          []
                 :method-index     {}
                 :observables      []
                 :observables-typed [{:name "count" :type-kw :int}]
                 :observable-index {"count" {:name "count" :type-kw :int}}
                 :invariants       []
                 :transitions      []
                 :message-index    {}
                 :stream-index     {}
                 :stream-order     []
                 :stream-payload-index {}
                 :subscription-map {}
                 :slot-names       []
                 :extras           []}])
             instantiations)))

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

(declare lower-for-each-call lambda-body-stmts unbox-prelude-for-element)

(defn- lower-method-call
  "`target.method(args)` dispatches on target type:
   - record target → C free function call `Record_method(target, args...)`
     with self as first positional arg
   - atom target → ObjC message send `[target methodWithArg:...]` with
     arg-name-derived selector parts, falling back to observable-index
     for zero-arg synthesized getters
   - list-container target + method `for_each` → special-cased block
     literal path (Layer 5 Step 5); the lambda arg gets bound + body
     lowered inside a fresh ctx with the element name in locals
   Named args on method calls fail-nyi — parameters have declaration
   order in the source-level method decl."
  [call-node ctx]
  (let [dot         (:callee call-node)
        target-ty   (infer-type (:target dot) ctx)
        method-name (:field dot)
        args        (:args call-node)
        target-lo   (lower-expr (:target dot) ctx)
        list-cont?  (and (map? target-ty) (:atom target-ty)
                         (= :list-container
                            (get-in ctx [:atom-env (:atom target-ty) :kind])))
        for-each-with-lambda?
        (and list-cont?
             (= "for_each" method-name)
             (= 1 (count args))
             (= :lambda (get-in args [0 :value :node])))]
    (when (some #(= :named (:kind %)) args)
      (fail-nyi! "named args on method call (positional only for now)"
                 call-node))
    (if for-each-with-lambda?
      (lower-for-each-call target-lo target-ty (get-in args [0 :value]) ctx)
      (let [arg-exprs (mapv #(lower-expr (:value %) ctx) args)]
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
          (fail-nyi! "method call on non-record/non-atom target" call-node))))))

(defn- lambda-body-stmts
  "Extract the sequence of body statements from a lambda AST node.
   `body` may be a single statement (from inline `for e in xs:
   <stmt>`) or a `:block` wrapping multi-statement form. Wrapping
   the single case in a vector keeps the downstream emit consistent."
  [lambda-node]
  (let [body (:body lambda-node)]
    (cond
      (nil? body) []
      (and (map? body) (= :block (:node body))) (:stmts body)
      :else [body])))

(defn- unbox-prelude-for-element
  "Given the block's raw id-typed parameter name (`_cxc_e`) and the
   user-declared lambda param name + element type, produce zero or
   one prelude statements that bind the user name at the correct
   primitive/pointer type inside the block body.

   - :int   → `int <name> = [<raw> intValue];`
   - :float → `double <name> = [<raw> doubleValue];`
   - atom   → `<AtomType> *<name> = (<AtomType> *)<raw>;`
   - id/other → no prelude; user name is bound directly to raw id"
  [raw-name user-name element-type-kw]
  (cond
    (= :int element-type-kw)
    [{:node :raw-c
      :text (str "int " user-name " = [" raw-name " intValue];")}]

    (= :float element-type-kw)
    [{:node :raw-c
      :text (str "double " user-name " = [" raw-name " doubleValue];")}]

    (and (map? element-type-kw) (:atom element-type-kw))
    [{:node :raw-c
      :text (str (:atom element-type-kw) " *" user-name
                 " = (" (:atom element-type-kw) " *)" raw-name ";")}]

    :else
    ;; id-typed pass-through: just alias user-name to raw
    [{:node :raw-c
      :text (str "id " user-name " = " raw-name ";")}]))

(defn- lower-for-each-call
  "Special-case lowering for `xs.for_each(e => body)` on a List
   container atom. Produces `[xs cxc_for_each:^(id _cxc_e) { <prelude>
   <body-stmts> }]`. Prelude unboxes the id-typed block param into
   the user's declared name at the element's type; body stmts are
   lowered with `e` bound as a local in ctx (so `infer-type` on
   uses of `e` succeeds).

   Free-variable capture: self and any locals from the enclosing
   scope are captured by ObjC's block copy semantics automatically
   (self as strong reference, primitives by value)."
  [target-lo target-ty lambda-node ctx]
  (let [list-name        (:atom target-ty)
        element-name     (get-in ctx [:atom-env list-name :element-type])
        element-type-kw  (cxc-type->type-kw element-name
                                            (:record-env ctx)
                                            (:atom-env ctx)
                                            (:enum-env ctx)
                                            lambda-node)
        user-param-name  (get-in lambda-node [:params 0 :name])
        raw-param-name   "_cxc_e"
        prelude          (unbox-prelude-for-element raw-param-name
                                                    user-param-name
                                                    element-type-kw)
        body-ctx         (assoc-in ctx [:locals user-param-name] element-type-kw)
        body-nodes       (lambda-body-stmts lambda-node)
        body-stmts       (mapv #(lower-stmt % body-ctx) body-nodes)]
    {:node :objc-msg
     :recv target-lo
     :selector "cxc_for_each:"
     :args [{:node :objc-block-literal
             :params [{:name raw-param-name :c-type "id"}]
             :stmts (into (vec prelude) body-stmts)}]}))

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

(defn- lower-stream-emit
  "Lower an untargeted `:>` — stream emission from the enclosing atom.
   The stream must have been discovered during build-atom-env (i.e. the
   atom has some `:>` site declaring this stream, which this call is
   one of). Payload args are marshalled by name against the emit-site's
   declared payload shape; the enclosing atom is identified via
   `self` in ctx locals (set by lower-atom / lower-message-triggered-
   transitions before descending into body clauses). Lowers to
   `[self cxc_emit_<stream>[With<k>:...]]` — the synthesized emit
   method (see stream-methods-for-atom) owns fan-out."
  [node ctx]
  (let [self-info (get-in ctx [:locals "self"])
        _         (when-not (and (map? self-info) (:atom self-info))
                    (fail-nyi! (str "untargeted `:> " (:msg node)
                                    "` outside a def-atom transition body "
                                    "(no `self` in scope)")
                               node))
        atom-name (:atom self-info)
        stream    (get-in ctx [:atom-env atom-name :stream-index (:msg node)])
        _         (when-not stream
                    ;; Shouldn't happen — collect-stream-shapes registered
                    ;; every emit site — but guard against ctx drift.
                    (fail-nyi! (str "atom `" atom-name "` has no `:> "
                                    (:msg node) "` emit registered "
                                    "(stream-index missed it)")
                               node))
        declared-params (mapv (fn [p] {:name (:name p) :default nil})
                              (:payload stream))
        resolved  (resolve-args-against-params
                   (str "stream emit `:> " (:msg node) "` on `" atom-name "`")
                   declared-params
                   (:args node))
        arg-exprs (mapv (fn [{:keys [value]}] (lower-expr value ctx)) resolved)
        param-names (mapv :name (:payload stream))
        selector  (objc-method-selector (str "cxc_emit_" (:msg node)) param-names)]
    {:node :objc-msg
     :recv {:node :name :sym "self"}
     :selector selector
     :args arg-exprs}))

(defn- lower-send
  "Lower a targeted `:emit` node to a `cxc_send_async` / `cxc_send_sync`
   runtime helper call. Untargeted `:>` fans out through the enclosing
   atom's synthesized stream emit method (Step 13). Payload args are
   matched by name against the receiver's declared `msg{...}` payload
   shape (design §9/§12) — arg order in the sender may differ from
   receiver declaration order. Sync sends are direct method calls under
   Layer 4 single-thread (design §15); Layer 4c upgrades them to real
   `dispatch_sync` when queue targets differ."
  [node ctx]
  (if (nil? (:target node))
    (lower-stream-emit node ctx)
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
        msg-name  (:msg node)
        ;; Cross-atom shape check (Step 10 formalizes diagnostic).
        msg-shape (get-in atom-info [:message-index msg-name])
        _         (when-not msg-shape
                    (fail-nyi! (str "atom `" atom-name "` has no `<: " msg-name
                                    "` message-triggered transition — cannot send")
                               node))
        declared-params (mapv (fn [p] {:name    (:name p)
                                       :default nil})
                              (:payload msg-shape))
        ;; Match sender's args (named/positional) against declared payload.
        resolved  (resolve-args-against-params
                   (str "send `" msg-name "` to `" atom-name "`")
                   declared-params
                   (:args node))
        arg-exprs (mapv (fn [{:keys [value]}] (lower-expr value ctx)) resolved)
        param-names (mapv :name (:payload msg-shape))
        selector  (objc-method-selector msg-name param-names)
        helper    (if (:async? node) "cxc_send_async" "cxc_send_sync")]
    {:node :call
     :fn   helper
     :args [target-lo
            {:node :objc-block-literal
             :stmts [{:node :objc-msg
                      :recv target-lo
                      :selector selector
                      :args arg-exprs}]}]})))

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

(defn- capitalize-first
  [s]
  (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn- field-needs-slot-setter?
  "True when writing this field in a `then:` update must route through
   the synthesized slot setter instead of a direct ivar assignment.
   Applies when the field is a subscription root on the enclosing
   atom's :subscription-map. Other fields keep the simple write."
  [field-name ctx]
  (let [self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))
        info      (get-in ctx [:atom-env atom-name])
        subs      (get-in info [:subscription-map field-name])]
    (boolean (seq subs))))

(defn- list-add-remove-pattern
  "Detect `xs => xs + e` or `xs => xs - e` where xs is a List-typed
   field on self. Returns {:op :add|:remove :element <element-expr>}
   or nil if not the pattern. Layer 5 Step 4 — mutation-in-place
   under the hybrid container shape (design §1)."
  [update ctx]
  (let [field-name (:field update)
        expr       (:expr update)
        self-field (get-in ctx [:self-fields field-name])]
    (when (and self-field
               (string? (:cxc-ty self-field))
               (.startsWith ^String (:cxc-ty self-field) "_Cxc_ListOf")
               (map? expr)
               (= :binop (:node expr))
               (contains? #{"+" "-"} (:op expr))
               (= :ident (get-in expr [:left :node]))
               (= field-name (get-in expr [:left :name])))
      {:op      (case (:op expr) "+" :add "-" :remove)
       :element (:right expr)})))

(defn- lower-list-add-remove
  "Emit `[_field cxc_add:e]` / `[_field cxc_remove:e]` for the
   list-add/remove pattern detected by `list-add-remove-pattern`.
   Element is boxed by type: primitives → NSNumber literal;
   atoms/records lower normally (id-typed pass-through)."
  [update pattern ctx]
  (let [field-name (:field update)
        elem-expr  (:element pattern)
        elem-ty    (infer-type elem-expr ctx)
        boxed      (case elem-ty
                     :int   {:node :objc-num-lit :kind :int
                             :value (:value elem-expr)}
                     :float {:node :objc-num-lit :kind :float
                             :value (:value elem-expr)}
                     ;; Non-primitive element: lower and hand to
                     ;; NSMutableArray directly (ObjC objects work
                     ;; as-is; boxing needed only for primitives).
                     (lower-expr elem-expr ctx))]
    {:node :objc-msg
     :recv {:node :name :sym (str "_" field-name)}
     :selector (case (:op pattern) :add "cxc_add:" :remove "cxc_remove:")
     :args [boxed]}))

(defn- lower-then-update
  "`then: field => expr` → `_field = <expr>;` for the common case.
   For atom-typed subscription-root fields (Layer 4b nullable-slot
   dynamic subscription), routes through the synthesized slot setter
   `-set<Field>:` so it can (un)register subscriptions on old/new
   source values. For `xs => xs + e` / `xs => xs - e` on List-typed
   fields, routes to `-cxc_add:` / `-cxc_remove:` (Step 4 mutation-
   in-place shape — the hybrid container's operation surface)."
  [update ctx]
  (if-let [pat (list-add-remove-pattern update ctx)]
    (lower-list-add-remove update pat ctx)
    (if (field-needs-slot-setter? (:field update) ctx)
      {:node :objc-msg
       :recv {:node :name :sym "self"}
       :selector (str "set" (capitalize-first (:field update)) ":")
       :args [(lower-expr (:expr update) ctx)]}
      {:node :assign
       :lhs  {:node :name :sym (str "_" (:field update))}
       :rhs  (lower-expr (:expr update) ctx)})))

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

(defn- transition-trigger-kind
  "Classify a receive-glyph transition's source pattern:
   - :message — bare ident like `bump`. Direct message-triggered
     transition (Steps 4-7).
   - :stream  — dot expression like `source.beep`. Stream subscription
     (Step 13); source expression names an atom whose stream emits
     drive this transition.

   Anything else (complex expressions, method calls in the source
   position) is out of scope for Layer 4."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident :message
      :dot   :stream
      (fail-nyi! (str "receive-glyph source pattern must be a bare ident "
                      "(message) or a dot expression (stream); got "
                      (:node src))
                 src))))

(defn- transition-msg-name
  "Extract the message-name from a message-triggered receive-glyph
   transition. Only bare-ident sources call this; stream transitions
   go through transition-stream-descriptor."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident (:name src)
      (fail-nyi! (str "expected bare-ident message source (got " (:node src) ")")
                 src))))

(defn- transition-display-name
  "Human-readable identifier for a receive-glyph transition, used in
   diagnostics. Handles both message-triggered (`bump`) and stream-
   subscribed (`source.beep`) shapes without discriminating on kind."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident (:name src)
      :dot   (str (get-in src [:target :name]) "." (:field src))
      (pr-str src))))

(defn- stream-selector
  "Cocoa selector on the SUBSCRIBER side for a stream receive. Encoding:
   `cxc_stream_<streamname>_from_<SourceClass>[With<k1>:<k2>:...]`. The
   `_from_<Source>` suffix scopes the selector to the source class, so
   two receive-glyph transitions on the same subscriber pulling streams
   named identically from different sources compile to distinct selectors
   (Choice 1A, see design analysis)."
  [source-class stream-name payload-keys]
  (objc-method-selector (str "cxc_stream_" stream-name "_from_" source-class)
                        payload-keys))

(defn- stream-emit-selector
  "Cocoa selector on the SOURCE side for a stream emit method:
   `cxc_emit_<streamname>[With<k1>:<k2>:...]`. Called by `:>` emit-site
   lowering; body iterates the subscriber hashtable + cxc_send_async's
   each."
  [stream-name payload-keys]
  (objc-method-selector (str "cxc_emit_" stream-name) payload-keys))

(defn- wildcards-by-target-phase
  "Index wildcards (`-> phase then:/do:`) by their target phase. A
   phase may have zero, one, or (rare) multiple wildcards; multiples
   are concat'd in declaration order (design §6 leaves the count
   unspecified but doesn't forbid — declaration order is the natural
   choice)."
  [transitions]
  (reduce (fn [acc t] (update acc (:target t) (fnil conj []) t))
          {}
          (filter wildcard-on-enter? transitions)))

(defn- inline-wildcard-then-stmts
  "Then-clauses from all wildcards targeting phase X, in order. Fires
   BEFORE the specific transition's then: so the specific can override
   (design §6 emit ordering: wildcard defaults, then specific overrides)."
  [wildcards ctx]
  (vec (mapcat (fn [w]
                 (mapcat (fn [c] (mapv #(lower-then-update % ctx) (:updates c)))
                         (filter #(= :then-clause (:node %)) (:body w))))
               wildcards)))

(defn- inline-wildcard-do-stmts
  "Do-clauses from all wildcards targeting phase X, in order. Fires
   BEFORE the specific transition's do: — wildcard's side effects
   establish invariants of the phase, then the specific transition
   layers its own on top."
  [wildcards ctx]
  (vec (mapcat (fn [w]
                 (mapcat (fn [c] (lower-do-clause c ctx))
                         (filter #(= :do-clause (:node %)) (:body w))))
               wildcards)))

(defn- lower-transition-branch
  "Lower one transition into a `:transition-branch` — an `if` block on
   the source phase (+ combined header/body `when:` guards) that runs
   the transition's body and returns. Multiple transitions on the same
   selector are stacked in first-match order inside one method (design
   §12: first-match dispatch).

   Emit order per §6:
     1. wildcard then: (for target phase)   \\
     2. specific then:                        }-- state changes
     3. phase update (source → target)      /
     4. wildcard do:  (for target phase)    \\
     5. specific do:                          }-- side effects
     6. post-transition invariants (Step 14)

   Payload names in `msg{k: T}` bind as locals visible in when:/then:/do:
   (design §12). Their type-kws land in the ctx locals so infer-type
   resolves references, and lower-expr emits them as `{:node :name :sym k}`."
  [atom-name t ctx wildcards-by-phase]
  (when (not= :phase (get-in t [:source :kind]))
    (fail-nyi! (str "message-triggered transition on def-atom `" atom-name
                    "` must have an explicit source phase (got "
                    (get-in t [:source :kind]) ")")
               t))
  (let [source-name  (get-in t [:source :name])
        target       (:target t)
        msg          (get-in t [:receive :msg])
        payload      (or (:payload msg) [])
        payload-locals (into {} (map (fn [p]
                                       [(:name p)
                                        (cxc-type->type-kw (base-type-name (:type p))
                                                           (:record-env ctx)
                                                           (:atom-env ctx)
                                                           (:enum-env ctx)
                                                           p)])
                                     payload))
        ;; `self` + payload names in the branch body — infer-type needs
        ;; them for guard/then/do expressions that reference payload.
        ctx'         (assoc ctx :locals
                            (merge (:locals ctx)
                                   payload-locals
                                   {"self" {:atom atom-name}}))
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
        ;; Wildcards inline only when the transition actually CHANGES
        ;; phase (target != source). Self-loops don't count as "entering"
        ;; the phase — the atom was already there — so a wildcard's `do:`
        ;; doesn't rerun on every message that keeps the phase the same.
        ;; Without this, `-> alive do: self |> bump` combined with
        ;; `alive <: bump -> alive` would recurse infinitely.
        target-wildcards (if (= target source-name)
                           []
                           (get wildcards-by-phase target []))
        w-then-stmts (inline-wildcard-then-stmts target-wildcards ctx')
        w-do-stmts   (inline-wildcard-do-stmts   target-wildcards ctx')
        ;; target=end routes through -transitionToEnd (design §7.3);
        ;; that method owns phase-set, `-> end` wildcard, cascade,
        ;; parent notify. Specific then:/do: on the target=end
        ;; transition would run OUTSIDE that flow — order-sensitive
        ;; and confusing — so reject for now (users fold side effects
        ;; into the `-> end do:` wildcard).
        end-target?  (= "end" target)
        _            (when (and end-target? (or (seq then-stmts) (seq do-stmts)))
                       (fail-nyi!
                        (str "specific `then:`/`do:` on a target=`end` transition "
                             "(atom `" atom-name "`, trigger `"
                             (transition-display-name t)
                             "`) — end handling runs through -transitionToEnd; "
                             "put side effects on the `-> end do:` wildcard instead")
                        t))
        ;; Post-transition invariant check (design §14). Skipped for
        ;; target=end — the transitionToEnd path owns invariant checks
        ;; on its own trajectory.
        check-invariants-stmt {:node :objc-msg
                               :recv {:node :name :sym "self"}
                               :selector "checkInvariants"
                               :args []}
        raw-body     (cond-> []
                       (seq w-then-stmts) (into w-then-stmts)
                       (seq then-stmts)   (into then-stmts)
                       phase-update       (conj phase-update)
                       (seq w-do-stmts)   (into w-do-stmts)
                       (seq do-stmts)     (into do-stmts)
                       :always            (conj check-invariants-stmt))
        ;; BEAM-style @try/@catch (design §20 / Step 15). Wraps the
        ;; body so any runtime exception routes the atom into
        ;; -transitionToEnd, leaving the parent slot to nil and the
        ;; process running. End-target branches don't need wrapping —
        ;; they're just `[self transitionToEnd]`, and transitionToEnd
        ;; owns its own catch (loop-guard variant).
        body         (if end-target?
                       [{:node :objc-msg
                         :recv {:node :name :sym "self"}
                         :selector "transitionToEnd"
                         :args []}]
                       [{:node :end-catch-wrap :body raw-body}])]
    {:node        :transition-branch
     :source-phase-const (phase-constant-name atom-name source-name)
     :guard       guard-lo
     :body        body}))

(defn- resolve-stream-source
  "The receive-glyph's source is `source.event` (a :dot node). Walk
   the target to determine which atom class emits the stream; must
   be an atom-typed data-field / cap on the enclosing atom. Nullable
   slots are supported at Layer 4b via slot setters that re-register
   subscriptions on assignment. Returns `{:source-class C
   :source-field F}` when legal, else fails."
  [atom-name enclosing-fields dot-src]
  (let [target (:target dot-src)
        _      (when-not (= :ident (:node target))
                 (fail-nyi! (str "receive-glyph source `" (:field dot-src)
                                 "` must be `<field>." (:field dot-src)
                                 "` where <field> names a data-field / cap "
                                 "on the enclosing atom (got "
                                 (:node target) ")")
                            dot-src))
        fname  (:name target)
        field  (get enclosing-fields fname)
        _      (when-not field
                 (fail-nyi! (str "receive-glyph source `" fname "." (:field dot-src)
                                 "` — no data-field / cap named `" fname
                                 "` on atom `" atom-name "`")
                            dot-src))
        cxc-ty (:cxc-ty field)]
    {:source-class cxc-ty
     :source-field fname}))

(defn- validate-stream-subscribe
  "Cross-check a stream subscription against the source atom's
   stream-index: source class must exist, stream must be declared
   (i.e. source atom has some `:>` emit for it), payload keys AND
   inferred types must match the subscriber's declared payload.
   Fail-nyi with a diagnostic naming the mismatch — parallels the
   cross-atom message shape check at Step 10."
  [atom-name t {:keys [source-class]} ctx]
  (let [msg          (get-in t [:receive :msg])
        stream-name  (get-in msg [:source :field])
        payload      (or (:payload msg) [])
        source-info  (get-in ctx [:atom-env source-class])
        _            (when-not source-info
                       (fail-nyi! (str "receive-glyph source `" source-class
                                       "` is not a def-atom in the program")
                                  t))
        stream       (get-in source-info [:stream-index stream-name])
        _            (when-not stream
                       (fail-nyi! (str "atom `" source-class "` emits no `:> "
                                       stream-name "` stream — receive-glyph "
                                       "`" (:source-field t) "." stream-name
                                       "` on `" atom-name "` has no source")
                                  t))
        expected-keys (mapv :name (:payload stream))
        got-keys      (mapv :name payload)
        _            (when (not= expected-keys got-keys)
                       (fail-nyi! (str "receive-glyph payload shape on atom `"
                                       atom-name "` subscribing to `"
                                       source-class "." stream-name
                                       "` differs from source-side emit shape: "
                                       "declared " got-keys ", source emits "
                                       expected-keys)
                                  t))
        ;; Type check: subscriber's declared types vs source's inferred.
        source-typed (get-in source-info [:stream-payload-index stream-name])
        _            (when (seq payload)
                       (doseq [[sub-p src-p] (map vector payload source-typed)]
                         (let [sub-ty (cxc-type->type-kw
                                       (base-type-name (:type sub-p))
                                       (:record-env ctx) (:atom-env ctx)
                                       (:enum-env ctx) sub-p)
                               src-ty (:type-kw src-p)]
                           (when (not= sub-ty src-ty)
                             (fail-nyi!
                              (str "receive-glyph payload key `" (:name sub-p)
                                   "` on atom `" atom-name "` subscribing to `"
                                   source-class "." stream-name
                                   "` declared as " (pr-str sub-ty)
                                   ", but source emits it as " (pr-str src-ty))
                              t)))))]
    {:source-class source-class
     :stream-name  stream-name
     :payload      payload}))

(defn- lower-receive-glyph-transitions
  "Lower every receive-glyph transition on this atom, grouping by
   selector so `alive <: bump ...` and `dying <: bump ...` collapse
   into one ObjC method with two first-match branches (design §12).
   Handles both discriminants of receive-glyph source (Step 13):
     - :message — bare-ident (`bump`), receiver selector = `<msg>[With...]`
     - :stream  — dot expression (`source.beep`), receiver selector =
       `cxc_stream_<stream>_from_<Source>[With...]`, plus a
       subscription tuple registered later in cxc_runtimeBinding.
   Returns `{:methods [...], :stream-subscriptions [...]}`. The
   subscription vector carries `{:source-field :source-class
   :stream-name}` per unique subscription (dedup'd, so multiple
   receive sites on the same source-class.stream register once)."
  [atom-name transitions ctx]
  (let [receive-txns (filter #(and (:receive %)
                                   (contains? #{"<:" "<|"}
                                              (get-in % [:receive :glyph])))
                             transitions)
        ;; Under Layer 4's single-thread model (queues target main),
        ;; `<:` and `<|` are functionally equivalent: the atom's serial
        ;; queue already ensures "no other transition runs meanwhile"
        ;; that spec §11 attributes to `<|`. Blocks dispatched via
        ;; cxc_send_async run in the enqueue order; cxc_send_sync
        ;; already runs the block synchronously. Layer 4c multi-thread
        ;; will need actual dispatch_semaphore rendezvous for `<|` to
        ;; distinguish from `<:` semantically.
        wildcards-by-phase (wildcards-by-target-phase transitions)
        enclosing-fields (get-in ctx [:self-fields])
        ;; Descriptor per transition: {:kind :group-key :source-selector-info}.
        descs      (mapv
                    (fn [t]
                      (case (transition-trigger-kind t)
                        :message
                        (let [msg-name (transition-msg-name t)
                              payload  (or (get-in t [:receive :msg :payload]) [])
                              param-names (mapv :name payload)]
                          {:kind        :message
                           :txn         t
                           :group-key   [:message msg-name]
                           :selector    (objc-method-selector msg-name param-names)
                           :payload     payload})
                        :stream
                        (let [src-info (resolve-stream-source
                                        atom-name enclosing-fields
                                        (get-in t [:receive :msg :source]))
                              validated (validate-stream-subscribe
                                         atom-name t src-info ctx)
                              stream-name (:stream-name validated)
                              source-class (:source-class validated)
                              payload    (:payload validated)
                              param-names (mapv :name payload)]
                          {:kind         :stream
                           :txn          t
                           :group-key    [:stream source-class stream-name]
                           :selector     (stream-selector source-class stream-name param-names)
                           :payload      payload
                           :source-class source-class
                           :source-field (:source-field src-info)
                           :stream-name  stream-name})))
                    receive-txns)
        by-key    (reduce (fn [acc d] (update acc (:group-key d) (fnil conj []) d))
                          {}
                          descs)
        key-order (distinct (mapv :group-key descs))
        methods   (mapv
                   (fn [k]
                     (let [group (get by-key k)
                           first-d (first group)
                           payload (:payload first-d)
                           method-params (mapv
                                          (fn [p]
                                            {:name   (:name p)
                                             :c-type (cxc-type->c
                                                      (:type p)
                                                      (:record-env ctx)
                                                      (:atom-env ctx)
                                                      (:enum-env ctx))})
                                          payload)
                           branches (mapv #(lower-transition-branch
                                            atom-name (:txn %) ctx
                                            wildcards-by-phase)
                                          group)]
                       {:node        :objc-method
                        :selector    (:selector first-d)
                        :return-type "void"
                        :params      method-params
                        :body        branches}))
                   key-order)
        ;; Dedup stream subscriptions by (source-field, stream-name):
        ;; multiple receive-glyphs on the same source.stream on THIS
        ;; atom register only once (the ObjC method holds all branches).
        subs (->> descs
                  (filter #(= :stream (:kind %)))
                  (map (fn [d] (select-keys d [:source-field :source-class
                                               :stream-name])))
                  distinct
                  vec)]
    {:methods              methods
     :stream-subscriptions subs}))

(defn- transition-payload-locals
  "Locals a receive-glyph transition body sees, keyed by payload name →
   inferred :type-kw. Used to build ctx for stream emit type inference
   inside receive-glyph bodies (design §9/§12 payload binding)."
  [t record-env atom-env enum-env]
  (let [payload (or (get-in t [:receive :msg :payload]) [])]
    (into {} (map (fn [p]
                    [(:name p)
                     (cxc-type->type-kw (base-type-name (:type p))
                                        record-env atom-env enum-env p)])
                  payload))))

(defn- infer-streams-in-atom-env
  "Second pass over atom-env: for every atom, walk every emit site in
   the ctx that would exist at that site's textual position and infer
   each named arg's :type-kw + :c-type. Group by stream name; every
   emit of the same stream must agree on each key's type
   (subscriber-side selector encodes types into a single signature).

   Ctx per site (mirrors the lowering ctx):
   - wildcard-on-enter targeting the initial phase → self-fields +
     ctor-params (matches ctx-in-init)
   - wildcard-on-enter targeting a non-initial phase → self-fields
     only. Non-initial wildcards inline at multiple transition sites
     with different payload bindings; picking one arbitrarily would
     miscompile — so payload-consuming emits inside such wildcards
     fail during infer-type on the unknown local (users move them
     to the specific transition's `do:`).
   - receive-glyph transition → self-fields + payload locals

   Populates :stream-payload-index on each atom-env entry:
     {stream-name [{:name :type-kw :c-type} ...]}
   Must run AFTER infer-observables-in-atom-env because emit args
   may reference observables (typed on that pass)."
  [atom-env record-env enum-env]
  (reduce-kv
   (fn [env atom-name info]
     (let [self-fields (into {} (map (juxt :name identity) (:fields info)))
           self-plus-obs (into self-fields
                               (map (juxt :name identity)
                                    (:observables-typed info)))
           ctor-locals (into {} (map (fn [p]
                                       [(:name p)
                                        (cxc-type->type-kw (:cxc-ty p)
                                                           record-env atom-env
                                                           enum-env p)])
                                     (:ctor-params info)))
           self-local  {"self" {:atom atom-name}}
           base-ctx    {:record-env record-env :atom-env atom-env
                        :enum-env enum-env
                        :self-fields self-plus-obs}
           initial-phase (initial-phase-for (:transitions info))
           ctx-for
           (fn [t]
             (cond
               (and (wildcard-on-enter? t) (= initial-phase (:target t)))
               (assoc base-ctx :locals (merge ctor-locals self-local))

               (wildcard-on-enter? t)
               (assoc base-ctx :locals self-local)

               :else
               (assoc base-ctx :locals
                      (merge self-local
                             (transition-payload-locals
                              t record-env atom-env enum-env)))))
           sites (mapcat
                  (fn [t]
                    (let [ctx (ctx-for t)]
                      (map (fn [e]
                             {:stream (:msg e)
                              :emit   e
                              :args   (mapv (fn [a]
                                              {:name (:name a)
                                               :type-kw (infer-type (:value a) ctx)})
                                            (:args e))})
                           (emit-nodes-in [t]))))
                  (:transitions info))
           typed (reduce
                  (fn [acc {:keys [stream args emit]}]
                    (if-let [existing (get acc stream)]
                      (do (doseq [[i {:keys [name type-kw]}]
                                  (map-indexed vector args)]
                            (let [prev (get-in existing [i :type-kw])]
                              (when (not= prev type-kw)
                                (fail-nyi!
                                 (str "def-atom `" atom-name
                                      "` emits stream `" stream
                                      "` with inconsistent type on key `"
                                      name "`: previously " (pr-str prev)
                                      ", here " (pr-str type-kw)
                                      "; every emit of the same stream must "
                                      "agree on payload key types")
                                 emit))))
                          acc)
                      (assoc acc stream
                             (mapv (fn [{:keys [name type-kw]}]
                                     {:name    name
                                      :type-kw type-kw
                                      :c-type  (type-kw->c-type type-kw emit)})
                                   args))))
                  {}
                  sites)]
       (assoc env atom-name
              (assoc info :stream-payload-index typed))))
   atom-env atom-env))

;; ---------------------------------------------------------------------------
;; Stream source-side synthesis (Step 13, Choice 3A + generic dispatch).
;;
;; A source atom owns one NSHashTable (weak refs) per stream it emits.
;; The compiler synthesizes four kinds of members per atom:
;;   1. Ivar/property `_stream_subscribers_<name>` per stream
;;   2. Init statement in cxc_structuralSetup: hashtable allocation
;;   3. Emit method `-cxc_emit_<name>[With<k>:...]` iterating + fan-out
;;   4. Subscribe methods:
;;        - Per-stream `-cxc_addSubscriberFor_<name>:` (compiled sites)
;;        - Generic  `-cxc_addSubscriberToStream:sub:` (restore/reflection)
;;
;; All subscribe bodies wrap addObject in dispatch_sync(_own_queue, ^{})
;; to future-proof against Layer 4c multi-thread — free under single-
;; thread (dispatch_sync-to-current-queue is fast). See design analysis
;; §4.5 for the rationale.
;; ---------------------------------------------------------------------------

(defn- stream-subscribers-ivar-name
  [stream-name]
  (str "_stream_subscribers_" stream-name))

(defn- stream-subscribers-prop
  "Per-stream `NSHashTable *_stream_subscribers_<name>` property.
   `strong` under ARC because the hashtable itself is owned by this
   atom; weak semantics are on the HASHTABLE's stored subscribers
   (configured via +weakObjectsHashTable), not on the container."
  [stream-name]
  {:name   (subs (stream-subscribers-ivar-name stream-name) 1)  ; drop leading _
   :c-type "NSHashTable *"
   :attrs  ["nonatomic" "strong"]})

(defn- stream-source-methods
  "Synthesize the per-stream ivars, init statements, and methods for
   an atom that emits `streams`. `typed-payloads` maps stream-name →
   inferred payload [{:name :type-kw :c-type} ...] (from
   infer-stream-payload-types). Callers wire the returned pieces into
   the class's :properties, cxc_structuralSetup body, and :methods
   list."
  [source-class stream-order typed-payloads]
  (if (empty? stream-order)
    {:properties [] :init-stmts [] :methods []}
    (let [props     (mapv stream-subscribers-prop stream-order)
          init-stmts (mapv (fn [nm]
                             {:node :hashtable-init
                              :ivar (stream-subscribers-ivar-name nm)})
                           stream-order)
          emit-methods
          (mapv (fn [nm]
                  (let [payload (get typed-payloads nm [])
                        keys    (mapv :name payload)
                        method-params (mapv (fn [p] {:name (:name p)
                                                     :c-type (:c-type p)})
                                            payload)]
                    {:node        :objc-method
                     :selector    (stream-emit-selector nm keys)
                     :return-type "void"
                     :params      method-params
                     :body        [{:node       :stream-fanout
                                    :ivar       (stream-subscribers-ivar-name nm)
                                    :selector   (stream-selector source-class nm keys)
                                    :arg-names  keys}]}))
                stream-order)
          per-stream-add
          (mapv (fn [nm]
                  {:node        :objc-method
                   :selector    (str "cxc_addSubscriberFor_" nm ":")
                   :return-type "void"
                   :params      [{:name "sub" :c-type "id"}]
                   :body        [{:node :stream-add-sub
                                  :ivar (stream-subscribers-ivar-name nm)}]})
                stream-order)
          per-stream-remove
          ;; Layer 4b: nullable-slot dynamic subscription. When a slot
          ;; is reassigned, the setter unsubs from the old source. Sends
          ;; are nil-message-safe (Cocoa), so callers pass whatever's in
          ;; the slot without pre-checking.
          (mapv (fn [nm]
                  {:node        :objc-method
                   :selector    (str "cxc_removeSubscriberFor_" nm ":")
                   :return-type "void"
                   :params      [{:name "sub" :c-type "id"}]
                   :body        [{:node :stream-remove-sub
                                  :ivar (stream-subscribers-ivar-name nm)}]})
                stream-order)
          generic-add
          {:node        :objc-method
           :selector    "cxc_addSubscriberToStream:sub:"
           :return-type "void"
           :params      [{:name "streamName" :c-type "NSString *"}
                         {:name "sub"        :c-type "id"}]
           :body        [{:node     :stream-add-sub-dispatch
                          :branches (mapv (fn [nm]
                                            {:stream-name nm
                                             :selector    (str "cxc_addSubscriberFor_"
                                                               nm ":")})
                                          stream-order)}]}
          generic-remove
          {:node        :objc-method
           :selector    "cxc_removeSubscriberFromStream:sub:"
           :return-type "void"
           :params      [{:name "streamName" :c-type "NSString *"}
                         {:name "sub"        :c-type "id"}]
           :body        [{:node     :stream-add-sub-dispatch
                          :branches (mapv (fn [nm]
                                            {:stream-name nm
                                             :selector    (str "cxc_removeSubscriberFor_"
                                                               nm ":")})
                                          stream-order)}]}]
      {:properties props
       :init-stmts init-stmts
       :methods    (-> emit-methods
                       (into per-stream-add)
                       (into per-stream-remove)
                       (conj generic-add generic-remove))})))

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

(defn- lower-list-literal-init
  "Lower `xs: List of T = [e1, e2, ...]` in a data-field default.
   Produces `_xs = [[_Cxc_ListOfT alloc] initWithElements:@[<boxed items>]]`.
   Element boxing follows the element type: primitives → NSNumber
   literal (`@n`); records/atoms/enums land in later steps as
   game-toys forces the shape."
  [field element-name items ctx]
  (let [class-name (list-container-class-name element-name)
        box        (fn [item-node]
                     (let [ty (infer-type item-node ctx)]
                       (cond
                         (= :int ty)
                         {:node :objc-num-lit :kind :int
                          :value (:value item-node)}
                         (= :float ty)
                         {:node :objc-num-lit :kind :float
                          :value (:value item-node)}
                         :else
                         (fail-nyi! (str "List of `" element-name "` literal — "
                                         "element type " (pr-str ty) " not "
                                         "boxable at Layer 5 Step 3")
                                    item-node))))
        boxed      (mapv box items)
        array-lit  {:node :objc-array-literal :items boxed}]
    {:node :assign
     :lhs  {:node :name :sym (str "_" (:name field))}
     :rhs  {:node :objc-init-with
            :class class-name
            :args  [{:name "elements" :value array-lit}]}}))

(defn- field-is-list-of?
  "Field's :cxc-ty is a `List of T` mangled class name."
  [field]
  (and (string? (:cxc-ty field))
       (.startsWith ^String (:cxc-ty field) "_Cxc_ListOf")))

(defn- field-list-element-name [field]
  (subs (:cxc-ty field) (count "_Cxc_ListOf")))

(defn- default-init-stmt-for [field ctx]
  (let [d (:default field)]
    (cond
      ;; List of T with a list literal default: synthesize a
      ;; -initWithElements: call instead of dropping through to
      ;; lower-expr (which doesn't know list-literal nodes).
      (and (field-is-list-of? field)
           (map? d) (= :list (:node d)))
      (lower-list-literal-init field
                               (field-list-element-name field)
                               (:items d)
                               ctx)

      :else
      (let [rhs (if d (lower-expr d ctx) {:node :int :value 0})]
        {:node :assign
         :lhs  {:node :name :sym (str "_" (:name field))}
         :rhs  rhs}))))

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
   ;; Fires from init AND post-transition (Step 14); label stays generic.
   :label (str atom-name " invariant #" idx " failed")})

(defn- class-name-for-atom
  "Emitted ObjC class name. Regular atoms use their cxc name verbatim.
   Main gets `CxcMain` — `main` collides with C's main() symbol and
   also isn't idiomatic ObjC (class names are UpperCamel)."
  [atom-name]
  (if (= "main" atom-name) "CxcMain" atom-name))

(defn- lower-atom [atom-name ctx & {:keys [is-main?]}]
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
    ;; Message-triggered transitions become instance methods (Step 4/7).
    ;; Wildcard on-enter (implicit source): initial-phase wildcards inline
    ;; into cxc_stateInit; non-initial-phase wildcards inline at each
    ;; message-triggered branch that targets that phase (Step 8). A
    ;; wildcard whose target phase is never entered is dead code — a
    ;; lint concern (Layer 5+), not a hard error here.
    (let [unhandled (remove #(or (message-triggered? %)
                                 (wildcard-on-enter? %))
                            transitions)]
      (when (seq unhandled)
        (fail-nyi! (str "def-atom `" atom-name "` has transitions Layer 4 "
                        "doesn't handle yet (sync-receive `<|` → Layer 4b): "
                        (mapv #(select-keys % [:source :receive :target]) unhandled))
                   {:atom atom-name :unhandled unhandled})))
    (let [phase-names     (collect-phase-names transitions)
          slot-names      (set (:slot-names rec))
          queue-init-stmt {:node :queue-init :ivar "_own_queue"}
          phase-init-stmt {:node :assign
                           :lhs  {:node :name :sym "_phase"}
                           :rhs  {:node :name
                                  :sym  (phase-constant-name atom-name initial-phase)}}
          ;; For nested-slot fields, install this atom as the child's
          ;; parent right after the child is constructed. The install
          ;; setter stashes weak parent + slot name on the child so
          ;; the child's -transitionToEnd can cascade back (design §7.2).
          install-parent-for-slot
          (fn [field]
            (when (contains? slot-names (:name field))
              [{:node :objc-msg
                :recv {:node :name :sym (str "_" (:name field))}
                :selector "cxc_installParent:inSlot:"
                :args [{:node :name :sym "self"}
                       ;; NSString literal, not C string — slotName is
                       ;; typed NSString * in cxc_installParent:inSlot:.
                       {:node :objc-str :value (:name field)}]}]))
          default-stmts   (vec (mapcat (fn [f]
                                         (cons (default-init-stmt-for f ctx-in-init)
                                               (install-parent-for-slot f)))
                                       (:fields rec)))
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
          ;; Parent back-ref (weak → parent isn't kept alive by children;
          ;; child is removed from parent's slot via nilSlot: on end).
          ;; Slot name is copied — defensive against callers passing
          ;; mutable NSStrings; canonical `@"..."` literals no-op it.
          parent-prop     {:name "parent"           :c-type "id"
                           :attrs ["nonatomic" "weak"]}
          parent-slot-prop {:name "parent_slot_name" :c-type "NSString *"
                            :attrs ["nonatomic" "copy"]}
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
          ;; Step 13 stream-source infrastructure. Every atom that
          ;; emits any `:>` gets per-stream ivars, a hashtable init in
          ;; structuralSetup, an emit method, per-stream + generic
          ;; subscribe methods. Payload types on the emit method
          ;; signature are inferred across all emit sites in this
          ;; atom (Step 13 follow-up) — see infer-stream-payload-types.
          stream-order    (:stream-order rec)
          typed-payloads  (:stream-payload-index rec)
          stream-parts    (stream-source-methods
                           (class-name-for-atom atom-name)
                           stream-order typed-payloads)
          stream-props        (:properties stream-parts)
          stream-init-stmts   (:init-stmts stream-parts)
          stream-source-mths  (:methods stream-parts)
          ;; Layer 4b: slot setters for atom-typed subscription-root
          ;; fields. Each maps to a `-set<Field>:(<Type>)newValue`
          ;; method that unsubs self from every stream on the old
          ;; source value, assigns the ivar, and subs self to every
          ;; stream on the new source value. Cocoa nil-messaging keeps
          ;; both branches safe on the nil case (start-nil or transition
          ;; -to-nil), so no explicit guards are needed.
          slot-setter-methods
          (mapv (fn [[field-name streams]]
                  (let [field-info (get-in rec [:field-index field-name])
                        c-type     (:c-type field-info)]
                    {:node        :objc-method
                     :selector    (str "set" (capitalize-first field-name) ":")
                     :return-type "void"
                     :params      [{:name "newValue" :c-type c-type}]
                     :body        [{:node    :slot-setter-body
                                    :ivar    (str "_" field-name)
                                    :streams streams}]}))
                (:subscription-map rec))
          setup-method    {:node :objc-method :selector "cxc_structuralSetup"
                           :return-type "void" :params []
                           :body (-> [queue-init-stmt phase-init-stmt]
                                     (into stream-init-stmts))}
          state-method    {:node :objc-method :selector state-selector
                           :return-type "void"
                           :params (mapv (fn [p] {:name (:name p) :c-type (:c-type p)})
                                         ctor-params)
                           :body (into default-stmts on-enter-stmts)}
          ;; Factor invariants into -checkInvariants (design §14) so
          ;; init AND every message-triggered transition can call it —
          ;; duplication-free, single source of truth. cxc_runtimeBinding
          ;; forwards to it so the check runs at end-of-construction.
          check-invariants-method {:node :objc-method
                                   :selector "checkInvariants"
                                   :return-type "void" :params []
                                   :body invariant-stmts}
          txn-result      (lower-receive-glyph-transitions
                           atom-name transitions ctx-with-self)
          txn-methods     (:methods txn-result)
          stream-subs     (:stream-subscriptions txn-result)
          ;; Subscribe registrations run in cxc_runtimeBinding BEFORE
          ;; the invariant check. Timing: this atom's stateInit has
          ;; already run (so nested-slot sources like `_source` exist),
          ;; source atom's own runtimeBinding also ran (see design §7.4).
          ;; Any events source dispatches to its queue during its own
          ;; init will still be picked up because the queue targets main
          ;; and dispatch_main hasn't started yet — by the time it does,
          ;; we're subscribed.
          sub-register-stmts
          (mapv (fn [s]
                  {:node :objc-msg
                   :recv {:node :name :sym (str "_" (:source-field s))}
                   :selector (str "cxc_addSubscriberFor_" (:stream-name s) ":")
                   :args [{:node :name :sym "self"}]})
                stream-subs)
          bind-method     {:node :objc-method :selector "cxc_runtimeBinding"
                           :return-type "void" :params []
                           :body (-> []
                                     (into sub-register-stmts)
                                     (conj (self-msg-noargs "checkInvariants")))}
          init-shell      [(self-msg-noargs "cxc_structuralSetup")
                           state-init-msg
                           (self-msg-noargs "cxc_runtimeBinding")]
          ;; -cxc_installParent:inSlot: setter. Called by the PARENT
          ;; right after constructing this atom in its cxc_stateInit,
          ;; so this atom can cascade nil back on -> end.
          install-method  {:node :objc-method
                           :selector "cxc_installParent:inSlot:"
                           :return-type "void"
                           :params [{:name "parent"    :c-type "id"}
                                    {:name "slotName"  :c-type "NSString *"}]
                           :body [{:node :assign
                                   :lhs {:node :name :sym "_parent"}
                                   :rhs {:node :name :sym "parent"}}
                                  {:node :assign
                                   :lhs {:node :name :sym "_parent_slot_name"}
                                   :rhs {:node :objc-msg
                                         :recv {:node :name :sym "slotName"}
                                         :selector "copy"
                                         :args []}}]}
          ;; -transitionToEnd (design §7.3). Idempotent; sets phase to
          ;; end constant; runs the `-> end` wildcard (if declared);
          ;; cascades end into every nested-slot child; notifies parent
          ;; (weak back-ref) to nil the slot holding self. Step 16
          ;; layers main-atom cxc_shutdown() on top.
          end-const       (phase-constant-name atom-name "end")
          end-wildcards   (get (wildcards-by-target-phase transitions) "end" [])
          end-wildcard-then (inline-wildcard-then-stmts end-wildcards ctx-in-init)
          end-wildcard-do   (inline-wildcard-do-stmts   end-wildcards ctx-in-init)
          cascade-stmts   (mapv (fn [slot-name]
                                  {:node :end-cascade-child :slot-name slot-name})
                                (:slot-names rec))
          end-body        (-> []
                              (into end-wildcard-then)
                              (conj {:node :assign
                                     :lhs {:node :name :sym "_phase"}
                                     :rhs {:node :name :sym end-const}})
                              (into end-wildcard-do)
                              (into cascade-stmts)
                              (conj {:node :end-cascade-parent}
                                    {:node :objc-msg
                                     :recv {:node :name :sym "self"}
                                     :selector "checkInvariants"
                                     :args []})
                              ;; Main atom's end shuts down the process
                              ;; (design §19). exit(0) after cascade so
                              ;; children get a chance to run their -> end
                              ;; handlers — best-effort under async cascade
                              ;; (a full sync shutdown needs `<|` in 4b).
                              (cond-> is-main?
                                (conj {:node :call
                                       :fn "cxc_shutdown" :args []})))
          ;; Idempotency guard OUTSIDE the try/catch — a re-entry from
          ;; within a raised catch (e.g. our own cascade or invariant
          ;; check throws) would loop otherwise. Design §20 loop-guard.
          end-method      {:node :objc-method
                           :selector "transitionToEnd"
                           :return-type "void" :params []
                           :body [{:node :phase-guard-eq
                                   :phase-const end-const :ret "return;"}
                                  {:node :end-catch-log-only :body end-body}]}
          ;; -nilSlot: parent-side setter. Compile-time switch on the
          ;; known slot names (`isEqualToString:` — slotName may be a
          ;; child-copied NSString, not pointer-equal to a canonical
          ;; literal).
          slot-clear-stmts
          (mapv (fn [slot-name]
                  {:node :nil-slot-branch :slot-name slot-name})
                (:slot-names rec))
          nil-slot-method {:node :objc-method
                           :selector "nilSlot:"
                           :return-type "void"
                           :params [{:name "slotName" :c-type "NSString *"}]
                           :body slot-clear-stmts}]
      {:node            :objc-class
       :name            (class-name-for-atom atom-name)
       :superclass      "NSObject"
       :phase-constants (mapv (fn [p] {:name (phase-constant-name atom-name p)
                                       :value p}) phase-names)
       :properties      (-> [phase-prop own-queue-prop parent-prop parent-slot-prop]
                            (into data-props)
                            (into stream-props))
       :init-params     (mapv (fn [p] {:name (:name p) :c-type (:c-type p)}) ctor-params)
       :init-body       init-shell
       :methods         (-> [setup-method state-method bind-method
                             check-invariants-method
                             install-method end-method nil-slot-method]
                            (into user-methods)
                            (into obs-methods)
                            (into txn-methods)
                            (into stream-source-mths)
                            (into slot-setter-methods))})))

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

(defn- names-mentioned-in
  "Set of :ident-node names and :type-ref-node names anywhere in a
   subtree that fall in the given name-set. Single-pass walk; cheap
   enough to run over full top-nodes for prelude tree-shaking."
  [subtree name-set]
  (let [found (java.util.HashSet.)]
    (walk/postwalk
     (fn [n]
       (when (map? n)
         (let [nm (or (and (contains? #{:ident :type-ref :name} (:node n))
                           (or (:name n) (:sym n)))
                      (:name n))]
           (when (and nm (contains? name-set nm))
             (.add found nm))))
       n)
     subtree)
    (into #{} found)))

(defn- reachable-prelude-atoms
  "Which prelude atoms are actually referenced by non-prelude
   top-nodes. Non-prelude atoms are always kept (users authored them
   directly). Prelude atoms are added only when the user program
   mentions them by name, so shipping capability stubs like Clock
   doesn't pull their fanout selectors into unrelated programs.
   Names here are post-rename (see cxc.prelude/rename-table)."
  [top-nodes prelude-atom-names]
  (names-mentioned-in top-nodes prelude-atom-names))

(defn- synthesize-list-container-class
  "Emit-ready :objc-class node for one `_Cxc_ListOf<T>` instantiation.
   Contents:
     - `NSMutableArray *_elements` ivar (via a property so the setter
       isn't emitted — we access `_elements` directly)
     - `-initWithElements:(NSArray *)elements` constructor that copies
       the input NSArray into a new NSMutableArray
     - `-count` returning `(int)[_elements count]` (satisfies Int
       observable typing)
   Fan-in / add/remove / for_each ivars + methods land at Steps
   4-6; the shape here is deliberately minimal."
  [class-name element-name]
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
                 ;; Layer 5 Step 4 — mutation-in-place add/remove on
                 ;; the hybrid container. Void return; call site is
                 ;; a statement inside a `then:` clause. Element type
                 ;; is `id` at the ObjC level (NSMutableArray holds
                 ;; boxed primitives / atom references uniformly).
                 {:name        "cxc_add"
                  :selector    "cxc_add:"
                  :params      [{:name "e" :c-type "id"}]
                  :return-type "void"
                  :body        [{:node :objc-msg
                                 :recv {:node :name :sym "_elements"}
                                 :selector "addObject:"
                                 :args [{:node :name :sym "e"}]}]}
                 {:name        "cxc_remove"
                  :selector    "cxc_remove:"
                  :params      [{:name "e" :c-type "id"}]
                  :return-type "void"
                  :body        [{:node :objc-msg
                                 :recv {:node :name :sym "_elements"}
                                 :selector "removeObject:"
                                 :args [{:node :name :sym "e"}]}]}
                 ;; Layer 5 Step 5 — for_each on the hybrid container.
                 ;; Iterates via NSFastEnumeration (`for (id e in ...)`)
                 ;; and invokes the caller-supplied block on each
                 ;; element. Snapshot iteration: observation at call
                 ;; time (query view semantics per design §4); the
                 ;; block runs synchronously inside the calling
                 ;; transition, so mutations to _elements during the
                 ;; block are outside spec.
                 {:name        "cxc_for_each"
                  :selector    "cxc_for_each:"
                  :params      [{:name "block" :c-type "void (^)(id)"}]
                  :return-type "void"
                  :body        [{:node :raw-c
                                 :text "for (id _cxc_e in _elements) { block(_cxc_e); }"}]}]
   :element-name element-name})

(defn lower
  "Take parsed top-level nodes and produce an ObjC/C program AST."
  [top-nodes]
  (let [main-atom  (first (filter main-def-atom? top-nodes))
        _          (when-not main-atom
                     (throw (ex-info "no `def main` entry point found at top level"
                                     {:top top-nodes})))
        enum-env   (build-enum-env top-nodes)
        atom-env   (build-atom-env top-nodes enum-env)
        ;; Prelude tree-shaking: only keep prelude atoms that the
        ;; user program actually references. Prevents compiler errors
        ;; from fanout bodies that message subscriber-side selectors
        ;; on classes no user atom declares. The `def Clock: ...`
        ;; node itself is filtered out before scanning so it doesn't
        ;; count as a self-reference.
        non-prelude-nodes (remove #(and (= :def-atom (:node %))
                                        (contains? prelude/atom-names (:name %)))
                                  top-nodes)
        atom-env   (let [kept-prelude (reachable-prelude-atoms
                                       non-prelude-nodes prelude/atom-names)]
                     (into {} (filter (fn [[k _]]
                                        (or (not (contains? prelude/atom-names k))
                                            (contains? kept-prelude k)))
                                      atom-env)))
        record-env (build-record-env top-nodes atom-env enum-env)
        atom-env   (infer-observables-in-atom-env atom-env record-env enum-env)
        atom-env   (infer-streams-in-atom-env atom-env record-env enum-env)
        ;; Layer 5 Step 3: scan post-inference atom-env for `List of T`
        ;; field/ctor usages, register a synthesized entry per distinct
        ;; T for method-lookup, and generate a matching :objc-class per
        ;; instantiation. Runs after inference so any observable typed
        ;; as `List of T` also participates. Registration is idempotent
        ;; across the two passes: entries keyed by mangled class name
        ;; (which is a deterministic function of the element type).
        list-instantiations (list-instantiations-from-atom-env atom-env)
        list-atom-entries   (synthesize-list-container-entries list-instantiations)
        atom-env            (merge atom-env list-atom-entries)
        ctx        {:record-env record-env :atom-env atom-env :enum-env enum-env :locals {}}
        records    (filter #(= :record (:node %)) top-nodes)
        enums      (filter #(= :enum   (:node %)) top-nodes)
        c-enums    (mapv lower-enum enums)
        structs    (mapv #(lower-record-struct % ctx) records)
        functions  (record-method-functions ctx)
        ;; main-as-atom kicks in whenever the atom-env contains main
        ;; (build-atom-env puts it in iff other atoms exist).
        main-as-atom?  (contains? atom-env "main")
        _              (when (and main-as-atom? (seq (get-in atom-env ["main" :ctor-params])))
                         (fail-nyi! "def main with ctor params (env-cap wiring for main is Layer 5)"
                                    {:atom "main"}))
        ;; Split atom-env into real atoms (get lower-atom) and
        ;; container synth-classes (get their own generator). Container
        ;; entries carry `:kind :list-container` and skip the queue /
        ;; phase / transition machinery lower-atom assumes.
        real-atom-names       (filter #(not= :list-container
                                             (:kind (get atom-env %)))
                                      (keys atom-env))
        container-atom-names  (filter #(= :list-container
                                          (:kind (get atom-env %)))
                                      (keys atom-env))
        real-atom-classes     (mapv (fn [nm]
                                      (lower-atom nm ctx :is-main? (= "main" nm)))
                                    real-atom-names)
        list-container-classes (mapv (fn [nm]
                                       (synthesize-list-container-class
                                        nm
                                        (:element-type (get atom-env nm))))
                                     container-atom-names)
        classes    (into (vec list-container-classes) real-atom-classes)
        needs-foundation? (boolean (seq classes))
        needs-abort?      (boolean (some #(seq (:invariants (second %))) atom-env))
        ;; Runtime helpers (cxc_send_async / cxc_send_sync / cxc_shutdown
        ;; + CXCAtom protocol) are needed by every atom's compiler-inserted
        ;; -transitionToEnd (parent nil-slot cascade uses cxc_send_async
        ;; and casts to id<CXCAtom>). So the presence of any atom class
        ;; means we emit the runtime block.
        needs-runtime?    (boolean (seq classes))
        ;; dispatch_main() only when the program actually enqueues async
        ;; work (`:>` send). Programs whose only cross-atom talk is sync
        ;; (`|>` — direct call under Layer 4) complete synchronously in
        ;; the constructor and exit normally — no dispatch loop needed,
        ;; no `-> end` termination path required from the user.
        ;; dispatch_main() gated on ANY reachable atom (including kept
        ;; prelude atoms) doing an async send. non-prelude-nodes alone
        ;; would miss prelude-atom self-sends the user reaches into.
        ;; Reachable prelude atoms live in atom-env already (after
        ;; tree-shaking above); scan all their transitions.
        needs-dispatch-loop? (or (program-has-async-sends? non-prelude-nodes)
                                 (some (fn [[nm info]]
                                         (and (contains? prelude/atom-names nm)
                                              (program-has-async-sends?
                                               (:transitions info))))
                                       atom-env))
        base-includes     (if needs-foundation?
                            ["<Foundation/Foundation.h>"]
                            ["<stdio.h>"])
        includes          (cond-> base-includes
                            needs-abort?   (conj "<stdlib.h>")
                            needs-runtime? (conj "<stdlib.h>"))
        ;; Dedup includes while preserving order.
        includes          (vec (distinct includes))
        main-body         (if main-as-atom?
                            ;; Bootstrap the Main atom. Stored in file-
                            ;; scope static (emit renders both the decl
                            ;; and the alloc/init) so it stays alive
                            ;; across dispatch_main() — the auto-release
                            ;; pool ends before dispatch_main starts;
                            ;; a local would deallocate at pool drain.
                            [{:node :main-bootstrap
                              :class-name (class-name-for-atom "main")}]
                            (main-atom-body main-atom ctx))]
    {:node                :objc-program
     :includes            includes
     :needs-runtime?      needs-runtime?
     :needs-dispatch-loop? needs-dispatch-loop?
     :enums               c-enums
     :structs             structs
     :functions           functions
     :classes             classes
     :main                {:body         (if main-as-atom?
                                           main-body   ; return 0 unreachable after dispatch_main
                                           (conj main-body
                                                 {:node :return :value {:node :int :value 0}}))
                           :autorelease? needs-foundation?}}))
