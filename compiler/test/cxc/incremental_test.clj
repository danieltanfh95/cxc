(ns cxc.incremental-test
  "F53 L3.a — module cache-key derivation. Standalone pure-fn tests;
   no filesystem, no compile pipeline. L3.b/c/d land the I/O + wiring
   layers on top."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cxc.incremental :as incremental]
            [cxc.manifest :as manifest]))

(deftest module-cache-key-is-deterministic
  (testing "F53 L3.a — same inputs produce the same cache key byte-for-byte across calls. Foundation invariant for the cache: without this, no hit is ever reliable."
    (let [k1 (incremental/module-cache-key "src-hash-abc" ["dep1" "dep2"])
          k2 (incremental/module-cache-key "src-hash-abc" ["dep1" "dep2"])]
      (is (= k1 k2)))))

(deftest module-cache-key-is-order-insensitive-on-dep-hashes
  (testing "F53 L3.a — dep hashes are sorted internally, so passing the same set in different orders (or as a set vs a vector) yields the same key. Dep discovery order shouldn't affect cache hits."
    (let [k-vec-abc (incremental/module-cache-key "src" ["a" "b" "c"])
          k-vec-cab (incremental/module-cache-key "src" ["c" "a" "b"])
          k-set     (incremental/module-cache-key "src" #{"c" "b" "a"})]
      (is (= k-vec-abc k-vec-cab))
      (is (= k-vec-abc k-set)))))

(deftest module-cache-key-source-change-invalidates
  (testing "F53 L3.a — changing the source hash flips the cache key. Editing a module must miss the cache and trigger recompile."
    (let [k-orig    (incremental/module-cache-key "src-hash-original" ["dep1"])
          k-changed (incremental/module-cache-key "src-hash-changed"  ["dep1"])]
      (is (not= k-orig k-changed)))))

(deftest module-cache-key-dep-change-invalidates
  (testing "F53 L3.a — changing any dependency manifest hash flips the cache key. A dep-only rebuild must invalidate every downstream module's cache entry."
    (let [k-orig    (incremental/module-cache-key "src" ["dep1-v1" "dep2"])
          k-changed (incremental/module-cache-key "src" ["dep1-v2" "dep2"])]
      (is (not= k-orig k-changed)))))

(deftest module-cache-key-dep-added-invalidates
  (testing "F53 L3.a — adding a new dependency flips the cache key. The sort is over the input collection, so a `[]` → `[\"new\"]` transition necessarily changes the joined string."
    (let [k-no-deps  (incremental/module-cache-key "src" [])
          k-one-dep  (incremental/module-cache-key "src" ["new"])]
      (is (not= k-no-deps k-one-dep)))))

(deftest module-cache-key-compiler-version-invalidates
  (testing "F53 L3.a — the folded-in compiler-version means a release that changes lowering invalidates every cache entry. Exercises the 3-arity override so tests don't have to mutate the manifest/compiler-version constant."
    (let [k-v1 (incremental/module-cache-key "src" ["dep1"] "0.11.0")
          k-v2 (incremental/module-cache-key "src" ["dep1"] "0.12.0")]
      (is (not= k-v1 k-v2)))))

(deftest module-cache-key-two-arity-uses-manifest-version
  (testing "F53 L3.a — the 2-arity call folds in `cxc.manifest/compiler-version` by default. Equivalent to explicitly passing the current value; guarantees production callers pin to the pinned version without threading it through every call."
    (let [k-implicit (incremental/module-cache-key "src" ["dep1"])
          k-explicit (incremental/module-cache-key "src" ["dep1"]
                                                   manifest/compiler-version)]
      (is (= k-implicit k-explicit)))))

(deftest module-cache-key-returns-hex-sha256
  (testing "F53 L3.a — output is lowercase hex sha256 (64 chars, [0-9a-f]). Shape matters for filesystem-safe cache directory names in L3.b."
    (let [k (incremental/module-cache-key "src" ["dep1"])]
      (is (= 64 (count k)))
      (is (re-matches #"[0-9a-f]{64}" k)))))

;; ---------------------------------------------------------------------------
;; F53 L3.b — filesystem I/O for the module cache. Standalone shape:
;; `try-restore` returns hit/miss + copies artifacts on hit; `store`
;; persists .m + manifest under the cache-key directory. No wiring
;; into the compile pipeline in this sub-landing.
;; ---------------------------------------------------------------------------

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "cxc-incremental-test-"
            (into-array java.nio.file.attribute.FileAttribute []))))

(deftest try-restore-miss-when-cache-empty
  (testing "F53 L3.b — `try-restore` returns `{:cache-hit? false}` when no entry exists for the given cache-key. Caller runs the full compile pipeline and then calls `store` on success."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          result     (incremental/try-restore
                       cache-root "nonexistent-key" out-dir "mymod")]
      (is (= {:cache-hit? false} result)))))

(deftest store-then-try-restore-hits
  (testing "F53 L3.b — `store` followed by `try-restore` with the same cache-key returns a hit and copies both artifacts (`.m` + `.cxc-manifest.json`) into out-dir. Round-trip proof."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          m-content  "// emitted objc for mymod\nvoid foo(void) {}\n"
          man-content "{\"schema\":\"cxc-manifest/v1\",\"module\":\"mymod\"}\n"
          _          (incremental/store
                       cache-root "abc123" "mymod"
                       m-content man-content)
          restored   (incremental/try-restore
                       cache-root "abc123" out-dir "mymod")]
      (is (true? (:cache-hit? restored)))
      (is (= m-content   (slurp (:m-path restored))))
      (is (= man-content (slurp (:manifest-path restored)))))))

