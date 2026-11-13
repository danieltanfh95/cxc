(ns cxc.coverage
  "cxc coverage — compute arm-level coverage from a trace corpus against
   a binary's shape sidecar.

   The shape sidecar (`<bin>.shape.json`) carries the full static arm
   list per atom: {:class :msg :from :to :line :has_guard}. Each `arm`
   event in a trace carries (target_cls, msg, extra=\"arm=N,line=L\").
   Coverage is the set-diff: static arms whose (class, msg, line) tuple
   never appears in a trace event are uncovered.

   Reports per-class hit/miss counts and lists uncovered arms by phase
   transition. Exit 0 always; the report is advisory, not pass/fail."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- load-shape [bin-path]
  (let [sidecar (io/file (str bin-path ".shape.json"))]
    (when-not (.exists sidecar)
      (throw (ex-info (str "no shape sidecar at " (.getPath sidecar)
                           " — compile the binary with the current toolchain first")
                      {:bin bin-path :exit 2})))
    (json/read-str (slurp sidecar) :key-fn keyword)))

(defn- static-arms
  "Flatten shape sidecar into per-arm maps keyed by (class, msg, line)."
  [shape]
  (for [atom (:atoms shape)
        [kind txns] [[:fwd (:transitions atom)]
                     [:self (:self_transitions atom)]]
        txn txns]
    {:class  (:class atom)
     :atom   (:name atom)
     :msg    (:msg txn)
     :from   (:from txn)
     :to     (:to txn)
     :line   (:line txn)
     :guard? (boolean (:has_guard txn))
     :kind   kind}))

(defn- parse-extra
  "Parse `arm=N,line=L,...` into a map of keyword → long."
  [extra]
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

(defn- fired-keys
  "Return set of {:class :msg :line} tuples from `kind=arm` events."
  [trace-paths]
  (reduce
    (fn [acc path]
      (with-open [r (io/reader path)]
        (reduce
          (fn [a line]
            (let [ev (try (json/read-str line :key-fn keyword)
                          (catch Exception _ nil))]
              (if (and ev (= "arm" (:kind ev)))
                (let [ex (parse-extra (:extra ev))]
                  (if (:line ex)
                    (conj a {:class (:target_cls ev)
                             :msg   (:msg ev)
                             :line  (:line ex)})
                    a))
                a)))
          acc
          (line-seq r))))
    #{}
    trace-paths))

(defn- key-of [a] (select-keys a [:class :msg :line]))

(defn- report! [shape trace-paths]
  (let [static (static-arms shape)
        fired  (fired-keys trace-paths)
        covered? #(contains? fired (key-of %))
        by-class (sort-by first (group-by :class static))]
    (doseq [[cls arms] by-class]
      (let [[hit miss] [(filter covered? arms) (remove covered? arms)]
            atom-name (-> arms first :atom)]
        (println (format "%-40s  %3d/%-3d arms covered"
                         (str atom-name " (" cls ")")
                         (count hit) (count arms)))
        (doseq [a (sort-by (juxt :msg :line) miss)]
          (let [src (if (= :self (:kind a)) "*" (:from a))]
            (println (format "  MISS  %s <: %s -> %s  (line %s%s)"
                             src (:msg a) (:to a) (:line a)
                             (if (:guard? a) ", guarded" "")))))))
    (let [total (count static)
          hit   (count (filter covered? static))
          pct   (if (zero? total) 0.0 (* 100.0 (/ (double hit) total)))]
      (println)
      (println (format "Total: %d/%d arms (%.1f%%) across %d trace file(s)"
                       hit total pct (count trace-paths))))))

(defn- usage []
  (println "Usage: cxc coverage <binary> <trace.jsonl> [<trace.jsonl>...]")
  (println)
  (println "  <binary>       path to the compiled cxc binary (reads <binary>.shape.json)")
  (println "  <trace.jsonl>  one or more trace files (run with CXC_TRACE_PATH)"))

(defn run [args]
  (cond
    (or (empty? args) (contains? #{"-h" "--help"} (first args)))
    (do (usage) (when (empty? args) (System/exit 2)))

    (< (count args) 2)
    (do (usage) (System/exit 2))

    :else
    (let [[bin & traces] args
          shape (load-shape bin)]
      (doseq [t traces]
        (when-not (.exists (io/file t))
          (throw (ex-info (str "trace file missing: " t) {:exit 2}))))
      (report! shape traces))))
