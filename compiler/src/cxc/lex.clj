(ns cxc.lex
  "Lexer for cxc source. Produces a flat token vector with layout markers.

   Layout: cxc is indentation-sensitive. Lines ending in `:` open a block
   whose extent is determined by subsequent indentation. This lexer emits
   :indent / :dedent / :newline tokens so downstream Instaparse grammar
   can stay context-free.

   Token shape: [:kind value line col]
     :kind    keyword — :ident, :int, :float, :string, :char, :kw,
              :glyph, :punct, :indent, :dedent, :newline, :eof
     :value   token payload (string / number / keyword)
     :line    1-based line number
     :col     1-based column

   Glyphs recognized (longest-match): <: <| |> :> :< -> => == != <= >=
   Single-char operators emit as :glyph with the char as value.
   Punctuation: ( ) [ ] { } , : . ;

   Reserved words emit as :kw. Everything else identifier-shaped is :ident."
  (:require [clojure.string :as str]))

(def reserved-words
  #{"module" "from" "import" "as" "expose"
    "def" "record" "class" "enum" "protocol"
    "data" "use" "methods" "invariants" "contracts" "constructor" "metadata"
    "deprecated"
    "extern"
    "then" "do" "when" "let" "cases"
    "self" "nil" "true" "false"
    "and" "or" "not" "is" "in" "of"
    "if" "else" "return" "match" "case" "switch"
    "function"
    "for"})

(def multi-char-glyphs
  ["<:" "<|" "|>" ":>" ":<" "->" "=>" "==" "!=" "<=" ">=" "//" "**"])

