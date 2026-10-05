;; Ambient table for the chapter-36 observable-inference slice (slice 41).
;;
;; `cxc.lower.ffi-builtins/ffi-builtins` is registry data, so the harness
;; writes it once per run and hands it to the port, the chapter-32 idiom.
;; One line per builtin, `<name><TAB><return-kw>`, sorted, where the
;; return keyword is printed with pr-str (no builtin carries C-type
;; metadata on its return keyword).

(require 'cxc.lower.ffi-builtins)

(doseq [[k v] (sort-by key cxc.lower.ffi-builtins/ffi-builtins)]
  (println (str k "\t" (pr-str (:return-kw v)))))
