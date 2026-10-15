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
            [cxc.util.cli :as cli])
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
    (cond
      (nil? origin)
      nil
      (allowed-origin? origin)
      (do (.add (.getResponseHeaders ex) "Access-Control-Allow-Origin" origin)
          (.add (.getResponseHeaders ex) "Vary" "Origin"))
      :else
      nil)))

(defn- send-text [^HttpExchange ex status ^String body]
  (apply-cors! ex)
  (.add (.getResponseHeaders ex) "Content-Type" "text/plain; charset=utf-8")
  (let [bs (.getBytes body "UTF-8")]
    (.sendResponseHeaders ex status (count bs))
    (with-open [out (.getResponseBody ex)]
      (.write out bs))))

(defn- send-json [^HttpExchange ex status v]
  (apply-cors! ex)
  (.add (.getResponseHeaders ex) "Content-Type" "application/json")
  (let [bs (.getBytes (json-write-str v) "UTF-8")]
    (.sendResponseHeaders ex status (count bs))
    (with-open [out (.getResponseBody ex)]
      (.write out bs))))

(defn- send-bytes [^HttpExchange ex status ^String ctype ^bytes bs]
  (apply-cors! ex)
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
  "Resolve child under root, reject escaping segments. Uses Path/startsWith
   so a sibling like `rootname-other` doesn't satisfy a string-prefix check
   against `rootname`."
  [root child]
  (let [rp (-> (io/file root) .getCanonicalFile .toPath)
        f  (io/file root child)
        cp (-> f .getCanonicalFile .toPath)]
    (when (.startsWith cp rp) f)))

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
          (try (send-text ex 500 (str "server error: " (.getSimpleName (class t))))
               (catch Throwable _ nil)))))))

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

(defn- resolve-trace-name [trace-dir ^String name]
  (safe-join trace-dir name))

(defn- handle-annotations [trace-dir]
  (handler
    (fn [ex]
      (let [method (.getRequestMethod ex)
            trace  (:trace (parse-query (.getQuery (.getRequestURI ex))))]
        (cond
          (not trace)
          (send-text ex 400 "missing ?trace=<name>")

          :else
          (if-let [trace-file (resolve-trace-name trace-dir trace)]
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
                "OPTIONS" (do (.add (.getResponseHeaders ex) "Access-Control-Allow-Methods"
                                    "GET, POST, OPTIONS")
                              (.add (.getResponseHeaders ex) "Access-Control-Allow-Headers"
                                    "Content-Type")
                              (send-text ex 204 ""))
                (send-text ex 405 (str "method not allowed: " method))))
            (send-text ex 403 "trace path escapes root")))))))

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
                  base    (-> trace
                              (str/replace #"\.trace\.jsonl$" "")
                              (str/replace #"\.jsonl$" ""))
                  rel     (str base ".annotations.assets/" uuid "." ext)
                  out-dir (assets-dir-for trace-dir trace)
                  out     (io/file out-dir (str uuid "." ext))]
              (when-not (safe-join trace-dir (str base ".annotations.assets"))
                (throw (ex-info "asset dir escapes root" {:kind :user-error})))
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

          (send-text ex 405 (str "method not allowed: " method)))))))

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

          "OPTIONS"
          (do (.add (.getResponseHeaders ex) "Access-Control-Allow-Methods"
                    "GET, POST, OPTIONS")
              (.add (.getResponseHeaders ex) "Access-Control-Allow-Headers"
                    "Content-Type")
              (send-text ex 204 ""))

          (send-text ex 405 (str "method not allowed: " method)))))))

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
  "Three outcomes: `:missing`, `[:ok parsed]`, or `[:invalid message]`.
   Keeps the three distinct so callers can create on missing, merge on ok,
   and refuse on invalid instead of conflating absence and parse failure."
  []
  (let [f (user-settings-file)]
    (if-not (.exists f)
      :missing
      (try [:ok (json-read-str (slurp f))]
           (catch Throwable t
             [:invalid (.getMessage t)])))))

(defn- print-inbound-status
  "Report the inbound state after any auto-write has happened. If
   crossSessionInbound is `accept`, say so. If it's something else, warn
   the user's own choice wins. If it's still unset (we chose not to
   write), explain the trade-off."
  []
  (let [f  (user-settings-file)
        rs (read-user-settings)
        cs (when (and (vector? rs) (= :ok (first rs)))
             (:crossSessionInbound (second rs)))]
    (cond
      (and (vector? rs) (= :invalid (first rs)))
      (println (str "  inbound   = unknown (" (.getPath f)
                    " is not valid JSON; left alone)"))

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
   preserving any other keys. Creates ~/.claude/ if missing. Idempotent.
   Refuses to touch the file when it is present but not valid JSON, so a
   half-written settings file is never clobbered."
  []
  (let [f  (user-settings-file)
        rs (read-user-settings)]
    (cond
      (and (vector? rs) (= :invalid (first rs)))
      (binding [*out* *err*]
        (println (str "warning: " (.getPath f) " is not valid JSON ("
                      (second rs) "); leaving it alone")))

      :else
      (let [existing (if (= :missing rs) {} (second rs))
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
    (let [srv (HttpServer/create
                (InetSocketAddress. "127.0.0.1" port)
                0)]
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
        (try
          (start! {:port port
                   :trace-dir trace-dir
                   :block? true
                   :push? push?
                   :accept-peer-inbound? accept?})
          (catch clojure.lang.ExceptionInfo e
            (binding [*out* *err*] (println (.getMessage e)))
            (System/exit (if (= :user-error (:kind (ex-data e))) 2 1))))))))
