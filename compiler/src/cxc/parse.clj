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
  (let [[inner p]
        (cond
          ;; F15 Landing 1 — paren-tuple type at start position. Two
          ;; shapes disambiguated by peeking after the `(`:
          ;;   (x: Int, y: Int)  — named tuple / shape (spec §3.2)
          ;;   (Int, String)     — anonymous tuple (spec §3.2)
          ;; Mixed forms are grammar errors: every field named, or none.
          (at? tokens pos :punct "(")
          (let [after-lp (inc pos)
                ;; Named tuple iff `ident :` follows the `(`.
                named? (and (at? tokens after-lp :ident)
                            (at? tokens (inc after-lp) :punct ":"))]
            (if named?
              ;; (ident : type, ident : type, ...)
              (loop [fields [] p after-lp]
                (let [[nm p1] (expect tokens p :ident)
                      _       (when-not (at? tokens p1 :punct ":")
                                (fail! tokens p1
                                       "expected `:` in named-tuple field decl"))
                      [ty p2] (parse-type tokens (inc p1))
                      fields  (conj fields {:name nm :type ty})]
                  (cond
                    (at? tokens p2 :punct ",")
                    (recur fields (inc p2))
                    (at? tokens p2 :punct ")")
                    [{:node :type-named-tuple :fields fields} (inc p2)]
                    :else
                    (fail! tokens p2
                           "expected `,` or `)` in named-tuple type"))))
              ;; (type, type, ...) — anonymous tuple; error on mixed
              ;; ident+colon form we didn't recognize as named.
              (loop [ts [] p after-lp]
                (let [[t p1] (parse-type tokens p)
                      ts (conj ts t)]
                  (cond
                    (at? tokens p1 :punct ",")
                    (recur ts (inc p1))
                    (at? tokens p1 :punct ")")
                    (if (= 1 (count ts))
                      ;; `(T)` = paren-grouped single type; no tuple.
                      [t (inc p1)]
                      [{:node :type-tuple :types ts} (inc p1)])
                    :else
                    (fail! tokens p1
                           "expected `,` or `)` in tuple type"))))))

          :else
          (let [[name p]  (expect tokens pos :ident)
                [inner p] (cond
                            ;; `T of ( K , V , ... )` — Layer 6 Step 8.
                            ;; Paren-tuple as the `of` operand carries
                            ;; multiple type params (Map of (K, V), Pair
                            ;; of (A, B) etc). The :inner slot becomes a
                            ;; :type-tuple node whose :types is the
                            ;; ordered vector of sub-types.
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
                            [{:node :type-ref :name name} p])]
            [inner p]))
        ;; F39 Landing 2 — postfix `?` / `!` are grammar-mutually-exclusive.
        ;; `T?` is nullable (Optional for value types, redundant on refs
        ;; per L1). `T!` is non-nil contract (author expectation; runtime
        ;; enforced at observation points in L3). Nesting like `T?!` or
        ;; `T!?` is a grammar error.
        [wrapped p] (cond
                      (at? tokens p :punct "?")
                      (let [w [{:node :type-nullable :inner inner} (inc p)]]
                        (when (or (at? tokens (inc p) :punct "?")
                                  (at? tokens (inc p) :punct "!"))
                          (fail! tokens (inc p)
                                 "cannot stack `?`/`!` on the same type (spec §3.6.1)"))
                        w)
                      (at? tokens p :punct "!")
                      (let [w [{:node :type-nonnil :inner inner} (inc p)]]
                        (when (or (at? tokens (inc p) :punct "?")
                                  (at? tokens (inc p) :punct "!"))
                          (fail! tokens (inc p)
                                 "cannot stack `?`/`!` on the same type (spec §3.6.1)"))
                        w)
                      :else [inner p])]
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

(defn- warn-function-deprecated!
  "K8 — the `function` method opener is retired at v1.0 (spec §1201,
   §2330); spec canonical is `def`. Prints a per-occurrence
   deprecation warning to *err* naming file/line so users can migrate.
   Non-fatal — parser still accepts the keyword. A `--strict-
   deprecations` flag to promote to error is a follow-on."
  [tokens pos]
  (let [t (peek-tok tokens pos)]
    (binding [*out* *err*]
      (println (str "warning: `function` method opener is deprecated at v1.0 "
                    "(spec §1201, §2330) — use `def` instead. At line "
                    (t-line t) ", col " (t-col t) ".")))))

