;; Oracle for the chapter-26 expression-parse slice (forms widened in
;; chapter 27).
;;
;; Input per line. One cxc expression source, with the chapter-23
;; corpus conventions (`\\n` is a physical newline, `\\N` the two chars
;; backslash-n inside a literal).
;;
;; Reference. `cxc.parse/parse-expression` (lex, parse-expr, trailing
;; token check) followed by the private `desugar-chained-comparisons`
;; postwalk. `parse-expression` alone returns the raw left-assoc binop
;; tree, `cxc.parse/parse` runs the desugar over every top-level form,
;; so the desugared tree is what an expression in a real program
;; becomes.
;;
;; Output per input line. One S-expression, or `error|<message>` when
;; lex or parse throws (the ex-info message verbatim, which may span
;; several physical lines).
;;
;;   (int <str value>)           (float <str value>)
;;   (string <pr-str value>)     (char <pr-str value>)
;;   (bool true|false)   (nil)   (self)   (ident <name>)
;;   (neg <e>)           (not <e>)
;;   (binop <op> <l> <r>)
;;   (chained-cmp (<op> <op> ...) <e> <e> <e> ...)
;;   (dot <e> <field>)
;;   (call <callee> <arg>*)  with <arg> = (pos <e>) | (named <name> <e>)
;;   (subscript <e> <index>)
;;   (list <e>*)
;;   (lambda (<param>*) <body>)
;;   (multi-arm (arm <guard> <body>)+)  with an `else` guard printed bare
;;
;; Chapter 27 forms.
;;
;;   (raise <TypeName> <arg>*)
;;   (emit async <target> <msg> <arg>*)   `:>`, target `nil` when bare
;;   (emit sync <target> <msg> <arg>*)    `|>`, target `nil` on an ident call
;;   (msg-shape <target> <entry>*)  with <entry> = (field <name> <type>)
;;                                  or (field <name> TR:Any bare)
;;   (variant <Enum> <case> <bind>*)  or (variant . <case> <bind>*)
;;                                  with <bind> = (let <as>) | (let <field> <as>)
;;
;; Types print the way the parse-layer oracle prints them, plus `TQ`
;; for the banned `T?` suffix the parser still builds.
;;
;;   TR:<name>  TN(<t>)  TQ(<t>)  TO:<outer>(<t>)  TT(<t>,<t>,...)
;;   TNT(<f>:<t>,...)
;;
;; Chapter 28 forms. `do:` blocks, `def:` lambda bodies, and `<:`
;; dispatch blocks in expressions, and a statement mode.
;;
;;   (do <stmt>*)                  do-block-expr, also a def: lambda body
;;   (dispatch <scrutinee> <arm>*) with
;;     <arm> = (arm (<pat>+) <guard>|nil <body>)
;;     <pat> = * | (lit int <v>) | (lit bool <v>) | (case <name> <bind>*)
;;             | (case .<name> <bind>*)
;;     <body> = (block <stmt>*) for an indented body, else the expression
;;
;; Statement mode (env CXC_PARSE_MODE=stmt). The row is a statement
;; sequence. `parse-statement` and `skip-newlines` are private, so the
;; oracle derefs them and runs parse-block's own loop over the lexed
;; tokens with eof in place of DEDENT, then the chained-comparison
;; desugar over the statement vector, which is what `cxc.parse/parse`
;; does to every top-level form. Output is `(stmts <stmt>*)`.
;;
;;   (return <e>)   (alias <name> <e>)   (expr-stmt <e>)
;;   (let <name> <type>|nil <e> <or-arm>*)
;;   (if <cond> <block> <block>|nil)
;;   (if-let ((bind <name> <type>|nil <e>)+) <block> <block>|nil)
;;   (while <cond> <block>)   (break)   (continue)
;;   (block <stmt>*)  an indented or one-statement inline body
;;   (emit ...) and (dispatch ...) print bare, as parse-statement keeps
;;   them unwrapped. A statement `|>` emit carries its arms,
;;   (emit sync <target> <msg> <arg>* <or-arm>*)
;;   <or-arm> = (or bare|(bind <name>)|(case <T> <bind>*) <block>)
;;
;; Chapter 29 item mode (env CXC_PARSE_MODE=item). The row is a sequence
;; of atom-body items. parse-def-atom has no single-item entry, so the
;; oracle copies its body loop arm for arm (`parse-items` below), with
;; eof in place of DEDENT and the fixed def name `Item`, and calls the
;; same private parsers through their vars. No fold or reshape runs.
;; Output is `(items <item>*)`.
;;
;;   (field <name> <type>|nil <default>|nil [weak|unowned] [bare])
;;   (prop <pred> [(as <T> (named <f> <e>)*)])
;;     <pred> = <e> | (type-bound <name> <type>)
;;   (obs <name> (params <p>*) <rtype>|nil <e> [(props <prop>*)])
;;   (obs-sig <name> (params <p>*) <rtype>)  <p> = (param self) | field
;;   (transition <line> <src> <recv>|nil <target> <clause>*)
;;     <src>  = implicit | self | * | (phase <name>)
;;     <recv> = (recv <glyph> <msg>)
;;     <msg>  = * | (typed <name> <field>*) | (src <e> (field <n> <t> [bare])*)
;;     <clause> = (prop ...) | (then (ups (set <f> <e>)*) (stmts <s>*))
;;   (recv-sig <name> (params ...))   (use <name> <type>)
;;   (enum-case <name> (params ...) [<raw>])   (on-nil (<method> <e>)*)
;;   (class-fn <name> (params ...) <rtype>|nil (block <stmt>*))

