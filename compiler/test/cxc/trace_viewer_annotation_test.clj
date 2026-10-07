(ns cxc.trace-viewer-annotation-test
  (:require [etaoin.api :as e]
            [etaoin.keys :as k]
            #_{:clj-kondo/ignore [:refer-all]}
            [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str])
  (:import [java.io File IOException]
           [java.net HttpURLConnection ServerSocket URI]
           [java.time Instant]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

;; Etaoin 1.1.43 uses an installed WebDriver; we deliberately do not download
;; drivers in tests. Browser cases print SKIP when chromedriver is not on PATH:
;;   brew install chromedriver
;;   xattr -d com.apple.quarantine "$(command -v chromedriver)"
;; Use a driver matching Chrome. Headless is the default; HEADED=1 opts out.
;; Run from compiler/: clj -X:test :nses '[cxc.trace-viewer-annotation-test]'
(def ^:private compiler-dir (.getCanonicalFile (io/file ".")))
(def ^:private sample "sample-01-single-atom")
(def ^:private trace-name (str sample ".trace.jsonl"))
(def ^:private wait-opts {:timeout 15 :interval 0.1})
(def ^:dynamic *scratch-dir* nil)

(use-fixtures :each
  (fn [test-fn]
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "cxc-annotation-flow-" (UUID/randomUUID)))]
      (when-not (.mkdir dir)
        (throw (ex-info "Cannot create annotation test directory" {:dir dir})))
      (try
        ;; These tracked snapshots came from research/; never write there.
        (doseq [suffix [".shape.json" ".trace.jsonl" ".annotations.json"]]
          (io/copy (io/file compiler-dir "test/cxc/fixtures/annotation_flow"
                            (str sample suffix))
                   (io/file dir (str sample suffix))))
        (binding [*scratch-dir* dir] (test-fn))
        (finally
          (doseq [f (reverse (file-seq dir))]
            (io/delete-file f)))))))

(defn- sidecar-file []
  (io/file *scratch-dir* (str sample ".annotations.json")))

(defn- read-sidecar []
  (json/read-str (slurp (sidecar-file)) :key-fn keyword))

(defn- executable-on-path [name]
  (some (fn [dir]
          (let [f (io/file dir name)]
            (when (and (.isFile f) (.canExecute f)) (.getCanonicalPath f))))
        (str/split (or (System/getenv "PATH") "")
                   (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))))

(defn- cli-command []
  (let [cxc (executable-on-path "cxc")
        wrapper (.getCanonicalPath (io/file compiler-dir "bin/cxc"))]
    ;; The current in-repo wrapper rewrites annotation/list and flag values
    ;; to absolute paths. Use the verified cxc.core/-main entry point for it
    ;; as well as when cxc is absent; other installed launchers are used as-is.
    (if (and cxc (not= cxc wrapper))
      [cxc]
      [(or (executable-on-path "clojure") "clojure") "-M" "-m" "cxc.core"])))

(defn- free-port []
  ;; Release the OS-selected ephemeral port before the child binds it. The
  ;; usual small probe/bind race is bounded by startup polling and diagnostics.
  (with-open [socket (ServerSocket. 0)] (.getLocalPort socket)))

(defn- server-ready? [url]
  (try
    (let [^HttpURLConnection conn (.openConnection (.toURL (URI. url)))]
      (try
        (.setConnectTimeout conn 300)
        (.setReadTimeout conn 300)
        (and (= 200 (.getResponseCode conn))
             (with-open [in (.getInputStream conn)]
               (= 1 (:cxc_annotation_version
                     (json/read-str (slurp in) :key-fn keyword)))))
        (finally (.disconnect conn))))
    (catch IOException _ false)))

(defn- stop-server! [^Process process]
  ;; Snapshot descendants before stopping the launcher, in case it forks Java.
  (with-open [descendants (.descendants process)]
    (doseq [handle (reverse (vec (.toArray descendants)))]
      (.destroyForcibly ^java.lang.ProcessHandle handle)))
  (.destroy process)
  (when-not (.waitFor process 5 TimeUnit/SECONDS)
    (.destroyForcibly process)
    (.waitFor process 5 TimeUnit/SECONDS)))

