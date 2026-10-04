;; Oracle for the chapter-31 symbol-table slice (slice 37,
;; `resolve_program_symbols.cxc`).
;;
;; Input per line. One whole cxc source file in the chapter-23 corpus
;; convention (`\\n` a physical newline, `\\N` the two chars
;; backslash-n), the same rows the slice-36 harness feeds.
;;
;; Reference. The public `cxc.parse/parse`, then the public
;; `cxc.resolve/build-symbol-table` over its node vector. When the env
;; var CXC_RESOLVE_MODULE is set and not empty, a `{:node :module :name
;; <m>}` marker goes ahead of the nodes, as
;; `build/symbol-table-across-modules` does for each file.
;;
;; Output per input line. `error|<message>` when lex or parse throws,
;; else `(table <n>)` and one row per table entry sorted by name.
;;
;;   (entry <name> <module> record struct|class (fields <field>*) (observables <obs>*))
;;   (entry <name> <module> def-atom nil (data_fields <field>*) (observables <obs>*)
;;       (transitions <transition>*) (use_caps (use <n> <T>)*) (invariants <prop>*)
;;       (handler_decls <item>*))
;;   (entry <name> <module> enum nil (cases <case-name>*))
;;
;; Refs mode. When CXC_RESOLVE_MODE is `refs`, the oracle runs the
;; private `cxc.resolve/resolve-references-in-file` on `{:module m
;; :nodes nodes}` the way `build/compile-source-to-file` does, with
;; m the CXC_RESOLVE_MODULE name or `main` when it is empty, and the
;; own-decl set `decls-in-file` gives for the file. It then walks the
;; enriched tree (`tree-seq` over maps and sequentials) and prints
;; `(refs <n>)` and one row per distinct reference, sorted.
;;
;;   (ref type <name> <module>|nil)    a :type-ref
;;   (ref call <name> <module>|nil)    a :call whose callee is a capitalized :ident
;;   (ref dot <name> <module>|nil)     a :dot whose target is a capitalized :ident
;;   (ref extend <name> <module>|nil)  an :extend-class target
;;   (ref of <name> <module>|nil)      a :type-of with a capitalized :outer
;;
;; <module> is the `:resolves-to` the enrichment attached, nil when it
;; attached none. Resolution depends only on the name, so distinct rows
;; are distinct (kind, name) pairs.
;;
;; Each row is one line. Members print through the slice-36 oracle's
;; serializers, which this file loads from the path in CXC_SER_ORACLE
;; (the forms between that file's `(do` and its stdin loop), so a member
;; prints exactly as slice 36 renders the node. `:methods` mirrors
;; `:observables` and is not printed.

(require 'cxc.parse)
(require 'cxc.lex)
(require 'cxc.resolve)
(require 'clojure.string)

(do

(let [txt   (slurp (System/getenv "CXC_SER_ORACLE"))
      from  (+ (clojure.string/index-of txt "\n(do\n") 5)
      upto  (clojure.string/index-of txt "(doseq [ln (line-seq")]
  (load-string (subs txt from upto)))

(defn- grp [nm xs]
  (str "(" nm (apply str (map #(str " " %) xs)) ")"))

(defn- ser-entry [e]
  (let [m    (:members e)
        head (str "(entry " (:name e) " " (name (:module e)) " " (name (:kind e)) " "
                  (if-let [v (:variant e)] (name v) "nil"))]
    (str head " "
         (case (:kind e)
           :record   (str (grp "fields" (map ser-field (:fields m))) " "
                          (grp "observables" (map ser-item (:observables m))))
           :def-atom (str (grp "data_fields" (map ser-field (:data-fields m))) " "
                          (grp "observables" (map ser-item (:observables m))) " "
                          (grp "transitions" (map ser-item (:transitions m))) " "
                          (grp "use_caps" (map #(str "(use " (:name %) " " (ser-type (:type %)) ")")
                                               (:use-caps m))) " "
                          (grp "invariants" (map ser-item (:invariants m))) " "
                          (grp "handler_decls" (map ser-item (:handler-decls m))))
           :enum     (grp "cases" (:cases m)))
         ")")))

(defn- cap? [x]
  (and (string? x) (pos? (count x))
       (<= (int \A) (int (.charAt ^String x 0)) (int \Z))))

(defn- ref-row [x]
  (when (map? x)
    (let [row (fn [k nm m] (str "(ref " k " " nm " " (if m (name m) "nil") ")"))]
      (cond
        (and (= :type-ref (:node x)) (string? (:name x)))
        (row "type" (:name x) (:resolves-to x))
        (and (= :call (:node x)) (map? (:callee x))
             (= :ident (:node (:callee x))) (cap? (:name (:callee x))))
        (row "call" (:name (:callee x)) (:resolves-to (:callee x)))
        (and (= :dot (:node x)) (map? (:target x))
             (= :ident (:node (:target x))) (cap? (:name (:target x))))
        (row "dot" (:name (:target x)) (:resolves-to (:target x)))
        (and (= :extend-class (:node x)) (string? (:target x)))
        (row "extend" (:target x) (:resolves-to x))
        (and (= :type-of (:node x)) (cap? (:outer x)))
        (row "of" (:outer x) (:resolves-to x))))))

(defn- ref-rows [nodes mod]
  (let [m        (keyword (if (and mod (not= mod "")) mod "main"))
        decls    (@#'cxc.resolve/decls-in-file nodes)
        enriched (:nodes (@#'cxc.resolve/resolve-references-in-file
                          {:module m :nodes nodes} {m decls}
                          (cxc.resolve/prelude-name->module)))
        rows     (->> (tree-seq coll? #(if (map? %) (vals %) (seq %)) enriched)
                      (keep ref-row)
                      distinct
                      sort)]
    (println (str "(refs " (count rows) ")"))
    (doseq [r rows] (println r))))

(let [mod  (System/getenv "CXC_RESOLVE_MODULE")
      refs (= "refs" (System/getenv "CXC_RESOLVE_MODE"))]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [src (-> ln
                  (clojure.string/replace "\\n" "\n")
                  (clojure.string/replace "\\N" "\\n"))
          res (try
                (let [nodes (cxc.parse/parse src)]
                  {:nodes (if (and mod (not= mod "") (not refs))
                            (into [{:node :module :name mod}] nodes)
                            nodes)})
                (catch Exception e
                  {:err (.getMessage e)}))]
      (if-let [err (:err res)]
        (println (str "error|" err))
        (if refs
          (ref-rows (:nodes res) mod)
        (let [table (cxc.resolve/build-symbol-table (:nodes res))]
          (println (str "(table " (count table) ")"))
          (doseq [k (sort (keys table))]
            (println (ser-entry (get table k))))))))))

)
