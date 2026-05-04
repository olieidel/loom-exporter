(ns loom-exporter.video-test
  (:require [clojure.test :refer [deftest is]]
            [loom-exporter.video :as video]))

(deftest dedupe-merges-by-id-or-url
  (let [videos (video/dedupe-videos [{:id "a" :title "Old"}
                                     {:id "a" :description "New"}])]
    (is (= 1 (count videos)))
    (is (= "Old" (:title (first videos))))
    (is (= "New" (:description (first videos))))))
