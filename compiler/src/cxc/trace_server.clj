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
            [cxc.trace-annotations :as ann]
            [cxc.trace-schema :as schema]
            [cxc.util.chatlog :as chatlog]
            [cxc.util.cli :as cli]
            [cxc.util.io :refer [eprintln swallow]])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.file Files]
           [java.util.concurrent Executors]))

;; ---------- helpers ----------

(defn- json-read-str [s]
  ((requiring-resolve 'clojure.data.json/read-str) s :key-fn keyword))

(defn- json-write-str [v & opts]
  (apply (requiring-resolve 'clojure.data.json/write-str) v opts))

(def ^:private mime-by-ext
  "Static-asset Content-Type by filename suffix. First match wins.
   application/octet-stream applies when no entry matches."
  {".html"  "text/html; charset=utf-8"
   ".js"    "application/javascript"
   ".css"   "text/css"
   ".json"  "application/json"
   ".jsonl" "application/x-ndjson"
   ".svg"   "image/svg+xml"
   ".png"   "image/png"
   ".ico"   "image/x-icon"})

(defn- content-type [path]
  (or (some (fn [[ext ct]] (when (str/ends-with? path ext) ct)) mime-by-ext)
      "application/octet-stream"))

(def ^:private ext-by-ctype
  "Upload-filename extension by Content-Type prefix. Vector of pairs
   so match order is explicit. nil Content-Type and anything unmatched
   fall back to \"bin\"."
  [["image/png"     "png"]
   ["image/jpeg"    "jpg"]
   ["image/gif"     "gif"]
   ["image/webp"    "webp"]
   ["image/svg+xml" "svg"]])

(defn- ext-for [^String ctype]
  (if (nil? ctype)
    "bin"
    (or (some (fn [[prefix ext]] (when (str/starts-with? ctype prefix) ext))
              ext-by-ctype)
        "bin")))

(defn- read-body [^HttpExchange ex]
  (with-open [in (.getRequestBody ex)]
    (slurp in)))

(defn- read-body-bytes [^HttpExchange ex]
  (with-open [in  (.getRequestBody ex)
              out (java.io.ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn- allowed-origin?
  "Only the loopback origins the local viewer actually serves itself from.
   Browsers that reach the server via public DNS get denied, so the local
   fixtures can't be scraped from a page the user didn't open themselves."
  [^String origin]
  (boolean
    (and origin
         (or (re-matches #"https?://127\.0\.0\.1(:\d+)?" origin)
             (re-matches #"https?://localhost(:\d+)?" origin)
             (re-matches #"https?://\[::1\](:\d+)?" origin)))))

(defn- apply-cors! [^HttpExchange ex]
  (let [origin (.getFirst (.getRequestHeaders ex) "Origin")]
    (when (allowed-origin? origin)
      (.add (.getResponseHeaders ex) "Access-Control-Allow-Origin" origin)
      (.add (.getResponseHeaders ex) "Vary" "Origin"))))

(defn- send-response!
  "Write a response with CORS applied, the given Content-Type header,
   status and byte body. The three public shapes (text, json, bytes)
   call through here."
  [^HttpExchange ex status ^String ctype ^bytes bs]
  (apply-cors! ex)
  (.add (.getResponseHeaders ex) "Content-Type" ctype)
  (.sendResponseHeaders ex status (count bs))
  (with-open [out (.getResponseBody ex)]
    (.write out bs)))

(defn- send-text [^HttpExchange ex status ^String body]
  (send-response! ex status "text/plain; charset=utf-8" (.getBytes body "UTF-8")))

(defn- send-json [^HttpExchange ex status v]
  (send-response! ex status "application/json" (.getBytes (json-write-str v) "UTF-8")))

(defn- send-bytes [^HttpExchange ex status ^String ctype ^bytes bs]
  (send-response! ex status ctype bs))

(defn- preflight!
  "Reply to an OPTIONS preflight. `methods` is the per-handler
   Access-Control-Allow-Methods string, kept per-handler so each
   advertises only what it serves."
  [^HttpExchange ex ^String methods]
  (apply-cors! ex)
  (.add (.getResponseHeaders ex) "Access-Control-Allow-Methods" methods)
  (.add (.getResponseHeaders ex) "Access-Control-Allow-Headers" "Content-Type")
  (send-text ex 204 ""))

(defn- method-not-allowed! [^HttpExchange ex ^String method]
  (send-text ex 405 (str "method not allowed: " method)))

(defn- parse-query [^String q]
  (when q
    (into {}
          (for [pair (str/split q #"&")
                :let [[k v] (str/split pair #"=" 2)]]
            [(keyword k)
             (when v (URLDecoder/decode v "UTF-8"))]))))

(defn- safe-join
  "Resolve child under root, reject escaping segments. Uses Path/startsWith
   so a sibling like `rootname-other` doesn't satisfy a string-prefix check
   against `rootname`."
  [root child]
  (let [rp (-> (io/file root) .getCanonicalFile .toPath)
        f  (io/file root child)
        cp (-> f .getCanonicalFile .toPath)]
    (when (.startsWith cp rp) f)))

(defn- trace-base
  "Strip trace-filename suffixes to the base used by annotations and
   asset directory naming. `foo.trace.jsonl` and `foo.jsonl` both
   produce `foo`."
  [^String name]
  (-> name
      (str/replace #"\.trace\.jsonl$" "")
      (str/replace #"\.jsonl$" "")))

;; ---------- handlers ----------

(defn- handler
  "Wrap a (fn [ex]) in HttpHandler + one error seam. On throw: log class
   and stack to stderr, respond 500 with the exception's simple-name so
   the client can distinguish a parse error from a disk error without
   leaking stack frames."
  [f]
  (reify HttpHandler
    (handle [_ ex]
      (try (f ex)
        (catch Throwable t
          (binding [*out* *err*]
            (println (str "server error (" (.getSimpleName (class t)) "): "
                          (.getMessage t)))
            (.printStackTrace t))
          (swallow (send-text ex 500 (str "server error: " (.getSimpleName (class t))))))))))

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
  (handler (fn [ex] (try-serve-static ex trace-dir (.getPath (.getRequestURI ex))))))

(defn- handle-annotations [trace-dir]
  (handler
    (fn [ex]
      (let [method (.getRequestMethod ex)
            trace  (:trace (parse-query (.getQuery (.getRequestURI ex))))]
        (if-not trace
          (send-text ex 400 "missing ?trace=<name>")
          (if-let [trace-file (safe-join trace-dir trace)]
            (let [ann-path (ann/trace->annotations-path (.getPath trace-file))]
              (case method
                "GET"     (send-json ex 200 (ann/read-sidecar ann-path))
                "POST"    (let [body (json-read-str (read-body ex))]
                            (cond
                              (ann/sidecar-stale? ann-path body)
                              (send-json ex 409 {:error     "stale-rev"
                                                 :on_disk   (schema/effective-rev
                                                              (ann/read-sidecar ann-path))
                                                 :supplied  (schema/effective-rev body)
                                                 :sidecar   (ann/read-sidecar ann-path)})
                              :else
                              (if-let [err (schema/validate-sidecar body)]
                                (send-json ex 400 {:error "schema" :detail err})
                                (do (ann/write-sidecar ann-path body)
                                    (send-json ex 200
                                               {:ok  true
                                                :rev (schema/effective-rev
                                                       (ann/read-sidecar ann-path))})))))
                "OPTIONS" (preflight! ex "GET, POST, OPTIONS")
                (method-not-allowed! ex method)))
            (send-text ex 403 "trace path escapes root")))))))

(defn- assets-dir-for
  "Where image attachments live. `<trace-dir>/<trace-base>.annotations.assets/`.
   Co-located with the sidecar so a git-tracked review carries its evidence."
  [trace-dir trace-name]
  (io/file trace-dir (str (trace-base trace-name) ".annotations.assets")))

(defn- handle-asset
  "POST a raw image body with Content-Type: image/*. Writes a UUID-named
   file under `<trace>.annotations.assets/` and returns the relative path
   the viewer can embed in an attachment record."
  [trace-dir]
  (handler
    (fn [ex]
      (let [method (.getRequestMethod ex)
            trace  (:trace (parse-query (.getQuery (.getRequestURI ex))))]
        (case method
          "POST"
          (cond
            (not trace)
            (send-text ex 400 "missing ?trace=<name>")

            (not (safe-join trace-dir trace))
            (send-text ex 403 "trace path escapes root")

            :else
            (let [ctype   (-> ex .getRequestHeaders (.getFirst "Content-Type"))
                  bs      (read-body-bytes ex)
                  ext     (ext-for ctype)
                  uuid    (str (java.util.UUID/randomUUID))
                  base    (trace-base trace)
                  rel     (str base ".annotations.assets/" uuid "." ext)
                  out-dir (assets-dir-for trace-dir trace)
                  out     (io/file out-dir (str uuid "." ext))]
              (when-not (safe-join trace-dir (str base ".annotations.assets"))
                (throw (ex-info "asset dir escapes root" {:kind :user-error})))
              (io/make-parents out)
              (with-open [o (java.io.FileOutputStream. out)]
                (.write o bs))
              (send-json ex 200 {:path rel :bytes (count bs) :content_type ctype})))

          "OPTIONS" (preflight! ex "POST, OPTIONS")

          (method-not-allowed! ex method))))))

(defn- handle-focus [trace-dir]
  (handler
    (fn [ex]
      (let [method (.getRequestMethod ex)
            fp     (ann/trace->focus-path (.getPath trace-dir))]
        (case method
          "GET"
          (let [f (io/file fp)]
            (if (.exists f)
              (send-json ex 200 (json-read-str (slurp f)))
              (send-json ex 200 {})))

          "POST"
          (do (io/make-parents fp)
              (spit fp (json-write-str (json-read-str (read-body ex))))
              (send-text ex 200 "ok"))

          "OPTIONS" (preflight! ex "GET, POST, OPTIONS")

          (method-not-allowed! ex method))))))

;; ---------- chat excerpt lookup ----------

(def ^:private token-re #"^[a-z0-9]{4,16}$")

(defn- handle-chat-excerpt
  "GET /chat-excerpt?token=<tok> → JSON array of {session, timestamp, text}
   for every assistant-role line in any user-local session JSONL whose
   content contains the token. Token must match [a-z0-9]{4,16}; anything
   else is rejected to prevent exfil of unrelated chat prose."
  []
  (handler
    (fn [ex]
      (let [token (:token (parse-query (.getQuery (.getRequestURI ex))))]
        (if-not (and token (re-matches token-re token))
          (send-text ex 400 "missing or malformed ?token= (expected [a-z0-9]{4,16})")
          (send-json ex 200 (chatlog/scan-for-token token)))))))

;; ---------- user-settings helpers (crossSessionInbound) ----------

(defn- user-settings-file ^java.io.File []
  (io/file (System/getProperty "user.home") ".claude" "settings.json"))

(defn- read-user-settings
  "Return a `[tag payload]` pair. The tag is one of `:missing`, `:ok`,
   or `:invalid`. Keeping the three distinct lets callers create on
   missing, merge on ok, and refuse on invalid instead of conflating
   absence and parse failure. Payload is nil, the parsed JSON value,
   or an error message string respectively."
  []
  (let [f (user-settings-file)]
    (if-not (.exists f)
      [:missing nil]
      (try [:ok (json-read-str (slurp f))]
           (catch Throwable t
             [:invalid (.getMessage t)])))))

(defn- print-inbound-status
  "Report the inbound state after any auto-write has happened. If
   crossSessionInbound is `accept`, say so. If it's something else, warn
   the user's own choice wins. If it's still unset (we chose not to
   write), explain the trade-off."
  []
  (let [f           (user-settings-file)
        [tag payload] (read-user-settings)
        cs          (when (= :ok tag) (:crossSessionInbound payload))]
    (case tag
      :invalid
      (println (str "  inbound   = unknown (" (.getPath f)
                    " is not valid JSON; left alone)"))

      (:ok :missing)
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
            (println "              or re-run without --no-accept-peer-inbound to merge it in"))))))

(defn- write-user-settings-accept!
  "Merge `crossSessionInbound: accept` into ~/.claude/settings.json,
   preserving any other keys. Creates ~/.claude/ if missing. Idempotent.
   Refuses to touch the file when it is present but not valid JSON, so a
   half-written settings file is never clobbered."
  []
  (let [f             (user-settings-file)
        [tag payload] (read-user-settings)]
    (case tag
      :invalid
      (eprintln (str "warning: " (.getPath f) " is not valid JSON ("
                     payload "); leaving it alone"))

      (:ok :missing)
      (let [existing (or payload {})
            current  (:crossSessionInbound existing)]
        (cond
          (= "accept" current)
          (println (str "  (no change; " (.getPath f) " already has crossSessionInbound=accept)"))

          (and (string? current) (not= "accept" current))
          (eprintln (str "warning: " (.getPath f) " has crossSessionInbound=\"" current
                         "\"; refusing to overwrite your explicit choice. Edit the file"
                         " by hand or remove the key, then rerun."))

          :else
          (do (io/make-parents f)
              (spit f (json-write-str (assoc existing :crossSessionInbound "accept")
                                      :escape-slash false :indent true))
              (println (str "  wrote crossSessionInbound=accept to " (.getPath f)))))))))

;; ---------- startup ----------

(defn start!
  "Start the trace server. Returns the HttpServer instance so a caller
   can .stop it. Blocks on current thread forever when `block?` is true,
   returns otherwise."
  [{:keys [port trace-dir block? push? accept-peer-inbound?]
    :or   {port 8767 block? false push? true accept-peer-inbound? true}}]
  (let [td (io/file trace-dir)]
    (when-not (.isDirectory td)
      (throw (ex-info (str "trace-dir does not exist: " (.getPath td))
                      {:kind :user-error :trace-dir (.getPath td)})))
    (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
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
      srv)))

;; ---------- CLI entry ----------

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
  (let [flags (cli/parse-flags args)]
    (if (:help flags)
      (usage)
      (let [port      (if-let [p (:port flags)] (Integer/parseInt p) 8767)
            trace-dir (or (:trace-dir flags)
                          (:dir flags)
                          (System/getProperty "user.dir"))
            push?     (not (true? (:no-push flags)))
            accept?   (not (true? (:no-accept-peer-inbound flags)))]
        (try
          (start! {:port port
                   :trace-dir trace-dir
                   :block? true
                   :push? push?
                   :accept-peer-inbound? accept?})
          (catch clojure.lang.ExceptionInfo e
            (eprintln (.getMessage e))
            (System/exit 2)))))))
