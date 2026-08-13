(ns cxc.lower
  "Lower cxc AST to ObjC AST.

   ObjC AST vocabulary (Clojure data — a subset of what real ObjC allows,
   grown feature by feature alongside the parser):

     {:node :objc-program
      :includes    [\"<stdio.h>\" \"<Foundation/Foundation.h>\"]
      :classes     [ ... :objc-class ... ]
      :main        { :body [ ... :stmt ... ] }}

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
     {:node :str      :value \"hello\"}     — C string literal
     {:node :int      :value 42}
     {:node :float    :value 3.14}
     {:node :name     :sym \"self\"}
     {:node :objc-msg :recv <expr> :selector \"length\" :args []}

   Coverage this file: enough to lower the `def main: -> running do:
   print(\"...\")` shape to a hello-world main. Everything else throws
   :not-yet-implemented so subsequent work surfaces the gaps.

   Entry point convention: the top-level `def main` atom (lowercase `main`)
   is the runtime entry point (see spec §1.11 retirement note for `program`).
   Its `-> running do: ...` transition becomes the body of the emitted
   `int main(int argc, char *argv[])`.")

(defn- fail-nyi! [what node]
  (throw (ex-info (str "lowering not yet implemented: " what)
                  {:kind :not-yet-implemented :what what :node node})))

;; ---------------------------------------------------------------------------
;; Expression lowering
;; ---------------------------------------------------------------------------

(declare lower-expr lower-stmt)

(defn- lower-expr [node]
  (case (:node node)
    :lit-string {:node :str   :value (:value node)}
    :lit-int    {:node :int   :value (:value node)}
    :lit-float  {:node :float :value (:value node)}
    :ident      {:node :name  :sym (:name node)}
    :self       {:node :name  :sym "self"}
    :call
    ;; For hello-world scope: recognize `print` as the sole builtin.
    ;; Anything else fails until we grow FFI / method dispatch.
    (let [callee-name (get-in node [:callee :name])]
      (if (= "print" callee-name)
        {:node :call
         :fn   "printf"
         :args [{:node :str :value "%s\n"}
                (lower-expr (:value (first (:args node))))]}
        (fail-nyi! (str "call to `" callee-name "`") node)))
    (fail-nyi! (str "expression " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Statement lowering
;; ---------------------------------------------------------------------------

(defn- lower-stmt [node]
  (case (:node node)
    :expr-stmt (lower-expr (:expr node))
    :emit      (fail-nyi! ":emit statement (needs mailbox runtime)" node)
    :return    {:node :return :value (lower-expr (:expr node))}
    (fail-nyi! (str "statement " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Transition body — collect statements from `do:` clauses
;; ---------------------------------------------------------------------------

(defn- lower-do-clause [clause]
  (mapv lower-stmt (:stmts clause)))

(defn- lower-transition-body [clauses]
  ;; For the hello-world subset we only lower `do:` clauses. `then:`, `let:`,
  ;; `when:` require field mutation, local bindings, and guard-emission
  ;; respectively — all subsequent work.
  (vec (mapcat (fn [c]
                 (case (:node c)
                   :do-clause (lower-do-clause c)
                   (fail-nyi! (str "transition body clause " (:node c)) c)))
               clauses)))

;; ---------------------------------------------------------------------------
;; Program top-level
;; ---------------------------------------------------------------------------

(defn- main-atom-body
  "Extract the `-> running do: ...` transition's statements from the
   entry-point atom. Assumes exactly one transition targeting `running`
   with a `do:` body — the hello-world shape. All other shapes fail-nyi."
  [main-node]
  (let [transitions (filter #(= :transition (:node %)) (:body main-node))
        running-txn (first (filter #(= "running" (:target %)) transitions))]
    (when-not running-txn
      (fail-nyi! "`def main` must have a `-> running` transition" main-node))
    (lower-transition-body (:body running-txn))))

(defn- main-def-atom? [node]
  (and (= :def-atom (:node node)) (= "main" (:name node))))

(defn lower
  "Take parsed top-level nodes and produce an ObjC program AST.

   Restriction: exactly one top-level `def main` atom is required.
   Everything else (records, other def-atoms, module decl, imports) is
   ignored for the hello-world subset — they'll be lowered as we grow
   coverage."
  [top-nodes]
  (let [main-atom (first (filter main-def-atom? top-nodes))]
    (when-not main-atom
      (throw (ex-info "no `def main` entry point found at top level" {:top top-nodes})))
    {:node     :objc-program
     :includes ["<stdio.h>"]
     :classes  []
     :main     {:body (conj (main-atom-body main-atom)
                            {:node :return :value {:node :int :value 0}})}}))
