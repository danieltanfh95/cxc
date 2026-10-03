;; Oracle for cxc.resolve/resolve-name-to-module (four-way lookup).
;;
;; Reads lines formatted as `<name>|<own-mod>|<own-decls>|<imports>|<prelude>`.
;; `<own-decls>` is a comma-separated set, `<imports>` and `<prelude>`
;; are comma-separated `name=module` pairs. Dereferences the private
;; resolve-name-to-module var and prints the resolved module name
;; (or `NIL`) per line.

(require 'cxc.resolve)
(require '[clojure.string :as str])

(let [resolve-fn (deref #'cxc.resolve/resolve-name-to-module)
      parse-set  (fn [s]
                   (into #{}
                         (filter seq (str/split s #","))))
      parse-map  (fn [s]
                   (into {}
                         (keep (fn [kv]
                                 (let [parts (str/split kv #"=" 2)]
                                   (when (and (first parts)
                                              (seq (first parts))
                                              (second parts))
                                     [(first parts) (keyword (second parts))]))))
                         (filter seq (str/split s #","))))]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [parts (str/split ln #"\|" 5)
          nm    (nth parts 0 "")
          own   (nth parts 1 "")
          decls (parse-set (nth parts 2 ""))
          imps  (parse-map (nth parts 3 ""))
          prel  (parse-map (nth parts 4 ""))
          r     (resolve-fn nm (keyword own) decls imps prel)]
      (println (if r (name r) "NIL")))))
