(ns cxc.build
  "Build orchestration — write ObjC source to disk, invoke clang, return
   the resulting binary's path. Assumes clang is on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [cxc.parse :as parse]
            [cxc.lower :as lower]
            [cxc.emit  :as emit]))

(defn compile-source-to-file
  "Compile cxc source text through parse → lower → emit → clang.
   Writes .m to `<out-dir>/<name>.m` and produces binary at `<out-dir>/<name>`.
   Returns {:m-path <path> :bin-path <path> :status <clang-exit-code>
            :stdout <clang-stdout> :stderr <clang-stderr>}."
  [source name out-dir]
  (let [_        (io/make-parents (io/file out-dir "placeholder"))
        m-src    (-> source parse/parse lower/lower emit/emit)
        m-file   (io/file out-dir (str name ".m"))
        bin-file (io/file out-dir name)]
    (spit m-file m-src)
    (let [{:keys [exit out err]}
          (shell/sh "clang" "-fobjc-arc" "-o" (.getPath bin-file) (.getPath m-file))]
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
