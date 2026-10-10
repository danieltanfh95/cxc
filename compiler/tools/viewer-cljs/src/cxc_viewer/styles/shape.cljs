(ns cxc-viewer.styles.shape
  (:require [garden.stylesheet :refer [at-media]]))

(def ^:private zoom-cluster
  [["#shape-zoom"
    {:position "absolute" :top "12px" :right "12px" :display "flex" :gap "4px"
     :align-items "center" :background "var(--p-bg)" :border "0.75px solid var(--p-ink)"}
    {:padding "3px 6px" :font-family "'IBM Plex Mono', monospace" :font-size "10px" :z-index "4"}]
   ["#shape-zoom button"
    {:background "transparent" :color "var(--p-ink)" :border "0.5px solid var(--p-ink)"
     :font "inherit" :font-size "13px" :line-height "1" :min-width "28px" :min-height "28px"}
    {:padding "0 8px" :cursor "pointer" :display "inline-flex" :align-items "center"
     :justify-content "center"}]
   ["#shape-zoom button:hover" {:background "var(--p-ink)" :color "var(--p-bg)"}]
   ["#shape-zoom-level"
    {:min-width "40px" :text-align "center" :color "var(--p-ink2)" :letter-spacing ".1em"
     :align-self "center" :font-variant-numeric "tabular-nums" :padding "0 2px"}]
   ["#shape-container.panning" {:cursor "grabbing" :user-select "none"}]
   ["#shape-container.panning *" {:pointer-events "none"}]
   ["#shape-container" {:cursor "grab"}]])

(def ^:private legend
  [["#shape-legend" "#shape-legend-reopen"
    {:position "absolute" :bottom "calc(var(--pt-transport-h, 76px) + 20px)"
     :left "12px" :background "var(--p-bg)"
     :border "0.75px solid var(--p-ink)" :padding "8px 12px"
     :font-family "'IBM Plex Mono', monospace" :font-size "10px"}
    {:z-index "5"}]
   ["#shape-legend"
    {:max-height "calc(100% - var(--pt-transport-h, 76px) - 60px)"
     :overflow-y "auto" :max-width "260px"}]
   ["#shape-legend-reopen"
    {:padding "4px 8px" :cursor "pointer" :font "11px 'IBM Plex Mono', monospace"
     :letter-spacing "0" :color "var(--p-ink)"}]
   ["#shape-legend h5"
    {:margin "0 0 4px" :font-size "10px" :text-transform "uppercase" :letter-spacing ".3em"
     :color "var(--p-ink2)" :font-weight "700"}]
   ["#shape-legend .legend-row"
    {:display "flex" :align-items "center" :gap "6px" :padding "2px 0"}]
   ["#shape-legend .legend-swatch"
    {:width "14px" :height "10px" :flex-shrink "0" :border "0.5px solid var(--p-hair)"}]
   ["#shape-legend .legend-line" {:width "22px" :height "0" :flex-shrink "0"}]
   ["#shape-legend .legend-line.legend-self-dash"
    {:border-top "0"
     :background "linear-gradient(to right, var(--p-rust) 60%, transparent 0) repeat-x"
     :background-size "7px 1.6px" :height "1.6px"}]
   ["#shape-legend .legend-group + .legend-group"
    {:margin-top "6px" :padding-top "6px" :border-top "0.5px dashed var(--p-hair)"}]
   ["#shape-legend-close"
    {:position "absolute" :top "2px" :right "4px" :background "none" :border "0"
     :cursor "pointer" :color "var(--p-ink2)" :padding "2px 4px"}]
   [".legend-layers" {:margin-bottom "6px" :padding-bottom "6px"
                      :border-bottom "0.5px dashed var(--p-hair)"}]
   [".legend-layer-row"
    {:display "flex" :align-items "center" :gap "6px" :padding "2px 0" :cursor "pointer"
     :font "10px 'IBM Plex Mono', monospace" :letter-spacing ".05em"
     :color "var(--p-ink)" :text-transform "none"}]
   [".legend-layer-row input[type=checkbox]"
    {:margin "0" :accent-color "var(--p-accent)" :cursor "pointer"
     :width "12px" :height "12px"}]
   [".legend-layer-row input:disabled" {:cursor "not-allowed"}]
   [".legend-layer-row input:disabled ~ *" {:opacity "0.5"}]
   [".legend-layer-name" {:font-weight "600" :letter-spacing ".05em" :text-transform "uppercase"}]
   [".legend-layer-meta" {:color "var(--p-ink2)" :font-size "9px" :letter-spacing "0"}]
   ["#shape-legend-reopen[data-layers-active=\"true\"]"
    "body.theme-dark #shape-legend-reopen[data-layers-active=\"true\"]"
    {:border-color "var(--p-accent)" :color "var(--p-accent)"}]
   [".legend-layer-badge" {:color "var(--p-accent)" :font-weight "700"}]
   ["body.observations-on .shape-block.has-observations:not(.hover):not(.selected):not(.pt-flash-src):not(.pt-flash-dst):not(.root)"
    {:stroke "var(--p-accent) !important" :stroke-width "1.6 !important"}]
   ["body.observations-on .shape-block:not(.has-observations):not(.container):not(.hover):not(.selected):not(.pt-flash-src):not(.pt-flash-dst):not(.root)"
    {:opacity "0.55"}]])

