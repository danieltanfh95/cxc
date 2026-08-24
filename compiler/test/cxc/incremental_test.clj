(ns cxc.incremental-test
  "F53 L3.a — module cache-key derivation. Standalone pure-fn tests;
   no filesystem, no compile pipeline. L3.b/c/d land the I/O + wiring
   layers on top."
  (:require [clojure.test :refer [deftest is testing]]
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
