;; Oracle for lex::scan-string with escape sequences.
;;
;; Dereferences the private scan-string, calls with (ln 0 1 1), and
;; prints `<payload>|<end-index>` on success. The payload is
;; re-encoded (literal backslash + letter form) so a decoded newline
;; or tab in the string doesn't break the one-line-per-record wire
;; format the port emits. The port keeps the escape in literal form
;; so both sides match on byte-equal re-encoded output.
;;
;; `ERR` on any exception.

(require 'cxc.lex)
(require '[clojure.string :as str])

(let [re-encode (fn [s]
                  (-> s
                      (str/replace "\\" "\\\\")
                      (str/replace "\n" "\\n")
                      (str/replace "\t" "\\t")
                      (str/replace "\r" "\\r")
                      (str/replace "\"" "\\\"")
                      (str/replace "'"  "\\'")))
      scan      (deref #'cxc.lex/scan-string)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [[payload end] (scan ln 0 1 1)]
        (println (str (re-encode payload) "|" end)))
      (catch Exception _
        (println "ERR")))))
