(ns cxc.trace-diff
  "Compare two JSONL trace dumps. Canonicalizes non-deterministic
   pointers via atom_init construction order so `LaneSide 4386570304`
   becomes `LaneSide#0` and matches across runs.

   Reports the first divergent event by seq, printing both sides."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- parse-line [s]
  (try
    ((requiring-resolve 'clojure.data.json/read-str) s :key-fn keyword)
    (catch Throwable _ nil)))

(defn- load-events [path]
  (with-open [r (io/reader path)]
    (->> (line-seq r)
         (map str/trim)
         (remove str/blank?)
         (keep parse-line)
         (sort-by :seq)
         vec)))

(defn- build-ptr-map
  "Walk atom_init events in seq order; assign each ptr a stable id
   `<Class>#<index>`. Pointers with no atom_init keep as `?ptr`."
  [events]
  (loop [i 0 counts {} ptrs {}]
    (if (>= i (count events))
      ptrs
      (let [e (nth events i)]
        (if (= "atom_init" (:kind e))
          (let [cls (:target_cls e)
                n   (get counts cls 0)]
            (recur (inc i)
                   (assoc counts cls (inc n))
                   (assoc ptrs (:target e) (str cls "#" n))))
          (recur (inc i) counts ptrs))))))

(defn- canonicalize-ptr [ptrs p]
  (when p (get ptrs p (str "?" p))))

(defn- canonicalize-extra
  "Replace numeric ptr-like tokens (0x...) in extra with their stable
   ids. Unknown ptrs (heap allocations that aren't atoms — queue
   blocks, NSStrings) collapse to `?ptr` so parallel runs match; we
   can't check value-identity for non-atom ptrs across runs anyway."
  [ptrs extra]
  (if-not extra
    extra
    (str/replace extra #"0x[0-9a-fA-F]+"
                 (fn [m]
                   (let [n (try (Long/parseUnsignedLong (subs m 2) 16)
                                (catch Throwable _ nil))]
                     (or (when n (get ptrs n)) "?ptr"))))))

(defn- canonicalize [ptrs e]
  (-> e
      (assoc :target (canonicalize-ptr ptrs (:target e)))
      (assoc :sender (canonicalize-ptr ptrs (:sender e)))
      (update :extra #(canonicalize-extra ptrs %))
      (dissoc :t)))

(defn- fmt [e]
  (str "seq=" (:seq e)
       " kind=" (:kind e)
       " target=" (:target e)
       " msg=" (:msg e)
       (when (:extra e) (str " extra=" (:extra e)))))

(defn- print-help []
  (println "Usage: cxc trace-diff <a.jsonl> <b.jsonl>")
  (println "")
  (println "Canonicalizes ptrs via atom_init construction order,")
  (println "then reports the first divergent event (by seq)."))

(defn run [args]
  (cond
    (or (< (count args) 2)
        (contains? #{"-h" "--help"} (first args)))
    (print-help)

    :else
    (let [[a-path b-path] args
          a-evts (load-events a-path)
          b-evts (load-events b-path)
          a-ptrs (build-ptr-map a-evts)
          b-ptrs (build-ptr-map b-evts)
          a-can  (mapv #(canonicalize a-ptrs %) a-evts)
          b-can  (mapv #(canonicalize b-ptrs %) b-evts)
          n      (min (count a-can) (count b-can))]
      (loop [i 0]
        (cond
          (>= i n)
          (if (= (count a-can) (count b-can))
            (println (str "no divergence over " n " events"))
            (do (println (str "traces match through seq " n
                              "; length differs (" (count a-can)
                              " vs " (count b-can) ")"))
                (println (str "next in longer: "
                              (fmt (nth (if (> (count a-can) n) a-can b-can)
                                        n))))))

          (= (nth a-can i) (nth b-can i))
          (recur (inc i))

          :else
          (do (println (str "diverges at index " i
                            " (a-seq=" (:seq (nth a-can i))
                            " b-seq=" (:seq (nth b-can i)) "):"))
              (println (str "  a: " (fmt (nth a-can i))))
              (println (str "  b: " (fmt (nth b-can i))))))))))
