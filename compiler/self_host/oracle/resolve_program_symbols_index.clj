;; Prelude index for the chapter-31 symbol-table slice (slice 37,
;; `resolve_program_symbols.cxc`).
;;
;; `cxc.resolve/build-symbol-table` reads `prelude-name->module`
;; internally. The port cannot, so the harness runs this file once and
;; hands its output to the port as a file. One `<name>|<module>` line
;; per entry, sorted by name, the module printed as the keyword name
;; (`std.time`).

(require 'cxc.resolve)

(do
  (doseq [[k v] (sort-by key (cxc.resolve/prelude-name->module))]
    (println (str k "|" (name v)))))
