;; Oracle for the parse-layer bootstrap — terminal subset per
;; compiler/self_host/design/parse-layer-bootstrap.md.
;;
;; Input per line. A cxc source snippet with `\\n` sequences
;; representing physical newlines; the oracle expands them to real
;; newlines before calling cxc.parse/parse.
;;
;; Output line format (one serialized node per input):
;;   module|<name>
;;   fromimport|<from>|<n1>[=<as1>],<n2>[=<as2>],...
;;   defrecord|<name>|<f-ser>;...
;;   defatom|<name>|<ctor-params-ser>|<field-ser>;...
;;   defenum|<name>|<case-ser>[;<case-ser>...]
;;   parseerror|<message>
;;
;; Type serialization. TR:<name> | TN(<inner>) | TO:List(<inner>) |
;;   TO:Map(TT(<K>,<V>)) | TO:Set(<inner>)

;; Wrapped in `do` so `clj -M -e` emits only the per-line println
;; output, not the `#'user/ser-type` var-ref prints the top-level
;; defn forms would otherwise produce.

(require 'cxc.parse)
(require 'clojure.string)

(do

(defn- ser-type [t]
  (case (:node t)
    :type-ref     (str "TR:" (:name t))
    :type-nonnil  (str "TN(" (ser-type (:inner t)) ")")
    :type-of      (str "TO:" (:outer t) "(" (ser-type (:inner t)) ")")
    :type-tuple   (str "TT("
                       (clojure.string/join ","
                                            (map ser-type (:types t)))
                       ")")
    (str "T?:" (:node t))))

(defn- ser-default [d]
  (if (nil? d)
    ""
    (case (:node d)
      :lit-int    (str "=INT:" (:value d))
      :lit-string (str "=STR:" (pr-str (:value d)))
      :lit-char   (str "=CHR:" (pr-str (:value d)))
      :lit-bool   (str "=BOOL:" (:value d))
      :ident      (str "=IDENT:" (:name d))
      (str "=?:" (:node d)))))

(defn- ser-field [f]
  (str (:name f) ":" (ser-type (:type f)) (ser-default (:default f))))

(defn- ser-case [ce]
  (if (seq (:payload ce))
    (str (:name ce)
         "("
         (clojure.string/join ";" (map ser-field (:payload ce)))
         ")")
    (:name ce)))

(defn- ser-node [n]
  (case (:node n)
    :module       (str "module|" (:name n))
    :from-import  (str "fromimport|" (:from n) "|"
                       (clojure.string/join ","
                                            (map (fn [s]
                                                   (if (:as s)
                                                     (str (:name s) "=" (:as s))
                                                     (:name s)))
                                                 (:names n))))
    :record
    (let [fields (mapcat :fields
                         (filter #(= :data-section (:node %))
                                 (:sections n)))]
      (str "defrecord|" (:name n) "|"
           (clojure.string/join ";" (map ser-field fields))))
    :enum
    (str "defenum|" (:name n) "|"
         (clojure.string/join ";" (map ser-case (:case-entries n))))
    :def-atom
    (let [fields (mapcat :fields
                         (filter #(= :data-section (:node %)) (:body n)))]
      (str "defatom|" (:name n) "|"
           (clojure.string/join ";" (map ser-field (:ctor-params n)))
           "|"
           (clojure.string/join ";" (map ser-field fields))))
    (str "unknown|" (:node n))))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [src (clojure.string/replace ln "\\n" "\n")]
    (try
      (let [nodes (cxc.parse/parse src)]
        (if (empty? nodes)
          (println "")
          (println (clojure.string/join "\n" (map ser-node nodes)))))
      (catch Exception e
        (println (str "parseerror|" (.getMessage e)))))))

)
