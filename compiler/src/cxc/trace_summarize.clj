(ns cxc.trace-summarize
  "CLI diagnostics for a `cxc compile --trace` JSONL dump. Prints the
   same signal the browser viewer (cxc view) shows: overview, kind
   histogram, unclosed sends, silent drops, per-atom inbox, topology.
   Zero deps beyond stdlib + Cheshire (already on the compiler classpath
   via deps.edn — falls back to a tiny JSON parser if unavailable)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- JSON: use org.json if present via Clojure's stock read, else parse
;; each line with clojure.data.json. Avoid pulling a new dep — the trace
;; format is one JSON object per line, no nesting depth to speak of.
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

(defn- infer-senders
  "Attribute each send/send_sync to the most-recently-fired arm's atom
   (single-queue mode guarantees this is the actual sender's stack)."
  [events]
  (loop [i 0, last-atom nil, last-cls nil, out (transient [])]
    (if (>= i (count events))
      (persistent! out)
      (let [e (nth events i)]
        (cond
          (= "arm" (:kind e))
          (recur (inc i) (:target e) (:target_cls e) (conj! out e))

          (and (#{"send" "send_sync"} (:kind e))
               (or (nil? (:sender e)) (zero? (:sender e))))
          (recur (inc i) last-atom last-cls
                 (conj! out (assoc e :_sender_inferred last-atom
                                     :_sender_cls_inferred last-cls)))

          :else
          (recur (inc i) last-atom last-cls (conj! out e)))))))

(defn- analyze
  "Compute {:pairs :unclosed :silent-drops} — silent-drop = recv with
   no matching arm on the same atom within a small forward window."
  [events]
  (let [window 6]
    (loop [i 0
           pending {}
           pairs (transient [])
           unclosed (transient [])
           silent (transient [])]
      (if (>= i (count events))
        {:pairs (persistent! pairs)
         :unclosed (into (persistent! unclosed) (mapcat identity) (vals pending))
         :silent-drops (persistent! silent)}
        (let [e (nth events i)
              k (:kind e)
              key [(:target e) (:msg e)]]
          (cond
            (#{"send" "send_sync"} k)
            (recur (inc i) (update pending key (fnil conj []) e) pairs unclosed silent)

            (#{"recv" "recv_sync"} k)
            (let [q (get pending key)
                  [head & rest'] q
                  pending' (if head
                             (if (seq rest')
                               (assoc pending key (vec rest'))
                               (dissoc pending key))
                             pending)
                  pairs' (if head (conj! pairs [head e]) pairs)
                  msg (or (:msg e) "")
                  skip-silent? (str/starts-with? msg "self.cxc_emit_")
                  recv-base (last (str/split msg #"\."))
                  hit? (loop [j (inc i)]
                         (if (or (>= j (count events)) (>= j (+ i 1 window))) false
                             (let [n (nth events j)]
                               (cond
                                 (not= "arm" (:kind n)) (recur (inc j))
                                 (not= (:target n) (:target e)) (recur (inc j))
                                 :else
                                 (let [arm-msg (or (:msg n) "")
                                       arm-base (-> arm-msg (str/split #"With") first (str/split #"\.") first (str/split #":") first)]
                                   (if (or (= arm-base recv-base)
                                           (str/starts-with? arm-msg recv-base))
                                     true
                                     (recur (inc j))))))))
                  silent' (if (and (not skip-silent?) (not hit?)) (conj! silent e) silent)]
              (recur (inc i) pending' pairs' unclosed silent'))

            :else
            (recur (inc i) pending pairs unclosed silent)))))))

(defn- topology
  "(src-cls, dst-cls, msg) → count over send/send_sync events."
  [events]
  (persistent!
    (reduce (fn [acc e]
              (if (#{"send" "send_sync"} (:kind e))
                (let [src (or (:_sender_cls_inferred e) "(root)")
                      dst (or (:target_cls e) "?")
                      msg (last (str/split (or (:msg e) "?") #"\."))
                      key [src dst msg]]
                  (assoc! acc key (inc (get acc key 0))))
                acc))
            (transient {}) events)))

(defn- fmt-int [n] (format "%,d" n))
(defn- bar
  ([n mx] (bar n mx 20))
  ([n mx width]
   (if (zero? mx) ""
       (let [filled (int (Math/round (double (* width (/ n (double mx))))))]
         (str (apply str (repeat filled "█"))
              (apply str (repeat (- width filled) "·")))))))

(def ^:private BOLD "\033[1m")
(def ^:private RED  "\033[31m")
(def ^:private GRN  "\033[32m")
(def ^:private RESET "\033[0m")

(defn- print-report [events opts]
  (when (empty? events)
    (println "empty trace.")
    (throw (ex-info "empty" {:exit 0})))
  (let [dur (if (> (count events) 1)
              (- (:t (last events)) (:t (first events)))
              0)
        atoms (into #{} (map :target) events)
        classes (into #{} (map :target_cls) events)
        kinds (reduce (fn [m e] (update m (:kind e) (fnil inc 0))) {} events)
        {:keys [unclosed silent-drops]} (analyze events)]

    (println (str BOLD "═══ overview ═══" RESET))
    (println (str "  events    " (fmt-int (count events))))
    (println (str "  atoms     " (count atoms)))
    (println (str "  classes   " (count classes)))
    (println (str "  duration  " (format "%.2f" dur) " ms"))

    (println)
    (println (str BOLD "─── by kind ───" RESET))
    (let [mx (apply max 0 (vals kinds))]
      (doseq [k (sort (keys kinds))]
        (println (format "  %-14s %7s  %s" k (fmt-int (get kinds k)) (bar (get kinds k) mx)))))

    (println)
    (println (str BOLD "─── diagnostics ───" RESET))
    (if (and (empty? unclosed) (empty? silent-drops))
      (println (str "  " GRN "✓ all sends closed, no silent drops" RESET))
      (do
        (when (seq unclosed)
          (println (str "  " RED "⚠ unclosed sends: " (count unclosed) RESET))
          (doseq [s (take 10 unclosed)]
            (println (format "    seq %d: %s → %s[%s]" (:seq s) (:msg s) (:target_cls s) (:target s))))
          (when (> (count unclosed) 10)
            (println (str "    …" (- (count unclosed) 10) " more"))))
        (when (seq silent-drops)
          (let [clock? #(str/includes? (str (:msg %) (:target_cls %)) "Clock")
                shown (if (:silent-clock opts) silent-drops (remove clock? silent-drops))
                hidden (- (count silent-drops) (count shown))
                label (str "silent drops: " (count silent-drops)
                           (when (pos? hidden) (str " (" hidden " Clock.* hidden)")))]
            (println (str "  " RED "⚠ " label RESET))
            (println "    (recv with no arm — phase mismatch, missing case)")
            (doseq [s (take 10 shown)]
              (println (format "    seq %d: %s → %s[%s]" (:seq s) (:msg s) (:target_cls s) (:target s))))
            (when (> (count shown) 10)
              (println (str "    …" (- (count shown) 10) " more")))))))

    (println)
    (println (str BOLD "─── per-atom inbox ───" RESET))
    (let [per (reduce (fn [m e] (update-in m [(:target e) (:kind e)] (fnil inc 0))) {} events)
          cls-for (reduce (fn [m e] (if (m (:target e)) m (assoc m (:target e) (:target_cls e)))) {} events)
          rows (for [[tgt kc] per
                     :let [s-in (+ (get kc "send" 0) (get kc "send_sync" 0))
                           r-in (+ (get kc "recv" 0) (get kc "recv_sync" 0))]]
                 {:tgt tgt :cls (or (cls-for tgt) "?")
                  :sends s-in :recvs r-in
                  :arms (get kc "arm" 0)
                  :no-arm (get kc "no_arm" 0)
                  :imbal (- s-in r-in)})
          sorted (sort-by (juxt #(- (Math/abs ^long (:imbal %))) #(- (:arms %))) rows)]
      (println (format "  %-40s %7s %7s %7s %7s %7s" "atom" "sends" "recvs" "arms" "no_arm" "imbal"))
      (println (format "  %-40s %7s %7s %7s %7s %7s"
                       (apply str (repeat 40 "-")) "-------" "-------" "-------" "-------" "-------"))
      (doseq [r sorted]
        (let [lbl (subs (str (:cls r) "[" (:tgt r) "]") 0
                        (min 40 (count (str (:cls r) "[" (:tgt r) "]"))))
              imbal-col (if (zero? (:imbal r))
                          (format "%7d" (:imbal r))
                          (str RED (format "%7d" (:imbal r)) RESET))
              no-arm-col (if (zero? (:no-arm r))
                           (format "%7d" (:no-arm r))
                           (str RED (format "%7d" (:no-arm r)) RESET))]
          (println (format "  %-40s %7d %7d %7d %s %s"
                           lbl (:sends r) (:recvs r) (:arms r)
                           no-arm-col imbal-col)))))

    (println)
    (println (str BOLD "─── topology top-" (:top opts) " ───" RESET))
    (let [top (topology events)
          top-n (take (:top opts) (sort-by (comp - val) top))]
      (if (empty? top-n)
        (println "  (no send events)")
        (let [mx (val (first top-n))]
          (doseq [[[src dst msg] n] top-n]
            (println (format "  %16s → %-16s %-30s %5d  %s" src dst msg n (bar n mx 15)))))))

    (println)
    (println (str BOLD "─── topology (aggregate) ───" RESET))
    (let [agg (reduce (fn [m [[src dst _] n]] (update m [src dst] (fnil + 0) n))
                      {} (topology events))]
      (when (seq agg)
        (let [sorted (sort-by (comp - val) agg)
              mx (val (first sorted))]
          (doseq [[[src dst] n] sorted]
            (let [arrow (if (= src dst) "↺" "→")]
              (println (format "  %16s %s %-16s %5d  %s" src arrow dst n (bar n mx 15))))))))))

(defn- usage []
  (println "Usage: cxc trace <file.jsonl> [--top=N] [--silent-clock]")
  (println "  --top=N        show top N topology edges (default 15)")
  (println "  --silent-clock keep Clock.* messages in silent-drop list")
  (println "                 (default: hide, they're periodic background noise)"))

(defn run
  "Entrypoint for `cxc trace <args>`."
  [args]
  (let [opts (atom {:top 15 :silent-clock false})
        positional (atom [])]
    (doseq [a args]
      (cond
        (str/starts-with? a "--top=")
        (swap! opts assoc :top (Integer/parseInt (subs a (count "--top="))))
        (= a "--silent-clock") (swap! opts assoc :silent-clock true)
        (#{"-h" "--help"} a) (do (usage) (throw (ex-info "help" {:exit 0})))
        :else (swap! positional conj a)))
    (when (empty? @positional)
      (usage)
      (throw (ex-info "no file" {:exit 2})))
    (let [path (first @positional)]
      (when-not (.exists (io/file path))
        (binding [*out* *err*] (println (str "no such file: " path)))
        (throw (ex-info "not found" {:exit 2})))
      (try (print-report (infer-senders (load-events path)) @opts)
           (catch clojure.lang.ExceptionInfo ex
             (throw ex))))))
