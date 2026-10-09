(ns cxc.trace-view
  "Launcher for the browser-based trace viewer. Spins up a tiny HTTP
   server rooted at the compiler/tools/ directory so trace-viewer.html
   can fetch the JSONL over http:// (file:// URLs hit CORS restrictions
   when the page tries to `fetch(?trace=…)`), copies the caller's trace
   file to `.current-trace.jsonl` in that directory, and opens the
   default browser to the viewer URL with the ?trace= query param set.

   Ctrl-C stops the server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpServer HttpHandler HttpExchange)
           (java.net InetSocketAddress URI)
           (java.awt Desktop)))

(defn- content-type [^String path]
  (cond
    (str/ends-with? path ".html") "text/html; charset=utf-8"
    (str/ends-with? path ".js")   "application/javascript; charset=utf-8"
    (str/ends-with? path ".css")  "text/css; charset=utf-8"
    (str/ends-with? path ".jsonl") "application/x-ndjson; charset=utf-8"
    (str/ends-with? path ".json") "application/json; charset=utf-8"
    :else "application/octet-stream"))

(defn- serve-file [^HttpExchange ex ^java.io.File root]
  (let [uri (.getRequestURI ex)
        p   (.getPath uri)
        p   (if (= p "/") "/trace-viewer.html" p)
        rel (subs p 1)
        f   (io/file root rel)
        headers (.getResponseHeaders ex)]
    (if (and (.exists f) (.isFile f)
             (.startsWith (.getCanonicalPath f) (.getCanonicalPath root)))
      (let [bytes (java.nio.file.Files/readAllBytes (.toPath f))]
        (.set headers "Content-Type" (content-type rel))
        (.sendResponseHeaders ex 200 (alength bytes))
        (with-open [os (.getResponseBody ex)]
          (.write os bytes)))
      (do
        (.sendResponseHeaders ex 404 -1)
        (.close (.getResponseBody ex))))))

(defn- start-server [^java.io.File root port]
  (let [srv (HttpServer/create (InetSocketAddress. port) 0)]
    (.createContext srv "/"
      (reify HttpHandler
        (handle [_ ex] (serve-file ex root))))
    (.setExecutor srv nil)
    (.start srv)
    srv))

(defn- open-browser [url]
  ;; Prefer macOS `open`, then Linux `xdg-open`, then java.awt.Desktop.
  (try
    (cond
      (.contains (.toLowerCase (System/getProperty "os.name")) "mac")
      (.waitFor (.exec (Runtime/getRuntime) (into-array String ["open" url])))

      (.exists (io/file "/usr/bin/xdg-open"))
      (.waitFor (.exec (Runtime/getRuntime) (into-array String ["xdg-open" url])))

      :else
      (when (Desktop/isDesktopSupported)
        (.browse (Desktop/getDesktop) (URI. url))))
    (catch Throwable _ nil)))

