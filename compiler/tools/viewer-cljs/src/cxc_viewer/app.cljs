(ns cxc-viewer.app
  "Trace viewer entry point. Keeps everything the view needs in one
   ratom-less atom. Every state transition rerenders by `replicant/render`
   against the current snapshot, so a stale async completion that lands
   after a view-id change can be dropped by checking (:gen @state).

   The HTML shell at compiler/tools/trace-viewer-v2.html loads this bundle
   plus shape-canvas.js (the shared ELK adapter), then this init! runs.
   Schema (enums, target-brief, filter-annotations) comes from the shared
   cxc.trace-schema .cljc so viewer and CLI see the same values."
  (:require [replicant.dom :as rdom]
            [clojure.string :as str]
            [cxc.trace-schema :as schema]))

(declare heat-rebuild-and-apply!)

;; ---------- state ----------

(defonce ^:private state
  (atom {:gen            0
         :loading?       false
         :events         []
         :shape          nil
         :sidecar        (schema/empty-sidecar "" "" "")
         :trace-name     nil
         :shape-name     nil
         :annotation-key nil   ;; the ?trace= value we POST sidecar updates against
         :filter-status  nil   ;; nil = all
         :error          nil
         :layout         nil
         :playback       {:cursor-seq nil   ;; nil = at-start, number = current event seq
                          :paused?    true
                          :speed      1}
         :heatmap        {:on?       false
                          :per-atom  {}
                          :per-send  {}
                          :atom-max  0
                          :send-max  0}}))

(defn- next-gen! []
  (:gen (swap! state update :gen inc)))

(defn- commit!
  "Apply f to the state iff the gen is still `my-gen`. Lets a slow fetch
   or layout call bail silently when the user has already loaded a
   different artifact."
  [my-gen f]
  (swap! state (fn [s] (if (= my-gen (:gen s)) (f s) s))))

;; ---------- jsonl ----------

(defn- parse-jsonl [^String text]
  (->> (str/split-lines (or text ""))
       (keep (fn [line]
               (let [t (str/trim line)]
                 (when-not (str/blank? t)
                   (try
                     (js->clj (js/JSON.parse t) :keywordize-keys true)
                     (catch :default _ nil))))))
       vec))

(defn- with-sender-cls
  "Infer the sender class for each send/send_sync event (single-queue mode:
   the sender is the atom whose arm most recently fired). Matches the
   legacy viewer's `_inferredSenderCls` pass so edge-flash selectors line up."
  [events]
  (let [sorted (vec (sort-by :seq events))]
    (loop [i 0, last-arm nil, out (transient [])]
      (if (>= i (count sorted))
        (persistent! out)
        (let [e (nth sorted i)]
          (cond
            (= "arm" (:kind e))
            (recur (inc i) (:target_cls e) (conj! out e))

            (and (#{"send" "send_sync"} (:kind e))
                 (or (nil? (:sender e)) (zero? (:sender e))))
            (recur (inc i) last-arm (conj! out (assoc e :_inferred_sender_cls last-arm)))

            :else
            (recur (inc i) last-arm (conj! out e))))))))

;; ---------- playback helpers (seq-keyed) ----------

(defn- events-for-playback
  "The ordered sequence that playback walks. Hook point for future seq-range
   / atom filters; today it's just all events sorted by seq. Keyed by seq so
   adding a filter later keeps the cursor pointing at the same logical event."
  [events]
  (vec (sort-by :seq events)))

(defn- cursor-index
  "The current index in `evs` for `cursor-seq`. Returns 0 when cursor-seq
   is nil or the list is empty. When the cursor-seq no longer matches an
   event (filter changed), returns the next event with seq >= cursor-seq,
   or the last index if none."
  [evs cursor-seq]
  (cond
    (zero? (count evs)) 0
    (nil? cursor-seq)   0
    :else (let [n (count evs)]
            (loop [i 0]
              (cond
                (>= i n)                              (dec n)
                (>= (:seq (nth evs i)) cursor-seq)    i
                :else                                 (recur (inc i)))))))