(def ^:private hint-and-heat
  [["#shape-hint"
    {:position "absolute" :top "12px" :left "12px" :max-width "calc(100% - 290px)"
     :background "var(--p-paper)" :border "1px solid var(--p-ink)" :padding "4px 10px"
     :font "10px 'IBM Plex Mono', monospace"}
    {:letter-spacing ".1em" :line-height "1.5" :color "var(--p-ink2)" :box-sizing "border-box"
     :z-index "4"}]
   ["#shape-hint button"
    {:background "none" :color "var(--p-ink)" :border "0" :cursor "pointer"}]
   ["#shape-hint b" {:color "var(--p-ink)" :font-weight "600"}]
   ["#shape-hint .dismiss" {:margin-left "8px" :padding "0 4px" :vertical-align "middle"}]
   [".legend-heat-ramp"
    {:height "8px" :background "linear-gradient(to right, var(--p-paper3), var(--p-amber))"}]
   [".phase-legend" {:color "var(--p-dim)" :font "10px 'IBM Plex Mono', monospace"}]
   (at-media {:max-width "600px"}
             ["#shape-hint" {:top "56px" :left "12px" :right "12px" :max-width "none"}])])

(def ^:private stage
  [[".stage-toolbar"
    {:display "flex" :align-items "center" :flex-wrap "wrap" :gap "4px" :flex "none"
     :padding "8px 14px" :background "var(--p-paper)" :border-bottom "0.75px solid var(--p-ink)"}]
   [".stage-toolbar label"
    {:display "inline-flex" :align-items "center" :gap "5px" :font-size "10px"
     :letter-spacing ".15em" :text-transform "uppercase" :color "var(--p-ink2)"}]
   [".stage-toolbar input" ".stage-toolbar select"
    {:background "var(--p-paper)" :color "var(--p-ink)" :border "0.75px solid var(--p-ink)"
     :border-radius "0" :font "10px 'IBM Plex Mono', monospace" :padding "3px 6px"
     :letter-spacing ".1em"}]
   [".stage-toolbar input[type=checkbox]" {:accent-color "var(--p-indigo)"}]
   [".stage-toolbar input::placeholder" {:color "var(--p-ink2)" :opacity "1"}]
   [".stage-toolbar .filter-input" {:width "90px"}]
   [".stage-toolbar .toolbar-spacer" {:flex "1"}]
   [".advanced-layouts" {:position "relative" :margin "0"}]
   [".advanced-layouts .layout-controls"
    {:position "absolute" :top "calc(100% + 5px)" :left "0" :z-index "35" :display "flex"
     :flex-direction "column" :gap "8px" :min-width "220px"}
    {:max-width "calc(100% - 32px)" :padding "10px" :background "var(--p-paper)"
     :border "0.75px solid var(--p-hair)" :box-sizing "border-box"}]
   [".advanced-layouts .layout-controls label" {:justify-content "space-between"}]
   [".shape-stage"
    {:flex "1" :min-height "100px" :background "var(--p-paper)" :position "relative"}]
   [".shape-container" {:position "absolute" :inset "0" :overflow "hidden"}]
   [".shape-container svg" {:display "block" :width "100%" :height "100%"}]])

