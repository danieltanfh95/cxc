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
         shape-svg-el shape-fit-scale shape-view apply-shape-view! wire-shape-pan! highlight-shape! update-selection!
         global-key! popup-key! wire-document-drop! dump-layout! pt-pause! normalize-playback!
         drafts-controls open-popup!
         load-annotations! start-poll! next-ann-id
         class-resolver painter-context apply-heatmap! detail-pane toggle-sidebar!
         annotation-detail-pane annotation-table refresh-chat!
         open-annotation! resolution-submit! refresh-badges! popup-return-focus
         target-brief show-event! edge-descriptor focus-toolbar general-annotation
         focus-ready? focus-key apply-focus-from-url! modal-lifecycle! heat-element! heat-label!)

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
         :ann-browse (try (let [v (.getItem js/localStorage "cxc-ann-browse")]
                            (if v (update (js->clj (js/JSON.parse v) :keywordize-keys true) :statuses set)
                                {:query "" :statuses #{} :sort :id :descending? false}))
                         (catch :default _ {:query "" :statuses #{} :sort :id :descending? false}))
         :ann-selected #{}
         :filter-status  nil   ;; nil = all
         :error          nil
         :annotation-status :unknown
         :annotation-error nil
         :save-error     nil
         :saved-sidecar  nil
         :annotation-dirty? false
         :layout         nil
         ;; gaps 17–19: shared legacy preferences; URL overrides are applied at init.
         :sidebar-hidden? (= "1" (.getItem js/localStorage "cxc-sidebar-collapsed"))
         :layout-options {:algorithm (or (.getItem js/localStorage "cxc-shape-layout") "layered")
                          :direction (or (.getItem js/localStorage "cxc-shape-direction") "DOWN")
                          :showInit (= "1" (.getItem js/localStorage "cxc-shape-show-init"))}
         :playback       {:cursor-seq nil   ;; nil = at-start, number = current event seq
                          :paused?    true
                          :speed      1}
         :heatmap        {:on?       false
                          :per-atom  {}
                          :per-send  {}
                          :atom-max  0
                          :send-max  0}
         ;; gap 47: same persistent first-use dismissal as legacy.
         :hint-dismissed? (= "1" (.getItem js/localStorage "cxc-shape-hint-dismissed"))
         :legend-open?   (= "0" (or (.getItem js/localStorage "cxc-shape-legend-hidden") "1"))
         :filters        {:seq-range   ""
                          :msg-query   ""
                          :kinds-off   #{}
                          :atoms-off   #{}}
         :popup          {:open?       false
                          :target      {}
                          :kind        "node"
                          :note        ""
                          :editing-id  nil
                          :attachments []}
         :detail-height  (js/parseInt (or (.getItem js/localStorage "cxc-phase-pane-h") (str (* 0.45 (or (.-innerHeight js/window) 900)))) 10)
         :detail-atom    nil
         :chat-excerpts  {}}))     ;; token → {:status :loading/:loaded/:error, :hits [...]}

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
            (recur (inc i) last-arm (conj! out (assoc e :_inferredSenderCls last-arm)))

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
  [events {:keys [seq-range kinds-off atoms-off msg-query]}]
  (let [rng (parse-seq-range seq-range)]
    (vec (filter (fn [e]
                   (and (not (contains? kinds-off (:kind e)))
                        (not (contains? atoms-off (or (:target_cls e) (:atom_cls e))))
                        ;; gap 14: match legacy case-insensitive message substring.
                        (str/includes? (str/lower-case (or (:msg e) ""))
                                       (str/lower-case (or msg-query "")))
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
  (when-let [E (aget js/window "ELK")]
    (new E)))

(defonce ^:private shape-render-epoch (atom 0))

(defn- render-shape! [{:keys [shape events gen filters layout-options]} epoch]
  (when-let [container (js/document.getElementById "shape-container")]
    (let [current? #(and (= gen (:gen @state))
                        (= epoch @shape-render-epoch)
                        (identical? container (js/document.getElementById "shape-container")))
          SC (aget js/window "CxcShapeCanvas")]
      (when (and (current?) (or shape (seq events)))
        (-> ((aget SC "renderShapeInto") container
             #js {:shape (clj->js shape) :events (clj->js (filter-events events filters))
                  :elk elk-instance
                  :opts (clj->js layout-options)
                  :context (do (heat-rebuild-and-apply!) (painter-context)) :isCurrent current?})
            (.then (fn [res]
                     (when (and res (current?))
                       (commit! gen
                         #(assoc % :render-error nil :layout {:nodes (aget res "nodeCount")
                                            :edges (aget res "edgeCount")}))
                       (reset! shape-view nil)
                       (apply-shape-view!)
                       (wire-shape-pan!)
                       (heat-rebuild-and-apply!)
                       (refresh-badges!)
                       (update-selection!)
                       (dump-layout! res))))
            (.catch (fn [e]
                      (when (current?)
                        (commit! gen
                          #(assoc % :render-error (str "shape render: " (.-message e))))))))))))

;; gap 62: legacy geometry schema for layout-metrics.py and headless snapshots.
(defn- dump-layout! [res]
  (when (:layout-debug? @state)
    (let [svg (shape-svg-el) debug (aget res "debug")
          el (or (js/document.getElementById "layout-debug")
                 (let [el (js/document.createElement "script")]
                   (set! (.-id el) "layout-debug") (set! (.-type el) "application/json")
                   (.appendChild js/document.body el) el))]
      (aset debug "view" #js {:stageW (.-clientWidth svg) :stageH (.-clientHeight svg)
                              :scale (:scale @shape-view) :fit (shape-fit-scale svg)})
      (set! (.-textContent el) (js/JSON.stringify debug)))))

;; ---------- shape zoom + pan (viewBox-based) ----------
;;
;; shape-canvas sets data-content-{x,y,w,h} on the painted svg; we zoom by
;; shrinking the viewBox around a center point. View state lives outside the
;; replicant atom so a drag doesn't trigger per-frame rerenders — only the
;; zoom-level readout updates when zoom() runs, and that's cheap.

(defonce ^:private shape-view (atom nil))   ;; {:scale, :cx, :cy}
(defonce ^:private pan-wired? (atom nil))

(defn- shape-svg-el []
  (when-let [c (js/document.getElementById "shape-container")]
    (.querySelector c "svg")))

(defn- shape-content-box [svg]
  (let [d (.-dataset svg)
        x (js/parseFloat (aget d "contentX"))
        y (js/parseFloat (aget d "contentY"))
        w (js/parseFloat (aget d "contentW"))
        h (js/parseFloat (aget d "contentH"))]
    (if (and (every? js/Number.isFinite [x y w h]) (pos? w) (pos? h))
      {:x x :y y :w w :h h}
      {:x 0 :y 0 :w (max 1 (.-clientWidth svg)) :h (max 1 (.-clientHeight svg))})))

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
    (when (and (pos? (.-clientWidth svg)) (pos? (.-clientHeight svg)))
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
  (when-let [stage (js/document.getElementById "shape-container")]
    (when-not (identical? stage @pan-wired?)
      (reset! pan-wired? stage)
      (let [stage stage
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
                ;; gap 27: painter click/keyboard callbacks own graph activation.
                (and blk (.-shiftKey ev)) nil
                ;; Otherwise left-drag starts a pan on empty canvas.
                (and @shape-view (not (and t (.closest t ".pt-transport,#shape-zoom,#shape-legend,#atom-detail"))))
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
            ;; gap 52: keep the moved flag through the native click dispatch.
            (swap! state* assoc :active? false)
            (js/setTimeout #(swap! state* assoc :moved? false) 0))))
      (.addEventListener stage "click"
        (fn [ev] (when (:moved? @state*) (.preventDefault ev) (.stopImmediatePropagation ev))) true)
      (when (exists? js/ResizeObserver)
        (.observe (js/ResizeObserver. (fn [_] (apply-shape-view!))) stage))))))


;; gaps 21, 24, 27: painter callbacks share normalized descriptors.
(defn- atom-name [cls]
  (let [r (class-resolver (:shape @state) (:events @state))]
    (or (some-> ((aget r "atomShapeForClass") cls) (aget "name"))
        ((aget r "displayLabel") cls))))

 ;; gap 38: aliases and lifecycle edges precede their atom self-edge encoding.
(defn- target-brief [t]
  (let [n (or (:trace_seq t) (:seq t)) lo (or (:trace_seq_start t) (:from_seq t))
        hi (or (:trace_seq_end t) (:to_seq t)) src (or (:from_atom t) (:src t)) dst (or (:to_atom t) (:dst t))]
    (cond
      (some? n) (str "seq=" n (when (:event_kind t) (str " " (:event_kind t)))
                     (when (or (:atom_cls t) (:atom t)) (str " atom=" (or (:atom_cls t) (:atom t))))
                     (when (:msg t) (str " msg=" (:msg t))))
      (and (some? lo) (some? hi)) (str "seq=" lo ".." hi)
      (and (:atom t) (some? (:from t)) (some? (:to t)))
      (str "atom=" (:atom t) " " (:from t) "→" (:to t)
           (when (or (:on t) (:msg t)) (str " on " (or (:on t) (:msg t)))))
      (and src dst) (str src "→" dst (when-let [m (or (:via t) (:msg t) (:arm t))] (str " via " m)))
      :else (schema/target-brief t))))

(defn- event-descriptor [e]
  {:kind "event" :target {:trace_seq (:seq e) :event_kind (:kind e)
                          :atom (:target e) :atom_cls (atom-name (:target_cls e)) :msg (:msg e)}})

(defn- edge-descriptor
  ([e] (edge-descriptor e nil))
  ([e msg]
   {:kind "edge" :target {:from_atom (atom-name (aget e "src"))
                          :to_atom (atom-name (aget e "dst"))
                          :msg (or msg (str/join ", " (map first
                            (sort-by second > (js->clj (js/Array.from (.entries (aget e "msgs"))))))))}}))

(defn- wire-annotation! [el descriptor anchor annotation-only?]
  (.setAttribute el "data-ann-anchor" anchor)
  (let [open (fn [ev]
               (when (identical? el (.closest (.-target ev) "[data-ann-anchor]"))
                 (.preventDefault ev) (.stopImmediatePropagation ev)
                 (open-popup! (js->clj descriptor :keywordize-keys true))))]
    (.addEventListener el "click" #(when (.-shiftKey %) (open %)) true)
    (.addEventListener el "contextmenu" open true)
    (when annotation-only?
      (.setAttribute el "tabindex" "0") (.setAttribute el "role" "button")
      (.setAttribute el "aria-label" (str "Annotate " (target-brief (:target (js->clj descriptor :keywordize-keys true))))))
    (.addEventListener el "keydown"
      #(when (or (= "ContextMenu" (.-key %))
                 (and (.-shiftKey %) (= "F10" (.-key %)))
                 (and (or annotation-only? (.-shiftKey %)) (#{"Enter" " "} (.-key %))))
         (open %)) true)))

(defn- wire-keyboard! [el label]
  (.setAttribute el "tabindex" "0") (.setAttribute el "role" "button")
  (.setAttribute el "aria-label" label)
  (.addEventListener el "keydown"
    (fn [ev]
      (when (and (not (.-defaultPrevented ev)) (#{"Enter" " "} (.-key ev)))
        (.preventDefault ev) (.stopPropagation ev)
        (.dispatchEvent el (js/MouseEvent. "click" #js {:bubbles true :shiftKey (.-shiftKey ev)}))))))

;; gaps 49/50: hover and selection change classes, preserving playback effects.
(defn- highlight-shape! [ids]
  (when-let [svg (shape-svg-el)]
    (let [ids (if (array? ids) (set (array-seq ids)) (when ids #{ids}))]
      (doseq [el (array-seq (.querySelectorAll svg ".shape-block"))]
        (.toggle (.-classList el) "hover" (boolean (contains? ids (aget (.-dataset el) "nodeId"))))
        (.toggle (.-classList el) "dim" (boolean (and ids (not (contains? ids (aget (.-dataset el) "nodeId")))))))
      (doseq [el (array-seq (.querySelectorAll svg ".shape-edge"))]
        (.toggle (.-classList el) "dim" (boolean (and ids
          (not (contains? ids (aget (.-dataset el) "src"))) (not (contains? ids (aget (.-dataset el) "dst"))))))))))

(defn- update-selection! []
  (when-let [svg (shape-svg-el)]
    (doseq [el (array-seq (.querySelectorAll svg ".shape-block-halo-outer"))] (.remove el))
    (let [selected (some-> (:detail-atom @state) atom-name)
          blocks (array-seq (.querySelectorAll svg ".shape-block"))]
      (doseq [el blocks]
        (let [on? (= selected (atom-name (aget (.-dataset el) "nodeName")))]
          (.toggle (.-classList el) "selected" (boolean (and selected on?)))
          (.toggle (.-classList el) "dim-when-selected" (boolean (and selected (not on?))))
          (when (and selected on?)
            (let [b (.getBBox el) halo (js/document.createElementNS "http://www.w3.org/2000/svg" "rect")]
              (doseq [[k v] [["x" (- (.-x b) 6)] ["y" (- (.-y b) 6)]
                            ["width" (+ (.-width b) 12)] ["height" (+ (.-height b) 12)]
                            ["rx" 10] ["class" "shape-block-halo-outer"]]] (.setAttribute halo k v))
              (.appendChild svg halo)))))
      (when-let [el (first (filter #(and (= selected (atom-name (aget (.-dataset %) "nodeName")))
                                        (not (.contains (.-classList %) "container"))) blocks))]
        (when-let [v @shape-view]
          (let [b (.getBBox el) w (/ (.-clientWidth svg) (:scale v)) h (/ (.-clientHeight svg) (:scale v))]
            (when-not (and (>= (.-x b) (- (:cx v) (/ w 2))) (<= (+ (.-x b) (.-width b)) (+ (:cx v) (/ w 2)))
                           (>= (.-y b) (- (:cy v) (/ h 2))) (<= (+ (.-y b) (.-height b)) (+ (:cy v) (/ h 2))))
              (swap! shape-view assoc :cx (+ (.-x b) (/ (.-width b) 2)) :cy (+ (.-y b) (/ (.-height b) 2)))
              (apply-shape-view!))))))))

(defn- painter-context []
  #js {:selectedNode (:detail-atom @state) :highlight highlight-shape!
       :selectNode (fn [n] (swap! state assoc :detail nil :focus nil :detail-atom
                                (or (some-> (aget n "shape") (aget "name")) (aget n "cls") (aget n "id"))))
       :openEdge (fn [e rev] (swap! state assoc :detail-atom nil
                                  :focus (edge-descriptor e)
                                  :detail {:kind :edge :directions (vec (remove nil? [e rev]))}))
       :wireAnnotation wire-annotation! :wireKeyboardClick wire-keyboard!
       :heatOn (get-in @state [:heatmap :on?])
       :heatAtomCount #(get-in @state [:heatmap :per-atom (or (some-> (aget % "shape") (aget "name")) (atom-name (aget % "cls")))] 0)
       :heatElement (fn [el c stroke?] (heat-element! el c stroke? (get-in @state [:heatmap :atom-max])))
       :heatLabel heat-label!
       :heatEdgeElement (fn [el e] (heat-element! el (get-in @state [:heatmap :per-send (str (aget e "src") "|" (aget e "dst"))] 0)
                                                 false (get-in @state [:heatmap :send-max])))
       :atomName atom-name :edgeTarget #(clj->js (:target (edge-descriptor %)))})

;; gap 15: FIFO send closure and legacy six-event silent-drop window.
(defn- diagnostics [events]
  (loop [i 0 pending {} unclosed [] drops []]
    (if (= i (count events))
      {:unclosed (vec (mapcat #(get pending %) unclosed))
       :silent-drops (vec (sort-by (fn [e] [(if (re-find #"Clock" (str (:msg e) (:target_cls e))) 1 0) (:seq e)]) drops))}
      (let [e (nth events i) k [(:target e) (:msg e)] kind (:kind e)
            recv? (#{"recv" "recv_sync"} kind)
            base (last (str/split (or (:msg e) "") #"\."))
            hit? (some #(and (= "arm" (:kind %)) (= (:target %) (:target e))
                             (or (= (first (str/split (or (:msg %) "") #"[.:With]")) base)
                                 (str/starts-with? (or (:msg %) "") base)))
                       (subvec events (inc i) (min (count events) (+ i 7))))]
        (recur (inc i)
               (cond (#{"send" "send_sync"} kind) (update pending k (fnil conj []) e)
                     recv? (update pending k #(vec (rest %))) :else pending)
               (if (and (#{"send" "send_sync"} kind) (not (contains? pending k))) (conj unclosed k) unclosed)
               (if (and recv? (not (str/starts-with? (or (:msg e) "") "self.cxc_emit_")) (not hit?))
                 (conj drops e) drops))))))

(defn- show-event! [e]
  (when e
    (swap! state assoc :detail-atom nil :focus (event-descriptor e) :detail {:kind :event :event e})
    (swap! state assoc-in [:playback :cursor-seq] (:seq e))))

(defn- diagnostics-pane [events secondary-stats]
  [:details.diagnostics [:summary "diagnostics"]
   secondary-stats
   (when (seq events)
    (let [{:keys [unclosed silent-drops]} (diagnostics events)]
       (if (and (empty? unclosed) (empty? silent-drops))
         [:p "all sends closed ✓"]
         (for [[label es] [["unclosed sends" unclosed] ["silent drops" silent-drops]] :when (seq es)]
           [:div [:p (str label ": " (count es))]
            [:div.diagnostic-list
             (for [e (take 20 es)]
               [:div [:button.btn {:on {:click [::inspect-event e]}} (str "seq " (:seq e) ": " (:msg e))]
                [:button.btn {:on {:click [::popup-open (event-descriptor e)]}} "+ annotate"]])
             (when (> (count es) 20) [:p (str "…" (- (count es) 20) " more")])]]))))])

;; gaps 25/26: typed bounds become actual event endpoints; focus precedes cursor.
(defn- selected-range [s]
  (when-let [[lo hi] (parse-seq-range (get-in s [:filters :seq-range]))]
    (when (<= lo hi)
      (let [run (filter #(<= lo (:seq %) hi) (:events s))]
        (when (seq run)
          {:kind "range" :target {:trace_seq_start (:seq (first run)) :trace_seq_end (:seq (last run))}})))))

(defn- general-annotation [s]
  (or (selected-range s) (:focus s)
      (when (:detail-atom s) {:kind "node" :target {:atom (atom-name (:detail-atom s))}})
      (let [evs (events-for-playback (:events s) (:filters s))]
        (when (seq evs) (event-descriptor (nth evs (cursor-index evs (get-in s [:playback :cursor-seq]))))))
      {:kind "node" :target {}}))

(defn- toggle-sidebar! []
  (let [hidden? (not (:sidebar-hidden? @state))]
    (.setItem js/localStorage "cxc-sidebar-collapsed" (if hidden? "1" "0"))
    (.toggle (.-classList js/document.body) "hide-sidebar" hidden?)
    (swap! state assoc :sidebar-hidden? hidden?)
    (js/setTimeout apply-shape-view! 0)))

;; ---------- heatmap ----------
;;
;; Count events per atom and per send-direction. Overlay colour on the
;; shape SVG after shape-canvas has painted (classList + CSS vars; body
;; carries the `heatmap-on` toggle so the shared CSS kicks in).
;;
;; gap 31: phase and transition counts share the loaded trace's atom scale.

(defonce ^:private resolver-cache (atom nil))
(defn- class-resolver [shape events]
  ;; gaps 21/22/38: reuse the same name map throughout one artifact snapshot.
  (let [cached @resolver-cache]
    (if (and (identical? shape (:shape cached)) (identical? events (:events cached)) (:value cached))
      (:value cached)
      (let [value ((aget (aget js/window "CxcShapeCanvas") "classResolver") (clj->js shape) (clj->js events))]
        (reset! resolver-cache {:shape shape :events events :value value})
        value))))

(defn- atom-shape-for-class [shape cls]
  (when cls
    (js->clj ((aget (class-resolver shape (:events @state)) "atomShapeForClass") cls)
             :keywordize-keys true)))

(defn- heat-recompute
  "Return the heat counts for the current events + shape. Per-atom is a
   straight frequency over target_cls / atom_cls. Per-send reuses
   CxcShapeCanvas.buildGraph + .topology so sender inference stays
   aligned with the painted SVG."
  [events shape]
  (let [resolver (class-resolver shape events)
        resolve-atom (aget resolver "atomShapeForClass")
        display-name (aget resolver "displayLabel")
        per-atom (persistent!
                   (reduce
                     (fn [m e]
                       (let [cls  (or (:target_cls e) (:atom_cls e) (:atom e))
                             nm   (when cls (or (some-> (resolve-atom cls) (aget "name"))
                                               (display-name cls)))]
                         (if nm (assoc! m nm (inc (get m nm 0))) m)))
                     (transient {})
                     events))
        atom-max (if (seq per-atom) (apply max 0 (vals per-atom)) 0)
        SC       (aget js/window "CxcShapeCanvas")
        per-send (if (and SC (seq events))
                   (let [disp     (fn [id] (if (str/includes? id ".")
                                             (last (str/split id #"\."))
                                             id))
                         graph    ((aget SC "buildGraph")
                                   (clj->js shape) true (clj->js events)
                                   disp
                                   (fn [m] (or m "")))
                         topo-res ((aget SC "topology")
                                   (aget graph "nodes") (aget graph "edges")
                                   (clj->js shape)
                                   resolve-atom
                                   true)
                         edges    (aget topo-res "edges")]
                     (into {}
                           (for [i (range (.-length edges))]
                             (let [e (aget edges i)]
                               [(str (aget e "src") "|" (aget e "dst"))
                                (aget e "total")]))))
                   {})
        send-max (if (seq per-send) (apply max 0 (vals per-send)) 0)]
    {:on?      false
     :per-atom per-atom
     :per-send per-send
     :atom-max atom-max
     :send-max send-max
     :phase-counts (js->clj ((aget SC "phaseHeat") (clj->js shape) (clj->js events)) :keywordize-keys false)}))

(defn- heat-color-str [count max-count]
  (let [ratio (if (pos? max-count)
                (min 1.0 (/ count max-count))
                0.0)]
    (str "color-mix(in srgb, var(--p-bg), var(--p-accent) "
         (.toFixed (* ratio 100) 3) "%)")))

(defn- shape-svg []
  (when-let [c (js/document.getElementById "shape-container")]
    (.querySelector c "svg")))

 ;; gaps 29/30/31: protected external direction fields, shared heat scale.
(defn- heat-element! [el hits stroke? max-count]
  (.add (.-classList el) (if stroke? "heat-stroke" "heat-fill"))
  (.setProperty (.-style el) "--heat-color" (heat-color-str hits max-count))
  (.setProperty (.-style el) "--heat-ink"
                (str "color-mix(in srgb, var(--p-ink), #22160A "
                     (if (pos? max-count) (* 100 (min 1 (/ hits max-count))) 0) "%)"))
  (.setAttribute el "data-heat-count" (str hits))
  (when (get-in @state [:heatmap :on?])
    (let [tip (or (.querySelector el "title")
                  (let [t (.createElementNS js/document "http://www.w3.org/2000/svg" "title")] (.appendChild el t) t))
          base (or (aget (.-dataset tip) "heatBase") (.-textContent tip) "")
          total (count (:events @state))]
      (aset (.-dataset tip) "heatBase" base)
      (set! (.-textContent tip) (str base "\n" hits " events · "
        (.toFixed (if (pos? total) (* 100 (/ hits total)) 0) 1) "% of trace")))))

(defn- heat-label! [el heated]
  (.add (.-classList el) "heat-label")
  (.setProperty (.-style el) "--heat-ink" (.getPropertyValue (.-style heated) "--heat-ink")))

(defn- apply-heatmap! []
  (.toggle (.-classList js/document.body) "heatmap-on" (get-in @state [:heatmap :on?])))

(defn- refresh-badges! []
  (when-let [svg (shape-svg)]
    ((aget (aget js/window "CxcShapeCanvas") "renderAnnotationBadges") svg
     (clj->js (map #(assoc % :effective_status (schema/effective-status %)) (get-in @state [:sidecar :annotations])))
     (class-resolver (:shape @state) (:events @state)) (painter-context))))

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
    (let [src-id (:_inferredSenderCls e)
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
                        (cond
                          (or (empty? evs) (>= i cap))
                          (do (swap! state assoc-in [:playback :cursor-seq] (some-> evs last :seq))
                              (when (and (= 1 (count evs)) (nil? cursor-seq)) (pt-flash-event! (first evs) true))
                              (pt-pause!))
                          (nil? last-tick)
                          (do (swap! state assoc-in [:playback :last-tick] now)
                              (reset! pt-raf-handle (js/requestAnimationFrame tick)))
                          :else (let [step-ms (/ 1000 (* 60 (or speed 1)))
                                dt      (- now last-tick)]
                            (if (and (>= dt step-ms) (< i cap))
                              (let [n-steps (max 1 (js/Math.floor (/ dt step-ms)))
                                    i2      (min cap (+ i n-steps))
                                    e2      (nth evs i2)]
                                (swap! state update :playback assoc
                                       :cursor-seq (:seq e2)
                                       :last-tick  now)
                                ;; gap 43: flash every advanced event, including the initial cursor.
                                (doseq [e (subvec evs (if (nil? cursor-seq) 0 (inc i)) (inc i2))]
                                  (when (#{"send" "send_sync" "arm" "no_arm"} (:kind e))
                                    (pt-flash-event! e true)))
                                (if (>= i2 cap)
                                  (pt-pause!)
                                  (reset! pt-raf-handle (js/requestAnimationFrame tick))))
                              (reset! pt-raf-handle (js/requestAnimationFrame tick)))))))))]
          (reset! pt-raf-handle (js/requestAnimationFrame tick)))))))

;; gap 63: keep a narrowed seq-keyed domain finite, including empty/one-event cases.
(defn- normalize-playback! []
  (let [evs (events-for-playback (:events @state)) cursor (get-in @state [:playback :cursor-seq])
        e (when (seq evs) (nth evs (cursor-index evs cursor)))]
    (when (some? cursor) (swap! state assoc-in [:playback :cursor-seq] (:seq e)))
    (when (or (empty? evs) (= 1 (count evs)) (and cursor e (= (:seq e) (:seq (last evs))))) (pt-pause!))))

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

;; gap 51: equivalent secondary anchors for HTML labels and review rows.
(defn- annotation-anchor [descriptor]
  {:click [::annotate-anchor descriptor] :contextmenu [::annotate-anchor descriptor]
   :keydown [::annotate-anchor descriptor]})

(defn- filters-pane [{:keys [events filters]}]
  (let [kinds (sort (into #{} (keep :kind events)))
        atoms (sort (into #{} (keep #(or (:target_cls %) (:atom_cls %)) events)))
        {:keys [kinds-off atoms-off]} (or filters {})
        kinds-off (or kinds-off #{})
        atoms-off (or atoms-off #{})]
    [:details.filters
     [:summary "filters"]
     [:div.filter-group
      [:label.filter-label "msg contains "
       [:input.filter-input {:value (or (:msg-query filters) "") :on {:input [::set-msg-query]}}]]]
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
          [:label.filter {:data-atom a :on (annotation-anchor {:kind "node" :target {:atom (atom-name a)}})}
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
                                  "mark verified"]
                                 [:button.btn.wontfix {:on {:click [::transition id "wontfix"]}} "wontfix"]]
      :else                     [:div.ann-actions
                                 [:button.btn.addressed
                                  {:on {:click [::transition id "addressed"]}}
                                  "addressed"]
                                 [:button.btn.wontfix
                                  {:on {:click [::transition id "wontfix"]}}
                                  "wontfix"]])))

(defn- resolve-chat-excerpt!
  "Fetch /chat-excerpt?token=X once per token; cache status + hits in state."
  ([token] (resolve-chat-excerpt! token false))
  ([token retry?]
  (let [cur (get-in @state [:chat-excerpts token])]
    (when (or (nil? cur) (and retry? (not= :loading (:status cur))
                                            (or (= :error (:status cur)) (empty? (:hits cur)))))
      (swap! state assoc-in [:chat-excerpts token] {:status :loading})
      (-> (js/fetch (str "/chat-excerpt?token=" (js/encodeURIComponent token)))
          (.then (fn [r]
                   (if (.-ok r)
                     (.json r)
                     (throw (js/Error. (str "HTTP " (.-status r)))))))
          (.then (fn [j]
                   (let [hits (js->clj j :keywordize-keys true)]
                     (swap! state assoc-in [:chat-excerpts token]
                            {:status :loaded :hits hits}))))
          (.catch (fn [e]
                    (swap! state assoc-in [:chat-excerpts token]
                           {:status :error :message (.-message e)}))))))))

(defn- chat-excerpt-view [token]
  (let [cur (get-in @state [:chat-excerpts token])]
    (cond
      (nil? cur)              [:div.chat-excerpt-loading "resolving chat excerpt from the agent…"]
      (= :loading (:status cur)) [:div.chat-excerpt-loading "resolving chat excerpt from the agent…"]
      (= :error (:status cur))   [:div.chat-excerpt-error (str "chat lookup failed: " (:message cur))]
      :else (let [hits (:hits cur) expanded? (get-in @state [:ui :excerpt-expanded token])
                         compact? (or (> (count hits) 1) (> (reduce + 0 (map #(count (:text %)) hits)) 200)
                                      (some #(> (count (str/split-lines (:text %))) 5) hits))]
              (if (empty? hits)
                [:div.chat-excerpt-loading (str "waiting for agent reply (token " token ")…")]
                [:div
                 [:div.chat-excerpt-body {:class (str (when compact? "compact ") (when expanded? "expanded"))}
                 (for [h hits]
                   [:div.chat-excerpt-hit
                    [:div.chat-excerpt-meta (str (:timestamp h) " · " (:session h))]
                    [:div.chat-excerpt-text {:title (:text h)} (:text h)]])]
                 (when compact?
                   [:button.btn.chat-excerpt-toggle {:aria-expanded (boolean expanded?)
                     :on {:click [::excerpt-toggle token]}} (if expanded? "show less" "show more")])])))))

(defn- comment-item [{:keys [by text at kind token linked_commit commit]}]
  (if (and (= "pending" kind) token)
    (do (resolve-chat-excerpt! token)
        [:li.ann-comment
         [:div
          [:span.c-author (or by "?")] " "
          [:span.c-at at] (when (or linked_commit commit) [:span.c-commit (str " · " (or linked_commit commit))]) " · pending · token:" token]
         (chat-excerpt-view token)
         [:button.btn {:on {:click [::chat-refresh token]}} "refresh excerpt"]])
    [:li.ann-comment
     [:span.c-author (or by "?")] " "
     [:span.c-at at] (when (or linked_commit commit) [:span.c-commit (str " · " (or linked_commit commit))]) [:br]
     [:span.c-body text]]))

(defn- comments-pane [{:keys [id comments]}]
  (let [replying? (get-in @state [:ui :reply-open id])
        draft    (get-in @state [:ui :reply-text id] "")]
    [:div.ann-comments
     (when (seq comments)
       [:ul.ann-comments-list
        (for [c comments] (comment-item c))])
     (if replying?
       [:div.ann-reply
        [:textarea {:value draft :placeholder "reply · ⌘+Enter sends · Esc cancels"
                    :on {:input [::reply-text id]
                         :keydown [::reply-key id]}}]
        [:div.reply-actions
         [:button.btn {:on {:click [::reply-cancel id]}} "cancel"]
         [:button.btn.primary {:disabled (get-in @state [:ui :reply-saving id])
                              :on {:click [::reply-send id]}} "reply"]]]
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
                  :data-severity severity :tabindex 0
                  :aria-label (str "View " id ". Shift-click or right-click to annotate this target.")
                  :on (merge (annotation-anchor (select-keys ann [:kind :target]))
                             {:click [::ann-row-click id (select-keys ann [:kind :target])]
                              :keydown [::ann-row-key id (select-keys ann [:kind :target])]})}
     [:div.ann-head
      [:button.btn.ann-id {:on {:click [::ann-open id]}} id]
      [:span.ann-status status]
      [:span.ann-sev severity]]
     [:div.ann-target (target-brief (:target ann))]
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
     [:details.ann-section {:open true}
     [:summary.ann-header
      [:span.title "annotations"]
      (when (or (seq all) (#{:unavailable :loading} (:annotation-status s)))
        [:span.ann-count (case (:annotation-status s)
                         :unavailable "server unavailable"
                         :loading "loading…"
                         (str (count shown) " of " (count all)))])
      [:button.btn.ann-table-toggle {:title "Browse all annotations in a table"
                                    :aria-pressed (= :table (get-in s [:detail :kind]))
                                    :on {:click [::ann-table-open]}} "⊞ table"]]
     (when (#{:unavailable :loading} (:annotation-status s))
       [:div.ann-unavailable {:role "status"}
        (if (= :loading (:annotation-status s)) "loading annotations…"
          (str "annotation server unavailable · " (:annotation-error s)))
        [:button.btn {:on {:click [::annotations-retry]}} "retry connection"]])
     (when (seq all)
       [:div.ann-filters
      (for [status (cons nil (sort schema/statuses))]
        [:button.ann-filter
         {:data-status (or status "all")
          :data-active (= status filter-status)
          :on {:click [::set-filter status]}}
         (if status
           (str status " (" (or (counts status) 0) ")")
           "all")])])
     [:ul.ann-list
      (if (empty? shown)
        [:li.ann-empty (when-not (#{:unavailable :loading} (:annotation-status s))
                         (if (seq all) "no annotations"
                           "none yet · click an atom, edge, or event, then \"+ annotate\""))]
        (for [status (if filter-status [filter-status] ["draft" "open" "in_progress" "addressed" "verified" "wontfix" "stale"])
              :let [group (filter #(= status (schema/effective-status %)) shown)] :when (seq group)]
          [:li.ann-status-group {:data-ann-status status}
           [:h4.ann-status-heading (str (str/replace status "_" " ") " (" (count group) ")")]
           [:ul.ann-list (map annotation-row group)]]))]
     [:div.annotation-controls
      [:button.btn.primary {:disabled (#{:unavailable :loading} (:annotation-status s)) :on {:click [::popup-open-blank]}} "+ annotate"]
      [:button.btn {:on {:click [::ann-export]}} "export"]]
     (drafts-controls s)]]))


;; gaps 32/33: combined status filtering, search, sort and range selection.
(defn- browse-annotations [s]
  (let [{:keys [query statuses sort descending?]} (:ann-browse s)
        key (keyword (or sort :id)) q (str/lower-case (or query ""))]
    (cond->> (:annotations (:sidecar s))
      true (filter #(and (or (empty? statuses) (contains? statuses (schema/effective-status %)))
                         (str/includes? (str/lower-case (str (:id %) " " (target-brief (:target %)) " "
                           (:note %) " " (:severity %) " " (:kind %) " " (:author %) " " (:proposed_fix %) " " (:resolution %))) q)))
      true (sort-by #(case key :status (schema/effective-status %) :target (target-brief (:target %))
                           :severity (or (:severity %) "") (or (get % key) "")))
      descending? reverse)))

(defn- set-browse! [f]
  (swap! state update :ann-browse f)
  (.setItem js/localStorage "cxc-ann-browse" (js/JSON.stringify (clj->js (:ann-browse @state)))))

(defn- annotation-table [s]
  (let [shown (vec (browse-annotations s)) selected (:ann-selected s)
        eligible (filter #(and (contains? selected (:id %)) (= "addressed" (schema/effective-status %)))
                         (:annotations (:sidecar s)))]
    [:div.ann-table-pane
     [:div.ann-top-actions
      [:button.btn {:on {:click [::ann-back]}} "back"]
      [:label "search " [:input {:value (get-in s [:ann-browse :query]) :on {:input [::ann-search]}}]]
      [:button.btn {:disabled (empty? eligible) :on {:click [::ann-verify-selected]}}
       (str "verify selected (" (count eligible) ")")]]
     [:div.ann-filters
      (for [status (sort schema/statuses)]
        [:label [:input {:type "checkbox" :checked (contains? (get-in s [:ann-browse :statuses]) status)
                        :on {:change [::ann-status-filter status]}}] status])]
     [:table.message-table.ann-table
      [:thead [:tr
       [:th [:input {:type "checkbox" :aria-label "Select all visible annotations"
                     :checked (and (seq shown) (every? #(contains? selected (:id %)) shown))
                     :on {:change [::ann-select-all]}}]]
       (for [k [:id :status :severity :kind :target :note :author :created_at]]
         [:th [:button.btn {:on {:click [::ann-sort k]}} (name k)]])]]
      [:tbody (for [a shown]
        [:tr {:data-ann-id (:id a)}
         [:td [:input {:type "checkbox" :checked (contains? selected (:id a)) :aria-label (str "Select " (:id a))
                       :on {:click [::ann-select (:id a)]}}]]
         [:td [:button.btn {:on {:click [::ann-open (:id a)]}} (:id a)]]
         [:td (schema/effective-status a)] [:td (:severity a)] [:td (:kind a)]
         [:td (target-brief (:target a))] [:td (:note a)] [:td (:author a)] [:td (:created_at a)]])]]]))

(defn- refresh-chat! [ann]
  (doseq [c (:comments ann) :when (and (= "pending" (:kind c)) (:token c))]
    (resolve-chat-excerpt! (:token c) true)))

(defn- open-annotation! [id]
  (let [s @state a (first (filter #(= id (:id %)) (get-in s [:sidecar :annotations])))]
    (when a
      (swap! state assoc :detail-atom nil :focus (select-keys a [:kind :target]) :detail {:kind :annotation :id id}
             :ann-return (select-keys s [:detail :detail-atom :focus]))
      (refresh-chat! a))))

;; gaps 34/35: full review and saved attachments, including metadata.
(defn- annotation-detail-pane [s id]
  (when-let [a (first (filter #(= id (:id %)) (get-in s [:sidecar :annotations])))]
    [:div.ann-full-detail
     [:button.btn {:on {:click [::ann-back]}} "back"]
     [:h3 (str id " · " (schema/effective-status a) " · " (:severity a))]
     [:p (target-brief (:target a))]
     (focus-toolbar (select-keys a [:kind :target]))
     [:div.ann-full-note {:style {:white-space "pre-wrap"}} (:note a)]
     (when (:proposed_fix a) [:div [:h4 "proposed fix"] [:pre.raw-event (:proposed_fix a)]])
     [:div.saved-attachments
      (for [att (:attachments a)]
        (if (= "image" (:kind att))
          [:a {:href (:path att) :target "_blank" :rel "noopener"}
           [:img {:src (:path att) :alt (or (:title att) "attachment")}]]
          [:a {:href (:url att) :target "_blank" :rel "noopener"} (or (:title att) (:url att))]))]
     (when (:resolution a) [:div [:h4 "resolution"] [:pre.raw-event (js/JSON.stringify (clj->js (:resolution a)) nil 2)]])
     [:p (str "by " (:author a) " · created " (:created_at a) " · updated " (:updated_at a))]
     (when (:commit a) [:p (str "commit " (:commit a))])
     (when (:verified_by a) [:p (str "verified by " (:verified_by a) " · " (:verified_at a))])
     (if (= "draft" (schema/effective-status a)) (draft-actions a) (transition-buttons a))
     (comments-pane a)]))

;; gap 37: LLM-ready open-review export, preserving full note/fix text.
(defn- annotation-export [s]
  (str/join "\n"
    (concat ["### Trace annotations from the human reviewer" ""]
      (when-let [c (get-in s [:sidecar :commit])] (when (seq c) [(str "Commit under review: " c)]))
      [(str (if (seq (:events s)) "Trace artifact: " "Shape artifact: ")
            (or (get-in s [:sidecar :source]) (:trace-name s) (:shape-name s))) ""]
      (mapcat (fn [a] [(str "**" (:id a) " (" (str/join ", " (remove nil? [(:severity a) (:kind a)])) ")** — target: "
                           (target-brief (:target a)))
                       (str "Note: " (:note a))
                       (when (:proposed_fix a) (str "Proposed fix: " (:proposed_fix a))) ""])
              (filter #(= "open" (schema/effective-status %)) (get-in s [:sidecar :annotations]))))))

(defn- copy-text! [text]
  (if-let [clip (.-clipboard js/navigator)]
    (-> (.writeText clip text) (.catch (fn [_] (js/prompt "Copy text" text))))
    (js/prompt "Copy text" text)))

(defn- legend-card [open?]
  (if open?
    [:div#shape-legend
     [:button#shape-legend-close {:aria-label "Hide legend" :title "hide legend" :on {:click [::legend-toggle false]}} "×"]
     [:div.legend-group
      [:h5 "atoms"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-sepia)"}}] "main"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper)"}}] "user atom"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper3)"}}] "init / end"]]
     [:div.legend-group
      [:h5 "edges"]
      [:div.legend-row [:span.legend-line {:style {:border-top "1.6px solid var(--p-ink)"}}] "async send"]
      [:div.legend-row [:span.legend-line {:style {:border-top "1.6px solid var(--p-indigo)"}}] "sync only"]
      [:div.legend-row [:span.legend-line.legend-self-dash] "self-loop"]
      [:div.legend-row {:title "Two-way atoms share a line with an arrowhead at each end; each label arrow shows its message direction."} "▲▼ label arrow = direction"]
      [:div.legend-row {:title "The top message plus N other names in this direction. Hover the label for the full list."} "+N = more messages"]]
     [:div.legend-group
      [:h5 "phases"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-amber)"}}] "initial"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper)"}}] "reached"]
      [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper3)"}}] "end"]
      [:div.legend-row {:title "Not reachable from the initial phase through declared transitions."} "dashed = unreached"]
      [:div.legend-row {:title "N declared arms leave the phase unchanged."} "self N = N self-transitions"]]
     [:div.legend-group [:h5 "ownership"] [:div.legend-row "box = owner holds atoms in non-ctor fields"]]
     [:div.legend-group [:h5 "heatmap"]
      [:div.legend-heat-ramp {:aria-label "Heat scale: low to high activity"}]
      [:div.legend-row "normalized to the whole trace maximum"]]]
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
         [:button.icon-button {:on {:click [::pt-rewind]} :aria-label "Jump to start" :title "jump to start"} "⏮"]
         [:button.icon-button {:on {:click [::pt-step-back]} :aria-label "Step back" :title "step back"} "←"]
         [:button.pt-play {:on {:click [::pt-toggle]} :aria-label "Play or pause" :title "play / pause"}
          (if paused? "PLAY" "PAUSE")]
         [:button.icon-button {:on {:click [::pt-step-fwd]} :aria-label "Step forward" :title "step forward"} "→"]
         [:input {:aria-label "Playback position" :type       "range"
                  :min        "0"
                  :max        (str (dec total))
                  :value      (str i)
                  :on         {:input [::pt-scrub]}}]
         [:select {:aria-label "Playback speed" :value (str speed)
                   :on    {:change [::pt-set-speed]}}
          [:option {:value "1"}   "1×"]
          [:option {:value "5"}   "5×"]
          [:option {:value "20"}  "20×"]
          [:option {:value "100"} "100×"]]
         [:span.pt-status {:role "button" :tabindex 0 :on {:click [::inspect-event e] :keydown [::inspect-event-key e]}}
          [:b (str "seq " seq)]
          " · " msg
          [:span.pt-count (str "(" (inc i) "/" total ")")]]]))))

(defonce ^:private phase-render-epoch (atom 0))
(defn- render-phase-graph! [atom-name]
  (when-let [container (js/document.getElementById "phase-graph-container")]
    (set! (.-innerHTML container) "")
    (when-let [atom (atom-shape-for-class (:shape @state) atom-name)]
      (let [SC (aget js/window "CxcShapeCanvas")
            gen (:gen @state) epoch (swap! phase-render-epoch inc)
            current? #(and (= epoch @phase-render-epoch) (= gen (:gen @state)) (= atom-name (:detail-atom @state))
                           (identical? container (js/document.getElementById "phase-graph-container")))]
        (-> ((aget SC "renderPhaseGraphInto") container
             #js {:atom (clj->js atom) :elk elk-instance :isCurrent current?
                  :context #js {:wireAnnotation wire-annotation! :heatOn (get-in @state [:heatmap :on?])
                    :heatElement (fn [el c stroke?] (heat-element! el c stroke? (get-in @state [:heatmap :atom-max])))
                    :heatLabel heat-label!
                    :phaseCount #(get-in @state [:heatmap :phase-counts "phases" (js/JSON.stringify #js [(:name atom) %])] 0)
                    :transitionCount #(get-in @state [:heatmap :phase-counts "transitions" (js/JSON.stringify #js [(:name atom) (aget % "from") (aget % "to") (aget % "msg")])] 0)}})
            (.catch (fn [e]
                      (when (current?)
                        (set! (.-textContent container)
                              (str "phase layout failed: " (.-message e)))))))))))


;; gaps 16/21/22/39: one focus toolbar for every inspected primitive.
(defn- focus-toolbar [descriptor]
  [:div.focus-toolbar
   [:button.btn {:on {:click [::popup-open descriptor]}} "+ annotate"]
   [:button.btn {:on {:click [::copy-focus-link descriptor]}} "⎘ copy link"]])

(defn- traffic-table [title entries descriptor-fn]
  [:div [:h4 title]
   [:table.message-table [:thead [:tr [:th "message"] [:th "count"]]]
    [:tbody (for [[msg c] (sort-by (fn [[m c]] [(- c) m]) entries)]
      [:tr [:td msg] [:td c] [:td (focus-toolbar (descriptor-fn msg))]])]]])

(defn- node-traffic [s cls]
  (let [nm (atom-name cls) es (:events s)
        sends (filter #(#{"send" "send_sync"} (:kind %)) es)
        counts (frequencies (map #(vector (atom-name (or (:sender_cls %) (:_inferredSenderCls %) "(root)"))
                                         (atom-name (:target_cls %)) (:msg %)) sends))]
    [:div
     [:p (str (count (filter #(= nm (atom-name (or (:target_cls %) (:atom_cls %)))) es)) " trace events")]
     (for [[label endpoint] [["incoming" 1] ["outgoing" 0]]]
       [:div [:h4 label]
        [:table.message-table [:thead [:tr [:th "direction / message"] [:th "count"]]]
         [:tbody (for [[[src dst msg] c] (sort-by (fn [[key c]] [(- c) key]) counts)
                       :when (= nm (nth [src dst] endpoint))]
           [:tr [:td (str src " → " dst " · " msg)] [:td c]
            [:td (focus-toolbar {:kind "edge" :target {:from_atom src :to_atom dst :msg msg}})]])]]])]))

(defn- detail-pane [{:keys [detail-atom detail shape detail-height focus] :as s}]
  (when (or detail-atom detail)
    (let [atom (when detail-atom (atom-shape-for-class shape detail-atom))
          descriptor (or focus (when detail-atom {:kind "node" :target {:atom (atom-name detail-atom)}}))]
      [:div.detail-wrap {:style {:height (str detail-height "px")}}
       ;; gap 28: splitter remains keyboard reachable and remembers size.
       [:div#detail-splitter {:role "separator" :tabindex 0 :aria-orientation "horizontal"
                             :aria-label "Resize detail" :aria-valuenow detail-height
                             :on {:pointerdown [::splitter-drag] :keydown [::splitter-key] :dblclick [::splitter-reset]}}]
       [:section#atom-detail
        [:div.atom-detail-header
         [:span.atom-detail-name {:tabindex 0 :on (annotation-anchor descriptor)} (if detail-atom (atom-name detail-atom) (name (:kind detail))) ]
         (when atom [:span.atom-detail-summary {:title "Phases are declared states; arms are message handlers; observations are queries; initial is the entry phase."}
                     (str (count (:phases atom)) " phases · " (count (:transitions atom)) " arms · "
                          (count (:observations atom)) " observations · initial " (:initial_phase atom))])
         (when atom [:button.btn {:on {:click [::popup-open {:kind "node" :target {:atom (:name atom) :phase (:initial_phase atom)}}]}}
                     (str "initial " (:initial_phase atom))])
         (when descriptor (focus-toolbar descriptor))
         [:button.btn.close {:aria-label "Close detail" :on {:click [::close-atom-detail]}} "×"]]
        (cond
          (= :annotation (:kind detail)) (annotation-detail-pane s (:id detail))
          (= :table (:kind detail)) (annotation-table s)
          atom [:div
                ;; gaps 48/56: legend stays with the inspected phase graph.
                [:p.phase-legend {:title "Reachability follows declared transitions from initial, independent of runtime hits."}
                 "amber = initial · dashed = unreached · self N = unchanged-phase arms · |: = guarded arm · ×N = grouped arms"]
                [:div#phase-graph-container.phase-graph-container]]
          detail-atom (node-traffic s detail-atom)
          (= :event (:kind detail)) [:pre.raw-event (js/JSON.stringify (clj->js (:event detail)) nil 2)]
          (= :range (:kind detail)) [:p (target-brief (:target descriptor))]
          (= :edge (:kind detail))
          (if (seq (:directions detail))
            (for [e (:directions detail)]
              [:div [:h4 (str (atom-name (aget e "src")) " → " (atom-name (aget e "dst")))]
               [:p (str (aget e "total") " messages · " (aget e "sync") " sync")]
               (focus-toolbar (edge-descriptor e))
               (traffic-table "messages" (js->clj (js/Array.from (.entries (aget e "msgs")))) #(edge-descriptor e %))])
            [:p (target-brief (:target descriptor))]))]])))

(defn- set-detail-height! [h]
  (let [h (max 120 (min (* 0.65 (or (.-innerHeight js/window) 900)) h))]
    (.setItem js/localStorage "cxc-phase-pane-h" (str h))
    (swap! state assoc :detail-height h)
    (js/setTimeout apply-shape-view! 0)))

(defn- reset-detail-height! []
  (set-detail-height! (* 0.45 (or (.-innerHeight js/window) 900)))
  (.removeItem js/localStorage "cxc-phase-pane-h"))

(defn- splitter-drag! [ev]
  (.preventDefault ev)
  (let [start (.-clientY ev) height (:detail-height @state)
        move #(set-detail-height! (+ height (- start (.-clientY %))))
        end (fn end [_]
              (.removeEventListener js/window "pointermove" move)
              (.removeEventListener js/window "pointerup" end))]
    (.addEventListener js/window "pointermove" move)
    (.addEventListener js/window "pointerup" end)))

(defn- popup-dialog [{:keys [popup save-error]}]
  (when (:open? popup)
    (let [{:keys [kind target note editing-id attachments]} popup]
      [:div
       [:div#annotate-backdrop {:on {:click [::popup-close]}}]
       [:div#annotate-popup {:role "dialog" :aria-modal "true" :on {:keydown [::dialog-key]}}
        [:div.popup-title (if (:resolution-id popup) (str (:resolution-status popup) " · " (:resolution-id popup))
                            (if editing-id (str "edit " editing-id) "annotate"))]
        [:div.popup-target "target: " [:span (target-brief target)]]
        (when save-error [:div.error {:role "alert"} save-error])
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
              [:button.popup-att-remove {:aria-label "Remove attachment" :on {:click [::popup-remove-att i]}} "×"]])])
        [:div.popup-actions
         [:span.popup-hint "⌘/Ctrl+Enter sends · ⌘/Ctrl+Shift+Enter saves draft"]
         [:button {:on {:click [::popup-close]}} "cancel"]
         (when-not (:resolution-id popup)
           [:button {:disabled (:saving? popup) :on {:click [::popup-submit :draft]}} "save draft"])
         [:button.primary {:disabled (:saving? popup) :on {:click [::popup-submit :send]}}
          (if (:resolution-id popup) (if (= "wontfix" (:resolution-status popup)) "save reason" "save summary") "send")]]]])))

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
      ;; gap 46: declared atoms remain distinct from rendered init nodes.
      [:span.trace-name.status (str (or trace-name shape-name)
                                   (when (seq events) (str " — " (count events) " events"))
                                   (when shape (str " · shape: " (count (:atoms shape)) " atoms")))])]
   (when loading? [:div.loading "loading…"])
   (for [[artifact message] [["trace" (:trace-error s)] ["shape" (:shape-error s)]
                              ["layout" (:render-error s)] ["load" error]] :when message]
     [:div.artifact-error {:role "status"} (str artifact ": " message)])
   (cond
     (and (empty? events) (nil? shape))
     [:div.empty
      [:p "no trace or shape loaded. open with ?trace=... or ?shape=... or drop one below."]
      [:div#drop {:role "button" :tabindex 0
                  :on   {:keydown [::drop-key]
                         :click    [::drop-click]
                         :dragover [::drop-over]
                         :drop     [::drop-file]}}
       "drop trace.jsonl / shape.json" [:br]
       [:span.drop-sub "or click to browse"]]
      [:input#file-picker {:type "file" :accept ".jsonl,.json,.log"
                           :style {:display "none"}
                           :on {:change [::file-picked]}}]]
     :else
     [:section.main
      [:button#sidebar-toggle {:aria-label "Toggle sidebar" :on {:click [::sidebar-toggle]} :title "Toggle sidebar (⌘/Ctrl+\\)"
                               :aria-controls "sidebar" :aria-expanded (not (:sidebar-hidden? s))} (if (:sidebar-hidden? s) "›" "‹")]
      [:aside#sidebar.stats
       [:div#drop {:role      "button"
                   :tabindex  0
                   :on        {:keydown [::drop-key]
                         :click    [::drop-click]
                               :dragover [::drop-over]
                               :drop     [::drop-file]}}
        "drop trace.jsonl / shape.json" [:br]
        [:span.drop-sub "or click to browse"]]
       [:span.trace-name (or trace-name shape-name)]
       [:input#file-picker {:type "file" :accept ".jsonl,.json,.log"
                            :style {:display "none"}
                            :on {:change [::file-picked]}}]
       ;; gap 59: collapsible overview independent of filters/review.
       [:details.overview {:open true}
        [:summary "overview"]
       (stats-row "events"
                  (count events))
       (stats-row "atoms"
                  (count (into #{} (keep :target) events)))
       (stats-row "classes"
                  (count (into #{} (keep :target_cls) events)))
       (stats-row "duration"
                  (str (.toFixed (if (seq events) (- (:t (last events)) (:t (first events))) 0) 2) " ms"))]
       (diagnostics-pane events
        [:div.diagnostic-stats
         (when (and shape (empty? events))
           [:div.shape-overview
            (stats-row "atoms" (count (:atoms shape)))
            (stats-row "transitions" (reduce + 0 (map #(+ (count (:transitions %)) (count (:self_transitions %))) (:atoms shape))))
            (stats-row "self transitions" (reduce + 0 (map #(+ (count (filter (fn [t] (= (:from t) (:to t))) (:transitions %)))
                                                                (count (:self_transitions %))) (:atoms shape))))
            (stats-row "longest chain depth" (apply max 0 (map #((aget (aget js/window "CxcShapeCanvas") "shapeChainDepth") (clj->js %)) (:atoms shape))))])
         (stats-row "annotations (open)"
                  (count (schema/filter-annotations (:annotations sidecar)
                                                    {:status "open"})))
         (stats-row "schema version"
                  (:cxc_annotation_version sidecar))
         (when layout
           (list (stats-row "shape nodes" (:nodes layout))
                 (stats-row "shape edges" (:edges layout))))])
       (filters-pane s)
       (annotations-pane s)]
      [:div.workspace
       [:div.stage-toolbar
       (let [{:keys [on? atom-max send-max]} (:heatmap s)]
         [:label.heat-toggle
          [:input {:type     "checkbox"
                   :checked  on?
                   :disabled (zero? (count events))
                   :on       {:change [::heat-toggle]}}]
          " heatmap "
          [:span.heat-ramp
           (str "max atom " atom-max " · max send " send-max)]])
       [:details.advanced-layouts {:open (:advanced? s) :on {:toggle [::advanced-toggle]}}
        [:summary "advanced layouts"]
        [:div.layout-controls
        [:label "layout " [:select {:value (get-in s [:layout-options :algorithm]) :on {:change [::layout-option :algorithm]}}
          (for [a ["layered" "stress" "force" "mrtree" "radial" "disco"]]
            [:option {:value a :disabled (and (:layout-capabilities s) (not (contains? (:layout-capabilities s) a)))} a])]]
        [:label "direction " [:select {:value (get-in s [:layout-options :direction])
                                     :disabled (not (#{"layered" "mrtree"} (get-in s [:layout-options :algorithm])))
                                     :on {:change [::layout-option :direction]}}
          (for [d ["DOWN" "RIGHT" "UP" "LEFT"]] [:option {:value d} d])]]]]
       [:span.toolbar-spacer]
       [:label [:input {:type "checkbox" :checked (get-in s [:layout-options :showInit])
                        :on {:change [::show-init]}}] " show init"]
       [:label.filter-label
        "seq range "
        [:input.filter-input {:type "text" :value (or (get-in s [:filters :seq-range]) "")
                              :placeholder "e.g. 0-500"
                              :on {:input [::set-seq-range]}}]]]
       [:div.shape-stage
       [:div#shape-container.shape-container]
       [:div#shape-zoom
        [:button {:on {:click [::zoom-out]} :aria-label "Zoom out" :title "zoom out"} "−"]
        [:span#shape-zoom-level "100%"]
        [:button {:on {:click [::zoom-in]} :aria-label "Zoom in" :title "zoom in"} "+"]
        [:button {:on {:click [::zoom-reset]} :aria-label "Reset zoom" :title "reset zoom"} "⟲"]
        [:button {:on {:click [::zoom-fit]} :aria-label "Reset view" :title "reset view"} "reset view"]]
       (when-not (:hint-dismissed? s)
         [:div#shape-hint "Click any atom for its " [:b "phase graph"] ". Shift-click or right-click to " [:b "annotate"] ". "
          [:button.dismiss {:aria-label "Dismiss interaction hint" :on {:click [::hint-dismiss]}} "×"]])
       (legend-card (and (:legend-open? s) (nil? (:detail-atom s))))
       (transport-bar s)]
       (detail-pane s)]])
   (when-let [message (when-not (get-in s [:popup :open?]) (:save-error s))]
     [:div.error {:role "alert"}
      message " · edits retained "
      [:button.btn {:on {:click [::retry-save]}} "retry save"]])
   (popup-dialog s)])

;; ---------- render loop ----------

(def ^:private root (atom nil))

(defn- rerender! []
  (when-let [el @root]
    (rdom/render el (view @state))))

(defn- on-state-change [_k _r old-s new-s]
  (rerender!)
  (modal-lifecycle! old-s new-s)
  (when (not= (:detail-atom old-s) (:detail-atom new-s)) (update-selection!))
  (when (not= (:filters old-s) (:filters new-s)) (normalize-playback!))
  (when (and (:pending-focus new-s) (focus-ready? new-s))
    (let [f (:pending-focus new-s)] (swap! state dissoc :pending-focus) (apply-focus-from-url! f)))
  (when (and (not (:pending-focus @state)) (:focus new-s)
             (or (not= (:shape old-s) (:shape new-s)) (not= (:events old-s) (:events new-s)))
             (contains? #{"event" "edge"} (get-in new-s [:focus :kind])))
    (apply-focus-from-url! (focus-key (:focus @state))))
  (when (not= (:sidecar old-s) (:sidecar new-s)) (refresh-badges!))
  (when (or (not= (:shape old-s) (:shape new-s))
            (not= (:events old-s) (:events new-s))
            (not= (:gen old-s) (:gen new-s))
            (not= (:filters old-s) (:filters new-s))
            (not= (:layout-options old-s) (:layout-options new-s))
            (not= (get-in old-s [:heatmap :on?]) (get-in new-s [:heatmap :on?]))
            (and (:loading? old-s) (not (:loading? new-s))))
    ;; Invalidate in-flight work immediately, even before the next timer runs.
    (let [epoch (swap! shape-render-epoch inc)]
      (js/setTimeout #(render-shape! @state epoch) 0)))
  (when (and (:detail-atom new-s)
             (or (not= (:detail-atom old-s) (:detail-atom new-s))
                 (not= (:shape old-s) (:shape new-s))
                 (not= (:events old-s) (:events new-s))
                 (not= (get-in old-s [:heatmap :on?]) (get-in new-s [:heatmap :on?]))))
    (swap! phase-render-epoch inc)
    (js/setTimeout #(render-phase-graph! (:detail-atom @state)) 0)))

;; ---------- url + load ----------

(defn- query-params []
  (let [p (js/URLSearchParams. (.-search js/location))]
    {:trace (.get p "trace")
     :shape (.get p "shape")
     :focus (or (.get p "focus") (when-let [a (.get p "expand")] (str "atom:" a)))
     :algorithm (.get p "layout")
     :direction (or (.get p "direction") (when-let [o (.get p "shape-orient")] (if (= "lr" o) "RIGHT" "DOWN")))
     :sync? (some #{"1"} (array-seq (.getAll p "sync")))
     :layout-debug? (= "1" (.get p "layout-debug"))
     :overrides (.get p "elkopt")
     :hide-sidebar? (= "1" (.get p "hide-sidebar"))}))


(defn- focus-ready? [s]
  (let [tag (first (str/split (:pending-focus s) #":" 2))]
    (if (contains? #{"event" "range" "edge"} tag)
      (or (seq (:events s)) (and (not (:loading? s)) (:shape s)))
      (or (:shape s) (seq (:events s))))))

(defn- focus-key [{:keys [kind target]}]
  (case kind
    "node" (if (:phase target) (str "phase:" (:atom target) "." (:phase target)) (str "atom:" (:atom target)))
    "event" (str "event:" (or (:trace_seq target) (:seq target)))
    "range" (str "range:" (or (:trace_seq_start target) (:from_seq target)) ":" (or (:trace_seq_end target) (:to_seq target)))
    "edge" (str "edge:" (:from_atom target) ":" (:to_atom target) ":" (:msg target))
    (pr-str target)))

(defn- apply-focus-from-url! [key]
  (let [[tag val] (str/split key #":" 2)]
    (case tag
      "atom" (swap! state assoc :detail nil :detail-atom val :focus {:kind "node" :target {:atom val}})
      "phase" (let [[a p] (str/split val #"\." 2)]
                (swap! state assoc :detail nil :detail-atom a :focus {:kind "node" :target {:atom a :phase p}}))
      "event" (let [n (js/Number val) e (first (filter #(= n (:seq %)) (:events @state)))]
                (when (js/Number.isFinite n)
                  (if e (show-event! e)
                    (swap! state assoc :detail-atom nil :focus {:kind "event" :target {:trace_seq n}}
                           :detail {:kind :event :event {:seq n}}))))
      "range" (let [[lo hi] (str/split val #"[:-]" 2) lo (js/Number lo) hi (js/Number hi)]
                (when (and (js/Number.isFinite lo) (js/Number.isFinite hi) (<= lo hi))
                  (swap! state assoc :detail-atom nil :detail {:kind :range}
                         :focus {:kind "range" :target {:trace_seq_start lo :trace_seq_end hi}})
                  (swap! state assoc-in [:filters :seq-range] (str lo "-" hi))))
      "edge" (let [[src dst msg] (str/split val #":" 3)
                   SC (aget js/window "CxcShapeCanvas") s @state
                   resolver (class-resolver (:shape s) (:events s))
                   graph ((aget SC "buildGraph") (clj->js (:shape s)) (boolean (seq (:events s)))
                          (clj->js (filter-events (:events s) (:filters s))) (aget resolver "displayLabel") (aget SC "prettifyMsg"))
                   topo ((aget SC "topology") (aget graph "nodes") (aget graph "edges") (clj->js (:shape s))
                         (aget resolver "atomShapeForClass") (get-in s [:layout-options :showInit]))
                   directions (filter #(or (and (= src (atom-name (aget % "src"))) (= dst (atom-name (aget % "dst"))))
                                           (and (= dst (atom-name (aget % "src"))) (= src (atom-name (aget % "dst")))))
                                      (array-seq (aget topo "edges")))]
               (swap! state assoc :detail-atom nil :detail {:kind :edge :directions (vec directions)}
                      :focus {:kind "edge" :target {:from_atom src :to_atom dst :msg msg}}))
      nil)))

(defn- derive-shape-path [trace-path]
  (when trace-path
    ;; gap 67: legacy append fallback for .log, .json and extensionless paths.
    (if (re-find #"(?:\.trace)?\.jsonl$" trace-path)
      (str/replace trace-path #"(?:\.trace)?\.jsonl$" ".shape.json")
      (str trace-path ".shape.json"))))

(defn- fetch-json [url]
  (-> (js/fetch url #js {:cache "no-store"})
      (.then (fn [r]
               (if (.-ok r)
                 (.json r)
                 (throw (js/Error. (str "HTTP " (.-status r) " on " url))))))))

(defn- shape-annotation-key [shape-path]
  (when shape-path
    (str (-> shape-path
             (str/replace #"\.shape\.json$" "")
             (str/replace #"\.json$" ""))
         ".trace.jsonl")))

(defn- switch-review! [key source]
  (when (not= key (:annotation-key @state))
    (let [empty-review (schema/empty-sidecar source "" "")]
      (swap! state assoc :annotation-key key :sidecar empty-review
             :saved-sidecar empty-review :annotation-dirty? false :save-error nil
             :annotation-status :loading :annotation-error nil
             :popup {:open? false} :ui {} :ann-selected #{} :ann-return nil :table-return nil))
    (load-annotations!)
    (start-poll!)))

(defonce ^:private shape-load-epoch (atom 0))

(defn- valid-shape [j]
  (when-not (and j (array? (aget j "atoms"))
                  (every? #(string? (aget % "name")) (array-seq (aget j "atoms"))))
    (throw (js/Error. "shape requires an atoms array with named atoms")))
  (js->clj j :keywordize-keys true))

(defn- load-shape! [shape-url]
  (let [my-gen (:gen @state) epoch (swap! shape-load-epoch inc)]
    (swap! state assoc :shape-name shape-url :shape-error nil)
    (when-not (:trace-name @state)
      (switch-review! (shape-annotation-key shape-url) shape-url))
    (-> (fetch-json shape-url)
        (.then (fn [j]
                 (when (= epoch @shape-load-epoch)
                   (commit! my-gen
                     #(assoc % :shape (valid-shape j))))))
        (.catch (fn [e] (when (= epoch @shape-load-epoch)
                         (commit! my-gen #(assoc % :shape nil :shape-error (.-message e)))))))))

(defn- hydrate-trace-from-text! [name text my-gen]
  (when (= my-gen (:gen @state))
    (swap! state assoc :trace-name name :trace-error nil :error nil :loading? false)
    (switch-review! name name)
    (commit! my-gen (fn [s] (assoc s
                              :events (with-sender-cls (parse-jsonl text))
                              :playback {:cursor-seq nil :paused? true :speed 1})))))

(defn- hydrate-shape-from-text! [name text my-gen]
  (when (= my-gen (:gen @state))
    (swap! state assoc :shape-name name :shape-error nil :error nil :loading? false)
    (switch-review! (shape-annotation-key name) name)
    (try
      (let [j (js/JSON.parse text)]
        (commit! my-gen #(assoc % :shape (valid-shape j))))
      (catch :default e
        (swap! state assoc :shape nil :shape-error (str "shape parse: " (.-message e)))))))

(defn- load-dropped-file! [file]
  (let [name (.-name file)
        my-gen (next-gen!)
        shape-file? (str/ends-with? name ".shape.json")
        rdr (js/FileReader.)]
    ;; Invalidate fetches/layouts at selection time, not after FileReader finishes.
    (swap! state assoc :events [] :shape nil :layout nil :loading? true
           :trace-error nil :shape-error nil :render-error nil :detail nil :detail-atom nil :focus nil
           :trace-name (when-not shape-file? name) :shape-name (when shape-file? name))
    (switch-review! (if shape-file? (shape-annotation-key name) name) name)
    (set! (.-onload rdr)
          (fn [_]
            (let [text (.-result rdr)]
              (if (or shape-file? (try (array? (aget (js/JSON.parse text) "atoms")) (catch :default _ false)))
                (do (swap! state assoc :trace-name nil) (hydrate-shape-from-text! name text my-gen))
                (hydrate-trace-from-text! name text my-gen)))))
    (.readAsText rdr file)))

(defn- load-trace! [trace-url]
  (let [different? (not= trace-url (:trace-name @state))
        my-gen (next-gen!)]
    (when different?
      (swap! state assoc :events [] :shape nil :shape-name nil :layout nil))
    (swap! state assoc :loading? true :trace-name trace-url :error nil :trace-error nil :render-error nil)
    (switch-review! trace-url trace-url)
    (-> (js/fetch trace-url #js {:cache "no-store"})
        (.then (fn [r]
                 (if (.-ok r)
                   (.text r)
                   (throw (js/Error. (str "HTTP " (.-status r)))))))
        (.then (fn [text]
                 (commit! my-gen (fn [s] (assoc s
                                                :events (with-sender-cls (parse-jsonl text))
                                                :loading? false
                                                :playback {:cursor-seq nil
                                                           :paused? true :speed 1})))))
        (.catch (fn [e]
                  (commit! my-gen (fn [s] (assoc s :trace-error (.-message e)
                                                    :loading? false))))))))

;; ---------- annotations load + poll ----------

(defonce ^:private poll-handle (atom nil))
(defonce ^:private pending-save-count (atom 0))
(defonce ^:private save-epoch (atom 0))
(defonce ^:private save-chain (atom (js/Promise.resolve nil)))

(defn- canonical-sidecar [sidecar]
  (update sidecar :annotations
    (fn [anns]
      (mapv (fn [a]
              (cond-> a
                (:comments a)
                (update :comments
                  (fn [cs]
                    (mapv (fn [c]
                            (-> c
                                (assoc :by (or (:by c) (:author c))
                                       :text (or (:text c) (:body c)))
                                (dissoc :author :body))) cs)))))
            (or anns [])))))

(defn- load-annotations! []
  (when-let [k (:annotation-key @state)]
    (when (and (zero? @pending-save-count) (not (:annotation-dirty? @state)))
      (let [my-gen (:gen @state) epoch @save-epoch]
        (-> (js/fetch (str "/annotations?trace=" (js/encodeURIComponent k)))
            (.then (fn [r] (if (.-ok r) (.json r)
                             (throw (js/Error. (str "HTTP " (.-status r)))))))
            (.then (fn [j]
                     ;; A GET started before a save must never replace that save,
                     ;; even when both still belong to the same artifact generation.
                     (when (and j (= epoch @save-epoch) (zero? @pending-save-count))
                       (commit! my-gen
                         (fn [s]
                           (if (and (= k (:annotation-key s)) (not (:annotation-dirty? s)))
                             (let [sidecar (canonical-sidecar (js->clj j :keywordize-keys true))]
                               (assoc s :sidecar sidecar :saved-sidecar sidecar
                                      :annotation-status :available :annotation-error nil))
                             s))))))
            (.catch (fn [e]
                      (when (and (= epoch @save-epoch) (zero? @pending-save-count))
                        (commit! my-gen #(if (= k (:annotation-key %))
                          (assoc % :annotation-status :unavailable :annotation-error (.-message e)) %))))))))))

(defn- start-poll! []
  (when @poll-handle (js/clearInterval @poll-handle))
  (reset! poll-handle (js/setInterval load-annotations! 2000)))

(defn- merge-fields [base local remote]
  (cond
    (= base local) remote
    (= base remote) local
    (= local remote) local
    (and (map? base) (map? local) (map? remote))
    (reduce (fn [out k]
              (let [b (get base k ::missing) l (get local k ::missing)
                    r (get remote k ::missing)
                    v (if (= k :comments)
                        (vec (distinct (concat (when (sequential? r) r)
                                               (remove (set (when (sequential? b) b))
                                                       (when (sequential? l) l)))))
                        (merge-fields b l r))]
                (if (= ::missing v) (dissoc out k) (assoc out k v))))
            remote (into #{} (concat (keys base) (keys local) (keys remote))))
    :else (throw (js/Error. "save conflict: both writers changed the same field; local edits retained, server unchanged"))))

(defn- remap-ann-ids [sidecar ids]
  (update sidecar :annotations
          #(mapv (fn [a] (update a :id (fn [id] (get ids id id)))) %)))

(defn- merge-sidecars
  "Three-way CAS merge: keep remote changes to untouched fields, local edits
   to changed fields, and comments from both writers. Rename colliding new IDs."
  [base local remote]
  (let [index #(into {} (map (juxt :id identity) (:annotations %)))
        b (index base) r (index remote)
        ids (reduce (fn [ids a]
                      (let [id (:id a)]
                        (if (and (not (contains? b id)) (contains? r id) (not= a (get r id)))
                          (assoc ids id (next-ann-id
                                        (concat (:annotations local) (:annotations remote)
                                                (map (fn [id] {:id id}) (vals ids)))))
                          ids)))
                    {} (:annotations local))
        local (remap-ann-ids local ids)
        l (index local)
        order (distinct (concat (map :id (:annotations remote)) (map :id (:annotations local))))
        anns (keep (fn [id]
                     (let [v (merge-fields (get b id ::missing) (get l id ::missing)
                                           (get r id ::missing))]
                       (when-not (= ::missing v) v))) order)]
    {:sidecar (assoc (merge-fields (dissoc base :annotations :rev)
                                  (dissoc local :annotations :rev)
                                  (dissoc remote :annotations :rev))
                     :rev (schema/effective-rev remote) :annotations (vec anns))
     :id-remap ids}))

(defn- save-sidecar!
  "Serialize saves; merge and retry CAS conflicts. Resolve true only after
   persistence succeeds. Failed edits stay dirty, so polling cannot erase them."
  []
  (let [{:keys [annotation-key gen]} @state
        current? #(and (= gen (:gen @state)) (= annotation-key (:annotation-key @state)))]
    (if-not annotation-key
      (do (swap! state assoc :save-error "load an artifact before saving")
          (js/Promise.resolve false))
      (do
        (swap! save-epoch inc)
        (swap! pending-save-count inc)
        (swap! state assoc :annotation-dirty? true :save-error nil)
        (letfn [(post! [base sent attempt]
                  (if-not (current?)
                    (js/Promise.resolve false)
                    (if-let [error (schema/validate-sidecar sent)]
                      (js/Promise.reject (js/Error. error))
                      (-> (js/fetch (str "/annotations?trace=" (js/encodeURIComponent annotation-key))
                            #js {:method "POST" :headers #js {"Content-Type" "application/json"}
                                 :body (js/JSON.stringify (clj->js sent))})
                          (.then (fn [response]
                                   (cond
                                     (.-ok response)
                                     (-> (.json response)
                                         (.then (fn [j]
                                                  (when (current?)
                                                    (let [saved (assoc sent :rev (aget j "rev"))]
                                                      (commit! gen
                                                        (fn [s]
                                                          (let [working (:sidecar (merge-sidecars sent (:sidecar s) saved))]
                                                            (assoc s :sidecar working :saved-sidecar saved
                                                                   :annotation-dirty? (not= working saved)
                                                                   :save-error nil :annotation-status :available :annotation-error nil))))
                                                      true)))))
                                     (= 409 (.-status response))
                                     (-> (.json response)
                                         (.then (fn [j]
                                                  (when (current?)
                                                    (let [fresh (canonical-sidecar
                                                                  (js->clj (aget j "sidecar") :keywordize-keys true))
                                                          {merged :sidecar ids :id-remap} (merge-sidecars base sent fresh)]
                                                      (commit! gen
                                                        (fn [s]
                                                          (-> s
                                                              (assoc :saved-sidecar fresh
                                                                     :sidecar (:sidecar
                                                                               (merge-sidecars (remap-ann-ids sent ids)
                                                                                               (remap-ann-ids (:sidecar s) ids)
                                                                                               merged)))
                                                              (update-in [:popup :editing-id] #(get ids % %)))))
                                                      (if (< attempt 3)
                                                        (post! fresh (:sidecar @state) (inc attempt))
                                                        (throw (js/Error. "save conflicted repeatedly; retry your retained edits"))))))))
                                     :else
                                     (throw (js/Error. (str "save failed: HTTP " (.-status response)))))))))))]
          (let [result (-> @save-chain
                           (.then (fn [_]
                                    (when (current?)
                                      (post! (or (:saved-sidecar @state) (:sidecar @state))
                                             (canonical-sidecar (:sidecar @state)) 0))))
                           (.catch (fn [e]
                                     (when (current?)
                                       (commit! gen #(assoc % :save-error (.-message e))))
                                     false))
                           (.finally (fn [] (swap! pending-save-count dec))))]
            (reset! save-chain result)
            result))))))

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
                    (swap! state assoc :save-error (.-message e))))))))

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
          (let [el (.-target ev) url (str/trim text) md (str "[" url "](" url ")")
                note (get-in @state [:popup :note] "") start (or (.-selectionStart el) (count note))
                end (or (.-selectionEnd el) start) pos (+ start (count md))]
            ;; gap 54: one state update so Replicant preserves the inserted note.
            (swap! state (fn [s] (-> s
              (assoc-in [:popup :note] (str (subs note 0 start) md (subs note end)))
              (update-in [:popup :attachments] #(conj (vec %) {:kind "link" :url url})))))
            (js/setTimeout #(.setSelectionRange el pos pos) 0)))))))

(defn- open-popup!
  "Open the annotate popup. target is a map; kind is 'node' / 'event' /
   'range' / 'phase'. editing-id non-nil means edit mode."
  [{:keys [kind target editing-id prefill-note prefill-attachments resolution-id resolution-status resolution-current-status] :as _args}]
  ;; gap 68: server/artifact guards do not disturb an already-open failed-save composer.
  (if (or (nil? (:annotation-key @state)) (#{:unavailable :loading} (:annotation-status @state)))
    (js/alert (if (:annotation-key @state)
                "Annotation server unavailable or loading; retry the connection before annotating."
                "Load an artifact before annotating."))
    (do
  (when-not (get-in @state [:popup :open?]) (reset! popup-return-focus (.-activeElement js/document)))
  (swap! state assoc :popup
         {:open? true :session (random-uuid) :kind (or kind "node") :target (or target {})
          :note (or prefill-note "")
          :editing-id editing-id :resolution-id resolution-id :resolution-status resolution-status
          :resolution-current-status resolution-current-status
          :attachments (or prefill-attachments [])}))))

(defn- close-popup! []
  (swap! state assoc-in [:popup :open?] false))

(defn- popup-submit!
  "mode = :send (status open, stamps submitted_at) or :draft (status draft).
   Edits preserve status unless we're sending a draft (then flip to open)."
  [mode]
  (when-not (get-in @state [:popup :saving?])
    (let [{:keys [popup sidecar gen]} @state
        {:keys [kind target note editing-id attachments session]} popup
        now (now-iso)
        send? (= mode :send)]
    (when (str/blank? note)
      (js/alert "write something")
      (throw (ex-info "empty note" {})))
    (let [anns (:annotations sidecar)
          id (or editing-id (next-ann-id anns))
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
            (let [base {:id          id
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
      ;; Remember the optimistic ID so a failed submission retries this note.
      (swap! state update :popup assoc :editing-id id :saving? true)
      (-> (save-sidecar!)
          (.then (fn [saved?]
                   (when (and (= gen (:gen @state)) (= session (get-in @state [:popup :session])))
                     (swap! state assoc-in [:popup :saving?] false)
                     (when (and saved?
                                (= note (get-in @state [:popup :note]))
                                (= attachments (get-in @state [:popup :attachments])))
                       (close-popup!))))))))))

(defn- submit-all-drafts!
  "Bulk-flip every draft annotation to open with submitted_at=now."
  []
  (when (js/confirm "Submit all drafts to the agent?")
  (let [now (now-iso)]
    (swap! state update-in [:sidecar :annotations]
           (fn [anns]
             (mapv (fn [a]
                     (if (= "draft" (schema/effective-status a))
                       (merge a {:status "open" :submitted_at now})
                       a))
                   anns))))
  (save-sidecar!))) ;; gap 60


(defn- can-transition? [a status]
  (let [cur (schema/effective-status a)]
    (case status "verified" (= "addressed" cur)
                 "addressed" (contains? #{"open" "in_progress"} cur)
                 "wontfix" (contains? #{"open" "in_progress" "addressed"} cur) false)))

(defn- transition-record [a status text now]
  (if-not (can-transition? a status) a
    (assoc a :status status :updated_at now
      :resolution (case status
        "verified" (merge {:by "human" :at now} (:resolution a)
                           {:kind "verified" :verified_by "human" :verified_at now})
        "addressed" {:by "human" :at now :kind "fix_landed" :summary (str/trim text)}
        "wontfix" {:by "human" :at now :kind "wontfix" :reason (str/trim text) :summary (str/trim text)}))))

(defn- transition! [ann-id new-status]
  (when-let [a (first (filter #(= ann-id (:id %)) (get-in @state [:sidecar :annotations])))]
    (when (can-transition? a new-status)
      (if (= "verified" new-status)
        (when (js/confirm (str "Mark " ann-id " verified?"))
          (swap! state update-in [:sidecar :annotations]
                   #(mapv (fn [a] (if (= ann-id (:id a)) (transition-record a new-status "" (now-iso)) a)) %))
            (save-sidecar!))
        (open-popup! {:kind (:kind a) :target (:target a) :resolution-status new-status :resolution-id ann-id :resolution-current-status (schema/effective-status a)})))))

(defn- resolution-submit! []
  (let [{:keys [note resolution-id resolution-status resolution-current-status resolution-applied? session]} (:popup @state) gen (:gen @state)]
    (when (and (not (get-in @state [:popup :saving?])) (not (str/blank? note)))
      (swap! state assoc-in [:popup :saving?] true)
      (swap! state update-in [:sidecar :annotations]
             #(mapv (fn [a]
                      (if (= resolution-id (:id a))
                        (transition-record (if (and resolution-applied? (= resolution-status (:status a)))
                                             (assoc a :status resolution-current-status) a)
                                           resolution-status note (now-iso)) a)) %))
      (swap! state assoc-in [:popup :resolution-applied?] true)
      (-> (save-sidecar!)
          (.then (fn [ok?]
                   (when (and (= gen (:gen @state)) (= session (get-in @state [:popup :session])))
                     (swap! state assoc-in [:popup :saving?] false)
                     (when ok? (close-popup!)))))))))

;; gap 41: capture initiating focus, isolate background and restore it on close.
(defonce ^:private popup-return-focus (atom nil))
(defonce ^:private modal-inert (atom []))
(defn- modal-lifecycle! [old-s new-s]
  (let [before (get-in old-s [:popup :open?]) after (get-in new-s [:popup :open?])]
    (when (and after (not before))
      (js/setTimeout
        (fn []
          (when (get-in @state [:popup :open?])
            (let [els (array-seq (.querySelectorAll js/document "#app-root > header, #app-root > .main, #app-root > .empty, #sidebar-toggle, #theme-toggle"))]
              (reset! modal-inert (mapv #(vector % (.-inert %)) els))
              (doseq [el els] (set! (.-inert el) true)))
            (when-let [el (js/document.getElementById "annotate-note")] (.focus el)))) 0))
    (when (and before (not after))
      (doseq [[el previous] @modal-inert] (set! (.-inert el) previous))
      (reset! modal-inert [])
      (when-let [el @popup-return-focus]
        (when (.-isConnected el) (.focus el)))
      (reset! popup-return-focus nil))))

(defn- trap-popup! [ev]
  (when (= "Tab" (.-key ev))
    (let [dialog (js/document.getElementById "annotate-popup")
          els (vec (array-seq (.querySelectorAll dialog "button:not([disabled]), textarea, input, a[href], [tabindex='0']")))
          active (.-activeElement js/document)]
      (cond
        (empty? els) (.preventDefault ev)
        (and (.-shiftKey ev) (or (identical? active (first els)) (not (.contains dialog active))))
        (do (.preventDefault ev) (.focus (last els)))
        (and (not (.-shiftKey ev)) (or (identical? active (last els)) (not (.contains dialog active))))
        (do (.preventDefault ev) (.focus (first els)))))))

;; ---------- event dispatch ----------

(defn- dom-event-value [rep-data]
  (some-> rep-data :replicant/dom-event .-target .-value))

;; gap 61: popup-wide shortcuts and one Escape layer per keypress.
(defn- popup-key! [ev]
  (when-not (.-defaultPrevented ev)
    (cond
      (= "Escape" (.-key ev)) (do (.preventDefault ev) (.stopPropagation ev) (close-popup!))
      (and (or (.-metaKey ev) (.-ctrlKey ev)) (= "Enter" (.-key ev)))
      (do (.preventDefault ev) (.stopPropagation ev)
          (if (get-in @state [:popup :resolution-id]) (resolution-submit!)
              (popup-submit! (if (.-shiftKey ev) :draft :send)))))))

(defn- global-key! [e]
  (when-not (.-defaultPrevented e)
    (let [k (.-key e) tgt (.-target e)
          interactive? (and tgt (or (.-isContentEditable tgt)
            (.closest tgt "input,textarea,select,button,a,summary,[contenteditable='true'],[role='button'],[role='link'],[role='slider'],[role='separator'],[role='checkbox'],[role='radio'],[role='switch'],[role='tab'],[role='menuitem']")))]
      (cond
        (get-in @state [:popup :open?]) (popup-key! e)
        (and (or (.-metaKey e) (.-ctrlKey e)) (= k "\\")) (do (.preventDefault e) (toggle-sidebar!))
        (and (= k "Escape") (:legend-open? @state) (nil? (:detail-atom @state)))
        (do (.preventDefault e) (.setItem js/localStorage "cxc-shape-legend-hidden" "1") (swap! state assoc :legend-open? false))
        (and (= k "Escape") (or (:detail-atom @state) (:detail @state)))
        (do (.preventDefault e) (swap! state assoc :detail-atom nil :detail nil :focus nil))
        (and (= k " ") (not interactive?) (seq (:events @state)))
        (do (.preventDefault e) (if (get-in @state [:playback :paused?]) (pt-play!) (pt-pause!)))))))

(defn- handle-event [rep-data [tag & args]]
  (case tag
    ::annotate-anchor (when-let [ev (:replicant/dom-event rep-data)]
                        (when (or (= "contextmenu" (.-type ev))
                                  (and (= "click" (.-type ev)) (.-shiftKey ev))
                                  (= "ContextMenu" (.-key ev))
                                  (and (.-shiftKey ev) (#{"F10" "Enter" " "} (.-key ev))))
                          (.preventDefault ev) (.stopPropagation ev) (open-popup! (first args))))
    ::ann-row-key (let [ev (:replicant/dom-event rep-data)]
                    (handle-event rep-data [::annotate-anchor (second args)])
                    (when (and (not (.-defaultPrevented ev)) (#{"Enter" " "} (.-key ev))
                               (not (.closest (.-target ev) "button,input,textarea,select,a")))
                      (.preventDefault ev) (open-annotation! (first args))))
    ::ann-row-click (let [ev (:replicant/dom-event rep-data)]
                      (if (.-shiftKey ev)
                        (handle-event rep-data [::annotate-anchor (second args)])
                        (when-not (.closest (.-target ev) "button,input,textarea,select,a")
                          (open-annotation! (first args)))))
    ::ann-export (copy-text! (annotation-export @state))
    ::excerpt-toggle (swap! state update-in [:ui :excerpt-expanded (first args)] not)
    ::annotations-retry (load-annotations!)
    ::chat-refresh (resolve-chat-excerpt! (first args) true)
    ::ann-open (open-annotation! (first args))
    ::ann-back (let [snapshot (or (:ann-return @state) {:detail nil :detail-atom nil :focus nil})]
                 (swap! state merge snapshot)
                 (swap! state assoc :ann-return (:table-return @state)))
    ::ann-table-open (let [s @state]
                       (swap! state assoc :table-return (select-keys s [:detail :detail-atom :focus])
                              :ann-return (select-keys s [:detail :detail-atom :focus])
                              :detail-atom nil :focus nil :detail {:kind :table}))
    ::ann-search (set-browse! #(assoc % :query (dom-event-value rep-data)))
    ::ann-sort (let [k (keyword (first args))]
                 (set-browse! #(assoc % :sort k :descending? (and (= (keyword (:sort %)) k) (not (:descending? %))))))
    ::ann-status-filter (let [status (first args)]
                          (set-browse! #(update % :statuses (fn [xs] (if (contains? xs status) (disj xs status) (conj xs status))))))
    ::ann-select-all (let [ids (set (map :id (browse-annotations @state))) all? (every? (:ann-selected @state) ids)]
                       (swap! state update :ann-selected #(if all? (reduce disj % ids) (into % ids))))
    ::ann-select (let [id (first args) rows (vec (map :id (browse-annotations @state)))
                       prior (.indexOf (clj->js rows) (:ann-selection-anchor @state)) current (.indexOf (clj->js rows) id)
                       shift? (some-> rep-data :replicant/dom-event .-shiftKey)
                       ids (if (and shift? (>= prior 0) (>= current 0))
                             (subvec rows (min prior current) (inc (max prior current))) [id])
                       on? (not (contains? (:ann-selected @state) id))]
                   (swap! state update :ann-selected #(reduce (if on? conj disj) % ids))
                   (swap! state assoc :ann-selection-anchor id))
    ::ann-verify-selected (when (js/confirm "Mark the selected addressed annotations verified?")
                           (let [ids (:ann-selected @state) now (now-iso)]
                            (swap! state update-in [:sidecar :annotations]
                                   #(mapv (fn [a] (if (contains? ids (:id a)) (transition-record a "verified" "" now) a)) %))
                            (-> (save-sidecar!) (.then (fn [ok?] (when ok? (swap! state assoc :ann-selected #{})))))))
    ::dialog-key (let [ev (:replicant/dom-event rep-data)] (trap-popup! ev) (popup-key! ev))
    ::splitter-reset (reset-detail-height!)
    ::splitter-drag (splitter-drag! (:replicant/dom-event rep-data))
    ::splitter-key (when-let [e (:replicant/dom-event rep-data)]
                     (case (.-key e)
                       "ArrowUp" (do (.preventDefault e) (set-detail-height! (+ 24 (:detail-height @state))))
                       "ArrowDown" (do (.preventDefault e) (set-detail-height! (- (:detail-height @state) 24)))
                       "Home" (do (.preventDefault e) (reset-detail-height!)) nil))
    ::sidebar-toggle (toggle-sidebar!)
    ::set-msg-query (swap! state assoc-in [:filters :msg-query] (dom-event-value rep-data))
    ::inspect-event (show-event! (first args))
    ::inspect-event-key (when-let [e (:replicant/dom-event rep-data)]
                         (when (#{"Enter" " "} (.-key e)) (.preventDefault e) (show-event! (first args))))
    ::drop-key (when-let [e (:replicant/dom-event rep-data)]
                (when (#{"Enter" " "} (.-key e)) (.preventDefault e) (.click (js/document.getElementById "file-picker"))))
    ::show-init (let [on? (not (get-in @state [:layout-options :showInit]))]
                  (.setItem js/localStorage "cxc-shape-show-init" (if on? "1" "0"))
                  (swap! state assoc-in [:layout-options :showInit] on?))
    ::advanced-toggle (when-let [e (:replicant/dom-event rep-data)]
                        (let [on? (.-open (.-target e))]
                          (.setItem js/localStorage "cxc-shape-advanced" (if on? "1" "0"))
                          (swap! state assoc :advanced? on?)))
    ::layout-option (let [k (keyword (first args)) v (dom-event-value rep-data)]
                      (.setItem js/localStorage (str "cxc-shape-" (if (= k :algorithm) "layout" (name k))) v)
                      (swap! state assoc-in [:layout-options k] v)
                      (let [url (js/URL. (.-href js/location))]
                        (.set (.-searchParams url) (if (= k :algorithm) "layout" "direction") v)
                        (.replaceState js/history nil "" url)))
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
    ::hint-dismiss (do (.setItem js/localStorage "cxc-shape-hint-dismissed" "1")
                       (swap! state assoc :hint-dismissed? true))
    ::legend-toggle (let [open? (boolean (first args))]
                      (.setItem js/localStorage "cxc-shape-legend-hidden" (if open? "0" "1"))
                      (swap! state assoc :legend-open? open?))
    ::drop-click   (.click (js/document.getElementById "file-picker"))
    ::drop-over    (when-let [ev (:replicant/dom-event rep-data)]
                     (.preventDefault ev)
                     (when-let [el (js/document.getElementById "drop")] (.add (.-classList el) "dragging")))
    ::drop-file    (when-let [ev (:replicant/dom-event rep-data)]
                     (.preventDefault ev)
                     (when-let [el (js/document.getElementById "drop")] (.remove (.-classList el) "dragging"))
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
    ::popup-open-blank (open-popup! (general-annotation @state))
    ::popup-open     (open-popup! (first args))
    ::popup-close    (close-popup!)
    ::popup-set-note (when-let [v (dom-event-value rep-data)]
                       (swap! state assoc-in [:popup :note] v))
    ::popup-key (when-let [ev (:replicant/dom-event rep-data)] (popup-key! ev))
    ::popup-submit   (if (get-in @state [:popup :resolution-id]) (resolution-submit!) (popup-submit! (first args)))
    ::retry-save     (save-sidecar!)
    ::popup-remove-att
    (let [i (first args)]
      (swap! state update-in [:popup :attachments]
             (fn [a] (vec (concat (subvec a 0 i) (subvec a (inc i)))))))
    ::popup-paste  (when-let [ev (:replicant/dom-event rep-data)]
                     (handle-popup-paste! ev))
    ::close-atom-detail (swap! state assoc :detail-atom nil :detail nil :focus nil)
    ::popup-open-atom
    (let [atom (first args)]
      (open-popup! {:kind "node" :target {:atom atom}}))
    ::copy-focus-link
    (let [focus (first args)
          key   (focus-key focus)
          url   (-> js/window.location.href
                    js/URL.
                    (doto (-> .-searchParams (.set "focus" key)))
                    .-href)
          name (schema/target-brief (:target focus))
          md   (str "[" name "](" url ")")]
      (if (and (exists? js/navigator.clipboard) (.-writeText js/navigator.clipboard))
        (.writeText js/navigator.clipboard md)
        (js/alert md)))
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
      (when (js/confirm (str "Discard draft " id "?"))
        (swap! state update-in [:sidecar :annotations]
               (fn [anns] (vec (remove #(= (:id %) id) anns))))
        (save-sidecar!)))
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
                         (do (.preventDefault ev) (.stopPropagation ev)
                             (handle-event rep-data [::reply-cancel id])))))
    ::reply-send   (let [id (first args)
                         gen (:gen @state)
                         text (or (get-in @state [:ui :reply-text id]) "")
                         pending (get-in @state [:ui :reply-pending id])
                         comment (if (= text (:text pending)) pending
                                     {:by "human" :text text :at (now-iso)})]
                     (when (and (not (str/blank? text))
                                (not (get-in @state [:ui :reply-saving id])))
                       (swap! state update-in [:sidecar :annotations]
                              (fn [anns]
                                (mapv (fn [a]
                                        (if (= (:id a) id)
                                          (update a :comments
                                                  (fn [cs]
                                                    (conj (vec (remove #(= pending %) cs)) comment)))
                                          a)) anns)))
                       (swap! state assoc-in [:ui :reply-pending id] comment)
                       (swap! state assoc-in [:ui :reply-saving id] true)
                       (-> (save-sidecar!)
                           (.then (fn [saved?]
                                    (when (= gen (:gen @state))
                                      (swap! state update-in [:ui :reply-saving] dissoc id)
                                      (when saved?
                                        (swap! state update-in [:ui :reply-pending] dissoc id)
                                        (when (= text (get-in @state [:ui :reply-text id]))
                                          (swap! state update-in [:ui :reply-open] dissoc id)
                                          (swap! state update-in [:ui :reply-text] dissoc id)))))))))
    (js/console.warn "unhandled event" (pr-str tag))))

;; gap 53: accepts off-target file drops and marks the visible drop area.
(defn- wire-document-drop! []
  (let [depth (atom 0)
        feedback #(when-let [el (js/document.getElementById "drop")]
                    (.toggle (.-classList el) "dragging" %))
        files? #(some #{"Files"} (array-seq (or (some-> % .-dataTransfer .-types) #js [])))]
    (.addEventListener js/document "dragenter" (fn [ev] (when (files? ev) (swap! depth inc) (feedback true))))
    (.addEventListener js/document "dragleave" (fn [_] (swap! depth #(max 0 (dec %))) (when (zero? @depth) (feedback false))))
    (.addEventListener js/document "dragover" (fn [ev] (when (files? ev) (.preventDefault ev) (feedback true))))
    (.addEventListener js/document "drop"
      (fn [ev]
        (reset! depth 0) (feedback false)
        (when (and (not (.-defaultPrevented ev)) (not (get-in @state [:popup :open?])))
          (when-let [file (some-> ev .-dataTransfer .-files (aget 0))]
            (.preventDefault ev) (load-dropped-file! file)))))))

;; ---------- init ----------

(defn ^:export init! []
  (reset! root (js/document.getElementById "app"))
  (rdom/set-dispatch!
    (fn [replicant-data handler-event]
      (handle-event replicant-data handler-event)))
  (add-watch state ::rerender on-state-change)
  (rerender!)
  (wire-document-drop!)
  (let [{:keys [trace shape focus algorithm direction hide-sidebar? sync? layout-debug? overrides]} (query-params)
        shape-url (or shape (derive-shape-path trace))
        SC (aget js/window "CxcShapeCanvas")]
    (swap! state assoc :pending-focus focus
           :advanced? (= "1" (.getItem js/localStorage "cxc-shape-advanced")))
    ;; gap 62: private worker fields remain behind quoted JS contracts.
    (when sync? ((aget SC "enableSynchronousElk") elk-instance))
    (swap! state assoc :layout-debug? (boolean (or sync? layout-debug?)))
    (when sync? (swap! state assoc-in [:layout-options :overrides] overrides))
    (when algorithm (swap! state assoc-in [:layout-options :algorithm] algorithm))
    (when-not (contains? (set (js->clj (aget SC "algorithms"))) (get-in @state [:layout-options :algorithm]))
      (swap! state assoc-in [:layout-options :algorithm] "layered"))
    (when direction (swap! state assoc-in [:layout-options :direction] direction))
    (when-not (contains? (set (js->clj (aget SC "directions"))) (get-in @state [:layout-options :direction]))
      (swap! state assoc-in [:layout-options :direction] "DOWN"))
    (when hide-sidebar? (swap! state assoc :sidebar-hidden? true))
    (.toggle (.-classList js/document.body) "hide-sidebar" (:sidebar-hidden? @state))
    ;; gap 18: fall back when a URL/saved algorithm is not in this ELK build.
    (when elk-instance
      (-> ((aget SC "availableAlgorithms") elk-instance)
          (.then (fn [available]
                   (let [caps (set (js->clj (js/Array.from available)))]
                     (swap! state assoc :layout-capabilities caps)
                     (when-not (contains? caps (get-in @state [:layout-options :algorithm]))
                       (swap! state assoc-in [:layout-options :algorithm] "layered")))))))
    (when trace (load-trace! trace))
    (when shape-url (load-shape! shape-url)))
  (.addEventListener js/window "keydown" global-key!)
  (aset js/window "cxcViewerV2"
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
