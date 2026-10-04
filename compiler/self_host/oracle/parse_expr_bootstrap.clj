;; Oracle for the chapter-26 expression-parse slice.
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

(require 'cxc.parse)
(require 'clojure.string)

(do

(declare ser)

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
    (str "(?" (name (:node n)) ")")))

(def ^:private desugar @#'cxc.parse/desugar-chained-comparisons)

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [src (-> ln
                (clojure.string/replace "\\n" "\n")
                (clojure.string/replace "\\N" "\\n"))]
    (try
      (println (ser (desugar (cxc.parse/parse-expression src))))
      (catch Exception e
        (println (str "error|" (.getMessage e)))))))

)
