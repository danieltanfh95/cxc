;; Ambient tables for the chapter-32 pipeline slice (slice 38,
;; `prelude_program.cxc`), the chapter-5 pattern. The harness runs this
;; file once per table and hands each output to the port as a file, so
;; the port reads exactly the data the Clojure passes read internally.
;;
;; CXC_TABLE=stdlib    `build/stdlib-manifest`, the known exports of the
;;                     prelude std.* modules that validate-imports checks.
;;                     One `<module>|<name>` line per export, sorted by
;;                     module and then name (the order the
;;                     `does not export` message lists them in).
;; CXC_TABLE=registry  `prelude/prelude-modules`, the concatenation order
;;                     of the prelude sources. One `<name>|<kind>` line
;;                     per module, in registry order.
;;
;; The prelude name index (`resolve/prelude-name->module`, whose key set
;; is validate-no-prelude-collisions!'s protected-name set) comes from
;; the slice-37 file `resolve_program_symbols_index.clj`.

(require 'cxc.build)
(require 'cxc.prelude)

(do
  (case (System/getenv "CXC_TABLE")
    "stdlib"
    (doseq [[m names] (sort-by key @#'cxc.build/stdlib-manifest)
            nm (sort names)]
      (println (str m "|" nm)))
    "registry"
    (doseq [{:keys [name kind]} cxc.prelude/prelude-modules]
      (println (str (clojure.core/name name) "|" (clojure.core/name kind))))))
