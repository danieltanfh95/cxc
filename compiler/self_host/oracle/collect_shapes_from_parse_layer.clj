;; Oracle for the chapter-19 composition slice,
;; parse_layer_bootstrap + tnt_to_nt_bridge + collect_shapes.
;;
;; Each stdin line is a cxc source snippet (with `\\n` sequences
;; representing physical newlines, same convention as the
;; parse-layer-bootstrap corpus). The oracle runs the live
;; cxc.parse/parse + cxc.named-tuple/collect-shapes, and emits a
;; comma-joined, alphabetically-sorted canonical-name list — the same
;; shape as the collect-shapes oracle (chapter 8), but driven by real
;; AST produced by the parse layer rather than a hand-rolled Nt-flat
;; serialization.
;;
;; Wrapped in `do` so `clj -M -e` emits only per-line println output.

(require 'cxc.parse)
(require 'cxc.named-tuple)
(require 'clojure.string)

(do
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    ;; Chapter 23 `\\N` sentinel, mirrored from the parse-layer oracle.
    (let [src    (-> ln
                     (clojure.string/replace "\\n" "\n")
                     (clojure.string/replace "\\N" "\\n"))
          ast    (cxc.parse/parse src)
          shapes (cxc.named-tuple/collect-shapes ast)
          sorted (sort (keys shapes))]
      (println (clojure.string/join "," sorted)))))