(defn- parse-function-decl
  "Method declaration in two accepted forms:
     function name(params) -> RetType: <block>   ; legacy — audit K8
     def name(params) -> RetType: <block>        ; spec §2.5 canonical

   The `function` keyword is retired at v1.0 (spec §1201/§2330); we
   still parse it during the grace period but emit a per-occurrence
   deprecation warning via `warn-function-deprecated!`. The `def`
   form is distinguished from a nested-atom slot (`def name: TypeName`)
   by the paren immediately after the name."
  [tokens pos]
  (let [[_ p]      (cond
                     (at? tokens pos :kw "function")
                     (do (warn-function-deprecated! tokens pos)
                         (expect tokens pos :kw "function"))
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

(defn- parse-data-section
  "F67 L5 — `data:` section header is retired (Proposal D). Fields
   move to class-body top level:

     def X:                     def X:
         data:              →       n: Int = 10
             n: Int = 10           m: Float = 1.0
             m: Float = 1.0

   Semantics unchanged — the parser folds top-level `:field` nodes into
   a synthetic `:data-section` (see fold-top-level-fields) so every
   downstream lower/emit consumer keeps its shape. The collapse removes
   the redundant grouping section: fields ARE the class body's default
   content, so declaring the group is noise."
  [tokens pos]
  (fail! tokens pos
         (str "F67 L5: `data:` section header is retired (Proposal G / "
              "Proposal D — class-body collapse). Delete the `data:` line "
              "and unindent the fields by 4 spaces so they sit at class-body "
              "top level:\n\n"
              "    def X:                     def X:\n"
              "        data:              →       n: Int = 10\n"
              "            n: Int = 10           m: Float = 1.0\n\n"
              "Semantics unchanged (spec §1.11). Fields at top level are "
              "gathered into the same data-section AST downstream; the "
              "collapse removes the redundant grouping header.")))

(defn- parse-methods-section
  "F67 L4 — `methods:` section is retired (Proposal G class-body collapse).
   Under Corvid's actor substrate, class-body callables collapse to two
   shapes:

     1. Observable (pure read):
            <name>[(args)] :< <expression>
     2. Receive-glyph transition (message handler; may mutate):
            * <: <name>(args) -> *
                [when: <guard>] [then: <update>] [do: <side effect>]

   Utility functions unrelated to `self` move to module scope
   (`def name(args) -> T: body`). Records get the reader + utility
   cases; transitions are structurally forbidden on records."
  [tokens pos]
  (fail! tokens pos
         (str "F67 L4: `methods:` section is retired (Proposal G class-body "
              "collapse). Under Corvid's actor substrate, class-body callables "
              "are one of two shapes:\n\n"
              "  1. Observable (pure read):\n"
              "         <name>(args) :< <expression>\n\n"
              "  2. Receive-glyph transition (message handler; may mutate):\n"
              "         * <: <name>(args) -> *\n"
              "             [when: <guard>] [then: <update>] [do: <side effect>]\n\n"
              "Migrate each method by category:\n\n"
              "  - `function foo(self, args) -> T: return <expr>`  (reader)\n"
              "      → `foo(args) :< <expr>`\n"
              "  - `function foo(self, args): <side effect>`       (void)\n"
              "      → `* <: foo(args) -> * do: <side effect>`\n"
              "  - `function foo(self, args): <mutation>`          (writer)\n"
              "      → `* <: foo(args) -> * then: <update>`\n\n"
              "Utility functions that don't operate on `self` belong at module\n"
              "scope: `def foo(args) -> T: body`.\n\n"
              "See spec §1.11.")))

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
;; Observables:
;;   Zero-arg:   <name> :< <expr>
;;   Parametric: <name>(<param-list>) :< <expr>       # F67 L2
;;   Declaration-flavored assignment (§1.11). The colon-less-than glyph.
;;   Parametric observables (Proposal G L2) replace reader methods: any
;;   pure computation over self's fields with args now goes here rather
;;   than in a `methods:` section.
;; ---------------------------------------------------------------------------

(defn- parse-observable [tokens pos]
  (let [[name p]        (expect tokens pos :ident)
        [params p]      (if (at? tokens p :punct "(")
                          (parse-param-list-parens tokens p)
                          [[] p])
        ;; F67 L4 — optional explicit return-type annotation
        ;; `name(args) -> T :< expr`. Needed for observables serving as
        ;; method-stream sources (receive-glyph subscription requires
        ;; parametric `Stream of T` structure). Inference otherwise.
        [ret p]         (if (at? tokens p :glyph "->")
                          (parse-type tokens (inc p))
                          [nil p])
        [_ p]           (expect tokens p :glyph ":<")
        [expr p]        (parse-expr tokens p)]
    [{:node :observable :name name :params params
      :return-type ret :expr expr} p]))

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

(defn- looks-like-typed-params?
  "At `pos` = `(`. Distinguish an F67 L3 typed-params receive pattern
   (`msg(a: T, b: U)` — the handler-collapse form) from a message-shape
   expression's parens (`clock.every(rate){t: Instant}`). Typed-params
   shape: `()` or `(ident : ...)`. Everything else = expression."
  [tokens pos]
  (let [p (inc pos)]
    (or (at? tokens p :punct ")")
        (and (at? tokens p :ident)
             (at? tokens (inc p) :punct ":")))))

(defn- parse-msg-pattern
  "Parse a receive-glyph source pattern. Two shapes:

     1. Payload destructure (from streams/observables):
          tick{dt: Duration}
          input.pressed{key: Key}
          clock.every(rate){_: Instant}
          active_enemies.died{e: Enemy}
        parse-expr turns `X{...}` into a :msg-shape postfix.

     2. F67 L3 — typed-params receive (handler-collapse form):
          log(msg: String)
          fire(target: Enemy, dmg: Int)
        Distinguished by parens holding typed params (not expressions).
        Under `* <: msg(args) -> *`, this lowers to a handler-shaped
        method entry (spec §1265 semantics) — see lower-def-atom."
  [tokens pos]
  (cond
    ;; F67 L3 — typed-params form: `<ident>(<params>)`
    (and (at? tokens pos :ident)
         (at? tokens (inc pos) :punct "(")
         (looks-like-typed-params? tokens (inc pos)))
    (let [[name p]   (expect tokens pos :ident)
          [params p] (parse-param-list-parens tokens p)]
      [{:source {:node :ident :name name}
        :params params
        :payload []}
       p])

    :else
    (let [[e p] (parse-expr tokens pos)]
      (if (= :msg-shape (:node e))
        [{:source (:target e) :payload (:fields e) :params nil} p]
        [{:source e :payload [] :params nil} p]))))

(defn- parse-source-and-receive
  "Look at the head of a transition. Returns [source receive p] where:
     source  = {:kind :phase :name X}
             | {:kind :self}
             | {:kind :wildcard}     ; F67 L3 — `*` = from any phase
             | {:kind :implicit}
     receive = {:glyph <: :msg {...}} | nil

   Allows newline between source and receive-glyph — the two- and three-line
   transition header shapes both parse to the same AST."
  [tokens pos]
  (cond
    (at? tokens pos :glyph "->")
    [{:kind :implicit} nil pos]

    :else
    (let [source (cond
                   (at? tokens pos :glyph "*")   {:kind :wildcard}
                   (at? tokens pos :kw "self")   {:kind :self}
                   (at? tokens pos :ident)       {:kind :phase :name (t-value (peek-tok tokens pos))}
                   :else (fail! tokens pos "expected source-phase, self, `*`, or `->` at start of transition"))
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
                     ;; F67 L3 — `*` target = wildcard (no state change).
                     ;; Semantically equivalent to `self` for phase-machine
                     ;; purposes; kept separate so we can enforce
                     ;; `* <: msg(args) -> *` = handler-shape at lowering.
                     (at? tokens p :glyph "*") ["*" (inc p)]
                     (at? tokens p :kw "self") ["self" (inc p)]
                     (at? tokens p :ident)     [(t-value (peek-tok tokens p)) (inc p)]
                     :else (fail! tokens p "expected target phase, `self`, or `*` after `->`"))
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
;;     - `<ident> :<` or `<ident>(...) :<`           → observable (zero-arg or parametric)
;;     - `<ident|self> <:|<|` OR `<ident> ->` OR `->` → transition
;; ---------------------------------------------------------------------------

(defn- scan-past-parens
  "Given the token at `pos` is an open `(`, return the position just
   after its matching close `)`. Balances nested parens. Returns nil if
   no match is found before EOF."
  [tokens pos]
  (loop [p (inc pos) depth 1]
    (cond
      (>= p (count tokens)) nil
      (at? tokens p :punct "(") (recur (inc p) (inc depth))
      (at? tokens p :punct ")") (if (= depth 1) (inc p) (recur (inc p) (dec depth)))
      :else                     (recur (inc p) depth))))

(defn- looks-like-observable? [tokens pos]
  (and (at? tokens pos :ident)
       (or (at? tokens (inc pos) :glyph ":<")
           ;; F67 L2 — parametric observable: `<name>(params) :<`
           ;; F67 L4 — optional explicit return type between `)` and `:<`:
           ;;   `<name>(params) -> T :< expr`.
           (and (at? tokens (inc pos) :punct "(")
                (when-let [after-close (scan-past-parens tokens (inc pos))]
                  (or (at? tokens after-close :glyph ":<")
                      (at? tokens after-close :glyph "->")))))))

;; F14 / F67 L3 — the legacy `-> handler(args):` sugar (F14) is retired
;; under Proposal G's class-body collapse. The migration target is the
;; explicit wildcard-transition form:
;;
;;   -> foo(msg: T):    →    * <: foo(msg: T) -> *
;;       stmt                     do: stmt
;;
;; Both forms lower to the same handler-shaped method entry (spec §1265
;; semantics); the collapse removes the parallel opener glyph. This
;; predicate + parser fire the migration diagnostic — see
;; transition->handler-decl in lower.clj for the target lowering.
(defn- looks-like-handler-decl? [tokens pos]
  (and (at? tokens pos :glyph "->")
       (at? tokens (inc pos) :ident)
       (at? tokens (+ pos 2) :punct "(")))

(defn- parse-handler-decl [tokens pos]
  (let [[_ _]        (expect tokens pos :glyph "->")
        name-tok     (peek-tok tokens (inc pos))
        handler-name (t-value name-tok)]
    (fail! tokens pos
           (str "F67 L3: `-> " handler-name "(args):` handler-decl sugar is "
                "retired (Proposal G class-body collapse). Migrate to the "
                "explicit handler-shape transition:\n\n"
                "    * <: " handler-name "(args) -> *\n"
                "        do: <body>\n\n"
                "Semantics unchanged (spec §1265) — same method-table entry, "
                "same external invocation surface. The collapse removes the "
                "parallel opener glyph so the class body has one shape per "
                "kind of declaration."))))

(defn- looks-like-field-decl?
  "F67 L5 — top-level field declaration at atom/record body:
     `<lowercase-ident> : <TypeRef> [= <expr>]`
   Distinguished from transition source-phase (`<ident> <:` / `<ident> ->`)
   and from observable (`<ident> :<` / `<ident>(...) :<`) by requiring
   `:punct \":\"` at position 1 (observables use `:glyph \":<\"`,
   transitions use `:glyph`). Section keywords are `:kw`, not `:ident`,
   so they never match here.

   The extra `NOT :newline at pos+2` guard rejects retired-section
   headers with lowercase-ident-looking names (e.g. `state:` which
   would otherwise slip through since `state` lexes as `:ident`, not
   `:kw`). Field decls always have a type token after the colon."
  [tokens pos]
  (and (at? tokens pos :ident)
       (at? tokens (inc pos) :punct ":")
       (not (at? tokens (+ pos 2) :newline))))

(defn- fold-top-level-fields
  "F67 L5 — after parsing an atom/record body containing individual
   `:field` nodes (Proposal D top-level surface), gather them into a
   single synthetic `:data-section` at the position of the first field
   so downstream AST consumers stay shape-stable. If a legacy
   `:data-section` is also present, fold the top-level fields into it
   at the section's position (preserving declaration order via the
   section-first, top-level-fields-second convention).

   Body positions of individual `:field` nodes are removed; the
   synthesized/merged section takes their combined position slot."
  [body]
  (let [fields   (filterv #(= :field (:node %)) body)
        legacy   (first (filter #(= :data-section (:node %)) body))]
    (cond
      (empty? fields)
      body

      legacy
      (let [merged  (assoc legacy :fields
                          (into (or (:fields legacy) []) fields))
            without-fields (remove #(= :field (:node %)) body)]
        (mapv (fn [n] (if (= :data-section (:node n)) merged n))
              without-fields))

      :else
      (let [first-field-idx (some (fn [[i n]]
                                    (when (= :field (:node n)) i))
                                  (map-indexed vector body))
            without-fields (remove #(= :field (:node %)) body)
            synthetic       {:node :data-section :fields fields}]
        (into (subvec (vec without-fields) 0 first-field-idx)
              (into [synthetic]
                    (subvec (vec without-fields) first-field-idx)))))))

(defn- looks-like-transition? [tokens pos]
  ;; Called after observable / field / section checks have failed. At
  ;; atom-body top level, the remaining possibilities are transitions
  ;; and errors. A transition starts with `->` (implicit source),
  ;; `self`, `*` (F67 L3 wildcard source), or a source-phase identifier.
  ;; Since the observable + field cases are already excluded, any bare
  ;; ident is treated as a transition-source.
  (or (at? tokens pos :glyph "->")
      (at? tokens pos :glyph "*")
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
        [{:node :def-atom :name name :ctor-params ctor
          :body (fold-top-level-fields body)}
         (inc p)]

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

        ;; F67 L4 — `function` keyword inside atom body retires with the
        ;; `methods:` section (Proposal G class-body collapse). Free
        ;; functions still live at module scope.
        (at? tokens p :kw "function")
        (fail! tokens p
               (str "F67 L4: `function` keyword inside `def " name "` body is "
                    "retired (Proposal G class-body collapse). Migrate the "
                    "method by category:\n\n"
                    "  - reader → observable: `name(args) :< <expr>`\n"
                    "  - void   → transition: `* <: name(args) -> * do: <body>`\n"
                    "  - writer → transition: `* <: name(args) -> * then: <update>`\n\n"
                    "Utility functions unrelated to `self` belong at module "
                    "scope (`def name(args) -> T: body`). See spec §1.11."))

        ;; F67 L4 — spec-canonical method form `def name(args) -> T: body`
        ;; inside a class body retires with `methods:` (Proposal G collapse).
        (at-def-method? tokens p)
        (fail! tokens p
               (str "F67 L4: `def " (t-value (peek-tok tokens (inc p)))
                    "(args)` method form inside `def " name "` body is "
                    "retired (Proposal G class-body collapse). Migrate the "
                    "method by category:\n\n"
                    "  - reader → observable: `" (t-value (peek-tok tokens (inc p)))
                    "(args) :< <expr>`\n"
                    "  - void   → transition: `* <: " (t-value (peek-tok tokens (inc p)))
                    "(args) -> * do: <body>`\n"
                    "  - writer → transition: `* <: " (t-value (peek-tok tokens (inc p)))
                    "(args) -> * then: <update>`\n\n"
                    "Utility functions unrelated to `self` belong at module "
                    "scope. See spec §1.11."))

        ;; Nested atom slot: `def <name>: <TypeName>[(ctor-args)]`
        ;; Distinct from top-level `def <Name>:` (which opens a block)
        ;; and from the retired method form `def name(...) -> T:`
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

        ;; F67 L5 — top-level field declaration (replaces `data:` section
        ;; per Proposal D). Must precede `looks-like-transition?` because
        ;; a bare ident matches both; the `:punct ":"` guard picks fields
        ;; and leaves phase-source idents (`phase <:`, `phase ->`) to the
        ;; transition branch.
        (looks-like-field-decl? tokens p)
        (let [[f p2] (parse-field tokens p)]
          (recur (conj body f) (skip-newlines tokens p2)))

        (looks-like-transition? tokens p)
        (let [[t p2] (parse-transition tokens p)]
          (recur (conj body t) (skip-newlines tokens p2)))

        :else
        (fail! tokens p "expected use:, field decl (`name: Type [= default]`), invariants:, deprecated:, observable (`name[(args)] :< expr`), or transition inside def-atom body")))))

;; ---------------------------------------------------------------------------
;; F9 Landing 1 — `class` / `protocol` sugar over `class + metadata: kind:`.
;;
;;   class Foo:
;;       metadata:
;;           kind: regular            # or record / enum / protocol / section
;;       <sections>+
;;
;;   Sugar form (spec §4.11 — sugar desugars losslessly):
;;   protocol P:                      → class P + metadata: kind: protocol
;;       <sections>+
;;
;;   Header parens conformances (§3.4.5):
;;   class Foo(P1, P2):               → :conforms [P1 P2] on the class node
;;       <sections>+
;;
;;   Body-form conformances (§3.4.5, canonical for extensions / long lists):
;;   class Foo:
;;       conforms: P1
;;       conforms: P2                 → :conforms [P1 P2]
;;
;;   L1 is grammar-only. Lower emits `fail-nyi!` on `:class` nodes with a
;;   pointer at F9 L2 (which builds protocol-env + attaches semantics).
;;   Existing `record` / `enum` sugar keywords keep their dedicated parsers
;;   and continue to produce `:record` / `:enum` nodes for backward compat;
;;   the L1 → :class path handles ONLY `class` / `protocol` at top level.
;; ---------------------------------------------------------------------------

(def ^:private legal-kinds
  #{"regular" "record" "enum" "protocol" "section"})

(defn- parse-accepts-signature
  "Parse one `accepts:` entry — a method signature with no body.
     [def|function] name(params) -> ReturnType
   L2 MVP: no contract clauses (`requires`/`ensures`) and no effect
   annotations (`async`/`io`). Continuation-indented lines after the
   signature fail-nyi with a pointer at a follow-on landing."
  [tokens pos]
  (let [[_ p]      (cond
                     (at? tokens pos :kw "function")
                     (do (warn-function-deprecated! tokens pos)
                         (expect tokens pos :kw "function"))
                     (at? tokens pos :kw "def")      (expect tokens pos :kw "def")
                     :else                            [nil pos])
        [name p]   (expect tokens p :ident)
        [params p] (parse-param-list-parens tokens p)
        [ret p]    (if (at? tokens p :glyph "->")
                     (parse-type tokens (inc p))
                     [nil p])
        [_ p]      (expect tokens p :newline)
        ;; Reject continuation lines (requires/ensures/async/io).
        _          (when (at? tokens p :indent)
                     (fail! tokens p
                            (str "contract clauses (`requires`/`ensures`) and "
                                 "effect annotations (`async`/`io`) on `accepts:` "
                                 "entries are not yet implemented. F9 L2 ships the "
                                 "signature surface; contract inheritance lands "
                                 "with F49 protocol-inherited-contracts. See "
                                 "plans/f9-class-protocol-extension.md L2.")))]
    [{:node :accepts-entry :name name :params params :return-type ret} p]))

(defn- parse-accepts-section
  "`accepts:` block on `kind: protocol` classes.
     accepts:
         foo(Int) -> Int
         bar(String) -> Bool
   Also supports the per-entry sugar at class-body level:
     accepts foo(Int) -> Int
   handled inside parse-class-section."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "accepts")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [entries [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :accepts-section :entries entries} (inc p)]

        (at? tokens p :newline)
        (recur entries (inc p))

        :else
        (let [[e p2] (parse-accepts-signature tokens p)]
          (recur (conj entries e) (skip-newlines tokens p2)))))))

(defn- parse-metadata-section
  "L1: accepts only `kind: <value>` inside. Other metadata keys (thread,
   qos, pool, superclass, extends, custom user keys) belong to F10 and
   fail-nyi with a targeted pointer."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "metadata")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [entries [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :metadata-section :entries entries} (inc p)]

        (at? tokens p :newline)
        (recur entries (inc p))

        (and (at? tokens p :ident) (= "kind" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct ":"))
        (let [[_ p2]   (expect tokens p :ident)
              [_ p3]   (expect tokens p2 :punct ":")
              [kv p4]  (cond
                         (at? tokens p3 :ident) (expect tokens p3 :ident)
                         (at? tokens p3 :kw)    (expect tokens p3 :kw))
              _        (when-not (legal-kinds kv)
                         (fail! tokens p3
                                (str "unknown `metadata: kind:` value `" kv "`; "
                                     "must be one of: regular, record, enum, "
                                     "protocol, section (spec §3.3).")))]
          (recur (conj entries {:key :kind :value (keyword kv)})
                 (skip-newlines tokens p4)))

        (at? tokens p :ident)
        (fail! tokens p
               (str "`metadata:` key `" (t-value (peek-tok tokens p))
                    "` is not yet implemented — F9 Landing 1 accepts "
                    "only `kind:`. Full metadata surface (threading, "
                    "QoS, dispatch pool, superclass, custom keys) "
                    "lands with F10. See spec §4.10.6."))

        :else
        (fail! tokens p "expected `kind:` entry or dedent in metadata section")))))

(defn- parse-conforms-line
  "Body-form conformance declaration:
     conforms: <TypeName>
   Multiple `conforms:` lines legal per §3.4.5 rule 5 (union semantics)."
  [tokens pos]
  (let [[_ p]  (expect tokens pos :ident)                      ; \"conforms\"
        [_ p]  (expect tokens p :punct ":")
        [ty p] (parse-type tokens p)
        [_ p]  (expect tokens p :newline)]
    [{:node :conforms-line :type ty} p]))

(defn- parse-header-parens-conformances
  "Parse `(P1, P2, ...)` after a class/protocol name. Empty parens `()` is
   a hard error (nothing conforms to nothing — this is not the Unit type
   at type-position, per §3.4.5 rule not-said-but-implied)."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (when (at? tokens p :punct ")")
      (fail! tokens p
             "empty conformance list `()` — omit the parens if no protocols."))
    (loop [confs [] p p]
      (let [[ty p2] (parse-type tokens p)]
        (cond
          (at? tokens p2 :punct ",")
          (recur (conj confs ty) (inc p2))
          (at? tokens p2 :punct ")")
          [(conj confs ty) (inc p2)]
          :else
          (fail! tokens p2
                 "expected `,` or `)` in conformance list"))))))

(defn- fold-class-body
  "Post-parse fold: extract `metadata: kind:` into the class node's `:kind`
   field; extract body-form `conforms:` entries into `:conforms`; extract
   `:accepts-section` entries into `:accepts` (merged across all sections
   in the same class, per §3.4.5 rule 5's union semantics for repeated
   headers). Validates sugar-kind agreement (e.g. `protocol Foo:
   metadata: kind: enum` errors) and kind-vs-section legality (e.g.
   `accepts:` only on `kind: protocol`; `data:` forbidden on protocols).

   sugar-kind is a keyword (:protocol, :regular) inferred from the top-level
   keyword; nil for the primitive `class` form."
  [tokens pos-for-diag class-name sugar-kind header-conforms body]
  (let [metadata-secs (filter #(= :metadata-section (:node %)) body)
        conforms-lines (filter #(= :conforms-line (:node %)) body)
        accepts-secs  (filter #(= :accepts-section (:node %)) body)
        data-secs     (filter #(= :data-section (:node %)) body)
        other-sections (remove #(contains? #{:metadata-section :conforms-line
                                             :accepts-section}
                                            (:node %))
                                body)
        kinds-from-metadata (keep (fn [ms]
                                    (some (fn [e]
                                            (when (= :kind (:key e)) (:value e)))
                                          (:entries ms)))
                                  metadata-secs)
        _ (when (> (count metadata-secs) 1)
            (fail! tokens pos-for-diag
                   (str "multiple `metadata:` sections on class `" class-name
                        "` — collapse into one.")))
        _ (when (> (count kinds-from-metadata) 1)
            (fail! tokens pos-for-diag
                   (str "duplicate `kind:` entries in `metadata:` on class `"
                        class-name "` — kind is set once at declaration.")))
        explicit-kind (first kinds-from-metadata)
        final-kind (cond
                     (and sugar-kind explicit-kind
                          (not= sugar-kind explicit-kind))
                     (fail! tokens pos-for-diag
                            (str "sugar keyword `" (name sugar-kind)
                                 "` on class `" class-name "` contradicts "
                                 "`metadata: kind: " (name explicit-kind)
                                 "` — pick one."))
                     :else
                     (or explicit-kind sugar-kind :regular))
        _ (when (and (seq accepts-secs) (not= :protocol final-kind))
            (fail! tokens pos-for-diag
                   (str "`accepts:` is only valid on `kind: protocol`; "
                        "class `" class-name "` has kind `" (name final-kind)
                        "`. Regular/record/enum classes declare their "
                        "surface via observables (`name(args) :< expr`) "
                        "and receive-glyph transitions "
                        "(`* <: name(args) -> * do: <body>`). See spec §3.4.5.")))
        _ (when (and (= :protocol final-kind) (seq data-secs))
            (fail! tokens pos-for-diag
                   (str "`data:` is not permitted on `kind: protocol` — "
                        "protocols declare no state, only method surface. "
                        "See spec §3.4.5. Class `" class-name "`.")))
        accepts-entries (vec (mapcat :entries accepts-secs))
        body-conforms (mapv :type conforms-lines)
        all-conforms  (into (vec header-conforms) body-conforms)]
    {:node     :class
     :name     class-name
     :kind     final-kind
     :conforms all-conforms
     :accepts  accepts-entries
     :body     (vec other-sections)}))

(declare parse-transition parse-observable)

(defn- parse-class-section
  "Dispatch one section inside a `class`/`protocol` body. Accepts:
   - metadata: (L1 — kind: only)
   - data:            (retired L5; raises with L5 diagnostic)
   - methods:         (retired L4; raises with L4 diagnostic)
   - invariants:
   - use:
   - deprecated:
   - conforms: (body-form)
   - accepts:         (L2 — protocol signature section)
   - accepts <sig>    (L2 — per-entry sugar at class body level)
   - observable       (F67 L4 — reader-default on protocols; observable
                       body on regular/record kinds)
   - transition       (F67 L4 — wildcard self-transition default)
   Rejects `state:` (retired at v1.0); other keys fall through to the
   caller's else-branch."
  [tokens pos]
  (cond
    (at? tokens pos :kw "metadata")   (parse-metadata-section tokens pos)
    (at? tokens pos :kw "data")       (parse-data-section tokens pos)
    (at? tokens pos :kw "methods")    (parse-methods-section tokens pos)
    (at? tokens pos :kw "invariants") (parse-invariants-section tokens pos)
    (at? tokens pos :kw "use")        (parse-use-section tokens pos)
    (at? tokens pos :kw "deprecated") (parse-deprecated-section tokens pos)

    ;; F67 L4 — `function` keyword inside class/protocol body retires
    ;; with `methods:` (Proposal G collapse). Free functions still live
    ;; at module scope.
    (at? tokens pos :kw "function")
    (fail! tokens pos
           (str "F67 L4: `function` keyword inside class body is retired "
                "(Proposal G class-body collapse). Migrate:\n\n"
                "  - reader → observable: `name(args) :< <expr>`\n"
                "  - void   → transition: `* <: name(args) -> * do: <body>`\n"
                "  - writer → transition: `* <: name(args) -> * then: <update>`\n\n"
                "Utility functions unrelated to `self` belong at module scope. "
                "See spec §1.11."))

    ;; F67 L4 — spec-canonical `def name(args) -> T: body` method form
    ;; inside a class body retires (Proposal G collapse).
    (at-def-method? tokens pos)
    (fail! tokens pos
           (str "F67 L4: `def " (t-value (peek-tok tokens (inc pos)))
                "(args)` method form inside class body is retired "
                "(Proposal G class-body collapse). Migrate:\n\n"
                "  - reader → observable: `" (t-value (peek-tok tokens (inc pos)))
                "(args) :< <expr>`\n"
                "  - void   → transition: `* <: " (t-value (peek-tok tokens (inc pos)))
                "(args) -> * do: <body>`\n"
                "  - writer → transition: `* <: " (t-value (peek-tok tokens (inc pos)))
                "(args) -> * then: <update>`\n\n"
                "See spec §1.11."))

    ;; F9 L2 — `accepts:` section (multiple signatures under one header)
    (and (at? tokens pos :kw "accepts") (at? tokens (inc pos) :punct ":"))
    (parse-accepts-section tokens pos)

    ;; F9 L2 — `accepts foo(...)` per-entry sugar at class body level.
    ;; Distinguished from `accepts:` by presence of an identifier (not
    ;; `:`) immediately after the keyword. Produces a single-entry
    ;; :accepts-section for uniform downstream consumption.
    (and (at? tokens pos :kw "accepts") (at? tokens (inc pos) :ident))
    (let [[_ p]  (expect tokens pos :kw "accepts")
          [e p2] (parse-accepts-signature tokens p)]
      [{:node :accepts-section :entries [e]} p2])

    (and (at? tokens pos :ident) (= "conforms" (t-value (peek-tok tokens pos)))
         (at? tokens (inc pos) :punct ":"))
    (parse-conforms-line tokens pos)

    (and (at? tokens pos :ident) (= "state" (t-value (peek-tok tokens pos)))
         (at? tokens (inc pos) :punct ":"))
    (fail! tokens pos
           (str "`state:` section is retired at v1.0 — all fields are `data:`. "
                "See spec §1.11."))

    ;; F67 L4 — observable (reader-default on protocols; observable
    ;; body on regular/record classes). Must precede field-decl guard.
    (looks-like-observable? tokens pos)
    (parse-observable tokens pos)

    ;; F67 L5 — top-level field declaration (Proposal D). Fold
    ;; produces the same synthetic `:data-section` that `data:` did,
    ;; so protocol-kind rejection etc still fires uniformly.
    (looks-like-field-decl? tokens pos)
    (parse-field tokens pos)

    :else nil))

(defn- parse-class
  "Parse a `class` or `protocol` top-level form. Returns [{:node :class …}
   next-pos]. Detects `sealed protocol Foo:` — spec-retired form (§3.4.1) —
   with a targeted diagnostic pointing at `enum` for closed sum types."
  [tokens pos sugar-kind]
  (let [pos-for-diag pos
        [_ p] (cond
                (= :protocol sugar-kind) (expect tokens pos :kw "protocol")
                (= :regular sugar-kind)  (expect tokens pos :kw "class")
                :else                    (expect tokens pos :kw "class"))
        [class-name p] (expect tokens p :ident)
        [header-conforms p] (if (at? tokens p :punct "(")
                              (parse-header-parens-conformances tokens p)
                              [[] p])
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [body [] p p]
      (cond
        (at? tokens p :dedent)
        [(fold-class-body tokens pos-for-diag class-name sugar-kind
                          header-conforms (fold-top-level-fields body))
         (inc p)]

        (at? tokens p :newline)
        (recur body (inc p))

        :else
        (if-let [[sec p2] (parse-class-section tokens p)]
          (recur (conj body sec) (skip-newlines tokens p2))
          (fail! tokens p
                 (str "expected metadata:, invariants:, use:, deprecated:, "
                      "conforms:, accepts:, observable "
                      "(`name[(args)] :< expr`), transition, field decl, "
                      "or dedent in `"
                      (if (= :protocol sugar-kind) "protocol" "class")
                      " " class-name "` body")))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 3 — `extend class Foo:` top-level form.
;;
;;   extend class Foo:                    → :extend-class :target Foo
;;       methods:
;;           function bar(self): ...
;;
;;   extend class Foo(NewProtocol):       → header-parens add to :conforms
;;       ...
;;   extend class Foo:
;;       conforms: NewProtocol            → body-form adds to :conforms
;;
;; Per §3.3.2 "What extensions cannot add", extension bodies accept only
;; methods:, invariants:, and conforms:. `data:`, `state:`, `use:`,
;; `cases:`, `metadata:` (any subkey), and `deprecated:` are rejected
;; at parse with a targeted §3.3.2 pointer. Method entries carry the
;; `replace` modifier via `:replace?` (parsed by shared parse-methods-section).
;;
;; Extension target-name resolution is deferred to lower (L4 composition
;; consumes extension-env; unknown-target diagnostics fire there when the
;; full symbol table is available).
;; ---------------------------------------------------------------------------

(defn- parse-extend-section
  "Dispatch one section inside an `extend class Foo:` body. Strict —
   rejects sections spec §3.3.2 forbids (data/state/use/cases/metadata/
   deprecated). Accepts observables (F67 L4), wildcard self-transitions
   (F67 L4), invariants:, conforms:. `methods:` retires (F67 L4)."
  [tokens pos target]
  (cond
    (at? tokens pos :kw "methods")    (parse-methods-section tokens pos)
    (at? tokens pos :kw "invariants") (parse-invariants-section tokens pos)

    (and (at? tokens pos :ident) (= "conforms" (t-value (peek-tok tokens pos)))
         (at? tokens (inc pos) :punct ":"))
    (parse-conforms-line tokens pos)

    ;; F67 L4 — observable on extension (parametric or zero-arg).
    (looks-like-observable? tokens pos)
    (parse-observable tokens pos)

    ;; F67 L4 — wildcard self-transition (`* <: msg(args) -> *`) on
    ;; extension. Extensions can't add explicit-phase transitions
    ;; (state-machine structure invariance §3.3.2), so we accept the
    ;; transition here and defer the wildcard-only check to lower.
    (at? tokens pos :glyph "*")
    (parse-transition tokens pos)

    (at? tokens pos :kw "data")
    (fail! tokens pos
           (str "`data:` is not permitted inside `extend class " target
                "` — extensions cannot add state (§3.3.2). Add fields "
                "at the original class definition."))

    ;; F67 L5 — under Proposal D, fields live at class-body top level
    ;; (no `data:` header). Extensions still can't add state; the
    ;; message points at the new surface for consistency.
    (looks-like-field-decl? tokens pos)
    (fail! tokens pos
           (str "field declaration `" (t-value (peek-tok tokens pos))
                ": …` is not permitted inside `extend class " target
                "` — extensions cannot add state (§3.3.2). Add fields "
                "at the original class definition."))

    (at? tokens pos :kw "use")
    (fail! tokens pos
           (str "`use:` is not permitted inside `extend class " target
                "` — extensions cannot add capabilities (§3.3.2). "
                "Declare `use:` at the original class definition."))

    (at? tokens pos :kw "cases")
    (fail! tokens pos
           (str "`cases:` is not permitted inside `extend class " target
                "` — extensions cannot change an enum's case set (§3.3.2)."))

    (at? tokens pos :kw "metadata")
    (fail! tokens pos
           (str "`metadata:` is not permitted inside `extend class " target
                "` — kind/threading/QoS are set at the original class "
                "definition and not modifiable by extensions (§3.3.2)."))

    (at? tokens pos :kw "deprecated")
    (fail! tokens pos
           (str "`deprecated:` is not permitted inside `extend class "
                target "` — deprecation belongs on the original definition."))

    (and (at? tokens pos :ident) (= "state" (t-value (peek-tok tokens pos)))
         (at? tokens (inc pos) :punct ":"))
    (fail! tokens pos
           (str "`state:` section is retired at v1.0 — and extensions "
                "cannot add fields anyway (§3.3.2, §1.11)."))

    :else nil))

(defn- parse-extend-class
  "Parse a top-level `extend class Foo:` form. Header-parens conformances
   and body-form `conforms:` lines stack additively (§3.4.5 rule 5).
   Rejects nested `extend` blocks (top-level form only, per plan Q3.3)."
  [tokens pos]
  (let [[_ p]   (expect tokens pos :kw "extend")
        _       (when-not (at? tokens p :kw "class")
                  (fail! tokens p
                         (str "expected `class` after `extend` — `extend class Foo:` "
                              "is the only extension form. `extend enum` / `extend "
                              "protocol` are not spec surfaces; extend the underlying "
                              "class instead. See spec §3.3.2.")))
        [_ p]   (expect tokens p :kw "class")
        [target p] (expect tokens p :ident)
        [header-conforms p] (if (at? tokens p :punct "(")
                              (parse-header-parens-conformances tokens p)
                              [[] p])
        [_ p]   (expect tokens p :punct ":")
        [_ p]   (expect tokens p :newline)
        [_ p]   (expect tokens p :indent)]
    (loop [body [] p p]
      (cond
        (at? tokens p :dedent)
        (let [conforms-lines (filter #(= :conforms-line (:node %)) body)
              other-sections (remove #(= :conforms-line (:node %)) body)
              body-conforms  (mapv :type conforms-lines)]
          [{:node     :extend-class
            :target   target
            :conforms (into (vec header-conforms) body-conforms)
            :body     (vec other-sections)}
           (inc p)])

        (at? tokens p :newline)
        (recur body (inc p))

        (at? tokens p :kw "extend")
        (fail! tokens p
               (str "nested `extend` blocks are not permitted — extension "
                    "is a top-level form only. See F9 plan Q3.3."))

        :else
        (if-let [[sec p2] (parse-extend-section tokens p target)]
          (recur (conj body sec) (skip-newlines tokens p2))
          (fail! tokens p
                 (str "expected observable (`name[(args)] :< expr`), "
                      "transition (`* <: msg(args) -> * …`), invariants:, "
                      "conforms:, or dedent in `extend class " target "` body")))))))

;; ---------------------------------------------------------------------------
;; Record  — the smallest top-level form
;;
;;   record <Name>:
;;       <field declarations>
;;       <observables — pure readers, parametric or zero-arg>
;;
;;   F67 L4 — `methods:` retires; records only carry fields + observables.
;;   F67 L5 — `data:` header retires; fields live at record-body top level.
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
        [{:node :record :name name
          :sections (fold-top-level-fields sections)}
         (inc p)]

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
                    "immutable; use top-level field declarations. See "
                    "spec §1.11."))

        ;; F67 L4a — observables on records (Proposal G: records
        ;; collapse the same way — pure-data readers become
        ;; observables `name(args) :< <expr>`, utility functions
        ;; move to module scope). Same shape / same parser as atom
        ;; observables (F67 L2).
        (looks-like-observable? tokens p)
        (let [[o p2] (parse-observable tokens p)
              p3     (skip-newlines tokens p2)]
          (recur (conj sections o) p3))

        ;; F67 L5 — top-level field declaration on records (replaces
        ;; `data:` section per Proposal D). Records are pure data holders;
        ;; the collapse removes the redundant grouping section.
        (looks-like-field-decl? tokens p)
        (let [[f p2] (parse-field tokens p)
              p3     (skip-newlines tokens p2)]
          (recur (conj sections f) p3))

        :else
        (fail! tokens p "expected field decl (`name: Type [= default]`), observable (`name[(args)] :< expr`), deprecated:, or dedent in record body")))))

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

      ;; F9 L1 — `class Foo:` / `protocol Foo:` produce a `:class` node
      ;; with `:kind` populated from the sugar keyword (or `metadata: kind:`
      ;; in the body). Lower fail-nyis until F9 L2+ wires semantics.
      (at? tokens p :kw "class")    (parse-class tokens p :regular)
      (at? tokens p :kw "protocol") (parse-class tokens p :protocol)

      ;; F9 L3 — `extend class Foo:` at top level. Extension body is
      ;; strict per §3.3.2: methods:/invariants:/conforms: only.
      ;; Target-name resolution and composition (§3.3.2 rules 1-7)
      ;; land in L4.
      (at? tokens p :kw "extend")   (parse-extend-class tokens p)

      ;; F9 L1 — spec §3.4.1 removed `sealed protocol`. Enumerations
      ;; cover the closed-sum-type case with better ergonomics.
      (and (at? tokens p :ident) (= "sealed" (t-value (peek-tok tokens p)))
           (at? tokens (inc p) :kw "protocol"))
      (fail! tokens p
             (str "`sealed protocol` is retired at v1.0 (spec §3.4.1). "
                  "For closed sum types use `enum` with associated values; "
                  "for open protocols (any type may conform), drop `sealed`."))
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

