;; Oracle for the chapter-32 pipeline slice (slice 38,
;; `prelude_program.cxc`).
;;
;; Input per line. `<mode>|<source>`, the source in the chapter-23
;; corpus convention (`\\n` a physical newline, `\\N` the two chars
;; backslash-n). The mode is split off at the first `|`.
;;
;; Reference. The passes `build/compile-source-to-file` applies between
;; `cxc.parse/parse` and `lower/lower`, in its order, through the same
;; functions (private ones through their vars).
;;
;;   user     validate-imports, prelude/validate-no-prelude-collisions!,
;;            validate-plugin-capability-uses!, rewrite-objc-type-aliases,
;;            extract, validate-plugin-symbol-refs!, rewrite-plugin-refs,
;;            rewrite-foreign-alias-fields, resolve-references (module
;;            :main), the behavioral walkers (none are registered), then
;;            prelude/desugar-prelude-class-members over the user nodes.
;;   full     the user chain, then `build/process-prelude-nodes :macos`
;;            on the real prelude, the prelude nodes prepended to the
;;            user nodes, and desugar-prelude-class-members over the
;;            whole stream. This is exactly what `lower/lower` receives.
;;   prelude  `build/process-prelude-nodes` with `prelude/assemble-source`
;;            redefined to return the row's source (rewrite-objc-type-
;;            aliases, extract, validate-plugin-symbol-refs! when a
;;            manifest exists, rewrite-plugin-refs,
;;            rewrite-foreign-alias-fields, resolve/enrich-prelude-nodes),
;;            then desugar-prelude-class-members. The harness feeds it the
;;            assembled macOS prelude and hand rows.
;;   prefs    the references in `build/process-prelude-nodes`'s output
;;            (before desugar-prelude-class-members, which rebuilds the
;;            two class-member nodes without `:resolves-to`), one row per
;;            distinct reference sorted, with the `:resolves-to` that
;;            `resolve/enrich-prelude-nodes` attached. The rows are the
;;            slice-37 refs rows, `(refs <n>)` first.
;;   assemble `prelude/assemble-source :macos`, collapsed to one row in
;;            the corpus convention. The source part of the row is empty
;;            for the real registry, or the path of a registry file
;;            (`<name>|<kind>` lines) that replaces `prelude-modules`.
;;
;; Output per input line. `(program <node>*)`, or `error|<message>` when
;; any pass throws (the ex-info message verbatim). Assemble mode prints
;; the collapsed source.
;;
;; Format. Nodes print through the slice-36 oracle's serializers, loaded
;; at run time from CXC_SER_ORACLE as the slice-37 oracle does, so the
;; two formats cannot drift. The rewrites introduce three expression
;; nodes, which `ser` is extended with here.
;;
;;   (foreign-ref "<lib>" "<sym>" <sig-kind> <sig>)
;;       plugin.get(LIT), <sig-kind> is always `symbol`
;;   (foreign-selector-call "<lib>" "<cls>" "<sel>" <sig> <receiver> <arg>*)
;;       plugin.send(LIT, RECV, ARGS...)
;;   (foreign-class-selector-call "<lib>" "<cls>" "<sel>" <sig> <arg>*)
;;       plugin.class_send(LIT, ARGS...)
;;
;; <sig> is the foreign entry's signature through `ser-type` (a TF(...)
;; atom), <arg> prints as `(pos e)` or `(named k e)`. The `:orig-node`
;; slot (the original call, kept for diagnostics) and the
;; `:objc-original` key that rewrite-objc-type-aliases leaves on a
;; `TR:Any` are not printed, and neither is `:resolves-to`. No
;; consumer in lower or emit reads the first two. The enrichment is
;; pinned by slice 37's refs mode.

