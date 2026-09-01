(ns cxc.lex-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.lex :as lex]))

(defn- kinds
  "Extract just the token kinds for compact assertions."
  [toks]
  (mapv first toks))

(defn- vals-of
  "Extract [kind value] pairs, skipping :newline/:indent/:dedent."
  [toks]
  (->> toks
       (remove #(#{:newline :indent :dedent :eof} (first %)))
       (mapv (fn [t] [(first t) (second t)]))))

(deftest empty-source
  (is (= [[:eof nil 1 1]] (lex/tokenize ""))))

(deftest simple-tokens
  (testing "keywords, identifiers, punctuation, integer"
    (is (= [[:kw "def"] [:ident "foo"] [:punct ":"] [:ident "x"] [:glyph "="] [:int 1]]
           (vals-of (lex/tokenize "def foo:\n    x = 1\n"))))))

(deftest retired-alias-word-is-an-identifier
  (testing "L6 — the retired F7 declaration opener is no longer reserved"
    (is (= [[:ident "alias"]]
           (vals-of (lex/tokenize "alias"))))))

(deftest multi-char-glyphs
  (testing "all cxc wire glyphs recognized as single tokens"
    (doseq [g ["<:" "<|" "|>" ":>" ":-" "->" "=>" "==" "!=" "<=" ">="]]
      (let [toks (vals-of (lex/tokenize (str "a " g " b")))]
        (is (= [:glyph g] (nth toks 1))
            (str "glyph " g " should lex as :glyph"))))))

(deftest proposition-marker-token
  (testing "|: lexes as one proposition-marker glyph"
    (is (= [[:glyph "|:"]]
           (vals-of (lex/tokenize "|:"))))))

(deftest proposition-marker-in-context
  (testing "|: starts a proposition without splitting into | and :"
    (is (= [[:glyph "|:"] [:ident "x"] [:glyph ">="] [:int 0]]
           (vals-of (lex/tokenize "|: x >= 0"))))))

;; @ annotation-prefix retired 2026-08-30 (annotation-grammar-retirement
;; wave). `@` at column 0 is a targeted retirement diagnostic; no token
;; is emitted for it. Coverage moves to parse_test / lex_test retirement
;; diagnostics.

(deftest annotation-prefix-retired
  (testing "annotation-grammar-retirement (2026-08-30) — `@` character rejects with a targeted retirement diagnostic"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"annotation grammar .*retired at v1.0"
         (lex/tokenize "@deprecated(\"msg\")")))))

(deftest string-literals
  (testing "basic string"
    (is (= [[:string "hello"]]
           (vals-of (lex/tokenize "\"hello\""))))))

(deftest numbers
  (testing "int and float"
    (is (= [[:int 42]]      (vals-of (lex/tokenize "42"))))
    (is (= [[:float 3.14]]  (vals-of (lex/tokenize "3.14"))))))

(deftest comments-are-stripped
  (is (= [[:ident "x"] [:glyph "="] [:int 1]]
         (vals-of (lex/tokenize "x = 1  # this is a comment\n")))))

(deftest layout-indent-dedent
  (testing "opening a block emits :indent; closing emits :dedent"
    (let [toks (lex/tokenize "def foo:\n    x\n")]
      (is (= [:kw :ident :punct :newline :indent :ident :newline :dedent :eof]
             (kinds toks))))))

