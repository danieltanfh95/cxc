(ns cxc.daemon
  "Persistent-JVM compile daemon. Keeps a warm JVM listening on a UNIX socket
   so repeated `cxc compile` calls skip the 3-4s cold start and benefit from
   HotSpot JIT warmup across compiles.

   Protocol (NDJSON, one line per direction, request-response, connection closes
   after single exchange):

     Request from client:
       {\"cmd\": \"compile\",
        \"args\": [\"<input-abs>\", \"<out-dir-abs>\", \"--target=...\"? , \"--trace\"?],
        \"cwd\":  \"<abs>\"}

     Response from daemon:
       {\"exit\": <int>,
        \"stdout\": \"<captured>\",
        \"stderr\": \"<captured>\",
        \"elapsed_ms\": <int>}

   Stale-source detection (2026-10-04). The daemon records its start time on
   `serve` boot. Each `compile` request walks `src/cxc/**/*.clj` plus
   `resources/prelude/**/*.cxc` and returns exit 99 with a `stale` marker
   when any file mtime is newer than the start time, so the paired bin/cxc
   can fall through to `exec clj` for a fresh read. See memory
   corvid-stale-daemon-bites-parity for the chapter-3 near-miss that
   motivated this.

   Default socket path: /tmp/cxc-daemon.sock (overridable via env CXC_DAEMON_SOCK).

   Usage:
     clj -M -m cxc.daemon serve         # run in foreground
     clj -M -m cxc.daemon serve &       # background it yourself

   Client-side pairing lives in bin/cxc; it tries the socket first, falls back
   to a cold `clj -M -m cxc.core` on any socket error."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cxc.core :as core])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel]
           [java.nio ByteBuffer]
           [java.io ByteArrayOutputStream PrintStream BufferedReader InputStreamReader OutputStreamWriter BufferedWriter File]
           [java.nio.file Files Paths LinkOption]
           [java.nio.file.attribute PosixFilePermission PosixFilePermissions]))

(def ^:private default-sock-path
  (or (System/getenv "CXC_DAEMON_SOCK") "/tmp/cxc-daemon.sock"))

;; Daemon start time, set once in `serve`. Used by stale-source detection.
(def ^:private daemon-start-ms (atom nil))

;; Source roots scanned for stale-source detection. Relative to the
;; compiler/ dir (the daemon's working directory). Keep this list small
;; because every scan happens on every compile request.
(def ^:private source-roots
  [{:dir "src/cxc"          :suffix ".clj"}
   {:dir "resources/prelude" :suffix ".cxc"}])

(defn- stale-source-newer-than-start?
  "Return the first file path (as a String) whose mtime exceeds the
   daemon's recorded start time, or nil when every file is older. Walks
   the roots in `source-roots`. The paths are relative to the daemon's
   working directory so a File-based walk suffices."
  []
  (let [start @daemon-start-ms]
    (when start
      (reduce
       (fn [_ {:keys [^String dir ^String suffix]}]
         (let [root (io/file dir)]
           (if (.isDirectory root)
             (if-let [hit (first
                           (for [^java.io.File f (file-seq root)
                                 :when (and (.isFile f)
                                            (.endsWith (.getName f) suffix)
                                            (> (.lastModified f) start))]
                             (.getPath f)))]
               (reduced hit)
               nil)
             nil)))
       nil
       source-roots))))

(defn- sock-addr [path]
  (UnixDomainSocketAddress/of path))

(defn- delete-stale-socket! [path]
  (try
    (Files/deleteIfExists (Paths/get path (into-array String [])))
    (catch Throwable _ nil)))

(defn- handle-compile
  "Run cxc.core/run-compile with the given args, capturing stdout+stderr.
   Returns {:exit int :stdout str :stderr str :elapsed-ms int}."
  [args]
  (let [out-baos (ByteArrayOutputStream.)
        err-baos (ByteArrayOutputStream.)
        out-ps   (PrintStream. out-baos true "UTF-8")
        err-ps   (PrintStream. err-baos true "UTF-8")
        orig-out System/out
        orig-err System/err
        start    (System/currentTimeMillis)
        exit     (atom 0)]
    (System/setOut out-ps)
    (System/setErr err-ps)
    (try
      (binding [*out* (java.io.PrintWriter. out-ps true)
                *err* (java.io.PrintWriter. err-ps true)]
        (try
          (core/run-compile (vec args))
          (catch clojure.lang.ExceptionInfo ex
            (reset! exit (:exit (ex-data ex) 1))
            (let [msg (.getMessage ex)]
              (when-not (= msg "compile failed")
                (binding [*out* *err*]
                  (println msg)))))
          (catch Throwable t
            (reset! exit 1)
            (binding [*out* *err*]
              (println (str "daemon handler exception: " (.getMessage t)))
              (.printStackTrace t)))))
      (finally
        (.flush out-ps) (.flush err-ps)
        (System/setOut orig-out)
        (System/setErr orig-err)))
    {:exit       @exit
     :stdout     (.toString out-baos "UTF-8")
     :stderr     (.toString err-baos "UTF-8")
     :elapsed-ms (- (System/currentTimeMillis) start)}))

(defn- handle-request
  "Dispatch a parsed request map. Returns a response map."
  [req]
  (case (:cmd req)
    "compile"
    (if-let [stale-path (stale-source-newer-than-start?)]
      ;; Exit 99 is the stale-daemon marker. bin/cxc inspects for 99
      ;; and falls through to `exec clj` for a fresh read. stderr is
      ;; swallowed by the client; the message is kept for daemon logs.
      {:exit 99 :stdout ""
       :stderr (str "daemon stale (source newer than daemon start): "
                    stale-path "; client should retry cold\n")
       :elapsed_ms 0}
      (let [{:keys [exit stdout stderr elapsed-ms]} (handle-compile (:args req))]
        {:exit exit :stdout stdout :stderr stderr :elapsed_ms elapsed-ms}))
    "ping"    {:exit 0 :stdout "pong\n" :stderr "" :elapsed_ms 0}
    {:exit 2 :stdout "" :stderr (str "unknown cmd: " (:cmd req)) :elapsed_ms 0}))

(defn- read-line-from-channel
  "Read bytes from the SocketChannel until newline. Returns the line as a String
   (without trailing newline) or nil on EOF."
  [^SocketChannel ch]
  (let [baos (ByteArrayOutputStream.)
        buf  (ByteBuffer/allocate 4096)]
    (loop []
      (.clear buf)
      (let [n (.read ch buf)]
        (cond
          (= n -1) (if (zero? (.size baos)) nil (.toString baos "UTF-8"))
          (zero? n) (recur)
          :else
          (do
            (.flip buf)
            (let [bytes (byte-array (.remaining buf))]
              (.get buf bytes)
              (.write baos bytes 0 (alength bytes)))
            (if (= (int \newline) (int (nth (.toByteArray baos) (dec (.size baos)))))
              (let [s (.toString baos "UTF-8")]
                (subs s 0 (dec (count s))))
              (recur))))))))

(defn- write-line-to-channel
  "Write a String followed by newline to the SocketChannel."
  [^SocketChannel ch ^String s]
  (let [bytes (.getBytes (str s "\n") "UTF-8")
        buf   (ByteBuffer/wrap bytes)]
    (while (.hasRemaining buf)
      (.write ch buf))))

(defn- serve-connection
  "Handle one accepted connection: read request, run, write response, close."
  [^SocketChannel ch]
  (try
    (when-let [line (read-line-from-channel ch)]
      (let [req  (json/read-str line :key-fn keyword)
            resp (handle-request req)]
        (write-line-to-channel ch (json/write-str resp))))
    (catch Throwable t
      (try
        (write-line-to-channel ch
                               (json/write-str
                                 {:exit 2 :stdout ""
                                  :stderr (str "daemon error: " (.getMessage t))
                                  :elapsed_ms 0}))
        (catch Throwable _ nil)))
    (finally
      (try (.close ch) (catch Throwable _ nil)))))

(defn serve
  "Run the daemon in the foreground. Blocks forever."
  ([] (serve default-sock-path))
  ([sock-path]
   (delete-stale-socket! sock-path)
   (reset! daemon-start-ms (System/currentTimeMillis))
   (let [addr   (sock-addr sock-path)
         server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
     (.bind server addr)
     (println (str "cxc-daemon listening on " sock-path))
     (.flush *out*)
     ;; Register shutdown hook so Ctrl-C cleans the socket.
     (.addShutdownHook (Runtime/getRuntime)
                       (Thread.
                         (fn []
                           (try (.close server) (catch Throwable _ nil))
                           (delete-stale-socket! sock-path))))
     (loop []
       (let [ch (.accept server)]
         ;; Run each request on the agent soloExecutor so a slow compile does
         ;; not block the next connection. Shared-pool is fine; compiles are
         ;; mostly bound by CPU/clang, not by the number of inflight requests.
         (future (serve-connection ch)))
       (recur)))))

(defn -main [& args]
  (let [[cmd & rest] args]
    (case (or cmd "serve")
      "serve" (serve (or (first rest) default-sock-path))
      (do
        (println "Usage: clj -M -m cxc.daemon serve [sock-path]")
        (System/exit 2)))))
