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
  ;; F68 L6b (Proposal H) — `record` / `class` / `enum` / `protocol` /
  ;; `methods` / `metadata` / `cases` retired. `def` is the sole opener;
  ;; kind flows from body structural cues (accepts:→protocol, case→enum,
  ;; transition/use:/ctor params/nested-slot→class, otherwise→record).
  ;;
  ;; F68 L6c — `data` retired (F67 L5 collapsed the `data:` header).
  ;; `contracts` / `constructor` were reserved but never dispatched.
  ;;
  ;; F33 L6 — `expose` retired under Proposal C+C1. External reads of
  ;; `data:` fields are legitimate observation (Corvid's actor substrate
  ;; makes external mutation impossible by grammar), so there is nothing
  ;; to expose — every class member is readable by default and API
  ;; stability is a documentation contract, not a compiler-enforced
  ;; marker (spec §7.7). Weak/unowned reference modifiers stay a
  ;; separate concern (F35 parent back-refs use a companion mechanism).
  ;;
  ;; `function` kept only for the grace-period module-scope `def name(...)
  ;; -> T:` migration (K8).
  #{"module" "from" "import" "as"
    "def"
    "use" "invariants"
    "accepts"
    "extend" "replace"
    "deprecated"
    "then" "do" "when" "let"
    "self" "nil" "true" "false"
    "and" "or" "not" "is" "in" "of"
    ;; `match` retired 2026-08-18 — never dispatched by parser and
    ;; not on spec §2.7 committed keyword list (line 2400). Spec
    ;; §3.7 line 702 originally said cxc uses `switch`, not `match`.
    ;; F69 L5 (2026-08-19) — `switch` retires from the reserved-word
    ;; set: pattern-matched dispatch is expression-position `<:`
    ;; receive-glyph blocks (spec §3.7 rewrite). `switch` remains as
    ;; a diagnostic anchor in parse-top / parse-statement, which fire
    ;; a targeted rewrite hint when a user still writes `switch`.
    ;; `case` STAYS reserved — it's still valid inside enum bodies
    ;; (F68 L2 tagged-variant defs).
    "if" "else" "return" "case"
    "function"
    "for"
    ;; F13 L1 (2026-08-18) — reference modifiers on `data:` field
    ;; declarations. Shape per spec §6.14: `name: weak T` /
    ;; `name: unowned T`. Distinct from F35's compiler-synthesized
    ;; parent back-refs (those use an internal marker, not this
    ;; user-facing keyword).
    "weak" "unowned"
    ;; F17 L1 (2026-08-18) — statement-form loop primitives per
    ;; spec §3.8.3 / §4.20.3. `while <cond>: <body>` + `break` +
    ;; `continue`. `loop` (infinite loop with `break value`) is
    ;; F17 L2 — reserving the keyword is safe at L1 to reduce
    ;; parser churn later.
    "while" "loop" "break" "continue"})

(def multi-char-glyphs
  ["<:" "<|" "|>" ":>" ":<" "->" "=>" "==" "!=" "<=" ">=" "//" "**"])