(def single-char-glyphs
  #{\+ \- \* \/ \% \= \< \> \! \& \|})

(def punctuation
  #{\( \) \[ \] \{ \} \, \: \. \; \? \!})

(defn- classify-word [w]
  (if (reserved-words w) :kw :ident))

(defn- scan-string [s i line col]
  ;; s starts with " at index i; scan to matching closing "
  (let [n (count s)]
    (loop [j (inc i) buf (StringBuilder.)]
      (cond
        (>= j n)
        (throw (ex-info "unterminated string literal" {:line line :col col}))

        (= \" (.charAt s j))
        [(str buf) (inc j)]

        (= \\ (.charAt s j))
        (if (>= (inc j) n)
          (throw (ex-info "trailing backslash in string" {:line line :col col}))
          (let [esc (.charAt s (inc j))
                ch  (case esc
                      \n \newline
                      \t \tab
                      \r \return
                      \\ \\
                      \" \"
                      esc)]
            (.append buf ch)
            (recur (+ j 2) buf)))

        :else
        (do (.append buf (.charAt s j))
            (recur (inc j) buf))))))

(defn- scan-number
  "Scan digits, optional single dot for float (no exponent for v1.0-lite),
   and optional trailing unit-suffix identifier (`1000ms`, `500ms`, `16ms`,
   `2.5s`). Returns [numeric-substring unit-or-nil end-index kind]."
  [s i]
  (let [n (count s)]
    (loop [j i seen-dot? false]
      (cond
        (>= j n)
        [(subs s i j) nil j (if seen-dot? :float :int)]

        (Character/isDigit (.charAt s j))
        (recur (inc j) seen-dot?)

        (and (= \. (.charAt s j))
             (not seen-dot?)
             (< (inc j) n)
             (Character/isDigit (.charAt s (inc j))))
        (recur (inc j) true)

        (Character/isLetter (.charAt s j))
        ;; unit suffix: consume identifier chars up to non-word
        (let [unit-start j]
          (loop [k j]
            (if (and (< k n)
                     (let [c (.charAt s k)]
                       (or (Character/isLetterOrDigit c) (= c \_))))
              (recur (inc k))
              [(subs s i unit-start)
               (subs s unit-start k)
               k
               (if seen-dot? :float :int)])))

        :else
        [(subs s i j) nil j (if seen-dot? :float :int)]))))

(defn- scan-word [s i]
  (let [n (count s)]
    (loop [j i]
      (if (and (< j n)
               (let [c (.charAt s j)]
                 (or (Character/isLetterOrDigit c) (= c \_))))
        (recur (inc j))
        [(subs s i j) j]))))

(defn- match-multi-glyph [s i]
  (some (fn [g]
          (when (and (<= (+ i (count g)) (count s))
                     (= g (subs s i (+ i (count g)))))
            g))
        multi-char-glyphs))

(defn- tokenize-line [line line-no]
  "Tokenize a single line's content (indent already stripped).
   Returns a vector of tokens (no :newline / :indent / :dedent added here)."
  (let [n (count line)]
    (loop [i 0 out (transient [])]
      (cond
        (>= i n) (persistent! out)

        (Character/isWhitespace (.charAt line i))
        (recur (inc i) out)

        (= \# (.charAt line i))
        (persistent! out)          ; rest of line is comment

        (= \" (.charAt line i))
        (let [[s j] (scan-string line i line-no (inc i))]
          (recur j (conj! out [:string s line-no (inc i)])))

        (Character/isDigit (.charAt line i))
        (let [[num unit j kind] (scan-number line i)
              value (if (= kind :int)
                      (Long/parseLong num)
                      (Double/parseDouble num))
              tok-kind (cond
                         (and unit (= kind :int))   :int-unit
                         (and unit (= kind :float)) :float-unit
                         :else                       kind)
              tok-val  (if unit {:value value :unit unit} value)]
          (recur j (conj! out [tok-kind tok-val line-no (inc i)])))

        (or (Character/isLetter (.charAt line i)) (= \_ (.charAt line i)))
        (let [[w j] (scan-word line i)]
          (recur j (conj! out [(classify-word w) w line-no (inc i)])))

        :else
        (if-let [g (match-multi-glyph line i)]
          (recur (+ i (count g)) (conj! out [:glyph g line-no (inc i)]))
          (let [c (.charAt line i)]
            (cond
              (punctuation c)
              (recur (inc i) (conj! out [:punct (str c) line-no (inc i)]))

              (single-char-glyphs c)
              (recur (inc i) (conj! out [:glyph (str c) line-no (inc i)]))

              :else
              (throw (ex-info (str "unexpected character: " (pr-str c))
                              {:line line-no :col (inc i) :char c})))))))))

(defn- leading-indent [line]
  "Return [indent-count content] where indent-count is spaces and content
   is the rest of the line. Tabs count as 8 spaces (matches ObjC/Cocoa
   convention). Blank / comment-only lines return [nil nil] and are skipped."
  (let [n (count line)]
    (loop [i 0 indent 0]
      (cond
        (>= i n) [nil nil]
        (= \space (.charAt line i)) (recur (inc i) (inc indent))
        (= \tab   (.charAt line i)) (recur (inc i) (+ indent 8))
        (= \# (.charAt line i)) [nil nil]
        :else [indent (subs line i)]))))

(defn- bracket-delta
  "Net (opens - closes) for parens/brackets/braces in the token vector."
  [toks]
  (reduce (fn [d t]
            (cond
              (and (= :punct (nth t 0)) (#{"(" "[" "{"} (nth t 1))) (inc d)
              (and (= :punct (nth t 0)) (#{")" "]" "}"} (nth t 1))) (dec d)
              :else d))
          0
          toks))

(defn tokenize
  "Tokenize cxc source into a flat vector of tokens with layout markers.

   Layout preprocessor tracks bracket-depth across lines: while any
   parens/brackets/braces are open, subsequent lines are treated as
   physical-line continuations of the logical line — no INDENT/DEDENT/
   NEWLINE emitted, tokens simply concatenated. This lets multi-line
   argument lists, list literals, and message payloads work naturally."
  [source]
  (if (str/blank? source)
    [[:eof nil 1 1]]
    (let [lines (str/split-lines source)
          n     (count lines)]
    (loop [i 0
           indent-stack [0]
           bracket-depth 0                     ; unmatched open brackets from prior lines
           out (transient [])]
      (if (>= i n)
        (let [out (reduce (fn [acc _] (conj! acc [:dedent nil (inc n) 1]))
                          out
                          (rest indent-stack))]
          (persistent! (conj! out [:eof nil (inc n) 1])))
        (let [line (nth lines i)
              [indent content] (leading-indent line)]
          (cond
            ;; blank / comment-only line — skip entirely
            (nil? indent)
            (recur (inc i) indent-stack bracket-depth out)

            ;; still inside brackets from prior line(s) — continuation:
            ;; append this line's tokens without layout markers or newline
            (pos? bracket-depth)
            (let [line-toks (tokenize-line content (inc i))
                  new-depth (+ bracket-depth (bracket-delta line-toks))
                  out'      (reduce conj! out line-toks)]
              (recur (inc i) indent-stack new-depth out'))

            :else
            (let [top (peek indent-stack)
                  line-toks (tokenize-line content (inc i))
                  new-depth (+ bracket-depth (bracket-delta line-toks))]
              (cond
                (> indent top)
                (let [out' (-> out
                               (conj! [:indent nil (inc i) 1])
                               (as-> o (reduce conj! o line-toks))
                               (as-> o (if (zero? new-depth)
                                         (conj! o [:newline nil (inc i) 1])
                                         o)))]
                  (recur (inc i) (conj indent-stack indent) new-depth out'))

                (= indent top)
                (let [out' (reduce conj! out line-toks)
                      out' (if (zero? new-depth)
                             (conj! out' [:newline nil (inc i) 1])
                             out')]
                  (recur (inc i) indent-stack new-depth out'))

                :else                                       ; indent < top → dedent(s)
                (let [[stack' out']
                      (loop [stack indent-stack
                             out out]
                        (let [top (peek stack)]
                          (cond
                            (< indent top)
                            (recur (pop stack) (conj! out [:dedent nil (inc i) 1]))

                            (= indent top)
                            [stack out]

                            :else
                            (throw (ex-info "inconsistent dedent"
                                            {:line (inc i)
                                             :indent indent
                                             :stack indent-stack})))))
                      out' (reduce conj! out' line-toks)
                      out' (if (zero? new-depth)
                             (conj! out' [:newline nil (inc i) 1])
                             out')]
                  (recur (inc i) stack' new-depth out')))))))))))
