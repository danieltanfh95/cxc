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
         :layout         nil}))

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
                                          :edges (.-edgeCount res)}))))
            (.catch (fn [e]
                      (commit! my-gen
                        #(assoc % :error (str "shape render: " (.-message e)))))))))))

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
               (stats-row "shape edges" (:edges layout))))]
      [:div#shape-container {:class "shape-stage"}]
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
                 (commit! my-gen (fn [s] (assoc s :events (parse-jsonl text)
                                                   :loading? false)))))
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

(defn- handle-event [[tag & args]]
  (case tag
    ::set-filter (swap! state assoc :filter-status (first args))
    ::transition (apply transition! args)
    (js/console.warn "unhandled event" (pr-str tag))))

;; ---------- init ----------

(defn ^:export init! []
  (reset! root (js/document.getElementById "app"))
  (rdom/set-dispatch!
    (fn [_replicant-data handler-event]
      (handle-event handler-event)))
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
  (set! (.-cxcViewerV2 js/window)
        #js {:state       state
             :loadTrace   load-trace!
             :loadShape   load-shape!
             :loadAnns    load-annotations!
             :rerender    rerender!}))
