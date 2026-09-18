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
     cxc.lower    — cxc AST → ObjC AST (v1.0 language surface)
     cxc.emit     — ObjC AST → .m source string
     cxc.build    — write .m, invoke clang, produce binary

   Runtime deps at link time: libobjc2 · libdispatch · libuv · cxc runtime lib.

   Coverage: v1.0 language surface — records / def-atom actors /
   enums / capabilities / F9 extensions / F32 concurrent atoms /
   F37 canonical bytes / F51 module scope / F52 imports and let-
   bindings / F56 emit-boundary mangling. Entry point is `def main`
   (lowercase) by naming convention — no `program` keyword (retired
   at v1.0, see spec §1.11)."
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

(defn- parse-trace-flag
  [args]
  (let [[flag positional] ((juxt filter remove) #{"--trace"} args)]
    [(vec positional) (boolean (seq flag))]))

(defn -main
  [& args]
  (let [[positional target] (parse-target-flag args)
        [positional trace?] (parse-trace-flag positional)]
    (case (count positional)
      2 (let [[input-path out-dir] positional
              in-file (io/file input-path)
              opts    (cond-> {}
                        target (assoc :target target)
                        trace? (assoc :trace? true))
              dir?    (.isDirectory in-file)
              name    (if dir?
                        (.getName in-file)
                        (-> input-path
                            io/file
                            .getName
                            (str/replace #"\.cxc$" "")))
              {:keys [status stderr bin-path]}
              (if dir?
                (build/compile-directory-to-file in-file name out-dir opts)
                (let [src (slurp input-path)]
                  (build/compile-source-to-file src name out-dir opts)))]
          (if (zero? status)
            (println (str "compiled → " bin-path))
            (do (println "compile failed:")
                (println stderr)
                (System/exit status))))
      (do (println "Usage: clj -M -m cxc.core <input.cxc-or-dir> <out-dir> [--target=<name>] [--trace]")
          (println "Args received:" (pr-str args))))))
