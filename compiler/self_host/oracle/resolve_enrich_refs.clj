;; Oracle for cxc.resolve/enrich-node-with-resolution — b.ii
;; enrichment slice.
;;
;; For each ref entry (slice-19 DSL `<kind>:<name>`), synthesize the
;; AST node, run the private enrich-node-with-resolution against the
;; file scope, and emit `<kind>:<name>:<resolve-result>` where
;; resolve-result is the attached :resolves-to module name, `NIL`
;; when enrichment leaves the node unchanged BUT the ref kind is
;; contributing (unresolved), or `SKIP` when the ref kind doesn't
;; participate in L3b resolution (lowercase ident at :call /:dot
;; /:type-of position, or :ident / :other).
;;
;; Input per line: same six-field layout as slice 25.
;;
;; Output per ref entry: one line, newline-joined.

(require 'cxc.resolve)
(require '[clojure.string :as str])

(let [enrich     (deref #'cxc.resolve/enrich-node-with-resolution)
      cap?       (fn [s]
                   (and (string? s)
                        (pos? (count s))
                        (let [c (int (.charAt ^String s 0))]
                          (and (>= c (int \A)) (<= c (int \Z))))))
      contributes? (fn [k nm]
                     (case k
                       "type-ref" true
                       "extend"   true
                       "call"     (cap? nm)
                       "dot"      (cap? nm)
                       "type-of"  (cap? nm)
                       false))
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
      extract    (fn [k node]
                   (case k
                     ("type-ref" "extend" "type-of") (:resolves-to node)
                     "call"                           (:resolves-to (:callee node))
                     "dot"                            (:resolves-to (:target node))
                     nil))
      parse-set  (fn [s] (into #{} (filter seq (str/split s #","))))
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
          decls   (parse-set (nth parts 2 ""))
          imports (parse-map (nth parts 3 ""))
          prelude (parse-map (nth parts 4 ""))
          refs-s  (nth parts 5 "")
          entries (if (empty? refs-s) [] (str/split refs-s #";"))
          lines   (keep (fn [e]
                          (let [pp     (str/split e #":" 2)
                                k      (first pp)
                                nm     (or (second pp) "")
                                tail   (if-not (contributes? k nm)
                                         "SKIP"
                                         (let [node    (make-node k nm)
                                               enriched (enrich node
                                                                (keyword mod-s)
                                                                decls
                                                                imports
                                                                prelude)
                                               mod     (extract k enriched)]
                                           (if mod (name mod) "NIL")))]
                            (str k ":" nm ":" tail)))
                        entries)]
      (println (str/join "\n" lines)))))
