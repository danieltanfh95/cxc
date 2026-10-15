(ns cxc.trace-io
  "Shared JSONL input helpers for the trace CLI commands. Three sibling
   namespaces (`trace-diff`, `trace-summarize`, `trace-filter`) grew
   identical tolerant JSON parsers, and the first two also shared a
   `load-events` whole-file reader. Centralised here so a schema or
   wire format change lands once."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse-line
  "Decode one JSONL line to a map keyed by keyword. Returns nil on any
   decode failure so callers can `keep` across a stream."
  [s]
  (try
    ((requiring-resolve 'clojure.data.json/read-str) s :key-fn keyword)
    (catch Throwable _ nil)))

(defn load-events
  "Read a trace JSONL file into a seq-sorted vector of event maps.
   Blank lines and undecodable lines are dropped."
  [path]
  (with-open [r (io/reader path)]
    (->> (line-seq r)
         (map str/trim)
         (remove str/blank?)
         (keep parse-line)
         (sort-by :seq)
         vec)))
