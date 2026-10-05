;; Oracle for the chapter-38 second inference round (slice 43, the
;; `round2` mode of `lower_observables.cxc`).
;;
;; Input per line. `<mode>|<payload>`, the slice-39 row convention
;; (`full`, `raw`, or `multi` streams, `lower` or `envs` checks), see
;; `oracle/lower_envs.clj`. The slice-42 oracle is loaded at run time
;; (CXC_P42_ORACLE), which loads the slice-41, slice-40, slice-39,
;; slice-38, and slice-36 oracles in turn, so the stream and every row
;; printer are shared.
;;
;;   lower  the real `cxc.lower/lower` runs with infer-protocol-defaults,
;;          the second calls of infer-observables-in-record-env,
;;          infer-observables-in-atom-env, infer-streams-in-atom-env, and
;;          augment-atom-env-with-companions wrapped to record their
;;          results, and build-extension-env (the step after the second
;;          augment) stopping the run.
;;   envs   the slice-42 envs steps (the slice-41 envs steps, then the
;;          augment), then infer-protocol-defaults with the full ctx lower
;;          builds (symbol table, decl->module, module map, named-tuple
;;          env), synthesize-container-entries, and the second round,
;;          the record pass and the atom pass with `defer?` false, the
;;          stream pass, and the augment, the way lower calls them.
;;
;; Output per input line. Only the sections this slice adds. An error in
;; any step before infer-protocol-defaults prints `error|<message>` alone.
;;
;;   (protocol-defaults <n>)  then (pdefault <protocol> <name> <ret>|nil),
;;       one row per default of every protocol, sorted, <ret> the
;;       `:cxc-return-ty` after the pass.
;;   (record-obs <n>), (atom-obs <n>), (stream-payloads <n>)  the slice-41
;;       sections for the second round.
;;   (companions <n>)  the slice-42 section for the second augment.
;;
;; A throw prints the sections of the steps before it, then
;; `error|<message>`. The container synthesis prints nothing here (slice
;; 42 prints it), so a throw there follows the protocol-defaults section.

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
(require 'cxc.lower.shape-collection)
(require 'cxc.lower.extensions)
(require 'clojure.string)

(do

(let [txt   (slurp (System/getenv "CXC_P42_ORACLE"))
      from  (+ (clojure.string/index-of txt "\n(do\n") 5)
      upto  (clojure.string/last-index-of txt "(doseq [ln (line-seq")]
  (load-string (subs txt from upto)))

(def ^:private infer-pd @#'cxc.lower/infer-protocol-defaults)
(def ^:private mk-ctx* @#'cxc.lower/mk-ctx)

(defn- ser-pdefaults [pe]
  (for [[pn info] pe d (:defaults info)]
    (str "(pdefault " pn " " (:name d) " " (or (:cxc-return-ty d) "nil") ")")))

(defn- sections43 [rec]
  (concat
    (when-let [pe (:pd rec)]
      (sorted-rows "protocol-defaults" (ser-pdefaults pe)))
    (when-let [re (:record-obs2 rec)]
      (sorted-rows "record-obs" (map ser-obs (obs-rows re))))
    (when-let [ae (:atom-obs2 rec)]
      (sorted-rows "atom-obs" (map ser-obs (obs-rows ae))))
    (when-let [se (:stream-env2 rec)]
      (sorted-rows "stream-payloads" (map ser-spi (spi-rows se))))
    (when (:comp-env2 rec)
      (sorted-rows "companions"
                   (ser-companions {:demand (:demand2 rec) :comp-env (:comp-env2 rec)})))))

;; envs. The slice-42 envs steps up to the augment, then the rest of the
;; second round in lower's order.
(defn- run-envs43 [nodes rec]
  (let [ae  (run-envs41 nodes rec)
        re  (:record-obs @rec)
        en  (:enum-env @rec)
        ae  (augment ae re en)
        top (cxc.resolve/ensure-references-enriched nodes)
        d   (cxc.env/attribute-modules-to-decls top)
        mm  (cxc.env/module-map-from-attribution d)
        top (vec (remove #(= :def-plugin (:node %)) top))
        pe  (cxc.lower.protocol-env/build-protocol-env top)]
    (binding [cxc.mangle/*decl->module* d]
      (let [st (cxc.resolve/build-symbol-table top)
            nt (cxc.named-tuple/collect-shapes top)
            pe (infer-pd pe (mk-ctx* {:record-env re :atom-env ae :enum-env en
                                      :symbol-table st :decl->module d
                                      :module-map mm :named-tuple-env nt}))
            _  (swap! rec assoc :pd pe)
            ae (synth-containers ae re en)
            re (infer-rec re ae en false)
            _  (swap! rec assoc :record-obs2 re)
            ae (infer-atom ae re en false)
            _  (swap! rec assoc :atom-obs2 ae)
            ae (infer-streams ae re en)
            _  (swap! rec assoc :stream-env2 ae)
            _  (swap! rec assoc :demand2 (demand-of ae))
            ae (augment ae re en)]
        (swap! rec assoc :comp-env2 ae)))))

;; lower. The real lower/lower, stopped after the second augment. The
;; three-argument arity of each observable pass calls the four-argument
;; one through its var, so a second-round call is the one whose defer?
;; argument is false, and the stream pass and the augment are counted.
(defn- run-lower43 [nodes rec]
  (let [ns (atom 0) nc (atom 0)]
    (try
      (with-redefs-fn
        {#'cxc.lower/infer-protocol-defaults
         (fn [p c] (let [x (infer-pd p c)] (swap! rec assoc :pd x) x))
         #'cxc.lower/infer-observables-in-record-env
         (fn [r a e & more]
           (let [x (apply infer-rec r a e more)]
             (when (= [false] (vec more)) (swap! rec assoc :record-obs2 x))
             x))
         #'cxc.lower/infer-observables-in-atom-env
         (fn [a r e & more]
           (let [x (apply infer-atom a r e more)]
             (when (= [false] (vec more)) (swap! rec assoc :atom-obs2 x))
             x))
         #'cxc.lower/infer-streams-in-atom-env
         (fn [a r e & more]
           (let [x (apply infer-streams a r e more)]
             (when (= 2 (swap! ns inc)) (swap! rec assoc :stream-env2 x))
             x))
         #'cxc.lower.shape-collection/augment-atom-env-with-companions
         (fn [a r e]
           (let [two? (= 2 (swap! nc inc))
                 _    (when two? (swap! rec assoc :demand2 (demand-of a)))
                 x    (augment a r e)]
             (when two? (swap! rec assoc :comp-env2 x))
             x))
         #'cxc.lower.extensions/build-extension-env
         (fn [& _] (throw (ex-info "slice-43 stop" {::stop true})))
         #'cxc.lower.program-scan/warn-sync-send-cycles! (fn [_] #{})}
        (fn [] (cxc.lower/lower nodes)))
      (catch clojure.lang.ExceptionInfo e
        (when-not (::stop (ex-data e)) (throw e))))))

(defn- run-row43 [mode payload]
  (let [[kind check] (clojure.string/split mode #"-")
        nodes (try (stream kind payload)
                   (catch Exception e (str "error|" (.getMessage e))))]
    (if (string? nodes)
      nodes
      (let [rec (atom {})
            err (try
                  (if (= check "lower") (run-lower43 nodes rec) (run-envs43 nodes rec))
                  nil
                  (catch Exception e (.getMessage e)))]
        (if (and err (not (:pd @rec)))
          (str "error|" err)
          (clojure.string/join "\n"
            (concat (sections43 @rec)
                    (when err [(str "error|" err)]))))))))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [bar (clojure.string/index-of ln "|")]
    (println (run-row43 (subs ln 0 bar) (subs ln (inc bar))))))

)