(def ^:private transport
  [[".pt-transport"
    {:flex-wrap "wrap" :justify-content "center" :max-width "calc(100% - 28px)"}]
   [".pt-transport"
    {:position "absolute" :bottom "14px" :left "14px" :right "14px" :width "fit-content"
     :margin "0 auto" :box-sizing "border-box" :display "flex"}
    {:gap "6px" :align-items "center" :background "var(--p-bg)"
     :border "0.75px solid var(--p-ink)" :padding "6px 10px" :font-size "10px"
     :font-family "'IBM Plex Mono', monospace" :letter-spacing ".1em"}
    {:z-index "5"}]
   [".pt-transport button"
    {:background "transparent" :color "var(--p-ink)" :border "0.5px solid var(--p-ink)"
     :font-family "inherit" :font-size "11px" :padding "2px 7px" :cursor "pointer"
     :text-transform "uppercase"}
    {:letter-spacing "0"}]
   [".pt-transport .icon-button" {:padding "0" :width "28px" :font-size "18px"}]
   [".pt-transport .pt-play" {:min-width "56px"}]
   [".pt-transport button:hover" {:background "var(--p-ink)" :color "var(--p-bg)"}]
   [".pt-transport input[type=range]"
    {:width "clamp(80px, 20vw, 260px)" :min-width "0" :margin "0"
     :accent-color "var(--p-accent)"}]
   [".pt-transport select"
    {:background "var(--p-bg)" :color "var(--p-ink)" :border "0.5px solid var(--p-ink)"
     :font-family "inherit" :font-size "10px" :padding "1px 4px" :letter-spacing "0"}]
   [".pt-status"
    {:display "inline-flex" :align-items "center" :justify-content "center" :gap "4px"
     :min-width "0" :flex "1 1 180px" :text-align "center"
     :color "var(--p-dim)" :font-variant-numeric "tabular-nums" :white-space "nowrap"
     :overflow "hidden" :text-overflow "ellipsis"}]
   [".pt-status b" {:color "var(--p-ink)" :font-weight "600"}]
   [".pt-count" {:margin-left "6px"}]])

(def ^:private flash
  [[".shape-block.pt-flash-src"
    {:stroke "var(--p-indigo) !important" :stroke-width "2.6 !important"
     :filter "drop-shadow(0 0 8px var(--p-indigo))"}]
   [".shape-block.pt-flash-dst"
    {:stroke "var(--p-accent) !important" :stroke-width "2.6 !important"
     :filter "drop-shadow(0 0 10px var(--p-accent))"}]
   [".shape-edge.pt-flash"
    {:stroke "var(--p-accent) !important" :stroke-width "2.4 !important"
     :filter "drop-shadow(0 0 6px var(--p-accent))" :opacity "1 !important"}]
   [".pt-dot"
    {:fill "var(--p-accent)" :pointer-events "none"
     :filter "drop-shadow(0 0 4px var(--p-accent))"}]])

(def ^:private detail
  [["#atom-detail"
    {:flex "1" :min-height "0" :overflow "auto" :margin "0" :padding "8px"
     :border-top "1px solid var(--p-dim)"}]
   ["#atom-detail .atom-detail-header"
    {:display "flex" :align-items "baseline" :flex-wrap "wrap" :gap "10px" :row-gap "4px"
     :padding-bottom "6px" :margin-bottom "6px"}]
   ["#atom-detail .atom-detail-name"
    {:font-family "'Barlow Condensed', Impact, sans-serif" :font-weight "700" :font-size "15px"
     :letter-spacing ".08em" :color "var(--p-ink)" :text-transform "uppercase"}]
   ["#atom-detail .atom-detail-summary"
    {:color "var(--p-ink2)" :font-size "11px" :font-family "'IBM Plex Mono', monospace"}]
   ["#atom-detail .btn" {:margin-left "8px" :font-size "10px" :padding "2px 8px"}]
   ["#atom-detail .btn.close" {:margin-left "auto"}]
   ["#atom-detail .focus-toolbar" {:display "inline-flex" :gap "4px" :margin-left "12px"}]
   ["#atom-detail .phase-graph-container"
    {:overflow-x "auto" :max-height "none"
     :border "1px dotted var(--p-dim)" :background "var(--p-bg)" :padding "12px"
     :display "flex" :justify-content "safe center"}]
   ["#atom-detail .phase-graph-container > svg" {:display "block" :flex-shrink "0"}]
   [".detail-wrap"
    {:flex "none" :min-height "100px" :max-height "75%" :display "flex" :flex-direction "column"}]
   ["#detail-splitter"
    {:height "6px" :flex "none" :cursor "ns-resize" :background "var(--p-paper3)"
     :touch-action "none"}]
   ["#detail-splitter:focus-visible" {:outline "2px solid var(--p-accent)"}]
   [".message-table" {:width "100%" :border-collapse "collapse"}]
   [".message-table td" ".message-table th"
    {:text-align "left" :padding "4px" :border-bottom "1px dotted var(--p-dim)"}]
   [".raw-event" {:white-space "pre-wrap" :overflow-wrap "anywhere"}]
   [".diagnostic-list" {:max-height "160px" :overflow "auto"}]])

(def rules
  [zoom-cluster legend hint-and-heat stage transport flash detail])
