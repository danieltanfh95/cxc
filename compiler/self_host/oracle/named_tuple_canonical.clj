;; Oracle for named_tuple_canonical.
;;
;; Parses each stdin line as `<f>|<m>[;<f>|<m>...]`, synthesises a
;; `:type-named-tuple` AST node, and prints the result of
;; cxc.named-tuple/canonical-named-tuple-name — catching any
;; validation exception and printing `INVALID` for parity with the
;; cxc port's single-marker invalid signal. Empty lines also print
;; `INVALID` because the Clojure fn throws on an empty :fields list.
;;
;; Mangle parse: a leading `Nn_` splits into `:type-nonnil` wrapping
;; a `:type-ref` of the stripped remainder; otherwise the whole
;; string is the `:type-ref` name.
;;
;; Helpers are inlined as `let` bindings so clj -M -e doesn't print
;; var references (`#'user/parse-mangle`) at top level.

(require 'cxc.named-tuple)
(require '[clojure.string :as str])

(let [parse-mangle (fn [m]
                     (if (str/starts-with? m "Nn_")
                       {:node :type-nonnil
                        :inner {:node :type-ref :name (subs m 3)}}
                       {:node :type-ref :name m}))
      parse-field  (fn [p]
                     (let [parts (str/split p #"\|" 2)
                           n     (first parts)
                           m     (second parts)]
                       (when-not (and n m)
                         (throw (ex-info "pair missing `|` separator"
                                         {:pair p})))
                       {:name n :type (parse-mangle m)}))]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (if (= ln "")
      (println "INVALID")
      (try
        (let [pairs  (str/split ln #";")
              fields (vec (map parse-field pairs))
              shape  {:node :type-named-tuple :fields fields}]
          (println (cxc.named-tuple/canonical-named-tuple-name shape)))
        (catch Exception _
          (println "INVALID"))))))