(deftest nested-indents
  (testing "multiple nesting levels produce matching indent/dedent pairs"
    (let [toks (lex/tokenize "a:\n    b:\n        c\n")
          ks   (kinds toks)]
      (is (= 2 (count (filter #{:indent} ks))))
      (is (= 2 (count (filter #{:dedent} ks)))))))

(deftest nullable-marker
  (testing "? lexes as punct so `T?` parses as type + suffix"
    (is (= [:ident :punct]
           (kinds (remove #(#{:newline :indent :dedent :eof} (first %))
                          (lex/tokenize "Position?")))))))

(deftest dotted-message-payload
  (testing "self <: clock.every(rate){_: Instant} — realistic mix"
    (let [toks (vals-of (lex/tokenize "self <: clock.every(rate){_: Instant}"))]
      (is (= [:kw "self"]      (nth toks 0)))
      (is (= [:glyph "<:"]     (nth toks 1)))
      (is (= [:ident "clock"]  (nth toks 2)))
      (is (= [:punct "."]      (nth toks 3)))
      (is (= [:ident "every"]  (nth toks 4)))
      (is (= [:punct "("]      (nth toks 5)))
      (is (= [:ident "rate"]   (nth toks 6)))
      (is (= [:punct ")"]      (nth toks 7)))
      (is (= [:punct "{"]      (nth toks 8)))
      (is (= [:ident "_"]      (nth toks 9)))
      (is (= [:punct ":"]      (nth toks 10)))
      (is (= [:ident "Instant"] (nth toks 11)))
      (is (= [:punct "}"]      (nth toks 12))))))

(deftest game-toys-lexes
  (testing "the full game-toys.cxc example lexes without error"
    (let [src (slurp "../design/examples/game-toys.cxc")]
      (is (some? (lex/tokenize src))))))

(deftest tab-then-space-in-indent-rejected
  (testing "F3 / §2.4 — indent starting with a tab then a space is a compile error"
    (let [src "def foo:\n\t x = 1\n"
          ex  (try (lex/tokenize src) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "tab followed by space in indent must throw")
      (is (re-find #"tabs and spaces" (ex-message ex)))
      (is (= 2 (:line (ex-data ex))) "diagnostic points at the offending line"))))

(deftest space-then-tab-in-indent-rejected
  (testing "F3 / §2.4 — indent starting with a space then a tab is a compile error"
    (let [src "def foo:\n \tx = 1\n"
          ex  (try (lex/tokenize src) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "space followed by tab in indent must throw")
      (is (re-find #"tabs and spaces" (ex-message ex))))))

(deftest all-tabs-indent-still-accepted
  (testing "F3 — pure tab indent is legal (single convention per line)"
    (is (some? (lex/tokenize "def foo:\n\tx = 1\n")))))

(deftest all-spaces-indent-still-accepted
  (testing "F3 — pure space indent (existing convention) is legal"
    (is (some? (lex/tokenize "def foo:\n    x = 1\n")))))

(deftest block-comment-inline
  (testing "F2 / §2.2 — `#| ... |#` on one line strips out"
    (is (= [[:ident "a"] [:glyph "="] [:int 1]]
           (vals-of (lex/tokenize "a #| ignored |# = 1\n"))))))

(deftest block-comment-multiline
  (testing "F2 — block comment spans lines; content vanishes, line numbers preserved"
    (let [toks (lex/tokenize "a = 1\n#|\nignored line one\nignored line two\n|#\nb = 2\n")]
      (is (= [[:ident "a"] [:glyph "="] [:int 1] [:ident "b"] [:glyph "="] [:int 2]]
             (vals-of toks)))
      (let [b-tok (first (filter #(= [:ident "b"] [(first %) (second %)]) toks))]
        (is (= 6 (nth b-tok 2)) "`b` sits at original line 6 despite the 4-line block comment above")))))

(deftest block-comment-nested
  (testing "F2 — nested block comments close correctly (Racket-style)"
    (is (= [[:ident "a"] [:glyph "="] [:int 1]]
           (vals-of (lex/tokenize "a #| outer #| inner |# still-in-outer |# = 1\n"))))))

(deftest block-comment-inside-string-ignored
  (testing "F2 — `#|` inside a string literal is NOT a comment opener"
    (is (= [[:string "x #| not a comment"]]
           (vals-of (lex/tokenize "\"x #| not a comment\""))))))

(deftest block-comment-unterminated-rejected
  (testing "F2 — unterminated `#|` raises a compile error pointing at the opener line"
    (let [ex (try (lex/tokenize "a = 1\n#| never closed\nb = 2\n") nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"unterminated block comment" (ex-message ex))))))

(deftest doc-comment-still-line-comment
  (testing "F2 — `##` doc comment currently lexes as ordinary line comment (F10 will attach it)"
    (is (= [[:ident "a"] [:glyph "="] [:int 1]]
           (vals-of (lex/tokenize "## doc\na = 1\n"))))))

;; ---------------------------------------------------------------------------
;; F1 — Unicode identifiers per UAX #31
;; ---------------------------------------------------------------------------

(deftest f1-latin-extended-identifier
  (testing "F1 — Latin-Extended identifiers (café, résumé) lex as :ident, not stopped at the diacritic."
    (is (= [[:ident "café"]]
           (vals-of (lex/tokenize "café"))))
    (is (= [[:ident "résumé"] [:glyph "="] [:int 1]]
           (vals-of (lex/tokenize "résumé = 1"))))))

(deftest f1-cjk-identifier
  (testing "F1 — CJK ideographs qualify as UAX #31 identifier chars."
    (is (= [[:ident "変数"] [:glyph "="] [:int 42]]
           (vals-of (lex/tokenize "変数 = 42"))))))

(deftest f1-greek-identifier
  (testing "F1 — Greek letters (common math variable names)."
    (is (= [[:ident "α"] [:glyph "+"] [:ident "β"]]
           (vals-of (lex/tokenize "α + β"))))))

(deftest f1-leading-underscore-still-legal
  (testing "F1 — leading `_` (Pc category, outside UAX #31 ID_Start) remains legal by explicit allowance."
    (is (= [[:ident "_x"] [:glyph "="] [:int 1]]
           (vals-of (lex/tokenize "_x = 1"))))))

(deftest f1-digit-continuation-with-unicode
  (testing "F1 — identifier continuation accepts digits after Unicode start."
    (is (= [[:ident "café1"]]
           (vals-of (lex/tokenize "café1"))))))

;; ---------------------------------------------------------------------------
;; F4 — numeric literal grammar per spec §2.7.1
;; ---------------------------------------------------------------------------

(deftest f4-decimal-with-underscore-separators
  (testing "F4 — `_` between digits is legal readability separator; stripped before parsing."
    (is (= [[:int 1000000]]
           (vals-of (lex/tokenize "1_000_000"))))))

(deftest f4-hex-integer
  (testing "F4 — `0xFF` / `0xDEAD_BEEF` — hex prefix, case-insensitive, underscores legal."
    (is (= [[:int 255]]        (vals-of (lex/tokenize "0xFF"))))
    (is (= [[:int 255]]        (vals-of (lex/tokenize "0xff"))))
    (is (= [[:int 0xDEADBEEF]] (vals-of (lex/tokenize "0xDEAD_BEEF"))))
    (is (= [[:int 255]]        (vals-of (lex/tokenize "0XfF"))))))

(deftest f4-binary-integer
  (testing "F4 — `0b1010` / `0b1111_0000` — binary prefix, case-insensitive."
    (is (= [[:int 10]]  (vals-of (lex/tokenize "0b1010"))))
    (is (= [[:int 240]] (vals-of (lex/tokenize "0b1111_0000"))))
    (is (= [[:int 10]]  (vals-of (lex/tokenize "0B1010"))))))

(deftest f4-octal-integer
  (testing "F4 — `0o17` / `0o755` — octal prefix (NOT C's leading-zero form)."
    (is (= [[:int 15]]  (vals-of (lex/tokenize "0o17"))))
    (is (= [[:int 493]] (vals-of (lex/tokenize "0o755"))))))

(deftest f4-leading-zero-decimal-rejected
  (testing "F4 — C-style leading-zero-octal (`017`) rejected as ambiguous per spec §2.7.1."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"leading-zero integer.*cxc rejects C's `017` octal"
         (lex/tokenize "017")))))

(deftest f4-decimal-exponent
  (testing "F4 — decimal float with exponent: `1e10`, `2.5e-3`, `1.5E+2`."
    (is (= [[:float 1.0e10]]  (vals-of (lex/tokenize "1e10"))))
    (is (= [[:float 2.5e-3]]  (vals-of (lex/tokenize "2.5e-3"))))
    (is (= [[:float 150.0]]   (vals-of (lex/tokenize "1.5E+2"))))))

(deftest f4-hex-float
  (testing "F4 — C99 hex-float `0x1.8p+1` (= 3.0). Requires the `p`/`P` binary exponent."
    (is (= [[:float 3.0]]  (vals-of (lex/tokenize "0x1.8p+1"))))
    (is (= [[:float 0.5]]  (vals-of (lex/tokenize "0x1p-1"))))))

(deftest f4-hex-float-requires-p-exponent
  (testing "F4 — `0x1.8` without `p` exponent is rejected (C99 hex-floats mandate `p`)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"hex-float literal requires the `p`/`P` binary exponent"
         (lex/tokenize "0x1.8")))))

(deftest f4-underscore-at-numeric-start-rejected
  (testing "F4 — `_` immediately after prefix (`0x_FF`) — no preceding digit → rejected."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`_` in numeric literal must follow a digit"
         (lex/tokenize "0x_FF")))))

(deftest f4-empty-prefix-rejected
  (testing "F4 — `0x` with no digits is rejected."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"empty numeric literal after `0x`"
         (lex/tokenize "0x")))))

(deftest f4-f5-unit-suffix-still-rejected-on-hex
  (testing "F4 — F5 unit-suffix diagnostic still fires on `0xFFms` (identifier char after numeric run)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unit suffixes are not part of v1"
         (lex/tokenize "0xFFms")))))

;; ---------------------------------------------------------------------------
;; F6 — character literals + escape sequences per spec §2.7.1
;; ---------------------------------------------------------------------------

(deftest f6-simple-char-literal
  (testing "F6 — `'a'` lexes as :char with the scalar as payload."
    (is (= [[:char "a"]] (vals-of (lex/tokenize "'a'"))))))

(deftest f6-char-escape-sequences
  (testing "F6 — standard C-style escapes: `\\n \\t \\r \\\\ \\' \\\" \\0 \\a \\b \\f \\v`."
    (is (= [[:char "\n"]]        (vals-of (lex/tokenize "'\\n'"))))
    (is (= [[:char "\t"]]        (vals-of (lex/tokenize "'\\t'"))))
    (is (= [[:char "\\"]]        (vals-of (lex/tokenize "'\\\\'"))))
    (is (= [[:char "'"]]         (vals-of (lex/tokenize "'\\''"))))
    (is (= [[:char (str (char 0))]] (vals-of (lex/tokenize "'\\0'"))))))

(deftest f6-char-hex-byte-escape
  (testing "F6 — `\\xNN` hex-byte escape, exactly two hex digits."
    (is (= [[:char (str (char 0x1F))]]
           (vals-of (lex/tokenize "'\\x1F'"))))))

(deftest f6-char-unicode-scalar-escape
  (testing "F6 — `\\u{HEX+}` Unicode scalar escape; supports supplementary code points (emoji)."
    (is (= [[:char (String. (Character/toChars 0x1F600))]]
           (vals-of (lex/tokenize "'\\u{1F600}'"))))))

(deftest f6-empty-char-rejected
  (testing "F6 — empty char literal `''` rejected with a helpful message."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"empty char literal"
         (lex/tokenize "''")))))

(deftest f6-multi-char-rejected
  (testing "F6 — `'ab'` rejected — char literal holds exactly one scalar."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"char literal must contain exactly one character"
         (lex/tokenize "'ab'")))))

(deftest f6-unknown-escape-rejected
  (testing "F6 — unknown escape (`\\q`) is a hard error, not silently dropped."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown escape sequence"
         (lex/tokenize "\"a\\qb\"")))))

(deftest f6-string-hex-byte-escape
  (testing "F6 — string literals share the extended escape set (\\xNN, \\u{...})."
    (is (= [[:string "A B"]]  (vals-of (lex/tokenize "\"A\\x20B\""))))))

(deftest f6-string-unicode-escape
  (testing "F6 — `\\u{...}` in strings supports supplementary code points."
    (is (= [[:string (str "hi " (String. (Character/toChars 0x1F44B)))]]
           (vals-of (lex/tokenize "\"hi \\u{1F44B}\""))))))
