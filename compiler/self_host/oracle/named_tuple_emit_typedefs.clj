;; Oracle for cxc.named-tuple/emit-typedefs (shallow scope).
;;
;; Each stdin line is a serialized ser. For a `Nt<count>_<body>`
;; shape, the oracle parses it into a `:type-named-tuple` AST,
;; wraps it in a {canonical → shape} map, calls emit-typedefs with
;; a primitive→c-type fn derived from argv-driven mapping, and
;; prints the result. For any non-shape input the oracle prints
;; an empty line. The mapping is read from argv (not stdin) so
;; it stays constant across the corpus.
;;
;; The port output and the oracle output are both multi-line; the
;; harness diffs them byte-for-byte.

(require 'cxc.named-tuple)
(require '[clojure.string :as str])

(let [mapping-str (or (System/getenv "CXC_PRIM_MAPPING") "")
      mapping     (into {}
                        (map (fn [kv]
                               (let [[k v] (str/split kv #"=" 2)]
                                 [k v])))
                        (filter seq (str/split mapping-str #",")))
      prim->c     (fn [p]
                    (or (get mapping p)
                        (throw (ex-info (str "no c-type for " p) {}))))
      parse-prim  (fn [p]
                    (if (str/starts-with? p "Nn_")
                      {:node :type-nonnil
                       :inner {:node :type-ref :name (subs p 3)}}
                      {:node :type-ref :name p}))
      parse-body  (fn [body]
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
                                 (conj fields {:name nm :type tnode}))))))]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (if (and (str/starts-with? ln "Nt") (>= (count ln) 4))
      (let [us    (str/index-of ln "_")
            body  (subs ln (inc us))
            node  {:node :type-named-tuple :fields (parse-body body)}
            name  (cxc.named-tuple/canonical-named-tuple-name node)
            shapes (sorted-map name node)
            text  (cxc.named-tuple/emit-typedefs shapes prim->c)]
        (println text))
      (println ""))))
