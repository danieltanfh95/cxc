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
            [cxc.trace-schema :as schema]
            [cxc-viewer.shape-canvas-adapter :as shape-adapter] ;; H2-14
            [cxc-viewer.styles :as styles]
            [cxc-viewer.styles.palette :as palette])) ;; H2-09/H2-10

(declare heat-rebuild-and-apply! with-artifact-heat ;; H08: heat belongs to artifact succession.
         shape-svg-el shape-fit-scale shape-view apply-shape-view! wire-shape-pan! wire-transport-measure! highlight-shape! update-selection!
         global-key! popup-key! wire-document-drop! dump-layout! pt-pause! normalize-playback
         artifact-successor ;; H10: derived transitions finish at publication.
         drafts-controls open-popup!
         load-annotations! start-poll! next-ann-id
         class-resolver! painter-context apply-heatmap! apply-observations! apply-arrows! detail-pane toggle-sidebar!
         annotation-detail-pane annotation-table refresh-chat!
         open-annotation! resolution-submit! refresh-badges! popup-return-focus
         target-brief show-event! edge-descriptor focus-toolbar general-annotation
         focus-ready? focus-key apply-focus-from-url! modal-lifecycle! heat-element! heat-label!)

(defn send-kind? [value]
  (#{"send" "send_sync"} value))

(defn playback-flash-kind? [value]
  (#{"send" "send_sync" "arm" "no_arm"} value))

(defn activation-key? [value]
  (#{"Enter" " "} value))

(defn annotation-server-blocked? [value]
  (#{:unavailable :loading :verifying :disabled} value))

;; ---------- state ----------

;; H2-01: persistence is optional; unavailable storage yields absent strings.
(defn- storage-get [key]
  (try (.getItem js/localStorage key) (catch :default _ nil)))

(defn- storage-set! [key value]
  (try (.setItem js/localStorage key value) (catch :default _ nil)))

(defn- storage-remove! [key]
  (try (.removeItem js/localStorage key) (catch :default _ nil)))

;; H07: stored JSON crosses into one specified browse value here.
(defn- read-browse-preferences []
  (let [defaults {:query "" :statuses #{} :sort :id :descending? false}]
    (try
      (if-let [text (storage-get "cxc-ann-browse")] ;; H2-01
        (let [value                                                (-> text js/JSON.parse (js->clj :keywordize-keys true))
              {:keys [query statuses sort descending?] :as browse} (merge defaults value)]
          (when-not (and (map? value) (string? query)
                         (or (sequential? statuses) (set? statuses))
                         (every? schema/statuses statuses)
                         (or (string? sort) (keyword? sort))
                         (contains? #{:id :status :severity :kind :target :note :author :created_at} (keyword sort))
                         (boolean? descending?))
            (throw (js/Error. "invalid annotation browse preferences")))
          (assoc browse :statuses (set statuses) :sort (keyword sort)))
        defaults)
      (catch :default _ defaults))))

;; H2-02: stored text becomes finite pixels before entering view/splitter state.
(defn- read-detail-height [text viewport-height]
  (let [height (when (and (string? text) (not (str/blank? text))) (js/Number text))
        height (if (and (js/Number.isFinite height) (pos? height))
                 height (* 0.45 viewport-height))]
    (max 120 (min (* 0.65 viewport-height) height))))

(defonce ^:private state
  (atom {:gen            0
         :loading?       false
         :events         []
         :dropped-lines  []
         :shape          nil
         :sidecar        (schema/empty-sidecar "" "" "")
         :trace-name     nil
         :shape-name     nil
         :annotation-key nil   ;; the ?trace= value we POST sidecar updates against
         :artifact-key   nil
         :reviews        {}
         :annotation-disabled-reason nil
         :ann-browse     (read-browse-preferences) ;; H07
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
         :sidebar-hidden? (if-let [stored (storage-get "cxc-sidebar-collapsed")] ;; H2-01
                            (= "1" stored)
                            (<= (or (.-innerWidth js/window) 1200) 540))
         :layout-options {:algorithm (or (storage-get "cxc-shape-layout") "layered") ;; H2-01
                          :direction (or (storage-get "cxc-shape-direction") "DOWN") ;; H2-01
                          :showInit (= "1" (storage-get "cxc-shape-show-init"))} ;; H2-01
         :playback       {:cursor-seq nil   ;; nil = at-start, number = current event seq
                          :paused?    true
                          :speed      1}
         :heatmap        {:on?       false
                          :per-atom  {}
                          :per-send  {}
                          :atom-max  0
                          :send-max  0}
         ;; Default ON: obs edges participate in ELK layout regardless of this
         ;; toggle, so hiding them while arrows are on gives the viewer weird
         ;; routing with no explanation. Showing them makes the layout legible.
         :observations   {:on? (not= "0" (or (storage-get "cxc-shape-observations") "1"))
                          :atoms-with 0 :total 0}
         :arrows         {:on? (not= "0" (or (storage-get "cxc-shape-arrows") "1"))}
         ;; gap 47: same persistent first-use dismissal as legacy.
         :hint-dismissed? (= "1" (storage-get "cxc-shape-hint-dismissed")) ;; H2-01
         :legend-open?   (= "0" (or (storage-get "cxc-shape-legend-hidden") "1")) ;; H2-01
         :filters        {:msg-query   ""
                          :kinds-off   #{}
                          :atoms-off   #{}}
         :popup          {:open?       false
                          :target      {}
                          :kind        "node"
                          :note        ""
                          :editing-id  nil
                          :attachments []}
         :detail-height  (read-detail-height (storage-get "cxc-phase-pane-h") (or (.-innerHeight js/window) 900)) ;; H2-02; H2-01
         :detail-atom    nil
         :chat-excerpts  {}}))     ;; token → {:status :loading/:loaded/:error, :hits [...]}

(defn- next-gen! []
  (:gen (swap! state update :gen inc)))

(defn- commit!
  "Apply f to the state iff the gen is still `my-gen`. Lets a slow fetch
   or layout call bail silently when the user has already loaded a
   different artifact."
  [my-gen f]
  ;; H10: generation-owned artifact arrivals publish completed successor values.
  (swap! state (fn [s] (if (= my-gen (:gen s)) (artifact-successor s (f s)) s))))

;; H10: synchronous artifact resets/errors use the same transition seam.
(defn- update-artifact! [f & args]
  (swap! state (fn [s] (artifact-successor s (apply f s args)))))

;; H04: a local review edit publishes its data and dirty marker together.
(defn- edit-review! [f & args]
  (swap! state (fn [s] (assoc (apply f s args) :annotation-dirty? true))))

(defn- unsaved-review? [{:keys [annotation-dirty? popup ui]}]
  (or annotation-dirty?
      (and (:open? popup)
           (or (not= (or (:note popup) "") (or (:initial-note popup) ""))
               (not= (or (:attachments popup) []) (or (:initial-attachments popup) []))))
      (some (complement str/blank?) (vals (:reply-text ui)))))

(defn- unsaved-reviews? [{:keys [artifact-key reviews] :as snapshot}]
  (or (unsaved-review? snapshot)
      (some unsaved-review? (vals (dissoc reviews artifact-key)))))

(defn- before-unload! [event]
  (when (unsaved-reviews? @state)
    (.preventDefault event)
    (set! (.-returnValue event) "")))

(defn- update-unload-guard! [old-s new-s]
  (let [before? (boolean (unsaved-reviews? old-s))
        after?  (boolean (unsaved-reviews? new-s))]
    (when (not= before? after?)
      (if after?
        (.addEventListener js/window "beforeunload" before-unload!)
        (.removeEventListener js/window "beforeunload" before-unload!)))))

;; ---------- jsonl ----------

(defn- collect-jsonl-value [out line value]
  (cond
    (nil? value) (update out :dropped conj {:line line :reason "null"})
    (map? value) (update out :kept conj value)
    :else (update out :dropped conj {:line line :reason "not-object"})))

(defn- parse-jsonl-line [out index text]
  (if (str/blank? text)
    out
    (try
      (collect-jsonl-value out (inc index)
                          (-> text js/JSON.parse (js->clj :keywordize-keys true)))
      (catch :default _
        (update out :dropped conj {:line (inc index) :reason "parse-error"})))))

(defn- parse-jsonl [^String text]
  (reduce-kv parse-jsonl-line {:kept [] :dropped []}
             (str/split-lines (or text ""))))

(defn- with-sender-cls
  "Infer the sender class for each send/send_sync event (single-queue mode:
   the sender is the atom whose arm most recently fired). Matches the
   legacy viewer's `_inferredSenderCls` pass so edge-flash selectors line up."
  [events]
  (let [sorted (->> events (sort-by :seq) vec)]
    (loop [i        0
           last-arm nil
           out      (transient [])]
      (if (>= i (count sorted))
        (persistent! out)
        (let [{:keys [kind target_cls sender] :as e} (nth sorted i)]
          (cond
            (= "arm" kind) (recur (inc i) target_cls (conj! out e))
            (and (send-kind? kind)
                 (or (nil? sender) (zero? sender))) (recur (inc i) last-arm (conj! out (assoc e :_inferredSenderCls last-arm)))
            :else (recur (inc i) last-arm (conj! out e))))))))

;; ---------- playback helpers (seq-keyed) ----------

(defn- filter-events
  "Apply the user filters (kind off-set, atom off-set, message substring) to events.
   Return the preserved-order vector."
  [events {:keys [kinds-off atoms-off msg-query]}]
  (filterv (fn [{:keys [kind target_cls atom_cls msg]}]
             (and (not (contains? kinds-off kind))
                  (not (contains? atoms-off (or target_cls atom_cls)))
                  (str/includes? (-> msg (or "") str/lower-case)
                                 (-> msg-query (or "") str/lower-case))))
           events))

(defn- events-for-playback
  "The ordered sequence that playback walks. Honours current :filters so a
   kind / atom / msg filter narrows the cursor's domain. Keyed by seq
   so filter changes keep the cursor pointing at the same logical event."
  ([events] (events-for-playback events (:filters @state)))
  ([events filters]
    (->> (or filters {})
         (filter-events events)
         (sort-by :seq)
         vec)))

(defn- cursor-index-at-or-after [evs cursor-seq]
  (let [n (count evs)]
    (loop [i 0]
      (cond
        (>= i n)                              (dec n)
        (>= (:seq (nth evs i)) cursor-seq)    i
        :else                                 (recur (inc i))))))

(defn- cursor-index
  "The current index in `evs` for `cursor-seq`. Returns 0 when cursor-seq
   is nil or the list is empty. When the cursor-seq no longer matches an
   event (filter changed), returns the next event with seq >= cursor-seq,
   or the last index if none."
  [evs cursor-seq]
  (cond
    (zero? (count evs)) 0
    (nil? cursor-seq)   0
    :else (cursor-index-at-or-after evs cursor-seq)))

;; ---------- shape layout (imperative, mounted into #shape-container) ----------

(defonce ^:private elk-instance
  (when-let [E (aget js/window "ELK")]
    (new E)))

(defonce ^:private shape-render-epoch (atom 0))

;; H08: describe geometry from one value; building a request publishes nothing.
(defn- request-shape-layout [{:keys [shape events filters layout-options gen] :as snapshot} epoch]
  {:shape shape :events (filter-events events filters)
   :opts layout-options :context (painter-context snapshot)
   :gen gen :epoch epoch})

;; H08: camera initialization and its viewBox/readout belong to the layout result.
(defn- apply-layout-result! [container res current?]
  (when (and res (current?))
    (when-let [svg (.querySelector container "svg")]
      (reset! shape-view nil)
      (apply-shape-view! svg))))

;; H08: attachment/overlay effects share the mount's DOM-ownership gate.
(defn- install-mount-effects! [container current?]
  (when (and (current?) (.querySelector container "svg"))
    (let [snapshot @state]
      (wire-shape-pan!)
      (wire-transport-measure!)
      (apply-heatmap!)
      (apply-arrows!)
      (apply-observations! snapshot)
      (refresh-badges! snapshot)
      (update-selection! snapshot))))

;; H08: result accounting/debug output is separate from camera and attachments.
(defn- publish-layout-metrics! [snapshot res current?]
  (when (and res (current?))
    (commit! (:gen snapshot)
             #(assoc % :render-error nil :layout {:nodes (:node-count res) :edges (:edge-count res)}))
    (dump-layout! res snapshot)))

(defn- render-shape! [snapshot epoch]
  (when-let [container (js/document.getElementById "shape-container")]
    (let [{:keys [shape gen] :as request} (request-shape-layout snapshot epoch)
          ;; H08: one ownership predicate, shared with the adapter's actual mount site.
          current?                        #(and (= gen (:gen @state))
                                                (= epoch @shape-render-epoch)
                                                (identical? container (js/document.getElementById "shape-container")))]
      ;; H08: a trace remains renderable when filters hide every event.
      (when (and (current?) (or shape (seq (:events snapshot))))
        ;; H2-14: request/result projection is owned by the boundary adapter.
        (-> (shape-adapter/render-shape! container (assoc request :elk elk-instance :is-current? current?))
            (.then (fn [res]
                     (when res
                       (apply-layout-result! container res current?)
                       (install-mount-effects! container current?)
                       (publish-layout-metrics! snapshot res current?))))
            (.catch (fn [e]
                      (when (current?)
                        (commit! gen
                                 #(assoc % :render-error (str "shape render: " (.-message e))))))))))))

;; gap 62: legacy geometry schema for layout-metrics.py and headless snapshots.
(defn- dump-layout!
  ([res] (dump-layout! res @state))
  ;; H08: debug enablement is part of the geometry request's snapshot.
  ([res snapshot]
    (when (:layout-debug? snapshot)
      ;; H2-14: debug is an immutable projection; add view data without mutation.
      (let [svg   (shape-svg-el)
            debug (:debug res)
            el    (or (js/document.getElementById "layout-debug")
                      (let [el (js/document.createElement "script")]
                        (set! (.-id el) "layout-debug") (set! (.-type el) "application/json")
                        (.appendChild js/document.body el) el))]
        (set! (.-textContent el)
              (-> (assoc debug "view"
                         {:stageW (.-clientWidth svg) :stageH (.-clientHeight svg)
                          :scale (:scale @shape-view) :fit (shape-fit-scale svg)}) clj->js js/JSON.stringify))))))

;; ---------- shape zoom + pan (viewBox-based) ----------
;;
;; shape-canvas sets data-content-{x,y,w,h} on the painted svg; we zoom by
;; shrinking the viewBox around a center point. View state lives outside the
;; replicant atom so a drag doesn't trigger per-frame rerenders — only the
;; zoom-level readout updates when zoom() runs, and that's cheap.

(defonce ^:private shape-view (atom nil))   ;; {:scale, :cx, :cy}
(defonce ^:private pan-wired? (atom nil))
(defonce ^:private transport-measured? (atom nil))
(defonce ^:private transport-observer (atom nil))

(defn- shape-svg-el []
  (when-let [c (js/document.getElementById "shape-container")]
    (.querySelector c "svg")))

(defn- shape-content-box [svg]
  (shape-adapter/content-box svg)) ;; H2-14: one dataset reader at the boundary.

(defn- shape-fit-scale [svg]
  (let [{:keys [w h]} (shape-content-box svg)]
    (min (/ (.-clientWidth svg) w) (/ (.-clientHeight svg) h))))

(defn- shape-fit-view [svg]
  (let [{:keys [x y w h]} (shape-content-box svg)]
    {:scale (shape-fit-scale svg) :cx (+ x (/ w 2)) :cy (+ y (/ h 2))}))

(defn- shape-default-view [svg]
  (let [{:keys [x y w h]} (shape-content-box svg)
        fit               (shape-fit-scale svg)]
    {:scale (min fit 1.5) :cx (+ x (/ w 2)) :cy (+ y (/ h 2))}))

(defn- apply-shape-view!
  ([] (when-let [svg (shape-svg-el)] (apply-shape-view! svg)))
  ;; H08: a layout result applies camera geometry to its owned SVG.
  ([svg]
    (when (and (pos? (.-clientWidth svg)) (pos? (.-clientHeight svg)))
      (when (nil? @shape-view)
        (reset! shape-view (shape-default-view svg)))
      (let [{:keys [scale cx cy]} @shape-view
            vw                    (/ (.-clientWidth svg) scale)
            vh                    (/ (.-clientHeight svg) scale)]
        (.setAttribute svg "viewBox"
                       (str (- cx (/ vw 2)) " " (- cy (/ vh 2))
                            " " vw " " vh))
        (when-let [lvl (js/document.getElementById "shape-zoom-level")]
          (set! (.-textContent lvl) (str (js/Math.round (* scale 100)) "%")))))))

(defn- zoom-shape-at! [factor client-x client-y]
  (let [svg                         (shape-svg-el)
        {:keys [scale cx cy] :as v} @shape-view]
    (when (and svg v)
      (let [r  (.getBoundingClientRect svg)
            ns (max 0.1 (min 4 (* scale factor)))
            ox (- client-x (+ (.-left r) (/ (.-width r) 2)))
            oy (- client-y (+ (.-top r)  (/ (.-height r) 2)))
            ux (+ cx (/ ox scale))
            uy (+ cy (/ oy scale))]
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

;; COV-02/NEW-01: publish real transport height so legend bottom clears any wrap.
(defn- wire-transport-measure! []
  (when-let [transport (js/document.querySelector ".pt-transport")]
    (when-not (identical? transport @transport-measured?)
      (when-let [prior @transport-observer] (.disconnect prior))
      (reset! transport-measured? transport)
      ;; Guard against publishing "0px". offsetHeight can read 0 during mount
      ;; or during an initial ResizeObserver callback; writing that value pins
      ;; the var and defeats the `calc(var(..., 76px) + 20px)` fallback at
      ;; styles/shape.cljs:24, which collapses legend bottom into the
      ;; transport bar at narrow viewports (verified 2026-10-12).
      (let [publish #(when (identical? transport @transport-measured?)
                       (let [h (.-offsetHeight transport)]
                         (when (pos? h)
                           (.setProperty (.-style (.-documentElement js/document))
                                         "--pt-transport-h"
                                         (str h "px")))))]
        (publish)
        (when (exists? js/ResizeObserver)
          (let [obs (js/ResizeObserver. (fn [_] (publish)))]
            (.observe obs transport)
            (reset! transport-observer obs)))))))

(defn- wire-shape-pan!
  "Attach wheel + drag listeners to #shape-container. Idempotent."
  []
  (when-let [stage (js/document.getElementById "shape-container")]
    (when-not (identical? stage @pan-wired?)
      (reset! pan-wired? stage)
      (let [stage  stage
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
                                     (swap! shape-view (fn [{:keys [cx cy scale] :as v}]
                                                         (assoc v :cx (+ cx (* (/ (* (.-deltaX ev) unit) scale)))
                                                                :cy (+ cy (* (/ (* (.-deltaY ev) unit) scale))))))
                                     (apply-shape-view!))))))
                           #js {:passive false})
        (.addEventListener stage "mousedown"
                           (fn [ev]
                             (cond
                               (zero? (.-button ev)) (let [t   (.-target ev)
                                                           blk (and t (.closest t ".shape-block"))]
                                                       (cond
                                                         ;; gap 27: painter click/keyboard callbacks own graph activation.
                                                         (and blk (.-shiftKey ev)) nil
                                                         (and @shape-view (not (and t (.closest t ".pt-transport,#shape-zoom,#shape-legend,#atom-detail")))) (swap! state* assoc :active? true :moved? false
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
;; H10: watcher effects resolve names from the observed snapshot.
(defn- atom-name
  ([cls] (atom-name (class-resolver! (:shape @state) (:events @state)) cls))
  ([resolver cls]
    (or (some-> ((aget resolver "atomShapeForClass") cls) (aget "name"))
        ((aget resolver "displayLabel") cls))))

(defn- atom-name-of-name
  "Name-first resolver for inputs that are already user-visible atom
   names (detail-atom, DOM block names, annotation targets with :atom
   or :from_atom). Avoids the class-first atomShapeForClass remap when
   another atom's mangled class string happens to equal the input
   (round-9 raw-name-shadow identity collisions). Falls through to
   atom-name when the input isn't a declared atom name — covers legacy
   sidecars without `:class` and compiler-synthesized classes."
  ([nm] (atom-name-of-name (class-resolver! (:shape @state) (:events @state)) nm))
  ([resolver nm]
    (let [by-name (aget resolver "atomByName")]
      (or (some-> (when by-name (by-name nm)) (aget "name"))
          (atom-name resolver nm)))))

(defn- event-target-brief [n {:keys [event_kind atom_cls atom msg]}]
  (str "seq=" n (when event_kind (str " " event_kind))
       (when (or atom_cls atom) (str " atom=" (or atom_cls atom)))
       (when msg (str " msg=" msg))))

(defn- transition-target-brief [{:keys [atom from to on msg]}]
  (str "atom=" atom " " from "→" to
       (when (or on msg) (str " on " (or on msg)))))

;; gap 38: aliases and lifecycle edges precede their atom self-edge encoding.
(defn- target-brief [{:keys [trace_seq seq trace_seq_start from_seq trace_seq_end to_seq from_atom
                             src to_atom dst atom msg from to via arm] :as t}]
  (let [n   (or trace_seq seq)
        lo  (or trace_seq_start from_seq)
        hi  (or trace_seq_end to_seq)
        src (or from_atom src)
        dst (or to_atom dst)]
    (cond
      (some? n) (event-target-brief n t)
      (and (some? lo) (some? hi)) (str "seq=" lo ".." hi)
      (and atom (some? from) (some? to)) (transition-target-brief t)
      (and src dst) (str src "→" dst (when-let [m (or via msg arm)] (str " via " m)))
      :else (schema/target-brief t))))

;; H18: URL resolution supplies its snapshot's name function.
(defn- event-descriptor
  ([e] (event-descriptor e atom-name))
  ([{:keys [seq kind target target_cls msg]} name-for-class]
    {:kind "event" :target {:trace_seq seq :event_kind kind
                            :atom target :atom_cls (name-for-class target_cls) :msg msg}}))

(defn- edge-descriptor
  ([e] (edge-descriptor e nil))
  ([{:keys [src dst msgs]} msg]
    ;; H2-14: descriptors consume projected directions, including Map entries.
    {:kind "edge" :target {:from_atom (atom-name-of-name src)
                           :to_atom (atom-name-of-name dst)
                           :msg (or msg (->> msgs (sort-by second >) (map first) (str/join ", ")))}}))

(defn- wire-annotation! [el descriptor anchor annotation-only?]
  (.setAttribute el "data-ann-anchor" anchor)
  (let [open! (fn [ev]
                (when (identical? el (.closest (.-target ev) "[data-ann-anchor]"))
                  (.preventDefault ev) (.stopImmediatePropagation ev)
                  (open-popup! (js->clj descriptor :keywordize-keys true))))]
    (.addEventListener el "click" #(when (.-shiftKey %) (open! %)) true)
    (.addEventListener el "contextmenu" open! true)
    (when annotation-only?
      (.setAttribute el "tabindex" "0") (.setAttribute el "role" "button")
      (.setAttribute el "aria-label" (str "Annotate " (-> descriptor (js->clj :keywordize-keys true) :target target-brief))))
    (.addEventListener el "keydown"
                       #(when (or (= "ContextMenu" (.-key %))
                                  (and (.-shiftKey %) (= "F10" (.-key %)))
                                  (and (or annotation-only? (.-shiftKey %)) (activation-key? (.-key %))))
                          (open! %)) true)))

(defn- wire-keyboard! [el label]
  (.setAttribute el "tabindex" "0") (.setAttribute el "role" "button")
  (.setAttribute el "aria-label" label)
  (.addEventListener el "keydown"
                     (fn [ev]
                       (when (and (not (.-defaultPrevented ev)) (activation-key? (.-key ev)))
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

(defn- update-selection!
  ([] (update-selection! @state))
  ;; H10: use the completed successor for selection and centering effects.
  ([{:keys [shape events detail-atom] :as snapshot}]
    (when-let [svg (shape-svg-el)]
      (doseq [el (array-seq (.querySelectorAll svg ".shape-block-halo-outer"))] (.remove el))
      (let [resolver       (class-resolver! shape events)
            name-for-class #(atom-name-of-name resolver %)
            selected       (some-> detail-atom name-for-class)
            blocks         (array-seq (.querySelectorAll svg ".shape-block"))]
        (doseq [el blocks]
          ;; H2-14: DOM name/geometry are read as boundary values.
          (let [on? (= selected (name-for-class (shape-adapter/block-name el)))]
            (.toggle (.-classList el) "selected" (boolean (and selected on?)))
            (.toggle (.-classList el) "dim-when-selected" (boolean (and selected (not on?))))
            (when (and selected on?)
              (let [{:keys [x y w h]} (shape-adapter/element-box el)
                    halo              (js/document.createElementNS "http://www.w3.org/2000/svg" "rect")
                    overlay           (.querySelector svg ".cxc-obs-overlay")]
                (doseq [[k v] [["x" (- x 6)] ["y" (- y 6)]
                               ["width" (+ w 12)] ["height" (+ h 12)]
                               ["rx" 10] ["class" "shape-block-halo-outer"]]] (.setAttribute halo k v))
                (if overlay (.insertBefore svg halo overlay) (.appendChild svg halo))))))
        (when-let [el (->> blocks
                           (filter #(and (= selected (name-for-class (shape-adapter/block-name %)))
                                         (not (.contains (.-classList %) "container"))))
                           first)]
          (when-let [{:keys [scale cx cy]} @shape-view]
            (let [{:keys [x y] bw :w bh :h} (shape-adapter/element-box el)
                  w                         (/ (.-clientWidth svg) scale)
                  h                         (/ (.-clientHeight svg) scale)]
              (when-not (and (>= x (- cx (/ w 2))) (<= (+ x bw) (+ cx (/ w 2)))
                             (>= y (- cy (/ h 2))) (<= (+ y bh) (+ cy (/ h 2))))
                (swap! shape-view assoc :cx (+ x (/ bw 2)) :cy (+ y (/ bh 2)))
                (apply-shape-view!)))))))))

(defn- painter-context
  ([] (painter-context @state))
  ;; H08: requests capture heat counts/selection; callbacks retain their effect edges.
  ([snapshot]
    (let [{:keys [shape events detail-atom]
           {:keys [on? atom-max send-max] :as heatmap} :heatmap} snapshot
          resolver       ((aget (shape-adapter/host) "classResolver") (clj->js shape) (clj->js events))
          name-for-class #(atom-name-of-name resolver %)]
      ;; H2-14: callback arguments retain the JS contract; state gets value projections.
      #js {:selectedNode detail-atom :highlight highlight-shape!
           :selectNode (fn [n] (swap! state assoc :detail nil :focus nil :detail-atom
                                      (shape-adapter/node-name n)))
           :openEdge (fn [e rev] (let [directions (shape-adapter/read-directions e rev)
                                       e          (first directions)]
                                   (swap! state assoc :detail-atom nil
                                          :focus (edge-descriptor e)
                                          :detail {:kind :edge :directions directions})))
           :wireAnnotation wire-annotation! :wireKeyboardClick wire-keyboard!
           :heatOn on?
           :heatAtomCount #(let [node (shape-adapter/read-node %)]
                             (get-in heatmap [:per-atom (or (get-in node [:shape :name]) (name-for-class (:cls node)))] 0))
           :heatElement (fn [el c stroke?] (heat-element! el c stroke? atom-max))
           :heatLabel heat-label!
           :heatEdgeElement (fn [el e] (let [edge (shape-adapter/read-edge e)]
                                         (heat-element! el (get-in heatmap [:per-send (str (:src edge) "|" (:dst edge))] 0)
                                                        false send-max)))
           :atomName name-for-class :edgeTarget #(-> % shape-adapter/read-edge edge-descriptor :target clj->js)})))

;; gap 15: FIFO send closure and legacy six-event silent-drop window.
(defn- diagnostics [events]
  (loop [i        0
         pending  {}
         unclosed []
         drops    []]
    (if (= i (count events))
      {:unclosed (vec (mapcat pending unclosed))
       :silent-drops (->> drops
                          (sort-by (fn [{:keys [msg target_cls seq]}] [(if (re-find #"Clock" (str msg target_cls)) 1 0) seq]))
                          vec)}
      (let [{:keys [target msg kind] :as e} (nth events i)
            k                               [target msg]
            recv?                           (#{"recv" "recv_sync"} kind)
            base                            (-> msg (or "") (str/split #"\.") last)
            hit?                            (some (fn [{arm-kind :kind arm-target :target arm-msg :msg}]
                                                    (and (= "arm" arm-kind) (= arm-target target)
                                                         (or (= (-> arm-msg (or "") (str/split #"[.:With]") first) base)
                                                             (str/starts-with? (or arm-msg "") base))))
                                                  (subvec events (inc i) (min (count events) (+ i 7))))]
        (recur (inc i)
               (cond (send-kind? kind) (update pending k (fnil conj []) e)
                     recv? (update pending k #(vec (rest %))) :else pending)
               (if (and (send-kind? kind) (not (contains? pending k))) (conj unclosed k) unclosed)
               (if (and recv? (not (str/starts-with? (or msg "") "self.cxc_emit_")) (not hit?))
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

(defn- general-annotation [{:keys [focus detail-atom events filters] :as s}]
  (or focus
      (when detail-atom {:kind "node" :target {:atom (atom-name-of-name detail-atom)}})
      (let [evs (events-for-playback events filters)]
        (when (seq evs) (event-descriptor (nth evs (cursor-index evs (get-in s [:playback :cursor-seq]))))))
      {:kind "node" :target {}}))

(defn- toggle-sidebar! []
  (let [hidden? (not (:sidebar-hidden? @state))]
    (storage-set! "cxc-sidebar-collapsed" (if hidden? "1" "0")) ;; H2-01
    (.toggle (.-classList js/document.body) "hide-sidebar" hidden?)
    (swap! state assoc :sidebar-hidden? hidden?)
    (js/setTimeout apply-shape-view! 0)))

;; ---------- heatmap ----------
;;
;; gap 31: phase and transition counts share the loaded trace's atom scale.

(defonce ^:private resolver-cache (atom nil))
(defn- class-resolver! [shape events]
  ;; gaps 21/22/38: reuse the same name map throughout one artifact snapshot.
  (let [{cached-shape :shape cached-events :events :keys [value]} @resolver-cache]
    (if (and (identical? shape cached-shape) (identical? events cached-events) value)
      value
      (let [value ((-> js/window (aget "CxcShapeCanvas") (aget "classResolver")) (clj->js shape) (clj->js events))]
        (reset! resolver-cache {:shape shape :events events :value value})
        value))))

(defn- atom-shape-for-class [shape cls]
  (when cls
    (js->clj ((aget (class-resolver! shape (:events @state)) "atomShapeForClass") cls)
             :keywordize-keys true)))

(defn- atom-shape-for-name
  "Resolve a sidecar atom entry by its user-visible name. Prefer this
   over `atom-shape-for-class` for inputs that originate from the
   sidecar's own atom entries (shape-only block clicks, detail-atom
   state, phase lookups) — those are source names, and the class-first
   `atomShapeForClass` lookup remaps them when another atom's mangled
   class string happens to equal the name (round-8 raw-name-shadow
   CLJS collateral)."
  [shape nm]
  (when nm
    (let [resolver (class-resolver! shape (:events @state))
          by-name (aget resolver "atomByName")]
      (js->clj (when by-name (by-name nm)) :keywordize-keys true))))

(defn- heat-recompute
  "Return the heat counts for the current events + shape. Per-atom is a
   straight frequency over target_cls / atom_cls. Per-send reuses
   CxcShapeCanvas.buildGraph + .topology so sender inference stays
   aligned with the painted SVG."
  [events shape]
  (let [resolver     (class-resolver! shape events)
        resolve-atom (aget resolver "atomShapeForClass")
        display-name (aget resolver "displayLabel")
        per-atom     (persistent!
                       (reduce
                         (fn [m {:keys [target_cls atom_cls atom]}]
                           (let [cls (or target_cls atom_cls atom)
                                 nm  (when cls (or (some-> (resolve-atom cls) (aget "name"))
                                                   (display-name cls)))]
                             (if nm (assoc! m nm (inc (get m nm 0))) m)))
                         (transient {})
                         events))
        atom-max     (if (seq per-atom) (apply max 0 (vals per-atom)) 0)
        per-send     (if (and (shape-adapter/host) (seq events))
                       (let [disp     (fn [id] (if (str/includes? id ".")
                                                 (last (str/split id #"\."))
                                                 id))
                             ;; H2-14: graph/Map workspaces stop at the adapter.
                             topo-res (shape-adapter/topology
                                        {:shape shape :trace-loaded? true :events events
                                         :display-label disp :prettify-msg #(or % "")
                                         :atom-shape-for-class resolve-atom :show-init? true})]
                         (into {}
                               (map (fn [{:keys [src dst total]}] [(str src "|" dst) total])
                                    ;; Observation edges carry total=0 and would
                                    ;; wipe a real message count for the same
                                    ;; (src,dst) pair. Exclude by kind.
                                    (remove #(= "obs" (:kind %)) (:edges topo-res)))))
                       {})
        send-max     (if (seq per-send) (apply max 0 (vals per-send)) 0)]
    {:on?      false
     :per-atom per-atom
     :per-send per-send
     :atom-max atom-max
     :send-max send-max
     :phase-counts (shape-adapter/phase-heat shape events)})) ;; H2-14

(defn- heat-color-str [count max-count]
  (let [ratio (if (pos? max-count)
                (min 1.0 (/ count max-count))
                0.0)]
    (str "color-mix(in srgb, var(--p-paper3), var(--p-amber) "
         (.toFixed (* ratio 100) 3) "%)")))

(defn- shape-svg []
  (when-let [c (js/document.getElementById "shape-container")]
    (.querySelector c "svg")))

;; gaps 29/30/31: protected external direction fields, shared heat scale.
(defn- heat-element! [el hits stroke? max-count]
  (.add (.-classList el) (if stroke? "heat-stroke" "heat-fill"))
  (.setProperty (.-style el) "--heat-color" (heat-color-str hits max-count))
  (.setProperty (.-style el) "--heat-ink"
                (if (> (if (pos? max-count) (/ hits max-count) 0) 0.41)
                  "var(--heat-ink-hot)"
                  "var(--heat-ink-cold)"))
  (.setAttribute el "data-heat-count" (str hits))
  (when (get-in @state [:heatmap :on?])
    (let [tip   (or (.querySelector el "title")
                    (let [t (.createElementNS js/document "http://www.w3.org/2000/svg" "title")] (.appendChild el t) t))
          base  (or (aget (.-dataset tip) "heatBase") (.-textContent tip) "")
          total (count (:events @state))]
      (aset (.-dataset tip) "heatBase" base)
      (set! (.-textContent tip) (str base "\n" hits " events · "
                                     (.toFixed (if (pos? total) (* 100 (/ hits total)) 0) 1) "% of trace")))))

(defn- heat-label! [el heated]
  (.add (.-classList el) "heat-label")
  (.setProperty (.-style el) "--heat-ink" (.getPropertyValue (.-style heated) "--heat-ink")))

(defn- apply-heatmap! []
  (.toggle (.-classList js/document.body) "heatmap-on" (get-in @state [:heatmap :on?])))

;; Hide message edges when the arrows layer is off so observations can be read without clutter.
(defn- apply-arrows! []
  (.toggle (.-classList js/document.body) "arrows-off" (not (get-in @state [:arrows :on?]))))

(def ^:private svg-ns "http://www.w3.org/2000/svg")

(defn- draw-obs-stubs! [overlay block obs classify]
  (let [x (js/parseFloat (.getAttribute block "x"))
        y (js/parseFloat (.getAttribute block "y"))
        w (js/parseFloat (.getAttribute block "width"))
        g (.createElementNS js/document svg-ns "g")]
    (.setAttribute g "class" "cxc-obs-stubs")
    (.setAttribute g "pointer-events" "none")
    (dorun
     (map-indexed
      (fn [i {o-nm :name o-params :params}]
        (let [yi (+ y 14 (* i 12))
              line (.createElementNS js/document svg-ns "line")
              text (.createElementNS js/document svg-ns "text")
              cls  (classify o-nm)
              suffix (case cls
                       :external " obs-external"
                       :internal " obs-internal"
                       :unused   " obs-unused"
                       "")]
          (.setAttribute line "x1" (str (+ x w 2)))
          (.setAttribute line "y1" (str yi))
          (.setAttribute line "x2" (str (+ x w 6)))
          (.setAttribute line "y2" (str yi))
          (.setAttribute line "class" (str "cxc-obs-stub-line" suffix))
          (.setAttribute text "x" (str (+ x w 8)))
          (.setAttribute text "y" (str (+ yi 3)))
          (.setAttribute text "class" (str "cxc-obs-stub-label" suffix))
          (.setAttribute text "text-anchor" "start")
          (set! (.-textContent text) (if (seq o-params) (str o-nm "(…)") o-nm))
          (.appendChild g line)
          (.appendChild g text)))
      obs))
    (.appendChild overlay g)))

;; Three-way classification per (atom, observation): external if any entry in
;; `obs_calls` names it, internal if any entry in `obs_self_calls` names it,
;; else unused. External wins over internal when both are present (a reader
;; outside the atom raises the stub to the primary accent).
(defn- obs-class-index [shape]
  (let [bump (fn [m k v] (if (and k v) (assoc-in m [k v] :external) m))
        bump-s (fn [m k v]
                 (if (and k v)
                   (if (= :external (get-in m [k v])) m (assoc-in m [k v] :internal))
                   m))
        m-ext (reduce (fn [m {:keys [target obs]}] (bump m target obs))
                      {} (:obs_calls shape))
        m-both (reduce (fn [m {:keys [target obs]}] (bump-s m target obs))
                       m-ext (:obs_self_calls shape))]
    m-both))

;; Compound atoms emit two rects per nodeName (container in containerG, self-pill
;; in blockG). Anchor stubs to the OUTER rect (container when present, else sole)
;; so they sit outside the atom's bounds and never collide with child cards.
(defn- pick-anchor-blocks [svg]
  (let [picked (atom {})]
    (doseq [el (array-seq (.querySelectorAll svg ".shape-block"))]
      (let [nm (aget (.-dataset el) "nodeName")
            container? (.contains (.-classList el) "container")
            prior (get @picked nm)]
        (when (or (nil? prior)
                  (and container? (not (.contains (.-classList prior) "container"))))
          (swap! picked assoc nm el))))
    @picked))

;; Reuse one top-level overlay group so stubs paint above every atom and edge,
;; and so a single removeChild clears them before each redraw.
(defn- obs-overlay-group! [svg]
  (or (.querySelector svg ".cxc-obs-overlay")
      (let [g (.createElementNS js/document svg-ns "g")]
        (.setAttribute g "class" "cxc-obs-overlay")
        (.setAttribute g "pointer-events" "none")
        (.appendChild svg g)
        g)))

;; Walk the mounted SVG and tag each atom block whose shape carries observations,
;; so CSS can distinguish atoms with derived state from atoms without.
;;
;; Observation EDGES are no longer drawn here — they ride the main ELK edge
;; pipeline in shape-canvas.js (same orthogonal routing, same arrow-marker,
;; same label placement as message edges). Stubs stay viewer-side because
;; they anchor to atom frames rather than route between them.
(defn- apply-observations!
  ([] (apply-observations! @state))
  ([{:keys [shape observations]}]
    (.toggle (.-classList js/document.body) "observations-on" (boolean (:on? observations)))
    (when-let [svg (shape-svg)]
      (let [overlay (obs-overlay-group! svg)
            by-name (into {} (map (fn [a] [(:name a) a]) (:atoms shape)))]
        (set! (.-textContent overlay) "")
        ;; Tag every rect for the atom so .has-observations stroke applies to both frames.
        (doseq [el (array-seq (.querySelectorAll svg ".shape-block"))]
          (let [nm (aget (.-dataset el) "nodeName")
                obs (:observations (get by-name nm))]
            (.toggle (.-classList el) "has-observations" (boolean (seq obs)))
            (when (seq obs) (.setAttribute el "data-obs-count" (str (count obs))))))
        (let [anchors (pick-anchor-blocks svg)
              cls-ix  (obs-class-index shape)]
          ;; Stubs: one tick-mark per observation on the atom's own frame.
          ;; Each stub carries its classification (external / internal / unused).
          (doseq [[nm el] anchors]
            (when-let [obs (seq (:observations (get by-name nm)))]
              (draw-obs-stubs! overlay el obs
                               (fn [o-nm] (or (get-in cls-ix [nm o-nm]) :unused))))))))))

;; H08: observation counts belong to the artifact snapshot, like heat counts.
(defn- with-artifact-observations [{:keys [shape] :as snapshot}]
  (let [atoms       (:atoms shape)
        atoms-with  (count (filter #(seq (:observations %)) atoms))
        total       (reduce + 0 (map #(count (:observations %)) atoms))
        calls       (:obs_calls shape)
        edge-count  (count calls)
        call-sites  (reduce + 0 (map #(or (:call_count %) 0) calls))
        cls-ix      (obs-class-index shape)
        ;; Count every declared observation once by classification.
        tally       (reduce
                      (fn [acc {:keys [name observations]}]
                        (reduce (fn [acc {o-nm :name}]
                                  (update acc (or (get-in cls-ix [name o-nm]) :unused)
                                          (fnil inc 0)))
                                acc observations))
                      {:external 0 :internal 0 :unused 0}
                      atoms)]
    (update snapshot :observations assoc
            :atoms-with atoms-with :total total
            :edges edge-count :call-sites call-sites
            :external (:external tally) :internal (:internal tally)
            :unused (:unused tally))))

(defn- refresh-badges!
  ([] (refresh-badges! @state))
  ;; H10: review effects take the same snapshot as the watcher.
  ([snapshot]
    (when-let [svg (shape-svg)]
      ((-> js/window (aget "CxcShapeCanvas") (aget "renderAnnotationBadges")) svg
                                                                              (->> (get-in snapshot [:sidecar :annotations])
                                                                                   (map #(assoc % :effective_status (schema/effective-status %)))
                                                                                   clj->js)
                                                                              (class-resolver! (:shape snapshot) (:events snapshot)) (painter-context snapshot))))) ;; H08

;; H08: whole-artifact counts are derived before publication, independent of layout/filters.
(defn- with-artifact-heat [{:keys [events shape] :as snapshot}]
  (assoc snapshot :heatmap
         (assoc (heat-recompute events shape) :on? (get-in snapshot [:heatmap :on?]))))

;; H08: preserve the explicit console/debug rebuild hook; render never invokes it.
(defn- heat-rebuild-and-apply! []
  (swap! state with-artifact-heat)
  (apply-heatmap!))

;; ---------- playback: imperative SVG flashes ----------
;;
;; Replicant owns the transport DOM; shape-canvas.js owns the SVG DOM.

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
                      ;; Obs edges share .shape-edge + data-src/data-dst with
                      ;; message edges. :not(.shape-obs-edge) keeps playback
                      ;; from flashing a static obs route as runtime traffic.
                      (str ".shape-edge:not(.shape-obs-edge)[data-src=\"" (esc src-id)
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
          (letfn [(step! [now]
                    (let [t (/ (- now t0) dur)]
                      (if (>= t 1)
                        (.remove dot)
                        (let [pt (.getPointAtLength edge (* t total))]
                          (.setAttribute dot "cx" (.-x pt))
                          (.setAttribute dot "cy" (.-y pt))
                          (js/requestAnimationFrame step!)))))]
            (js/requestAnimationFrame step!))))
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
  (let [{:keys [events] {:keys [cursor-seq]} :playback} @state]
    (when-let [evs (not-empty (events-for-playback events))]
      (let [i   (cursor-index evs cursor-seq)
            cap (dec (count evs))
            i2  (cond
                  forward?       (min cap (inc i))
                  (pos? i)       (dec i)
                  :else          0)
            e   (nth evs i2)]
        (swap! state assoc-in [:playback :cursor-seq] (:seq e))
        (when (playback-flash-kind? (:kind e))
          (pt-flash-event! e forward?))))))

;; H10: stopping a scheduled frame is an effect, separate from paused state.
(defn- cancel-playback-frame! []
  (when-let [h @pt-raf-handle]
    (js/cancelAnimationFrame h)
    (reset! pt-raf-handle nil)))

(defn- pt-pause! []
  (swap! state assoc-in [:playback :paused?] true)
  (cancel-playback-frame!))

(defn- pt-play! []
  (let [{:keys [events] {:keys [cursor-seq]} :playback} @state]
    (when-let [evs (not-empty (events-for-playback events))]
      (when (= cursor-seq (:seq (last evs)))
        (swap! state assoc-in [:playback :cursor-seq] nil))
      (swap! state update :playback assoc :paused? false :last-tick nil)
      (let [tag (swap! pt-run-tag inc)]
        (letfn [(tick! [now]
                  (when (= tag @pt-run-tag)
                    (let [{:keys [events]
                           {:keys [cursor-seq speed last-tick paused?]} :playback}
                          @state
                          evs (events-for-playback events)
                          cap (dec (count evs))
                          i   (cursor-index evs cursor-seq)]
                      (when-not paused?
                        (cond
                          (or (empty? evs) (>= i cap)) (do (swap! state assoc-in [:playback :cursor-seq] (some-> evs last :seq))
                                                           (when (and (= 1 (count evs)) (nil? cursor-seq)) (pt-flash-event! (first evs) true))
                                                           (pt-pause!))
                          (nil? last-tick) (do (swap! state assoc-in [:playback :last-tick] now)
                                               (reset! pt-raf-handle (js/requestAnimationFrame tick!)))
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
                                        (when (playback-flash-kind? (:kind e))
                                          (pt-flash-event! e true)))
                                      (if (>= i2 cap)
                                        (pt-pause!)
                                        (reset! pt-raf-handle (js/requestAnimationFrame tick!))))
                                    (reset! pt-raf-handle (js/requestAnimationFrame tick!)))))))))]
          (reset! pt-raf-handle (js/requestAnimationFrame tick!)))))))

;; H10; gap 63: normalize inside the filter transition, including empty/one-event domains.
(defn- normalize-playback [{:keys [events filters] :as snapshot}]
  (let [evs    (events-for-playback events filters)
        cursor (get-in snapshot [:playback :cursor-seq])
        e      (when (seq evs) (nth evs (cursor-index evs cursor)))]
    (cond-> snapshot
      (some? cursor) (assoc-in [:playback :cursor-seq] (:seq e))
      (or (empty? evs) (= 1 (count evs)) (and cursor e (= (:seq e) (:seq (last evs))))) (assoc-in [:playback :paused?] true))))

;; H10: filters and their seq-keyed playback domain are one publication.
(defn- update-filters! [f & args]
  (swap! state (fn [s]
                 (let [next-s (apply update s :filters f args)]
                   (if (= (:filters s) (:filters next-s)) next-s (normalize-playback next-s))))))

(defn- pt-rewind! []
  (pt-pause!)
  (pt-clear-flashes!)
  (swap! state assoc-in [:playback :cursor-seq] nil))

(defn- pt-scrub-to-index! [i]
  (pt-pause!)
  (when-let [evs (not-empty (events-for-playback (:events @state)))]
    (let [cap (dec (count evs))
          i'  (max 0 (min cap i))
          e   (nth evs i')]
      (pt-clear-flashes!)
      (swap! state assoc-in [:playback :cursor-seq] (:seq e))
      (when (playback-flash-kind? (:kind e))
        (pt-flash-event! e false)))))

;; ---------- view ----------

(defn- stats-row [label value]
  [:div.stat-row [:span.k label] [:span.v value]])

;; gap 51: equivalent secondary anchors for HTML labels and review rows.
(defn- annotation-anchor [descriptor]
  {:click [::annotate-anchor descriptor] :contextmenu [::annotate-anchor descriptor]
   :keydown [::annotate-anchor descriptor]})

(defn- filters-pane [{:keys [events filters]}]
  (let [kinds                         (sort (into #{} (keep :kind events)))
        atoms                         (sort (into #{} (keep #(or (:target_cls %) (:atom_cls %)) events)))
        {:keys [kinds-off atoms-off]} (or filters {})
        kinds-off                     (or kinds-off #{})
        atoms-off                     (or atoms-off #{})]
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
          [:label.filter {:data-atom a :on (annotation-anchor {:kind "node" :target {:atom (atom-name-of-name a)}})}
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

;; H13: establish the HTTP value before the excerpt view consumes text.
(defn- read-chat-excerpts [j]
  (let [hits (js->clj j :keywordize-keys true)]
    (when-not (and (array? j)
                   (every? #(and (map? %) (string? (:text %))) hits))
      (throw (js/Error. "chat excerpt requires an array of objects with string text")))
    hits))

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
                     (let [hits (read-chat-excerpts j)] ;; H13
                       (swap! state assoc-in [:chat-excerpts token]
                              {:status :loaded :hits hits}))))
            (.catch (fn [e]
                      (swap! state assoc-in [:chat-excerpts token]
                             {:status :error :message (.-message e)}))))))))

(defn- chat-excerpt-hits [token hits]
  (let [expanded? (get-in @state [:ui :excerpt-expanded token])
        compact?  (or (> (count hits) 1) (> (reduce + 0 (map #(count (:text %)) hits)) 200)
                      (some #(> (-> % :text str/split-lines count) 5) hits))]
    (if (empty? hits)
      [:div.chat-excerpt-loading (str "waiting for agent reply (token " token ")…")]
      [:div
       [:div.chat-excerpt-body {:class (str (when compact? "compact ") (when expanded? "expanded"))}
        (for [{:keys [timestamp session text]} hits]
          [:div.chat-excerpt-hit
           [:div.chat-excerpt-meta (str timestamp " · " session)]
           [:div.chat-excerpt-text {:title text} text]])]
       (when compact?
         [:button.btn.chat-excerpt-toggle {:aria-expanded (boolean expanded?)
                                           :on {:click [::excerpt-toggle token]}} (if expanded? "show less" "show more")])])))

(defn- chat-excerpt-view [token]
  (let [{:keys [status message hits] :as cur} (get-in @state [:chat-excerpts token])]
    (cond
      (nil? cur)              [:div.chat-excerpt-loading "resolving chat excerpt from the agent…"]
      (= :loading status) [:div.chat-excerpt-loading "resolving chat excerpt from the agent…"]
      (= :error status)   [:div.chat-excerpt-error (str "chat lookup failed: " message)]
      :else (chat-excerpt-hits token hits))))

(defn- comment-item [{:keys [by text at kind token linked_commit commit]}]
  (if (and (= "pending" kind) token)
    ;; H03: view construction describes the excerpt; rerender! owns fetching.
    [:li.ann-comment
     [:div
      [:span.c-author (or by "?")] " "
      [:span.c-at at] (when (or linked_commit commit) [:span.c-commit (str " · " (or linked_commit commit))]) " · pending · token:" token]
     (chat-excerpt-view token)
     [:button.btn {:on {:click [::chat-refresh token]}} "refresh excerpt"]]
    [:li.ann-comment
     [:span.c-author (or by "?")] " "
     [:span.c-at at] (when (or linked_commit commit) [:span.c-commit (str " · " (or linked_commit commit))]) [:br]
     [:span.c-body text]]))

(defn- comments-pane [{:keys [id comments]}]
  (let [replying? (get-in @state [:ui :reply-open id])
        draft     (get-in @state [:ui :reply-text id] "")]
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

(defn- annotation-row [{:keys [id note severity kind] :as ann}]
  ;; ANN-GAP-02: no inline transition/reply buttons; click row to open detail.
  ;; ANN-GAP-03: ann-id is flat bold text, not a button-chip.
  ;; ANN-GAP-06: row collapses to id + severity + kind + 90-char note excerpt.
  (let [status (schema/effective-status ann)
        n      (or note "")
        trunc  (if (> (count n) 90) (str (subs n 0 90) "…") n)]
    [:li.ann-row {:data-ann-id   id
                  :data-status   status
                  :data-severity severity :tabindex 0
                  :role          "button"
                  :aria-label (str "View " id ". Shift-click or right-click to annotate this target.")
                  :on (merge (annotation-anchor (select-keys ann [:kind :target]))
                             {:click [::ann-row-click id (select-keys ann [:kind :target])]
                              :keydown [::ann-row-key id (select-keys ann [:kind :target])]})}
     [:div.ann-head
      ;; gap-01: legacy carries a 7px status swatch as well as the left stripe.
      [:span.ann-status-swatch {:aria-hidden true}]
      [:b.ann-id id]
      (when severity [:span.ann-sev severity])
      (when (and kind (not= "note" kind)) [:span.ann-kind kind])]
     [:div.ann-note trunc]
     (when (= "draft" status) (draft-actions ann))]))

(defn- annotations-pane [{:keys [sidecar filter-status annotation-status annotation-error annotation-disabled-reason] :as s}]
  (let [all      (:annotations sidecar)
        shown    (schema/filter-annotations all (when filter-status
                                                  {:status filter-status}))
        counts   (->> all
                      (map schema/effective-status)
                      frequencies)
        drafts-n (or (counts "draft") 0)]
    [:section.annotations
     [:details.ann-section {:open true}
      [:summary.ann-header
       ;; gap-04: count inherits the condensed label font inside one summary row.
       [:span.ann-summary-row
        [:span.title.ann-summary-label "annotations "
         (when (or (seq all) (annotation-server-blocked? annotation-status))
           [:span.ann-count (case annotation-status
                              :unavailable "server unavailable"
                              :loading "loading…"
                              :verifying "verifying identity…"
                              :disabled "disabled"
                              (str "(" (count all) " total"
                                   (when (pos? drafts-n)
                                     (str " · " drafts-n (if (= 1 drafts-n) " draft" " drafts")))
                                   ")"))])]
        [:button.btn.ann-table-toggle {:title "Browse all annotations in a table"
                                       :aria-pressed (= :table (get-in s [:detail :kind]))
                                       :on {:click [::ann-table-open]}}
         [:span {:aria-hidden true} "⊞"] " table"]]]
      (when (annotation-server-blocked? annotation-status)
        [:div.ann-unavailable {:role "status"}
         (case annotation-status
           :loading "loading annotations…"
           :verifying "verifying artifact identity…"
           :disabled annotation-disabled-reason
           (str "annotation server unavailable · " annotation-error))
         (when (contains? #{:loading :unavailable} annotation-status)
           [:button.btn {:on {:click [::annotations-retry]}} "retry connection"])])
      [:ul.ann-list
       (if (empty? shown)
         [:li.ann-empty (when-not (annotation-server-blocked? annotation-status)
                          (if (seq all) "no annotations"
                              "none yet · click an atom, edge, or event, then \"+ annotate\""))]
         (for [status (if filter-status [filter-status] ["draft" "open" "in_progress" "addressed" "verified" "wontfix" "stale"])
               :let [group (filter #(= status (schema/effective-status %)) shown)] :when (seq group)]
           [:li.ann-status-group {:data-ann-status status :data-status status}
            ;; gap-02/gap-03: full-width heading carries the effective status color.
            [:span.ann-status-heading {:data-status status}
             (str (-> status (str/replace "_" " ") str/upper-case) " " (count group))]
            [:ul.ann-list (map annotation-row group)]]))]
      ;; gap-05: equal-width legacy footer controls, retaining the v2 actions.
      [:div.annotation-controls {:aria-label "Annotation controls"}
       [:button.btn.primary {:disabled (annotation-server-blocked? annotation-status) :on {:click [::popup-open-blank]}} "+ annotate"]
       [:button.btn {:on {:click [::ann-export]}} "export"]]
      (drafts-controls s)]]))


;; gaps 32/33: combined status filtering, search, sort and range selection.
(defn- browse-annotations [s]
  (let [{:keys [query statuses sort descending?]} (:ann-browse s)
        key                                       sort
        q                                         (str/lower-case query)] ;; H07: the preference seam owns defaults and types.
    (cond->> (get-in s [:sidecar :annotations])
      true (filter (fn [{:keys [id target note severity kind author proposed_fix resolution] :as ann}]
                     (and (or (empty? statuses) (contains? statuses (schema/effective-status ann)))
                          (str/includes? (-> (str id " " (target-brief target) " "
                                                  note " " severity " " kind " " author " " proposed_fix " " resolution)
                                             str/lower-case) q))))
      true (sort-by #(case key :status (schema/effective-status %) :target (target-brief (:target %))
                           :severity (or (:severity %) "") (or (get % key) "")))
      descending? reverse)))

(defn- set-browse! [f]
  (swap! state update :ann-browse f)
  (storage-set! "cxc-ann-browse" (-> @state :ann-browse clj->js js/JSON.stringify))) ;; H2-01

(defn- annotation-table [{:keys [ann-selected sidecar] :as s}]
  (let [shown    (vec (browse-annotations s))
        selected ann-selected
        eligible (filter #(and (contains? selected (:id %)) (= "addressed" (schema/effective-status %)))
                         (:annotations sidecar))]
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
      [:tbody (for [{:keys [id severity kind target note author created_at] :as a} shown]
                [:tr {:data-ann-id id}
                 [:td [:input {:type "checkbox" :checked (contains? selected id) :aria-label (str "Select " id)
                               :on {:click [::ann-select id]}}]]
                 [:td [:button.btn {:on {:click [::ann-open id]}} id]]
                 [:td (schema/effective-status a)] [:td severity] [:td kind]
                 [:td (target-brief target)] [:td note] [:td author] [:td created_at]])]]]))

(defn- refresh-chat! [ann]
  (doseq [c (:comments ann) :when (and (= "pending" (:kind c)) (:token c))]
    (resolve-chat-excerpt! (:token c) true)))

(defn- open-annotation! [id]
  (let [s @state]
    (when-let [a (->> (get-in s [:sidecar :annotations])
                      (filter #(= id (:id %)))
                      first)]
      (swap! state assoc :detail-atom nil :focus (select-keys a [:kind :target]) :detail {:kind :annotation :id id}
             :ann-return (select-keys s [:detail :detail-atom :focus]))
      (refresh-chat! a))))

;; gaps 34/35: full review and saved attachments, including metadata.
(defn- annotation-detail-pane [s id]
  (when-let [{:keys [severity target note proposed_fix attachments resolution author created_at updated_at commit verified_by verified_at] :as a} (->> (get-in s [:sidecar :annotations])
                                                                                                                                                       (filter #(= id (:id %)))
                                                                                                                                                       first)]
    [:div.ann-full-detail
     [:button.btn {:on {:click [::ann-back]}} "back"]
     [:h3 (str id " · " (schema/effective-status a) " · " severity)]
     [:p (target-brief target)]
     (focus-toolbar (select-keys a [:kind :target]))
     [:div.ann-full-note {:style {:white-space "pre-wrap"}} note]
     (when proposed_fix [:div [:h4 "proposed fix"] [:pre.raw-event proposed_fix]])
     [:div.saved-attachments
      (for [{:keys [kind path title url]} attachments]
        (if (= "image" kind)
          [:a {:href path :target "_blank" :rel "noopener"}
           [:img {:src path :alt (or title "attachment")}]]
          [:a {:href url :target "_blank" :rel "noopener"} (or title url)]))]
     (when resolution [:div [:h4 "resolution"] [:pre.raw-event (-> resolution clj->js (js/JSON.stringify nil 2))]])
     [:p (str "by " author " · created " created_at " · updated " updated_at)]
     (when commit [:p (str "commit " commit)])
     (when verified_by [:p (str "verified by " verified_by " · " verified_at)])
     (if (= "draft" (schema/effective-status a)) (draft-actions a) (transition-buttons a))
     (comments-pane a)]))

;; gap 37: LLM-ready open-review export, preserving full note/fix text.
(defn- annotation-export [{:keys [events trace-name shape-name] :as s}]
  (str/join "\n"
            (concat ["### Trace annotations from the human reviewer" ""]
                    (when-let [c (get-in s [:sidecar :commit])] (when (seq c) [(str "Commit under review: " c)]))
                    [(str (if (seq events) "Trace artifact: " "Shape artifact: ")
                          (or (get-in s [:sidecar :source]) trace-name shape-name)) ""]
                    (mapcat (fn [{:keys [id severity kind target note proposed_fix]}] [(str "**" id " (" (->> [severity kind] (remove nil?) (str/join ", ")) ")** — target: "
                                                                                            (target-brief target))
                                                                                       (str "Note: " note)
                                                                                       (when proposed_fix (str "Proposed fix: " proposed_fix)) ""])
                            (filter #(= "open" (schema/effective-status %)) (get-in s [:sidecar :annotations]))))))

(defn- copy-text! [text]
  (if-let [clip (.-clipboard js/navigator)]
    (-> (.writeText clip text) (.catch (fn [_] (js/prompt "Copy text" text))))
    (js/prompt "Copy text" text)))

(defn- legend-card [open? {:keys [heatmap observations arrows events]}]
  (let [heat-on?       (boolean (:on? heatmap))
        obs-on?        (boolean (:on? observations))
        arrows-on?     (boolean (:on? arrows))
        heat-disabled? (zero? (count events))
        {:keys [atom-max send-max]} heatmap
        {:keys [atoms-with total edges call-sites external internal unused]} observations
        obs-disabled?  (zero? (or total 0))
        layers-on-count (cond-> 0 heat-on? inc obs-on? inc arrows-on? inc)]
    (if open?
      [:div#shape-legend
       [:button#shape-legend-close {:aria-label "Hide legend" :title "hide legend" :on {:click [::legend-toggle false]}} "×"]
       [:div.legend-group.legend-layers
        [:h5 "layers"]
        [:label.legend-layer-row
         [:input {:type "checkbox" :checked heat-on? :disabled heat-disabled?
                  :on {:change [::heat-toggle]}}]
         [:span.legend-layer-name "heatmap"]
         (when heat-on?
           [:span.legend-layer-meta (str "max atom " atom-max " · max send " send-max)])]
        (when heat-on?
          [:div.legend-heat-ramp {:aria-label "Heat scale: low to high activity"}])
        [:label.legend-layer-row
         [:input {:type "checkbox" :checked arrows-on?
                  :on {:change [::arrows-toggle]}}]
         [:span.legend-layer-name "arrows"]
         [:span.legend-layer-meta (if arrows-on? "message edges shown" "message edges hidden")]]
        [:label.legend-layer-row
         [:input {:type "checkbox" :checked obs-on? :disabled obs-disabled?
                  :on {:change [::obs-toggle]}}]
         [:span.legend-layer-name "observations"]
         [:span.legend-layer-meta
          (if (pos? (or total 0))
            (str atoms-with " atom" (when (not= 1 atoms-with) "s") " · "
                 total " exposed"
                 (when (pos? (or edges 0))
                   (str " · " call-sites " read" (when (not= 1 (or call-sites 0)) "s"))))
            "none in shape")]]
        (when (and obs-on? (pos? (or total 0)))
          [:div.legend-obs-tally
           {:title "Classification by call-site: external reader, same-atom reader, or no reader."}
           [:span.legend-obs-tally-cell.obs-external (str (or external 0) " external")]
           [:span.legend-obs-tally-cell.obs-internal (str (or internal 0) " internal")]
           [:span.legend-obs-tally-cell.obs-unused  (str (or unused 0) " unused")]])]
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
        [:div.legend-row
         {:title "Observation read by at least one atom other than its owner. Stub drawn in the primary accent; cross-atom route shown as a solid accent line."}
         [:span.legend-line.legend-obs-external] "external obs"]
        [:div.legend-row
         {:title "Observation read only by its owning atom (self.<obs> or bare <obs> inside the same atom)."}
         [:span.legend-line.legend-obs-internal] "internal obs"]
        [:div.legend-row
         {:title "Observation declared but not read anywhere in the program. Candidate for removal."}
         [:span.legend-line.legend-obs-unused] "unused obs"]
        [:div.legend-row {:title "Two-way atoms share a line with an arrowhead at each end; each label arrow shows its message direction."} "▲▼ label arrow = direction"]
        [:div.legend-row {:title "The top message plus N other names in this direction. Hover the label for the full list."} "+N = more messages"]]
       [:div.legend-group
        [:h5 "phases"]
        [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-amber)"}}] "initial"]
        [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper)"}}] "reached"]
        [:div.legend-row [:span.legend-swatch {:style {:background "var(--p-paper3)"}}] "end"]
        [:div.legend-row {:title "Not reachable from the initial phase through declared transitions."} "dashed = unreached"]
        [:div.legend-row {:title "N declared arms leave the phase unchanged."} "self N = N self-transitions"]]
       [:div.legend-group [:h5 "ownership"] [:div.legend-row "box = owner holds atoms in non-ctor fields"]]]
      [:button#shape-legend-reopen {:title "show legend" :data-layers-active (str (pos? layers-on-count))
                                    :on {:click [::legend-toggle true]}}
       "legend"
       (when (pos? layers-on-count)
         [:span.legend-layer-badge (str " · " layers-on-count)])])))

(defn- transport-bar [{:keys [events playback]}]
  (when-let [evs (not-empty (events-for-playback events))]
    (let [{:keys [cursor-seq paused? speed]} playback
          total                              (count evs)
          i                                  (cursor-index evs cursor-seq)
          e                                  (nth evs i)
          seq                                (:seq e)
          msg                                (or (:msg e) "")]
      ;; `:replicant/key` keeps the transport's identity stable across
       ;; legend-open? toggles. Without it, Replicant rebuilds the sibling
       ;; chain when `legend-card` returns a button vs. div, which detaches
       ;; the old ResizeObserver and leaves `--pt-transport-h` stale (round-2
       ;; narrow review B1).
       [:div.pt-transport {:replicant/key "pt-transport"}
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
        [:span.pt-count (str "(" (inc i) "/" total ")")]]])))

(defonce ^:private phase-render-epoch (atom 0))
(defn- render-phase-graph! [snapshot epoch]
  ;; H10: queued phase work uses its snapshot and the watcher's invalidation epoch.
  (when-let [container (js/document.getElementById "phase-graph-container")]
    (let [{:keys [gen detail-atom shape events heatmap]} snapshot
          current?                                       #(and (= epoch @phase-render-epoch) (= gen (:gen @state)) (= detail-atom (:detail-atom @state))
                                                               (identical? container (js/document.getElementById "phase-graph-container")))
          resolver                                       (class-resolver! shape events)
          by-name                                        (aget resolver "atomByName")
          atom                                           (js->clj
                                                           (or (when by-name (by-name detail-atom))
                                                               ((aget resolver "atomShapeForClass") detail-atom))
                                                           :keywordize-keys true)]
      (when (current?)
        (set! (.-innerHTML container) "")
        (when atom
          ;; H2-14: phase requests share the documented callback/result boundary.
          (-> (shape-adapter/render-phase! container
                                           {:atom atom :elk elk-instance :is-current? current?
                                            :context #js {:wireAnnotation wire-annotation! :heatOn (:on? heatmap)
                                                          :heatElement (fn [el c stroke?] (heat-element! el c stroke? (:atom-max heatmap)))
                                                          :heatLabel heat-label!
                                                          :phaseCount #(get-in heatmap [:phase-counts "phases" (js/JSON.stringify #js [(:name atom) %])] 0)
                                                          :transitionCount #(get-in heatmap [:phase-counts "transitions" (js/JSON.stringify #js [(:name atom) (aget % "from") (aget % "to") (aget % "msg")])] 0)}})
              (.catch (fn [e]
                        (when (current?)
                          (set! (.-textContent container)
                                (str "phase layout failed: " (.-message e))))))))))))


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
  (let [nm     (atom-name-of-name cls)
        es     (:events s)
        sends  (filter #(send-kind? (:kind %)) es)
        counts (->> sends
                    (map (fn [{:keys [sender_cls _inferredSenderCls target_cls msg]}]
                           [(atom-name (or sender_cls _inferredSenderCls "(root)"))
                            (atom-name target_cls) msg]))
                    frequencies)]
    [:div
     [:p (str (->> es
                   (filter #(= nm (atom-name (or (:target_cls %) (:atom_cls %)))))
                   count) " trace events")]
     (for [[label endpoint] [["incoming" 1] ["outgoing" 0]]]
       [:div [:h4 label]
        [:table.message-table [:thead [:tr [:th "direction / message"] [:th "count"]]]
         [:tbody (for [[[src dst msg] c] (sort-by (fn [[key c]] [(- c) key]) counts)
                       :when (= nm (nth [src dst] endpoint))]
                   [:tr [:td (str src " → " dst " · " msg)] [:td c]
                    [:td (focus-toolbar {:kind "edge" :target {:from_atom src :to_atom dst :msg msg}})]])]]])]))

;; H2-14: detail values contain immutable directions/message pairs.
(defn- edge-detail [directions descriptor]
  (if (seq directions)
    (for [{:keys [src dst total sync msgs] :as e} directions]
      [:div [:h4 (str (atom-name-of-name src) " → " (atom-name-of-name dst))]
       [:p (str total " messages · " sync " sync")]
       (focus-toolbar (edge-descriptor e))
       (traffic-table "messages" msgs #(edge-descriptor e %))])
    [:p (target-brief (:target descriptor))]))

(defn- detail-pane [{:keys [detail-atom shape detail-height focus]
                     {detail-kind :kind :keys [id event directions] :as detail} :detail :as s}]
  (when (or detail-atom detail)
    (let [{:keys [phases transitions self_transitions observations initial_phase] atom-label :name :as atom} (when detail-atom (or (atom-shape-for-name shape detail-atom) (atom-shape-for-class shape detail-atom)))
          descriptor                                                                        (or focus (when detail-atom {:kind "node" :target {:atom (atom-name-of-name detail-atom)}}))]
      [:div.detail-wrap {:style {:height (str detail-height "px")}}
       ;; gap 28: splitter remains keyboard reachable and remembers size.
       [:div#detail-splitter {:role "separator" :tabindex 0 :aria-orientation "horizontal"
                              :aria-label "Resize detail" :aria-valuenow detail-height
                              :on {:pointerdown [::splitter-drag] :keydown [::splitter-key] :dblclick [::splitter-reset]}}]
       [:section#atom-detail
        [:div.atom-detail-header
         [:span.atom-detail-name {:tabindex 0 :on (annotation-anchor descriptor)} (if detail-atom (atom-name-of-name detail-atom) (name detail-kind))]
         (when atom [:span.atom-detail-summary {:title "Phases are declared states; arms are message handlers; observations are queries; initial is the entry phase."}
                     (str (count phases) " phases · " (+ (count transitions) (count self_transitions)) " arms · "
                          (count observations) " observations · initial " initial_phase)])
         (when atom [:button.btn {:on {:click [::popup-open {:kind "node" :target {:atom atom-label :phase initial_phase}}]}}
                     (str "initial " initial_phase)])
         (when descriptor (focus-toolbar descriptor))
         [:button.btn.close {:aria-label "Close detail" :on {:click [::close-atom-detail]}} "×"]]
        (cond
          (= :annotation detail-kind) (annotation-detail-pane s id)
          (= :table detail-kind) (annotation-table s)
          atom [:div
                ;; gaps 48/56: legend stays with the inspected phase graph.
                [:p.phase-legend {:title "Reachability follows declared transitions from initial, independent of runtime hits."}
                 "amber = initial · dashed = unreached · self N = unchanged-phase arms · |: = guarded arm · ×N = grouped arms"]
                [:div#phase-graph-container.phase-graph-container]]
          detail-atom (node-traffic s detail-atom)
          (= :event detail-kind) [:pre.raw-event (-> event clj->js (js/JSON.stringify nil 2))]
          (= :range detail-kind) [:p (target-brief (:target descriptor))]
          (= :edge detail-kind) (edge-detail directions descriptor))]])))

(defn- set-detail-height! [h]
  (let [h (max 120 (min (* 0.65 (or (.-innerHeight js/window) 900)) h))]
    (storage-set! "cxc-phase-pane-h" (str h)) ;; H2-01
    (swap! state assoc :detail-height h)
    (js/setTimeout apply-shape-view! 0)))

(defn- reset-detail-height! []
  (set-detail-height! (* 0.45 (or (.-innerHeight js/window) 900)))
  (storage-remove! "cxc-phase-pane-h")) ;; H2-01

(defn- splitter-drag! [ev]
  (.preventDefault ev)
  (let [start  (.-clientY ev)
        height (:detail-height @state)
        move!  #(set-detail-height! (+ height (- start (.-clientY %))))
        end!   (fn end! [_]
                 (.removeEventListener js/window "pointermove" move!)
                 (.removeEventListener js/window "pointerup" end!))]
    (.addEventListener js/window "pointermove" move!)
    (.addEventListener js/window "pointerup" end!)))

(defn- popup-title [{:keys [resolution-id resolution-status editing-id]}]
  (cond
    resolution-id (str resolution-status " · " resolution-id)
    editing-id (str "edit " editing-id)
    :else "annotate"))

(defn- popup-submit-label [{:keys [resolution-id resolution-status]}]
  (cond
    (not resolution-id) "send"
    (= "wontfix" resolution-status) "save reason"
    :else "save summary"))

(defn- popup-dialog [{:keys [save-error]
                      {:keys [open? target note attachments resolution-id saving?] :as popup} :popup}]
  (when open?
    [:div
     [:div#annotate-backdrop {:on {:click [::popup-close]}}]
     [:div#annotate-popup {:role "dialog" :aria-modal "true" :on {:keydown [::dialog-key]}}
      [:div.popup-title (popup-title popup)]
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
         (for [[i {:keys [kind path url]}] (map-indexed vector attachments)]
           [:div.popup-att {:data-ann-att i}
            (if (= "image" kind)
              [:img {:src path :alt "attachment"}]
              [:span "🔗 " url])
            [:button.popup-att-remove {:aria-label "Remove attachment" :on {:click [::popup-remove-att i]}} "×"]])])
      [:div.popup-actions
       [:span.popup-hint "⌘/Ctrl+Enter sends · ⌘/Ctrl+Shift+Enter saves draft"]
       [:button {:on {:click [::popup-close]}} "cancel"]
       (when-not resolution-id
         [:button {:disabled saving? :on {:click [::popup-submit :draft]}} "save draft"])
       [:button.primary {:disabled saving? :on {:click [::popup-submit :send]}}
        (popup-submit-label popup)]]]]))

(defn- drafts-controls [{:keys [sidecar]}]
  (let [drafts (schema/filter-annotations (:annotations sidecar) {:status "draft"})]
    (when (seq drafts)
      [:div.drafts-controls
       [:span (str (count drafts) " draft(s)")]
       [:button.btn.primary {:on {:click [::drafts-submit-all]}}
        "submit review"]])))

(defn- view [{:keys [loading? events dropped-lines shape sidecar trace-name shape-name error layout
                     annotation-disabled-reason
                     trace-error shape-error render-error sidebar-hidden? heatmap advanced?
                     observations arrows
                     layout-capabilities hint-dismissed? legend-open? save-error] :as s}]
  [:div#app-root
   [:header
    [:h1 "cxc trace viewer"]
    (when (or trace-name shape-name)
      ;; gap 46: declared atoms remain distinct from rendered init nodes.
      [:span.trace-name.status (str (or trace-name shape-name)
                                    (when (seq events) (str " — " (count events) " events"))
                                    (when shape (str " · shape: " (-> shape :atoms count) " atoms")))])]
   (when loading? [:div.loading "loading…"])
   (when annotation-disabled-reason
     [:div.artifact-error.annotation-disabled {:role "status"} annotation-disabled-reason])
   (when (seq dropped-lines)
     [:details.artifact-error.jsonl-dropped {:role "status"}
      [:summary (str (count dropped-lines) " lines dropped from " trace-name)]
      [:ul (for [{:keys [line reason]} dropped-lines]
             [:li (str "line " line ": " reason)])]])
   (for [[artifact message] [["trace" trace-error] ["shape" shape-error]
                             ["layout" render-error] ["load" error]] :when message]
     [:div.artifact-error {:role "status"} (str artifact ": " message)])
   (cond
     (and (empty? events) (nil? shape)) [:div.empty
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
     :else [:section.main
            [:button#sidebar-toggle {:aria-label "Toggle sidebar" :on {:click [::sidebar-toggle]} :title "Toggle sidebar (⌘/Ctrl+\\)"
                                     :aria-controls "sidebar" :aria-expanded (not sidebar-hidden?)} (if sidebar-hidden? "›" "‹")]
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
                         (->> events (into #{} (keep :target)) count))
              (stats-row "classes"
                         (->> events (into #{} (keep :target_cls)) count))
              (stats-row "duration"
                         (str (.toFixed (if (seq events) (- (:t (last events)) (:t (first events))) 0) 2) " ms"))]
             (diagnostics-pane events
                               [:div.diagnostic-stats
                                (when (and shape (empty? events))
                                  [:div.shape-overview
                                   (stats-row "atoms" (-> shape :atoms count))
                                   (stats-row "transitions" (reduce + 0 (map #(+ (count (:transitions %)) (count (:self_transitions %))) (:atoms shape))))
                                   (stats-row "self transitions" (reduce + 0 (map #(+ (->> (:transitions %)
                                                                                           (filter (fn [t] (= (:from t) (:to t))))
                                                                                           count)
                                                                                      (count (:self_transitions %))) (:atoms shape))))
                                   (stats-row "longest chain depth" (apply max 0 (map #((-> js/window (aget "CxcShapeCanvas") (aget "shapeChainDepth")) (clj->js %)) (:atoms shape))))])
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
              [:details.advanced-layouts {:open advanced? :on {:toggle [::advanced-toggle]}}
               [:summary "advanced layouts"]
               [:div.layout-controls
                [:label "layout " [:select {:value (get-in s [:layout-options :algorithm]) :on {:change [::layout-option :algorithm]}}
                                   (for [a ["layered" "stress" "force" "mrtree" "radial" "disco"]]
                                     [:option {:value a :disabled (and layout-capabilities (not (contains? layout-capabilities a)))} a])]]
                [:label "direction " [:select {:value (get-in s [:layout-options :direction])
                                               :disabled (not (#{"layered" "mrtree"} (get-in s [:layout-options :algorithm])))
                                               :on {:change [::layout-option :direction]}}
                                      (for [d ["DOWN" "RIGHT" "UP" "LEFT"]] [:option {:value d} d])]]]]
              [:span.toolbar-spacer]
              [:label [:input {:type "checkbox" :checked (get-in s [:layout-options :showInit])
                               :on {:change [::show-init]}}] " show init"]]
             [:div.shape-stage
              [:div#shape-container.shape-container]
              [:div#shape-zoom
               [:button {:on {:click [::zoom-out]} :aria-label "Zoom out" :title "zoom out"} "−"]
               [:span#shape-zoom-level "100%"]
               [:button {:on {:click [::zoom-in]} :aria-label "Zoom in" :title "zoom in"} "+"]
               [:button {:on {:click [::zoom-reset]} :aria-label "Reset zoom" :title "reset zoom"} "⟲"]
               [:button {:on {:click [::zoom-fit]} :aria-label "Reset view" :title "reset view"} "reset view"]]
              (when-not hint-dismissed?
                [:div#shape-hint "Click any atom for its " [:b "phase graph"] ". Shift-click or right-click to " [:b "annotate"] ". "
                 [:button.dismiss {:aria-label "Dismiss interaction hint" :on {:click [::hint-dismiss]}} "×"]])
              (legend-card legend-open?
                           {:heatmap heatmap :observations observations :arrows arrows :events events})
              (transport-bar s)]
             (detail-pane s)]])
   (when-let [message (when-not (get-in s [:popup :open?]) save-error)]
     [:div.error {:role "alert"}
      message " · edits retained "
      [:button.btn {:on {:click [::retry-save]}} "retry save"]])
   (popup-dialog s)])

;; ---------- render loop ----------

(def ^:private root (atom nil))

(defn- rerender!
  ([] (rerender! @state))
  ;; H10: build the view and its effect requests from the observed successor.
  ([s]
    ;; H03: requests run after view construction, only for displayed comments.
    (when-let [el @root]
      (rdom/render el (view s)))
    (when (= :annotation (get-in s [:detail :kind]))
      (let [id  (get-in s [:detail :id])
            ann (->> (get-in s [:sidecar :annotations])
                     (filter #(= id (:id %)))
                     first)]
        (doseq [c (:comments ann) :when (and (= "pending" (:kind c)) (:token c))]
          (resolve-chat-excerpt! (:token c)))))))

(defn- on-state-change!
  [_k _r {old-detail-atom :detail-atom
          old-sidecar :sidecar
          old-shape :shape
          old-events :events
          old-gen :gen
          old-filters :filters
          old-layout-options :layout-options
          old-loading? :loading?
          :as old-s}
   {new-detail-atom :detail-atom
    new-sidecar :sidecar
    new-shape :shape
    new-events :events
    new-gen :gen
    new-filters :filters
    new-layout-options :layout-options
    new-loading? :loading?
    :as new-s}]
  ;; H10: observe completed state and schedule effects; never create successor state.
  (rerender! new-s)
  (update-unload-guard! old-s new-s)
  (modal-lifecycle! old-s new-s)
  (when (and (not (get-in old-s [:playback :paused?])) (get-in new-s [:playback :paused?]))
    (cancel-playback-frame!))
  (when (not= old-detail-atom new-detail-atom) (update-selection! new-s))
  (when (not= old-sidecar new-sidecar) (refresh-badges! new-s))
  (when (or (not= old-shape new-shape)
            (not= old-events new-events)
            (not= old-gen new-gen)
            (not= old-filters new-filters)
            (not= old-layout-options new-layout-options)
            (not= (get-in old-s [:heatmap :on?]) (get-in new-s [:heatmap :on?]))
            (and old-loading? (not new-loading?)))
    ;; Invalidate in-flight work immediately, even before the next timer runs.
    (let [epoch (swap! shape-render-epoch inc)]
      (js/setTimeout #(render-shape! new-s epoch) 0)))
  (when (and new-detail-atom
             (or (not= old-detail-atom new-detail-atom)
                 (not= old-shape new-shape)
                 (not= old-events new-events)
                 (not= (get-in old-s [:heatmap :on?]) (get-in new-s [:heatmap :on?]))))
    (let [epoch (swap! phase-render-epoch inc)]
      (js/setTimeout #(render-phase-graph! new-s epoch) 0))))

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


(defn- focus-ready? [{:keys [pending-focus events loading? shape]}]
  (let [tag (-> pending-focus (str/split #":" 2) first)]
    (if (contains? #{"event" "range" "edge"} tag)
      (or (seq events) (and (not loading?) shape))
      (or shape (seq events)))))

(defn- focus-key [{:keys [kind]
                   {:keys [phase atom trace_seq seq trace_seq_start from_seq trace_seq_end to_seq
                           from_atom to_atom msg] :as target} :target}]
  (case kind
    "node" (if phase (str "phase:" atom "." phase) (str "atom:" atom))
    "event" (str "event:" (or trace_seq seq))
    "range" (str "range:" (or trace_seq_start from_seq) ":" (or trace_seq_end to_seq))
    "edge" (str "edge:" from_atom ":" to_atom ":" msg)
    (pr-str target)))

(defn- parse-focus-number [text]
  (when-let [n (when-not (str/blank? text) (js/Number text))]
    (when (js/Number.isFinite n) n)))

(defn- parse-phase-focus [value]
  (let [[atom phase] (str/split value #"\." 2)]
    (when-not (str/blank? atom)
      {:tag :phase :payload {:atom atom :phase phase}})))

(defn- parse-range-focus [value]
  (let [[lo hi] (str/split value #"[:-]" 2)
        lo      (parse-focus-number lo)
        hi      (parse-focus-number hi)]
    (when (and (some? lo) (some? hi) (<= lo hi))
      {:tag :range :payload {:lo lo :hi hi}})))

(defn- parse-edge-focus [value]
  (let [[src dst msg] (str/split value #":" 3)]
    (when (and (not (str/blank? src)) (not (str/blank? dst)))
      {:tag :edge :payload {:src src :dst dst :msg msg}})))

;; H18: decode once; malformed keys never reach lookup or publication.
(defn- parse-focus-key [key]
  (when (string? key)
    (let [[tag value] (str/split key #":" 2)]
      (when-not (str/blank? value)
        (case tag
          "atom" {:tag :atom :payload {:atom value}}
          "phase" (parse-phase-focus value)
          "event" (when-let [n (parse-focus-number value)] {:tag :event :payload {:seq n}})
          "range" (parse-range-focus value)
          "edge" (parse-edge-focus value)
          nil)))))

;; H18: pure snapshot lookup, including unresolved events and both edge directions.
(defn- resolve-focus-in-snapshot [{:keys [shape events filters layout-options]} {:keys [tag payload]}]
  (let [{:keys [atom phase seq lo hi src dst msg]} payload
        resolver                                   (when (contains? #{:event :edge} tag)
                                                     ((aget (shape-adapter/host) "classResolver") (clj->js shape) (clj->js events)))
        name-for-class                             (fn [cls]
                                                     (or (some-> ((aget resolver "atomShapeForClass") cls) (aget "name"))
                                                         ((aget resolver "displayLabel") cls)))]
    (case tag
      :atom {:detail nil :detail-atom atom :focus {:kind "node" :target {:atom atom}}}
      :phase {:detail nil :detail-atom atom :focus {:kind "node" :target {:atom atom :phase phase}}}
      :event (if-let [e (->> events
                             (filter #(= seq (:seq %)))
                             first)]
               {:detail-atom nil :detail {:kind :event :event e}
                :focus (event-descriptor e name-for-class) :playback-patch {:cursor-seq seq}}
               {:detail-atom nil :detail {:kind :event :event {:seq seq}}
                :focus {:kind "event" :target {:trace_seq seq}}})
      :range {:detail-atom nil :detail {:kind :range}
              :focus {:kind "range" :target {:trace_seq_start lo :trace_seq_end hi}}}
      :edge (let [topo       (shape-adapter/topology
                               {:shape shape :trace-loaded? (boolean (cljs.core/seq events))
                                :events (filter-events events filters)
                                :display-label (aget resolver "displayLabel")
                                :prettify-msg (aget (shape-adapter/host) "prettifyMsg")
                                :atom-shape-for-class (aget resolver "atomShapeForClass")
                                :show-init? (:showInit layout-options)})
                  ;; Edge detail only ever concerns runtime messages. Obs edges
                  ;; have no msgs/sync counts and would render as empty rows.
                  directions (filter #(and (not= "obs" (:kind %))
                                           (or (and (= src (name-for-class (:src %))) (= dst (name-for-class (:dst %))))
                                               (and (= dst (name-for-class (:src %))) (= src (name-for-class (:dst %))))))
                                     (:edges topo))]
              {:detail-atom nil :detail {:kind :edge :directions (vec directions)}
               :focus {:kind "edge" :target {:from_atom src :to_atom dst :msg msg}}})
      nil)))

;; H18: turn nested patches into one mergeable state value without losing peers.
(defn- focus-state-patch [snapshot {:keys [filter-patch playback-patch] :as resolved}]
  (let [patch (cond-> (dissoc resolved :filter-patch :playback-patch)
                filter-patch (assoc :filters (merge (:filters snapshot) filter-patch))
                playback-patch (assoc :playback (merge (:playback snapshot) playback-patch)))]
    ;; H10: a URL range owns the same normalization as a filter control.
    (cond-> patch
      (and filter-patch (not= (:filters snapshot) (:filters patch))) (assoc :playback (:playback (normalize-playback (merge snapshot patch)))))))

;; H18: parsing and resolution finish before the sole state publication.
(defn- apply-focus-from-url! [key]
  (when-let [parsed (parse-focus-key key)]
    (let [snapshot @state
          resolved (resolve-focus-in-snapshot snapshot parsed)]
      (swap! state merge (focus-state-patch snapshot resolved)))))

;; H10: artifact succession owns first-ready URL focus and event/edge refresh.
;; Resolution uses H18's pure pipeline in the same publication as the arrival.
(defn- artifact-successor [old-s snapshot]
  (let [changed? (or (not= (:shape old-s) (:shape snapshot))
                     (not= (:events old-s) (:events snapshot)))
        ;; H08: count once when artifact data changes, before focus/view consumers run.
        snapshot (if changed? (-> snapshot with-artifact-heat with-artifact-observations) snapshot)
        pending  (:pending-focus snapshot)
        ready?   (and pending (focus-ready? snapshot))
        key      (if ready? pending
                     (when (and (not pending) changed?
                                (contains? #{"event" "edge"} (get-in snapshot [:focus :kind])))
                       (focus-key (:focus snapshot))))
        parsed   (parse-focus-key key)
        snapshot (if ready? (dissoc snapshot :pending-focus) snapshot)]
    (if parsed
      (merge snapshot (focus-state-patch snapshot (resolve-focus-in-snapshot snapshot parsed)))
      snapshot)))

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

(def ^:private review-fields
  [:sidecar :saved-sidecar :annotation-dirty? :save-error :annotation-status
   :annotation-error :popup :ui :ann-selected :ann-return :table-return])

(defn- retained-review [snapshot]
  (-> (select-keys snapshot review-fields)
      (assoc-in [:popup :saving?] false)
      (assoc-in [:ui :reply-saving] {})))

(defn- review-successor [{:keys [artifact-key reviews] :as snapshot} identity source]
  (let [reviews      (cond-> reviews artifact-key (assoc artifact-key (retained-review snapshot)))
        empty-review (schema/empty-sidecar source "" "")
        review       (get reviews (:artifact-key identity)
                          {:sidecar empty-review :saved-sidecar empty-review
                           :annotation-dirty? false :save-error nil
                           :annotation-status :loading :annotation-error nil
                           :popup {:open? false} :ui {} :ann-selected #{} :ann-return nil :table-return nil})]
    (merge snapshot review {:reviews reviews :annotation-disabled-reason nil} identity)))

(defn- switch-review-identity! [identity source]
  (when (or (not= (:artifact-key identity) (:artifact-key @state))
            (:annotation-status identity))
    (swap! state review-successor identity source)
    (load-annotations!)
    (start-poll!)))

(defn- switch-review! [key source]
  (switch-review-identity! {:artifact-key key :annotation-key key} source))

(defonce ^:private shape-load-epoch (atom 0))

(defn- valid-shape [j]
  (when-not (and j (array? (aget j "atoms"))
                 (every? #(string? (aget % "name")) (array-seq (aget j "atoms"))))
    (throw (js/Error. "shape requires an atoms array with named atoms")))
  (js->clj j :keywordize-keys true))

(defn- load-shape! [shape-url]
  (let [my-gen (:gen @state)
        epoch  (swap! shape-load-epoch inc)]
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

(defn- trace-from-text [text]
  (let [{:keys [kept dropped]} (parse-jsonl text)]
    {:events (with-sender-cls kept)
     :dropped-lines dropped
     :loading? false
     :playback {:cursor-seq nil :paused? true :speed 1}}))

(defn- hydrate-trace-from-text! [name text my-gen]
  (when (= my-gen (:gen @state))
    (update-artifact! assoc :trace-name name :trace-error nil :error nil :loading? false) ;; H10
    (commit! my-gen #(merge % (trace-from-text text)))))

(defn- hydrate-shape-from-text! [name text my-gen]
  (when (= my-gen (:gen @state))
    (update-artifact! assoc :shape-name name :shape-error nil :error nil :loading? false) ;; H10
    (try
      (let [j (js/JSON.parse text)]
        (commit! my-gen #(assoc % :shape (valid-shape j))))
      (catch :default e
        (update-artifact! assoc :shape nil :shape-error (str "shape parse: " (.-message e))))))) ;; H10

(defn- sha256 [bytes]
  (-> (.digest (.-subtle js/crypto) "SHA-256" bytes)
      (.then (fn [digest]
               (->> (js/Uint8Array. digest)
                    array-seq
                    (map #(.padStart (.toString % 16) 2 "0"))
                    (str/join ""))))))

(defn- disabled-review-identity [name hash reason]
  {:artifact-key [name hash]
   :annotation-key nil
   :annotation-status :disabled
   :annotation-disabled-reason (str "annotations disabled — " reason)})

(defn- match-mounted-review! [name key hash]
  (-> (js/fetch (js/encodeURIComponent name) #js {:cache "no-store"})
      (.then (fn [response]
               (if (.-ok response)
                 (.arrayBuffer response)
                 (throw (js/Error. (str "HTTP " (.-status response)))))))
      (.then sha256)
      (.then (fn [mounted-hash]
               (if (= hash mounted-hash)
                 {:artifact-key key :annotation-key key}
                 (disabled-review-identity name hash
                                          "content does not match the server-mounted trace of this name."))))
      (.catch (fn [error]
                (disabled-review-identity name hash
                                         (str "cannot verify the server-mounted artifact: " (.-message error)))))))

(defn- dropped-review-identity! [name key bytes]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] (sha256 bytes)))
      (.then #(match-mounted-review! name key %))
      (.catch (fn [error]
                (disabled-review-identity name (random-uuid)
                                         (str "cannot hash this artifact: " (.-message error)))))))

(defn- hydrate-dropped-file! [name bytes shape-file? my-gen]
  (when (= my-gen (:gen @state))
    (let [text        (.decode (js/TextDecoder.) bytes)
          shape-file? (or shape-file? (try (array? (aget (js/JSON.parse text) "atoms")) (catch :default _ false)))
          key         (if shape-file? (shape-annotation-key name) name)]
      (if shape-file?
        (do (swap! state assoc :trace-name nil) (hydrate-shape-from-text! name text my-gen))
        (hydrate-trace-from-text! name text my-gen))
      (-> (dropped-review-identity! name key bytes)
          (.then (fn [identity]
                   (when (= my-gen (:gen @state))
                     (switch-review-identity! identity name))))))))

(defn- load-dropped-file! [file]
  (let [name        (.-name file)
        my-gen      (next-gen!)
        shape-file? (str/ends-with? name ".shape.json")]
    (switch-review-identity! {:artifact-key nil :annotation-key nil :annotation-status :verifying} name)
    ;; Invalidate fetches/layouts at selection time, not after the file read finishes.
    (update-artifact! assoc :events [] :dropped-lines [] :shape nil :layout nil :loading? true ;; H10
                      :trace-error nil :shape-error nil :render-error nil :detail nil :detail-atom nil :focus nil
                      :trace-name (when-not shape-file? name) :shape-name (when shape-file? name))
    (-> (.arrayBuffer file)
        (.then #(hydrate-dropped-file! name % shape-file? my-gen))
        (.catch (fn [error]
                  (commit! my-gen #(assoc % :error (str "file read: " (.-message error)) :loading? false)))))))

(defn- load-trace! [trace-url]
  (let [different? (not= trace-url (:trace-name @state))
        my-gen     (next-gen!)]
    (when different?
      (update-artifact! assoc :events [] :dropped-lines [] :shape nil :shape-name nil :layout nil)) ;; H10
    (update-artifact! assoc :loading? true :trace-name trace-url :error nil :trace-error nil :render-error nil) ;; H10
    (switch-review! trace-url trace-url)
    (-> (js/fetch trace-url #js {:cache "no-store"})
        (.then (fn [r]
                 (if (.-ok r)
                   (.text r)
                   (throw (js/Error. (str "HTTP " (.-status r)))))))
        (.then (fn [text]
                 (commit! my-gen #(merge % (trace-from-text text)))))
        (.catch (fn [e]
                  (commit! my-gen (fn [s] (assoc s :trace-error (.-message e)
                                                 :loading? false))))))))

;; ---------- annotations load + poll ----------

(defonce ^:private poll-handle (atom nil))
(defonce ^:private pending-save-count (atom 0))
(defonce ^:private save-epoch (atom 0))
(defonce ^:private save-chain (atom (js/Promise.resolve nil)))

(defn- canonical-comments [cs]
  (mapv (fn [{:keys [by author text body] :as c}]
          (-> c
              (assoc :by (or by author)
                     :text (or text body))
              (dissoc :author :body))) cs))

(defn- canonical-sidecar [sidecar]
  (update sidecar :annotations
          (fn [anns]
            (mapv (fn [a]
                    (cond-> a
                      (:comments a) (update :comments canonical-comments)))
                  (or anns [])))))

;; H01: GET and CAS conflict responses share the outgoing wire contract.
(defn- read-sidecar [j]
  (let [sidecar (js->clj j :keywordize-keys true)]
    (when-let [error (schema/validate-sidecar sidecar)]
      (throw (js/Error. (str "annotation sidecar: " error))))
    (canonical-sidecar sidecar)))

(defn- load-annotations! []
  (when-let [k (:annotation-key @state)]
    (when (and (zero? @pending-save-count) (not (unsaved-review? @state)))
      (let [my-gen (:gen @state)
            epoch  @save-epoch]
        (-> (js/fetch (str "/annotations?trace=" (js/encodeURIComponent k)))
            (.then (fn [r] (if (.-ok r) (.json r)
                               (throw (js/Error. (str "HTTP " (.-status r)))))))
            (.then (fn [j]
                     ;; A GET started before a save must never replace that save,
                     ;; even when both still belong to the same artifact generation.
                     (when (and (= epoch @save-epoch) (zero? @pending-save-count)) ;; H01
                       (commit! my-gen
                                (fn [s]
                                  (if (and (= k (:annotation-key s)) (not (unsaved-review? s)))
                                    (let [sidecar (read-sidecar j)] ;; H01
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

(declare merge-fields)

(defn- merge-map-fields [base local remote]
  (reduce (fn [out k]
            (let [b (get base k ::missing)
                  l (get local k ::missing)
                  r (get remote k ::missing)
                  v (if (= k :comments)
                      (->> (concat (when (sequential? r) r)
                                   (remove (set (when (sequential? b) b))
                                           (when (sequential? l) l)))
                           (distinct)
                           vec)
                      (merge-fields b l r))]
              (if (= ::missing v) (dissoc out k) (assoc out k v))))
          remote (into #{} (concat (keys base) (keys local) (keys remote)))))

(defn- merge-fields [base local remote]
  (cond
    (= base local) remote
    (= base remote) local
    (= local remote) local
    (and (map? base) (map? local) (map? remote)) (merge-map-fields base local remote)
    :else (throw (js/Error. "save conflict: both writers changed the same field; local edits retained, server unchanged"))))

(defn- remap-ann-ids [sidecar ids]
  (update sidecar :annotations
          #(mapv (fn [a] (update a :id (fn [id] (get ids id id)))) %)))

(defn- merge-sidecars
  "Three-way CAS merge: keep remote changes to untouched fields, local edits
   to changed fields, and comments from both writers. Rename colliding new IDs."
  [base local remote]
  (let [index #(into {} (map (juxt :id identity) (:annotations %)))
        b     (index base)
        r     (index remote)
        ids   (reduce (fn [ids a]
                        (let [id (:id a)]
                          (if (and (not (contains? b id)) (contains? r id) (not= a (get r id)))
                            (assoc ids id (next-ann-id
                                            (concat (:annotations local) (:annotations remote)
                                                    (map (fn [id] {:id id}) (vals ids)))))
                            ids)))
                      {} (:annotations local))
        local (remap-ann-ids local ids)
        l     (index local)
        order (distinct (concat (map :id (:annotations remote)) (map :id (:annotations local))))
        anns  (keep (fn [id]
                      (let [v (merge-fields (get b id ::missing) (get l id ::missing)
                                            (get r id ::missing))]
                        (when-not (= ::missing v) v))) order)]
    {:sidecar (assoc (merge-fields (dissoc base :annotations :rev)
                                   (dissoc local :annotations :rev)
                                   (dissoc remote :annotations :rev))
                     :rev (schema/effective-rev remote) :annotations (vec anns))
     :id-remap ids}))

;; H02: an HTTP acknowledgment must supply the revision consumed by CAS.
(defn- read-save-rev [j]
  (let [rev (when j (aget j "rev"))]
    (when-not (and (js/Number.isInteger rev) (<= 0 rev))
      (throw (js/Error. "save acknowledgment requires a nonnegative integer rev")))
    rev))

(defn- save-sidecar!
  "Serialize saves; merge and retry CAS conflicts. Resolve true only after
   persistence succeeds. Failed edits stay dirty, so polling cannot erase them."
  []
  (let [{:keys [annotation-key gen]} @state
        current?                     #(and (= gen (:gen @state)) (= annotation-key (:annotation-key @state)))]
    (if (or (nil? annotation-key) (contains? #{:verifying :disabled} (:annotation-status @state)))
      (do (swap! state assoc :save-error (or (:annotation-disabled-reason @state) "load a verified artifact before saving"))
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
                                         :body (-> sent clj->js js/JSON.stringify)})
                          (.then (fn [response]
                                   (cond
                                     (.-ok response) (-> (.json response)
                                                         (.then (fn [j]
                                                                  (when (current?)
                                                                    (let [saved (assoc sent :rev (read-save-rev j))] ;; H02
                                                                      (commit! gen
                                                                               (fn [s]
                                                                                 (let [working (:sidecar (merge-sidecars sent (:sidecar s) saved))]
                                                                                   (assoc s :sidecar working :saved-sidecar saved
                                                                                          :annotation-dirty? (not= working saved)
                                                                                          :save-error nil :annotation-status :available :annotation-error nil))))
                                                                      true)))))
                                     (= 409 (.-status response)) (-> (.json response)
                                                                     (.then (fn [j]
                                                                              (when (current?)
                                                                                (let [fresh                           (read-sidecar (aget j "sidecar")) ;; H01
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
                                     :else (throw (js/Error. (str "save failed: HTTP " (.-status response)))))))))))]
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

;; H2-03: a successful upload must yield a usable image descriptor.
(defn- read-image-attachment [j]
  (let [attachment (js->clj j :keywordize-keys true)]
    (when-not (and (map? attachment) (string? (:path attachment))
                   (not (str/blank? (:path attachment))))
      (throw (js/Error. "asset upload requires an object with a nonblank path")))
    (assoc attachment :kind "image")))

(defn- upload-image-blob!
  "POST a blob to /annotations-asset?trace=... and append the returned
   {path:..., kind:'image'} descriptor to the popup's :attachments."
  [blob]
  ;; H06: both outcomes belong to the initiating artifact and composer session.
  (let [{:keys [annotation-key gen popup]} @state
        k                                  annotation-key
        session                            (:session popup)
        current?                           #(and (= gen (:gen @state)) (= k (:annotation-key @state))
                                                 (get-in @state [:popup :open?]) (= session (get-in @state [:popup :session])))]
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
                   (when (current?) ;; H06
                     (let [attachment (read-image-attachment j)] ;; H2-03
                       (swap! state update-in [:popup :attachments]
                              (fn [a] (conj (vec a) attachment)))))))
          (.catch (fn [e]
                    (when (current?) ;; H06
                      (swap! state assoc :save-error (.-message e)))))))))

(defn- handle-popup-paste!
  "Walk the clipboard: image → upload+attach; otherwise a http(s) URL →
   attach as a link; otherwise fall through to the browser's default
   insert-text-into-textarea."
  [ev]
  (let [dt       (.-clipboardData ev)
        items    (and dt (.-items dt))
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
          (let [el    (.-target ev)
                url   (str/trim text)
                md    (str "[" url "](" url ")")
                note  (get-in @state [:popup :note] "")
                start (or (.-selectionStart el) (count note))
                end   (or (.-selectionEnd el) start)
                pos   (+ start (count md))]
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
  (if (or (nil? (:annotation-key @state)) (annotation-server-blocked? (:annotation-status @state)))
    (js/alert (if (:annotation-key @state)
                "Annotation server unavailable or loading; retry the connection before annotating."
                "Load an artifact before annotating."))
    (do
      (when-not (get-in @state [:popup :open?]) (reset! popup-return-focus (.-activeElement js/document)))
      (swap! state assoc :popup
             {:open? true :session (random-uuid) :kind (or kind "node") :target (or target {})
              :note (or prefill-note "")
              :initial-note (or prefill-note "")
              :editing-id editing-id :resolution-id resolution-id :resolution-status resolution-status
              :resolution-current-status resolution-current-status
              :attachments (or prefill-attachments [])
              :initial-attachments (or prefill-attachments [])}))))

(defn- close-popup! []
  (swap! state assoc :popup {:open? false}))

(defn- popup-submit!
  "mode = :send (status open, stamps submitted_at) or :draft (status draft).
   Edits preserve status unless we're sending a draft (then flip to open)."
  [mode]
  (when-not (get-in @state [:popup :saving?])
    (let [{:keys [popup sidecar gen]}                               @state
          {:keys [kind target note editing-id attachments session]} popup
          now                                                       (now-iso)
          send?                                                     (= mode :send)]
      (when (str/blank? note)
        (js/alert "write something")
        (throw (ex-info "empty note" {})))
      (let [anns  (:annotations sidecar)
            id    (or editing-id (next-ann-id anns))
            anns' (if editing-id
                    (mapv (fn [a]
                            (if (not= (:id a) editing-id) a
                                (let [cur-status (schema/effective-status a)
                                      patch      (cond-> {:note note :target target :kind kind
                                                          :updated_at now :attachments attachments}
                                                   (and send? (= "draft" cur-status)) (assoc :status "open" :submitted_at now))]
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
        ;; H04: observers see the optimistic note and its retry identity together.
        (edit-review! (fn [s] (-> s
                                  (assoc-in [:sidecar :annotations] anns')
                                  (update :popup assoc :editing-id id :saving? true))))
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
      (edit-review! update-in [:sidecar :annotations] ;; H04
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
  (when-let [a (->> (get-in @state [:sidecar :annotations])
                    (filter #(= ann-id (:id %)))
                    first)]
    (when (can-transition? a new-status)
      (if (= "verified" new-status)
        (when (js/confirm (str "Mark " ann-id " verified?"))
          (edit-review! update-in [:sidecar :annotations] ;; H04
                        #(mapv (fn [a] (if (= ann-id (:id a)) (transition-record a new-status "" (now-iso)) a)) %))
          (save-sidecar!))
        (open-popup! {:kind (:kind a) :target (:target a) :resolution-status new-status :resolution-id ann-id :resolution-current-status (schema/effective-status a)})))))

(defn- resolution-submit! []
  (let [{:keys [note resolution-id resolution-status resolution-current-status resolution-applied? session]} (:popup @state)
        gen                                                                                                  (:gen @state)]
    (when (and (not (get-in @state [:popup :saving?])) (not (str/blank? note)))
      ;; H04: resolution data and its retry/saving bookkeeping form one edit.
      (let [now (now-iso)]
        (edit-review! (fn [s] (-> s
                                  (update :popup assoc :saving? true :resolution-applied? true)
                                  (update-in [:sidecar :annotations]
                                             #(mapv (fn [a]
                                                      (if (= resolution-id (:id a))
                                                        (transition-record (if (and resolution-applied? (= resolution-status (:status a)))
                                                                             (assoc a :status resolution-current-status) a)
                                                                           resolution-status note now) a)) %))))))
      (-> (save-sidecar!)
          (.then (fn [ok?]
                   (when (and (= gen (:gen @state)) (= session (get-in @state [:popup :session])))
                     (swap! state assoc-in [:popup :saving?] false)
                     (when ok? (close-popup!)))))))))

;; gap 41: capture initiating focus, isolate background and restore it on close.
(defonce ^:private popup-return-focus (atom nil))
(defonce ^:private modal-inert (atom []))
(defn- modal-lifecycle! [old-s new-s]
  ;; H17: scheduled activation belongs to this opening, including replacement.
  (let [before  (get-in old-s [:popup :open?])
        after   (get-in new-s [:popup :open?])
        session (get-in new-s [:popup :session])]
    (when (and after (or (not before) (not= session (get-in old-s [:popup :session]))))
      (js/setTimeout
        (fn []
          (when (and (get-in @state [:popup :open?]) (= session (get-in @state [:popup :session])))
            (when (empty? @modal-inert)
              (let [els (array-seq (.querySelectorAll js/document "#app-root > header, #app-root > .main, #app-root > .empty, #sidebar-toggle, #theme-toggle"))]
                (reset! modal-inert (mapv #(vector % (.-inert %)) els))
                (doseq [el els] (set! (.-inert el) true))))
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
          els    (vec (array-seq (.querySelectorAll dialog "button:not([disabled]), textarea, input, a[href], [tabindex='0']")))
          active (.-activeElement js/document)]
      (cond
        (empty? els) (.preventDefault ev)
        (and (.-shiftKey ev) (or (identical? active (first els)) (not (.contains dialog active)))) (do (.preventDefault ev) (.focus (last els)))
        (and (not (.-shiftKey ev)) (or (identical? active (last els)) (not (.contains dialog active)))) (do (.preventDefault ev) (.focus (first els)))))))

;; ---------- event dispatch ----------

(defn- dom-event-value [rep-data]
  (some-> rep-data :replicant/dom-event .-target .-value))

;; gap 61: popup-wide shortcuts and one Escape layer per keypress.
(defn- popup-key! [ev]
  (when-not (.-defaultPrevented ev)
    (cond
      (= "Escape" (.-key ev)) (do (.preventDefault ev) (.stopPropagation ev) (close-popup!))
      (and (or (.-metaKey ev) (.-ctrlKey ev)) (= "Enter" (.-key ev))) (do (.preventDefault ev) (.stopPropagation ev)
                                                                          (if (get-in @state [:popup :resolution-id]) (resolution-submit!)
                                                                              (popup-submit! (if (.-shiftKey ev) :draft :send)))))))

(defn- global-key! [e]
  (when-not (.-defaultPrevented e)
    (let [k            (.-key e)
          tgt          (.-target e)
          interactive? (and tgt (or (.-isContentEditable tgt)
                                    (.closest tgt "input,textarea,select,button,a,summary,[contenteditable='true'],[role='button'],[role='link'],[role='slider'],[role='separator'],[role='checkbox'],[role='radio'],[role='switch'],[role='tab'],[role='menuitem']")))]
      (cond
        (get-in @state [:popup :open?]) (popup-key! e)
        (and (or (.-metaKey e) (.-ctrlKey e)) (= k "\\")) (do (.preventDefault e) (toggle-sidebar!))
        (and (= k "Escape") (:legend-open? @state) (nil? (:detail-atom @state))) (do (.preventDefault e) (storage-set! "cxc-shape-legend-hidden" "1") (swap! state assoc :legend-open? false)) ;; H2-01
        (and (= k "Escape") (or (:detail-atom @state) (:detail @state))) (do (.preventDefault e) (swap! state assoc :detail-atom nil :detail nil :focus nil))
        (and (= k " ") (not interactive?) (seq (:events @state))) (do (.preventDefault e) (if (get-in @state [:playback :paused?]) (pt-play!) (pt-pause!)))))))

(defn- handle-event! [rep-data [tag & args]]
  (case tag
    ::annotate-anchor (when-let [ev (:replicant/dom-event rep-data)]
                        (when (or (= "contextmenu" (.-type ev))
                                  (and (= "click" (.-type ev)) (.-shiftKey ev))
                                  (= "ContextMenu" (.-key ev))
                                  (and (.-shiftKey ev) (#{"F10" "Enter" " "} (.-key ev))))
                          (.preventDefault ev) (.stopPropagation ev) (open-popup! (first args))))
    ::ann-row-key (let [ev (:replicant/dom-event rep-data)]
                    (handle-event! rep-data [::annotate-anchor (second args)])
                    (when (and (not (.-defaultPrevented ev)) (activation-key? (.-key ev))
                               (not (.closest (.-target ev) "button,input,textarea,select,a")))
                      (.preventDefault ev) (open-annotation! (first args))))
    ::ann-row-click (let [ev (:replicant/dom-event rep-data)]
                      (if (.-shiftKey ev)
                        (handle-event! rep-data [::annotate-anchor (second args)])
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
                 (set-browse! #(assoc % :sort k :descending? (and (= (:sort %) k) (not (:descending? %)))))) ;; H07
    ::ann-status-filter (let [status (first args)]
                          (set-browse! #(update % :statuses (fn [xs] (if (contains? xs status) (disj xs status) (conj xs status))))))
    ::ann-select-all (let [ids  (into #{} (map :id) (browse-annotations @state))
                           all? (every? (:ann-selected @state) ids)]
                       (swap! state update :ann-selected #(if all? (reduce disj % ids) (into % ids))))
    ::ann-select (let [id      (first args)
                       rows    (mapv :id (browse-annotations @state))
                       prior   (.indexOf (clj->js rows) (:ann-selection-anchor @state))
                       current (.indexOf (clj->js rows) id)
                       shift?  (some-> rep-data :replicant/dom-event .-shiftKey)
                       ids     (if (and shift? (>= prior 0) (>= current 0))
                                 (subvec rows (min prior current) (inc (max prior current))) [id])
                       on?     (not (contains? (:ann-selected @state) id))]
                   (swap! state update :ann-selected #(reduce (if on? conj disj) % ids))
                   (swap! state assoc :ann-selection-anchor id))
    ::ann-verify-selected (when (js/confirm "Mark the selected addressed annotations verified?")
                            (let [ids (:ann-selected @state)
                                  now (now-iso)]
                              (edit-review! update-in [:sidecar :annotations] ;; H04
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
    ::set-msg-query (update-filters! assoc :msg-query (dom-event-value rep-data)) ;; H10
    ::inspect-event (show-event! (first args))
    ::inspect-event-key (when-let [e (:replicant/dom-event rep-data)]
                          (when (activation-key? (.-key e)) (.preventDefault e) (show-event! (first args))))
    ::drop-key (when-let [e (:replicant/dom-event rep-data)]
                 (when (activation-key? (.-key e)) (.preventDefault e) (.click (js/document.getElementById "file-picker"))))
    ::show-init (let [on? (not (get-in @state [:layout-options :showInit]))]
                  (storage-set! "cxc-shape-show-init" (if on? "1" "0")) ;; H2-01
                  (swap! state assoc-in [:layout-options :showInit] on?))
    ::advanced-toggle (when-let [e (:replicant/dom-event rep-data)]
                        (let [on? (.-open (.-target e))]
                          (storage-set! "cxc-shape-advanced" (if on? "1" "0")) ;; H2-01
                          (swap! state assoc :advanced? on?)))
    ::layout-option (let [k (keyword (first args))
                          v (dom-event-value rep-data)]
                      (storage-set! (str "cxc-shape-" (if (= k :algorithm) "layout" (name k))) v) ;; H2-01
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
    ::obs-toggle   (do (swap! state update-in [:observations :on?] not)
                       (storage-set! "cxc-shape-observations"
                                     (if (get-in @state [:observations :on?]) "1" "0"))
                       (apply-observations!))
    ::arrows-toggle (do (swap! state update-in [:arrows :on?] not)
                        (storage-set! "cxc-shape-arrows"
                                      (if (get-in @state [:arrows :on?]) "1" "0"))
                        (apply-arrows!))
    ::zoom-in      (zoom-shape-centred! 1.25)
    ::zoom-out     (zoom-shape-centred! (/ 1 1.25))
    ::zoom-reset   (reset-shape-zoom!)
    ::zoom-fit     (fit-shape-view!)
    ::hint-dismiss (do (storage-set! "cxc-shape-hint-dismissed" "1") ;; H2-01
                       (swap! state assoc :hint-dismissed? true))
    ::legend-toggle (let [open? (boolean (first args))]
                      (storage-set! "cxc-shape-legend-hidden" (if open? "0" "1")) ;; H2-01
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
    ::toggle-kind  (let [k (first args)]
                     (update-filters! update :kinds-off ;; H10
                                      (fn [s] (let [s (or s #{})]
                                                (if (contains? s k) (disj s k) (conj s k))))))
    ::toggle-atom  (let [a (first args)]
                     (update-filters! update :atoms-off ;; H10
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
    ::popup-remove-att (let [i (first args)]
                         (swap! state update-in [:popup :attachments]
                                (fn [a] (vec (concat (subvec a 0 i) (subvec a (inc i)))))))
    ::popup-paste  (when-let [ev (:replicant/dom-event rep-data)]
                     (handle-popup-paste! ev))
    ::close-atom-detail (swap! state assoc :detail-atom nil :detail nil :focus nil)
    ::popup-open-atom (let [atom (first args)]
                        (open-popup! {:kind "node" :target {:atom atom}}))
    ::copy-focus-link (let [focus (first args)
                            key   (focus-key focus)
                            url   (-> js/window.location.href
                                      js/URL.
                                      (doto (-> .-searchParams (.set "focus" key)))
                                      .-href)
                            name  (schema/target-brief (:target focus))
                            md    (str "[" name "](" url ")")]
                        (if (and (exists? js/navigator.clipboard) (.-writeText js/navigator.clipboard))
                          (.writeText js/navigator.clipboard md)
                          (js/alert md)))
    ::drafts-submit-all (submit-all-drafts!)
    ::popup-edit-draft (let [id (first args)]
                         (when-let [{:keys [kind target note attachments]} (->> (get-in @state [:sidecar :annotations])
                                                                                (filter #(= (:id %) id))
                                                                                first)]
                           (open-popup! {:kind         kind
                                         :target       target
                                         :editing-id   id
                                         :prefill-note note
                                         :prefill-attachments attachments})))
    ::popup-send-draft (let [id  (first args)
                             now (now-iso)]
                         (edit-review! update-in [:sidecar :annotations] ;; H04
                                       (fn [anns]
                                         (mapv (fn [a]
                                                 (if (= (:id a) id)
                                                   (merge a {:status "open" :submitted_at now})
                                                   a))
                                               anns)))
                         (save-sidecar!))
    ::delete-draft (let [id (first args)]
                     (when (js/confirm (str "Discard draft " id "?"))
                       (edit-review! update-in [:sidecar :annotations] ;; H04
                                     (fn [anns] (into [] (remove #(= (:id %) id)) anns)))
                       (save-sidecar!)))
    ::reply-open   (swap! state assoc-in [:ui :reply-open (first args)] true)
    ::reply-cancel (do (swap! state update-in [:ui :reply-open] dissoc (first args))
                       (swap! state update-in [:ui :reply-text] dissoc (first args)))
    ::reply-text   (let [id (first args)
                         v  (dom-event-value rep-data)]
                     (swap! state assoc-in [:ui :reply-text id] (or v "")))
    ::reply-key    (when-let [ev (:replicant/dom-event rep-data)]
                     (let [id (first args)]
                       (cond
                         (and (or (.-metaKey ev) (.-ctrlKey ev)) (= "Enter" (.-key ev))) (do (.preventDefault ev)
                                                                                             (handle-event! rep-data [::reply-send id]))
                         (= "Escape" (.-key ev)) (do (.preventDefault ev) (.stopPropagation ev)
                                                     (handle-event! rep-data [::reply-cancel id])))))
    ::reply-send   (let [id      (first args)
                         gen     (:gen @state)
                         text    (or (get-in @state [:ui :reply-text id]) "")
                         pending (get-in @state [:ui :reply-pending id])
                         comment (if (= text (:text pending)) pending
                                     {:by "human" :text text :at (now-iso)})]
                     (when (and (not (str/blank? text))
                                (not (get-in @state [:ui :reply-saving id])))
                       ;; H04: publish the comment and its retry record atomically.
                       (edit-review! (fn [s] (-> s
                                                 (update-in [:sidecar :annotations]
                                                            (fn [anns]
                                                              (mapv (fn [a]
                                                                      (if (= (:id a) id)
                                                                        (update a :comments
                                                                                (fn [cs]
                                                                                  (conj (into [] (remove #(= pending %)) cs) comment)))
                                                                        a)) anns)))
                                                 (assoc-in [:ui :reply-pending id] comment)
                                                 (assoc-in [:ui :reply-saving id] true))))
                       (-> (save-sidecar!)
                           (.then (fn [saved?]
                                    (when (= gen (:gen @state))
                                      ;; H04: completion also publishes one editor value.
                                      (swap! state (fn [s]
                                                     (cond-> (update-in s [:ui :reply-saving] dissoc id)
                                                       saved? (update-in [:ui :reply-pending] dissoc id)
                                                       (and saved? (= text (get-in s [:ui :reply-text id]))) (update-in [:ui :reply-open] dissoc id)
                                                       (and saved? (= text (get-in s [:ui :reply-text id]))) (update-in [:ui :reply-text] dissoc id))))))))))
    (js/console.warn "unhandled event" (pr-str tag))))

;; gap 53: accepts off-target file drops and marks the visible drop area.
(defn- wire-document-drop! []
  (let [depth     (atom 0)
        feedback! #(when-let [el (js/document.getElementById "drop")]
                     (.toggle (.-classList el) "dragging" %))
        files?    #(some #{"Files"} (array-seq (or (some-> % .-dataTransfer .-types) #js [])))]
    (.addEventListener js/document "dragenter" (fn [ev] (when (files? ev) (swap! depth inc) (feedback! true))))
    (.addEventListener js/document "dragleave" (fn [_] (swap! depth #(max 0 (dec %))) (when (zero? @depth) (feedback! false))))
    (.addEventListener js/document "dragover" (fn [ev] (when (files? ev) (.preventDefault ev) (feedback! true))))
    (.addEventListener js/document "drop"
                       (fn [ev]
                         (reset! depth 0) (feedback! false)
                         (when (and (not (.-defaultPrevented ev)) (not (get-in @state [:popup :open?])))
                           (when-let [file (some-> ev .-dataTransfer .-files (aget 0))]
                             (.preventDefault ev) (load-dropped-file! file)))))))

;; ---------- init ----------

(defn ^:export init! []
  (styles/inject!)
  ;; H2-09/H2-10: publish palette-owned badge fills before any painter runs.
  ((-> js/window (aget "CxcShapeCanvas") (aget "setStatusColors"))
    (clj->js palette/status->badge-fill))
  (reset! root (js/document.getElementById "app"))
  (rdom/set-dispatch!
    handle-event!)
  (add-watch state ::rerender on-state-change!)
  (rerender!)
  (wire-document-drop!)
  (let [{:keys [trace shape focus algorithm direction hide-sidebar? sync? layout-debug? overrides]} (query-params)
        shape-url                                                                                   (or shape (derive-shape-path trace))
        SC                                                                                          (aget js/window "CxcShapeCanvas")]
    (update-artifact! assoc :pending-focus focus ;; H10: also handles an already-ready artifact.
                      :advanced? (= "1" (storage-get "cxc-shape-advanced"))) ;; H2-01
    ;; gap 62: private worker fields remain behind quoted JS contracts.
    (when sync? ((aget SC "enableSynchronousElk") elk-instance))
    (swap! state assoc :layout-debug? (boolean (or sync? layout-debug?)))
    (when sync? (swap! state assoc-in [:layout-options :overrides] overrides))
    (when algorithm (swap! state assoc-in [:layout-options :algorithm] algorithm))
    (when-not (contains? (-> SC (aget "algorithms") js->clj set) (get-in @state [:layout-options :algorithm]))
      (swap! state assoc-in [:layout-options :algorithm] "layered"))
    (when direction (swap! state assoc-in [:layout-options :direction] direction))
    (when-not (contains? (-> SC (aget "directions") js->clj set) (get-in @state [:layout-options :direction]))
      (swap! state assoc-in [:layout-options :direction] "DOWN"))
    (when hide-sidebar? (swap! state assoc :sidebar-hidden? true))
    (.toggle (.-classList js/document.body) "hide-sidebar" (:sidebar-hidden? @state))
    ;; gap 18: fall back when a URL/saved algorithm is not in this ELK build.
    (when elk-instance
      (-> ((aget SC "availableAlgorithms") elk-instance)
          (.then (fn [available]
                   (let [caps (-> available js/Array.from js->clj set)]
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
