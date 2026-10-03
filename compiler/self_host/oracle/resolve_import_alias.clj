;; Oracle for the resolve::import-source-map alias-expansion slice.
;;
;; Parses each line as `<kind>|<mod>|<sym>|<alias>`, synthesises a
;; `:from-import` or `:import` AST node, feeds a one-element seq into
;; cxc.resolve/import-source-map, and prints the sole `<local>=<mod>`
;; pair the fn returns.

(require 'cxc.resolve)
(require '[clojure.string :as str])

(let [im-src (deref #'cxc.resolve/import-source-map)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [parts (str/split ln #"\|" 4)
            kind  (nth parts 0 "")
            mod   (nth parts 1 "")
            sym   (nth parts 2 "")
            als   (nth parts 3 "")
            stmt  (if (= kind "F")
                    {:node :from-import
                     :from mod
                     :names [(cond-> {:name sym}
                               (pos? (count als)) (assoc :as als))]}
                    (cond-> {:node :import :from mod}
                      (pos? (count als)) (assoc :as als)))
            m     (im-src [stmt])
            [k v] (first m)]
        (println (str k "=" (name v))))
      (catch Exception _
        (println "ERR")))))
