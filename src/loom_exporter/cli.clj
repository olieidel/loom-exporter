(ns loom-exporter.cli
  (:gen-class)
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [loom-exporter.core :as core]
            [loom-exporter.json :as json]
            [loom-exporter.tui :as tui]))

(def option-defs
  {:archive [nil "--archive DIR" "Existing archive directory"]
   :archive-format [nil "--archive-format FORMAT" "Archive data format: edn or json" :default "edn"
                    :validate [#{"edn" "json"} "Must be edn or json"]]
   :cookie-file [nil "--cookie-file FILE" "Raw Cookie header or Netscape cookies.txt file"]
   :ffmpeg-bin [nil "--ffmpeg-bin BIN" "ffmpeg executable" :default "ffmpeg"]
   :force [nil "--force" "Redownload even when a video file exists"]
   :help ["-h" "--help"]
   :jobs [nil "--jobs N" "Parallel export jobs" :default 1 :parse-fn parse-long]
   :limit [nil "--limit N" "Maximum Loom web results" :parse-fn parse-long]
   :list-format [nil "--list-format FORMAT" "Output format: table, json, edn" :default "table"
                 :validate [#{"table" "json" "edn"} "Must be table, json, or edn"]]
   :loom-source [nil "--loom-source SOURCE" "Loom web source enum" :default "ALL"]
   :manifest [nil "--manifest FILE" "Read videos from manifest file"]
   :no-progress [nil "--no-progress" "Disable terminal progress bars"]
   :out ["-o" "--out DIR" "Archive output directory" :default "exports/loom"]
   :skip-video [nil "--skip-video" "Write metadata only; do not download video files"]
   :url [nil "--url URL" "Loom URL to include; repeatable"
         :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   :urls-file [nil "--urls-file FILE" "File containing Loom URLs, one per line"]
   :video-password [nil "--video-password PASSWORD" "Password for protected Loom videos"]})

(def command-option-overrides
  {"list" {:archive [nil "--archive DIR" "List videos from archive directory"]}
   "export" {:archive [nil "--archive DIR" "Read videos from archive directory"]}
   "verify" {:archive [nil "--archive DIR" "Archive directory to validate"]
             :out ["-o" "--out DIR" "Archive directory to validate" :default "exports/loom"]}})

(def command-option-keys
  {"list" [:archive :manifest :url :urls-file :cookie-file :loom-source :limit
           :video-password :list-format :help]
   "inventory" [:out :url :urls-file :cookie-file :loom-source :limit
                :video-password :archive-format :help]
   "select" [:out :archive :manifest :url :urls-file :cookie-file :loom-source :limit
             :jobs :no-progress :video-password :force :archive-format :ffmpeg-bin :help]
   "export" [:out :archive :manifest :url :urls-file :cookie-file :loom-source :limit
             :jobs :no-progress :video-password :skip-video :force :archive-format
             :ffmpeg-bin :help]
   "verify" [:archive :out :archive-format :help]})

(def command-descriptions
  {"list" "List accessible Loom videos"
   "select" "Interactively select videos and download them"
   "inventory" "Discover accessible Loom videos and write a manifest"
   "export" "Export videos from manifest or discovery"
   "verify" "Validate an archive"})

(defn- option-list [command ks]
  (let [overrides (get command-option-overrides command)]
    (mapv #(or (get overrides %) (option-defs %)) ks)))

(def usage
  (str/join
   "\n"
   ["Loom exporter"
    ""
    "Commands:"
    "  list       List accessible Loom videos"
    "  select     Interactively select videos and download them"
    "  inventory  Discover accessible Loom videos and write manifest.edn"
    "  export     Export videos from manifest or discovery"
    "  verify     Validate an archive"
    ""
    "Examples:"
    "  clojure -M:run list --cookie-file cookies.txt --limit 20"
    "  clojure -M:run select --cookie-file cookies.txt --out exports/loom"
    "  clojure -M:run inventory --out exports/loom --url https://www.loom.com/share/..."
    "  clojure -M:run export --out exports/loom --cookie-file cookies.txt"
    "  clojure -M:run verify --archive exports/loom"
    ""
    "Run `clojure -M:run <command> --help` for command-specific options."]))

(defn- command-usage [command summary]
  (str/join
   "\n"
   [(str "Loom exporter " command)
    ""
    (command-descriptions command)
    ""
    "Options:"
    summary]))

(defn- parse-command [args]
  (let [[command & rest] (if (#{"--help" "-h"} (first args))
                           [nil (first args)]
                           args)
        options (if-let [ks (command-option-keys command)]
                  (option-list command ks)
                  (option-list nil [:help]))
        parsed (parse-opts rest options)]
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
  (case (:list-format options)
    "json" (println (json/write-str result))
    "edn" (print-result result)
    "table" (do
              (print-table (:videos result))
              (println)
              (println (str (:video-count result) " videos")))
    (throw (ex-info "Unsupported list format. Use table, json, or edn."
                    {:type :invalid-format
                     :list-format (:list-format options)}))))

(defn- select-and-download! [options]
  (let [{:keys [videos]} (core/list-videos options)]
    (if (empty? videos)
      (println "No videos found.")
      (if-let [selected-indices (tui/prompt-selection videos)]
        (let [selected (mapv #(nth videos %) (sort selected-indices))]
          (if (empty? selected)
            (println "No videos selected.")
            (print-result (core/export-selected! options selected))))
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
      (nil? command)
      (do (println usage)
          (shutdown-agents))

      (:help options)
      (do (if (command-descriptions command)
            (println (command-usage command summary))
            (println usage))
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
