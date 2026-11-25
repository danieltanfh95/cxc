(ns cxc.scenario-test
  "Private-fn coverage for cxc.scenario's trace ingestion. Primary
   motivation: hickey-cycle-285 (2026-11-21, R3 #3) — pre-cycle the
   ingestion swallowed every decode failure silently, so an
   `arms_absent` expectation over a corrupted trace passed against
   evidence the harness could not read. The new contract:
   `load-arm-events` returns `{:events <vec by :seq>, :errors <vec>}`
   and the caller surfaces :errors as a failure row.

   Tests here hit `load-arm-events` directly through its var; a full
   end-to-end `run-scenario` test would require a live compiled
   binary and is out of scope."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io]
            [clojure.string :as str])
  (:import [java.io File]))

(require 'cxc.scenario)

(defn- with-temp-trace
  "Write `lines` as a JSONL trace, call f with the path, delete on exit."
  [lines f]
  (let [^File tmp (File/createTempFile "cxc-scenario-test" ".jsonl")]
    (try
      (spit tmp (str/join "\n" lines))
      (f (.getAbsolutePath tmp))
      (finally (.delete tmp)))))

(deftest load-arm-events-separates-events-from-errors
  (testing "well-formed arm events return under :events sorted by :seq"
    (with-temp-trace
      [(str "{\"kind\":\"arm\",\"target_cls\":\"A\",\"msg\":\"m\",\"seq\":2}")
       (str "{\"kind\":\"arm\",\"target_cls\":\"A\",\"msg\":\"m\",\"seq\":1}")]
      (fn [p]
        (let [{:keys [events errors]} (#'cxc.scenario/load-arm-events p)]
          (is (empty? errors) "no decode errors on clean JSONL")
          (is (= [1 2] (mapv :seq events))
              "events sorted by :seq at ingestion boundary, not physical order")))))

  (testing "malformed JSON lines surface as :errors, not silent drops"
    ;; Pre-cycle the whole file read empty and `arms_absent` passed against
    ;; it. Post-cycle the harness receives errors it can surface.
    (with-temp-trace
      [(str "{\"kind\":\"arm\",\"target_cls\":\"A\",\"msg\":\"m\",\"seq\":1}")
       "this is not JSON"
       "{incomplete"]
      (fn [p]
        (let [{:keys [events errors]} (#'cxc.scenario/load-arm-events p)]
          (is (= 1 (count events))
              "the one valid arm event survives decoding")
          (is (= 2 (count errors))
              "both malformed lines surface as errors")
          (is (every? :line errors)
              "each error carries the line number the harness failed on")
          (is (every? :reason errors)
              "each error carries a reason")))))

  (testing "non-map top-level JSON values are errors, not silently accepted"
    ;; R3 #3: trace_io also accepts arbitrary JSON values including
    ;; numbers. Scenario's contract rejects anything that isn't a map.
    (with-temp-trace
      ["42"
       "\"a string\""
       "[1, 2, 3]"]
      (fn [p]
        (let [{:keys [events errors]} (#'cxc.scenario/load-arm-events p)]
          (is (empty? events)
              "no event comes from non-map top-level values")
          (is (= 3 (count errors))
              "each non-map line reports as an error")))))

  (testing "hickey-cycle-286 — timeout_ms kills a hung non-chaos binary"
    ;; Pre-cycle the non-chaos path called `shell/sh`, which blocks
    ;; without a deadline; a hung child froze the harness. The fix
    ;; uses ProcessBuilder + waitFor(timeout) + destroy → forcibly →
    ;; wait, and surfaces a dedicated failure row. `/bin/sleep` is
    ;; available on macOS and Linux and is the simplest hung-child
    ;; probe without depending on a compiled cxc binary.
    (when (.exists (clojure.java.io/file "/bin/sleep"))
      (let [fixture {:name       "sleep-should-timeout"
                     :binary     "/bin/sleep"
                     :args       ["30"]
                     :timeout_ms 500
                     :expect     {}}
            result  (#'cxc.scenario/run-scenario fixture)]
        (is (false? (:pass? result))
            "overall result fails when the deadline lapses")
        (is (some #(and (not (:pass? %))
                        (re-find #"exceeded timeout_ms" (:msg %)))
                  (:results result))
            "a dedicated 'exceeded timeout_ms' failure row is present"))))

  (testing "blank lines drop silently (not an error)"
    ;; Blank-line tolerance matches the JSONL convention; the trace
    ;; writer flushes with trailing newlines that produce blank splits
    ;; at EOF. A blank is not evidence, nor is it corruption.
    (with-temp-trace
      [""
       (str "{\"kind\":\"arm\",\"target_cls\":\"A\",\"msg\":\"m\",\"seq\":1}")
       "   "]
      (fn [p]
        (let [{:keys [events errors]} (#'cxc.scenario/load-arm-events p)]
          (is (= 1 (count events)))
          (is (empty? errors) "blank lines are not corruption"))))))
