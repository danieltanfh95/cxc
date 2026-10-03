;; Oracle for the chapter-13 standalone tokenize-with-layout slice.
;; Input per line. One cxc source snippet with `\\n` for physical
;; newlines. Oracle expands then calls cxc.lex/tokenize.
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
  (let [src (clojure.string/replace ln "\\n" "\n")]
    (try
      (let [toks (cxc.lex/tokenize src)]
        (println (clojure.string/join "/" (map ser-tok toks))))
      (catch Exception e
        (println (str "lexerror|" (.getMessage e)))))))

)
