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
  (atom {:gen        0
         :loading?   false
         :events     []
         :shape      nil
         :sidecar    (schema/empty-sidecar "" "" "")
         :trace-name nil
         :shape-name nil
         :error      nil
         :layout     nil}))

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

(defn- view [{:keys [loading? events shape sidecar trace-name shape-name error layout]}]
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
      [:div#shape-container {:class "shape-stage"}]])])

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
    (swap! state assoc :loading? true :trace-name trace-url :error nil)
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

;; ---------- init ----------

(defn ^:export init! []
  (reset! root (js/document.getElementById "app"))
  (add-watch state ::rerender on-state-change)
  (rerender!)
  (let [{:keys [trace shape]} (query-params)
        shape-url (or shape (derive-shape-path trace))]
    (when trace     (load-trace! trace))
    (when shape-url (load-shape! shape-url)))
  (set! (.-cxcViewerV2 js/window)
        #js {:state      state
             :loadTrace  load-trace!
             :loadShape  load-shape!
             :rerender   rerender!}))
