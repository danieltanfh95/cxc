;; Oracle for the chapter-35 record-env slice (slice 40,
;; `lower_records.cxc`).
;;
;; Input per line. `<mode>|<payload>`, the slice-39 row convention
;; (`full`, `raw`, or `multi` streams, `lower` or `envs` checks), see
;; `oracle/lower_envs.clj`. The slice-39 oracle is loaded at run time
;; (CXC_P39_ORACLE), which loads the slice-38 oracle and the slice-36
;; serializers in turn, so the stream and every row printer are shared.
;;
;;   lower  the real `cxc.lower/lower` runs with build-record-env and
;;          validate-no-agent-fields-in-records! wrapped to record their
;;          results, and `infer-observables-in-record-env` stopping the
;;          run when it is called. Every check lower makes up to that
;;          point fires in lower's order.
;;   envs   the builders are called in lower's order (the slice-39 envs
;;          run, then build-record-env, the F38 check), then the two steps
;;          after observable inference that do not read its result,
;;          `resolve/verify-parity!` over the symbol table and
;;          `named-tuple/collect-shapes` over the top nodes. Neither
;;          reads an observable type, and observable inference adds no
;;          env key, so they run on the envs as build-record-env leaves
;;          them.
;;
;; Output per input line. Only the sections this slice adds. An error in
;; any earlier step (the user chain or a slice-39 builder) prints
;; `error|<message>` alone, the way slice 40 passes slice 39's error on.
;;
;;   (record-env <n>)   then one row per record, sorted by name.
;;       (record <name> struct|class (fields <f>*) (observables <name>*))
;;       <f> is the slice-39 field row (no picker, records have none).
;;   (parity <records> <atoms> <enums>)   envs only, the number of env
;;       names verify-parity! checks per kind (atom-env without the
;;       runtime-provided and container entries).
;;   (nt-shapes <n>)    envs only, then (shape <mangled>) per key of the
;;       sorted map collect-shapes returns.
;;
;; A throw after the record env prints the sections before it, then
;; `error|<message>`.

(require 'cxc.parse)
(require 'cxc.lex)
(require 'cxc.build)
(require 'cxc.prelude)
(require 'cxc.plugin-manifest)
(require 'cxc.section)
(require 'cxc.lower)
(require 'cxc.env)
(require 'cxc.resolve)
(require 'cxc.mangle)
(require 'cxc.named-tuple)
(require 'cxc.lower.record-env)
(require 'cxc.lower.protocol-env)
(require 'cxc.lower.enum-env)
(require 'cxc.lower.program-scan)
(require 'clojure.string)

(do

(let [txt   (slurp (System/getenv "CXC_P39_ORACLE"))
      from  (+ (clojure.string/index-of txt "\n(do\n") 5)
      upto  (clojure.string/last-index-of txt "(doseq [ln (line-seq")]
  (load-string (subs txt from upto)))

(defn- ser-record [[nm e]]
  (str "(record " nm " " (name (:variant e)) " "
       (wrap "fields" (map ser-afield (:fields e))) " "
       (wrap "observables" (map :name (:observables e)))
       ")"))

(defn- trim-env [ae]
  (into {} (remove (fn [[_ v]] (cxc.env/synthesized-atom-kind? (:kind v))) ae)))

(defn- sections40 [rec]
  (concat
    (when-let [re (:record-env rec)]
      (rows "record-env" (map ser-record (sort-by key re))))
    (when-let [[r a e] (:parity rec)]
      [(str "(parity " r " " a " " e ")")])
    (when-let [sh (:shapes rec)]
      (rows "nt-shapes" (map #(str "(shape " % ")") (keys sh))))))

;; envs. The slice-39 builders in lower's order, then this slice's.
(defn- run-envs40 [nodes rec]
  (let [top (cxc.resolve/ensure-references-enriched nodes)
        d   (cxc.env/attribute-modules-to-decls top)
        mm  (cxc.env/module-map-from-attribution d)
        _   (shape-scan top)
        top (vec (remove #(= :def-plugin (:node %)) top))
        _   (cxc.lower.protocol-env/build-protocol-env top)
        _   (cxc.lower.program-scan/sync-send-edges top)
        en  (cxc.lower.enum-env/build-enum-env top)]
    (binding [cxc.mangle/*decl->module* d]
      (let [ae (shake top (@#'cxc.lower/build-atom-env top en mm))
            _  (swap! rec assoc ::prior-ok true)
            re (cxc.lower.record-env/build-record-env top ae en mm)
            _  (swap! rec assoc :record-env re)
            _  (cxc.lower.record-env/validate-no-agent-fields-in-records! re ae)
            st (cxc.resolve/build-symbol-table top)
            ta (trim-env ae)
            _  (cxc.resolve/verify-parity! st {:record-env re :atom-env ta :enum-env en})
            _  (swap! rec assoc :parity [(count re) (count ta) (count en)])]
        (swap! rec assoc :shapes (cxc.named-tuple/collect-shapes top))))))

;; lower. The real lower/lower, stopped at observable inference.
(defn- run-lower40 [nodes rec]
  (let [renv @#'cxc.lower.record-env/build-record-env
        vnaf @#'cxc.lower.record-env/validate-no-agent-fields-in-records!]
    (try
      (with-redefs-fn
        {#'cxc.lower.record-env/build-record-env
         (fn [t a e m]
           (swap! rec assoc ::prior-ok true)
           (let [r (renv t a e m)] (swap! rec assoc :record-env r) r))
         #'cxc.lower.record-env/validate-no-agent-fields-in-records!
         (fn [r a] (vnaf r a))
         #'cxc.lower/infer-observables-in-record-env
         (fn [& _] (throw (ex-info "slice-40 stop" {::stop true})))
         #'cxc.lower.program-scan/warn-sync-send-cycles! (fn [_] #{})}
        (fn [] (cxc.lower/lower nodes)))
      (catch clojure.lang.ExceptionInfo e
        (when-not (::stop (ex-data e)) (throw e))))))

(defn- run-row40 [mode payload]
  (let [[kind check] (clojure.string/split mode #"-")
        nodes (try (stream kind payload)
                   (catch Exception e (str "error|" (.getMessage e))))]
    (if (string? nodes)
      nodes
      (let [rec (atom {})
            err (try
                  (if (= check "lower") (run-lower40 nodes rec) (run-envs40 nodes rec))
                  nil
                  (catch Exception e (.getMessage e)))]
        (if (and err (not (::prior-ok @rec)))
          (str "error|" err)
          (clojure.string/join "\n"
            (concat (sections40 @rec)
                    (when err [(str "error|" err)]))))))))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [bar (clojure.string/index-of ln "|")]
    (println (run-row40 (subs ln 0 bar) (subs ln (inc bar))))))

)
