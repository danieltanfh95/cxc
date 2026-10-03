;; Oracle for cxc.resolve/build-symbol-table.
;;
;; Each stdin line is a `;`-separated list of `<kind>:<name>` entries.
;; kind ∈ {module, record, def-atom, enum, other}. The oracle maps
;; each entry to the matching AST node shape:
;;
;;   module    → {:node :module :name <name>}
;;   record    → {:node :record :name <name> :sections []}
;;   def-atom  → {:node :def-atom :name <name> :body []}
;;   enum      → {:node :enum :name <name> :cases []}
;;   other     → {:node :literal :value <name>} (not a declaration)
;;
;; Build-symbol-table is called on the resulting seq; the oracle
;; then prints the `<name>=<module>` entries sorted by name, comma-
;; joined. The corpus avoids declaration names that match
;; `prelude-name->module` so the oracle's prelude attribution never
;; competes with the current-module tracking.

(require 'cxc.resolve)
(require '[clojure.string :as str])

(let [make-node (fn [k nm]
                  (case k
                    "module"   {:node :module :name nm}
                    "record"   {:node :record :name nm :sections []}
                    "def-atom" {:node :def-atom :name nm :body []}
                    "enum"     {:node :enum :name nm :cases []}
                    "other"    {:node :literal :value nm}
                    nil))]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [entries (if (empty? ln) [] (str/split ln #";"))
          nodes   (keep (fn [e]
                          (let [parts (str/split e #":" 2)]
                            (make-node (first parts) (or (second parts) ""))))
                        entries)
          table   (cxc.resolve/build-symbol-table nodes)
          sorted  (sort-by key table)
          pairs   (map (fn [[nm entry]]
                         (str nm "=" (name (:module entry))))
                       sorted)]
      (println (str/join "," pairs)))))
