(ns loom-exporter.archive-test
  (:import [java.nio.file Files])
  (:require [clojure.test :refer [deftest is]]
            [loom-exporter.archive :as archive]
            [loom-exporter.process :as process]
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

(deftest archive-data-defaults-to-edn
  (let [dir (.toFile (Files/createTempDirectory
                      "loom-exporter-archive-edn"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        manifest (archive/manifest [{:id "abc123" :title "Demo Video"}])]
    (try
      (archive/write-manifest! dir manifest {})
      (is (.exists (java.io.File. dir "manifest.edn")))
      (is (not (.exists (java.io.File. dir "manifest.json"))))
      (is (= manifest (archive/read-manifest dir {})))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (clojure.java.io/delete-file f true))))))

(deftest archive-data-can-be-written-as-json
  (let [dir (.toFile (Files/createTempDirectory
                      "loom-exporter-archive-json"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        opts {:archive-format "json"}
        manifest (archive/manifest [{:id "abc123" :title "Demo Video"}])]
    (try
      (archive/write-manifest! dir manifest opts)
      (is (.exists (java.io.File. dir "manifest.json")))
      (is (not (.exists (java.io.File. dir "manifest.edn"))))
      (is (= manifest (archive/read-manifest dir opts)))
      (is (= manifest (archive/read-manifest dir {})))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (clojure.java.io/delete-file f true))))))

(deftest invalid-video-file-is-not-complete-when-ffprobe-is-available
  (when (process/executable? "ffprobe")
    (let [dir (.toFile (Files/createTempDirectory
                        "loom-exporter-test"
                        (make-array java.nio.file.attribute.FileAttribute 0)))]
      (try
        (spit (java.io.File. dir "video.mp4") "not a video")
        (is (nil? (archive/complete-video-file? dir {:duration-seconds 10})))
        (finally
          (doseq [f (reverse (file-seq dir))]
            (clojure.java.io/delete-file f true)))))))