(require 'cxc.parse)
(require 'cxc.lex)
(require 'cxc.build)
(require 'cxc.prelude)
(require 'cxc.plugin-manifest)
(require 'cxc.resolve)
(require 'cxc.section)
(require 'clojure.string)

(do

(let [txt   (slurp (System/getenv "CXC_SER_ORACLE"))
      from  (+ (clojure.string/index-of txt "\n(do\n") 5)
      upto  (clojure.string/index-of txt "(doseq [ln (line-seq")]
  (load-string (subs txt from upto)))

(def ^:private base-ser ser)

(defn- ser [n]
  (case (:node n)
    :foreign-ref
    (str "(foreign-ref " (pr-str (:library n)) " " (pr-str (:symbol n)) " "
         (name (:sig-kind n)) " " (ser-type (:signature n)) ")")
    :foreign-selector-call
    (str "(foreign-selector-call " (pr-str (:library n)) " " (pr-str (:cls n)) " "
         (pr-str (:selector n)) " " (ser-type (:signature n)) " " (ser (:receiver n))
         (apply str (map #(str " " (ser-arg %)) (:args n))) ")")
    :foreign-class-selector-call
    (str "(foreign-class-selector-call " (pr-str (:library n)) " " (pr-str (:cls n)) " "
         (pr-str (:selector n)) " " (ser-type (:signature n))
         (apply str (map #(str " " (ser-arg %)) (:args n))) ")")
    (base-ser n)))

(defn- program [nodes]
  (str "(program" (apply str (map #(str " " (ser-top %)) nodes)) ")"))

;; build/compile-source-to-file, from parse to the enriched user nodes.
(defn- user-chain [source]
  (let [parsed (cxc.parse/parse source)
        _      (@#'cxc.build/validate-imports parsed)
        _      (cxc.prelude/validate-no-prelude-collisions! parsed)
        _      (cxc.plugin-manifest/validate-plugin-capability-uses! parsed)
        parsed (cxc.plugin-manifest/rewrite-objc-type-aliases parsed)
        mfst   (cxc.plugin-manifest/extract parsed)
        _      (cxc.plugin-manifest/validate-plugin-symbol-refs! parsed mfst)
        parsed (cxc.plugin-manifest/rewrite-plugin-refs parsed mfst)
        parsed (cxc.plugin-manifest/rewrite-foreign-alias-fields parsed)]
    (-> [{:module :main :nodes parsed}]
        cxc.resolve/resolve-references
        first
        :nodes)))

;; build/compile-parsed-to-file, up to lower/lower's argument.
(defn- lower-input [user-parsed full-parsed]
  (doseq [[_ walker-var] (cxc.section/behavioral-walkers)]
    (walker-var user-parsed full-parsed))
  (cxc.prelude/desugar-prelude-class-members full-parsed))

(defn- prelude-chain [source]
  (with-redefs [cxc.prelude/assemble-source (fn [_] source)]
    (@#'cxc.build/process-prelude-nodes :macos)))

(defn- cap? [x]
  (and (string? x) (pos? (count x))
       (<= (int \A) (int (.charAt ^String x 0)) (int \Z))))

;; Copied from the slice-37 oracle.
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

(defn- refs [nodes]
  (let [rows (->> (tree-seq coll? #(if (map? %) (vals %) (seq %)) nodes)
                  (keep ref-row)
                  distinct
                  sort)]
    (clojure.string/join "\n" (cons (str "(refs " (count rows) ")") rows))))

(defn- collapse [s]
  (-> s
      (clojure.string/replace "\\n" "\\N")
      (clojure.string/replace "\n" "\\n")))

(defn- registry [path]
  (vec (for [ln (clojure.string/split-lines (slurp path))
             :when (not= ln "")
             :let [[nm kind] (clojure.string/split ln #"\|")]]
         {:name (keyword nm) :kind (keyword kind)})))

(defn- run-row [mode src]
  (case mode
    "user"     (let [u (user-chain src)]
                 (program (lower-input u u)))
    "full"     (let [u (user-chain src)
                     p (@#'cxc.build/process-prelude-nodes :macos)]
                 (program (lower-input u (into (vec p) u))))
    "prelude"  (let [p (prelude-chain src)]
                 (program (lower-input p p)))
    "prefs"    (refs (prelude-chain src))
    "assemble" (collapse
                 (if (= src "")
                   (cxc.prelude/assemble-source :macos)
                   (with-redefs [cxc.prelude/prelude-modules (registry src)]
                     (cxc.prelude/assemble-source :macos))))))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [bar  (clojure.string/index-of ln "|")
        mode (subs ln 0 bar)
        src  (-> (subs ln (inc bar))
                 (clojure.string/replace "\\n" "\n")
                 (clojure.string/replace "\\N" "\\n"))]
    (println
      (try
        (run-row mode (if (= mode "assemble") (subs ln (inc bar)) src))
        (catch Exception e
          (str "error|" (.getMessage e)))))))

)
