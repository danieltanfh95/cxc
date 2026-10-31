(ns cxc.util.io
  "Shared stderr println shim. Keeps `(binding [*out* *err*] (println ...))`
   from recurring across every CLI command, build phase, and compiler
   warning emission.")

(defn eprintln
  "Print the args to stderr with println semantics. Thin shim over the
   `(binding [*out* *err*] (apply println xs))` boilerplate — use at any
   warning / error log site that would otherwise write that dance inline."
  [& xs]
  (binding [*out* *err*] (apply println xs)))
