(ns cxc.incremental
  "F53 L3 — module-level incremental cache.

   Skip recompile of a module when its source AND its dependencies'
   manifests are all unchanged. Complements the tier-1 binary cache
   in `cxc.build` (which keys on emitted `.m` content); the module
   cache keys on inputs (source + dep manifests + compiler version)
   so a per-module fast-path can short-circuit parse+lower+emit
   before ever seeing the emitted C.

   Sub-landing status:
   - **L3.a** — pure `module-cache-key` derivation. Standalone,
     deterministic, no I/O.
   - **L3.b (this file)** — filesystem I/O (`try-restore`, `store`).
   - **L3.c** — wire into `build/compile-directory-to-file` per-module.
   - **L3.d** — integration tests (cache hit on unchanged, miss on
     source change, miss on dep change)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.manifest :as manifest]
            [cxc.util.hash :as hash]))

(def ^:private sha256-hex hash/sha256-hex)

(defn module-cache-key
  "F53 L3.a — derive a deterministic cache key for a module's compiled
   output. Same inputs → same key byte-for-byte; different inputs →
   different key with cryptographic collision resistance.

   Inputs:
   - `source-hash` — sha256 hex of the module's own source (typically
     what F53 L1 already stamps into `<module>.cxc-manifest.json`
     under `:source-hash`).
   - `dep-manifest-hashes` — collection of sha256 hex strings, one per
     transitive dependency's manifest. Order-insensitive: the fn
     sorts internally so callers can pass a set, seq, or vec.
   - `compiler-version` (3-arity) — override the folded-in version
     string. Defaults to `cxc.manifest/compiler-version`; tests use
     the override to verify version invalidation without mutating
     the global constant.

   Invariants:
   - Compiler-version pinning: a release that changes lowering
     invalidates every prior cache entry.
   - Deterministic: `(module-cache-key h [a b]) = (module-cache-key h [b a])`.
   - Every input flows through the sha256 domain — no length-extension
     or delimiter-injection concerns (each field is terminated by \\n).

   Returns a lowercase hex sha256 string."
  ([source-hash dep-manifest-hashes]
   (module-cache-key source-hash dep-manifest-hashes manifest/compiler-version))
  ([source-hash dep-manifest-hashes compiler-version]
   (sha256-hex
    (str compiler-version
         "\n"
         source-hash
         "\n"
         (str/join "\n" (sort dep-manifest-hashes))))))

;; ---------------------------------------------------------------------------
;; F53 L3.b — filesystem I/O for the module cache.
;;
;; Layout:
;;   .cxc-cache/modules/<cache-key>/
;;     <module-name>.m
;;     <module-name>.cxc-manifest.json
;;
;; Each cache-key directory is self-contained — the two artifacts that
;; a module contributes to a build (its emitted ObjC + its manifest)
;; live together and restore atomically. Different modules share the
;; `modules/` root but never collide on <cache-key> (sha256 domain).
;;
;; No wiring into the compile pipeline in this sub-landing — L3.c adds
;; the call sites in `build/compile-directory-to-file`.
;; ---------------------------------------------------------------------------

(def ^:private modules-subdir "modules")

(defn module-cache-dir
  "Absolute `java.io.File` for the directory that would hold a module's
   cached artifacts under `cache-root`. Pure — does not create the
   directory or touch the filesystem. Callers pass `cache-root` as an
   absolute path (typically `.cxc-cache` under the build cwd) so tests
   can point at tmpdirs."
  ^java.io.File [^java.io.File cache-root ^String cache-key]
  (io/file cache-root modules-subdir cache-key))

(defn- m-file-in ^java.io.File [dir module-name]
  (io/file dir (str module-name ".m")))

(defn- manifest-file-in ^java.io.File [dir module-name]
  (io/file dir (str module-name ".cxc-manifest.json")))

(defn try-restore
  "F53 L3.b — attempt to restore a module's cached artifacts.

   If a cache entry exists at `.cxc-cache/modules/<cache-key>/` AND
   both the `<module>.m` and `<module>.cxc-manifest.json` files are
   present, copy them into `out-dir` (creating parents as needed) and
   return `{:cache-hit? true :m-path <str> :manifest-path <str>}`.

   Otherwise return `{:cache-hit? false}` — the caller runs the full
   compile and (on success) calls `store` to populate the cache.

   The two-file existence check guards against a partially-written
   entry (crash between .m write and manifest write) — treat a
   half-populated dir as a miss rather than serving a torn restore.
   L3.c's `store` writes both files then leaves them in place; a
   future L3.b hardening could stage-and-rename for atomicity."
  [^java.io.File cache-root ^String cache-key ^java.io.File out-dir ^String module-name]
  (let [dir      (module-cache-dir cache-root cache-key)
        m-src    (m-file-in dir module-name)
        man-src  (manifest-file-in dir module-name)]
    (if (and (.exists m-src) (.exists man-src))
      (let [m-dst   (m-file-in out-dir module-name)
            man-dst (manifest-file-in out-dir module-name)]
        (io/make-parents m-dst)
        (io/copy m-src m-dst)
        (io/copy man-src man-dst)
        {:cache-hit?    true
         :m-path        (.getPath m-dst)
         :manifest-path (.getPath man-dst)})
      {:cache-hit? false})))

(defn store
  "F53 L3.b — persist a module's compile artifacts to the cache.

   Writes `<cache-root>/modules/<cache-key>/<module>.m` and
   `<cache-root>/modules/<cache-key>/<module>.cxc-manifest.json`
   from the given content strings. Overwrites any existing entry —
   L3.c calls this only after a successful compile, so replacing a
   stale entry (e.g. one from an interrupted store) is safe.

   Returns the entry directory as a `java.io.File` for the caller's
   logging/telemetry."
  ^java.io.File [^java.io.File cache-root ^String cache-key ^String module-name
                 ^String m-content ^String manifest-content]
  (let [dir (module-cache-dir cache-root cache-key)]
    (io/make-parents (io/file dir "placeholder"))
    (spit (m-file-in dir module-name) m-content)
    (spit (manifest-file-in dir module-name) manifest-content)
    dir))