(require 'cxc.parse)
(require 'cxc.lex)
(require 'cxc.section)
(require 'clojure.string)

(do

(declare ser ser-stmt)

(defn- ser-type [t]
  (case (:node t)
    :type-ref      (str "TR:" (:name t))
    :type-nonnil   (str "TN(" (ser-type (:inner t)) ")")
    :type-nullable (str "TQ(" (ser-type (:inner t)) ")")
    :type-of       (str "TO:" (:outer t) "(" (ser-type (:inner t)) ")")
    :type-tuple    (str "TT(" (clojure.string/join "," (map ser-type (:types t))) ")")
    :type-named-tuple
    (str "TNT("
         (clojure.string/join "," (map #(str (:name %) ":" (ser-type (:type %))) (:fields t)))
         ")")
    (str "T?:" (:node t))))

(defn- ser-bind [b]
  (if (:field b)
    (str "(let " (:field b) " " (:as b) ")")
    (str "(let " (:as b) ")")))

(defn- ser-block [b]
  (str "(block" (apply str (map #(str " " (ser-stmt %)) (:stmts b))) ")"))

(defn- ser-pat [p]
  (case (:kind p)
    :wildcard "*"
    :literal  (str "(lit " (name (:lit-type p)) " " (:value p) ")")
    :case     (str "(case " (when (:leading-dot? p) ".") (:name p)
                   (apply str (map #(str " " (ser-bind %)) (:bindings p))) ")")
    (str "(?pat " (:kind p) ")")))

(defn- ser-or-pat [p]
  (cond
    (nil? p)              "bare"
    (= :bind (:kind p))   (str "(bind " (:name p) ")")
    :else                 (str "(case " (:name p)
                               (apply str (map #(str " " (ser-bind %)) (:bindings p))) ")")))

(defn- ser-arms [arms]
  (apply str (map #(str " (or " (ser-or-pat (:pattern %)) " "
                        (ser-block {:stmts (:body %)}) ")")
                  arms)))

(defn- ser-arg [a]
  (if (= :named (:kind a))
    (str "(named " (:name a) " " (ser (:value a)) ")")
    (str "(pos " (ser (:value a)) ")")))

(defn- ser [n]
  (case (:node n)
    :lit-int     (str "(int " (:value n) ")")
    :lit-float   (str "(float " (:value n) ")")
    :lit-string  (str "(string " (pr-str (:value n)) ")")
    :lit-char    (str "(char " (pr-str (:value n)) ")")
    :lit-bool    (str "(bool " (:value n) ")")
    :lit-nil     "(nil)"
    :self        "(self)"
    :ident       (str "(ident " (:name n) ")")
    :neg         (str "(neg " (ser (:expr n)) ")")
    :not         (str "(not " (ser (:expr n)) ")")
    :binop       (str "(binop " (:op n) " " (ser (:left n)) " " (ser (:right n)) ")")
    :chained-cmp (str "(chained-cmp (" (clojure.string/join " " (:ops n)) ") "
                      (clojure.string/join " " (map ser (:operands n))) ")")
    :dot         (str "(dot " (ser (:target n)) " " (:field n) ")")
    :call        (str "(call " (ser (:callee n))
                      (apply str (map #(str " " (ser-arg %)) (:args n))) ")")
    :subscript   (str "(subscript " (ser (:target n)) " " (ser (:index n)) ")")
    :list        (str "(list" (apply str (map #(str " " (ser %)) (:items n))) ")")
    :lambda      (str "(lambda (" (clojure.string/join " " (map :name (:params n))) ") "
                      (ser (:body n)) ")")
    :multi-arm-dispatch
    (str "(multi-arm"
         (apply str (map #(str " (arm " (ser (:guard %)) " " (ser (:body %)) ")") (:arms n)))
         ")")
    :else-marker "else"
    :raise       (str "(raise " (:type-name n)
                      (apply str (map #(str " " (ser-arg %)) (:args n))) ")")
    :emit        (str "(emit " (if (:async? n) "async" "sync") " "
                      (if (:target n) (ser (:target n)) "nil") " " (:msg n)
                      (apply str (map #(str " " (ser-arg %)) (:args n)))
                      (ser-arms (:arms n)) ")")
    :do-block-expr (str "(do" (apply str (map #(str " " (ser-stmt %)) (:stmts n))) ")")
    :dispatch    (str "(dispatch " (ser (:scrutinee n))
                      (apply str
                        (map #(str " (arm (" (clojure.string/join " " (map ser-pat (:patterns %))) ") "
                                   (if (:guard %) (ser (:guard %)) "nil") " "
                                   (if (= :block (:node (:body %)))
                                     (ser-block (:body %))
                                     (ser (:body %))) ")")
                             (:arms n))) ")")
    :msg-shape   (str "(msg-shape " (ser (:target n))
                      (apply str (map #(str " (field " (:name %) " " (ser-type (:type %))
                                            (when (:bareword? %) " bare") ")")
                                      (:fields n))) ")")
    :variant-pattern
    (str "(variant " (if (:leading-dot? n) "." (:enum-name n)) " " (:case-name n)
         (apply str (map #(str " " (ser-bind %)) (:bindings n))) ")")
    (str "(?" (name (:node n)) ")")))

(declare ser-prop)

(defn- ser-stmt [n]
  (case (:node n)
    :proposition   (ser-prop n)
    :return        (str "(return " (ser (:expr n)) ")")
    :alias-stmt    (str "(alias " (:name n) " " (ser (:expr n)) ")")
    :expr-stmt     (str "(expr-stmt " (ser (:expr n)) ")")
    :let-stmt      (str "(let " (:name n) " " (if (:type n) (ser-type (:type n)) "nil") " "
                        (ser (:expr n)) (ser-arms (:arms n)) ")")
    :if-stmt       (str "(if " (ser (:cond n)) " " (ser-block (:then n)) " "
                        (if (:else n) (ser-block (:else n)) "nil") ")")
    :if-let        (str "(if-let ("
                        (clojure.string/join " "
                          (map #(str "(bind " (:name %) " " (if (:type %) (ser-type (:type %)) "nil")
                                     " " (ser (:expr %)) ")")
                               (:bindings n)))
                        ") " (ser-block (:then n)) " "
                        (if (:else n) (ser-block (:else n)) "nil") ")")
    :while-stmt    (str "(while " (ser (:cond n)) " " (ser-block (:body n)) ")")
    :break-stmt    "(break)"
    :continue-stmt "(continue)"
    (ser n)))

;; Chapter 29 item forms.

(defn- ser-field [f]
  (str "(field " (:name f) " " (if (:type f) (ser-type (:type f)) "nil") " "
       (if (:default f) (ser (:default f)) "nil")
       (when (:modifier f) (str " " (name (:modifier f))))
       (when (:bareword? f) " bare") ")"))

(defn- ser-param [p]
  (if (= :param (:node p)) "(param self)" (ser-field p)))

(defn- ser-params [ps]
  (str "(params" (apply str (map #(str " " (ser-param %)) ps)) ")"))

(defn- ser-prop [p]
  (let [pr (:predicate p)]
    (str "(prop "
         (if (= :type-bound (:node pr))
           (str "(type-bound " (:name (:parameter pr)) " " (ser-type (:bound pr)) ")")
           (ser pr))
         (when (:as-error-type p)
           (str " (as " (ser-type (:as-error-type p))
                (apply str (map (fn [[k v]] (str " (named " k " " (ser v) ")"))
                                (:as-error-args p)))
                ")"))
         ")")))

(defn- ser-then [c]
  (str "(then (ups"
       (apply str (map #(str " (set " (:field %) " " (ser (:expr %)) ")") (:updates c)))
       ") (stmts"
       (apply str (map #(str " " (ser-stmt %)) (:stmts c)))
       "))"))

(defn- ser-source [s]
  (case (:kind s)
    :implicit "implicit"
    :self     "self"
    :wildcard "*"
    :phase    (str "(phase " (:name s) ")")))

(defn- ser-msg [m]
  (cond
    (= :wildcard-msg (:node (:source m))) "*"
    (some? (:params m))
    (str "(typed " (:name (:source m))
         (apply str (map #(str " " (ser-field %)) (:params m))) ")")
    :else
    (str "(src " (ser (:source m))
         (apply str (map #(str " (field " (:name %) " " (ser-type (:type %))
                               (when (:bareword? %) " bare") ")")
                         (:payload m))) ")")))

(defn- ser-item [n]
  (case (:node n)
    :proposition (ser-prop n)
    :then-clause (ser-then n)
    :transition  (str "(transition " (:src-line n) " " (ser-source (:source n)) " "
                      (if-let [r (:receive n)]
                        (str "(recv " (:glyph r) " " (ser-msg (:msg r)) ")")
                        "nil")
                      " " (:target n)
                      (apply str (map #(str " " (ser-item %)) (:body n))) ")")
    :observable  (str "(obs " (:name n) " " (ser-params (:params n)) " "
                      (if (:return-type n) (ser-type (:return-type n)) "nil") " "
                      (ser (:expr n))
                      (when (seq (:propositions n))
                        (str " (props" (apply str (map #(str " " (ser-prop %)) (:propositions n))) ")"))
                      ")")
    :observation-signature
    (str "(obs-sig " (:name n) " " (ser-params (:params n)) " " (ser-type (:return-type n)) ")")
    :receive-signature (str "(recv-sig " (:name n) " " (ser-params (:params n)) ")")
    :use-section (apply str (map #(str "(use " (:name %) " " (ser-type (:type %)) ")") (:caps n)))
    :field       (ser-field n)
    :enum-case   (str "(enum-case " (:case-name n) " " (ser-params (:payload n))
                      (when (:raw-value n) (str " " (:raw-value n))) ")")
    :on-nil-section
    (str "(on-nil" (apply str (map #(str " (" (:method %) " " (ser (:value %)) ")") (:entries n))) ")")
    :class-function
    (str "(class-fn " (:name n) " " (ser-params (:params n)) " "
         (if (:return-type n) (ser-type (:return-type n)) "nil") " "
         (ser-block (:body n)) ")")
    (str "(?" (name (:node n)) ")")))

(def ^:private desugar @#'cxc.parse/desugar-chained-comparisons)
(def ^:private parse-statement @#'cxc.parse/parse-statement)
(def ^:private skip-newlines @#'cxc.parse/skip-newlines)
(def ^:private stmt-mode? (= "stmt" (System/getenv "CXC_PARSE_MODE")))
(def ^:private item-mode? (= "item" (System/getenv "CXC_PARSE_MODE")))

(def ^:private at? @#'cxc.parse/at?)
(def ^:private peek-tok @#'cxc.parse/peek-tok)
(def ^:private fail! @#'cxc.parse/fail!)
(def ^:private parse-proposition @#'cxc.parse/parse-proposition)
(def ^:private parse-receive-signature @#'cxc.parse/parse-receive-signature)
(def ^:private parse-use-line @#'cxc.parse/parse-use-line)
(def ^:private parse-class-function-decl @#'cxc.parse/parse-class-function-decl)
(def ^:private at-def-method? @#'cxc.parse/at-def-method?)
(def ^:private parse-param-list-parens @#'cxc.parse/parse-param-list-parens)
(def ^:private looks-like-observable? @#'cxc.parse/looks-like-observable?)
(def ^:private parse-observable @#'cxc.parse/parse-observable)
(def ^:private looks-like-handler-decl? @#'cxc.parse/looks-like-handler-decl?)
(def ^:private parse-handler-decl @#'cxc.parse/parse-handler-decl)
(def ^:private parse-on-nil-section @#'cxc.parse/parse-on-nil-section)
(def ^:private looks-like-field-decl? @#'cxc.parse/looks-like-field-decl?)
(def ^:private parse-field @#'cxc.parse/parse-field)
(def ^:private looks-like-transition? @#'cxc.parse/looks-like-transition?)
(def ^:private parse-transition @#'cxc.parse/parse-transition)
(def ^:private parse-foreign-block @#'cxc.parse/parse-foreign-block)
(def ^:private expect @#'cxc.parse/expect)

;; parse-block's loop at top level. eof ends it where DEDENT would.
(defn- parse-statements [src]
  (let [tokens (cxc.lex/tokenize src)]
    (loop [stmts [] p 0]
      (let [p (skip-newlines tokens p)]
        (if (= :eof (first (nth tokens p nil)))
          stmts
          (let [[s p2] (parse-statement tokens p)]
            (recur (conj stmts s) p2)))))))

;; Chapter 29 item mode. parse-def-atom's body loop, with eof in place
;; of DEDENT and the fixed def name `Item` for the rejections that
;; quote it. The cond arms are copied from parse-def-atom in order and
;; call the same private parsers. The use arm goes through the section
;; registry exactly as parse-def-atom does. No fold or reshape runs, so
;; the items print in source order as the loop built them.
(def ^:private item-def-name "Item")

(defn- parse-items [src]
  (let [tokens (cxc.lex/tokenize src)
        name   item-def-name
        t-value (fn [t] (nth t 1))
        t-kind  (fn [t] (nth t 0))]
    (loop [body [] p 0]
      (cond
        (at? tokens p :eof)
        body

        (at? tokens p :newline)
        (recur body (inc p))

        (at? tokens p :annotation-prefix)
        (fail! tokens p "annotation grammar (`@name(...)`) is retired at v1.0")

        (at? tokens p :glyph "|:")
        (let [[prop p2] (parse-proposition tokens p)]
          (recur (conj body prop) (skip-newlines tokens p2)))

        (at? tokens p :kw "invariants")
        (fail! tokens p
               (str "`invariants:` section header is retired at v1.0 "
                    "(four-position `|:` unification, ratified 2026-08-25). "
                    "Migrate each invariant to a def-body `|:` proposition "
                    "on its own line at def-body top level:\n\n"
                    "  def " name ":\n"
                    "      <field decls>\n"
                    "      |: <proposition>          \u2190 was `invariants:` entry\n"
                    "      |: <another proposition>\n\n"
                    "See spec \u00a71.11 retirement mapping table and \u00a73.4.2."))

        (at? tokens p :glyph "<:")
        (let [[sig p2] (parse-receive-signature tokens p)]
          (recur (conj body sig) (skip-newlines tokens p2)))

        (and (at? tokens p :ident)
             (= "foreign" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :string)
             (at? tokens (+ p 2) :punct ":"))
        (let [[blk p2] (parse-foreign-block tokens p)]
          (recur (conj body blk) (skip-newlines tokens p2)))

        (and (at? tokens p :kw)
             (cxc.section/behavioral-parser (t-value (peek-tok tokens p))))
        (let [parser (cxc.section/behavioral-parser (t-value (peek-tok tokens p)))
              [s p2] (parser tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "use")
        (let [[s p2] (parse-use-line tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "replace")
        (fail! tokens p
               (str "`replace` modifier inside `def " name "` body is only "
                    "meaningful in an `extend` context \u2014 a class's "
                    "own declarations can't shadow themselves (\u00a73.3.2 rule 4). "
                    "Drop the `replace` prefix, or move the declaration "
                    "into an `extend` block if the intent was to "
                    "shadow another declaration."))

        (and (at? tokens p :ident)
             (= "class" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :kw "function"))
        (let [[cf p2] (parse-class-function-decl tokens p)]
          (recur (conj body cf) (skip-newlines tokens p2)))

        (at? tokens p :kw "function")
        (fail! tokens p
               (str "F67 L4: `function` keyword inside `def " name "` body is "
                    "retired (Proposal G class-body collapse). Migrate the "
                    "method by category:\n\n"
                    "  - reader \u2192 observable: `name(args) :- <expr>`\n"
                    "  - void   \u2192 transition: `* <: name(args) -> * then: <body>`\n"
                    "  - writer \u2192 transition: `* <: name(args) -> * then: <update>`\n\n"
                    "Utility functions unrelated to `self` belong at module "
                    "scope (`def name(args) -> T: body`). See spec \u00a71.11."))

        (at-def-method? tokens p)
        (fail! tokens p
               (str "F67 L4: `def " (t-value (peek-tok tokens (inc p)))
                    "(args)` method form inside `def " name "` body is "
                    "retired (Proposal G class-body collapse). Migrate the "
                    "method by category:\n\n"
                    "  - reader \u2192 observable: `" (t-value (peek-tok tokens (inc p)))
                    "(args) :- <expr>`\n"
                    "  - void   \u2192 transition: `* <: " (t-value (peek-tok tokens (inc p)))
                    "(args) -> * then: <body>`\n"
                    "  - writer \u2192 transition: `* <: " (t-value (peek-tok tokens (inc p)))
                    "(args) -> * then: <update>`\n\n"
                    "Utility functions unrelated to `self` belong at module "
                    "scope. See spec \u00a71.11."))

        (and (at? tokens p :kw "def")
             (at? tokens (inc p) :ident)
             (at? tokens (+ p 2) :punct ":"))
        (let [nm  (t-value (peek-tok tokens (inc p)))
              ty-tok (peek-tok tokens (+ p 3))
              ty-nm  (if (and ty-tok (= :ident (t-kind ty-tok)))
                       (t-value ty-tok)
                       "TypeName")]
          (fail! tokens p
                 (str "F34 L3: `def " nm ": " ty-nm "` nested-atom slot form "
                      "inside `def " name "` body is retired. Migrate to the "
                      "spec-canonical `data:` field shape (\u00a71.11):\n\n"
                      "  " nm ": " ty-nm " = " ty-nm "()\n\n"
                      "If the original carried ctor args (`def " nm ": " ty-nm
                      "(args...)`), preserve them on the RHS ctor call:\n\n"
                      "  " nm ": " ty-nm " = " ty-nm "(args...)")))

        (and (at? tokens p :ident)
             (= "state" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct ":"))
        (fail! tokens p
               (str "`state:` section header is retired at v1.0 "
                    "(class-body-collapse F67 L5). Migrate each field to "
                    "def-body top level with no wrapping header:\n\n"
                    "  def " name ":\n"
                    "      <field>: <Type> [= <default>]     \u2190 was inside `state:`\n"
                    "      <another field>: <Type>\n\n"
                    "Mutation still happens only through transitions. "
                    "See spec \u00a71.11."))

        (at? tokens p :kw "accepts")
        (fail! tokens p
               (str "`accepts:` and `accepts name(...)` are retired at "
                    "v1.0. Declare protocol members directly in `def " name
                    "` using `<: name(args)` for async receives or "
                    "`name(args) :- ReturnType` for sync observations."))

        (at? tokens p :kw "case")
        (let [[_ p2]     (expect tokens p :kw "case")
              [nm p3]    (expect tokens p2 :ident)
              [payload p4] (if (at? tokens p3 :punct "(")
                             (parse-param-list-parens tokens p3)
                             [[] p3])
              _          (doseq [pp payload]
                           (when (:default pp)
                             (fail! tokens p3
                                    (str "F16 L1: default on enum payload field `"
                                         (:name pp) "` in case `" nm
                                         "` \u2014 payload fields don't carry defaults "
                                         "(the case *value* is the payload; a default "
                                         "would confuse construction semantics). "
                                         "Drop the `= <default>`."))))
              [raw-val p5] (if (at? tokens p4 :glyph "=")
                             (let [after-eq (inc p4)]
                               (when (seq payload)
                                 (fail! tokens after-eq
                                        (str "F16 L2: case `" nm "` has both a "
                                             "payload `(...)` and a raw value `= ...` "
                                             "\u2014 these are mutually exclusive shapes "
                                             "per \u00a73.3. Payload enums carry associated "
                                             "values; raw-value enums attach fixed "
                                             "integer values to bare cases. Pick one.")))
                               (when-not (at? tokens after-eq :int)
                                 (fail! tokens after-eq
                                        (str "F16 L2: raw value on case `" nm
                                             "` must be an integer literal (v1.0 "
                                             "scope \u2014 string/float raw values are "
                                             "deferred to a follow-on landing).")))
                               [(t-value (peek-tok tokens after-eq)) (inc after-eq)])
                             [nil p4])]
          (recur (conj body (cond-> {:node :enum-case :case-name nm :payload payload}
                              raw-val (assoc :raw-value raw-val)))
                 (skip-newlines tokens p5)))

        (looks-like-observable? tokens p)
        (let [[o p2] (parse-observable tokens p)]
          (recur (conj body o) (skip-newlines tokens p2)))

        (looks-like-handler-decl? tokens p)
        (let [[h p2] (parse-handler-decl tokens p)]
          (recur (conj body h) (skip-newlines tokens p2)))

        (and (at? tokens p :ident)
             (= "on_nil" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct ":"))
        (let [[s p2] (parse-on-nil-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (looks-like-field-decl? tokens p)
        (let [[f p2] (parse-field tokens p)]
          (recur (conj body f) (skip-newlines tokens p2)))

        (looks-like-transition? tokens p)
        (let [[t p2] (parse-transition tokens p)]
          (recur (conj body t) (skip-newlines tokens p2)))

        :else
        (fail! tokens p "expected direct use (`use name: Type`), field decl (`name: Type [= default]`), own-line `|:` proposition, on_nil:, observable/signature (`name[(args)] :- expr-or-type`), async signature (`<: name(args)`), or transition inside def-atom body")))))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [src (-> ln
                (clojure.string/replace "\\n" "\n")
                (clojure.string/replace "\\N" "\\n"))]
    (try
      (println
        (cond
          item-mode?
          (str "(items" (apply str (map #(str " " (ser-item %)) (desugar (parse-items src)))) ")")
          stmt-mode?
          (str "(stmts" (apply str (map #(str " " (ser-stmt %)) (desugar (parse-statements src)))) ")")
          :else
          (ser (desugar (cxc.parse/parse-expression src)))))
      (catch Exception e
        (println (str "error|" (.getMessage e)))))))

)
