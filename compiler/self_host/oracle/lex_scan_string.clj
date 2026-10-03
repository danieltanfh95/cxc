;; Oracle for the lex::scan-string ASCII no-escape port.
;;
;; Requires cxc.lex, derefs the private scan-string var, applies it
;; with i=0, line=1, col=1 and prints the result as `<body>|<end>`
;; matching the cxc port's single-line format.

(require 'cxc.lex)

(let [scan (deref #'cxc.lex/scan-string)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [[body end-idx] (scan ln 0 1 1)]
      (println (str body "|" end-idx)))))
