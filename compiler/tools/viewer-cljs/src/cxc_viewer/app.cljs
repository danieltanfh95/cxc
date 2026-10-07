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
         :sidecar    (schema/empty-sidecar "" "" "")
         :trace-name nil
         :shape-name nil
         :error      nil}))

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

;; ---------- view ----------

(defn- stats-row [label value]
  [:div.stat-row
   [:span.k label]
   [:span.v value]])

(defn- view [{:keys [loading? events sidecar trace-name error]}]
  [:div#app-root
   [:header
    [:h1 "cxc trace viewer (v2)"]
    (when trace-name [:div.trace-name trace-name])]
   (cond
     error     [:div.error (str "load failed: " error)]
     loading?  [:div.loading "loading…"]
     (empty? events)
     [:div.empty "no trace loaded. open with ?trace=sample.trace.jsonl"]
     :else
     [:section.stats
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
                 (:cxc_annotation_version sidecar))])])

;; ---------- render loop ----------

(def ^:private root (atom nil))

(defn- rerender! []
  (when-let [el @root]
    (rdom/render el (view @state))))

(defn- watch-state! []
  (add-watch state ::rerender (fn [_ _ _ _] (rerender!))))

;; ---------- url + load ----------

(defn- query-params []
  (let [p (js/URLSearchParams. (.-search js/location))]
    {:trace (.get p "trace")
     :shape (.get p "shape")}))

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
  (watch-state!)
  (rerender!)
  (let [{:keys [trace shape]} (query-params)]
    (swap! state assoc :shape-name shape)
    (when trace (load-trace! trace)))
  ;; Playwright hooks. Keep this set in sync with the legacy viewer's
  ;; window.* exports so the smoke harness can poke the same handles.
  (set! (.-cxcViewerV2 js/window)
        #js {:state      state
             :loadTrace  load-trace!
             :rerender   rerender!}))
