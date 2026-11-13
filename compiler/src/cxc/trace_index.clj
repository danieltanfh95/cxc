(ns cxc.trace-index
  "cxc trace-index — scan queries over a trace JSONL.

   Query operators:
     atom-of <ptr>            — events whose target matches `ptr`
     arms-of <ptr>            — arm events only, for `ptr`
     between <lo-seq> <hi-seq> — events with lo <= seq <= hi
     count-by-kind            — tally events grouped by `kind`
     tally-arms               — tally arms by (target_cls, msg, line)

   `ptr` accepts `0x`-prefixed hex or a decimal id; both normalize to a
   64-bit numeric id compared against each event's `:target`.

   Malformed JSON, missing `:seq`/`:kind`, or a non-numeric `:target`
   raises at load time with path + line + cause. The scanner refuses
   to silently drop records — a dropped event is a missing arm firing
   somewhere, so the audit result stops being load-bearing."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- parse-arm-extra
  "Decode an arm event's `:extra` string into a map of arm metadata.
   Keys without `=` collapse to `[kw true]`. Called only on `kind=arm`
   events; other kinds carry free-form `:extra` payloads that would
   produce misleading keys here."
  [extra]
  (when (and (string? extra) (seq extra))
    (into {}
          (map (fn [part]
                 (let [eq (.indexOf ^String part "=")]
                   (if (neg? eq)
                     [(keyword part) true]
                     [(keyword (subs part 0 eq)) (subs part (inc eq))]))))
          (str/split extra #","))))

(defn- parse-event-line [path line-num line]
  (let [ev (try
             (json/read-str line :key-fn keyword)
             (catch Exception e
               (throw (ex-info (str "trace-index: malformed JSON at "
                                    path ":" line-num " (" (.getMessage e) ")")
                               {:path path :line line-num}
                               e))))]
    (when-not (and (integer? (:seq ev))
                   (string? (:kind ev))
                   (integer? (:target ev)))
      (throw (ex-info (str "trace-index: record at " path ":" line-num
                           " missing or mistyped :seq/:kind/:target")
                      {:path path :line line-num :event ev})))
    (cond-> ev
      (= "arm" (:kind ev)) (assoc :extra-parsed (parse-arm-extra (:extra ev))))))

(defn- load-events [path]
  (with-open [r (io/reader path)]
    (into []
          (map-indexed (fn [i line]
                         (parse-event-line path (inc i) line)))
          (line-seq r))))

(defn- parse-ptr
  "Accept `0xHEX` or a decimal integer. Return a Long or nil."
  [s]
  (when (string? s)
    (try
      (cond
        (str/starts-with? s "0x") (Long/parseUnsignedLong (subs s 2) 16)
        :else                      (Long/parseLong s))
      (catch NumberFormatException _ nil))))

(defn- usage-error! [msg]
  (binding [*out* *err*]
    (println (str "cxc trace-index: " msg))
    (println "Usage: cxc trace-index <trace.jsonl> <op> [args...]")
    (println)
    (println "  atom-of <0xHEX|dec>          events for target id")
    (println "  arms-of <0xHEX|dec>          arm events for target id")
    (println "  between <lo-seq> <hi-seq>    events in seq range")
    (println "  count-by-kind                tally events grouped by kind")
    (println "  tally-arms                   tally arms by (cls,msg,line)"))
  (System/exit 2))

(defn- cmd-atom-of [events ptr]
  (doseq [e events
          :when (= ptr (:target e))]
    (println (format "%6d  %-10s  %-20s  %s"
                     (:seq e) (:kind e) (:target_cls e) (:msg e)))))

(defn- cmd-arms-of [events ptr]
  (doseq [e events
          :when (and (= "arm" (:kind e))
                     (= ptr (:target e)))]
    (println (format "%6d  %-20s  %s  (%s)"
                     (:seq e) (:target_cls e) (:msg e)
                     (pr-str (:extra-parsed e))))))

(defn- cmd-between [events lo hi]
  (doseq [e events
          :when (and (>= ^long (:seq e) ^long lo)
                     (<= ^long (:seq e) ^long hi))]
    (println (format "%6d  %-10s  %-20s  %s"
                     (:seq e) (:kind e) (:target_cls e) (:msg e)))))

(defn- cmd-count-by-kind [events]
  (let [tally (frequencies (map :kind events))]
    (doseq [[k n] (sort-by (juxt (comp - val) key) tally)]
      (println (format "%8d  %s" n k)))))

(defn- cmd-tally-arms [events]
  (let [arms  (filter #(= "arm" (:kind %)) events)
        key-of (fn [e] [(:target_cls e) (:msg e)
                        (get-in e [:extra-parsed :line])])
        tally (frequencies (map key-of arms))]
    (doseq [[[cls msg line] n]
            (sort-by (juxt (comp - val) key) tally)]
      (println (format "%8d  %-30s  %-20s  line %s" n cls msg line)))))

(defn- require-ptr [op args]
  (when (not= 1 (count args))
    (usage-error! (str op " needs exactly one <0xHEX|dec> argument")))
  (or (parse-ptr (first args))
      (usage-error! (str op " argument `" (first args)
                         "` is not a 0xHEX or decimal id"))))

(defn- require-seq-range [args]
  (when (not= 2 (count args))
    (usage-error! "between needs exactly two integer arguments"))
  (let [[a b] args]
    (try [(Long/parseLong a) (Long/parseLong b)]
         (catch NumberFormatException _
           (usage-error! (str "between: `" a "` or `" b
                              "` is not an integer sequence number"))))))

(defn- require-no-args [op args]
  (when (seq args)
    (usage-error! (str op " takes no arguments, got " (count args)))))

(defn run [args]
  (cond
    (or (empty? args) (contains? #{"-h" "--help"} (first args)))
    (do (usage-error! "missing <trace.jsonl> <op>")
        nil)

    (< (count args) 2)
    (usage-error! "missing <op>")

    :else
    (let [[trace op & rest-args] args
          events (load-events trace)]
      (case op
        "atom-of"       (cmd-atom-of events (require-ptr "atom-of" rest-args))
        "arms-of"       (cmd-arms-of events (require-ptr "arms-of" rest-args))
        "between"       (let [[lo hi] (require-seq-range rest-args)]
                          (cmd-between events lo hi))
        "count-by-kind" (do (require-no-args "count-by-kind" rest-args)
                            (cmd-count-by-kind events))
        "tally-arms"    (do (require-no-args "tally-arms" rest-args)
                            (cmd-tally-arms events))
        (usage-error! (str "unknown op: " op))))))
