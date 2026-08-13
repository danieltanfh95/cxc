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
            [cxc.lex :as lex]))

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
        [inner p] (if (at? tokens p :kw "of")
                    (let [[inner-t p2] (parse-type tokens (inc p))]
                      [{:node :type-of :outer name :inner inner-t} p2])
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

(declare parse-primary parse-args-parens parse-msg-args-braces parse-list-literal)

(defn- parse-postfix
  "Chain of .field, (args), {msg-args} suffixes on a primary expression."
  [tokens pos base]
  (loop [p pos node base]
    (cond
      (at? tokens p :punct ".")
      (let [[fname p2] (expect tokens (inc p) :ident)]
        (recur p2 {:node :dot :target node :field fname}))

      (at? tokens p :punct "(")
      (let [[args p2] (parse-args-parens tokens p)]
        (recur p2 {:node :call :callee node :args args}))

      (at? tokens p :punct "{")
      (let [[margs p2] (parse-msg-args-braces tokens p)]
        (recur p2 {:node :msg-shape :target node :fields margs}))

      :else [node p])))

(defn- parse-unary [tokens pos]
  (cond
    (at? tokens pos :glyph "-")
    (let [[inner p] (parse-unary tokens (inc pos))]
      [{:node :neg :expr inner} p])

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
                "(" (let [[e p] (parse-expr tokens (inc pos))
                          [_ p2] (expect tokens p :punct ")")]
                      [e p2])
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
  "function name(params) -> RetType: <block>"
  [tokens pos]
  (let [[_ p]      (expect tokens pos :kw "function")
        [name p]   (expect tokens p :ident)
        [params p] (parse-param-list-parens tokens p)
        [ret p]    (if (at? tokens p :glyph "->")
                     (parse-type tokens (inc p))
                     [nil p])
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [body p]   (parse-block tokens p)]
    [{:node :function :name name :params params :return-type ret :body body} p]))

;; ---------------------------------------------------------------------------
;; Statements & blocks
;;   A statement (in method bodies etc.) is currently one of:
;;     return <expr>
;;     <expr> (bare expression statement)
;;
;;   A block is delimited by :indent … :dedent, and contains one-or-more
;;   statements terminated by :newline.
;; ---------------------------------------------------------------------------

(defn- parse-statement [tokens pos]
  (cond
    (at? tokens pos :kw "return")
    (let [[e p] (parse-expr tokens (inc pos))]
      [{:node :return :expr e} p])

    :else
    (let [[e p] (parse-expr tokens pos)]
      [{:node :expr-stmt :expr e} p])))

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
        (at? tokens p :kw "function")
        (let [[fdecl p2] (parse-function-decl tokens p)
              p3 (skip-newlines tokens p2)]
          (recur (conj methods fdecl) p3))
        :else (fail! tokens p "expected `function` in methods section")))))

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

        :else
        (fail! tokens p "expected data:, methods:, or dedent in record body")))))

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
      (at? tokens p :eof)      [nil p]
      (at? tokens p :kw "module") (parse-module-decl tokens p)
      (at? tokens p :kw "from")   (parse-from-import tokens p)
      (at? tokens p :kw "record") (parse-record tokens p)
      :else (fail! tokens p "expected top-level form"))))

(defn parse
  "Parse a cxc source string into a sequence of top-level AST nodes."
  [source]
  (let [tokens (lex/tokenize source)]
    (loop [nodes [] pos 0]
      (let [pos (skip-newlines tokens pos)]
        (if (at? tokens pos :eof)
          nodes
          (let [[node pos'] (parse-top tokens pos)]
            (recur (conj nodes node) pos')))))))

