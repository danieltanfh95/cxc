;; Oracle for the ASCII predicates port.
;;
;; Reads pipe-separated lines from stdin, each `<kind>|<code>`,
;; calls Character/digit (int-code variant) or Character/isWhitespace
;; and prints the result. Covers the four kinds the cxc port
;; implements, digit10, digit16, digit2, whitespace.

(require '[clojure.string :as str])

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [[kind c-str] (str/split ln #"\|" 2)
        c (Integer/parseInt c-str)]
    (println
     (case kind
       "digit10"    (str (Character/digit c 10))
       "digit16"    (str (Character/digit c 16))
       "digit2"     (str (Character/digit c 2))
       "whitespace" (str (Character/isWhitespace c))
       "ERROR"))))
