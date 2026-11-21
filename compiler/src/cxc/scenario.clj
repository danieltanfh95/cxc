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
  (:import [java.lang ProcessBuilder]
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

(defn- load-arm-events
  "Return `{:events <vec>, :errors <vec>}`. :events is the sequence of
   arm events in :seq order. :errors is a vector of {:line :reason}
   maps for lines the harness could not decode (malformed JSON, or a
   non-map top-level value).

   hickey-cycle-285 (2026-11-21) — R3 #3. Pre-cycle this swallowed
   every decode failure to nil so `arms_absent` on a corrupted trace
   passed as if the file were empty. The reader also preserved the
   physical line order even though `first-match-seq` promised the
   earliest :seq, which under shuffled traces produced the wrong
   winner. Now acquisition errors are returned as data and :seq
   ordering is enforced here."
  [trace-path]
  (with-open [r (io/reader trace-path)]
    (let [line-nums (volatile! 0)
          errors    (volatile! [])
          events    (into []
                          (keep (fn [line]
                                  (vswap! line-nums inc)
                                  (let [trimmed (str/trim line)]
                                    (when (seq trimmed)
                                      (let [ev (try
                                                 (json/read-str trimmed
                                                                :key-fn keyword)
                                                 (catch Exception e
                                                   (vswap! errors conj
                                                           {:line   @line-nums
                                                            :reason (.getMessage e)})
                                                   nil))]
                                        (cond
                                          (nil? ev) nil
                                          (not (map? ev))
                                          (do (vswap! errors conj
                                                      {:line   @line-nums
                                                       :reason "top-level JSON is not a map"})
                                              nil)
                                          :else (arm-event ev)))))))
                          (line-seq r))]
      {:events (vec (sort-by :seq events))
       :errors @errors})))

(defn- pattern-matches?
  "An arm event matches a pattern when every key present in the pattern
   equals the same key on the event. Absent keys are wildcards. The
   :seq key on events is never required-by-pattern — callers compare
   it directly for ordering."
  [pattern event]
  (every? (fn [[k v]] (= v (get event k))) (dissoc pattern :seq)))

(defn- first-match-seq
  "Earliest :seq of an event matching the pattern, or nil. Callers pass
   events already sorted by :seq (see `load-arm-events`), so a single
   left-to-right scan returns the earliest. hickey-cycle-285 moved the
   sort to the ingestion boundary; this scan assumes it has happened."
  [pattern events]
  (some #(when (pattern-matches? pattern %) (:seq %)) events))

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

(defn- pump-stream!
  "Drain `in` concurrently into a StringBuilder so pipe backpressure
   does not block the child while we're asleep. Returns a function
   that, when called, joins the thread and yields the collected string."
  [^java.io.InputStream in]
  (let [sb  (StringBuilder.)
        thr (Thread.
             ^Runnable
             (fn []
               (with-open [r (io/reader in)]
                 (let [buf (char-array 4096)]
                   (loop []
                     (let [n (.read r buf 0 (alength buf))]
                       (when (pos? n)
                         (.append sb buf 0 n)
                         (recur))))))))]
    (.setDaemon thr true)
    (.start thr)
    (fn []
      (.join thr 2000)
      (str sb))))

(defn- await-socket!
  "Poll for the REPL socket up to deadline-ms. Return the path when
   it appears, nil when the deadline lapses. Prevents the chaos
   write from racing an unready child."
  [^String sock deadline-ms]
  (let [start (System/currentTimeMillis)
        f     (io/file sock)]
    (loop []
      (cond
        (.exists f) sock
        (> (- (System/currentTimeMillis) start) deadline-ms) nil
        :else (do (Thread/sleep 25) (recur))))))

(defn- send-chaos!
  "Open the REPL socket, write `chaos <rate>\\n`, read the
   acknowledgement, return it. Nil return means the exchange did not
   complete; caller treats that as a chaos setup failure."
  [sock rate]
  (let [addr (UnixDomainSocketAddress/of ^String sock)
        ch   (SocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.connect ch addr)
      (.write ch (ByteBuffer/wrap
                  (.getBytes (str "chaos " rate "\n")
                             StandardCharsets/UTF_8)))
      (.shutdownOutput ch)
      (let [buf (ByteBuffer/allocate 256)]
        (loop [acc (StringBuilder.)]
          (let [n (.read ch buf)]
            (if (neg? n)
              (.toString acc)
              (do (.flip buf)
                  (let [bs (byte-array (.remaining buf))]
                    (.get buf bs)
                    (.append acc (String. bs StandardCharsets/UTF_8))
                    (.clear buf)
                    (recur acc)))))))
      (catch Exception e (.getMessage e))
      (finally (.close ch)))))

(defn- run-chaos!
  "Run `bin` under CXC_REPL=1, wait for the socket, post a chaos rate,
   validate the acknowledgement, let the child run, then reap. Owns
   the child's lifecycle: feeds stdin, drains stdout/stderr through
   dedicated pumps so backpressure does not block the run, and kills
   on timeout. Returns `{:exit :out :err :chaos-err :trace-ok?}`.
   `:chaos-err` is nil on success, else a one-line reason. Caller
   uses it to report `chaos setup failed` as an explicit failure row
   instead of letting absence expectations pass against no evidence."
  [bin args env-strs stdin-str fixture chaos timeout-ms trace-path]
  (let [pb      (ProcessBuilder. ^java.util.List (into [bin] args))
        _       (-> (.environment pb) (.clear))
        _       (doseq [s env-strs]
                  (let [eq (.indexOf ^String s "=")]
                    (when-not (neg? eq)
                      (.put (.environment pb)
                            (subs s 0 eq) (subs s (inc eq))))))
        _       (.put (.environment pb) "CXC_REPL" "1")
        p       (.start pb)
        pid     (.pid p)
        sock    (str "/tmp/cxc-repl-" pid ".sock")
        pre-ms  (or (:chaos_pre_ms fixture) 500)
        run-ms  (or (:chaos_run_ms fixture) 1500)
        out-get (pump-stream! (.getInputStream p))
        err-get (pump-stream! (.getErrorStream p))]
    (try
      (when stdin-str
        (with-open [os (.getOutputStream p)]
          (.write os (.getBytes ^String stdin-str StandardCharsets/UTF_8))))
      (let [ready-sock (await-socket! sock pre-ms)
            ack (when ready-sock (send-chaos! ready-sock chaos))
            applied? (and (string? ack)
                          (str/includes? ack
                                         (str "chaos:drop_rate=" chaos)))
            chaos-err (cond
                        (nil? ready-sock)
                        (str "REPL socket never appeared at " sock
                             " within " pre-ms "ms")
                        (not applied?)
                        (str "chaos ack did not confirm rate "
                             chaos ": " (pr-str ack)))]
        (Thread/sleep ^long run-ms)
        (when (.isAlive p) (.destroy p))
        (.waitFor p timeout-ms TimeUnit/MILLISECONDS)
        (when (.isAlive p) (.destroyForcibly p))
        {:exit      (.exitValue p)
         :out       (out-get)
         :err       (err-get)
         :chaos-err chaos-err
         :trace-ok? (.exists (io/file trace-path))})
      (catch Exception e
        (when (.isAlive p) (.destroyForcibly p))
        {:exit -1 :out (out-get) :err (err-get)
         :chaos-err (str "chaos run threw: " (.getMessage e))
         :trace-ok? false}))))

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
        {exit :exit out :out err :err chaos-err :chaos-err trace-ok? :trace-ok?
         timed-out? :timed-out?}
        (if chaos
          (-> (run-chaos! bin args env-strs stdin-str fixture chaos timeout trace-path)
              (assoc :timed-out? false))
          ;; hickey-cycle-286 (2026-11-21) — R3 #10 slice. Pre-cycle the
          ;; non-chaos path used `shell/sh`, which blocks without a
          ;; deadline; a hung binary froze the harness forever even
          ;; though the fixture carried `:timeout_ms`. Replaced with
          ;; the same ProcessBuilder shape chaos already uses, plus an
          ;; explicit waitFor(timeout) + destroy/destroyForcibly + a
          ;; dedicated failure row when the deadline lapses.
          (let [pb (doto (ProcessBuilder. ^java.util.List (into [bin] args))
                     (.redirectErrorStream false))
                _  (let [e (.environment pb)]
                     (.clear e)
                     (doseq [es env-strs
                             :let [idx (.indexOf ^String es "=")]
                             :when (pos? idx)]
                       (.put e
                             (subs es 0 idx)
                             (subs es (inc idx)))))
                p  (.start pb)
                out-get (pump-stream! (.getInputStream p))
                err-get (pump-stream! (.getErrorStream p))]
            (when stdin-str
              (with-open [os (.getOutputStream p)]
                (.write os (.getBytes ^String stdin-str StandardCharsets/UTF_8))))
            (let [finished? (.waitFor p (long timeout) TimeUnit/MILLISECONDS)]
              (when-not finished?
                (.destroy p)
                (when-not (.waitFor p 2000 TimeUnit/MILLISECONDS)
                  (.destroyForcibly p)
                  (.waitFor p 2000 TimeUnit/MILLISECONDS)))
              {:exit       (if finished? (.exitValue p) nil)
               :out        (out-get)
               :err        (err-get)
               :chaos-err  nil
               :trace-ok?  (.exists (io/file trace-path))
               :timed-out? (not finished?)})))
        {events :events trace-errors :errors}
        (if trace-ok? (load-arm-events trace-path) {:events [] :errors []})
        base-results (check-expectations (:expect fixture) events exit out)
        ;; hickey-cycle-257 (2026-11-13) — chaos setup failure and
        ;; trace acquisition failure each surface as a dedicated
        ;; failure row, not silently as `[]` events (which would let
        ;; absence expectations pass against no evidence).
        ;; hickey-cycle-285 (2026-11-21) — corrupted lines in an
        ;; otherwise-existing trace file also surface as their own
        ;; failure row. Pre-cycle this was indistinguishable from an
        ;; empty trace, so `arms_absent` passed against evidence the
        ;; harness could not read.
        results (cond-> base-results
                  chaos-err
                  (conj {:pass? false
                         :msg   (str "chaos setup failed: " chaos-err)})
                  (and (not chaos-err) (not trace-ok?))
                  (conj {:pass? false
                         :msg   (str "trace file not produced at " trace-path)})
                  (seq trace-errors)
                  (conj {:pass? false
                         :msg   (str "trace decode errors (" (count trace-errors)
                                     "): first at line "
                                     (:line (first trace-errors)) " — "
                                     (:reason (first trace-errors)))})
                  ;; hickey-cycle-286 — timeout_ms is now authoritative on
                  ;; the non-chaos path; a lapsed deadline produces a
                  ;; dedicated failure row with the specific bound hit.
                  timed-out?
                  (conj {:pass? false
                         :msg   (str "process exceeded timeout_ms (" timeout
                                     "ms) — killed")}))
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
