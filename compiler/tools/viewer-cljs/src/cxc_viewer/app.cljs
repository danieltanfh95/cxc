(ns cxc-viewer.app
  "Trace viewer entry point. Keeps everything the view needs in one
   ratom-less atom. Every state transition rerenders by `replicant/render`
   against the current snapshot, so a stale async completion that lands
   after a view-id change can be dropped by checking (:gen @state).

   The HTML shell at compiler/tools/trace-viewer.html loads this bundle
   plus shape-canvas.js (the shared ELK adapter), then this init! runs.
   Schema (enums, target-brief, filter-annotations) comes from the shared
   cxc.trace-schema .cljc so viewer and CLI see the same values."
  (:require [replicant.dom :as rdom]
            [clojure.string :as str]
            [cxc.trace-schema :as schema]))

(declare heat-rebuild-and-apply!
         shape-view apply-shape-view! wire-shape-pan!
         drafts-controls open-popup!)

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
                          :send-max  0}
         :legend-open?   (= "0" (or (.getItem js/localStorage "cxc-shape-legend-hidden") "1"))
         :filters        {:seq-range   ""
                          :kinds-off   #{}
                          :atoms-off   #{}}
         :popup          {:open?       false
                          :target      {}
                          :kind        "node"
                          :note        ""
                          :editing-id  nil
                          :attachments []}
         :detail-atom    nil}))

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

