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

(defn- usage []
  (println "Usage: cxc view [<trace.jsonl>] [--port=N]")
  (println "  Opens the browser-based viewer. If a trace file is given,")
  (println "  it's copied to compiler/tools/.current-trace.jsonl and")
  (println "  auto-loaded via ?trace=... in the URL."))

(defn run [args]
  (let [opts (atom {:port 8765})
        positional (atom [])]
    (doseq [a args]
      (cond
        (str/starts-with? a "--port=")
        (swap! opts assoc :port (Integer/parseInt (subs a (count "--port="))))
        (#{"-h" "--help"} a) (do (usage) (throw (ex-info "help" {:exit 0})))
        :else (swap! positional conj a)))
    (let [root (io/file (tools-dir))
          _    (when-not (.exists (io/file root "trace-viewer.html"))
                 (binding [*out* *err*]
                   (println (str "trace-viewer.html not found under " (.getPath root))))
                 (throw (ex-info "no viewer" {:exit 2})))
          query (if-let [trace (first @positional)]
                  (let [src (io/file trace)]
                    (when-not (.exists src)
                      (binding [*out* *err*] (println (str "no such file: " trace)))
                      (throw (ex-info "not found" {:exit 2})))
                    (io/copy src (io/file root ".current-trace.jsonl"))
                    "?trace=.current-trace.jsonl")
                  "")
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
