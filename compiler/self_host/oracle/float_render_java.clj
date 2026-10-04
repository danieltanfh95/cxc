;; Oracle for the chapter-25 float renderer slice (slice 34).
;;
;; Input per line. A well-formed decimal float literal, `_` separators
;; allowed. Output per line. `(str (Double/parseDouble lit))` with the
;; separators stripped, which is the Java `Double.toString` text the
;; Clojure compiler writes for a `:float` node in `cxc.emit/emit-expr`
;; and the parse oracle prints for a `:lit-float` default.

(require 'clojure.string)

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (println (str (Double/parseDouble (clojure.string/replace ln "_" "")))))
