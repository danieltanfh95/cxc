;; Oracle for cxc.named-tuple/collect-shapes.
;;
;; Each stdin line is a `;`-separated list of serialized type
;; expressions:
;;   `<prim>`              — primitive type-ref
;;   `Nn_<prim>`           — non-nil wrapper on a primitive
;;   `Nt<count>_<body>`    — named-tuple whose body is `<count>` field-type
;;                           pairs, each pair being `<fname>_<type>`.
;;                           The `<type>` inside a nested body may itself
;;                           be any of the three ser forms, so nesting
;;                           recurses unboundedly.
;;
;; The oracle parses each ser into the matching
;; `:type-ref` / `:type-nonnil` / `:type-named-tuple` map (recursive on
;; nested `Nt`), wraps the whole seq in a root sequence, and calls
;; `cxc.named-tuple/collect-shapes`. The returned map's keys are sorted
;; and comma-joined.
;;
;; Chapter 17 extended the parser from the chapter-8 shallow scope to
;; depth-N nesting via `parse-ser-rest` which returns both the parsed
;; node AND the unconsumed tail. `parse-nt-body-n` loops exactly
;; `<count>` pairs and threads the tail through each pair-parse, so
;; nested `Nt<K>_<inner>` sub-bodies consume exactly their declared
;; width and leave the surrounding parent-body intact.

(do
  (require 'cxc.named-tuple)
  (require '[clojure.string :as str])

  (declare parse-ser-rest)

  (defn- parse-nt-body-n [cnt body]
    (loop [k      cnt
           b      body
           fields []]
      (if (zero? k)
        [fields b]
        (let [nus    (str/index-of b "_")
              fname  (subs b 0 nus)
              rest1  (subs b (inc nus))
              [tnode rest2] (parse-ser-rest rest1)]
          (recur (dec k) rest2 (conj fields {:name fname :type tnode}))))))

  (defn- parse-ser-rest [s]
    (cond
      (str/starts-with? s "Nn_")
      (let [after (subs s 3)
            sep   (str/index-of after "_")
            prim  (if sep (subs after 0 sep) after)
            rest-s (if sep (subs after (inc sep)) "")]
        [{:node :type-nonnil :inner {:node :type-ref :name prim}} rest-s])

      (and (str/starts-with? s "Nt")
           (re-matches #"Nt\d+_.*" s))
      (let [m    (re-matches #"Nt(\d+)_(.*)" s)
            cnt  (Integer/parseInt (nth m 1))
            body (nth m 2)
            [fields rest-s] (parse-nt-body-n cnt body)]
        [{:node :type-named-tuple :fields fields} rest-s])

      :else
      (let [sep  (str/index-of s "_")
            prim (if sep (subs s 0 sep) s)
            rest-s (if sep (subs s (inc sep)) "")]
        [{:node :type-ref :name prim} rest-s])))

  (defn- parse-ser [s]
    (first (parse-ser-rest s)))

  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [entries (if (empty? ln) [] (str/split ln #";"))
          nodes   (mapv parse-ser entries)
          shapes  (cxc.named-tuple/collect-shapes nodes)
          sorted  (sort (keys shapes))]
      (println (str/join "," sorted)))))
