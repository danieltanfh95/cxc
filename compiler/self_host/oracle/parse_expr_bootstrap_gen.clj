;; Seeded random row generator for the slice-35 expression-parse corpus.
;;
;; Chapter 27. Writes one corpus row per line to stdout, grammar rows
;; first, then token-soup rows. The rows use the corpus conventions
;; (`\n` is a physical newline). Reproducible, the same seed and counts
;; always print the same rows.
;;
;;   cd compiler && clj -M self_host/oracle/parse_expr_bootstrap_gen.clj \
;;     [seed] [grammar-rows] [soup-rows]
;;
;; Defaults are seed 27, 400 grammar rows, 150 soup rows. The chapter-27
;; corpus keeps a slice of the seed-27 run.
;;
;; Chapter 28 adds a fourth argument, the mode. `expr` (the default)
;; prints exactly the chapter-27 rows for a given seed. `stmt` prints
;; statement-sequence rows for the statement corpus, every statement
;; kind parse-statement accepts or rejects, indented blocks, `or`
;; recovery arms, `<:` dispatch blocks with inline and block arm
;; bodies, `do:` blocks, and `def:` lambda bodies inside call args, then
;; statement token soup. The chapter-28 corpus keeps a slice of the
;; seed-28 run.
;;
;;   cd compiler && clj -M self_host/oracle/parse_expr_bootstrap_gen.clj \
;;     28 300 120 stmt

