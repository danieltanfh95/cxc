(ns cxc.trace-server
  "Local HTTP host for the browser trace viewer. Replaces the ad-hoc
   `python3 -m http.server :8767` with a zero-dep Clojure server, so
   the browser can POST annotations and focus state back to disk
   without a second runtime.

   Mounts a static-file root (default: trace-dir, else compiler/tools/
   where trace-viewer.html lives) and two JSON endpoints:

     POST /annotations?trace=<name>  body = sidecar JSON (overwrites)
     GET  /annotations?trace=<name>  returns current sidecar JSON
     POST /focus                     body = {trace, atom, seq, selected_edge}
     GET  /focus                     returns current focus JSON

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

(defn- json-write-str [v]
  ((requiring-resolve 'clojure.data.json/write-str) v))

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

;; ---------- startup ----------

(defn start!
  "Start the trace server. Returns the HttpServer instance so a caller
   can .stop it. Blocks on current thread forever when `block?` is true,
   returns otherwise."
  [{:keys [port trace-dir block?]
    :or   {port 8767 block? false}}]
  (let [td  (io/file trace-dir)
        _   (when-not (.isDirectory td)
              (binding [*out* *err*]
                (println (str "trace-dir does not exist: " (.getPath td))))
              (System/exit 2))
        srv (HttpServer/create (InetSocketAddress. port) 0)]
    (.createContext srv "/annotations" (handle-annotations td))
    (.createContext srv "/focus"       (handle-focus td))
    (.createContext srv "/"            (handle-static td))
    (.setExecutor srv (Executors/newFixedThreadPool 4))
    (.start srv)
    (println (str "cxc trace serve, listening on http://localhost:" port))
    (println (str "  trace-dir = " (.getCanonicalPath td)))
    (println (str "  tools-dir = " (.getCanonicalPath (fallback-tools-dir))))
    (println (str "  viewer    = http://localhost:" port "/trace-viewer.html"))
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
          (recur (assoc out (keyword (subs f 2)) true) (cons v more)))
        :else
        (recur (update out ::positional (fnil conj []) f) (cons v more)))
      out)))

(defn- usage []
  (println "Usage: cxc trace serve [--port 8767] [--trace-dir <path>]")
  (println "")
  (println "  Serves the browser trace viewer at http://localhost:<port>")
  (println "  Mounts <trace-dir> as the static root so *.trace.jsonl,")
  (println "  *.shape.json, and *.annotations.json are reachable as")
  (println "  /<name>. Falls back to compiler/tools/ for trace-viewer.html")
  (println "  if the file isn't in trace-dir.")
  (println "")
  (println "  POST /annotations?trace=<name> writes <name>.annotations.json")
  (println "  POST /focus writes cxc-trace-focus.json in trace-dir"))

(defn run [args]
  (let [flags (parse-flags args)]
    (cond
      (:help flags)
      (usage)

      :else
      (let [port      (if-let [p (:port flags)] (Integer/parseInt p) 8767)
            trace-dir (or (:trace-dir flags)
                          (:dir flags)
                          (System/getProperty "user.dir"))]
        (start! {:port port :trace-dir trace-dir :block? true})))))
