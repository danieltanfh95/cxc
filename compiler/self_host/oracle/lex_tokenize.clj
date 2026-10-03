;; Oracle for the lex::tokenize composed port.
;;
;; Requires cxc.lex, derefs the private tokenize-line var, applies it
;; with line-no 1. Filters tokens to the three kinds the cxc port
;; emits (ident, kw, int), formats each as `<kind>:<value>@<col>`,
;; joins with `;`. Prints one line per input.

(require 'cxc.lex)
(require '[clojure.string :as str])

(let [tok-line (deref #'cxc.lex/tokenize-line)]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [tokens (tok-line ln 1)
          filtered (filter (fn [[k _v _l _c]]
                             (contains? #{:ident :kw :int} k))
                           tokens)
          formatted (map (fn [[k v _l c]]
                           (str (name k) ":" v "@" c))
                         filtered)]
      (println (str/join ";" formatted)))))