(defn- parse-seq-range
  "`\"lo-hi\"` or `\"\"` → [lo hi] or nil. Both bounds inclusive."
  [s]
  (when-let [m (and s (re-matches #"\s*(\d+)\s*-\s*(\d+)\s*" s))]
    [(js/parseInt (nth m 1) 10) (js/parseInt (nth m 2) 10)]))

(defn- filter-events
  "Apply the user filters (seq range, kind off-set, atom off-set) to events.
   Return the preserved-order vector."
  [events {:keys [seq-range kinds-off atoms-off]}]
  (let [rng (parse-seq-range seq-range)]
    (vec (filter (fn [e]
                   (and (not (contains? kinds-off (:kind e)))
                        (not (contains? atoms-off (or (:target_cls e) (:atom_cls e))))
                        (or (nil? rng)
                            (<= (nth rng 0) (:seq e) (nth rng 1)))))
                 events))))

(defn- events-for-playback
  "The ordered sequence that playback walks. Honours current :filters so a
   seq-range / kind / atom filter narrows the cursor's domain. Keyed by seq
   so filter changes keep the cursor pointing at the same logical event."
  ([events] (events-for-playback events (:filters @state)))
  ([events filters]
   (vec (sort-by :seq (filter-events events (or filters {}))))))

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
                     (reset! shape-view nil)
                     (apply-shape-view!)
                     (wire-shape-pan!)
                     (heat-rebuild-and-apply!)))
            (.catch (fn [e]
                      (commit! my-gen
                        #(assoc % :error (str "shape render: " (.-message e)))))))))))

;; ---------- shape zoom + pan (viewBox-based) ----------
;;
;; shape-canvas sets data-content-{x,y,w,h} on the painted svg; we zoom by
;; shrinking the viewBox around a center point. View state lives outside the
;; replicant atom so a drag doesn't trigger per-frame rerenders — only the
;; zoom-level readout updates when zoom() runs, and that's cheap.

(defonce ^:private shape-view (atom nil))   ;; {:scale, :cx, :cy}
(defonce ^:private pan-wired? (atom false))

(defn- shape-svg-el []
  (when-let [c (js/document.getElementById "shape-container")]
    (.querySelector c "svg")))

(defn- shape-content-box [svg]
  (let [d (.-dataset svg)]
    {:x (js/parseFloat (.-contentX d))
     :y (js/parseFloat (.-contentY d))
     :w (js/parseFloat (.-contentW d))
     :h (js/parseFloat (.-contentH d))}))

(defn- shape-fit-scale [svg]
  (let [{:keys [w h]} (shape-content-box svg)]
    (min (/ (.-clientWidth svg) w) (/ (.-clientHeight svg) h))))

(defn- shape-fit-view [svg]
  (let [{:keys [x y w h]} (shape-content-box svg)]
    {:scale (shape-fit-scale svg) :cx (+ x (/ w 2)) :cy (+ y (/ h 2))}))

(defn- shape-default-view [svg]
  (let [{:keys [x y w h]} (shape-content-box svg)
        fit (shape-fit-scale svg)]
    {:scale (min fit 1.5) :cx (+ x (/ w 2)) :cy (+ y (/ h 2))}))

(defn- apply-shape-view! []
  (when-let [svg (shape-svg-el)]
    (when (pos? (.-clientWidth svg))
      (when (nil? @shape-view)
        (reset! shape-view (shape-default-view svg)))
      (let [{:keys [scale cx cy]} @shape-view
            vw (/ (.-clientWidth svg) scale)
            vh (/ (.-clientHeight svg) scale)]
        (.setAttribute svg "viewBox"
                       (str (- cx (/ vw 2)) " " (- cy (/ vh 2))
                            " " vw " " vh))
        (when-let [lvl (js/document.getElementById "shape-zoom-level")]
          (set! (.-textContent lvl) (str (js/Math.round (* scale 100)) "%")))))))

(defn- zoom-shape-at! [factor client-x client-y]
  (let [svg (shape-svg-el) v @shape-view]
    (when (and svg v)
      (let [r  (.getBoundingClientRect svg)
            ns (max 0.1 (min 4 (* (:scale v) factor)))
            ox (- client-x (+ (.-left r) (/ (.-width r) 2)))
            oy (- client-y (+ (.-top r)  (/ (.-height r) 2)))
            ux (+ (:cx v) (/ ox (:scale v)))
            uy (+ (:cy v) (/ oy (:scale v)))]
        (reset! shape-view {:scale ns :cx (- ux (/ ox ns)) :cy (- uy (/ oy ns))})
        (apply-shape-view!)))))

(defn- zoom-shape-centred! [factor]
  (when-let [svg (shape-svg-el)]
    (let [r (.getBoundingClientRect svg)]
      (zoom-shape-at! factor
                      (+ (.-left r) (/ (.-width r) 2))
                      (+ (.-top r)  (/ (.-height r) 2))))))

(defn- reset-shape-zoom! []
  (when-let [svg (shape-svg-el)]
    (reset! shape-view (shape-default-view svg))
    (apply-shape-view!)))

(defn- fit-shape-view! []
  (when-let [svg (shape-svg-el)]
    (reset! shape-view (shape-fit-view svg))
    (apply-shape-view!)))

(defn- wire-shape-pan!
  "Attach wheel + drag listeners to #shape-container. Idempotent."
  []
  (when-not @pan-wired?
    (reset! pan-wired? true)
    (let [stage (js/document.getElementById "shape-container")
          state* (atom {:active? false :sx 0 :sy 0 :moved? false :cx0 0 :cy0 0})]
      (.addEventListener stage "wheel"
        (fn [ev]
          (when-let [v @shape-view]
            (.preventDefault ev)
            (let [unit (case (.-deltaMode ev) 1 16 2 (.-clientHeight stage) 1)]
              (if (or (.-ctrlKey ev) (.-metaKey ev))
                (zoom-shape-at! (js/Math.exp (* (- (.-deltaY ev)) unit 0.0025))
                                (.-clientX ev) (.-clientY ev))
                (do
                  (swap! shape-view (fn [v]
                    (assoc v :cx (+ (:cx v) (* (/ (* (.-deltaX ev) unit) (:scale v))))
                             :cy (+ (:cy v) (* (/ (* (.-deltaY ev) unit) (:scale v)))))))
                  (apply-shape-view!))))))
        #js {:passive false})
      (.addEventListener stage "mousedown"
        (fn [ev]
          (cond
            (zero? (.-button ev))
            (let [t   (.-target ev)
                  blk (and t (.closest t ".shape-block"))]
              (cond
                ;; Shift-click a shape block → open annotate popup.
                (and blk (.-shiftKey ev))
                (do (.preventDefault ev)
                    (open-popup! {:kind   "node"
                                  :target {:atom (.-nodeName (.-dataset blk))}}))
                ;; Plain click on a shape block → open atom detail pane.
                blk
                (do (.preventDefault ev)
                    (swap! state assoc :detail-atom (.-nodeName (.-dataset blk))))
                ;; Otherwise left-drag starts a pan on empty canvas.
                (not (and t (.closest t ".pt-transport,#shape-zoom,#shape-legend,#atom-detail")))
                (swap! state* assoc :active? true :moved? false
                       :sx (.-clientX ev) :sy (.-clientY ev)
                       :cx0 (:cx @shape-view) :cy0 (:cy @shape-view)))))))
      (.addEventListener js/window "mousemove"
        (fn [ev]
          (when (:active? @state*)
            (let [dx (- (.-clientX ev) (:sx @state*))
                  dy (- (.-clientY ev) (:sy @state*))]
              (when (or (:moved? @state*) (>= (js/Math.hypot dx dy) 4))
                (swap! state* assoc :moved? true)
                (.add (.-classList stage) "panning")
                (when-let [v @shape-view]
                  (reset! shape-view
                          (assoc v :cx (- (:cx0 @state*) (/ dx (:scale v)))
                                   :cy (- (:cy0 @state*) (/ dy (:scale v)))))
                  (apply-shape-view!))
                (.preventDefault ev))))))
      (.addEventListener js/window "mouseup"
        (fn [_]
          (when (:active? @state*)
            (.remove (.-classList stage) "panning")
            (swap! state* assoc :active? false :moved? false))))
      (when (exists? js/ResizeObserver)
        (.observe (js/ResizeObserver. (fn [_] (apply-shape-view!))) stage)))))

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

(defn- filters-pane [{:keys [events filters]}]
  (let [kinds (sort (into #{} (keep :kind events)))
        atoms (sort (into #{} (keep #(or (:target_cls %) (:atom_cls %)) events)))
        {:keys [seq-range kinds-off atoms-off]} (or filters {})
        kinds-off (or kinds-off #{})
        atoms-off (or atoms-off #{})]
    [:details.filters
     [:summary "filters"]
     [:div.filter-group
      [:label.filter-label
       "seq range "
       [:input.filter-input {:type "text" :value (or seq-range "")
                             :placeholder "e.g. 0-500"
                             :on {:input [::set-seq-range]}}]]]
     (when (seq kinds)
       [:div.filter-group
        [:div.filter-head "kinds"]
        (for [k kinds]
          [:label.filter {:data-kind k}
           [:input {:type "checkbox"
                    :checked (not (contains? kinds-off k))
                    :on {:change [::toggle-kind k]}}]
           " " k])])
     (when (seq atoms)
       [:div.filter-group
        [:div.filter-head "atoms"]
        (for [a atoms]
          [:label.filter {:data-atom a}
           [:input {:type "checkbox"
                    :checked (not (contains? atoms-off a))
                    :on {:change [::toggle-atom a]}}]
           " " a])])]))

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

(defn- comments-pane [{:keys [id comments]}]
  (let [replying? (get-in @state [:ui :reply-open id])
        draft    (get-in @state [:ui :reply-text id] "")]
    [:div.ann-comments
     (when (seq comments)
       [:ul.ann-comments-list
        (for [c comments]
          [:li.ann-comment
           [:span.c-author (or (:author c) "?")] " "
           [:span.c-at    (:at c)] [:br]
           [:span.c-body  (:body c)]])])
     (if replying?
       [:div.ann-reply
        [:textarea {:value draft :placeholder "reply · ⌘+Enter sends · Esc cancels"
                    :on {:input [::reply-text id]
                         :keydown [::reply-key id]}}]
        [:div.reply-actions
         [:button.btn {:on {:click [::reply-cancel id]}} "cancel"]
         [:button.btn.primary {:on {:click [::reply-send id]}} "reply"]]]
       [:button.btn.reply-open {:on {:click [::reply-open id]}} "+ reply"])]))

(defn- draft-actions [{:keys [id] :as _ann}]
  [:div.ann-actions
   [:button.btn {:on {:click [::popup-edit-draft id]}} "edit"]
   [:button.btn {:on {:click [::popup-send-draft id]}} "send"]
   [:button.btn {:on {:click [::delete-draft id]}} "delete"]])

(defn- annotation-row [{:keys [id note severity] :as ann}]
  (let [status (schema/effective-status ann)]
    [:li.ann-row {:data-ann-id   id
                  :data-status   status
                  :data-severity severity}
     [:div.ann-head
      [:span.ann-id id]
      [:span.ann-status status]
      [:span.ann-sev severity]]
     [:div.ann-target (schema/target-brief (:target ann))]
     [:div.ann-note (subs (or note "") 0 (min 120 (count (or note ""))))]
     (if (= "draft" status) (draft-actions ann) (transition-buttons ann))
     (when (not= "draft" status)
       (comments-pane ann))]))

(defn- annotations-pane [{:keys [sidecar filter-status] :as s}]
  (let [all     (:annotations sidecar)
        shown   (schema/filter-annotations all (when filter-status
                                                 {:status filter-status}))
        counts  (frequencies (map schema/effective-status all))]
    [:section.annotations
     [:header.ann-header
      [:span.title "annotations"]
      [:span.ann-count (str (count shown) " of " (count all))]]
     [:div.ann-top-actions
      [:button.btn {:on {:click [::popup-open-blank]}} "+ annotate"]
      (drafts-controls s)]
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

(defn- legend-card [open?]
  (if open?
    [:div#shape-legend
     [:button#shape-legend-close {:title "hide legend" :on {:click [::legend-toggle false]}} "×"]
     [:div.legend-group
      [:h5 "atoms"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-sepia)"}}] "main"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper)"}}] "user atom"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper3)"}}] "init / end"]]
     [:div.legend-group
      [:h5 "edges"]
      [:div.legend-row [:span.legend-line {:style {:border-top "1.6px solid var(--p-ink)"}}] "async send"]
      [:div.legend-row [:span.legend-line {:style {:border-top "1.6px solid var(--p-indigo)"}}] "sync only"]
      [:div.legend-row [:span.legend-line.legend-self-dash] "self-loop"]]
     [:div.legend-group
      [:h5 "phases"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-amber)"}}] "initial"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper)"}}] "reached"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper3)"}}] "end"]]]
    [:button#shape-legend-reopen {:title "show legend" :on {:click [::legend-toggle true]}} "legend"]))

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

(defn- render-phase-graph! [atom-name]
  (when-let [container (js/document.getElementById "phase-graph-container")]
    (set! (.-innerHTML container) "")
    (when-let [atom (atom-shape-for-class (:shape @state) atom-name)]
      (let [SC (.-CxcShapeCanvas js/window)]
        (-> (.renderPhaseGraphInto SC container
                                   #js {:atom (clj->js atom)
                                        :elk  elk-instance})
            (.catch (fn [e]
                      (set! (.-textContent container)
                            (str "phase layout failed: " (.-message e))))))))))

(defn- atom-detail-pane [{:keys [detail-atom shape]}]
  (when (and detail-atom shape)
    (let [atom (atom-shape-for-class shape detail-atom)]
      [:section#atom-detail
       [:div.atom-detail-header
        [:span.atom-detail-name detail-atom]
        (when atom
          [:span.atom-detail-summary
           (str (count (:phases atom)) " phases · "
                (+ (count (:transitions atom))
                   (count (:self_transitions atom))) " arms · "
                (count (:observations atom)) " observations · initial "
                (or (:initial_phase atom) "?"))])
        [:button.btn {:on {:click [::close-atom-detail]}} "×"]]
       [:div#phase-graph-container.phase-graph-container]])))

(defn- popup-dialog [{:keys [popup]}]
  (when (:open? popup)
    (let [{:keys [kind target note editing-id attachments]} popup]
      [:div
       [:div#annotate-backdrop {:on {:click [::popup-close]}}]
       [:div#annotate-popup {:role "dialog" :aria-modal "true"}
        [:div.popup-title (if editing-id (str "edit " editing-id) "annotate")]
        [:div.popup-target "target: " [:span (schema/target-brief target)]]
        [:textarea#annotate-note
         {:value note
          :placeholder "describe what you noticed · ⌘+Enter sends · paste a URL or image to attach"
          :on {:input [::popup-set-note]
               :keydown [::popup-key]
               :paste [::popup-paste]}}]
        (when (seq attachments)
          [:div.popup-attachments
           (for [[i a] (map-indexed vector attachments)]
             [:div.popup-att {:data-ann-att i}
              (if (= "image" (:kind a))
                [:img {:src (:path a) :alt "attachment"}]
                [:span "🔗 " (:url a)])
              [:button.popup-att-remove {:on {:click [::popup-remove-att i]}} "×"]])])
        [:div.popup-actions
         [:span.popup-hint "⌘/Ctrl+Enter sends · ⌘/Ctrl+Shift+Enter saves draft"]
         [:button {:on {:click [::popup-close]}} "cancel"]
         [:button {:on {:click [::popup-submit :draft]}} "save draft"]
         [:button.primary {:on {:click [::popup-submit :send]}} "send"]]]])))

(defn- drafts-controls [{:keys [sidecar]}]
  (let [drafts (schema/filter-annotations (:annotations sidecar) {:status "draft"})]
    (when (seq drafts)
      [:div.drafts-controls
       [:span (str (count drafts) " draft(s)")]
       [:button.btn.primary {:on {:click [::drafts-submit-all]}}
        "submit review"]])))

(defn- view [{:keys [loading? events shape sidecar trace-name shape-name error layout] :as s}]
  [:div#app-root
   [:header
    [:h1 "cxc trace viewer"]
    (when (or trace-name shape-name)
      [:div.trace-name (or trace-name shape-name)])]
   (cond
     error     [:div.error (str "load failed: " error)]
     loading?  [:div.loading "loading…"]
     (and (empty? events) (nil? shape))
     [:div.empty
      [:p "no trace or shape loaded. open with ?trace=... or ?shape=... or drop one below."]
      [:div#drop {:role "button" :tabindex 0
                  :on   {:click    [::drop-click]
                         :dragover [::drop-over]
                         :drop     [::drop-file]}}
       "drop .trace.jsonl / .shape.json" [:br]
       [:span.drop-sub "(or click to browse)"]]
      [:input#file-picker {:type "file" :accept ".jsonl,.json,.log"
                           :style {:display "none"}
                           :on {:change [::file-picked]}}]]
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
           (str "max atom " atom-max " · max send " send-max)]])
       [:div#drop {:role      "button"
                   :tabindex  0
                   :on        {:click    [::drop-click]
                               :dragover [::drop-over]
                               :drop     [::drop-file]}}
        "drop .trace.jsonl / .shape.json" [:br]
        [:span.drop-sub "(or click to browse)"]]
       [:input#file-picker {:type "file" :accept ".jsonl,.json,.log"
                            :style {:display "none"}
                            :on {:change [::file-picked]}}]
       (filters-pane s)]
      [:div.shape-stage
       [:div#shape-container.shape-container]
       [:div#shape-zoom
        [:button {:on {:click [::zoom-out]} :title "zoom out"} "−"]
        [:span#shape-zoom-level "100%"]
        [:button {:on {:click [::zoom-in]} :title "zoom in"} "+"]
        [:button {:on {:click [::zoom-reset]} :title "reset zoom"} "⟲"]
        [:button {:on {:click [::zoom-fit]} :title "fit"} "fit"]]
       (legend-card (:legend-open? s))
       (transport-bar s)]
      (annotations-pane s)])
   (atom-detail-pane s)
   (popup-dialog s)])

;; ---------- render loop ----------

(def ^:private root (atom nil))

(defn- rerender! []
  (when-let [el @root]
    (rdom/render el (view @state))))

(defn- on-state-change [_k _r old-s new-s]
  (rerender!)
  (when (not= (:shape old-s) (:shape new-s))
    (js/setTimeout #(render-shape! new-s) 0))
  (when (and (:detail-atom new-s)
             (not= (:detail-atom old-s) (:detail-atom new-s)))
    (js/setTimeout #(render-phase-graph! (:detail-atom new-s)) 0)))

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

(defn- hydrate-trace-from-text! [name text]
  (let [my-gen (next-gen!)]
    (swap! state assoc :trace-name name :annotation-key name :error nil :loading? false)
    (commit! my-gen (fn [s] (assoc s
                              :events   (with-sender-cls (parse-jsonl text))
                              :playback {:cursor-seq nil :paused? true :speed 1})))))

(defn- hydrate-shape-from-text! [name text]
  (let [my-gen (:gen @state)]
    (swap! state assoc :shape-name name)
    (try
      (let [j (js/JSON.parse text)]
        (commit! my-gen #(assoc % :shape (js->clj j :keywordize-keys true))))
      (catch :default e
        (swap! state assoc :error (str "shape parse: " (.-message e)))))))

(defn- load-dropped-file! [file]
  (let [name (.-name file)
        rdr  (js/FileReader.)]
    (set! (.-onload rdr)
          (fn [_]
            (let [text (.-result rdr)]
              (cond
                (re-find #"\.shape\.json$" name)  (hydrate-shape-from-text! name text)
                (re-find #"\.(json)$" name)       (hydrate-shape-from-text! name text)
                :else                             (hydrate-trace-from-text! name text)))))
    (.readAsText rdr file)))

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

(defn- next-ann-id [existing-anns]
  (let [max-n (reduce (fn [m a]
                        (if-let [m2 (and (:id a) (re-matches #"ann_(\d+)" (:id a)))]
                          (max m (js/parseInt (nth m2 1) 10))
                          m))
                      0
                      existing-anns)]
    (str "ann_" (-> (inc max-n) str (.padStart 2 "0")))))

(defn- upload-image-blob!
  "POST a blob to /annotations-asset?trace=... and append the returned
   {path:..., kind:'image'} descriptor to the popup's :attachments."
  [blob]
  (let [k (:annotation-key @state)]
    (when k
      (-> (js/fetch (str "/annotations-asset?trace=" (js/encodeURIComponent k))
                    #js {:method  "POST"
                         :headers #js {"Content-Type"
                                       (or (.-type blob) "application/octet-stream")}
                         :body    blob})
          (.then (fn [r]
                   (if (.-ok r)
                     (.json r)
                     (throw (js/Error. (str "asset upload failed: HTTP " (.-status r)))))))
          (.then (fn [j]
                   (let [m (js->clj j :keywordize-keys true)]
                     (swap! state update-in [:popup :attachments]
                            (fn [a] (conj (vec a)
                                          (assoc m :kind "image")))))))
          (.catch (fn [e]
                    (swap! state assoc :error (.-message e))))))))

(defn- handle-popup-paste!
  "Walk the clipboard: image → upload+attach; otherwise a http(s) URL →
   attach as a link; otherwise fall through to the browser's default
   insert-text-into-textarea."
  [ev]
  (let [dt (.-clipboardData ev)
        items (and dt (.-items dt))
        img-item (when items
                   (first (for [i (range (.-length items))
                                :let [it (aget items i)]
                                :when (and (= "file" (.-kind it))
                                           (str/starts-with? (or (.-type it) "") "image/"))]
                            it)))]
    (if img-item
      (do (.preventDefault ev)
          (upload-image-blob! (.getAsFile img-item)))
      (let [text (and dt (.getData dt "text/plain"))]
        (when (and text (re-matches #"^https?://\S+$" (str/trim text)))
          (.preventDefault ev)
          (swap! state update-in [:popup :attachments]
                 (fn [a] (conj (vec a) {:kind "link" :url (str/trim text)}))))))))

(defn- open-popup!
  "Open the annotate popup. target is a map; kind is 'node' / 'event' /
   'range' / 'phase'. editing-id non-nil means edit mode."
  [{:keys [kind target editing-id prefill-note prefill-attachments] :as _args}]
  (swap! state assoc :popup
         {:open? true :kind (or kind "node") :target (or target {})
          :note (or prefill-note "")
          :editing-id editing-id
          :attachments (or prefill-attachments [])}))

(defn- close-popup! []
  (swap! state assoc-in [:popup :open?] false))

(defn- popup-submit!
  "mode = :send (status open, stamps submitted_at) or :draft (status draft).
   Edits preserve status unless we're sending a draft (then flip to open)."
  [mode]
  (let [{:keys [popup sidecar]} @state
        {:keys [kind target note editing-id attachments]} popup
        now (now-iso)
        send? (= mode :send)]
    (when (str/blank? note)
      (js/alert "write something")
      (throw (ex-info "empty note" {})))
    (let [anns (:annotations sidecar)
          anns'
          (if editing-id
            (mapv (fn [a]
                    (if (not= (:id a) editing-id) a
                        (let [cur-status (schema/effective-status a)
                              patch (cond-> {:note note :target target :kind kind
                                             :updated_at now :attachments attachments}
                                      (and send? (= "draft" cur-status))
                                      (assoc :status "open" :submitted_at now))]
                          (merge a patch))))
                  anns)
            (let [id (next-ann-id anns)
                  base {:id          id
                        :kind        kind
                        :target      target
                        :note        note
                        :attachments attachments
                        :author      "human"
                        :created_at  now
                        :status      (if send? "open" "draft")
                        :comments    []}]
              (conj (vec anns) (cond-> base send? (assoc :submitted_at now)))))]
      (swap! state assoc-in [:sidecar :annotations] anns')
      (save-sidecar!)
      (close-popup!))))

(defn- submit-all-drafts!
  "Bulk-flip every draft annotation to open with submitted_at=now."
  []
  (let [now (now-iso)]
    (swap! state update-in [:sidecar :annotations]
           (fn [anns]
             (mapv (fn [a]
                     (if (= "draft" (schema/effective-status a))
                       (merge a {:status "open" :submitted_at now})
                       a))
                   anns))))
  (save-sidecar!))

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
    ::zoom-in      (zoom-shape-centred! 1.25)
    ::zoom-out     (zoom-shape-centred! (/ 1 1.25))
    ::zoom-reset   (reset-shape-zoom!)
    ::zoom-fit     (fit-shape-view!)
    ::legend-toggle (let [open? (boolean (first args))]
                      (.setItem js/localStorage "cxc-shape-legend-hidden" (if open? "0" "1"))
                      (swap! state assoc :legend-open? open?))
    ::drop-click   (.click (js/document.getElementById "file-picker"))
    ::drop-over    (when-let [ev (:replicant/dom-event rep-data)] (.preventDefault ev))
    ::drop-file    (when-let [ev (:replicant/dom-event rep-data)]
                     (.preventDefault ev)
                     (when-let [files (-> ev .-dataTransfer .-files)]
                       (when (pos? (.-length files))
                         (load-dropped-file! (aget files 0)))))
    ::file-picked  (when-let [ev (:replicant/dom-event rep-data)]
                     (when-let [files (-> ev .-target .-files)]
                       (when (pos? (.-length files))
                         (load-dropped-file! (aget files 0)))))
    ::set-seq-range (when-let [v (dom-event-value rep-data)]
                      (swap! state assoc-in [:filters :seq-range] v))
    ::toggle-kind  (let [k (first args)]
                     (swap! state update-in [:filters :kinds-off]
                            (fn [s] (let [s (or s #{})]
                                      (if (contains? s k) (disj s k) (conj s k))))))
    ::toggle-atom  (let [a (first args)]
                     (swap! state update-in [:filters :atoms-off]
                            (fn [s] (let [s (or s #{})]
                                      (if (contains? s a) (disj s a) (conj s a))))))
    ::popup-open-blank
    (let [{:keys [events playback]} @state
          evs (events-for-playback events)
          cur (when (:cursor-seq playback)
                (first (filter #(= (:seq %) (:cursor-seq playback)) evs)))]
      (if cur
        (open-popup! {:kind "event"
                      :target {:trace_seq (:seq cur)
                               :event_kind (:kind cur)
                               :atom_cls (:target_cls cur)
                               :msg (:msg cur)}})
        (open-popup! {:kind "node" :target {}})))
    ::popup-open     (open-popup! (first args))
    ::popup-close    (close-popup!)
    ::popup-set-note (when-let [v (dom-event-value rep-data)]
                       (swap! state assoc-in [:popup :note] v))
    ::popup-key      (when-let [ev (:replicant/dom-event rep-data)]
                       (cond
                         (and (or (.-metaKey ev) (.-ctrlKey ev)) (= "Enter" (.-key ev)))
                         (do (.preventDefault ev)
                             (popup-submit! (if (.-shiftKey ev) :draft :send)))
                         (= "Escape" (.-key ev))
                         (do (.preventDefault ev) (close-popup!))))
    ::popup-submit   (popup-submit! (first args))
    ::popup-remove-att
    (let [i (first args)]
      (swap! state update-in [:popup :attachments]
             (fn [a] (vec (concat (subvec a 0 i) (subvec a (inc i)))))))
    ::popup-paste  (when-let [ev (:replicant/dom-event rep-data)]
                     (handle-popup-paste! ev))
    ::close-atom-detail (swap! state assoc :detail-atom nil)
    ::drafts-submit-all (submit-all-drafts!)
    ::popup-edit-draft
    (let [id (first args)
          ann (first (filter #(= (:id %) id) (:annotations (:sidecar @state))))]
      (when ann
        (open-popup! {:kind         (:kind ann)
                      :target       (:target ann)
                      :editing-id   id
                      :prefill-note (:note ann)
                      :prefill-attachments (:attachments ann)})))
    ::popup-send-draft
    (let [id (first args) now (now-iso)]
      (swap! state update-in [:sidecar :annotations]
             (fn [anns]
               (mapv (fn [a]
                       (if (= (:id a) id)
                         (merge a {:status "open" :submitted_at now})
                         a))
                     anns)))
      (save-sidecar!))
    ::delete-draft
    (let [id (first args)]
      (swap! state update-in [:sidecar :annotations]
             (fn [anns] (vec (remove #(= (:id %) id) anns))))
      (save-sidecar!))
    ::reply-open   (swap! state assoc-in [:ui :reply-open (first args)] true)
    ::reply-cancel (do (swap! state update-in [:ui :reply-open] dissoc (first args))
                       (swap! state update-in [:ui :reply-text] dissoc (first args)))
    ::reply-text   (let [id (first args) v (dom-event-value rep-data)]
                     (swap! state assoc-in [:ui :reply-text id] (or v "")))
    ::reply-key    (when-let [ev (:replicant/dom-event rep-data)]
                     (let [id (first args)]
                       (cond
                         (and (or (.-metaKey ev) (.-ctrlKey ev)) (= "Enter" (.-key ev)))
                         (do (.preventDefault ev)
                             (handle-event rep-data [::reply-send id]))
                         (= "Escape" (.-key ev))
                         (do (.preventDefault ev)
                             (handle-event rep-data [::reply-cancel id])))))
    ::reply-send   (let [id (first args)
                         body (or (get-in @state [:ui :reply-text id]) "")]
                     (when-not (str/blank? body)
                       (swap! state update-in [:sidecar :annotations]
                              (fn [anns]
                                (mapv (fn [a]
                                        (if (= (:id a) id)
                                          (update a :comments
                                                  (fn [cs] (conj (vec cs)
                                                                 {:author "human"
                                                                  :body   body
                                                                  :at     (now-iso)})))
                                          a))
                                      anns)))
                       (swap! state update-in [:ui :reply-open] dissoc id)
                       (swap! state update-in [:ui :reply-text] dissoc id)
                       (save-sidecar!)))
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
        (cond
          (and (= k "Escape") (get-in @state [:popup :open?]))
          (do (.preventDefault e) (close-popup!))
          (and (= k "Escape") (:detail-atom @state))
          (do (.preventDefault e) (swap! state assoc :detail-atom nil))
          (and (= k " ")
               (not (contains? #{"INPUT" "TEXTAREA" "SELECT"} tag))
               (seq (:events @state)))
          (do (.preventDefault e)
              (if (get-in @state [:playback :paused?]) (pt-play!) (pt-pause!)))))))
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
