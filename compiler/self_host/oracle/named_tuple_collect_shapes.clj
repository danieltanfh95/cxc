;; Oracle for cxc.named-tuple/collect-shapes.
;;
;; Each stdin line is a `;`-separated list of serialized type
;; expressions (`<prim>`, `Nn_<prim>`, or `Nt<count>_<body>` with
;; pre-sorted flat body). The oracle parses each ser into the
;; matching `:type-ref` / `:type-nonnil` / `:type-named-tuple` map,
;; wraps the whole seq in a root sequence, and calls collect-shapes.
;; The returned map's keys are sorted and comma-joined.
;;
;; SHALLOW scope. Nested `:type-named-tuple` fields are not in the
;; corpus. The body parser walks pair-by-pair recognising a leading
;; `Nn_` prefix on the ser side, so `Nt2_x_Nn_Int_y_String` decodes
;; cleanly without the naive `str/split body #"_"` partition
;; (which would shatter the `Nn_` tokens).

(require 'cxc.named-tuple)
(require '[clojure.string :as str])

(let [parse-prim (fn [p]
                   (if (str/starts-with? p "Nn_")
                     {:node :type-nonnil
                      :inner {:node :type-ref :name (subs p 3)}}
                     {:node :type-ref :name p}))
      parse-nt-body (fn parse-nt-body [body]
                      (loop [b      body
                             fields []]
                        (if (empty? b)
                          fields
                          (let [nus    (str/index-of b "_")
                                nm     (subs b 0 nus)
                                rest-b (subs b (inc nus))
                                is-nn  (str/starts-with? rest-b "Nn_")
                                tail   (if is-nn (subs rest-b 3) rest-b)
                                sep    (str/index-of tail "_")
                                prim   (if sep (subs tail 0 sep) tail)
                                next-b (if sep (subs tail (inc sep)) "")
                                tnode  (if is-nn
                                         {:node :type-nonnil
                                          :inner {:node :type-ref :name prim}}
                                         {:node :type-ref :name prim})]
                            (recur next-b
                                   (conj fields {:name nm :type tnode}))))))
      parse-ser (fn [s]
                  (if (str/starts-with? s "Nt")
                    (let [us   (str/index-of s "_")
                          body (subs s (inc us))]
                      {:node :type-named-tuple
                       :fields (parse-nt-body body)})
                    (parse-prim s)))]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [entries (if (empty? ln) [] (str/split ln #";"))
          nodes   (mapv parse-ser entries)
          shapes  (cxc.named-tuple/collect-shapes nodes)
          sorted  (sort (keys shapes))]
      (println (str/join "," sorted)))))
