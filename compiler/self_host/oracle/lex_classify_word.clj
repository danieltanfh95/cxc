;; Oracle for the lex::classify-word port.
;;
;; Requires cxc.lex, reads the private classify-word via var, applies
;; it to each stdin line, writes "kw" or "ident" per line.

(require 'cxc.lex)

(let [classify (deref #'cxc.lex/classify-word)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (println (name (classify ln)))))
