;; Oracle for the scan-digits decimal port.
;;
;; Requires cxc.lex, derefs the private scan-digits var, applies it
;; with base 10 and the whole input as the scan range. Prints the
;; first element of the returned [digits end-index] vector.
;;
;; Scoped to the well-formed subset per the port contract. Malformed
;; underscores throw inside Clojure and are intentionally not in the
;; corpus.

(require 'cxc.lex)

(let [scan (deref #'cxc.lex/scan-digits)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [[digits _end-idx] (scan ln 0 (count ln) 10 1)]
      (println digits))))