(deftest try-restore-different-key-still-miss
  (testing "F53 L3.b — a store under one cache-key doesn't satisfy a try-restore under a different cache-key. Isolation between distinct compile inputs (post-L3.c wiring, this prevents source-A's cache entry from being served for source-B)."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          _          (incremental/store
                       cache-root "keyA" "mymod" "// A\n" "{}\n")
          result     (incremental/try-restore
                       cache-root "keyB" out-dir "mymod")]
      (is (false? (:cache-hit? result))))))

(deftest try-restore-partial-entry-treated-as-miss
  (testing "F53 L3.b — if only the `.m` is present but the manifest is missing (crash between the two writes), `try-restore` treats the entry as a miss rather than serving a torn restore. Documents the two-file existence check that guards against half-populated dirs."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          dir        (incremental/module-cache-dir cache-root "partial-key")
          _          (io/make-parents (io/file dir "placeholder"))
          _          (spit (io/file dir "mymod.m") "// only .m present\n")
          ;; no manifest written
          result     (incremental/try-restore
                       cache-root "partial-key" out-dir "mymod")]
      (is (false? (:cache-hit? result))))))

(deftest store-overwrites-existing-entry
  (testing "F53 L3.b — storing to an existing cache-key directory overwrites both artifacts. L3.c only calls store after a successful compile, so replacing a stale entry (e.g. from an interrupted store) is intentional."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          _          (incremental/store
                       cache-root "keyX" "mymod" "// old m\n" "// old manifest\n")
          _          (incremental/store
                       cache-root "keyX" "mymod" "// new m\n" "// new manifest\n")
          restored   (incremental/try-restore
                       cache-root "keyX" out-dir "mymod")]
      (is (true? (:cache-hit? restored)))
      (is (= "// new m\n"        (slurp (:m-path restored))))
      (is (= "// new manifest\n" (slurp (:manifest-path restored)))))))

(deftest module-cache-dir-is-under-modules-subdir
  (testing "F53 L3.b — `module-cache-dir` computes the entry path deterministically under `<cache-root>/modules/<cache-key>/`. Pure fn — does not touch the filesystem. Exposed so L3.c's caller can construct paths for logging/telemetry without depending on the private layout constants."
    (let [cache-root (io/file "/tmp/example-root")
          d          (incremental/module-cache-dir cache-root "abcd")]
      (is (= "/tmp/example-root/modules/abcd" (.getPath d)))
      (is (false? (.exists d))
          "pure — does not create the directory"))))

;; ---------------------------------------------------------------------------
;; F53 L3 integration — prove L3.a (key derivation) and L3.b (filesystem
;; I/O) compose correctly for the flow L3.c will drive: (source-hash,
;; dep-manifest-hashes) → cache-key → store/restore. Exercises the
;; guarantees users depend on: unchanged inputs hit; source change misses;
;; dependency change misses; compiler-version change misses. These aren't
;; new mechanisms — the pieces already exist — but a wire-up test catches
;; refactors that break the composition without breaking each piece
;; individually.
;; ---------------------------------------------------------------------------

(defn- fake-manifest [module-name source-hash]
  (str "{\"schema\":\"cxc-manifest/v1\",\"module\":\""
       module-name "\",\"source-hash\":\"" source-hash "\"}\n"))

