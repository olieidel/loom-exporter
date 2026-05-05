(ns loom-exporter.core-test
  (:import [java.nio.file Files])
  (:require [clojure.test :refer [deftest is]]
            [loom-exporter.archive :as archive]
            [loom-exporter.core :as core]))

(defn- ex-type [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:type (ex-data e)))))

(deftest discovery-requires-cookie-file-or-explicit-urls
  (is (= :discovery-source-missing (ex-type #(core/list-videos {}))))
  (is (= :discovery-source-missing (ex-type #(core/inventory! {:out "exports/test"}))))
  (let [dir (.toFile (Files/createTempDirectory
                      "loom-exporter-core-empty-export"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (is (= :discovery-source-missing (ex-type #(core/export! {:out (.getPath dir)}))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (clojure.java.io/delete-file f true))))))

(deftest list-can-read-manifest-without-cookie-file
  (let [dir (.toFile (Files/createTempDirectory
                      "loom-exporter-core"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        manifest (archive/manifest [{:id "abc123" :title "Demo Video"}])
        manifest-file (archive/manifest-path dir {})]
    (try
      (archive/write-manifest! dir manifest {})
      (is (= {:status :ok
              :video-count 1
              :videos [{:id "abc123" :title "Demo Video"}]}
             (core/list-videos {:manifest (.getPath manifest-file)})))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (clojure.java.io/delete-file f true))))))
