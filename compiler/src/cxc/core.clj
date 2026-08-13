(ns cxc.core
  "cxc compiler entry point.

   Usage:
     clj -M -m cxc.core <input.cxc> <out-dir>

   Pipeline:
     cxc.lex      — tokenize .cxc source (layout-aware, bracket-suppressed)
     cxc.parse    — token stream → cxc AST
     cxc.lower    — cxc AST → ObjC AST (hello-world subset for now)
     cxc.emit     — ObjC AST → .m source string
     cxc.build    — write .m, invoke clang, produce binary

   Runtime deps at link time: libobjc2 · libdispatch · libuv · cxc runtime lib
   (per Phase 6 decisions in design/working-notes.md).

   Coverage: hello-world subset. `def main: -> running do: print(\"...\")`
   compiles and runs. Larger cxc programs parse but fail-nyi at lowering.
   Entry point is `def main` (lowercase) by naming convention — no
   `program` keyword (retired at v1.0, see spec §1.11)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.build :as build])
  (:gen-class))

(defn -main
  [& args]
  (case (count args)
    2 (let [[input-path out-dir] args
            src   (slurp input-path)
            name  (-> input-path
                      io/file
                      .getName
                      (str/replace #"\.cxc$" ""))
            {:keys [status stderr bin-path]}
            (build/compile-source-to-file src name out-dir)]
        (if (zero? status)
          (println (str "compiled → " bin-path))
          (do (println "compile failed:")
              (println stderr)
              (System/exit status))))
    (do (println "Usage: clj -M -m cxc.core <input.cxc> <out-dir>")
        (println "Args received:" (pr-str args)))))
