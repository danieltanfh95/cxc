;; Oracle for the chapter-13 standalone tokenize-with-layout slice.
;; Input per line. One cxc source snippet with `\\n` for physical
;; newlines. Oracle expands then calls cxc.lex/tokenize.
;;
;; Chapter-23 sentinel. `\\n` in a corpus line is always a physical
;; newline, so a source-level backslash-n escape is spelled `\\N` in the
;; corpus and rewritten to the two chars `\\n` after the newline
;; expansion. `\\N` is never legal cxc source, so the sentinel cannot
;; collide with a real row. The port applies the same two rewrites.
;;
;; Output per input line. One output line listing all tokens
;; separated by `/`. Each token serialized as
;;   <kind>|<pr-str value>|<line>|<col>
;; so layout tokens emit `nil` as the value field.

(require 'cxc.lex)
(require 'clojure.string)

(do

(defn- ser-tok [t]
  (str (name (first t)) "|"
       (pr-str (second t)) "|"
       (nth t 2) "|"
       (nth t 3)))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [src (-> ln
                  (clojure.string/replace "\\n" "\n")
                  (clojure.string/replace "\\N" "\\n"))]
    (try
      (let [toks (cxc.lex/tokenize src)]
        (println (clojure.string/join "/" (map ser-tok toks))))
      (catch Exception e
        (println (str "lexerror|" (.getMessage e)))))))

)
