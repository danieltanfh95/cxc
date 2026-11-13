(ns cxc.core
  "cxc compiler entry point + subcommand dispatch.

   Usage:
     cxc compile      <input.cxc-or-dir> <out-dir> [--target=<name>] [--trace]
     cxc trace        <file.jsonl> [--top=N] [--silent-clock]
     cxc trace-filter <file.jsonl> [--kinds=...] [--atom=...] [--selector=...] [--seq=lo-hi]
     cxc trace-diff   <a.jsonl> <b.jsonl>
     cxc coverage     <binary> <trace.jsonl> [<trace.jsonl>...]
     cxc test         <fixture.edn> [<fixture.edn>...]
     cxc trace-index  <trace.jsonl> <op> [args...]
     cxc view         [<trace.jsonl>] [--port=N]
     cxc help         [<subcommand>]

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

(set! *warn-on-reflection* true)

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

(def ^:private trace-nested-subs
  #{"annotation" "annotate" "focus"})

(defn run-trace [args]
  ;; `cxc trace {annotation|annotate|focus} ...` routes to the review-loop
  ;; CLI, `cxc trace serve ...` to the Clojure viewer host, everything else
  ;; is the legacy trace summary (backward compat). The viewer host now also
  ;; owns the push-to-inbox-socket fan-out that used to live under
  ;; `cxc trace channel`.
  (cond
    (= "serve" (first args))
    ((requiring-resolve 'cxc.trace-server/run) (vec (rest args)))

    (contains? trace-nested-subs (first args))
    ((requiring-resolve 'cxc.trace-annotations/run) args)

    :else
    ((requiring-resolve 'cxc.trace-summarize/run) args)))

(defn run-view [args]
  ((requiring-resolve 'cxc.trace-view/run) args))

(declare run-help)

(def ^:private subcommands
  "Subcommand catalogue. One value, three readers: `top-usage` listing,
   `run-help` routing, `-main` dispatch and legacy-path exclusion."
  (array-map
   "compile"      {:handler #'run-compile
                   :synopsis "compile a .cxc source or plugin directory to a binary"
                   :help     compile-usage}
   "trace"        {:handler #'run-trace
                   :synopsis "print CLI diagnostics for a --trace JSONL dump"
                   :help     #(run-trace ["--help"])}
   "trace-filter" {:handler (fn [args] ((requiring-resolve 'cxc.trace-filter/run) args))
                   :synopsis "filter a JSONL trace by kind/atom/selector/seq range"}
   "trace-diff"   {:handler (fn [args] ((requiring-resolve 'cxc.trace-diff/run) args))
                   :synopsis "canonicalize ptrs, report first divergence between two traces"}
   "coverage"     {:handler (fn [args] ((requiring-resolve 'cxc.coverage/run) args))
                   :synopsis "arm-level coverage from a trace corpus against a binary's shape sidecar"
                   :help     #((requiring-resolve 'cxc.coverage/run) ["--help"])}
   "test"         {:handler (fn [args] ((requiring-resolve 'cxc.scenario/run) args))
                   :synopsis "run EDN scenario fixtures, match trace against patterns"
                   :help     #((requiring-resolve 'cxc.scenario/run) ["--help"])}
   "trace-index"  {:handler (fn [args] ((requiring-resolve 'cxc.trace-index/run) args))
                   :synopsis "scan queries over a trace JSONL (atom-of / arms-of / between / count-by-kind / tally-arms)"
                   :help     #((requiring-resolve 'cxc.trace-index/run) ["--help"])}
   "view"         {:handler #'run-view
                   :synopsis "open the browser-based trace viewer"
                   :help     #(run-view ["--help"])}
   "help"         {:handler #'run-help
                   :synopsis "show this help, or `cxc help <subcommand>`"}))

(defn- top-usage []
  (println "Usage: cxc <subcommand> [args...]")
  (println "")
  (println "Subcommands:")
  (doseq [[name {:keys [synopsis]}] subcommands]
    (println (format "  %-13s %s" name synopsis))))

(defn run-help [args]
  (if-let [help-fn (:help (get subcommands (first args)))]
    (help-fn)
    (top-usage)))

(defn -main [& args]
  (try
    (let [[sub & rest] args
          ;; Backward compat: if first arg is a path that exists (or looks
          ;; like a .cxc file), treat as `compile`.
          legacy-compile? (and sub
                               (not (contains? subcommands sub))
                               (not (#{"-h" "--help"} sub))
                               (or (str/ends-with? sub ".cxc")
                                   (.exists (io/file sub))))]
      (cond
        legacy-compile?           (run-compile args)
        (nil? sub)                (do (top-usage) (System/exit 2))
        (#{"-h" "--help"} sub)    (top-usage)
        :else
        (if-let [{:keys [handler]} (get subcommands sub)]
          (handler (vec rest))
          (do (println (str "unknown subcommand: " sub))
              (top-usage)
              (System/exit 2)))))
    (catch clojure.lang.ExceptionInfo ex
      (let [d    (ex-data ex)
            exit (:exit d 1)
            msg  (.getMessage ex)
            loc  (:location d)
            rule (:rule-ref d)
            kind (:kind d)]
        ;; Only surface the message when it isn't the run-compile
        ;; sentinel (which already printed "compile failed:" + stderr).
        (when-not (= msg "compile failed")
          (binding [*out* *err*]
            ;; BOARD-7 (2026-11-13) — canonical diagnostic format
            ;; `file:line:col: <kind>[:rule-ref]: <msg>` so the error
            ;; line is grep-friendly and tooling can parse it. Drops
            ;; back to bare msg when the thrower didn't attach a
            ;; location (older sites or non-user errors).
            (let [prefix (cond
                           (and loc (:line loc))
                           (str (or (:file loc) "<unknown>")
                                ":" (:line loc)
                                (when (:col loc) (str ":" (:col loc)))
                                ": ")
                           :else "")
                  tag (cond
                        (and kind rule) (str (name kind) " [" rule "]: ")
                        kind            (str (name kind) ": ")
                        :else           "")]
              (println (str prefix tag msg)))
            (when-let [node (:node d)]
              (println (str "  at node: "
                            (pr-str (select-keys node [:node :name :sym])))))))
        (System/exit exit))))
  ;; Success path falls through here. clojure.java.shell/sh spawns futures on
  ;; the agent soloExecutor whose pool keeps the JVM alive for its 60s idle
  ;; timeout. Shut agents down so the JVM exits promptly.
  (shutdown-agents))
