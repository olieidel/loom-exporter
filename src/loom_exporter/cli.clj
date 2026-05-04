(ns loom-exporter.cli
  (:gen-class)
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [loom-exporter.core :as core]
            [loom-exporter.json :as json]
            [loom-exporter.tui :as tui]))

(def common-options
  [["-o" "--out DIR" "Archive output directory" :default "exports/loom"]
   [nil "--archive DIR" "Archive directory for verify"]
   [nil "--manifest FILE" "Existing manifest to export from"]
   [nil "--url URL" "Loom URL to include; repeatable" :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--urls-file FILE" "File containing Loom URLs, one per line"]
   [nil "--loom-web" "Use Loom's authenticated web GraphQL API for inventory"]
   [nil "--loom-source SOURCE" "Loom web source enum" :default "ALL"]
   [nil "--page-size N" "Loom web page size" :default 50 :parse-fn parse-long]
   [nil "--page-limit N" "Maximum Loom web pages to request" :parse-fn parse-long]
   [nil "--first N" "Maximum Loom web results" :parse-fn parse-long]
   [nil "--cookie COOKIE" "Raw Loom browser Cookie header"]
   [nil "--cookie-file FILE" "Raw Cookie header or Netscape cookies.txt file"]
   [nil "--ffmpeg-bin BIN" "ffmpeg executable" :default "ffmpeg"]
   [nil "--video-password PASSWORD" "Password for protected Loom videos"]
   [nil "--skip-video" "Write metadata only; do not download video files"]
   [nil "--force" "Redownload even when a video file exists"]
   [nil "--format FORMAT" "Output format for list: table, json, edn" :default "table"]
   ["-h" "--help"]])

(def usage
  (str/join
   "\n"
   ["Loom exporter"
    ""
    "Commands:"
    "  list       List accessible Loom videos"
    "  select     Interactively select videos and download them"
    "  inventory  Discover accessible Loom videos and write manifest.json"
    "  export     Export videos from manifest or discovery"
    "  verify     Validate an archive"
    ""
    "Examples:"
    "  clojure -M:run list --loom-web --cookie-file cookies.txt --first 20"
    "  clojure -M:run select --loom-web --cookie-file cookies.txt --out exports/loom"
    "  clojure -M:run inventory --out exports/loom --url https://www.loom.com/share/..."
    "  clojure -M:run export --out exports/loom --cookie-file cookies.txt"
    "  clojure -M:run verify --archive exports/loom"]))

(defn- parse-command [args]
  (let [[command & rest] (if (#{"--help" "-h"} (first args))
                           [nil (first args)]
                           args)
        parsed (parse-opts rest common-options)]
    (assoc parsed :command command)))

(defn- print-result [result]
  (pprint/pprint result))

(defn- truncate [s n]
  (let [s (str (or s ""))]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (- n 1))) "…"))))

(defn- pad-right [s n]
  (let [s (truncate s n)]
    (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

(defn- video-row [video]
  [(or (:id video) "")
   (or (:visibility video) "")
   (name (or (:source video) ""))
   (or (:title video) "")
   (or (:url video) "")])

(defn- print-table [videos]
  (let [headers ["ID" "VISIBILITY" "SOURCE" "TITLE" "URL"]
        widths [34 12 10 42 48]
        rows (map video-row videos)
        print-row (fn [row]
                    (println (str/join "  " (map pad-right row widths))))]
    (print-row headers)
    (print-row (map #(apply str (repeat % "-")) widths))
    (doseq [row rows]
      (print-row row))))

(defn- print-list-result [options result]
  (case (:format options)
    "json" (println (json/write-str result))
    "edn" (print-result result)
    "table" (do
              (print-table (:videos result))
              (println)
              (println (str (:video-count result) " videos")))
    (throw (ex-info "Unsupported list format. Use table, json, or edn."
                    {:type :invalid-format
                     :format (:format options)}))))

(defn- select-and-download! [options]
  (let [{:keys [videos]} (core/list-videos options)]
    (if (empty? videos)
      (println "No videos found.")
      (if-let [selected-indices (tui/prompt-selection videos)]
        (let [selected (mapv #(nth videos %) (sort selected-indices))]
          (if (empty? selected)
            (println "No videos selected.")
            (if (tui/confirm? (count selected) (:out options))
              (print-result (core/export-selected! options selected))
              (println "Cancelled."))))
        (println "Cancelled.")))))

(defn- fail! [message data]
  (binding [*out* *err*]
    (println message)
    (when data
      (pprint/pprint data)))
  (System/exit 1))

(defn -main [& args]
  (let [{:keys [command options errors summary]} (parse-command args)]
    (cond
      (or (:help options) (nil? command))
      (do (println usage)
          (println)
          (println summary)
          (shutdown-agents))

      (seq errors)
      (fail! "Invalid options" errors)

      :else
      (try
        (case command
          "list" (print-list-result options (core/list-videos options))
          "select" (select-and-download! options)
          "inventory" (print-result (core/inventory! options))
          "export" (print-result (core/export! options))
          "verify" (print-result (core/verify! (update options :archive #(or % (:out options)))))
          (fail! (str "Unknown command: " command) nil))
        (shutdown-agents)
        (catch clojure.lang.ExceptionInfo e
          (shutdown-agents)
          (fail! (.getMessage e) (ex-data e)))
        (catch Exception e
          (shutdown-agents)
          (fail! (.getMessage e) nil))))))
