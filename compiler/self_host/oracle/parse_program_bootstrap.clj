;; Oracle for the chapter-30 whole-program parse slice (slice 36,
;; `parse_program_bootstrap.cxc`).
;;
;; Input per line. One whole cxc source file, with the chapter-23
;; corpus conventions (`\\n` is a physical newline, `\\N` the two chars
;; backslash-n inside a literal).
;;
;; Reference. The public `cxc.parse/parse` (lex, parse*, parse-top over
;; every top-level form, the fold passes and reshapes inside
;; parse-def-atom, and the chained-comparison desugar over the result).
;;
;; Output per input line. `(program <node>*)`, or `error|<message>`
;; when lex or parse throws (the ex-info message verbatim).
;;
;; Expression, statement, and item forms print exactly as in the
;; slice-35 oracle `parse_expr_bootstrap.clj`. Chapter 30 adds the
;; top-level nodes.
;;
;;   (from-import <dotted> (name <n> <alias>|nil)+)
;;   (from-foreign-import <lang> "<header>" (name <n> <alias>|nil)+)
;;   (import <dotted> <alias>|nil)
;;   (foreign-import <lang> "<header>")
;;   (agent <name> (params <p>*) <queue>|nil <item>*)  a :def-atom with
;;     inferred kind :agent, <queue> = (queue unbounded) | (queue bounded <n>)
;;   (record <name> <item>*)
;;   (protocol <name> <item>*)
;;   (enum <name> <raw-type>|nil <enum-case>*)
;;   (plugin (fields <field>*) <foreign>*)
;;   (extend <target> (conforms <type>*) <item>*), and a `replace`
;;     prefixed item prints as (replace <item>)
;;
;; Body items after the fold passes. All fields fold into one data
;; section at the first field's position, all `use` lines into one use
;; section at the first use line's position.
;;
;;   (data <field>*)   (uses (use <name> <type>)+)
;;
;; Plugin foreign blocks and function types.
;;
;;   (foreign "<lib>" <entry>*), <entry> = (symbol "<s>" <sig>)
;;     | (selector "<cls>" "<sel>" <sig>) | (class_selector ...)
;;     | (type "<objc>" <alias>)
;;   TF(<args>,<ret>) a function type, at a foreign signature or a field

(require 'cxc.parse)
(require 'cxc.lex)
(require 'clojure.string)

(do

(declare ser ser-stmt ser-type)

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
    :foreign-fn-type (str "TF(" (ser-type (:args t)) "," (ser-type (:return t)) ")")
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

(defn- ser-foreign-entry [e]
  (case (:kind e)
    :type (str "(type " (pr-str (first (:name-strs e))) " " (:alias e) ")")
    (str "(" (name (:kind e))
         (apply str (map #(str " " (pr-str %)) (:name-strs e)))
         " " (ser-type (:signature e)) ")")))

(declare ser-item*)

(defn- ser-item [n]
  (if (:replace? n)
    (str "(replace " (ser-item* n) ")")
    (ser-item* n)))

(defn- ser-item* [n]
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
    :use-section (str "(uses" (apply str (map #(str " (use " (:name %) " " (ser-type (:type %)) ")") (:caps n))) ")")
    :data-section (str "(data" (apply str (map #(str " " (ser-field %)) (:fields n))) ")")
    :foreign-block (str "(foreign " (pr-str (:library n))
                        (apply str (map #(str " " (ser-foreign-entry %)) (:entries n))) ")")
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

(defn- ser-items [xs]
  (apply str (map #(str " " (ser-item %)) xs)))

(defn- ser-name [e]
  (str "(name " (:name e) " " (or (:as e) "nil") ")"))

(defn- ser-top [n]
  (case (:node n)
    :from-import (str "(from-import " (:from n) (apply str (map #(str " " (ser-name %)) (:names n))) ")")
    :from-foreign-import
    (str "(from-foreign-import " (name (:lang n)) " " (pr-str (:header n))
         (apply str (map #(str " " (ser-name %)) (:names n))) ")")
    :import (str "(import " (:from n) " " (or (:as n) "nil") ")")
    :foreign-import (str "(foreign-import " (name (:lang n)) " " (pr-str (:header n)) ")")
    :def-atom
    (str "(agent " (:name n) " " (ser-params (:ctor-params n)) " "
         (if-let [q (:queue-config n)]
           (if (= :bounded (:kind q))
             (str "(queue bounded " (:capacity q) ")")
             "(queue unbounded)")
           "nil")
         (ser-items (:body n)) ")")
    :record (str "(record " (:name n) (ser-items (:sections n)) ")")
    :class (str "(protocol " (:name n) (ser-items (:body n)) ")")
    :enum
    (str "(enum " (:name n) " " (or (:raw-type n) "nil")
         (apply str (map #(str " (enum-case " (:name %) " " (ser-params (:payload %))
                               (when (:raw-value %) (str " " (:raw-value %))) ")")
                         (:case-entries n)))
         ")")
    :def-plugin
    (str "(plugin (fields" (apply str (map #(str " " (ser-field %)) (:fields n))) ")"
         (ser-items (:foreign-blocks n)) ")")
    :extend-class
    (str "(extend " (:target n) " (conforms"
         (apply str (map #(str " " (ser-type %)) (:conforms n))) ")"
         (ser-items (:body n)) ")")
    (str "(?" (name (:node n)) ")")))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [src (-> ln
                (clojure.string/replace "\\n" "\n")
                (clojure.string/replace "\\N" "\\n"))]
    (try
      (println (str "(program" (apply str (map #(str " " (ser-top %)) (cxc.parse/parse src))) ")"))
      (catch Exception e
        (println (str "error|" (.getMessage e)))))))

)
