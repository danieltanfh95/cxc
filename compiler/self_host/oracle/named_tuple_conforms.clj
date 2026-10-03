;; Oracle for named_tuple_conforms.
;;
;; Parses each stdin line as "<value-fields>#<required-fields>" with
;; `;`-separated `<name>|<type>` pairs on each side, synthesises
;; corresponding field maps, and calls
;; cxc.named-tuple/structural-conforms?. Emits one of:
;;   OK                                     — conforms
;;   MISS:<field>                           — required field absent on value side
;;   WRONG:<field>:<expected>:<got>         — required field's type differs
;; where <expected> and <got> echo the input mangle strings
;; (`Int` / `Nn_Int` / …). The oracle intentionally drops the Clojure
;; result's `:available` list from the output so the cxc port doesn't
;; need an extra sort pass for parity.

(require 'cxc.named-tuple)
(require '[clojure.string :as str])

(let [parse-mangle (fn [m]
                     (if (str/starts-with? m "Nn_")
                       {:node :type-nonnil
                        :inner {:node :type-ref :name (subs m 3)}}
                       {:node :type-ref :name m}))
      fmt-type     (fn [t]
                     (if (= :type-nonnil (:node t))
                       (str "Nn_" (:name (:inner t)))
                       (:name t)))
      parse-side   (fn [side]
                     (if (empty? side)
                       []
                       (vec (map (fn [p]
                                   (let [parts (str/split p #"\|" 2)
                                         n (first parts)
                                         m (second parts)]
                                     (when-not (and n m)
                                       (throw (ex-info "pair missing `|`"
                                                       {:pair p})))
                                     {:name n :type (parse-mangle m)}))
                                 (str/split side #";")))))]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [parts (str/split ln #"#" 2)
            v    (parse-side (first parts))
            r    (parse-side (or (second parts) ""))
            res  (cxc.named-tuple/structural-conforms? v r)]
        (cond
          (:conforms res)
          (println "OK")

          (= :missing-field (:reason res))
          (println (str "MISS:" (:field res)))

          (= :wrong-type (:reason res))
          (println (str "WRONG:" (:field res) ":"
                        (fmt-type (:expected res)) ":"
                        (fmt-type (:got res))))

          :else
          (println "UNKNOWN")))
      (catch Exception _
        (println "ERR")))))
