(ns cxc.incremental
  "F53 L3 — module-level incremental cache.

   Skip recompile of a module when its source AND its dependencies'
   manifests are all unchanged. Complements the tier-1 binary cache
   in `cxc.build` (which keys on emitted `.m` content); the module
   cache keys on inputs (source + dep manifests + compiler version)
   so a per-module fast-path can short-circuit parse+lower+emit
   before ever seeing the emitted C.

   Sub-landing status:
   - **L3.a (this file)** — pure `module-cache-key` derivation.
     Standalone, deterministic, no I/O. Foundation for L3.b/c/d.
   - **L3.b** — filesystem I/O (`try-restore`, `store`).
   - **L3.c** — wire into `build/compile-directory-to-file` per-module.
   - **L3.d** — integration tests (cache hit on unchanged, miss on
     source change, miss on dep change)."
  (:require [clojure.string :as str]
            [cxc.manifest :as manifest]))

(defn- sha256-hex ^String [^String s]
  (let [md  (java.security.MessageDigest/getInstance "SHA-256")
        _   (.update md (.getBytes s "UTF-8"))
        raw (.digest md)]
    (apply str (map #(format "%02x" %) raw))))

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
