;; Oracle for cxc.lex/scan-string — ASCII body + simple + \xNN
;; escapes (DECODED output).
;;
;; Dereferences the private scan-string and calls it with (ln 0 1 1).
;; Emits `<decoded-body>|<end-index>` on success, `ERR` on any
;; exception. The decoded body is included verbatim in the output
;; line (a `\n` in the decoded body becomes a literal newline
;; in the output file; both sides emit identically).
;;
;; Out of scope. The Clojure scan-string accepts \u{...}, \0, \a,
;; \b, \f, \v — the corpus avoids these so the port's `ERR` doesn't
;; clash with Clojure's successful decode.

(require 'cxc.lex)

(let [scan (deref #'cxc.lex/scan-string)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [[decoded end] (scan ln 0 1 1)]
        (println (str decoded "|" end)))
      (catch Exception _
        (println "ERR")))))
