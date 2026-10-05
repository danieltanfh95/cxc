;; Oracle for the chapter-36 observable-inference slice (slice 41,
;; `lower_observables.cxc`).
;;
;; Input per line. `<mode>|<payload>`, the slice-39 row convention
;; (`full`, `raw`, or `multi` streams, `lower` or `envs` checks), see
;; `oracle/lower_envs.clj`. The slice-40 oracle is loaded at run time
;; (CXC_P40_ORACLE), which loads the slice-39, slice-38, and slice-36
;; oracles in turn, so the stream and every row printer are shared.
;;
;;   lower  the real `cxc.lower/lower` runs with build-record-env and the
;;          two inference passes wrapped to record their results, and
;;          `infer-streams-in-atom-env` (the step after the atom pass in
;;          `finalize-atom-env-inference`) stopping the run. Every check
;;          lower makes up to that point fires in lower's order.
;;   envs   the slice-40 envs steps in their order (the slice-39 builders,
;;          build-record-env, the F38 check, verify-parity!, and
;;          collect-shapes), then the first record pass
;;          `infer-observables-in-record-env` and the first atom pass
;;          `infer-observables-in-atom-env`, both with `defer?` true,
;;          the way lower calls them. Inference reads nothing the two
;;          slice-40 steps after it compute, so only the winner between
;;          two errors moves.
;;
;; Output per input line. Only the sections this slice adds. An error in
;; any earlier step prints `error|<message>` alone, the way slice 41
;; passes slice 40's error on.
;;
;;   (record-obs <n>)   then one row per observable of every record.
;;   (atom-obs <n>)     then one row per observable of every atom.
;;       (obs <owner> <name> <type-kw>|nil "<c-type>"|nil), sorted.
;;       <type-kw> is the pr-str of the inferred type keyword (`:int`,
;;       `[:optional :int]`, `{:record "Card"}`), nil when the first pass
;;       deferred it, and <c-type> is `type-kw->c-type` of it.
;;
;; A throw in the atom pass prints the record section, then
;; `error|<message>`. A throw in the record pass prints the error alone.

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

(let [txt   (slurp (System/getenv "CXC_P40_ORACLE"))
      from  (+ (clojure.string/index-of txt "\n(do\n") 5)
      upto  (clojure.string/last-index-of txt "(doseq [ln (line-seq")]
  (load-string (subs txt from upto)))

(defn- ser-obs [[owner o]]
  (str "(obs " owner " " (:name o) " "
       (if (:type-kw o) (pr-str (:type-kw o)) "nil") " "
       (if (:c-type o) (pr-str (:c-type o)) "nil") ")"))

(defn- obs-rows [env]
  (for [[nm e] env o (:observables-typed e)] [nm o]))

(defn- sections41 [rec]
  (concat
    (when-let [re (:record-obs rec)]
      (sorted-rows "record-obs" (map ser-obs (obs-rows re))))
    (when-let [ae (:atom-obs rec)]
      (sorted-rows "atom-obs" (map ser-obs (obs-rows ae))))))

(def ^:private infer-rec @#'cxc.lower/infer-observables-in-record-env)
(def ^:private infer-atom @#'cxc.lower/infer-observables-in-atom-env)

;; envs. The slice-40 envs steps, then the two first passes.
(defn- run-envs41 [nodes rec]
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
            re (cxc.lower.record-env/build-record-env top ae en mm)
            _  (cxc.lower.record-env/validate-no-agent-fields-in-records! re ae)
            st (cxc.resolve/build-symbol-table top)
            _  (cxc.resolve/verify-parity! st {:record-env re :atom-env (trim-env ae) :enum-env en})
            _  (cxc.named-tuple/collect-shapes top)
            re (infer-rec re ae en)
            _  (swap! rec assoc :record-obs re)
            ae (infer-atom ae re en)]
        (swap! rec assoc :atom-obs ae)))))

;; lower. The real lower/lower, stopped after the first atom pass.
(defn- run-lower41 [nodes rec]
  (try
    (with-redefs-fn
      {#'cxc.lower/infer-observables-in-record-env
       (fn [r a e & more]
         (let [x (apply infer-rec r a e more)] (swap! rec assoc :record-obs x) x))
       #'cxc.lower/infer-observables-in-atom-env
       (fn [a r e & more]
         (let [x (apply infer-atom a r e more)] (swap! rec assoc :atom-obs x) x))
       #'cxc.lower/infer-streams-in-atom-env
       (fn [& _] (throw (ex-info "slice-41 stop" {::stop true})))
       #'cxc.lower.program-scan/warn-sync-send-cycles! (fn [_] #{})}
      (fn [] (cxc.lower/lower nodes)))
    (catch clojure.lang.ExceptionInfo e
      (when-not (::stop (ex-data e)) (throw e)))))

(defn- run-row41 [mode payload]
  (let [[kind check] (clojure.string/split mode #"-")
        nodes (try (stream kind payload)
                   (catch Exception e (str "error|" (.getMessage e))))]
    (if (string? nodes)
      nodes
      (let [rec (atom {})
            err (try
                  (if (= check "lower") (run-lower41 nodes rec) (run-envs41 nodes rec))
                  nil
                  (catch Exception e (.getMessage e)))]
        (if (and err (not (:record-obs @rec)))
          (str "error|" err)
          (clojure.string/join "\n"
            (concat (sections41 @rec)
                    (when err [(str "error|" err)]))))))))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [bar (clojure.string/index-of ln "|")]
    (println (run-row41 (subs ln 0 bar) (subs ln (inc bar))))))

)
