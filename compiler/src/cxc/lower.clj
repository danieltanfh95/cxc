(ns cxc.lower
  "Lower cxc AST to ObjC/C AST.

   ObjC AST vocabulary (Clojure data — a subset of what real ObjC allows,
   grown feature by feature alongside the parser):

     {:node :objc-program
      :includes    [\"<stdio.h>\" \"<Foundation/Foundation.h>\"]
      :structs     [ ... :c-struct ... ]        ; C typedef structs (records)
      :classes     [ ... :objc-class ... ]      ; ObjC classes (def-atoms)
      :main        { :body [ ... :stmt ... ] }}

     {:node :c-struct
      :name        \"V\"
      :fields      [ {:name \"x\" :c-type \"int\"} ... ]}

     {:node :objc-class
      :name        \"Paddle\"
      :superclass  \"NSObject\"
      :ivars       [ {:name \"y\" :type \"double\"} ... ]
      :methods     [ ... ]}

     {:node :objc-method
      :selector    \"init\"
      :return-type \"instancetype\"
      :params      [ {:name \"foo\" :type \"NSString *\"} ]
      :body        [ ... :stmt ... ]}

   Statement nodes (in :body vectors):
     {:node :call    :fn \"printf\" :args [ ... :expr ... ]}
     {:node :return  :value <expr>}
     {:node :assign  :lhs <expr> :rhs <expr>}

   Expression nodes (as :args, :value, :lhs, :rhs):
     {:node :str        :value \"hello\"}
     {:node :int        :value 42}
     {:node :float      :value 3.14}
     {:node :name       :sym \"self\"}
     {:node :binop      :op \"+\" :left <expr> :right <expr>}
     {:node :field      :target <expr> :field \"x\"}
     {:node :struct-lit :type \"V\" :fields [{:name \"x\" :value <expr>} ...]}
     {:node :objc-msg   :recv <expr> :selector \"length\" :args []}
     {:node :call       :fn \"printf\" :args [ ... ]}

   Coverage this file (2026-08-13):
   - Hello-world: `def main: -> running do: print(\"...\")`
   - Arithmetic in print: `print(3 + 4 * 2)` → prints `11`
   - Records as C structs: `record V: data: x: Int, y: Int` → `typedef struct { int x; int y; } V;`
   - Record construction: `V(x: 3, y: 4)` → `((V){.x = 3, .y = 4})`
   - Field access: `v.x` → `v.x`
   - Type-driven `print`: `%d`/`%f`/`%s` selected from argument type.

   Not yet: transitions with then:, def-atoms as ObjC classes, use:
   injection, contracts, method dispatch, the actor runtime. Those raise
   :not-yet-implemented so subsequent work surfaces the gaps.

   Entry point convention: the top-level `def main` atom (lowercase `main`)
   is the runtime entry point (see spec §1.11 retirement note for `program`).
   Its `-> running do: ...` transition becomes the body of the emitted
   `int main(int argc, char *argv[])`.")

(defn- fail-nyi! [what node]
  (throw (ex-info (str "lowering not yet implemented: " what)
                  {:kind :not-yet-implemented :what what :node node})))

;; ---------------------------------------------------------------------------
;; Type env
;;
;; Built from top-level record decls. Keyed by record name, value is a map
;; {:fields [{:name :ty}] :field-index {name -> ty}}. cxc type names map
;; onto C type names via `cxc-type->c` — for v1.0's numeric+string subset
;; it's a table; when we grow generics / user aliases we'll reroute this
;; through a real type environment.
;; ---------------------------------------------------------------------------

(def ^:private primitive-c-type
  {"Int"     "int"
   "Float"   "double"
   "String"  "const char *"
   "Bool"    "int"})     ;; No stdbool in scope yet; treat bool as int for now.

(defn- cxc-type->c
  "Map a cxc type-ref node to a C type string. Record types map to their
   own struct name. Unknown types fail-nyi so we surface the gap early."
  [ty-node record-env]
  (let [nm (:name ty-node)]
    (cond
      (contains? primitive-c-type nm) (primitive-c-type nm)
      (contains? record-env nm)       nm
      :else                            (fail-nyi! (str "type " nm) ty-node))))

(defn- build-record-env
  "Two-pass: first collect record names (so record→record refs resolve),
   then resolve each field type. Cyclic-refs are legal in C via pointers,
   but we don't emit pointers yet — a cycle in field types would fail-nyi
   naturally when a downstream test tries to lay out an infinite struct."
  [top-nodes]
  (let [records    (filter #(= :record (:node %)) top-nodes)
        skeleton   (into {} (map (fn [r] [(:name r) {:fields [] :field-index {}}]) records))
        with-fields
        (reduce
         (fn [env r]
           (let [name    (:name r)
                 dsec    (first (filter #(= :data-section (:node %)) (:sections r)))
                 fields  (mapv (fn [f]
                                 {:name   (:name f)
                                  :cxc-ty (:name (:type f))
                                  :c-type (cxc-type->c (:type f) env)})
                               (:fields dsec))
                 idx     (into {} (map (juxt :name identity) fields))]
             (assoc env name {:fields fields :field-index idx})))
         skeleton
         records)]
    with-fields))

;; ---------------------------------------------------------------------------
;; Expression type inference (on cxc AST, before lowering)
;;
;; Just enough to pick `%d`/`%f`/`%s` in `print` and let downstream passes
;; keep expression semantics straight. Not a full type-checker — we defer
;; that to a proper contracts+types pass once atoms are in play.
;; ---------------------------------------------------------------------------

(declare infer-type)

(defn- infer-binop-type
  "Standard numeric promotion: float dominates int; anything else on a
   numeric binop is an error we'll surface at compile time."
  [{:keys [op left right]} record-env]
  (let [lt (infer-type left  record-env)
        rt (infer-type right record-env)]
    (cond
      (#{"==" "!=" "<" "<=" ">" ">="} op) :bool
      (or (= lt :float) (= rt :float))    :float
      (and (= lt :int) (= rt :int))       :int
      :else (fail-nyi! (str "binop " op " between " lt " and " rt)
                       {:left lt :right rt}))))

(defn- infer-call-type
  "A `:call` is either a record constructor (callee-name is a known
   record) or a function call (only `print` for now, returning nothing).
   Everything else fail-nyi."
  [{:keys [callee] :as node} record-env]
  (let [nm (:name callee)]
    (cond
      (contains? record-env nm) {:record nm}
      (= "print" nm)            :void
      :else (fail-nyi! (str "call to `" nm "`") node))))

(defn- infer-type [node record-env]
  (case (:node node)
    :lit-string :string
    :lit-int    :int
    :lit-float  :float
    :ident      (fail-nyi! (str "type-of identifier `" (:name node)
                                "` (no local-binding env yet)")
                           node)
    :binop      (infer-binop-type node record-env)
    :call       (infer-call-type node record-env)
    :dot        (let [tgt-ty (infer-type (:target node) record-env)]
                  (if-let [rec (and (map? tgt-ty) (:record tgt-ty))]
                    (let [f (get-in record-env [rec :field-index (:field node)])]
                      (when-not f
                        (fail-nyi! (str "field `" (:field node) "` on record `" rec "`")
                                   node))
                      (case (:cxc-ty f)
                        "Int"    :int
                        "Float"  :float
                        "String" :string
                        "Bool"   :bool
                        {:record (:cxc-ty f)}))
                    (fail-nyi! "field access on non-record" node)))
    (fail-nyi! (str "infer-type " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Expression lowering
;; ---------------------------------------------------------------------------

(declare lower-expr lower-stmt)

(defn- lower-record-ctor
  "`V(x: 3, y: 4)` → C designated initializer. Named-args are matched by
   name; positional-args are matched by declaration order. Missing fields
   fail-nyi (we haven't wired defaults through the ctor yet)."
  [record-name args record-env]
  (let [rec       (get record-env record-name)
        by-name   (into {} (keep (fn [a] (when (= :named (:kind a))
                                           [(:name a) (:value a)]))
                                 args))
        positional (filterv #(= :positional (:kind %)) args)
        fields    (mapv (fn [i f]
                          (let [v (or (get by-name (:name f))
                                      (:value (get positional i)))]
                            (when-not v
                              (fail-nyi! (str "no value provided for field `"
                                              (:name f) "` on `" record-name "`")
                                         {:record record-name :field (:name f)}))
                            {:name  (:name f)
                             :value (lower-expr v record-env)}))
                        (range)
                        (:fields rec))]
    {:node :struct-lit :type record-name :fields fields}))

(defn- print-format-string
  "Pick `%s\\n` / `%d\\n` / `%f\\n` based on argument type."
  [arg-type]
  (case arg-type
    :string "%s\n"
    :int    "%d\n"
    :bool   "%d\n"
    :float  "%f\n"
    (fail-nyi! (str "print of type " (pr-str arg-type)) {:type arg-type})))

(defn- lower-print [call record-env]
  (let [arg-expr (:value (first (:args call)))
        arg-ty   (infer-type arg-expr record-env)]
    {:node :call
     :fn   "printf"
     :args [{:node :str :value (print-format-string arg-ty)}
            (lower-expr arg-expr record-env)]}))

(defn- lower-expr [node record-env]
  (case (:node node)
    :lit-string {:node :str   :value (:value node)}
    :lit-int    {:node :int   :value (:value node)}
    :lit-float  {:node :float :value (:value node)}
    :ident      {:node :name  :sym (:name node)}
    :self       {:node :name  :sym "self"}
    :binop      {:node  :binop
                 :op    (:op node)
                 :left  (lower-expr (:left node)  record-env)
                 :right (lower-expr (:right node) record-env)}
    :dot        {:node   :field
                 :target (lower-expr (:target node) record-env)
                 :field  (:field node)}
    :call
    (let [callee-name (get-in node [:callee :name])]
      (cond
        (contains? record-env callee-name)
        (lower-record-ctor callee-name (:args node) record-env)

        (= "print" callee-name)
        (lower-print node record-env)

        :else
        (fail-nyi! (str "call to `" callee-name "`") node)))
    (fail-nyi! (str "expression " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Statement lowering
;; ---------------------------------------------------------------------------

(defn- lower-stmt [node record-env]
  (case (:node node)
    :expr-stmt (lower-expr (:expr node) record-env)
    :emit      (fail-nyi! ":emit statement (needs mailbox runtime)" node)
    :return    {:node :return :value (lower-expr (:expr node) record-env)}
    (fail-nyi! (str "statement " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Transition body — collect statements from `do:` clauses
;; ---------------------------------------------------------------------------

(defn- lower-do-clause [clause record-env]
  (mapv #(lower-stmt % record-env) (:stmts clause)))

(defn- lower-transition-body [clauses record-env]
  ;; For the current subset we only lower `do:` clauses. `then:`, `let:`,
  ;; `when:` require field mutation, local bindings, and guard-emission
  ;; respectively — all subsequent work.
  (vec (mapcat (fn [c]
                 (case (:node c)
                   :do-clause (lower-do-clause c record-env)
                   (fail-nyi! (str "transition body clause " (:node c)) c)))
               clauses)))

;; ---------------------------------------------------------------------------
;; Top-level lowering
;; ---------------------------------------------------------------------------

(defn- lower-record [record-node record-env]
  ;; The env already has the resolved field types; just project into the
  ;; :c-struct shape emit.clj expects.
  (let [rec (get record-env (:name record-node))]
    {:node   :c-struct
     :name   (:name record-node)
     :fields (mapv (fn [f] {:name (:name f) :c-type (:c-type f)}) (:fields rec))}))

(defn- main-atom-body
  "Extract the `-> running do: ...` transition's statements from the
   entry-point atom. Assumes exactly one transition targeting `running`
   with a `do:` body — the current subset. Other shapes fail-nyi."
  [main-node record-env]
  (let [transitions (filter #(= :transition (:node %)) (:body main-node))
        running-txn (first (filter #(= "running" (:target %)) transitions))]
    (when-not running-txn
      (fail-nyi! "`def main` must have a `-> running` transition" main-node))
    (lower-transition-body (:body running-txn) record-env)))

(defn- main-def-atom? [node]
  (and (= :def-atom (:node node)) (= "main" (:name node))))

(defn lower
  "Take parsed top-level nodes and produce an ObjC/C program AST.

   Requires exactly one top-level `def main` atom. Top-level `record`
   decls are lowered to C typedef structs. Everything else (other
   def-atoms, module decl, imports, protocols, enums, classes) is
   ignored for now — they'll be lowered as we grow coverage."
  [top-nodes]
  (let [main-atom  (first (filter main-def-atom? top-nodes))
        _          (when-not main-atom
                     (throw (ex-info "no `def main` entry point found at top level"
                                     {:top top-nodes})))
        record-env (build-record-env top-nodes)
        records    (filter #(= :record (:node %)) top-nodes)
        structs    (mapv #(lower-record % record-env) records)]
    {:node     :objc-program
     :includes ["<stdio.h>"]
     :structs  structs
     :classes  []
     :main     {:body (conj (main-atom-body main-atom record-env)
                            {:node :return :value {:node :int :value 0}})}}))