(deftest l3-integration-unchanged-inputs-hit-cache
  (testing "F53 L3 integration — the canonical happy path: given a module with a stable source and stable deps, deriving the cache-key twice and using it to store/restore produces a hit on the second call. This is what L3.c will exercise on repeat builds where nothing has changed."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          src-hash   "srchash-for-mymod"
          dep-hashes ["dep-a-manifest-hash" "dep-b-manifest-hash"]
          key        (incremental/module-cache-key src-hash dep-hashes)
          m-content  "// mymod.m contents\n"
          man-content (fake-manifest "mymod" src-hash)]
      ;; Prime the cache.
      (incremental/store cache-root key "mymod" m-content man-content)
      ;; Second compile with SAME inputs derives the SAME key and hits.
      (let [key-2   (incremental/module-cache-key src-hash dep-hashes)
            result  (incremental/try-restore cache-root key-2 out-dir "mymod")]
        (is (= key key-2) "same inputs must derive the same key")
        (is (true? (:cache-hit? result)) "second call must hit the primed entry")
        (is (= m-content   (slurp (:m-path result))))
        (is (= man-content (slurp (:manifest-path result))))))))

(deftest l3-integration-source-change-misses-cache
  (testing "F53 L3 integration — when a module's own source changes, the derived cache-key changes, and try-restore under the new key misses. This forces recompile in L3.c even if the module's deps are otherwise unchanged."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          dep-hashes ["dep-a-hash"]
          key-orig   (incremental/module-cache-key "src-v1" dep-hashes)
          key-new    (incremental/module-cache-key "src-v2" dep-hashes)]
      (is (not= key-orig key-new)
          "source-hash change must flip the derived key")
      ;; Prime with the original.
      (incremental/store cache-root key-orig "mymod"
                        "// v1\n" (fake-manifest "mymod" "src-v1"))
      ;; Retrieve under the new key: miss.
      (let [result (incremental/try-restore cache-root key-new out-dir "mymod")]
        (is (false? (:cache-hit? result))
            "the new cache-key has no entry — L3.c would run the full compile pipeline")))))

(deftest l3-integration-dep-manifest-change-misses-cache
  (testing "F53 L3 integration — when a dependency's manifest hash changes (e.g. someone edited the dep and its manifest sha rolled), the dependent's derived cache-key changes and try-restore misses. This is the cascade behavior L3.c relies on: rebuilding a leaf module invalidates every downstream module's cache entry."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          src-hash   "consumer-src-hash"
          key-orig   (incremental/module-cache-key src-hash ["dep-v1-manifest"])
          key-new    (incremental/module-cache-key src-hash ["dep-v2-manifest"])]
      (is (not= key-orig key-new)
          "changing a dep-manifest-hash must flip the dependent's derived key")
      (incremental/store cache-root key-orig "consumer"
                        "// prior consumer.m\n"
                        (fake-manifest "consumer" src-hash))
      (let [result (incremental/try-restore cache-root key-new out-dir "consumer")]
        (is (false? (:cache-hit? result))
            "the new key has no entry — L3.c would recompile consumer against the updated dep")))))

(deftest l3-integration-compiler-version-change-invalidates-across-modules
  (testing "F53 L3 integration — a compiler-version bump invalidates every cache entry uniformly, regardless of the source or dep hashes. L3.a's 3-arity variant lets this integration test verify version-invalidation without mutating manifest/compiler-version. Two distinct modules share the same source/dep hashes across the version bump; both cache entries stored under the old version miss under the new version."
    (let [cache-root (tmp-dir)
          out-dir    (tmp-dir)
          src-hash-a "srca"
          src-hash-b "srcb"
          deps       ["dep-hash"]
          key-a-v1   (incremental/module-cache-key src-hash-a deps "0.11.0")
          key-b-v1   (incremental/module-cache-key src-hash-b deps "0.11.0")
          key-a-v2   (incremental/module-cache-key src-hash-a deps "0.12.0")
          key-b-v2   (incremental/module-cache-key src-hash-b deps "0.12.0")]
      ;; Prime both modules under compiler-version 0.11.0.
      (incremental/store cache-root key-a-v1 "mod-a" "// a v1\n" (fake-manifest "mod-a" src-hash-a))
      (incremental/store cache-root key-b-v1 "mod-b" "// b v1\n" (fake-manifest "mod-b" src-hash-b))
      ;; Attempt restore under 0.12.0: both miss.
      (is (not= key-a-v1 key-a-v2))
      (is (not= key-b-v1 key-b-v2))
      (is (false? (:cache-hit? (incremental/try-restore cache-root key-a-v2 out-dir "mod-a"))))
      (is (false? (:cache-hit? (incremental/try-restore cache-root key-b-v2 out-dir "mod-b"))))
      ;; And the old-version entries still hit under the old key (the
      ;; cache doesn't destroy old entries — L3.c leaves cleanup to a
      ;; separate reaper if that ever ships).
      (is (true? (:cache-hit? (incremental/try-restore cache-root key-a-v1 out-dir "mod-a"))))
      (is (true? (:cache-hit? (incremental/try-restore cache-root key-b-v1 out-dir "mod-b")))))))
