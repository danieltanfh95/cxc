;; Oracle for the chapter-37 companion and container slice (slice 42,
;; `lower_containers.cxc`).
;;
;; Input per line. `<mode>|<payload>`, the slice-39 row convention
;; (`full`, `raw`, or `multi` streams, `lower` or `envs` checks), see
;; `oracle/lower_envs.clj`. The slice-41 oracle is loaded at run time
;; (CXC_P41_ORACLE), which loads the slice-40, slice-39, slice-38, and
;; slice-36 oracles in turn, so the stream and every row printer are
;; shared.
;;
;;   lower  the real `cxc.lower/lower` runs with the inference passes and
;;          `augment-atom-env-with-companions` wrapped to record their
;;          results, and `resolve/build-symbol-table` (the step after
;;          `finalize-atom-env-inference`) stopping the run. Only the
;;          companion section prints, since container synthesis comes
;;          after the symbol-table parity check and the protocol-default
;;          inference, which no slice runs in lower mode yet.
;;   envs   the slice-41 envs steps (the slice-40 envs steps, both
;;          observable passes, the stream pass), then
;;          `augment-atom-env-with-companions` and
;;          `synthesize-container-entries` the way lower calls them.
;;          `infer-protocol-defaults`, which lower runs between the two,
;;          changes neither env and is left out.
;;
;; Output per input line. Only the sections this slice adds. An error in
;; any earlier step prints `error|<message>` alone.
;;
;;   (companions <n>)   then (companion <target> <msg> <type-kw> "<c-type>")
;;       per companion stream the augment adds, from
;;       `collect-companion-demand`, sorted. The augment also puts the
;;       stream in the target's `:stream-index` with the one key `reason`
;;       and appends it to `:stream-order` when it is not there.
;;   (containers <n>)   then one row per synthesized container, sorted.
;;       (container <class> list <elem> (methods <m>*) (fanin-order <s>*)
;;                  (fanin-index (<s> <key>*)*)
;;                  (fanin-payloads (<s> (<key> <type-kw> "<c-type>")*)*))
;;       (container <class> map <key-type> <val-type> (methods <m>*))
;;       (container <class> set <elem> (methods <m>*))
;;       <m> = (m <name> (<param> <cxc-ty>)* <return-ty> opt|raw), the
;;       params without `self`, opt when `:return-optional?` is set.
;;
;; A throw in the container step prints the companion section first.

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
(require 'clojure.string)

(do

(let [txt   (slurp (System/getenv "CXC_P41_ORACLE"))
      from  (+ (clojure.string/index-of txt "\n(do\n") 5)
      upto  (clojure.string/last-index-of txt "(doseq [ln (line-seq")]
  (load-string (subs txt from upto)))

(def ^:private demand-of @#'cxc.lower.shape-collection/collect-companion-demand)
(def ^:private augment cxc.lower.shape-collection/augment-atom-env-with-companions)
(def ^:private synth-containers @#'cxc.lower/synthesize-container-entries)

(defn- ser-companions [rec]
  (let [d  (:demand rec)
        ae (:comp-env rec)]
    (for [[target msgs] d [msg _] msgs]
      (let [p (first (get-in ae [target :stream-payload-index msg]))]
        (str "(companion " target " " msg " " (pr-str (:type-kw p)) " "
             (pr-str (:c-type p)) ")")))))

(defn- ser-method [m]
  (str "(m " (:name m)
       (apply str (map #(str " (" (:name %) " " (:cxc-ty %) ")")
                       (remove :is-self? (:params m))))
       " " (:cxc-return-ty m) " " (if (:return-optional? m) "opt" "raw") ")"))

(defn- ser-methods [e]
  (str "(methods" (apply str (map #(str " " (ser-method %)) (:methods e))) ")"))

(defn- ser-fanin [e]
  (str "(fanin-order" (apply str (map #(str " " %) (:stream-order e))) ") "
       "(fanin-index"
       (apply str (map (fn [[s v]]
                         (str " (" s (apply str (map #(str " " (:name %)) (:payload v))) ")"))
                       (sort-by key (:stream-index e))))
       ") (fanin-payloads"
       (apply str (map (fn [[s args]]
                         (str " (" s
                              (apply str (map #(str " (" (:name %) " " (pr-str (:type-kw %))
                                                    " " (pr-str (:c-type %)) ")")
                                              args))
                              ")"))
                       (sort-by key (:stream-payload-index e))))
       ")"))

(defn- ser-container [[cls e]]
  (case (:kind e)
    :list-container (str "(container " cls " list " (:element-type e) " "
                         (ser-methods e) " " (ser-fanin e) ")")
    :map-container  (str "(container " cls " map " (:key-type e) " " (:val-type e) " "
                         (ser-methods e) ")")
    :set-container  (str "(container " cls " set " (:element-type e) " "
                         (ser-methods e) ")")))

(defn- sections42 [rec]
  (concat
    (when (:comp-env rec)
      (sorted-rows "companions" (ser-companions rec)))
    (when-let [ce (:cont-env rec)]
      (sorted-rows "containers"
                   (map ser-container
                        (filter #(contains? #{:list-container :map-container :set-container}
                                            (:kind (val %)))
                                ce))))))

;; envs. The slice-41 envs steps, then the augment and the container
;; synthesis.
(defn- run-envs42 [nodes rec]
  (let [ae (run-envs41 nodes rec)
        re (:record-obs @rec)
        en (:enum-env @rec)
        _  (swap! rec assoc :demand (demand-of ae))
        ae (augment ae re en)
        _  (swap! rec assoc :comp-env ae)
        ae (synth-containers ae re en)]
    (swap! rec assoc :cont-env ae)))

;; lower. The real lower/lower, stopped after finalize-atom-env-inference.
(defn- run-lower42 [nodes rec]
  (try
    (with-redefs-fn
      {#'cxc.lower.shape-collection/augment-atom-env-with-companions
       (fn [a r e]
         (swap! rec assoc :demand (demand-of a))
         (let [x (augment a r e)] (swap! rec assoc :comp-env x) x))
       #'cxc.resolve/build-symbol-table
       (fn [& _] (throw (ex-info "slice-42 stop" {::stop true})))
       #'cxc.lower.program-scan/warn-sync-send-cycles! (fn [_] #{})}
      (fn [] (cxc.lower/lower nodes)))
    (catch clojure.lang.ExceptionInfo e
      (when-not (::stop (ex-data e)) (throw e)))))

(defn- run-row42 [mode payload]
  (let [[kind check] (clojure.string/split mode #"-")
        nodes (try (stream kind payload)
                   (catch Exception e (str "error|" (.getMessage e))))]
    (if (string? nodes)
      nodes
      (let [rec (atom {})
            err (try
                  (if (= check "lower") (run-lower42 nodes rec) (run-envs42 nodes rec))
                  nil
                  (catch Exception e (.getMessage e)))]
        (if (and err (not (:comp-env @rec)))
          (str "error|" err)
          (clojure.string/join "\n"
            (concat (sections42 @rec)
                    (when err [(str "error|" err)]))))))))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [bar (clojure.string/index-of ln "|")]
    (println (run-row42 (subs ln 0 bar) (subs ln (inc bar))))))

)
