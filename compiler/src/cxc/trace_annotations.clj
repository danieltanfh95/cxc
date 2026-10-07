(ns cxc.trace-annotations
  "Trace annotation CLI, read + write surface for the agent-side of the
   trace-review loop. Sidecars live at <path>/<name>.annotations.json
   next to <path>/<name>.trace.jsonl, both tracked in git.

   See docs/design/trace-annotation.md for the schema and the lifecycle
   state machine. This namespace is the one place that writes the
   sidecar; the server (cxc.trace-server) is the one place that writes
   it from a browser POST."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [cxc.trace-schema :as schema]
            [cxc.util.chatlog :as chatlog]
            [cxc.util.cli :as cli]))

(defn- json-read-str [s]
  ((requiring-resolve 'clojure.data.json/read-str) s :key-fn keyword))

(defn- json-write-str [v]
  ((requiring-resolve 'clojure.data.json/write-str) v :indent 2))

;; ---------- paths ----------

;; Shared with the CLJS viewer so one string transform decides the sidecar
;; key on both sides.
(def trace->annotations-path schema/trace->annotations-path)

(defn trace->focus-path [trace-dir]
  (str (io/file trace-dir "cxc-trace-focus.json")))

;; ---------- sidecar I/O ----------

;; Re-exported so existing require sites (`ann/empty-sidecar`) keep working.
(def empty-sidecar schema/empty-sidecar)

(defn read-sidecar
  "Read the annotation sidecar at path. Returns the empty structure when
   missing, so callers never have to branch on existence."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (json-read-str (slurp f))
      (empty-sidecar "" "" ""))))

(defn write-sidecar [path sidecar]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f (json-write-str sidecar))))

;; ---------- git-driven defaults ----------

(defn- git-user-name []
  (let [{:keys [exit out]} (sh/sh "git" "config" "user.name")]
    (when (zero? exit) (str/trim out))))

(defn- git-head-sha []
  (let [{:keys [exit out]} (sh/sh "git" "rev-parse" "HEAD")]
    (when (zero? exit) (str/trim out))))

(defn- now-iso []
  (-> (java.time.Instant/now)
      (.truncatedTo java.time.temporal.ChronoUnit/SECONDS)
      str))

;; ---------- id generation ----------

(defn- next-id [anns]
  (let [nums (keep (fn [{:keys [id]}]
                     (when-let [m (re-matches #"ann_(\d+)" (or id ""))]
                       (Long/parseLong (second m))))
                   anns)
        n    (inc (apply max 0 nums))]
    (format "ann_%02d" n)))

;; ---------- lifecycle write primitives ----------

(defn- find-ann [sidecar id]
  (first (filter #(= id (:id %)) (:annotations sidecar))))

(defn- update-ann [sidecar id f]
  (update sidecar :annotations
          (fn [anns] (mapv (fn [a] (if (= id (:id a)) (f a) a)) anns))))

(def ^:private terminal? schema/terminal?)

(defn- fail-write! [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

;; ---------- filtering + target formatting ----------
;;
;; Shared with the CLJS viewer so the status filter and the target one-liner
;; agree across every surface that renders them.

(def ^:private effective-status schema/effective-status)
(def filter-annotations          schema/filter-annotations)
(def target-brief                schema/target-brief)

;; ---------- list ----------

(defn- fmt-cell [s w]
  (let [s (str s)]
    (if (> (count s) w)
      (str (subs s 0 (max 0 (- w 1))) "…")
      (format (str "%-" w "s") s))))

(defn print-list [anns {:keys [format]}]
  (case format
    "json"
    (println (json-write-str (vec anns)))

    (do
      (println
       (str (fmt-cell "ID" 8)       " "
            (fmt-cell "STATUS" 12)  " "
            (fmt-cell "SEV" 8)      " "
            (fmt-cell "KIND" 20)    " "
            "NOTE"))
      (println (apply str (repeat 80 \-)))
      (doseq [a anns]
        (println
         (str (fmt-cell (:id a) 8) " "
              (fmt-cell (effective-status a) 12) " "
              (fmt-cell (:severity a) 8) " "
              (fmt-cell (str (:kind a) " " (target-brief (:target a))) 20) " "
              (fmt-cell (or (:note a) "") 40))))
      (println)
      (println (str (count anns) " annotation(s)")))))

;; ---------- show ----------

(defn- indent [s n]
  (let [pad (apply str (repeat n \space))]
    (->> (str/split-lines (str s))
         (map #(str pad %))
         (str/join "\n"))))

(defn- read-trace-context
  "Pull the trace line(s) a target references, plus a couple of neighbours.
   Return nil if the trace file isn't present or the seq isn't found."
  [trace-path target]
  (when (and trace-path (:trace_seq target))
    (let [f (io/file trace-path)]
      (when (.exists f)
        (let [centre  (:trace_seq target)
              lo      (max 1 (- centre 2))
              hi      (+ centre 2)
              lines   (with-open [r (io/reader f)]
                        (vec (line-seq r)))
              matches (into {}
                            (keep (fn [line]
                                    (when-let [m (try (json-read-str line) (catch Throwable _ nil))]
                                      (when (and (:seq m) (<= lo (:seq m) hi))
                                        [(:seq m) line]))))
                            lines)]
          (when (seq matches)
            (->> (sort (keys matches))
                 (map (fn [k] (str "  seq=" k " " (get matches k))))
                 (str/join "\n"))))))))

(defn print-annotation [ann trace-path]
  (println (str "id:         " (:id ann)))
  (println (str "kind:       " (:kind ann)))
  (println (str "target:     " (target-brief (:target ann))))
  (println (str "severity:   " (:severity ann)))
  (println (str "status:     " (effective-status ann)))
  (when (:claimed_by ann)
    (println (str "claimed_by: " (:claimed_by ann)
                  " at " (:claimed_at ann))))
  (println (str "author:     " (:author ann)))
  (println (str "created_at: " (:created_at ann)))
  (when-let [n (:note ann)]
    (println "note:")
    (println (indent n 2)))
  (when-let [p (:proposed_fix ann)]
    (println "proposed_fix:")
    (println (indent p 2)))
  (when (seq (:comments ann))
    (println "comments:")
    (doseq [c (:comments ann)]
      (println (str "  - [" (:by c) " @ " (:at c) "]"
                    (when (:linked_commit c) (str " commit=" (:linked_commit c)))))
      (println (indent (:text c) 4))))
  (when-let [r (:resolution ann)]
    (println "resolution:")
    (println (str "  kind:          " (:kind r)))
    (println (str "  by:            " (:by r) " at " (:at r)))
    (when (:linked_commit r)
      (println (str "  linked_commit: " (:linked_commit r))))
    (when (:summary r)
      (println "  summary:")
      (println (indent (:summary r) 4))))
  (when-let [ctx (read-trace-context trace-path (:target ann))]
    (println "trace context:")
    (println ctx)))

;; ---------- flag parsing ----------

(defn- resolve-trace-path
  "Flag --trace wins; else probe common locations."
  [flags cwd]
  (or (:trace flags)
      (let [candidates (for [f (.listFiles (io/file cwd))
                             :when (and (.isFile f)
                                        (str/ends-with? (.getName f) ".trace.jsonl"))]
                         (.getPath f))]
        (first candidates))))

;; ---------- dispatch ----------

(defn- usage []
  (println "Usage:")
  (println "  cxc trace annotation list [--status X] [--severity Y] [--kind Z] [--trace path] [--format table|json] [--include-drafts]")
  (println "  cxc trace annotation show <id> [--trace path]")
  (println "")
  (println "Phase-2 subcommands (lifecycle writes) are implemented in a later pass:")
  (println "  cxc trace annotation claim     <id>")
  (println "  cxc trace annotation comment   <id> --text ...  [--commit sha]")
  (println "  cxc trace annotation addressed <id> --commit sha --summary ...")
  (println "  cxc trace annotation wontfix   <id> --reason ...")
  (println "  cxc trace annotation verified  <id>")
  (println "  cxc trace annotate --from-focus --note ... --severity X --kind Y"))

(declare run)

(defn- run-list [flags]
  (let [cwd  (System/getProperty "user.dir")
        tp   (resolve-trace-path flags cwd)
        ap   (when tp (trace->annotations-path tp))
        side (if ap (read-sidecar ap) (empty-sidecar "" "" ""))
        anns (filter-annotations (:annotations side) flags)
        ;; Drafts are the human's in-progress review; agents should not
        ;; see them until the human runs "submit review" from the viewer.
        ;; An explicit --status draft or --include-drafts turns them on.
        hide-drafts? (and (not (:status flags))
                          (not (:include-drafts flags)))
        anns (if hide-drafts?
               (remove #(= "draft" (effective-status %)) anns)
               anns)]
    (print-list anns {:format (or (:format flags) "table")})))

(defn- run-show [flags]
  (let [id   (first (::positional flags))
        _    (when-not id
               (binding [*out* *err*] (println "missing <id>"))
               (System/exit 2))
        cwd  (System/getProperty "user.dir")
        tp   (resolve-trace-path flags cwd)
        ap   (when tp (trace->annotations-path tp))
        side (if ap (read-sidecar ap) (empty-sidecar "" "" ""))
        ann  (first (filter #(= id (:id %)) (:annotations side)))]
    (if ann
      (print-annotation ann tp)
      (do (binding [*out* *err*] (println (str "annotation not found: " id)))
          (System/exit 1)))))

(defn- open-sidecar [flags cwd]
  (let [tp   (resolve-trace-path flags cwd)
        _    (when-not tp
               (fail-write! "no --trace specified and no *.trace.jsonl found in cwd"))
        ap   (trace->annotations-path tp)]
    {:ann-path   ap
     :trace-path tp
     :sidecar    (read-sidecar ap)}))

(defn- require-id [flags]
  (or (first (::positional flags))
      (fail-write! "missing <id>")))

(defn- run-claim [flags]
  (let [id   (require-id flags)
        who  (or (:as flags) (git-user-name) "agent")
        cwd  (System/getProperty "user.dir")
        {:keys [ann-path sidecar]} (open-sidecar flags cwd)
        ann  (find-ann sidecar id)]
    (when-not ann (fail-write! (str "annotation not found: " id)))
    (let [status (effective-status ann)]
      (cond
        (terminal? status)
        (fail-write! (str "annotation " id " is already " status ", refusing to claim"))

        (and (= status "in_progress")
             (:claimed_by ann)
             (not= (:claimed_by ann) who))
        (fail-write! (str "annotation " id " already claimed by "
                          (:claimed_by ann) " at " (:claimed_at ann)))

        :else
        (do (write-sidecar ann-path
                    (update-ann sidecar id
                                #(assoc % :status     "in_progress"
                                          :claimed_by who
                                          :claimed_at (now-iso))))
            (println (str id " claimed by " who)))))))

(defn- run-comment [flags]
  (let [id    (require-id flags)
        text  (or (:text flags) (fail-write! "missing --text \"...\""))
        who   (or (:as flags) (git-user-name) "agent")
        comm  {:by   who
               :at   (now-iso)
               :text text}
        comm  (cond-> comm
                (:commit flags) (assoc :linked_commit (:commit flags)))
        cwd   (System/getProperty "user.dir")
        {:keys [ann-path sidecar]} (open-sidecar flags cwd)]
    (when-not (find-ann sidecar id)
      (fail-write! (str "annotation not found: " id)))
    (write-sidecar ann-path
            (update-ann sidecar id
                        #(update % :comments (fnil conj []) comm)))
    (println (str "commented on " id " as " who))))

(defn- run-addressed [flags]
  (let [id      (require-id flags)
        commit  (or (:commit flags) (git-head-sha)
                    (fail-write! "no --commit and git HEAD unavailable"))
        summary (or (:summary flags) (fail-write! "missing --summary \"...\""))
        who     (or (:as flags) (git-user-name) "agent")
        cwd     (System/getProperty "user.dir")
        {:keys [ann-path sidecar]} (open-sidecar flags cwd)
        ann     (find-ann sidecar id)]
    (when-not ann (fail-write! (str "annotation not found: " id)))
    (when (terminal? (effective-status ann))
      (fail-write! (str "annotation " id " already terminal (" (effective-status ann) ")")))
    (write-sidecar ann-path
            (update-ann sidecar id
                        #(assoc % :status     "addressed"
                                  :resolution {:by            who
                                               :at            (now-iso)
                                               :kind          "fix_landed"
                                               :linked_commit commit
                                               :summary       summary})))
    (println (str id " marked addressed, commit=" commit))))

(defn- run-wontfix [flags]
  (let [id     (require-id flags)
        reason (or (:reason flags) (fail-write! "missing --reason \"...\""))
        who    (or (:as flags) (git-user-name) "agent")
        cwd    (System/getProperty "user.dir")
        {:keys [ann-path sidecar]} (open-sidecar flags cwd)
        ann    (find-ann sidecar id)]
    (when-not ann (fail-write! (str "annotation not found: " id)))
    (when (terminal? (effective-status ann))
      (fail-write! (str "annotation " id " already terminal (" (effective-status ann) ")")))
    (write-sidecar ann-path
            (update-ann sidecar id
                        #(assoc % :status     "wontfix"
                                  :resolution {:by      who
                                               :at      (now-iso)
                                               :kind    "wontfix"
                                               :summary reason})))
    (println (str id " marked wontfix"))))

(defn- run-verified [flags]
  (let [id   (require-id flags)
        who  (or (:as flags) (git-user-name) "human")
        cwd  (System/getProperty "user.dir")
        {:keys [ann-path sidecar]} (open-sidecar flags cwd)
        ann  (find-ann sidecar id)]
    (when-not ann (fail-write! (str "annotation not found: " id)))
    (when-not (= "addressed" (effective-status ann))
      (fail-write! (str id " must be 'addressed' before verified, found "
                        (effective-status ann))))
    (write-sidecar ann-path
            (update-ann sidecar id
                        #(-> %
                             (assoc :status "verified")
                             (update :resolution assoc
                                     :verified_by who
                                     :verified_at (now-iso)))))
    (println (str id " marked verified"))))

;; ---------- focus ----------

(defn- run-focus-cmd [flags]
  (let [cwd (System/getProperty "user.dir")
        tp  (resolve-trace-path flags cwd)
        td  (if tp (.getParent (io/file tp)) cwd)
        fp  (trace->focus-path td)
        f   (io/file fp)]
    (if (.exists f)
      (println (slurp f))
      (do (binding [*out* *err*] (println (str "no focus sidecar at " fp)))
          (System/exit 1)))))

;; ---------- annotate at focus ----------

(defn- run-annotate [flags]
  (let [cwd    (System/getProperty "user.dir")
        tp     (resolve-trace-path flags cwd)
        _      (when-not tp (fail-write! "no --trace specified and no *.trace.jsonl in cwd"))
        ap     (trace->annotations-path tp)
        side   (read-sidecar ap)
        focus  (when (:from-focus flags)
                 (let [fp (trace->focus-path (.getParent (io/file tp)))
                       f  (io/file fp)]
                   (if (.exists f)
                     (json-read-str (slurp f))
                     (fail-write! (str "no focus sidecar at " fp)))))
        kind   (or (:kind flags)
                   (cond
                     (:selected_edge focus)             "edge"
                     (and (:atom focus) (:seq focus))   "event"
                     (:atom focus)                      "node"
                     :else                              "node"))
        target (or (when focus
                     (cond
                       (= kind "event")
                       {:trace_seq (:seq focus) :atom (:atom focus)}
                       (= kind "node")
                       {:atom (:atom focus)}
                       (= kind "edge")
                       (:selected_edge focus)
                       :else
                       {:atom (:atom focus)}))
                   (fail-write! "need either --from-focus or an explicit --target JSON"))
        note   (or (:note flags) (fail-write! "missing --note \"...\""))
        sev    (or (:severity flags) (fail-write! "missing --severity bug|finding|advisory|praise"))
        who    (or (:as flags) (git-user-name) "human")
        id     (next-id (:annotations side))
        new    (cond-> {:id         id
                        :kind       kind
                        :target     target
                        :note       note
                        :severity   sev
                        :author     who
                        :created_at (now-iso)
                        :status     "open"
                        :comments   []}
                 (:proposed-fix flags) (assoc :proposed_fix (:proposed-fix flags)))]
    (write-sidecar ap (update side :annotations (fnil conj []) new))
    (println (str "created " id))))

(defn- freeze-comment [c]
  (if-not (and (= "pending" (:kind c)) (:token c))
    c
    (let [hits (chatlog/scan-for-token (:token c))]
      (if (empty? hits)
        c
        {:by   (:by c)
         :at   (:at c)
         :text (str/join "\n\n---\n\n"
                         (map (fn [h] (str (:timestamp h) "\n" (:text h))) hits))}))))

(defn- run-freeze
  "Resolve every `pending` comment's `token` to the matching assistant
   turn(s) in ~/.claude/projects/*/*.jsonl and inline the body into the
   sidecar as an ordinary comment. Strips the pending/token fields on
   success. Idempotent — pending comments with no match are left alone
   so a later run can pick them up."
  [flags]
  (let [cwd  (System/getProperty "user.dir")
        tp   (resolve-trace-path flags cwd)
        ap   (when tp (trace->annotations-path tp))
        _    (when-not ap
               (binding [*out* *err*]
                 (println "no --trace specified and no *.trace.jsonl in cwd"))
               (System/exit 2))
        side    (read-sidecar ap)
        updated (update side :annotations
                        (fn [anns]
                          (mapv (fn [a] (update a :comments #(mapv freeze-comment %)))
                                anns)))
        pending-count (fn [sc]
                        (count (filter #(= "pending" (:kind %))
                                       (mapcat :comments (:annotations sc)))))
        n-frozen (- (pending-count side) (pending-count updated))]
    (write-sidecar ap updated)
    (println (str "froze " n-frozen " pending comment(s) in " ap))))

(defn run
  "Entry from cxc.core/run-trace. Args is the sequence after `cxc trace`,
   so a user invocation `cxc trace annotation list` arrives here as
   (\"annotation\" \"list\")."
  [args]
  (let [[group & rest] args]
    (case group
      "annotation"
      (let [[sub & more] rest]
        (case sub
          "list"      (run-list      (cli/parse-flags (vec more)))
          "show"      (run-show      (cli/parse-flags (vec more)))
          "claim"     (run-claim     (cli/parse-flags (vec more)))
          "comment"   (run-comment   (cli/parse-flags (vec more)))
          "addressed" (run-addressed (cli/parse-flags (vec more)))
          "wontfix"   (run-wontfix   (cli/parse-flags (vec more)))
          "verified"  (run-verified  (cli/parse-flags (vec more)))
          "freeze"    (run-freeze    (cli/parse-flags (vec more)))
          (do (usage) (System/exit (if sub 2 0)))))

      "annotate"
      (run-annotate (cli/parse-flags (vec rest)))

      "focus"
      (run-focus-cmd (cli/parse-flags (vec rest)))

      (do (usage) (System/exit (if group 2 0))))))
