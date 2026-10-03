;; Oracle for cxc.lex/scan-number — decimal int + float scope.
;;
;; Dereferences the private scan-number and calls it with (ln 0 1).
;; Emits:
;;   `int|<decimal-value>|<end>`              on :int
;;   `float|<literal-text-cursor-run>|<end>`  on :float
;;   `ERR`                                    on any exception
;;
;; The float column emits the LITERAL input substring (ln[0..end]),
;; not the rendered Double, so the port can parity-check shape +
;; end-index without matching Java vs Foundation float-string
;; renderings. Clojure's `pr-str (Double/parseDouble s)` renders
;; `1.5` as `"1.5"` but `1e5` as `"100000.0"` and `0.1` as `"0.1"` —
;; the port's `.to_double_or(x).to_string()` would render `1e5` as
;; `"100000.000000"` and `0.1` as `"0.100000"` under NSString `%f`.
;; Rather than add a Java-shape double renderer, the parity harness
;; checks shape + end + literal; actual round-trip precision is
;; verified separately via the smoke test on the compiler landing.

(require 'cxc.lex)

(let [scan (deref #'cxc.lex/scan-number)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [[end kind _value] (scan ln 0 1)]
        (case kind
          :int    (println (str "int|" _value "|" end))
          :float  (println (str "float|" (subs ln 0 end) "|" end))
          (println "ERR")))
      (catch Exception _
        (println "ERR")))))
