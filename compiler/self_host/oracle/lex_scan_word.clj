;; Oracle for the lex::scan-word ASCII port.
;;
;; Requires cxc.lex, derefs the private scan-word var, applies it
;; starting at offset 0 and prints the result as `<word>|<end>`
;; matching the cxc port's single-line format.

(require 'cxc.lex)

(let [scan (deref #'cxc.lex/scan-word)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [[word end-idx] (scan ln 0)]
      (println (str word "|" end-idx)))))
