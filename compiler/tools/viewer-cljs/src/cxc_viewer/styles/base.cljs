(ns cxc-viewer.styles.base
  (:require [garden.stylesheet :refer [at-media]]))

(def ^:private palette
  [[":root"
    {:--p-bg "#FBF4E7" :--p-ink "#22160A" :--p-dim "#6E604A" :--p-accent "#B65A2A"
     :--p-paper "#FBF4E7" :--p-paper2 "#F2E8D2" :--p-paper3 "#E7D9BA" :--p-ink2 "#6E604A"}
    {:--p-ink3 "#988872" :--p-hair "#D6C6A8" :--p-sepia "#EAD6AF" :--p-wash "#FAE8CB"
     :--p-amber "#D08A3A" :--p-indigo "#4F5B8C" :--p-rust "#8A4225" :--p-success "#42652B"}
    {:--p-fade "rgba(34,22,10,.08)"}]
   ["body.theme-dark"
    {:color-scheme "dark" :--p-bg "#1E1E22" :--p-ink "#E6E6EA" :--p-dim "#A4A4AC"
     :--p-accent "#E89B88" :--p-paper "#1E1E22" :--p-paper2 "#2A2A2E" :--p-paper3 "#36363B"}
    {:--p-ink2 "#A4A4AC" :--p-ink3 "#74747C" :--p-hair "#48484F" :--p-sepia "#C9A47B"
     :--p-wash "rgba(232,155,136,.16)" :--p-amber "#E8CF95" :--p-indigo "#8DB4E0"
     :--p-rust "#E8A685"}
    {:--p-success "#A2CA83" :--p-fade "rgba(230,230,234,.08)"}]])

(def ^:private theme-toggle
  [["#theme-toggle"
    {:position "fixed" :top "8px" :right "8px" :z-index "50" :background "var(--p-bg)"
     :color "var(--p-ink)" :border "0.75px solid var(--p-ink)" :border-radius "0"}
    {:font-family "'IBM Plex Mono', monospace" :width "28px" :height "28px" :font-size "18px"
     :letter-spacing "0" :padding "0" :cursor "pointer"}]
   ["#theme-toggle:hover" {:background "var(--p-paper2)"}]])

(def ^:private drop-zone
  [["#drop"
    {:border "1px dashed var(--p-ink)" :padding "14px" :text-align "center" :font-size "10px"
     :letter-spacing "0" :text-transform "uppercase" :color "var(--p-ink)" :cursor "pointer"}]
   ["#drop .drop-sub" {:color "var(--p-ink2)" :font-size "10px"}]
   ["#drop:hover" "#drop.dragging" {:border-color "var(--p-accent)" :color "var(--p-ink)"}]])

(def ^:private filters
  [[".filters" {:font-size "11px"}]
   [".filters .filter-group" {:padding "4px 0" :border-top "1px dotted var(--p-dim)"}]
   [".filters .filter-head"
    {:color "var(--p-dim)" :font-size "10px" :text-transform "uppercase" :letter-spacing ".1em"
     :margin-bottom "2px"}]
   [".filters .filter" {:display "block" :padding "2px 0" :font-size "11px"}]
   [".filters .filter-label"
    {:display "flex" :align-items "center" :gap "6px" :font-size "11px" :color "var(--p-dim)"}]
   [".filters .filter-input"
    {:background "transparent" :border "1px solid var(--p-dim)" :color "var(--p-ink)"
     :font "inherit" :padding "2px 4px" :width "90px"}]])

(def ^:private hide-chrome
  [["body.hide-sidebar .main" {:grid-template-columns "1fr"}]
   ["body.hide-sidebar .stats" {:display "none"}]
   ["body.hide-chrome header" "body.hide-chrome #theme-toggle" "body.hide-chrome #shape-zoom"
    "body.hide-chrome #shape-legend" "body.hide-chrome #shape-legend-reopen"
    "body.hide-chrome #shape-overlay-pills"
    "body.hide-chrome .stage-toolbar" "body.hide-chrome .pt-transport"
    {:display "none !important"}]
   ["body.hide-chrome .main" {:grid-template-columns "minmax(0, 1fr)" :padding "0" :gap "0"}]
   ["body.hide-chrome .stats" "body.hide-chrome #sidebar-toggle" "body.hide-chrome #shape-hint"
    {:display "none !important"}]])

(def ^:private sidebar-details
  [["details" {:margin "16px 0 0"}]
   ["#sidebar summary" "details summary"
    {:cursor "pointer" :color "var(--p-ink)"
     :font-family "'Barlow Condensed', Impact, sans-serif" :font-weight "700" :font-size "13px"
     :letter-spacing ".08em" :text-transform "uppercase"
     :border-bottom "0.5px solid var(--p-ink)"}
    {:padding "4px 0" :margin-bottom "6px"}]
   ["#sidebar details > summary"
    {:display "flex" :align-items "center" :gap "6px" :min-height "34px" :line-height "24px"
     :list-style "none"}]
   ["#sidebar details > summary::-webkit-details-marker" {:display "none"}]
   ["#sidebar details > summary::marker" {:content "''"}]
   ["#sidebar details > summary::before"
    {:content "'▸'" :flex "0 0 10px" :font-family "ui-monospace, Menlo, monospace"
     :font-size "12px" :font-weight "400" :letter-spacing "0" :line-height "24px"
     :text-align "center"}
    {:color "var(--p-ink2)"}]
   ["#sidebar details[open] > summary::before" {:content "'▾'"}]])