(do

(def ^:private args *command-line-args*)
(def ^:private seed  (Long/parseLong (or (first args) "27")))
(def ^:private n-gram (Long/parseLong (or (second args) "400")))
(def ^:private n-soup (Long/parseLong (or (nth args 2 nil) "150")))
(def ^:private mode  (or (nth args 3 nil) "expr"))
(def ^:dynamic *one-line* false)
(def ^:private rng (java.util.Random. seed))

(defn- r [n] (.nextInt rng n))
(defn- pick [xs] (nth xs (r (count xs))))
(defn- chance [pct] (< (r 100) pct))

(def ^:private idents ["a" "b" "x" "xs" "foo" "obj" "n" "k"])
(def ^:private types-n ["Int" "String" "Bool" "Foo"])
(def ^:private enums ["Res" "Opt" "E"])
(def ^:private cases ["ok" "err" "none" "some"])
(def ^:private leaves ["42" "1.5" "2e3" "0x1F" "\"s\"" "'c'" "true" "false" "nil" "self"])
(def ^:private ops ["or" "??" "and" "==" "!=" "<=" ">=" "<" ">" "in" "+" "-" "*" "/" "%"])

(declare gen-expr gen-type)

(defn- gen-args [d]
  (let [n (r 3)]
    (str "("
         (clojure.string/join ", "
           (for [_ (range n)]
             (if (chance 40)
               (str (pick ["k" "n" "code"]) ": " (gen-expr (dec d)))
               (gen-expr (dec d)))))
         ")")))

(defn- gen-type [d]
  (let [base (pick types-n)
        t (if (<= d 0)
            base
            (case (r 7)
              0 (str "List of " (gen-type (dec d)))
              1 (str "Map of (" (gen-type (dec d)) ", " (gen-type (dec d)) ")")
              2 (str "(" (gen-type (dec d)) ", " (gen-type (dec d)) ")")
              3 (str "(p: " (gen-type (dec d)) ", q: " (gen-type (dec d)) ")")
              4 (str "(" (gen-type (dec d)) ")")
              base))]
    (case (r 6) 0 (str t "!") 1 (str t "?") t)))

(defn- gen-payload [d]
  (let [n (inc (r 3))]
    (str (pick idents) "("
         (clojure.string/join ", "
           (for [i (range n)]
             (cond
               (zero? i)   (str (pick ["a" "b"]) ": " (pick types-n) (pick ["" "!"]))
               (chance 20) (str (pick ["c" "d"]) ":")
               :else       (str (pick ["c" "d"]) ": " (gen-type 2)))))
         ")")))

(defn- gen-binds []
  (if (chance 40)
    ""
    (str "("
         (clojure.string/join ", "
           (for [_ (range (r 3))]
             (if (chance 50)
               (str "let " (pick ["v" "w"]))
               (str (pick ["code" "msg"]) ": let " (pick ["v" "w"])))))
         ")")))

(defn- gen-variant []
  (str (if (chance 50) "." (str (pick enums) ".")) (pick cases) (gen-binds)))

(defn- gen-chain [d]
  (let [n (inc (r 3))
        sep (case (r 3) 0 " " 1 "\\n" 2 "\\n    ")
        sep (if *one-line* " " sep)]
    (str "||: " (gen-expr (dec d)) " : " (gen-expr (dec d))
         (apply str
           (for [i (range n)]
             (str sep "||: " (if (= i (dec n)) "else" (gen-expr (dec d)))
                  " : " (gen-expr (dec d))))))))

(defn- gen-expr [d]
  (if (<= d 0)
    (if (chance 50) (pick leaves) (pick idents))
    (case (r 22)
      0  (pick leaves)
      1  (pick idents)
      2  (str "-" (gen-expr (dec d)))
      3  (str "not " (gen-expr (dec d)))
      4  (str (gen-expr (dec d)) " " (pick ops) " " (gen-expr (dec d)))
      5  (str (gen-expr (dec d)) " " (pick ops) " " (gen-expr (dec d)))
      6  (str "(" (gen-expr (dec d)) ")")
      7  (str (pick idents) (gen-args d))
      8  (str (pick idents) "." (pick ["m" "len" "at"]) (gen-args d))
      9  (str (pick idents) "[" (gen-expr (dec d)) "]")
      10 (str "[" (gen-expr (dec d)) ", " (gen-expr (dec d)) "]")
      11 (str (pick ["x" "y"]) " => " (gen-expr (dec d)))
      12 (gen-chain d)
      13 (str "raise " (pick ["Err" "Bad"]) (gen-args d))
      14 (str ":> " (pick ["ping" "go"]) (if (chance 50) (gen-args d) ""))
      15 (str (gen-expr (dec d)) " :> " (pick ["ping" "go"]) (if (chance 50) (gen-args d) ""))
      16 (str "|> " (pick idents) "." (pick ["m" "go"]) (gen-args d))
      17 (str "|> " (pick ["f" "g"]) (gen-args d))
      18 (str "|> " (gen-expr (dec d)))
      19 (gen-payload d)
      20 (str (gen-expr (dec d)) " is " (gen-variant))
      21 (str (gen-expr (dec d)) " is " (gen-expr (dec d))))))

(def ^:private soup-toks
  ["a" "b" "x" "Int" "List" "of" "42" "\"s\"" "raise" ":>" "|>" "is" "." "let"
   "(" ")" "," ":" "?" "!" "||:" "else" "=>" "+" "<" "[" "]" "not" "\\n" "\\n    "])

(defn- gen-soup []
  (clojure.string/join " " (for [_ (range (inc (r 7)))] (pick soup-toks))))

(defn- ind [n] (apply str (repeat n " ")))

(declare gen-stmt)

;; A block body at indent `i`, as a vector of lines.
(defn- gen-body [i d]
  (vec (mapcat #(gen-stmt i (dec %2) %1) (range (inc (r 3))) (repeat d))))

;; An inline body or a NEWLINE INDENT block for `if`, `while`, `else`.
(defn- gen-tail [head i d]
  (if (or (<= d 0) (chance 40))
    [(str (ind i) head " " (first (gen-stmt 0 0 0)))]
    (into [(str (ind i) head)] (gen-body (+ i 4) d))))

(defn- sexpr [d] (binding [*one-line* true] (gen-expr d)))

(defn- gen-or-arms [i d]
  (vec (mapcat (fn [_]
                 (into [(str (ind i) "or"
                             (case (r 4)
                               0 ""
                               1 (str " " (pick ["e" "err"]))
                               2 (str " " (pick ["Bad" "Err"]) "()")
                               3 (str " " (pick ["Bad" "Err"]) (gen-binds)))
                             ":")]
                       (gen-body (+ i 4) (dec d))))
               (range (r 3)))))

(defn- gen-pat []
  (case (r 6)
    0 "*"
    1 (str (r 9))
    2 (pick ["true" "false"])
    3 (str "." (pick cases) (gen-binds))
    (str (pick cases) (gen-binds))))

(defn- gen-dispatch [head i d]
  (into [(str (ind i) head ":")]
        (mapcat (fn [_]
                  (let [pats (clojure.string/join ", " (for [_ (range (inc (r 2)))] (gen-pat)))
                        guard (if (chance 25) (str " when: " (sexpr 1)) "")
                        arm (str (ind (+ i 4)) "<: " pats guard ":")]
                    (if (chance 50)
                      [(str arm " " (sexpr (dec d)))]
                      (into [arm] (gen-body (+ i 8) (dec d))))))
                (range (inc (r 3))))))

(defn- gen-stmt
  ([i d] (gen-stmt i d 0))
  ([i d _]
   (let [p (ind i)
         e #(sexpr (max 0 (dec d)))]
     (if (<= d 0)
       [(str p (case (r 6)
                 0 (str "return " (e))
                 1 (str "let " (pick ["v" "w"]) " = " (e))
                 2 (str (pick idents) "(" (e) ")")
                 3 (str ":> " (pick ["ping" "go"]))
                 4 (pick ["break" "continue"])
                 (e)))]
       (case (r 20)
         0  [(str p "return " (e))]
         1  (into [(str p "let " (pick ["v" "w"])
                        (if (chance 40) (str ": " (gen-type 1)) "") " = " (e))]
                  (when (chance 30) (gen-or-arms i d)))
         2  [(str p "alias " (pick ["v" "w"]) " :- " (e))]
         3  [(str p ":> " (pick ["ping" "go"]) (if (chance 50) (gen-args 1) ""))]
         4  (into [(str p "|> " (pick idents) "." (pick ["m" "go"]) (gen-args 1))]
                  (when (chance 40) (gen-or-arms i d)))
         5  (let [t (gen-tail (str "if " (e) ":") i d)]
              (if (chance 50) (into t (gen-tail "else:" i d)) t))
         6  (let [t (gen-tail (str "if let " (pick ["v" "w"])
                                   (if (chance 30) (str ": " (gen-type 0)) "")
                                   " = " (e)
                                   (if (chance 30) (str ", let u = " (e)) "") ":") i d)]
              (if (chance 40) (into t (gen-tail "else:" i d)) t))
         7  (gen-tail (str "while " (e) ":") i d)
         8  [(str p (pick ["break" "continue" "break 1"]))]
         9  (gen-dispatch (e) i d)
         10 (into [(str p "let " (pick ["v" "w"]) " = do:")] (gen-body (+ i 4) (dec d)))
         11 [(str p "let " (pick ["v" "w"]) " = do: " (first (gen-stmt 0 0)))]
         12 (into [(str p (pick idents) "(" (pick ["x" "y"]) " => def:")]
                  (conj (gen-body (+ i 4) (dec d)) (str p ")")))
         13 [(str p (pick ["x" "obj"]) " => " (e))]
         14 [(str p (pick ["for x in xs: f(x)" "switch x: y" "let case = 1" "let 5 = x"]))]
         15 [(str p (pick idents) " :> " (pick ["ping" "go"]) (gen-args 1))]
         [(str p (e))])))))

(def ^:private stmt-soup-toks
  ["a" "x" "f" "42" "return" "let" "if" "else" "while" "break" "do" "def" "or"
   "alias" ":-" "=" "=>" ":" "(" ")" "," "<:" "*" "." ":>" "|>" "when"
   "\\n" "\\n    " "\\n        "])

(defn- gen-stmt-soup []
  (clojure.string/join " " (for [_ (range (inc (r 9)))] (pick stmt-soup-toks))))

(if (= mode "stmt")
  (do
    (doseq [_ (range n-gram)]
      (println (clojure.string/join "\\n" (mapcat #(gen-stmt 0 %) (repeat (inc (r 2)) (inc (r 3)))))))
    (doseq [_ (range n-soup)] (println (gen-stmt-soup))))
  (do
    (doseq [_ (range n-gram)] (println (gen-expr (inc (r 4)))))
    (doseq [_ (range n-soup)] (println (gen-soup)))))

)
