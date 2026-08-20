(ns cxc.core
  "cxc compiler entry point.

   Usage:
     clj -M -m cxc.core <input.cxc> <out-dir> [--target=<name>]

   Target: `--target=macos` (default) selects prelude modules from
   `resources/prelude/target/macos/`. Additional targets can be added
   by dropping a target directory alongside.

   Pipeline:
     cxc.lex      — tokenize .cxc source (layout-aware, bracket-suppressed)
     cxc.parse    — token stream → cxc AST
     cxc.lower    — cxc AST → ObjC AST (hello-world subset for now)
     cxc.emit     — ObjC AST → .m source string
     cxc.build    — write .m, invoke clang, produce binary

   Runtime deps at link time: libobjc2 · libdispatch · libuv · cxc runtime lib
   (per Phase 6 decisions in design/working-notes.md).

   Coverage: hello-world subset. `def main: -> running then: print(\"...\")`
   compiles and runs. Larger cxc programs parse but fail-nyi at lowering.
   Entry point is `def main` (lowercase) by naming convention — no
   `program` keyword (retired at v1.0, see spec §1.11)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.build :as build])
  (:gen-class))

(defn- parse-target-flag
  "Extract `--target=<name>` from args; returns [positional-args target].
   Missing flag → target is nil (caller substitutes prelude default)."
  [args]
  (let [[flag positional] ((juxt filter remove) #(str/starts-with? % "--target=") args)
        target (when-let [f (first flag)]
                 (keyword (subs f (count "--target="))))]
    [(vec positional) target]))

(defn -main
  [& args]
  (let [[positional target] (parse-target-flag args)]
    (case (count positional)
      2 (let [[input-path out-dir] positional
              src   (slurp input-path)
              name  (-> input-path
                        io/file
                        .getName
                        (str/replace #"\.cxc$" ""))
              opts  (if target {:target target} {})
              {:keys [status stderr bin-path]}
              (build/compile-source-to-file src name out-dir opts)]
          (if (zero? status)
            (println (str "compiled → " bin-path))
            (do (println "compile failed:")
                (println stderr)
                (System/exit status))))
      (do (println "Usage: clj -M -m cxc.core <input.cxc> <out-dir> [--target=<name>]")
          (println "Args received:" (pr-str args))))))
