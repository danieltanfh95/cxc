(ns cxc.core
  "cxc compiler entry point + subcommand dispatch.

   Usage:
     cxc compile <input.cxc-or-dir> <out-dir> [--target=<name>] [--trace]
     cxc trace   <file.jsonl> [--top=N] [--silent-clock]
     cxc view    [<trace.jsonl>] [--port=N]
     cxc help    [<subcommand>]

   For backward compatibility with the pre-subcommand invocation, if the
   first argument looks like a path (file or directory that exists), we
   route to `compile`. This will be dropped once callers migrate.

   Pipeline (compile):
     cxc.lex   → cxc.parse → cxc.lower → cxc.emit → cxc.build

   Runtime deps at link time: libobjc2 · libdispatch · libuv · cxc runtime lib."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.build :as build])
  (:gen-class))

(defn- parse-target-flag [args]
  (let [[flag positional] ((juxt filter remove) #(str/starts-with? % "--target=") args)
        target (when-let [f (first flag)]
                 (keyword (subs f (count "--target="))))]
    [(vec positional) target]))

(defn- parse-trace-flag [args]
  (let [[flag positional] ((juxt filter remove) #{"--trace"} args)]
    [(vec positional) (boolean (seq flag))]))

(defn- compile-usage []
  (println "Usage: cxc compile <input.cxc-or-dir> <out-dir> [--target=<name>] [--trace]"))

(defn run-compile [args]
  (let [[positional target] (parse-target-flag args)
        [positional trace?] (parse-trace-flag positional)]
    (if (= 2 (count positional))
      (let [[input-path out-dir] positional
            in-file (io/file input-path)
            opts    (cond-> {}
                      target (assoc :target target)
                      trace? (assoc :trace? true))
            dir?    (.isDirectory in-file)
            name    (if dir?
                      (.getName in-file)
                      (-> input-path io/file .getName (str/replace #"\.cxc$" "")))
            {:keys [status stderr bin-path]}
            (if dir?
              (build/compile-directory-to-file in-file name out-dir opts)
              (build/compile-source-to-file (slurp input-path) name out-dir opts))]
        (if (zero? status)
          (println (str "compiled → " bin-path))
          (do (println "compile failed:")
              (println stderr)
              (throw (ex-info "compile failed" {:exit status})))))
      (do (compile-usage)
          (println "Args received:" (pr-str args))
          (throw (ex-info "bad usage" {:exit 2}))))))

(defn run-trace [args]
  ;; Lazy-load so trace-summarize's data.json dep doesn't slow compile.
  ((requiring-resolve 'cxc.trace-summarize/run) args))

(defn run-view [args]
  ((requiring-resolve 'cxc.trace-view/run) args))

(defn- top-usage []
  (println "Usage: cxc <subcommand> [args...]")
  (println "")
  (println "Subcommands:")
  (println "  compile  compile a .cxc source or plugin directory to a binary")
  (println "  trace    print CLI diagnostics for a --trace JSONL dump")
  (println "  view     open the browser-based trace viewer")
  (println "  help     show this help, or `cxc help <subcommand>`"))

(defn run-help [args]
  (case (first args)
    "compile" (compile-usage)
    "trace"   (run-trace ["--help"])
    "view"    (run-view ["--help"])
    (top-usage)))

(defn -main [& args]
  (try
    (let [[sub & rest] args
          ;; Backward compat: if first arg is a path that exists (or looks
          ;; like a .cxc file), treat as `compile`.
          legacy-compile? (and sub
                               (not (#{"compile" "trace" "view" "help"
                                       "-h" "--help"} sub))
                               (or (str/ends-with? sub ".cxc")
                                   (.exists (io/file sub))))]
      (cond
        legacy-compile?           (run-compile args)
        (nil? sub)                (do (top-usage) (System/exit 2))
        (#{"-h" "--help"} sub)    (top-usage)
        (= sub "compile")         (run-compile (vec rest))
        (= sub "trace")           (run-trace (vec rest))
        (= sub "view")            (run-view (vec rest))
        (= sub "help")            (run-help (vec rest))
        :else (do (println (str "unknown subcommand: " sub))
                  (top-usage)
                  (System/exit 2))))
    (catch clojure.lang.ExceptionInfo ex
      (let [exit (:exit (ex-data ex) 1)]
        (System/exit exit)))))
