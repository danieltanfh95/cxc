;; Oracle for the lex::match-multi-glyph port.
;;
;; Reads tab-separated lines from stdin, each `<source>\t<index>`,
;; applies the live cxc.lex/match-multi-glyph (private, via var),
;; writes the matched glyph or an empty line on no match.

(require 'cxc.lex)
(require '[clojure.string :as str])

(let [match-fn (deref #'cxc.lex/match-multi-glyph)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [[s idx] (str/split ln #"\t" 2)
          i (Integer/parseInt (or idx "0"))
          hit (match-fn (or s "") i)]
      (println (or hit "")))))
