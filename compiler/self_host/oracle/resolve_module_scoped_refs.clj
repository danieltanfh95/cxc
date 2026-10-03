;; Oracle for cxc.resolve/module-scoped-ref-names.
;;
;; Each stdin line is a `;`-separated list of `<kind>:<name>` entries,
;; where kind ∈ {type-ref, call, dot, extend, type-of, ident, other}.
;; The oracle synthesises the corresponding AST shape per entry,
;; feeds the full seq into the private walker, and prints the sorted
;; distinct names comma-joined.

(require 'cxc.resolve)
(require '[clojure.string :as str])

(let [walker (deref #'cxc.resolve/module-scoped-ref-names)
      make-node (fn [k nm]
                  (case k
                    "type-ref" {:node :type-ref :name nm}
                    "call"     {:node :call :callee {:node :ident :name nm}}
                    "dot"      {:node :dot :target {:node :ident :name nm}}
                    "extend"   {:node :extend-class :target nm}
                    "type-of"  {:node :type-of :outer nm}
                    "ident"    {:node :ident :name nm}
                    "other"    {:node :literal :value nm}
                    nil))]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [entries (if (empty? ln) [] (str/split ln #";"))
          nodes   (keep (fn [e]
                          (let [parts (str/split e #":" 2)]
                            (make-node (first parts) (or (second parts) ""))))
                        entries)
          names   (walker nodes)
          sorted  (sort names)]
      (println (str/join "," sorted)))))
