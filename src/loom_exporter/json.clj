(ns loom-exporter.json
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn read-json-file [path]
  (with-open [reader (io/reader path)]
    (json/read reader :key-fn keyword)))

(defn read-json-string [s]
  (json/read-str s :key-fn keyword))

(defn write-str [value]
  (json/write-str value :escape-slash false :escape-unicode false))

(defn write-json-file! [path value]
  (io/make-parents path)
  (with-open [writer (io/writer path)]
    (json/write value writer :escape-slash false :escape-unicode false)))
