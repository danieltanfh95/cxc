;; Oracle for the chapter-33 environment-builder slice (slice 39,
;; `lower_envs.cxc`).
;;
;; Input per line. `<mode>|<payload>`. <mode> is `<stream>-<check>`.
;;
;;   <stream> full   the payload is one source row (chapter-23 corpus
;;                   convention). The stream is the slice-38 `full` stream,
;;                   `build/process-prelude-nodes :macos` then the user chain
;;                   of the row, through desugar-prelude-class-members.
;;            raw    the payload is one source row and the stream is
;;                   `cxc.parse/parse` of it alone, no prelude and no pass,
;;                   the way a unit test calls `lower/lower`. Two plugins
;;                   with different non-default runtime shapes only meet
;;                   here, `extract` rejects every non-default shape but
;;                   `main_runloop`.
;;            multi  the payload is pieces separated by a tab, each
;;                   `<module>=<source>`. Every piece runs the user chain,
;;                   the stream is the prelude, then per piece a
;;                   `{:node :module :name <module>}` marker (none when
;;                   <module> is empty) and its nodes, the shape
;;                   `compile-directory-to-file` assembles, through the
;;                   class-member desugar.
;;   <check>  lower  the real `cxc.lower/lower` runs on the stream, with the
;;                   builders wrapped to record their results and
;;                   `build-record-env` stopping the run when it is called,
;;                   its atom-env argument recorded as the tree-shake result.
;;                   Every check lower makes before that point fires in
;;                   lower's order, the `def main` check included.
;;            envs   the builders are called directly in lower's order with
;;                   no `def main` check, so a library file still prints
;;                   its environments.
;;
;; The user chain and the prelude stream come from the slice-38 oracle,
;; loaded at run time (CXC_P38_ORACLE), which loads the slice-36
;; serializers (CXC_SER_ORACLE) in turn.
;;
;; Output per input line. The sections in lower's order, each a count row
;; and its rows. When a step throws, the sections before it, then
;; `error|<message>`. An error in the user chain prints the error alone.
;;
;;   (attribution <n>)      then (decl <name> <module>), sorted by name.
;;                          `env/attribute-modules-to-decls`.
;;   (module-map <n>)       then (module <module> <name>+), modules sorted,
;;                          names sorted. `env/module-map-from-attribution`.
;;   (plugin-shapes <n>)    then (plugin-shape "<name>"|nil <shape>) in
;;                          stream order, then (runtime-shape <shape>).
;;                          The plugin scan and conflict check at the head
;;                          of `lower/lower`, <shape> the keyword name.
;;   (protocol-env <n>)     then one row per protocol, sorted by name.
;;                          `build-protocol-env`.
;;       (protocol <name> (accepts <accept>*) (conforms <type>*)
;;                 (defaults <default>*))
;;       <accept>  = (accept <name> <params> <type>|nil)
;;       <default> = (default <name> (params <p>+) <ret-ty>|nil <type>|nil <expr>)
;;       <p>       = (self <owner>) | (p <name> <cxc-ty>|nil bang|plain <expr>|nil)
;;   (sync-edges <n>)       then (edge <source> <target> <msg>), sorted.
;;                          `program-scan/sync-send-edges`. The cycle
;;                          warning goes to stderr and is not printed.
;;   (enum-env <n>)         then one row per enum, sorted by name.
;;                          `build-enum-env`.
;;       (enum <name> tagged|plain (cases <c>*) (case-index (<c> <i>)*)
;;             (entries <enum-case>*) (payload-index (<c> <params>)*)
;;             (raw-values <v>+)|nil <raw-type>|nil)
;;
;;   (atom-env <n>)         then one row per atom, sorted by name.
;;                          `build-atom-env` (chapter 34), called with
;;                          `mangle/*decl->module*` bound as lower binds it.
;;       (atom <name> (fields <f>*) (ctor-params <c>*) (methods <m>*)
;;             (observables <name>*) (invariants <prop>*) (transitions <t>*)
;;             (message-index (<msg> (payload <field>*))*)
;;             (companion-index (<name> <of-msg> (reasons <r>*))*)
;;             (use-caps (<name> <type-name>)*) (stream-index (<name> (keys <k>*))*)
;;             (stream-order <name>*) (subscriptions (<field> <msg>+)*)
;;             (slot-names <name>*) (on-nil (<method> <expr>)*)
;;             (extras <item>*) (class-fns <item>*) <queue>|nil)
;;       <f> = (f <name> <cxc-ty> bang|plain opt|raw "<scalar-c>" "<c-type>"
;;               <default>|nil <type> [(map <k> <v>)] [<modifier>] [(picked <lo> <hi>)])
;;       <c> = (c <name> <cxc-ty> bang|plain "<c-type>" <default>|nil <type> [(map <k> <v>)])
;;       <m> = (method <name> (params <p>+) <block>), <r> = (typed <T>) | violation
;;   (tree-shake <n>)       then (keep <name>) per key of the atom env after
;;                          the CxcStream seed and the prelude tree-shake,
;;                          sorted, CxcStream as (keep CxcStream
;;                          runtime-provided). The binding run is cut out of
;;                          lower.clj and evaluated in envs mode.
;;
;; `:nullable?` is not printed (a `T?` type throws), nor are the fields'
;; F41 markers, which fields-from-data-section drops. Indices print sorted by key. Params, types, expressions, and enum cases
;; print through the slice-36 serializers. accepts-index equals accepts
;; (duplicate signatures are rejected), so it is not printed.

