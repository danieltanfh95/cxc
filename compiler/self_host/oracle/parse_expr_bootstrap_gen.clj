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
;; corpus keeps a slice of the seed-27 run. Rows that reach a form the
;; port leaves out of scope (`do:`, `def:` lambda bodies, `<:` dispatch
;; blocks) are dropped by the caller, the generator never builds them
;; on purpose but soup can.

(do

(def ^:private args *command-line-args*)
(def ^:private seed  (Long/parseLong (or (first args) "27")))
(def ^:private n-gram (Long/parseLong (or (second args) "400")))
(def ^:private n-soup (Long/parseLong (or (nth args 2 nil) "150")))
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
        sep (case (r 3) 0 " " 1 "\\n" 2 "\\n    ")]
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

(doseq [_ (range n-gram)] (println (gen-expr (inc (r 4)))))
(doseq [_ (range n-soup)] (println (gen-soup)))

)