(def ^:private focus-and-sizing
  [["button" "select" "input:not([type=checkbox])" "summary" "[role=button]"
    {:min-height "28px" :box-sizing "border-box"}]
   ["button" {:min-width "28px"}]
   ["#shape-legend-close" "#shape-hint .dismiss" ".icon-button" "#theme-toggle"
    {:min-width "28px" :min-height "28px" :font-size "18px" :line-height "1" :letter-spacing "0"
     :display "inline-flex" :align-items "center" :justify-content "center"}]
   [":is(button, input, select, textarea, a, summary, [tabindex]):focus-visible"
    {:outline "2px solid var(--p-accent)" :outline-offset "2px"}]])

(def ^:private body-and-header
  [["html" "body"
    {:margin "0" :padding "0" :background "var(--p-bg)" :color "var(--p-ink)"
     :font-family "'IBM Plex Mono', ui-monospace, Menlo, Consolas, monospace"
     :letter-spacing ".02em"}]
   ["body" {:line-height "1.5" :color-scheme "light"}]
   ["header"
    {:display "flex" :align-items "center" :gap "8px" :padding "10px 14px"
     :border-bottom "0.75px solid var(--p-ink)"}]
   ["header h1"
    {:margin "0" :font-family "'Barlow Condensed', Impact, sans-serif" :font-size "16px"
     :font-weight "700" :letter-spacing ".3em" :text-transform "uppercase" :flex-shrink "0"}]
   ["header .status"
    {:color "var(--p-ink2)" :font-size "10px" :letter-spacing ".2em" :text-transform "uppercase"
     :min-width "0" :margin-right "36px" :overflow "hidden" :text-overflow "ellipsis"}
    {:white-space "nowrap"}]
   ["#sidebar .trace-name"
    {:display "block" :margin "10px 0 0" :font-size "11px" :overflow-wrap "anywhere"
     :color "var(--p-ink)"}]
   [".loading" ".empty" ".error" {:padding "24px" :color "var(--p-dim)"}]
   [".error" {:color "var(--p-accent)" :font-family "'IBM Plex Mono', monospace"}]])

(def ^:private layout
  [["html" "body" "#app" {:height "100%"}]
   ["#app-root" {:height "100dvh" :display "flex" :flex-direction "column"}]
   ["#app-root > header" {:flex "none"}]
   [".main"
    {:display "grid" :grid-template-columns "280px minmax(0, 1fr)" :position "relative"
     :--sidebar-width "280px" :min-height "0" :flex "1"}]
   [".stats" {:overflow "auto" :min-height "0"}]
   ["#sidebar"
    {:background "var(--p-paper2)" :padding "14px 14px 20px"
     :border-right "0.75px solid var(--p-ink)" :box-sizing "border-box"}]
   [".workspace" {:display "flex" :flex-direction "column" :min-width "0" :min-height "0"}]
   [".stat-row"
    {:display "flex" :justify-content "space-between" :padding "3px 0"
     :font-family "'IBM Plex Mono', monospace" :font-size "11px" :letter-spacing ".08em"
     :font-weight "400"}]
   [".stat-row .k" {:color "var(--p-dim)"}]
   [".stat-row .v" {:font-weight "400"}]
   [".overview .stat-row" {:border-bottom "0.5px dashed var(--p-hair)"}]
   [".overview .stat-row:last-child" {:border-bottom "0"}]
   [".diagnostics" {:font-size "11px"}]
   (at-media {:max-width "760px"}
     [".main" {:grid-template-columns "240px minmax(0, 1fr)" :--sidebar-width "240px"}])
   ["#sidebar-toggle"
    {:position "absolute" :left "calc(var(--sidebar-width) - 16px)" :top "12px" :z-index "51"
     :width "32px" :height "32px" :opacity ".35" :background "var(--p-paper2)"}
    {:border "0.5px solid var(--p-ink3)" :color "var(--p-ink2)"
     :font "10px 'IBM Plex Mono', monospace" :letter-spacing "0" :padding "0" :cursor "pointer"
     :display "inline-flex" :align-items "center"}
    {:justify-content "center"}]
   ["#sidebar-toggle:hover" "#sidebar-toggle:focus-visible"
    {:opacity "1" :color "var(--p-ink)" :background "var(--p-paper3)"}]
   ["body.hide-sidebar #sidebar-toggle" {:left "0"}]])

(def ^:private theme-dark-overrides
  [["body.theme-dark header" "body.theme-dark #sidebar" "body.theme-dark .stage-toolbar"
    "body.theme-dark #sidebar summary"
    {:border-color "var(--p-hair)"}]
   ["body.theme-dark #shape-zoom" "body.theme-dark #shape-zoom button"
    "body.theme-dark #shape-legend" "body.theme-dark #shape-legend-reopen"
    "body.theme-dark #shape-hint" "body.theme-dark #theme-toggle"
    "body.theme-dark .pt-transport" "body.theme-dark .pt-transport button"
    "body.theme-dark .pt-transport select" "body.theme-dark .stage-toolbar input"
    "body.theme-dark .stage-toolbar select" "body.theme-dark .advanced-layouts summary"
    {:border-color "var(--p-ink3)"}]])

(def rules
  [palette theme-toggle drop-zone filters hide-chrome sidebar-details focus-and-sizing
   body-and-header layout theme-dark-overrides])
