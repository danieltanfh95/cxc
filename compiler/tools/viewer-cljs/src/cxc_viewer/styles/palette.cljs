(ns cxc-viewer.styles.palette)

;; H2-09/H2-10: one ordered table owns status presentation on both surfaces.
;; Badge overrides preserve the existing pixels, including verified's literal
;; green. Panel and badge hues intentionally stay distinct in this wave.
(def ^:private status-palette
  (array-map
    "draft"       {:color-var "--p-indigo"  :badge-color-var "--p-dim"}
    "open"        {:color-var "--p-accent"}
    "in_progress" {:color-var "--p-rust"    :badge-color-var "--p-indigo"}
    "addressed"   {:color-var "--p-success" :badge-color-var "--p-amber"}
    "verified"    {:color-var "--p-success" :badge-fill "#4a9b70"}
    "wontfix"     {:color-var "--p-ink2"    :badge-color-var "--p-dim"}
    "stale"       {:color-var "--p-ink2"    :badge-color-var "--p-rust"}))

(def status->color-var
  (into (array-map) (map (fn [[status presentation]]
                          [status (:color-var presentation)])) status-palette))

(def status->badge-fill
  (into {} (map (fn [[status {:keys [color-var badge-color-var badge-fill]}]]
                  [status (or badge-fill (str "var(" (or badge-color-var color-var) ")"))]))
        status-palette))

(defn status-rules
  "Return Garden rules for a status-colored property in palette order."
  [selector property]
  (mapv (fn [[status color-var]]
          [(str selector "[data-status=\"" status "\"]")
           {property (str "var(" color-var ")")}])
        status->color-var))
