(ns loom-exporter.archive-test
  (:require [clojure.test :refer [deftest is]]
            [loom-exporter.archive :as archive]
            [loom-exporter.util :as util]))

(deftest safe-id-and-slug-are-filesystem-friendly
  (is (= "abc_123.mov" (util/safe-id "abc 123.mov")))
  (is (= "hello-loom-export" (util/slug "Hello, Loom Export!"))))

(deftest video-dir-is-stable
  (is (= "abc123__demo-video"
         (archive/video-dir-name {:id "abc123" :title "Demo Video"}))))

(deftest transcript-can-be-rendered-as-srt
  (is (= "1\n00:00:01,250 --> 00:00:03,000\nHello\n\n2\n00:00:03,000 --> 00:00:05,500\nWorld\n"
         (archive/transcript->srt [{:start 1.25 :end 3 :text "Hello"}
                                   {:start 3 :duration 2.5 :text "World"}]))))

