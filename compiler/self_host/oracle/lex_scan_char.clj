;; Oracle for lex::scan-char ASCII no-escape port.
;;
;; Derefs the private cxc.lex/scan-char and calls it on each stdin
;; line at index 0 (line 1, col 1 for the throw-info only). Prints
;; `<payload>|<end-index>` on success, `ERR` on any exception.

(require 'cxc.lex)

(let [scan-char (deref #'cxc.lex/scan-char)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [[p i] (scan-char ln 0 1 1)]
        (println (str p "|" i)))
      (catch Exception _
        (println "ERR")))))
