(ns cxc.daemon-test
  "hickey-cycle-288 (2026-11-21) — R3 #11. Daemon freshness detection
   now compares a full source-inventory snapshot against a fresh scan,
   catching additions, deletions and any mtime change. The pre-cycle
   `mtime > daemon-start` threshold missed deletions and any backdated
   edit whose timestamp was older than daemon start."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io])
  (:import [java.io File]))

(require 'cxc.daemon)

(defn- with-fake-inventory
  "Install `inv` as `daemon-start-inventory` for the body, restore after."
  [inv f]
  (let [atom-var #'cxc.daemon/daemon-start-inventory
        old      @@atom-var]
    (try
      (reset! @atom-var inv)
      (f)
      (finally (reset! @atom-var old)))))

(defn- with-redef-scan
  "Replace `scan-source-inventory` with `scan-fn` for the body."
  [scan-fn f]
  (with-redefs [cxc.daemon/scan-source-inventory scan-fn]
    (f)))

(deftest stale-source-inventory-drift-catches-every-change-shape
  (let [start {"src/cxc/parse.clj" 100
               "src/cxc/lower.clj" 200}]

    (testing "no drift → nil"
      (with-fake-inventory start
        (fn []
          (with-redef-scan (constantly start)
            (fn []
              (is (nil? (#'cxc.daemon/stale-source-inventory-drift))))))))

    (testing "added file flagged as :added"
      ;; Pre-cycle an added file was only caught if its mtime happened to be
      ;; newer than daemon start; a touch -t with an old timestamp slipped in.
      (with-fake-inventory start
        (fn []
          (with-redef-scan (constantly (assoc start "src/cxc/new.clj" 50))
            (fn []
              (let [r (#'cxc.daemon/stale-source-inventory-drift)]
                (is (some? r))
                (is (re-find #"added: src/cxc/new.clj" r))))))))

    (testing "deleted file flagged as :removed"
      ;; Pre-cycle this was invisible: file-seq simply didn't yield the path,
      ;; so no mtime check fired and the daemon kept serving a universe that
      ;; had just lost a namespace.
      (with-fake-inventory start
        (fn []
          (with-redef-scan (constantly (dissoc start "src/cxc/lower.clj"))
            (fn []
              (let [r (#'cxc.daemon/stale-source-inventory-drift)]
                (is (some? r))
                (is (re-find #"removed: src/cxc/lower.clj" r))))))))

    (testing "backdated edit flagged as :changed"
      ;; Pre-cycle a touch -t to an older timestamp with new content read as
      ;; fresh because mtime < daemon-start. Post-cycle any mtime difference
      ;; from the recorded snapshot is drift.
      (with-fake-inventory start
        (fn []
          (with-redef-scan (constantly
                             (assoc start "src/cxc/lower.clj" 50))
            (fn []
              (let [r (#'cxc.daemon/stale-source-inventory-drift)]
                (is (some? r))
                (is (re-find #"changed: src/cxc/lower.clj" r))
                (is (re-find #"200.*→.*50" r)
                    "drift message records the specific mtime transition"))))))))

  (testing "nil snapshot → nil (daemon never recorded a start — don't claim drift)"
    (with-fake-inventory nil
      (fn []
        (is (nil? (#'cxc.daemon/stale-source-inventory-drift)))))))