(defn- with-viewer [test-fn]
  (if-let [chromedriver (executable-on-path "chromedriver")]
    (let [port (free-port)
          base-url (str "http://127.0.0.1:" port)
          log (io/file *scratch-dir* "server.log")
          command (into (cli-command)
                        ["trace" "serve" "--port" (str port)
                         "--trace-dir" (.getAbsolutePath ^File *scratch-dir*)])
          process (.start (doto (ProcessBuilder. ^java.util.List command)
                            (.directory compiler-dir)
                            (.redirectErrorStream true)
                            (.redirectOutput log)))]
      (try
        (try
          (e/wait-predicate
           #(if (.isAlive process)
              (server-ready? (str base-url "/annotations?trace=" trace-name))
              (throw (ex-info "Trace server exited" {:exit (.exitValue process)})))
           {:timeout 30 :interval 0.1 :message "Trace server startup"})
          (catch Exception ex
            (throw (ex-info (str "Trace server did not start:\n" (slurp log))
                            {:command command} ex))))
        (let [chrome (io/file "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
              opts (cond-> {:path-driver chromedriver
                            :headless (not= "1" (System/getenv "HEADED"))
                            :size [1440 1100]}
                     (.isFile chrome) (assoc :path-browser (.getPath chrome)))
              driver (e/chrome opts)]
          (try
            (e/go driver (str base-url "/trace-viewer-legacy.html?trace=" trace-name))
            (e/wait-has-text driver {:id "annotations-count"} "(5)" wait-opts)
            ;; Wait for the companion shape's final render, rather than the
            ;; earlier trace-only topology that loadShape replaces async.
            (e/wait-has-text driver {:css "svg[data-shape-view]"}
                             "1 phases · 3 arms" wait-opts)
            (test-fn driver)
            (finally (e/quit driver))))
        (finally (stop-server! process))))
    ;; clojure.test has no built-in skip result: report it explicitly without
    ;; inventing a passing assertion. The two CLI deftests still run normally.
    (println "SKIP" (:name (meta (first *testing-vars*)))
             "— chromedriver missing on PATH. Run: brew install chromedriver;"
             "xattr -d com.apple.quarantine \"$(command -v chromedriver)\"")))

(defn- draft-row [id]
  {:css (str "#annotations-panel .ann-draft[data-ann-id='" id "']")})

(defn- save-popup! [driver]
  (e/click driver {:id "annotate-note"})
  (let [modifier (if (str/starts-with? (System/getProperty "os.name") "Mac")
                   k/command k/control-left)]
    (try
      (e/perform-actions driver
                         (-> (e/make-key-input)
                             (e/add-key-down modifier)
                             (e/add-key-press k/enter)
                             (e/add-key-up modifier)))
      (finally (e/release-actions driver))))
  (e/wait-invisible driver {:id "annotate-popup"} wait-opts))

(defn- create-draft! [driver note]
  (let [counter {:css "svg[data-shape-view] rect[data-node-name='Counter']"}]
    (e/wait-visible driver counter wait-opts)
    ;; Real WebDriver modifier + click, exercising the graph's shift handler.
    (try
      (e/perform-actions driver (e/add-key-down (e/make-key-input) k/shift-left))
      (e/click driver counter)
      (finally (e/release-actions driver)))
    (e/wait-visible driver {:id "annotate-popup"} wait-opts)
    (e/fill driver {:id "annotate-note"} note)
    (save-popup! driver)
    (last (:annotations (read-sidecar)))))

(deftest loads-existing-open-annotations
  (with-viewer
    (fn [driver]
      (is (= 5 (count (e/query-all driver {:css "#annotations-panel .ann-nondraft"}))))
      (is (= "(5)" (str/trim (e/get-element-text driver {:id "annotations-count"}))))
      (is (e/exists? driver {:xpath "//*[@id='annotations-panel']//span[normalize-space(.)='OPEN 5']"}))
      (is (= (set (map :id (:annotations (read-sidecar))))
             (set (map #(e/get-element-attr-el driver % "data-ann-id")
                       (e/query-all driver {:css "#annotations-panel .ann-row"})))))
      (is (e/absent? driver {:css "#annotations-panel .ann-draft"}))
      (is (e/absent? driver {:id "ann-submit-review"}))
      (is (not (str/includes? (str/lower-case (e/get-element-text driver {:id "annotations-panel"}))
                             "drafts ("))))))

