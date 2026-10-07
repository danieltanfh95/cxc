(ns cxc.trace-channel-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cxc.trace-annotations :as ann]
            [cxc.trace-channel :as channel])
  (:import [java.nio.file Files]))

(defn- with-poster [initial test-fn]
  (let [dir (.toFile (Files/createTempDirectory "cxc-channel-test-"
                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        sidecar (io/file dir "sample.annotations.json")
        sidecar-path (.getPath sidecar)
        messages (atom [])
        token-count (atom 0)]
    (try
      (ann/write-sidecar sidecar-path {:annotations initial})
      (with-redefs-fn {#'channel/snapshots (atom {})
                      #'channel/gen-token #(str "token" (swap! token-count inc))
                      #'channel/fanout-push! (fn [scope message]
                                               (swap! messages conj [scope message])
                                               1)}
        (fn []
          (#'channel/seed-snapshots! (.getPath dir))
          (test-fn {:path sidecar-path
                    :scope (.getPath dir)
                    :messages messages
                    :push! #(#'channel/maybe-push! (.getPath dir) sidecar-path)})))
      (finally
        (io/delete-file sidecar)
        (io/delete-file dir)))))

(deftest submission-token-and-fanout-regression
  (with-poster [{:id "ann_send" :status "draft" :note "send now"}
                {:id "ann_bulk" :status "draft" :note "bulk submit"}]
    (fn [{:keys [path scope messages push!]}]
      (testing "draft writes do not push"
        (push!)
        (is (empty? @messages)))
      (doseq [[id note] [["ann_send" "send now"] ["ann_bulk" "bulk submit"]]]
        (testing note
          (let [before (ann/read-sidecar path)
                updated (update before :annotations
                                #(mapv (fn [a] (if (= id (:id a))
                                                 (assoc a :status "open") a)) %))]
            (ann/write-sidecar path updated)
            (push!)
            (let [[pushed-scope message] (last @messages)
                  a (first (filter #(= id (:id %)) (:annotations (ann/read-sidecar path))))
                  pending (last (:comments a))]
              (is (= scope pushed-scope))
              (is (.contains message "1 annotation(s) submitted on sample"))
              (is (.contains message note))
              (is (= "pending" (:kind pending)))
              (is (.contains message (str "reply-token: " (:token pending)))))
            (push!))))
      (is (= 2 (count @messages)) "send, bulk and token writes do not duplicate pushes")
      (is (= [1 1] (mapv #(count (:comments %))
                         (:annotations (ann/read-sidecar path))))))))

(deftest verified-flip-detection
  (let [resolution {:by "agent" :at "2026-10-04T12:00:00Z"}
        current {:annotations [{:id "ann_01" :status "verified" :resolution resolution}]}]
    (doseq [status ["draft" "open" "in_progress" "verified" "wontfix" "stale"]]
      (is (empty? (#'channel/find-newly-verified
                   {:annotations [{:id "ann_01" :status status :resolution resolution}]}
                   current))
          (str status "→verified is not a human verification of an addressed finding")))
    (is (empty? (#'channel/find-newly-verified {:annotations []} current))
        "a newly loaded verified annotation does not push")
    (doseq [legacy-resolution [nil {} {:by "agent"} {:at "2026-10-04T12:00:00Z"}]]
      (is (empty? (#'channel/find-newly-verified
                   {:annotations [{:id "ann_01" :status "addressed" :resolution legacy-resolution}]}
                   current))
          "legacy resolutions without attribution do not push"))
    (is (= ["ann_01"]
           (mapv :id (#'channel/find-newly-verified
                      {:annotations [{:id "ann_01" :status "addressed" :resolution resolution}]}
                      current))))))

(deftest verification-token-and-original-resolution
  (let [resolution {:by "smoke-agent" :at "2026-10-04T12:00:00Z"
                    :kind "fix_landed" :summary "Fixed the finding"}
        existing-comment {:by "human" :text "Please check this"}]
    (with-poster [{:id "ann_02" :status "addressed" :resolution resolution
                   :comments [existing-comment]}]
      (fn [{:keys [path scope messages push!]}]
        (push!)
        (is (empty? @messages) "the addressed startup snapshot is the baseline")
        (let [verified (update (ann/read-sidecar path) :annotations
                               #(mapv (fn [a]
                                        (assoc a :status "verified"
                                               :resolution {:by "human" :at "2026-10-07T12:00:00Z"
                                                            :verified_by "human" :verified_at "2026-10-07T12:00:00Z"})) %))]
          (ann/write-sidecar path verified)
          (push!)
          (let [a (first (:annotations (ann/read-sidecar path)))
                pending (last (:comments a))]
            (is (= [[scope (str "[cxc-trace] sample: human verified ann_02 "
                                "(originally resolved by smoke-agent at 2026-10-04T12:00:00Z) "
                                "— your fix held. reply-token: " (:token pending))]]
                   @messages))
            (is (= existing-comment (first (:comments a))))
            (is (= "pending" (:kind pending)))
            (is (= "cxc-trace" (:by pending)))
            (is (string? (:at pending)))
            (is (= (:resolution (first (:annotations verified))) (:resolution a))))
          (push!)
          (is (= 1 (count @messages)) "token write-back does not repeat the verification")
          (is (= 2 (count (:comments (first (:annotations (ann/read-sidecar path))))))))))))

(deftest mixed-submit-and-verification-batch
  (let [resolution {:by "agent" :at "2026-10-04T12:00:00Z"}]
    (with-poster [{:id "ann_submit" :status "draft" :note "bulk submit"}
                  {:id "ann_verify_1" :status "addressed" :resolution resolution}
                  {:id "ann_verify_2" :status "addressed" :resolution resolution}
                  {:id "ann_legacy" :status "addressed"}
                  {:id "ann_loaded" :status "verified" :resolution resolution}]
      (fn [{:keys [path messages push!]}]
        (push!)
        (is (empty? @messages) "already verified at load time does not push")
        (ann/write-sidecar path
                           (update (ann/read-sidecar path) :annotations
                                   #(mapv (fn [a] (assoc a :status (if (= "ann_submit" (:id a))
                                                                    "open" "verified"))) %)))
        (push!)
        (is (= 2 (count @messages)) "submits and verifications have separate messages")
        (is (.contains (second (first @messages)) "1 annotation(s) submitted"))
        (doseq [id ["ann_verify_1" "ann_verify_2"]]
          (is (.contains (second (second @messages)) (str "human verified " id))))
        (let [annotations (:annotations (ann/read-sidecar path))
              tokens (mapv #(get-in % [:comments 0 :token]) (take 3 annotations))]
          (is (= 3 (count (set tokens))) "every submitted or verified entry has a distinct token")
          (is (every? string? tokens))
          (is (every? #(empty? (:comments %)) (drop 3 annotations))
              "legacy and startup-verified annotations are not stamped"))
        (push!)
        (is (= 2 (count @messages)) "combined token write-back does not duplicate either batch")))))
