(ns cxc.core-test
  (:require [clojure.test :refer [deftest is]]
            [cxc.core :as core]))

(deftest sanity
  (is (var? #'core/-main)))
