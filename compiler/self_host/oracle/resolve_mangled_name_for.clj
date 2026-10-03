;; Oracle for the resolve::mangled-name-for port.
;;
;; Reads tab-separated lines from stdin, each `<module>\t<name>`,
;; converts the empty-string module to nil (parity with the live
;; namespace which takes an optional keyword), and applies
;; cxc.resolve/mangled-name-for.

(require 'cxc.resolve)
(require '[clojure.string :as str])

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [[m n] (str/split ln #"\|" 2)
        mod (when (and m (not= m "")) (keyword m))
        nm  (or n "")]
    (println (cxc.resolve/mangled-name-for mod nm))))
