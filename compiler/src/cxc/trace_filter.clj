(ns cxc.trace-filter
  "Filter a JSONL trace by event-kind / atom class or ptr / selector /
   seq range. Reads one line at a time and writes to stdout. Zero deps
   beyond clojure.data.json (already on the compiler classpath)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- parse-line [s]
  (try
    ((requiring-resolve 'clojure.data.json/read-str) s :key-fn keyword)
    (catch Throwable _ nil)))

(defn- parse-flag [args prefix]
  (some #(when (str/starts-with? % prefix) (subs % (count prefix))) args))

(defn- parse-set-flag [args prefix]
  (when-let [v (parse-flag args prefix)]
    (set (str/split v #","))))

(defn- parse-seq-range [args]
  (when-let [v (parse-flag args "--seq=")]
    (let [[lo hi] (str/split v #"-" 2)]
      [(parse-long lo)
       (if (str/blank? hi) Long/MAX_VALUE (parse-long hi))])))

(defn- match? [preds e]
  (every? #(% e) preds))

(defn- build-preds [args]
  (let [kinds     (parse-set-flag args "--kinds=")
        atoms     (parse-set-flag args "--atom=")
        selectors (parse-set-flag args "--selector=")
        [lo hi]   (parse-seq-range args)]
    (cond-> []
      kinds     (conj #(contains? kinds (:kind %)))
      atoms     (conj #(or (contains? atoms (:target_cls %))
                           (contains? atoms (str (:target %)))))
      selectors (conj #(contains? selectors (:msg %)))
      lo        (conj #(<= lo (:seq %) hi)))))

(defn- print-help []
  (println "Usage: cxc trace-filter <file.jsonl>")
  (println "                        [--kinds=send,recv,arm,field_write,...]")
  (println "                        [--atom=<Class-or-ptr>[,...]]")
  (println "                        [--selector=<msg>[,...]]")
  (println "                        [--seq=<lo>-<hi>]")
  (println "")
  (println "Reads JSONL, writes matching events to stdout.")
  (println "Multiple flags AND together; commas within a flag OR."))

(defn run [args]
  (cond
    (or (empty? args) (contains? #{"-h" "--help"} (first args)))
    (print-help)

    :else
    (let [path  (first args)
          rest  (vec (drop 1 args))
          preds (build-preds rest)]
      (with-open [r (io/reader path)]
        (doseq [line (line-seq r)
                :when (not (str/blank? line))
                :let [e (parse-line line)]
                :when (and e (match? preds e))]
          (println line))))))
