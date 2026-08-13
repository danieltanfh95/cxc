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
