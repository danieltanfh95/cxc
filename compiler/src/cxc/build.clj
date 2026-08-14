(ns cxc.build
  "Build orchestration — write ObjC source to disk, invoke clang, return
   the resulting binary's path. Assumes clang is on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [cxc.parse   :as parse]
            [cxc.lower   :as lower]
            [cxc.emit    :as emit]
            [cxc.prelude :as prelude]))

;; ---------------------------------------------------------------------------
;; Build cache (tier 1: .m → binary)
;;
;; The emit output (.m source) fully determines the binary output — no
;; hidden inputs, no timestamps, no environment. So a content-hash of
;; the .m is a safe cache key: identical .m ⇒ identical binary. When
;; the compiler internals change (parse/lower/emit), the emit output
;; changes too, and the hash naturally invalidates the entry.
;;
;; Clang dominates the build: ~200–500ms per invocation. On a repeat
;; run with no compiler change, the cache serves every example from
;; disk in a few ms total.
;;
;; Side benefit: the LAST emitted .m for every named example is copied
;; to `.cxc-cache/last/<name>.m` for browsing. Point of the cache dir
;; is convenience, not correctness — nuking it and rebuilding always
;; produces the same output.
;; ---------------------------------------------------------------------------

(def ^:private cache-root (io/file ".cxc-cache"))
(def ^:private cache-bin-dir  (io/file cache-root "bin"))
(def ^:private cache-last-dir (io/file cache-root "last"))

(defn- sha256-hex [^String s]
  (let [md   (java.security.MessageDigest/getInstance "SHA-256")
        _    (.update md (.getBytes s "UTF-8"))
        raw  (.digest md)]
    (apply str (map #(format "%02x" %) raw))))

(defn- copy-file [^java.io.File src ^java.io.File dst]
  (io/make-parents dst)
  (java.nio.file.Files/copy (.toPath src) (.toPath dst)
                            (into-array java.nio.file.CopyOption
                                        [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))

(defn- ensure-executable [^java.io.File f]
  ;; Files/copy preserves mode on some platforms but not reliably —
  ;; mark the binary +x explicitly so a run after a cache-hit doesn't
  ;; hit "Permission denied".
  (.setExecutable f true false))

(defn compile-source-to-file
  "Compile cxc source text through parse → lower → emit → clang.
   Prepends the compiler-shipped prelude (see cxc.prelude) before
   parsing so records/atoms defined there are visible to user code
   without an import statement. Writes .m to `<out-dir>/<name>.m`
   and produces binary at `<out-dir>/<name>`.

   Tier-1 cache: content-hashes the emitted .m; on hit, copies the
   cached binary into place and skips clang entirely. On miss, runs
   clang as usual and, on success, stores the binary under
   `.cxc-cache/bin/<sha>`. Regardless of hit or miss, the .m is
   copied to `.cxc-cache/last/<name>.m` so you can browse the most
   recently emitted ObjC for any named example without hunting
   through test tmpdirs.

   Returns {:m-path <path> :bin-path <path> :status <clang-exit-code>
            :stdout <clang-stdout> :stderr <clang-stderr>
            :cache-hit? <bool>}."
  [source name out-dir]
  (let [_        (io/make-parents (io/file out-dir "placeholder"))
        m-src    (-> (str prelude/source source)
                     parse/parse
                     prelude/rewrite-prelude-names
                     lower/lower
                     emit/emit)
        m-file   (io/file out-dir (str name ".m"))
        bin-file (io/file out-dir name)
        _        (spit m-file m-src)
        ;; Browse-cache: always keep the last emitted .m for each name.
        _        (copy-file m-file (io/file cache-last-dir (str name ".m")))
        sha      (sha256-hex m-src)
        cache-bin (io/file cache-bin-dir sha)]
    (if (.exists cache-bin)
      (do (copy-file cache-bin bin-file)
          (ensure-executable bin-file)
          {:m-path     (.getPath m-file)
           :bin-path   (.getPath bin-file)
           :status     0
           :stdout     ""
           :stderr     ""
           :cache-hit? true})
      (let [{:keys [exit out err]}
            (shell/sh "clang" "-fobjc-arc"
                      "-framework" "Foundation"
                      "-o" (.getPath bin-file) (.getPath m-file))]
        (when (zero? exit)
          (copy-file bin-file cache-bin))
        {:m-path     (.getPath m-file)
         :bin-path   (.getPath bin-file)
         :status     exit
         :stdout     out
         :stderr     err
         :cache-hit? false}))))

(defn run-binary
  "Execute the compiled binary and return {:exit :stdout :stderr}."
  [bin-path]
  (let [{:keys [exit out err]} (shell/sh bin-path)]
    {:exit exit :stdout out :stderr err}))