(def single-char-glyphs
  #{\+ \- \* \/ \% \= \< \> \! \& \|})

(def punctuation
  #{\( \) \[ \] \{ \} \, \: \. \; \? \!})

(defn- classify-word [w]
  (if (reserved-words w) :kw :ident))

;; ---------------------------------------------------------------------------
;; F6 — character literals + full escape sequences per spec §2.7.1.
;;
;;   simple      := \n \t \r \\ \" \' \0 \a \b \f \v
;;   hex-byte    := \xNN          (exactly two hex digits, U+0000..U+00FF)
;;   unicode     := \u{HEX+}      (1-6 hex digits, U+0000..U+10FFFF)
;;
;; Shared scanner: strings (`"..."`) and char literals (`'x'`) accept
;; the same escape set. Unknown escapes are a hard error (spec is
;; explicit: only the listed sequences are legal).
;;
;; Char literal shape: `'<one-char-or-escape>'`. Rejects empty (`''`)
;; and multi-char (`'ab'`). Char token payload is the string form of the
;; scalar (upstream `parse-primary` reads `:lit-char :value` as a
;; string).
;; ---------------------------------------------------------------------------

(defn- scan-hex-byte-escape
  "After `\\x`: read exactly two hex digits, return [char end-index]."
  [s j n line col]
  (when (< (+ j 2) n)
    :ok)
  (when-not (and (<= (+ j 2) n)
                 (not= -1 (Character/digit (.charAt s j) 16))
                 (not= -1 (Character/digit (.charAt s (inc j)) 16)))
    (throw (ex-info "`\\xNN` escape requires exactly two hex digits"
                    {:line line :col col})))
  [(char (Integer/parseInt (subs s j (+ j 2)) 16)) (+ j 2)])

(defn- scan-unicode-escape
  "After `\\u`: read `{HEX+}` (1-6 hex digits, U+0000..U+10FFFF).
   Returns [string end-index]. Uses a string (not a char) because
   supplementary code points don't fit in a single Java char."
  [s j n line col]
  (when-not (and (< j n) (= \{ (.charAt s j)))
    (throw (ex-info "`\\u` escape requires `{...}` (e.g. `\\u{1F600}`)"
                    {:line line :col col})))
  (let [start (inc j)
        end (loop [k start]
              (cond
                (>= k n)
                (throw (ex-info "unterminated `\\u{...}` escape"
                                {:line line :col col}))
                (= \} (.charAt s k)) k
                (not= -1 (Character/digit (.charAt s k) 16)) (recur (inc k))
                :else (throw (ex-info
                              "`\\u{...}` escape body must be hex digits"
                              {:line line :col col}))))
        hex (subs s start end)]
    (when (or (zero? (count hex)) (> (count hex) 6))
      (throw (ex-info "`\\u{...}` escape requires 1-6 hex digits"
                      {:line line :col col})))
    (let [cp (Integer/parseInt hex 16)]
      (when (> cp 0x10FFFF)
        (throw (ex-info (str "Unicode escape U+" hex " exceeds U+10FFFF")
                        {:line line :col col})))
      [(String. (Character/toChars cp)) (inc end)])))

(defn- append-escape!
  "Consume an escape sequence starting at index `j` (which points at
   the char after `\\`). Appends the decoded chars to `buf`. Returns
   the index after the escape."
  [s buf j n line col]
  (let [esc (.charAt s j)]
    (case esc
      \n (do (.append buf \newline) (inc j))
      \t (do (.append buf \tab)     (inc j))
      \r (do (.append buf \return)  (inc j))
      \\ (do (.append buf \\)       (inc j))
      \" (do (.append buf \")       (inc j))
      \' (do (.append buf \')       (inc j))
      \0 (do (.append buf (char 0)) (inc j))
      \a (do (.append buf (char 7)) (inc j))
      \b (do (.append buf (char 8)) (inc j))
      \f (do (.append buf (char 12)) (inc j))
      \v (do (.append buf (char 11)) (inc j))
      \x (let [[ch end] (scan-hex-byte-escape s (inc j) n line col)]
           (.append buf ch)
           end)
      \u (let [[s' end] (scan-unicode-escape s (inc j) n line col)]
           (.append buf s')
           end)
      (throw (ex-info (str "unknown escape sequence `\\" esc
                           "` — legal escapes: \\n \\t \\r \\\\ \\\" \\' "
                           "\\0 \\a \\b \\f \\v \\xNN \\u{...} (spec §2.7.1)")
                      {:line line :col col})))))

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
          (let [end (append-escape! s buf (inc j) n line col)]
            (recur end buf)))

        :else
        (do (.append buf (.charAt s j))
            (recur (inc j) buf))))))

