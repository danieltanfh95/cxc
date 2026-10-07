(ns cxc.trace-channel
  "Push trace-annotation reviews into live Claude Code sessions via the
   per-session inbox socket (CLAUDE_CODE_MESSAGING_SOCKET).

   Wire protocol confirmed against the shipped claude binary (v2.1.280):

     line 1 (optional on macOS/Linux, required on Windows):
       {\"type\":\"auth\",\"token\":\"<CLAUDE_CODE_MESSAGING_TOKEN>\"}
     line 2 (the message):
       {\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"<text>\"}}

   The socket is a Unix domain socket at /tmp/cc-socks/<pid>.sock on
   macOS/Linux. Filesystem permissions (owner-only) are the only
   authentication on same-OS-user sends; the auth line is required for
   Windows and otherwise optional.

   Session discovery: list live `.sock` files in /tmp/cc-socks/, filter
   by connect-ability and optionally by the owning process's cwd. The
   watcher sends newly-opened annotations and human verifications to
   every surviving socket.

   The receiving Claude sees the message between tool calls as a plain
   user-role message (framed with peer-safety guardrails by Claude Code
   itself). The sender need not be a child of the receiver; same-OS-user
   is enough.

   This namespace is a library used by `cxc.trace-server`. It no longer
   provides a `run` entry point — the MCP stdio channel server it used
   to host has been retired now that cross-session messaging is in GA."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.trace-annotations :as ann])
  (:import [java.io IOException]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file FileSystems Paths StandardWatchEventKinds]
           [java.util.concurrent TimeUnit]))

;; ---------- JSON helpers ----------

(defn- json-write-str [v]
  ((requiring-resolve 'clojure.data.json/write-str) v))

(defn- log! [& parts]
  (binding [*out* *err*]
    (apply println parts)))

;; ---------- socket post ----------

(def ^:private sock-dir "/tmp/cc-socks")

(defn- connect-uds
  "Open a blocking Unix-domain SocketChannel to `path`, or nil if the
   socket is dead (ECONNREFUSED). Any other IO error logs and returns
   nil so one bad socket doesn't take the watcher down."
  ^SocketChannel [^String path]
  (try
    (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
      (.connect ch (UnixDomainSocketAddress/of path))
      ch)
    (catch IOException _ nil)
    (catch Throwable t
      (log! (str "uds connect error on " path ": " (.getMessage t)))
      nil)))

(defn- write-line! [^SocketChannel ch ^String s]
  (let [bytes (.getBytes (str s "\n") StandardCharsets/UTF_8)
        buf   (ByteBuffer/wrap bytes)]
    (while (.hasRemaining buf)
      (.write ch buf))))

(defn- post-to-socket!
  "Send one user-role message over the inbox socket. Returns true on
   successful write, false on any failure. Fire-and-forget — the server
   doesn't ACK."
  [sock-path message-text]
  (if-let [ch (connect-uds sock-path)]
    (try
      ;; auth line is optional on macOS/Linux and we have no token for a
      ;; peer session anyway. same-OS-user access is enforced by the
      ;; socket's 0700 directory.
      (write-line! ch (json-write-str
                        {:type "user"
                         :message {:role "user" :content message-text}}))
      true
      (catch Throwable t
        (log! (str "uds write error on " sock-path ": " (.getMessage t)))
        false)
      (finally
        (try (.close ch) (catch Throwable _ nil))))
    false))

(defn- pid-for-socket [^java.io.File f]
  (try
    (-> f .getName
        (str/replace #"\.sock$" "")
        Long/parseLong)
    (catch Throwable _ nil)))

(defn- own-pid []
  (try
    (.pid (java.lang.ProcessHandle/current))
    (catch Throwable _ -1)))

(defn- find-project-root
  "Walk up from `trace-dir` until a `.git` directory is found. That
   directory becomes the fan-out scope — only sessions whose cwd is at or
   under it receive the push. Falls back to the trace-dir itself if no
   `.git` ancestor exists."
  [^String trace-dir]
  (loop [d (io/file trace-dir)]
    (cond
      (nil? d)                        (.getCanonicalPath (io/file trace-dir))
      (.exists (io/file d ".git"))    (.getCanonicalPath d)
      :else                           (recur (.getParentFile d)))))

(defn- pid-cwd
  "Return the cwd of PID as an absolute String, or nil if we can't tell.
   Uses `lsof -a -p <pid> -d cwd -Fn` which works for same-user processes
   on macOS/Linux without privileges."
  [pid]
  (try
    (let [p (-> (ProcessBuilder. ["lsof" "-a" "-p" (str pid) "-d" "cwd" "-Fn"])
                (.redirectErrorStream true)
                (.start))
          out (slurp (.getInputStream p))]
      (.waitFor p)
      ;; lsof -Fn prints lines prefixed by 'n' for name; cwd line is 'n/some/path'
      (some (fn [^String line]
              (when (and line (.startsWith line "n"))
                (subs line 1)))
            (str/split-lines out)))
    (catch Throwable _ nil)))

(defn- under? [^String descendant ^String ancestor]
  (let [d (-> (io/file descendant) .getCanonicalPath)
        a (-> (io/file ancestor) .getCanonicalPath)]
    (or (= d a) (str/starts-with? d (str a "/")))))

(defn- live-sockets
  "Return paths of /tmp/cc-socks/*.sock entries whose owning process is
   (a) live, (b) not this process, and (c) has a cwd at or under
   `project-root`. Does not try to connect — connect-time failure is
   handled per send."
  [project-root]
  (let [d     (io/file sock-dir)
        self  (own-pid)
        procs (into #{} (map #(.pid %))
                    (iterator-seq (.iterator (java.lang.ProcessHandle/allProcesses))))]
    (if-not (.isDirectory d)
      []
      (vec
        (for [f (.listFiles d)
              :let [pid (pid-for-socket f)]
              :when (and pid
                         (not= pid self)
                         (contains? procs pid)
                         (.canRead f))
              :let [cwd (pid-cwd pid)]
              :when (and cwd (under? cwd project-root))]
          (.getPath f))))))

;; ---------- sidecar snapshot + diff ----------

(defonce ^:private snapshots
  (atom {}))

(defn- status-of [ann] (or (:status ann) "open"))

(defn- find-newly-open
  "Return annotations in `curr` whose status flipped to `open` from
   either `draft` or absent. Captures the human submit action without
   over-firing on agent-driven writes like open→in_progress."
  [prev curr]
  (let [prev-by-id (into {} (map (juxt :id identity) (:annotations prev)))]
    (filter (fn [a]
              (and (= "open" (status-of a))
                   (let [p (prev-by-id (:id a))]
                     (or (nil? p) (= "draft" (status-of p))))))
            (:annotations curr))))

(defn- find-newly-verified
  "Return annotations in `curr` whose status flipped addressed→verified.
   Require the previous resolution's author and time so legacy sidecars
   without a resolution do not produce a verification notification."
  [prev curr]
  (let [prev-by-id (into {} (map (juxt :id identity) (:annotations prev)))]
    (filter (fn [a]
              (let [p (prev-by-id (:id a))]
                (and (= "verified" (status-of a))
                     (= "addressed" (status-of p))
                     (get-in p [:resolution :by])
                     (get-in p [:resolution :at]))))
            (:annotations curr))))

(defn- target-brief [t]
  (cond
    (:trace_seq t)
    (str "seq=" (:trace_seq t)
         (when (:atom t) (str " atom=" (:atom t)))
         (when (:msg t)  (str " msg=" (:msg t))))

    (:trace_seq_start t)
    (str "seq=" (:trace_seq_start t) ".." (:trace_seq_end t))

    (and (:from_atom t) (:to_atom t))
    (str (:from_atom t) "→" (:to_atom t)
         (when (:msg t) (str " via " (:msg t))))

    (:atom t)
    (str "atom=" (:atom t)
         (when (:phase t) (str " phase=" (:phase t))))

    :else
    (pr-str t)))

(defn- attachment-brief [a]
  (cond
    (= "image" (:kind a)) (str "[image " (:path a) "]")
    (= "link"  (:kind a)) (str "[link " (:url a) "]")
    :else ""))

(defn- entry-text [a token]
  (let [atts (seq (:attachments a))]
    (str "- " (:id a) " · " (target-brief (:target a)) " · reply-token: " token "\n"
         "  " (:note a)
         (when atts
           (str "\n  attachments: "
                (str/join " " (map attachment-brief atts)))))))

(defn- compose-content [trace-base entries]
  ;; `entries` is a seq of [annotation token] pairs.
  (str "[cxc-trace] " (count entries)
       " annotation(s) submitted on " trace-base ":\n\n"
       (str/join "\n\n" (for [[a tok] entries] (entry-text a tok)))
       "\n\n"
       "Reply in chat with the reply-token included anywhere in your response. "
       "The trace viewer will fetch that chat excerpt and render it under the "
       "annotation automatically. Then `cxc trace annotation addressed <id> "
       "--summary ...` to close. See the trace-review skill for the full loop."))

(defn- compose-verified-content [trace-base entries prev]
  ;; Use the snapshot's resolution: verification may update its metadata.
  (let [prev-by-id (into {} (map (juxt :id identity) (:annotations prev)))]
    (str/join "\n"
              (for [[a tok] entries
                    :let [resolution (:resolution (prev-by-id (:id a)))]]
                (str "[cxc-trace] " trace-base ": human verified " (:id a)
                     " (originally resolved by " (:by resolution)
                     " at " (:at resolution) ") — your fix held. reply-token: " tok)))))

;; ---------- token generation + sidecar write-back ----------

(def ^:private token-charset
  (vec "abcdefghijklmnopqrstuvwxyz0123456789"))

(defn- gen-token
  "Random 6-char base36 token. ~2 billion combinations, grep-safe in a
   JSONL and visually distinct from annotation ids like ann_06."
  []
  (let [r (java.security.SecureRandom.)]
    (apply str (repeatedly 6 #(nth token-charset (.nextInt r (count token-charset)))))))

(defn- now-iso []
  (-> (java.time.Instant/now) str))

(defn- append-pending-comment
  "Return `ann` with a new pending comment carrying the token appended to
   `comments`. The viewer resolves the token to a chat excerpt on render."
  [ann token]
  (let [comment-entry {:by    "cxc-trace"
                       :kind  "pending"
                       :token token
                       :at    (now-iso)}]
    (update ann :comments (fnil conj []) comment-entry)))

(defn- stamp-tokens
  "For each annotation id in `ann-id->token`, append a pending comment
   with the matching token. Returns the updated sidecar map."
  [sidecar ann-id->token]
  (update sidecar :annotations
          (fn [anns]
            (vec (for [a anns]
                   (if-let [tok (ann-id->token (:id a))]
                     (append-pending-comment a tok)
                     a))))))

(defn- trace-base-from-sidecar [path]
  (-> (io/file path) .getName
      (str/replace #"\.annotations\.json$" "")))

(defn- fanout-push!
  "Send the message to every live Claude Code session's inbox socket
   whose cwd is at or under `project-root`. Returns the count written to.
   Returns 0 silently if no matching session is live — that's the normal
   case when the user hasn't started a Claude session for this project
   yet."
  [project-root message-text]
  (let [targets (live-sockets project-root)]
    (reduce (fn [n p]
              (if (post-to-socket! p message-text) (inc n) n))
            0 targets)))

(defn- write-sidecar! [path sidecar]
  ;; Delegate so we keep the single source of truth for sidecar
  ;; serialization, including atomic write semantics if that changes.
  (ann/write-sidecar path sidecar))

(defn- maybe-push! [project-root sidecar-path]
  (try
    (let [curr         (ann/read-sidecar sidecar-path)
          prev         (get @snapshots sidecar-path {:annotations []})
          new-opens    (find-newly-open prev curr)
          new-verifies (find-newly-verified prev curr)
          changed      (concat new-opens new-verifies)]
      (if (seq changed)
        (let [ann-id->token (into {} (for [a changed] [(:id a) (gen-token)]))
              with-tokens   (stamp-tokens curr ann-id->token)
              entries       (fn [anns]
                              (map (fn [a] [a (ann-id->token (:id a))]) anns))
              trace-base    (trace-base-from-sidecar sidecar-path)
              batches       (cond-> []
                              (seq new-opens)
                              (conj [new-opens (compose-content trace-base (entries new-opens))])
                              (seq new-verifies)
                              (conj [new-verifies (compose-verified-content trace-base (entries new-verifies) prev)]))]
          ;; Pre-write snapshot update so our own write doesn't re-fire a push
          (swap! snapshots assoc sidecar-path with-tokens)
          (write-sidecar! sidecar-path with-tokens)
          (doseq [[anns msg] batches]
            (let [n (fanout-push! project-root msg)]
              (log! (str "cxc-trace: pushed " (count anns)
                         " ann(s) on " trace-base
                         " [tokens: " (str/join "," (map #(ann-id->token (:id %)) anns)) "]"
                         " to " n " session(s) under " project-root)))))
        (swap! snapshots assoc sidecar-path curr)))
    (catch Throwable t
      (log! (str "cxc-trace: sidecar read error for " sidecar-path
                 ": " (.getMessage t))))))

;; ---------- WatchService loop ----------

(defn- seed-snapshots!
  "Prime the snapshot map from whatever sidecars already exist in the
   trace-dir on startup, so the first watch event is diffed against
   on-disk state rather than firing a bogus initial push."
  [trace-dir]
  (doseq [f (.listFiles (io/file trace-dir))
          :when (and (.isFile f)
                     (str/ends-with? (.getName f) ".annotations.json"))]
    (let [abs (.getPath f)]
      (swap! snapshots assoc abs (ann/read-sidecar abs)))))

(defn- watch-loop [project-root trace-dir]
  (let [ws   (-> (FileSystems/getDefault) .newWatchService)
        root (Paths/get trace-dir (into-array String []))]
    (.register root ws
               (into-array [StandardWatchEventKinds/ENTRY_MODIFY
                            StandardWatchEventKinds/ENTRY_CREATE]))
    (future
      (try
        (let [pending (atom #{})]
          (loop []
            (when-let [k (.poll ws 200 TimeUnit/MILLISECONDS)]
              (doseq [ev (.pollEvents k)]
                (let [name (-> ev .context .toString)]
                  (when (str/ends-with? name ".annotations.json")
                    (let [abs (str (.resolve root (-> ev .context)))]
                      (swap! pending conj abs)))))
              (.reset k))
            (when (seq @pending)
              (Thread/sleep 150)
              (doseq [p @pending] (maybe-push! project-root p))
              (reset! pending #{}))
            (recur)))
        (catch InterruptedException _ (log! "cxc-trace: watcher interrupted"))
        (catch Throwable t (log! (str "cxc-trace: watcher error: " (.getMessage t))))))
    ws))

;; ---------- entry point used by trace-server ----------

(defn start-poster!
  "Begin watching `trace-dir` for annotation submits and verifications.
   On draft→open and addressed→verified transitions, fan out to inbox sockets
   whose cwd is at or under the project root (walked up from trace-dir
   until a `.git` directory). Returns the WatchService so the caller can
   .close it on shutdown."
  [trace-dir]
  (let [project-root (find-project-root trace-dir)]
    (seed-snapshots! trace-dir)
    (log! (str "cxc-trace: watching " trace-dir
               ", project-root " project-root
               ", seeded " (count @snapshots) " sidecar snapshot(s)"))
    (watch-loop project-root trace-dir)))
