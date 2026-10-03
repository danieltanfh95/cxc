;; Oracle for the mangle::c-safe-param-name port.
;;
;; Reads lines from stdin, applies the live cxc.mangle namespace's
;; c-safe-param-name, writes one line per input to stdout. Pure
;; passthrough, no canonicalisation.
;;
;; Invocation. cd compiler && clj -M -e "$(cat self_host/oracle/c_safe_param.clj)"

(require 'cxc.mangle)

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (println (cxc.mangle/c-safe-param-name ln)))
