(ns cxc.project-test
  "hickey-cycle-287 (2026-11-21) — R3 #8. Lockfile reading now
   distinguishes absent, malformed, unsupported-version, and ok
   outcomes, and resolve-and-populate! reconciles per dep so an
   existing pin survives a manifest growing by one dep. Pre-cycle
   each malfunction collapsed into a silent re-resolve of every
   pin via `git ls-remote` — these tests pin the new behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io])
  (:import [java.io File]))

(require 'cxc.project)

(defn- with-temp-dir [f]
  (let [^File d (File/createTempFile "cxc-project-test" "")]
    (.delete d)
    (.mkdirs d)
    (try (f d)
         (finally
           (doseq [^File entry (reverse (file-seq d))]
             (.delete entry))))))

(defn- write-lock [^File dir ^String contents]
  (spit (io/file dir "project.lock") contents))

(deftest read-lockfile-detailed-distinguishes-outcomes
  (testing ":absent when no lockfile exists"
    (with-temp-dir
      (fn [d]
        (is (= :absent (:status (#'cxc.project/read-lockfile-detailed d)))))))

  (testing ":malformed when EDN does not parse"
    (with-temp-dir
      (fn [d]
        (write-lock d "{:this is #broken edn(")
        (let [r (#'cxc.project/read-lockfile-detailed d)]
          (is (= :malformed (:status r)))
          (is (string? (:reason r)) "malformed carries a reason")))))

  (testing ":malformed when top-level is not a map"
    (with-temp-dir
      (fn [d]
        (write-lock d "[:this :is :a :vector]")
        (is (= :malformed (:status (#'cxc.project/read-lockfile-detailed d)))))))

  (testing ":unsupported when lockfile-version differs"
    (with-temp-dir
      (fn [d]
        (write-lock d "{:cxc-lockfile-version 99 :deps []}")
        (let [r (#'cxc.project/read-lockfile-detailed d)]
          (is (= :unsupported (:status r)))
          (is (= 99 (:version r)))))))

  (testing ":ok with payload when version matches"
    (with-temp-dir
      (fn [d]
        (write-lock d (pr-str {:cxc-lockfile-version 1 :deps []}))
        (let [r (#'cxc.project/read-lockfile-detailed d)]
          (is (= :ok (:status r)))
          (is (= 1 (:cxc-lockfile-version (:data r)))))))))

(deftest read-lockfile-backward-compat
  (testing "legacy read-lockfile returns nil for every non-ok status"
    (with-temp-dir
      (fn [d]
        (is (nil? (#'cxc.project/read-lockfile d)) "absent → nil")
        (write-lock d "{:cxc-lockfile-version 99 :deps []}")
        (is (nil? (#'cxc.project/read-lockfile d)) "unsupported → nil")
        (write-lock d "not edn (")
        (is (nil? (#'cxc.project/read-lockfile d)) "malformed → nil")
        (write-lock d (pr-str {:cxc-lockfile-version 1 :deps [{:name "a"}]}))
        (is (= 1 (:cxc-lockfile-version (#'cxc.project/read-lockfile d)))
            "ok → the data map")))))

(deftest lockfile-index-keeps-full-pins-regardless-of-coverage
  (testing "the index is name+git+tag → entry over every dep with a non-blank SHA"
    (let [data {:cxc-lockfile-version 1
                :deps [{:name "a" :git "g1" :tag "v1" :sha "sha-a"}
                       {:name "b" :git "g2" :tag "v1" :sha "sha-b"}]}
          idx  (#'cxc.project/lockfile-index data)]
      (is (= 2 (count idx)))
      (is (= "sha-a" (:sha (get idx ["a" "g1" "v1"]))))))

  (testing "entries without a :sha are rejected at index time, not downstream"
    (let [data {:cxc-lockfile-version 1
                :deps [{:name "a" :git "g1" :tag "v1" :sha "sha-a"}
                       {:name "b" :git "g2" :tag "v1"}]}
          idx  (#'cxc.project/lockfile-index data)]
      (is (= 1 (count idx)) "only the pin with a real SHA enters")
      (is (nil? (get idx ["b" "g2" "v1"]))))))

(deftest resolve-and-populate-rejects-malformed-lockfile
  (testing "a malformed lockfile raises rather than silently losing every pin"
    ;; Pre-cycle this was indistinguishable from a first-build case:
    ;; `read-lockfile` returned nil for both, so the resolver quietly
    ;; re-resolved every dep via `git ls-remote`. Post-cycle the user
    ;; learns their lockfile doesn't parse.
    (with-temp-dir
      (fn [d]
        (write-lock d "not edn (")
        (let [ex (try
                   (cxc.project/resolve-and-populate! d {:deps []})
                   nil
                   (catch Exception e e))]
          (is (some? ex) "a malformed lockfile raises")
          (is (= :malformed (:lockfile-status (ex-data ex)))
              "ex-data carries the specific status")
          (is (re-find #"lockfile" (.getMessage ^Exception ex))
              "message says what the user needs to fix"))))))