(defn- tools-dir
  "Locate compiler/tools/ — the viewer HTML lives there. Search from
   the current working dir upward; fall back to the classpath resource
   lookup if the source tree isn't visible (e.g. jar-packaged install)."
  []
  (let [candidates ["tools" "compiler/tools"
                    "../compiler/tools"
                    "../../compiler/tools"]]
    (or (first (filter #(.exists (io/file % "trace-viewer.html")) candidates))
        "compiler/tools")))

(defn- executable-on-path? [command]
  (some (fn [directory]
          (let [file (io/file directory command)]
            (and (.isFile file) (.canExecute file))))
        (str/split (or (System/getenv "PATH") "")
                   (re-pattern java.io.File/pathSeparator))))

(defn- bundle-ready? [file]
  (and (.isFile file) (pos? (.length file))))

(defn- build-viewer-bundle! [root bundle]
  (let [project (io/file root "viewer-cljs")
        missing (remove executable-on-path? ["clojure" "java" "node"])]
    (when (seq missing)
      (throw (ex-info (str "Viewer bundle missing: " bundle ". Install missing dependencies: "
                           (str/join ", " missing) ". The build also needs access to Maven dependencies.")
                      {:exit 2})))
    (when-not (.isFile (io/file project "deps.edn"))
      (throw (ex-info (str "Viewer build sources missing under " project "; use a complete source checkout.")
                      {:exit 2})))
    (println (str "Viewer bundle missing; building " bundle " …"))
    (let [process (try
                    (-> (ProcessBuilder. ^java.util.List ["clojure" "-M" "-m" "shadow.cljs.devtools.cli" "release" "viewer-v2"])
                        (.directory (.getCanonicalFile project))
                        .inheritIO
                        .start)
                    (catch java.io.IOException error
                      (throw (ex-info "Cannot start viewer build; install Clojure CLI, Java and Node.js."
                                      {:exit 2} error))))
          status  (.waitFor process)]
      (when-not (and (zero? status) (bundle-ready? bundle))
        (throw (ex-info (str "Viewer build failed (exit " status "). Required: Clojure CLI, Java, Node.js, "
                             "and Maven access for shadow-cljs, Replicant and Garden. See the build output above.")
                        {:exit 2}))))))

(defn- ensure-viewer-bundle! [root]
  (let [bundle (io/file root "dist" "trace-viewer-v2.js")]
    (when-not (bundle-ready? bundle)
      (build-viewer-bundle! root bundle))))

(defn- usage []
  (println "Usage: cxc view [<trace.jsonl>] [--port=N] [--shape=<path>]")
  (println "  Opens the browser-based viewer. If a trace file is given,")
  (println "  it's copied to compiler/tools/.current-trace.jsonl and")
  (println "  auto-loaded via ?trace=... in the URL. The sidecar")
  (println "  <trace>.shape.json (or --shape=<path>) is copied to")
  (println "  compiler/tools/.current-trace.shape.json, or a stale")
  (println "  copy is cleared if no sidecar is available."))

(defn- resolve-shape-sidecar
  "Pick the shape.json file that should accompany `trace-file`. Precedence:
    1. explicit --shape=<path>
    2. sibling named `<trace-basename-sans-.jsonl>.shape.json`
    3. sibling named `<trace-basename>.shape.json` (treats trace as prefix)
    4. a single `*.shape.json` in the same directory as the trace
   Returns a java.io.File that exists, or nil."
  [trace-file explicit-path]
  (let [try-file (fn [p] (let [f (io/file p)] (when (.exists f) f)))]
    (or (when explicit-path
          (or (try-file explicit-path)
              (do (binding [*out* *err*]
                    (println (str "warning: --shape=" explicit-path
                                  " not found; shape view will be unavailable")))
                  nil)))
        (let [name (.getName trace-file)
              dir  (.getParentFile trace-file)
              stem (if (str/ends-with? name ".jsonl")
                     (subs name 0 (- (count name) 6))
                     name)]
          (or (try-file (io/file dir (str stem ".shape.json")))
              (try-file (io/file dir (str name ".shape.json")))
              (let [sibs (when dir
                           (filter #(str/ends-with? (.getName %) ".shape.json")
                                   (.listFiles dir)))]
                (when (= 1 (count sibs))
                  (first sibs))))))))

(defn- clear-shape-sidecar!
  "Delete a stale .current-trace.shape.json so the viewer falls back to
   trace-only mode instead of serving a shape doc from a prior run."
  [root]
  (let [f (io/file root ".current-trace.shape.json")]
    (when (.exists f) (.delete f))))

(defn- view-flag-value [flag value]
  (when (or (nil? value) (str/starts-with? value "--"))
    (throw (ex-info (str flag " requires a value") {:exit 2})))
  (str flag "=" value))

(defn- expand-view-flags [args]
  (loop [[arg & more] args out []]
    (cond
      (nil? arg) out
      (#{"--port" "--shape"} arg) (recur (rest more) (conj out (view-flag-value arg (first more))))
      :else (recur more (conj out arg)))))

(defn run [args]
  (let [opts       (atom {:port 8765})
        positional (atom [])]
    (doseq [a (expand-view-flags args)]
      (cond
        (str/starts-with? a "--port=") (swap! opts assoc :port (Integer/parseInt (subs a (count "--port="))))
        (str/starts-with? a "--shape=") (swap! opts assoc :shape (subs a (count "--shape=")))
        (#{"-h" "--help"} a) (do (usage) (throw (ex-info "help" {:exit 0})))
        :else (swap! positional conj a)))
    (let [root (io/file (tools-dir))
          _    (when-not (.exists (io/file root "trace-viewer.html"))
                 (binding [*out* *err*]
                   (println (str "trace-viewer.html not found under " (.getPath root))))
                 (throw (ex-info "no viewer" {:exit 2})))
          _     (ensure-viewer-bundle! root)
          query (if-let [trace (first @positional)]
                  (let [src  (io/file trace)
                        dest (io/file root ".current-trace.jsonl")]
                    (when-not (.exists src)
                      (binding [*out* *err*] (println (str "no such file: " trace)))
                      (throw (ex-info "not found" {:exit 2})))
                    ;; Guard: if the caller pointed at the already-mirrored
                    ;; file, io/copy would truncate it (open-for-write on
                    ;; dest happens before the read from src). Skip in that
                    ;; case; the file is already in place.
                    (when-not (= (.getCanonicalPath src) (.getCanonicalPath dest))
                      (io/copy src dest))
                    ;; Shape sidecar sync. The .shape.json is produced by
                    ;; `cxc compile` next to the binary; the viewer loads it
                    ;; from .current-trace.shape.json. Without this step the
                    ;; sidecar persists across runs and the shape view
                    ;; silently uses stale topology from a prior compile.
                    (let [shape-src (resolve-shape-sidecar src (:shape @opts))
                          shape-dst (io/file root ".current-trace.shape.json")]
                      (if shape-src
                        (do (when-not (= (.getCanonicalPath shape-src)
                                         (.getCanonicalPath shape-dst))
                              (io/copy shape-src shape-dst))
                            (println (str "shape sidecar: " (.getPath shape-src))))
                        (do (clear-shape-sidecar! root)
                            (println "shape sidecar: none found (shape view disabled)"))))
                    "?trace=.current-trace.jsonl")
                  (do (clear-shape-sidecar! root)
                      ""))
          port (:port @opts)
          url  (str "http://localhost:" port "/trace-viewer.html" query)
          srv  (start-server root port)]
      (println (str "trace-view running at " url))
      (println "Ctrl-C to stop.")
      (open-browser url)
      ;; Keep the JVM alive.
      (.. (Runtime/getRuntime)
          (addShutdownHook
            (Thread. #(.stop srv 0))))
      @(promise))))

(defn -main [& args]
  (try
    (when-not (some #{"-h" "--help"} args)
      (ensure-viewer-bundle! (io/file (tools-dir))))
    ((requiring-resolve 'cxc.trace-server/run) args)
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*] (println (.getMessage error)))
      (System/exit (:exit (ex-data error) 2)))))
