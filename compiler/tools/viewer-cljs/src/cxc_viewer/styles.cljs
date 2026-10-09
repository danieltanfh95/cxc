(ns cxc-viewer.styles
  (:require [cxc-viewer.styles.base         :as base]
            [cxc-viewer.styles.annotations  :as annotations]
            [cxc-viewer.styles.shape        :as shape]
            [cxc-viewer.styles.popup        :as popup]))

(def all-css
  (str base/css annotations/css shape/css popup/css))

(defn inject! []
  (let [doc js/document
        existing (.getElementById doc "cxc-styles")
        el (or existing
               (let [s (.createElement doc "style")]
                 (set! (.-id s) "cxc-styles")
                 (.appendChild (.-head doc) s)
                 s))]
    (set! (.-textContent el) all-css)))