(require 'cxc.parse)
(require 'cxc.lex)
(require 'cxc.build)
(require 'cxc.prelude)
(require 'cxc.plugin-manifest)
(require 'cxc.section)
(require 'cxc.lower)
(require 'cxc.env)
(require 'cxc.resolve)
(require 'cxc.lower.protocol-env)
(require 'cxc.lower.enum-env)
(require 'cxc.lower.program-scan)
(require 'cxc.mangle)
(require 'cxc.lower.record-env)
(require 'clojure.string)

(do

(let [txt   (slurp (System/getenv "CXC_P38_ORACLE"))
      from  (+ (clojure.string/index-of txt "\n(do\n") 5)
      upto  (clojure.string/last-index-of txt "(doseq [ln (line-seq")]
  (load-string (subs txt from upto)))

;; The runtime-shape scan is a run of let bindings inside `lower/lower`.
;; Its text is cut out of lower.clj and evaluated in cxc.lower, so the
;; oracle runs that exact code, conflict throw included.
(def ^:private shape-scan
  (let [txt  (slurp "src/cxc/lower.clj")
        from (clojure.string/index-of txt "plugin-runtime-shape\n")
        stop "runtime-shape    (or (first distinct-shapes) :dispatch-main)"
        upto (+ (clojure.string/index-of txt stop) (count stop))]
    (binding [*ns* (find-ns 'cxc.lower)]
      (eval (read-string
              (str "(fn [top-nodes] (let [" (subs txt from upto) "] "
                   "{:plugin-shapes plugin-shapes :runtime-shape runtime-shape}))"))))))

(def ^:private prelude-nodes
  (delay (@#'cxc.build/process-prelude-nodes :macos)))

(defn- unrow [s]
  (-> s
      (clojure.string/replace "\\n" "\n")
      (clojure.string/replace "\\N" "\\n")))

(defn- stream [kind payload]
  (case kind
    "raw"   (cxc.parse/parse (unrow payload))
    "full"  (let [u (user-chain (unrow payload))]
              (lower-input u (into (vec @prelude-nodes) u)))
    "multi" (let [pieces (for [pc (clojure.string/split payload #"\t")
                               :let [k (clojure.string/index-of pc "=")]]
                           [(subs pc 0 k) (user-chain (unrow (subs pc (inc k))))])
                  u (vec (mapcat (fn [[m ns]]
                                   (if (= m "") ns (cons {:node :module :name m} ns)))
                                 pieces))]
              (lower-input u (into (vec @prelude-nodes) u)))))

(defn- rows [title xs]
  (cons (str "(" title " " (count xs) ")") xs))

(defn- sorted-rows [title xs]
  (rows title (sort xs)))

(defn- ser-p [p]
  (if (:is-self? p)
    (str "(self " (:cxc-ty p) ")")
    (str "(p " (:name p) " " (or (:cxc-ty p) "nil") " "
         (if (:nonnil? p) "bang" "plain") " "
         (if (:default p) (ser (:default p)) "nil") ")")))

(defn- ser-protocol [[nm e]]
  (str "(protocol " nm
       " (accepts"
       (apply str (map #(str " (accept " (:name %) " " (ser-params (:params %)) " "
                             (if (:return-type %) (ser-type (:return-type %)) "nil") ")")
                       (:accepts e)))
       ") (conforms" (apply str (map #(str " " (ser-type %)) (:conforms e)))
       ") (defaults"
       (apply str (map #(str " (default " (:name %) " (params"
                             (apply str (map (fn [p] (str " " (ser-p p))) (:params %)))
                             ") " (or (:cxc-return-ty %) "nil") " "
                             (if (:cxc-return-ty-node %) (ser-type (:cxc-return-ty-node %)) "nil")
                             " " (ser (:expr (first (:stmts (:body %))))) ")")
                       (:defaults e)))
       "))"))

(defn- ser-enum-case [c]
  (str "(enum-case " (:name c) " " (ser-params (:payload c))
       (when (:raw-value c) (str " " (:raw-value c))) ")"))

(defn- ser-enum [[nm e]]
  (str "(enum " nm " " (if (:tagged? e) "tagged" "plain")
       " (cases" (apply str (map #(str " " %) (:cases e)))
       ") (case-index" (apply str (map (fn [[c i]] (str " (" c " " i ")")) (sort-by key (:case-index e))))
       ") (entries" (apply str (map #(str " " (ser-enum-case %)) (:case-entries e)))
       ") (payload-index" (apply str (map (fn [[c ps]] (str " (" c " " (ser-params ps) ")"))
                                          (sort-by key (:payload-index e))))
       ") " (if (:raw-values e)
              (str "(raw-values" (apply str (map #(str " " %) (:raw-values e))) ")")
              "nil")
       " " (or (:raw-type e) "nil") ")"))

;; build-atom-env rows (chapter 34).
(defn- ser-afield [f]
  (str "(f " (:name f) " " (or (:cxc-ty f) "nil") " "
       (if (:nonnil? f) "bang" "plain") " " (if (:optional? f) "opt" "raw") " "
       (pr-str (:scalar-c f)) " " (pr-str (:c-type f)) " "
       (if (:default f) (ser (:default f)) "nil") " " (ser-type (:type f))
       (when (:map-key f) (str " (map " (:map-key f) " " (:map-val f) ")"))
       (when (:modifier f) (str " " (name (:modifier f))))
       (when-let [[lo hi] (:picker-bounds f)] (str " (picked " lo " " hi ")"))
       ")"))

(defn- ser-actor [c]
  (str "(c " (:name c) " " (or (:cxc-ty c) "nil") " "
       (if (:nonnil? c) "bang" "plain") " " (pr-str (:c-type c)) " "
       (if (:default c) (ser (:default c)) "nil") " " (ser-type (:type c))
       (when (:map-key c) (str " (map " (:map-key c) " " (:map-val c) ")"))
       ")"))

(defn- ser-method [m]
  (str "(method " (:name m) " (params"
       (apply str (map #(str " " (ser-p %)) (:params m)))
       ") " (ser-block (:body m)) ")"))

(defn- ser-reason [r]
  (if (= :typed (:kind r)) (str "(typed " (:type-name r) ")") "violation"))

(defn- wrap [title xs]
  (str "(" title (apply str (map #(str " " %) xs)) ")"))

(defn- ser-atom [[nm e]]
  (str "(atom " nm " "
       (wrap "fields" (map ser-afield (:fields e))) " "
       (wrap "ctor-params" (map ser-actor (:ctor-params e))) " "
       (wrap "methods" (map ser-method (:methods e))) " "
       (wrap "observables" (map :name (:observables e))) " "
       (wrap "invariants" (map ser-prop (:invariants e))) " "
       (wrap "transitions" (map ser-item (:transitions e))) " "
       (wrap "message-index"
             (map (fn [[m v]] (str "(" m " " (wrap "payload" (map ser-field (:payload v))) ")"))
                  (sort-by key (:message-index e)))) " "
       (wrap "companion-index"
             (map (fn [[m v]] (str "(" m " " (:of-msg v) " " (wrap "reasons" (map ser-reason (:reason-types v))) ")"))
                  (sort-by key (:companion-index e)))) " "
       (wrap "use-caps" (map #(str "(" (:name %) " " (:type-name %) ")") (:use-caps e))) " "
       (wrap "stream-index"
             (map (fn [[m v]] (str "(" m " " (wrap "keys" (map :name (:payload v))) ")"))
                  (sort-by key (:stream-index e)))) " "
       (wrap "stream-order" (:stream-order e)) " "
       (wrap "subscriptions"
             (map (fn [[f ms]] (str "(" f (apply str (map #(str " " %) ms)) ")"))
                  (sort-by key (:subscription-map e)))) " "
       (wrap "slot-names" (:slot-names e)) " "
       (wrap "on-nil" (map (fn [[m v]] (str "(" m " " (ser v) ")")) (sort-by key (:on-nil-entries e)))) " "
       (wrap "extras" (map ser-item (:extras e))) " "
       (wrap "class-fns" (map ser-item (:class-fns e))) " "
       (if-let [q (:queue-config e)]
         (if (= :bounded (:kind q)) (str "(queue bounded " (:capacity q) ")") "(queue unbounded)")
         "nil")
       ")"))

(defn- sections [rec]
  (concat
    (when-let [d (:d->m rec)]
      (sorted-rows "attribution" (map (fn [[k v]] (str "(decl " k " " (name v) ")")) d)))
    (when-let [mm (:module-map rec)]
      (rows "module-map"
            (map (fn [[m ns]] (str "(module " (name m) (apply str (map #(str " " %) (sort ns))) ")"))
                 (sort-by (comp name key) mm))))
    (when-let [s (:shape rec)]
      (concat
        (rows "plugin-shapes"
              (map #(str "(plugin-shape " (if (:name %) (pr-str (:name %)) "nil") " "
                         (name (:shape %)) ")")
                   (:plugin-shapes s)))
        [(str "(runtime-shape " (name (:runtime-shape s)) ")")]))
    (when-let [p (:protocol-env rec)]
      (rows "protocol-env" (map ser-protocol (sort-by key p))))
    (when-let [ed (:edges rec)]
      (sorted-rows "sync-edges" (map (fn [[s t m]] (str "(edge " s " " t " " m ")")) ed)))
    (when-let [en (:enum-env rec)]
      (rows "enum-env" (map ser-enum (sort-by key en))))
    (when-let [ae (:atom-env rec)]
      (rows "atom-env" (map ser-atom (sort-by key ae))))
    (when-let [sh (:shaken rec)]
      (sorted-rows "tree-shake"
                   (map (fn [[k v]] (str "(keep " k
                                         (when (= :runtime-provided (:kind v)) " runtime-provided")
                                         ")"))
                        sh)))))

;; The CxcStream seed and the prelude tree-shake are a run of let bindings
;; inside `lower/lower` between build-atom-env and build-record-env. They
;; are cut out of lower.clj the same way.
(def ^:private shake
  (let [txt  (slurp "src/cxc/lower.clj")
        from (clojure.string/index-of txt "atom-env   (assoc atom-env \"CxcStream\"")
        upto (clojure.string/index-of txt "record-env (build-record-env top-nodes")]
    (binding [*ns* (find-ns 'cxc.lower)]
      (eval (read-string
              (str "(fn [top-nodes atom-env] (let [" (subs txt from upto) "] atom-env))"))))))

;; envs. The builders in lower's order, no `def main` check.
(defn- run-envs [nodes rec]
  (let [top (cxc.resolve/ensure-references-enriched nodes)
        d   (cxc.env/attribute-modules-to-decls top)
        _   (swap! rec assoc :d->m d)
        _   (swap! rec assoc :module-map (cxc.env/module-map-from-attribution d))
        _   (swap! rec assoc :shape (shape-scan top))
        top (vec (remove #(= :def-plugin (:node %)) top))
        _   (swap! rec assoc :protocol-env (cxc.lower.protocol-env/build-protocol-env top))
        _   (swap! rec assoc :edges (cxc.lower.program-scan/sync-send-edges top))
        en  (cxc.lower.enum-env/build-enum-env top)
        _   (swap! rec assoc :enum-env en)]
    (binding [cxc.mangle/*decl->module* d]
      (let [ae (@#'cxc.lower/build-atom-env top en (:module-map @rec))]
        (swap! rec assoc :atom-env ae)
        (swap! rec assoc :shaken (shake top ae))))))

;; lower. The real lower/lower, stopped once build-enum-env returns.
(defn- run-lower [nodes rec]
  (let [attr  cxc.env/attribute-modules-to-decls
        mmap  cxc.env/module-map-from-attribution
        penv  cxc.lower.protocol-env/build-protocol-env
        edges cxc.lower.program-scan/sync-send-edges
        eenv  cxc.lower.enum-env/build-enum-env
        aenv  @#'cxc.lower/build-atom-env]
    (try
      (with-redefs-fn
        {#'cxc.lower/build-atom-env
         (fn [t e m] (let [a (aenv t e m)]
                       (swap! rec assoc :atom-env a)
                       a))
         #'cxc.lower.record-env/build-record-env
         (fn [t a e m]
           (swap! rec assoc :shaken a)
           (throw (ex-info "slice-39 stop" {::stop true})))}
       (fn []
      (with-redefs [cxc.env/attribute-modules-to-decls
                    (fn [t] (let [d (attr t)] (swap! rec assoc :d->m d) d))
                    cxc.env/module-map-from-attribution
                    (fn [d] (let [m (mmap d)] (swap! rec assoc :module-map m) m))
                    cxc.lower.protocol-env/build-protocol-env
                    (fn [t] (let [p (penv t)] (swap! rec assoc :protocol-env p) p))
                    cxc.lower.program-scan/sync-send-edges
                    (fn [t] (let [e (edges t)] (swap! rec assoc :edges e) e))
                    cxc.lower.program-scan/warn-sync-send-cycles! (fn [_] #{})
                    cxc.lower.enum-env/build-enum-env
                    (fn [t] (let [e (eenv t)]
                              (swap! rec assoc :enum-env e)
                              e))]
        (cxc.lower/lower nodes))))
      (catch clojure.lang.ExceptionInfo e
        (when-not (::stop (ex-data e)) (throw e))))))

(defn- run-row [mode payload]
  (let [[kind check] (clojure.string/split mode #"-")
        nodes (try (stream kind payload)
                   (catch Exception e (str "error|" (.getMessage e))))]
    (if (string? nodes)
      nodes
      (let [rec (atom {})
            err (try
                  (if (= check "lower") (run-lower nodes rec) (run-envs nodes rec))
                  nil
                  (catch Exception e (.getMessage e)))
            ;; In lower mode the shape value is a local. The same code runs
            ;; again on the same enriched stream, and the section prints
            ;; when the scan finished inside lower (it did not throw).
            _   (when (and (= check "lower") (:module-map @rec))
                  (try (swap! rec assoc :shape
                              (shape-scan (cxc.resolve/ensure-references-enriched nodes)))
                       (catch Exception _ nil)))]
        (clojure.string/join "\n"
          (concat (sections @rec)
                  (when err [(str "error|" err)])))))))

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [bar (clojure.string/index-of ln "|")]
    (println (run-row (subs ln 0 bar) (subs ln (inc bar))))))

)
