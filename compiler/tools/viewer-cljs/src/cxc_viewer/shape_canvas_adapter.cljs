(ns cxc-viewer.shape-canvas-adapter
  "H2-14: value boundary for window.CxcShapeCanvas. All host property names
   remain literal strings; JS objects/Maps are read here before entering state.

   Shape request: container DOM element plus {:shape nullable shape map,
   :events ordered event vector, :elk ELK instance, :opts layout-options map,
   :context JS callback bag, :is-current? () -> boolean}. opts accepts algorithm,
   direction, showInit, overrides and the painter's existing spacing options.
   Phase request uses :atom (shape atom map), :elk, :context, :is-current?.
   The host owns mounting and checks isCurrent before mounting; a superseded
   request resolves to nil. A current result projects to nonnegative integer
   :node-count/:edge-count and an immutable :debug map (shape renders only).
   Raw ELK layout/Map workspaces and DOM nodes never enter that result value.

   Context contracts (optional; omitted hooks keep the host's defaults):
   selectedNode: nullable class/id string; heatOn/traceLoaded: booleans;
   shape: nullable shape JSON; idPrefix: string.
   highlight: (id string | id array | null) -> effect;
   selectNode: (JS node {id?, cls, shape?}) -> effect;
   openEdge: (JS direction, nullable reverse direction) -> effect;
   wireAnnotation: (element, JS {kind, target}, anchor string, optional boolean) -> effect;
   wireKeyboardClick: (element, label string) -> effect;
   heatElement: (element, nonnegative count, optional stroke? boolean) -> effect;
   heatLabel: (label element, heated element) -> effect;
   heatAtomCount: (JS node) -> nonnegative count;
   heatEdgeElement: (element, JS direction) -> effect;
   atomName: (id/class string) -> display string;
   edgeTarget: (JS direction) -> JS annotation target.
   Phase contexts share wireAnnotation, heatOn, heatElement and heatLabel;
   phaseCount: (phase string) -> count;
   transitionCount: (JS {from, to, msg}) -> count. Callbacks retain these JS
   arguments; readers below project the values callbacks publish into state.

   Graph request: nullable shape, ordered events, trace-loaded? and show-init?
   booleans, display-label/prettify-msg string functions, atom-shape-for-class
   string -> nullable JS shape atom. Result: vectors of immutable nodes/edges,
   child-id -> owner-id map and dropped ownership diagnostics. A direction has
   string :src/:dst, nonnegative :total/:sync and :msgs as an ordered vector of
   [message string, count] pairs, preserving JS Map insertion order. Phase heat
   is {\"phases\" {JSON-key count}, \"transitions\" {JSON-key count}}.")

(defn host [] (aget js/window "CxcShapeCanvas"))

(defn read-node [node]
  {:id (aget node "id")
   :cls (aget node "cls")
   :label (aget node "label")
   :size (aget node "size")
   :shape (js->clj (aget node "shape") :keywordize-keys true)})

(defn node-name [node]
  (or (some-> (aget node "shape") (aget "name"))
      (aget node "cls") (aget node "id")))

(defn read-edge [edge]
  (when edge
    (let [kind (aget edge "kind")]
      (cond-> {:src (aget edge "src") :dst (aget edge "dst")
               :total (aget edge "total") :sync (aget edge "sync")
               :msgs (mapv (fn [pair] [(aget pair 0) (aget pair 1)])
                           (array-seq (js/Array.from (.entries (aget edge "msgs")))))}
        kind                       (assoc :kind kind)
        (= "obs" kind)             (assoc :obs (aget edge "obs")
                                           :count (aget edge "count"))))))

(defn read-directions [edge reverse-edge]
  (mapv read-edge (remove nil? [edge reverse-edge])))

(defn topology
  [{:keys [shape events trace-loaded? display-label prettify-msg
           atom-shape-for-class show-init?]}]
  (let [sc (host)
        shape-js (clj->js shape)
        graph ((aget sc "buildGraph") shape-js trace-loaded? (clj->js events)
               display-label prettify-msg)
        value ((aget sc "topology") (aget graph "nodes") (aget graph "edges")
               shape-js atom-shape-for-class show-init?)]
    {:nodes (mapv read-node (array-seq (aget value "nodes")))
     :edges (mapv read-edge (array-seq (aget value "edges")))
     :owner-of (into {} (map (fn [pair] [(aget pair 0) (aget pair 1)]))
                     (array-seq (js/Array.from (.entries (aget value "ownerOf")))))
     :dropped-ownership (vec (or (js->clj (aget value "droppedOwnership") :keywordize-keys true) []))}))

(defn phase-heat [shape events]
  (js->clj ((aget (host) "phaseHeat") (clj->js shape) (clj->js events))
           :keywordize-keys false))

(defn read-render-result [result]
  (when result
    {:node-count (aget result "nodeCount") :edge-count (aget result "edgeCount")
     :debug (js->clj (aget result "debug") :keywordize-keys true)}))

(defn render-shape!
  [container {:keys [shape events elk opts context is-current?]}]
  (-> ((aget (host) "renderShapeInto") container
       #js {:shape (clj->js shape) :events (clj->js events) :elk elk
            :opts (clj->js opts) :context context :isCurrent is-current?})
      (.then read-render-result)))

(defn render-phase!
  [container {:keys [atom elk context is-current?]}]
  (-> ((aget (host) "renderPhaseGraphInto") container
       #js {:atom (clj->js atom) :elk elk :context context :isCurrent is-current?})
      (.then read-render-result)))

(defn block-name [element]
  (aget (.-dataset element) "nodeName"))

(defn element-box
  "SVG getBBox -> immutable local-coordinate rectangle."
  [element]
  (let [box (.getBBox element)]
    {:x (.-x box) :y (.-y box) :w (.-width box) :h (.-height box)}))

(defn content-box
  "Dataset strings -> finite positive content box, or the existing stage fallback."
  [svg]
  (let [d (.-dataset svg)
        x (js/parseFloat (aget d "contentX")) y (js/parseFloat (aget d "contentY"))
        w (js/parseFloat (aget d "contentW")) h (js/parseFloat (aget d "contentH"))]
    (if (and (every? js/Number.isFinite [x y w h]) (pos? w) (pos? h))
      {:x x :y y :w w :h h}
      {:x 0 :y 0 :w (max 1 (.-clientWidth svg)) :h (max 1 (.-clientHeight svg))})))
