;; Oracle for the mangle::->c-safe port.
;;
;; Reads the ambient table file from the CXC_TABLE_PATH env var,
;; builds an in-memory map `{name -> keyword-module}`, then reads
;; query names from stdin one per line and applies the live
;; cxc.resolve/mangled-name-for.
;;
;; Not-found names return verbatim, matching cxc.mangle/->c-safe's
;; falsey-ambient-or-missing-key branch.

(require 'cxc.resolve)
(require '[clojure.string :as str])

(let [table-path (System/getenv "CXC_TABLE_PATH")
      content    (slurp table-path)
      rows       (filter (complement str/blank?)
                         (str/split content #"\n"))
      table      (into {}
                       (map (fn [r]
                              (let [[nm m] (str/split r #"\|" 2)]
                                [nm (keyword m)]))
                            rows))]
  (doseq [q (line-seq (java.io.BufferedReader. *in*))]
    (if (contains? table q)
      (println (cxc.resolve/mangled-name-for (get table q) q))
      (println q))))
