(ns cxc.trace-viewer-annotation-test
  (:require #_{:clj-kondo/ignore [:refer-all]}
            [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str])
  (:import [java.io File]
           [java.time Instant]
           [java.util UUID]))

;; CLI-only coverage of the annotation sidecar. Browser flows for the
;; v2 CLJS viewer live in the Playwright e2e scripts under /tmp (CAS,
;; schema, filter, zoom, legend, drop, attach, create, reply, focus);
;; the retired legacy single-file viewer had its selectors tied up with
;; chromedriver, which is not available in CI.
(def ^:private compiler-dir (.getCanonicalFile (io/file ".")))
(def ^:private sample "sample-01-single-atom")
(def ^:private trace-name (str sample ".trace.jsonl"))
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
    (if (and cxc (not= cxc wrapper))
      [cxc]
      [(or (executable-on-path "clojure") "clojure") "-M" "-m" "cxc.core"])))

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
