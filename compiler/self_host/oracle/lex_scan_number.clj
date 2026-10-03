;; Oracle for cxc.lex/scan-number — decimal + hex integer scope.
;;
;; Dereferences the private scan-number and calls it with
;; (ln 0 1). On success emits `int|<value>|<end>` when kind is :int,
;; or ERR when kind is :float (out of scope for the port). On any
;; exception the oracle emits ERR.

(require 'cxc.lex)

(let [scan (deref #'cxc.lex/scan-number)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [[end kind value] (scan ln 0 1)]
        (if (= kind :int)
          (println (str "int|" value "|" end))
          (println "ERR")))
      (catch Exception _
        (println "ERR")))))