(defn- scan-char
  "Scan a `'x'` char literal starting at `i` (which points at the
   opening `'`). Returns [payload-string end-index]. Payload is the
   scalar as a string (may be >1 char for supplementary code points
   via `\\u{...}`).

   Rejects: empty `''`; unterminated (`'x`); multi-char (`'ab'`)."
  [s i line col]
  (let [n (count s)
        buf (StringBuilder.)]
    (when (>= (inc i) n)
      (throw (ex-info "unterminated char literal" {:line line :col col})))
    (let [c1 (.charAt s (inc i))]
      (cond
        (= \' c1)
        (throw (ex-info "empty char literal `''` — need one character"
                        {:line line :col col}))

        (= \\ c1)
        (do (when (>= (+ i 2) n)
              (throw (ex-info "trailing backslash in char literal"
                              {:line line :col col})))
            (let [end (append-escape! s buf (+ i 2) n line col)]
              (when (or (>= end n) (not= \' (.charAt s end)))
                (throw (ex-info "unterminated char literal — expected `'`"
                                {:line line :col col})))
              [(.toString buf) (inc end)]))

        :else
        (do (.append buf c1)
            (when (or (>= (+ i 2) n) (not= \' (.charAt s (+ i 2))))
              (throw (ex-info "char literal must contain exactly one character"
                              {:line line :col col})))
            [(.toString buf) (+ i 3)])))))

;; ---------------------------------------------------------------------------
;; F4 — numeric literals per spec §2.7.1.
;;
;;   decimal   := digit(_?digit)*
;;   hex-int   := 0[xX] hex-digit(_?hex-digit)*
;;   binary    := 0[bB] bin-digit(_?bin-digit)*
;;   octal     := 0[oO] oct-digit(_?oct-digit)*
;;   float     := decimal `.` decimal (e[+-]?decimal)?
;;              | decimal e[+-]?decimal
;;   hex-float := 0[xX] hex-digits (`.` hex-digits)? [pP][+-]?decimal
;;
;; C-style leading-zero-octal (`017`) is rejected as ambiguous.
;; Underscore separators must sit between two digits (no leading, no
;; trailing, no `__` runs). F5 unit-suffix retirement is preserved via
;; `reject-unit-suffix!` — any identifier-like char immediately after a
;; numeric run raises `:lex-unit-suffix-retired`.
;; ---------------------------------------------------------------------------

(defn- scan-digits
  "Scan a run of base-N digits with legal `_` separators from `s`
   starting at `start`. Returns [digits-string end-index] with `_`
   stripped. Empty run returns [\"\" start] — caller decides whether
   that's legal (hex-float integer part is optional). `_` at the
   start/end of the run or adjacent to a non-digit throws."
  [s start n base line-no]
  (let [sb (StringBuilder.)]
    (loop [j start last-was-digit? false]
      (cond
        (>= j n)
        (do (when (and (pos? (.length sb)) (not last-was-digit?))
              (throw (ex-info "numeric literal ends with `_`"
                              {:line line-no :col j})))
            [(.toString sb) j])

        (not= -1 (Character/digit (.charAt s j) base))
        (do (.append sb (.charAt s j)) (recur (inc j) true))

        (= \_ (.charAt s j))
        (if last-was-digit?
          (recur (inc j) false)
          (throw (ex-info "`_` in numeric literal must follow a digit"
                          {:line line-no :col (inc j)})))

        :else
        (do (when (and (pos? (.length sb)) (not last-was-digit?))
              (throw (ex-info "numeric literal has trailing `_`"
                              {:line line-no :col j})))
            [(.toString sb) j])))))

(defn- reject-unit-suffix!
  "F5 retirement — any identifier-continuation char immediately after
   a numeric run is a retired unit suffix. Raises
   `:lex-unit-suffix-retired` with a pointer at the equivalent type-
   constructor form."
  [s j n num-str line-no]
  (when (and (< j n)
             (let [c (.charAt s j)]
               (or (Character/isLetter c) (= c \_))))
    (let [k (loop [k j]
              (if (and (< k n)
                       (let [c (.charAt s k)]
                         (or (Character/isLetterOrDigit c) (= c \_))))
                (recur (inc k))
                k))]
      (throw (ex-info
              (str "numeric literal with unit suffix at line " line-no
                   ", col " (inc j) ": `" num-str (subs s j k)
                   "` — unit suffixes are not part of v1 (spec §2.7.1). "
                   "Use the type constructor instead, e.g. "
                   "`Duration(millis: " num-str ")`.")
              {:kind      :lex-unit-suffix-retired
               :line      line-no
               :col       (inc j)
               :numeric   num-str
               :suffix    (subs s j k)})))))

(defn- scan-signed-exponent
  "Scan `[+-]?decimal-digits` for `e`/`E` / `p`/`P` exponents. Returns
   [sign-string exp-digits-string end-index]. Empty exp-digits throws."
  [s start n line-no marker-col]
  (let [[sign j0] (cond
                    (and (< start n) (= \+ (.charAt s start)))
                    ["+" (inc start)]
                    (and (< start n) (= \- (.charAt s start)))
                    ["-" (inc start)]
                    :else ["" start])
        [exp-digits j] (scan-digits s j0 n 10 line-no)]
    (when (zero? (count exp-digits))
      (throw (ex-info "exponent requires digits after `e`/`E`/`p`/`P`"
                      {:line line-no :col marker-col})))
    [sign exp-digits j]))

(defn- scan-hex-number
  "Handle `0[xX]...` — hex integer OR C99 hex-float `0x1.8p+1` (which
   REQUIRES the `p`/`P` binary exponent). Returns [end-index kind
   value]."
  [s i n line-no]
  (let [digit-start (+ i 2)
        [int-digits j] (scan-digits s digit-start n 16 line-no)
        has-dot? (and (< j n) (= \. (.charAt s j))
                      (< (inc j) n)
                      (not= -1 (Character/digit (.charAt s (inc j)) 16)))
        [frac-digits j2] (if has-dot?
                           (scan-digits s (inc j) n 16 line-no)
                           [nil j])
        has-p? (and (< j2 n)
                    (contains? #{\p \P} (.charAt s j2)))]
    (cond
      (and (not has-dot?) (not has-p?))
      (do (when (zero? (count int-digits))
            (throw (ex-info "empty numeric literal after `0x`/`0X` prefix"
                            {:line line-no :col (inc i)})))
          (reject-unit-suffix! s j n (str "0x" int-digits) line-no)
          [j :int (Long/parseLong int-digits 16)])

      has-p?
      (let [[sign exp-digits j3] (scan-signed-exponent
                                  s (inc j2) n line-no (inc j2))
            hex-lit (str "0x" int-digits
                         (when has-dot? (str "." frac-digits))
                         "p" sign exp-digits)]
        (reject-unit-suffix! s j3 n hex-lit line-no)
        [j3 :float (Double/parseDouble hex-lit)])

      :else
      (throw (ex-info
              (str "hex-float literal requires the `p`/`P` binary "
                   "exponent (C99 form; e.g. `0x1.8p+1`)")
              {:line line-no :col (inc i)})))))

(defn- scan-decimal-number
  "Decimal integer, decimal float, or decimal float with `e`/`E`
   exponent. Returns [end-index kind value]."
  [s i n line-no]
  (let [[int-digits j] (scan-digits s i n 10 line-no)
        _              (when (zero? (count int-digits))
                         (throw (ex-info "empty numeric literal"
                                         {:line line-no :col (inc i)})))
        has-dot? (and (< j n) (= \. (.charAt s j))
                      (< (inc j) n)
                      (Character/isDigit (.charAt s (inc j))))
        [frac-digits j2] (if has-dot?
                           (scan-digits s (inc j) n 10 line-no)
                           [nil j])
        has-e? (and (< j2 n)
                    (contains? #{\e \E} (.charAt s j2)))]
    (cond
      has-e?
      (let [[sign exp-digits j3] (scan-signed-exponent
                                  s (inc j2) n line-no (inc j2))
            lit (str int-digits
                     (when has-dot? (str "." frac-digits))
                     "e" sign exp-digits)]
        (reject-unit-suffix! s j3 n lit line-no)
        [j3 :float (Double/parseDouble lit)])

      has-dot?
      (let [lit (str int-digits "." frac-digits)]
        (reject-unit-suffix! s j2 n lit line-no)
        [j2 :float (Double/parseDouble lit)])

      :else
      (do (reject-unit-suffix! s j n int-digits line-no)
          [j :int (Long/parseLong int-digits)]))))

(defn- scan-number
  "Scan a numeric literal starting at `i` (which must be a digit).
   Returns [end-index kind value]. Handles decimal, hex, binary,
   octal, decimal-float, exponent, and C99 hex-float shapes per
   spec §2.7.1. F5 unit-suffix retirement fires uniformly across
   shapes via `reject-unit-suffix!`."
  [s i line-no]
  (let [n (count s)
        c0 (.charAt s i)
        prefix-char (when (and (= c0 \0) (< (inc i) n))
                      (Character/toLowerCase (.charAt s (inc i))))]
    (cond
      (= prefix-char \x)
      (scan-hex-number s i n line-no)

      (= prefix-char \b)
      (let [[digits j] (scan-digits s (+ i 2) n 2 line-no)]
        (when (zero? (count digits))
          (throw (ex-info "empty numeric literal after `0b`/`0B` prefix"
                          {:line line-no :col (inc i)})))
        (reject-unit-suffix! s j n (str "0b" digits) line-no)
        [j :int (Long/parseLong digits 2)])

      (= prefix-char \o)
      (let [[digits j] (scan-digits s (+ i 2) n 8 line-no)]
        (when (zero? (count digits))
          (throw (ex-info "empty numeric literal after `0o`/`0O` prefix"
                          {:line line-no :col (inc i)})))
        (reject-unit-suffix! s j n (str "0o" digits) line-no)
        [j :int (Long/parseLong digits 8)])

      ;; Reject C-style leading-zero-octal (`017`) as ambiguous per spec.
      ;; `0.5`, `0`, `0e1` are fine — the guard checks for a digit right
      ;; after the leading zero, not `.`/`e`/end-of-token.
      (and (= c0 \0) (< (inc i) n) (Character/isDigit (.charAt s (inc i))))
      (throw (ex-info
              (str "leading-zero integer `" (subs s i (min n (+ i 5))) "…` — "
                   "cxc rejects C's `017` octal form as ambiguous. Use "
                   "`0o17` for octal or drop the leading zero for decimal. "
                   "See spec §2.7.1.")
              {:line line-no :col (inc i)}))

      :else
      (scan-decimal-number s i n line-no))))

(defn- id-start?
  "F1 / §2.1 — an identifier-start character per UAX #31. Delegates
   to Java's `isUnicodeIdentifierStart` (Unicode categories Lu, Ll,
   Lt, Lm, Lo, Nl + Other_ID_Start) and explicitly allows `_` since
   Unicode's Pc category isn't in UAX #31's ID_Start set but Corvid
   permits leading underscores (private-name convention)."
  [c]
  (or (Character/isUnicodeIdentifierStart ^int (int c))
      (= c \_)))

(defn- id-continue?
  "F1 / §2.1 — an identifier-continue character per UAX #31. Adds
   digits and combining marks to the start set via
   `isUnicodeIdentifierPart`; keeps the explicit `_` fallback
   symmetric with `id-start?`. Supplementary code points (>U+FFFF)
   and NFC normalization are deferred — this MVP handles the BMP
   Unicode range which covers all v1 use cases."
  [c]
  (or (Character/isUnicodeIdentifierPart ^int (int c))
      (= c \_)))

(defn- scan-word [s i]
  (let [n (count s)]
    (loop [j i]
      (if (and (< j n) (id-continue? (.charAt s j)))
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

        (= \' (.charAt line i))
        (let [[ch j] (scan-char line i line-no (inc i))]
          (recur j (conj! out [:char ch line-no (inc i)])))

        (Character/isDigit (.charAt line i))
        (let [[j kind value] (scan-number line i line-no)]
          (recur j (conj! out [kind value line-no (inc i)])))

        (id-start? (.charAt line i))
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

(defn- leading-indent [line line-no]
  "Return [indent-count content] where indent-count is spaces and content
   is the rest of the line. Tabs count as 8 spaces (matches ObjC/Cocoa
   convention). Blank / comment-only lines return [nil nil] and are skipped.

   F3 / §2.4: tabs and spaces MUST NOT mix within a single line's leading
   indent. Mixing raises a compile error naming the line + offending column."
  (let [n (count line)]
    (loop [i 0 indent 0 saw-space? false saw-tab? false]
      (cond
        (>= i n) [nil nil]
        (= \space (.charAt line i))
        (if saw-tab?
          (throw (ex-info (str "indent mixes tabs and spaces (spec §2.4 forbids). "
                               "Pick one convention per file; convert tabs to spaces "
                               "or vice versa.")
                          {:line line-no :col (inc i) :char \space}))
          (recur (inc i) (inc indent) true saw-tab?))
        (= \tab (.charAt line i))
        (if saw-space?
          (throw (ex-info (str "indent mixes tabs and spaces (spec §2.4 forbids). "
                               "Pick one convention per file; convert tabs to spaces "
                               "or vice versa.")
                          {:line line-no :col (inc i) :char \tab}))
          (recur (inc i) (+ indent 8) saw-space? true))
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

(defn- strip-block-comments
  "F2 / §2.2 — remove `#| ... |#` block comments from source before the
   line-based tokenizer runs. Nesting is supported (Racket-style). String
   literals are opaque; `#|` inside a `\"...\"` is not a comment start.
   Newlines are preserved verbatim and non-newline comment content is
   replaced with spaces so that line numbers and columns downstream stay
   truthful to the original source. Unterminated block comment raises a
   compile error pointing at the opener."
  [source]
  (let [n (count source)
        sb (StringBuilder.)]
    (loop [i 0 in-string? false depth 0 open-line 1 line 1]
      (cond
        (>= i n)
        (if (pos? depth)
          (throw (ex-info "unterminated block comment `#|` (spec §2.2)"
                          {:line open-line}))
          (.toString sb))

        in-string?
        (let [c (.charAt source i)]
          (.append sb c)
          (cond
            (= c \\)
            (do (when (< (inc i) n) (.append sb (.charAt source (inc i))))
                (recur (+ i 2) true depth open-line
                       (if (and (< (inc i) n) (= \newline (.charAt source (inc i))))
                         (inc line) line)))
            (= c \")
            (recur (inc i) false depth open-line line)
            (= c \newline)
            (recur (inc i) true depth open-line (inc line))
            :else
            (recur (inc i) true depth open-line line)))

        (pos? depth)
        (let [c (.charAt source i)]
          (cond
            (and (< (inc i) n) (= c \|) (= \# (.charAt source (inc i))))
            (do (.append sb "  ") (recur (+ i 2) false (dec depth) open-line line))

            (and (< (inc i) n) (= c \#) (= \| (.charAt source (inc i))))
            (do (.append sb "  ") (recur (+ i 2) false (inc depth) open-line line))

            (= c \newline)
            (do (.append sb \newline) (recur (inc i) false depth open-line (inc line)))

            :else
            (do (.append sb \space) (recur (inc i) false depth open-line line))))

        :else
        (let [c (.charAt source i)]
          (cond
            (and (< (inc i) n) (= c \#) (= \| (.charAt source (inc i))))
            (do (.append sb "  ") (recur (+ i 2) false 1 line line))

            (= c \")
            (do (.append sb c) (recur (inc i) true 0 open-line line))

            (= c \newline)
            (do (.append sb c) (recur (inc i) false 0 open-line (inc line)))

            :else
            (do (.append sb c) (recur (inc i) false 0 open-line line))))))))

(defn tokenize
  "Tokenize cxc source into a flat vector of tokens with layout markers.

   Layout preprocessor tracks bracket-depth across lines: while any
   parens/brackets/braces are open, subsequent lines are treated as
   physical-line continuations of the logical line — no INDENT/DEDENT/
   NEWLINE emitted, tokens simply concatenated. This lets multi-line
   argument lists, list literals, and message payloads work naturally.

   F2 / §2.2 — `#| ... |#` block comments (nested, multi-line) are
   stripped first; `##` doc comments still lex as ordinary line comments
   (attachment to declaration metadata waits on F10)."
  [source]
  (if (str/blank? source)
    [[:eof nil 1 1]]
    (let [source (strip-block-comments source)
          lines (str/split-lines source)
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
              [indent content] (leading-indent line (inc i))]
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
