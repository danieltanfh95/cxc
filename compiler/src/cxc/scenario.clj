(ns cxc.scenario
  "cxc test — scenario-driven e2e harness.

   Reads an EDN fixture, runs a cxc binary with CXC_TRACE_PATH set,
   then matches the resulting trace against operators. Replaces
   imperative arrange-act-assert with declarative (fixture, pattern).

   Fixture shape:
     {:name     \"<string>\"
      :binary   \"<path-to-binary>\"       ; absolute or relative to CWD
      :args     [\"a\" \"b\" ...]            ; positional argv
      :env      {\"NAME\" \"value\" ...}     ; overlays process env
      :stdin    \"...\"                     ; optional piped stdin
      :timeout_ms 5000                     ; optional, default 10s
      :expect   {:exit         <int>       ; required-match if present
                 :stdout_contains [\"...\"]
                 :arms_contain [{:class \"...\" :msg \"...\" :line <int>}]
                 :arms_absent  [{:class \"...\" :msg \"...\"}]
                 :arm_count    {{:msg \"bump\"} 3}
                 :ordered_after [[{:msg \"start\"} {:msg \"stop\"}]]}}

   Match semantics:
     - arms_contain: each pattern matches at least one `kind=arm` trace
                     event. Pattern keys are all optional; omitted keys
                     match anything.
     - arms_absent:  no trace event matches any pattern.
     - arm_count:    for each pattern, count of matching events equals
                     the integer value.
     - exit:         process exit code equals integer.
     - stdout_contains: each substring appears in captured stdout.
     - ordered_after:  earlier-seq match for pat-a precedes pat-b.

   Chaos variant: when `:chaos <rate>` is present on the fixture, the
   binary spawns with CXC_REPL=1, the harness connects to the REPL
   socket after `:chaos_pre_ms` (default 200ms) to post the rate, and
   lets the binary run `:chaos_run_ms` (default 1500ms) before killing
   and collecting the trace. Use for residuality probes.

   Exit 0 on all-match, 1 on any mismatch."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(defn- parse-extra [extra]
  (when (and extra (seq extra))
    (into {}
          (keep (fn [part]
                  (let [[k v] (str/split part #"=")]
                    (when (and k v)
                      (case k
                        "arm"  [:arm-index (Long/parseLong v)]
                        "line" [:line (Long/parseLong v)]
                        nil)))))
          (str/split extra #","))))

(defn- arm-event
  "Normalize an arm event into {:class :msg :line :arm-index :seq}
   (keys present only when the trace carries them). Returns nil for
   non-arm."
  [ev]
  (when (= "arm" (:kind ev))
    (let [ex (parse-extra (:extra ev))]
      (cond-> {:class (:target_cls ev)
               :msg   (:msg ev)
               :seq   (:seq ev)}
        (:line ex)      (assoc :line (:line ex))
        (:arm-index ex) (assoc :arm-index (:arm-index ex))))))

(defn- load-arm-events [trace-path]
  (with-open [r (io/reader trace-path)]
    (into []
          (keep (fn [line]
                  (let [ev (try (json/read-str line :key-fn keyword)
                                (catch Exception _ nil))]
                    (when ev (arm-event ev)))))
          (line-seq r))))

(defn- pattern-matches?
  "An arm event matches a pattern when every key present in the pattern
   equals the same key on the event. Absent keys are wildcards. The
   :seq key on events is never required-by-pattern — callers compare
   it directly for ordering."
  [pattern event]
  (every? (fn [[k v]] (= v (get event k))) (dissoc pattern :seq)))

(defn- first-match-seq
  "Earliest :seq of an event matching the pattern, or nil."
  [pattern events]
  (some #(when (pattern-matches? pattern %) (:seq %)) events))

(defn- count-matches [patterns events]
  (into {}
        (map (fn [pat]
               [pat (count (filter #(pattern-matches? pat %) events))])
             patterns)))

(defn- check-expectations [expect events exit-code stdout]
  (let [results (atom [])]
    (when-let [ex (:exit expect)]
      (swap! results conj
             {:op :exit
              :pass? (= ex exit-code)
              :msg (str "exit expected=" ex " actual=" exit-code)}))
    (doseq [s (:stdout_contains expect)]
      (swap! results conj
             {:op :stdout_contains
              :pass? (str/includes? stdout s)
              :msg (str "stdout_contains " (pr-str s))}))
    (doseq [pat (:arms_contain expect)]
      (let [hit (some #(pattern-matches? pat %) events)]
        (swap! results conj
               {:op :arms_contain
                :pass? (boolean hit)
                :msg (str "arms_contain " (pr-str pat))})))
    (doseq [pat (:arms_absent expect)]
      (let [hit (some #(pattern-matches? pat %) events)]
        (swap! results conj
               {:op :arms_absent
                :pass? (not hit)
                :msg (str "arms_absent " (pr-str pat))})))
    (doseq [[pat expected-n] (:arm_count expect)]
      (let [actual (count (filter #(pattern-matches? pat %) events))]
        (swap! results conj
               {:op :arm_count
                :pass? (= expected-n actual)
                :msg (str "arm_count " (pr-str pat)
                          " expected=" expected-n " actual=" actual)})))
    (doseq [pair (:ordered_after expect)]
      (let [[pat-a pat-b] pair
            sa (first-match-seq pat-a events)
            sb (first-match-seq pat-b events)]
        (swap! results conj
               {:op :ordered_after
                :pass? (and sa sb (< sa sb))
                :msg (str "ordered_after " (pr-str pat-a)
                          " -> " (pr-str pat-b)
                          " (a=" sa " b=" sb ")")})))
    @results))

(defn- envify [env-map]
  (reduce-kv (fn [m k v] (assoc m (str k) (str v))) {} (or env-map {})))

(defn- run-scenario [fixture]
  (let [trace-path (str (System/getProperty "java.io.tmpdir")
                        "/cxc-scenario-"
                        (System/currentTimeMillis)
                        ".jsonl")
        bin   (:binary fixture)
        args  (mapv str (or (:args fixture) []))
        env   (-> (into {} (System/getenv))
                  (merge (envify (:env fixture)))
                  (assoc "CXC_TRACE_PATH" trace-path))
        stdin-str (:stdin fixture)
        env-strs (reduce-kv (fn [a k v] (conj a (str k "=" v))) [] env)
        _ (when-not (.exists (io/file bin))
            (throw (ex-info (str "binary missing: " bin) {:exit 2})))
        chaos    (:chaos fixture)
        timeout  (or (:timeout_ms fixture) 10000)
        ;; BOARD-20 chaos (2026-11-13) — `:chaos <rate>` opts in to a
        ;; ProcessBuilder spawn with CXC_REPL=1 so we can connect to
        ;; the REPL socket and post `chaos <rate>` before the run
        ;; completes. Env already carries the overrides; add the
        ;; CXC_REPL=1 overlay for this launch only.
        {exit :exit out :out err :err}
        (if chaos
          (let [pb      (ProcessBuilder. ^java.util.List (into [bin] args))
                _       (-> (.environment pb) (.clear))
                _       (doseq [s env-strs]
                          (let [[k v] (str/split s #"=" 2)]
                            (.put (.environment pb) k (or v ""))))
                _       (.put (.environment pb) "CXC_REPL" "1")
                _       (.redirectErrorStream pb false)
                p       (.start pb)
                sock    (str "/tmp/cxc-repl-" (.pid p) ".sock")
                pre-ms  (or (:chaos_pre_ms fixture) 200)
                run-ms  (or (:chaos_run_ms fixture) 1500)]
            (Thread/sleep ^long pre-ms)
            (when (.exists (io/file sock))
              (let [addr (UnixDomainSocketAddress/of sock)
                    ch   (SocketChannel/open StandardProtocolFamily/UNIX)]
                (try
                  (.connect ch addr)
                  (.write ch (ByteBuffer/wrap
                              (.getBytes (str "chaos " chaos "\n")
                                         StandardCharsets/UTF_8)))
                  (.shutdownOutput ch)
                  (finally (.close ch)))))
            (Thread/sleep ^long run-ms)
            (when (.isAlive p) (.destroy p))
            (.waitFor p timeout TimeUnit/MILLISECONDS)
            (let [out-s (slurp (.getInputStream p))
                  err-s (slurp (.getErrorStream p))]
              {:exit (.exitValue p) :out out-s :err err-s}))
          (let [shell-args (concat [bin] args
                                   [:env (into-array String env-strs)]
                                   (when stdin-str [:in stdin-str]))]
            (apply shell/sh shell-args)))
        events (if (.exists (io/file trace-path))
                 (load-arm-events trace-path)
                 [])
        results (check-expectations (:expect fixture) events exit out)
        pass? (every? :pass? results)]
    {:name    (:name fixture)
     :pass?   pass?
     :results results
     :trace   trace-path
     :exit    exit
     :stdout  out
     :stderr  err}))

(defn- print-result! [r]
  (if (:pass? r)
    (println (format "  PASS  %s" (:name r)))
    (do
      (println (format "  FAIL  %s" (:name r)))
      (doseq [res (:results r) :when (not (:pass? res))]
        (println (format "        - %s" (:msg res))))
      (when (seq (:stderr r))
        (println "        stderr:")
        (doseq [l (str/split-lines (:stderr r))]
          (println (str "          " l)))))))

(defn- usage []
  (println "Usage: cxc test <fixture.edn> [<fixture.edn>...]")
  (println)
  (println "Fixture keys: :name :binary :args :env :stdin :timeout_ms :expect")
  (println "Expect ops:   :exit :stdout_contains :arms_contain :arms_absent")
  (println "              :arm_count :ordered_after"))

(defn run [args]
  (cond
    (or (empty? args) (contains? #{"-h" "--help"} (first args)))
    (do (usage) (when (empty? args) (System/exit 2)))

    :else
    (let [fixtures (for [p args]
                     (edn/read-string (slurp p)))
          results  (mapv run-scenario fixtures)]
      (println (format "Scenarios: %d" (count results)))
      (doseq [r results] (print-result! r))
      (let [passed (count (filter :pass? results))
            failed (- (count results) passed)]
        (println (format "\n%d passed, %d failed" passed failed))
        (when (pos? failed) (System/exit 1))))))
