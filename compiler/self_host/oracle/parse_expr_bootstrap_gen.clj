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
;;
;; Chapter 29 adds the `item` mode for the atom-body item corpus (seed
;; 29), every item kind parse-def-atom's body loop dispatches on, then
;; item token soup.
;;
;;   cd compiler && clj -M self_host/oracle/parse_expr_bootstrap_gen.clj \
;;     29 300 120 item
;;
;; Chapter 30 adds the `prog` mode for the slice-36 whole-program
;; corpus (seed 30), whole programs then program token soup.
;;
;;   cd compiler && clj -M self_host/oracle/parse_expr_bootstrap_gen.clj \
;;     30 300 120 prog

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

;; Chapter 29 item mode. Atom-body items, each a vector of lines at
;; indent 0. Fields, own-line propositions, observables in every form,
;; transitions with every head and body shape, async signatures, use
;; lines, enum cases, on_nil sections, and class functions, plus a few
;; rejection shapes each. Item rows take no random numbers from the
;; other modes, so seeds 27 and 28 print the same rows as before.

(def ^:private phases ["idle" "running" "done"])
(def ^:private msgs ["tick" "go" "ping"])

(defn- gen-field-line []
  (str (pick ["count" "name" "items" "owner"]) ": "
       (case (r 16) 0 "weak " 1 "unowned " "")
       (gen-type 1)
       (if (chance 40) (str " = " (sexpr 1)) "")))

(defn- gen-params []
  (str "("
       (clojure.string/join ", "
         (for [_ (range (r 3))]
           (if (chance 10)
             "self"
             (str (pick ["a" "b" "n"]) ": " (gen-type 1)
                  (if (chance 15) (str " = " (sexpr 0)) "")))))
       ")"))

(defn- gen-prop-lines [i]
  (let [p (ind i)
        pred (if (chance 15) (str (pick ["T" "U"]) ": " (gen-type 1)) (sexpr 2))]
    (case (r 10)
      0 [(str p "|: " pred " as " (pick ["Bad" "Err"]) (gen-args 1))]
      1 [(str p "|: " pred) (str (ind (+ i 4)) "as " (pick ["Bad" "Err"]) (gen-args 1))]
      2 [(str p "|: " pred " " (pick ["x" "as" "then"]))]
      [(str p "|: " pred)])))

(defn- gen-then-lines [i d]
  (if (chance 35)
    [(str (ind i) "then: "
          (if (chance 50)
            (str (pick idents) " => " (sexpr 1))
            (first (gen-stmt 0 0))))]
    (into [(str (ind i) "then:")]
          (mapcat (fn [_]
                    (case (r 5)
                      0 [(str (ind (+ i 4)) (pick ["x" "n" "k"]) " => " (sexpr 1))]
                      1 [(str (ind (+ i 4)) (pick ["x" "n" "k"]) " => " (sexpr 1))]
                      2 (gen-prop-lines (+ i 4))
                      (gen-stmt (+ i 4) d)))
                  (range (inc (r 4)))))))

(defn- gen-msg []
  (case (r 8)
    0 "*"
    1 (str (pick msgs) "()")
    2 (str (pick msgs) "(" (pick ["n" "reason"]) ": " (if (chance 30) "" (gen-type 1))
           (if (chance 30) (str ", c: " (gen-type 0)) "") ")")
    3 (str (pick ["obj" "clock"]) "." (pick msgs))
    4 (str (pick ["obj" "clock"]) ".every(rate)(t: " (pick types-n) ")")
    (pick msgs)))

(defn- gen-transition [d]
  (let [src (case (r 6) 0 "" 1 "* " 2 "self " (str (pick phases) " "))
        recv (if (or (= src "") (chance 25)) ""
               (str (pick ["<: " "<: " "<: " "<| "]) (gen-msg) " "))
        head (str src recv "-> " (case (r 20) 0 "self" 1 "self" 2 "*" (pick phases)))
        head (if (and (seq recv) (chance 15))
               (str src recv "\\n-> " (pick phases))
               head)]
    (case (r 9)
      0 [head]
      1 [(str head " then: " (if (chance 50)
                               (str (pick idents) " => " (sexpr 1))
                               (first (gen-stmt 0 0))))]
      2 [(str head " " (pick ["do: x" "let: k" "then: |: x"]))]
      (into [head]
            (concat
              (when (chance 40) (gen-prop-lines 4))
              (mapcat (fn [_] (gen-then-lines 4 d)) (range (inc (r 2))))
              (when (chance 10) (gen-prop-lines 4)))))))

