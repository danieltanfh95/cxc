;; Oracle for cxc.lex/tokenize-line — full composition slice.
;;
;; Dereferences the private tokenize-line + per-token scanners. For
;; :string / :char / :float tokens the token value is DECODED (string
;; contents + Double value), but the port emits LITERAL substring
;; form. The oracle re-extracts the literal from the source using the
;; relevant scanner's end-index, so both sides agree byte-for-byte.
;;
;; Output format mirrors the port:
;;   `<kind>|<value>|<col>` per token, one per line.
;;   kw / ident / glyph / punct   value = token value (string)
;;   int                           value = decimal rendering of long
;;   float / string / char         value = literal substring
;;
;; `#` comments terminate the token stream (Clojure tokenize-line
;; stops at #). Blank line → blank output line.

(require 'cxc.lex)

(let [tokenize-line (deref #'cxc.lex/tokenize-line)
      scan-string   (deref #'cxc.lex/scan-string)
      scan-char     (deref #'cxc.lex/scan-char)
      scan-number   (deref #'cxc.lex/scan-number)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [tokens (tokenize-line ln 1)
            rendered
            (map (fn [[kind value _line col]]
                   (let [start-idx (dec col)
                         literal
                         (case kind
                           :string (let [[_ end] (scan-string ln start-idx 1 col)]
                                     (subs ln start-idx end))
                           :char   (let [[_ end] (scan-char   ln start-idx 1 col)]
                                     (subs ln start-idx end))
                           :float  (let [[end _ _] (scan-number ln start-idx 1)]
                                     (subs ln start-idx end))
                           :int    (str value)
                           (str value))]
                     (str (name kind) "|" literal "|" col)))
                 tokens)]
        (println (clojure.string/join "\n" rendered)))
      (catch Exception _
        (println "ERR")))))
