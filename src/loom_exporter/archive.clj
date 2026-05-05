(ns loom-exporter.archive
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [loom-exporter.json :as json]
            [loom-exporter.process :as process]
            [loom-exporter.util :as util]))

(def manifest-version 1)
(def archive-formats #{:edn :json})

(defn root [out]
  (io/file out))

(defn archive-format [opts]
  (let [format (keyword (or (:archive-format opts) "edn"))]
    (when-not (archive-formats format)
      (throw (ex-info "Unsupported archive format. Use edn or json."
                      {:type :invalid-archive-format
                       :archive-format (:archive-format opts)})))
    format))

(defn data-path [dir basename opts]
  (io/file dir (str basename "." (name (archive-format opts)))))

(defn manifest-path
  ([out] (manifest-path out nil))
  ([out opts]
   (data-path (root out) "manifest" opts)))

(defn- extension [path]
  (some->> (.getName (io/file path))
           (re-find #"\.([^.]+)$")
           second
           str/lower-case
           keyword))

(defn read-data-file [path]
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

(defn write-data-file! [path value]
  (case (extension path)
    :edn (write-edn-file! path value)
    :json (json/write-json-file! path value)
    (throw (ex-info "Unsupported data file extension. Use .edn or .json."
                    {:type :unsupported-data-extension
                     :path (str path)}))))

(defn video-dir-name [video]
  (str (util/safe-id (or (:id video) (util/loom-id-from-url (:url video))))
       "__"
       (util/slug (:title video))))

(defn video-dir [out video]
  (io/file (root out) "videos" (video-dir-name video)))

(defn- candidate-paths [dir basename opts]
  (distinct
   (concat [(data-path dir basename opts)]
           (map #(data-path dir basename {:archive-format (name %)})
                archive-formats))))

(defn read-manifest
  ([out] (read-manifest out nil))
  ([out opts]
   (some (fn [f]
           (when (.exists f)
             (read-data-file f)))
         (candidate-paths (root out) "manifest" opts))))

(defn write-manifest!
  ([out manifest] (write-manifest! out manifest nil))
  ([out manifest opts]
   (write-data-file! (manifest-path out opts) manifest)))

(defn manifest [videos]
  {:manifest-version manifest-version
   :generated-at (util/now-iso)
   :video-count (count videos)
   :videos (vec videos)})

(defn metadata-path
  ([out video] (metadata-path out video nil))
  ([out video opts]
   (data-path (video-dir out video) "metadata" opts)))

(defn existing-metadata-file [out video opts]
  (some (fn [f]
          (when (.exists f) f))
        (candidate-paths (video-dir out video) "metadata" opts)))

(defn write-video-metadata!
  ([out video status] (write-video-metadata! out video status nil))
  ([out video status opts]
   (let [dir (video-dir out video)]
     (.mkdirs dir)
     (write-data-file! (metadata-path out video opts)
                       (assoc video :export status))
     dir)))

(defn- seconds->srt-time [seconds]
  (let [millis (long (* 1000.0 (double (or seconds 0))))
        h (quot millis 3600000)
        m (quot (mod millis 3600000) 60000)
        s (quot (mod millis 60000) 1000)
        ms (mod millis 1000)]
    (format "%02d:%02d:%02d,%03d" h m s ms)))

(defn transcript-segments [transcript]
  (cond
    (sequential? transcript) transcript
    (sequential? (:segments transcript)) (:segments transcript)
    (sequential? (:items transcript)) (:items transcript)
    (sequential? (:sentences transcript)) (:sentences transcript)
    :else nil))

(defn transcript->srt [transcript]
  (when-let [segments (seq (transcript-segments transcript))]
    (->> segments
         (map-indexed
          (fn [i seg]
            (let [start (or (:start seg) (:startTime seg) (:start_time seg) 0)
                  end (or (:end seg) (:endTime seg) (:end_time seg)
                          (when (:duration seg) (+ (double start) (double (:duration seg))))
                          (+ (double start) 2.0))
                  text (or (:text seg) (:value seg) (:word seg) "")]
              (str (inc i) "\n"
                   (seconds->srt-time start) " --> " (seconds->srt-time end) "\n"
                   (str/trim (str text)) "\n"))))
         (str/join "\n"))))

(defn write-transcript!
  ([dir transcript] (write-transcript! dir transcript nil))
  ([dir transcript opts]
   (when transcript
     (write-data-file! (data-path dir "transcript" opts) transcript)
     (when-let [srt (transcript->srt transcript)]
       (spit (io/file dir "captions.srt") srt)))))

(defn write-readme! [dir video status]
  (spit (io/file dir "README.md")
        (str "# " (:title video "Untitled Loom") "\n\n"
             "- Loom URL: " (or (:url video) "unknown") "\n"
             "- ID: " (or (:id video) "unknown") "\n"
             "- Export status: " (:status status) "\n"
             (when-let [reason (:reason status)]
               (str "- Reason: `" (name reason) "`\n"))
             (when-let [description (:description video)]
               (str "\n## Description\n\n" description "\n")))))

(defn video-file [dir]
  (some #(when (and (.isFile %)
                    (re-matches #"video\.(mp4|webm|mkv|mov)" (.getName %))
                    (pos? (.length %)))
           %)
        (file-seq (io/file dir))))

(defn- parse-duration-double [s]
  (try
    (Double/parseDouble (str/trim (str s)))
    (catch Exception _
      nil)))

(defn media-duration-seconds [file]
  (when (and file (process/executable? "ffprobe"))
    (let [{:keys [exit out]} (process/run!
                              ["ffprobe"
                               "-v" "error"
                               "-show_entries" "format=duration"
                               "-of" "default=noprint_wrappers=1:nokey=1"
                               (.getPath file)])]
      (when (zero? exit)
        (parse-duration-double out)))))

(defn- expected-duration [video]
  (or (:duration-seconds video)
      (:playable_duration video)
      (:source_duration video)
      (get-in video [:raw :playable_duration])
      (get-in video [:raw :source_duration])
      (get-in video [:raw :video_properties :duration])))

(defn- duration-complete? [file video]
  (let [ffprobe? (process/executable? "ffprobe")
        expected (expected-duration video)
        actual (media-duration-seconds file)]
    (cond
      (not ffprobe?) true
      (nil? actual) false
      (nil? expected) (pos? actual)
      :else (>= actual (max 1.0 (* 0.95 (double expected)))))))

(defn complete-video-file?
  ([dir] (complete-video-file? dir nil))
  ([dir video]
   (when-let [file (video-file dir)]
     (when (duration-complete? file video)
       file))))
