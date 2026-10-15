(ns cxc.util.chatlog
  "Read-only scanner over ~/.claude/projects/<slug>/<session>.jsonl.

   Each line is one Claude Code chat record. The shape is either
   {:role ... :content ...} directly, or {:message {:role ... :content ...}}
   where content is either a string or a vector of {type text ...} blocks.

   Public surface:
     session-files     — seq of File over every session JSONL
     assistant-text    — pull the text segments out of one parsed line
                         when its role is 'assistant', else nil
     scan-for-token    — walk every session file for a substring token,
                         return a vec of hits sorted by timestamp"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- json-read-str [s]
  ((requiring-resolve 'clojure.data.json/read-str) s :key-fn keyword))

(def ^:private projects-dir
  (io/file (System/getProperty "user.home") ".claude" "projects"))

(defn session-files []
  (when (.isDirectory projects-dir)
    (for [proj (.listFiles projects-dir)
          :when (.isDirectory proj)
          f    (.listFiles proj)
          :when (and (.isFile f) (str/ends-with? (.getName f) ".jsonl"))]
      f)))

(defn- parts->text [content]
  (cond
    (string? content)     content
    (sequential? content) (->> content
                               (keep (fn [p]
                                       (cond
                                         (string? p) p
                                         (map? p)    (:text p))))
                               (str/join "\n"))
    :else nil))

(defn assistant-text
  "Return the text body of `parsed` iff it is an assistant-role record,
   else nil."
  [parsed]
  (let [msg     (get parsed :message parsed)
        role    (or (:role parsed)
                    (:role msg)
                    (:type parsed))]
    (when (= role "assistant")
      (parts->text (:content msg)))))

(defn scan-for-token
  "Return all assistant-role hits whose text contains `token`, sorted by
   timestamp. Each hit is {:session, :file, :timestamp, :text}. Parse
   errors on malformed lines are swallowed per-line so one broken record
   doesn't stop the scan."
  [^String token]
  (let [hits (atom [])]
    (doseq [^java.io.File f (session-files)]
      (with-open [r (io/reader f)]
        (doseq [^String line (line-seq r)]
          (when (and line (.contains line token))
            (try
              (let [parsed (json-read-str line)]
                (when-let [txt (assistant-text parsed)]
                  (when (.contains txt token)
                    (swap! hits conj
                           {:session   (.getName (.getParentFile f))
                            :file      (.getName f)
                            :timestamp (or (:timestamp parsed) "")
                            :text      txt}))))
              (catch Throwable _ nil))))))
    (vec (sort-by :timestamp @hits))))
