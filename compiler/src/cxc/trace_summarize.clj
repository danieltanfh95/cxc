(ns cxc.trace-summarize
  "CLI diagnostics for a `cxc compile --trace` JSONL dump. Prints the
   same signal the browser viewer (cxc view) shows: overview, kind
   histogram, unclosed sends, silent drops, per-atom inbox, topology."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.trace-io :as trace-io]
            [cxc.util.io :refer [eprintln]]))

(def ^:private send-kinds #{"send" "send_sync"})
(def ^:private recv-kinds #{"recv" "recv_sync"})
(defn- send-kind? [k] (contains? send-kinds k))
(defn- recv-kind? [k] (contains? recv-kinds k))

(def ^:private silent-drop-window
  "Forward-scan window (events) used by `analyze` to decide whether a
   recv is a silent drop. A recv counts as serviced if an `arm` on the
   same target appears within this window after the recv."
  6)

(defn- infer-senders
  "Attribute each send/send_sync whose recorded :sender is nil-or-zero
   to the most-recently-fired arm's class (single-queue mode makes
   this the heuristic the viewer uses). Writes :_sender_cls_inferred;
   `topology` reads it as the src classifier."
  [events]
  (loop [i 0, last-cls nil, out (transient [])]
    (if (>= i (count events))
      (persistent! out)
      (let [e (nth events i)]
        (cond
          (= "arm" (:kind e))
          (recur (inc i) (:target_cls e) (conj! out e))

          (and (send-kind? (:kind e))
               (or (nil? (:sender e)) (zero? (:sender e))))
          (recur (inc i) last-cls
                 (conj! out (assoc e :_sender_cls_inferred last-cls)))

          :else
          (recur (inc i) last-cls (conj! out e)))))))

(defn- analyze
  "Compute {:unclosed :silent-drops}. A silent drop is a recv with no
   matching arm on the same atom within `silent-drop-window` events
   after it."
  [events]
  (loop [i 0
         pending {}
         unclosed (transient [])
         silent (transient [])]
    (if (>= i (count events))
      {:unclosed (into (persistent! unclosed) (mapcat identity) (vals pending))
       :silent-drops (persistent! silent)}
      (let [e (nth events i)
            k (:kind e)
            key [(:target e) (:msg e)]]
        (cond
          (send-kind? k)
          (recur (inc i) (update pending key (fnil conj []) e) unclosed silent)

          (recv-kind? k)
          (let [q (get pending key)
                [head & rest'] q
                pending' (if head
                           (if (seq rest')
                             (assoc pending key (vec rest'))
                             (dissoc pending key))
                           pending)
                msg (or (:msg e) "")
                skip-silent? (str/starts-with? msg "self.cxc_emit_")
                recv-base (last (str/split msg #"\."))
                hit? (loop [j (inc i)]
                       (if (or (>= j (count events)) (>= j (+ i 1 silent-drop-window))) false
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
            (recur (inc i) pending' unclosed silent'))

          :else
          (recur (inc i) pending unclosed silent))))))

(defn- topology
  "(src-cls, dst-cls, msg) → count over send/send_sync events."
  [events]
  (persistent!
    (reduce (fn [acc e]
              (if (send-kind? (:kind e))
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

(def ^:private tty? (some? (System/console)))
(def ^:private BOLD  (if tty? "\033[1m"  ""))
(def ^:private RED   (if tty? "\033[31m" ""))
(def ^:private GRN   (if tty? "\033[32m" ""))
(def ^:private RESET (if tty? "\033[0m"  ""))

(defn- print-seq-rows
  "Print the first `limit` entries of `coll` via `seq %d: msg → cls[tgt]`,
   then a `…N more` line when the collection overflows. Shared by the
   unclosed-sends and silent-drops diagnostic sections."
  [coll limit]
  (doseq [s (take limit coll)]
    (println (format "    seq %d: %s → %s[%s]"
                     (:seq s) (:msg s) (:target_cls s) (:target s))))
  (when (> (count coll) limit)
    (println (str "    …" (- (count coll) limit) " more"))))

(defn- print-report [events opts]
  (when (empty? events)
    (println "empty trace.")
    (throw (ex-info "empty" {:exit 0})))
  (let [dur (if (> (count events) 1)
              (double (- (:t (last events)) (:t (first events))))
              0.0)
        atoms (into #{} (map :target) events)
        classes (into #{} (map :target_cls) events)
        kinds (reduce (fn [m e] (update m (:kind e) (fnil inc 0))) {} events)
        {:keys [unclosed silent-drops]} (analyze events)
        top-map (topology events)]

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
          (print-seq-rows unclosed 10))
        (when (seq silent-drops)
          (let [clock? #(str/includes? (str (:msg %) (:target_cls %)) "Clock")
                shown (if (:silent-clock opts) silent-drops (remove clock? silent-drops))
                hidden (- (count silent-drops) (count shown))
                label (str "silent drops: " (count silent-drops)
                           (when (pos? hidden) (str " (" hidden " Clock.* hidden)")))]
            (println (str "  " RED "⚠ " label RESET))
            (println "    (recv with no arm — phase mismatch, missing case)")
            (print-seq-rows shown 10)))))

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
        (let [s (str (:cls r) "[" (:tgt r) "]")
              lbl (subs s 0 (min 40 (count s)))
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
    (let [top-n (take (:top opts) (sort-by (comp - val) top-map))]
      (if (empty? top-n)
        (println "  (no send events)")
        (let [mx (val (first top-n))]
          (doseq [[[src dst msg] n] top-n]
            (println (format "  %16s → %-16s %-30s %5d  %s" src dst msg n (bar n mx 15)))))))

    (println)
    (println (str BOLD "─── topology (aggregate) ───" RESET))
    (let [agg (reduce (fn [m [[src dst _] n]] (update m [src dst] (fnil + 0) n))
                      {} top-map)]
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

(defn- parse-top-flag [raw-value]
  (try
    (let [n (Integer/parseInt raw-value)]
      (when (pos? n) n))
    (catch NumberFormatException _ nil)))

(defn- parse-args
  "Pure argv parse → {:opts :positional :error :help?}. One pass,
   immutable result."
  [args]
  (reduce
    (fn [acc a]
      (cond
        (:error acc)                    acc
        (#{"-h" "--help"} a)            (assoc acc :help? true)
        (str/starts-with? a "--top=")   (if-let [n (parse-top-flag (subs a (count "--top=")))]
                                          (assoc-in acc [:opts :top] n)
                                          (assoc acc :error (str "invalid --top=: " a)))
        (= a "--silent-clock")          (assoc-in acc [:opts :silent-clock] true)
        (str/starts-with? a "--")       (assoc acc :error (str "unknown flag: " a))
        :else                           (update acc :positional conj a)))
    {:opts {:top 15 :silent-clock false} :positional []}
    args))

(defn run
  "Entrypoint for `cxc trace <args>`."
  [args]
  (let [{:keys [opts positional error help?]} (parse-args args)]
    (cond
      help?
      (do (usage) (throw (ex-info "help" {:exit 0})))

      error
      (do (eprintln error)
          (usage)
          (throw (ex-info error {:exit 2})))

      (not= 1 (count positional))
      (do (usage) (throw (ex-info "expected one <file.jsonl>" {:exit 2})))

      :else
      (let [path (first positional)]
        (when-not (.exists (io/file path))
          (eprintln (str "no such file: " path))
          (throw (ex-info "not found" {:exit 2})))
        (print-report (infer-senders (trace-io/load-events path)) opts)))))
