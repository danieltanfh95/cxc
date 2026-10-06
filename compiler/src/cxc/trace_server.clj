(ns cxc.trace-server
  "Local HTTP host for the browser trace viewer. Replaces the ad-hoc
   `python3 -m http.server :8767` with a zero-dep Clojure server, so
   the browser can POST annotations and focus state back to disk
   without a second runtime.

   Mounts a static-file root (default: trace-dir, else compiler/tools/
   where trace-viewer.html lives) and two JSON endpoints:

     POST /annotations?trace=<name>       body = sidecar JSON (overwrites)
     GET  /annotations?trace=<name>       returns current sidecar JSON
     POST /annotations-asset?trace=<name> body = raw image bytes (Content-Type image/*)
                                          writes <trace-base>.annotations.assets/<uuid>.<ext>
                                          returns {path, bytes, content_type}
     POST /focus                          body = {trace, atom, seq, selected_edge}
     GET  /focus                          returns current focus JSON

   Query param `trace` is a filename relative to trace-dir, matching
   the viewer's ?trace= URL param."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.trace-annotations :as ann])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.file Files]
           [java.util.concurrent Executors]))

;; ---------- helpers ----------

(defn- json-read-str [s]
  ((requiring-resolve 'clojure.data.json/read-str) s :key-fn keyword))

(defn- json-write-str
  ([v]           ((requiring-resolve 'clojure.data.json/write-str) v))
  ([v & opts]    (apply (requiring-resolve 'clojure.data.json/write-str) v opts)))

(defn- content-type [path]
  (cond
    (str/ends-with? path ".html") "text/html; charset=utf-8"
    (str/ends-with? path ".js")   "application/javascript"
    (str/ends-with? path ".css")  "text/css"
    (str/ends-with? path ".json") "application/json"
    (str/ends-with? path ".jsonl") "application/x-ndjson"
    (str/ends-with? path ".svg")  "image/svg+xml"
    (str/ends-with? path ".png")  "image/png"
    (str/ends-with? path ".ico")  "image/x-icon"
    :else "application/octet-stream"))

(defn- read-body [^HttpExchange ex]
  (with-open [in (.getRequestBody ex)]
    (slurp in)))

(defn- read-body-bytes [^HttpExchange ex]
  (with-open [in  (.getRequestBody ex)
              out (java.io.ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn- ext-for [^String content-type]
  (cond
    (nil? content-type)                 "bin"
    (str/starts-with? content-type "image/png")   "png"
    (str/starts-with? content-type "image/jpeg")  "jpg"
    (str/starts-with? content-type "image/gif")   "gif"
    (str/starts-with? content-type "image/webp")  "webp"
    (str/starts-with? content-type "image/svg+xml") "svg"
    :else                                "bin"))

(defn- send-text [^HttpExchange ex status ^String body]
  (.add (.getResponseHeaders ex) "Access-Control-Allow-Origin" "*")
  (.add (.getResponseHeaders ex) "Content-Type" "text/plain; charset=utf-8")
  (let [bs (.getBytes body "UTF-8")]
    (.sendResponseHeaders ex status (count bs))
    (with-open [out (.getResponseBody ex)]
      (.write out bs))))

(defn- send-json [^HttpExchange ex status v]
  (.add (.getResponseHeaders ex) "Access-Control-Allow-Origin" "*")
  (.add (.getResponseHeaders ex) "Content-Type" "application/json")
  (let [bs (.getBytes (json-write-str v) "UTF-8")]
    (.sendResponseHeaders ex status (count bs))
    (with-open [out (.getResponseBody ex)]
      (.write out bs))))

(defn- send-bytes [^HttpExchange ex status ^String ctype ^bytes bs]
  (.add (.getResponseHeaders ex) "Access-Control-Allow-Origin" "*")
  (.add (.getResponseHeaders ex) "Content-Type" ctype)
  (.sendResponseHeaders ex status (count bs))
  (with-open [out (.getResponseBody ex)]
    (.write out bs)))

(defn- parse-query [^String q]
  (when q
    (into {}
          (for [pair (str/split q #"&")
                :let [[k v] (str/split pair #"=" 2)]]
            [(keyword k)
             (when v (URLDecoder/decode v "UTF-8"))]))))

(defn- safe-join
  "Resolve child under root, reject escaping segments."
  [root child]
  (let [rp  (.getCanonicalPath (io/file root))
        f   (io/file root child)
        cp  (.getCanonicalPath f)]
    (when (str/starts-with? cp rp) f)))

;; ---------- handlers ----------

(defn- fallback-tools-dir
  "Where trace-viewer.html lives in-repo. Resolved from the compiler cwd
   so bin/cxc's cd into compiler/ picks it up."
  []
  (io/file (System/getProperty "user.dir") "tools"))

(defn- try-serve-static [^HttpExchange ex trace-dir path]
  ;; Shipped viewer assets (trace-viewer.html, future js/css) live in
  ;; compiler/tools and win over any same-named file in trace-dir so a
  ;; leftover copy from an older workflow can't shadow the live viewer.
  ;; Everything else resolves from trace-dir.
  (let [path   (if (= "/" path) "/trace-viewer.html" path)
        rel    (subs path 1)
        tools  (safe-join (fallback-tools-dir) rel)
        traced (safe-join trace-dir rel)
        f      (cond
                 (and tools  (.exists tools)  (.isFile tools))  tools
                 (and traced (.exists traced) (.isFile traced)) traced
                 :else nil)]
    (if f
      (let [bs (Files/readAllBytes (.toPath f))]
        (send-bytes ex 200 (content-type path) bs))
      (send-text ex 404 (str "not found: " path)))))

(defn- handle-static [trace-dir]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (try-serve-static ex trace-dir (.getPath (.getRequestURI ex)))
        (catch Throwable t
          (send-text ex 500 (str "server error: " (.getMessage t))))))))

(defn- resolve-trace-name [trace-dir ^String name]
  (safe-join trace-dir name))

(defn- handle-annotations [trace-dir]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [method (.getRequestMethod ex)
              q      (parse-query (.getQuery (.getRequestURI ex)))
              trace  (:trace q)]
          (cond
            (not trace)
            (send-text ex 400 "missing ?trace=<name>")

            :else
            (let [trace-file (resolve-trace-name trace-dir trace)
                  _          (when-not trace-file
                               (send-text ex 403 "trace path escapes root"))
                  ann-path   (ann/trace->annotations-path (.getPath trace-file))]
              (case method
                "GET"
                (send-json ex 200 (ann/read-sidecar ann-path))

                "POST"
                (let [body (read-body ex)
                      v    (json-read-str body)]
                  (ann/write-sidecar ann-path v)
                  (send-text ex 200 "ok"))

                "OPTIONS"
                (do (.add (.getResponseHeaders ex) "Access-Control-Allow-Methods"
                          "GET, POST, OPTIONS")
                    (.add (.getResponseHeaders ex) "Access-Control-Allow-Headers"
                          "Content-Type")
                    (send-text ex 204 ""))

                (send-text ex 405 (str "method not allowed: " method))))))
        (catch Throwable t
          (send-text ex 500 (str "server error: " (.getMessage t))))))))

(defn- assets-dir-for
  "Where image attachments live. `<trace-dir>/<trace-base>.annotations.assets/`.
   Co-located with the sidecar so a git-tracked review carries its evidence."
  [trace-dir trace-name]
  (let [base (-> trace-name
                 (str/replace #"\.trace\.jsonl$" "")
                 (str/replace #"\.jsonl$" ""))]
    (io/file trace-dir (str base ".annotations.assets"))))

(defn- handle-asset
  "POST a raw image body with Content-Type: image/*. Writes a UUID-named
   file under `<trace>.annotations.assets/` and returns the relative path
   the viewer can embed in an attachment record."
  [trace-dir]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [method (.getRequestMethod ex)
              q      (parse-query (.getQuery (.getRequestURI ex)))
              trace  (:trace q)]
          (case method
            "POST"
            (cond
              (not trace)
              (send-text ex 400 "missing ?trace=<name>")

              :else
              (let [ctype   (-> ex .getRequestHeaders (.getFirst "Content-Type"))
                    bs      (read-body-bytes ex)
                    ext     (ext-for ctype)
                    uuid    (str (java.util.UUID/randomUUID))
                    base    (-> trace
                                (str/replace #"\.trace\.jsonl$" "")
                                (str/replace #"\.jsonl$" ""))
                    rel     (str base ".annotations.assets/" uuid "." ext)
                    out-dir (assets-dir-for trace-dir trace)
                    out     (io/file out-dir (str uuid "." ext))]
                (io/make-parents out)
                (with-open [o (java.io.FileOutputStream. out)]
                  (.write o bs))
                (send-json ex 200 {:path rel :bytes (count bs) :content_type ctype})))

            "OPTIONS"
            (do (.add (.getResponseHeaders ex) "Access-Control-Allow-Methods"
                      "POST, OPTIONS")
                (.add (.getResponseHeaders ex) "Access-Control-Allow-Headers"
                      "Content-Type")
                (send-text ex 204 ""))

            (send-text ex 405 (str "method not allowed: " method))))
        (catch Throwable t
          (send-text ex 500 (str "server error: " (.getMessage t))))))))

(defn- handle-focus [trace-dir]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [method (.getRequestMethod ex)
              fp     (ann/trace->focus-path (.getPath trace-dir))]
          (case method
            "GET"
            (let [f (io/file fp)]
              (if (.exists f)
                (send-json ex 200 (json-read-str (slurp f)))
                (send-json ex 200 {})))

            "POST"
            (let [body (read-body ex)
                  v    (json-read-str body)]
              (io/make-parents fp)
              (spit fp (json-write-str v))
              (send-text ex 200 "ok"))

            "OPTIONS"
            (do (.add (.getResponseHeaders ex) "Access-Control-Allow-Methods"
                      "GET, POST, OPTIONS")
                (.add (.getResponseHeaders ex) "Access-Control-Allow-Headers"
                      "Content-Type")
                (send-text ex 204 ""))

            (send-text ex 405 (str "method not allowed: " method))))
        (catch Throwable t
          (send-text ex 500 (str "server error: " (.getMessage t))))))))

;; ---------- chat excerpt lookup ----------

(def ^:private projects-dir
  (io/file (System/getProperty "user.home") ".claude" "projects"))

(defn- all-session-jsonls []
  (when (.isDirectory projects-dir)
    (for [proj (.listFiles projects-dir)
          :when (.isDirectory proj)
          f    (.listFiles proj)
          :when (and (.isFile f) (str/ends-with? (.getName f) ".jsonl"))]
      f)))

(defn- extract-assistant-text
  "Walk the Claude Code chat-record line's content and pull all text
   segments. Content may be a bare string or a vector of {type text ...}
   blocks. Returns a single joined string, or nil if nothing textual."
  [parsed]
  (let [msg     (get parsed :message parsed)
        content (:content msg)]
    (cond
      (string? content)
      content

      (sequential? content)
      (->> content
           (keep (fn [part]
                   (cond
                     (string? part) part
                     (map?    part) (:text part))))
           (str/join "\n"))

      :else nil)))

(def ^:private token-re #"^[a-z0-9]{4,16}$")

(defn- handle-chat-excerpt
  "GET /chat-excerpt?token=<tok> → JSON array of {session, timestamp, text}
   for every assistant-role line in any user-local session JSONL whose
   content contains the token. Token must match [a-z0-9]{4,16}; anything
   else is rejected to prevent exfil of unrelated chat prose."
  []
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [q     (parse-query (.getQuery (.getRequestURI ex)))
              token (:token q)]
          (cond
            (not (and token (re-matches token-re token)))
            (send-text ex 400 "missing or malformed ?token= (expected [a-z0-9]{4,16})")

            :else
            (let [needle token
                  hits   (atom [])]
              (doseq [^java.io.File f (all-session-jsonls)]
                (with-open [r (io/reader f)]
                  (doseq [^String line (line-seq r)]
                    (when (and line (.contains line ^String needle))
                      (try
                        (let [parsed (json-read-str line)
                              role   (or (:role parsed)
                                         (-> parsed :message :role)
                                         (:type parsed))]
                          (when (= role "assistant")
                            (when-let [txt (extract-assistant-text parsed)]
                              (when (.contains txt ^String needle)
                                (swap! hits conj
                                       {:session   (.getName (.getParentFile f))
                                        :file      (.getName f)
                                        :timestamp (or (:timestamp parsed) "")
                                        :text      txt})))))
                        (catch Throwable _ nil))))))
              ;; Sort chronologically if timestamps present.
              (send-json ex 200 (vec (sort-by :timestamp @hits))))))
        (catch Throwable t
          (send-text ex 500 (str "server error: " (.getMessage t))))))))

;; ---------- user-settings helpers (crossSessionInbound) ----------

(defn- user-settings-file ^java.io.File []
  (io/file (System/getProperty "user.home") ".claude" "settings.json"))

(defn- read-user-settings
  "Return the parsed settings map or nil if the file doesn't exist or is
   not valid JSON. Warns on the latter so a corrupt file is never silently
   overwritten."
  []
  (let [f (user-settings-file)]
    (when (.exists f)
      (try (json-read-str (slurp f))
           (catch Throwable _
             (binding [*out* *err*]
               (println (str "warning: " (.getPath f)
                             " is not valid JSON; leaving it alone")))
             nil)))))

(defn- print-inbound-status
  "Report the inbound state after any auto-write has happened. If
   crossSessionInbound is `accept`, say so. If it's something else, warn
   the user's own choice wins. If it's still unset (we chose not to
   write), explain the trade-off."
  []
  (let [f  (user-settings-file)
        cs (some-> (read-user-settings) :crossSessionInbound)]
    (cond
      (= "accept" cs)
      (println "  inbound   = accept (your Claude sessions will receive pushes immediately)")

      (string? cs)
      (println (str "  inbound   = \"" cs "\" (your sessions will hold or drop pushes;"
                    " respecting your explicit choice)"))

      :else
      (do (println "  inbound   = default (bypassPermissions-mode sessions will silently HOLD pushes)")
          (println (str "              add {\"crossSessionInbound\":\"accept\"} to "
                        (.getPath f)))
          (println "              or re-run without --no-accept-peer-inbound to merge it in")))))

(defn- write-user-settings-accept!
  "Merge `crossSessionInbound: accept` into ~/.claude/settings.json,
   preserving any other keys. Creates ~/.claude/ if missing. Idempotent."
  []
  (let [f        (user-settings-file)
        existing (or (read-user-settings) {})
        current  (:crossSessionInbound existing)]
    (cond
      (= "accept" current)
      (println (str "  (no change; " (.getPath f) " already has crossSessionInbound=accept)"))

      (and (string? current) (not= "accept" current))
      (binding [*out* *err*]
        (println (str "warning: " (.getPath f) " has crossSessionInbound=\"" current
                      "\"; refusing to overwrite your explicit choice. Edit the file"
                      " by hand or remove the key, then rerun.")))

      :else
      (do (io/make-parents f)
          (let [merged (assoc existing :crossSessionInbound "accept")]
            (spit f (json-write-str merged :escape-slash false :indent true)))
          (println (str "  wrote crossSessionInbound=accept to " (.getPath f)))))))

;; ---------- startup ----------

(defn start!
  "Start the trace server. Returns the HttpServer instance so a caller
   can .stop it. Blocks on current thread forever when `block?` is true,
   returns otherwise."
  [{:keys [port trace-dir block? push? accept-peer-inbound?]
    :or   {port 8767 block? false push? true accept-peer-inbound? true}}]
  (let [td  (io/file trace-dir)
        _   (when-not (.isDirectory td)
              (binding [*out* *err*]
                (println (str "trace-dir does not exist: " (.getPath td))))
              (System/exit 2))
        srv (HttpServer/create (InetSocketAddress. port) 0)]
    (.createContext srv "/annotations"       (handle-annotations td))
    (.createContext srv "/annotations-asset" (handle-asset td))
    (.createContext srv "/focus"             (handle-focus td))
    (.createContext srv "/chat-excerpt"      (handle-chat-excerpt))
    (.createContext srv "/"                  (handle-static td))
    (.setExecutor srv (Executors/newFixedThreadPool 4))
    (.start srv)
    (println (str "cxc trace serve, listening on http://localhost:" port))
    (println (str "  trace-dir = " (.getCanonicalPath td)))
    (println (str "  tools-dir = " (.getCanonicalPath (fallback-tools-dir))))
    (println (str "  viewer    = http://localhost:" port "/trace-viewer.html"))
    (if push?
      (do ((requiring-resolve 'cxc.trace-channel/start-poster!)
           (.getCanonicalPath td))
          (println "  push      = on (fan-out to any live claude session's inbox socket)")
          (when accept-peer-inbound?
            (write-user-settings-accept!))
          (print-inbound-status))
      (println "  push      = off (--no-push)"))
    (when block?
      @(promise))
    srv))

;; ---------- CLI entry ----------

(defn- parse-flags [args]
  (loop [out {} args args]
    (if-let [[f v & more] (seq args)]
      (cond
        (str/starts-with? f "--")
        (if (and v (not (str/starts-with? v "--")))
          (recur (assoc out (keyword (subs f 2)) v) more)
          ;; Trailing boolean flag: v may be nil. Only push it back when non-nil.
          (recur (assoc out (keyword (subs f 2)) true)
                 (if v (cons v more) more)))
        :else
        (recur (update out ::positional (fnil conj []) f)
               (if v (cons v more) more)))
      out)))

(defn- usage []
  (println "Usage: cxc trace serve [--port 8767] [--trace-dir <path>] [--no-push]")
  (println "                       [--no-accept-peer-inbound]")
  (println "")
  (println "  Serves the browser trace viewer at http://localhost:<port>")
  (println "  Mounts <trace-dir> as the static root so *.trace.jsonl,")
  (println "  *.shape.json, and *.annotations.json are reachable as")
  (println "  /<name>. Falls back to compiler/tools/ for trace-viewer.html")
  (println "  if the file isn't in trace-dir.")
  (println "")
  (println "  POST /annotations?trace=<name>       writes <name>.annotations.json")
  (println "  POST /annotations-asset?trace=<name> writes an image attachment to <name>.annotations.assets/")
  (println "  POST /focus writes cxc-trace-focus.json in trace-dir")
  (println "")
  (println "  By default, draft→open transitions in a sidecar fan out as a plain")
  (println "  user-role message to every live Claude Code session's inbox socket")
  (println "  (/tmp/cc-socks/<pid>.sock). Pass --no-push to disable the fan-out.")
  (println "")
  (println "  By default, if ~/.claude/settings.json has no crossSessionInbound key,")
  (println "  this merges {\"crossSessionInbound\":\"accept\"} into it so bypassPermissions-mode")
  (println "  sessions deliver pushes immediately instead of silently holding them.")
  (println "  An existing value (accept/hold/refuse) is NEVER overwritten.")
  (println "  Pass --no-accept-peer-inbound to skip the merge."))

(defn run [args]
  (let [flags (parse-flags args)]
    (cond
      (:help flags)
      (usage)

      :else
      (let [port      (if-let [p (:port flags)] (Integer/parseInt p) 8767)
            trace-dir (or (:trace-dir flags)
                          (:dir flags)
                          (System/getProperty "user.dir"))
            push?     (not (true? (:no-push flags)))
            accept?   (not (true? (:no-accept-peer-inbound flags)))]
        (start! {:port port
                 :trace-dir trace-dir
                 :block? true
                 :push? push?
                 :accept-peer-inbound? accept?})))))
