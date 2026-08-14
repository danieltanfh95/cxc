(ns cxc.build
  "Build orchestration — write ObjC source to disk, invoke clang, return
   the resulting binary's path. Assumes clang is on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [cxc.parse   :as parse]
            [cxc.lower   :as lower]
            [cxc.emit    :as emit]
            [cxc.prelude :as prelude]))

(defn compile-source-to-file
  "Compile cxc source text through parse → lower → emit → clang.
   Prepends the compiler-shipped prelude (see cxc.prelude) before
   parsing so records/atoms defined there are visible to user code
   without an import statement. Writes .m to `<out-dir>/<name>.m`
   and produces binary at `<out-dir>/<name>`.
   Returns {:m-path <path> :bin-path <path> :status <clang-exit-code>
            :stdout <clang-stdout> :stderr <clang-stderr>}."
  [source name out-dir]
  (let [_        (io/make-parents (io/file out-dir "placeholder"))
        m-src    (-> (str prelude/source source)
                     parse/parse
                     prelude/rewrite-prelude-names
                     lower/lower
                     emit/emit)
        m-file   (io/file out-dir (str name ".m"))
        bin-file (io/file out-dir name)]
    (spit m-file m-src)
    (let [{:keys [exit out err]}
          (shell/sh "clang" "-fobjc-arc"
                    "-framework" "Foundation"
                    "-o" (.getPath bin-file) (.getPath m-file))]
      {:m-path   (.getPath m-file)
       :bin-path (.getPath bin-file)
       :status   exit
       :stdout   out
       :stderr   err})))

(defn run-binary
  "Execute the compiled binary and return {:exit :stdout :stderr}."
  [bin-path]
  (let [{:keys [exit out err]} (shell/sh bin-path)]
    {:exit exit :stdout out :stderr err}))
