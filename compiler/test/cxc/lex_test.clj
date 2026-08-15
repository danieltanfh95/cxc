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

(deftest multi-char-glyphs
  (testing "all cxc wire glyphs recognized as single tokens"
    (doseq [g ["<:" "<|" "|>" ":>" ":<" "->" "=>" "==" "!=" "<=" ">="]]
      (let [toks (vals-of (lex/tokenize (str "a " g " b")))]
        (is (= [:glyph g] (nth toks 1))
            (str "glyph " g " should lex as :glyph"))))))

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