(defn- gen-observable [d]
  (let [nm (pick ["size" "total" "ready"])
        ps (if (chance 50) (gen-params) "")]
    (case (r 8)
      0 [(str nm ps " :- " (sexpr 2))]
      1 [(str nm ps " :- " (gen-type 1) " = " (sexpr 2))]
      2 (into [(str nm ps " :- " (gen-type 1))]
              (concat (mapcat (fn [_] (gen-prop-lines 4)) (range (inc (r 2))))
                      [(str "= " (sexpr 1))]))
      3 (into [(str nm ps " :- " (gen-type 1))] (gen-body 4 d))
      4 (into [(str nm ps " :- ")] (gen-body 4 d))
      5 [(str nm (gen-params) " :- " (gen-type 1))]
      6 [(str nm ps " :- " (pick types-n) (pick [".m" "(1)" " + 1" ""]))]
      [(str nm ps " :- " (sexpr 1))])))

(defn- gen-item [d]
  (case (r 20)
    0 [(gen-field-line)]
    1 [(gen-field-line)]
    2 (gen-prop-lines 0)
    3 (gen-observable d)
    4 (gen-observable d)
    5 (gen-transition d)
    6 (gen-transition d)
    7 (gen-transition d)
    8 [(str "<: " (pick msgs) (gen-params) (if (chance 15) " -> idle" ""))]
    9 [(str "use " (pick ["console" "fs" "hash" "clock"]) ": " (gen-type 1))]
    10 [(str "case " (pick cases)
             (if (chance 50) (gen-params) "")
             (if (chance 25) (str " = " (pick ["1" "0x10" "x" "\"s\""])) ""))]
    11 (into ["on_nil:"]
             (for [_ (range (inc (r 3)))]
               (str "    " (pick ["size" "name"]) ": " (sexpr 1))))
    (12 13) [(gen-field-line)]
    (14 15) (gen-observable d)
    (16 17) (gen-transition d)
    18 (into [(str "class function " (pick ["make" "from"]) (gen-params)
                   (if (chance 60) (str " -> " (gen-type 1)) "") ":")]
             (gen-body 4 d))
    [(pick ["invariants: x" "replace x :- 1" "function f(): x" "def f(x: Int): x"
            "def slot: Foo" "state:" "accepts ping" "-> go(x: Int):" "5" "then: x"])]))

(def ^:private item-soup-toks
  ["a" "x" "Int" "42" "->" "<:" "<|" "*" "self" "then" "do" "let" ":" ":-" "=>"
   "|:" "as" "=" "(" ")" "," "case" "use" "weak" "on_nil" "." "\\n" "\\n    "])

(defn- gen-item-soup []
  (clojure.string/join " " (for [_ (range (inc (r 9)))] (pick item-soup-toks))))

;; Chapter 30 prog mode. Whole programs for the slice-36 corpus
;; (seed 30). One to three top-level forms, each a `from` or `import`
;; line, a `def` with a random header (ctor params, `queue:` configs,
;; lowercase free-function names) over a body biased toward each
;; reshape (record, agent, enum, protocol, plugin with foreign blocks),
;; an `extend` block, or a retired form. Item lines come from the item
;; mode and are indented under the header. Prog rows take no random
;; numbers from the other modes, so seeds 27 to 29 print the same rows.

