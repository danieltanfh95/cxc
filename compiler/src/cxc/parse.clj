(ns cxc.parse
  "Parser for cxc. Hand-rolled recursive-descent over the token stream
   produced by cxc.lex. Grown incrementally against design/examples/*.cxc.

   AST shape: Clojure maps with :node key naming the node type. Every node
   carries :line for diagnostics. Parser is state-monadic in style —
   functions take [tokens pos] and return [node new-pos] or throw ex-info
   on parse error.

   Coverage target: parse design/examples/game-toys.cxc end-to-end.
   Current coverage: module header, from-import, record, method signatures
   and bodies (partial — expressions still limited)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cxc.lex :as lex]))

;; ---------------------------------------------------------------------------
;; Chained-comparison desugaring
;;
;; The general binop parser left-associates comparison ops, producing
;; `((a <= b) <= c)`. That's incorrect C semantics for chained bounds
;; like `-max <= y <= max`. We rewrite the left-associated tree into a
;; `:chained-cmp` node carrying the flat operand + op vectors:
;;
;;   {:node :chained-cmp
;;    :operands [a b c ...]
;;    :ops      [cmp1 cmp2 ...]}
;;
;; Lower/emit render this as a GNU statement-expression that binds
;; each *interior* operand (operands[1..n-2]) to a `__typeof__` local,
;; then evaluates `operands[0] op0 t1 && t1 op1 t2 && ...` against
;; the bound locals. Result: every interior operand — including the
;; middle of a 3-operand chain — is evaluated exactly once at
;; runtime, so `-max <= counter.next() <= max` no longer duplicates
;; the call. (F27 semantic-once-only landing.)
;; ---------------------------------------------------------------------------

(def ^:private cmp-ops #{"<" "<=" ">" ">="})

(defn- chained? [n]
  (and (map? n)
       (= :binop (:node n))
       (contains? cmp-ops (:op n))
       (map? (:left n))
       (or (and (= :binop (get-in n [:left :node]))
                (contains? cmp-ops (get-in n [:left :op])))
           (= :chained-cmp (get-in n [:left :node])))))

(defn- desugar-chained-node [n]
  (cond
    (not (chained? n)) n

    ;; Left operand is an already-desugared :chained-cmp: extend it
    ;; with the new (op, rhs) pair. Postwalk visits children first,
    ;; so a 3+-operand chain (((a<b)<c)<d) is rebuilt into a single
    ;; :chained-cmp node in one pass.
    (= :chained-cmp (get-in n [:left :node]))
    (let [inner (:left n)]
      {:node :chained-cmp
       :operands (conj (:operands inner) (:right n))
       :ops      (conj (:ops inner)      (:op    n))})

    ;; Left operand is a bare binop cmp: fold into the base :chained-
    ;; cmp shape with three operands and two ops.
    :else
    (let [inner (:left n)]
      {:node :chained-cmp
       :operands [(:left inner) (:right inner) (:right n)]
       :ops      [(:op inner)   (:op n)]})))

(defn- desugar-chained-comparisons
  "Bottom-up rewrite: every left-associated cmp chain becomes a single
   `:chained-cmp` node. Recursively applied via postwalk so inner
   chains rewrite before outer ones — needed for 3+ operand chains."
  [ast]
  (walk/postwalk desugar-chained-node ast))

;; ---------------------------------------------------------------------------
;; Token stream primitives
;; ---------------------------------------------------------------------------

(defn- t-kind  [tok] (nth tok 0))
(defn- t-value [tok] (nth tok 1))
(defn- t-line  [tok] (nth tok 2))
(defn- t-col   [tok] (nth tok 3))

(defn- peek-tok [tokens pos]
  (nth tokens pos nil))

(defn- at?
  "True if token at pos matches kind (and optionally value)."
  ([tokens pos kind]
   (when-let [t (peek-tok tokens pos)]
     (= kind (t-kind t))))
  ([tokens pos kind value]
   (when-let [t (peek-tok tokens pos)]
     (and (= kind (t-kind t)) (= value (t-value t))))))

(defn- fail! [tokens pos msg]
  (let [t (peek-tok tokens pos)]
    (throw (ex-info (str "parse error: " msg
                         (when t
                           (str " (got " (t-kind t) " "
                                (pr-str (t-value t)) " at line "
                                (t-line t) ")")))
                    {:pos pos :token t}))))

(defn- expect
  "Consume a token of the given kind (+ optional value). Return [value new-pos]."
  ([tokens pos kind]
   (if (at? tokens pos kind)
     (let [t (peek-tok tokens pos)]
       [(t-value t) (inc pos)])
     (fail! tokens pos (str "expected " kind))))
  ([tokens pos kind value]
   (if (at? tokens pos kind value)
     [value (inc pos)]
     (fail! tokens pos (str "expected " kind " " (pr-str value))))))

(defn- skip-newlines [tokens pos]
  (loop [p pos]
    (if (at? tokens p :newline)
      (recur (inc p))
      p)))

;; ---------------------------------------------------------------------------
;; Type expressions — smallest useful subset
;;   type := ident               ("Float", "Int", "Vector2")
;;         | ident 'of' type     ("List of Enemy", "Stream of Bytes")
;;         | type '?'            (nullable — Optional<T>)
;; ---------------------------------------------------------------------------

(declare parse-expr)

(defn- parse-type [tokens pos]
  (let [[name p]  (expect tokens pos :ident)
        [inner p] (cond
                    ;; `T of ( K , V , ... )` — Layer 6 Step 8. Paren-
                    ;; tuple as the `of` operand carries multiple type
                    ;; params (Map of (K, V), Pair of (A, B) etc). The
                    ;; :inner slot becomes a :type-tuple node whose
                    ;; :types is the ordered vector of sub-types.
                    (and (at? tokens p :kw "of")
                         (at? tokens (inc p) :punct "("))
                    (loop [ts [] p (inc (inc p))]
                      (let [[t p2] (parse-type tokens p)
                            ts (conj ts t)]
                        (cond
                          (at? tokens p2 :punct ",")
                          (recur ts (inc p2))
                          (at? tokens p2 :punct ")")
                          [{:node :type-of :outer name
                            :inner {:node :type-tuple :types ts}}
                           (inc p2)]
                          :else
                          (fail! tokens p2
                                 "expected `,` or `)` in type-parameter list"))))

                    (at? tokens p :kw "of")
                    (let [[inner-t p2] (parse-type tokens (inc p))]
                      [{:node :type-of :outer name :inner inner-t} p2])

                    :else
                    [{:node :type-ref :name name} p])
        [wrapped p] (if (at? tokens p :punct "?")
                      [{:node :type-nullable :inner inner} (inc p)]
                      [inner p])]
    [wrapped p]))

;; ---------------------------------------------------------------------------
;; Expressions — pratt-style precedence climbing (partial coverage)
;;   Precedence (low → high):
;;     0 => (arrow — field-update RHS, handled at statement level)
;;     1 or
;;     2 and
;;     3 == != <= >= < > is in
;;     4 + -
;;     5 * / %
;;     6 unary - not
;;     7 postfix .field, (args), [i], {msg-args}
;;     8 primary: literal, ident, self, nil, true, false, (expr), lambda
;;
;; Lambdas: (params) => expr        or        x => expr
;; Method args: foo(x: 1, y: 2)     or        foo{x: Int}    (message shape)
;; ---------------------------------------------------------------------------

(def ^:private glyph-precedence
  {"or" 1 "and" 2
   "==" 3 "!=" 3 "<=" 3 ">=" 3 "<" 3 ">" 3 "is" 3 "in" 3
   "+" 4 "-" 4
   "*" 5 "/" 5 "%" 5})

(defn- binary-glyph? [tokens pos]
  (when-let [t (peek-tok tokens pos)]
    (let [k (t-kind t) v (t-value t)]
      (or (and (= k :glyph) (glyph-precedence v))
          (and (= k :kw) (glyph-precedence v))))))

(declare parse-primary parse-args-parens parse-msg-args-braces parse-list-literal parse-emit-message)

(defn- parse-postfix
  "Chain of .field, (args), {msg-args}, :> send, |> send suffixes on a
   primary expression. `:>` / `|>` produce :emit expression nodes so that
   sends can appear in lambda bodies and other expression positions."
  [tokens pos base]
  (loop [p pos node base]
    (cond
      (at? tokens p :punct ".")
      (let [[fname p2] (expect tokens (inc p) :ident)]
        (recur p2 {:node :dot :target node :field fname}))

      (at? tokens p :punct "(")
      (let [[args p2] (parse-args-parens tokens p)]
        (recur p2 {:node :call :callee node :args args}))

      (at? tokens p :punct "[")
      (let [[idx p2] (parse-expr tokens (inc p))
            [_ p3]   (expect tokens p2 :punct "]")]
        (recur p3 {:node :subscript :target node :index idx}))

      (at? tokens p :punct "{")
      (let [[margs p2] (parse-msg-args-braces tokens p)]
        (recur p2 {:node :msg-shape :target node :fields margs}))

      (at? tokens p :glyph ":>")
      (let [[m p2] (parse-emit-message tokens (inc p))]
        [{:node :emit :async? true :target node :msg (:msg m) :args (:args m)} p2])

      (at? tokens p :glyph "|>")
      (let [[m p2] (parse-emit-message tokens (inc p))]
        [{:node :emit :async? false :target node :msg (:msg m) :args (:args m)} p2])

      :else [node p])))

(defn- parse-unary [tokens pos]
  (cond
    (at? tokens pos :glyph "-")
    (let [[inner p] (parse-unary tokens (inc pos))]
      [{:node :neg :expr inner} p])

    (at? tokens pos :glyph "+")
    ;; unary `+` is a no-op — return the inner expr unchanged
    (parse-unary tokens (inc pos))

    (at? tokens pos :kw "not")
    (let [[inner p] (parse-unary tokens (inc pos))]
      [{:node :not :expr inner} p])

    :else
    (let [[prim p] (parse-primary tokens pos)]
      (parse-postfix tokens p prim))))

(defn- parse-binop [tokens pos min-prec]
  (loop [[lhs p] (parse-unary tokens pos)]
    (let [t (peek-tok tokens p)
          v (when t (t-value t))
          prec (glyph-precedence v)]
      (if (and prec (>= prec min-prec) (binary-glyph? tokens p))
        (let [[rhs p2] (parse-binop tokens (inc p) (inc prec))]
          (recur [{:node :binop :op v :left lhs :right rhs} p2]))
        [lhs p]))))

(defn- parse-expr [tokens pos]
  (parse-binop tokens pos 1))

(defn- parse-primary [tokens pos]
  (let [t (peek-tok tokens pos)]
    (case (t-kind t)
      :int    [{:node :lit-int    :value (t-value t)} (inc pos)]
      :float  [{:node :lit-float  :value (t-value t)} (inc pos)]
      :string [{:node :lit-string :value (t-value t)} (inc pos)]
      :char   [{:node :lit-char   :value (t-value t)} (inc pos)]
      :kw     (case (t-value t)
                "self"  [{:node :self}    (inc pos)]
                "nil"   [{:node :lit-nil} (inc pos)]
                "true"  [{:node :lit-bool :value true}  (inc pos)]
                "false" [{:node :lit-bool :value false} (inc pos)]
                (fail! tokens pos (str "unexpected keyword " (t-value t) " in expression")))
      :ident  ;; ident, maybe followed by lambda arrow => in parse-lambda-or-ident
      (let [ident-node {:node :ident :name (t-value t)}]
        (if (at? tokens (inc pos) :glyph "=>")
          (let [[body p] (parse-expr tokens (+ pos 2))]
            [{:node :lambda :params [{:name (t-value t)}] :body body} p])
          [ident-node (inc pos)]))
      :punct  (case (t-value t)
                "(" (cond
                      ;; Layer 6 Step 12 — zero-arg lambda `() => body`.
                      ;; Distinct from `(expr)` grouping via the `)` `=>`
                      ;; lookahead. Currently the only escaping-block
                      ;; grammar path — `Block` at MVP is zero-arg void
                      ;; return, so a zero-param lambda is what matches.
                      (and (at? tokens (inc pos) :punct ")")
                           (at? tokens (+ pos 2) :glyph "=>"))
                      (let [[body p] (parse-expr tokens (+ pos 3))]
                        [{:node :lambda :params [] :body body} p])

                      :else
                      (let [[e p] (parse-expr tokens (inc pos))
                            [_ p2] (expect tokens p :punct ")")]
                        [e p2]))
                "[" (let [[items p] (parse-list-literal tokens pos)]
                      [items p])
                (fail! tokens pos "unexpected punctuation in expression"))
      (fail! tokens pos "expected an expression"))))

(defn- parse-list-literal [tokens pos]
  ;; assumes at "["
  (let [[_ p] (expect tokens pos :punct "[")]
    (if (at? tokens p :punct "]")
      [{:node :list :items []} (inc p)]
      (loop [items [] p p]
        (let [[e p2] (parse-expr tokens p)]
          (cond
            (at? tokens p2 :punct ",")
            (recur (conj items e) (inc p2))

            (at? tokens p2 :punct "]")
            [{:node :list :items (conj items e)} (inc p2)]

            :else (fail! tokens p2 "expected `,` or `]` in list literal")))))))

(defn- parse-args-parens
  "Parse (a: 1, b: 2, c) — positional or named args."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (if (at? tokens p :punct ")")
      [[] (inc p)]
      (loop [args [] p p]
        (let [[arg p2]
              (if (and (at? tokens p :ident) (at? tokens (inc p) :punct ":"))
                (let [name (t-value (peek-tok tokens p))
                      [val p2] (parse-expr tokens (+ p 2))]
                  [{:kind :named :name name :value val} p2])
                (let [[val p2] (parse-expr tokens p)]
                  [{:kind :positional :value val} p2]))]
          (cond
            (at? tokens p2 :punct ",")
            (recur (conj args arg) (inc p2))

            (at? tokens p2 :punct ")")
            [(conj args arg) (inc p2)]

            :else (fail! tokens p2 "expected `,` or `)` in arg list")))))))

(defn- parse-msg-args-braces
  "Parse {name: Type, name2: Type} — message payload shape."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct "{")]
    (if (at? tokens p :punct "}")
      [[] (inc p)]
      (loop [args [] p p]
        (let [[name p2] (expect tokens p :ident)
              [_ p3]    (expect tokens p2 :punct ":")
              [ty p4]   (parse-type tokens p3)]
          (cond
            (at? tokens p4 :punct ",")
            (recur (conj args {:name name :type ty}) (inc p4))

            (at? tokens p4 :punct "}")
            [(conj args {:name name :type ty}) (inc p4)]

            :else (fail! tokens p4 "expected `,` or `}` in message shape")))))))

;; ---------------------------------------------------------------------------
;; Field declarations, function/method declarations
;; ---------------------------------------------------------------------------

(defn- parse-field
  "name: Type [= default]"
  [tokens pos]
  (let [[name p]  (expect tokens pos :ident)
        [_ p]     (expect tokens p :punct ":")
        [ty p]    (parse-type tokens p)
        [dflt p]  (if (at? tokens p :glyph "=")
                    (let [[e p2] (parse-expr tokens (inc p))]
                      [e p2])
                    [nil p])]
    [{:node :field :name name :type ty :default dflt} p]))

(defn- parse-param
  "For function params & atom constructor params: name: Type [= default]."
  [tokens pos]
  (cond
    (at? tokens pos :kw "self")
    [{:node :param :name "self" :type nil :default nil} (inc pos)]

    :else (parse-field tokens pos)))

(defn- parse-param-list-parens [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (if (at? tokens p :punct ")")
      [[] (inc p)]
      (loop [ps [] p p]
        (let [[param p2] (parse-param tokens p)]
          (cond
            (at? tokens p2 :punct ",")
            (recur (conj ps param) (inc p2))

            (at? tokens p2 :punct ")")
            [(conj ps param) (inc p2)]

            :else (fail! tokens p2 "expected `,` or `)` in param list")))))))

(declare parse-block)

(defn- parse-function-decl
  "Method declaration in two accepted forms:
     function name(params) -> RetType: <block>   ; legacy — audit K8
     def name(params) -> RetType: <block>        ; spec §2.5 canonical

   The `function` keyword is retired at v1.0 (spec §1201/§2330); we
   still parse it during the grace period and emit no warning yet
   (deprecation-warning is a follow-up). The `def` form is
   distinguished from a nested-atom slot (`def name: TypeName`) by
   the paren immediately after the name."
  [tokens pos]
  (let [[_ p]      (cond
                     (at? tokens pos :kw "function") (expect tokens pos :kw "function")
                     (at? tokens pos :kw "def")      (expect tokens pos :kw "def")
                     :else (fail! tokens pos "expected `def` or `function` for method"))
        [name p]   (expect tokens p :ident)
        [params p] (parse-param-list-parens tokens p)
        [ret p]    (if (at? tokens p :glyph "->")
                     (parse-type tokens (inc p))
                     [nil p])
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [body p]   (parse-block tokens p)]
    [{:node :function :name name :params params :return-type ret :body body} p]))

(defn- at-def-method?
  "Look-ahead for the spec-canonical `def name(...)` method form
   inside a def-body or methods: section — distinguishes from the
   F34 nested-slot form `def name: TypeName` (paren vs colon after
   the identifier)."
  [tokens pos]
  (and (at? tokens pos :kw "def")
       (at? tokens (inc pos) :ident)
       (at? tokens (+ pos 2) :punct "(")))

;; ---------------------------------------------------------------------------
;; Statements & blocks
;;   A statement (in method bodies etc.) is currently one of:
;;     return <expr>
;;     <expr> (bare expression statement)
;;
;;   A block is delimited by :indent … :dedent, and contains one-or-more
;;   statements terminated by :newline.
;; ---------------------------------------------------------------------------

(defn- parse-emit-message
  "Parse `<msg>[(args)]` following a send glyph."
  [tokens pos]
  (let [[name p] (expect tokens pos :ident)
        [args p] (if (at? tokens p :punct "(")
                   (parse-args-parens tokens p)
                   [[] p])]
    [{:msg name :args args} p]))

(declare parse-for-statement)

(defn- parse-statement
  "One statement:
     return <expr>
     :> <msg>[(args)]                        — untargeted async emit
     |> <msg>[(args)]                        — untargeted sync emit
     <expr> :> <msg>[(args)]                 — targeted async send
     <expr> |> <msg>[(args)]                 — targeted sync send
     for <var> in <expr>: <body>             — sugar → <expr>.for_each(<var> => <body>)
     <expr>                                  — bare expression"
  [tokens pos]
  (cond
    (at? tokens pos :kw "return")
    (let [[e p] (parse-expr tokens (inc pos))]
      [{:node :return :expr e} p])

    (at? tokens pos :glyph ":>")
    (let [[m p] (parse-emit-message tokens (inc pos))]
      [{:node :emit :async? true :target nil :msg (:msg m) :args (:args m)} p])

    (at? tokens pos :glyph "|>")
    (let [[m p] (parse-emit-message tokens (inc pos))]
      [{:node :emit :async? false :target nil :msg (:msg m) :args (:args m)} p])

    (at? tokens pos :kw "for")
    (parse-for-statement tokens pos)

    :else
    (let [[e p] (parse-expr tokens pos)]
      (if (= :emit (:node e))
        [e p]                              ; targeted send parsed as postfix — already a statement
        [{:node :expr-stmt :expr e} p]))))

(defn- parse-for-statement
  "Sugar: `for <var> in <expr>: <body>` desugars at parse time to
   `<expr>.for_each(<var> => <body>)`. Body forms:
     - inline:  `for e in xs: <single-stmt>`
     - block:   `for e in xs:\\n    <stmts...>` (indented)
   Multi-stmt bodies wrap as `{:node :block :stmts [...]}`. Grammar
   production `for_expr` per spec §1.11 note; downstream lowering
   treats the result as an ordinary method call whose sole positional
   arg is a lambda literal. Runtime support for the lambda arg lands
   in Step 5 (List.for_each + ObjC blocks); Step 2 lands the parse
   rewrite only, verified via parse_test.clj."
  [tokens pos]
  (let [[_ p]     (expect tokens pos :kw "for")
        [var-name p] (expect tokens p :ident)
        [_ p]     (expect tokens p :kw "in")
        [coll p]  (parse-expr tokens p)
        [_ p]     (expect tokens p :punct ":")
        [body p]  (if (at? tokens p :newline)
                    ;; block form
                    (let [[_ p]  (expect tokens p :newline)
                          [_ p]  (expect tokens p :indent)]
                      (loop [stmts [] p p]
                        (cond
                          (at? tokens p :dedent)
                          [{:node :block :stmts stmts} (inc p)]
                          (at? tokens p :newline)
                          (recur stmts (inc p))
                          :else
                          (let [[s p2] (parse-statement tokens p)
                                p3     (skip-newlines tokens p2)]
                            (recur (conj stmts s) p3)))))
                    ;; inline form — single statement is the body
                    (parse-statement tokens p))
        lambda    {:node :lambda
                   :params [{:name var-name}]
                   :body body}
        call      {:node :call
                   :callee {:node :dot :target coll :field "for_each"}
                   :args [{:kind :positional :value lambda}]}]
    [{:node :expr-stmt :expr call} p]))

(defn- parse-block
  "Parse an indented block: :indent (stmt :newline)+ :dedent."
  [tokens pos]
  (let [[_ p] (expect tokens pos :indent)]
    (loop [stmts [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :block :stmts stmts} (inc p)]

        (at? tokens p :newline)
        (recur stmts (inc p))

        :else
        (let [[stmt p2] (parse-statement tokens p)
              p3 (skip-newlines tokens p2)]
          (recur (conj stmts stmt) p3))))))

;; ---------------------------------------------------------------------------
;; Sections (data:, methods:, use:, invariants:, then:, do:, let:, cases:)
;;   Each section header is `<name>:` followed by :newline :indent … :dedent
;;   Section body content differs per section — dispatch here.
;; ---------------------------------------------------------------------------

(defn- section-header?
  "True if pos looks like `<kw|ident>:` where the keyword names a section."
  [tokens pos section-names]
  (and (or (at? tokens pos :kw) (at? tokens pos :ident))
       (section-names (t-value (peek-tok tokens pos)))
       (at? tokens (inc pos) :punct ":")))

(defn- parse-data-section [tokens pos]
  ;; assumes at `data` keyword
  (let [[_ p] (expect tokens pos :kw "data")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [fields [] p p]
      (cond
        (at? tokens p :dedent) [{:node :data-section :fields fields} (inc p)]
        (at? tokens p :newline) (recur fields (inc p))
        :else (let [[f p2] (parse-field tokens p)
                    p3 (skip-newlines tokens p2)]
                (recur (conj fields f) p3))))))

(defn- parse-methods-section [tokens pos]
  (let [[_ p] (expect tokens pos :kw "methods")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [methods [] p p]
      (cond
        (at? tokens p :dedent) [{:node :methods-section :methods methods} (inc p)]
        (at? tokens p :newline) (recur methods (inc p))
        (or (at? tokens p :kw "function")
            (at-def-method? tokens p))
        (let [[fdecl p2] (parse-function-decl tokens p)
              p3 (skip-newlines tokens p2)]
          (recur (conj methods fdecl) p3))
        :else (fail! tokens p "expected `def` or `function` in methods section")))))

(defn- parse-deprecated-section
  "deprecated: \"<message>\"
   Layer 6 Step 11 — inline body-form deprecation marker. Emits a
   `:deprecated` node the compiler attaches to the enclosing def
   (record or atom). Requires a non-empty string literal — the
   message that use-site warnings will carry."
  [tokens pos]
  (let [[_ p]   (expect tokens pos :kw "deprecated")
        [_ p]   (expect tokens p :punct ":")
        [msg p] (expect tokens p :string)
        _       (when (str/blank? msg)
                  (fail! tokens p "deprecated: message must be non-empty"))
        [_ p]   (expect tokens p :newline)]
    [{:node :deprecated :message msg} p]))

(defn- parse-use-section
  "use:
       cap: SomeCapability
       other_cap: OtherCapability"
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "use")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [caps [] p p]
      (cond
        (at? tokens p :dedent) [{:node :use-section :caps caps} (inc p)]
        (at? tokens p :newline) (recur caps (inc p))
        :else (let [[name p2] (expect tokens p :ident)
                    [_ p3]    (expect tokens p2 :punct ":")
                    [ty p4]   (parse-type tokens p3)
                    p5        (skip-newlines tokens p4)]
                (recur (conj caps {:name name :type ty}) p5))))))

(defn- parse-invariants-section
  "invariants:
       <expr>
       <expr>
   Each line is a predicate expression (chained comparisons ok — parsed
   by the general binop path since <, <=, >, >= all have equal precedence
   and left-associate). Downstream contract-check will interpret chained
   forms specially."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "invariants")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [preds [] p p]
      (cond
        (at? tokens p :dedent) [{:node :invariants-section :predicates preds} (inc p)]
        (at? tokens p :newline) (recur preds (inc p))
        :else (let [[e p2] (parse-expr tokens p)
                    p3     (skip-newlines tokens p2)]
                (recur (conj preds e) p3))))))

;; ---------------------------------------------------------------------------
;; Observables:  <name> :< <expr>
;;   Declaration-flavored assignment (§1.11). The colon-less-than glyph.
;; ---------------------------------------------------------------------------

(defn- parse-observable [tokens pos]
  (let [[name p]  (expect tokens pos :ident)
        [_ p]     (expect tokens p :glyph ":<")
        [expr p]  (parse-expr tokens p)]
    [{:node :observable :name name :expr expr} p]))

;; ---------------------------------------------------------------------------
;; Transitions
;;
;;   Full shape:
;;     [<source-phase>] [<receive-glyph> <msg>[{args}]] [when: <expr>]
;;         -> <target-phase>
;;         [inline-body | indented-body]
;;
;;   Inline body:  same line as `->`; one of `then: X => Y` / `do: STMT` / `let: NAME: T = E`
;;   Indented body: NEWLINE INDENT (then:|do:|let: block)+ DEDENT
;;
;;   For simplicity of this first pass, body clauses accept either an inline
;;   single item or an indented multi-item block.
;; ---------------------------------------------------------------------------

(declare parse-transition-body)

(defn- parse-msg-pattern
  "Parse a receive-glyph source pattern — a general expression optionally
   suffixed by `{payload-shape}`. Handles the full range:
     tick{dt: Duration}
     input.pressed{key: Key}
     clock.every(rate){_: Instant}
     active_enemies.died{e: Enemy}
   The expression parser already turns `X{...}` into a :msg-shape postfix,
   so we just call parse-expr and unpack whichever shape came back."
  [tokens pos]
  (let [[e p] (parse-expr tokens pos)]
    (if (= :msg-shape (:node e))
      [{:source (:target e) :payload (:fields e)} p]
      [{:source e :payload []} p])))

(defn- parse-source-and-receive
  "Look at the head of a transition. Returns [source receive p] where:
     source  = {:kind :phase :name X} | {:kind :self} | {:kind :implicit}
     receive = {:glyph <: :msg {...}} | nil

   Allows newline between source and receive-glyph — the two- and three-line
   transition header shapes both parse to the same AST."
  [tokens pos]
  (cond
    (at? tokens pos :glyph "->")
    [{:kind :implicit} nil pos]

    :else
    (let [source (cond
                   (at? tokens pos :kw "self")   {:kind :self}
                   (at? tokens pos :ident)       {:kind :phase :name (t-value (peek-tok tokens pos))}
                   :else (fail! tokens pos "expected source-phase, self, or `->` at start of transition"))
          p (skip-newlines tokens (inc pos))]    ; allow newline after source
      (cond
        (at? tokens p :glyph "<:")
        (let [[msg p2] (parse-msg-pattern tokens (inc p))]
          [source {:glyph "<:" :msg msg} p2])

        (at? tokens p :glyph "<|")
        (let [[msg p2] (parse-msg-pattern tokens (inc p))]
          [source {:glyph "<|" :msg msg} p2])

        :else [source nil p]))))

(defn- parse-then-clause-inline
  "then: <field> => <expr>   — single field update on one line"
  [tokens pos]
  (let [[_ p]   (expect tokens pos :kw "then")
        [_ p]   (expect tokens p :punct ":")
        [name p] (expect tokens p :ident)
        [_ p]    (expect tokens p :glyph "=>")
        [rhs p]  (parse-expr tokens p)]
    [{:node :then-clause :updates [{:field name :expr rhs}]} p]))

(defn- parse-then-clause-block
  "then:  (indented block of `<field> => <expr>` lines)"
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "then")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [ups [] p p]
      (cond
        (at? tokens p :dedent) [{:node :then-clause :updates ups} (inc p)]
        (at? tokens p :newline) (recur ups (inc p))
        :else (let [[name p2] (expect tokens p :ident)
                    [_ p3]    (expect tokens p2 :glyph "=>")
                    [rhs p4]  (parse-expr tokens p3)
                    p5        (skip-newlines tokens p4)]
                (recur (conj ups {:field name :expr rhs}) p5))))))

(defn- parse-then-clause [tokens pos]
  ;; peek past `then:` — if next is :newline, it's a block form; else inline.
  (if (at? tokens (+ pos 2) :newline)
    (parse-then-clause-block tokens pos)
    (parse-then-clause-inline tokens pos)))

(defn- parse-do-clause-inline
  "do: <statement>"
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "do")
        [_ p] (expect tokens p :punct ":")
        [stmt p] (parse-statement tokens p)]
    [{:node :do-clause :stmts [stmt]} p]))

(defn- parse-do-clause-block
  "do:  (indented block of statements)"
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "do")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [stmts [] p p]
      (cond
        (at? tokens p :dedent) [{:node :do-clause :stmts stmts} (inc p)]
        (at? tokens p :newline) (recur stmts (inc p))
        :else (let [[stmt p2] (parse-statement tokens p)
                    p3 (skip-newlines tokens p2)]
                (recur (conj stmts stmt) p3))))))

(defn- parse-do-clause [tokens pos]
  (if (at? tokens (+ pos 2) :newline)
    (parse-do-clause-block tokens pos)
    (parse-do-clause-inline tokens pos)))

(defn- reject-legacy-let-type-annotation!
  "F23 — the pre-spec `let: k: Int = 2` shape (with `<Type> = <expr>`
   after the binding-name colon) is retired. Spec §1.11 form is
   `let: k: <expr>` with the type inferred from the expression."
  [tokens pos]
  (fail! tokens pos
         (str "`let:` binding uses `name: expression` (spec §1.11) — the "
              "type is inferred from the RHS. Remove the `Type = ` between "
              "the binding-name colon and the expression: `let: k: 2` "
              "instead of `let: k: Int = 2`.")))

(defn- parse-let-binding-rhs
  "After `<name> :`, parse the binding's RHS. Spec form is a bare
   expression; reject the legacy `<Type> = <expr>` shape with a
   targeted migration diagnostic."
  [tokens pos]
  (let [[rhs p] (parse-expr tokens pos)]
    ;; If parse-expr stopped short of `=`, it means the user gave us
    ;; a legacy `Type = expr` form (parse-expr grabbed `Type` as an
    ;; ident, then couldn't consume `=`). Detect and reject.
    (if (at? tokens p :glyph "=")
      (reject-legacy-let-type-annotation! tokens pos)
      [rhs p])))

(defn- parse-let-clause-inline
  "let: <name>: <expr>       (spec §1.11 — type inferred from RHS)"
  [tokens pos]
  (let [[_ p]     (expect tokens pos :kw "let")
        [_ p]     (expect tokens p :punct ":")
        [name p]  (expect tokens p :ident)
        [_ p]     (expect tokens p :punct ":")
        [rhs p]   (parse-let-binding-rhs tokens p)]
    [{:node :let-clause :bindings [{:name name :expr rhs}]} p]))

(defn- parse-let-clause-block
  "let:  (indented block of `<name>: <expr>` lines)"
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "let")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [bindings [] p p]
      (cond
        (at? tokens p :dedent) [{:node :let-clause :bindings bindings} (inc p)]
        (at? tokens p :newline) (recur bindings (inc p))
        :else (let [[name p2] (expect tokens p :ident)
                    [_ p3]    (expect tokens p2 :punct ":")
                    [rhs p4]  (parse-let-binding-rhs tokens p3)
                    p5        (skip-newlines tokens p4)]
                (recur (conj bindings {:name name :expr rhs}) p5))))))

(defn- parse-let-clause [tokens pos]
  (if (at? tokens (+ pos 2) :newline)
    (parse-let-clause-block tokens pos)
    (parse-let-clause-inline tokens pos)))

(defn- parse-when-clause
  "when: <expr>   — additional guard when appearing in transition body."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "when")
        [_ p] (expect tokens p :punct ":")
        [e p] (parse-expr tokens p)]
    [{:node :when-clause :expr e} p]))

(defn- body-clause-head?
  "True if pos is at `then:` | `do:` | `let:` | `when:`."
  [tokens pos]
  (and (at? tokens pos :kw)
       (#{"then" "do" "let" "when"} (t-value (peek-tok tokens pos)))
       (at? tokens (inc pos) :punct ":")))

;; Audit F22: spec §10 requires transition sub-blocks in the order
;; `when, let, then, do`, each at most once. `clause-order` is the
;; canonical index used to detect out-of-order and duplicate clauses.
(def ^:private clause-order
  {:when-clause 0 :let-clause 1 :then-clause 2 :do-clause 3})

(defn- clause-kw->src [kw]
  ({:when-clause "when" :let-clause "let"
    :then-clause "then" :do-clause "do"} kw))

(defn- enforce-clause-order-and-multiplicity!
  "Audit F22: reject repeated clauses and out-of-order clauses in a
   transition body. Message spec: `when, let, then, do`."
  [clauses tokens pos]
  (loop [seen #{} prev-idx -1 [c & rest] clauses]
    (when c
      (let [kw  (:node c)
            idx (clause-order kw)]
        (when (contains? seen kw)
          (fail! tokens pos
                 (str "duplicate `" (clause-kw->src kw)
                      ":` clause in transition body (each of when/let/then/do "
                      "may appear at most once — spec §10)")))
        (when (< idx prev-idx)
          (fail! tokens pos
                 (str "`" (clause-kw->src kw) ":` clause out of order — "
                      "spec §10 requires transition sub-blocks in the order "
                      "`when, let, then, do`")))
        (recur (conj seen kw) idx rest)))))

(defn- parse-transition-body
  "After the `-> target` head, an optional body:
     - if next token is a body-clause keyword on same line → inline body
     - if next is :newline :indent → indented body with multiple clauses
     - else → no body (transition ends)

   Audit F22: after collecting clauses, verify they appear in
   canonical order (`when, let, then, do`) with each kind at most
   once."
  [tokens pos]
  (cond
    (body-clause-head? tokens pos)
    ;; Inline single clause (rest of transition is on the same line).
    (let [[clause p]
          (case (t-value (peek-tok tokens pos))
            "then" (parse-then-clause tokens pos)
            "do"   (parse-do-clause tokens pos)
            "let"  (parse-let-clause tokens pos)
            "when" (parse-when-clause tokens pos)
            (fail! tokens pos "unexpected clause"))]
      [[clause] p])

    (and (at? tokens pos :newline) (at? tokens (inc pos) :indent))
    (let [p (+ pos 2)]
      (loop [clauses [] p p]
        (cond
          (at? tokens p :dedent)
          (do (enforce-clause-order-and-multiplicity! clauses tokens p)
              [clauses (inc p)])
          (at? tokens p :newline) (recur clauses (inc p))
          (body-clause-head? tokens p)
          (let [[clause p2]
                (case (t-value (peek-tok tokens p))
                  "then" (parse-then-clause tokens p)
                  "do"   (parse-do-clause tokens p)
                  "let"  (parse-let-clause tokens p)
                  "when" (parse-when-clause tokens p)
                  (fail! tokens p "unexpected clause"))]
            (recur (conj clauses clause) (skip-newlines tokens p2)))
          :else
          (do (enforce-clause-order-and-multiplicity! clauses tokens p)
              [clauses p]))))

    :else [[] pos]))

(defn- parse-transition
  "Full transition. See grammar in section header.

   The header may be split across two lines: source+receive on line 1,
   `-> target` on line 2 (per game-toys.cxc convention). We skip a
   :newline between components if the next non-newline token is what
   we're expecting, so both single-line and two-line shapes parse the
   same AST."
  [tokens pos]
  (let [[source receive p] (parse-source-and-receive tokens pos)
        p (skip-newlines tokens p)              ; allow newline before when:/->
        [guard p]  (if (and (at? tokens p :kw "when")
                            (at? tokens (inc p) :punct ":"))
                     (let [[e p2] (parse-expr tokens (+ p 2))]
                       [e p2])
                     [nil p])
        p (skip-newlines tokens p)              ; allow newline before ->
        [_ p]      (expect tokens p :glyph "->")
        [target p] (cond
                     (at? tokens p :kw "self") ["self" (inc p)]
                     (at? tokens p :ident)     [(t-value (peek-tok tokens p)) (inc p)]
                     :else (fail! tokens p "expected target phase after `->`"))
        [body p]   (parse-transition-body tokens p)]
    [{:node :transition
      :source source
      :receive receive
      :when guard
      :target target
      :body body} p]))

;; ---------------------------------------------------------------------------
;; def-atom top-level form
;;
;;   def <Name>[(ctor-params)]:
;;       (use: | data: | invariants: | methods: | observable | transition)+
;;
;;   Determines what to parse by look-ahead:
;;     - `use:`, `data:`, `invariants:`, `methods:` → section
;;     - `<ident> :<`                                → observable
;;     - `<ident|self> <:|<|` OR `<ident> ->` OR `->` → transition
;; ---------------------------------------------------------------------------

(defn- looks-like-observable? [tokens pos]
  (and (at? tokens pos :ident)
       (at? tokens (inc pos) :glyph ":<")))

;; F14 / spec §1.11 — `-> handler(args):` is an externally invocable
;; side-effect-only handler declaration, distinguished from the
;; wildcard-source transition `-> target-phase` by the paren after
;; the identifier.
(defn- looks-like-handler-decl? [tokens pos]
  (and (at? tokens pos :glyph "->")
       (at? tokens (inc pos) :ident)
       (at? tokens (+ pos 2) :punct "(")))

(defn- parse-handler-decl [tokens pos]
  (let [[_ p]      (expect tokens pos :glyph "->")
        [name p]   (expect tokens p :ident)
        [params p] (parse-param-list-parens tokens p)
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [body p]   (parse-block tokens p)]
    [{:node :handler-decl :name name :params params :body body} p]))

(defn- looks-like-transition? [tokens pos]
  ;; Called after observable / section checks have failed. At atom-body top
  ;; level, the remaining possibilities are transitions and errors. A
  ;; transition starts with `->` (implicit source), `self`, or a source-phase
  ;; identifier. Since the observable case is already excluded, any bare
  ;; ident is treated as a transition-source.
  (or (at? tokens pos :glyph "->")
      (at? tokens pos :kw "self")
      (at? tokens pos :ident)))

(defn- parse-def-atom [tokens pos]
  (let [[_ p]      (expect tokens pos :kw "def")
        [name p]   (expect tokens p :ident)
        [ctor p]   (if (at? tokens p :punct "(")
                     (parse-param-list-parens tokens p)
                     [[] p])
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [_ p]      (expect tokens p :indent)]
    (loop [body [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :def-atom :name name :ctor-params ctor :body body} (inc p)]

        (at? tokens p :newline)
        (recur body (inc p))

        (at? tokens p :kw "use")
        (let [[s p2] (parse-use-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "deprecated")
        (let [[s p2] (parse-deprecated-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "data")
        (let [[s p2] (parse-data-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "invariants")
        (let [[s p2] (parse-invariants-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "methods")
        (let [[s p2] (parse-methods-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (or (at? tokens p :kw "function")
            (at-def-method? tokens p))
        (let [[f p2] (parse-function-decl tokens p)]
          (recur (conj body f) (skip-newlines tokens p2)))

        ;; Nested atom slot: `def <name>: <TypeName>[(ctor-args)]`
        ;; Distinct from top-level `def <Name>:` (which opens a block)
        ;; and from the spec-canonical method form `def name(...) -> T:`
        ;; caught above. F34-retirement-pending shape.
        (at? tokens p :kw "def")
        (let [[_ p2]   (expect tokens p :kw "def")
              [nm p3]  (expect tokens p2 :ident)
              [_ p4]   (expect tokens p3 :punct ":")
              [ty p5]  (parse-type tokens p4)
              [args p6] (if (at? tokens p5 :punct "(")
                          (parse-args-parens tokens p5)
                          [nil p5])]
          (recur (conj body {:node :nested-atom :name nm :type ty :ctor-args args})
                 (skip-newlines tokens p6)))

        ;; `state:` section retired at v1.0 (spec §1.11, §1.259). All
        ;; fields are `data:` (per-instance; updated only through
        ;; transitions). Targeted diagnostic beats the generic
        ;; "expected …" fall-through so the migration path is visible.
        (and (at? tokens p :ident)
             (= "state" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct ":"))
        (fail! tokens p
               (str "`state:` section is retired at v1.0. All fields are "
                    "`data:` (per-instance, updated only through "
                    "transitions). See spec §1.11."))

        (looks-like-observable? tokens p)
        (let [[o p2] (parse-observable tokens p)]
          (recur (conj body o) (skip-newlines tokens p2)))

        (looks-like-handler-decl? tokens p)
        (let [[h p2] (parse-handler-decl tokens p)]
          (recur (conj body h) (skip-newlines tokens p2)))

        (looks-like-transition? tokens p)
        (let [[t p2] (parse-transition tokens p)]
          (recur (conj body t) (skip-newlines tokens p2)))

        :else
        (fail! tokens p "expected use:, data:, invariants:, methods:, deprecated:, observable, or transition inside def-atom body")))))

;; ---------------------------------------------------------------------------
;; Record  — the smallest top-level form
;;
;;   record <Name>:
;;       <sections>+
;;
;;   Supported sections here: data:, methods:.
;; ---------------------------------------------------------------------------

(defn- parse-record [tokens pos]
  (let [[_ p]      (expect tokens pos :kw "record")
        [name p]   (expect tokens p :ident)
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [_ p]      (expect tokens p :indent)]
    (loop [sections [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :record :name name :sections sections} (inc p)]

        (at? tokens p :newline)
        (recur sections (inc p))

        (at? tokens p :kw "data")
        (let [[s p2] (parse-data-section tokens p)
              p3 (skip-newlines tokens p2)]
          (recur (conj sections s) p3))

        (at? tokens p :kw "methods")
        (let [[s p2] (parse-methods-section tokens p)
              p3 (skip-newlines tokens p2)]
          (recur (conj sections s) p3))

        (at? tokens p :kw "deprecated")
        (let [[s p2] (parse-deprecated-section tokens p)
              p3 (skip-newlines tokens p2)]
          (recur (conj sections s) p3))

        ;; `state:` section retired at v1.0 (spec §1.11).
        (and (at? tokens p :ident)
             (= "state" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct ":"))
        (fail! tokens p
               (str "`state:` section is retired at v1.0. Records are "
                    "immutable; use `data:` fields. See spec §1.11."))

        :else
        (fail! tokens p "expected data:, methods:, deprecated:, or dedent in record body")))))

(defn- parse-enum
  "Parse `enum Foo:` followed by an indented list of ident cases,
   one per line. Cases carry no payloads in this subset (v1.0 spec
   allows tagged variants but game-toys uses bare cases only).

     enum Key:
         arrow_up
         arrow_down
         f"
  [tokens pos]
  (let [[_ p]    (expect tokens pos :kw "enum")
        [name p] (expect tokens p :ident)
        [_ p]    (expect tokens p :punct ":")
        [_ p]    (expect tokens p :newline)
        [_ p]    (expect tokens p :indent)]
    (loop [cases [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :enum :name name :cases cases} (inc p)]

        (at? tokens p :newline)
        (recur cases (inc p))

        (at? tokens p :ident)
        (let [[c p2] (expect tokens p :ident)]
          (recur (conj cases c) (skip-newlines tokens p2)))

        :else
        (fail! tokens p "expected an enum case identifier or dedent in enum body")))))

;; ---------------------------------------------------------------------------
;; Module header  — module a.b.c
;; from-import   — from a.b.c import X, Y, Z
;; ---------------------------------------------------------------------------

(defn- parse-dotted [tokens pos]
  (let [[first p] (expect tokens pos :ident)]
    (loop [parts [first] p p]
      (if (and (at? tokens p :punct ".") (at? tokens (inc p) :ident))
        (recur (conj parts (t-value (peek-tok tokens (inc p)))) (+ p 2))
        [(str/join "." parts) p]))))

(defn- parse-module-decl [tokens pos]
  (let [[_ p]   (expect tokens pos :kw "module")
        [dotted p] (parse-dotted tokens p)
        [_ p]   (expect tokens p :newline)]
    [{:node :module :name dotted} p]))

(defn- parse-from-import [tokens pos]
  (let [[_ p]      (expect tokens pos :kw "from")
        [dotted p] (parse-dotted tokens p)
        [_ p]      (expect tokens p :kw "import")]
    (loop [names [] p p]
      (let [[n p2] (expect tokens p :ident)]
        (cond
          (at? tokens p2 :punct ",")
          (recur (conj names n) (inc p2))
          (at? tokens p2 :newline)
          [{:node :from-import :from dotted :names (conj names n)} (inc p2)]
          :else (fail! tokens p2 "expected `,` or newline in from-import"))))))

;; ---------------------------------------------------------------------------
;; Top-level dispatch
;; ---------------------------------------------------------------------------

(defn- parse-top [tokens pos]
  (let [p (skip-newlines tokens pos)]
    (cond
      (at? tokens p :eof)          [nil p]
      (at? tokens p :kw "module")  (parse-module-decl tokens p)
      (at? tokens p :kw "from")    (parse-from-import tokens p)
      (at? tokens p :kw "record")  (parse-record tokens p)
      (at? tokens p :kw "enum")    (parse-enum tokens p)
      (at? tokens p :kw "def")     (parse-def-atom tokens p)
      ;; `extern <Name>:` section retired at v1.0 (spec §1.12, §2335).
      ;; FFI is a capability atom (Extern) provisioned to stdlib shims
      ;; via `use: extern: Extern`; foreign symbols are declared in
      ;; per-target prelude `foreign library "..."` blocks (§1.14).
      ;; Both are deferred to Phase D (see compiler/design/spec-drift-audit.md).
      (and (at? tokens p :ident)
           (= "extern" (t-value (peek-tok tokens p))))
      (fail! tokens p
             (str "`extern` as a top-level section keyword is retired at v1.0. "
                  "FFI moves to prelude `foreign library` blocks + the "
                  "`Extern` capability atom. See spec §1.12 and §1.14."))
      :else (fail! tokens p "expected top-level form"))))

(defn parse
  "Parse a cxc source string into a sequence of top-level AST nodes."
  [source]
  (let [tokens (lex/tokenize source)]
    (loop [nodes [] pos 0]
      (let [pos (skip-newlines tokens pos)]
        (if (at? tokens pos :eof)
          (desugar-chained-comparisons nodes)
          (let [[node pos'] (parse-top tokens pos)]
            (recur (conj nodes node) pos')))))))

