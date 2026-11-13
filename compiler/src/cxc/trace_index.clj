(ns cxc.trace-index
  "cxc trace-index — build an in-memory, map-indexed view of a trace
   JSONL and expose basic queries. Stepping stone toward the Datalevin
   migration in docs/design/trace-store.md: the query surface
   stabilizes here (atom-of, phase-at, arms-of, events-between) with a
   simple hash-index backing. When the Datalevin backend lands, the
   same subcommand swaps its implementation without changing user-
   facing output.

   Query operators (all scan O(n) today, Datalevin reduces to O(log
   n) for indexed components):
     atom-of <ptr>            — list distinct events for a target hex id
     arms-of <ptr>            — list `kind=arm` events for a target
     between <lo-seq> <hi-seq> — events with lo <= seq <= hi
     count-by-kind            — tally events grouped by `kind`
     tally-arms               — tally per (target_cls, msg, line) arm
                                firings; ordered by count desc"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- parse-extra [extra]
  (when (and extra (seq extra))
    (into {}
          (keep (fn [part]
                  (let [[k v] (str/split part #"=")]
                    (when (and k v [k v])
                      [(keyword k) v]))))
          (str/split extra #","))))

(defn- load-events [path]
  (with-open [r (io/reader path)]
    (into []
          (keep (fn [line]
                  (try (let [ev (json/read-str line :key-fn keyword)]
                         (cond-> ev
                           (:extra ev) (assoc :extra-parsed
                                              (parse-extra (:extra ev)))))
                       (catch Exception _ nil))))
          (line-seq r))))

(defn- cmd-atom-of [events ptr]
  (doseq [e (filter #(or (= ptr (str "0x" (Long/toHexString (:target %))))
                         (= (str (:target %)) ptr))
                    events)]
    (println (format "%6d  %-10s  %-20s  %s"
                     (:seq e) (:kind e) (:target_cls e) (:msg e)))))

(defn- cmd-arms-of [events ptr]
  (doseq [e (filter #(and (= "arm" (:kind %))
                          (or (= ptr (str "0x" (Long/toHexString (:target %))))
                              (= (str (:target %)) ptr)))
                    events)]
    (println (format "%6d  %-20s  %s  (%s)"
                     (:seq e) (:target_cls e) (:msg e)
                     (pr-str (:extra-parsed e))))))

(defn- cmd-between [events lo hi]
  (doseq [e events
          :when (and (>= (:seq e) lo) (<= (:seq e) hi))]
    (println (format "%6d  %-10s  %-20s  %s"
                     (:seq e) (:kind e) (:target_cls e) (:msg e)))))

(defn- cmd-count-by-kind [events]
  (let [tally (frequencies (map :kind events))]
    (doseq [[k n] (reverse (sort-by second tally))]
      (println (format "%8d  %s" n k)))))

(defn- cmd-tally-arms [events]
  (let [arms (filter #(= "arm" (:kind %)) events)
        key-of (fn [e] [(:target_cls e) (:msg e)
                        (get-in e [:extra-parsed :line])])
        tally  (frequencies (map key-of arms))]
    (doseq [[[cls msg line] n] (reverse (sort-by second tally))]
      (println (format "%8d  %-30s  %-20s  line %s" n cls msg line)))))

(defn- usage []
  (println "Usage: cxc trace-index <trace.jsonl> <op> [args...]")
  (println)
  (println "  atom-of <0xHEX>              events for target id")
  (println "  arms-of <0xHEX>              arm events for target id")
  (println "  between <lo-seq> <hi-seq>    events in seq range")
  (println "  count-by-kind                tally events grouped by kind")
  (println "  tally-arms                   tally arms by (cls,msg,line)"))

(defn run [args]
  (cond
    (or (empty? args) (contains? #{"-h" "--help"} (first args)))
    (do (usage) (when (empty? args) (System/exit 2)))

    (< (count args) 2)
    (do (usage) (System/exit 2))

    :else
    (let [[trace op & rest-args] args
          events (load-events trace)]
      (case op
        "atom-of"       (cmd-atom-of events (first rest-args))
        "arms-of"       (cmd-arms-of events (first rest-args))
        "between"       (cmd-between events
                                     (Long/parseLong (first rest-args))
                                     (Long/parseLong (second rest-args)))
        "count-by-kind" (cmd-count-by-kind events)
        "tally-arms"    (cmd-tally-arms events)
        (do (println (str "unknown op: " op))
            (usage)
            (System/exit 2))))))
