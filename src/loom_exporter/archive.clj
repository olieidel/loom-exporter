(ns loom-exporter.archive
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loom-exporter.json :as json]
            [loom-exporter.process :as process]
            [loom-exporter.util :as util]))

(def manifest-version 1)

(defn root [out]
  (io/file out))

(defn manifest-path [out]
  (io/file (root out) "manifest.json"))

(defn video-dir-name [video]
  (str (util/safe-id (or (:id video) (util/loom-id-from-url (:url video))))
       "__"
       (util/slug (:title video))))

(defn video-dir [out video]
  (io/file (root out) "videos" (video-dir-name video)))

(defn read-manifest [out]
  (let [f (manifest-path out)]
    (when (.exists f)
      (json/read-json-file f))))

(defn write-manifest! [out manifest]
  (json/write-json-file! (manifest-path out) manifest))

(defn manifest [videos]
  {:manifest-version manifest-version
   :generated-at (util/now-iso)
   :video-count (count videos)
   :videos (vec videos)})

(defn write-video-metadata! [out video status]
  (let [dir (video-dir out video)]
    (.mkdirs dir)
    (json/write-json-file! (io/file dir "metadata.json")
                           (assoc video :export status))
    dir))

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

(defn write-transcript! [dir transcript]
  (when transcript
    (json/write-json-file! (io/file dir "transcript.json") transcript)
    (when-let [srt (transcript->srt transcript)]
      (spit (io/file dir "captions.srt") srt))))

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