(deftest writes-new-annotation-as-draft
  (with-viewer
    (fn [driver]
      (let [before (read-sidecar)
            note "Counter bump needs a closer review"
            draft (create-draft! driver note)]
        (is (e/invisible? driver {:id "annotate-popup"}))
        (is (str/includes? (str/lower-case (e/get-element-text driver {:id "annotations-panel"}))
                           "drafts (1)"))
        (is (e/has-text? driver (draft-row (:id draft)) note))
        (is (= "draft" (:status draft)))
        (is (= "ann_06" (:id draft)))
        (is (= "node" (:kind draft)))
        (is (= {:atom "Counter"} (:target draft)))
        (is (= note (:note draft)))
        (is (= 6 (count (:annotations (read-sidecar)))))
        (is (= (:annotations before) (vec (butlast (:annotations (read-sidecar))))))))))

(deftest edits-draft-in-place
  (with-viewer
    (fn [driver]
      (let [draft (create-draft! driver "Initial Counter note")
            id (:id draft)
            note "Updated Counter note"]
        (e/click driver {:css (str "#annotations-panel .ann-draft[data-ann-id='" id
                                  "'] [data-ann-click='edit']")})
        (e/wait-visible driver {:id "annotate-popup"} wait-opts)
        ;; The title is visually uppercased by CSS; textContent preserves case.
        (is (= (str "edit " id)
               (e/get-element-property driver {:id "annotate-popup-title"} "textContent")))
        (is (= (:note draft) (e/get-element-value driver {:id "annotate-note"})))
        ;; Viewer timestamps have second precision. Cross that boundary so an
        ;; edit stamped with the old created_at cannot accidentally pass.
        (e/wait-predicate #(.isAfter (Instant/now)
                                    (.plusSeconds (Instant/parse (:created_at draft)) 1))
                          wait-opts)
        (e/clear driver {:id "annotate-note"})
        (e/fill driver {:id "annotate-note"} note)
        (save-popup! driver)
        (e/wait-has-text driver (draft-row id) note wait-opts)
        (let [records (:annotations (read-sidecar))
              edited (first (filter #(= id (:id %)) records))]
          (is (= 6 (count records)))
          (is (= 1 (count (filter #(= id (:id %)) records))))
          (is (e/has-text? driver (draft-row id) note))
          (is (= note (:note edited)))
          (is (= (:created_at draft) (:created_at edited)))
          (is (string? (:updated_at edited)))
          (is (.isAfter (Instant/parse (:updated_at edited))
                        (Instant/parse (:created_at draft))))
          (is (= (dissoc draft :note) (dissoc edited :note :updated_at))))))))

(deftest deletes-draft
  (with-viewer
    (fn [driver]
      (let [draft (create-draft! driver "Discard this Counter draft")
            id (:id draft)
            before (read-sidecar)]
        (e/click driver {:css (str "#annotations-panel .ann-draft[data-ann-id='" id
                                  "'] [data-ann-click='delete']")})
        (e/wait-has-alert driver wait-opts)
        (is (str/includes? (e/get-alert-text driver) id))
        (e/accept-alert driver)
        (e/wait-absent driver (draft-row id) wait-opts)
        (is (e/absent? driver (draft-row id)))
        (is (e/absent? driver {:id "ann-submit-review"}))
        (is (= (dec (count (:annotations before))) (count (:annotations (read-sidecar)))))
        (is (= (update before :annotations #(vec (remove (fn [a] (= id (:id a))) %)))
               (read-sidecar)))))))

(deftest submits-review-flips-all-drafts
  (with-viewer
    (fn [driver]
      (let [before (read-sidecar)
            drafts [(create-draft! driver "Review Counter bump")
                    (create-draft! driver "Review Counter reset")]
            submit-start (Instant/now)]
        (is (str/includes? (str/lower-case (e/get-element-text driver {:id "annotations-panel"}))
                           "drafts (2)"))
        (is (e/has-text? driver {:id "ann-submit-review"} "submit to agent →"))
        (e/click driver {:id "ann-submit-review"})
        (e/wait-has-alert driver wait-opts)
        (is (str/includes? (e/get-alert-text driver) "2 draft(s)"))
        (e/accept-alert driver)
        (e/wait-absent driver {:id "ann-submit-review"} wait-opts)
        (is (e/absent? driver {:css "#annotations-panel .ann-draft"}))
        (is (not (str/includes? (str/lower-case (e/get-element-text driver {:id "annotations-panel"}))
                               "drafts (")))
        (is (e/exists? driver {:xpath "//*[@id='annotations-panel']//span[normalize-space(.)='OPEN 7']"}))
        (is (= "(7)" (str/trim (e/get-element-text driver {:id "annotations-count"}))))
        (is (= 7 (count (e/query-all driver {:css "#annotations-panel .ann-nondraft"}))))
        (let [after (read-sidecar)
              by-id (into {} (map (juxt :id identity) (:annotations after)))]
          (is (= 7 (count (:annotations after))))
          (is (= (dissoc before :annotations) (dissoc after :annotations)))
          (is (= (:annotations before) (mapv by-id (map :id (:annotations before)))))
          (doseq [draft drafts
                  :let [submitted (by-id (:id draft))]]
            (is (= "open" (:status submitted)))
            (is (string? (:submitted_at submitted)))
            (let [at (Instant/parse (:submitted_at submitted))]
              (is (not (.isBefore at (.minusSeconds submit-start 1))))
              (is (not (.isAfter at (Instant/now)))))
            (is (= (dissoc draft :status)
                   (dissoc submitted :status :submitted_at)))))))))

(defn- seed-cli-annotations! []
  (let [sidecar (read-sidecar)
        [a b] (:annotations sidecar)
        records [(assoc a :status "draft" :note "private draft note" :comments [])
                 (assoc b :status "open" :note "submitted open note" :comments [])]]
    (spit (sidecar-file) (json/write-str (assoc sidecar :annotations records)))
    records))

(defn- list-annotations [& flags]
  (let [args (concat (cli-command) ["trace" "annotation" "list" "--trace"
                                    (.getAbsolutePath (io/file *scratch-dir* trace-name))]
                     flags [:dir (.getPath compiler-dir)])
        {:keys [exit out err]} (apply sh/sh args)]
    (is (zero? exit) (str "Annotation CLI exited " exit ": " err))
    out))

(deftest cli-hides-drafts-by-default
  ;; CLI tests never start the server or browser.
  (let [[draft open] (seed-cli-annotations!)
        before (read-sidecar)]
    (doseq [[flags expected hidden]
            [[[] [open] [draft]]
             [["--include-drafts"] [draft open] []]
             [["--status" "draft"] [draft] [open]]]]
      (let [out (apply list-annotations flags)]
        (doseq [a expected]
          (is (str/includes? out (:id a)))
          (is (str/includes? out (:note a))))
        (doseq [a hidden]
          (is (not (str/includes? out (:id a))))
          (is (not (str/includes? out (:note a)))))))
    (is (= before (read-sidecar)) "Listing must not modify the sidecar")))

(deftest cli-list-json-roundtrip
  (let [records (seed-cli-annotations!)
        before (read-sidecar)
        listed (json/read-str (list-annotations "--include-drafts" "--format" "json")
                              :key-fn keyword)]
    ;; list --format json is an array of schema-v1 records, not the sidecar
    ;; envelope. Compare complete records so optional fields survive the CLI.
    (is (= 1 (:cxc_annotation_version before)))
    (is (vector? listed))
    (is (= records listed))
    (doseq [a listed]
      (is (every? #(contains? a %)
                  [:id :kind :target :note :severity :author :created_at :status :comments]))
      (is (re-matches #"ann_\d{2,}" (:id a)))
      (is (contains? #{"draft" "open"} (:status a)))
      (is (map? (:target a)))
      (is (vector? (:comments a)))
      (is (instance? Instant (Instant/parse (:created_at a)))))
    (is (= listed (json/read-str (json/write-str listed) :key-fn keyword)))
    (is (= before (read-sidecar)))))
