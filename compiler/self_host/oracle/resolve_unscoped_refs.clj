;; Oracle for cxc.resolve/find-unscoped-type-refs — composition slice.
;;
;; Mirrors the port's composition shape rather than driving
;; find-unscoped-type-refs with synthetic AST nodes. The composition
;; is slice-19's walker (module-scoped-ref-names) + slice-18's
;; resolver (resolve-name-to-module). Both port and oracle implement
;; the composition; parity means the two agree.
;;
;; Input per line: six positional fields separated by `|`.
;;   `<module>|<file>|<decls>|<imports>|<prelude>|<refs>`
;; `<decls>` is a comma-separated name set.
;; `<imports>` and `<prelude>` are comma-separated `name=module` pairs.
;; `<refs>` is a `;`-separated list of `<kind>:<name>` entries matching
;; the slice 19 DSL.
;;
;; Output per unscoped ref: `<module>|<file>|<name>` on its own line,
;; alphabetical by name. No unscoped refs → one empty-string line
;; (println of nothing is one newline; port emits "" via console.write
;; so no trailing newline. Harness runs both through the same bash
;; echo path).

(require 'cxc.resolve)
(require '[clojure.string :as str])

(let [walker     (deref #'cxc.resolve/module-scoped-ref-names)
      resolve-fn (deref #'cxc.resolve/resolve-name-to-module)
      make-node  (fn [k nm]
                   (case k
                     "type-ref" {:node :type-ref :name nm}
                     "call"     {:node :call :callee {:node :ident :name nm}}
                     "dot"      {:node :dot :target {:node :ident :name nm}}
                     "extend"   {:node :extend-class :target nm}
                     "type-of"  {:node :type-of :outer nm}
                     "ident"    {:node :ident :name nm}
                     "other"    {:node :literal :value nm}
                     nil))
      parse-set  (fn [s]
                   (into #{} (filter seq (str/split s #","))))
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
    (let [parts   (str/split ln #"\|" 6)
          mod-s   (nth parts 0 "")
          file-s  (nth parts 1 "")
          decls   (parse-set (nth parts 2 ""))
          imports (parse-map (nth parts 3 ""))
          prelude (parse-map (nth parts 4 ""))
          refs-s  (nth parts 5 "")
          entries (if (empty? refs-s) [] (str/split refs-s #";"))
          nodes   (keep (fn [e]
                          (let [pp (str/split e #":" 2)]
                            (make-node (first pp) (or (second pp) ""))))
                        entries)
          names   (walker nodes)
          unscoped (remove #(resolve-fn % (keyword mod-s) decls imports prelude)
                           names)
          sorted  (sort unscoped)
          out-str (str/join "\n"
                            (map (fn [nm] (str mod-s "|" file-s "|" nm))
                                 sorted))]
      ;; One `println` per corpus line (even when zero unscoped) so
      ;; the oracle emits a trailing newline that matches the port's
      ;; `puts()` behavior for every input line.
      (println out-str))))
