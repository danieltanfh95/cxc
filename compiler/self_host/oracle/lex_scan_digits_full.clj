;; Oracle for the full-coverage lex::scan-digits port.
;;
;; Derefs the private cxc.lex/scan-digits and calls it with
;; (ln 0 (count ln) 10 1). Prints `<digits>|<end-index>` on success,
;; `ERR` on any exception.

(require 'cxc.lex)

(let [scan-digits (deref #'cxc.lex/scan-digits)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [[d i] (scan-digits ln 0 (count ln) 10 1)]
        (println (str d "|" i)))
      (catch Exception _
        (println "ERR")))))
