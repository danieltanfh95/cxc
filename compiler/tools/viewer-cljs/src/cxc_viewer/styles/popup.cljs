(ns cxc-viewer.styles.popup)

(def ^:private annotate
  [["#annotate-backdrop"
    {:position "fixed" :inset "0" :background "rgba(0,0,0,.35)" :z-index "55"}]
   ["#annotate-popup"
    {:position "fixed" :top "50%" :left "50%" :transform "translate(-50%,-50%)"
     :width "min(640px, calc(100vw - 32px))" :max-height "calc(100vh - 32px)" :overflow-y "auto"
     :background "var(--p-bg)"}
    {:border "0.75px solid var(--p-ink)" :padding "14px 16px" :z-index "60" :font-size "11px"
     :color "var(--p-ink)" :box-shadow "0 2px 16px rgba(0,0,0,.3)"
     :font-family "'IBM Plex Mono', monospace"}]
   ["#annotate-popup .popup-title"
    {:font-family "'Barlow Condensed', Impact, sans-serif" :letter-spacing ".25em"
     :text-transform "uppercase" :font-size "12px" :margin-bottom "8px" :color "var(--p-ink2)"}]
   ["#annotate-popup .popup-target"
    {:color "var(--p-ink2)" :font-size "10px" :letter-spacing ".1em" :margin-bottom "8px"}]
   ["#annotate-popup .popup-target span"
    {:color "var(--p-ink)" :word-break "break-all" :text-transform "none"}]
   ["#annotate-note"
    {:width "100%" :font "inherit" :padding "6px 8px" :border "0.5px solid var(--p-ink2)"
     :background "var(--p-bg)" :color "var(--p-ink)" :resize "vertical" :box-sizing "border-box"}
    {:min-height "110px"}]
   ["#annotate-popup .popup-attachments"
    {:display "flex" :flex-wrap "wrap" :gap "6px" :margin-top "6px"}]
   ["#annotate-popup .popup-att"
    {:padding "3px 6px" :border "0.5px solid var(--p-ink2)" :font-size "10px"
     :display "inline-flex" :align-items "center" :gap "4px"}]
   ["#annotate-popup .popup-att img" {:max-width "72px" :max-height "72px" :display "block"}]
   ["#annotate-popup .popup-att-remove"
    {:background "none" :border "0" :cursor "pointer" :color "var(--p-ink)" :font "inherit"
     :padding "0 4px"}]
   ["#annotate-popup .popup-actions"
    {:display "flex" :gap "8px" :justify-content "flex-end" :margin-top "10px"
     :align-items "center"}]
   ["#annotate-popup .popup-hint"
    {:margin-right "auto" :color "var(--p-ink2)" :font-size "10px"}]
   ["#annotate-popup button"
    {:background "transparent" :color "var(--p-ink)" :border "1px solid var(--p-ink)"
     :font "inherit" :padding "3px 10px" :cursor "pointer"}]
   ["#annotate-popup button.primary"
    {:background "var(--p-accent)" :color "var(--p-bg)" :border-color "var(--p-accent)"}]])

(def ^:private chat-excerpt
  [[".chat-excerpt-loading" ".chat-excerpt-error"
    {:font-style "italic" :color "var(--p-ink2)" :font-size "10px" :padding "4px 6px"
     :border-left "2px solid var(--p-indigo)"}]
   [".chat-excerpt-error" {:color "var(--p-accent)" :border-left-color "var(--p-accent)"}]
   [".chat-excerpt-hit"
    {:margin "4px 0" :padding "4px 0" :border-top "0.5px dashed var(--p-dim)"}]
   [".chat-excerpt-hit:first-child" {:border-top "0"}]
   [".chat-excerpt-meta" {:color "var(--p-ink2)" :font-size "9px" :margin-bottom "2px"}]
   [".chat-excerpt-text" {:white-space "pre-wrap" :font-size "10px"}]
   [".chat-excerpt-body.compact" {:max-height "100px" :overflow "hidden"}]
   [".chat-excerpt-body.expanded" {:max-height "none"}]])

(def rules
  [annotate chat-excerpt])
