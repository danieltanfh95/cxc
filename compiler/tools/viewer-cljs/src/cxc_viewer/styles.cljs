(ns cxc-viewer.styles
  (:require [garden.core                    :as garden]
            [cxc-viewer.styles.base         :as base]
            [cxc-viewer.styles.annotations  :as annotations]
            [cxc-viewer.styles.shape        :as shape]
            [cxc-viewer.styles.popup        :as popup]))

;; Declaration maps stay at most eight entries to preserve Garden's order
;; for shorthand/longhand pairs; large rules use consecutive maps.
(def all-css
  (garden/css base/rules annotations/rules shape/rules popup/rules))

(defn inject! []
  (let [doc js/document
        existing (.getElementById doc "cxc-styles")
        el (or existing
               (let [s (.createElement doc "style")]
                 (set! (.-id s) "cxc-styles")
                 (.appendChild (.-head doc) s)
                 s))]
    (set! (.-textContent el) all-css)))
