(ns loom-exporter.data
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [loom-exporter.json :as json]))

(def formats #{:edn :json})

(defn format-from-opts [opts]
  (let [format (keyword (or (:archive-format opts) "edn"))]
    (when-not (formats format)
      (throw (ex-info "Unsupported archive format. Use edn or json."
                      {:type :invalid-archive-format
                       :archive-format (:archive-format opts)})))
    format))

(defn path [dir basename opts]
  (io/file dir (str basename "." (name (format-from-opts opts)))))

(defn extension [path]
  (some->> (.getName (io/file path))
           (re-find #"\.([^.]+)$")
           second
           str/lower-case
           keyword))

(defn read-file [path]
  (case (extension path)
    :edn (edn/read-string (slurp path))
    :json (json/read-json-file path)
    (throw (ex-info "Unsupported data file extension. Use .edn or .json."
                    {:type :unsupported-data-extension
                     :path (str path)}))))

(defn- write-edn-file! [path value]
  (io/make-parents path)
  (with-open [writer (io/writer path)]
    (binding [*out* writer]
      (pprint/pprint value))))

(defn write-file! [path value]
  (case (extension path)
    :edn (write-edn-file! path value)
    :json (json/write-json-file! path value)
    (throw (ex-info "Unsupported data file extension. Use .edn or .json."
                    {:type :unsupported-data-extension
                     :path (str path)}))))

(defn candidate-paths [dir basename opts]
  (distinct
   (concat [(path dir basename opts)]
           (map #(path dir basename {:archive-format (name %)})
                formats))))