(defn- indent-lines [n lines]
  (map #(str (ind n) (clojure.string/replace % "\\n" (str "\\n" (ind n)))) lines))

(def ^:private def-names ["Main" "Point" "E" "P" "plugin" "Widget" "helper"])

(defn- gen-import []
  (case (r 9)
    0 (str "import " (pick ["foo" "a.b" "std.io"]) (if (chance 40) " as q" ""))
    1 (str "import " (pick ["objc" "c"]) " \"" (pick ["x.h" "Foundation/Foundation.h"]) "\"")
    2 (str "from " (pick ["objc" "c"]) " \"x.h\" import " (pick ["A" "A, B" "A as B"]))
    3 (str "import " (pick ["foo bar" "5" "a." "foo as" ""]))
    4 (str "from " (pick ["a" "a.b"]) " import " (pick ["x" "x," "x y" "x as" ""]))
    (str "from " (pick ["a" "a.b" "std"]) " import "
         (clojure.string/join ", "
           (for [_ (range (inc (r 3)))]
             (str (pick ["X" "Y" "z"]) (if (chance 30) (str " as " (pick ["W" "w"])) "")))))))

(defn- gen-header []
  (str "def " (pick def-names)
       (case (r 9)
         0 (gen-params)
         1 (gen-params)
         2 (str "(queue: " (pick ["unbounded" "bounded(4)" "unbounded" "bounded(16)" "bounded(0)" "fifo"])
                (if (chance 40) (str ", " (pick ["a" "n"]) ": " (gen-type 1)) "")
                (if (chance 10) " x)" ")"))
         3 "()"
         "")
       (if (chance 8) (pick [" x:" ": x"]) ":")))

(defn- gen-sig-line []
  (if (chance 50)
    (str (pick ["size" "name"]) (gen-params) " :- " (gen-type 1))
    (str "<: " (pick msgs) (gen-params))))

(defn- gen-case-line []
  (str "case " (pick (conj cases "self_ref"))
       (case (r 6)
         0 (gen-params)
         1 (str "(inner: " (pick ["E" "E!" "E?" "List of E" "Ex"]) ")")
         "")
       (if (chance 30) (str " = " (pick ["1" "300" "70000" "4294967296" "0x10"])) "")))

(defn- gen-foreign-lines []
  (into [(str "foreign \"" (pick ["libc" "m"]) "\":")]
        (for [_ (range (inc (r 3)))]
          (str "    "
               (case (r 6)
                 0 (str "symbol \"f\" -> " (gen-type 1))
                 1 (str "symbol \"g\" -> (" (gen-type 1) ", " (pick ["Int" "Bool" "Double"]) ") -> " (gen-type 1))
                 2 (str "selector \"C\" \"s:\" -> () -> " (pick ["Int" "Ptr"]))
                 3 (str "class_selector \"C\" \"t\" -> (Int32) -> Int32")
                 4 (str "type \"NSFoo\"" (if (chance 50) " as Foo" ""))
                 (pick ["method \"x\" -> Int" "5" "symbol x -> Int" "type \"T\" as"]))))))

(defn- gen-def-body [d]
  (case (r 8)
    0 (mapcat (fn [_] [(gen-case-line)]) (range (inc (r 3))))
    1 (concat (mapcat (fn [_] [(gen-sig-line)]) (range (inc (r 2))))
              (when (chance 30) (gen-observable d)))
    2 (mapcat (fn [_] [(gen-field-line)]) (range (inc (r 3))))
    3 (concat [(gen-field-line)] (when (chance 60) (gen-foreign-lines))
              (when (chance 30) (gen-item d)))
    4 (concat [(str "use " (pick ["console" "fs"]) ": " (pick ["Console" "Fs"]))]
              (mapcat gen-item (repeat (inc (r 2)) 1))
              [(str "use " (pick ["clock" "net"]) ": Clock")])
    (mapcat gen-item (repeat (inc (r 3)) (inc (r 2))))))

(defn- gen-ext-line [d]
  (case (r 14)
    0 [(str "conforms: " (gen-type 1))]
    1 (into [(str "replace " (first (gen-observable d)))] (rest (gen-observable d)))
    2 [(str "replace * <: " (pick msgs) "() -> self")]
    3 (gen-prop-lines 0)
    4 [(pick ["x: Int" "use c: Console" "deprecated: x" "state:" "go <: ping -> go"
              "invariants: x" "replace x: Int" "extend Bar:" "-> go"])]
    5 [(str "* <: " (pick msgs) "() -> self")]
    (gen-observable d)))

(defn- gen-top [d]
  (case (r 12)
    0 [(gen-import)]
    1 [(gen-import)]
    2 (into [(str "extend " (pick ["Foo" "P"])
                  (if (chance 10) (pick ["()" "(Q R)"]) (pick ["" "" "(Q)" "(Q, R of Int)"])) ":")]
            (indent-lines 4 (mapcat gen-ext-line (repeat (inc (r 3)) d))))
    3 (if (chance 50)
        [(pick ["module a.b" "let x = 1" "extern Foo:" "record Foo:" "sealed protocol P:" "foo" "x: Int"])]
        (into [(gen-header)] (indent-lines 4 (gen-def-body d))))
    (into [(gen-header)] (indent-lines 4 (gen-def-body d)))))

(def ^:private prog-soup-toks
  ["def" "X" "x" "Int" "42" "->" "<:" "*" "extend" "from" "import" "as" "case"
   "use" "queue" ":" ":-" "=" "(" ")" "," "." "\"s\"" "foreign" "\\n" "\\n    " "\\n        "])

(defn- gen-prog-soup []
  (clojure.string/join " " (for [_ (range (inc (r 10)))] (pick prog-soup-toks))))

(cond
  (= mode "prog")
  (do
    (doseq [_ (range n-gram)]
      (println (clojure.string/join (pick ["\\n" "\\n\\n"]) (mapcat gen-top (repeat (inc (r 3)) (inc (r 2)))))))
    (doseq [_ (range n-soup)] (println (gen-prog-soup))))

  (= mode "item")
  (do
    (doseq [_ (range n-gram)]
      (println (clojure.string/join "\\n" (mapcat gen-item (repeat (inc (r 3)) (inc (r 2)))))))
    (doseq [_ (range n-soup)] (println (gen-item-soup))))

  (= mode "stmt")
  (do
    (doseq [_ (range n-gram)]
      (println (clojure.string/join "\\n" (mapcat #(gen-stmt 0 %) (repeat (inc (r 2)) (inc (r 3)))))))
    (doseq [_ (range n-soup)] (println (gen-stmt-soup))))
  :else
  (do
    (doseq [_ (range n-gram)] (println (gen-expr (inc (r 4)))))
    (doseq [_ (range n-soup)] (println (gen-soup)))))

)