;; ---------- shape layout (imperative, mounted into #shape-container) ----------

(defonce ^:private elk-instance
  (when-let [E (.-ELK js/window)]
    (new E)))

(defn- render-shape! [{:keys [shape events gen] :as _snapshot}]
  (when-let [container (js/document.getElementById "shape-container")]
    (if (nil? shape)
      (set! (.-innerHTML container) "")
      (let [my-gen gen
            SC     (.-CxcShapeCanvas js/window)
            opts   #js {:algorithm "layered" :direction "DOWN" :showInit true}]
        (-> (.renderShapeInto SC container
                              #js {:shape   (clj->js shape)
                                   :events  (clj->js events)
                                   :elk     elk-instance
                                   :opts    opts
                                   :context #js {}})
            (.then (fn [res]
                     (commit! my-gen
                       #(assoc % :layout {:nodes (.-nodeCount res)
                                          :edges (.-edgeCount res)}))
                     (heat-rebuild-and-apply!)))
            (.catch (fn [e]
                      (commit! my-gen
                        #(assoc % :error (str "shape render: " (.-message e)))))))))))

;; ---------- heatmap ----------
;;
;; Count events per atom and per send-direction. Overlay colour on the
;; shape SVG after shape-canvas has painted (classList + CSS vars; body
;; carries the `heatmap-on` toggle so the shared CSS kicks in).
;;
;; Phase and transition heat (inside the atom detail view) are deferred
;; until the v2 viewer grows an expanded-atom panel; today the shape
;; shows blocks + inter-atom edges only, which is what per-atom and
;; per-send cover.

(defn- atom-shape-for-class [shape cls]
  (when (and shape cls)
    (first (filter #(= cls (:name %)) (:atoms shape)))))

(defn- atom-ann-name
  "Strip the `Cxc` compiler prefix so the viewer displays user class names."
  [cls]
  (when cls
    (if (str/starts-with? cls "Cxc")
      (subs cls 3)
      cls)))

(defn- heat-recompute
  "Return the heat counts for the current events + shape. Per-atom is a
   straight frequency over target_cls / atom_cls. Per-send reuses
   CxcShapeCanvas.buildGraph + .topology so sender inference stays
   aligned with the painted SVG."
  [events shape]
  (let [per-atom (persistent!
                   (reduce
                     (fn [m e]
                       (let [cls  (or (:target_cls e) (:atom_cls e) (:atom e))
                             nm   (or (:name (atom-shape-for-class shape cls))
                                      (atom-ann-name cls))]
                         (if nm (assoc! m nm (inc (get m nm 0))) m)))
                     (transient {})
                     events))
        atom-max (if (seq per-atom) (apply max 0 (vals per-atom)) 0)
        SC       (.-CxcShapeCanvas js/window)
        per-send (if (and SC shape (seq events))
                   (let [disp     (fn [id] (if (str/includes? id ".")
                                             (last (str/split id #"\."))
                                             id))
                         graph    (.buildGraph SC
                                   (clj->js shape) true (clj->js events)
                                   disp
                                   (fn [m] (or m "")))
                         topo-res (.topology SC
                                   (.-nodes graph) (.-edges graph)
                                   (clj->js shape)
                                   (fn [cls] (clj->js (atom-shape-for-class shape cls)))
                                   true)
                         edges    (.-edges topo-res)]
                     (into {}
                           (for [i (range (.-length edges))]
                             (let [e (aget edges i)]
                               [(str (.-src e) "|" (.-dst e))
                                (.-total e)]))))
                   {})
        send-max (if (seq per-send) (apply max 0 (vals per-send)) 0)]
    {:on?      false
     :per-atom per-atom
     :per-send per-send
     :atom-max atom-max
     :send-max send-max}))

(defn- heat-color-str [count max-count]
  (let [ratio (if (pos? max-count)
                (min 1.0 (/ count max-count))
                0.0)]
    (str "color-mix(in srgb, var(--p-bg), var(--p-accent) "
         (.toFixed (* ratio 100) 3) "%)")))

(defn- shape-svg []
  (when-let [c (js/document.getElementById "shape-container")]
    (.querySelector c "svg")))

(defn- apply-heatmap! []
  (let [{:keys [heatmap]} @state
        {:keys [on? per-atom per-send atom-max send-max]} heatmap
        svg (shape-svg)]
    (.toggle (.-classList js/document.body) "heatmap-on" on?)
    (when (and on? svg)
      (doseq [blk (array-seq (.querySelectorAll svg ".shape-block"))]
        (let [nm (.-nodeName (.-dataset blk))
              c  (get per-atom nm 0)]
          (.add (.-classList blk) "heat-fill")
          (.setProperty (.-style blk) "--heat-color" (heat-color-str c atom-max))
          (.setAttribute blk "data-heat-count" (str c))))
      (doseq [edge (array-seq (.querySelectorAll svg ".shape-edge"))]
        (let [src (.-src (.-dataset edge))
              dst (.-dst (.-dataset edge))
              c   (get per-send (str src "|" dst) 0)]
          (.add (.-classList edge) "heat-fill")
          (.setProperty (.-style edge) "--heat-color" (heat-color-str c send-max))
          (.setAttribute edge "data-heat-count" (str c)))))))

(defn- heat-rebuild-and-apply! []
  (let [{:keys [events shape]} @state
        prev-on? (get-in @state [:heatmap :on?])
        hm       (assoc (heat-recompute events shape) :on? prev-on?)]
    (swap! state assoc :heatmap hm)
    (apply-heatmap!)))

;; ---------- playback: imperative SVG flashes ----------
;;
;; Replicant owns the transport-bar DOM. Everything inside the shape SVG is
;; still painted by shape-canvas.js (step 5 contract), so we reach into it
;; by querySelector exactly like the legacy viewer did. Keeps the diff
;; narrow and avoids having to teach replicant about SVG class toggles.

(defn- pt-clear-flashes! []
  (when-let [svg (shape-svg)]
    (doseq [el (array-seq (.querySelectorAll svg ".pt-flash-src,.pt-flash-dst"))]
      (.remove (.-classList el) "pt-flash-src" "pt-flash-dst"))
    (doseq [el (array-seq (.querySelectorAll svg ".pt-flash"))]
      (.remove (.-classList el) "pt-flash"))
    (doseq [el (array-seq (.querySelectorAll svg ".pt-dot"))]
      (.remove el))))

(defn- pt-flash-event! [e forward?]
  (when-let [svg (shape-svg)]
    (let [src-id (:_inferred_sender_cls e)
          dst-id (:target_cls e)
          q      (fn [sel] (when sel (.querySelector svg sel)))
          esc    (fn [s] (.escape js/CSS s))
          src-el (q (when src-id (str ".shape-block[data-node-id=\"" (esc src-id) "\"]")))
          dst-el (q (when dst-id (str ".shape-block[data-node-id=\"" (esc dst-id) "\"]")))
          edge   (q (when (and src-id dst-id)
                      (str ".shape-edge[data-src=\"" (esc src-id)
                           "\"][data-dst=\"" (esc dst-id) "\"]")))]
      (when src-el (.add (.-classList src-el) "pt-flash-src"))
      (when dst-el (.add (.-classList dst-el) "pt-flash-dst"))
      (when edge   (.add (.-classList edge)   "pt-flash"))
      (when (and edge forward?)
        (let [total (.getTotalLength edge)
              dot   (js/document.createElementNS "http://www.w3.org/2000/svg" "circle")
              t0    (.now js/performance)
              dur   (max 220 (min 600 (* total 0.9)))]
          (.setAttribute dot "r" 4)
          (.setAttribute dot "class" "pt-dot")
          (.appendChild (.-parentNode edge) dot)
          (letfn [(step [now]
                    (let [t (/ (- now t0) dur)]
                      (if (>= t 1)
                        (.remove dot)
                        (let [pt (.getPointAtLength edge (* t total))]
                          (.setAttribute dot "cx" (.-x pt))
                          (.setAttribute dot "cy" (.-y pt))
                          (js/requestAnimationFrame step)))))]
            (js/requestAnimationFrame step))))
      (js/setTimeout
        (fn []
          (when src-el (.remove (.-classList src-el) "pt-flash-src"))
          (when dst-el (.remove (.-classList dst-el) "pt-flash-dst"))
          (when edge   (.remove (.-classList edge)   "pt-flash")))
        280))))

;; ---------- playback: raf loop ----------
;;
;; The raf handle lives outside the state atom so a stale closure can't
;; cancel the current loop. `pt-run-tag` identifies the running loop; the
;; raf callback bails if the tag changes under it (pause / stop / new play).

(defonce ^:private pt-raf-handle (atom nil))
(defonce ^:private pt-run-tag    (atom 0))

(defn- pt-advance! [forward?]
  (let [{:keys [events] {:keys [cursor-seq]} :playback} @state
        evs (events-for-playback events)]
    (when (seq evs)
      (let [i   (cursor-index evs cursor-seq)
            cap (dec (count evs))
            i2  (cond
                  forward?       (min cap (inc i))
                  (pos? i)       (dec i)
                  :else          0)
            e   (nth evs i2)]
        (swap! state assoc-in [:playback :cursor-seq] (:seq e))
        (when (#{"send" "send_sync" "arm" "no_arm"} (:kind e))
          (pt-flash-event! e forward?))))))

(defn- pt-pause! []
  (swap! state assoc-in [:playback :paused?] true)
  (when-let [h @pt-raf-handle]
    (js/cancelAnimationFrame h)
    (reset! pt-raf-handle nil)))

(defn- pt-play! []
  (let [{:keys [events] {:keys [cursor-seq]} :playback} @state
        evs (events-for-playback events)]
    (when (seq evs)
      (when (= cursor-seq (:seq (last evs)))
        (swap! state assoc-in [:playback :cursor-seq] nil))
      (swap! state update :playback assoc :paused? false :last-tick nil)
      (let [tag (swap! pt-run-tag inc)]
        (letfn [(tick [now]
                  (when (= tag @pt-run-tag)
                    (let [{:keys [events]
                           {:keys [cursor-seq speed last-tick paused?]} :playback}
                          @state
                          evs (events-for-playback events)
                          cap (dec (count evs))
                          i   (cursor-index evs cursor-seq)]
                      (when-not paused?
                        (if (nil? last-tick)
                          (do (swap! state assoc-in [:playback :last-tick] now)
                              (reset! pt-raf-handle (js/requestAnimationFrame tick)))
                          (let [step-ms (/ 1000 (* 60 (or speed 1)))
                                dt      (- now last-tick)]
                            (if (and (>= dt step-ms) (< i cap))
                              (let [n-steps (max 1 (js/Math.floor (/ dt step-ms)))
                                    i2      (min cap (+ i n-steps))
                                    e2      (nth evs i2)]
                                (swap! state update :playback assoc
                                       :cursor-seq (:seq e2)
                                       :last-tick  now)
                                (when (#{"send" "send_sync" "arm" "no_arm"} (:kind e2))
                                  (pt-flash-event! e2 true))
                                (if (>= i2 cap)
                                  (pt-pause!)
                                  (reset! pt-raf-handle (js/requestAnimationFrame tick))))
                              (reset! pt-raf-handle (js/requestAnimationFrame tick)))))))))]
          (reset! pt-raf-handle (js/requestAnimationFrame tick)))))))

(defn- pt-rewind! []
  (pt-pause!)
  (pt-clear-flashes!)
  (swap! state assoc-in [:playback :cursor-seq] nil))

(defn- pt-scrub-to-index! [i]
  (pt-pause!)
  (let [evs (events-for-playback (:events @state))
        cap (dec (count evs))]
    (when (seq evs)
      (let [i' (max 0 (min cap i))
            e  (nth evs i')]
        (pt-clear-flashes!)
        (swap! state assoc-in [:playback :cursor-seq] (:seq e))
        (when (#{"send" "send_sync" "arm" "no_arm"} (:kind e))
          (pt-flash-event! e false))))))

;; ---------- view ----------

(defn- stats-row [label value]
  [:div.stat-row [:span.k label] [:span.v value]])

(defn- transition-buttons [{:keys [id] :as ann}]
  (let [status (schema/effective-status ann)]
    (cond
      (schema/terminal? status) [:div.ann-actions.terminal (str status)]
      (= "addressed" status)    [:div.ann-actions
                                 [:button.btn.verify
                                  {:on {:click [::transition id "verified"]}}
                                  "mark verified"]]
      :else                     [:div.ann-actions
                                 [:button.btn.addressed
                                  {:on {:click [::transition id "addressed"]}}
                                  "addressed"]
                                 [:button.btn.wontfix
                                  {:on {:click [::transition id "wontfix"]}}
                                  "wontfix"]])))

(defn- annotation-row [{:keys [id note severity] :as ann}]
  [:li.ann-row {:data-ann-id   id
                :data-status   (schema/effective-status ann)
                :data-severity severity}
   [:div.ann-head
    [:span.ann-id id]
    [:span.ann-status (schema/effective-status ann)]
    [:span.ann-sev severity]]
   [:div.ann-target (schema/target-brief (:target ann))]
   [:div.ann-note (subs (or note "") 0 (min 120 (count (or note ""))))]
   (transition-buttons ann)])

(defn- annotations-pane [{:keys [sidecar filter-status]}]
  (let [all     (:annotations sidecar)
        shown   (schema/filter-annotations all (when filter-status
                                                 {:status filter-status}))
        counts  (frequencies (map schema/effective-status all))]
    [:section.annotations
     [:header.ann-header
      [:span.title "annotations"]
      [:span.ann-count (str (count shown) " of " (count all))]]
     [:div.ann-filters
      (for [status (cons nil (sort schema/statuses))]
        [:button.ann-filter
         {:data-status (or status "all")
          :data-active (= status filter-status)
          :on {:click [::set-filter status]}}
         (if status
           (str status " (" (or (counts status) 0) ")")
           "all")])]
     [:ul.ann-list
      (if (empty? shown)
        [:li.ann-empty "no annotations"]
        (map annotation-row shown))]]))

(defn- transport-bar [{:keys [events playback]}]
  (let [evs (events-for-playback events)]
    (when (seq evs)
      (let [{:keys [cursor-seq paused? speed]} playback
            total (count evs)
            i     (cursor-index evs cursor-seq)
            e     (nth evs i)
            seq   (:seq e)
            msg   (or (:msg e) "")]
        [:div.pt-transport
         [:button {:on {:click [::pt-rewind]} :title "jump to start"} "⏮"]
         [:button {:on {:click [::pt-step-back]} :title "step back"} "←"]
         [:button {:on {:click [::pt-toggle]} :title "play / pause"}
          (if paused? "play" "pause")]
         [:button {:on {:click [::pt-step-fwd]} :title "step forward"} "→"]
         [:input {:type       "range"
                  :min        "0"
                  :max        (str (dec total))
                  :value      (str i)
                  :on         {:input [::pt-scrub]}}]
         [:select {:value (str speed)
                   :on    {:change [::pt-set-speed]}}
          [:option {:value "1"}   "1×"]
          [:option {:value "5"}   "5×"]
          [:option {:value "20"}  "20×"]
          [:option {:value "100"} "100×"]]
         [:span.pt-status
          [:b (str "seq " seq)]
          " · " msg
          [:span.pt-count (str "(" (inc i) "/" total ")")]]]))))

(defn- view [{:keys [loading? events shape sidecar trace-name shape-name error layout] :as s}]
  [:div#app-root
   [:header
    [:h1 "cxc trace viewer (v2)"]
    (when (or trace-name shape-name)
      [:div.trace-name (or trace-name shape-name)])]
   (cond
     error     [:div.error (str "load failed: " error)]
     loading?  [:div.loading "loading…"]
     (and (empty? events) (nil? shape))
     [:div.empty "no trace or shape loaded. open with ?trace=... or ?shape=..."]
     :else
     [:section.main
      [:aside.stats
       (stats-row "events"
                  (count events))
       (stats-row "distinct atoms"
                  (count (into #{} (keep :target) events)))
       (stats-row "distinct classes"
                  (count (into #{} (keep :target_cls) events)))
       (stats-row "duration (ns)"
                  (if (seq events)
                    (- (:t (last events)) (:t (first events)))
                    0))
       (stats-row "annotations (open)"
                  (count (schema/filter-annotations (:annotations sidecar)
                                                    {:status "open"})))
       (stats-row "schema version"
                  (:cxc_annotation_version sidecar))
       (when layout
         (list (stats-row "shape nodes" (:nodes layout))
               (stats-row "shape edges" (:edges layout))))
       (let [{:keys [on? atom-max send-max]} (:heatmap s)]
         [:label.heat-toggle
          [:input {:type     "checkbox"
                   :checked  on?
                   :disabled (zero? (count events))
                   :on       {:change [::heat-toggle]}}]
          " heatmap "
          [:span.heat-ramp
           (str "max atom " atom-max " · max send " send-max)]])]
      [:div.shape-stage
       [:div#shape-container.shape-container]
       (transport-bar s)]
      (annotations-pane s)])])

;; ---------- render loop ----------

(def ^:private root (atom nil))

(defn- rerender! []
  (when-let [el @root]
    (rdom/render el (view @state))))

(defn- on-state-change [_k _r old-s new-s]
  (rerender!)
  (when (not= (:shape old-s) (:shape new-s))
    ;; Defer one tick so replicant has committed the container <div>
    ;; before render-shape! queries it by id.
    (js/setTimeout #(render-shape! new-s) 0)))

;; ---------- url + load ----------

(defn- query-params []
  (let [p (js/URLSearchParams. (.-search js/location))]
    {:trace (.get p "trace")
     :shape (.get p "shape")}))

(defn- derive-shape-path [trace-path]
  (when trace-path
    (-> trace-path
        (str/replace #"\.trace\.jsonl$" ".shape.json")
        (str/replace #"\.jsonl$"        ".shape.json"))))

(defn- fetch-json [url]
  (-> (js/fetch url #js {:cache "no-store"})
      (.then (fn [r]
               (if (.-ok r)
                 (.json r)
                 (throw (js/Error. (str "HTTP " (.-status r) " on " url))))))))

(defn- load-shape! [shape-url]
  (let [my-gen (:gen @state)]
    (swap! state assoc :shape-name shape-url)
    (-> (fetch-json shape-url)
        (.then (fn [j]
                 (commit! my-gen
                   #(assoc % :shape (js->clj j :keywordize-keys true))))))))

(defn- load-trace! [trace-url]
  (let [my-gen (next-gen!)]
    (swap! state assoc :loading? true :trace-name trace-url
                       :annotation-key trace-url :error nil)
    (-> (js/fetch trace-url #js {:cache "no-store"})
        (.then (fn [r]
                 (if (.-ok r)
                   (.text r)
                   (throw (js/Error. (str "HTTP " (.-status r)))))))
        (.then (fn [text]
                 (commit! my-gen (fn [s] (assoc s
                                                :events   (with-sender-cls (parse-jsonl text))
                                                :loading? false
                                                :playback {:cursor-seq nil
                                                           :paused?    true
                                                           :speed      1})))))
        (.catch (fn [e]
                  (commit! my-gen (fn [s] (assoc s :error (.-message e)
                                                    :loading? false))))))))

;; ---------- annotations load + poll ----------

(defn- load-annotations! []
  (when-let [k (:annotation-key @state)]
    (let [my-gen (:gen @state)]
      (-> (js/fetch (str "/annotations?trace=" (js/encodeURIComponent k)))
          (.then (fn [r]
                   (when (.-ok r) (.json r))))
          (.then (fn [j]
                   (when j
                     (commit! my-gen
                       #(assoc % :sidecar (js->clj j :keywordize-keys true))))))
          (.catch (fn [_] nil))))))

(defonce ^:private poll-handle (atom nil))
(defonce ^:private pending-save-count (atom 0))

(defn- start-poll! []
  (when @poll-handle (js/clearInterval @poll-handle))
  ;; Skip the poll while a save is in flight so the server's response
  ;; doesn't clobber the optimistic local update before it lands.
  (reset! poll-handle (js/setInterval
                        #(when (zero? @pending-save-count) (load-annotations!))
                        2000)))

(defn- save-sidecar!
  "POST the full sidecar for the current annotation-key. Optimistic local
   state is already updated by the caller. Save failures surface the
   server message but don't roll back the local atom (poll reconciles)."
  []
  (let [{:keys [annotation-key sidecar]} @state]
    (when annotation-key
      (swap! pending-save-count inc)
      (-> (js/fetch (str "/annotations?trace=" (js/encodeURIComponent annotation-key))
                    #js {:method  "POST"
                         :headers #js {"Content-Type" "application/json"}
                         :body    (js/JSON.stringify (clj->js sidecar))})
          (.then (fn [r]
                   (when-not (.-ok r)
                     (throw (js/Error. (str "save failed: HTTP " (.-status r)))))))
          (.catch (fn [e]
                    (swap! state assoc :error (.-message e))))
          (.finally (fn [] (swap! pending-save-count dec)))))))

(defn- now-iso [] (-> (js/Date.) .toISOString))

(defn- transition!
  "Apply a status transition to one annotation locally, then POST the
   updated sidecar. Uses the shared schema predicates so viewer and CLI
   agree on what counts as terminal."
  [ann-id new-status]
  (swap! state update-in [:sidecar :annotations]
    (fn [anns]
      (mapv (fn [a]
              (if (= (:id a) ann-id)
                (cond-> (assoc a :status new-status)
                  (contains? #{"addressed" "wontfix"} new-status)
                  (assoc :resolution {:by   "viewer"
                                      :at   (now-iso)
                                      :kind (if (= new-status "wontfix") "wontfix" "fix_landed")}))
                a))
            anns)))
  (save-sidecar!))

;; ---------- event dispatch ----------

(defn- dom-event-value [rep-data]
  (some-> rep-data :replicant/dom-event .-target .-value))

(defn- handle-event [rep-data [tag & args]]
  (case tag
    ::set-filter   (swap! state assoc :filter-status (first args))
    ::transition   (apply transition! args)
    ::pt-rewind    (pt-rewind!)
    ::pt-step-fwd  (do (pt-pause!) (pt-advance! true))
    ::pt-step-back (do (pt-pause!) (pt-advance! false))
    ::pt-toggle    (if (get-in @state [:playback :paused?]) (pt-play!) (pt-pause!))
    ::pt-scrub     (when-let [v (dom-event-value rep-data)]
                     (pt-scrub-to-index! (js/parseInt v 10)))
    ::pt-set-speed (when-let [v (dom-event-value rep-data)]
                     (swap! state assoc-in [:playback :speed] (js/parseFloat v)))
    ::heat-toggle  (do (swap! state update-in [:heatmap :on?] not)
                       (apply-heatmap!))
    (js/console.warn "unhandled event" (pr-str tag))))

;; ---------- init ----------

(defn ^:export init! []
  (reset! root (js/document.getElementById "app"))
  (rdom/set-dispatch!
    (fn [replicant-data handler-event]
      (handle-event replicant-data handler-event)))
  (add-watch state ::rerender on-state-change)
  (rerender!)
  (let [{:keys [trace shape]} (query-params)
        shape-url (or shape (derive-shape-path trace))]
    (when trace     (load-trace! trace))
    (when shape-url (load-shape! shape-url))
    (when (or trace shape-url)
      (swap! state assoc :annotation-key (or trace shape-url))
      (load-annotations!)
      (start-poll!)))
  (.addEventListener js/window "keydown"
    (fn [e]
      (let [k (.-key e)
            tgt (.-target e)
            tag (and tgt (.-tagName tgt))]
        (when (and (= k " ")
                   (not (contains? #{"INPUT" "TEXTAREA" "SELECT"} tag))
                   (seq (:events @state)))
          (.preventDefault e)
          (if (get-in @state [:playback :paused?]) (pt-play!) (pt-pause!))))))
  (set! (.-cxcViewerV2 js/window)
        #js {:state       state
             :getState    #(clj->js @state)
             :loadTrace   load-trace!
             :loadShape   load-shape!
             :loadAnns    load-annotations!
             :rerender    rerender!
             :ptPlay      pt-play!
             :ptPause     pt-pause!
             :ptRewind    pt-rewind!
             :ptStepFwd   #(do (pt-pause!) (pt-advance! true))
             :ptStepBack  #(do (pt-pause!) (pt-advance! false))
             :ptScrubIdx  pt-scrub-to-index!
             :ptEventCount #(count (events-for-playback (:events @state)))
             :heatToggle   #(do (swap! state update-in [:heatmap :on?] not)
                                (apply-heatmap!))
             :heatRebuild  heat-rebuild-and-apply!}))
